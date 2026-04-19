package com.proactive.codex

import com.proactive.domain.Diff
import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskRequest
import kotlinx.coroutines.flow.FlowCollector
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
 * Tests for the hand-rolled [OpenAIResponsesClient]. Uses OkHttp MockWebServer — NO real
 * OpenAI calls. Scripts the multi-round tool loop via canned function_call JSON responses.
 */
class OpenAIResponsesClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `dispatches get_diff and terminates on finalize_risk`(): Unit = runBlocking {
        // Round 1: model calls get_diff
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "resp_1",
                  "output": [
                    {"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_diff","arguments":"{}"}
                  ]
                }
                """.trimIndent()
            )
        )
        // Round 2: model calls finalize_risk
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "resp_2",
                  "output": [
                    {"type":"function_call","id":"fc_2","call_id":"call_2","name":"finalize_risk",
                     "arguments":"{\"final_score\":85,\"baseline_delta\":25,\"reasoning\":\"cites INC-4402\",\"citations\":[\"INC-4402\"]}"}
                  ]
                }
                """.trimIndent()
            )
        )

        val baseline = BaselineScore(60, emptyMap(), listOf("payment-service"))
        val client = OpenAIResponsesClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "test-model",
        )

        val events = mutableListOf<RiskEvent>()
        val collector = FlowCollector<RiskEvent> { events.add(it) }

        val dispatcher = ToolDispatcher(
            project = null,
            diffProvider = { "--- changed lines here ---" },
            baselineFallback = baseline,
        )

        val verdict = client.run(
            systemPrompt = "sys",
            userPrompt = "usr",
            tools = OpenAIResponsesClient.DEFAULT_TOOLS,
            dispatcher = dispatcher,
            collector = collector,
        )

        assertEquals(85, verdict.finalScore)
        assertEquals(25, verdict.codexDelta)
        assertEquals(listOf("INC-4402"), verdict.citations)

        // Tool call events for get_diff + finalize_risk should be present.
        val toolNames = events.filterIsInstance<RiskEvent.ToolCallStarted>().map { it.tool }
        assertTrue("get_diff" in toolNames, "expected get_diff among $toolNames")
        assertTrue("finalize_risk" in toolNames, "expected finalize_risk among $toolNames")
        assertNotNull(events.filterIsInstance<RiskEvent.Finalized>().firstOrNull())
    }

    @Test fun `clamps codex delta outside plus minus 25`(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"output":[{"type":"function_call","id":"fc_1","call_id":"c1","name":"finalize_risk",
                 "arguments":"{\"final_score\":100,\"baseline_delta\":75,\"reasoning\":\"huge\",\"citations\":[]}"}]}
                """.trimIndent()
            )
        )
        val baseline = BaselineScore(50, emptyMap(), listOf("s"))
        val client = OpenAIResponsesClient(
            apiKey = "k",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "m",
        )
        val events = mutableListOf<RiskEvent>()
        val verdict = client.run(
            "sys", "usr", OpenAIResponsesClient.DEFAULT_TOOLS,
            ToolDispatcher(null, { "d" }, baseline),
            FlowCollector { events.add(it) },
        )
        assertEquals(75, verdict.finalScore) // 50 + clamp(75, -25..25) = 50 + 25 = 75
        assertTrue(verdict.codexDelta <= 25)
    }

    @Test fun `non-200 response throws which coordinator can translate to fallback`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val client = OpenAIResponsesClient(
            apiKey = "k",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "m",
        )
        var threw = false
        try {
            client.run(
                "s", "u", OpenAIResponsesClient.DEFAULT_TOOLS,
                ToolDispatcher(null, { "d" }, BaselineScore(0, emptyMap(), emptyList())),
                FlowCollector { /* ignore */ },
            )
        } catch (t: Throwable) {
            threw = true
            assertTrue(t.message?.contains("500") == true, "expected HTTP 500 in message, got: ${t.message}")
        }
        assertTrue(threw, "expected exception on 500")
    }

    @Test fun `request payload retains input history across rounds`(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"output":[{"type":"function_call","id":"fc_1","call_id":"c1","name":"get_diff","arguments":"{}"}]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"output":[{"type":"function_call","id":"fc_2","call_id":"c2","name":"finalize_risk",
                   "arguments":"{\"final_score\":50,\"baseline_delta\":0,\"reasoning\":\"ok\",\"citations\":[]}"}]}"""
            )
        )
        val baseline = BaselineScore(50, emptyMap(), emptyList())
        val client = OpenAIResponsesClient(
            apiKey = "k",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "m",
        )
        client.run(
            "s", "u", OpenAIResponsesClient.DEFAULT_TOOLS,
            ToolDispatcher(null, { "(no changes)" }, baseline),
            FlowCollector { },
        )
        // Round 2 body must contain the function_call AND function_call_output from round 1.
        server.takeRequest() // round 1
        val round2Body = server.takeRequest().body.readUtf8()
        assertTrue(round2Body.contains("function_call"), "expected function_call replay in round 2: $round2Body")
        assertTrue(round2Body.contains("function_call_output"), "expected function_call_output in round 2: $round2Body")
    }

    @Test fun `reasoning items are replayed alongside their function_call on next round`(): Unit = runBlocking {
        // Round 1: model emits a `reasoning` item followed by a `function_call` — these
        // must travel together in round 2's input[] or the Responses API 400s with
        // "function_call ... was provided without its required 'reasoning' item".
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "resp_1",
                  "output": [
                    {"type":"reasoning","id":"rs_abc123","summary":[]},
                    {"type":"function_call","id":"fc_abc123","call_id":"call_abc","name":"get_diff","arguments":"{}"}
                  ]
                }
                """.trimIndent()
            )
        )
        // Round 2: finalize_risk terminates the loop.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"output":[{"type":"function_call","id":"fc_end","call_id":"call_end","name":"finalize_risk",
                 "arguments":"{\"final_score\":60,\"baseline_delta\":0,\"reasoning\":\"ok\",\"citations\":[]}"}]}
                """.trimIndent()
            )
        )

        val baseline = BaselineScore(60, emptyMap(), emptyList())
        val client = OpenAIResponsesClient(
            apiKey = "k",
            baseUrl = server.url("/").toString().trimEnd('/'),
            model = "m",
        )
        client.run(
            "s", "u", OpenAIResponsesClient.DEFAULT_TOOLS,
            ToolDispatcher(null, { "d" }, baseline),
            FlowCollector { },
        )

        server.takeRequest() // round 1
        val round2Body = server.takeRequest().body.readUtf8()
        assertTrue(
            round2Body.contains("rs_abc123"),
            "expected reasoning id rs_abc123 replayed in round 2: $round2Body",
        )
        assertTrue(
            round2Body.contains("\"type\":\"reasoning\""),
            "expected reasoning item replayed in round 2: $round2Body",
        )
        assertTrue(
            round2Body.contains("fc_abc123"),
            "expected function_call id fc_abc123 replayed in round 2: $round2Body",
        )
        // Reasoning must appear BEFORE its paired function_call in the replayed input[].
        val reasoningIdx = round2Body.indexOf("rs_abc123")
        val fcIdx = round2Body.indexOf("fc_abc123")
        assertTrue(
            reasoningIdx in 0 until fcIdx,
            "reasoning must precede its function_call — rs idx=$reasoningIdx, fc idx=$fcIdx",
        )
    }

    // Silence unused-parameter warnings for RiskRequest/Diff used elsewhere in tests
    @Suppress("unused")
    private val sampleRequest = RiskRequest(Diff("", emptyList(), 0), emptyList())
}
