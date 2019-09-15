## JavaFx WebView Debugger

[TOC levels=3,6]: # "Version History"

### Version History
- [0.7.6](#076)
- [0.7.4](#074)
- [0.7.2](#072)
- [0.7.0](#070)
- [0.6.10](#0610)
- [0.6.8](#068)
- [0.6.6](#066)
- [0.6.4](#064)
- [0.6.2](#062)
- [0.6.0](#060)
- [0.5.12](#0512)
- [0.5.10](#0510)
- [0.5.8](#058)
- [0.5.6](#056)


### 0.7.6

* Fix: update to boxed json 0.5.28

### 0.7.4

* Fix: update to boxed json 0.5.24

### 0.7.2

* Fix: update to boxed json 0.5.22

### 0.7.0

* Fix: change constructor params from `Debugger` to `WebEngine` so that `DevToolsDebugProxy` is
  responsible for obtaining the debugger instance, if it can.
* Fix: change debug proxy debugger field to nullable to allow the rest of the app to work even
  if obtaining the debugger is not possible on the JRE version.

### 0.6.10

* Fix: bump up boxed-json version to 0.5.20

### 0.6.8

* Fix: bump up boxed-json version to 0.5.18

### 0.6.6

* Add: `suppressNoMarkdownException` arg to `DevToolsDebuggerJsBridge` constructor to use JS for
  connecting JsBridge which will fail silently if `markdownNavigator` variable is not defined or
  is false.

### 0.6.4

* Fix: change log error to warn in JS if using `markdownNavigator.setJsBridge()` with script load
  failure.

### 0.6.2

* Fix: change lambdas to functions to have `arguments` available (causing exception in JetBrains
  Open JDK 1.8.0_152-release-1293-b10 x86_64

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

