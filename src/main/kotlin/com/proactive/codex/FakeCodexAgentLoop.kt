package com.proactive.codex

import com.proactive.domain.Incident
import com.proactive.risk.ProposedPatch
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskVerdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Scripted Codex stand-in. Emits [RiskEvent]s on a timer so the UI layer can be built and demoed
 * without network, auth, or real Codex integration. Doubles as the fallback-trace renderer:
 * the pre-cached `PaymentService.charge` trace committed under `resources/fallback/` is just a
 * serialized [Scenario].
 */
class FakeCodexAgentLoop(
    private val scenario: Scenario,
    private val stepDelayMs: Long = 500,
    private val throwBefore: Int? = null,
) : CodexAgentLoop {

    override fun run(input: CodexInput): Flow<RiskEvent> = flow {
        scenario.steps.forEachIndexed { index, step ->
            throwBefore?.let { if (index >= it) error("Fake codex: scripted failure at step $index") }
            when (step) {
                is Step.StartTool -> {
                    emit(RiskEvent.ToolCallStarted(step.step, step.tool))
                    delay(stepDelayMs)
                }
                is Step.CompleteTool -> {
                    emit(RiskEvent.ToolCallCompleted(step.step, step.tool, step.summary, step.elapsedMs))
                }
                is Step.EmitIncidents -> emit(RiskEvent.IncidentsRetrieved(step.incidents))
                is Step.Finalize -> emit(RiskEvent.Finalized(step.verdict))
            }
        }
    }

    data class Scenario(val name: String, val steps: List<Step>)

    sealed class Step {
        data class StartTool(val step: Int, val tool: String) : Step()
        data class CompleteTool(
            val step: Int,
            val tool: String,
            val summary: String,
            val elapsedMs: Long,
        ) : Step()
        data class EmitIncidents(val incidents: List<Incident>) : Step()
        data class Finalize(val verdict: RiskVerdict) : Step()
    }

    companion object {
        /**
         * Scenario A from ARCHITECTURE.md §3.2: diff removes a null-guard in `PaymentService.charge`,
         * Codex cites resolved incident INC-4402, bumps baseline 60 → 85.
         */
        fun scenarioA_regressionINC4402(): Scenario {
            val incident = Incident(
                id = "INC-4402",
                service = "payment-service",
                status = Incident.Status.RESOLVED,
                severity = "SEV-1",
                title = "NullPointerException in PaymentService.charge for EU customers",
                description = "Charge requests failing with NPE for customers without a resolved billing country. Errors swallowed by generic catch and logged as timeouts. Masked the real cause for 2h.",
                rootCause = "Missing null-check on `request` / `request.getCreditCard()` before `controller.clearPayment(request)`; generic `catch(Exception e)` swallows the NPE and surfaces it as a gRPC UNAVAILABLE, masking the real cause.",
                offendingMethods = listOf("PaymentServiceImpl.charge"),
                offendingFiles = listOf(
                    "src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java",
                ),
                offendingCodeSnippet = "String transactionId = \"none\";\ntry {\n    transactionId = controller.clearPayment(request);\n} catch(Exception e) {\n    this.log.log(Level.SEVERE, e.getClass().getName() + \" occurred. Cannot process payment transaction.\");\n    // ... swallows all exceptions, maps to gRPC UNAVAILABLE\n}",
                keywords = listOf("null", "NPE", "getCreditCard", "clearPayment", "ChargeRequest", "swallowed", "UNAVAILABLE"),
                openedAt = "2026-03-14T23:48:00Z",
                resolvedAt = "2026-03-15T02:11:00Z",
            )
            val proposedPatch = ProposedPatch(
                citedIncidentId = "INC-4402",
                targetFilePath = "src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java",
                targetFqName = "PaymentServiceImpl.charge",
                unifiedDiff = buildString {
                    appendLine("--- a/src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java")
                    appendLine("+++ b/src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java")
                    appendLine("@@ -39,4 +39,10 @@")
                    appendLine("-            String transactionId = \"none\";")
                    appendLine("-            try {")
                    appendLine("-                transactionId = controller.clearPayment(request);")
                    appendLine("+            // INC-4402: guard against NPE when credit card is missing before clearPayment.")
                    appendLine("+            if (request == null || request.getCreditCard() == null) {")
                    appendLine("+                log.warning(\"charge() called without a credit card; rejecting (INC-4402 guard).\");")
                    appendLine("+                responseObserver.onError(new IllegalArgumentException(\"credit card required\"));")
                    appendLine("+                return;")
                    appendLine("+            }")
                    appendLine("+            String transactionId = \"none\";")
                    appendLine("+            try {")
                    appendLine("+                transactionId = controller.clearPayment(request);")
                },
                rationale = "Guards against INC-4402 NPE regression by null-checking credit card before clearPayment call.",
            )
            return Scenario(
                name = "regression-INC-4402",
                steps = listOf(
                    Step.StartTool(2, "get_diff"),
                    Step.CompleteTool(2, "get_diff", "12 lines changed in PaymentService.java", 40),
                    Step.StartTool(3, "compute_baseline_score"),
                    Step.CompleteTool(3, "compute_baseline_score", "60 — error_rate_delta + deploy_window", 5),
                    Step.StartTool(4, "get_related_incidents"),
                    Step.CompleteTool(
                        4,
                        "get_related_incidents",
                        "matched INC-4402 via structural + keywords [null, billing, country]",
                        120,
                    ),
                    Step.EmitIncidents(listOf(incident)),
                    Step.StartTool(5, "propose_patch"),
                    Step.CompleteTool(5, "propose_patch", "proposed null-check guard for INC-4402 regression", 20),
                    Step.StartTool(6, "finalize_risk"),
                    Step.CompleteTool(6, "finalize_risk", "final 85 (Δ+25) — reintroduces INC-4402", 30),
                    Step.Finalize(
                        RiskVerdict(
                            finalScore = 85,
                            baselineScore = 60,
                            codexDelta = 25,
                            affectedServices = listOf("payment-service", "order-service"),
                            recommendation = "Hold this PR — reintroduces INC-4402 null-check at PaymentService:142.",
                            citations = listOf("INC-4402"),
                            proposedPatch = proposedPatch,
                        ),
                    ),
                ),
            )
        }
    }
}
