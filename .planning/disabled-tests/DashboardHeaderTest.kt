package com.stevenyang.datadogproactive.toolwindow

import com.stevenyang.datadogproactive.util.DashboardResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the tool-window dashboard integration surface:
 *
 *   1. [DashboardResources.liveDatadogEventsUrl] produces the expected URL-encoded
 *      Events-Explorer deep link for our canonical ingest tag.
 *   2. [RiskCardPanel.dashboardHeaderVisible] returns `true` — the persistent header
 *      row (Open Datadog dashboard + Open local dashboard) is always rendered, not
 *      hidden behind the mode swap.
 *
 * These are the two contract assertions that the dashboard integration (header
 * toolbar + live-org deep link) is wired correctly regardless of JCEF availability.
 */
class DashboardHeaderTest {

    @Test
    fun `liveDatadogEventsUrl encodes the hackathon tag correctly with 30-day window`() {
        val now = 1_713_484_800_000L // 2024-04-19 00:00 UTC, deterministic anchor
        val url = DashboardResources.liveDatadogEventsUrl(nowMillis = now)
        val fromTs = now - DashboardResources.DEFAULT_LOOKBACK_MILLIS
        assertEquals(
            "https://app.datadoghq.com/event/explorer" +
                "?query=tags%3Aswagstore.hackathon.v1" +
                "&from_ts=$fromTs" +
                "&to_ts=$now" +
                "&live=false" +
                "&messageDisplay=expanded-lg",
            url,
        )
    }

    @Test
    fun `liveDatadogEventsUrl encodes custom tags and carries the wide time window`() {
        val now = 1_713_484_800_000L
        val url = DashboardResources.liveDatadogEventsUrl("demo.tag.v2", nowMillis = now)
        val fromTs = now - DashboardResources.DEFAULT_LOOKBACK_MILLIS
        assertEquals(
            "https://app.datadoghq.com/event/explorer" +
                "?query=tags%3Ademo.tag.v2" +
                "&from_ts=$fromTs" +
                "&to_ts=$now" +
                "&live=false" +
                "&messageDisplay=expanded-lg",
            url,
        )
    }

    @Test
    fun `liveDatadogEventsUrl disables live refresh and widens to 30 days`() {
        val url = DashboardResources.liveDatadogEventsUrl()
        assertTrue("URL must disable auto-refresh (live=false)", url.contains("live=false"))
        assertTrue("URL must include from_ts param", url.contains("from_ts="))
        assertTrue("URL must include to_ts param", url.contains("to_ts="))
        assertTrue(
            "URL must keep expanded event display",
            url.contains("messageDisplay=expanded-lg"),
        )
    }

    @Test
    fun `dashboard header is visible on freshly-constructed panel`() {
        // Headless construction: project=null is the unit-test path the panel already supports.
        val panel = RiskCardPanel(project = null)
        assertTrue(
            "dashboard header should be visible so the live/local dashboard buttons are always reachable",
            panel.dashboardHeaderVisible(),
        )
    }
}
