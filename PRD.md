# PRD — Datadog Proactive Observability + PR Risk Plugin for JetBrains IDE

**48-hour ship (JetBrains x OpenAI Hackathon, April 18–19, 2026)** · **Author:** Steven · **Version:** 1.1

> Companion docs: [`HACKATHON.md`](HACKATHON.md) (event, tracks, judging) · [`DATASET.md`](DATASET.md) (fixtures) · [`CLAUDE.md`](CLAUDE.md) (dev playbook)

---

## Core user story

As a developer editing `PaymentService.charge()`, I instantly see live Datadog risk context *inside the editor* (gutter + tooltip) **while I type**, and I get a one-click **PR blast-radius risk score** before I push — turning Datadog's reactive Bits AI into a prevention-first, editor-native SRE co-pilot.

## The two defensible wedges

1. **Proactive editor-native context** ("invert the trigger"): cursor enters a method → plugin surfaces open incidents, error-rate delta since last commit, today's deploy window. Datadog hasn't shipped this because their surface is dashboards/Slack — not the cursor.
2. **PR-time blast-radius risk prediction** (hybrid reasoning): read diff → score it via deterministic heuristic → Codex agent mode reasons causally over historical incidents → return final score + cited justification. Prevention, not post-incident triage.

This is **not** "just a plugin" — it owns the active-cursor asymmetry Datadog hasn't defended.

## Success criteria

- 100% native inside IntelliJ (no external frontend)
- Demo runs on plugin sandbox with `DataDog/tsre-microservices` loaded
- <350 new lines of Kotlin total (fork the Datadog plugin, don't rewrite)
- `MockDatadogService` default; `RealDatadogService` behind a one-line flag toggle
- Live 3-minute on-stage demo rehearsed; 60-sec Loom as backup submission
- **H12 playable-loop gate** — gutter icon visible on a tsre method on demo laptop. If missed, cut Wedge 2
- **H36 feature-freeze gate** — both wedges E2E working; remaining 12h is polish + pitch

---

## Wedge 1 — Proactive Editor Context

- On file open or cursor entering a method in `methods_of_interest`, background thread queries `DatadogService.getMethodContext(fqName)` (never blocks EDT)
- Gutter icon renders via `LineMarkerProvider` extending the upstream Datadog gutter system
- Tooltip:
  ```
  ⚠️ This function appears in 2 open incidents
  • Error rate +4× since last commit
  • Scheduled in today's deploy window
  [Open in Datadog] [Ask Codex about this incident]
  ```
- Appears within 2s of cursor landing

## Wedge 2 — PR Blast-Radius Risk Prediction (hybrid reasoning)

**The "novel use of Codex" beat.** Two cooperating layers, kept separate in code.

### Layer 1 — deterministic baseline (Kotlin, never fails)

`RiskHeuristic.computeBaseline(services)` returns `{score: Int, factors: Map<String, Double>}` via a reproducible weighted formula: `error_rate × 30 + deploy_window_today × 20 + direct_dep_count × 5 + change_velocity_7d × 10`, clamped 0–100. Unit-testable with fixed fixture inputs.

### Layer 2 — Codex semantic override (OpenAI Responses API, agent mode)

Codex receives the baseline score + factors, the raw diff, and the full historical incident set (descriptions, root causes, offending code snippets, keywords). Its job: determine whether the diff *re-introduces* a past failure pattern or *addresses* an open one, and adjust the score **±25 points** with a written justification citing specific incident IDs.

**Registered tools:**
- `get_diff()` → current Git diff text
- `get_datadog_context(service)` → error rates, incidents, deploy window
- `get_related_incidents(methods[], keywords[])` → historical incidents matching the diff
- `compute_baseline_score(services[])` → returns the Layer 1 output
- `find_incidents_by_code_pattern(pattern)` → Kotlin scans `incidents[*].offending_code_snippet` for a substring match. Returns `[{incident_id, title, offending_code_snippet, matched_fragment}]`. This is the novel beat: the agent pattern-matches the PR diff against the *source code of past bugs*, not just service names or keywords.
- `finalize_risk(final_score, baseline_delta, reasoning, citations)` → Codex's final answer

**Agent loop:** iterate `response.tool_calls` → dispatch to `MockDatadogService` → feed result back → repeat until `finalize_risk` called.

**Output shape rendered in tool window:**
```json
{
  "final_score": 85,
  "baseline_score": 60,
  "codex_delta": 25,
  "affected_services": ["payment-service", "order-service"],
  "recommendation": "Hold this PR — reintroduces INC-4402 null-check bug in PaymentService:142.",
  "past_incident_matches": [
    {
      "incident_id": "INC-4402",
      "matched_fragment": "country = customer.billingAddress.country",
      "offending_code_snippet": "if (retryCount > 0) {\n  country = customer.billingAddress.country; // NPE\n}"
    }
  ],
  "reasoning_trace": [
    { "step": 1, "action": "get_diff",                        "summary": "12 lines changed in PaymentService.java" },
    { "step": 2, "action": "compute_baseline_score",          "result":  "60 (high error rate + deploy window)" },
    { "step": 3, "action": "get_related_incidents",           "result":  "Matched INC-4402 keywords: null, billing, country" },
    { "step": 4, "action": "find_incidents_by_code_pattern",  "result":  "Pattern 'customer.billingAddress.country' matched INC-4402 offending_code_snippet" },
    { "step": 5, "action": "codex_reasoning",                 "text":    "Diff removes null-check at line 142 — exact root cause of INC-4402 (resolved 2026-03-15). Same fragment as offending commit a3f4c2b. Score 60 → 85." }
  ]
}
```

The reasoning trace IS the demo moment — proves Codex reasons causally, not formats.

### UI — risk card layout

Risk card rendered in the `Datadog Risk` tool window when the user hits "Analyze PR Risk":

1. **Header chip** — baseline score → final score (e.g. `60 → 85`), colored by risk band (green/amber/red).
2. **Affected services** — chip list.
3. **Recommendation** — one-sentence SRE-voice verdict.
4. **Past incident match** *(only shown when `past_incident_matches` is non-empty)* — **visual side-by-side**: the matched diff fragment on the left, the historical incident's `offending_code_snippet` on the right, with the incident ID + title above. Same monospace font, same width, so the judge sees "two code blocks, same bug" at a glance. This is the visceral demo moment.
5. **Reasoning trace** — expandable, numbered tool calls in order.

### Bonus inline action (H24+ if time allows)

Right-click a method in the gutter → "Ask Codex about this incident" → spawns an agent session pre-loaded with the method source + related incident history.

## Codex invocation path + auth

- **Path:** OpenAI Responses API direct (BYOK). Clean agent-mode support; no JetBrains SDK discovery risk.
- **Key storage:** JetBrains `PasswordSafe` (OS-level secure credential store). Never plaintext. Never logged.
- **Env fallback:** `OPENAI_API_KEY` read at init if PasswordSafe empty. Dev `.env` gitignored.
- **Settings panel** (`Settings → Tools → Datadog Proactive`):
  - `OpenAI API Key` (masked, PasswordSafe-backed)
  - `Model` (default `gpt-5-codex`; configurable)
  - `Base URL` (default `https://api.openai.com/v1`)
  - `Use Real Datadog` (checkbox; default off — this is the on-stage flip)
- **Fallback ladder** on API failure: (1) retry w/ 2s backoff, (2) pre-cached canned trace for `PaymentService.charge`, (3) "using cached analysis" banner. Never leave the judge staring at a spinner.

## Technical approach (fork + mock + ship fast)

- **Base:** Fork https://github.com/DataDog/datadog-for-intellij-platform (Apache-2.0)
- **Language:** Kotlin + IntelliJ Platform Gradle Plugin 2.x
- **Data layer** (Ben Shih pattern — static-first, real-second):
  - `DatadogService` interface
  - `MockDatadogService` — loads `src/main/resources/fixtures/datadog-fixtures.json` (see [`DATASET.md`](DATASET.md))
  - `RealDatadogService` — upstream DatadogApiClient; one-line flip
- **Demo codebase:** Clone `DataDog/tsre-microservices` into the sandbox IDE at boot — real 12-microservice app, function names match our fixture method names

## Out of scope (cut for 48h)

- Real Datadog API auth flow (mock is the demo path)
- Auto-PR GitHub comments
- Multi-language beyond Java/Kotlin in the demo repo
- Config UI beyond one settings panel
- Any web frontend, companion site, or external dashboard

---

## Live demo script (3-minute on-stage pitch)

**0:00–0:20 — Problem.** "Datadog tells you when you're on fire. It can't tell you *before* you light the match." One line, one slide.

**0:20–1:20 — Wedge 1 live.** Sandbox IntelliJ with tsre-microservices loaded. Click into `PaymentService.charge()`. Gutter icon appears <2s. Hover → tooltip: "2 open incidents • error rate +4× since last commit • in today's deploy window." *"I didn't search for this. I didn't open a dashboard. It found me."*

**1:20–2:20 — Wedge 2 live.** Click "Analyze PR Risk." Risk card slides in: baseline 60 → Codex 85, three affected services, recommendation. Tool window expands the reasoning trace — `get_diff` → `compute_baseline_score(60)` → `get_related_incidents(...)` → `find_incidents_by_code_pattern("customer.billingAddress.country")` → `codex_reasoning: "reintroduces INC-4402 null-check at line 142."`

Then point at the **side-by-side code match** block that just rendered in the risk card — the diff fragment on the left, INC-4402's offending snippet on the right, same shape. Say it in developer-language:

> *"Codex isn't just checking service names — it just grep'd every past incident's source code and found a match. That snippet you just added? It's the same code that broke production on March 15th. INC-4402. Same null-check missing. Same file. Your reviewer might catch this. Your memory won't. This does."*

*"Codex isn't writing a paragraph — it's investigating like an SRE. Baseline number, causal override, pattern-match against past bugs, eight seconds. Before I push."*

**2:20–2:45 — "It's real" moment.** Flip `USE_REAL_DATADOG` in settings. Re-run Wedge 2. Panel shows the real Datadog API path wiring up (may error on auth — fine; the wiring is there). *"Mock off, real API on. Same code path. Ship-ready."*

**2:45–3:00 — Close.** "Prevention-first observability. Lives in the cursor, not the dashboard. The IDE reimagined as an SRE co-pilot."

**60-second Loom backup:** Wedges 1 + 2 only, no real-flag moment.

## Repo & submission

- Public GitHub repo on branch `hackathon-proactive`; Apache-2.0 preserved; `NOTICE` credits upstream Datadog plugin
- Contains: `PRD.md`, `HACKATHON.md`, `DATASET.md`, `CLAUDE.md`, `scripts/generate-fixtures.py`, `src/main/resources/fixtures/datadog-fixtures.json`, plugin source, `README.md`
- `README.md` sections: 30-sec run instructions, "How Codex is used," wedge screenshots, Loom link
- Tags at top of README: `JETBRAINS`, `OPENAI`, `CODEX`, `DATADOG`, `SRE`
- Packaged plugin `.zip` via `./gradlew buildPlugin` tested on a clean IntelliJ install (not just dev)
- Submission: GitHub link + Loom URL + live on-stage demo

---

This PRD is the single source of truth for *what*. `CLAUDE.md` is the source of truth for *how*. Build exactly what is written here — no extra features, no LLM fluff, no new infrastructure. Ship the moment the core loop is playable.

**Ready to build.**
