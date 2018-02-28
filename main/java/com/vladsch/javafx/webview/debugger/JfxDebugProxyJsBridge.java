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

public interface JfxDebugProxyJsBridge {
    void consoleLog(String type, JSObject args);
    void println(String text);
    void print(String text);
    void pageLoadComplete();
    void setEventHandledBy(String handledBy);
    Object getState(String name);
    void setState(String name, Object state);
}
