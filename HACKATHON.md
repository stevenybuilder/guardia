# HACKATHON.md — JetBrains x OpenAI: The IDE Reimagined

**Event:** Cerebral Valley — "The IDE Reimagined: JetBrains Codex Hackathon"
**When:** April 18–19, 2026, in-person SF
**Build window:** 48 hours
**Team:** Solo (Steven) + Claude Code agent pool

## Track (must fit ≥1)

**Problem Statement 3 — Reviewing & Deploying Code** (primary fit).
> *"AI-powered code review, automated change summaries, deployment risk analysis, smart CI/CD integrations that live right inside the IDE. Shorten feedback loops and give developers the confidence to hit merge."*

Wedge 1 (proactive editor context) also laps into **PS1 (Writing & Generating Code)** for codebase-aware context — worth mentioning in the pitch for Creativity points.

## Judging criteria (with weights)

| Criterion | Weight | What it means for us |
|---|---|---|
| **Demo** | **50%** | Bulletproof live execution. Mocks by default; pre-cached fallback. Never call the real internet during the 3-min pitch. |
| **Impact** | 25% | "Prevention over triage" narrative. The `USE_REAL_DATADOG` flag flip proves ship-readiness. |
| **Creativity** | 15% | Wedge 1 (gutter context) + hybrid reasoning (Layer 1 + Layer 2) are the differentiators. |
| **Pitch** | 10% | 3-min live pitch + 1–2 min Q/A. Top 6 teams → stage: 3-min + 2–3 min Q/A. |

## Judging process

- **Round 1:** Assigned judging group. 3-minute live pitch + 1–2 min Q/A.
- **Round 2:** Top 6 → main stage. 3-min pitch + 2–3 min Q/A to sponsor panel.

## Submission deliverables

1. Public GitHub repo (Apache-2.0 preserved, `NOTICE` file credits upstream Datadog plugin fork)
2. Packaged plugin `.zip` (`./gradlew buildPlugin`)
3. `README.md` with: 30-sec run instructions, "How Codex is used" section, screenshots of both wedges, Loom link
4. 60-second Loom demo (backup artifact)
5. Live on-stage demo — the real deliverable

## Target prizes

- **Best Use of OpenAI Codex** — leans on agent-mode + 5 registered tools + hybrid reasoning trace
- **Best JetBrains IDE Integration** — novel editor surface (live cursor-reactive risk gutter), not a sidebar

## Open questions (verify before kickoff)

- Exact submission deadline time on April 19 (behind Cerebral Valley auth wall — sign in with `stevenybusiness@gmail.com`)
- Devpost mirror vs. CV-internal form
- Confirmed prize list and sponsor names
- Team size limits
