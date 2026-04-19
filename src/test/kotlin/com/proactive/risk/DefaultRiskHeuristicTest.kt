package com.proactive.risk

import com.proactive.domain.Diff
import com.proactive.domain.Incident
import com.proactive.domain.ServiceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Fixed-input → fixed-output tests for ARCHITECTURE.md §2.1's baseline formula.
 * Pure JVM, no IntelliJ harness — JUnit 4 only. Running via `./gradlew test`.
 */
class DefaultRiskHeuristicTest {

    private val fixedNow: () -> Instant = { Instant.parse("2026-04-18T00:00:00Z") }
    private val heuristic = DefaultRiskHeuristic(fixedNow)

    private fun service(
        name: String = "payment-service",
        errDelta: Double = 0.0,
        depCount: Int = 0,
        velocity: Int = 0,
        deployWindow: Boolean = false,
    ) = ServiceContext(
        name = name,
        errorRate1h = 0.0,
        errorRateDeltaVsLastCommit = errDelta,
        directDepCount = depCount,
        changeVelocity7d = velocity,
        deployWindowToday = deployWindow,
        openIncidentCount = 0,
    )

    private fun diff(lines: Int = 0, files: List<String> = emptyList()) = Diff(
        text = "",
        touchedFiles = files,
        linesChanged = lines,
    )

    @Test
    fun zero_signal_produces_zero_score() {
        val baseline = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("payment-service")),
            services = listOf(service()),
            incidents = emptyList(),
        )
        assertEquals(0, baseline.score)
        // Factor map contains every named contribution (for Codex's Layer-2 prompt).
        assertEquals(
            setOf(
                "error_rate_delta",
                "deploy_window",
                "blast_radius",
                "velocity",
                "churn",
                "incident_recency",
            ),
            baseline.factors.keys,
        )
    }

    @Test
    fun error_rate_delta_scales_at_8x_and_caps_at_40() {
        // errDelta=3.0 → 3.0 * 8 = 24; nothing else contributes
        val low = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("s")),
            listOf(service(errDelta = 3.0)),
            emptyList(),
        )
        assertEquals(24.0, low.factors["error_rate_delta"]!!, 1e-9)
        assertEquals(24, low.score)

        // errDelta=10.0 → would be 80, but caps at 40
        val capped = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("s")),
            listOf(service(errDelta = 10.0)),
            emptyList(),
        )
        assertEquals(40.0, capped.factors["error_rate_delta"]!!, 1e-9)
        assertEquals(40, capped.score)
    }

    @Test
    fun deploy_window_is_binary_15() {
        val on = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("s")),
            listOf(service(deployWindow = true)),
            emptyList(),
        )
        assertEquals(15.0, on.factors["deploy_window"]!!, 1e-9)

        val off = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("s")),
            listOf(service(deployWindow = false)),
            emptyList(),
        )
        assertEquals(0.0, off.factors["deploy_window"]!!, 1e-9)
    }

    @Test
    fun blast_radius_sums_and_caps_at_20() {
        // Three services at 7 deps each → sum=21, cap at 20
        val capped = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("a", "b", "c")),
            listOf(service("a", depCount = 7), service("b", depCount = 7), service("c", depCount = 7)),
            emptyList(),
        )
        assertEquals(20.0, capped.factors["blast_radius"]!!, 1e-9)

        val uncapped = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("a")),
            listOf(service("a", depCount = 5)),
            emptyList(),
        )
        assertEquals(5.0, uncapped.factors["blast_radius"]!!, 1e-9)
    }

    @Test
    fun velocity_scales_by_point_seven_and_caps_at_fifteen_input() {
        // velocity=10 → min(10, 15) * 0.7 = 7.0
        val seven = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("s")),
            listOf(service(velocity = 10)),
            emptyList(),
        )
        assertEquals(7.0, seven.factors["velocity"]!!, 1e-9)

        // velocity=50 → capped at 15 * 0.7 = 10.5
        val capped = heuristic.compute(
            RiskRequest(diff = diff(), candidateServices = listOf("s")),
            listOf(service(velocity = 50)),
            emptyList(),
        )
        assertEquals(10.5, capped.factors["velocity"]!!, 1e-9)
    }

    @Test
    fun churn_caps_at_eight_at_fifty_lines() {
        val small = heuristic.compute(
            RiskRequest(diff = diff(lines = 10), candidateServices = listOf("s")),
            listOf(service()),
            emptyList(),
        )
        // 10/50 * 8 = 1.6
        assertEquals(1.6, small.factors["churn"]!!, 1e-9)

        val big = heuristic.compute(
            RiskRequest(diff = diff(lines = 500), candidateServices = listOf("s")),
            listOf(service()),
            emptyList(),
        )
        assertEquals(8.0, big.factors["churn"]!!, 1e-9)
    }

    @Test
    fun incident_recency_open_sev2_on_touched_file_is_15() {
        val inc = Incident(
            id = "INC-9001",
            service = "payment-service",
            status = Incident.Status.OPEN,
            severity = "SEV-2",
            title = "x",
            description = "y",
            rootCause = null,
            offendingMethods = listOf("PaymentService.charge"),
            offendingFiles = listOf("src/PaymentService.java"),
            offendingCodeSnippet = null,
            keywords = emptyList(),
            openedAt = "2026-04-17T10:00:00Z",
            resolvedAt = null,
        )
        val baseline = heuristic.compute(
            RiskRequest(diff = diff(files = listOf("src/PaymentService.java")), candidateServices = listOf("s")),
            listOf(service()),
            listOf(inc),
        )
        assertEquals(15.0, baseline.factors["incident_recency"]!!, 1e-9)
    }

    @Test
    fun incident_recency_resolved_under_30d_is_9() {
        // 0.6 * 15 = 9.0
        val inc = Incident(
            id = "INC-9002",
            service = "s",
            status = Incident.Status.RESOLVED,
            severity = "SEV-2",
            title = "x",
            description = "y",
            rootCause = null,
            offendingMethods = emptyList(),
            offendingFiles = listOf("src/PaymentService.java"),
            offendingCodeSnippet = null,
            keywords = emptyList(),
            openedAt = "2026-04-01T10:00:00Z",
            resolvedAt = "2026-04-01T12:00:00Z", // 17 days before fixedNow
        )
        val baseline = heuristic.compute(
            RiskRequest(diff = diff(files = listOf("src/PaymentService.java")), candidateServices = listOf("s")),
            listOf(service()),
            listOf(inc),
        )
        assertEquals(9.0, baseline.factors["incident_recency"]!!, 1e-9)
    }

    @Test
    fun incident_recency_resolved_over_90d_is_zero() {
        val inc = Incident(
            id = "INC-9003",
            service = "s",
            status = Incident.Status.RESOLVED,
            severity = "SEV-1",
            title = "x",
            description = "y",
            rootCause = null,
            offendingMethods = emptyList(),
            offendingFiles = listOf("src/PaymentService.java"),
            offendingCodeSnippet = null,
            keywords = emptyList(),
            openedAt = "2025-10-01T00:00:00Z",
            resolvedAt = "2025-10-02T00:00:00Z",
        )
        val baseline = heuristic.compute(
            RiskRequest(diff = diff(files = listOf("src/PaymentService.java")), candidateServices = listOf("s")),
            listOf(service()),
            listOf(inc),
        )
        assertEquals(0.0, baseline.factors["incident_recency"]!!, 1e-9)
    }

    @Test
    fun full_stack_scenarioA_ish_clamps_to_100() {
        // Worst-case signals: triggers all terms near their caps. Expected raw > 100,
        // clamped to 100.
        val inc = Incident(
            id = "INC-4402",
            service = "payment-service",
            status = Incident.Status.OPEN,
            severity = "SEV-1",
            title = "x",
            description = "y",
            rootCause = null,
            offendingMethods = listOf("PaymentService.charge"),
            offendingFiles = listOf("src/PaymentService.java"),
            offendingCodeSnippet = null,
            keywords = emptyList(),
            openedAt = "2026-04-17T00:00:00Z",
            resolvedAt = null,
        )
        val baseline = heuristic.compute(
            RiskRequest(
                diff = diff(lines = 200, files = listOf("src/PaymentService.java")),
                candidateServices = listOf("payment-service", "order-service", "inventory-service"),
            ),
            services = listOf(
                service("payment-service", errDelta = 10.0, depCount = 10, velocity = 20, deployWindow = true),
                service("order-service", errDelta = 2.0, depCount = 10, velocity = 5, deployWindow = true),
                service("inventory-service", errDelta = 1.0, depCount = 10, velocity = 5, deployWindow = false),
            ),
            incidents = listOf(inc),
        )
        // factors: error_rate=40, deploy=15, blast=20, velocity=10.5, churn=8, recency=15 → 108.5 → clamp 100
        assertEquals(100, baseline.score)
        assertTrue(baseline.factors["error_rate_delta"]!! >= 39.9)
        assertTrue(baseline.factors["incident_recency"]!! >= 14.9)
    }

    @Test
    fun no_services_does_not_throw() {
        val baseline = heuristic.compute(
            RiskRequest(diff = diff(lines = 10), candidateServices = emptyList()),
            services = emptyList(),
            incidents = emptyList(),
        )
        assertEquals(0, baseline.factors["error_rate_delta"]!!.toInt())
        assertTrue(baseline.score in 0..100)
    }
}
