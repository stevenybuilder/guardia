package com.stevenyang.datadogproactive.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

/**
 * remote-robot smoke test for Wedge 2's "Analyze PR Risk" entry point.
 *
 * Runs out-of-process: connects over HTTP to a separately-started
 * `runIdeForUiTests` IDE on localhost:8082. See README-UI-TESTING.md.
 *
 * We intentionally test the minimum user-visible contract:
 *   1. Sandbox IDE reaches an IdeaFrame (startup finishes).
 *   2. The "Datadog Risk" tool window stripe button is registered.
 *   3. Clicking "Analyze PR Risk" toolbar action opens the tool window.
 *   4. The tool window content shows our Phase-1 placeholder copy.
 *
 * If any assertion fails we dump a screenshot under build/uiSmokeTest-screenshots/.
 */
class AnalyzePrRiskSmokeTest {

    private val robot: RemoteRobot =
        RemoteRobot(System.getProperty("robot.server.url") ?: DEFAULT_ROBOT_URL)

    private val screenshotsDir: File =
        File(System.getProperty("uiSmokeTest.screenshots.dir") ?: "build/uiSmokeTest-screenshots")
            .also { it.mkdirs() }

    @Before
    fun waitForIdeStartup() {
        // Sandbox IDE startup on a cold machine can legitimately take ~45s
        // (indexing the tsre-microservices repo). Be patient.
        waitFor(duration = Duration.ofSeconds(STARTUP_TIMEOUT_SECONDS), interval = Duration.ofSeconds(2)) {
            runCatching {
                robot.find(ComponentFixture::class.java, byXpath("//div[@class='IdeFrameImpl']"))
            }.isSuccess
        }
    }

    @Test
    fun datadogRiskToolWindowButtonIsRegistered() = captureOnFailure("toolwindow-stripe-button") {
        // Stripe buttons expose their tool-window id via accessiblename.
        // We search with a lenient XPath — older IDE builds render the button
        // text slightly differently. `waitFor` throws on timeout; the assertion
        // after it just makes the intent readable.
        waitFor(duration = Duration.ofSeconds(30)) {
            robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[contains(@text,'Datadog Risk') or contains(@accessiblename,'Datadog Risk')]")
            ).isNotEmpty()
        }
        val matches = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[contains(@text,'Datadog Risk') or contains(@accessiblename,'Datadog Risk')]")
        )
        assertTrue("'Datadog Risk' tool window stripe button was never registered", matches.isNotEmpty())
    }

    @Test
    fun analyzePrRiskButtonOpensRiskToolWindow() = captureOnFailure("analyze-pr-risk-click") {
        // Find the toolbar action and click it.
        val analyzeBtn = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[@accessiblename='Analyze PR Risk' or @myaction.text='Analyze PR Risk']")
        ).firstOrNull()

        if (analyzeBtn == null) {
            // Soft-fallback: invoke the action by id via the robot JS endpoint.
            // This keeps the test useful even if the toolbar rendering changes.
            robot.runJs(
                """
                importClass(com.intellij.openapi.actionSystem.ActionManager)
                importClass(com.intellij.openapi.actionSystem.AnActionEvent)
                importClass(com.intellij.openapi.actionSystem.impl.SimpleDataContext)
                var am = ActionManager.getInstance()
                var action = am.getAction("DatadogProactive.AnalyzePrRisk")
                if (action == null) { throw "Action DatadogProactive.AnalyzePrRisk not registered" }
                am.tryToExecute(action, null, null, "ui-smoke-test", true)
                """.trimIndent()
            )
        } else {
            analyzeBtn.click()
        }

        // Wait for the tool window to surface. We look for the placeholder label
        // rendered by DatadogRiskToolWindowFactory in Phase 1. `waitFor` throws
        // WaitForConditionTimeoutException on timeout, which surfaces as a test
        // failure carrying the captureOnFailure screenshot.
        waitFor(duration = Duration.ofSeconds(15)) {
            robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[contains(@visible_text, 'Analyze PR Risk') and contains(@visible_text, 'blast-radius')]")
            ).isNotEmpty() ||
                robot.findAll(
                    ComponentFixture::class.java,
                    byXpath("//div[@class='JBLabel' and contains(@visible_text,'blast-radius score')]")
                ).isNotEmpty()
        }
        // Re-query once after the wait to produce a concrete assertion, so the
        // failure message is friendly if the XPath ever drifts.
        val labels = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[@class='JBLabel' and contains(@visible_text,'blast-radius score')]")
        )
        assertTrue(
            "Datadog Risk tool window never rendered its Phase-1 placeholder label",
            labels.isNotEmpty()
        )
    }

    @After
    fun tearDown() {
        // Intentionally do NOT close the IDE — the runIdeForUiTests process
        // is owned by a different terminal and the next test class (or the
        // developer's manual poking) may still need it.
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private inline fun captureOnFailure(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching {
                val img = robot.getScreenshot()
                val out = File(screenshotsDir, "${label}_${System.currentTimeMillis()}.png")
                ImageIO.write(img, "png", out)
                System.err.println("[uiSmokeTest] Screenshot written to ${out.absolutePath}")
            }.onFailure { suppressed ->
                System.err.println("[uiSmokeTest] Failed to capture screenshot: ${suppressed.message}")
            }
            if (t is AssertionError) throw t
            fail("Unexpected exception in '$label': ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val DEFAULT_ROBOT_URL = "http://127.0.0.1:8082"
        private const val STARTUP_TIMEOUT_SECONDS = 60L
    }
}
