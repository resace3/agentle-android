# Project state

Updated 2026-10-02 16:10 UTC. New threads resume from this file, not from old conversations.

## Where things are
- main: shared contracts, build tooling (St_Johns test zone, type-resolved clock rules, light branch CI), navigation contract (`:core:ui` AppRoute/AppNavigator), Lineage/SourceFamily, revised `docs/ARCHITECTURE.md` and the red-team register `docs/ARCHITECTURE_ISSUES.md` (119 issues). CI green.
- Android modules build only on GitHub Actions (Google Maven blocked in the cloud container); JVM modules build locally with `AGENTLE_JVM_ONLY=true`.

## Branches (not yet merged)
| Branch | Scope | State |
|---|---|---|
| team/jitai-dsl | rule DSL | done, 719 tests; in review |
| team/analytics | daily features, insights | done, 512 tests; in review |
| team/siwc | ChatGPT sign-in, OAuth | done, 513 tests; 3 reviewers |
| team/realtime-features | realtime JITAI features | repairing review findings |
| team/jitai-engine | JITAI engine | building |
| team/googlehealth | Google Health API connector + fake | building |
| team/ai-context | AI egress and consent | building |
| android/data, android/collectors | storage, Android collectors | building |
| android/test-infra | test tooling items C-I | building |
| android/ui-hub, ui-insights, ui-connections, ui-settings | screens | building |
| android/interventions, android/background | delivery, scheduling | building |

## Next
1. Repair review findings, re-verify, merge each branch to main.
2. App wiring (implement feature ports over :data and connectors, remove `app/.../staging`), Play services Google authorization adapter.
3. Test, security and emulator passes; 36-step scenario; final report with real test counts.

## Rules
Never use the legacy Fitbit Web API (Google Health API only). Agents work token-efficiently: targeted reads and tests, full suite only before completion. Update this file after each major milestone.
