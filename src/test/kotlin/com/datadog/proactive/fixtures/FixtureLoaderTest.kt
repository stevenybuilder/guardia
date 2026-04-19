package com.datadog.proactive.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-0 sanity gate (see CLAUDE.md §P0 Hour-0 sanity).
 * Run: ./gradlew test --tests FixtureLoaderTest
 *
 * Pure-JVM test — no IntelliJ platform harness needed because FixtureLoader.load() only
 * uses the classloader + Moshi. If the counts drift, either the generator was re-run
 * with different params or the JSON location moved. Both are PRD violations.
 */
class FixtureLoaderTest {

    @Test
    fun fixture_loads_with_expected_counts() {
        val root = FixtureLoader().load()
        assertEquals("services", 12, root.services.size)
        assertEquals("methods_of_interest", 15, root.methods_of_interest.size)
        // Incident + event counts are now archetype-generated at scale; test inequality to
        // stay stable across generator tweaks (demo incidents remain pinned as constants).
        assertTrue("incidents >= 2000 (got ${root.incidents.size})", root.incidents.size >= 2000)
        assertTrue("events >= 10000 (got ${root.events.size})", root.events.size >= 10000)
    }

    @Test
    fun fixture_index_builds_cleanly() {
        val index = FixtureLoader().index
        assertTrue("PaymentServiceImpl.charge is a method of interest",
            "PaymentServiceImpl.charge" in index.methodsByFqName)
        assertTrue("payment-service is a known service",
            "payment-service" in index.servicesByName)
        assertTrue("at least one incident keyword index entry",
            index.incidentsByKeyword.isNotEmpty())
    }
}
