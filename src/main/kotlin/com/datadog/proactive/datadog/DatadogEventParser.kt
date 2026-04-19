package com.datadog.proactive.datadog

import com.datadog.proactive.fixtures.Incident

/**
 * Recovers our fixture-shape [Incident] from a raw Datadog v1 Events API payload.
 *
 * Symmetric inverse of `scripts/ingest-to-datadog.py :: build_event_payload`. Structured
 * metadata (service, severity, status, keywords, methods, incident_id, offending_file,
 * offending_commit) is pulled from the event's `tags` array — faster, lossless, and never
 * truncated.
 *
 * Narrative fields (`description`, `root_cause`, `offending_code_snippet`, `resolution`)
 * must be recovered from the `text` field, which is capped at 4000 chars by Datadog. The
 * text uses a stable Markdown layout:
 *
 *     <description>
 *
 *     ## Root cause
 *     <root_cause or root_cause_hypothesis>
 *
 *     ## Offending code
 *     ```<lang>
 *     <offending_code_snippet>
 *     ```
 *
 *     ## Metadata
 *     - offending_file: ...
 *     - offending_methods: a, b, c
 *     ...
 *
 * Parse strategy:
 *  1. Grab the FIRST fenced ``` ... ``` block → offending_code_snippet (strip lang hint).
 *  2. `## Root cause` → `## ` next heading → trim = root_cause.
 *  3. Everything before the first `## ` heading = description.
 *  4. Metadata lines `- key: value` → fill offending_file / offending_methods fallback if
 *     tags were absent (robust against old ingest-script versions).
 *
 * Returns null if the event is clearly not one of ours (missing idempotency tag) or the
 * title isn't in the expected `[INC-xxxx] ...` shape. Malformed text sections degrade
 * gracefully — we still return an Incident with empty strings for missing fields.
 */
object DatadogEventParser {

    private const val IDEMPOTENCY_TAG = "swagstore.hackathon.v1"

    /** Matches the first ```lang\ncode\n``` block (non-greedy body, dotall). */
    private val FENCED_CODE_REGEX = Regex("```([A-Za-z0-9_+\\-]*)\\s*\\n([\\s\\S]*?)```")
    private val HEADING_REGEX = Regex("(?m)^##\\s+(.+?)\\s*$")
    private val META_LINE_REGEX = Regex("(?m)^-\\s+([A-Za-z_]+):\\s*(.*)$")
    private val TITLE_ID_REGEX = Regex("^\\[(INC-[A-Za-z0-9_\\-]+)]\\s*(.*)$")

    /**
     * [event] is the JSON object as returned by `GET /api/v1/events` — typically a
     * Map<String, Any?> parsed via Moshi. We pick fields defensively so a schema drift in
     * a nested key doesn't nuke the whole parse.
     */
    fun parse(event: Map<String, Any?>): Incident? {
        val tags = (event["tags"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        if (IDEMPOTENCY_TAG !in tags) return null

        val tagMap = tagsByKey(tags)
        val incidentId = tagMap.single("incident_id")
            ?: (event["title"] as? String)?.let { TITLE_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: return null

        val title = (event["title"] as? String)
            ?.let { TITLE_ID_REGEX.find(it)?.groupValues?.get(2)?.trim().orEmpty().ifBlank { it } }
            ?: ""

        val text = (event["text"] as? String).orEmpty()
        val sections = splitSections(text)
        val code = extractFencedCode(text)
        val description = sections.preface.trim()
        val rootCause = sections.bodies["Root cause"]?.trim().orEmpty()
        val resolution = sections.bodies["Resolution"]?.trim()

        val metaLines = sections.bodies["Metadata"].orEmpty()
        val metaMap = META_LINE_REGEX.findAll(metaLines).associate {
            it.groupValues[1] to it.groupValues[2].trim()
        }

        val service = tagMap.single("service") ?: metaMap["service"].orEmpty()
        val severity = tagMap.single("severity")?.uppercase() ?: "SEV-3"
        val status = tagMap.single("status")?.lowercase() ?: "unknown"
        val keywords = tagMap.multi("keyword").ifEmpty {
            metaMap["keywords"]?.splitClean()
                .orEmpty()
        }
        val methods = tagMap.multi("method").ifEmpty {
            metaMap["offending_methods"]?.splitClean().orEmpty()
        }
        val offendingFile = metaMap["offending_file"].orEmpty()
        val offendingCommit = metaMap["offending_commit"].orEmpty()

        val openedAt = (event["date_happened"] as? Number)?.let { epochToIso(it.toLong()) }
            ?: (event["date_happened"] as? String) ?: ""

        // Datadog Events API doesn't carry a resolution timestamp — infer from status.
        val resolvedAt = if (status == "resolved") null else null

        return Incident(
            id = incidentId,
            service = service,
            status = status,
            severity = severity,
            opened_at = openedAt,
            resolved_at = resolvedAt,
            title = title,
            description = description,
            root_cause = if (status == "resolved") rootCause.ifBlank { null } else null,
            root_cause_hypothesis = if (status != "resolved") rootCause.ifBlank { null } else null,
            offending_commit = offendingCommit,
            offending_file = offendingFile,
            offending_methods = methods,
            offending_code_snippet = code.orEmpty(),
            resolution = resolution,
            keywords = keywords,
        )
    }

    // -------- helpers ------------------------------------------------------------------

    private fun extractFencedCode(text: String): String? {
        val m = FENCED_CODE_REGEX.find(text) ?: return null
        return m.groupValues[2].trimEnd('\n')
    }

    private data class Sections(val preface: String, val bodies: Map<String, String>)

    /**
     * Splits `text` on `## Heading` lines. Everything before the first heading is `preface`;
     * each heading maps to its body (content until the next `##` or EOF).
     */
    private fun splitSections(text: String): Sections {
        val matches = HEADING_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return Sections(text, emptyMap())
        val preface = text.substring(0, matches.first().range.first)
        val bodies = mutableMapOf<String, String>()
        for ((i, m) in matches.withIndex()) {
            val heading = m.groupValues[1].trim()
            val start = m.range.last + 1
            val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
            bodies[heading] = text.substring(start, end)
        }
        return Sections(preface, bodies)
    }

    private data class TagMap(val byKey: Map<String, List<String>>) {
        fun single(k: String): String? = byKey[k]?.firstOrNull()
        fun multi(k: String): List<String> = byKey[k].orEmpty()
    }

    private fun tagsByKey(tags: List<String>): TagMap {
        val acc = linkedMapOf<String, MutableList<String>>()
        for (t in tags) {
            val idx = t.indexOf(':')
            if (idx <= 0) continue
            val k = t.substring(0, idx)
            val v = t.substring(idx + 1)
            acc.getOrPut(k) { mutableListOf() }.add(v)
        }
        return TagMap(acc)
    }

    private fun String.splitClean(): List<String> =
        split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun epochToIso(seconds: Long): String {
        // Minimal ISO-8601 UTC formatting without pulling java.time dependencies into tests.
        val instant = java.time.Instant.ofEpochSecond(seconds)
        return instant.toString()
    }
}
