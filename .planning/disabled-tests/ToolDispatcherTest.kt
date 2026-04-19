package com.proactive.codex

import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the `compute_baseline_score` tool path. Pre-fix this code went through a reflective
 * lookup of `com.proactive.risk.RiskHeuristicImpl` (a class that doesn't exist) and silently
 * fell through to [baselineFallback]. The constructor-injected [baselineComputer] hook lets
 * the test prove the heuristic output is actually returned when one is wired.
 */
class ToolDispatcherTest {

    private val fallback = BaselineScore(
        score = 42,
        factors = mapOf("error_rate_delta" to 12.0),
        affectedServices = listOf("fallback-service"),
    )

    private suspend fun runComputeBaseline(dispatcher: ToolDispatcher, argsJson: String): String {
        val sink = FlowCollector<RiskEvent> { /* ignore */ }
        return dispatcher.dispatch("compute_baseline_score", argsJson, sink, step = 0)
    }

    @Test
    fun `compute_baseline_score returns heuristic output when baselineComputer is provided`(): Unit = runBlocking {
        val heuristicOutput = BaselineScore(
            score = 77,
            factors = mapOf("error_rate_delta" to 40.0, "deploy_window" to 15.0),
            affectedServices = listOf("payment-service", "order-service"),
        )
        val dispatcher = ToolDispatcher(
            project = null,
            diffProvider = { "" },
            baselineFallback = fallback,
            baselineComputer = { _ -> heuristicOutput },
        )

        val json = runComputeBaseline(dispatcher, """{"services":["payment-service","order-service"]}""")

        // Assert the heuristic's score flows through — NOT the fallback (which would be 42).
        assertTrue(json.contains("\"score\":77"), "expected heuristic score 77, got: $json")
        assertFalse(json.contains("\"score\":42"), "fallback score 42 should not be present: $json")
        assertTrue(json.contains("payment-service"), "expected heuristic's affected services: $json")
        assertTrue(json.contains("\"deploy_window\":15.0"), "expected heuristic's factor: $json")
    }

    @Test
    fun `compute_baseline_score falls back when no project and no baselineComputer`(): Unit = runBlocking {
        // With project=null and no injected computer, we have no way to hydrate ServiceContexts,
        // so the dispatcher must return the safety-net fallback verbatim.
        val dispatcher = ToolDispatcher(
            project = null,
            diffProvider = { "" },
            baselineFallback = fallback,
        )

        val json = runComputeBaseline(dispatcher, """{"services":["payment-service"]}""")
        assertTrue(json.contains("\"score\":42"), "expected fallback score: $json")
        assertTrue(json.contains("fallback-service"), "expected fallback affected services: $json")
    }

    @Test
    fun `compute_baseline_score falls back when baselineComputer throws`(): Unit = runBlocking {
        val dispatcher = ToolDispatcher(
            project = null,
            diffProvider = { "" },
            baselineFallback = fallback,
            baselineComputer = { _ -> throw IllegalStateException("boom") },
        )
        val json = runComputeBaseline(dispatcher, """{"services":["payment-service"]}""")
        assertTrue(json.contains("\"score\":42"), "should gracefully use fallback on throw: $json")
    }

    @Test
    fun `compute_baseline_score passes through services list to injected computer`(): Unit = runBlocking {
        var captured: List<String>? = null
        val dispatcher = ToolDispatcher(
            project = null,
            diffProvider = { "" },
            baselineFallback = fallback,
            baselineComputer = { svcs ->
                captured = svcs
                BaselineScore(10, emptyMap(), svcs)
            },
        )
        runComputeBaseline(dispatcher, """{"services":["a","b","c"]}""")
        assertEquals(listOf("a", "b", "c"), captured)
    }
}
