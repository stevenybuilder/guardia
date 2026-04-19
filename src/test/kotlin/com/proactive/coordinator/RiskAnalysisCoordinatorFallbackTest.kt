package com.proactive.coordinator

import com.proactive.codex.CodexAgentLoop
import com.proactive.codex.CodexInput
import com.proactive.domain.Diff
import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskHeuristic
import com.proactive.risk.RiskRequest
import com.proactive.risk.RiskVerdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the fallback-ladder hardening: no matter what the codex layer does — synchronous
 * throw inside the flow builder, async throw mid-collect, or even a runtime explosion after
 * the inner try/catch's emit() — the coordinator's collector must see a terminal
 * FallbackEngaged + Finalized pair rather than an uncaught exception.
 */
class RiskAnalysisCoordinatorFallbackTest {

    private val request = RiskRequest(
        diff = Diff(
            text = "-  if (billingAddress != null)",
            touchedFiles = listOf("PaymentService.java"),
            linesChanged = 12,
        ),
        candidateServices = listOf("payment-service"),
    )

    private val baseline = BaselineScore(
        score = 60,
        factors = mapOf("error_rate_delta" to 32.0),
        affectedServices = listOf("payment-service"),
    )

    private val fallbackVerdict = RiskVerdict(
        finalScore = 60,
        baselineScore = 60,
        codexDelta = 0,
        affectedServices = listOf("payment-service"),
        recommendation = "Heuristic-only (cached).",
        citations = emptyList(),
    )

    private fun coordinator(codex: CodexAgentLoop) = RiskAnalysisCoordinator(
        heuristic = RiskHeuristic { _, _, _ -> baseline },
        codex = codex,
        servicesFor = { emptyList() },
        incidentsFor = { emptyList() },
        fallback = RiskAnalysisCoordinator.FallbackProvider { _, _ -> fallbackVerdict },
    )

    @Test
    fun synchronous_IllegalStateException_from_codex_still_yields_FallbackEngaged_and_Finalized() {
        // Mimics the reported bug: OpenAIResponsesClient throws "OpenAI Responses API 400: …"
        // synchronously from inside the codex flow builder. Coordinator must not propagate.
        val codex = object : CodexAgentLoop {
            override fun run(input: CodexInput): Flow<RiskEvent> = flow {
                error("mock 400")
            }
        }

        val events = runBlocking { coordinator(codex).analyze(request).toList() }

        // Baseline still computed and emitted first.
        assertTrue(events.first() is RiskEvent.BaselineComputed, "baseline must still be emitted first")
        // Must contain a FallbackEngaged somewhere in the stream.
        assertTrue(
            events.any { it is RiskEvent.FallbackEngaged },
            "expected FallbackEngaged, got: ${events.map { it::class.simpleName }}",
        )
        // Stream must terminate on a Finalized (so the panel moves out of RUNNING).
        val last = events.last()
        assertTrue(last is RiskEvent.Finalized, "last event must be Finalized, was: ${last::class.simpleName}")
        assertEquals(fallbackVerdict, last.verdict)
    }

    @Test
    fun exception_during_baseline_computation_is_caught_and_surfaces_Finalized() {
        // Baseline itself blows up — the `.catch` belt-and-suspenders at the coordinator's
        // returned flow boundary should still produce FallbackEngaged + Finalized.
        val explodingHeuristic = RiskHeuristic { _, _, _ -> error("heuristic exploded") }
        val unusedCodex = object : CodexAgentLoop {
            override fun run(input: CodexInput): Flow<RiskEvent> = flow { /* never reached */ }
        }
        val coord = RiskAnalysisCoordinator(
            heuristic = explodingHeuristic,
            codex = unusedCodex,
            servicesFor = { emptyList() },
            incidentsFor = { emptyList() },
            fallback = RiskAnalysisCoordinator.FallbackProvider { _, baseline ->
                RiskVerdict(
                    finalScore = baseline.score.coerceIn(0, 100),
                    baselineScore = baseline.score.coerceIn(0, 100),
                    codexDelta = 0,
                    affectedServices = baseline.affectedServices,
                    recommendation = "degraded",
                    citations = emptyList(),
                )
            },
        )
        val events = runBlocking { coord.analyze(request).toList() }
        assertTrue(
            events.any { it is RiskEvent.FallbackEngaged },
            "expected FallbackEngaged on baseline blow-up, got: ${events.map { it::class.simpleName }}",
        )
        val finalized = events.filterIsInstance<RiskEvent.Finalized>().firstOrNull()
        assertNotNull(finalized, "expected a Finalized verdict even when baseline exploded")
    }
}
