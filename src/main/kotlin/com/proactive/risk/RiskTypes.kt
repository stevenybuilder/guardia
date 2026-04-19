package com.proactive.risk

import com.proactive.domain.Diff

data class RiskRequest(
    val diff: Diff,
    val candidateServices: List<String>,
)

data class BaselineScore(
    val score: Int,
    val factors: Map<String, Double>,
    val affectedServices: List<String>,
)

data class RiskVerdict(
    val finalScore: Int,
    val baselineScore: Int,
    val codexDelta: Int,
    val affectedServices: List<String>,
    val recommendation: String,
    val citations: List<String>,
    /** Optional unified-diff patch Codex generated from the top-cited incident's root cause +
     *  resolution. Null when Codex didn't propose one (e.g. no matching incident found). */
    val proposedPatch: ProposedPatch? = null,
) {
    init {
        require(kotlin.math.abs(codexDelta) <= 25) { "codex_delta must be within ±25" }
        require(finalScore in 0..100) { "final_score must be 0..100" }
        require(finalScore == (baselineScore + codexDelta).coerceIn(0, 100)) {
            "final_score must equal clamp(baseline + delta)"
        }
    }
}

/**
 * One-shot remediation patch emitted by the `propose_patch` Codex tool OR pre-baked on a
 * demo-critical incident. Applied by `ApplyRemediationAction.applyPatch` under
 * `WriteCommandAction` so Cmd+Z undoes the change.
 */
data class ProposedPatch(
    val citedIncidentId: String,
    val targetFilePath: String,
    val targetFqName: String,
    val unifiedDiff: String,
    val rationale: String,
)
