package com.proactive.domain

import com.datadog.proactive.fixtures.Incident as FixtureIncident
import com.datadog.proactive.fixtures.Service as FixtureService

/**
 * Adapter layer bridging the fixture DTO shape (snake_case, JSON-mirrored) to the pure-Kotlin
 * domain types used by Layer 1 reasoning ([com.proactive.risk.RiskHeuristic]) and
 * [com.proactive.risk.IncidentRetriever]. Keeping this mapping in a single file lets
 * `RealDatadogService` (future) emit the same domain types without rewriting callers.
 *
 * One asymmetry worth knowing: fixture `Incident.offending_file` is a single String; domain
 * `Incident.offendingFiles` is a `List<String>`. The list form is future-proof for Datadog v2
 * which returns multi-file offenders via `relationships.commits[*].files`.
 */

fun FixtureService.toDomain(): ServiceContext = ServiceContext(
    name = name,
    errorRate1h = error_rate_1h,
    errorRateDeltaVsLastCommit = error_rate_delta_vs_last_commit,
    directDepCount = direct_dep_count,
    changeVelocity7d = change_velocity_7d,
    deployWindowToday = deploy_window_today,
    openIncidentCount = open_incident_count,
)

fun FixtureIncident.toDomain(): Incident = Incident(
    id = id,
    service = service,
    status = when (status.lowercase()) {
        "open" -> Incident.Status.OPEN
        "resolved" -> Incident.Status.RESOLVED
        else -> Incident.Status.OPEN
    },
    severity = severity,
    title = title,
    description = description,
    rootCause = root_cause ?: root_cause_hypothesis,
    offendingMethods = offending_methods,
    offendingFiles = listOf(offending_file),
    offendingCodeSnippet = offending_code_snippet,
    keywords = keywords,
    openedAt = opened_at,
    resolvedAt = resolved_at,
)
