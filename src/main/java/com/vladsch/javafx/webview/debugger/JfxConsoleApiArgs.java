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

import com.vladsch.boxed.json.BoxedJsValue;

public class JfxConsoleApiArgs {
    private final Object[] myArgs;
    private final String myLogType;
    private final BoxedJsValue[] myJsonParams;
    private long myTimestamp;
    private String myPausedParam;

    public JfxConsoleApiArgs(final Object[] args, final String logType, final long timestamp) {
        myArgs = args;
        myLogType = logType;
        myJsonParams = new BoxedJsValue[args.length];
        myTimestamp = timestamp;
        myPausedParam = null;
    }

    public String getPausedParam() {
        return myPausedParam;
    }

    public void setPausedParam(final String pausedParam) {
        myPausedParam = pausedParam;
    }

    public void setParamJson(int paramIndex, BoxedJsValue paramJson) {
        myJsonParams[paramIndex] = paramJson;
    }

    public void clearAll() {
        int iMax = myArgs.length;
        for (int i = 0; i < iMax; i++) {
            myArgs[i] = null;
            myJsonParams[i] = null;
        }
    }

    public long getTimestamp() {
        return myTimestamp;
    }

    public Object[] getArgs() {
        return myArgs;
    }

    public String getLogType() {
        return myLogType;
    }

    public BoxedJsValue[] getJsonParams() {
        return myJsonParams;
    }
}
