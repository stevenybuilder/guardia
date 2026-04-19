# CLAUDE.md — Dev Playbook

This file is **the playbook**, not the spec. Every agent reads it on startup.

| Document | Owns |
|---|---|
| `PRD.md` | Product spec — wedges, reasoning layer, pitch script, demo flow |
| `HACKATHON.md` | Event context — track, criteria, submission, judging |
| `DATASET.md` | Fixture schema + generation + what each layer reads |
| `CLAUDE.md` (this) | Dev rules, phased plan, AI role split, test wiring |
| `src/main/resources/fixtures/datadog-fixtures.json` | Frozen data contract |

If a question is answered elsewhere, go there. Don't duplicate.

---

## Core principle

**Write the spec. Freeze the fixtures. Build the loop. Polish only what the judge will see.** Everything we do is downstream of PRD.md and `datadog-fixtures.json`. Ben Shih's PokeLenny rule: the PRD determines 80% of build smoothness — nailing it up front is the single highest-leverage move.

## Rules (non-negotiable)

1. **Spec first.** Not in `PRD.md`? Don't build it. Propose a PRD edit first.
2. **Fixtures are frozen.** `datadog-fixtures.json` shape changes only via `scripts/generate-fixtures.py` regeneration + PRD amendment.
3. **Phase gates are hard.** Miss H12 → cut Wedge 2. Miss H36 → feature freeze, ship what works.
4. **Demo path is mock-only.** `MockDatadogService` is default in every run. Real Datadog is a 25-second on-stage toggle, nothing more.
5. **Fix before feature.** Red tests block new work.
6. **Atomic commits.** One task per commit. Tag with phase: `feat(p2): gutter icon renders on methods_of_interest`.
7. **Decision Log is append-only.** Wrong decisions get a reversal entry, not an edit.

---

## AI role division (Ben's rule — don't thrash tools)

| Role | Agent | Worktree | Notes |
|---|---|---|---|
| **Orchestrator** | Claude Code (main session) | No | Owns PRD, dispatches work, makes gate decisions, keeps Decision Log |
| **Plugin-Core** | Claude Code sub-agent | Yes | Kotlin — GutterIconProvider, tool window, actions, settings panel |
| **Codex-Agent** | Claude Code sub-agent | Yes | OpenAI Responses API wrapper, tool registry, reasoning trace rendering |
| **Fixtures** | Claude Code sub-agent | No | `scripts/generate-fixtures.py` edits + regeneration |
| **Testing/QA** | Claude Code sub-agent | Yes | `./gradlew test` + manual UAT checklists + pre-demo smoke |
| **Pitch/Demo** | Human (Steven) | No | Script rehearsal, Loom, README screenshots |
| **Researcher** | Claude Code sub-agent | No | IntelliJ Platform APIs, Responses API schema, fork quirks |

**Executor tools** (when writing real code): Codex CLI for bulk execution; Claude Code for architect/review; Cursor only for utility scripts. Decide once, don't switch mid-build.

---

## Phased dev plan (modeled on Ben Shih's PokeLenny process)

Ben's process: **PRD → fork/POC → static data → playable loop → self-test ("feels like play or work?") → content via scripts → polish → ship.** Compressed to 48h:

### Phase 0 — Hour-0 sanity (H0 → H2)

**Goal:** stack works end-to-end before building anything. Eliminates 80% of "why is nothing working" rabbit holes (ref Stanford hackathon LEARNINGS).

**Work:**
- Fork `DataDog/datadog-for-intellij-platform`; `./gradlew runIde` launches sandbox with tsre-microservices loaded
- OpenAI API key: store in PasswordSafe via settings panel + verify `OPENAI_API_KEY` env fallback path
- Throwaway Responses API call with one tool — confirm tool_call round-trip on demo laptop
- `MockDatadogService` loads `datadog-fixtures.json` at init. **Sanity check:** run `./gradlew test --tests FixtureLoaderTest` — asserts `services=12, incidents=20, methods=15, events=2200`.
- `GitRepositoryManager.getInstance(project).repositories.first()` returns something on the tsre repo
- Stop hook in `.claude/settings.json` runs `./gradlew test` after every agent turn

**Verify:** All 5 above are green.
**Exit gate:** 30-minute timebox. If not green, triage ruthlessly — nothing else gets built until this is done.

### Phase 1 — Spec freeze + skeleton (H2 → H8)

**Goal:** PRD locked, skeleton compiles, agents can work in parallel from here on.

**Work:**
- `DatadogService` interface + `MockDatadogService` impl (reads fixtures, answers `getMethodContext`, `getServiceContext`, `isInDeployWindow`)
- `DatadogServiceProvider` reads `USE_REAL_DATADOG` setting, returns Mock or Real impl (Real impl is a TODO-stub)
- Register `"Datadog Risk"` tool window (right side, empty panel)
- Register `"Analyze PR Risk"` toolbar action (no-op stub)
- 3 smoke tests: fixture loads, interface contract holds, action resolves

**Verify:** `./gradlew test` green; sandbox IDE shows tool window icon; action appears in toolbar.
**Commit:** `feat(p1): skeleton compiles, fixtures load, tool window registered`.

### Phase 2 — Wedge 1 playable (H8 → H16)

**Goal:** live gutter + tooltip on real tsre-microservices code. This is the creativity beat.

**Work:**
- `DatadogGutterIconProvider` implementing `LineMarkerProvider` — reads `methods_of_interest`, renders ⚠️ on matched methods
- Tooltip with incident count + error delta + deploy window banner
- Background thread for Datadog lookups (never block EDT)
- Start with ONE hardcoded method (`PaymentService.charge`), then generalize to all 15 in fixtures

**Ben-style self-test:** open 3 files cold in the sandbox. Does the gutter *find* you, or do you have to hunt for it? If you hunt, the icon is too subtle.

**H12 gate (HARD):** if gutter + tooltip doesn't work on tsre-microservices on the demo laptop by H12 → STOP. Cut Wedge 2 scope. Make Wedge 1 unbreakable. The gutter moment alone is a demo.
**Exit:** icon appears <2s after cursor lands; tooltip has real data; feels "alive."

### Phase 3 — Wedge 2 playable (H16 → H28)

**Goal:** PR risk card with hybrid reasoning working end-to-end.

**Work (Layer 1 — deterministic):**
- `RiskHeuristic.computeBaseline(services)` Kotlin function — weighted formula (see PRD §Wedge 2)
- Unit tests with fixed fixture inputs → fixed outputs

**Work (Layer 2 — Codex agent mode):**
- `OpenAIResponsesClient` — authenticated from PasswordSafe/env, retries + timeout
- Register 6 tools: `get_diff`, `get_datadog_context`, `get_related_incidents`, `compute_baseline_score`, `find_incidents_by_code_pattern`, `finalize_risk`
- Agent loop: iterate `response.tool_calls`, dispatch to `MockDatadogService`, feed result back, repeat until `finalize_risk` is called
- Parse structured verdict: `{baseline_score, codex_delta, final_score, affected_services, recommendation, reasoning_trace}`

**Work (UI):**
- Risk card in tool window: score chip, services list, one-sentence recommendation, expandable reasoning trace (numbered tool calls)
- **Side-by-side code match UI** when `past_incident_matches` is non-empty — matched diff fragment on the left, historical `offending_code_snippet` on the right, incident ID + title above. This is the visceral "same code, same bug" demo moment (see PRD §Wedge 2 UI).
- **Pre-cached fallback response** for `PaymentService.charge` diff committed to repo — fallback ladder: retry → cache → "using cached analysis" banner

**Ben-style self-test:** dirty the tsre repo (modify `PaymentService.charge` to remove a null check), run "Analyze PR Risk." Does Codex actually cite INC-4402? If not, the prompt or fixture keyword mapping is wrong.

**Exit:** end-to-end on demo laptop in <8s. Fallback path tested with WiFi off.

### Phase 4 — Polish + "does it feel real" (H28 → H36)

**Goal:** fix rough edges that would embarrass on stage. This phase is where most hackathons die — budget it.

**Work:**
- Icon + color polish (risk_hint band: red/amber/green)
- Copy pass on tooltip + recommendation phrasing (short, confident, SRE-voice)
- `USE_REAL_DATADOG` setting visible + wired (demo path stays on Mock)
- `README.md`: 30-sec run instructions, "How Codex is used" section, wedge screenshots, Loom placeholder
- `NOTICE` file for upstream Datadog fork attribution
- Full dress rehearsal on the demo laptop — clone fresh from GitHub, run `./gradlew runIde`, execute pitch

**Ben-style self-test:** watch the Loom back. Does it look like a product or a hack? If hack — 10 minutes of targeted polish, not a rewrite.

**H36 gate (HARD):** feature freeze. No new code unless it fixes a demo-path bug.

### Phase 5 — Pitch + ship (H36 → H48)

**Goal:** crisp 3-min pitch + submission artifacts.

**Work:**
- 3× full-dress pitch rehearsal, each recorded + self-reviewed
- 60-second Loom recorded (backup submission)
- `./gradlew buildPlugin` → `.zip` tested on clean IntelliJ install (not just the dev one)
- Repo pushed public on branch `hackathon-proactive`; commit history clean
- Submission form filled at https://cerebralvalley.ai
- **Pre-flight demo checklist on the demo laptop:** sandbox IDE launches cold in <10s, fixtures load, gutter appears, risk card populates, flag flip works, WiFi-off fallback works

**Exit:** shipped.

---

## Test wiring

**Unit + integration (primary):**
```bash
./gradlew test --quiet 2>&1 | tail -30
```
IntelliJ `BasePlatformTestCase` + `LightPlatformCodeInsightFixture4TestCase`. One test per `DatadogService` method, one per action, one for tool-window lifecycle, one for `computeBaseline` formula.

**Manual UAT (sandbox):**
```bash
./gradlew runIde
```
Follows `tests/manual-uat.md` (create at Phase 1). H12 and H36 are manual UAT gates.

**Stop hook (auto-test after every agent turn)** — `.claude/settings.json`:
```json
{
  "hooks": {
    "Stop": [{ "hooks": [{ "type": "command",
      "command": "./gradlew test --quiet 2>&1 | tail -30 ; exit 0" }] }]
  }
}
```

**Playwright MCP** — reserved. Kept in `.mcp.json` but unused in v1 (Swing UI can't be driven by Playwright). Flip on only if v1.5 adds a JCEF webview.

## Context management

| Mechanism | Purpose |
|---|---|
| `PRD.md` + this file | Spec + instructions — every agent reads on startup |
| Agent tool | Fresh context per sub-agent; no decay on long builds |
| Git worktrees | Parallel branches without merge conflicts |
| TaskCreate/TaskUpdate | Orchestrator progress; survives context compression |
| Decision Log (below) | Persists decisions across sessions and sleep |

No planning docs beyond the 4 tracked here. Ben's rule.

---

## Anti-patterns (condensed from past hackathons)

| Don't | Do |
|---|---|
| Start features before Phase 0 is green | 30 min of sanity saves 3h of mystery-debugging |
| Hand-edit the fixture JSON | `python3 scripts/generate-fixtures.py`, commit both files |
| Call real Datadog in the demo | Mock is default; real is a 25s toggle moment |
| Treat Codex as one-shot prompt | Agent mode, 5 tools, hybrid reasoning trace IS the creativity beat |
| Let Codex produce the raw score alone | Heuristic baseline always; Codex adjusts ±25 with a cited justification |
| Store OpenAI key in plaintext or committed `.env` | PasswordSafe (runtime) + gitignored `.env` (dev) |
| Decide invocation path mid-build | OpenAI Responses API direct (BYOK). Logged. Don't switch. |
| Ship a demo path that requires internet | Every demo-path call resolves locally or falls back to cache |
| Skip pitch rehearsal | 3× full rehearsals minimum in Phase 5. Record. Watch back. |

---

## Decision Log

*Append-only. Format: `YYYY-MM-DD — decision — reason`.*

- 2026-04-18 — Fork `DataDog/datadog-for-intellij-platform` as base — preserves auth + gutter plumbing, Apache-2.0 safe
- 2026-04-18 — OpenAI Responses API (direct, BYOK) for Codex agent mode — cleanest tool-call support, avoids JetBrains AI Assistant SDK discovery risk
- 2026-04-18 — Demo codebase: `DataDog/tsre-microservices` — real 12-microservice repo, no synthesis needed
- 2026-04-18 — Drop Kaggle dataset — generic anomaly rows can't produce narrative incident tickets referencing real method names; hand-authored fixtures stitched to tsre method names is cleaner and richer
- 2026-04-18 — Dataset: 12 services + 20 narrative incidents + 2200 algorithmic events + 15 methods-of-interest — 848KB, deterministic seed, regenerated via `scripts/generate-fixtures.py`
- 2026-04-18 — Hybrid risk scoring (Layer 1 heuristic + Layer 2 Codex ±25 override with cited justification) — deterministic floor prevents stage blowups; Codex contributes causal reasoning heuristic can't
- 2026-04-18 — OpenAI key: JetBrains `PasswordSafe` (runtime) + gitignored `.env` (dev) + `OPENAI_API_KEY` env fallback — no plaintext, no committed secrets
- 2026-04-18 — Dataset architecture: JSON on disk + in-memory Kotlin `HashMap` indexes built once at plugin startup. Reject SQLite/CSV/Parquet — research agent §Decision Matrix: zero judging upside, adds dependencies and demo risk. Only serialization dep needed is `kotlinx.serialization-json` (already planned for the Responses API I/O layer).
- 2026-04-18 — Reject SQL-as-tool for Codex — expands hallucination surface, not novel, and we'd be competing with Datadog's own query UX. Curated typed tools (`get_datadog_context`, `get_related_incidents`, `find_incidents_by_code_pattern`) win on reliability and demo clarity.
- 2026-04-18 — Add 5th Codex tool `find_incidents_by_code_pattern(pattern)` — scans `incidents[*].offending_code_snippet` (substring match; regex is a stretch). Genuine novel beat: the agent pattern-matches the PR diff against the *source code of past bugs*, not just service names or keywords. Drives the side-by-side "same code, same bug" demo moment.
- 2026-04-18 — **Reverses 2026-04-18 fork decision above.** `DataDog/datadog-for-intellij-platform` contains only LICENSE + README — the actual plugin is closed-source on JetBrains Marketplace. Nothing to fork. Building from scratch on the official IntelliJ Platform Plugin Template 2.x. NOTICE file credits Datadog for inspiration/trademark only, not code. Net cost: ~+2h Kotlin scaffolding that would have been inherited.
- 2026-04-18 — Automated UAT via `BasePlatformTestCase` (headless, fast, for LineMarkerProvider / service contract / action resolution) + JetBrains `remote-robot` (sandbox IDE, for tool-window + toolbar-button end-to-end). Playwright MCP remains reserved for a JCEF webview case we are not building in v1. Manual UAT shrinks to demo-laptop dress rehearsal.
- 2026-04-19 — Dashboard integration: header button (BrowserUtil → live Datadog Events Explorer) + JCEF "Live Feed" tool-window tab (local HTML) + settings fallback. Graceful non-JCEF degradation via link panel. Local dashboard extraction now copies `index.html` + sibling `datadog-fixtures.json` flat for the browser path, and builds a single-file `index.html` with `window.FIXTURE_DATA` inlined for the JCEF path (sidesteps `file://` CORS).
- 2026-04-19 — `AnalyzePrRiskAction` falls through `PR_DIFF → METHOD_UNDER_CURSOR → CURRENT_FILE → INBOX_SELECTION → friendly "no context" balloon`. Keeps Codex in the demo path on clean clones without changing the PR-time thesis. Banner text reflects the resolved mode so users see *why* the analysis ran on what it did.
- 2026-04-19 — Fixture `methods_of_interest` fq_names patched to real tsre Java methods (7 renames): `PaymentServiceImpl.charge`, `PaymentController.clearPayment`, `PrometheusHealthResource.postStatus`, `PaymentservicejavaApplication.initializeBackupScheduler`, `AdService.getAds`, `AdService.ad_analytics`, `AdServiceClient.getAds`. 8 legacy fictional entries kept for BM25 noise + graceful inbox "not in project" balloon. midbuild §1.3 claimed the remap was done; QA confirmed it wasn't. Patched in both `datadog-fixtures.json` (492 incident + 1617 event refs rewritten) AND `scripts/generate-fixtures.py` (METHODS_OF_INTEREST + 4 DEMO_INCIDENTS) so regeneration stays in sync. Also: registered `HighlighterStartupActivity` as `postStartupActivity` so `ActiveFileListener` + `ProactiveRiskHighlighter` sweep fire at project-open (not lazily on first tool-window activation); flipped block-inlay anchor `showAbove=false` with K&R-style `lBrace` offset so the inlay renders below the method signature.
- 2026-04-19 — `ProactiveRiskHighlighter` match widened: exact-line → fuzzy Jaccard 0.4 → always-highlight-signature-line-on-method-of-interest (softer amber). Guarantees Wedge 1 paints *something* on every flagged method even when the fictional fixture snippet doesn't literally match real tsre source (e.g. `customer.billingAddress.country` vs `customer.getBillingAddress().getCountry()`). PSI class resolver tries `ClassName.method` → stripped-suffix (`PaymentServiceImpl` → `PaymentService`) → simple-name index. Listener sweeps `FileEditorManager.openFiles` on install so files already open are covered. INFO log `methodsMatched=N linesHighlighted=M per file`.
- 2026-04-19 — Side-by-side incident diff shipped via IntelliJ `DiffManager.showDiff`. Left = current method body, right = incident `offending_code_snippet`. Triggered from `MethodDetailCard` Compare button + right-click `CompareToIncidentAction`. Closes the PRD §Wedge 2 "Side-by-side code match UI" line pending since Day 1.
- 2026-04-19 — Codex now outputs optional `ProposedPatch` via new `propose_patch` tool. `ApplyRemediationAction` applies textually-matched hunks under `CommandProcessor` undo (Cmd+Z fully reverts). Closes the prediction→remediation loop per user feedback. Fallback `scenario-a-regression-INC4402.json` seeded with a sample patch so the offline demo path still shows the Apply-fix button.
- 2026-04-19 — Inbox promotes in-project methods above legacy fictional entries (secondary sort by risk_hint, stable). Legacy rows labeled "(legacy)" with tooltip; subheader shows "N in project". Live-Datadog URL widened to 30-day window via `from_ts` / `to_ts` / `live=false` params so the Events Explorer actually shows results instead of "Past 15 Minutes".
- 2026-04-19 — `LiveFeedPanel` uses `DashboardResources.extractLocalDashboardInlined()` — single HTML file with fixture JSON inlined as `window.FIXTURE_DATA` so JCEF's `file://` origin never needs `fetch()`. Browser path uses the flat variant (separate fixture file). Fixes the "Couldn't load fixtures: Failed to fetch" error user hit in both surfaces.
- 2026-04-19 — Project tree decoration: `DatadogRiskProjectViewNodeDecorator` + `RiskFilesIndex` paint methods-of-interest files orange in the Project tool window + fold-level "(N at-risk files)" badges. Extends Wedge 1 proactive surface to the file browser so risky files are visible without opening the Datadog Risk tool window. Path match = exact + last-3-segment suffix so relative vs absolute fixture paths both resolve.
