package com.stevenyang.datadogproactive.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression test for the Wedge 1 user-feedback wave: legacy fictional methods were
 * surfacing above real tsre-microservices methods in the inbox. Clicking the top row
 * opened a balloon ("not in project") rather than navigating, which read as a bug.
 *
 * Fix: once PSI resolution identifies which method-of-interest fq_names live in the
 * project's source roots, re-rank so in-project methods surface first and legacy ones
 * sink. This test drives that re-rank deterministically via the test hook
 * [MethodsAtRiskInbox.rebuildForTest] so we don't depend on real PSI indexing inside
 * a light-platform fixture (no Java sources are materialized here).
 */
class InboxOrderingTest : BasePlatformTestCase() {

    fun testInProjectMethodsRankAboveLegacy() {
        val inbox = MethodsAtRiskInbox(project)
        val methods = inbox.snapshot()
        assertTrue("Fixture must expose methods-of-interest", methods.size >= 2)

        // Pick a "real" in-project fq_name (the fixture anchors on PaymentServiceImpl.charge
        // and PaymentController.clearPayment as real tsre methods — see CLAUDE.md decision
        // log and the demo-repos/tsre-microservices tree). Fallback to the second-to-last
        // fixture entry in case the fixture is re-patched later.
        val knownRealFqName = methods
            .map { it.method.fq_name }
            .firstOrNull { it == "PaymentServiceImpl.charge" || it == "PaymentController.clearPayment" }
            ?: methods.last().method.fq_name
        val inProject = setOf(knownRealFqName)

        inbox.rebuildForTest(methods, inProject)

        val rendered = inbox.renderedRowsForTest()
        assertEquals(
            "Rebuild should render one row per method-of-interest",
            methods.size,
            rendered.size,
        )

        val firstRow = rendered.first()
        assertEquals(
            "First row after rebuild must be the in-project method",
            knownRealFqName,
            firstRow.fqName,
        )
        assertFalse(
            "First row must not be flagged legacy",
            firstRow.legacy,
        )

        // Any row whose fq_name is not in the in-project set must be flagged legacy.
        val legacyFlagsAfterFirst = rendered.drop(1).map { it.legacy }
        assertTrue(
            "All rows after the in-project block must be flagged legacy",
            legacyFlagsAfterFirst.all { it },
        )
    }

    fun testAllInProjectMethodsBubbleUpBeforeAnyLegacy() {
        val inbox = MethodsAtRiskInbox(project)
        val methods = inbox.snapshot()
        assertTrue("Need at least 3 methods for a meaningful ordering assertion", methods.size >= 3)

        // Mark the 1st and 3rd fq_names as in-project; all others should sink.
        val inProject = setOf(
            methods[0].method.fq_name,
            methods[2].method.fq_name,
        )
        inbox.rebuildForTest(methods, inProject)

        val rendered = inbox.renderedRowsForTest()
        val firstLegacyIndex = rendered.indexOfFirst { it.legacy }
        val lastInProjectIndex = rendered.indexOfLast { !it.legacy }
        assertTrue(
            "Every in-project row must appear before the first legacy row " +
                "(firstLegacyIndex=$firstLegacyIndex, lastInProjectIndex=$lastInProjectIndex)",
            firstLegacyIndex == -1 || lastInProjectIndex < firstLegacyIndex,
        )
        assertEquals(
            "The in-project row count must match the injected set size",
            inProject.size,
            rendered.count { !it.legacy },
        )
    }
}
