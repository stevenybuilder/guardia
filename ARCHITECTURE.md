# ARCHITECTURE.md — System Design

How Guardia is wired. For product scope see [`README.md`](README.md); data contract in [`DATASET.md`](DATASET.md).

---

## 1. System topology

```
┌──────────────────────────── IntelliJ JVM (plugin sandbox) ─────────────────────────────┐
│                                                                                         │
│  ┌───────────── EDITOR SURFACE ─────────────┐       ┌─────── RIGHT TOOL WINDOW ──────┐  │
│  │ DatadogLineMarkerProvider  (Wedge 1)     │       │ RiskCardPanel      (Wedge 2)   │  │
│  │  ├─ gutter ⚠ icon / "i" / none           │       │  ├─ Score dial (animated)      │  │
│  │  ├─ HintManager tooltip (HTML)           │       │  ├─ Affected services list     │  │
│  │  ├─ RangeHighlighter flash (during       │◀──────┤  ├─ Recommendation sentence    │  │
│  │  │   Codex reasoning)                    │       │  ├─ Reasoning pipeline chips   │  │
│  │  └─ Editor notification banner           │       │  └─ Fallback banner (cache)    │  │
│  └──────────────────────▲────────────────────┘      └──────────────▲──────────────────┘  │
│                         │                                          │                     │
│              LineMarker lookup                          Toolbar action: "Analyze PR Risk"│
│                         │                                          │                     │
│  ┌──────────────────────┴──────────────────────────────────────────┴─────────────────┐   │
│  │                             RiskAnalysisCoordinator                                │  │
│  │   - Orchestrates the agent loop, publishes streaming UI events to EDT              │  │
│  │   - Owns the fallback ladder (retry → pre-cached trace → banner)                   │  │
│  └───────────┬─────────────────┬──────────────────────┬────────────────────┬─────────┘   │
│              │                 │                      │                    │             │
│      ┌───────▼───────┐  ┌──────▼──────┐  ┌────────────▼────────┐  ┌────────▼────────┐    │
│      │ RiskHeuristic │  │ DiffProvider│  │ IncidentRetriever   │  │ OpenAIResponses │    │
│      │ (Layer 1)     │  │ (JB Git API)│  │ (structural+BM25)   │  │ Client (Layer 2)│    │
│      └───────┬───────┘  └──────┬──────┘  └──────────┬──────────┘  └────────┬────────┘    │
│              │                 │                    │                      │             │
│      ┌───────▼─────────────────▼────────────────────▼──────────┐  ┌────────▼────────┐    │
│      │              DatadogService (interface)                  │  │ PasswordSafe +  │    │
│      │   impls: MockDatadogService | RealDatadogService         │  │ OPENAI_API_KEY  │    │
│      └──────────────────────────────────────────────────────────┘  └─────────────────┘    │
│                                                                                            │
└────────────────────────────────────────────────────────────────────────────────────────────┘
                                            │
                                            ▼
                                 OpenAI Responses API  +  Datadog Incidents/Events API
```

**Key invariants:**
- All outbound network calls are behind an interface (`DatadogService`, `OpenAIResponsesClient`). Backends swap per-call with no `if (useReal)` branches anywhere else.
- The editor surface is EDT-only. Everything off `RiskAnalysisCoordinator` runs on a background executor and marshals UI mutations via `ApplicationManager.invokeLater`.
- Fixture is loaded once at plugin init and held immutable per session in `FixtureStore`.

---

## 2. Reasoning layer

Two-layer hybrid. Layer 1 floors the score. Layer 2 contributes causal reasoning a heuristic can't. The delta between them is the creativity beat.

### 2.1 Layer 1 — deterministic baseline

Pure Kotlin, <5 ms over 12 services × multi-thousand-incident corpus, clamped 0–100:

```kotlin
fun computeBaseline(
  diff: Diff,
  services: List<ServiceContext>,
  incidents: List<Incident>
): BaselineScore {
  val errorRate    = services.maxOf { it.errorRateDeltaVsLastCommit } * 8.0   // max ~40
  val deployWindow = if (services.any { it.deployWindowToday }) 15.0 else 0.0
  val blastRadius  = min(services.sumOf { it.directDepCount }, 20) * 1.0      // max 20
  val velocity     = min(services.maxOf { it.changeVelocity7d }, 15) * 0.7    // max ~10
  val churn        = min(diff.linesChanged / 50.0, 1.0) * 8.0                 // max 8
  val recency      = incidentRecencyScore(diff.touchedFiles, incidents) * 15  // max 15
  //  open SEV-1/2 on touched file = 1.0 │ resolved <30d = 0.6 │ <90d = 0.3 │ else 0
  val raw = errorRate + deployWindow + blastRadius + velocity + churn + recency
  return BaselineScore(
    score   = raw.coerceIn(0.0, 100.0).toInt(),
    factors = mapOf(
      "error_rate_delta" to errorRate,
      "deploy_window"    to deployWindow,
      "blast_radius"     to blastRadius,
      "velocity"         to velocity,
      "churn"            to churn,
      "incident_recency" to recency))
}
```

The `factors` map is passed into Codex's prompt so Layer 2 can cite which signal drove the floor. Weights follow industry convergence (Google SRE, Harness, DX, DRS-OSS) — `error_rate_delta` and `incident_recency` are the two most load-bearing signals and carry the highest weight.

### 2.2 Layer 2 — Codex agent-mode override

OpenAI Responses API, agent mode, 6 registered tools, bounded ±25 delta, must cite at least one incident ID if non-zero.

**Tool registry:**

| Tool | Params | Returns |
|---|---|---|
| `get_diff` | `{}` | `{files, hunks, linesChanged}` via Git4Idea |
| `get_datadog_context` | `{service: string}` | `ServiceContext` |
| `get_related_incidents` | `{methods, keywords}` | `[Incident]` (top 5, hybrid retrieval — §3) |
| `compute_baseline_score` | `{services: [str]}` | `BaselineScore` |
| `find_incidents_by_code_pattern` | `{pattern: string}` | `[CodePatternMatch]` — substring scan over past offending snippets |
| `finalize_risk` | `{final_score, baseline_delta, reasoning, citations}` | terminator |

**Agent loop contract:**

1. Agent MUST call `get_diff` + `compute_baseline_score` first.
2. Agent MUST call `get_related_incidents` before `finalize_risk`.
3. `finalize_risk` is the only terminator. Score must be `baseline ± codex_delta` where `|codex_delta| ≤ 25`. Out-of-bounds deltas are clamped and annotated.
4. Hallucinated incident IDs are rejected; the agent is re-prompted once.
5. Max 6 tool calls total. Hard stop at 8 s wall-clock — on timeout, fall through to cached trace.

**Structured output shape** (parsed back in Kotlin):

```json
{
  "baseline_score": 60,
  "codex_delta": 25,
  "final_score": 85,
  "affected_services": ["payment-service", "order-service"],
  "recommendation": "Hold this PR — reintroduces INC-4402 null-check at PaymentService:142.",
  "reasoning_trace": [ { "step": 1, "tool": "get_diff", ... }, ... ],
  "citations": ["INC-4402"]
}
```

### 2.3 Model selection

**Default:** `gpt-5-codex` via `POST /v1/responses`, agent-mode tool calling. Purpose-built for agentic code reasoning — the exact workload here (diff + tool calls → bounded score + citations). The `find_incidents_by_code_pattern` tool benefits specifically from Codex's code-pattern strength. Typical loop: 3–8 s across 5–7 tool calls.

**Alternative (exposed in Settings, not default):** `gpt-5-mini` — ~10× cheaper, recommended for team-scale deployment. Same API surface; no IDE restart required to swap.

Key storage: JetBrains `PasswordSafe` with `OPENAI_API_KEY` env fallback.

---

## 3. Historical-data retrieval

`get_related_incidents` is the single most load-bearing piece of the reasoning layer: if retrieval misses the right incident on the diff, Codex can't cite it and the override evaporates. At the current corpus scale, embeddings + a vector DB add latency and failure modes without improving recall — an in-process structural + lexical hybrid is strictly better.

### 3.1 Retrieval pipeline

```kotlin
fun getRelatedIncidents(
  methods: List<String>,
  keywords: List<String>,
  touchedFiles: List<String>
): List<Incident> {
  val all = fixtureStore.incidents

  // Stage 1 — STRUCTURAL MATCH (highest-precision signal; Getafix-style)
  val structural = all.filter { inc ->
    inc.offendingMethods.any { it in methods } ||
    inc.offendingFiles.any   { f -> touchedFiles.any { t -> f.endsWith(t) || t.endsWith(f) } }
  }

  // Stage 2 — BM25 OVER DESCRIPTION + ROOT_CAUSE + KEYWORDS
  val corpus = all.map { it.id to "${it.description} ${it.rootCause.orEmpty()} ${it.keywords.joinToString(" ")}" }
  val bm25Ranked = BM25(corpus).topK((keywords + methods).joinToString(" "), k = 5)

  // Stage 3 — RECIPROCAL RANK FUSION (structural outranks lexical)
  return rrf(structural, bm25Ranked, k = 60).take(5)
}
```

- **Structural match dominates.** Meta's Getafix work (2018) showed AST-context matching alone fixed 53% of null-call bugs with no LLM. Our analogue is `offending_methods` + `offending_files`: if the diff touches `PaymentService.charge`, any incident referencing it is top-ranked regardless of keyword overlap.
- **BM25** fuses the lexical tail sub-millisecond on this corpus size.
- **Reciprocal Rank Fusion** combines the two without tuning weights; structural hits bubble to the top.
- **Scale path:** when the corpus exceeds ~500 incidents against real Datadog, swap the in-memory BM25 for a disk-backed Lucene index. The `IncidentRetriever` interface stays identical.

### 3.2 Real-data migration contract

Three independent interfaces keep the swap clean: `DatadogService`, `IncidentRetriever`, `ServiceGraphProvider`. Each substitutes at the real-data boundary without touching the reasoning layer.

- `Incident` DTO aligns with the Datadog Incidents API v2 shape (`id`, `attributes.title`, `attributes.fields.root_cause`, `relationships.commits`).
- `offending_code_snippet` is fixture-only; in real mode, derive at query time from `relationships.commits[*]` via Git4Idea.
- `keywords` aliases Datadog `tags` via `incident.searchableTerms()`; no caller reads `.keywords` directly.
- Service dep graph comes from Datadog APM Service Map behind `ServiceGraphProvider` (5-min TTL cache).
- Rate-limit + retry live inside `RealDatadogService`, not in retrieval.

---

## 4. Streaming event contract (coordinator ↔ UI)

`RiskAnalysisCoordinator` exposes a `Flow<RiskEvent>` the UI subscribes to on EDT:

```kotlin
sealed class RiskEvent {
  data class ToolCallStarted(val step: Int, val tool: String) : RiskEvent()
  data class ToolCallCompleted(val step: Int, val tool: String,
                               val summary: String, val elapsedMs: Long) : RiskEvent()
  data class BaselineComputed(val score: Int, val factors: Map<String, Double>) : RiskEvent()
  data class IncidentsRetrieved(val incidents: List<Incident>) : RiskEvent()
  data class Finalized(val verdict: RiskVerdict) : RiskEvent()
  data class FallbackEngaged(val reason: String) : RiskEvent()
}
```

Chip timeline subscribes to `ToolCall*`. Score dial to `BaselineComputed` + `Finalized`. Editor highlight to `IncidentsRetrieved`. Fallback banner to `FallbackEngaged`. UI surfaces are decoupled from the agent loop — the flow is the only contract.

---

## 5. Failure modes + fallback ladder

The 8-second stage budget is the credibility deadline.

| Failure | Where | Response |
|---|---|---|
| OpenAI API error (4xx / 5xx) | `OpenAIResponsesClient` | 1 retry with 2 s backoff → pre-cached trace → "using cached analysis" banner |
| OpenAI timeout (>8 s) | `RiskAnalysisCoordinator` | Kill loop, emit `FallbackEngaged`, serve cached trace |
| `finalize_risk` clamp hit (|delta| > 25) | Coordinator | Clamp to ±25, annotate trace |
| `finalize_risk` before `get_related_incidents` | Coordinator | Reject, re-prompt with a nudge |
| Hallucinated incident ID | Coordinator | Strip from citations, refuse to finalize, re-prompt once |
| Fixture missing | Plugin init | Fail-fast balloon; tool window not registered |
| No diff (clean repo) | `DiffProvider` | Tool window shows "no changes to analyze" — no agent call |
| Real Datadog unreachable | `RealDatadogService` | Surface error; does NOT silently fall through to Mock |

Pre-cached traces live at `src/main/resources/fallback/*.json`. One captured successful run per demo scenario, version-controlled. Regeneration is a deliberate human action.

---

## 6. Security

- **Credentials** — OpenAI, Datadog, and Supabase keys stored in JetBrains `PasswordSafe` (OS keychain). Never written to disk in cleartext, never logged. `.env` is dev-only and gitignored.
- **Env fallback** — `OPENAI_API_KEY` / `DD_API_KEY` read once at plugin init if `PasswordSafe` is empty; not cached past request boundaries.
- **Log hygiene** — `OpenAIResponsesClient` redacts the `Authorization` header before any log call.
- **Tool sandboxing** — tools only return data from `MockDatadogService` or `RealDatadogService`. No shell-out, no filesystem writes from tool dispatch. `get_diff` is read-only via Git4Idea.
- **Prompt-injection surface** — in real Datadog mode, incident `description` fields are untrusted input and are enclosed in explicit delimiters in the prompt.
- **Supabase** — anon key + Row-Level Security insert policy. No service-role key in the client.

---

## Research sources

**Retrieval + deployment-risk literature:**
- [Getafix: how Facebook tools learn to fix bugs automatically](https://engineering.fb.com/2018/11/06/developer-tools/getafix-how-facebook-tools-learn-to-fix-bugs-automatically/) — AST-structural match dominates lexical.
- [DRS-OSS: LLM-Driven Diff Risk Scoring (arXiv 2511.21964)](https://arxiv.org/html/2511.21964v1) — LLMs add value on top of change metrics, not instead of.
- [Google SRE Workbook — Canarying Releases](https://sre.google/workbook/canarying-releases/) — error-rate delta is the load-bearing signal.
- [Harness — ML to safeguard deployments](https://www.harness.io/blog/machine-learning-to-safeguard-your-deployments), [DX — Change Failure Rate](https://getdx.com/blog/change-failure-rate/) — churn, author familiarity, incident recency as standard features.
- [Hybrid Search (LanceDB)](https://www.lancedb.com/blog/hybrid-search-combining-bm25-and-semantic-search-for-better-results-with-lan-1358038fe7e6) — RRF fusion pattern.

**Model selection:**
- [Models — Codex | OpenAI Developers](https://developers.openai.com/codex/models)

**IntelliJ Platform APIs:**
- [Inlay Hints](https://plugins.jetbrains.com/docs/intellij/inlay-hints.html)
- [Controlling Highlighting](https://plugins.jetbrains.com/docs/intellij/controlling-highlighting.html)
