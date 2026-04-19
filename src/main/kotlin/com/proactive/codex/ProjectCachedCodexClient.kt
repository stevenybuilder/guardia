package com.proactive.codex

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.proactive.risk.RiskEvent
import com.stevenyang.datadogproactive.settings.DatadogProactiveSettings
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.flow.Flow

/**
 * Project-scoped adapter that resolves all per-project dependencies (OpenAI key, diff
 * provider, ToolDispatcher) and delegates to an underlying [CachedCodexClient].
 *
 * Registered in plugin.xml as a `@Service(PROJECT)` bound to [CodexAgentLoop], so the
 * existing `DefaultRiskAnalysisCoordinator.resolveCodex()` picks it up automatically
 * via `project.getService(CodexAgentLoop::class.java)`. When unreachable (tests), the
 * coordinator still falls back to its scripted [FakeCodexAgentLoop].
 */
@Service(Service.Level.PROJECT)
class ProjectCachedCodexClient(private val project: Project) : CodexAgentLoop {

    private val log = logger<ProjectCachedCodexClient>()

    private val settings: DatadogProactiveSettings?
        get() = try {
            ApplicationManager.getApplication().getService(DatadogProactiveSettings::class.java)
        } catch (_: Throwable) {
            null
        }

    override fun run(input: CodexInput): Flow<RiskEvent> {
        val client = CachedCodexClient(
            live = LiveFactoryImpl(),
            offlineMode = { settings?.offlineMode == true },
            dispatcherFor = { inp -> ToolDispatcher(project, ::currentDiffText, inp.baseline) },
        )
        return client.run(input)
    }

    /**
     * Returns the current git diff text for the project's VCS roots, or "(no changes)" when
     * the repo is clean / unresolvable. Shells out to `git diff HEAD` for the authoritative
     * unified-diff format — Codex's agent loop depends on seeing actual `-`/`+` lines, not
     * char-count metadata. Falls back to ChangeListManager's before/after content (as a
     * synthetic set-difference diff) if the `git` binary is unavailable.
     */
    private fun currentDiffText(): String {
        // Primary path: git CLI. Iterate candidate roots (each change-list dirty root +
        // the project base path) until one returns non-empty diff output.
        try {
            val roots = candidateRepoRoots()
            for (root in roots) {
                val gitOut = runGit(listOf("git", "diff", "HEAD", "--unified=3"), root)
                if (gitOut.isNotBlank()) return gitOut.take(24_000)
            }
        } catch (t: Throwable) {
            log.debug("git CLI diff extraction failed: ${t.message}")
        }
        // Fallback: ChangeListManager before/after synthetic diff (set-difference lines).
        return try {
            val clm = ChangeListManager.getInstance(project)
            val changes = clm.allChanges.toList()
            if (changes.isEmpty()) return "(no changes)"
            buildString {
                for (change in changes.take(50)) {
                    val before = runCatching { change.beforeRevision?.content }.getOrNull()
                    val after = runCatching { change.afterRevision?.content }.getOrNull()
                    val path = change.virtualFile?.path
                        ?: change.afterRevision?.file?.path
                        ?: change.beforeRevision?.file?.path
                        ?: "<unknown>"
                    if (before != null && after != null) {
                        appendLine("--- a/$path")
                        appendLine("+++ b/$path")
                        val afterSet = after.lines().toHashSet()
                        val beforeSet = before.lines().toHashSet()
                        for (l in before.lines()) if (l !in afterSet) appendLine("-$l")
                        for (l in after.lines()) if (l !in beforeSet) appendLine("+$l")
                    } else if (after != null) {
                        appendLine("--- /dev/null")
                        appendLine("+++ b/$path")
                        for (l in after.lines().take(800)) appendLine("+$l")
                    } else if (before != null) {
                        appendLine("--- a/$path")
                        appendLine("+++ /dev/null")
                        for (l in before.lines().take(800)) appendLine("-$l")
                    }
                }
            }.ifBlank { "(no changes)" }
        } catch (t: Throwable) {
            log.debug("ChangeListManager fallback failed: ${t.message}")
            "(no changes)"
        }
    }

    private fun candidateRepoRoots(): List<java.io.File> {
        val roots = linkedSetOf<java.io.File>()
        runCatching {
            val clm = ChangeListManager.getInstance(project)
            for (change in clm.allChanges) {
                val path = change.virtualFile?.path
                    ?: change.afterRevision?.file?.path
                    ?: change.beforeRevision?.file?.path
                if (path != null) {
                    var f: java.io.File? = java.io.File(path).parentFile
                    while (f != null) {
                        if (java.io.File(f, ".git").exists()) { roots.add(f); break }
                        f = f.parentFile
                    }
                }
            }
        }
        project.basePath?.let { roots.add(java.io.File(it)) }
        // Also include the well-known demo-repo path as a safety net for the hackathon demo.
        project.basePath?.let { roots.add(java.io.File(it, "demo-repos/tsre-microservices")) }
        return roots.toList()
    }

    private fun runGit(cmd: List<String>, dir: java.io.File): String {
        if (!dir.exists() || !java.io.File(dir, ".git").exists()) return ""
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return if (proc.exitValue() == 0) out else ""
    }

    /** Builds a live [OpenAIResponsesClient] when key + model are configured; null otherwise. */
    private inner class LiveFactoryImpl : CachedCodexClient.LiveFactory {
        override fun build(): OpenAIResponsesClient? {
            val s = settings ?: run {
                log.warn("DatadogProactiveSettings service unavailable; cannot build live Codex client")
                return null
            }
            val key = s.getApiKey()?.takeIf { it.isNotBlank() } ?: run {
                log.warn("OPENAI_API_KEY not set (PasswordSafe empty + env var missing); falling back to cached analysis")
                return null
            }
            val model = s.model.ifBlank { "gpt-5-codex" }
            val baseUrl = s.baseUrl.ifBlank { "https://api.openai.com/v1" }
            log.info("Codex live client configured: model=$model baseUrl=$baseUrl keyPrefix=${key.take(7)}...")
            return OpenAIResponsesClient(apiKey = key, baseUrl = baseUrl, model = model)
        }
    }
}
