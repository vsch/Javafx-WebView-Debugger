/*
  The MIT License (MIT)
  <p>
  Copyright (c) 2018 Vladimir Schneider (https://github.com/vsch)

  Rewritten to use TooTallNate/Java-WebSocket for easier integration
  into a JetBrains Plugin API environment
  <p>
  Copyright (c) 2016 boskokg (https://github.com/boskokg)
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

import com.sun.javafx.scene.web.Debugger;
import javafx.application.Platform;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.NotYetConnectedException;

public class DevToolsDebuggerServer {             //chrome-devtools://devtools/bundled/inspector.html?ws=localhost:51742/?1
    private static final String CHROME_DEBUG_URL = "chrome-devtools://devtools/bundled/inspector.html?ws=localhost:";
    static JavaFxServer server;

    public static final String WEB_SOCKET_RESOURCE = "/?%s";

    final Debugger debugger;
    final String webSocketResource;
    final int debuggerPort;

    public DevToolsDebuggerServer(Debugger debugger, int debuggerPort, int instance) throws Exception {
        webSocketResource = String.format(WEB_SOCKET_RESOURCE, instance);
        this.debuggerPort = debuggerPort;

        if (server == null) {
            server = new JavaFxServer(new InetSocketAddress("localhost", debuggerPort));
            server.start();
        }

        debugger.setEnabled(true);
        debugger.sendMessage("{\"id\" : -1, \"method\" : \"Network.enable\"}");

        this.debugger = debugger;
        debugger.setMessageCallback(data -> {
            try {
                server.send(webSocketResource, data);
            } catch (NotYetConnectedException e) {
                e.printStackTrace();
            }
            return null;
        });

        server.addServer(webSocketResource, this);
    }

    public String getDebugUrl() {
        return CHROME_DEBUG_URL + debuggerPort + webSocketResource;
    }

    public void stopDebugServer() throws Exception {
        if (server != null) {
            server.removeServer(webSocketResource);
            Platform.runLater(() -> {
                debugger.setEnabled(false);
                debugger.setMessageCallback(null);
            });
        }
    }

    public static void shutdownWebSocketServer() {
        if (server != null) {
            try {
                server.stop();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            server = null;
        }
    }

    public void sendMessageToBrowser(final String data) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                debugger.sendMessage(data);
            }
        });
    }
}
