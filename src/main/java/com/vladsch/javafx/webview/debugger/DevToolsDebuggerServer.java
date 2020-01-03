/*
 *   The MIT License (MIT)
 *   <p>
 *   Copyright (c) 2018-2020 Vladimir Schneider (https://github.com/vsch)
 *   <p>
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *   <p>
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *   <p>
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE
 *
 */

package com.vladsch.javafx.webview.debugger;

import com.sun.javafx.scene.web.Debugger;
import javafx.application.Platform;
import netscape.javascript.JSObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.nio.channels.NotYetConnectedException;
import java.util.HashMap;
import java.util.function.Consumer;

public class DevToolsDebuggerServer implements JfxDebuggerConnector {
    final static HashMap<Integer, JfxWebSocketServer> ourServerMap = new HashMap<>();

    final Debugger myDebugger;
    JfxWebSocketServer myServer;
    final LogHandler LOG = LogHandler.getInstance();

    public DevToolsDebuggerServer(@NotNull Debugger debugger, int debuggerPort, final int instanceId, @Nullable Consumer<Throwable> onFailure, @Nullable Runnable onStart) {
        myDebugger = debugger;
        boolean freshStart = false;

        JfxWebSocketServer jfxWebSocketServer;

        synchronized (ourServerMap) {
            jfxWebSocketServer = ourServerMap.get(debuggerPort);

            if (jfxWebSocketServer == null) {
                jfxWebSocketServer = new JfxWebSocketServer(new InetSocketAddress("localhost", debuggerPort), (ex) -> {
                    synchronized (ourServerMap) {
                        if (ourServerMap.get(debuggerPort).removeServer(this)) {
                            // unused
                            ourServerMap.remove(debuggerPort);
                        }
                    }

                    if (onFailure != null) {
                        onFailure.accept(ex);
                    }
                }, (server) -> {
                    myServer = server;
                    initDebugger(instanceId, onStart);
                });

                ourServerMap.put(debuggerPort, jfxWebSocketServer);
                freshStart = true;
            }
        }

        jfxWebSocketServer.addServer(this, instanceId);

        if (freshStart) {
            jfxWebSocketServer.start();
        } else {
            myServer = jfxWebSocketServer;
            initDebugger(instanceId, onStart);
        }
    }

    private void initDebugger(int instanceId, @Nullable Runnable onStart) {
        Platform.runLater(() -> {
            myDebugger.setEnabled(true);
            myDebugger.sendMessage("{\"id\" : -1, \"method\" : \"Network.enable\"}");

            this.myDebugger.setMessageCallback(data -> {
                try {
                    myServer.send(this, data);
                } catch (NotYetConnectedException e) {
                    e.printStackTrace();
                }
                return null;
            });

            if (LOG.isDebugEnabled()) {
                String remoteUrl = getDebugUrl();
                System.out.println("Debug session created. Debug URL: " + remoteUrl);
                LOG.debug("Debug session created. Debug URL: " + remoteUrl);
            }

            if (onStart != null) {
                onStart.run();
            }
        });
    }

    public boolean isDebuggerConnected() {
        return myServer.isDebuggerConnected(this);
    }

    @NotNull
    public String getDebugUrl() {
        // Chrome won't launch first session if we have a query string
        return myServer != null ? myServer.getDebugUrl(this) : "";
    }

    public void stopDebugServer(@NotNull Consumer<Boolean> onStopped) {
        if (myServer != null) {
            Platform.runLater(() -> {
                Runnable action = () -> {
                    if (LOG.isDebugEnabled()) {
                        String remoteUrl = getDebugUrl();
                        System.out.println("Debug session stopped for URL: " + remoteUrl);
                        LOG.debug("Debug session stopped for URL: " + remoteUrl);
                    }

                    boolean handled = false;
                    boolean unusedServer;

                    synchronized (ourServerMap) {
                        unusedServer = myServer.removeServer(this);
                        if (unusedServer) {
                            ourServerMap.remove(myServer.getAddress().getPort());
                        }
                    }

                    if (unusedServer) {
                        // shutdown
                        try {
                            myServer.stop(1000);
                            myServer = null;
                            if (LOG.isDebugEnabled()) {
                                System.out.println("WebView debug server shutdown.");
                                LOG.debug("WebView debug server shutdown.");
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } finally {
                            handled = true;
                            // instance removed and server stopped
                            onStopped.accept(true);
                        }
                    }

                    if (!handled) {
                        // instance removed, server running
                        onStopped.accept(false);
                    }
                };

                if (myDebugger instanceof JfxDebuggerProxy) {
                    // release from break point or it has a tendency to core dump
                    ((JfxDebuggerProxy) myDebugger).removeAllBreakpoints(() -> {
                        ((JfxDebuggerProxy) myDebugger).releaseDebugger(true, () -> {
                            // doing this if was stopped at a break point will core dump the whole app
                            myDebugger.setEnabled(false);
                            myDebugger.setMessageCallback(null);
                            action.run();
                        });
                    });
                } else {
                    myDebugger.setEnabled(false);
                    myDebugger.setMessageCallback(null);
                    action.run();
                }
            });
        } else {
            // have not idea since this instance was already disconnected
            onStopped.accept(null);
        }
    }

    @Override
    public void onOpen() {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).onOpen();
        }
    }

    @Override
    public void pageReloading() {
        if (myDebugger instanceof JfxDebuggerProxy && isDebuggerConnected()) {
            ((JfxDebuggerProxy) myDebugger).pageReloading();
        }
    }

    @Override
    public void reloadPage() {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).reloadPage();
        }
    }

    @Override
    public void onClosed(final int code, final String reason, final boolean remote) {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).onClosed(code, reason, remote);
        }
    }

    @Override
    public void log(final String type, final long timestamp, final JSObject args) {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).log(type, timestamp, args);
        }
    }

    @Override
    public void setDebugOnLoad(final DebugOnLoad debugOnLoad) {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).setDebugOnLoad(debugOnLoad);
        }
    }

    @Override
    public DebugOnLoad getDebugOnLoad() {
        if (myDebugger instanceof JfxDebuggerProxy) {
            return ((JfxDebuggerProxy) myDebugger).getDebugOnLoad();
        }
        return DebugOnLoad.NONE;
    }

    @Override
    public void debugBreak() {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).debugBreak();
        }
    }

    @Override
    public boolean isDebuggerPaused() {
        if (myDebugger instanceof JfxDebuggerProxy) {
            return ((JfxDebuggerProxy) myDebugger).isDebuggerPaused();
        }
        return false;
    }

    @Override
    public void releaseDebugger(final boolean shuttingDown, @Nullable Runnable runnable) {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).releaseDebugger(shuttingDown, runnable);
        }
    }

    @Override
    public void removeAllBreakpoints(@Nullable Runnable runnable) {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).removeAllBreakpoints(runnable);
        }
    }

    @Override
    public void pageLoadComplete() {
        if (myDebugger instanceof JfxDebuggerProxy) {
            ((JfxDebuggerProxy) myDebugger).pageLoadComplete();
        }
    }

    public void sendMessageToBrowser(final String data) {
        Platform.runLater(() -> myDebugger.sendMessage(data));
    }
}
