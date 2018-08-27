## JavaFx WebView Debugger

[TOC levels=3,6]: # "Version History"

### Version History
- [0.6.0](#060)
- [0.5.12](#0512)
- [0.5.10](#0510)
- [0.5.8](#058)
- [0.5.6](#056)


### 0.6.0

* Fix: add debugger argument to `DevToolsDebuggerJsBridge` constructor to allow for Java 9 or
  other java version specific debugger use.

### 0.5.12

* Fix: dev tools console commands were not properly converted from JSON to strings, and did not
  unescape the `\"` causing exception during eval.

### 0.5.10

* boxed-json fix incorporated.

### 0.5.8

* Add: `JfxDebuggerAccess.onConnectionOpen()` to call back on open connection to debugger
* Add: `JfxDebuggerAccess.onConnectionClosed()` to call back on close connection to debugger
* Fix: page reload in `DevToolsDebuggerJsBridge` Not on FX application thread exception.

### 0.5.6

First working maven version

