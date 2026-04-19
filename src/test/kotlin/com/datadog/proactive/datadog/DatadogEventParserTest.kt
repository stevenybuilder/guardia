package com.datadog.proactive.datadog

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM parser tests — no IDE harness. The input JSON mirrors what the Datadog v1
 * Events API returns for an event produced by `scripts/ingest-to-datadog.py`.
 *
 * Contract being asserted: the symmetric inverse of that script's `build_event_payload`
 * round-trips through [DatadogEventParser] with the same structured fields recovered.
 */
class DatadogEventParserTest {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun parseJson(json: String): Map<String, Any?> {
        val t = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(t).fromJson(json)!!
    }

    @Test
    fun parses_resolved_npe_incident_recovers_code_snippet_and_metadata() {
        // Shape lifted from a real Datadog v1 Events API response. Irrelevant fields
        // (url, resource, etc.) left in to prove we cope with extra keys.
        val eventJson = """
        {
          "id": 7123456789012345678,
          "title": "[INC-4402] NullPointerException in PaymentService.charge for EU customers",
          "text": "Charge requests failing with NPE for customers without a resolved billing country. Errors swallowed by generic catch and logged as timeouts. Masked the real cause for 2h.\n\n## Root cause\nMissing null-check on customer.billingAddress.country at PaymentService.java:142 when retry branch activated.\n\n## Offending code\n```java\nif (retryCount > 0) {\n  country = customer.billingAddress.country; // NPE\n}\n```\n\n## Metadata\n- offending_file: services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java\n- offending_methods: PaymentService.charge\n- offending_commit: a3f4c2b\n- keywords: null, NPE, billing, address, country, retry\n- incident_id: INC-4402",
          "priority": "normal",
          "tags": [
            "swagstore.hackathon.v1",
            "incident_id:inc-4402",
            "service:payment-service",
            "severity:sev-1",
            "status:resolved",
            "keyword:null",
            "keyword:npe",
            "keyword:billing",
            "keyword:address",
            "keyword:country",
            "keyword:retry",
            "method:paymentservice.charge"
          ],
          "alert_type": "error",
          "source_type_name": "my_apps",
          "date_happened": 1741995120,
          "url": "/event/event?id=7123456789012345678"
        }
        """.trimIndent()

        val event = parseJson(eventJson)
        val incident = DatadogEventParser.parse(event)
        assertNotNull("parser should recognize swagstore event", incident)
        incident!!

        // Tag-derived structured fields
        assertEquals("inc-4402", incident.id.lowercase()) // tag is lowercased by sanitize
        assertEquals("payment-service", incident.service)
        assertEquals("SEV-1", incident.severity)
        assertEquals("resolved", incident.status)
        assertTrue("keywords pulled from tags", incident.keywords.containsAll(listOf("null", "retry")))
        assertTrue("methods pulled from tags", incident.offending_methods.any { it.contains("charge", ignoreCase = true) })

        // Metadata-line fallback fields
        assertEquals("a3f4c2b", incident.offending_commit)
        assertTrue(
            "offending_file metadata preserved",
            incident.offending_file.endsWith("PaymentService.java")
        )

        // Description = text BEFORE the first `##` heading
        assertTrue(
            "description captures the pre-heading preface",
            incident.description.startsWith("Charge requests failing with NPE")
        )
        assertTrue(
            "description does not leak the Root cause heading",
            "## Root cause" !in incident.description
        )

        // Root cause section recovered (resolved → root_cause, not hypothesis)
        assertNotNull("root_cause populated for resolved incident", incident.root_cause)
        assertNull("hypothesis is null for resolved incident", incident.root_cause_hypothesis)
        assertTrue(
            "root cause mentions PaymentService:142",
            incident.root_cause!!.contains("PaymentService.java:142")
        )

        // Fenced code block recovery — the key parser trick
        val code = incident.offending_code_snippet
        assertTrue("code snippet recovered", code.isNotBlank())
        assertTrue("code snippet does not leak backticks", "```" !in code)
        assertTrue("code snippet does not leak language hint", !code.startsWith("java"))
        assertTrue("code snippet contains retry branch", code.contains("retryCount"))
        assertTrue("code snippet contains NPE line", code.contains("customer.billingAddress.country"))

        // Title cleaned of `[INC-xxxx]` prefix
        assertTrue(
            "title stripped of incident-id prefix",
            incident.title.startsWith("NullPointerException")
        )
    }

    @Test
    fun parses_open_incident_uses_hypothesis_field() {
        val eventJson = """
        {
          "title": "[INC-4417] Elevated 5xx on PaymentService.charge in EU region",
          "text": "Error rate spiked 8x starting 09:12 UTC.\n\n## Root cause\nRetry loop added in commit b82a11c lacks backoff.\n\n## Offending code\n```java\nwhile (result == null && attempts++ < 5) {\n  result = stripe.charges().create(params);\n}\n```\n\n## Metadata\n- offending_file: services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java\n- offending_methods: PaymentService.charge\n- offending_commit: b82a11c\n- keywords: retry, stripe, 429\n- incident_id: INC-4417",
          "tags": [
            "swagstore.hackathon.v1",
            "incident_id:inc-4417",
            "service:payment-service",
            "severity:sev-2",
            "status:open",
            "keyword:retry",
            "method:paymentservice.charge"
          ],
          "date_happened": 1744796320
        }
        """.trimIndent()

        val incident = DatadogEventParser.parse(parseJson(eventJson))!!
        assertEquals("open", incident.status)
        assertNull("resolved incidents set root_cause; open set hypothesis", incident.root_cause)
        assertNotNull("hypothesis populated for open incident", incident.root_cause_hypothesis)
        assertTrue(incident.root_cause_hypothesis!!.contains("backoff"))
        assertTrue(incident.offending_code_snippet.contains("stripe.charges().create"))
    }

    @Test
    fun rejects_events_without_idempotency_tag() {
        val eventJson = """
        {
          "title": "[INC-0000] Unrelated event from another tool",
          "text": "whatever",
          "tags": ["some-other-tag", "incident_id:inc-0000"],
          "date_happened": 1744796320
        }
        """.trimIndent()
        assertNull(
            "parser must return null for non-swagstore events",
            DatadogEventParser.parse(parseJson(eventJson))
        )
    }

    @Test
    fun missing_fenced_block_degrades_gracefully() {
        val eventJson = """
        {
          "title": "[INC-9999] Truncated event",
          "text": "Only a description line, no code block, no metadata.",
          "tags": ["swagstore.hackathon.v1", "incident_id:inc-9999", "service:payment-service", "status:open", "severity:sev-3"],
          "date_happened": 1744796320
        }
        """.trimIndent()
        val incident = DatadogEventParser.parse(parseJson(eventJson))!!
        assertEquals("", incident.offending_code_snippet)
        assertEquals("", incident.offending_file)
        assertTrue(incident.description.startsWith("Only a description"))
    }

    @Test
    fun tag_value_sanitizer_matches_ingest_script() {
        // Symmetric with scripts/ingest-to-datadog.py :: sanitize_tag_value.
        assertEquals("inc-4402", RealDatadogService.sanitizeTagValue("INC-4402"))
        assertEquals("payment-service", RealDatadogService.sanitizeTagValue("payment-service"))
        assertEquals(
            "slashes_colons_allowed_a/b:c",
            RealDatadogService.sanitizeTagValue("slashes colons_allowed_a/b:c")
        )
        // Leading non-letter gets an `x` prefix
        assertEquals("x4_tag", RealDatadogService.sanitizeTagValue("4 tag"))
    }

    @Test
    fun method_tag_value_reduces_fq_names_to_simple_form() {
        assertEquals(
            "paymentservice.charge",
            RealDatadogService.methodTagValue("com.swagstore.payment.PaymentService.charge")
        )
        assertEquals(
            "orderservice.place",
            RealDatadogService.methodTagValue("OrderService.place")
        )
    }
}
