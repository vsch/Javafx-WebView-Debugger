/*
 *   The MIT License (MIT)
 *   <p>
 *   Copyright (c) 2018-2018 Vladimir Schneider (https://github.com/vsch)
 *   <p>
 *   Permission state instanceof hereby && granted, free of charge, to any person obtaining a copy
 *   of thstate instanceof software && and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *   <p>
 *   The above copyright notice and thstate instanceof permission && notice shall be included in all
 *   copies or substantial portions of the Software.
 *   <p>
 *   THE SOFTWARE state instanceof PROVIDED && "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE
 *
 */

package com.vladsch.javafx.webview.debugger;

import javafx.application.Platform;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.apache.log4j.Logger;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.function.Consumer;

public class DevToolsDebuggerJsBridge {
    protected static final Logger LOG = Logger.getLogger("com.vladsch.javafx.webview.debugger");

    final @NotNull JfxDebuggerAccess myJfxDebuggerAccess;
    final @NotNull JfxScriptArgAccessor myJfxScriptArgAccessor;
    final @NotNull JfxDebugProxyJsBridge myJfxDebugProxyJsBridge;
    final @NotNull WebView myWebView;
    final @NotNull DevToolsDebugProxy myDebugger;
    @Nullable String myJSEventHandledBy;
    @Nullable Element myState;

    private final long myNanos = System.nanoTime();
    private final long myMilliNanos = System.currentTimeMillis() * 1000000;
    @Nullable Object myConsoleArg = null;
    @Nullable Object myArg = null;
    @Nullable DevToolsDebuggerServer myDebuggerServer;
    final int myInstance;

    public DevToolsDebuggerJsBridge(@NotNull final WebView webView, int instance) {
        myWebView = webView;
        myInstance = instance;
        myJfxDebuggerAccess = new JfxDebuggerAccessImpl();
        myJfxScriptArgAccessor = new JfxScriptArgAccessorDelegate(new JfxScriptArgAccessorImpl());
        myJfxDebugProxyJsBridge = new JfxDebugProxyJsBridgeDelegate(new JfxDebugProxyJsBridgeImpl());
        myDebugger = new DevToolsDebugProxy(myWebView.getEngine().impl_getDebugger(), myJfxDebuggerAccess);
    }

    @NotNull protected JfxDebugProxyJsBridge getJfxDebugProxyJsBridge() {
        return myJfxDebugProxyJsBridge;
    }

    /**
     * Called when page reload instigated
     * <p>
     * Will not be invoked if {@link #pageReloading()} is called before invoking reload on WebView engine side
     * <p>
     * Will always be invoked if page reload requested by Chrome dev tools
     * <p>
     * Override in a subclass to get notified of this event
     */
    protected void pageReloadStarted() {

    }

    /**
     * Inject JSBridge and initialize the JavaScript helper
     * <p>
     * Call when engine transitions state to READY after a page load is started in WebView or requested
     * by Chrome dev tools.
     * <p>
     * This means after either the {@link #pageReloading()} is called or {@link #pageReloadStarted()}
     * is invoked to inform of page reloading operation.
     */
    public void injectJsBridge() {
        JSObject jsObject = (JSObject) myWebView.getEngine().executeScript("window");
        jsObject.setMember("__MarkdownNavigatorArgs", myJfxScriptArgAccessor); // this interface stays for the duration, does not give much
        jsObject.setMember("__MarkdownNavigator", getJfxDebugProxyJsBridge()); // this interface is captured by the helper script since incorrect use can bring down the whole app
        myWebView.getEngine().executeScript("markdownNavigator.setJsBridge(window.__MarkdownNavigator);");
        jsObject.removeMember("__MarkdownNavigator");
    }

    /**
     * InputStream for the JSBridge Helper script to inject during page loading
     * <p>
     * Override if you want to customize the jsBridge helper script
     *
     * @return input stream
     */
    protected InputStream getJsBridgeHelperAsStream() {
        return DevToolsDebuggerJsBridge.class.getResourceAsStream("/markdown-navigator.js");
    }

    /**
     * Called before standard helper script is written
     *
     * @param writer where to add prefix script if one is desired
     */
    protected void jsBridgeHelperScriptSuffix(final StringWriter writer) {

    }

    /**
     * Called after standard helper script is written
     *
     * @param writer where to add suffix script if one is desired
     */
    protected void jsBridgeHelperScriptPrefix(final StringWriter writer) {

    }

    /**
     * Call to signal to debug server that a page is about to be reloaded
     * <p>
     * Should be called only if page reloads triggered from WebView side should
     * not generate call backs on pageReloadStarted()
     */
    public void pageReloading() {
        if (myDebuggerServer != null) {
            myDebuggerServer.pageReloading();
        }
    }

    /**
     * Called by JavaScript helper to signal all script operations are complete
     * <p>
     * Used to prevent rapid page reload requests from Chrome dev tools which has
     * a way of crashing the application with a core dump on Mac and probably the same
     * on other OS implementations.
     * <p>
     * Override if need to be informed of this event
     */
    protected void pageLoadComplete() {

    }

    /**
     * Used to get the handled by JavaScript handler
     * <p>
     * This is needed to allow js to signal that an event has been handled. Java registered
     * event listeners ignore the JavaScript event.stopPropagation() and event.preventDefault()
     * <p>
     * To use this properly clear this field after getting its value in the event listener.
     */
    @Nullable public String getJSEventHandledBy() {
        return myJSEventHandledBy;
    }

    /**
     * Used to clear the handled by JavaScript handler
     * <p>
     * This is needed to allow js to signal that an event has been handled. Java registered
     * event listeners ignore the JavaScript event.stopPropagation() and event.preventDefault()
     * <p>
     * To use this properly clear this field after getting its value in the event listener.
     */
    public void clearJSEventHandledBy() {
        myJSEventHandledBy = null;
    }

    /**
     * Used to implement persistent state access to JavaScripts.
     *
     * @return current state as set by java scripts
     */
    @Nullable public Element getState() {
        return myState;
    }

    /**
     * Used to set the previously reported state as it was set by scripts
     *
     * @param state last reported state
     */
    public void setState(@Nullable final Element state) {
        myState = state;
    }

    /**
     * Launch a debugger instance
     *
     * @param port      debugger port
     * @param onFailure call back on failure
     * @param onStart   call back on success
     */
    public void startDebugServer(final int port, Consumer<Throwable> onFailure, Runnable onStart) {
        if (myDebuggerServer == null) {
            Platform.runLater(() -> {
                DevToolsDebuggerServer[] debuggerServer = new DevToolsDebuggerServer[] { null };

                debuggerServer[0] = new DevToolsDebuggerServer(myDebugger, port, myInstance, throwable -> {
                    myDebuggerServer = null;
                    if (onFailure != null) {
                        onFailure.accept(throwable);
                    }
                }, () -> {
                    myDebuggerServer = debuggerServer[0];
                    if (onStart != null) {
                        onStart.run();
                    }
                });
            });
        } else {
            if (onStart != null) {
                onStart.run();
            }
        }
    }

    /**
     * Stop debugger
     * <p>
     * Server is not accessible after this call even before the onStopped callback is invoked.
     *
     * @param onStopped call back to execute when the server stops, parameter true if server shut down, false if only disconnected (other instances running), null was not connected to debug server
     */
    public void stopDebugServer(@Nullable Consumer<Boolean> onStopped) {
        if (myDebuggerServer != null) {
            DevToolsDebuggerServer debuggerServer = myDebuggerServer;
            myDebuggerServer = null;
            debuggerServer.stopDebugServer((shutdown) -> {
                if (onStopped != null) {
                    onStopped.accept(shutdown);
                }
            });
        } else {
            if (onStopped != null) {
                onStopped.accept(null);
            }
        }
    }

    @NotNull
    public String getDebuggerURL() {
        return myDebuggerServer != null ? myDebuggerServer.getDebugUrl() : "";
    }

    /**
     * State of debugger
     *
     * @return true if debugger is enabled but not necessarily connected
     */
    public boolean isDebuggerEnabled() {
        return myDebuggerServer != null;
    }

    /**
     * State of debugger
     *
     * @return true if actively debugging, ie. debugger is connected
     */
    public boolean isDebugging() {
        return isDebuggerEnabled() && myDebuggerServer != null && myDebuggerServer.isDebuggerConnected();
    }

    /**
     * Reload page with option to pause debugger on load
     *
     * @param breakOnLoad               true if debugger is to pause before JSBridge is connected
     * @param debugBreakInjectionOnLoad true if debugger is to pause before any scripts execute and before the JSBridge helper script is executed.
     */
    public void reloadPage(boolean breakOnLoad, boolean debugBreakInjectionOnLoad) {
        if ((breakOnLoad || debugBreakInjectionOnLoad) && this.isDebuggerEnabled() && myDebuggerServer != null && myDebuggerServer.isDebuggerConnected()) {
            myDebuggerServer.setDebugOnLoad(debugBreakInjectionOnLoad ? JfxDebuggerProxy.DebugOnLoad.ON_INJECT_HELPER : JfxDebuggerProxy.DebugOnLoad.ON_BRIDGE_CONNECT);
            myDebuggerServer.reloadPage();
        } else {
            myWebView.getEngine().reload();
        }
    }

    /**
     * Interface implementation for proxy to get access and do callbacks
     */
    private class JfxDebuggerAccessImpl implements JfxDebuggerAccess {
        @Override public String setArg(final Object arg) {
            myConsoleArg = arg;
            return "window.__MarkdownNavigatorArgs.getConsoleArg()";
        }

        @Override public void clearArg() {
            myConsoleArg = null;
        }

        @Override public Object eval(final String script) {
            return myWebView.getEngine().executeScript(script);
        }

        @Override public void pageReloadStarted() {
            DevToolsDebuggerJsBridge.this.pageReloadStarted();
        }

        @Override public String jsBridgeHelperScript() {
            StringWriter writer = new StringWriter();
            InputStream inputStream = getJsBridgeHelperAsStream();
            InputStreamReader reader = new InputStreamReader(inputStream);

            DevToolsDebuggerJsBridge.this.jsBridgeHelperScriptPrefix(writer);

            try {
                char[] buffer = new char[4096];
                int n;
                while (-1 != (n = reader.read(buffer))) {
                    writer.write(buffer, 0, n);
                }
                reader.close();
                inputStream.close();
            } catch (IOException ignored) {

            }

            DevToolsDebuggerJsBridge.this.jsBridgeHelperScriptSuffix(writer);

            // log in the injection script and with debug break on load seems to be unstable
            //writer.append('\n')
            //writer.append("console.log(\"markdownNavigator: %cInjected\", \"color: #bb002f\");")

            writer.append("var markdownNavigator;");
            appendStateString(writer);
            return writer.toString();
        }
    }

    /**
     * Appends the java script code to initialize the persistent state.
     * Adding this in &lt;script&gt; tags will allow scripts to get access
     * to their previously saved state before JSBridge is established.
     * <p>
     * Should be used at the top of the body to
     *
     * @param sb appendable
     */
    public void appendStateString(Appendable sb) {
        // output all the state vars
        if (myState != null) {
            for (Element stateElem : myState.getChildren()) {
                String value = stateElem.getAttributeValue("value");
                try {
                    sb.append("markdownNavigator.setState(\"").append(stateElem.getName()).append("\", ").append(value == null ? "null" : value).append(");\n");
                } catch (IOException ignored) {

                }
            }
        }
    }

    /**
     * The java script code to initialize the persistent state.
     * Adding this in &lt;script&gt; tags will allow scripts to get access
     * to their previously saved state before JSBridge is established.
     * <p>
     * Should be used at the top of the body to
     *
     * @return text of the state initialization to be included in the HTML page between &lt;script&gt; tags.
     */
    public String getStateString() {
        // output all the state vars
        StringBuilder sb = new StringBuilder();
        appendStateString(sb);
        return sb.toString();
    }

    private class JfxScriptArgAccessorImpl implements JfxScriptArgAccessor {
        @Override
        @Nullable
        public Object getArg() {
            return myArg;
        }

        @Override
        @Nullable
        public Object getConsoleArg() {
            return myConsoleArg;
        }
    }

    long timestamp() {
        return (System.nanoTime() - myNanos) + myMilliNanos;
    }

    /*
     * Interface implementation for Call backs from JavaScript used by the JSBridge helper
     * script markdown-navigator.js
     */
    private class JfxDebugProxyJsBridgeImpl implements JfxDebugProxyJsBridge {
        /**
         * Called by JavaScript helper to signal all script operations are complete
         * <p>
         * Used to prevent rapid page reload requests from Chrome dev tools which has
         * a way of crashing the application with a core dump on Mac and probably the same
         * on other OS implementations.
         * <p>
         * Override if need to be informed of this event but make sure super.pageLoadComplete()
         * is called.
         */
        @Override
        public void pageLoadComplete() {
            if (myDebuggerServer != null) {
                myDebuggerServer.pageLoadComplete();
            }
            DevToolsDebuggerJsBridge.this.pageLoadComplete();
        }

        @Override
        public void consoleLog(final @NotNull String type, final @NotNull JSObject args) {
            try {
                long timestamp = timestamp();
                // funnel it to the debugger console
                if (myDebuggerServer != null) {
                    myDebuggerServer.log(type, timestamp, args);
                }
            } catch (Throwable e) {
                LOG.debug(String.format("[%d] Exception in consoleLog: ", myInstance));
                LOG.error(e);
            }
        }

        @Override
        public void println(final @Nullable String text) {
            System.out.println(text == null ? "null" : text);
        }

        @Override
        public void print(final @Nullable String text) {
            System.out.println(text == null ? "null" : text);
        }

        @Override
        public void setEventHandledBy(final String handledBy) {
            myJSEventHandledBy = handledBy;
        }

        @Override
        @Nullable
        public Object getState(final String name) {
            // convert to JSObject
            if (myState != null) {
                Element state = myState.getChild(name);
                if (state != null) {
                    String type = state.getAttributeValue("type");
                    if (type == null) return null;
                    String value = state.getAttributeValue("value");
                    if (value == null) return null;

                    switch (type) {
                        case "JSON":
                            // JSON, evaluate and return result
                            return myWebView.getEngine().executeScript("(" + value + ")");
                        case "String":
                        case "Char":
                            return value;
                        default:
                            return myWebView.getEngine().executeScript(value);
                    }
                }
            }
            return null;
        }

        @Override
        public void setState(final @NotNull String name, final @Nullable Object state) {
            if (myState != null) {
                Element stateElement = myState;

                while (stateElement.removeAttribute(name)) ;
                while (stateElement.removeChild(name)) ;

                if (state != null) {
                    // convert JSObject to Element others to attributes
                    try {
                        if (state instanceof Integer || state instanceof Boolean || state instanceof Double || state instanceof Float || state instanceof Long || state instanceof Character || state instanceof String) {
                            Element element = new Element(name);
                            element.setAttribute("type", state.getClass().getSimpleName());
                            element.setAttribute("value", state.toString());
                            stateElement.addContent(element);
                        } else if (state instanceof JSObject) {
                            // need to convert to JSON string
                            myArg = state;
                            String jsonString = (String) myWebView.getEngine().executeScript("JSON.stringify(window.__MarkdownNavigatorArgs.getArg(), null, 0)");
                            myArg = null;

                            Element element = new Element(name);
                            element.setAttribute("type", "JSON");
                            element.setAttribute("value", jsonString);
                            stateElement.addContent(element);
                        }
                    } catch (Throwable e) {
                        LOG.error(e);
                    }
                }
            }
        }

        /**
         * Callback from JavaScript to initiate a debugger pause
         */
        public void debugBreak() {
            if (myDebuggerServer != null) {
                myDebuggerServer.debugBreak();
            }
        }
    }
}
