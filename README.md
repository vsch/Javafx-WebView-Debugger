# JavaFX WebView Debugger 
##### Via WebSocket connection to Google Chrome Dev Tools

[![Build status](https://travis-ci.org/vsch/Javafx-WebView-Debugger.svg?branch=master)](https://travis-ci.org/vsch/Javafx-WebView-Debugger)
[![Maven Central status](https://img.shields.io/maven-central/v/com.vladsch.javafx-webview-debugger/javafx-webview-debugger.svg)](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.vladsch.javafx-webview-debugger%22)

Here is a teaser screenshot of dev tools running with JavaFX WebView, showing off the console
logging from scripts, with caller location for one click navigation to source:

![DevTools](images/DevTools.png)


### Quick Start

For Maven:

```xml
<dependency>
    <groupId>com.vladsch.javafx-webview-debugger</groupId>
    <artifactId>javafx-webview-debugger</artifactId>
    <version>LATEST</version>
</dependency>
```

A WebViewSample application modified for debugging will be added shortly.

#### Credit where credit is due

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

I will be creating an example project based on Oracle's WebViewSample app with debugging
implemented. It is easier to create and see a working example than to write a lot of words
explaining how to do it. This will come later.

If you are working with JavaFX WebView scripts and need this functionality ASAP, please contact
me and I will see if we can make this happen sooner than later. Knowing that someone needs this
will provide a little motivation to getting it done. A little motivation can go a long way.

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

**The rest of this file is mostly a copy of one in [mohamnag/javafx_webview_debugger] project**
with some changes to reflect being able to run multiple debugging sessions on a single server.
It will be updated in the next few days.

Using debugger is done in three main steps:

1. Starting debug server
2. Connecting chrome debugger
3. Clean up

### Starting debug server

If you are using Java up to version 8, to enable debugging on a chosen WebView, you have to add
following code using its `webEngine`:

```java
DevToolsDebuggerServer devToolsDebuggerServer(webEngine.impl_getDebugger(), 51742, 1);
```

`WebEngine.impl_getDebugger()` is an internal API and is subject to change which is happened in
Java 9. So if you are using Java 9, you need to use following code instead to start the debug
server:

```java
Class webEngineClazz = WebEngine.class;

Field debuggerField = webEngineClazz.getDeclaredField("debugger");
debuggerField.setAccessible(true);

Debugger debugger = (Debugger) debuggerField.get(webView.getEngine());
DevToolsDebuggerServer devToolsDebuggerServer.startDebugServer(debugger, 51742, 1);
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

### Connecting chrome debugger

Then you only need to open following URL in a chrome browser:

```
chrome-devtools://devtools/bundled/inspector.html?ws=localhost:51742/?1
```

Where 1, 2, 3, ... is the session number you passed to the `DevToolsDebuggerServer` constructor
and 51742 is the port passed to the debug server.

**NOTE**: since the web-socket server is a shared static instance, all sessions will have to use
the same port number.

### Clean up

For a proper shutdown you have to call following when exiting a session or turning off debugging
for it:

```java
devToolsDebuggerServer.stopDebugServer();
```

This will disconnect the JavaFX WebView debugger from its session and this instance of
`DevToolsDebuggerServer` will no longer be usable for debugging. To re-start debugging on the
same session number, create a new instance.

To shut down the web-socket server after all sessions have been stopped call:

```java
DevToolsDebuggerServer.shutdownWebSocketServer();
```

## No Library Installation

Code is too small and will most likely need to be customized for your project. Add the two small
files to your project and modify to your needs. Add [TooTallNate/Java-WebSocket] as a
dependency.

These files were pulled from a project after I got it all working nicely. Removed IntelliJ API
related code and put them here so they can be of use to others.

<!-- 
### Maven

To use maven add this dependency to your pom.xml:

```
<dependency>
  <groupId>org.java-websocket</groupId>
  <artifactId>Java-WebSocket</artifactId>
  <version>1.3.7</version>
</dependency>
```

### Gradle

To use Gradle add the maven central repository to your repositories list :

```
mavenCentral()
```

Then you can just add the latest version to your build.

```
compile "org.java-websocket:Java-WebSocket:1.3.7"
```
 -->

JetBrains IntelliJ project files included if you need to reference them, Library is configured
and downloaded into `lib/`

## Enabling the console

JavaFX WebView does not have a console defined, so console calls inside JavaScript are not
possible by default. In order to enable JavaScript logging inside JavaFX, a bridge class and a
separate listener for JavaScript are required.

#### Bridge class

```java
public class JavaBridge {

    public void log(String text) {
        System.out.println(text);
    }

    public void error(String text) {
        System.err.println(text);
    }
}
```

It is recommended to use `System.out.println` for writing to console and avoid any custom
loggers, as they can cause the entire method to be undefined.

#### Code inside Main class

```java
webEngine.setJavaScriptEnabled(true);

webEngine.getLoadWorker().stateProperty().addListener( new ChangeListener<State>() {
    @Override
    public void changed(ObservableValue ov, State oldState, State newState) {
        if (newState == Worker.State.SUCCEEDED) {
            JSObject window = (JSObject) webEngine.executeScript("window");
            JavaBridge javaBridge = new JavaBridge();
            window.setMember("console", javaBridge); // "console" object is now known to JavaScript
        }
    }
});

```

Note that the name *console* in `window.setMember` can be arbitrary, but it is recommended to
override the console instead because many JavaScript APIs make hidden calls to the console.
Since the console is undefined by default, JavaScript will most likely stop working after that
point.

#### JavaScript example

```javascript
function initializeMap() {

    var moscowLonLat = \[37.618423, 55.751244\];

    map = new ol.Map({
        target: 'map',
        view: new ol.View({
            center: ol.proj.fromLonLat(moscowLonLat),
            zoom: 14
        })
    });
    mapLayer = new ol.layer.Tile({
        source: new ol.source.OSM()
    });

    map.addLayer(mapLayer);

    console.log("Map initialized!"); // This will appear in the JavaFX console
}
```

[IntelliJ IDEA]: http://www.jetbrains.com/idea
[Kotlin]: http://kotlinlang.org
[Markdown Navigator]: http://vladsch.com/product/markdown-navigator 
[mohamnag/javafx_webview_debugger]: https://github.com/mohamnag/javafx_webview_debugger
[TooTallNate/Java-WebSocket]: https://github.com/TooTallNate/Java-WebSocket

