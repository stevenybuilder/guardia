package com.datadog.proactive.fixtures

/**
 * In-memory HashMap indexes built once at plugin startup from FixtureRoot.
 * Backs MockDatadogService — every DatadogService query resolves via one of the 8 indexes.
 *
 * Index set (locked in 2026-04-18 research agent §Dataset Architecture):
 *   1. servicesByName                   — O(1) service lookup
 *   2. incidentsById                    — O(1) incident lookup
 *   3. incidentsByService               — service → incidents
 *   4. incidentsByKeyword               — lowercased inverted index for get_related_incidents
 *   5. incidentsByOffendingMethod       — fq_name → incidents that blamed that method
 *   6. methodsByFqName                  — O(1) method-of-interest lookup (gutter icon driver)
 *   7. eventsByService                  — sorted by timestamp ascending
 *   8. eventsByIncidentId               — incident-clustered event lookup
 */
class FixtureIndex private constructor(
    val root: FixtureRoot,
    val servicesByName: Map<String, Service>,
    val incidentsById: Map<String, Incident>,
    val incidentsByService: Map<String, List<Incident>>,
    val incidentsByKeyword: Map<String, List<Incident>>,
    val incidentsByOffendingMethod: Map<String, List<Incident>>,
    val methodsByFqName: Map<String, MethodOfInterest>,
    val eventsByService: Map<String, List<Event>>,
    val eventsByIncidentId: Map<String, List<Event>>,
) {
    companion object {
        fun build(root: FixtureRoot): FixtureIndex {
            val servicesByName = root.services.associateBy { it.name }
            val incidentsById = root.incidents.associateBy { it.id }
            val incidentsByService = root.incidents.groupBy { it.service }

            val keywordMap = HashMap<String, MutableList<Incident>>()
            for (inc in root.incidents) {
                for (kw in inc.keywords) {
                    keywordMap.getOrPut(kw.lowercase()) { mutableListOf() }.add(inc)
                }
            }

            val byMethod = HashMap<String, MutableList<Incident>>()
            for (inc in root.incidents) {
                for (m in inc.offending_methods) {
                    byMethod.getOrPut(m) { mutableListOf() }.add(inc)
                }
            }

            val methodsByFqName = root.methods_of_interest.associateBy { it.fq_name }

            val eventsByService = root.events
                .groupBy { it.service }
                .mapValues { (_, v) -> v.sortedBy { it.timestamp } }

            val eventsByIncidentId = root.events
                .filter { it.related_incident_id != null }
                .groupBy { it.related_incident_id!! }

            return FixtureIndex(
                root = root,
                servicesByName = servicesByName,
                incidentsById = incidentsById,
                incidentsByService = incidentsByService,
                incidentsByKeyword = keywordMap,
                incidentsByOffendingMethod = byMethod,
                methodsByFqName = methodsByFqName,
                eventsByService = eventsByService,
                eventsByIncidentId = eventsByIncidentId,
            )
        }
    }
}
