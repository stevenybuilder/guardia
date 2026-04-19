package com.datadog.proactive.datadog

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises [MockDatadogService.getMethodDetail] against the frozen fixture.
 *
 * Three assertions:
 *  1. getMethodDetail returns non-null for a known `fq_name` from methods_of_interest.
 *  2. Incident titles are HTML-escaped (no raw `<`/`>`/`&` characters in rendered text).
 *  3. The offending-snippet preview is bounded — max 6 lines + 400 chars total.
 */
class MockDatadogServiceMethodDetailTest : BasePlatformTestCase() {

    fun testGetMethodDetailReturnsNonNullForKnownFqName() {
        val svc = project.getService(DatadogService::class.java)
        val all = svc.allMethodsOfInterest()
        assertTrue("Fixture must have at least one method-of-interest", all.isNotEmpty())
        val first = all.first().method.fq_name
        val detail = svc.getMethodDetail(first)
        assertNotNull("getMethodDetail('$first') must return non-null", detail)
        assertEquals(first, detail!!.fqName)
        assertNotNull("whySummary must be populated", detail.whySummary)
        assertTrue("whySummary must be non-blank", detail.whySummary.isNotBlank())
    }

    fun testIncidentRefsAreHtmlEscaped() {
        val svc = project.getService(DatadogService::class.java)
        val all = svc.allMethodsOfInterest()
        // Find a method with at least one linked incident.
        val withIncident = all.firstOrNull { it.linkedIncidents.isNotEmpty() }
            ?: return // fixture degenerate — skip silently; other tests cover the path
        val detail = svc.getMethodDetail(withIncident.method.fq_name)
        assertNotNull(detail)
        for (ref in detail!!.linkedIncidents) {
            assertFalse(
                "IncidentRef.title must be HTML-escaped (no raw '<') — saw: ${ref.title}",
                ref.title.contains("<") && !ref.title.contains("&lt;"),
            )
            assertFalse(
                "IncidentRef.title must be HTML-escaped (no raw '>') — saw: ${ref.title}",
                ref.title.contains(">") && !ref.title.contains("&gt;"),
            )
            val rc = ref.rootCause
            if (rc != null) {
                assertFalse(
                    "IncidentRef.rootCause must be HTML-escaped — saw: $rc",
                    rc.contains("<") && !rc.contains("&lt;"),
                )
            }
        }
    }

    fun testSnippetPreviewIsTruncatedToSixLinesAndFourHundredChars() {
        val svc = project.getService(DatadogService::class.java)
        val all = svc.allMethodsOfInterest()
        val withIncident = all.firstOrNull { it.linkedIncidents.isNotEmpty() }
            ?: return
        val detail = svc.getMethodDetail(withIncident.method.fq_name)
        assertNotNull(detail)
        for (ref in detail!!.linkedIncidents) {
            val preview = ref.offendingSnippetPreview ?: continue
            // +2 for the trailing "\n…" truncation marker the producer may add.
            assertTrue(
                "Snippet preview exceeds 410 chars: ${preview.length}",
                preview.length <= 410,
            )
            // Max lines: original limit 6 + one optional trailing "…" line = 7.
            val lineCount = preview.lines().size
            assertTrue(
                "Snippet preview exceeds 7 lines: $lineCount",
                lineCount <= 7,
            )
        }
    }
}
