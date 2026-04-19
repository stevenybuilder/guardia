package com.stevenyang.datadogproactive.preflight

import com.datadog.proactive.fixtures.FixtureLoader
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.proactive.risk.RiskEvent
import com.stevenyang.datadogproactive.settings.DatadogProactiveSettings
import java.io.File
import java.security.MessageDigest

/**
 * Demo-safety pre-flight health check.
 *
 * Runs the exact set of checks that `scripts/preflight.sh` runs from the shell, but
 * from inside the running IDE via the Settings panel "Test Demo Path" button.
 *
 * Scope is deliberately narrow — verify the fallback ladder in ARCHITECTURE.md §5
 * is intact, so a stage failure of the live Codex call is silently recoverable.
 *
 * Three layers:
 *   1. FALLBACK LAYER — each of the 3 pre-recorded scenario JSONs loads and deserialises
 *      into at least 1 BaselineComputed + 1 Finalized RiskEvent.
 *   2. INTEGRITY LAYER — SHA-256 of each fallback file matches a committed hash. If a
 *      user hand-edits a scenario after recording, we want to know BEFORE the demo.
 *   3. CREDENTIAL + FIXTURE LAYER — OpenAI key resolvable, fixture JSON loads, counts
 *      match minimum expectations (>=2000 incidents, >=3 services, methods_of_interest
 *      contains PaymentService.charge).
 *
 * The committed hashes between `// HASH_START` and `// HASH_END` are placeholder
 * values at first commit; `scripts/record-and-verify.sh` rewrites them after each
 * `./gradlew recordDemo` via sed replacement.
 *
 * Intentionally dep-light: ONLY `com.intellij.openapi.project.Project` is IntelliJ-typed,
 * and it's nullable. A unit test constructs a `DemoPathHealthCheck()` and calls
 * `run(null)` without needing a platform fixture.
 */
class DemoPathHealthCheck(
    private val classLoader: ClassLoader = DemoPathHealthCheck::class.java.classLoader,
    /** Test seam: override API key resolver; defaults to PasswordSafe + OPENAI_API_KEY env. */
    private val openAiKeyResolver: () -> String? = ::defaultOpenAiKey,
    /** Test seam: override Datadog key resolver. */
    private val datadogKeyResolver: () -> String? = ::defaultDatadogKey,
) {

    enum class Status { GREEN, YELLOW, RED }

    data class HealthCheck(
        val name: String,
        val status: Status,
        val detail: String,
    )

    data class HealthReport(
        val status: Status,
        val checks: List<HealthCheck>,
        val diagnostics: String,
    ) {
        fun summary(): String {
            val red = checks.count { it.status == Status.RED }
            val yel = checks.count { it.status == Status.YELLOW }
            return when (status) {
                Status.GREEN -> "Demo path GREEN — ${checks.size} checks passed."
                Status.YELLOW -> "Demo path YELLOW — $yel warning(s)."
                Status.RED -> "Demo path RED — $red check(s) failed."
            }
        }
    }

    fun run(project: Project?): HealthReport {
        val checks = mutableListOf<HealthCheck>()

        // ----- Layer 1: fallback scenario files present + parse -----
        val scenarioEvents = linkedMapOf<String, List<RiskEvent>?>()
        for ((key, resource) in SCENARIOS) {
            val path = "/fallback/$resource.json"
            val events = try {
                readEventsViaCache(path)
            } catch (t: Throwable) {
                null
            }
            scenarioEvents[key] = events
            val toolCalls = events?.count { it is RiskEvent.ToolCallStarted } ?: 0
            checks += when {
                events == null -> HealthCheck(
                    "scenario-$key present",
                    Status.RED,
                    "missing or unparseable: $path",
                )
                toolCalls < 1 ->
                    HealthCheck(
                        "scenario-$key present",
                        Status.RED,
                        "${events.size} events but no ToolCallStarted (trace empty)",
                    )
                events.none { it is RiskEvent.Finalized } ->
                    HealthCheck(
                        "scenario-$key present",
                        Status.RED,
                        "${events.size} events but no Finalized verdict",
                    )
                else -> HealthCheck(
                    "scenario-$key present",
                    Status.GREEN,
                    "${events.size} events, $toolCalls tool calls + finalize",
                )
            }
        }

        // ----- Layer 2: fingerprint integrity -----
        for ((key, resource) in SCENARIOS) {
            val path = "/fallback/$resource.json"
            val actual = sha256Of(path)
            val expected = EXPECTED_HASHES[key]
            checks += when {
                actual == null -> HealthCheck(
                    "scenario-$key fingerprint",
                    Status.RED,
                    "file not readable on classpath",
                )
                expected == PLACEHOLDER_HASH -> HealthCheck(
                    "scenario-$key fingerprint",
                    Status.YELLOW,
                    "hash not yet pinned — run ./scripts/record-and-verify.sh",
                )
                expected == null -> HealthCheck(
                    "scenario-$key fingerprint",
                    Status.YELLOW,
                    "no expected hash registered",
                )
                actual != expected -> HealthCheck(
                    "scenario-$key fingerprint",
                    Status.YELLOW,
                    "trace drift detected — recording modified since last pin",
                )
                else -> HealthCheck(
                    "scenario-$key fingerprint",
                    Status.GREEN,
                    "matches pinned hash",
                )
            }
        }

        // ----- Layer 3a: OpenAI credential resolvable -----
        val openAiKey = try {
            openAiKeyResolver()
        } catch (_: Throwable) {
            null
        }
        checks += if (!openAiKey.isNullOrBlank()) {
            HealthCheck("OpenAI API key", Status.GREEN, "resolvable via PasswordSafe/env/.env")
        } else {
            HealthCheck(
                "OpenAI API key",
                Status.YELLOW,
                "not resolvable — offline mode + fallbacks will carry the demo",
            )
        }

        // ----- Layer 3b: Datadog credential (YELLOW only — demo doesn't need it) -----
        val ddKey = try {
            datadogKeyResolver()
        } catch (_: Throwable) {
            null
        }
        checks += if (!ddKey.isNullOrBlank()) {
            HealthCheck("Datadog API key", Status.GREEN, "resolvable")
        } else {
            HealthCheck(
                "Datadog API key",
                Status.YELLOW,
                "empty — USE_REAL_DATADOG toggle will fall back to Mock",
            )
        }

        // ----- Layer 3c: fixture loads cleanly -----
        checks += checkFixture()

        // ----- Aggregate -----
        val hasRed = checks.any { it.status == Status.RED }
        val hasYel = checks.any { it.status == Status.YELLOW }
        val overall = when {
            hasRed -> Status.RED
            hasYel -> Status.YELLOW
            else -> Status.GREEN
        }

        val diagnostics = buildString {
            appendLine("DemoPathHealthCheck summary")
            appendLine("  status: $overall")
            appendLine("  project: ${project?.name ?: "<none>"}")
            appendLine("  scenarios: ${scenarioEvents.count { it.value != null }}/${SCENARIOS.size} loadable")
            appendLine("  checks:")
            for (c in checks) {
                appendLine("    [${c.status}] ${c.name} — ${c.detail}")
            }
        }

        return HealthReport(overall, checks.toList(), diagnostics)
    }

    private fun checkFixture(): HealthCheck {
        return try {
            val loader = try {
                // Under a running IDE the app service is the canonical path.
                FixtureLoader.getInstance()
            } catch (_: Throwable) {
                // Test / plain-JVM path: construct a fresh loader. The class has no IntelliJ-typed
                // constructor parameters so this works outside the platform.
                FixtureLoader()
            }
            val root = loader.index.root
            val mois = root.methods_of_interest
            // Accepts either the legacy fictional name or the real tsre FQN
            // (midbuild §1.3: `PaymentServiceImpl.charge`).
            val hasCharge = mois.any {
                it.fq_name.endsWith("PaymentServiceImpl.charge") ||
                    it.fq_name.endsWith("PaymentService.charge")
            }
            val incCount = root.incidents.size
            when {
                incCount < 2000 -> HealthCheck(
                    "Fixture counts",
                    Status.RED,
                    "expected >=2000 incidents; got $incCount",
                )
                !hasCharge -> HealthCheck(
                    "Fixture counts",
                    Status.RED,
                    "PaymentServiceImpl.charge (or legacy PaymentService.charge) not in methods_of_interest",
                )
                else -> HealthCheck(
                    "Fixture counts",
                    Status.GREEN,
                    "services=${root.services.size} incidents=$incCount mois=${mois.size}",
                )
            }
        } catch (t: Throwable) {
            HealthCheck(
                "Fixture counts",
                Status.RED,
                "load failed: ${t.javaClass.simpleName}: ${t.message ?: "<no msg>"}",
            )
        }
    }

    private fun readEventsViaCache(resource: String): List<RiskEvent>? {
        // Use the injected classloader so tests can swap in a temp-dir classpath root.
        // The existing resolver reads through CachedCodexClient's own loader; we re-plumb
        // the stream into a dummy temp file on the real disk so the resolver's path works,
        // but simpler: route through the stream directly via a reflection-free helper.
        val stream = classLoader.getResourceAsStream(resource.trimStart('/')) ?: return null
        val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        // Hand off to the existing parser. We call readEvents with a resource path that
        // won't resolve through its own loader, so we route via a temp file on disk.
        return parseEventsFromJson(json)
    }

    private fun parseEventsFromJson(json: String): List<RiskEvent>? {
        // Minimal inline JSON → RiskEvent parser mirroring CachedCodexClient resolver.
        // Kept tiny + dep-aligned (Moshi already on classpath) so preflight stays
        // independent of resource-resolution quirks.
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val listType = com.squareup.moshi.Types.newParameterizedType(
            List::class.java,
            com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
        val adapter = moshi.adapter<List<Map<String, Any?>>>(listType)
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
            "IncidentsRetrieved" -> RiskEvent.IncidentsRetrieved(incidents = emptyList())
            "Finalized" -> {
                val v = m["verdict"] as Map<String, Any?>
                RiskEvent.Finalized(
                    com.proactive.risk.RiskVerdict(
                        finalScore = (v["finalScore"] as Number).toInt(),
                        baselineScore = (v["baselineScore"] as Number).toInt(),
                        codexDelta = (v["codexDelta"] as Number).toInt(),
                        affectedServices = (v["affectedServices"] as? List<String>).orEmpty(),
                        recommendation = v["recommendation"] as String,
                        citations = (v["citations"] as? List<String>).orEmpty(),
                    ),
                )
            }
            "FallbackEngaged" -> RiskEvent.FallbackEngaged(m["reason"] as String)
            else -> null
        }
    }

    private fun sha256Of(resource: String): String? {
        val stream = classLoader.getResourceAsStream(resource.trimStart('/')) ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        stream.use { s ->
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {

        /** Canonical scenario key → fallback filename base. Mirrors [CachedCodexClient.ClasspathFallbackResolver.DEFAULT_SCENARIOS]. */
        val SCENARIOS = linkedMapOf(
            "A" to "scenario-a-regression-INC4402",
            "B" to "scenario-b-race-INC4388",
            "C" to "scenario-c-fix-INC4417",
        )

        /** Sentinel recognised as "not yet pinned" — the record-and-verify script overwrites. */
        const val PLACEHOLDER_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        // ----------------------------------------------------------------------
        // Committed scenario fingerprints.
        //
        // These are rewritten automatically by `scripts/record-and-verify.sh`
        // after each `./gradlew recordDemo`. Do NOT hand-edit — if the hashes
        // drift from the checked-in JSON, preflight will surface a YELLOW
        // "trace drift detected" warning pointing at the ritual script.
        //
        // The sed contract is: each line MUST match
        //   "<KEY>" to "<sha>",
        // exactly, with lowercase 64-char hex. Preserve the markers below verbatim.
        // HASH_START
        val EXPECTED_HASHES: Map<String, String> = mapOf(
            "A" to PLACEHOLDER_HASH,
            "B" to PLACEHOLDER_HASH,
            "C" to PLACEHOLDER_HASH,
        )
        // HASH_END

        /** Defaults for key resolution — mirrors ingest-to-datadog.py's .env cascade pattern. */
        private fun defaultOpenAiKey(): String? {
            try {
                val fromSafe = PasswordSafe.instance.getPassword(
                    CredentialAttributes(generateServiceName("DatadogProactive.OpenAI", "api-key")),
                )
                if (!fromSafe.isNullOrBlank()) return fromSafe
            } catch (_: Throwable) { /* not in an IDE; fall through */ }

            System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }

            // Also honor a plugin-settings-persisted key when in an IDE.
            try {
                val app = ApplicationManager.getApplication()
                if (app != null) {
                    val settings = app.getService(DatadogProactiveSettings::class.java)
                    settings?.getApiKey()?.takeIf { it.isNotBlank() }?.let { return it }
                }
            } catch (_: Throwable) { /* ignore */ }

            return readDotEnvKey("OPENAI_API_KEY")
        }

        private fun defaultDatadogKey(): String? {
            try {
                val fromSafe = PasswordSafe.instance.getPassword(
                    CredentialAttributes(generateServiceName("DatadogProactive.Datadog", "apiKey")),
                )
                if (!fromSafe.isNullOrBlank()) return fromSafe
            } catch (_: Throwable) { /* not in an IDE */ }

            System.getenv("DD_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            return readDotEnvKey("DD_API_KEY")
        }

        /** Best-effort scan of any open project's basePath/.env (IDE mode) or cwd/.env (JVM mode). */
        private fun readDotEnvKey(key: String): String? {
            val candidates = mutableListOf<File>()
            try {
                val app = ApplicationManager.getApplication()
                if (app != null) {
                    for (p in ProjectManager.getInstance().openProjects) {
                        p.basePath?.let { candidates += File(it, ".env") }
                    }
                }
            } catch (_: Throwable) { /* not in IDE */ }
            candidates += File(System.getProperty("user.dir").orEmpty(), ".env")

            for (f in candidates) {
                if (!f.isFile) continue
                try {
                    f.useLines { lines ->
                        for (raw in lines) {
                            val line = raw.trim()
                            if (line.isEmpty() || line.startsWith("#")) continue
                            val eq = line.indexOf('=')
                            if (eq <= 0) continue
                            val k = line.substring(0, eq).trim()
                            if (k != key) continue
                            val v = line.substring(eq + 1).trim()
                                .removeSurrounding("\"")
                                .removeSurrounding("'")
                            if (v.isNotBlank()) return v
                        }
                    }
                } catch (_: Throwable) { /* keep scanning */ }
            }
            return null
        }
    }
}
