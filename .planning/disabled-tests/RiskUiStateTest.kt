package com.proactive.ui

import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskUiStateTest {

    @Test
    fun baseline_moves_state_to_running_and_starts_trace() {
        val s = RiskUiState.reduce(
            RiskUiState.EMPTY,
            RiskEvent.BaselineComputed(60, mapOf("error_rate_delta" to 32.0)),
        )
        assertEquals(RiskUiState.Phase.RUNNING, s.phase)
        assertEquals(60, s.baselineScore)
        assertNull(s.finalScore)
        assertEquals(1, s.trace.size)
        assertEquals(RiskUiState.TraceRow.Kind.BASELINE, s.trace.single().kind)
    }

    @Test
    fun tool_call_started_then_completed_upserts_same_row() {
        val s0 = RiskUiState.EMPTY
            .let { RiskUiState.reduce(it, RiskEvent.BaselineComputed(60, emptyMap())) }
            .let { RiskUiState.reduce(it, RiskEvent.ToolCallStarted(2, "get_diff")) }
        assertEquals(2, s0.trace.size)
        assertTrue(s0.trace.last().running)
        assertNull(s0.trace.last().elapsedMs)

        val s1 = RiskUiState.reduce(s0, RiskEvent.ToolCallCompleted(2, "get_diff", "12 lines", 40))
        assertEquals(2, s1.trace.size, "completed event must upsert same step, not append")
        assertEquals(false, s1.trace.last().running)
        assertEquals(40L, s1.trace.last().elapsedMs)
        assertEquals("12 lines", s1.trace.last().summary)
    }

    @Test
    fun finalized_sets_done_and_populates_verdict_fields() {
        val verdict = RiskVerdict(
            finalScore = 85,
            baselineScore = 60,
            codexDelta = 25,
            affectedServices = listOf("payment-service"),
            recommendation = "Hold",
            citations = listOf("INC-4402"),
        )
        val s = listOf(
            RiskEvent.BaselineComputed(60, emptyMap()),
            RiskEvent.Finalized(verdict),
        ).fold(RiskUiState.EMPTY, RiskUiState.Companion::reduce)

        assertEquals(RiskUiState.Phase.DONE, s.phase)
        assertEquals(85, s.finalScore)
        assertEquals(listOf("INC-4402"), s.citations)
        assertEquals("Hold", s.recommendation)
    }

    @Test
    fun fallback_then_finalize_keeps_phase_fallback() {
        val verdict = RiskVerdict(
            finalScore = 60,
            baselineScore = 60,
            codexDelta = 0,
            affectedServices = emptyList(),
            recommendation = "Baseline only",
            citations = emptyList(),
        )
        val s = listOf<RiskEvent>(
            RiskEvent.BaselineComputed(60, emptyMap()),
            RiskEvent.FallbackEngaged("timeout"),
            RiskEvent.Finalized(verdict),
        ).fold(RiskUiState.EMPTY, RiskUiState.Companion::reduce)

        assertEquals(RiskUiState.Phase.FALLBACK, s.phase)
        assertEquals("timeout", s.fallbackReason)
        assertEquals(60, s.finalScore)
    }

    @Test
    fun reducer_is_idempotent_on_duplicate_tool_complete() {
        val events = listOf<RiskEvent>(
            RiskEvent.BaselineComputed(60, emptyMap()),
            RiskEvent.ToolCallStarted(2, "get_diff"),
            RiskEvent.ToolCallCompleted(2, "get_diff", "x", 10),
            RiskEvent.ToolCallCompleted(2, "get_diff", "x", 10),
        )
        val s = events.fold(RiskUiState.EMPTY, RiskUiState.Companion::reduce)
        assertEquals(2, s.trace.size)
    }
}
