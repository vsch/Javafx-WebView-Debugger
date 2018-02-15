/*
  The MIT License (MIT)
  <p>
  Copyright (c) 2018 Vladimir Schneider (https://github.com/vsch)
  <p>
  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:
  <p>
  The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software.
  <p>
  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE
 */

package com.vladsch.javafx.webview.debugger;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.HashMap;

public class JavaFxServer extends WebSocketServer {
    final private HashMap<String, WebSocket> myConnections = new HashMap<>();
    final private HashMap<String, DevToolsDebuggerServer> myDevToolServers = new HashMap<>();

    public JavaFxServer(InetSocketAddress address) {
        super(address);
    }

    public boolean send(String resourceId, String data) throws NotYetConnectedException {
        WebSocket conn = myConnections.get(resourceId);
        if (conn == null) {
            if (!myConnections.containsKey(resourceId)) {
                throw new IllegalStateException("Resource " + resourceId + " not registered");
            }
            return false;
        }

        System.out.println("sending to " + conn.getRemoteSocketAddress() + ": " + data);
        conn.send(data);
        return true;
    }

    public void addServer(String resourceId, DevToolsDebuggerServer debugServer) {
        if (myConnections.containsKey(resourceId)) {
            throw new IllegalStateException("Resource id " + resourceId + " is already handled by " + myDevToolServers.get(resourceId));
        }
        myConnections.put(resourceId, null);
        myDevToolServers.put(resourceId, debugServer);
    }

    public void removeServer(final String resourceId) {
        WebSocket conn = myConnections.get(resourceId);
        if (conn != null) {
            conn.close();
        }
        myConnections.remove(resourceId);
        myDevToolServers.remove(resourceId);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (!myConnections.containsKey(conn.getResourceDescriptor())) {
            System.out.println("new connection to " + conn.getRemoteSocketAddress() + " rejected");
            conn.close();
        } else {
            myConnections.put(conn.getResourceDescriptor(), conn);
        }
        System.out.println("new connection to " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        myConnections.put(conn.getResourceDescriptor(), null);
        System.out.println("closed " + conn.getRemoteSocketAddress() + " with exit code " + code + " additional info: " + reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (myDevToolServers.containsKey(conn.getResourceDescriptor())) {
            myDevToolServers.get(conn.getResourceDescriptor()).sendMessageToBrowser(message);
        }
        System.out.println("received from " + conn.getRemoteSocketAddress() + ": " + message);
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        System.out.println("received ByteBuffer from " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            System.err.println("an error occurred on connection null :" + ex);
        } else {
            System.err.println("an error occurred on connection " + conn.getRemoteSocketAddress() + ":" + ex);
        }
    }

    @Override
    public void onStart() {
        System.out.println("server started successfully");
    }
}
