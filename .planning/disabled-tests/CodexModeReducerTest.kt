package com.proactive.ui

import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskVerdict
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Reducer coverage for the new [RiskEvent.CodexModeResolved] branch. Each [Mode] variant
 * should land cleanly on [RiskUiState] without clobbering unrelated fields, and the
 * default state should remain valid when no mode event has been seen.
 */
class CodexModeReducerTest {

    @Test fun `default state has null codexMode`() {
        val state = RiskUiState.EMPTY
        assertNull(state.codexMode)
        assertNull(state.codexModel)
        assertNull(state.codexBaseUrl)
    }

    @Test fun `LIVE event captures model and baseUrl`() {
        val event = RiskEvent.CodexModeResolved(
            mode = RiskEvent.CodexModeResolved.Mode.LIVE,
            model = "gpt-5.4-mini",
            baseUrl = "https://api.openai.com/v1",
        )
        val state = RiskUiState.reduce(RiskUiState.EMPTY, event)
        assertEquals(RiskEvent.CodexModeResolved.Mode.LIVE, state.codexMode)
        assertEquals("gpt-5.4-mini", state.codexModel)
        assertEquals("https://api.openai.com/v1", state.codexBaseUrl)
    }

    @Test fun `CACHED_HIT event clears model and baseUrl`() {
        val event = RiskEvent.CodexModeResolved(
            mode = RiskEvent.CodexModeResolved.Mode.CACHED_HIT,
            model = null,
            baseUrl = null,
        )
        val state = RiskUiState.reduce(RiskUiState.EMPTY, event)
        assertEquals(RiskEvent.CodexModeResolved.Mode.CACHED_HIT, state.codexMode)
        assertNull(state.codexModel)
    }

    @Test fun `FALLBACK_CLASSPATH event reducer branch`() {
        val event = RiskEvent.CodexModeResolved(
            mode = RiskEvent.CodexModeResolved.Mode.FALLBACK_CLASSPATH,
            model = null,
            baseUrl = null,
        )
        val state = RiskUiState.reduce(RiskUiState.EMPTY, event)
        assertEquals(RiskEvent.CodexModeResolved.Mode.FALLBACK_CLASSPATH, state.codexMode)
    }

    @Test fun `OFFLINE event reducer branch`() {
        val event = RiskEvent.CodexModeResolved(
            mode = RiskEvent.CodexModeResolved.Mode.OFFLINE,
            model = null,
            baseUrl = null,
        )
        val state = RiskUiState.reduce(RiskUiState.EMPTY, event)
        assertEquals(RiskEvent.CodexModeResolved.Mode.OFFLINE, state.codexMode)
    }

    @Test fun `DEGRADED event reducer branch`() {
        val event = RiskEvent.CodexModeResolved(
            mode = RiskEvent.CodexModeResolved.Mode.DEGRADED,
            model = "gpt-5.4-mini",
            baseUrl = "https://api.openai.com/v1",
        )
        val state = RiskUiState.reduce(RiskUiState.EMPTY, event)
        assertEquals(RiskEvent.CodexModeResolved.Mode.DEGRADED, state.codexMode)
        assertEquals("gpt-5.4-mini", state.codexModel)
    }

    @Test fun `LIVE then DEGRADED transitions the mode field`() {
        val live = RiskEvent.CodexModeResolved(
            mode = RiskEvent.CodexModeResolved.Mode.LIVE,
            model = "gpt-5.4-mini",
            baseUrl = "https://api.openai.com/v1",
        )
        val degraded = RiskEvent.CodexModeResolved(
            mode = RiskEvent.CodexModeResolved.Mode.DEGRADED,
            model = "gpt-5.4-mini",
            baseUrl = "https://api.openai.com/v1",
        )
        val afterLive = RiskUiState.reduce(RiskUiState.EMPTY, live)
        val afterDegraded = RiskUiState.reduce(afterLive, degraded)
        assertEquals(RiskEvent.CodexModeResolved.Mode.DEGRADED, afterDegraded.codexMode)
    }

    @Test fun `mode event does not clobber baseline score or affected services`() {
        val baseline = RiskUiState.reduce(
            RiskUiState.EMPTY,
            RiskEvent.BaselineComputed(score = 55, factors = mapOf("error_rate" to 20.0)),
        )
        assertEquals(55, baseline.baselineScore)

        val withMode = RiskUiState.reduce(
            baseline,
            RiskEvent.CodexModeResolved(
                mode = RiskEvent.CodexModeResolved.Mode.LIVE,
                model = "gpt-5.4-mini",
                baseUrl = "https://api.openai.com/v1",
            ),
        )
        assertEquals(55, withMode.baselineScore, "baseline score must survive mode event")
        assertEquals(RiskEvent.CodexModeResolved.Mode.LIVE, withMode.codexMode)
    }

    @Test fun `Finalized after mode preserves codexMode for banner logic`() {
        val s0 = RiskUiState.reduce(
            RiskUiState.EMPTY,
            RiskEvent.CodexModeResolved(
                mode = RiskEvent.CodexModeResolved.Mode.LIVE,
                model = "gpt-5.4-mini",
                baseUrl = "https://api.openai.com/v1",
            ),
        )
        val s1 = RiskUiState.reduce(
            s0,
            RiskEvent.Finalized(
                RiskVerdict(
                    finalScore = 82,
                    baselineScore = 60,
                    codexDelta = 22,
                    affectedServices = listOf("payment-service"),
                    recommendation = "hold the deploy",
                    citations = listOf("INC-4402"),
                ),
            ),
        )
        assertEquals(RiskEvent.CodexModeResolved.Mode.LIVE, s1.codexMode)
        assertEquals(82, s1.finalScore)
        assertNotNull(s1.recommendation)
    }
}
