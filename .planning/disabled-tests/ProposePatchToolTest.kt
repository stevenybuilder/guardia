package com.proactive.codex

import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the new `propose_patch` tool path + the `finalize_risk` handoff that stitches
 * the captured [com.proactive.risk.ProposedPatch] onto the terminal [com.proactive.risk.RiskVerdict].
 */
class ProposePatchToolTest {

    private val fallback = BaselineScore(
        score = 60,
        factors = mapOf("error_rate_delta" to 30.0),
        affectedServices = listOf("payment-service"),
    )

    @Test
    fun `DEFAULT_TOOLS contains propose_patch with required parameters`() {
        val tool = OpenAIResponsesClient.DEFAULT_TOOLS.firstOrNull { it["name"] == "propose_patch" }
        assertNotNull(tool, "propose_patch must be registered in DEFAULT_TOOLS")
        @Suppress("UNCHECKED_CAST")
        val params = tool["parameters"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val required = params["required"] as List<String>
        assertTrue(required.containsAll(
            listOf("cited_incident_id", "target_file_path", "target_fq_name", "unified_diff", "rationale"),
        ), "propose_patch is missing required fields: $required")
    }

    @Test
    fun `dispatch propose_patch stores patch and finalize_risk surfaces it on the verdict`(): Unit = runBlocking {
        val dispatcher = ToolDispatcher(
            project = null,
            diffProvider = { "" },
            baselineFallback = fallback,
        )
        val sink = FlowCollector<RiskEvent> { /* ignore */ }

        // 1. propose_patch is accepted and stored.
        val args = """
            {"cited_incident_id":"INC-4402",
             "target_file_path":"services/payment-service/PaymentService.java",
             "target_fq_name":"PaymentServiceImpl.charge",
             "unified_diff":"-a\n+b",
             "rationale":"Restores null check"}
        """.trimIndent()
        val out = dispatcher.dispatch("propose_patch", args, sink, step = 0)
        assertTrue(out.contains("\"accepted\":true"), "expected accepted marker in response: $out")
        val stored = dispatcher.lastProposedPatch()
        assertNotNull(stored)
        assertEquals("INC-4402", stored.citedIncidentId)

        // 2. finalize_risk emits a verdict with proposedPatch attached.
        val finalizeArgs = """
            {"final_score":85,"baseline_delta":25,"reasoning":"reintroduces INC-4402",
             "citations":["INC-4402"],"affected_services":["payment-service"]}
        """.trimIndent()
        val verdict = dispatcher.finalize(finalizeArgs)
        assertNotNull(verdict.proposedPatch)
        assertEquals("INC-4402", verdict.proposedPatch!!.citedIncidentId)
        assertEquals("PaymentServiceImpl.charge", verdict.proposedPatch!!.targetFqName)
        assertEquals("Restores null check", verdict.proposedPatch!!.rationale)
    }

    @Test
    fun `finalize_risk without propose_patch leaves proposedPatch null`() {
        val dispatcher = ToolDispatcher(
            project = null,
            diffProvider = { "" },
            baselineFallback = fallback,
        )
        val verdict = dispatcher.finalize(
            """{"final_score":60,"baseline_delta":0,"reasoning":"no risk","citations":[]}""",
        )
        assertNull(verdict.proposedPatch, "no propose_patch call → verdict.proposedPatch must be null")
    }
}
