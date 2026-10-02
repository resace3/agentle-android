# Project state

Updated 2026-10-02 18:30 UTC. All agents PAUSED at Nick's request (usage); every branch below is pushed and clean. New threads resume from this file, not from old conversations.

## Where things are
- main (CI green, d50241f): contracts, build/test tooling (St_Johns test zone, type-resolved detekt, coverage summary incl. Android Kover), `:core:ui` navigation, docs/ARCHITECTURE.md, docs/ARCHITECTURE_ISSUES.md, and these merged pieces: Sign in with ChatGPT, analytics, realtime features, JITAI DSL, AI context/egress, Google Health API connector + fake, JITAI engine, android test infra.
- Android modules build only on GitHub Actions (Google Maven blocked in the cloud container); JVM modules build locally with `AGENTLE_JVM_ONLY=true`.

## Branches (not yet merged; all have origin/main cd64b4d merged)
| Branch | Scope | State at pause |
|---|---|---|
| android/background | WorkManager scheduling | 11 review fixes verified; 3 P3 follow-ups open (local-date event cap, cap count under lock, sync-now drops streams on permanent error) |
| android/ui-settings | settings screens | 65/65 tests, goldens verified; 1 P2 open (delete-everything double-tap guard must use local state) |
| android/collectors | Android collectors | review: no P0/P1; fixing 6 P2/P3 (coverage gaps on rate-limit drops/failed flush/Bluetooth, 2FA filter all languages, SMS handler caching, calendar overlap) |
| android/ui-hub | onboarding, hub, permission center, timeline | 1 failing Robolectric test (PermissionCenterJourneyTest second denial); goldens not recorded |
| android/ui-connections | wearable, ChatGPT, AI sharing screens | 96 tests green; screenshot recording timed out, goldens not recorded |
| android/ui-insights | JITAI and insights screens | finishing; no final report yet |
| android/interventions | notification/image/voice/video delivery | closing test gaps; no final report yet |
| android/data | Room storage, deletion, retention | building deletion services and wiring contracts; needs 3 reviewers (critical area) |

Wiring contracts the app/data layer must honour are listed in the integrator's notes and in each branch's report (ledger range key `jitai_decision`, generation CAS on DecisionStore commit, NotificationContentPurger, EgressGuard as AiSendVerifier, per-install accountSalt, BackgroundStarter in Application.onCreate).

## Next
1. Finish the open items above, verify, merge each branch to main.
2. App wiring (implement feature ports over :data and connectors, remove `app/.../staging`), Play services Google authorization adapter.
3. Test, security and emulator passes; 36-step scenario; final report with real test counts.

## Rules
Never use the legacy Fitbit Web API (Google Health API only). Agents work token-efficiently: targeted reads and tests, full suite only before completion. Update this file after each major milestone.
