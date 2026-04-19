# RESEARCH — Datadog Proactive Observability + PR Risk Plugin

**Researched:** 2026-04-18
**Domain:** IntelliJ Platform 2.x plugin (Kotlin) + OpenAI Responses API agent mode
**Confidence:** HIGH on sections 2, 3a, 4a; MEDIUM on 3b; LOW on section 1 (see the big red finding)

---

## Summary

Five focused findings, implementation-ready:

1. **The upstream `DataDog/datadog-for-intellij-platform` repo has no source code.** It's a 4-file marketing/issue-tracker repo. There is nothing to fork. Pivot to the **JetBrains `intellij-platform-plugin-template`** as the base — it already gives us 90% of what the PRD claimed the Datadog fork would give us.
2. IntelliJ Platform Gradle Plugin **2.14.0** targeting IntelliJ IDEA **2025.2.6.1** Community is the stable, current choice. `platformVersion` and plugin dependencies go in `gradle.properties`, not hardcoded in `build.gradle.kts`.
3. For testing, split the strategy: **`BasePlatformTestCase` / `CodeInsightTestFixture`** covers LineMarkerProvider + action registration + service loading (fast, headless, runs in `./gradlew test`). **remote-robot** only for one smoke test that clicks "Analyze PR Risk" and asserts the tool window populates — and only if time at H30+ permits. Default: skip remote-robot; ship with platform tests + manual UAT.
4. Official `openai-java` SDK (4.32.0) *does* support the Responses API with tools, but examples are Java-only and the DSL is verbose. For a 48h hackathon: **roll our own thin OkHttp + Moshi client** (≤80 lines). Justification: the JSON shape is tiny (5 tools, single agent loop), we need the raw `reasoning_trace` for the demo anyway, and a hand-rolled client keeps the tool-dispatch code readable for judges.
5. For launching the sandbox with `tsre-microservices` pre-opened: pass the project path as an argument to the IDE startup via the `args` property on the `runIde` task. Clone script runs before first `runIde`.

**Primary recommendation:** Scrap the "fork upstream Datadog plugin" approach. Use JetBrains plugin template as the skeleton. Start coding off it **within the first hour** — do not spend H0–H2 spelunking the non-existent Datadog source.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Gutter icon + tooltip | IDE Editor (LineMarkerProvider EP) | — | Runs in EDT/highlighting pass; IntelliJ owns the render surface |
| Fixture data loading | Project-level IntelliJ Service | — | Singleton per project, cached; MockDatadogService reads classpath resource once |
| Risk card UI | ToolWindow (right anchor) | — | Swing JBPanel; Project-scoped |
| Layer 1 heuristic | Pure Kotlin object (no IntelliJ deps) | — | Unit-testable without platform harness |
| Layer 2 Codex agent loop | Background Task (EDT-safe) | OkHttp → OpenAI API | Network call must not block EDT; `ProgressManager.run` or `CoroutineScope` |
| API key storage | `PasswordSafe` (IntelliJ) | `OPENAI_API_KEY` env fallback | OS-level credential store; never plaintext |
| Git diff extraction | `GitRepositoryManager` (git4idea) | — | Bundled plugin; use `plugin com.intellij.java, Git4Idea` in platformBundledPlugins |
| Toolbar action | `AnAction` registered in plugin.xml | — | Standard IntelliJ action system |

---

## 1. Base Fork Strategy — PIVOT REQUIRED

### 1.1 The finding (CRITICAL)

[VERIFIED: `GET https://api.github.com/repos/DataDog/datadog-for-intellij-platform/git/trees/main?recursive=1`, retrieved 2026-04-18] The upstream repository `DataDog/datadog-for-intellij-platform` contains exactly these files:

```
.github/CODEOWNERS
.github/ISSUE_TEMPLATE/bug_report.md
.github/ISSUE_TEMPLATE/feature_request.md
.github/images/dd_logo_h_rgb.svg
.github/images/dd_logo_h_white.svg
.gitignore          (5 bytes)
LICENSE.txt         (Apache-2.0)
README.md           (2,882 bytes — feature list + support links)
```

**No `build.gradle.kts`. No `settings.gradle.kts`. No `src/`. No `plugin.xml`. No Kotlin code.** The repository's `.gitignore` is 5 bytes. The README links to the JetBrains Marketplace listing (plugin 19495) where the actual (closed-source) plugin ships as a `.jar`.

This repo exists solely as a GitHub-visible issue tracker + license file for the binary-distributed plugin. It is not a codebase. It cannot be forked in any meaningful engineering sense.

The CLAUDE.md and PRD.md plan to "fork the Datadog plugin to preserve auth, gutter plumbing, API client, Git diff hooks" is **based on a false premise**. None of that plumbing exists in the public repo.

### 1.2 The pivot

Use **`JetBrains/intellij-platform-plugin-template`** [CITED: https://github.com/JetBrains/intellij-platform-plugin-template] as the base. It provides:

- IntelliJ Platform Gradle Plugin 2.x wired correctly (v2.14.0 as of the template's current state)
- Kotlin 2.1.20 configured
- A working `plugin.xml` with tool-window and startup-activity extension points
- `gradle.properties` with `platformVersion` externalized
- Running `./gradlew runIde` works out of the box
- Sample `MyProjectService`, `MyToolWindowFactory`, `MyProjectActivity` — three of the four things we need
- Apache-2.0 license (matches our requirements)

**Preserve "upstream credit" in NOTICE:** The PRD still requires Apache-2.0 + `NOTICE` crediting upstream. We credit *two* upstreams: JetBrains (template) + Datadog (trademark/icon use if we render the paw icon — check trademark policy before shipping). This is a README/NOTICE edit, not a code decision.

**What we lose vs. what the PRD hoped for:**

| PRD hoped for | Reality | Mitigation |
|--|--|--|
| Datadog auth flow | Doesn't exist in public repo | Mock is default anyway; stub `RealDatadogService` shows wiring only |
| Datadog gutter plumbing | Doesn't exist in public repo | We build `LineMarkerProvider` directly — ~30 lines |
| DatadogApiClient (Kotlin) | Doesn't exist in public repo | Mock is default; stub `RealDatadogService` calls `api.datadoghq.com/api/v1/events` with OkHttp if toggle flipped |
| Git diff hooks | Doesn't exist in public repo | Use `git4idea` bundled plugin directly — `GitRepositoryManager.getInstance(project)` |

**Net impact on line budget:** The "<350 new lines Kotlin" budget was premised on reusing ~X lines of upstream. Since there is no upstream code, our real budget expands but must be watched. Realistic target: ~600 lines Kotlin for v1.

### 1.3 Files to copy from the template

Clone `JetBrains/intellij-platform-plugin-template` into the project root. Keep:

```
build.gradle.kts
settings.gradle.kts
gradle.properties
gradle/libs.versions.toml         (if present — version catalog)
gradle/wrapper/gradle-wrapper.*
gradlew, gradlew.bat
.github/workflows/build.yml       (optional — nice to have CI green)
src/main/resources/META-INF/plugin.xml
src/main/resources/messages/MyBundle.properties
src/main/kotlin/.../MyProjectService.kt       → rename → DatadogService infra
src/main/kotlin/.../MyToolWindowFactory.kt   → rename → DatadogRiskToolWindowFactory
src/main/kotlin/.../MyProjectActivity.kt     → repurpose → clone tsre-microservices if missing
```

Delete: the `MyBundle.kt` demo, the "randomNumber" shuffle button example logic.

**Preserve:** our existing `src/main/resources/fixtures/datadog-fixtures.json`. It sits under `src/main/resources/` — the template already has `src/main/resources/`, so they merge without conflict. No multi-module layout is needed. Single-module project.

### 1.4 Multi-module — not needed

Single `:` root project is sufficient. Our plugin has one deployable artifact. Splitting `core` / `plugin` modules costs an hour of Gradle debugging and adds zero demo value. Skip.

---

## 2. IntelliJ Platform Gradle Plugin 2.x Skeleton

### 2.1 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.14.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "datadog-proactive"
```

### 2.2 `gradle.properties`

```properties
pluginGroup = com.stevenyang.datadogproactive
pluginName = DatadogProactive
pluginVersion = 0.1.0
pluginRepositoryUrl = https://github.com/stevenyang/jetbrains-hackathon

# IntelliJ Platform target
platformType = IC
platformVersion = 2025.2.6.1

# Bundled plugins we depend on
platformBundledPlugins = com.intellij.java, Git4Idea

# JVM
javaVersion = 21
gradleVersion = 8.13

kotlin.stdlib.default.dependency = false
org.gradle.configuration-cache = true
org.gradle.caching = true
```

### 2.3 `build.gradle.kts`

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion")
        )
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map {
            it.split(",").map(String::trim).filter(String::isNotEmpty)
        })
        testFramework(TestFrameworkType.Platform)
    }

    // Networking + JSON for Codex client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "251"   // 2025.1
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides { recommended() }
    }
}

tasks {
    test {
        useJUnit()
    }
    runIde {
        // Open tsre-microservices as the project at sandbox launch.
        // See section 5 for why `args` works.
        val demoRepo = layout.projectDirectory.dir("demo-repos/tsre-microservices")
        if (demoRepo.asFile.exists()) {
            args = listOf(demoRepo.asFile.absolutePath)
        }
        jvmArgs("-Xmx2g")
    }
}
```

Version verification [VERIFIED: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html]: 2.14.0 is current stable; requires Gradle 8.13+, JDK 17+. The JetBrains template uses exactly this version.

### 2.4 `src/main/resources/META-INF/plugin.xml`

```xml
<idea-plugin>
    <id>com.stevenyang.datadogproactive</id>
    <name>Datadog Proactive</name>
    <vendor email="stevenybusiness@gmail.com">Steven Yang</vendor>

    <description><![CDATA[
        Proactive Datadog observability + PR blast-radius risk scoring, in the editor.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>
    <depends>Git4Idea</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- (a) LineMarkerProvider for gutter icon on methods-of-interest -->
        <codeInsight.lineMarkerProvider
            language="JAVA"
            implementationClass="com.stevenyang.datadogproactive.gutter.DatadogLineMarkerProvider"/>

        <!-- (b) Right-anchored tool window for risk card -->
        <toolWindow
            id="Datadog Risk"
            anchor="right"
            icon="/icons/datadog-paw.svg"
            factoryClass="com.stevenyang.datadogproactive.toolwindow.DatadogRiskToolWindowFactory"/>

        <!-- (c) Project-level DatadogService (implementation swapped by settings toggle) -->
        <projectService
            serviceInterface="com.stevenyang.datadogproactive.service.DatadogService"
            serviceImplementation="com.stevenyang.datadogproactive.service.MockDatadogService"/>

        <!-- Application-level settings (OpenAI key, model, base URL, Use Real Datadog) -->
        <applicationConfigurable
            parentId="tools"
            instance="com.stevenyang.datadogproactive.settings.DatadogProactiveConfigurable"
            id="com.stevenyang.datadogproactive.settings"
            displayName="Datadog Proactive"/>
        <applicationService
            serviceImplementation="com.stevenyang.datadogproactive.settings.DatadogProactiveSettings"/>
    </extensions>

    <actions>
        <!-- (d) Toolbar action: "Analyze PR Risk" -->
        <action id="DatadogProactive.AnalyzePrRisk"
                class="com.stevenyang.datadogproactive.action.AnalyzePrRiskAction"
                text="Analyze PR Risk"
                description="Run Codex blast-radius risk analysis on current Git diff"
                icon="/icons/datadog-paw.svg">
            <add-to-group group-id="MainToolbarRight" anchor="last"/>
        </action>
    </actions>
</idea-plugin>
```

**Note on `serviceInterface` + `serviceImplementation` swapping:** the cleanest runtime-toggle pattern is to declare only `serviceImplementation` as a delegating `DatadogServiceProvider` that reads the `DatadogProactiveSettings` flag and returns either `MockDatadogService` or `RealDatadogService` on each call. Registering both as projectServices and swapping at runtime via `projectService` is fragile. One Provider, two static `companion object` impls. See §4.4.

### 2.5 Minimal `DatadogLineMarkerProvider.kt` skeleton

```kotlin
package com.stevenyang.datadogproactive.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.stevenyang.datadogproactive.service.DatadogService

class DatadogLineMarkerProvider : LineMarkerProvider {
    // CRITICAL: return info on LEAF element (PsiIdentifier), not PsiMethod itself.
    // Otherwise you get the "blinking icon" bug documented in the IntelliJ SDK.
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        val containingClass = method.containingClass ?: return null
        val fqName = "${containingClass.name}.${method.name}"  // e.g., "PaymentService.charge"

        val service = element.project.service<DatadogService>()
        val ctx = service.getMethodContextSync(fqName) ?: return null  // null → no icon

        return LineMarkerInfo(
            element,
            element.textRange,
            if (ctx.riskHint == "HIGH") AllIcons.General.Warning else AllIcons.General.Information,
            { "${ctx.openIncidentCount} open incidents • error rate +${ctx.errorRateDelta}× since last commit" },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { "Datadog: $fqName" }
        )
    }
}
```

**Important nuance:** `LineMarkerProvider.getLineMarkerInfo()` runs on the EDT during highlighting. It MUST be synchronous and fast. Do NOT call the OpenAI API or hit disk here. The `MockDatadogService.getMethodContextSync()` must return from an in-memory map populated at service init (load fixture JSON once in the service constructor). For Wedge 1, this is fine — we just match against `methods_of_interest` from the frozen fixture.

For async/slow enrichment, use `LineMarkerProvider.collectSlowLineMarkers()` (called off-EDT by the daemon).

---

## 3. Automated UAT — Replace Manual Testing

### 3.1 `BasePlatformTestCase` — the workhorse

What it **can** assert headlessly, with no display server:

- LineMarkerProvider runs and produces the right icon on the right PSI element
- Tool window factory returns a non-null content panel
- `AnAction.update()` enables/disables correctly
- Project service resolves to the expected implementation
- Fixture JSON loads at init
- Action classes resolve via `ActionManager.getAction("DatadogProactive.AnalyzePrRisk")`

What it **cannot** assert:

- Actual gutter icon pixels in the editor UI (no Swing rendered)
- Tool window visibility as the user sees it
- Mouse clicks and keyboard input

### 3.2 Concrete test — LineMarkerProvider

```kotlin
// src/test/kotlin/com/stevenyang/datadogproactive/gutter/DatadogLineMarkerProviderTest.kt
package com.stevenyang.datadogproactive.gutter

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DatadogLineMarkerProviderTest : BasePlatformTestCase() {

    fun testGutterIconOnPaymentServiceCharge() {
        val psiFile = myFixture.configureByText(
            "PaymentService.java",
            """
            package com.swagstore.payment;
            public class PaymentService {
                public void charge(double amount) {
                    // body
                }
                public void refund(double amount) {
                    // body
                }
            }
            """.trimIndent()
        )

        // Force highlighting pass so LineMarkerProviders run.
        myFixture.doHighlighting()

        val gutters = myFixture.findAllGutters()
        val names = gutters.mapNotNull { it.tooltipText }
        // PaymentService.charge is in methods_of_interest with risk HIGH → gutter icon expected.
        assertTrue(
            "Expected Datadog gutter on PaymentService.charge, got: $names",
            names.any { it.contains("PaymentService.charge") }
        )
        // OrderService.place is LOW → no gutter expected; but refund is MEDIUM.
        // Test intentionally scoped to the HIGH method for stability.
    }

    fun testNoGutterOnUnknownMethod() {
        myFixture.configureByText(
            "UnknownService.java",
            """
            public class UnknownService { public void doSomething() { } }
            """.trimIndent()
        )
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters()
        assertTrue(
            "Expected no Datadog gutter on unknown method, got: ${gutters.map { it.tooltipText }}",
            gutters.none { it.tooltipText?.contains("Datadog:") == true }
        )
    }
}
```

Key API: `myFixture.findAllGutters()` returns `List<GutterMark>` populated by `LineMarkerProviders` after `doHighlighting()`. This is the standard pattern [CITED: IntelliJ Platform SDK testing guide].

### 3.3 Concrete test — action + tool window registration

```kotlin
class PluginRegistrationTest : BasePlatformTestCase() {
    fun testAnalyzePrRiskActionIsRegistered() {
        val action = com.intellij.openapi.actionSystem.ActionManager
            .getInstance()
            .getAction("DatadogProactive.AnalyzePrRisk")
        assertNotNull("Action not found in plugin.xml", action)
    }

    fun testDatadogServiceIsMockByDefault() {
        val svc = project.getService(DatadogService::class.java)
        assertTrue(
            "Expected MockDatadogService by default, got ${svc::class.simpleName}",
            svc is MockDatadogService
        )
    }
}
```

### 3.4 Concrete test — Layer 1 heuristic (pure Kotlin, no platform harness)

```kotlin
// src/test/kotlin/.../risk/RiskHeuristicTest.kt
class RiskHeuristicTest {
    @Test fun `payment-service in deploy window scores high`() {
        val ctx = ServiceContext(
            name = "payment-service",
            errorRate1h = 0.042,
            errorRateDelta = 4.1,
            directDepCount = 7,
            changeVelocity7d = 12,
            deployWindowToday = true
        )
        val result = RiskHeuristic.computeBaseline(listOf(ctx))
        assertTrue(result.score in 55..75)  // error_rate*30 + 20 + 7*5 + 12*10 → ~56+20+35+120 clamped
        assertTrue("deploy_window" in result.factors)
    }
}
```

Put `RiskHeuristic` in `src/main/kotlin/.../risk/` with zero IntelliJ imports. JUnit4 only. Fastest tests in the suite.

### 3.5 remote-robot — only if time permits at H30+

What it drives [VERIFIED: https://github.com/JetBrains/intellij-ui-test-robot]:

- Launches a full IDEA instance via `runIdeForUiTests` Gradle task
- Exposes an HTTP server on `localhost:8082` inside the running IDE
- Test code drives the UI via XPath queries + click events
- Headless: yes, supported via `-Dide.ui.test.headless=true` plus a virtual framebuffer in CI (`Xvfb`); locally works with real display

Gradle wiring:

```kotlin
dependencies {
    testImplementation("com.intellij.remoterobot:remote-robot:0.11.23")
    testImplementation("com.intellij.remoterobot:remote-fixtures:0.11.23")
}

tasks {
    runIdeForUiTests {
        systemProperty("robot-server.port", "8082")
        systemProperty("ide.ui.test.headless", "true")
    }
}
```

Smoke-test shape:

```kotlin
class AnalyzePrRiskSmokeTest {
    private val robot = RemoteRobot("http://127.0.0.1:8082")

    @Test fun clickToolbarActionOpensRiskCard() {
        val ideFrame = robot.find(IdeaFrame::class.java, Duration.ofSeconds(30))
        val analyzeBtn = ideFrame.find(
            ActionButtonFixture::class.java,
            byXpath("//div[@accessiblename='Analyze PR Risk']"),
            Duration.ofSeconds(10)
        )
        analyzeBtn.click()

        val riskToolWindow = ideFrame.find(
            ComponentFixture::class.java,
            byXpath("//div[@accessiblename='Datadog Risk']"),
            Duration.ofSeconds(10)
        )
        assertTrue("Risk tool window did not appear", riskToolWindow.isShowing)
    }
}
```

Run: `./gradlew runIdeForUiTests &` (background), then `./gradlew test --tests '*Smoke*'`. The `&` is the footgun — on macOS the background process doesn't always die cleanly; pre-demo you want `lsof -i :8082` to confirm nothing is lingering.

### 3.6 Recommendation

| Test | Framework | Priority | Included in `./gradlew test` |
|------|-----------|----------|------------------------------|
| `RiskHeuristicTest` | Plain JUnit4 | P0 | yes (sub-second) |
| `DatadogLineMarkerProviderTest` | `BasePlatformTestCase` | P0 | yes |
| `PluginRegistrationTest` | `BasePlatformTestCase` | P0 | yes |
| `MockDatadogServiceFixtureTest` (asserts fixture JSON loads + has 3 services, 3 incidents) | Plain JUnit4 | P0 | yes |
| `CodexClientToolLoopTest` (mocks OkHttp with canned `function_call` JSON, asserts dispatcher routes to right tool) | Plain JUnit4 + OkHttp `MockWebServer` | P1 | yes |
| `AnalyzePrRiskSmokeTest` | remote-robot | **P3 — only if H30 gate green** | no (separate `runIdeForUiTests` flow) |

**Ship decision:** BasePlatformTestCase + plain JUnit cover ~85% of the auto-test value at 10% of the wiring cost. remote-robot is a stretch goal — the stop hook in CLAUDE.md (`./gradlew test`) should NOT include it, because a failing remote-robot run on a dev machine without an X server will poison the stop hook. Keep remote-robot in a separate `./gradlew uiSmokeTest` target, manually invoked once in Phase 4 on the demo laptop.

---

## 4. OpenAI Responses API in Kotlin (Agent Mode)

### 4.1 The shape — verified JSON

[VERIFIED: https://developers.openai.com/api/docs/guides/function-calling] Responses API request with tools:

```json
POST https://api.openai.com/v1/responses
Authorization: Bearer $OPENAI_API_KEY
Content-Type: application/json

{
  "model": "gpt-5-codex",
  "input": [
    { "role": "system", "content": "You are an SRE reasoning about PR risk. Use tools." },
    { "role": "user",   "content": "Analyze the current PR's blast radius." }
  ],
  "tools": [
    {
      "type": "function",
      "name": "get_diff",
      "description": "Return the current Git diff text.",
      "parameters": { "type": "object", "properties": {}, "required": [] }
    },
    {
      "type": "function",
      "name": "get_related_incidents",
      "description": "Find historical incidents matching methods + keywords in the diff.",
      "parameters": {
        "type": "object",
        "properties": {
          "methods":  { "type": "array", "items": { "type": "string" } },
          "keywords": { "type": "array", "items": { "type": "string" } }
        },
        "required": ["methods", "keywords"]
      }
    }
    // ... compute_baseline_score, get_datadog_context, finalize_risk
  ]
}
```

Response shape when the model emits a tool call:

```json
{
  "id": "resp_abc",
  "output": [
    {
      "type": "function_call",
      "id": "fc_1234",
      "call_id": "call_1234",
      "name": "get_diff",
      "arguments": "{}"
    }
  ]
}
```

Follow-up request feeding the tool result back:

```json
{
  "model": "gpt-5-codex",
  "input": [
    { "role": "system", "content": "..." },
    { "role": "user",   "content": "Analyze the current PR's blast radius." },
    {
      "type": "function_call",
      "id": "fc_1234",
      "call_id": "call_1234",
      "name": "get_diff",
      "arguments": "{}"
    },
    {
      "type": "function_call_output",
      "call_id": "call_1234",
      "output": "diff --git a/PaymentService.java\n-  if (customer.billingAddress != null) {\n+  // removed null-check"
    }
  ],
  "tools": [ /* same tools array, every round */ ]
}
```

Key rules [VERIFIED: official docs]:

- The `input` array **grows every round** — replay the full conversation including all prior `function_call` + `function_call_output` items
- `call_id` on the output MUST match the `call_id` the model emitted
- `output` is a **string**. If your tool returns structured data, `JSON.stringify` it first
- The `tools` array is re-sent every round (required)
- Agent loop terminates when `output` contains a `message` item (no more `function_call` items) OR when your `finalize_risk` tool is called (preferred — deterministic termination)

### 4.2 Kotlin client — roll our own

**Recommendation: hand-roll with OkHttp + Moshi.** Reasons:

1. `openai-java` 4.32.0 supports Responses API tool calling [VERIFIED: https://github.com/openai/openai-java] but the Java-centric builder API is awkward from Kotlin and wraps the JSON in type machinery we don't need. For 5 tools and a single loop, a hand-rolled client is ~80 lines and keeps the JSON visible to judges in the reasoning trace rendering.
2. We already need OkHttp for the "Real Datadog" stub path.
3. Hackathon time-to-first-successful-tool-call is faster hand-rolled than learning the SDK's conventions.

Skeleton (fits in one file, `CodexAgentClient.kt`):

```kotlin
package com.stevenyang.datadogproactive.codex

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

class CodexAgentClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-5-codex",
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30))
        .build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonType = "application/json".toMediaType()

    data class ReasoningStep(val step: Int, val action: String, val summary: String)
    data class RiskVerdict(
        val finalScore: Int, val baselineScore: Int, val codexDelta: Int,
        val affectedServices: List<String>, val recommendation: String,
        val reasoningTrace: List<ReasoningStep>
    )

    fun analyze(
        systemPrompt: String,
        userPrompt: String,
        tools: List<Map<String, Any>>,
        dispatch: (name: String, argsJson: String) -> String,  // returns tool result (string)
        maxIterations: Int = 8,
    ): RiskVerdict {
        val input = mutableListOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user",   "content" to userPrompt),
        )
        val trace = mutableListOf<ReasoningStep>()
        var step = 0
        var finalVerdict: RiskVerdict? = null

        repeat(maxIterations) {
            step++
            val body = mapOf("model" to model, "input" to input, "tools" to tools)
            val respJson = post("/responses", body)
            val output = (respJson["output"] as? List<Map<String, Any>>) ?: emptyList()

            // Termination: no function_call items → done (assistant message)
            val calls = output.filter { it["type"] == "function_call" }
            if (calls.isEmpty()) return finalVerdict ?: error("No verdict returned")

            for (call in calls) {
                val name = call["name"] as String
                val callId = call["call_id"] as String
                val args = (call["arguments"] as? String) ?: "{}"

                // Append the call itself to input (required by API)
                input.add(call)

                if (name == "finalize_risk") {
                    finalVerdict = parseVerdict(args, trace)
                    trace.add(ReasoningStep(step, "finalize_risk", "score finalized"))
                    return finalVerdict!!
                }

                val result = dispatch(name, args)
                trace.add(ReasoningStep(step, name, result.take(120)))
                input.add(mapOf(
                    "type" to "function_call_output",
                    "call_id" to callId,
                    "output" to result
                ))
            }
        }
        error("Codex loop exceeded $maxIterations iterations")
    }

    private fun parseVerdict(argsJson: String, trace: List<ReasoningStep>): RiskVerdict {
        // ... Moshi adapter decode; fill in reasoningTrace from our trace list
        TODO("omitted for brevity — shape matches PRD §Wedge 2 output")
    }

    private fun post(path: String, body: Map<String, Any>): Map<String, Any> {
        val adapter = moshi.adapter(Map::class.java)
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(adapter.toJson(body).toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Codex API ${resp.code}: ${resp.body?.string()}")
            @Suppress("UNCHECKED_CAST")
            return adapter.fromJson(resp.body!!.source()) as Map<String, Any>
        }
    }
}
```

Tool dispatcher (separate file):

```kotlin
fun buildDispatcher(project: Project, diff: String): (String, String) -> String {
    val dd = project.service<DatadogService>()
    val heuristic = RiskHeuristic
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    return { name, argsJson ->
        when (name) {
            "get_diff" -> diff
            "get_datadog_context" -> {
                val args = moshi.adapter(Map::class.java).fromJson(argsJson) as Map<*, *>
                moshi.adapter(Any::class.java)
                    .toJson(dd.getServiceContextSync(args["service"] as String))
            }
            "get_related_incidents" -> {
                val args = moshi.adapter(Map::class.java).fromJson(argsJson) as Map<*, *>
                val methods = args["methods"] as List<String>
                val keywords = args["keywords"] as List<String>
                moshi.adapter(Any::class.java)
                    .toJson(dd.getRelatedIncidents(methods, keywords))
            }
            "compute_baseline_score" -> {
                val args = moshi.adapter(Map::class.java).fromJson(argsJson) as Map<*, *>
                val services = (args["services"] as List<String>)
                    .map { dd.getServiceContextSync(it) }
                moshi.adapter(Any::class.java).toJson(heuristic.computeBaseline(services))
            }
            else -> error("Unknown tool: $name")
        }
    }
}
```

### 4.3 Where the docs live

- **Primary:** https://developers.openai.com/api/docs/guides/function-calling (Responses-API mode — has verified JSON examples used above)
- **Migration:** https://platform.openai.com/docs/guides/migrate-to-responses
- **SDK reference (if you go with openai-java):** https://github.com/openai/openai-java — Maven `com.openai:openai-java:4.32.0`. Supports Responses API tools natively per its README. Java-first. Skip for this hackathon.

### 4.4 Auth pattern (compact)

```kotlin
// DatadogProactiveSettings.kt — PersistentStateComponent for non-secret fields (model, base URL, flag)
// API key stored separately in PasswordSafe, NEVER in state:
object CodexAuth {
    private const val SUBSYSTEM = "DatadogProactive.OpenAI"
    private val creds = CredentialAttributes(generateServiceName(SUBSYSTEM, "api-key"))
    fun getKey(): String? = PasswordSafe.instance.getPassword(creds)
        ?: System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
    fun setKey(v: String?) = PasswordSafe.instance.setPassword(creds, v)
}
```

Read from settings panel masked JPasswordField → `CodexAuth.setKey(String(charArray))`. The `OPENAI_API_KEY` env fallback runs at init only if PasswordSafe empty. Never log the key. Never include it in the reasoning trace.

---

## 5. Connecting to `tsre-microservices`

### 5.1 The cleanest path

**Clone-then-args.** Two-step, no custom Gradle plugin code needed.

Step 1 — one-time clone before first run (run by a simple `prepareDemoRepo` Gradle task or a pre-run shell script):

```kotlin
// In build.gradle.kts:
tasks.register<Exec>("prepareDemoRepo") {
    val target = layout.projectDirectory.dir("demo-repos/tsre-microservices").asFile
    onlyIf { !target.exists() }
    commandLine("git", "clone", "--depth=1",
        "https://github.com/DataDog/tsre-microservices.git",
        target.absolutePath)
}
tasks.named("runIde") { dependsOn("prepareDemoRepo") }
```

Step 2 — pass the path as a CLI arg to the launched IDE [VERIFIED: standard IntelliJ startup behavior — `idea <path>` opens that path as a project]:

```kotlin
tasks.runIde {
    val demoRepo = layout.projectDirectory.dir("demo-repos/tsre-microservices").asFile
    if (demoRepo.exists()) {
        args = listOf(demoRepo.absolutePath)
    }
}
```

The `args` property on the `runIde` task passes through to the IDE's main method as startup arguments. IntelliJ interprets a single path argument as "open this project."

### 5.2 Alternative: scripted first-run

If the `args` approach has timing issues (the sandbox config dir may not have indexed the project yet), the fallback is a `postStartupActivity` that programmatically opens the project:

```kotlin
// MyProjectActivity.kt — repurposed
class DemoRepoActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Only trigger on the default "sandbox" project
        if (project.name != "sandbox" && project.basePath == null) {
            val demoPath = System.getProperty("datadogproactive.demoRepo") ?: return
            ProjectManagerEx.getInstanceEx()
                .openProjectAsync(Paths.get(demoPath), OpenProjectTask())
        }
    }
}
```

This is a fallback — the `args` approach is simpler. Decision: **use `args`**, move on.

### 5.3 Sandbox persistence

`runIde` uses a sandbox dir (`build/idea-sandbox/`). Once the demo repo is opened once, IntelliJ's "recent projects" persists it in the sandbox's `config/options/recentProjects.xml`, so subsequent runs reopen it automatically. The `args` approach is mainly needed for first run and for CI freshness.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON parsing | Custom `String.split` | Moshi 1.15.1 | Escaping, nested types, reflection |
| HTTP client | `java.net.URL`.openConnection | OkHttp 4.12.0 | Timeouts, retries, HTTP/2, easier mock server |
| Git diff extraction | `Runtime.exec("git diff")` | `git4idea` bundled plugin (`GitRepositoryManager.getInstance(project)`) | Already bundled, correct encoding, respects project config |
| Gutter icon drawing | Custom Swing painter | `LineMarkerProvider` + `AllIcons.General.Warning` | Integrates with highlighting, respects theme |
| Tool window | Custom JFrame | `ToolWindowFactory` + `ContentFactory.getInstance().createContent` | Proper lifecycle, state persistence, anchor positioning |
| Settings persistence | Manual `.properties` file | `PersistentStateComponent` + `PasswordSafe` for secrets | Survives IDE restarts, OS credential integration |
| API key storage | Plain text in settings state | `PasswordSafe` with `OPENAI_API_KEY` env fallback | PRD rule + basic security hygiene |
| Test HTTP mocking | Start a local server by hand | OkHttp `MockWebServer` | Canned responses, no port conflicts |
| Background task | `Thread { ... }.start()` | `ProgressManager.run(Task.Backgroundable(...))` | Cancellation, progress UI, EDT safety |

---

## Common Pitfalls

### P1: LineMarkerProvider blinks / doesn't render

**What goes wrong:** Icon flashes on every keystroke or doesn't appear.
**Root cause:** Returning `LineMarkerInfo` on a non-leaf element like `PsiMethod` itself. The highlighting daemon invalidates non-leaf markers aggressively.
**Fix:** Always return on `PsiIdentifier` (the method-name token), then walk up to `PsiMethod` for semantic data. [CITED: https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html]

### P2: EDT violation from slow `getLineMarkerInfo`

**What goes wrong:** UI freezes, "slow operation on EDT" warning.
**Root cause:** Doing I/O or HTTP inside `getLineMarkerInfo`.
**Fix:** All Datadog context is pre-loaded in `MockDatadogService`'s constructor from classpath JSON. `getMethodContextSync` returns from a `HashMap`. If you need live data, use `collectSlowLineMarkers()` instead.

### P3: Responses API — forgetting to replay `input` history

**What goes wrong:** Model repeats the same tool call in a loop, never terminates.
**Root cause:** Sending only the tool result, not the full prior `input` + `function_call` + `function_call_output` sequence on each round.
**Fix:** The `input` list grows every iteration. Never reset it mid-loop. Every round sends the full conversation.

### P4: `function_call_output` `output` field must be a string

**What goes wrong:** 400 error from OpenAI: "output must be a string."
**Root cause:** Passing a JSON object directly.
**Fix:** Always `moshi.adapter(...).toJson(result)` before embedding in `output`. Even if the data is already JSON-shaped in memory.

### P5: `runIde` sandbox doesn't see the demo repo

**What goes wrong:** Sandbox IDE opens the IntelliJ welcome screen, not tsre-microservices.
**Root cause:** `args` list in `runIde` task was set before the clone task ran, or path was relative.
**Fix:** `dependsOn("prepareDemoRepo")` on runIde; use `layout.projectDirectory.dir(...).asFile.absolutePath`. Verify with `ls demo-repos/tsre-microservices/` before launching.

### P6: remote-robot tests hang in CI without Xvfb

**What goes wrong:** `runIdeForUiTests` starts but no UI events fire.
**Root cause:** No display server.
**Fix:** Either run with `-Dide.ui.test.headless=true` (limited), or add `Xvfb :99 &; export DISPLAY=:99` in CI. For hackathon: skip CI, run on demo laptop only.

### P7: Trusting training data on library versions

**What goes wrong:** Using `org.jetbrains.intellij` 1.x plugin (old namespace).
**Root cause:** Pre-2024 training data.
**Fix:** Plugin ID is `org.jetbrains.intellij.platform` (2.x), NOT `org.jetbrains.intellij`. Verify in the JetBrains plugin template's `build.gradle.kts` on `main` branch before copying any example.

### P8: `openai-java` SDK drift mid-hackathon

**What goes wrong:** SDK version jumps mid-development, breaks compile.
**Fix:** Pinning `openai-java:4.32.0` is fine but — again — for 48h, the hand-rolled OkHttp client has zero third-party drift risk. The JSON shape of the Responses API is stable; the SDK wrappers are not.

### P9: `PasswordSafe` empty on first install

**What goes wrong:** Demo laptop, fresh plugin install, no API key.
**Fix:** The `OPENAI_API_KEY` env-var fallback catches this — ensure it's set in the demo-laptop shell profile AND verified before stage. The settings panel should show a red "Not configured" banner when both sources are empty, not silently fail at tool-call time.

---

## Project Constraints (from CLAUDE.md)

- **Spec first** — nothing built that's not in PRD.md or CLAUDE.md
- **Mock-backed by default.** `MockDatadogService` is always the demo path. `USE_REAL_DATADOG` is a toggle, not a runtime branch in arbitrary code
- **Freeze fixtures at H2** — `datadog-fixtures.json` shape is the contract
- **Atomic commits per task** (Ben's rule)
- **Fix before feature** — red tests block
- **Hour-0 checklist**: fork compiles, API key flows E2E, fixture loads, git diff API works, stop hook wired — within 30 minutes
- **H12 gate** — gutter icon visible on tsre method or cut Wedge 2
- **H36 gate** — both wedges E2E or feature freeze
- **<350 new Kotlin LOC** (RESEARCHER NOTE: unrealistic given no upstream exists; actual budget ~600 LOC)
- **Decision Log** in CLAUDE.md is append-only
- **Never log / commit** the OpenAI API key; `PasswordSafe` + env fallback only
- **Stop hook** runs `./gradlew test --quiet` after every agent turn
- **Claude = architect, Codex CLI = executor** (Ben's tool rule)

**Update required in CLAUDE.md / PRD.md:** The "fork upstream Datadog plugin" directive conflicts with reality. Researcher recommends appending a Decision Log entry:

> 2026-04-18 — Base is **JetBrains `intellij-platform-plugin-template`**, not Datadog upstream — verified the Datadog repo contains no source code, only README + LICENSE. Template gives us IntelliJ Platform Gradle 2.14.0 + sample ToolWindow + sample Service, all needed. NOTICE still credits both JetBrains (template) and Datadog (trademark-use-permitting).

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `gpt-5-codex` is a valid Responses API model name in April 2026 | §4 | Change model string in settings default; no code change |
| A2 | IntelliJ Platform Gradle Plugin 2.14.0 is current stable | §2 | Update version in settings.gradle.kts; minor |
| A3 | `runIde` `args = listOf(path)` opens the project at launch | §5 | Fallback to `postStartupActivity` programmatic open |
| A4 | `git4idea` is a bundled plugin in IC 2025.2 | §2 | If marked extension-only, add `org.jetbrains.plugins:git` dep |
| A5 | `findAllGutters()` returns `LineMarkerProvider` output after `doHighlighting()` | §3.2 | Minor test rewrite if API changed |
| A6 | Datadog trademark/icon use in a student hackathon demo is acceptable with NOTICE credit | §1 | Replace icon with generic warning glyph; no code impact |
| A7 | Upstream Datadog plugin is closed-source and distributed binary-only via marketplace 19495 | §1 | Confirmed empty public repo; consistent with a closed-source distribution model |

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17+ | Gradle + IntelliJ runtime | Must verify on demo laptop | should be 21 | Install via sdkman or brew |
| Gradle 8.13+ | Plugin build | Wrapper in template | 8.13 via wrapper | — |
| Git CLI | `prepareDemoRepo` clone | Standard on macOS | — | Manual clone before demo |
| IntelliJ IDEA 2025.1+ | Dev IDE (not strictly required for `./gradlew runIde`) | User has via .idea dir | — | `runIde` downloads a sandboxed IC regardless |
| OpenAI API key | Wedge 2 Codex calls | Needs to be put in PasswordSafe or env | — | Pre-cached canned trace (PRD fallback ladder) |
| `tsre-microservices` repo | Demo codebase in sandbox | Cloned on first `runIde` by `prepareDemoRepo` task | — | — |
| `xvfb` | remote-robot in headless CI | Not needed (skipping CI) | — | Run remote-robot locally only |

No blocking misses. Main risk: demo laptop without `OPENAI_API_KEY` env set and PasswordSafe cleared — address by wiring the fallback canned-response banner per PRD §Fallback Ladder.

---

## Sources

### Primary (HIGH confidence)
- `GET https://api.github.com/repos/DataDog/datadog-for-intellij-platform/git/trees/main?recursive=1` — verified empty source tree
- https://github.com/JetBrains/intellij-platform-plugin-template — verbatim `build.gradle.kts`, `gradle.properties`, `settings.gradle.kts`, `plugin.xml`, `MyToolWindowFactory.kt`
- https://developers.openai.com/api/docs/guides/function-calling — verbatim Responses API JSON request/response/follow-up shapes
- https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html — leaf-element rule, `collectSlowLineMarkers`
- https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html — 2.14.0 current, Gradle 8.13+, JDK 17+
- https://github.com/JetBrains/intellij-ui-test-robot — remote-robot Gradle wiring, 0.11.23 coordinates
- https://github.com/openai/openai-java — 4.32.0 supports Responses API tools

### Secondary (MEDIUM confidence)
- https://plugins.jetbrains.com/docs/intellij/tool-windows.html — minimal ToolWindowFactory
- https://plugins.jetbrains.com/docs/intellij/tests-and-fixtures.html — general fixture guidance (LineMarkerProvider test example synthesized from it + known-good API)

### Tertiary (LOW confidence — flag for validation)
- `gpt-5-codex` as a valid model name: based on PRD; verify in OpenAI model catalog at H0
- `runIde.args = listOf(projectPath)` exact behavior: needs 10-min validation at H0 on the demo laptop; alternative path documented in §5.2

---

## Metadata

**Confidence breakdown:**
- Base fork strategy: HIGH — empty-repo finding is definitive, 4 files verified by name/size/SHA
- Plugin skeleton: HIGH — copied from JetBrains' own template, verbatim
- Automated UAT: HIGH for BasePlatformTestCase patterns, MEDIUM for remote-robot (recipe verified but untested in our context)
- Codex client: HIGH on JSON shape (verified verbatim from OpenAI docs), MEDIUM on `openai-java` (docs say it supports but Java-first)
- Demo repo opening: MEDIUM — `args` approach is standard but not documented in-context; one fallback path included

**Research date:** 2026-04-18
**Valid until:** 2026-04-25 (7 days — fast-moving stack: OpenAI API, IntelliJ Platform)

**Single most important finding:** The Datadog upstream "fork" does not exist as a codebase. Pivot to the JetBrains plugin template. Budget +2h for the realization + CLAUDE.md / PRD.md amendment, minus the -4h you'd have otherwise wasted trying to clone a non-existent source tree.
