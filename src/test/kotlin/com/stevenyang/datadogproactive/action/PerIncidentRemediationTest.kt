package com.stevenyang.datadogproactive.action

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.MethodDetail
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.proactive.risk.ProposedPatch

/**
 * Per-incident remediation coverage (CLAUDE.md Decision Log 2026-04-19).
 *
 * Asserts:
 *   1. The 4 demo-critical incidents (INC-4402 / 4417 / 4388 / 4213) ship with
 *      `resolution_text` AND `resolution_patch` populated — the Apply-fix button's
 *      fast path depends on these being present in the frozen fixture.
 *   2. An incident with no resolution signal is NOT fix-eligible — visibility gate.
 *   3. [ApplyRemediationAction.applyPatch]'s text-level apply path (reused by the
 *      per-incident button) swaps the `-` block for the `+` block on a document.
 */
class PerIncidentRemediationTest : BasePlatformTestCase() {

    fun testDemoCriticalIncidentsCarryResolutionPatch() {
        val svc = project.getService(DatadogService::class.java)
        val all = svc.allMethodsOfInterest()
        val refs = all.flatMap { svc.getMethodDetail(it.method.fq_name)?.linkedIncidents.orEmpty() }

        val demoIds = setOf("INC-4402", "INC-4417", "INC-4388", "INC-4213")
        val matches = refs.filter { it.id in demoIds }.associateBy { it.id }

        for (id in demoIds) {
            val ref = matches[id]
            assertNotNull("Expected demo incident $id to be surfaced via MethodDetail", ref)
            assertFalse(
                "$id must carry a non-blank resolution_patch for the fast-path Apply-fix button",
                ref!!.resolutionPatch.isNullOrBlank(),
            )
            assertFalse(
                "$id must carry a non-blank resolution_text for the tooltip + rationale",
                ref.resolutionText.isNullOrBlank(),
            )
            assertFalse(
                "$id must carry an offending_file so ProposedPatch.targetFilePath resolves",
                ref.offendingFile.isNullOrBlank(),
            )
        }
    }

    fun testFixEligibilityGate() {
        // Synthesize a minimal ref with no root cause / resolution / patch — must be ineligible.
        val emptyRef = MethodDetail.IncidentRef(
            id = "INC-EMPTY",
            title = "placeholder",
            severity = "SEV-3",
            status = "resolved",
            rootCause = null,
            offendingSnippetPreview = null,
            sourceUrl = null,
        )
        // Mirror the card's gate (MethodDetailCard.isFixEligible) here so the invariant
        // is pinned outside the Swing path.
        val eligible = !emptyRef.resolutionPatch.isNullOrBlank() ||
            !emptyRef.resolutionText.isNullOrBlank() ||
            (!emptyRef.rootCause.isNullOrBlank() && !emptyRef.offendingSnippetPreview.isNullOrBlank())
        assertFalse("Empty ref must not be fix-eligible", eligible)

        // Ref with root cause + snippet — eligible (second clause of the gate).
        val richRef = emptyRef.copy(
            rootCause = "Missing null check",
            offendingSnippetPreview = "x = y.z; // NPE",
        )
        val richEligible = !richRef.resolutionPatch.isNullOrBlank() ||
            !richRef.resolutionText.isNullOrBlank() ||
            (!richRef.rootCause.isNullOrBlank() && !richRef.offendingSnippetPreview.isNullOrBlank())
        assertTrue("Rich ref must be fix-eligible via root_cause + snippet", richEligible)
    }

    fun testApplyPatchSwapsMinusForPlusOnMockText() {
        // Exercise the pure-text helper shared by both the toolbar action and the
        // per-incident button — avoids needing a write-lock on an IntelliJ Document.
        val action = ApplyRemediationAction()
        val initial = """
            void charge() {
              String ccNumber = request.getCreditCard().getCreditCardNumber();
              log.info("charging");
            }
        """.trimIndent()

        val patch = ProposedPatch(
            citedIncidentId = "INC-4402",
            targetFilePath = "PaymentServiceImpl.java",
            targetFqName = "PaymentServiceImpl.charge",
            unifiedDiff = "--- PaymentServiceImpl.java\n" +
                "+++ PaymentServiceImpl.java\n" +
                "@@ charge @@\n" +
                "-  String ccNumber = request.getCreditCard().getCreditCardNumber();\n" +
                "+  if (request == null) return;\n" +
                "+  String ccNumber = request.getCreditCard().getCreditCardNumber();\n",
            rationale = "INC-4402",
        )

        val (minus, plus) = action.extractMinusPlus(patch.unifiedDiff)
        assertTrue("minus block must be non-blank", minus.isNotBlank())
        assertTrue("plus block must contain the guard", plus.contains("request == null"))

        val replaced = action.applyReplaceText(initial, minus, plus)
        assertNotNull("applyReplaceText must succeed against a matching text", replaced)
        assertTrue(
            "replaced text must contain the plus block: $replaced",
            replaced!!.contains("if (request == null) return;"),
        )
        assertTrue(
            "replaced text must retain the original ccNumber line unchanged inside the plus block",
            replaced.contains("String ccNumber = request.getCreditCard().getCreditCardNumber();"),
        )
    }
}
