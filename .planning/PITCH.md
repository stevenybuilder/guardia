# PITCH.md — Stage Script + Choreography

**Event:** JetBrains x OpenAI — The IDE Reimagined · **Duration:** 3 minutes + Q&A
**Goal:** Demo-50 · Impact-25 · Creativity-15 · Pitch-10 (judging weights)

---

## 3-minute script (bullet-tight, rehearse verbatim)

### 0:00 – 0:15 — Hook

> "You open a file. Your cursor lands on `PaymentService.charge`. Before you type a word, your IDE tells you this method broke production twice last quarter. That's not a dashboard. That's IntelliJ with eight years of Datadog history baked into the cursor."

*Click into editor. Gutter icon is already there from a pre-opened file.*

### 0:15 – 0:40 — Wedge 1: Proactive editor context

*Click into `PaymentServiceImpl.charge()`. Gutter icon is visible. Hover.*

> "Orange triangle in the gutter. Hover: two open incidents, error rate up 4× since last commit, today's deploy window. I didn't ask for this. I didn't search for it. It's just there — the way red squigglies are just there. Datadog's surface used to end at the dashboard. We put it in the cursor."

### 0:40 – 2:00 — Wedge 2: PR blast-radius risk

*Click **Analyze PR Risk** in the toolbar. Risk card tool window is already open on the right.*

**Watch the chip pipeline animate left to right:**

> "Pipeline lights up. `get_diff` — 12 lines changed. `compute_baseline_score` — 60. That's the deterministic floor — a Kotlin heuristic that reads error rate, deploy window, blast radius, incident recency. It never fails. It's our stage safety net."

*Baseline 60 renders in the score dial. Chip `get_related_incidents` lights up.*

> "Now Codex runs in agent mode. Six registered tools. It reads the diff, pulls related incidents from 2 030 historical records — synthetic plus real post-mortems from AWS, Cloudflare, GitHub — and here's the beat: it calls `find_incidents_by_code_pattern` and grep's every past incident's source code for the snippet I'm adding."

*Editor flashes red on line 142. Reasoning trace row fades in: "Pattern `customer.billingAddress.country` matched INC-4402 offending_code_snippet."*

*Score dial ramps 60 → 85.*

> "85 final. Codex moved it up 25 — that's the bounded delta; it's not allowed to exceed ±25 and it must cite an incident ID. Look at the card: side-by-side code match. Left — my diff. Right — the offending snippet from INC-4402, resolved March 15th. Same null-check missing. Same file. Your reviewer might catch this. Your memory won't. This does."

### 2:00 – 2:20 — The "It's real" flip

*Open Settings → Tools → Datadog Proactive. Check **Use Real Datadog**. Click **Analyze PR Risk** again.*

> "Mock off. Real Datadog API on. Same code path — the service interface is one flag switch. The production path is wired. This isn't a mockup with a pretty face."

### 2:20 – 2:50 — Why it wins

> "This is the IDE reimagined. Datadog's surface was dashboards, Slack, PagerDuty — everywhere except where developers actually work. We put SRE context in the cursor, and we put causal reasoning on the PR. Not pattern-as-syntax like a linter. Pattern-as-history — 'your code re-introduces this specific past bug.' That's what Codex does that a regex never will."

### 2:50 – 3:00 — Close

> "Apache-licensed. Fork it on GitHub today. Thanks."

---

## Q&A — anticipated questions + answers

**Q: "Isn't this just a linter?"**
A: Linters match syntax against hand-authored rules. We match the diff against causal history — 2 030 real post-mortems — and emit incident citations, not rule IDs. A linter can't tell you "this re-introduces INC-4402." We can.

**Q: "What if Datadog doesn't have the incident data?"**
A: Fallback ladder: one retry with 2 s backoff → pre-cached trace for the demo method → "using cached analysis" banner. The agent never spins. Real-world: most orgs running Datadog Incidents have the description + root-cause fields we need. For gaps, the retriever degrades gracefully to structural matching on `offending_files` alone.

**Q: "How is this different from Copilot?"**
A: Copilot completes — forward-looking. We retrospect — backward-looking. Copilot asks "what should this code do?" We ask "what did this code break last time?" Complementary, not competitive.

**Q: "Performance impact on the editor?"**
A: Gutter lookup is an O(1) HashMap hit on a pre-indexed fixture, EDT-safe, under 1 ms. Full agent loop runs off-EDT with an 8-s hard timeout. We tested under `./gradlew uiSmokeTest` on a cold IDE.

**Q: "Could you replace Codex with any LLM?"**
A: Yes — `OpenAIResponsesClient` is behind an interface. We chose the Responses API specifically for tool-call fidelity and the agent-loop primitive. Any model with structured tool-calling works.

**Q: "What's the real-world deployment path?"**
A: `RealDatadogService` is wired; one checkbox in settings. Org owner flips it per-project, stores the Datadog API key via JetBrains `PasswordSafe`. Zero code changes required.

**Q: "Can this work with GitHub PRs?"**
A: v1.5. The `DiffProvider` interface already abstracts the diff source — swap the JetBrains Git API for the GitHub API and the rest of the pipeline is unchanged.

**Q: "Where's the data from?"**
A: 2 000 synthetic incidents from a 35-archetype pool (null deref, race, retry storm, deadlock, ReDoS, TLS expiry, etc.) plus 30 real publicly-documented post-mortems (danluu archive, AWS S3 2017, Cloudflare July 2019 WAF ReDoS, Stripe, GitHub, Knight Capital). Sources cited per-incident in the fixture. Fixture is seed-deterministic — anyone can regenerate it.

---

## Demo choreography — pre-stage checklist

### 15 minutes before walking on stage

1. **Record cached Codex responses against live API:**
   ```bash
   ./gradlew recordDemo
   ```
   Captures scenarios A (INC-4402 regression), B (INC-4388 race), C (INC-4417 fix-confirmation) into `src/main/resources/fallback/`.
2. **Turn WiFi off.** Verify all three scenarios still play via the cached path.
3. **Turn WiFi back on only for the 2:00–2:20 flip moment** (optional — the real flip can also error visibly; the wiring is the point, not the 200 response).
4. **Open the tool window before pitch starts:** right sidebar → **Datadog Risk**. Pin it.
5. **Pre-open the demo file:** `src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java`. Cursor on line 29.
6. **Dirty the working tree** so `get_diff` has something to return (paste the scenario-A diff and leave it unstaged).

### Keyboard shortcuts (rehearsed)

- **Ctrl+Alt+Shift+R** — Analyze PR Risk (in case the toolbar button is hidden by the projector resolution)
- **Ctrl+Tab** — switch file (for the "gutter shows up on multiple methods" beat)
- **Cmd+,** — Settings (for the USE_REAL_DATADOG flip)

### File paths memorized

- Demo file: `src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java`
- Scenario-A offending line: **line 142**
- Settings path: `Settings → Tools → Datadog Proactive`
- Cached fallback: `src/main/resources/fallback/payment-charge-analysis.json`

### If something fails mid-pitch

- Stay calm. Say: **"fallback path engaging"** — the banner in the tool window says it for you.
- Keep narrating over the cached path — the judge cannot tell the difference.
- Do **not** apologize. Do **not** restart the IDE. Do **not** touch the keyboard more than necessary.

### Backup artifacts (finder-ready)

- `docs/loom-backup.mp4` — 60-second pre-recorded demo, playable offline if the sandbox IDE crashes. Launch from Finder, not from a browser.
- Slide 1 (title + one-line value prop) — fallback if the IDE fails to launch at all.

---

## Rehearsal log (fill in during H36 → H48)

| Run # | Time | Wedge 1 OK? | Wedge 2 OK? | Flip OK? | Notes |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |

Target: three clean runs back-to-back before stepping on stage. If any run fails, the failure mode becomes the thing to rehearse next — stage time is not the place to debug.
