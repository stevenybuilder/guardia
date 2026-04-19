package com.proactive.codex

import com.proactive.domain.Diff
import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskRequest
import com.proactive.risk.RiskVerdict
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Three-tier fallback ladder: memory cache → live → pre-recorded fallback.
 *
 * Live tier is exercised via [MockWebServer]; fallback tier via a lambda and via the
 * real classpath resolver (which reads the hand-authored JSON we ship).
 */
class CachedCodexClientTest {

    private val baseline = BaselineScore(60, mapOf("error_rate_delta" to 30.0), listOf("payment-service"))
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun requestFor(diffText: String): CodexInput = CodexInput(
        request = RiskRequest(
            diff = Diff(diffText, listOf("PaymentService.java"), 12),
            candidateServices = listOf("payment-service"),
        ),
        baseline = baseline,
    )

    private fun liveClient(): OpenAIResponsesClient = OpenAIResponsesClient(
        apiKey = "test-key",
        baseUrl = server.url("/").toString().trimEnd('/'),
        model = "test-model",
    )

    @Test fun `live tier emits events and caches them for next call`(): Unit = runBlocking {
        // First call — two rounds: get_diff → finalize_risk
        server.enqueue(okResponse("""{"output":[{"type":"function_call","id":"fc1","call_id":"c1","name":"get_diff","arguments":"{}"}]}"""))
        server.enqueue(okResponse("""{"output":[{"type":"function_call","id":"fc2","call_id":"c2","name":"finalize_risk",
            "arguments":"{\"final_score\":85,\"baseline_delta\":25,\"reasoning\":\"re-introduces INC-4402\",\"citations\":[\"INC-4402\"]}"}]}"""))

        val client = CachedCodexClient(
            live = { liveClient() },
            offlineMode = { false },
            fallbackResolver = { _, _ -> null },
            dispatcherFor = { ToolDispatcher(null, { "diff body" }, baseline) },
        )
        val input = requestFor("billingAddress null-check removed")

        val first = client.run(input).toList()
        val finalizedFirst = first.filterIsInstance<RiskEvent.Finalized>().firstOrNull()
        assertNotNull(finalizedFirst)
        assertEquals(85, finalizedFirst.verdict.finalScore)
        assertEquals(2, server.requestCount)

        // Second call — identical input — MUST hit cache (no new HTTP requests).
        val second = client.run(input).toList()
        assertEquals(2, server.requestCount, "cache should prevent additional live requests")
        assertEquals(first.size, second.size)
    }

    @Test fun `offlineMode skips live entirely and reads fallback resolver`(): Unit = runBlocking {
        val fallbackEvents = listOf<RiskEvent>(
            RiskEvent.Finalized(
                RiskVerdict(85, 60, 25, listOf("payment-service"), "canned", listOf("INC-4402")),
            ),
        )
        val client = CachedCodexClient(
            live = { liveClient() }, // never invoked
            offlineMode = { true },
            fallbackResolver = { _, _ -> fallbackEvents },
            dispatcherFor = { ToolDispatcher(null, { "" }, baseline) },
        )

        val events = client.run(requestFor("whatever")).toList()
        assertEquals(0, server.requestCount, "offlineMode must not hit the network")
        assertTrue(events.any { it is RiskEvent.FallbackEngaged && it.reason.contains("offline") })
        assertNotNull(events.filterIsInstance<RiskEvent.Finalized>().firstOrNull())
    }

    @Test fun `live failure falls through to fallback resolver`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val fallbackEvents = listOf<RiskEvent>(
            RiskEvent.Finalized(RiskVerdict(60, 60, 0, emptyList(), "cached", emptyList())),
        )
        val client = CachedCodexClient(
            live = { liveClient() },
            offlineMode = { false },
            fallbackResolver = { _, _ -> fallbackEvents },
            dispatcherFor = { ToolDispatcher(null, { "" }, baseline) },
        )

        val events = client.run(requestFor("diff")).toList()
        val engaged = events.filterIsInstance<RiskEvent.FallbackEngaged>()
        assertTrue(engaged.isNotEmpty(), "expected FallbackEngaged on 5xx")
        assertTrue(
            engaged.first().reason.contains("failed") || engaged.first().reason.contains("500"),
            "reason should describe failure: ${engaged.first().reason}",
        )
        assertNotNull(events.filterIsInstance<RiskEvent.Finalized>().firstOrNull())
    }

    @Test fun `null live factory (no API key) falls through to fallback`(): Unit = runBlocking {
        val fallbackEvents = listOf<RiskEvent>(
            RiskEvent.Finalized(RiskVerdict(70, 70, 0, emptyList(), "synth", emptyList())),
        )
        val client = CachedCodexClient(
            live = { null },
            offlineMode = { false },
            fallbackResolver = { _, _ -> fallbackEvents },
            dispatcherFor = { ToolDispatcher(null, { "" }, baseline) },
        )
        val events = client.run(requestFor("diff")).toList()
        val engaged = events.filterIsInstance<RiskEvent.FallbackEngaged>()
        assertTrue(engaged.isNotEmpty(), "expected FallbackEngaged when live unconfigured")
        assertTrue(engaged.first().reason.contains("unconfigured"))
    }

    @Test fun `missing fallback synthesizes baseline-only verdict`(): Unit = runBlocking {
        val client = CachedCodexClient(
            live = { null },
            offlineMode = { true },
            fallbackResolver = { _, _ -> null },
            dispatcherFor = { ToolDispatcher(null, { "" }, baseline) },
        )
        val events = client.run(requestFor("unknown diff")).toList()
        val finalized = events.filterIsInstance<RiskEvent.Finalized>().firstOrNull()
        assertNotNull(finalized)
        assertEquals(baseline.score, finalized.verdict.finalScore)
        assertEquals(0, finalized.verdict.codexDelta)
    }

    @Test fun `classpath fallback resolver loads scenario A JSON`() {
        val resolver = CachedCodexClient.ClasspathFallbackResolver()
        val input = requestFor("diff that removes billingAddress null-check")
        val events = resolver.resolve("nonexistent-cache-key", input)
        assertNotNull(events, "expected scenario A fallback JSON on classpath")
        val finalized = events.filterIsInstance<RiskEvent.Finalized>().firstOrNull()
        assertNotNull(finalized, "expected a Finalized event in scenario A")
        assertEquals("INC-4402", finalized.verdict.citations.firstOrNull())
    }

    @Test fun `defaultScenarioKey routes PaymentServiceImpl with INC-4402 marker to scenario A`() {
        val input = CodexInput(
            request = RiskRequest(
                diff = Diff(
                    text = "--- a/services/payment-service/.../PaymentServiceImpl.java\n" +
                        "-// Guard: INC-4402 — credit card is missing before clearPayment\n" +
                        "-throw new IllegalArgumentException(\"credit card required\");\n",
                    touchedFiles = listOf(
                        "services/payment-service/src/main/java/com/swagstore/payment/PaymentServiceImpl.java",
                    ),
                    linesChanged = 4,
                ),
                candidateServices = listOf("payment-service"),
            ),
            baseline = baseline,
        )
        val key = CachedCodexClient.defaultScenarioKey(input)
        assertEquals("scenario-a-regression-INC4402", key)
    }

    @Test fun `defaultScenarioKey routes InventoryService with Transactional marker to scenario B`() {
        val input = CodexInput(
            request = RiskRequest(
                diff = Diff(
                    text = "--- a/services/inventory-service/.../InventoryService.java\n" +
                        "-@Transactional\n" +
                        "-public void reserve(String sku, int qty) { ... }\n",
                    touchedFiles = listOf(
                        "services/inventory-service/src/main/java/com/swagstore/inventory/InventoryService.java",
                    ),
                    linesChanged = 3,
                ),
                candidateServices = listOf("inventory-service"),
            ),
            baseline = baseline,
        )
        val key = CachedCodexClient.defaultScenarioKey(input)
        assertEquals("scenario-b-race-INC4388", key)
    }

    @Test fun `defaultScenarioKey routes PaymentService with backoff marker to scenario C`() {
        val input = CodexInput(
            request = RiskRequest(
                diff = Diff(
                    text = "--- a/services/payment-service/.../PaymentService.java\n" +
                        "+// add exponential backoff before stripe retry\n" +
                        "+Thread.sleep(backoffMs);\n",
                    touchedFiles = listOf(
                        "services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java",
                    ),
                    linesChanged = 5,
                ),
                candidateServices = listOf("payment-service"),
            ),
            baseline = baseline,
        )
        val key = CachedCodexClient.defaultScenarioKey(input)
        assertEquals("scenario-c-fix-INC4417", key)
    }

    @Test fun `defaultScenarioKey falls through to hash key when no fingerprint matches`() {
        val input = CodexInput(
            request = RiskRequest(
                diff = Diff(
                    text = "--- a/README.md\n+unrelated docs tweak\n",
                    touchedFiles = listOf("README.md"),
                    linesChanged = 1,
                ),
                candidateServices = listOf("docs"),
            ),
            baseline = baseline,
        )
        val key = CachedCodexClient.defaultScenarioKey(input)
        assertTrue(key.isNotBlank(), "expected a non-blank hash key")
        assertTrue(
            !key.startsWith("scenario-"),
            "expected fall-through hash (got scenario key $key)",
        )
    }

    @Test fun `classpath resolver picks scenario C on backoff keyword`() {
        val resolver = CachedCodexClient.ClasspathFallbackResolver()
        val input = requestFor("adds exponential backoff for stripe retry loop")
        val events = resolver.resolve("nonexistent-key", input)
        assertNotNull(events)
        val finalized = events.filterIsInstance<RiskEvent.Finalized>().firstOrNull()
        assertNotNull(finalized)
        // Scenario C is the fix-confirmation (negative delta)
        assertTrue(
            finalized.verdict.codexDelta < 0,
            "scenario C should have negative delta, got ${finalized.verdict.codexDelta}",
        )
    }

    private fun okResponse(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
