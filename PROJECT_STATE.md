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
- UI: blue Material palette (core/ui Color.kt); MainActivity shell (app/shell); see the dashboards sidebar section below.
- Sign in with ChatGPT is wired for real: app/wiring/SiwcWiring.kt (Tink SecretVault credential store, browser launch, loopback callback, `agentle://siwc-done` return link). Not yet tried against OpenAI from a device.
- Chat: app/wiring/AppAi.kt builds AiContext (ContextSelectionEngine + EgressGuard) with ai/context PhoneUsageDataSource (screen time, unlocks, top apps). The user allows sharing on the chat tab (grant SCREEN_TIME_TOTALS + APP_IDENTITY for GENERAL_QUESTION). Tested by ai/context PhoneUsageChatTest. The audit log is in memory only.
- Known red CI: PermissionCenterJourneyTest (issue #4), feature:connections test timeout.

## 2026-10-04: Phone sensors tab
- connectors/api `sensors/`: SensorCatalog names every Android sensor type (34 public API 37 types plus 8 hidden ones phones list: tilt, wake gesture, device orientation, Android 17 moisture intrusion...); vendor types fall back to their string type. SensorCheck turns live observations into states (sending data, ready/armed for event sensors, needs permission, refused, no data after 5 s for continuous sensors).
- connectors/android `collectors/sensors/AndroidSensorGateway`: lists `getSensorList(TYPE_ALL)` plus dynamic sensors, listens to all while collected (one-shot sensors via requestTriggerSensor), skips sensors whose permission is missing (step sensors need ACTIVITY_RECOGNITION). Foreground only: Android sends no sensor events to background apps.
- App: "Phone sensors" drawer tab (app/shell SensorsScreen/SensorsViewModel); `adb shell am start -n dev.agentle.app/.MainActivity --es agentle.open sensors` opens it. Tests: SensorCatalogTest, SensorCheckTest, AndroidSensorGatewayTest (reflection over every Sensor.TYPE_* of each Robolectric SDK), PhoneSensorsTabTest.
- CI signs debug APKs with the DEBUG_KEYSTORE_B64 secret when it is set (key in /mnt/project-files/agentle-android/signing/), so reinstalls keep granted permissions.

## 2026-10-04: Dashboards sidebar (Nick's mockup) and ChatGPT-made dashboards
- Sidebar (MainActivity): "Dashboards" header with + (opens the chat), "New Dashboard" (the ChatGPT chat), the user's dashboards, a collapsible "More" (Today, Timeline, Insights, JITAIs, Data sources, Phone sensors, ChatGPT connection) and Settings at the bottom. It slides in from the top bar's menu button below 600 dp and stays open at 600 dp and wider.
- Chat replies are structured: ai/api `ChatReplySchema` v1 (GENERAL_QUESTION's output schema) is `{schemaVersion, reply, dashboard}`, where dashboard is null or `{title, metrics (1-6 codes from ChatReplySchema.METRICS), days 1-90}`. AppAi.ask validates it; SiwcChatPort turns it into a DashboardSpec via `DashboardSpec.validated`; the chat answer links to the new dashboard. Chat replies skip the number-provenance check (they were unchecked free text before). Instruction set was `agentle-ai-context-v2`. Superseded by ChatReplySchema v2 (2026-10-05 section below).
- Dashboards are declarative data only and are computed on the phone (DashboardCalculator, DashboardMath; tested by DashboardCalculatorTest over an in-memory repository): steps/distance prefer the source's daily total, else per-minute fused samples; active calories, exercise, sleep (counted on the wake-up day), screen time, unlocks and notifications take the largest source per day; heart rate is the mean of fused minutes; resting heart rate uses the reported date. Sources are never added together. DashboardMetric names must equal ChatReplySchema.METRICS keys (DashboardSpecTest).
- DashboardStore adds "Sleep Dashboard" and "Activity Dashboard" once on first start (prefs flag `starters_added`).
- ChatGPT still receives only the phone-usage summaries the user allowed; dashboard numbers stay on the phone.


## 2026-10-04: Chat crash, sign-in "rate_limited", squeezed ChatGPT screen, sensor list for ChatGPT
- Crash on the first real chat answer: Android's regex engine (ICU) rejects a `{` or `}` outside a quantifier, which the desktop JVM and Robolectric accept, so `AiTextPolicy` failed to load on the phone (ExceptionInInitializerError on Main). Fixed there and in ContentParts, PatternText and Redactor; `core/common` AndroidRegexSyntaxTest now scans every module's regex literals. ChatViewModel turns any Exception or LinkageError from a send into a chat message and words common failures (rate limit, network, sign-in).
- "rate_limited" right after signing in: a 401 on a fresh token forced a refresh before `earliest_refresh_at`, and the failure was saved as SERVER_ERROR/REFRESH_NOT_READY (provider Unavailable). SiwcSessionManager now fails only that call while the access token is still valid. SiwcChatPort counts UsageLimited and Unavailable as connected, so the chat keeps its input box.
- LabelValueRow (feature/connections) gives the value `weight(1f, fill = false)`, so long values no longer squeeze labels to one letter per line (LabelValueRowLayoutTest at w320dp, font scale 1.3).
- Chat questions can include the sensor inventory under AiDataCategory.DEVICE_STATE (ai/context SensorInventoryDataSource, joined with phone usage by CompositeAiContextDataSource): counts, closed type codes per sensor group (vendor-only types become VENDOR) and the recorded ones (the step counter while `android.steps` rows exist). No names, vendors, ranges or readings. The chat's sharing card now grants SCREEN_TIME_TOTALS + APP_IDENTITY + DEVICE_STATE, so accounts with the old grant are asked again.

## 2026-10-05: AI-made screens with Google's A2UI renderer (Nick chose "Build with Google")
- ChatReplySchema v2 is `{schemaVersion: 2, reply, screen}`: screen is null or `{title, components}`, a flat list of Agentle parts in A2UI's shape (ai/api `screen/ScreenSpec.kt`, catalog `urn:agentle:a2ui:catalog:v1`): Column, Row (up to 3), Card, Text (h1-h3, body, caption), Divider, MetricTile (total, average, latest), TrendChart (bar, line). Up to 20 parts, 4 levels, ids `^[a-z][a-z0-9_]{0,23}$`, a `root` part, every other part placed exactly once. Titles and texts may not hold digits or number words: the model never supplies numbers. ScreenRules is the trust gate (codes E114-E119), and A2UI's own checks run on top. Instruction set `agentle-ai-context-v3`.
- The app draws saved screens with `androidx.a2ui` 1.0.0-alpha01 (a2ui-model, compose-runtime, compose-ui; pre-release, so Compose runtime/ui resolve to 1.13.0-alpha03). `AgentleA2uiCatalog` is our own seven parts drawn with Material3; material3-a2ui is not used. `A2uiScreenHost` builds the A2UI messages only from a checked ScreenSpec. Metric parts read numbers the phone computed (`LocalScreenData` from DashboardCalculator).
- DashboardSpec gained `screen`; dashboards without one (the starters, v1 dashboards) are drawn as one bar chart card per metric (`layout()`). DashboardStore loads dashboards one by one (one that no longer reads is skipped). Storage is app-private SharedPreferences, not encrypted; a dashboard holds layout, metric codes and day ranges, never values.
- Changing a screen: "Change with ChatGPT" on a dashboard or "Change it" on an answer sets DashboardStore.editing; the next question carries the saved screen in words (ScreenDescription) and the new screen replaces it in place. The whole request must fit the 500-character user-text cap, or nothing is sent and the chat says so. An answer whose only problems are in its screen is retried once with the broken rules in words (ScreenRepair), when that fits too.
- Not in v1: buttons or actions on screens; images, links, inputs.
