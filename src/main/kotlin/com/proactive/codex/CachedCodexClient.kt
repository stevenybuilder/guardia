package com.proactive.codex

import com.intellij.openapi.diagnostic.logger
import com.proactive.domain.Incident
import com.proactive.risk.ProposedPatch
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskVerdict
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Demo-safety wrapper around [OpenAIResponsesClient].
 *
 * Three-tier dispatch (ARCHITECTURE.md §5):
 *   1. In-memory cache keyed by SHA-256(systemPrompt + userPrompt + diff)
 *   2. Live call with 5s soft / 8s hard timeout
 *   3. Pre-recorded fallback JSON under `/fallback/<scenario-key>.json` on the plugin classpath
 *
 * If [offlineMode] is true, the live step is skipped entirely — cache → fallback only. This is
 * the "demo-day insurance" toggle: flip it in Settings before the pitch and every run becomes
 * deterministic regardless of network conditions.
 *
 * On any fallback path we emit a [RiskEvent.FallbackEngaged] event so the UI can show the
 * "using cached analysis" banner per ARCHITECTURE.md §4.
 */
class CachedCodexClient(
    private val live: LiveFactory,
    private val offlineMode: () -> Boolean = { false },
    private val softTimeoutMs: Long = 30_000,
    private val hardTimeoutMs: Long = 45_000,
    private val fallbackResolver: FallbackResolver = ClasspathFallbackResolver(),
    private val scenarioKeyFor: (CodexInput) -> String = ::defaultScenarioKey,
    private val dispatcherFor: (CodexInput) -> ToolDispatcher,
) : CodexAgentLoop {

    private val log = logger<CachedCodexClient>()

    /** Factory for a live [OpenAIResponsesClient] on demand. Lazy so offline-mode never tries
     * to resolve an API key. Null return = unconfigured (no key / missing dep) → skip live tier. */
    fun interface LiveFactory {
        fun build(): OpenAIResponsesClient?
    }

    private val cache: MutableMap<String, List<RiskEvent>> = ConcurrentHashMap()

    override fun run(input: CodexInput): Flow<RiskEvent> = flow {
        val cacheKey = scenarioKeyFor(input)

        cache[cacheKey]?.let { cached ->
            cached.forEach { emit(it) }
            return@flow
        }

        if (offlineMode()) {
            emit(RiskEvent.FallbackEngaged("offline mode enabled"))
            emitFallback(cacheKey, input)
            return@flow
        }

        val liveClient = try {
            live.build()
        } catch (t: Throwable) {
            log.warn("Codex live client construction failed: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
            null
        }
        if (liveClient == null) {
            log.warn("Codex live client unconfigured (API key missing?); engaging fallback")
            emit(RiskEvent.FallbackEngaged("OpenAI client unconfigured"))
            emitFallback(cacheKey, input)
            return@flow
        }

        val captured = mutableListOf<RiskEvent>()
        val recording = object : kotlinx.coroutines.flow.FlowCollector<RiskEvent> {
            override suspend fun emit(value: RiskEvent) {
                captured.add(value)
                this@flow.emit(value)
            }
        }

        try {
            withTimeout(hardTimeoutMs) {
                liveClient.run(
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = buildUserPrompt(input),
                    tools = OpenAIResponsesClient.DEFAULT_TOOLS,
                    dispatcher = dispatcherFor(input),
                    collector = recording,
                )
            }
            cache[cacheKey] = captured.toList()
        } catch (t: TimeoutCancellationException) {
            log.warn("Codex live call timed out after ${hardTimeoutMs}ms; engaging fallback", t)
            emit(RiskEvent.FallbackEngaged("live call timeout (${hardTimeoutMs}ms)"))
            emitFallback(cacheKey, input)
        } catch (t: Throwable) {
            log.warn("Codex live call failed: ${t.javaClass.simpleName}: ${t.message ?: "<no message>"}", t)
            emit(
                RiskEvent.FallbackEngaged(
                    "live call failed: ${t.javaClass.simpleName}: ${t.message ?: "<no message>"}",
                ),
            )
            emitFallback(cacheKey, input)
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RiskEvent>.emitFallback(
        cacheKey: String,
        input: CodexInput,
    ) {
        // Defensive: no matter what the resolver or verdict-synthesis does, we MUST leave the
        // downstream collector with a Finalized event. Any exception here would blow past the
        // coordinator's catch (which has already run) and end up in AnalyzePrRiskAction's
        // onThrowable → panel.showError, which is exactly the failure this method prevents.
        val events = try {
            fallbackResolver.resolve(cacheKey, input)
        } catch (t: Throwable) {
            log.warn("fallbackResolver.resolve threw: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
            null
        }
        if (events != null) {
            try {
                events.forEach { emit(it) }
                // If the canned stream happened to not carry a Finalized, synthesize one so the
                // UI's phase transitions to DONE/FALLBACK instead of hanging in RUNNING.
                if (events.none { it is RiskEvent.Finalized }) {
                    emit(RiskEvent.Finalized(fallbackVerdictFromBaseline(input)))
                }
                return
            } catch (t: Throwable) {
                log.warn("fallback event stream emit failed: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
                // Fall through to baseline-only synthesis below.
            }
        }
        // No canned response available (or it failed mid-stream) → synthesize from the baseline.
        emit(RiskEvent.Finalized(fallbackVerdictFromBaseline(input)))
    }

    private fun fallbackVerdictFromBaseline(input: CodexInput): RiskVerdict {
        // RiskVerdict has `require` invariants (delta in ±25, finalScore in 0..100, etc.).
        // If any input happens to violate them we still need to return SOMETHING so the
        // downstream panel completes — synthesize a zero-score degraded verdict as last resort.
        val rawBaseline = input.baseline.score
        val clampedBaseline = rawBaseline.coerceIn(0, 100)
        return runCatching {
            RiskVerdict(
                finalScore = clampedBaseline,
                baselineScore = clampedBaseline,
                codexDelta = 0,
                affectedServices = input.baseline.affectedServices,
                recommendation = "Using deterministic baseline (live Codex unavailable).",
                citations = emptyList(),
            )
        }.getOrElse {
            log.warn("fallbackVerdictFromBaseline construction failed; synthesizing degraded zero verdict", it)
            RiskVerdict(
                finalScore = 0,
                baselineScore = 0,
                codexDelta = 0,
                affectedServices = emptyList(),
                recommendation = "Degraded: unable to synthesize verdict from baseline.",
                citations = emptyList(),
            )
        }
    }

    companion object {
        internal const val SYSTEM_PROMPT =
            "You are an SRE co-pilot reasoning about PR deployment risk. " +
                "Use the registered tools to gather diff + baseline + related incidents. " +
                "You MUST call get_diff and compute_baseline_score first, then get_related_incidents, " +
                "then finalize_risk with a baseline_delta within [-25, 25] and at least one citation " +
                "if your delta is non-zero. Keep reasoning concise."

        internal fun buildUserPrompt(input: CodexInput): String =
            "Analyze the current PR's blast radius. Candidate services: " +
                input.request.candidateServices.joinToString(", ") +
                ". Lines changed: ${input.request.diff.linesChanged}." +
                " Baseline score: ${input.baseline.score}."

        /**
         * Routes a [CodexInput] to a scenario cache key.
         *
         * Fingerprint matching (in priority order) — this is the "demo-safety" path that guarantees
         * the shipped cached traces under `/fallback/scenario-*.json` play when live Codex is
         * unavailable (offline mode / missing API key / timeout), instead of the resolver falling
         * through to a synthetic SHA-256 key with no JSON match (which would yield a baseline-only
         * Finalized and blow the 60→85 demo beat).
         *
         *   A. INC-4402 regression (PaymentServiceImpl.java + null-check guard markers).
         *      Highest priority — this is the primary demo scenario.
         *   B. INC-4388 race regression (InventoryService touch + locking/transactional markers).
         *   C. INC-4417 fix-confirmation (PaymentService touch + backoff/retry markers).
         *
         * On no fingerprint match, falls back to SHA-256 over prompt + diff text — preserves the
         * original in-memory cache behavior for live-tier runs.
         */
        internal fun defaultScenarioKey(input: CodexInput): String {
            val touched = input.request.diff.touchedFiles
            val diffText = input.request.diff.text
            val diffLower = diffText.lowercase()

            // Scenario A — INC-4402 regression (highest priority, primary demo path)
            val touchesPaymentImpl = touched.any { it.contains("PaymentServiceImpl.java", ignoreCase = true) }
            if (touchesPaymentImpl) {
                val hasRegressionMarker = diffText.contains("INC-4402") ||
                    diffText.contains("IllegalArgumentException(\"credit card required\")") ||
                    diffText.contains("credit card is missing before clearPayment")
                if (hasRegressionMarker) return "scenario-a-regression-INC4402"
            }

            // Scenario B — INC-4388 race regression
            val touchesInventory = touched.any {
                it.contains("InventoryService", ignoreCase = true) ||
                    it.contains("InventoryController", ignoreCase = true) ||
                    it.contains("inventory-service", ignoreCase = true)
            }
            if (touchesInventory) {
                val hasRaceMarker = diffText.contains("SELECT ... FOR UPDATE", ignoreCase = true) ||
                    diffText.contains("@Transactional") ||
                    diffLower.contains("row-level lock") ||
                    diffText.contains("INC-4388") ||
                    diffText.contains("reserve(")
                if (hasRaceMarker) return "scenario-b-race-INC4388"
            }

            // Scenario C — INC-4417 fix-confirmation
            val touchesPayment = touched.any {
                it.contains("PaymentService", ignoreCase = true) ||
                    it.contains("payment-service", ignoreCase = true)
            }
            if (touchesPayment) {
                val hasFixMarker = diffLower.contains("backoff") ||
                    diffText.contains("Stripe", ignoreCase = true) ||
                    diffLower.contains("retry") ||
                    diffText.contains("INC-4417") ||
                    diffLower.contains("exponential")
                if (hasFixMarker) return "scenario-c-fix-INC4417"
            }

            // Fall-through: content-hash key preserves in-memory cache semantics for live runs.
            val payload = buildString {
                append(SYSTEM_PROMPT)
                append('\n')
                append(buildUserPrompt(input))
                append('\n')
                append(diffText)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    /** Loads a pre-recorded event stream from the plugin classpath. */
    fun interface FallbackResolver {
        fun resolve(cacheKey: String, input: CodexInput): List<RiskEvent>?
    }

    class ClasspathFallbackResolver(
        private val namedScenarios: Map<String, String> = DEFAULT_SCENARIOS,
    ) : FallbackResolver {
        override fun resolve(cacheKey: String, input: CodexInput): List<RiskEvent>? {
            // 1) exact cache-key match: fallback/<sha>.json
            readEvents("/fallback/$cacheKey.json")?.let { return it }
            // 2) named-scenario match via diff heuristics
            val scenario = namedScenarios[pickScenarioName(input)] ?: return null
            return readEvents("/fallback/$scenario.json")
        }

        private fun pickScenarioName(input: CodexInput): String {
            val txt = input.request.diff.text.lowercase()
            return when {
                "billingaddress" in txt || "null-check" in txt || "null check" in txt -> "A"
                "transactional" in txt || "inventory" in txt || "race" in txt -> "B"
                "backoff" in txt || "stripe" in txt || "retry" in txt -> "C"
                else -> "A"
            }
        }

        companion object {
            val DEFAULT_SCENARIOS = mapOf(
                "A" to "scenario-a-regression-INC4402",
                "B" to "scenario-b-race-INC4388",
                "C" to "scenario-c-fix-INC4417",
            )

            private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            private val listType = Types.newParameterizedType(
                List::class.java,
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            )
            private val adapter = moshi.adapter<List<Map<String, Any?>>>(listType)

            fun readEvents(resource: String): List<RiskEvent>? {
                val stream = CachedCodexClient::class.java.getResourceAsStream(resource) ?: return null
                val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val raw = try { adapter.fromJson(json) ?: return null } catch (_: Throwable) { return null }
                return raw.mapNotNull(::deserializeEvent)
            }

            @Suppress("UNCHECKED_CAST")
            private fun deserializeEvent(m: Map<String, Any?>): RiskEvent? {
                return when (m["type"] as? String) {
                    "BaselineComputed" -> RiskEvent.BaselineComputed(
                        score = (m["score"] as Number).toInt(),
                        factors = (m["factors"] as? Map<String, Number>)?.mapValues { it.value.toDouble() }.orEmpty(),
                    )
                    "ToolCallStarted" -> RiskEvent.ToolCallStarted(
                        step = (m["step"] as Number).toInt(),
                        tool = m["tool"] as String,
                    )
                    "ToolCallCompleted" -> RiskEvent.ToolCallCompleted(
                        step = (m["step"] as Number).toInt(),
                        tool = m["tool"] as String,
                        summary = m["summary"] as String,
                        elapsedMs = (m["elapsedMs"] as Number).toLong(),
                    )
                    "IncidentsRetrieved" -> RiskEvent.IncidentsRetrieved(
                        incidents = (m["incidents"] as? List<Map<String, Any?>>)?.map(::mapIncident).orEmpty(),
                    )
                    "Finalized" -> {
                        val v = m["verdict"] as Map<String, Any?>
                        val patchMap = v["proposedPatch"] as? Map<String, Any?>
                        val patch = patchMap?.let {
                            ProposedPatch(
                                citedIncidentId = it["citedIncidentId"] as? String ?: "",
                                targetFilePath = it["targetFilePath"] as? String ?: "",
                                targetFqName = it["targetFqName"] as? String ?: "",
                                unifiedDiff = it["unifiedDiff"] as? String ?: "",
                                rationale = it["rationale"] as? String ?: "",
                            ).takeIf { p -> p.unifiedDiff.isNotBlank() && p.targetFilePath.isNotBlank() }
                        }
                        RiskEvent.Finalized(
                            RiskVerdict(
                                finalScore = (v["finalScore"] as Number).toInt(),
                                baselineScore = (v["baselineScore"] as Number).toInt(),
                                codexDelta = (v["codexDelta"] as Number).toInt(),
                                affectedServices = (v["affectedServices"] as? List<String>).orEmpty(),
                                recommendation = v["recommendation"] as String,
                                citations = (v["citations"] as? List<String>).orEmpty(),
                                proposedPatch = patch,
                            ),
                        )
                    }
                    "FallbackEngaged" -> RiskEvent.FallbackEngaged(m["reason"] as String)
                    "CodexModeResolved" -> RiskEvent.CodexModeResolved(
                        mode = runCatching { RiskEvent.CodexModeResolved.Mode.valueOf(m["mode"] as String) }
                            .getOrDefault(RiskEvent.CodexModeResolved.Mode.CACHED),
                        model = m["model"] as? String ?: "",
                        baseUrl = m["baseUrl"] as? String ?: "",
                    )
                    else -> null
                }
            }

            @Suppress("UNCHECKED_CAST")
            private fun mapIncident(m: Map<String, Any?>): Incident = Incident(
                id = m["id"] as String,
                service = m["service"] as String,
                status = if ((m["status"] as? String).equals("RESOLVED", ignoreCase = true))
                    Incident.Status.RESOLVED else Incident.Status.OPEN,
                severity = m["severity"] as String,
                title = m["title"] as String,
                description = m["description"] as String,
                rootCause = m["rootCause"] as? String,
                offendingMethods = (m["offendingMethods"] as? List<String>).orEmpty(),
                offendingFiles = (m["offendingFiles"] as? List<String>).orEmpty(),
                offendingCodeSnippet = m["offendingCodeSnippet"] as? String,
                keywords = (m["keywords"] as? List<String>).orEmpty(),
                openedAt = (m["openedAt"] as? String).orEmpty(),
                resolvedAt = m["resolvedAt"] as? String,
            )
        }
    }
}
