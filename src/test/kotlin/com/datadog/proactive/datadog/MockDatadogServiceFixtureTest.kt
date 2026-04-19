package com.datadog.proactive.datadog

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises [MockDatadogService.allMethodsOfInterest] against the frozen fixture.
 * Wedge 1 proactive inbox depends on this returning non-empty and in a deterministic,
 * risk-hint-descending order.
 */
class MockDatadogServiceFixtureTest : BasePlatformTestCase() {

    fun testAllMethodsOfInterestIsNonEmpty() {
        val svc = project.getService(DatadogService::class.java)
        val methods = svc.allMethodsOfInterest()
        assertTrue(
            "Expected at least one method-of-interest from the fixture; got ${methods.size}",
            methods.isNotEmpty(),
        )
    }

    fun testAllMethodsOfInterestIsSortedHighThenMediumThenLow() {
        val svc = project.getService(DatadogService::class.java)
        val methods = svc.allMethodsOfInterest()
        val rank = mapOf("HIGH" to 0, "MEDIUM" to 1, "LOW" to 2)
        val ranks = methods.map { rank[it.method.risk_hint.uppercase()] ?: 99 }
        assertEquals(
            "Methods should be sorted by risk_hint (HIGH → MEDIUM → LOW); got $ranks",
            ranks.sorted(),
            ranks,
        )
    }

    fun testAllMethodsOfInterestAreMaterializedWithServiceAndMethodContext() {
        val svc = project.getService(DatadogService::class.java)
        val methods = svc.allMethodsOfInterest()
        for (ctx in methods) {
            assertNotNull("Service must resolve for ${ctx.method.fq_name}", ctx.service)
            assertTrue(
                "openIncidentCount must be ≥0 for ${ctx.method.fq_name}",
                ctx.openIncidentCount >= 0,
            )
        }
    }
}
