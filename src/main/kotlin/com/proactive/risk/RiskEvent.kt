package com.proactive.risk

import com.proactive.domain.Incident

sealed class RiskEvent {
    data class BaselineComputed(
        val score: Int,
        val factors: Map<String, Double>,
    ) : RiskEvent()

    data class ToolCallStarted(
        val step: Int,
        val tool: String,
    ) : RiskEvent()

    data class ToolCallCompleted(
        val step: Int,
        val tool: String,
        val summary: String,
        val elapsedMs: Long,
    ) : RiskEvent()

    data class IncidentsRetrieved(
        val incidents: List<Incident>,
    ) : RiskEvent()

    data class Finalized(
        val verdict: RiskVerdict,
    ) : RiskEvent()

    data class FallbackEngaged(
        val reason: String,
    ) : RiskEvent()

    data class CodexModeResolved(
        val mode: Mode,
        val model: String,
        val baseUrl: String,
    ) : RiskEvent() {
        enum class Mode { LIVE, CACHED, DEGRADED }
    }
}
