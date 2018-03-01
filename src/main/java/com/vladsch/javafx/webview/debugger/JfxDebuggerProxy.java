/*
 * Copyright (c) 2015-2018 Vladimir Schneider <vladimir.schneider@gmail.com>, all rights reserved.
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

import netscape.javascript.JSObject;
import org.jetbrains.annotations.Nullable;

public interface JfxDebuggerProxy {
    void onOpen();
    void onClosed(int code, String reason, boolean remote);
    void log(String type, final long timestamp, final JSObject args);
    void debugBreak();
    void pageReloading();
    void reloadPage();
    void setDebugOnLoad(DebugOnLoad debugOnLoad);
    DebugOnLoad getDebugOnLoad();
    boolean isDebuggerPaused();
    void releaseDebugger(final boolean shuttingDown, @Nullable Runnable runnable);
    void removeAllBreakpoints(@Nullable Runnable runAfter);
    void pageLoadComplete();

    enum DebugOnLoad {
        NONE,
        ON_BRIDGE_CONNECT,
        ON_INJECT_HELPER,
    }

}
