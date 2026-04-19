package com.stevenyang.datadogproactive.diff

import com.datadog.proactive.datadog.MethodDetail
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for [IncidentDiffLauncher].
 *
 * We deliberately exercise [IncidentDiffLauncher.buildRequest] (package-internal)
 * rather than the public [IncidentDiffLauncher.showDiff] so we don't have to
 * mock out IntelliJ's diff window — the contract we care about is that the
 * [com.intellij.diff.requests.SimpleDiffRequest] is well-formed:
 *   - Non-null request, 2 contents
 *   - Right-pane label contains the incident id
 *   - Graceful handling when `offendingSnippetPreview` is null (no NPE)
 */
class IncidentDiffLauncherTest : BasePlatformTestCase() {

    private fun launcher(): IncidentDiffLauncher =
        project.getService(IncidentDiffLauncher::class.java)

    fun testBuildRequestIsNonNullWithTwoContents() {
        val ref = MethodDetail.IncidentRef(
            id = "INC-4402",
            title = "NPE in PaymentService.charge",
            severity = "SEV-1",
            status = "resolved",
            rootCause = "missing null-check on billingAddress.country",
            offendingSnippetPreview = "if (retryCount > 0) {\n  country = customer.billingAddress.country;\n}",
            sourceUrl = "https://example.com/postmortem/INC-4402",
        )
        val req = launcher().buildRequest(
            currentCode = "public void charge() { /* current */ }",
            incident = ref,
            currentLabel = "Your code — PaymentService.charge",
        )
        assertNotNull("buildRequest must return a non-null SimpleDiffRequest", req)
        assertEquals("SimpleDiffRequest must carry exactly 2 contents", 2, req.contents.size)
    }

    fun testRightLabelContainsIncidentId() {
        val ref = MethodDetail.IncidentRef(
            id = "INC-1234",
            title = "Example outage",
            severity = "SEV-2",
            status = "resolved",
            rootCause = null,
            offendingSnippetPreview = "// offending code here",
            sourceUrl = null,
        )
        val req = launcher().buildRequest("public void foo() {}", ref)
        val titles = req.contentTitles
        assertEquals(2, titles.size)
        val rightTitle = titles[1] ?: ""
        assertTrue(
            "Right pane label should contain the incident id, got: $rightTitle",
            rightTitle.contains("INC-1234"),
        )
    }

    fun testNullOffendingSnippetDoesNotThrow() {
        val ref = MethodDetail.IncidentRef(
            id = "INC-9999",
            title = "Historical incident with no snippet recorded",
            severity = "SEV-3",
            status = "resolved",
            rootCause = null,
            offendingSnippetPreview = null,
            sourceUrl = null,
        )
        // Should not throw NPE — the launcher must substitute a placeholder.
        val req = launcher().buildRequest("", ref)
        assertNotNull(req)
        assertEquals(2, req.contents.size)
    }

    fun testTitleReferencesSameCodeSameBugMoment() {
        val ref = MethodDetail.IncidentRef(
            id = "INC-2020",
            title = "Title",
            severity = "SEV-2",
            status = "open",
            rootCause = null,
            offendingSnippetPreview = "snippet",
            sourceUrl = null,
        )
        val req = launcher().buildRequest("code", ref)
        val title = req.title ?: ""
        assertTrue(
            "Diff title should surface the 'same code, same bug' demo framing, got: $title",
            title.contains("INC-2020") && title.contains("Same"),
        )
    }
}
