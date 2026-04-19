package com.stevenyang.datadogproactive.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Asserts that dashboard extraction puts both the HTML and the fixture JSON on
 * disk in a layout the embedded `<script>` can actually load, for both the
 * browser surface (flat file next to HTML via `fetch`) and the JCEF surface
 * (fixture inlined as `window.FIXTURE_DATA`).
 *
 * Guards two shipped bugs:
 *   - Bug A (browser): HTML was copied without sibling fixture → `fetch` 404.
 *   - Bug B (JCEF): even with sibling fixture, `file://`-origin CORS can block
 *     `fetch`. The inlined variant sidesteps the fetch entirely.
 */
class DashboardExtractionTest {

    @Test
    fun `extractLocalDashboard writes both index_html and datadog-fixtures_json flat`() {
        val htmlPath = DashboardResources.extractLocalDashboard()
        val dir = htmlPath.parent
        val fixturePath = dir.resolve("datadog-fixtures.json")

        assertTrue("index.html should exist at $htmlPath", Files.exists(htmlPath))
        assertTrue("datadog-fixtures.json should exist at $fixturePath", Files.exists(fixturePath))

        val html = String(Files.readAllBytes(htmlPath), Charsets.UTF_8)
        assertTrue(
            "index.html fetch path must be the flat sibling `./datadog-fixtures.json`",
            html.contains("fetch(\"./datadog-fixtures.json\"")
        )

        // Fixture is the real one — not empty, not the wrong file.
        val fixtureSize = Files.size(fixturePath)
        assertTrue("fixture JSON must be non-trivially sized (got $fixtureSize)", fixtureSize > 1000L)
    }

    @Test
    fun `extractLocalDashboardInlined embeds FIXTURE_DATA in the HTML`() {
        val htmlPath = DashboardResources.extractLocalDashboardInlined()
        assertTrue("inlined index.html should exist at $htmlPath", Files.exists(htmlPath))

        val html = String(Files.readAllBytes(htmlPath), Charsets.UTF_8)
        assertTrue(
            "inlined variant must define window.FIXTURE_DATA",
            html.contains("window.FIXTURE_DATA = {")
        )
        assertTrue(
            "inlined variant must reference fixture keys (services)",
            html.contains("\"services\"")
        )
        assertTrue(
            "inlined variant must reference fixture keys (incidents)",
            html.contains("\"incidents\"")
        )
    }
}
