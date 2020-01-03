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

import org.jetbrains.annotations.NotNull;

public abstract class LogHandler {
    // NOTE: this avoids conflicts with loading Logger in IntelliJ, set LogHandler.LOG_HANDLER by application
    public static LogHandler LOG_HANDLER = LogHandler.NULL;

    public static LogHandler getInstance() {
        return LOG_HANDLER;
    }

    public abstract void trace(@NotNull String message);

    public abstract void trace(@NotNull String message, @NotNull Throwable t);

    public abstract void trace(@NotNull Throwable t);

    public abstract boolean isTraceEnabled();

    public abstract void debug(@NotNull String message);

    public abstract void debug(@NotNull String message, @NotNull Throwable t);

    public abstract void debug(@NotNull Throwable t);

    public abstract void error(@NotNull String message);

    public abstract void error(@NotNull String message, @NotNull Throwable t);

    public abstract void error(@NotNull Throwable t);

    public abstract void info(@NotNull String message);

    public abstract void info(@NotNull String message, @NotNull Throwable t);

    public abstract void info(@NotNull Throwable t);

    public abstract boolean isDebugEnabled();

    public abstract void warn(@NotNull String message);

    public abstract void warn(@NotNull String message, @NotNull Throwable t);

    public abstract void warn(@NotNull Throwable t);

    final public static LogHandler NULL = new LogHandler() {
        public @Override
        void trace(@NotNull String message) {}

        public @Override
        void trace(@NotNull String message, @NotNull Throwable t) {}

        public @Override
        void trace(@NotNull Throwable t) {}

        public @Override
        boolean isTraceEnabled() {return false;}

        public @Override
        void debug(@NotNull String message) {}

        public @Override
        void debug(@NotNull String message, @NotNull Throwable t) {}

        public @Override
        void debug(@NotNull Throwable t) {}

        public @Override
        void error(@NotNull String message) {}

        public @Override
        void error(@NotNull String message, @NotNull Throwable t) {}

        public @Override
        void error(@NotNull Throwable t) {}

        public @Override
        void info(@NotNull String message) {}

        public @Override
        void info(@NotNull String message, @NotNull Throwable t) {}

        public @Override
        void info(@NotNull Throwable t) {}

        public @Override
        boolean isDebugEnabled() {return false;}

        public @Override
        void warn(@NotNull String message) {}

        public @Override
        void warn(@NotNull String message, @NotNull Throwable t) {}

        public @Override
        void warn(@NotNull Throwable t) {}
    };
}
