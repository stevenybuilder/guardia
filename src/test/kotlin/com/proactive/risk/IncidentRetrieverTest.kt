package com.proactive.risk

import com.proactive.domain.Incident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentRetrieverTest {

    private fun inc(
        id: String,
        methods: List<String> = emptyList(),
        files: List<String> = emptyList(),
        description: String = "",
        rootCause: String? = null,
        keywords: List<String> = emptyList(),
    ) = Incident(
        id = id,
        service = "svc",
        status = Incident.Status.RESOLVED,
        severity = "SEV-2",
        title = id,
        description = description,
        rootCause = rootCause,
        offendingMethods = methods,
        offendingFiles = files,
        offendingCodeSnippet = null,
        keywords = keywords,
        openedAt = "2026-04-01T00:00:00Z",
        resolvedAt = "2026-04-02T00:00:00Z",
    )

    // 10-incident synthetic corpus — one structural-match target plus nine distractors that
    // vary in lexical overlap with the demo query.
    private val corpus = listOf(
        inc(
            id = "INC-4402",
            methods = listOf("PaymentService.charge"),
            files = listOf("services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java"),
            description = "Null pointer on charge retry path for EU customers",
            rootCause = "Missing null-check on billing country at line 142",
            keywords = listOf("null", "NPE", "billing", "country", "retry"),
        ),
        inc(
            id = "INC-4403",
            methods = listOf("OrderService.place"),
            files = listOf("services/order-service/OrderService.java"),
            description = "Orders stuck in PENDING when downstream times out",
            rootCause = "Missing timeout in async publisher",
            keywords = listOf("timeout", "async", "pending"),
        ),
        inc(
            id = "INC-4388",
            methods = listOf("InventoryService.reserve"),
            files = listOf("services/inventory-service/InventoryService.java"),
            description = "Race condition reserving inventory during concurrent checkouts",
            rootCause = "Missing row-level lock on quantity update",
            keywords = listOf("race", "concurrent", "lock", "reserve"),
        ),
        inc(
            id = "INC-4417",
            methods = listOf("PaymentService.charge"),
            files = listOf("services/payment-service/PaymentService.java"),
            description = "Stripe retry amplification causing 429s on charge",
            rootCause = "Retry loop lacks backoff",
            keywords = listOf("retry", "stripe", "429", "backoff"),
        ),
        inc(
            id = "INC-4501",
            description = "DB pool exhausted during nightly batch job",
            keywords = listOf("db", "pool", "batch", "nightly"),
        ),
        inc(
            id = "INC-4502",
            description = "Cache stampede on catalog cold-start",
            keywords = listOf("cache", "catalog", "coldstart"),
        ),
        inc(
            id = "INC-4503",
            description = "Memory leak in image resizer",
            keywords = listOf("memory", "leak", "image"),
        ),
        inc(
            id = "INC-4504",
            description = "Kafka rebalance storms after broker restart",
            keywords = listOf("kafka", "rebalance", "storm"),
        ),
        inc(
            id = "INC-4505",
            description = "Login service DDOSed by credential stuffing",
            keywords = listOf("login", "ddos", "credential"),
        ),
        inc(
            id = "INC-4506",
            description = "Feature flag flip caused cart total regression",
            keywords = listOf("feature", "flag", "cart"),
        ),
    )

    private val retriever = DefaultIncidentRetriever(corpus)

    @Test
    fun structural_match_outranks_lexical_only_match() {
        // Query: touches the payment-service file + method. Structural should hit INC-4402
        // and INC-4417; lexical-only might dig out anything with "charge" or "retry".
        val results = retriever.retrieve(
            methods = listOf("PaymentService.charge"),
            keywords = listOf("null", "billing"),
            touchedFiles = listOf("services/payment-service/PaymentService.java"),
        )
        assertTrue("Top-5 should be populated", results.isNotEmpty())
        // The structural-matched incidents must appear ABOVE any lexical-only match.
        val firstId = results.first().id
        assertTrue(
            "Expected INC-4402 or INC-4417 first, got $firstId",
            firstId == "INC-4402" || firstId == "INC-4417",
        )
    }

    @Test
    fun bm25_surfaces_lexical_match_when_no_structural_hit() {
        // No method/file touches anything in the corpus → stage 1 empty.
        // Query keywords should pull "stampede / cache / catalog" to the top.
        val results = retriever.retrieve(
            methods = emptyList(),
            keywords = listOf("cache", "catalog", "stampede"),
            touchedFiles = listOf("unrelated/path/NoSuchFile.java"),
        )
        assertTrue("BM25 must surface at least one hit", results.isNotEmpty())
        assertEquals("INC-4502", results.first().id)
    }

    @Test
    fun empty_query_returns_empty() {
        val results = retriever.retrieve(
            methods = emptyList(),
            keywords = emptyList(),
            touchedFiles = emptyList(),
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun top_k_caps_at_five() {
        // Broad keyword set that matches many docs — ensure we still only get 5 back.
        val results = retriever.retrieve(
            methods = emptyList(),
            keywords = listOf("retry", "timeout", "race", "lock", "null", "backoff", "cache", "memory", "pool"),
            touchedFiles = emptyList(),
        )
        assertTrue("Expected ≤5 results, got ${results.size}", results.size <= 5)
    }

    @Test
    fun rrf_is_reciprocal_rank_fusion() {
        // White-box: feed the retriever's internal rrf helper two rankings and assert that
        // doc A (top of list 1) beats doc B (top of list 2) when both appear only once.
        val fused = retriever.rrf(
            listOf(
                listOf("A", "B"),
                listOf("B", "A"),
            ),
            k = 60,
        )
        // Both A and B get 1/61 + 1/62; tied — order preserved from first seen.
        assertEquals(listOf("A", "B"), fused)
    }

    @Test
    fun structural_fuzzy_file_suffix_match() {
        // Touched file is a shorter suffix — should still match incident's full offending file.
        val results = retriever.retrieve(
            methods = emptyList(),
            keywords = emptyList(),
            touchedFiles = listOf("PaymentService.java"),
        )
        assertTrue(
            "Expected PaymentService incidents via suffix match",
            results.any { it.id == "INC-4402" || it.id == "INC-4417" },
        )
    }
}
