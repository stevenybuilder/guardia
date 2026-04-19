package com.datadog.proactive.datadog

import com.datadog.proactive.fixtures.FixtureLoader
import com.datadog.proactive.fixtures.Incident
import com.datadog.proactive.fixtures.MethodOfInterest
import com.datadog.proactive.fixtures.Service as FixtureService
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Live Datadog backing for [DatadogService]. Reads events that were ingested via
 * `scripts/ingest-to-datadog.py` (idempotency tag `swagstore.hackathon.v1`) and parses
 * them back into our fixture-shape domain types via [DatadogEventParser].
 *
 * Service data (error rates, deploy windows, dep counts) is NOT modeled in Datadog Events,
 * so we fall back to the frozen fixture for [getServiceContext] + [getMethodContext] +
 * [isInDeployWindow]. The "live" surface is incident retrieval + code-pattern matching,
 * which is the Codex-facing reasoning surface (per ARCHITECTURE.md §2).
 *
 * **Fallback ladder** (ARCHITECTURE.md §5):
 *  - Network/5xx: retry once with 1s backoff → on second failure, log + return EMPTY for
 *    this query. We never throw. The LineMarkerProvider silently no-ops; the editor
 *    cannot grow a red error ribbon because of us.
 *  - First failure inside a 5-minute window → emit a one-shot balloon notification so the
 *    user sees "live Datadog unavailable — falling back". Suppressed thereafter.
 *  - Missing credentials → constructor logs a warning and marks this instance
 *    "degraded". Every method returns empty. This is the demo-safety pattern.
 *
 * **Caching**: 5-minute TTL, in-memory only, keyed by the query URL. The gutter calls
 * [getMethodContext] / [getRelatedIncidents] as the cursor moves; without caching we'd
 * hammer the Events API into 429s.
 */
@Service(Service.Level.PROJECT)
class RealDatadogService(@Suppress("UNUSED_PARAMETER") private val project: Project) : DatadogService {

    private val log = logger<RealDatadogService>()

    /** Service metadata comes from the frozen fixture — Datadog Events don't carry it. */
    private val fixtureIndex = runCatching { FixtureLoader.getInstance().index }.getOrNull()

    private val creds: Credentials? = resolveCredentials()
    private val degraded: Boolean = creds == null
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val cache = ConcurrentHashMap<String, CachedEntry>()

    /** Last notification wall-clock ms; used to throttle the balloon to 1-per-5-min. */
    private val lastNotifyMs = AtomicLong(0)

    init {
        if (degraded) {
            log.warn(
                "RealDatadogService: no Datadog credentials found (checked PasswordSafe, " +
                    "DD_API_KEY env var, and project .env). Running in DEGRADED mode — all " +
                    "queries return empty. Set DD_API_KEY via the settings panel."
            )
        } else {
            log.info("RealDatadogService: credentials resolved from ${creds!!.source}; site=${creds.site}")
        }
    }

    // ------------------------------------------------------------------------------------
    // DatadogService surface
    // ------------------------------------------------------------------------------------

    override fun getServiceContext(service: String): FixtureService? =
        fixtureIndex?.servicesByName?.get(service)

    override fun getMethodContext(fqName: String): MethodContext? {
        val method: MethodOfInterest = fixtureIndex?.methodsByFqName?.get(fqName) ?: return null
        val service = fixtureIndex.servicesByName[method.service] ?: return null

        // Pull live incidents for this service, union with fixture-linked incidents.
        val live = safely { fetchIncidentsByServiceTag(service.name) }.orEmpty()
        val byId = live.associateBy { it.id }.toMutableMap()
        for (id in method.linked_incidents) {
            if (id !in byId) {
                val fetched = safely { fetchIncidentById(id) }
                if (fetched != null) byId[id] = fetched
            }
        }
        val linked = method.linked_incidents.mapNotNull { byId[it] }

        return MethodContext(
            method = method,
            service = service,
            linkedIncidents = linked,
            openIncidentCount = linked.count { it.status == "open" },
            errorRateDeltaVsLastCommit = service.error_rate_delta_vs_last_commit,
            deployWindowToday = service.deploy_window_today,
        )
    }

    override fun isInDeployWindow(service: String): Boolean =
        fixtureIndex?.servicesByName?.get(service)?.deploy_window_today ?: false

    override fun allServices(): List<FixtureService> =
        fixtureIndex?.root?.services.orEmpty()

    override fun getRelatedIncidents(methods: List<String>, keywords: List<String>): List<Incident> {
        if (degraded) return emptyList()
        val hits = linkedSetOf<Incident>()
        for (m in methods) {
            val tagValue = methodTagValue(m)
            safely { fetchIncidentsByTag("method:$tagValue") }?.let(hits::addAll)
        }
        for (k in keywords) {
            safely { fetchIncidentsByTag("keyword:${sanitizeTagValue(k)}") }?.let(hits::addAll)
        }
        return hits.toList()
    }

    override fun findIncidentsByCodePattern(pattern: String): List<CodePatternMatch> {
        if (degraded || pattern.isBlank()) return emptyList()
        // Datadog Events text isn't indexed for substring queries — pull the full
        // swagstore corpus (capped at 200 per call) and scan locally. This is rare
        // (called from a Codex tool, not the gutter) so the round-trip cost is fine.
        val incidents = safely { fetchAllSwagstoreIncidents() }.orEmpty()
        return incidents.mapNotNull { inc ->
            if (inc.offending_code_snippet.contains(pattern)) {
                CodePatternMatch(
                    incidentId = inc.id,
                    title = inc.title,
                    offendingCodeSnippet = inc.offending_code_snippet,
                    matchedFragment = pattern,
                )
            } else null
        }
    }

    // ------------------------------------------------------------------------------------
    // Live queries
    // ------------------------------------------------------------------------------------

    private fun fetchIncidentById(incidentId: String): Incident? {
        val tagValue = sanitizeTagValue(incidentId)
        val events = queryEvents(listOf(IDEMPOTENCY_TAG, "incident_id:$tagValue"))
        return events.firstNotNullOfOrNull { DatadogEventParser.parse(it) }
    }

    private fun fetchIncidentsByServiceTag(service: String): List<Incident> =
        fetchIncidentsByTag("service:${sanitizeTagValue(service)}")

    private fun fetchIncidentsByTag(extraTag: String): List<Incident> {
        val events = queryEvents(listOf(IDEMPOTENCY_TAG, extraTag))
        return events.mapNotNull { DatadogEventParser.parse(it) }
    }

    private fun fetchAllSwagstoreIncidents(): List<Incident> {
        val events = queryEvents(listOf(IDEMPOTENCY_TAG))
        return events.mapNotNull { DatadogEventParser.parse(it) }
    }

    /**
     * GET /api/v1/events?start=<unix>&end=<unix>&tags=<csv>. Tag filters must all match
     * (AND semantics via the `tags=` CSV is Datadog's documented behavior for v1). We
     * query the last 90 days — our ingested events carry historical `date_happened`
     * stamps, not plugin-startup stamps.
     */
    private fun queryEvents(tags: List<String>): List<Map<String, Any?>> {
        val c = creds ?: return emptyList()
        val end = System.currentTimeMillis() / 1000
        val start = end - 90L * 24 * 60 * 60 // 90 days

        val url = "https://api.${c.site}/api/v1/events".toHttpUrl()
            .newBuilder()
            .addQueryParameter("start", start.toString())
            .addQueryParameter("end", end.toString())
            .addQueryParameter("tags", tags.joinToString(","))
            .build()

        val cacheKey = url.toString()
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { if (now - it.storedAt < CACHE_TTL_MS) return it.events }

        val events = executeWithRetry(url.toString(), c) ?: return emptyList()
        cache[cacheKey] = CachedEntry(now, events)
        return events
    }

    private fun executeWithRetry(url: String, c: Credentials): List<Map<String, Any?>>? {
        var lastError: String? = null
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("DD-API-KEY", c.apiKey)
                    .apply { c.appKey?.let { header("DD-APPLICATION-KEY", it) } }
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        return parseEventsArray(body)
                    }
                    lastError = "HTTP ${resp.code}"
                    // Only retry on 5xx / 429; 4xx is a configuration problem — bail fast.
                    if (resp.code < 500 && resp.code != 429) {
                        log.warn("Datadog Events API $url → ${resp.code} (not retrying)")
                        maybeNotifyDegraded(lastError!!)
                        return null
                    }
                }
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "<no message>")
                log.warn("Datadog Events API attempt ${attempt + 1} failed: $lastError")
            }
            if (attempt == 0) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        log.warn("Datadog Events API $url failed after retry: $lastError")
        maybeNotifyDegraded(lastError ?: "unknown")
        return null
    }

    /**
     * Datadog v1 `/events` returns `{ "status": "ok", "events": [ {...}, ... ] }`. We
     * avoid binding to a strict schema — the Events API has grown fields over the years
     * and rejecting on an unknown one would be fragile.
     */
    private fun parseEventsArray(json: String): List<Map<String, Any?>> {
        if (json.isBlank()) return emptyList()
        val rootType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val adapter = moshi.adapter<Map<String, Any?>>(rootType)
        val root = adapter.fromJson(json) ?: return emptyList()
        val raw = root["events"] as? List<*> ?: return emptyList()
        return raw.filterIsInstance<Map<String, Any?>>()
    }

    // ------------------------------------------------------------------------------------
    // Fallback + notification
    // ------------------------------------------------------------------------------------

    /**
     * Runs [block] and swallows any throwable into null. This is the outermost firewall
     * around every public-surface call. LineMarkerProviders run on the daemon thread;
     * any exception that escapes here paints a red ribbon in the editor. Unacceptable.
     */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        log.warn("RealDatadogService call failed softly: ${t.message}", t)
        maybeNotifyDegraded(t.javaClass.simpleName)
        null
    }

    private fun maybeNotifyDegraded(reason: String) {
        val now = System.currentTimeMillis()
        val prev = lastNotifyMs.get()
        if (now - prev < NOTIFY_THROTTLE_MS) return
        if (!lastNotifyMs.compareAndSet(prev, now)) return
        try {
            val group = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID) ?: return
            group.createNotification(
                "Guardia",
                "Live Datadog API unavailable ($reason). Falling back to empty results.",
                NotificationType.WARNING,
            ).notify(project)
        } catch (t: Throwable) {
            // Notifications are best-effort. Never let them crash the caller.
            log.debug("Notification bus unavailable: ${t.message}")
        }
    }

    // ------------------------------------------------------------------------------------
    // Credentials resolution: PasswordSafe → env → .env
    // ------------------------------------------------------------------------------------

    private data class Credentials(val apiKey: String, val appKey: String?, val site: String, val source: String)

    private fun resolveCredentials(): Credentials? {
        // 1) PasswordSafe
        val psApi = safeRead { PasswordSafe.instance.getPassword(API_KEY_ATTRS) }
        val psApp = safeRead { PasswordSafe.instance.getPassword(APP_KEY_ATTRS) }
        val envSite = System.getenv("DD_SITE")?.takeIf { it.isNotBlank() }
        if (!psApi.isNullOrBlank()) {
            return Credentials(psApi, psApp?.takeIf { it.isNotBlank() }, envSite ?: "datadoghq.com", "PasswordSafe")
        }
        // 2) Env vars
        val envApi = System.getenv("DD_API_KEY")?.takeIf { it.isNotBlank() }
        val envApp = System.getenv("DD_APP_KEY")?.takeIf { it.isNotBlank() }
        if (envApi != null) {
            return Credentials(envApi, envApp, envSite ?: "datadoghq.com", "env")
        }
        // 3) .env file at project or cwd root (dev convenience; matches ingest-to-datadog.py)
        val dotEnv = readDotEnv()
        val fileApi = dotEnv["DD_API_KEY"]
        if (!fileApi.isNullOrBlank()) {
            return Credentials(
                fileApi,
                dotEnv["DD_APP_KEY"],
                dotEnv["DD_SITE"] ?: envSite ?: "datadoghq.com",
                ".env",
            )
        }
        return null
    }

    private fun readDotEnv(): Map<String, String> {
        val candidates = listOfNotNull(
            project.basePath?.let { File(it, ".env") },
            File(System.getProperty("user.dir"), ".env"),
        )
        val out = linkedMapOf<String, String>()
        for (file in candidates) {
            if (!file.exists() || !file.isFile) continue
            try {
                file.readLines(Charsets.UTF_8).forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#") || "=" !in line) return@forEach
                    val (k, v) = line.split("=", limit = 2)
                    out.putIfAbsent(k.trim(), v.trim().trim('"'))
                }
            } catch (t: Throwable) {
                log.debug(".env read skipped: ${t.message}")
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    private fun <T> safeRead(block: () -> T?): T? = try { block() } catch (_: Throwable) { null }

    companion object {
        private const val IDEMPOTENCY_TAG = "swagstore.hackathon.v1"
        private const val CACHE_TTL_MS = 5L * 60 * 1000
        private const val NOTIFY_THROTTLE_MS = 5L * 60 * 1000
        private const val NOTIFICATION_GROUP_ID = "Guardia"

        private val API_KEY_ATTRS = CredentialAttributes(
            generateServiceName("DatadogProactive.Datadog", "apiKey")
        )
        private val APP_KEY_ATTRS = CredentialAttributes(
            generateServiceName("DatadogProactive.Datadog", "appKey")
        )

        /**
         * Match the ingest script's `sanitize_tag_value` — lowercase, keep alnum and
         * `._-/:`, replace anything else with `_`. Without this, queries by incident-id
         * tag miss on values that were mutated at ingest time.
         */
        internal fun sanitizeTagValue(v: String): String {
            val sb = StringBuilder(v.length)
            for (c in v.lowercase()) {
                sb.append(if (c.isLetterOrDigit() || c in "._-/:") c else '_')
            }
            var out = sb.toString().take(200)
            if (out.isNotEmpty() && !out.first().isLetter()) out = "x$out"
            return out
        }

        /**
         * The ingest script emits `method:<simpleClassName>.<methodName>`. Our fixtures
         * store FQ names like `com.swagstore.payment.PaymentService.charge`. Reduce to
         * the last-two-dot-segment form before tag lookup so cursor-side FQ names match
         * ingest-side simple names.
         */
        internal fun methodTagValue(fqName: String): String {
            val parts = fqName.split('.')
            val short = if (parts.size >= 2) "${parts[parts.size - 2]}.${parts.last()}" else fqName
            return sanitizeTagValue(short)
        }
    }

    private data class CachedEntry(val storedAt: Long, val events: List<Map<String, Any?>>)
}
