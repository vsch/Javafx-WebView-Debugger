# JavaFX WebView Debugger

##### Via WebSocket connection to Google Chrome Dev Tools

[![Build status](https://travis-ci.org/vsch/Javafx-WebView-Debugger.svg?branch=master)](https://travis-ci.org/vsch/Javafx-WebView-Debugger)
[![Maven Central status](https://img.shields.io/maven-central/v/com.vladsch.javafx-webview-debugger/javafx-webview-debugger.svg)](https://search.maven.org/search?q=g:com.vladsch.javafx-webview-debugger)<!-- @IGNORE PREVIOUS: link -->

JavaFx WebView debugging with Chrome Dev tools is highly dependent on Google Chrome version and
JavaFx Version.

If you can debug your scripts using another environment, I would highly recommend doing that instead.
Only use JavaFx WebView based debugging when you absolutely need to debug the code under this
environment.

JavaFx I use for debugging is JetBrains OpenJDK 1.8.0u152 build 1136 which I found to be the
most stable for JavaFx WebView debugging. Other versions are more quirky and after starting
debug server they need a page reload from the context menu before connecting Chrome Dev Tools.
Otherwise, JavaFX crashes and takes the application with it.

I use Chrome version 65.0.3325.181 under OS X. Later versions work but console has no output. I
have not investigated what is causing this. You can download older Chrome versions from
**[Google Chrome Older Versions Download (Windows, Linux & Mac)](https://www.slimjet.com/chrome/google-chrome-old-version.php)**

Here is a teaser screenshot of dev tools running with JavaFX WebView, showing off the console
logging from scripts, with caller location for one click navigation to source:

![DevTools](images/DevTools.png)

### Requirements

* Java 8 (not tested with 9)
* The project is on Maven: `com.vladsch.javafx-webview-debugger`
* dependencies:
  * `org.glassfish:javax.json`
  * `org.jetbrains.annotations`
  * `com.vladsch.boxed-json`
  * `org.apache.log4j`

### Quick Start

Take a look at the [Web View Debug Sample] application for a working example. You can play with
it and debug the embedded scripts before deciding whether you want to instrument your own
application for Chrome Dev Tools debugging.

For Maven:

```xml
<dependency>
    <groupId>com.vladsch.javafx-webview-debugger</groupId>
    <artifactId>javafx-webview-debugger</artifactId>
    <version>0.7.2</version>
</dependency>
```

### Credit where credit is due

I found out about the possibility of having any debugger for JavaFX WebView in
[mohamnag/javafx_webview_debugger]. That implementation in turn was based on the solution found
by Bosko Popovic.

I am grateful to Bosko Popovic for discovering the availability and to Mohammad Naghavi creating
an implementation of the solution. Without their original effort this solution would not be
possible.

### Finally real debugging for JavaFX WebView

Working with JavaScript code in WebView was a debugging nightmare that I tried to avoid. My
excitement about having a real debugger diminished when I saw how little was available with a
bare-bones implementation due to WebView's lack of a full Dev Tools protocol. The console did
not work and none of the console api calls from scripts made it to the Dev Tools console. No
commandLineAPI for the same reason. Having to use logs for these is last century.

A proxy to get between Chrome Dev Tools and WebView solved most of the WebView debugging
protocol limitations. It also allowed to prevent conditions that caused WebView to crash and
bring the rest of the application with it. I don't mean a Java exception. I mean a core dump and
sudden exit of the application. For my use cases this is not an acceptable option.

Now the console in the debugger works as expected, with completions, evaluations and console
logging from scripts, stepping in/out/over, break points, caller location one click away and
initialization debugging to allow pausing of the script before the JSBridge to JavaScript is
established. Having a real debugger makes minced meat of script initialization issues.

The current version is the the code I use in my [IntelliJ IDEA] plugin, [Markdown Navigator].
With any functionality specific to my project added using the API of this library.

If you are working with JavaFX WebView scripts you need this functionality ASAP. Bugs that took
hours to figure out now take literally seconds to minutes without any recompilation or major log
reading. Take a look at the [Web View Debug Sample] application to see what you get and how to
add it to your code.

#### What is working

* all `console` functions: `assert`, `clear`, `count`, `debug`, `dir`, `dirxml`, `error`,
  `exception`, `group`, `groupCollapsed`, `groupEnd`, `info`, `log`, `profile`, `profileEnd`,
  `select`, `table`, `time`, `timeEnd`, `trace`, `warn`. With added extras to output to the Java
  console: `print`, `println`
* all commandLineAPI functions implemented by JavaFX WebView: `$`, `$$`, `$x`, `dir`, `dirxml`,
  `keys`, `values`, `profile`, `profileEnd`, `table`, `monitorEvents`, `unmonitorEvents`,
  `inspect`, `copy`, `clear`, `getEventListeners`, `$0`, `$_`, `$exception`
  * Some limitations do exist because `Runtime.evaluate` is simulated. Otherwise, there was no
    way to get the stack frame for any console log calls that would result from the evaluation.
    Mainly, it caused the application to exit with a core dump more often than not. I figured it
    was more important to have the caller location for console logs than for the commandLineAPI
    calls.
* No longer necessary to instrument the page to have `JSBridge` support code working. Only
  requires a page reload from WebView or Dev Tools.
* Ability to debug break on page reload to debug scripts running before JSBridge is established
* Safely stop debug session from Dev Tools or WebView side. This should have been easy but
  turned out to be the hardest part to solve. Any wrong moves or dangling references would bring
  down the whole application with a core-dump message.

#### In progress

* highlighting of elements hovered over in the element tree of dev tools. Node resolution is
  working. Kludged highlighting by setting a class on the element with translucent background
  color defined instead of using an overlay element or the proper margin, borders, padding and
  container size.

#### Not done

* Debugger paused overlay

#### Probably not doable

* profiling not available. Don't know enough about what's needed to say whether it is doable.

### JSBridge Provided Debugging Support

The missing functionality from the WebView debugger is implemented via a proxy that gets between
chrome dev tools and the debugger to fill in the blanks and to massage the conversation allowing
chrome dev tools to do their magic.

For this to work, some JavaScript and the `JSBridge` instance need to work together to provide
the essential glue. The implementation is confined to the initialization script. Most other
scripts can be oblivious to whether the `JSBridge` is established or not. Console log api is one
of the missing pieces in the WebView debugger, any console log calls made before the `JSBridge`
to Java is established **will not have caller identification** and will instead point to the
initialization code that played back the cached log calls generated before the connection was
established.

The `JSBridge` implementation also provides a mechanism for data persistence between page
reloads. It is generic enough if all the data you need to persist can be `JSON.stringify'd`
because the implementation does a call back to the WebView engine to serialize the passed in
state argument. This text will need to be inserted into the generated HTML page to allow scripts
access to their state before the `JSBridge` is hooked in. Scripts can also register for a
callback when the `JSBridge` is established.

The down side of the latter approach, is by the time this happens, WebView has already visually
updated the page. If the script is responsible for any visual modification of the page based on
persisted state then the unmodified version will flash on screen before the script is run.

Allowing scripts to get their state before `JSBridge` is established makes for smoother page
refresh.

### Getting Full Featured Debugging

This requires a little support from the Java to JavaScript bridge and the debug proxy. See the
[Web View Debug Sample] application for an example.

I have not tried it with Java 9, and suspect that the debug protocol has changed and the proxy
may not work with it without modifications.

However, these are the instructions to compile for Java 9 WebView debugger access as given by
[mohamnag/javafx_webview_debugger].

`WebEngine.impl_getDebugger()` is an internal API and is subject to change which is happened in
Java 9. So if you are using Java 9, you need to use following code instead to start the debug
server.

:information_source: This code is now part of `DevToolsDebugProxy` and if it works on the JRE then debugging will
be available. Otherwise, it will not.

```java
Class webEngineClazz = WebEngine.class;

Field debuggerField = webEngineClazz.getDeclaredField("debugger");
debuggerField.setAccessible(true);

Debugger debugger = (Debugger) debuggerField.get(webView.getEngine());
DevToolsDebuggerServer devToolsDebuggerServer.startDebugServer(debugger, WEBVIEW_DEBUG_PORT, 0, null, null);
```

For this to work, you have to pass this parameter to Java compiler: `--add-exports
javafx.web/com.sun.javafx.scene.web=ALL-UNNAMED`.

As examples, this can be done for Maven as follows:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.7.0</version>
    <configuration>
        <source>9</source>
        <target>9</target>
        <compilerArgs>
            <arg>--add-exports</arg>
            <arg>javafx.web/com.sun.javafx.scene.web=ALL-UNNAMED</arg>
        </compilerArgs>
    </configuration>
 </plugin>
```

or for IntelliJ under **Additional command line parameters** in **Preferences > Build,
Execution, Deployment > Compiler > Java Compiler**.

[IntelliJ IDEA]: http://www.jetbrains.com/idea
[Markdown Navigator]: http://vladsch.com/product/markdown-navigator
[mohamnag/javafx_webview_debugger]: https://github.com/mohamnag/javafx_webview_debugger
[Web View Debug Sample]: https://github.com/vsch/WebViewDebugSample
[Javafx Web View Debugger Readme]: https://github.com/vsch/Javafx-WebView-Debugger/blob/master/README.md
[JavaFx WebView Debugger]: https://github.com/vsch/Javafx-WebView-Debugger
[Kotlin]: https://kotlinlang.org
[TooTallNate/Java-WebSocket]: https://github.com/TooTallNate/Java-WebSocket
[WebViewDebugSample.jar]: https://github.com/vsch/WebViewDebugSample/raw/master/WebViewDebugSample.jar

