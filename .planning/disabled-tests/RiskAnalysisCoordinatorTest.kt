package com.proactive.coordinator

import com.proactive.codex.FakeCodexAgentLoop
import com.proactive.domain.Diff
import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskHeuristic
import com.proactive.risk.RiskRequest
import com.proactive.risk.RiskVerdict
import com.proactive.ui.RiskUiState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskAnalysisCoordinatorTest {

    private val request = RiskRequest(
        diff = Diff(text = "-  if (x != null)", touchedFiles = listOf("PaymentService.java"), linesChanged = 12),
        candidateServices = listOf("payment-service", "order-service"),
    )

    private val fixedBaseline = BaselineScore(
        score = 60,
        factors = mapOf("error_rate_delta" to 32.0, "deploy_window" to 15.0),
        affectedServices = listOf("payment-service", "order-service"),
    )

    private val fixedHeuristic = RiskHeuristic { _, _, _ -> fixedBaseline }

    private val fallbackVerdict = RiskVerdict(
        finalScore = 60,
        baselineScore = 60,
        codexDelta = 0,
        affectedServices = listOf("payment-service"),
        recommendation = "Heuristic-only (cached).",
        citations = emptyList(),
    )

    private fun coordinator(
        codex: com.proactive.codex.CodexAgentLoop,
    ) = RiskAnalysisCoordinator(
        heuristic = fixedHeuristic,
        codex = codex,
        servicesFor = { emptyList() },
        incidentsFor = { emptyList() },
        fallback = RiskAnalysisCoordinator.FallbackProvider { _, _ -> fallbackVerdict },
    )

    @Test
    fun happy_path_emits_baseline_first_then_codex_stream_then_finalized() = runTest {
        val codex = FakeCodexAgentLoop(
            scenario = FakeCodexAgentLoop.scenarioA_regressionINC4402(),
            stepDelayMs = 0,
        )
        val events = coordinator(codex).analyze(request).toList()

        assertTrue(events.first() is RiskEvent.BaselineComputed, "baseline must be first")
        val finalized = events.last() as RiskEvent.Finalized
        assertEquals(85, finalized.verdict.finalScore)
        assertEquals(25, finalized.verdict.codexDelta)
        assertEquals(listOf("INC-4402"), finalized.verdict.citations)

        val incidentEvents = events.filterIsInstance<RiskEvent.IncidentsRetrieved>()
        assertEquals(1, incidentEvents.size, "IncidentsRetrieved must fire exactly once on scenario A")
    }

    @Test
    fun codex_failure_engages_fallback_and_still_finalizes() = runTest {
        val codex = FakeCodexAgentLoop(
            scenario = FakeCodexAgentLoop.scenarioA_regressionINC4402(),
            stepDelayMs = 0,
            throwBefore = 2,
        )
        val events = coordinator(codex).analyze(request).toList()

        assertTrue(events.any { it is RiskEvent.FallbackEngaged })
        val finalized = events.last() as RiskEvent.Finalized
        assertEquals(fallbackVerdict, finalized.verdict, "fallback verdict must be surfaced verbatim")
    }

    @Test
    fun full_stream_reduces_to_a_done_ui_state_with_animated_score_transition() = runTest {
        val codex = FakeCodexAgentLoop(FakeCodexAgentLoop.scenarioA_regressionINC4402(), stepDelayMs = 0)
        val finalUi = coordinator(codex).analyze(request)
            .toList()
            .fold(RiskUiState.EMPTY, RiskUiState.Companion::reduce)

        assertEquals(RiskUiState.Phase.DONE, finalUi.phase)
        assertEquals(60, finalUi.baselineScore, "baseline chip must animate FROM 60")
        assertEquals(85, finalUi.finalScore, "score must animate TO 85")
        assertEquals(listOf("INC-4402"), finalUi.citations)
        assertTrue(finalUi.trace.any { it.tool == "get_related_incidents" && !it.running })
        assertTrue(finalUi.retrievedIncidents.isNotEmpty(), "editor flash requires an incident")
    }
}
