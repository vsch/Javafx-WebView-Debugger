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

import com.sun.javafx.application.PlatformImpl;
import com.sun.javafx.scene.web.Debugger;
import com.vladsch.boxed.json.BoxedJsArray;
import com.vladsch.boxed.json.BoxedJsNumber;
import com.vladsch.boxed.json.BoxedJsObject;
import com.vladsch.boxed.json.BoxedJsString;
import com.vladsch.boxed.json.BoxedJsValue;
import com.vladsch.boxed.json.BoxedJson;
import com.vladsch.boxed.json.MutableJsArray;
import com.vladsch.boxed.json.MutableJsObject;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.util.Callback;
import netscape.javascript.JSException;
import netscape.javascript.JSObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.json.JsonValue;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class DevToolsDebugProxy implements Debugger, JfxDebuggerProxy, Callback<String, Void> {
    final static int RUNTIME_COMPILE_SCRIPT = 1;
    final static int RUNTIME_LOG_API = 2;
    final static int RUNTIME_LOG_STACK = 3; // resume after stack trace
    final static int RUNTIME_SKIP = 4; // just absorb it
    final static int RUNTIME_EVALUATE_SCRIPT = 5; // just absorb it
    final static int DEBUGGER_PAUSED = 6; // just absorb it
    final static int BREAK_POINT_REMOVE = 7;
    final static int REQUEST_JS_BRIDGE = 8;
    final static int DOM_GET_DOCUMENT = 9; // result is dom get document response

    private static final String EMPTY_EVAL_SCRIPT = "// injected eval to pause immediately";
    private static final String EMPTY_EVAL_STEP_SCRIPT = "\"\";";

    final @Nullable Debugger myDebugger;
    final @NotNull JfxDebuggerAccess myJfxDebuggerAccess;
    Callback<String, Void> myCallback;
    final HashMap<Integer, Integer> myAsyncIdMap = new HashMap<>();     // mapping of result messages waiting reception from the debugger
    final HashMap<Integer, Integer> myAsyncResultMap = new HashMap<>(); // which results we need to massage
    int myDebuggerId;       // id of next debugger message
    int myLastPageContextId;
    int mySendNesting = 0;
    String mySendNestingIndent = "";
    private boolean myWaitingForEvaluateScript = false;
    private final ArrayList<JfxConsoleApiArgs> myQueuedLogRequests = new ArrayList<>();
    private Runnable myOnEvalDoneRunnable = null;
    private Runnable myOnDebuggerResumedRunnable = null;
    private boolean myProcessingLogRequest = false;
    private Consumer<String> myOnPausedParamsRunnable = null;
    private Consumer<Integer> myOnPageContextCreatedRunnable = null;
    private DebugOnLoad myDebugOnLoad = DebugOnLoad.NONE;
    private final HashSet<Integer> myOldPageContextIds = new HashSet<>();
    private boolean mySuppressPageReloadRequest;   // when page reload requested from javafx updater not debugger and we don't want to receive page reloading message for it
    private DebuggerState myDebuggerState;
    private BoxedJsObject myRuntimeEvaluateArgResult;
    final private AtomicBoolean myDebuggerIsPaused = new AtomicBoolean(false);
    private boolean myPageReloadStarted = false; // true if started loading but did not yet complete, to prevent nested reload requests
    final private HashMap<String, String> myBreakpoints = new HashMap<>();
    final private HashMap<Integer, String> myBreakpointsToRemove = new HashMap<>(); // request to break point id
    private boolean myIsEnabled;
    private boolean myIsShuttingDown;
    private HashMap<Integer, BoxedJsObject> myNodeIdMap = new HashMap<>(); // node id to params of DOM.setChildNodes method from webView with "parentId" added to each node and "ordinal" position in parent's children
    private int myRootNodeId = 0; // this is the document node id
    final LogHandler LOG = LogHandler.getInstance();

    // reflects the last command received for debugger
    // needed so that after pausing for console log stack trace, we can use the same
    // command instead of resume
    public enum DebuggerState {
        PAUSED("Debugger.pause"),
        RUNNING("Debugger.resume"),
        STEP_OVER("Debugger.stepOver"),
        STEP_INTO("Debugger.stepInto"),
        STEP_OUT("Debugger.stepOut");

        final public String method;

        DebuggerState(final String method) {
            this.method = method;
        }
    }

    @Nullable
    public static Debugger getDebugger(@NotNull WebEngine engine) {
        try {
            // DEPRECATED: no replacement
            @SuppressWarnings("deprecation")
            Debugger debugger = engine.impl_getDebugger();
            return debugger;
        } catch (NoSuchMethodError error) {
            Class<?> webEngineClazz = WebEngine.class;

            Field debuggerField = null;
            try {
                debuggerField = webEngineClazz.getDeclaredField("debugger");
                debuggerField.setAccessible(true);
                return (Debugger) debuggerField.get(engine);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    public DevToolsDebugProxy(@NotNull final WebEngine engine, @NotNull final JfxDebuggerAccess jfxDebuggerAccess) {
        myJfxDebuggerAccess = jfxDebuggerAccess;
        myDebugger = getDebugger(engine);
        clearState();

        if (myDebugger != null) {
            myDebugger.setMessageCallback(this);
        }
    }

    private void clearState() {
        myDebuggerId = 1;
        myLastPageContextId = 0;
        myAsyncIdMap.clear();
        myAsyncResultMap.clear();
        mySendNesting = 0;
        mySendNestingIndent = "";
        mySuppressPageReloadRequest = false;
        myDebuggerState = DebuggerState.RUNNING;
        clearOnPageReload();
        myDebuggerIsPaused.set(false);
        myPageReloadStarted = false;
        myBreakpoints.clear();
        myBreakpointsToRemove.clear();
        myIsShuttingDown = false;
        myOnPageContextCreatedRunnable = null;
    }

    private void clearOnPageReload() {
        myQueuedLogRequests.clear();
        myWaitingForEvaluateScript = false;
        myOnEvalDoneRunnable = null;
        myOnPausedParamsRunnable = null;
        myProcessingLogRequest = false;
        myRuntimeEvaluateArgResult = null;
        myAsyncResultMap.clear();
        myOnDebuggerResumedRunnable = null;
        myNodeIdMap.clear();
        myRootNodeId = 0;
        myJfxDebuggerAccess.clearArg();
    }

    @Override
    public boolean isDebuggerPaused() {
        return myDebuggerIsPaused.get();
    }

    private void logMessage(final String message) {
        if (LOG.isDebugEnabled()) System.out.println(message);
    }

    @Override
    public void releaseDebugger(final boolean shuttingDown, @Nullable Runnable runnable) {
        if (!myIsEnabled) return;

        myIsShuttingDown = shuttingDown;
        if (myDebuggerIsPaused.get()) {
            Runnable action = () -> {
                if (myDebugger != null && myDebugger.isEnabled()) {
                    if (myOnDebuggerResumedRunnable == null) {
                        myOnDebuggerResumedRunnable = runnable;
                    } else if (runnable != null) {
                        // already being paused, chain them
                        Runnable other = myOnDebuggerResumedRunnable;
                        myOnDebuggerResumedRunnable = () -> {
                            other.run();
                            runnable.run();
                        };
                    }
                    debuggerSend(String.format("{\"id\":%d,\"method\":\"%s\"}", myDebuggerId++, DebuggerState.RUNNING.method), EMPTY_EVAL_SCRIPT);
                } else if (runnable != null) {
                    runnable.run();
                }
            };

            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                PlatformImpl.runAndWait(action);
            }
        } else if (runnable != null) {
            runnable.run();
        }
    }

    @Override
    public void onOpen() {
        myIsShuttingDown = false;
        Platform.runLater(myJfxDebuggerAccess::onConnectionOpen);
    }

    @Override
    public void setDebugOnLoad(final DebugOnLoad debugOnLoad) {
        myDebugOnLoad = debugOnLoad;
    }

    @Override
    public DebugOnLoad getDebugOnLoad() {
        return myDebugOnLoad;
    }

    @Override
    public void onClosed(final int code, final String reason, final boolean remote) {
        if (!myIsEnabled) return;

        //myIsShuttingDown = true; // this messes it up, don't do it, shutdown is when the user turns off debugging

        // TODO: return to not yet connected condition
        removeAllBreakpoints(() -> {
            releaseDebugger(true, () -> {
                //noinspection Convert2MethodRef
                clearOnPageReload();
            });
        });

        Platform.runLater(myJfxDebuggerAccess::onConnectionClosed);
    }

    @Override
    public void removeAllBreakpoints(final @Nullable Runnable runAfter) {
        if (!myIsEnabled || myDebugger == null) return;

        // must be called on javafx thread, debugger can be paused or not
        Platform.runLater(() -> {
            // one of those left over break points
            // Before resuming, remove all break points
            ArrayList<String> breakPoints = new ArrayList<>(myBreakpoints.keySet());
            BoxedJsObject jsRemoveBreakPoint = BoxedJson.boxedFrom("{\"id\":454,\"method\":\"Debugger.removeBreakpoint\",\"params\":{\"breakpointId\":\"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:16:0\"}}");
            for (String breakpointId : breakPoints) {
                // {"id":454,"method":"Debugger.removeBreakpoint","params":{"breakpointId":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:16:0"}}
                jsRemoveBreakPoint.evalSet("id", myDebuggerId++)
                        .evalSet("params.breakpointId", breakpointId)
                ;
                String removeParam = jsRemoveBreakPoint.toString();
                logMessage(String.format("Removing all breakpoints %s", removeParam));
                myDebugger.sendMessage(removeParam);
            }
            myBreakpoints.clear();
            myBreakpointsToRemove.clear();

            if (runAfter != null) {
                runAfter.run();
            }
        });
    }

    @Override
    public void pageReloading() {
        if (!myIsEnabled) return;

        mySuppressPageReloadRequest = true;
        myPageReloadStarted = true;
    }

    @Override
    public void reloadPage() {
        if (!myIsEnabled) return;

        // send page reload so it can be debugged, does not work
        if (!myPageReloadStarted) {
            myPageReloadStarted = true;
            Platform.runLater(() -> {
                mySuppressPageReloadRequest = false;
                logMessage(String.format("Sending page reload, request %d", myDebuggerId));
                debuggerSend(String.format("{\"id\":%d,\"method\":\"Page.reload\", \"params\": {\"ignoreCache\":false}}", myDebuggerId++), null);
            });
        }
    }

    @Override
    public void pageLoadComplete() {
        myPageReloadStarted = false;
    }

    void debuggerSend(String message, final String evalAfter) {
        if (myDebugger != null) {
            mySendNesting++;
            String wasIndent = mySendNestingIndent;
            mySendNestingIndent += "  ";
            logMessage(String.format("%sSending %s", mySendNestingIndent, message));
            myDebugger.sendMessage(message);
            if (evalAfter != null) {
                yieldDebugger(() -> {
                    myJfxDebuggerAccess.eval(evalAfter);
                });
            }
            mySendNestingIndent = wasIndent;
            mySendNesting--;
        }
    }

    /**
     * Called before the passed in parameters are funnelled as a dummy script for evaluation
     * In the callers context, the messages coming in from WebView debugger should be monitored and
     * the runtime console api message generated from its parameters
     *
     * @param type      console log call type
     * @param timestamp timestamp in nanoseconds since epoch 1970/1/1.
     * @param args      Arguments passed to the log function
     */
    @Override
    public void log(final String type, final long timestamp, final JSObject args) {
        if (!myIsEnabled || myIsShuttingDown) return;

        // {"method":"Runtime.consoleAPICalled","params":{"type":"warning","args":[{"type":"string","value":"warning"}],"executionContextId":30,"timestamp":1519047166210.763,"stackTrace":{"callFrames":[{"functionName":"","scriptId":"684","url":"","lineNumber":0,"columnNumber":8}]}}}
        // return string to append to end of the param call so we know what Runtime.evaluate text to look for to accumulate arguments
        // the string is a comment // #frameId.N   where N 0...args.length, with the last one being just #frameId, marking that we can generate and send the consoleAPI called message
        //
        int iMax = (int) args.getMember("length");
        Object[] argList = new Object[iMax];
        for (int i = 0; i < iMax; i++) {
            argList[i] = args.getSlot(i);
        }

        final JfxConsoleApiArgs consoleArgs = new JfxConsoleApiArgs(argList, type, timestamp);

        if (myWaitingForEvaluateScript) {
            // could not be sure to handle the evaluate args properly, so let the Runtime.evaluate through to the debugger
            // and it contained a log somewhere in the call tree. Won't get correct call location but everything else
            // is fine.
            myProcessingLogRequest = true;
            if (myQueuedLogRequests.isEmpty()) {
                // first log request in the Runtime.evaluate, it to the queue so we know the next one is not first
                myQueuedLogRequests.add(consoleArgs);
                myOnEvalDoneRunnable = () -> {
                    pause(pausedParam -> {
                        consoleArgs.setPausedParam(pausedParam);
                        // drop the first it is already in consoleArgs
                        JfxConsoleApiArgs tmp = myQueuedLogRequests.remove(0);
                        assert tmp == consoleArgs;
                        collectConsoleAPIParams(consoleArgs);
                    });
                };
            } else {
                // the rest will be run after the first
                myQueuedLogRequests.add(consoleArgs);
            }
        } else {
            if (myProcessingLogRequest) {
                // queue it up, they get log debug params if there are any
                myQueuedLogRequests.add(consoleArgs);
            } else {
                myProcessingLogRequest = true;
                pause(pausedParam -> {
                    consoleArgs.setPausedParam(pausedParam);
                    collectConsoleAPIParams(consoleArgs);
                });
            }
        }
    }

    private void yieldDebugger(final Runnable runnable) {
        //ApplicationManager.getApplication().invokeLater(() -> {
        SwingUtilities.invokeLater(() -> {
            Platform.runLater(() -> {
                //noinspection Convert2MethodRef,FunctionalExpressionCanBeFolded
                runnable.run();
            });
        });
    }

    private void pause(@NotNull Consumer<String> onPausedRunnable) {
        if (myWaitingForEvaluateScript || myOnPausedParamsRunnable != null) {
            throw new IllegalStateException("Pause request nested or during waiting on eval");
        }

        final int debuggerId = myDebuggerId++;
        myAsyncResultMap.put(debuggerId, DEBUGGER_PAUSED); // skip the result of this pause request
        logMessage(String.format("Pausing debugger, request %d", debuggerId));

        myOnPausedParamsRunnable = (pausedParams) -> {
            logMessage(String.format("Running onPaused callback for request %d", debuggerId));
            onPausedRunnable.accept(pausedParams);
        };

        debuggerSend(String.format("{\"id\":%d,\"method\":\"Debugger.pause\"}", debuggerId), EMPTY_EVAL_SCRIPT);
    }

    @Override
    public void debugBreak() {
        if (!myIsEnabled || myIsShuttingDown) return;

        debugBreak(null);
    }

    public void debugBreak(String evalAfter) {
        logMessage(String.format("DebugBreak debugger, request %d", myDebuggerId));
        myDebuggerState = DebuggerState.PAUSED;
        debuggerSend(String.format("{\"id\":%d,\"method\":\"Debugger.pause\"}", myDebuggerId++), evalAfter);
    }

    private BoxedJsObject getArgParam(Object arg) {
        return getArgParam(arg, argParamJson());
    }

    private BoxedJsObject getArgParam(Object arg, BoxedJsObject json) {
        //{"result":{"result":{"type":"object","objectId":"{\"injectedScriptId\":1,\"id\":5}","className":"Object","description":"Object","preview":{"type":"object","description":"Object","lossless":true,"properties":[{"name":"x","type":"number","value":"0"},{"name":"y","type":"number","value":"79.27999999999997"}]}},"wasThrown":false},"id":36}
        //{"id":36,"method":"Runtime.evaluate","params":{"expression":"onLoadScroll","objectGroup":"console","includeCommandLineAPI":true,"silent":false,"contextId":1,"returnByValue":false,"generatePreview":true,"userGesture":true,"awaitPromise":false}}

        final String argScript = myJfxDebuggerAccess.setArg(arg);
        json.evalSet("id", myDebuggerId)
                .evalSet("params.expression", argScript)
                .evalSet("params.contextId", myLastPageContextId);
        final String message = json.toString();

        myAsyncResultMap.put(myDebuggerId, RUNTIME_LOG_API);
        myDebuggerId++;

        myRuntimeEvaluateArgResult = null;

        debuggerSend(message, null);

        assert myRuntimeEvaluateArgResult != null;
        BoxedJsObject result = myRuntimeEvaluateArgResult;
        myRuntimeEvaluateArgResult = null;
        return result;
    }

    private BoxedJsObject argParamJson() {
        final String evalScript = "{\"id\":0,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":\"\",\"objectGroup\":\"console\",\"includeCommandLineAPI\":true,\"silent\":false,\"contextId\":1,\"returnByValue\":false,\"generatePreview\":true,\"userGesture\":true,\"awaitPromise\":false}}";
        return BoxedJson.boxedFrom(evalScript);
    }

    private void collectConsoleAPIParams(JfxConsoleApiArgs consoleArgs) {
        final BoxedJsObject json = argParamJson();
        final String firstPauseParams = consoleArgs.getPausedParam();
        assert firstPauseParams != null;

        do {
            // debugger was paused, we can now evaluate on call frame
            final Object[] args = consoleArgs.getArgs();
            final int iMax = args.length;

            for (int i = 0; i < iMax; i++) {
                final Object arg = args[i];
                // create Runtime.evaluate with this message

                logMessage(String.format("Evaluating result param[%d], request %d", i, myDebuggerId));
                BoxedJsObject result = getArgParam(arg, json);
                if (result == null) return;

                final BoxedJsObject jsParam = result.eval("result.result").asJsObject();
                consoleArgs.setParamJson(i, jsParam);
            }

            if (consoleArgs.getPausedParam() == null) {
                consoleArgs.setPausedParam(firstPauseParams);
            }

            sendConsoleAPI(consoleArgs);
            if (!myWaitingForEvaluateScript && !myQueuedLogRequests.isEmpty()) {
                consoleArgs = myQueuedLogRequests.remove(0);
            } else {
                // let the end of evaluation or next request log request continue the process
                consoleArgs = null;
                myProcessingLogRequest = false;
            }
        } while (consoleArgs != null);

        // resume in a state appropriate to what it was when consoleLog was called.
        logMessage(String.format("Resuming debugger after consoleLogAPI, request %d", myDebuggerId));
        boolean wasRunning = myDebuggerState == DebuggerState.RUNNING;
        String nextState = wasRunning ? DebuggerState.RUNNING.method : DebuggerState.STEP_OVER.method;
        myAsyncResultMap.put(myDebuggerId, RUNTIME_SKIP); // skip the result of this pause request
        debuggerSend(String.format("{\"id\":%d,\"method\":\"%s\"}", myDebuggerId++, nextState), wasRunning ? EMPTY_EVAL_SCRIPT : EMPTY_EVAL_STEP_SCRIPT);
    }

    private void sendConsoleAPI(JfxConsoleApiArgs consoleArgs) {
        // the parameters for consoleLog are not the break point stack params because it disappears before dev tools can get this information
        // so the console log api sends resolved information
        //{
        //  "method": "Runtime.consoleAPICalled",
        //  "params": {
        //    "type": "log",
        //    "args": [
        //      {
        //        "type": "string",
        //        "value": "test"
        //      }
        //    ],
        //    "executionContextId": 5,
        //    "timestamp": 1519412254600.44,
        //    "stackTrace": {
        //      "callFrames": [
        //        {
        //          "functionName": "",
        //          "scriptId": "183",
        //          "url": "",
        //          "lineNumber": 0,
        //          "columnNumber": 8
        //        }
        //      ]
        //    }
        //  }
        //}
        //
        // each call frame in the pausedParams is:
        //        "callFrameId": "{\"ordinal\":0,\"injectedScriptId\":2}",
        //        "functionName": "consoleLog",
        //        "location": {
        //          "scriptId": "43",
        //          "lineNumber": 33,
        //          "columnNumber": 8
        //        },
        //
        // need to convert it to what dev tools expects
        //
        BoxedJsObject json;//{"method":"Runtime.consoleAPICalled","params":{"type":"warning","args":[{"type":"string","value":"warning"}],"executionContextId":30,"timestamp":1519047166210.763,"stackTrace":{"callFrames":[{"functionName":"","scriptId":"684","url":"","lineNumber":0,"columnNumber":8}]}}}

        json = BoxedJson.boxedFrom(consoleArgs.getPausedParam());
        final BoxedJsArray jsCallFrames = json.eval("params.callFrames").asJsArray();
        final MutableJsArray jsStackFrames = new MutableJsArray(jsCallFrames.size());
        int skipFrames = jsCallFrames.size() > 1 ? 1 : 0;
        for (JsonValue jsonValue : jsCallFrames) {
            BoxedJsObject jsCallFrame = BoxedJson.boxedOf(jsonValue).asJsObject();
            if (jsCallFrame.isValid() && --skipFrames < 0) {
                BoxedJsObject jsStackFrame = BoxedJson.boxedOf(new MutableJsObject(5));
                jsStackFrame.evalSet("functionName", jsCallFrame.get("functionName").asJsString())
                        .evalSet("scriptId", jsCallFrame.eval("location.scriptId").asJsString())
                        .evalSet("lineNumber", jsCallFrame.eval("location.lineNumber").asJsNumber())
                        .evalSet("columnNumber", jsCallFrame.eval("location.columnNumber").asJsNumber())
                        .evalSet("url", "")
                ;

                jsStackFrames.add(jsStackFrame);
            }
        }

        // all args are done, we have our stack frame
        MutableJsArray args = new MutableJsArray(consoleArgs.getJsonParams().length);
        args.addAll(Arrays.asList(consoleArgs.getJsonParams()));

        final BoxedJsObject consoleApiJson = BoxedJson.boxedFrom("{\"method\":\"Runtime.consoleAPICalled\",\"params\":{\"type\":\"type\",\"args\":[],\"executionContextId\":30,\"stackTrace\":{\"callFrames\":[{\"functionName\":\"\",\"scriptId\":\"684\",\"url\":\"\",\"lineNumber\":0,\"columnNumber\":1}]}}}");
        consoleApiJson.evalSet("params.type", consoleArgs.getLogType())
                .evalSet("params.executionContextId", myLastPageContextId)
                .evalSet("params.timestamp", consoleArgs.getTimestamp() / 1000000.0)
                .evalSet("params.stackTrace.callFrames", jsStackFrames)
                .evalSet("params.args", args)
        ;
        String dataParam = consoleApiJson.toString();

        logMessage("Sending console log data " + dataParam);
        if (myCallback != null) {
            myCallback.call(dataParam);
        }

        consoleArgs.clearAll();
        myJfxDebuggerAccess.clearArg();
    }

    /**
     * Call back to send message to remote from the debugger
     *
     * @param param json
     *
     * @return void
     */
    @Override
    public Void call(final String param) {
        // pre-process results here and possibly change or filter calls to debugger
        String changedParam = param;
        BoxedJsObject json = BoxedJson.boxedFrom(param);
        boolean changed = false;
        boolean runOnEvalRunnables = false;
        final BoxedJsString method = json.get("method").asJsString();
        BoxedJsNumber jsId = json.getJsonNumber("id");

        // we need to extract the page id context for evaluation of console.log arguments
        // {"method":"Runtime.executionContextCreated","params":{"context":{"id":37,"origin":"https://www.chromium.org","name":"","auxData":{"isDefault":true,"frameId":"(4C61E9CA78FE3BCDFCDBF02580564A1)"}}}}
        switch (method.getString()) {
            case "Runtime.executionContextCreated": {
                // when seeing {"method":"Runtime.executionContextCreated","params":{"context":{"id":6,"isPageContext":true,"name":"","frameId":"0.1"}}}
                // then if on request we get the old context id, replace it with the latest page context
                final BoxedJsObject jsContext = json.evalJsObject("params.context");
                final BoxedJsNumber contextIdNumber = jsContext.getJsonNumber("id");
                if (jsContext.eval("isPageContext").isTrue() && contextIdNumber.isValid()) {
                    if (myLastPageContextId > 0) {
                        myOldPageContextIds.add(myLastPageContextId);
                    }
                    myLastPageContextId = contextIdNumber.intValue();
                    Consumer<Integer> onPageContextRunnable = myOnPageContextCreatedRunnable;
                    myOnPageContextCreatedRunnable = null;
                    if (onPageContextRunnable != null) {
                        onPageContextRunnable.accept(myLastPageContextId);
                    }
                }
                break;
            }
            case "Debugger.paused": {
                logMessage(String.format("Got debug paused: %s", param));
                myDebuggerIsPaused.set(true);

                final Consumer<String> runnable = myOnPausedParamsRunnable;
                myOnPausedParamsRunnable = null;
                if (runnable != null) {
                    yieldDebugger(() -> runnable.accept(param));
                    return null;
                }

                if (myIsShuttingDown) {
                    // make it continue so it does not hang the app
                    yieldDebugger(() -> {
                        if (myDebugger != null && myIsEnabled) {
                            // let's see the reason, if it is a break point, we delete it
                            //{"method":"Debugger.paused","params":{"callFrames":[{"callFrameId":"{\"ordinal\":0,\"injectedScriptId\":3}","functionName":"","location":{"scriptId":"81","lineNumber":10,"columnNumber":16},"scopeChain":[{"object":{"type":"object","objectId":"{\"injectedScriptId\":3,\"id\":174}","className":"JSLexicalEnvironment","description":"JSLexicalEnvironment"},"type":"local"},{"object":{"type":"object","objectId":"{\"injectedScriptId\":3,\"id\":175}","className":"JSLexicalEnvironment","description":"JSLexicalEnvironment"},"type":"closure"},{"object":{"type":"object","objectId":"{\"injectedScriptId\":3,\"id\":176}","className":"JSLexicalEnvironment","description":"JSLexicalEnvironment"},"type":"closure"},{"object":{"type":"object","objectId":"{\"injectedScriptId\":3,\"id\":177}","className":"JSLexicalEnvironment","description":"JSLexicalEnvironment"},"type":"closure"},{"object":{"type":"object","objectId":"{\"injectedScriptId\":3,\"id\":178}","className":"JSLexicalEnvironment","description":"JSLexicalEnvironment"},"type":"closure"},{"object":{"type":"object","objectId":"{\"injectedScriptId\":3,\"id\":179}","className":"Window","description":"Window"},"type":"global"}],"this":{"type":"object","objectId":"{\"injectedScriptId\":3,\"id\":180}","subtype":"node","className":"HTMLDivElement","description":"div.adm-block.adm-example.adm-collapsed"}}],"reason":"Breakpoint","data":{"breakpointId":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:10:0"}}}
                            //{
                            //  "method": "Debugger.paused",
                            //  "params": {
                            //    "reason": "Breakpoint",
                            //    "data": {
                            //      "breakpointId": "file:///Users/vlad/src/sites/public/mn-resources/admonition.js:10:0"
                            //    }
                            //  }
                            //}
                            BoxedJsString reason = json.eval("params.reason").asJsString();
                            BoxedJsString breakpointId = json.eval("params.data.breakpointId").asJsString();
                            if (breakpointId.isValid() && reason.getString().equals("Breakpoint")) {
                                // remove the thing
                                // {"id":454,"method":"Debugger.removeBreakpoint","params":{"breakpointId":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:16:0"}}
                                BoxedJsObject jsRemoveBreakPoint = BoxedJson.boxedFrom("{\"id\":454,\"method\":\"Debugger.removeBreakpoint\",\"params\":{\"breakpointId\":\"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:16:0\"}}");
                                jsRemoveBreakPoint.evalSet("id", myDebuggerId++);
                                jsRemoveBreakPoint.evalSet("params.breakpointId", breakpointId);
                                String removeParam = jsRemoveBreakPoint.toString();
                                logMessage(String.format("Removing leftover breakpoint %s", removeParam));
                                myDebugger.sendMessage(removeParam);
                            }
                            // now resume
                            debuggerSend(String.format("{\"id\":%d,\"method\":\"%s\"}", myDebuggerId++, DebuggerState.RUNNING.method), "");
                        }
                    });
                    return null;
                }

                break;
            }
            case "Debugger.resumed": {
                myDebuggerIsPaused.set(false);
                Runnable runnable = myOnDebuggerResumedRunnable;
                myOnDebuggerResumedRunnable = null;
                if (runnable != null) {
                    yieldDebugger(runnable);
                }
                logMessage(String.format("Got debug resumed: %s", param));
                break;
            }
            case "Debugger.globalObjectCleared": {
                //{"method":"Network.loadingFinished","params":{"requestId":"0.100","timestamp":0.06045897198055172}}
                //{"method":"Page.frameStartedLoading","params":{"frameId":"0.1"}}
                // {"method":"Debugger.globalObjectCleared"}
                myDebuggerState = DebuggerState.RUNNING;
                clearOnPageReload();

                // we use this to inject our custom code into the global space
                // to handle things until jsBridge is established. This is just in case
                // the page was not instrumented with our code. If it was then it will overwrite
                // this injection with a script file
                //myDebugOnLoad = false;
                // see if we can inject something into the global object before any scripts run
                // {"id":250,"method":"Runtime.evaluate","params":{"expression":"window.__MarkdownNavigatorArgs.getConsoleArg()","objectGroup":"console","includeCommandLineAPI":true,"silent":false,"contextId":4,"returnByValue":false,"generatePreview":true,"userGesture":true,"awaitPromise":false}}
                myOnPageContextCreatedRunnable = (pageContextId) -> {
                    if (myDebugOnLoad == DebugOnLoad.ON_INJECT_HELPER) {
                        // able to debug our injected script if issuing a pause here
                        // but with external pages does crash
                        myDebugOnLoad = DebugOnLoad.NONE;
                        logMessage(String.format("Setting pause after inject helpers, request %d", myDebuggerId));
                        myDebuggerState = DebuggerState.PAUSED;
                        debuggerSend(String.format("{\"id\":%d,\"method\":\"Debugger.pause\"}", myDebuggerId++), null);
                    }

                    BoxedJsObject paramJson = argParamJson();
                    //final String argScript = "window.injectedCode = { tests: () => { return \"I'm injected\"; } };";
                    final String argScript = myJfxDebuggerAccess.jsBridgeHelperScript();
                    paramJson.evalSet("id", myDebuggerId)
                            .evalSet("params.expression", argScript)
                            .evalSet("params.includeCommandLineAPI", true)
                            .evalSet("params.objectGroup", "markdownNavigatorInjected")
                            .evalSet("params.contextId", pageContextId)
                    ;

                    myAsyncResultMap.put(myDebuggerId, REQUEST_JS_BRIDGE);

                    logMessage(String.format("Injecting helper script, request %d", myDebuggerId));
                    myDebuggerId++;
                    debuggerSend(paramJson.toString(), null);
                };
                logMessage(String.format("Got Debugger.globalObjectCleared: %s", param));
                break;
            }
            //case "Page.frameStoppedLoading": {
            //    // not used
            //    break;
            //}

            case "DOM.setChildNodes": {
                BoxedJsNumber jsParentId = json.evalJsNumber("params.parentId");
                BoxedJsArray jsNodes = json.evalJsArray("params.nodes");
                //"parentId":93,
                // "nodes": [
                if (jsParentId.isValid() && jsNodes.isValid()) {
                    logMessage(String.format("Adding children of node: %d", jsParentId.intValue()));
                    addNodeChildren(jsParentId.intValue(), jsNodes, 0, null, 0);
                } else {
                    // did not add
                    logMessage(String.format("Did not process children for %s", param));
                    break;
                }
                break;
            }

            case "DOM.childNodeRemoved": {
                //        {
                //          "name": "childNodeRemoved",
                //          "parameters": [
                //            {
                //              "name": "parentNodeId",
                //              "$ref": "NodeId",
                //              "description": "Parent id."
                //            },
                //            {
                //              "name": "nodeId",
                //              "$ref": "NodeId",
                //              "description": "Id of the node that has been removed."
                //            }
                //          ],
                //          "description": "Mirrors <code>DOMNodeRemoved</code> event."
                //        }
                BoxedJsNumber jsParentId = json.evalJsNumber("params.parentNodeId");
                BoxedJsNumber jsNodeId = json.getJsonNumber("params.nodeId");
                BoxedJsObject jsParentParams = myNodeIdMap.getOrDefault(jsParentId.intValue(), BoxedJsValue.HAD_NULL_OBJECT);
                boolean handled = false;
                if (jsParentId.isValid() && jsNodeId.isValid() && jsParentParams.isValid()) {
                    // we now parse for nodes, remove this node and process it as new
                    logMessage(String.format("Removing child %d of node: %d", jsNodeId.intValue(), jsParentId.intValue()));
                    BoxedJsArray jsNodes = jsParentParams.getJsonArray("children");
                    addNodeChildren(jsParentId.intValue(), jsNodes, jsNodeId.intValue(), null, 0);
                    handled = true;
                }

                if (!handled) {
                    // did not add
                    logMessage(String.format("Did not insert child node for %s", param));
                    break;
                }
                break;
            }

            case "DOM.childNodeInserted": {
                //        {
                //          "name": "childNodeInserted",
                //          "parameters": [
                //            {
                //              "name": "parentNodeId",
                //              "$ref": "NodeId",
                //              "description": "Id of the node that has changed."
                //            },
                //            {
                //              "name": "previousNodeId",
                //              "$ref": "NodeId",
                //              "description": "If of the previous siblint."
                //            },
                //            {
                //              "name": "node",
                //              "$ref": "Node",
                //              "description": "Inserted node data."
                //            }
                //          ],
                //          "description": "Mirrors <code>DOMNodeInserted</code> event."
                //        },
                BoxedJsObject jsNode = json.evalJsObject("params.node");
                BoxedJsNumber jsPreviousNodeId = json.evalJsNumber("params.previousNodeId");
                BoxedJsNumber jsParentId = json.evalJsNumber("params.parentNodeId");
                BoxedJsObject jsParentParams = myNodeIdMap.getOrDefault(jsParentId.intValue(), BoxedJsValue.HAD_NULL_OBJECT);
                BoxedJsArray jsNodes = jsParentParams.getJsonArray("children");
                boolean handled = false;
                //"parentId":93,
                // "nodes": [
                if (jsNode.isValid() && jsPreviousNodeId.isValid() && jsParentId.isValid() && jsNodes.isValid()) {
                    logMessage(String.format("Inserting child of node: %d: %s", jsParentId.intValue(), jsNode.toString()));
                    addNodeChildren(jsParentId.intValue(), jsNodes, 0, jsNode, jsPreviousNodeId.intValue());
                    handled = true;
                }

                if (!handled) {
                    // did not add
                    logMessage(String.format("Did not insert child node for %s", param));
                    break;
                }
            }
        }

        BoxedJsObject jsError = json.get("error").asJsObject();
        BoxedJsObject jsResult = json.get("result").asJsObject();
        if (jsId.isValid() && (jsError.isValid() || jsResult.isValid())) {
            // may need to further massage the content
            int id = jsId.intValue();

            if (myAsyncResultMap.containsKey(id)) {
                // this one is a replacement
                int resultType = myAsyncResultMap.remove(id);
                switch (resultType) {
                    case RUNTIME_EVALUATE_SCRIPT: {
                        runOnEvalRunnables = true;
                        break;
                    }

                    case RUNTIME_COMPILE_SCRIPT: {
                        // information from the last Debugger.scriptParsed
                        // now just harmlessly mapped to noop
                        Integer remoteId = myAsyncIdMap.get(id);
                        if (remoteId != null) {
                            logMessage(String.format("Compile script done, request %d mapped to %d", remoteId, id));
                        }
                        break;
                    }

                    case RUNTIME_LOG_API: {
                        // accumulating log API
                        //{"result":{"result":{"type":"object","objectId":"{\"injectedScriptId\":1,\"id\":5}","className":"Object","description":"Object","preview":{"type":"object","description":"Object","lossless":true,"properties":[{"name":"x","type":"number","value":"0"},{"name":"y","type":"number","value":"79.27999999999997"}]}},"wasThrown":false},"id":36}
                        myJfxDebuggerAccess.clearArg();
                        myRuntimeEvaluateArgResult = json;
                        logMessage(String.format("Getting Runtime.evaluate param: %s", json.toString()));
                        return null;
                    }

                    case RUNTIME_LOG_STACK: {
                        logMessage(String.format("Skipping log stack trace, request %d", id));
                        return null;
                    }

                    case RUNTIME_SKIP: {
                        logMessage(String.format("Skipping request %d, %s", id, param));
                        return null;
                    }

                    case DEBUGGER_PAUSED: {
                        logMessage(String.format("Skipping debug paused request %d, %s", id, param));
                        return null;
                    }

                    case BREAK_POINT_REMOVE: {
                        String toRemove = myBreakpointsToRemove.remove(id);
                        if (toRemove != null) {
                            logMessage(String.format("Removing breakpoint request %d, %s", id, param));
                            myBreakpoints.remove(toRemove);
                        }
                    }

                    case REQUEST_JS_BRIDGE: {
                        if (mySuppressPageReloadRequest) {
                            mySuppressPageReloadRequest = false;
                        } else {
                            // able to debug our jsBridge connection if issuing pause here
                            if (myDebugOnLoad == DebugOnLoad.ON_BRIDGE_CONNECT) {
                                myDebugOnLoad = DebugOnLoad.NONE;
                                debugBreak(null);
                            }
                            logMessage(String.format("Skipping response and requesting JSBridge: %s", param));
                            myJfxDebuggerAccess.pageReloadStarted();
                            return null;
                        }
                    }

                    case DOM_GET_DOCUMENT: {
                        // {"result":{"root":{"nodeId":13,"nodeType":9,"nodeName":"#document","localName":"","nodeValue":"","childNodeCount":1,"children":[{"nodeId":14,"nodeType":1,"nodeName":"HTML","localName":"html","nodeValue":"","childNodeCount":1,"children":[{"nodeId":15,"nodeType":1,"nodeName":"HEAD","localName":"head","nodeValue":"","childNodeCount":9,"attributes":[]}],"attributes":[]}],"frameId":"0.1","documentURL":"file:///Users/vlad/src/sites/public/mn-resources/preview_2.html?2","baseURL":"file:///Users/vlad/src/sites/public/mn-resources/preview_2.html?2","xmlVersion":""}},"id":63}
                        BoxedJsObject jsRoot = json.eval("result.root").asJsObject();

                        if (jsRoot.isValid()) {
                            BoxedJsArray jsChildren = jsRoot.get("children").asJsArray();
                            int parentId = jsRoot.getJsNumber("nodeId").asJsNumber().intValue();

                            logMessage(String.format("Got DOM Root of node: %d", parentId));

                            if (addNodeChildren(parentId, jsChildren, 0, null, 0)) {
                                logMessage(String.format("Adding DOM Root node: %d", parentId));
                                myNodeIdMap.put(parentId, jsRoot);
                                myRootNodeId = parentId;
                            } else {
                                // did not add
                                logMessage(String.format("Did not add DOM Root node: %d, %s", parentId, param));
                            }
                        }
                    }
                }
            }

            // Request:
            // {"id":411,"method":"Debugger.setBreakpointByUrl","params":{"lineNumber":16,"url":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js","columnNumber":0,"condition":""}}
            // Response:
            // {"result":{"breakpointId":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:16:0","locations":[{"scriptId":"183","lineNumber":16,"columnNumber":0}]},"id":162}
            // Request:
            // {"id":454,"method":"Debugger.removeBreakpoint","params":{"breakpointId":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:16:0"}}
            // Response:
            // {"result":{},"id":177}

            // Request:
            // {"id":449,"method":"Debugger.getPossibleBreakpoints","params":{"start":{"scriptId":"220","lineNumber":14,"columnNumber":0},"end":{"scriptId":"220","lineNumber":15,"columnNumber":0},"restrictToFunction":false}}
            // Response: Need to send back break points or at least to remove all break points before resuming for disconnect. Otherwise the sucker stops waiting for a response
            // {"error":{"code":-32601,"message":"'Debugger.getPossibleBreakpoints' was not found"},"id":174}
            // see if break point created, we will remember it
            try {
                BoxedJsString breakpointId = jsResult.getJsString("breakpointId");
                if (breakpointId.isValid()) {
                    myBreakpoints.put(breakpointId.getString(), param);
                }
            } catch (Throwable throwable) {
                String message = throwable.getMessage();
                logMessage("Exception on getting breakpointId" + message);
            }

            // change id to what the remote expects if we inserted or stripped some calls
            Integer remoteId = myAsyncIdMap.remove(id);
            if (remoteId != null) {
                json.put("id", remoteId);
                logMessage(String.format("request %d mapped back to remote %d", id, remoteId));
                changed = true;
            }
        }

        if (changed) {
            changedParam = json.toString();
        }

        if (runOnEvalRunnables && myOnEvalDoneRunnable != null) {
            // schedule the next request
            String finalChangedParam = changedParam;
            final Runnable runnable = myOnEvalDoneRunnable;
            myOnEvalDoneRunnable = null;

            yieldDebugger(() -> {
                myWaitingForEvaluateScript = false;
                runnable.run();
                if (myCallback != null) {
                    myCallback.call(finalChangedParam);
                }
            });
        } else {
            myWaitingForEvaluateScript = false;
            if (myCallback != null) {
                myCallback.call(changedParam);
            }
        }

        return null;
    }

    boolean addNodeChildren(int parentId, @NotNull BoxedJsArray jsChildren, int removedNode, @Nullable BoxedJsObject jsInsertedNode, int afterSibling) {
        if (jsChildren.isValid()) {
            int iMax = jsChildren.size();
            int offset = 0;
            int previousNodeId = 0;

            if (jsInsertedNode == null || !jsInsertedNode.isValid()) {
                afterSibling = 0;
                jsInsertedNode = null;
            }

            for (int i = 0; i < iMax; i++) {
                BoxedJsObject jsNode = jsChildren.getJsObject(i + offset);
                BoxedJsNumber jsNodeId = jsNode.getJsNumber("nodeId");
                if (jsNodeId.isValid()) {
                    int nodeId = jsNodeId.intValue();
                    if (removedNode == nodeId) {
                        jsChildren.remove(i + offset);
                        offset--;
                    } else if (jsInsertedNode != null && afterSibling == previousNodeId) {
                        jsNode.put("parentId", parentId);
                        jsNode.put("ordinal", i + offset);

                        nodeId = jsInsertedNode.getJsNumber("nodeId").intValue();
                        myNodeIdMap.put(nodeId, jsInsertedNode);
                        jsChildren.add(i + offset, jsInsertedNode);

                        offset++;
                        // now recurse to add node's children
                        addNodeChildren(nodeId, jsNode.getJsArray("children"), 0, null, 0);
                    } else {
                        jsNode.put("parentId", parentId);
                        jsNode.put("ordinal", i + offset);
                        myNodeIdMap.put(nodeId, jsNode);

                        // now recurse to add node's children
                        addNodeChildren(jsNodeId.intValue(), jsNode.getJsArray("children"), 0, null, 0);
                    }

                    previousNodeId = nodeId;
                }
            }

            if (jsInsertedNode != null && afterSibling == previousNodeId) {
                BoxedJsObject jsNode = jsInsertedNode;
                BoxedJsNumber jsNodeId = jsNode.get("nodeId").asJsNumber();
                if (jsNodeId.isValid()) {
                    jsNode.put("parentId", parentId);
                    jsNode.put("ordinal", iMax + offset);
                    myNodeIdMap.put(jsNodeId.intValue(), jsNode);

                    // now recurse to add node's children
                    addNodeChildren(jsNodeId.intValue(), jsNode.getJsArray("children"), 0, null, 0);
                }
            }

            BoxedJsObject jsParentParams = myNodeIdMap.get(parentId);
            if (jsParentParams == null) {
                jsParentParams = BoxedJson.boxedFrom(String.format("{\"nodeId\":%d }", parentId));
            }

            // save updated details for the node
            jsParentParams.put("children", jsChildren);
            jsParentParams.put("childCount", jsChildren.size());
            myNodeIdMap.put(parentId, jsParentParams);
            return true;
        }
        return false;
    }

    @Override
    public void sendMessage(final String message) {
        // pre-process messages here and possibly filter/add other messages
        String changedMessage = message;
        BoxedJsObject json = BoxedJson.boxedFrom(message);
        boolean changed = false;
        String evalScript = null;

        final BoxedJsString jsonMethod = json.getJsonString("method");
        final BoxedJsNumber jsId = json.getJsonNumber("id");
        final int id = jsId.isValid() ? jsId.intValue() : 0;

        if (jsonMethod.isValid()) {
            String method = jsonMethod.getString();
            switch (method) {
                case "Runtime.compileScript": {
                    // change to harmless crap and fake the response
                    json = BoxedJson.boxedFrom(String.format("{\"id\":%s,\"method\":\"Runtime.enable\"}", jsId.intValue(myDebuggerId)));
                    changed = true;
                    myAsyncResultMap.put(myDebuggerId, RUNTIME_COMPILE_SCRIPT);
                    logMessage(String.format("Faking compileScript, request %d", jsId.intValue()));
                    break;
                }

                case "Runtime.evaluate": {
                    // grab the evaluate expression and eval it so we get the right context for the call
                    // then run this and pass it to dev tools
                    // {"id":250,"method":"Runtime.evaluate","params":{"expression":"window.__MarkdownNavigatorArgs.getConsoleArg()","objectGroup":"console","includeCommandLineAPI":true,"silent":false,"contextId":4,"returnByValue":false,"generatePreview":true,"userGesture":true,"awaitPromise":false}}
                    BoxedJsString jsEvalExpression = json.evalJsString("params.expression");
                    BoxedJsValue jsAwaitPromise = json.evalJsBoolean("params.awaitPromise");
                    BoxedJsValue jsSilent = json.evalJsBoolean("params.silent");
                    BoxedJsValue jsUserGesture = json.evalJsBoolean("params.userGesture");
                    BoxedJsValue jsReturnByValue = json.evalJsBoolean("params.returnByValue");
                    BoxedJsNumber jsContextId = json.evalJsNumber("params.contextId");
                    BoxedJsValue jsIncludeConsoleAPI = json.evalJsBoolean("params.includeCommandLineAPI");

                    boolean isPageContext = false;
                    if (jsContextId.isValid()) {
                        int contextId = jsContextId.intValue();
                        if (myOldPageContextIds.contains(contextId)) {
                            contextId = myLastPageContextId;
                        }
                        isPageContext = contextId == myLastPageContextId;
                    }

                    // lets make sure we can properly execute it
                    if (isPageContext
                            && jsEvalExpression.isValid()
                            && jsSilent.isFalse()
                            && jsAwaitPromise.isFalse()
                            && jsReturnByValue.isFalse()
                            && jsId.isValid()
                    ) {

                        final String evalExpression;
                        if (jsIncludeConsoleAPI.isTrue()) {
                            String expression = jsEvalExpression.getString();
                            evalExpression = String.format("with (__api) { %s }", expression);
                        } else {
                            evalExpression = jsEvalExpression.getString();
                        }

                        // {"result":{"result":{"type":"undefined"},"wasThrown":false},"id":38}
                        // {"result":{"result":{"type":"object","objectId":"{\"injectedScriptId\":2,\"id\":116}","className":"Object","description":"Object","preview":{"type":"object","description":"Object","lossless":false,"properties":[{"name":"setEventHandledBy","type":"function","value":""},{"name":"getState","type":"function","value":""},{"name":"setState","type":"function","value":""},{"name":"toggleTask","type":"function","value":""},{"name":"onJsBridge","type":"function","value":""}]}},"wasThrown":false},"id":56}
                        Object resultObject = myJfxDebuggerAccess.eval(evalExpression);

                        // now we get the type for the return result
                        BoxedJsObject jsResult = getArgParam(resultObject);
                        if (jsResult != null) {
                            jsResult.evalSet("id", jsId);
                            if (resultObject instanceof JSException) {
                                // change message to error
                                // {"result":{"result":{"type":"object","objectId":"{\"injectedScriptId\":4,\"id\":350}","subtype":"error","className":"ReferenceError","description":"ReferenceError: Can't find variable: b"},"wasThrown":true},"id":119}
                                BoxedJsObject jsResultResult = jsResult.evalJsObject("result.result");
                                jsResultResult.evalSet("subType", "error");
                                String errorMsg = ((JSException) resultObject).getMessage();
                                int pos = errorMsg.indexOf(':');
                                if (pos > 0) {
                                    jsResultResult.evalSet("className", errorMsg.substring(0, pos)); // set the class name
                                }
                                jsResultResult.evalSet("description", errorMsg);
                                jsResultResult.remove("preview");
                                jsResult.evalSet("result.wasThrown", true);
                            }
                            // this is what the real debugger responds with when getting an exception in the evaluate
                            // {"result":{"result":{"type":"object","objectId":"{\"injectedScriptId\":9,\"id\":123}","subtype":"error","className":"ReferenceError","description":"ReferenceError: Can't find variable: b"},"wasThrown":true},"id":64}
                            String param = jsResult.toString();
                            logMessage(String.format("Returning emulated Runtime.evaluate result, request %d: %s", jsId.intValue(), param));
                            // send to dev tools
                            if (myCallback != null) {
                                myCallback.call(param);
                            }
                        }
                        return;
                    } else {
                        // execute old code which does not give the right stack frame but won't mess up the debugger either
                        myAsyncResultMap.put(myDebuggerId, RUNTIME_EVALUATE_SCRIPT);
                        myWaitingForEvaluateScript = true;
                        logMessage(String.format("Waiting for evaluateScript, request %d", jsId.intValue()));
                    }
                    break;
                }

                case "Debugger.pause": {
                    evalScript = EMPTY_EVAL_SCRIPT;
                    myDebuggerState = DebuggerState.PAUSED;
                    break;
                }

                case "Debugger.stepOver": {
                    myDebuggerState = DebuggerState.STEP_OVER;
                    break;
                }
                case "Debugger.stepInto": {
                    myDebuggerState = DebuggerState.STEP_INTO;
                    break;
                }
                case "Debugger.stepOut": {
                    myDebuggerState = DebuggerState.STEP_OUT;
                    break;
                }
                case "Debugger.resume": {
                    myDebuggerState = DebuggerState.RUNNING;
                    break;
                }
                case "Page.reload": {
                    // need to make sure debugger is not paused
                    if (myPageReloadStarted) {
                        return;
                    }
                    myPageReloadStarted = true;
                    break;
                }

                // TODO: need to figure out what the correct response to this would be
                // probably a list of break point ids
                // Request:
                // {"id":449,"method":"Debugger.getPossibleBreakpoints","params":{"start":{"scriptId":"220","lineNumber":14,"columnNumber":0},"end":{"scriptId":"220","lineNumber":15,"columnNumber":0},"restrictToFunction":false}}
                // Response: Need to send back break points or at least to remove all break points before resuming for disconnect. Otherwise the sucker stops waiting for a response
                // {"error":{"code":-32601,"message":"'Debugger.getPossibleBreakpoints' was not found"},"id":174}
                // however WebView does send this when it parses a script:
                // {"method":"Debugger.breakpointResolved","params":{"breakpointId":"http://localhost/mn-resources/console-test.js:31:0","location":{"scriptId":"288","lineNumber":31,"columnNumber":0}}}
                case "Debugger.setBreakpointByUrl": {
                    // nothing to do, we grab all created, however if one already exists the effing debugger responds with an
                    // error instead of returning the pre-existing id. Typical Java mindset.
                    // Request:
                    // {"id":411,"method":"Debugger.setBreakpointByUrl","params":{"lineNumber":16,"url":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js","columnNumber":0,"condition":""}}
                    // Response:
                    // {"result":{"breakpointId":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:16:0","locations":[{"scriptId":"183","lineNumber":16,"columnNumber":0}]},"id":162}
                    BoxedJsObject params = json.eval("params").asJsObject();
                    BoxedJsNumber jsColumnNumber = params.getJsNumber("columnNumber");
                    BoxedJsNumber jsLineNumber = params.getJsNumber("lineNumber");
                    if (jsLineNumber.isValid() && jsColumnNumber.isValid()) {
                        int lineNumber = jsLineNumber.intValue();
                        int columnNumber = jsColumnNumber.intValue();
                        String url = params.get("url").asJsString().getString();
                        if (!url.isEmpty() && lineNumber > 0) {
                            String breakPointId = String.format("%s:%d:%d", url, lineNumber, columnNumber);
                            for (String key : myBreakpoints.keySet()) {
                                if (key.equals(breakPointId)) {
                                    // this one is it
                                    BoxedJsObject jsResult = BoxedJson.boxedFrom(myBreakpoints.get(key));
                                    jsResult.evalSet("id", json.get("id").asJsNumber());
                                    if (myCallback != null) {
                                        myCallback.call(jsResult.toString());
                                    }
                                    return;
                                }
                            }
                        }
                    }
                    break;
                }

                case "Debugger.removeBreakpoint": {
                    // Request:
                    // {"id":454,"method":"Debugger.removeBreakpoint","params":{"breakpointId":"file:///Users/vlad/src/sites/public/mn-resources/admonition.js:16:0"}}
                    // Response:
                    // {"result":{},"id":177}
                    BoxedJsString jsBreakpointId = json.eval("params.breakpointId").asJsString();
                    if (jsBreakpointId.isValid()) {
                        // we will remove it on the response, if it is in out list
                        if (myBreakpoints.containsKey(jsBreakpointId.getString())) {
                            myAsyncResultMap.put(myDebuggerId, BREAK_POINT_REMOVE);
                            myBreakpointsToRemove.put(myDebuggerId, jsBreakpointId.getString());
                        }
                    }
                    break;
                }

                case "DOM.getDocument": {
                    // Request:
                    // {"id":63,"method":"DOM.getDocument"}
                    // Response:
                    // {"result":{"root":{"nodeId":13,"nodeType":9,"nodeName":"#document","localName":"","nodeValue":"","childNodeCount":1,"children":[{"nodeId":14,"nodeType":1,"nodeName":"HTML","localName":"html","nodeValue":"","childNodeCount":1,"children":[{"nodeId":15,"nodeType":1,"nodeName":"HEAD","localName":"head","nodeValue":"","childNodeCount":9,"attributes":[]}],"attributes":[]}],"frameId":"0.1","documentURL":"file:///Users/vlad/src/sites/public/mn-resources/preview_2.html?2","baseURL":"file:///Users/vlad/src/sites/public/mn-resources/preview_2.html?2","xmlVersion":""}},"id":63}
                    myAsyncResultMap.put(myDebuggerId, DOM_GET_DOCUMENT);
                    break;
                }

                case "Overlay.setPausedInDebuggerMessage": {
                    // Request:
                    // {"id":108,"method":"Overlay.setPausedInDebuggerMessage"}
                    // Response:
                    // {"result":{},"id":177}
                    if (myDebugger != null && myDebugger.isEnabled() && jsId.isValid()) {
                        int responseId = jsId.intValue();
                        yieldDebugger(() -> {
                            call(String.format("{\"result\":{},\"id\":%d}", responseId));
                            // we now figure out the selector and invoke js helper
                            //myJfxDebuggerAccess.eval("");
                        });
                    }

                    return;
                }

                case "Overlay.highlightNode": {
                    // Request:
                    //  {"id":43,"method":"Overlay.highlightNode","params":{"highlightConfig":{"showInfo":true,"showRulers":false,"showExtensionLines":false,"contentColor":{"r":111,"g":168,"b":220,"a":0.66},"paddingColor":{"r":147,"g":196,"b":125,"a":0.55},"borderColor":{"r":255,"g":229,"b":153,"a":0.66},"marginColor":{"r":246,"g":178,"b":107,"a":0.66},"eventTargetColor":{"r":255,"g":196,"b":196,"a":0.66},"shapeColor":{"r":96,"g":82,"b":177,"a":0.8},"shapeMarginColor":{"r":96,"g":82,"b":127,"a":0.6},"displayAsMaterial":true,"cssGridColor":{"r":75,"g":0,"b":130}},"nodeId":2}}
                    // Response:
                    // {"result":{},"id":177}
                    if (myDebugger != null && myDebugger.isEnabled() && jsId.isValid()) {
                        int responseId = jsId.intValue();
                        BoxedJsObject jsParams = json.get("params").asJsObject();
                        BoxedJsNumber jsNodeId = jsParams.getJsonNumber("nodeId");
                        if (jsNodeId.isValid()) {
                            // build path based on index of child in parent
                            StringBuilder sb = new StringBuilder();
                            sb.append("[0");
                            addNodeSelectorPath(jsNodeId.intValue(), sb);
                            sb.append(']');
                            String nodeSelectorPath = sb.toString();

                            // validate that we get the same node after traversing the path from document
                            if (nodeSelectorPath.length() >= 5) {
                                // print out the nodes in the path
                                String path = nodeSelectorPath.substring(3, nodeSelectorPath.length() - 1);
                                String[] parts = path.split(",");

                                int iMax = parts.length;
                                int nodeId = myRootNodeId;

                                for (int i = iMax; i-- > 0; ) {
                                    int index = Integer.parseInt(parts[i]);
                                    BoxedJsObject jsParentParams = myNodeIdMap.get(nodeId);
                                    if (jsParentParams != null && jsParentParams.isValid()) {
                                        BoxedJsArray jsChildren = jsParentParams.getJsonArray("children");
                                        if (jsChildren.isValid()) {
                                            BoxedJsObject jsNode = jsChildren.getJsonObject(index);
                                            if (jsNode.isValid() && jsNode.getJsonNumber("nodeId").isValid()) {
                                                nodeId = jsNode.getInt("nodeId");
                                            } else {
                                                logMessage(String.format("Invalid child at %d for node %d: %s", index, nodeId, jsParentParams));
                                            }
                                        } else {
                                            logMessage(String.format("Invalid node children for %d: %s", nodeId, jsParentParams));
                                        }
                                    } else {
                                        logMessage(String.format("No node information for %d in path %s", nodeId, path));
                                    }
                                }

                                if (nodeId != jsNodeId.intValue()) {
                                    logMessage(String.format("Wrong node %d for requested %d from path %s", nodeId, jsNodeId.intValue(), path));
                                }

                                yieldDebugger(() -> {
                                    call(String.format("{\"result\":{},\"id\":%d}", responseId));
                                    // we now figure out the selector and invoke js helper
                                    BoxedJsObject paramJson = argParamJson();
                                    // {"id":250,"method":"Runtime.evaluate","params":{"expression":"window.__MarkdownNavigatorArgs.getConsoleArg()","objectGroup":"console","includeCommandLineAPI":true,"silent":false,"contextId":4,"returnByValue":false,"generatePreview":true,"userGesture":true,"awaitPromise":false}}
                                    //final String argScript = "window.injectedCode = { tests: () => { return \"I'm injected\"; } };";
                                    final String argScript = "markdownNavigator.highlightNode(" + nodeSelectorPath + ")";
                                    paramJson.evalSet("id", myDebuggerId)
                                            .evalSet("params.expression", argScript)
                                            .evalSet("params.includeCommandLineAPI", true)
                                            .evalSet("params.objectGroup", "console")
                                            .evalSet("params.userGesture", false)
                                            .evalSet("params.generatePreview", false)
                                            .evalSet("params.contextId", myLastPageContextId)
                                    ;

                                    logMessage(String.format("invoking highlightNode helper nodeId %d, request %d", jsNodeId.intValue(), myDebuggerId));

                                    myAsyncResultMap.put(myDebuggerId, RUNTIME_SKIP);
                                    myDebuggerId++;
                                    debuggerSend(paramJson.toString(), null);
                                });
                            } else if (!nodeSelectorPath.equals("[0]")) {
                                logMessage(String.format("Invalid path %s, for nodeId %d", nodeSelectorPath, jsNodeId.intValue()));
                            }
                        }
                    }

                    return;
                }

                case "Overlay.hideHighlight": {
                    // Request:
                    //  {"id":64,"method":"Overlay.hideHighlight"}
                    // Response:
                    // {"result":{},"id":177}
                    if (myDebugger != null && myDebugger.isEnabled() && jsId.isValid()) {
                        int responseId = jsId.intValue();
                        yieldDebugger(() -> {
                            call(String.format("{\"result\":{},\"id\":%d}", responseId));
                            // call jsHelper for that
                            BoxedJsObject paramJson = argParamJson();
                            //final String argScript = "window.injectedCode = { tests: () => { return \"I'm injected\"; } };";
                            final String argScript = "markdownNavigator.hideHighlight()";
                            paramJson.evalSet("id", myDebuggerId)
                                    .evalSet("params.expression", argScript)
                                    .evalSet("params.includeCommandLineAPI", true)
                                    .evalSet("params.objectGroup", "console")
                                    .evalSet("params.userGesture", false)
                                    .evalSet("params.generatePreview", false)
                                    .evalSet("params.contextId", myLastPageContextId)
                            ;

                            logMessage(String.format("invoking setHideHighlight helper, request %d", myDebuggerId));
                            myAsyncResultMap.put(myDebuggerId, RUNTIME_SKIP);
                            myDebuggerId++;
                            debuggerSend(paramJson.toString(), null);
                        });
                    }

                    return;
                }
            }
        }

        if (id > 0) {
            if (id != myDebuggerId) {
                // need to change ids
                json.put("id", myDebuggerId);
                changed = true;

                // will need to re-map id on result when it is ready
                myAsyncIdMap.put(myDebuggerId, id);
                logMessage(String.format("Mapping request %d to %d", id, myDebuggerId));
            }

            myDebuggerId++;
        }

        if (!myOldPageContextIds.isEmpty()) {
            // change old page context id to latest
            String contextIdPath = "params.contextId";
            BoxedJsNumber contextIdNumber = json.eval(contextIdPath).asJsNumber();
            Integer contextId = contextIdNumber.isValid() ? contextIdNumber.intValue() : null;

            if (contextId == null) {
                contextIdPath = "params.executionContextId";
                contextIdNumber = json.eval(contextIdPath).asJsNumber();
                contextId = contextIdNumber.isValid() ? contextIdNumber.intValue() : null;
            }

            if (contextId != null && myOldPageContextIds.contains(contextId)) {
                json.evalSet(contextIdPath, myLastPageContextId);
                logMessage(String.format("Mapping old context id %d to %d", contextId, myLastPageContextId));
                changed = true;
            }
        }

        if (changed) {
            changedMessage = json.toString();
        }

        if (myDebugger != null && myDebugger.isEnabled()) {
            debuggerSend(changedMessage, null);
            if (evalScript != null) {
                myJfxDebuggerAccess.eval(evalScript);
            }
        }
    }

    void addNodeSelectorPath(int nodeId, StringBuilder out) {
        if (nodeId != myRootNodeId) {
            BoxedJsObject jsNode = myNodeIdMap.getOrDefault(nodeId, BoxedJsValue.HAD_NULL_OBJECT);
            if (jsNode.isValid()) {
                // add node selector to list, these are reverse order
                // format is: ,ordinal in parent
                BoxedJsNumber jsParentId = jsNode.get("parentId").asJsNumber();
                BoxedJsNumber jsOrdinal = jsNode.get("ordinal").asJsNumber();
                if (jsParentId.isValid() && jsOrdinal.isValid()) {
                    out.append(',').append(jsOrdinal.intValue());
                    addNodeSelectorPath(jsParentId.intValue(), out);
                }
            }
        }
    }

    @Override
    public Callback<String, Void> getMessageCallback() {
        return myCallback;
    }

    @Override
    public void setMessageCallback(final Callback<String, Void> callback) {
        myCallback = callback;
    }

    @Override
    public boolean isEnabled() {
        myIsEnabled = myDebugger != null && myDebugger.isEnabled();
        return myIsEnabled;
    }

    @Override
    public void setEnabled(final boolean enabled) {
        if (myDebugger != null) {
            myIsEnabled = enabled;
            myDebugger.setEnabled(myIsEnabled);
        }
    }
}
