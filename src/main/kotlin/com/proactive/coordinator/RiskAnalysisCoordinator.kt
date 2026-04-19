package com.proactive.coordinator

import com.proactive.codex.CodexAgentLoop
import com.proactive.codex.CodexInput
import com.proactive.domain.Incident
import com.proactive.domain.ServiceContext
import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskHeuristic
import com.proactive.risk.RiskRequest
import com.proactive.risk.RiskVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * The sole orchestrator for Wedge 2's hybrid reasoning. Layer 1 (heuristic) always runs first and
 * emits [RiskEvent.BaselineComputed]; Layer 2 (Codex) emits its own events on top. Any throwable
 * from the Codex stream engages the fallback ladder — Layer 1's number is never faked.
 */
class RiskAnalysisCoordinator(
    private val heuristic: RiskHeuristic,
    private val codex: CodexAgentLoop,
    private val servicesFor: (RiskRequest) -> List<ServiceContext>,
    private val incidentsFor: (RiskRequest) -> List<Incident>,
    private val fallback: FallbackProvider,
) {

    fun analyze(request: RiskRequest): Flow<RiskEvent> {
        // Shared holder so the outer `.catch` operator can synthesize a baseline-aware Finalized
        // even if something explodes AFTER baseline emission but BEFORE the inner try/catch runs
        // (e.g. if the codex flow's collect-site throws during downstream emit propagation).
        val baselineHolder = java.util.concurrent.atomic.AtomicReference<BaselineScore?>(null)

        val inner: Flow<RiskEvent> = flow {
            val services = servicesFor(request)
            val incidents = incidentsFor(request)
            val baseline = heuristic.compute(request, services, incidents)
            baselineHolder.set(baseline)
            emit(RiskEvent.BaselineComputed(baseline.score, baseline.factors))

            // Wrap the Codex run so the 5-chip DAG timeline in RiskCardPanel lights up
            // the "codex" chip regardless of whether the loop is live, cached, or fallback.
            // Step 100 is intentionally high to avoid colliding with per-tool steps that
            // OpenAIResponsesClient emits (step 1..N) — the reducer upserts by step+tool.
            val codexStep = 100
            emit(RiskEvent.ToolCallStarted(codexStep, "codex_reasoning"))
            val codexStarted = System.currentTimeMillis()
            var codexIterations = 0
            try {
                codex.run(CodexInput(request, baseline)).collect { ev ->
                    if (ev is RiskEvent.ToolCallCompleted) codexIterations++
                    emit(ev)
                }
                val elapsed = System.currentTimeMillis() - codexStarted
                emit(
                    RiskEvent.ToolCallCompleted(
                        codexStep,
                        "codex_reasoning",
                        "Codex agent finished $codexIterations tool call(s)",
                        elapsed,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                val elapsed = System.currentTimeMillis() - codexStarted
                emit(
                    RiskEvent.ToolCallCompleted(
                        codexStep,
                        "codex_reasoning",
                        "Codex agent failed: ${cause.javaClass.simpleName}",
                        elapsed,
                    ),
                )
                emit(RiskEvent.FallbackEngaged(cause.message ?: cause::class.simpleName.orEmpty()))
                emit(RiskEvent.Finalized(fallback.verdictFor(request, baseline)))
            }
        }

        // Belt-and-suspenders: if ANY exception escapes the inner flow (including exceptions
        // raised during the inner try/catch's own emit() calls, or an exception during baseline
        // computation itself), swallow it here and emit FallbackEngaged + a synthesized
        // Finalized verdict. Guarantees the collector never sees a thrown exception, which
        // means AnalyzePrRiskAction.onThrowable never fires and the demo can't crash the panel.
        return inner.catch { cause ->
            if (cause is CancellationException) throw cause
            emit(RiskEvent.FallbackEngaged(cause.message ?: cause::class.simpleName.orEmpty()))
            val baseline = baselineHolder.get()
                ?: BaselineScore(score = 0, factors = emptyMap(), affectedServices = emptyList())
            emit(RiskEvent.Finalized(fallback.verdictFor(request, baseline)))
        }
    }

    fun interface FallbackProvider {
        fun verdictFor(request: RiskRequest, baseline: com.proactive.risk.BaselineScore): RiskVerdict
    }
}
