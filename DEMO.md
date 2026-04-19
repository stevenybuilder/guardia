# DEMO.md — Stage Rehearsal Script

**3-min live pitch + 1–2 min Q&A** · Cerebral Valley "IDE Reimagined" · April 19, 2026
**Source of truth for *what* is pitched:** [`PRD.md`](PRD.md) §Live demo script.
**This doc is *how* to execute it** — click-by-click, spoken words, fallback branches, Q&A cheat sheet.

---

## T-30 min — pre-demo checklist

Run through in order. If any step is RED, stop and fix before taking the stage.

| # | Check | Command / action | GREEN looks like |
|---|---|---|---|
| 1 | Demo laptop on stable power, brightness max, Do Not Disturb on | System prefs | — |
| 2 | WiFi on, venue network known good | `ping 1.1.1.1` | <50ms |
| 3 | Close Slack, email, notifications | — | No pop-ups |
| 4 | Fresh `./gradlew runIde` cold start | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew runIde` | Sandbox IDE opens with `demo-repos/tsre-microservices` loaded |
| 5 | **Settings → Tools → Datadog Proactive → "Test Demo Path"** | Click button | `Demo path GREEN — N checks passed.` (`DemoPathHealthCheck`) |
| 6 | Fallback scenarios load | Part of step 5 | 3/3 scenarios loadable, trace non-empty, Finalized present |
| 7 | OpenAI key resolvable | Part of step 5 | `OpenAI API key: resolvable` |
| 8 | `USE_REAL_DATADOG` = **OFF** | Settings → Datadog Proactive | Checkbox unchecked |
| 9 | Tool window "Datadog Risk" docked on right, ready but empty | Click right-edge icon | Placeholder panel visible |
| 10 | Open `PaymentServiceImpl.java` in a tab (tsre-microservices) but **collapse** it — start the demo cold-opening it | Editor | Tab exists, not focused |
| 11 | **WiFi-off fallback rehearsal** — toggle WiFi off, re-run "Analyze PR Risk", confirm the pre-cached trace renders and banner says "Using cached analysis" | One dry run | Risk card populates from `/fallback/scenario-a-regression-INC4402.json` |
| 12 | WiFi back on before taking stage | — | — |
| 13 | Loom backup recorded + URL in submission form | — | 60-sec clip, wedges 1 + 2 |
| 14 | GitHub repo public, `NOTICE` + `README.md` pushed | `git status` clean | — |

---

## Stage setup (window layout)

- **One external display** if offered. Mirror, don't extend — the judges see exactly what you see.
- **IntelliJ New UI** full-screen. Zoom level: 125% (Preferences → Appearance → IDE Font Size).
- **Editor:** Project tree left-collapsed, tool window "Datadog Risk" right-docked.
- **No terminal visible.** No `.env`. No Slack avatar. No secret keys in any URL bar.
- **Screen recorder running** as a passive backup (QuickTime → New Screen Recording).

---

## The 3-minute script (memorize the first 2 beats)

### 0:00 – 0:20 · Problem (slide or verbal)

> **"Datadog tells you when you're on fire. It can't tell you *before* you light the match. That's the gap I filled."**

*One sentence. No slide clicks. Look at the judges, not the screen.*

---

### 0:20 – 1:20 · Wedge 1 live — proactive editor context

**Click sequence:**
1. In the Project view, expand `demo-repos/tsre-microservices/src/paymentservice/…/PaymentServiceImpl.java`
2. Double-click to open. *The file opens cold — no prior navigation.*
3. **Expected:** Gutter icon (red ⚠️) appears on `charge()` within 2s. Editor banner at top reads: *"This file contains methods with live Datadog risk."*
4. Hover the gutter icon. Tooltip shows:
   - `⚠️ HIGH RISK — PaymentServiceImpl.charge`
   - `2 open incidents · Error rate +4× since last commit`
   - `In today's deploy window`
   - Links: `[Open in Datadog] [Analyze PR Risk]`

**Say while the gutter renders:**
> **"I didn't search for this. I didn't open a dashboard. I just opened the file — and the IDE told me this function is on fire. Two open incidents. Error rate 4× baseline. Shipping in today's deploy window. Proactive. The cursor becomes the surface."**

*If the gutter is slow: keep talking, it will appear. Don't acknowledge the delay.*

---

### 1:20 – 2:20 · Wedge 2 live — PR risk with Codex reasoning

**Click sequence:**
1. Press `Ctrl+Alt+Shift+R` (or click "Analyze PR Risk" in the right toolbar). The tool window takes focus.
2. **Expected in the risk card:**
   - Score chip animates `60 → 85` (red band)
   - Affected services: `payment-service`, `order-service`
   - Recommendation: *"Hold this PR — reintroduces INC-4402 null-check at PaymentService:142."*
   - Reasoning trace expands with 5 numbered steps
   - **Side-by-side code match** block: your diff fragment (left) + INC-4402's `offendingCodeSnippet` (right)

**Say while steps stream in:**
> **"Two layers. Layer 1: a deterministic Kotlin heuristic — baseline 60 from error rate plus deploy window. Never fails, runs in 5ms."**
>
> *(trace step 4 arrives — `find_incidents_by_code_pattern`)*
>
> **"Layer 2: OpenAI Codex in agent mode. Five tools: `get_diff`, `get_datadog_context`, `get_related_incidents`, `find_incidents_by_code_pattern`, `finalize_risk`. The novel one — `find_incidents_by_code_pattern` — lets Codex grep every past incident's *source code*, not just service names."**

**Point at the side-by-side block.** *This is the visceral beat.*

> **"Look at this. Left side: the diff I just wrote. Right side: the offending code from INC-4402 — a SEV-1 we shipped March 15th. Same null-check. Same file. Same bug. Your reviewer might catch this. Your memory won't. This does."**
>
> **"Baseline 60. Codex override plus 25. Final score 85 with a citation. Before I push."**

---

### 2:20 – 2:45 · "It's real" moment — flip `USE_REAL_DATADOG`

**Click sequence:**
1. `⌘ ,` (Preferences) → Tools → Datadog Proactive
2. Check **Use Real Datadog**. Click Apply.
3. Balloon notification: *"Switched to Real Datadog mode"*.
4. Close settings. Re-run `Ctrl+Alt+Shift+R`.
5. **Expected:** risk card re-renders; you may see *"Datadog API responded 401"* or a healthy response — either is fine, the wiring is what's demoed.

**Say:**
> **"Mock off. Real Datadog API on. Same code path. The `DatadogServiceProvider` swaps implementations per call — no restart. This is ship-ready, not a hackathon demo in disguise."**

---

### 2:45 – 3:00 · Close

> **"Prevention-first observability, living in the cursor, not the dashboard. The IDE reimagined as an SRE co-pilot. Datadog has the data. OpenAI has the reasoning. JetBrains has the surface. I stitched the three into the one place a developer actually lives."**

*Pause. Don't fill the silence.*

---

## Fallback branches (if something goes sideways)

| Failure | What to do | What to say |
|---|---|---|
| Gutter icon doesn't appear on `charge()` | Click into another method in the file, then back. Daemon will re-run the LineMarker pass. | Don't acknowledge — just keep narrating. If after 10s nothing: open `AdServiceImpl.java` instead, `getAds()` also has a gutter. |
| "Analyze PR Risk" button does nothing | Try `Ctrl+Alt+Shift+R`. If still nothing: Tools menu → Analyze PR Risk. | — |
| Tool window shows empty placeholder after click | The coordinator didn't emit. Re-click the action once. If empty again, **WiFi-off fallback auto-engages** — the pre-cached scenario renders in 3–4s. | *"This is the fallback ladder I built — when the live Codex call can't complete, the plugin plays a pre-recorded trace so the judge still sees the shape of the output."* |
| Codex returns an error mid-trace | The fallback auto-engages. Banner: *"Using cached analysis."* | Same as above. |
| Real Datadog flip throws 401 | **This is fine and expected.** | *"401 as expected — no production Datadog key on this laptop. The wiring is the point: one checkbox, no restart, production path live."* |
| Laptop freezes, IDE unresponsive | Stay calm. `⌘⌥Esc` force-quit, relaunch via `./gradlew runIde`. While relaunching: play the **60-sec Loom** from the submission form. | *"While the sandbox cold-starts, here's the recording — same flow, 60 seconds."* |
| Clock hits 3:00 mid-sentence | Stop. Judges respect the time. | — |

---

## Q&A cheat sheet (likely questions)

**"Isn't this just Datadog Bits AI?"**
> *"Bits is reactive — it lives in the Datadog dashboard and answers questions about incidents that already happened. This runs at cursor-landing and at PR-time, inside the IDE, before the incident. Different trigger surface, different problem."*

**"How does Codex know which incidents to match?"**
> *"Two paths. `get_related_incidents` uses BM25 + structural matching on methods and keywords. The novel one — `find_incidents_by_code_pattern` — does substring matching on `offendingCodeSnippet` fields across the entire incident history. So Codex matches on the code that actually broke, not just the service name."*

**"What if the real Codex call fails on stage?"**
> *"Three-layer fallback ladder: retry with 2s backoff, then pre-cached canned traces keyed by scenario ID committed to the repo, then a 'using cached analysis' banner. You just saw the pre-cached path — it's deterministic, so my demo can't blow up."*

**"How much of this is mocked?"**
> *"The Datadog API is mocked by default — `MockDatadogService` reads a 2000-incident fixture. The OpenAI Codex call is real; you watched five tool invocations round-trip to OpenAI. The `USE_REAL_DATADOG` flip proves the real-API path compiles and wires — just didn't bring production credentials on stage."*

**"Why fork the Datadog plugin?"**
> *"Apache-2.0 license, preserves their auth plumbing and gutter infrastructure. Added ~350 lines of Kotlin. Faster than rebuilding, more honest than pretending I shipped a full plugin in 48 hours."*

**"Could this ship as a real product?"**
> *"The mock-vs-real toggle is one line. The fixture dataset is generated from real incident schemas. The Codex tools are idempotent and cacheable. Yes — the gap to production is auth-flow polish and a real Datadog licensing conversation, not a rewrite."*

**"How do you handle false positives?"**
> *"The baseline heuristic is deterministic and conservative. Codex can only move the score ±25 and must cite incident IDs in the justification — so every override is auditable. A dev who disagrees can open the cited incident and see the source code match for themselves."*

**"Did an AI help you build this?"**
> *"Yes — Claude Code for scaffolding and test coverage, OpenAI Codex for the agent loop itself (dogfooding). The PRD, architecture, and design decisions are mine. The build is 48 hours of human-in-the-loop."*

---

## Post-demo · submission artifacts

- GitHub repo public, branch `hackathon-proactive` (or `master` if submission form allows)
- `README.md` top-of-file: 30-sec run, "How Codex is used" section, wedge screenshots, Loom URL
- `.zip` plugin artifact from `./gradlew buildPlugin`
- Cerebral Valley submission form filled with: repo URL, Loom URL, track PS3 selected
- Target prizes: **Best Use of OpenAI Codex** + **Best JetBrains IDE Integration**

---

## One-line mantra

> **"Cursor-native. Prevention-first. The IDE reimagined as an SRE co-pilot."**
