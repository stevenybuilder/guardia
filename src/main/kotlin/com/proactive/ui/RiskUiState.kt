package com.proactive.ui

import com.proactive.domain.Incident
import com.proactive.risk.ProposedPatch
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskVerdict

data class RiskUiState(
    val phase: Phase = Phase.IDLE,
    val baselineScore: Int? = null,
    val finalScore: Int? = null,
    val factors: Map<String, Double> = emptyMap(),
    val affectedServices: List<String> = emptyList(),
    val recommendation: String? = null,
    val citations: List<String> = emptyList(),
    val retrievedIncidents: List<Incident> = emptyList(),
    val trace: List<TraceRow> = emptyList(),
    val fallbackReason: String? = null,
    val codexMode: RiskEvent.CodexModeResolved.Mode? = null,
    val codexModel: String? = null,
    val codexBaseUrl: String? = null,
    /** Codex-proposed remediation patch from the top-cited incident. Drives Apply-fix callout
     *  visibility — patch presence is the authoritative signal (no score threshold). */
    val proposedPatch: ProposedPatch? = null,
) {
    enum class Phase { IDLE, RUNNING, DONE, FALLBACK }

    data class TraceRow(
        val step: Int,
        val kind: Kind,
        val tool: String?,
        val summary: String,
        val elapsedMs: Long?,
        val running: Boolean,
    ) {
        enum class Kind { BASELINE, TOOL, FINAL }
    }

    companion object {
        val EMPTY = RiskUiState()

        private const val BASELINE_STEP = 1

        fun reduce(state: RiskUiState, event: RiskEvent): RiskUiState = when (event) {
            is RiskEvent.BaselineComputed -> state.copy(
                phase = Phase.RUNNING,
                baselineScore = event.score,
                factors = event.factors,
                trace = state.trace.upsert(
                    TraceRow(
                        step = BASELINE_STEP,
                        kind = TraceRow.Kind.BASELINE,
                        tool = "compute_baseline",
                        summary = "baseline ${event.score}",
                        elapsedMs = null,
                        running = false,
                    ),
                ),
            )

            is RiskEvent.ToolCallStarted -> state.copy(
                phase = Phase.RUNNING,
                trace = state.trace.upsert(
                    TraceRow(
                        step = event.step,
                        kind = TraceRow.Kind.TOOL,
                        tool = event.tool,
                        summary = "calling ${event.tool}…",
                        elapsedMs = null,
                        running = true,
                    ),
                ),
            )

            is RiskEvent.ToolCallCompleted -> state.copy(
                trace = state.trace.upsert(
                    TraceRow(
                        step = event.step,
                        kind = TraceRow.Kind.TOOL,
                        tool = event.tool,
                        summary = event.summary,
                        elapsedMs = event.elapsedMs,
                        running = false,
                    ),
                ),
            )

            is RiskEvent.IncidentsRetrieved -> state.copy(
                retrievedIncidents = event.incidents,
            )

            is RiskEvent.Finalized -> state.copy(
                phase = if (state.phase == Phase.FALLBACK) Phase.FALLBACK else Phase.DONE,
                baselineScore = event.verdict.baselineScore,
                finalScore = event.verdict.finalScore,
                affectedServices = event.verdict.affectedServices,
                recommendation = event.verdict.recommendation,
                citations = event.verdict.citations,
                proposedPatch = event.verdict.proposedPatch,
                trace = state.trace.upsert(
                    TraceRow(
                        step = (state.trace.maxOfOrNull { it.step } ?: BASELINE_STEP) + 1,
                        kind = TraceRow.Kind.FINAL,
                        tool = "finalize_risk",
                        summary = "final ${event.verdict.finalScore} (Δ${event.verdict.codexDelta})",
                        elapsedMs = null,
                        running = false,
                    ),
                ),
            )

            is RiskEvent.FallbackEngaged -> state.copy(
                phase = Phase.FALLBACK,
                fallbackReason = event.reason,
            )

            is RiskEvent.CodexModeResolved -> state.copy(
                codexMode = event.mode,
                codexModel = event.model,
                codexBaseUrl = event.baseUrl,
            )
        }

        private fun List<TraceRow>.upsert(row: TraceRow): List<TraceRow> {
            val idx = indexOfFirst { it.step == row.step }
            return if (idx < 0) this + row else toMutableList().also { it[idx] = row }
        }
    }
}
