package com.stevenyang.datadogproactive.projectview

import com.datadog.proactive.fixtures.FixtureLoader
import com.datadog.proactive.fixtures.MethodOfInterest
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-scoped cache of "which files host methods_of_interest" so the project-view node
 * decorator can paint risk badges in O(1) per node render instead of re-walking the fixture
 * on every tree repaint.
 *
 * Key mapping strategy:
 *  - Fixture entries expose `MethodOfInterest.file` (relative path — either
 *    `demo-repos/tsre-microservices/...` for real tsre entries or `services/...` for
 *    legacy fictional entries).
 *  - We normalize to forward slashes and key by the **last 3 path segments** as a fallback
 *    suffix, in addition to the full relative path. Both are resolved against
 *    `VirtualFile.path` at decorate-time: the VF path is also scanned with the same
 *    suffix logic. This is robust to the repo being nested under an arbitrary absolute
 *    path on the demo laptop.
 *
 * Directory aggregation: for each at-risk file, every ancestor path segment also gets a
 * count entry so folder nodes can emit a `(N at-risk files)` badge without walking the
 * tree.
 *
 * Built lazily on first `lookupFile` / `lookupDirectory` call. No file I/O — purely derived
 * from the already-loaded FixtureIndex. `refresh()` drops the cached index so a future
 * `DatadogService.refresh` (or UAT hot-swap) forces a rebuild.
 */
@Service(Service.Level.PROJECT)
class RiskFilesIndex(private val project: Project) {

    enum class Severity { HIGH, MEDIUM, LOW }

    /** One at-risk file record. `incidentCount` is the union across all methods in the file. */
    data class RiskEntry(
        val filePathSuffix: String,
        val severity: Severity,
        val incidentCount: Int,
        val methodFqNames: List<String>,
    )

    @Volatile private var cache: Index? = null

    private data class Index(
        /** Keyed by the last 3 path segments (forward-slash, lowercased). */
        val fileBySuffix: Map<String, RiskEntry>,
        /** Directory suffix → count of at-risk descendant files. */
        val dirCountsBySuffix: Map<String, Int>,
    )

    fun lookupFile(virtualFilePath: String): RiskEntry? {
        val idx = cache ?: build().also { cache = it }
        val normalized = normalize(virtualFilePath)
        // Try progressively shorter suffixes: full path, last 4, last 3 — we keyed on last-3.
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.size < 1) return null
        val suffix3 = parts.takeLast(minOf(3, parts.size)).joinToString("/")
        return idx.fileBySuffix[suffix3]
    }

    fun lookupDirectory(virtualFilePath: String): Int? {
        val idx = cache ?: build().also { cache = it }
        val normalized = normalize(virtualFilePath)
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        // Check 1-3 trailing segments (folder nodes tend to be shallow suffixes).
        for (n in minOf(3, parts.size) downTo 1) {
            val key = parts.takeLast(n).joinToString("/")
            idx.dirCountsBySuffix[key]?.let { return it }
        }
        return null
    }

    /** Clears the cached index — next lookup will rebuild from the current fixture. */
    fun refresh() {
        cache = null
    }

    private fun build(): Index {
        // Read methods_of_interest directly from the application-scoped FixtureLoader index.
        // The DatadogService interface is intentionally narrow (one method per Codex tool), so
        // bulk-enumeration goes through the fixture layer instead. Future RealDatadogService
        // deployments can seed the same raw list.
        val methods: List<MethodOfInterest> = try {
            FixtureLoader.getInstance().index.root.methods_of_interest
        } catch (_: Throwable) {
            emptyList()
        }
        // project is referenced from the constructor only to scope the @Service level; no
        // per-project fixture filtering today.
        @Suppress("UNUSED_VARIABLE") val p = project

        val fileMap = HashMap<String, RiskEntry>()
        val dirCounts = HashMap<String, Int>()

        // Track ancestor directory suffixes per unique file so we don't double-count if
        // two methods sit in the same file.
        val filesSeenForDirRollup = HashSet<String>()

        for (method in methods) {
            val file = method.file
            if (file.isBlank()) continue
            val normalized = normalize(file)
            val parts = normalized.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val suffix3 = parts.takeLast(minOf(3, parts.size)).joinToString("/")
            val severity = when (method.risk_hint.uppercase()) {
                "HIGH" -> Severity.HIGH
                "MED", "MEDIUM" -> Severity.MEDIUM
                else -> Severity.LOW
            }
            val existing = fileMap[suffix3]
            fileMap[suffix3] = if (existing == null) {
                RiskEntry(
                    filePathSuffix = suffix3,
                    severity = severity,
                    incidentCount = method.linked_incidents.size,
                    methodFqNames = listOf(method.fq_name),
                )
            } else {
                RiskEntry(
                    filePathSuffix = suffix3,
                    severity = maxSeverity(existing.severity, severity),
                    incidentCount = existing.incidentCount + method.linked_incidents.size,
                    methodFqNames = existing.methodFqNames + method.fq_name,
                )
            }

            // Directory roll-up. For each ancestor directory in the file's path, key by
            // 1..3 trailing segments of that directory so the decorator can match a
            // directory VirtualFile.path (absolute) via suffix lookup.
            //   e.g. file parts = [..., src, paymentservice, src, main, java, ..., PaymentServiceImpl.java]
            //   ancestor dirs   = all prefixes length 1..parts.size-1
            //   dir key per anc = ancestor's last 1..3 segments
            if (normalized in filesSeenForDirRollup) continue
            filesSeenForDirRollup += normalized
            for (ancestorLen in 1 until parts.size) {
                val ancestorParts = parts.subList(0, ancestorLen)
                for (trail in 1..minOf(3, ancestorParts.size)) {
                    val dirKey = ancestorParts.takeLast(trail).joinToString("/")
                    dirCounts.merge(dirKey, 1) { a, b -> a + b }
                }
            }
        }

        return Index(fileMap, dirCounts)
    }

    private fun maxSeverity(a: Severity, b: Severity): Severity =
        if (a.ordinal <= b.ordinal) a else b  // HIGH=0 is the most severe

    private fun normalize(path: String): String = path.replace('\\', '/').lowercase()

    companion object {
        fun getInstance(project: Project): RiskFilesIndex = project.service()
    }
}
