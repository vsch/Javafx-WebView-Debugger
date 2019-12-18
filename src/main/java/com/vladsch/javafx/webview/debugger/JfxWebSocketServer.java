/*
 * Copyright (c) 2015-2019 Vladimir Schneider <vladimir.schneider@gmail.com>, all rights reserved.
 *
 * This code is private property of the copyright holder and cannot be used without
 * having obtained a license or prior written permission of the of the copyright holder.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package com.vladsch.javafx.webview.debugger;

import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class JfxWebSocketServer extends WebSocketServer {
    public static final String WEB_SOCKET_RESOURCE = "/?%s";
    private static final String CHROME_DEBUG_URL = "chrome-devtools://devtools/bundled/inspector.html?ws=localhost:";

    final private HashMap<String, WebSocket> myConnections = new HashMap<>();
    final private HashMap<String, JfxDebuggerConnector> myServers = new HashMap<>();
    final private HashMap<JfxDebuggerConnector, String> myServerIds = new HashMap<>();
    private Consumer<Throwable> onFailure;
    private Consumer<JfxWebSocketServer> onStart;
    private final AtomicInteger myServerUseCount = new AtomicInteger(0);
    final LogHandler LOG = LogHandler.getInstance();

    public JfxWebSocketServer(InetSocketAddress address, @Nullable Consumer<Throwable> onFailure, @Nullable Consumer<JfxWebSocketServer> onStart) {
        super(address, 4);
        this.onFailure = onFailure;
        this.onStart = onStart;
    }

    public boolean isDebuggerConnected(JfxDebuggerConnector server) {
        String resourceId = myServerIds.get(server);
        WebSocket conn = myConnections.get(resourceId);
        return conn != null;
    }

    public boolean send(JfxDebuggerConnector server, String data) throws NotYetConnectedException {
        String resourceId = myServerIds.get(server);
        WebSocket conn = myConnections.get(resourceId);
        if (conn == null) {
            return false;
        }

        if (LOG.isDebugEnabled()) System.out.println("sending to " + conn.getRemoteSocketAddress() + ": " + data);
        try {
            conn.send(data);
        } catch (WebsocketNotConnectedException e) {
            myConnections.put(resourceId, null);
            return false;
        }
        return true;
    }

    @NotNull
    public String getDebugUrl(JfxDebuggerConnector server) {
        // Chrome won't launch first session if we have a query string
        return CHROME_DEBUG_URL + super.getPort() + myServerIds.get(server);
    }

    public void addServer(JfxDebuggerConnector debugServer, int instanceID) {
        String resourceId = instanceID == 0 ? "/" : String.format(WEB_SOCKET_RESOURCE, instanceID);
        if (myServers.containsKey(resourceId)) {
            throw new IllegalStateException("Resource id " + resourceId + " is already handled by " + myServers.get(resourceId));
        }
        myConnections.put(resourceId, null);
        myServers.put(resourceId, debugServer);
        myServerIds.put(debugServer, resourceId);
        myServerUseCount.incrementAndGet();
    }

    public boolean removeServer(JfxDebuggerConnector server) {
        String resourceId = myServerIds.get(server);
        if (resourceId != null) {
            WebSocket conn = myConnections.get(resourceId);
            if (conn != null) {
                conn.close();
            }

            myServerIds.remove(server);
            myConnections.remove(resourceId);
            myServers.remove(resourceId);
            int serverUseCount = myServerUseCount.decrementAndGet();
            if (serverUseCount < 0) {
                LOG.error("Internal error: server use count <0");
                myServerUseCount.set(0);
            }
        }
        return myServerUseCount.get() <= 0;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String resourceId = conn.getResourceDescriptor();

        if (!myConnections.containsKey(resourceId)) {
            System.out.println("new connection to " + conn.getRemoteSocketAddress() + " rejected");
            if (LOG.isDebugEnabled()) System.out.println("new connection to " + conn.getRemoteSocketAddress() + " rejected");
            conn.close(CloseFrame.REFUSE, "No JavaFX WebView Debugger Instance");
        } else {
            WebSocket otherConn = myConnections.get(resourceId);
            if (otherConn != null) {
                // We will disconnect the other
                System.out.println("closing old connection to " + conn.getRemoteSocketAddress());
                if (LOG.isDebugEnabled()) System.out.println("closing old connection to " + conn.getRemoteSocketAddress());
                otherConn.close(CloseFrame.GOING_AWAY, "New Dev Tools connected");
            }

            myConnections.put(resourceId, conn);
            if (myServers.containsKey(resourceId)) {
                myServers.get(resourceId).onOpen();
            }
            System.out.println("new connection to " + conn.getRemoteSocketAddress());
            if (LOG.isDebugEnabled()) System.out.println("new connection to " + conn.getRemoteSocketAddress());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String resourceId = conn.getResourceDescriptor();

        WebSocket otherConn = myConnections.get(resourceId);
        if (otherConn == conn) {
            myConnections.put(resourceId, null);
            if (myServers.containsKey(resourceId)) {
                myServers.get(resourceId).onClosed(code, reason, remote);
            }
            System.out.println("closed " + conn.getRemoteSocketAddress() + " with exit code " + code + " additional info: " + reason);
            if (LOG.isDebugEnabled()) System.out.println("closed " + conn.getRemoteSocketAddress() + " with exit code " + code + " additional info: " + reason);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String resourceId = conn.getResourceDescriptor();

        if (myServers.containsKey(resourceId)) {
            myServers.get(resourceId).sendMessageToBrowser(message);
            if (LOG.isDebugEnabled()) System.out.println("received from " + conn.getRemoteSocketAddress() + ": " + message);
        } else {
            System.out.println("connection to " + conn.getRemoteSocketAddress() + " closed");
            if (LOG.isDebugEnabled()) System.out.println("connection to " + conn.getRemoteSocketAddress() + " closed");
            conn.close();
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        if (LOG.isDebugEnabled()) System.out.println("received ByteBuffer from " + conn.getRemoteSocketAddress());
    }

    @Override
    public void stop(final int timeout) throws InterruptedException {
        try {
            super.stop(timeout);
        } catch (Exception ex) {
            // nothing to do, handleFatal will clean up
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            if (LOG.isDebugEnabled()) {
                System.err.println("an error occurred on connection null :" + ex);
                LOG.error("an error occurred on connection null :", ex);
            }
        } else {
            if (LOG.isDebugEnabled()) {
                System.err.println("an error occurred on connection " + conn.getRemoteSocketAddress() + ":" + ex);
                LOG.error("an error occurred on connection " + conn.getRemoteSocketAddress() + ":", ex);
            }
        }

        onStart = null;
        if (onFailure != null) {
            Consumer<Throwable> failure = onFailure;
            onFailure = null;
            failure.accept(ex);
        }
    }

    @Override
    public void onStart() {
        onFailure = null;
        if (onStart != null) {
            Consumer<JfxWebSocketServer> start = onStart;
            onStart = null;
            start.accept(this);
        }
        if (LOG.isDebugEnabled()) {
            System.out.println("server started successfully");
            LOG.debug("server started successfully");
        }
    }
}
