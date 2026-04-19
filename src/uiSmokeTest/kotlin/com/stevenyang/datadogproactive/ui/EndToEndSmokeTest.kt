package com.stevenyang.datadogproactive.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

/**
 * End-to-end happy-path smoke test for Wedge 1 + Wedge 2 on `PaymentServiceImpl.charge`
 * (one of the 7 real tsre methods wired into the fixture after the patch wave).
 *
 * Runs out-of-process against a `runIdeForUiTests` sandbox on 127.0.0.1:8082.
 * See README-UI-TESTING.md.
 *
 * Each numbered test corresponds to one of the 8 mission assertions:
 *   01 — Tool window "Datadog Risk" is registered.
 *   02 — Methods-at-risk inbox renders ≥ 1 row.
 *   03 — Row for `PaymentServiceImpl.charge` is present.
 *   04 — Clicking that row opens editor with PaymentServiceImpl.java (detail card expands).
 *   05 — Red dotted-underline RangeHighlighter exists on ≥ 1 line of that editor.
 *   06 — "⇔ Compare to your code" opens a DiffViewerBase.
 *   07 — "Live Feed" tab exists (JCEF or fallback panel).
 *   08 — Screenshot each checkpoint → build/uiSmokeTest-screenshots/.
 *
 * FixMethodOrder: we run in alphabetical order so expensive bootstrap (open tool window /
 * click row) happens once at a known point in the sequence.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndSmokeTest {

    private val robot: RemoteRobot =
        RemoteRobot(System.getProperty("robot.server.url") ?: DEFAULT_ROBOT_URL)

    private val screenshotsDir: File =
        File(System.getProperty("uiSmokeTest.screenshots.dir") ?: "build/uiSmokeTest-screenshots")
            .also { it.mkdirs() }

    @Before
    fun waitForIdeStartup() {
        waitFor(duration = Duration.ofSeconds(STARTUP_TIMEOUT_SECONDS), interval = Duration.ofSeconds(2)) {
            runCatching {
                robot.find(ComponentFixture::class.java, byXpath("//div[@class='IdeFrameImpl']"))
            }.isSuccess
        }
    }

    // ---------------------------------------------------------------------------
    // 01 — Tool window is registered (stripe button visible).
    // ---------------------------------------------------------------------------
    @Test
    fun test01_datadogRiskToolWindowRegistered() = capture("01-toolwindow-registered") {
        waitFor(duration = Duration.ofSeconds(45)) {
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

    // ---------------------------------------------------------------------------
    // 02 — Open the tool window and verify the methods-at-risk inbox has ≥ 1 row.
    // ---------------------------------------------------------------------------
    @Test
    fun test02_inboxRendersRows() = capture("02-inbox-rows") {
        openRiskToolWindow()
        // A Row in MethodsAtRiskInbox is a plain JPanel, so xpath on @class='JPanel' +
        // @cursor='Hand Cursor' is a stable heuristic. But we take the more reliable
        // approach of looking at the inbox header label (stable literal copy) and
        // checking there are JBLabel components with FQ-name style html bold wrapping.
        waitFor(duration = Duration.ofSeconds(15)) {
            robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[@class='JBLabel' and contains(@visible_text,'Methods at risk in this project')]")
            ).isNotEmpty()
        }
        val fqnLabels = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[@class='JBLabel' and contains(@visible_text,'.')]")
        )
        assertTrue(
            "Methods-at-risk inbox rendered no FQ-name rows",
            fqnLabels.size >= 1
        )
    }

    // ---------------------------------------------------------------------------
    // 03 — Row for `PaymentServiceImpl.charge` exists in the inbox.
    // ---------------------------------------------------------------------------
    @Test
    fun test03_paymentServiceImplChargeRowPresent() = capture("03-row-paymentserviceimpl-charge") {
        openRiskToolWindow()
        waitFor(duration = Duration.ofSeconds(15)) {
            robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[@class='JBLabel' and contains(@visible_text,'PaymentServiceImpl.charge')]")
            ).isNotEmpty()
        }
        val rowLabel = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[@class='JBLabel' and contains(@visible_text,'PaymentServiceImpl.charge')]")
        )
        assertTrue(
            "No inbox row found for fq_name PaymentServiceImpl.charge (expected after fixture patch)",
            rowLabel.isNotEmpty()
        )
    }

    // ---------------------------------------------------------------------------
    // 04 — Click the PaymentServiceImpl.charge row → editor opens for PaymentServiceImpl.java
    // AND the detail card ("Past incidents that match this method" header) renders.
    // ---------------------------------------------------------------------------
    @Test
    fun test04_clickRowOpensEditorAndExpandsDetail() = capture("04-click-row-expand") {
        openRiskToolWindow()
        // Resolve and click the row via JS so we don't rely on pixel-accurate mouse-click,
        // which is flaky on headless CI. We look up the tool window's content component
        // by scanning SwingUtilities for a MethodsAtRiskInbox$Row whose fqName matches.
        robot.runJs(
            """
            importPackage(com.intellij.openapi.project)
            importPackage(com.intellij.openapi.wm)
            importPackage(java.awt)
            importPackage(javax.swing)

            function findRow(comp) {
              if (comp == null) return null
              var cls = comp.getClass().getName()
              if (cls.indexOf('MethodsAtRiskInbox${'$'}Row') >= 0) {
                try {
                  var f = comp.getClass().getDeclaredField('fqName')
                  f.setAccessible(true)
                  var fq = f.get(comp)
                  if (fq != null && fq.toString() == 'PaymentServiceImpl.charge') return comp
                } catch (ignore) {}
              }
              if (comp instanceof Container) {
                var kids = comp.getComponents()
                for (var i = 0; i < kids.length; i++) {
                  var r = findRow(kids[i])
                  if (r != null) return r
                }
              }
              return null
            }

            var project = ProjectManager.getInstance().getOpenProjects()[0]
            var tw = ToolWindowManager.getInstance(project).getToolWindow('Datadog Risk')
            if (tw == null) { throw 'Datadog Risk tool window missing' }
            tw.show(null)

            var roots = tw.getComponent()
            var row = findRow(roots)
            if (row == null) { throw 'Row for PaymentServiceImpl.charge not found in inbox' }

            // Dispatch a MOUSE_CLICKED event on the row's center, which is what
            // MethodsAtRiskInbox${'$'}Row's MouseAdapter listens for.
            var x = row.getWidth() / 2
            var y = row.getHeight() / 2
            var me = new java.awt.event.MouseEvent(
              row, java.awt.event.MouseEvent.MOUSE_CLICKED,
              java.lang.System.currentTimeMillis(), 0, x, y, 1, false,
              java.awt.event.MouseEvent.BUTTON1
            )
            for each (var l in row.getMouseListeners()) l.mouseClicked(me)
            true
            """.trimIndent()
        )

        // Now wait for the editor to open for PaymentServiceImpl.java.
        waitFor(duration = Duration.ofSeconds(20)) {
            openEditorFiles().any { it.endsWith("PaymentServiceImpl.java") }
        }
        val openFiles = openEditorFiles()
        assertTrue(
            "Editor never opened PaymentServiceImpl.java; open files: $openFiles",
            openFiles.any { it.endsWith("PaymentServiceImpl.java") }
        )

        // Detail card expansion: the "Past incidents that match this method" bold
        // header label is only present inside a MethodDetailCard.
        waitFor(duration = Duration.ofSeconds(10)) {
            robot.findAll(
                ComponentFixture::class.java,
                byXpath(
                    "//div[@class='JBLabel' and contains(@visible_text,'Past incidents that match this method')]"
                )
            ).isNotEmpty()
        }
        val detailHeader = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[@class='JBLabel' and contains(@visible_text,'Past incidents that match this method')]")
        )
        assertTrue(
            "Detail card never expanded (no 'Past incidents that match this method' header)",
            detailHeader.isNotEmpty()
        )
    }

    // ---------------------------------------------------------------------------
    // 05 — ≥1 red dotted-underline RangeHighlighter on the opened editor.
    // ---------------------------------------------------------------------------
    @Test
    fun test05_editorHasRiskHighlighter() = capture("05-editor-highlighter") {
        // Ensure 04 has been exercised: re-open if needed so tests can run in isolation.
        openRiskToolWindow()
        robot.runJs(
            """
            importPackage(com.intellij.openapi.project)
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.wm)
            var project = ProjectManager.getInstance().getOpenProjects()[0]
            // Nudge ProactiveRiskHighlighter in case the file was already open.
            var svc = project.getService(Java.type('com.stevenyang.datadogproactive.editor.ProactiveRiskHighlighter'))
            var fem = FileEditorManager.getInstance(project)
            var open = fem.getOpenFiles()
            for (var i = 0; i < open.length; i++) {
              if (open[i].getName() == 'PaymentServiceImpl.java') { svc.highlightFile(open[i]); break }
            }
            """.trimIndent()
        )

        // Poll for at least one bold-dotted-line highlighter on any editor attached to
        // PaymentServiceImpl.java. Use EditorFactory.allEditors + MarkupModel reflection.
        var seenHighlighters = 0
        var lastError: String? = null
        waitFor(duration = Duration.ofSeconds(30), interval = Duration.ofSeconds(2)) {
            try {
                val count = robot.callJs<Int>(
                    """
                    importPackage(com.intellij.openapi.editor)
                    importPackage(com.intellij.openapi.editor.markup)

                    var editors = EditorFactory.getInstance().getAllEditors()
                    var n = 0
                    for (var i = 0; i < editors.length; i++) {
                      var ed = editors[i]
                      var vf = ed.getVirtualFile()
                      if (vf == null) continue
                      if (!vf.getName().endsWith('PaymentServiceImpl.java')) continue
                      var mm = ed.getMarkupModel()
                      var hls = mm.getAllHighlighters()
                      for (var j = 0; j < hls.length; j++) {
                        var attrs = hls[j].getTextAttributes(null)
                        if (attrs == null) continue
                        if (attrs.getEffectType() == EffectType.BOLD_DOTTED_LINE) n++
                      }
                    }
                    n
                    """.trimIndent()
                ) ?: 0
                seenHighlighters = count
                count >= 1
            } catch (t: Throwable) {
                lastError = t.message
                false
            }
        }
        assertTrue(
            "Expected ≥1 BOLD_DOTTED_LINE highlighter on PaymentServiceImpl.java " +
                "(seen=$seenHighlighters, lastError=$lastError)",
            seenHighlighters >= 1
        )
    }

    // ---------------------------------------------------------------------------
    // 06 — "⇔ Compare to your code" opens a DiffViewerBase / diff window.
    // ---------------------------------------------------------------------------
    @Test
    fun test06_compareOpensDiff() = capture("06-compare-opens-diff") {
        // Invoke the IncidentDiffLauncher service directly with the first linked incident —
        // this is the exact path the link click follows (via MethodDetailCard.launchCompare).
        // Avoids depending on pixel-accurate Swing clicks inside nested BoxLayout.
        robot.runJs(
            """
            importPackage(com.intellij.openapi.project)
            importPackage(com.intellij.openapi.application)
            var project = ProjectManager.getInstance().getOpenProjects()[0]
            var svcCls = Java.type('com.datadog.proactive.datadog.DatadogService')
            var svc = project.getService(svcCls)
            var detail = svc.getMethodDetail('PaymentServiceImpl.charge')
            if (detail == null) { throw 'No MethodDetail for PaymentServiceImpl.charge' }
            var incidents = detail.getLinkedIncidents()
            if (incidents.isEmpty()) { throw 'No linked incidents — cannot test diff launcher' }
            var first = incidents.get(0)
            var launcherCls = Java.type('com.stevenyang.datadogproactive.diff.IncidentDiffLauncher')
            var launcher = project.getService(launcherCls)
            ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
              run: function() {
                launcher.showDiff('// placeholder for happy-path UI smoke', first, 'Your code — PaymentServiceImpl.charge')
              }
            }))
            """.trimIndent()
        )
        waitFor(duration = Duration.ofSeconds(15)) {
            robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[contains(@class,'DiffWindow') or contains(@class,'DiffViewer') or contains(@class,'SimpleDiffPanel') or contains(@title,'Same code, same bug')]")
            ).isNotEmpty()
        }
        val diffs = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[contains(@class,'DiffWindow') or contains(@class,'DiffViewer') or contains(@class,'SimpleDiffPanel') or contains(@title,'Same code, same bug')]")
        )
        assertTrue(
            "No DiffViewer surfaced after invoking IncidentDiffLauncher.showDiff",
            diffs.isNotEmpty()
        )
    }

    // ---------------------------------------------------------------------------
    // 07 — "Live Feed" tab present. Either a JBCefBrowser component is visible, or
    // the fallback link panel with its "Live Feed" + "Open local dashboard" button is.
    // ---------------------------------------------------------------------------
    @Test
    fun test07_liveFeedTabPresent() = capture("07-live-feed-tab") {
        openRiskToolWindow()
        // Select the Live Feed tab via ContentManager so we don't depend on tab-strip xpath.
        robot.runJs(
            """
            importPackage(com.intellij.openapi.project)
            importPackage(com.intellij.openapi.wm)
            var project = ProjectManager.getInstance().getOpenProjects()[0]
            var tw = ToolWindowManager.getInstance(project).getToolWindow('Datadog Risk')
            tw.show(null)
            var cm = tw.getContentManager()
            var contents = cm.getContents()
            for (var i = 0; i < contents.length; i++) {
              if (contents[i].getDisplayName() == 'Live Feed') {
                cm.setSelectedContent(contents[i])
                break
              }
            }
            """.trimIndent()
        )
        // Wait for EITHER a JBCefBrowser component OR the fallback panel's identifying label.
        waitFor(duration = Duration.ofSeconds(15)) {
            val jcef = robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[contains(@class,'JBCefOsrComponent') or contains(@class,'JBCefBrowser')]")
            )
            val fallback = robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[@class='JBLabel' and contains(@visible_text,'Live Feed')]")
            )
            jcef.isNotEmpty() || fallback.isNotEmpty()
        }
        val jcef = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[contains(@class,'JBCefOsrComponent') or contains(@class,'JBCefBrowser')]")
        )
        val fallback = robot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[@class='JBLabel' and contains(@visible_text,'Live Feed')]")
        )
        assertTrue(
            "Neither JCEF browser nor Live Feed fallback label rendered",
            jcef.isNotEmpty() || fallback.isNotEmpty()
        )
    }

    @After
    fun tearDown() {
        // Intentionally leave the IDE running — owner is the separate gradle process.
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    /** Shows the Datadog Risk tool window (idempotent). */
    private fun openRiskToolWindow() {
        robot.runJs(
            """
            importPackage(com.intellij.openapi.project)
            importPackage(com.intellij.openapi.wm)
            var project = ProjectManager.getInstance().getOpenProjects()[0]
            var tw = ToolWindowManager.getInstance(project).getToolWindow('Datadog Risk')
            if (tw != null) { tw.show(null) }
            """.trimIndent()
        )
        // Wait for at least one toolwindow child to appear — signals content factory fired.
        waitFor(duration = Duration.ofSeconds(20)) {
            robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[@class='JBLabel' and contains(@visible_text,'Methods at risk')]")
            ).isNotEmpty()
        }
    }

    private fun openEditorFiles(): List<String> = try {
        val raw = robot.callJs<String>(
            """
            importPackage(com.intellij.openapi.project)
            importPackage(com.intellij.openapi.fileEditor)
            var project = ProjectManager.getInstance().getOpenProjects()[0]
            var fem = FileEditorManager.getInstance(project)
            var files = fem.getOpenFiles()
            var names = []
            for (var i = 0; i < files.length; i++) names.push(files[i].getPath())
            names.join('|')
            """.trimIndent()
        )
        if (raw.isNullOrBlank()) emptyList() else raw.split("|")
    } catch (t: Throwable) {
        emptyList()
    }

    /** Screenshot every checkpoint (both success and failure) + propagate assertion. */
    private inline fun capture(label: String, block: () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (t: Throwable) {
            thrown = t
        }
        // Always attempt a screenshot — success or fail.
        runCatching {
            val img = robot.getScreenshot()
            val out = File(screenshotsDir, "${label}_${System.currentTimeMillis()}.png")
            ImageIO.write(img, "png", out)
            System.err.println("[uiSmokeTest] Screenshot $label → ${out.absolutePath}")
        }.onFailure { suppressed ->
            System.err.println("[uiSmokeTest] Screenshot capture for $label failed: ${suppressed.message}")
        }
        if (thrown != null) {
            if (thrown is AssertionError) throw thrown
            fail("Unexpected exception in '$label': ${thrown.javaClass.simpleName}: ${thrown.message}")
        }
    }

    companion object {
        private const val DEFAULT_ROBOT_URL = "http://127.0.0.1:8082"
        private const val STARTUP_TIMEOUT_SECONDS = 90L
    }
}
