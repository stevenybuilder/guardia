package com.proactive.coordinator

import com.datadog.proactive.datadog.DatadogService
import com.proactive.codex.CodexAgentLoop
import com.proactive.codex.FakeCodexAgentLoop
import com.proactive.domain.Incident
import com.proactive.domain.ServiceContext
import com.proactive.domain.toDomain
import com.proactive.risk.BaselineScore
import com.proactive.risk.DefaultIncidentRetriever
import com.proactive.risk.DefaultRiskHeuristic
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskRequest
import com.proactive.risk.RiskVerdict
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow

/**
 * Project-scoped IntelliJ service that assembles every Layer-1/Layer-2 dependency and exposes
 * the single [analyze] entrypoint the toolbar action calls. Keeps [RiskAnalysisCoordinator]
 * free of IntelliJ imports so the orchestrator logic stays unit-testable on plain JVM.
 *
 * Dependency resolution strategy:
 *   - DatadogService → project service (provider swaps Mock/Real based on settings).
 *   - CodexAgentLoop → project service lookup first (real client, another agent is writing);
 *     falls through to scripted [FakeCodexAgentLoop] (Scenario A) for the demo path.
 *   - Heuristic       → fresh [DefaultRiskHeuristic] per call; cheap to construct, stateless.
 *   - Retriever       → built lazily from the fixture-backed incidents on first call.
 *   - Fallback        → [CachedFallbackProvider] reading fallback JSON off the classpath.
 */
@Service(Service.Level.PROJECT)
class DefaultRiskAnalysisCoordinator(private val project: Project) {

    private val log = Logger.getInstance(DefaultRiskAnalysisCoordinator::class.java)

    fun analyze(request: RiskRequest): Flow<RiskEvent> =
        buildCoordinator().analyze(request).let { upstream ->
            // Post-process Finalized events: if Codex skipped the propose_patch tool but cited an
            // incident that ships a pre-baked `resolution_patch` in the fixture, synthesize a
            // ProposedPatch so the Risk tool window's Apply-fix callout renders deterministically.
            kotlinx.coroutines.flow.flow {
                upstream.collect { ev ->
                    if (ev is RiskEvent.Finalized && ev.verdict.proposedPatch == null &&
                        ev.verdict.citations.isNotEmpty()) {
                        val synthesized = synthesizePatchFromFixture(ev.verdict.citations)
                        if (synthesized != null) {
                            emit(RiskEvent.Finalized(ev.verdict.copy(proposedPatch = synthesized)))
                            return@collect
                        }
                    }
                    emit(ev)
                }
            }
        }

    /**
     * Look up each cited incident in the frozen fixture; if any has a non-blank `resolution_patch`,
     * return a [com.proactive.risk.ProposedPatch] built from it. Citations are probed in order so
     * the primary cited incident wins. Best-effort: any exception collapses to null (no patch).
     */
    private fun synthesizePatchFromFixture(
        citations: List<String>,
    ): com.proactive.risk.ProposedPatch? = runCatching {
        val index = com.datadog.proactive.fixtures.FixtureLoader.getInstance().index
        for (id in citations) {
            val inc = index.incidentsById[id] ?: continue
            val patch = inc.resolution_patch?.takeIf { it.isNotBlank() } ?: continue
            val targetFile = inc.offending_file.takeIf { it.isNotBlank() } ?: continue
            val targetFqName = inc.offending_methods.firstOrNull() ?: continue
            return@runCatching com.proactive.risk.ProposedPatch(
                citedIncidentId = id,
                targetFilePath = targetFile,
                targetFqName = targetFqName,
                unifiedDiff = patch,
                rationale = inc.resolution_text?.takeIf { it.isNotBlank() }
                    ?: "Apply resolution from $id to remediate the cited regression.",
            )
        }
        null
    }.getOrNull()

    private fun buildCoordinator(): RiskAnalysisCoordinator {
        val datadog = project.getService(DatadogService::class.java)
        val servicesFor: (RiskRequest) -> List<ServiceContext> = { req ->
            req.candidateServices
                .mapNotNull { datadog.getServiceContext(it) }
                .map { it.toDomain() }
        }
        val incidentsFor: (RiskRequest) -> List<Incident> = { req ->
            // Feed the retriever the full corpus, let it rank. Methods-of-interest are inferred
            // from the diff's touched files via the incident index's offendingFiles match.
            val allIncidents = collectAllIncidents(datadog)
            val retriever = DefaultIncidentRetriever(allIncidents)
            val methods = req.candidateServices
            val keywords = req.diff.touchedFiles.flatMap { it.split('/', '.').filter(String::isNotBlank) }
            retriever.retrieve(methods, keywords, req.diff.touchedFiles)
        }

        return RiskAnalysisCoordinator(
            heuristic = DefaultRiskHeuristic(),
            codex = resolveCodex(),
            servicesFor = servicesFor,
            incidentsFor = incidentsFor,
            fallback = CachedFallbackProvider(),
        )
    }

    /**
     * Pulls every incident from DatadogService and adapts to domain shape. For Mock this is a
     * cheap list copy (fixtures are ~20 items). For Real mode the DatadogService is expected
     * to cache at its boundary (see ARCHITECTURE.md §3.3), so we don't layer another cache here.
     */
    private fun collectAllIncidents(datadog: DatadogService): List<Incident> =
        // `allServices()` is already defined; there's no `allIncidents()` on DatadogService, so
        // fan out via the "match everything" signal — we pass broad keywords that hit every
        // incident's keyword index. The simpler + correct alternative is to ask each method-
        // of-interest-adjacent service for its incidents, but at v1 we only need a retrievable
        // corpus, not a live global fetch.
        datadog.getRelatedIncidents(methods = emptyList(), keywords = allCorpusKeywords).map { it.toDomain() }

    private fun resolveCodex(): CodexAgentLoop =
        try {
            project.getService(CodexAgentLoop::class.java)
                ?: FakeCodexAgentLoop(FakeCodexAgentLoop.scenarioA_regressionINC4402())
        } catch (cause: Throwable) {
            log.info("CodexAgentLoop project service not registered; using scripted fake for demo path", cause)
            FakeCodexAgentLoop(FakeCodexAgentLoop.scenarioA_regressionINC4402())
        }

    companion object {
        /**
         * Broad-net keyword list used to drain the fixture's incident index without calling
         * into classes the coordinator shouldn't depend on. Each of these hits at least one
         * incident in the shipped fixture per DATASET.md; together they cover the full set.
         */
        private val allCorpusKeywords = listOf(
            "null", "retry", "race", "timeout", "lock", "backoff", "throttle",
            "deadlock", "memory", "leak", "cascade", "regression", "nullpointer",
            "npe", "stripe", "inventory", "payment", "order", "charge",
        )
    }
}

/**
 * Reads pre-cached [RiskVerdict] artifacts from the fallback/ resource directory. The demo path
 * currently has one entry (`payment-charge-analysis.json`) covering Scenario A from
 * ARCHITECTURE.md §3.2. Any method not matched falls back to the baseline-only verdict.
 */
class CachedFallbackProvider : RiskAnalysisCoordinator.FallbackProvider {

    private val log = Logger.getInstance(CachedFallbackProvider::class.java)

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(CachedVerdictDto::class.java)

    override fun verdictFor(request: RiskRequest, baseline: BaselineScore): RiskVerdict {
        val key = keyFor(request)
        val cached = loadCached(key)
        if (cached != null) {
            return runCatching {
                RiskVerdict(
                    finalScore = cached.final_score,
                    baselineScore = cached.baseline_score,
                    codexDelta = cached.codex_delta,
                    affectedServices = cached.affected_services,
                    recommendation = cached.recommendation,
                    citations = cached.citations,
                )
            }.getOrElse {
                log.warn("Cached fallback for $key failed invariants; returning baseline-only verdict", it)
                heuristicOnly(baseline)
            }
        }
        return heuristicOnly(baseline)
    }

    private fun heuristicOnly(baseline: BaselineScore): RiskVerdict = RiskVerdict(
        finalScore = baseline.score,
        baselineScore = baseline.score,
        codexDelta = 0,
        affectedServices = baseline.affectedServices,
        recommendation = "Heuristic-only (cached).",
        citations = emptyList(),
    )

    private fun keyFor(request: RiskRequest): String {
        // The first touched file's basename (minus extension) is a good approximation of the
        // method-of-interest for the demo. Example: PaymentService.java → payment-charge.
        val file = request.diff.touchedFiles.firstOrNull() ?: return "default"
        val base = file.substringAfterLast('/').substringBeforeLast('.')
        return when {
            base.startsWith("PaymentService") -> "payment-charge-analysis"
            base.startsWith("InventoryService") -> "inventory-reserve-analysis"
            base.startsWith("OrderService") -> "order-place-analysis"
            else -> "default"
        }
    }

    private fun loadCached(key: String): CachedVerdictDto? {
        val path = "/fallback/$key.json"
        val stream = CachedFallbackProvider::class.java.getResourceAsStream(path) ?: return null
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return runCatching { adapter.fromJson(text) }.getOrNull()
    }

    internal data class CachedVerdictDto(
        val method: String,
        val final_score: Int,
        val baseline_score: Int,
        val codex_delta: Int,
        val affected_services: List<String>,
        val recommendation: String,
        val citations: List<String>,
    )
}
