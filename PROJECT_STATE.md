# Project state

Updated 2026-10-02 19:50 UTC. Draft mode (Nick, 18:28): one agent, no reviewers; merge everything and wire the app to real data. New threads resume from this file, not from old conversations.

## Where things are
- main a24a978: every team branch is merged (SIWC, analytics, realtime features, JITAI DSL and engine, AI context, Google Health API connector, test infra, data, collectors, background, interventions, and all UI screens).
- JVM modules: 3,603/3,603 tests pass on CI (run 37050667319).
- Android modules: NOT yet compiled after the merges. Every CI run since 58f44ed was cancelled or never started. Run 37053838903 did not start because GitHub Actions billing failed ("recent account payments have failed or your spending limit needs to be increased"). The Android build only runs on GitHub Actions, because Google Maven is blocked in the cloud container.
- Open review findings are filed as GitHub issues #1 to #5.

## App wiring (app/src/main/kotlin/dev/agentle/app/wiring)
- Wired:
  - Timeline over the Room event table (paged).
  - Dashboard: today's steps, unlocks and screen time from stored events, plus collector status.
  - Data Sources, using the Android collectors graph.
  - Scheduler collector sweeps (usage and device).
  - Retention runs.
  - Hub clock and settings time zone.
  - AgentleApplication starts the collectors graph and BackgroundStarter.
- Still staging (app/.../staging): onboarding, permission center, insights/JITAI screens, interventions, connections (wearable, ChatGPT, AI sharing), settings ports other than the time zone, the JITAI runner, wearable sync, feature refresh.

## Next
1. Restore GitHub Actions (billing), then fix whatever the first Android compile reports.
2. Wire the remaining staging ports (permission center and onboarding next, then JITAI screens over JitaiDefinitionStore, then settings deletion and retention).
3. Reviews, issues #1 to #5, the 36-step scenario and the final report, when the budget allows.

## Rules
Never use the legacy Fitbit Web API (Google Health API only). Agents work token-efficiently: targeted reads and tests, full suite only before completion. Update this file after each major milestone.
