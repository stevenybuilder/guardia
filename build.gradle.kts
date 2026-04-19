import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

kotlin {
    jvmToolchain(21)
}

// ---------------------------------------------------------------------------
// uiSmokeTest source set (remote-robot, out-of-process IDE UI tests).
//
// Kept STRICTLY separate from the default `test` source set so `./gradlew test`
// (which the Stop hook runs after every Claude turn) never attempts to spin up
// a real IDE. The `uiSmokeTest` task below is ONLY invoked manually via
// `JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
//  ./gradlew uiSmokeTest` on the demo laptop.
// ---------------------------------------------------------------------------
sourceSets {
    create("uiSmokeTest") {
        kotlin.srcDir("src/uiSmokeTest/kotlin")
        resources.srcDir("src/uiSmokeTest/resources")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val uiSmokeTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

@Suppress("UnusedPrivateProperty")
val uiSmokeTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.2.6.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }

    // Networking + JSON for OpenAI Responses API client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // Coroutines are provided by IntelliJ Platform (forked build in util-8.jar). We add
    // the stock stdlib-coroutines as compileOnly so Phase-3 source files referencing
    // kotlinx.coroutines.flow.Flow compile; at runtime IJ's forked version wins.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testCompileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.20")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // remote-robot scoped ONLY to the uiSmokeTest source set.
    // IMPORTANT: do NOT add these to testImplementation; the Stop hook's
    // `./gradlew test` must not pull in remote-robot at all.
    "uiSmokeTestImplementation"("com.intellij.remoterobot:remote-robot:0.11.23")
    "uiSmokeTestImplementation"("com.intellij.remoterobot:remote-fixtures:0.11.23")
    "uiSmokeTestImplementation"("junit:junit:4.13.2")
    "uiSmokeTestImplementation"("org.jetbrains.kotlin:kotlin-test-junit:2.1.20")
    "uiSmokeTestImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}

// ---------------------------------------------------------------------------
// `runIdeForUiTests` — a second sandbox IDE dedicated to remote-robot.
// Registered via the IntelliJ Platform 2.x `intellijPlatformTesting.runIde`
// container (creates a RunIdeTask with its own prepareSandbox/prepareTestSandbox
// wiring, independent of the default `runIde` task).
//
// Note: in IntelliJ Platform Gradle Plugin 2.14.0, `intellijPlatformTesting.testIdeUi`
// exists but is wired to the JetBrains *Starter* framework (in-process), not
// remote-robot's HTTP client/server model. We use the `runIde` container here
// instead so we get a plain IDE launch with the robot-server port exposed —
// the smoke-test JVM then connects over localhost:8082 à la RESEARCH.md §3.5.
// ---------------------------------------------------------------------------
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                dependsOn(tasks.named("prepareDemoRepo"))
                jvmArgs("-Xmx2g")
                systemProperty("robot-server.port", "8082")
                systemProperty("ide.mac.message.dialogs.as.sheets", "false")
                systemProperty("jb.privacy.policy.text", "<!--999.999-->")
                systemProperty("jb.consents.confirmation.enabled", "false")
                systemProperty("idea.trust.all.projects", "true")
                systemProperty("ide.show.tips.on.startup.default.value", "false")

                val demoRepo = layout.projectDirectory.dir("demo-repos/tsre-microservices").asFile
                doFirst {
                    if (demoRepo.exists()) {
                        args = listOf(demoRepo.absolutePath)
                    }
                }
            }
            plugins {
                // robot-server ships as a separate IntelliJ plugin that must be
                // installed into this sandbox. Without it, there's no HTTP port
                // to connect to.
                robotServerPlugin("0.11.23")
            }
        }
    }
}

tasks {
    test {
        useJUnit()
    }

    // Clone tsre-microservices into demo-repos/ on first runIde.
    register<Exec>("prepareDemoRepo") {
        val target = layout.projectDirectory.dir("demo-repos/tsre-microservices").asFile
        onlyIf { !target.exists() }
        doFirst { target.parentFile.mkdirs() }
        commandLine(
            "git", "clone", "--depth=1",
            "https://github.com/DataDog/tsre-microservices.git",
            target.absolutePath
        )
    }

    runIde {
        dependsOn("prepareDemoRepo")
        val demoRepo = layout.projectDirectory.dir("demo-repos/tsre-microservices").asFile
        // Source `.env` (if present) into the sandbox JVM environment so
        // OPENAI_API_KEY / OPENAI_MODEL / OPENAI_BASE_URL propagate without the
        // developer needing to `export` them in the shell. Values already set in
        // the parent shell win (shell > .env).
        val dotenv = layout.projectDirectory.file(".env").asFile
        if (dotenv.exists()) {
            dotenv.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .forEach { line ->
                    val idx = line.indexOf('=')
                    val k = line.substring(0, idx).trim()
                    var v = line.substring(idx + 1).trim()
                    // strip optional surrounding quotes
                    if ((v.startsWith("\"") && v.endsWith("\"")) ||
                        (v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length - 1)
                    }
                    if (k.isNotEmpty() && System.getenv(k).isNullOrEmpty()) {
                        environment(k, v)
                    }
                }
        }
        doFirst {
            if (demoRepo.exists()) {
                args = listOf(demoRepo.absolutePath)
                // Reset the demo repo to its pristine state on every runIde. Otherwise
                // Apply-fix edits from a previous sandbox session persist on disk and the
                // demo no longer shows the "broken" file that needs a remediation. Best-
                // effort: ignore failures so a partially-cloned repo doesn't break the boot.
                try {
                    val pb = ProcessBuilder("git", "reset", "--hard", "HEAD")
                        .directory(demoRepo)
                        .redirectErrorStream(true)
                    val proc = pb.start()
                    val out = proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                    println("[runIde] demo-repo reset: ${out.trim().take(200)}")
                    // Also clear untracked files that might have been created (e.g. .class).
                    ProcessBuilder("git", "clean", "-fd")
                        .directory(demoRepo)
                        .redirectErrorStream(true)
                        .start().waitFor()
                } catch (t: Throwable) {
                    println("[runIde] demo-repo reset failed (continuing): ${t.message}")
                }
                // Demo seed: simulate a developer mid-regression by removing the INC-4402
                // guard from PaymentServiceImpl.charge. With this seed in place, the "Analyze
                // PR Risk" button sees a diff that deletes the guard lines → Codex can flag
                // it as a re-introduction of INC-4402. Best-effort: a stale/failed seed is
                // far better than a broken `./gradlew runIde`.
                try {
                    // Resolve path relative to demoRepo's parent to avoid capturing a live
                    // Gradle script reference (breaks configuration cache).
                    val patchFile = File(demoRepo.parentFile.parentFile,
                        "scripts/demo-seeds/remove-inc4402-guard.patch")
                    if (!patchFile.exists()) {
                        println("[runIde] demo seed skipped: patch file missing at ${patchFile.absolutePath}")
                    } else {
                        val applyProc = ProcessBuilder(
                            "git", "apply", patchFile.absolutePath
                        ).directory(demoRepo).redirectErrorStream(true).start()
                        val applyOut = applyProc.inputStream.bufferedReader().readText()
                        applyProc.waitFor()
                        if (applyProc.exitValue() == 0) {
                            println("[runIde] demo seed: removed INC-4402 guard from PaymentServiceImpl.charge")
                        } else {
                            println("[runIde] demo seed failed (continuing): ${applyOut.trim().take(400)}")
                        }
                    }
                } catch (t: Throwable) {
                    println("[runIde] demo seed failed (continuing): ${t.message}")
                }
            }
        }
        jvmArgs("-Xmx2g")
    }

    // The `uiSmokeTest` task is a plain JUnit4 Test task. It connects to a
    // SEPARATELY-running `runIdeForUiTests` over http://127.0.0.1:8082.
    //
    // Intentionally does NOT depend on `runIdeForUiTests` — that task blocks
    // until the IDE quits, so making it a dependency would deadlock. The
    // developer's workflow is:
    //
    //   Terminal A: JAVA_HOME=... ./gradlew runIdeForUiTests
    //   Terminal B: JAVA_HOME=... ./gradlew uiSmokeTest
    //
    // See README-UI-TESTING.md.
    register<Test>("uiSmokeTest") {
        group = "verification"
        description = "remote-robot UI smoke tests. Requires `runIdeForUiTests` running in another terminal."
        useJUnit()
        testClassesDirs = sourceSets["uiSmokeTest"].output.classesDirs
        classpath = sourceSets["uiSmokeTest"].runtimeClasspath
        shouldRunAfter("test")
        systemProperty("robot.server.url", "http://127.0.0.1:8082")
        systemProperty("demo.repo.path", layout.projectDirectory.dir("demo-repos/tsre-microservices").asFile.absolutePath)
        systemProperty("uiSmokeTest.screenshots.dir", layout.buildDirectory.dir("uiSmokeTest-screenshots").get().asFile.absolutePath)
        outputs.upToDateWhen { false }
    }

    // Belt-and-suspenders: make sure `check` (which `test` is part of) never
    // grows a transitive dep on uiSmokeTest.
    named("check") {
        // no-op; just explicit that uiSmokeTest is excluded.
    }

    // ---------------------------------------------------------------------------
    // `recordDemo` — run the 3 demo scenarios against live OpenAI and capture the
    // full event stream to src/main/resources/fallback/*.json. Run this ONCE before
    // the demo on a machine with working WiFi. Hitting real OpenAI — never invoked
    // from Stop hook or `check`.
    //
    // Usage:
    //   export OPENAI_API_KEY=sk-...
    //   ./gradlew recordDemo
    //
    // Gracefully no-ops (exit 0, warning only) if OPENAI_API_KEY is unset.
    // ---------------------------------------------------------------------------
    register<JavaExec>("recordDemo") {
        group = "verification"
        description = "Record live OpenAI agent-mode runs into src/main/resources/fallback/."
        mainClass.set("com.proactive.codex.RecordDemo")
        classpath = sourceSets["main"].runtimeClasspath
        // Don't fail CI / Stop-hook if the user never runs this task.
        isIgnoreExitValue = true
        // Propagate real env vars for key lookup.
        environment = System.getenv()
    }
}
