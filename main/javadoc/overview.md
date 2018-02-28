**JavaFX WebView Debugger**

Library implementing WebSocket server for JavaFX WebView debugger to Chrome Dev Tools.

Creates a comfortable debugging environment for script debugging in JavaFX WebView:

* all `console` functions: `assert`, `clear`, `count`, `debug`, `dir`, `dirxml`, `error`,
  `exception`, `group`, `groupCollapsed`, `groupEnd`, `info`, `log`, `profile`, `profileEnd`,
  `select`, `table`, `time`, `timeEnd`, `trace`, `warn`. With added extras to output to the Java
  console: `print`, `println`
* all commandLineAPI functions implemented by JavaFX WebView: `$`, `$$`, `$x`, `dir`, `dirxml`,
  `keys`, `values`, `profile`, `profileEnd`, `table`, `monitorEvents`, `unmonitorEvents`,
  `inspect`, `copy`, `clear`, `getEventListeners`, `$0`, `$_`, `$exception`
