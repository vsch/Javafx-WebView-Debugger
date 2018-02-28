/*
 *   The MIT License (MIT)
 *   <p>
 *   Copyright (c) 2018-2018 Vladimir Schneider (https://github.com/vsch)
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

import netscape.javascript.JSObject;

public class JfxDebugProxyJsBridgeDelegate implements JfxDebugProxyJsBridge {
    final private JfxDebugProxyJsBridge myBridge;

    public JfxDebugProxyJsBridgeDelegate(final JfxDebugProxyJsBridge bridge) {
        myBridge = bridge;
    }

    @Override public void consoleLog(final String type, final JSObject args) {myBridge.consoleLog(type, args);}

    @Override public void println(final String text) {myBridge.println(text);}

    @Override public void print(final String text) {myBridge.print(text);}

    @Override public void pageLoadComplete() {myBridge.pageLoadComplete();}

    @Override public void setEventHandledBy(final String handledBy) {myBridge.setEventHandledBy(handledBy);}

    @Override public Object getState(final String name) {return myBridge.getState(name);}

    @Override public void setState(final String name, final Object state) {myBridge.setState(name, state);}
}
