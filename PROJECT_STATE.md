# Project state

Updated 2026-10-02 19:50 UTC. Draft mode (Nick, 18:28): one agent, no reviewers; merge everything and wire the app to real data. New threads resume from this file, not from old conversations.

## Where things are
- main a24a978: every team branch is merged (SIWC, analytics, realtime features, JITAI DSL and engine, AI context, Google Health API connector, test infra, data, collectors, background, interventions, and all UI screens).
- JVM modules: 3,603/3,603 tests pass on CI (run 37050667319).
- Android: the draft app builds on CI. Run 37065175047 uploads the `apks` artifact (fakeDebug, prodDebug, prodRelease). Android tests: 789/790 pass; the 1 failure is PermissionCenterJourneyTest (issue #4). The prodDebug APK scan found no test code. The repo is public since 2026-10-02, so Actions runs without billing.
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
1. Fix PermissionCenterJourneyTest (issue #4) so CI goes green.
2. Wire the remaining staging ports (permission center and onboarding next, then JITAI screens over JitaiDefinitionStore, then settings deletion and retention).
3. Reviews, issues #1 to #5, the 36-step scenario and the final report, when the budget allows.

## Rules
Never use the legacy Fitbit Web API (Google Health API only). Agents work token-efficiently: targeted reads and tests, full suite only before completion. Update this file after each major milestone.

## 2026-10-04: UI shell, Sign in with ChatGPT, chat over phone data
- UI: blue Material palette (core/ui Color.kt); MainActivity is a ModalNavigationDrawer shell (app/shell): Chat tab first, then the feature screens, then "My dashboards" (DashboardSpec, validated declarative specs in DashboardStore; ChatGPT does not create them yet).
- Sign in with ChatGPT is wired for real: app/wiring/SiwcWiring.kt (Tink SecretVault credential store, browser launch, loopback callback, `agentle://siwc-done` return link). Not yet tried against OpenAI from a device.
- Chat: app/wiring/AppAi.kt builds AiContext (ContextSelectionEngine + EgressGuard) with ai/context PhoneUsageDataSource (screen time, unlocks, top apps). The user allows sharing on the chat tab (grant SCREEN_TIME_TOTALS + APP_IDENTITY for GENERAL_QUESTION). Tested by ai/context PhoneUsageChatTest. The audit log is in memory only.
- Known red CI: PermissionCenterJourneyTest (issue #4), feature:connections test timeout.

## 2026-10-04: Phone sensors tab
- connectors/api `sensors/`: SensorCatalog names every Android sensor type (34 public API 37 types plus 8 hidden ones phones list: tilt, wake gesture, device orientation, Android 17 moisture intrusion...); vendor types fall back to their string type. SensorCheck turns live observations into states (sending data, ready/armed for event sensors, needs permission, refused, no data after 5 s for continuous sensors).
- connectors/android `collectors/sensors/AndroidSensorGateway`: lists `getSensorList(TYPE_ALL)` plus dynamic sensors, listens to all while collected (one-shot sensors via requestTriggerSensor), skips sensors whose permission is missing (step sensors need ACTIVITY_RECOGNITION). Foreground only: Android sends no sensor events to background apps.
- App: "Phone sensors" drawer tab (app/shell SensorsScreen/SensorsViewModel); `adb shell am start -n dev.agentle.app/.MainActivity --es agentle.open sensors` opens it. Tests: SensorCatalogTest, SensorCheckTest, AndroidSensorGatewayTest (reflection over every Sensor.TYPE_* of each Robolectric SDK), PhoneSensorsTabTest.
- CI signs debug APKs with the DEBUG_KEYSTORE_B64 secret when it is set (key in /mnt/project-files/agentle-android/signing/), so reinstalls keep granted permissions.
