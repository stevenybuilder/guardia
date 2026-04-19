package com.stevenyang.datadogproactive.editor

import com.datadog.proactive.datadog.DatadogService
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.stevenyang.datadogproactive.action.AppliedPatchesIndex

/**
 * Exercises the red pre-fix flag highlighter.
 *
 * Strategy: configure a Java file that contains a method matching a methods-of-interest
 * fq_name with at least one RESOLVED linked incident. `paintForFile` should install a
 * persistent RangeHighlighter on the signature line. Recording a fingerprint in
 * [AppliedPatchesIndex] that matches text in the document and calling paint again should
 * clear the red — proving the pre-fix → post-fix handoff.
 */
class ProactiveRiskFlagHighlighterTest : BasePlatformTestCase() {

    fun testPaintsRedOnResolvedUnfixedIncident() {
        val project = myFixture.project
        val ds = project.getService(DatadogService::class.java)
        // Pick a method-of-interest fq_name that actually has a resolved linked incident
        // in the bundled fixture. `PaymentService.charge` is guaranteed by CLAUDE.md §Mock
        // Fixtures to carry INC-4402 (resolved) among its linked incidents.
        val ctx = ds.getMethodContext("PaymentServiceImpl.charge")
        assertNotNull("Expected fixture to expose PaymentServiceImpl.charge methods-of-interest", ctx)
        val resolved = ctx!!.linkedIncidents.firstOrNull { !it.status.equals("open", ignoreCase = true) }
        assertNotNull(
            "Expected at least one resolved linked incident on PaymentServiceImpl.charge",
            resolved,
        )

        // Keep the test file minimal — single method matching a methods-of-interest entry
        // so we aren't accidentally flagging a sibling method with its own unfixed
        // incident chain.
        myFixture.configureByText(
            "PaymentServiceImpl.java",
            """
            package com.hipstershop.paymentservicejava;
            public class PaymentServiceImpl {
                public void charge(double amount) { }
            }
            """.trimIndent()
        )

        val vf = myFixture.file.virtualFile
        val highlighter = project.getService(ProactiveRiskFlagHighlighter::class.java)
        assertNotNull("Service should auto-register", highlighter)

        // Drain any file-open async paints first so they don't race our sync call below.
        drainAsyncPaints()
        highlighter.paintForFileSync(vf)

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull("Expected an open editor after configureByText", editor)
        val reds = editor!!.markupModel.allHighlighters.filter { isOurRed(it) }
        assertEquals(
            "Expected exactly one red-flag highlighter on the pre-fix method; got ${reds.size}",
            1,
            reds.size,
        )

        // Now simulate "the fix was applied" for every resolved linked incident by
        // recording a fingerprint that exists in the document. `paintForFile` should then
        // find no unfixed resolved incident and clear the red highlight.
        val index = project.getService(AppliedPatchesIndex::class.java)
        val fingerprint = "public void charge(double amount)"
        assertTrue(
            "Test fingerprint must be present in the sample document",
            editor.document.text.contains(fingerprint),
        )
        ctx.linkedIncidents
            .filter { !it.status.equals("open", ignoreCase = true) }
            .forEach { index.markApplied(it.id, "PaymentServiceImpl.charge", fingerprint) }

        // Drain any lingering async paints (e.g. ActiveFileListener) BEFORE our final sync.
        // They would otherwise race in after us and repaint the red we just cleared.
        drainAsyncPaints()
        highlighter.paintForFileSync(vf)
        // Drain one more time so any listener that fires on our markup change is processed
        // first — our sync call above is the authoritative last word.
        drainAsyncPaints()
        highlighter.paintForFileSync(vf)

        val redsAfter = editor.markupModel.allHighlighters.filter { isOurRed(it) }
        assertEquals(
            "Expected no red-flag highlighter after fingerprint recorded; got ${redsAfter.size}",
            0,
            redsAfter.size,
        )
    }

    /** Detect our red highlighter by its tooltip — no other gutter uses this string. */
    private fun isOurRed(hi: RangeHighlighter): Boolean {
        val tip = runCatching { hi.gutterIconRenderer?.tooltipText }.getOrNull() ?: return false
        return tip.contains("tied to resolved incident")
    }

    /**
     * Drain all pending EDT events + wait out any pooled-thread paints kicked off by
     * [com.stevenyang.datadogproactive.toolwindow.ActiveFileListener] on file-open.
     */
    private fun drainAsyncPaints() {
        repeat(8) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            try { Thread.sleep(25) } catch (_: InterruptedException) {}
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }
}
