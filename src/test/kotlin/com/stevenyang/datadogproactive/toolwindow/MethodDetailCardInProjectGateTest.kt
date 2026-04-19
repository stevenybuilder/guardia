package com.stevenyang.datadogproactive.toolwindow

import com.datadog.proactive.datadog.MethodDetail
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit-level gate check for the Compare-code UX fix. Verifies:
 *  - MethodDetailCard built with `inProject = true` reports Compare as enabled.
 *  - MethodDetailCard built with `inProject = false` reports Compare as disabled.
 *
 * This is the contract that [MethodsAtRiskInbox.insertDetailCard] relies on when
 * routing the inProject flag from its cached PSI resolve — if the card didn't
 * surface this state, legacy fixture FQNs could fire a no-op diff tab.
 */
class MethodDetailCardInProjectGateTest : BasePlatformTestCase() {

    fun testCompareEnabledWhenInProject() {
        val card = MethodDetailCard(project, buildDetail(), inProject = true)
        assertTrue(
            "Compare button must be enabled for in-project methods",
            card.compareButtonEnabledForTest(),
        )
    }

    fun testCompareDisabledWhenNotInProject() {
        val card = MethodDetailCard(project, buildDetail(), inProject = false)
        assertFalse(
            "Compare button must be disabled for legacy fixture FQNs",
            card.compareButtonEnabledForTest(),
        )
    }

    private fun buildDetail(): MethodDetail = MethodDetail(
        fqName = "com.example.PaymentService.charge",
        service = "payment-service",
        riskHint = "HIGH",
        openIncidentCount = 2,
        errorRateDelta = 0.47,
        deployWindowToday = true,
        linkedIncidents = listOf(
            MethodDetail.IncidentRef(
                id = "INC-4402",
                title = "NullPointerException in charge()",
                severity = "SEV-2",
                status = "open",
                rootCause = "Missing null check after provider lookup",
                offendingSnippetPreview = "if (provider.getToken() != null)",
                sourceUrl = null,
            ),
        ),
        whySummary = "Deploy today + 2 open incidents",
    )
}
