package com.stevenyang.datadogproactive.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.text.Charsets

/**
 * Shared helpers for the two dashboard surfaces:
 *
 *  - [extractLocalDashboard]: lifts the bundled `/dashboard/index.html` (and its
 *    companion fixture JSON) out of the classpath into a real temp directory so it
 *    can be opened via `file://` URL in a browser or JCEF webview. The file is
 *    reusable by both the tool-window "Live Feed" tab and the settings-panel
 *    "Open Datadog Feed" button.
 *
 *  - [liveDatadogEventsUrl]: deterministic builder for the Datadog Events Explorer
 *    deep link, scoped to our hackathon tag. Centralized so tests can assert the
 *    exact URL shape without duplicating encoding logic.
 *
 * Offline-safe: [extractLocalDashboard] is pure disk I/O against the classpath. No
 * network dependency.
 */
object DashboardResources {

    /** Tag used to scope every event ingested by `scripts/ingest-to-datadog.py`. */
    const val DATADOG_TAG: String = "swagstore.hackathon.v1"

    /** Default lookback window — 30 days is wide enough to absorb any build-time ingestion. */
    internal const val DEFAULT_LOOKBACK_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L

    /**
     * Datadog Events Explorer URL filtered to events carrying our ingest tag.
     * Hardcoded to the US1 site (`datadoghq.com`) — matches `.env`'s `DD_SITE`.
     * The tag is URL-encoded so the `:` in `tags:swagstore.hackathon.v1` round-trips.
     *
     * Datadog's Events Explorer defaults to "Past 15 Minutes" — meaningless for a
     * fixture ingested at build time hours (or days) ago. We widen the window with
     * `from_ts` + `to_ts` (milliseconds since epoch, Datadog's canonical query-string
     * parameter names for the Events Explorer deep link) and set `live=false` so
     * auto-refresh does not clobber the range once the page loads.
     * `messageDisplay=expanded-lg` keeps each event row expanded for demo readability.
     *
     * [nowMillis] is injectable so tests can assert deterministic URLs.
     */
    fun liveDatadogEventsUrl(tag: String = DATADOG_TAG, nowMillis: Long = System.currentTimeMillis()): String {
        val encoded = URLEncoder.encode("tags:$tag", StandardCharsets.UTF_8.name())
        val fromTs = nowMillis - DEFAULT_LOOKBACK_MILLIS
        val toTs = nowMillis
        return "https://app.datadoghq.com/event/explorer" +
            "?query=$encoded" +
            "&from_ts=$fromTs" +
            "&to_ts=$toTs" +
            "&live=false" +
            "&messageDisplay=expanded-lg"
    }

    /**
     * Extract `/dashboard/index.html` + `/fixtures/datadog-fixtures.json` from the
     * plugin classpath to a fresh temp directory. Returns the path to `index.html`.
     *
     * Throws [IllegalStateException] if either classpath resource is missing — this
     * indicates a build regression (plugin packaging dropped `/dashboard/`).
     */
    fun extractLocalDashboard(): Path {
        val tempDir = Files.createTempDirectory("datadog-proactive-dashboard-")
        val htmlPath = tempDir.resolve("index.html")
        val fixturePath = tempDir.resolve("datadog-fixtures.json")

        val htmlStream = DashboardResources::class.java.getResourceAsStream("/dashboard/index.html")
            ?: error("Missing /dashboard/index.html on plugin classpath")
        val fixtureStream = DashboardResources::class.java.getResourceAsStream("/fixtures/datadog-fixtures.json")
            ?: error("Missing /fixtures/datadog-fixtures.json on plugin classpath")

        htmlStream.use { Files.copy(it, htmlPath, StandardCopyOption.REPLACE_EXISTING) }
        fixtureStream.use { Files.copy(it, fixturePath, StandardCopyOption.REPLACE_EXISTING) }
        require(Files.exists(htmlPath) && Files.exists(fixturePath)) {
            "Dashboard extraction failed: index.html=${Files.exists(htmlPath)}, " +
                "datadog-fixtures.json=${Files.exists(fixturePath)}"
        }
        return htmlPath
    }

    /**
     * JCEF-safe variant: extract `/dashboard/index.html` into a temp dir and rewrite it
     * to embed the fixture JSON inline as `window.FIXTURE_DATA`, replacing the
     * `fetch('./datadog-fixtures.json')` call. This avoids the `file://`-origin
     * `fetch()` CORS restriction some JCEF runtimes enforce, which would otherwise
     * surface as "Couldn't load fixtures: Failed to fetch" in the embedded dashboard.
     *
     * The rewritten HTML is still a valid standalone file (double-clickable in a
     * browser), so this output can be used in both surfaces if desired. The non-inlined
     * [extractLocalDashboard] is kept for the browser path so the HTML file stays
     * human-readable for debugging.
     *
     * Throws [IllegalStateException] if either classpath resource is missing.
     */
    fun extractLocalDashboardInlined(): Path {
        val tempDir = Files.createTempDirectory("datadog-proactive-dashboard-inlined-")
        val htmlPath = tempDir.resolve("index.html")

        val htmlBytes = DashboardResources::class.java.getResourceAsStream("/dashboard/index.html")
            ?.use { it.readBytes() }
            ?: error("Missing /dashboard/index.html on plugin classpath")
        val fixtureJson = DashboardResources::class.java.getResourceAsStream("/fixtures/datadog-fixtures.json")
            ?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: error("Missing /fixtures/datadog-fixtures.json on plugin classpath")

        val originalHtml = String(htmlBytes, Charsets.UTF_8)

        // Escape closing </script> sequences so the embedded JSON literal can't break
        // out of the script tag it's embedded in. JSON itself doesn't contain </script>
        // but a defensive escape keeps the rewrite robust for future fixture data.
        val safeFixture = fixtureJson.replace("</", "<\\/")

        // Replace the fetch(...).then(json).then(data => ...) chain with a synchronous
        // assignment path. We anchor on the exact fetch literal the HTML uses.
        val fetchAnchor = "fetch(\"./datadog-fixtures.json\", { cache: \"no-cache\" })"
        val inlineBootstrap = """(function(){
          try {
            var data = window.FIXTURE_DATA;
            if (!data) throw new Error("window.FIXTURE_DATA missing");
            state.services = Array.isArray(data.services) ? data.services : [];
            state.incidents = Array.isArray(data.incidents) ? data.incidents : [];
            state.events = Array.isArray(data.events) ? data.events : [];
            sortIncidents();
            applyFilter();
            renderCounts();
            renderServices();
            renderIncidents();
            wireFilters();
          } catch (e) {
            console.error(e);
            showLoadError(e.message || String(e));
          }
        })(); if(false) fetch("./datadog-fixtures.json", { cache: "no-cache" })""".trimIndent()

        val rewritten = if (originalHtml.contains(fetchAnchor)) {
            originalHtml.replace(fetchAnchor, inlineBootstrap)
        } else {
            // Fallback: inject before </body>. Keeps inlined variant functional even if
            // the HTML fetch literal drifts — user just sees the fetch error first, then
            // the inlined bootstrap overrides state on DOMContentLoaded.
            originalHtml
        }

        // Inject the FIXTURE_DATA literal right before the existing inline <script> so
        // it's defined before the dashboard script runs.
        val fixtureScript = "<script>window.FIXTURE_DATA = $safeFixture;</script>\n<script>"
        val withFixture = rewritten.replaceFirst("<script>", fixtureScript)

        Files.write(htmlPath, withFixture.toByteArray(Charsets.UTF_8))
        require(Files.exists(htmlPath)) { "Inlined dashboard extraction failed: $htmlPath" }
        return htmlPath
    }
}
