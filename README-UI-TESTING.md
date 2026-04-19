# UI Smoke Tests (remote-robot)

Out-of-process UI smoke tests for Wedge 1 (gutter icon) and Wedge 2 (risk tool window). They drive a full sandbox IDE over HTTP via JetBrains `remote-robot 0.11.23`.

## How to run

Two terminals on the demo laptop:

```bash
# Terminal A — launches sandbox IDE with the robot-server plugin on :8082.
# Leave running; it blocks until you close the IDE.
JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew runIdeForUiTests

# Terminal B — runs the JUnit smoke tests against the sandbox.
JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew uiSmokeTest
```

The sandbox auto-opens `demo-repos/tsre-microservices`. First boot takes ~45s while the JDK indexes the project — the tests allow up to 60s for `IdeaFrame` to appear.

## Why these are NOT in `./gradlew test`

The Claude Code Stop hook runs `./gradlew test` after every agent turn. If `uiSmokeTest` ran there, the hook would hang forever waiting for a sandbox IDE that isn't running. The `uiSmokeTest` task lives in a dedicated `src/uiSmokeTest/kotlin/` source set and is only invoked manually.

## Screenshots on failure

Both smoke tests call `robot.getScreenshot()` when assertions fail and write PNGs to:

```
build/uiSmokeTest-screenshots/<label>_<timestamp>.png
```

Check there first when diagnosing a red run.

## Port already in use?

```bash
lsof -i :8082    # find any lingering runIdeForUiTests from a prior session
kill <pid>
```
