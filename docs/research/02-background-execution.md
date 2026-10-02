# 02 - Background execution design review (Agentle)

Status: v1 complete. It covers all six required items, the open questions (section 8) and the hand-off (section 9). Facts are cited inline as `[ID]`; the IDs resolve in section 0.
Updated 2026-10-02 with three additions: AOSP JobScheduler network and flex-policy findings (section 6.2), the Task Manager stop page (section 5.1), and verified `cmd notification` syntax (E9).
Research dates: 2026-10-01 to 2026-10-02. Author: Agent 2 (Android background execution specialist).
Scope: compileSdk/targetSdk 37 (Android 17), minSdk candidates 26-31, Kotlin + Compose + Room + Hilt + WorkManager + DataStore + OkHttp/Retrofit + kotlinx.serialization.
Ground rules: public APIs and documented special access only. No root, no exploits, no hidden APIs, no reflection on `@hide` members.

---

## TL;DR - decisions this document makes

1. **No always-on foreground service (FGS) in v1.** Every collector is one of the following:
   - **Event-driven:** manifest receivers for broadcasts on the exemption list, PendingIntent-based Play services and connectivity callbacks, the system-bound NotificationListenerService, or WorkManager content-URI triggers.
   - **Periodic WorkManager.**
   - **Only-while-app-open:** raw motion and environment sensors, and nearby-device scanning.

   FGS types are reserved for optional, user-started, visible operations (section 1.4).
2. **WorkManager 2.12.0** (stable 2026-09-23, minSdk 24) is the only scheduler for deferrable work [WM-REL].
   - Periodic work floor: 15 min interval, 5 min flex [AX-WORK PeriodicWorkRequest.kt].
   - Backoff: 10 s minimum, 5 h maximum, 30 s default [AX-WORK WorkRequest.kt].
3. **JITAIs are scheduled without aggressive wakeups:**
   - Event-driven checks run as expedited one-time work with `RUN_AS_NON_EXPEDITED_WORK_REQUEST`.
   - A periodic "tick" runs at 15 / 30 / 60 min, depending on the profile.
   - Daily jobs are pinned with `setNextScheduleTimeOverride` + `UPDATE`.
   - Inexact `setWindow` alarms (10 min or longer) are used only for user-set reminders.
   - **No exact alarms in v1.**
4. **Never request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.** Agentle is not one of the acceptable use cases in [DOZE]. The app degrades gracefully in every standby bucket, including `restricted`, and tells the user so.
5. **Runtime receivers for `SCREEN_ON` / `SCREEN_OFF` / `USER_PRESENT` must use `RECEIVER_EXPORTED`** (or no flag, under the Android 14 "system broadcasts only" exception).
   - `RECEIVER_NOT_EXPORTED` silently drops `USER_PRESENT`, because SystemUI sends it from a non-system UID.
   - Verified in AOSP (section 6.11). This corrects the brief's assumption.
   - The same applies to Bluetooth broadcasts, including the `exported` attribute of manifest receivers.
6. **Screen and unlock timestamps from runtime receivers are hints, not truth.** They exist only while the process is alive, and Android 14 queues and merges them while the app is cached [A14-ALL].
   - The source of truth is the `UsageEvents` backfill: `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`, `KEYGUARD_HIDDEN` (API 28) [REF-UE].
   - Events "are only kept by the system for a few days" [REF-USM], so the usage sweep must run at least daily in every profile.
7. **Activity Recognition and geofence PendingIntents must be explicit and `FLAG_MUTABLE`.**
   - The official platform sample uses an implicit `Intent(action)` with `FLAG_MUTABLE`.
   - That throws `IllegalArgumentException` for targetSdk 34 and above [REF-PI][A14-TGT][PS-AR-SAMPLE]. Do not copy it.
8. **Duplicate work** is prevented in three layers:
   - Namespaced unique work names. `KEEP` on reconcile, `UPDATE` on settings change, never the deprecated `REPLACE` for periodic work.
   - Natural-key upserts, with watermarks committed in the same Room transaction as the data.
   - Deterministic JITAI decision IDs mapped to notification IDs.
9. **Token refresh is single-flight per provider** (`Mutex` + token-version double-check + atomic persist-before-use).
   - This is mandatory for ChatGPT tokens. Their refresh tokens are single-use, and reuse is a terminal `refresh_token_reused` error [DOC06].
10. **Recovery is one idempotent `ScheduleReconciler`.** It is triggered by:
    - Every process start.
    - `BOOT_COMPLETED`, which on Android 15+ is also sent when the app leaves the stopped state [A15-ALL].
    - `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, and `TIMEZONE_OFFSET_CHANGED` (API 37; it "triggers specifically on offset changes like DST transitions" [A17-RN]) [REF-INTENT].
    - Permission changes.

    It re-arms everything the platform does not persist: geofences, activity-transition requests, network PendingIntent callbacks, runtime receivers and daily overrides.
11. **minSdk from the background-execution angle:**
    - **Recommend 31.** It gives native expedited jobs without the FGS fallback, `FLAG_MUTABLE`, uniform FGS-start and exact-alarm rules, and the restricted bucket [WM-DEFINE][FGS-BG][STANDBY].
    - **Acceptable floor: 29.** It adds `ACTIVITY_RESUMED`/`PAUSED` and `DEVICE_STARTUP`/`SHUTDOWN` [REF-UE], and runtime `ACTIVITY_RECOGNITION` [A10-PRIV].
    - **26-28** means a documented degraded mode. The final call belongs to docs 01 and 07.

---

## 0. Sources and method

**Method.** All pages were read from cached copies fetched with `curl` (no WebFetch/WebSearch was used).
- AOSP and androidx sources came from GitHub mirrors (`raw.githubusercontent.com`, plus a sparse `git clone` of androidx).
- `android.googlesource.com` and `support.google.com` returned 403 to curl, and `maven.google.com` metadata redirects to the blocked `dl.google.com`.
  - Those hosts were **not** reached by any other route.
  - Play Help Center specifics are therefore marked **UNVERIFIED**, and the developer.android.com summaries of those policies are cited instead.
- The AOSP GitHub mirror lags AOSP. Its `main` branch is roughly Android 16 QPR-level, so Android 17 internals are taken from the API 37 SDK stubs and the Android 17 docs only.

**Cache location.**
- Earlier-run pages: `/tmp/claude-0/-home-claude/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/agent2/` (`*.html` + `*.txt`) and `.../scratchpad/pages/`.
- This run: `/tmp/claude-0/-home-claude-agentle-android/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/a2/`.

| ID | Source (all fetched 2026-10-01 unless noted) |
|---|---|
| WM-REL | https://developer.android.com/jetpack/androidx/releases/work (2.12.0 stable 2026-09-23; minSdk 23 -> 24; work-analytics; event listener APIs) |
| WM-DEFINE | https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work |
| WM-LONG | https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running |
| WM-MANAGE | https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work |
| WM-UPDATE | https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/update-work |
| WM-TESTINT | https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing |
| WM-TESTIMPL | https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl |
| WM-DEBUG | https://developer.android.com/develop/background-work/background-tasks/testing/persistent/debug |
| REF-EPWP / REF-EWP | https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy , https://developer.android.com/reference/androidx/work/ExistingWorkPolicy |
| REF-TD / REF-TLWB | https://developer.android.com/reference/androidx/work/testing/TestDriver , https://developer.android.com/reference/androidx/work/testing/TestListenableWorkerBuilder |
| AX-WORK | androidx source, commit `140d24a49994c6e5b113270a67e3187adf63668f` (2026-10-01), `work/work-runtime` and `work/work-testing` (`WorkRequest.kt`, `PeriodicWorkRequest.kt`, `Configuration.kt`, `Constraints.kt`, `SystemJobInfoConverter.java`, `ForceStopRunnable.java`, `AndroidManifest.xml`, `TestDriver.java`, `TestListenableWorkerBuilder.java`, `TestClock.kt`) via `git clone --sparse https://github.com/androidx/androidx` |
| PWR | https://developer.android.com/topic/performance/power/power-details (resource limits by device state, app state, standby bucket) |
| STANDBY | https://developer.android.com/topic/performance/appstandby |
| DOZE | https://developer.android.com/training/monitoring-device-state/doze-standby |
| OPT-BATT | https://developer.android.com/develop/background-work/background-tasks/optimize-battery |
| VITALS | https://developer.android.com/google/play/vitals (excessive partial wake locks 5 %, excessive battery usage 1 %; memory thresholds affecting visibility from February 2027) |
| WL | https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/identify-wls |
| ALARMS | https://developer.android.com/develop/background-work/services/alarms |
| REF-AM | https://developer.android.com/reference/android/app/AlarmManager |
| FGS-TYPES | https://developer.android.com/develop/background-work/services/fgs/service-types |
| FGS-BG | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start |
| FGS-TIMEOUT | https://developer.android.com/develop/background-work/services/fgs/timeout |
| FGS-STOP | https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping (fetched 2026-10-02) |
| FGS-CHANGES | https://developer.android.com/develop/background-work/services/fgs/changes |
| UIDT | https://developer.android.com/develop/background-work/background-tasks/uidt |
| BCAST | https://developer.android.com/develop/background-work/background-tasks/broadcasts |
| BCAST-EXC | https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions |
| REF-INTENT | https://developer.android.com/reference/android/content/Intent (API 37 level; includes `ACTION_TIMEZONE_OFFSET_CHANGED`) |
| REF-PI | https://developer.android.com/reference/android/app/PendingIntent |
| REF-NLS | https://developer.android.com/reference/android/service/notification/NotificationListenerService |
| REF-USM / REF-UE | https://developer.android.com/reference/android/app/usage/UsageStatsManager , https://developer.android.com/reference/android/app/usage/UsageEvents.Event |
| REF-CM | https://developer.android.com/reference/android/net/ConnectivityManager |
| REF-JIB | https://developer.android.com/reference/android/app/job/JobInfo.Builder |
| GEOFENCE | https://developer.android.com/develop/sensors-and-location/location/geofencing |
| LOC-LIMITS | https://developer.android.com/about/versions/oreo/background-location-limits |
| LOC-BG | https://developer.android.com/develop/sensors-and-location/location/background |
| AR | https://developer.android.com/develop/sensors-and-location/location/transitions |
| PS-AR-SAMPLE | https://raw.githubusercontent.com/android/platform-samples/main/samples/location/src/main/java/com/example/platform/location/useractivityrecog/UserActivityTransitionManager.kt (re-fetched 2026-10-01, lines 92-102) |
| SENSORS | https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview |
| RECAPI | https://developer.android.com/health-and-fitness/recording-api |
| HC-READ / HC-SYNC / HC-RATE | https://developer.android.com/health-and-fitness/health-connect/read-data , .../sync-data , .../rate-limiting |
| PROCLIFE | https://developer.android.com/guide/components/activities/process-lifecycle |
| A9 | https://developer.android.com/about/versions/pie/android-9.0-changes-all |
| A9-PWR | https://developer.android.com/about/versions/pie/power |
| A10-PRIV | https://developer.android.com/about/versions/10/privacy/changes |
| A14-ALL / A14-TGT | https://developer.android.com/about/versions/14/behavior-changes-all , .../14/behavior-changes-14 |
| A15-ALL / A15-TGT | https://developer.android.com/about/versions/15/behavior-changes-all , .../15/behavior-changes-15 |
| A16-ALL / A16-TGT / A16-FEAT | https://developer.android.com/about/versions/16/behavior-changes-all , .../16/behavior-changes-16 , .../16/features |
| A17-ALL / A17-TGT / A17-FEAT / A17-SUM / A17-RN | https://developer.android.com/about/versions/17/behavior-changes-all , .../17/behavior-changes-17 , .../17/features , .../17/summary , .../17/release-notes |
| SDK37 | `/opt/android-sdk/platforms/android-37.0/android-stubs-src.jar` (API 37 signatures, no javadoc) |
| AOSP-x | `https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/<path>`. Files used: `services/core/java/com/android/server/am/BroadcastSkipPolicy.java`, `.../am/BroadcastController.java`, `core/java/android/app/ActivityManager.java`, `packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java`, `services/core/java/com/android/server/power/Notifier.java`, `core/res/AndroidManifest.xml`, `services/core/java/com/android/server/notification/ManagedServices.java`, `.../notification/NotificationManagerService.java`, `apex/jobscheduler/service/java/com/android/server/job/controllers/QuotaController.java`, `.../job/controllers/ConnectivityController.java`, `.../job/controllers/JobStatus.java`, `.../job/controllers/FlexibilityController.java`, `services/core/java/com/android/server/notification/NotificationShellCmd.java` (the last four fetched 2026-10-02), `apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java`, `core/java/android/app/AlarmManager.java`, `apex/jobscheduler/service/java/com/android/server/job/JobSchedulerShellCommand.java`, `apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java`, `services/core/java/com/android/server/BatteryService.java`, `services/core/java/com/android/server/am/ActivityManagerShellCommand.java`, `services/core/java/com/android/server/power/PowerManagerShellCommand.java`, `services/core/java/com/android/server/power/batterysaver/BatterySaverStateMachine.java`, `services/core/java/com/android/server/timedetector/TimeDetectorShellCommand.java`, `.../timezonedetector/TimeZoneDetectorShellCommand.java`, `cmds/svc/svc.sh` |
| ROBO | Robolectric 4.17 (`repo.maven.apache.org/maven2/org/robolectric/...`): `shadows-framework-4.17.jar` (javap of `ShadowAlarmManager`, `ShadowPowerManager`, `ShadowUsageStatsManager`, `ShadowJobScheduler`, `ShadowNotificationListenerService`, `ShadowApplication$Wrapper`, `ShadowService`, `ShadowSystemClock`, `ShadowPendingIntent`), `DefaultSdkProvider` (SDK 37 "CINNAMON_BUN" REL, JDK 21) |
| DOC05 / DOC06 / DOC07 | Sibling research docs in this repo: `docs/research/05-google-health-and-health-connect.md`, `docs/research/06-openai-sign-in-with-chatgpt.md`, `docs/research/07-architecture-and-versions.md` (read-only; used for the token and sync constraints they established, and for the version and minSdk status) |

---

## 1. Collection and scheduling architecture (every collector)

### 1.1 Mode legend

| Code | Mode | Wakes a dead or cached app? | Cost driver |
|---|---|---|---|
| **EV-M** | Event-driven, delivered by the system to a **manifest** receiver or a PendingIntent: implicit-broadcast exceptions, Play services PendingIntents, `ConnectivityManager` PendingIntent | Yes | User-driven, bounded |
| **EV-S** | Event-driven via a **system-bound service** (NotificationListenerService) | System keeps it bound | Per notification |
| **EV-R** | Event-driven via a **runtime receiver** or callback registered in `Application.onCreate` | **No.** Lives and dies with the process, and is deferred or merged while cached on Android 14+ [A14-ALL] | Free (no wakeups) |
| **CT** | WorkManager one-time work with a **content-URI trigger** (API 24+), re-armed after each fire | Yes (JobScheduler) | Bounded by provider changes plus update delay |
| **PW** | **Periodic WorkManager** (interval of 15 min or more) | Yes (JobScheduler, Doze-batched) | Fixed per day |
| **DW** | **Daily WorkManager** (24 h periodic, pinned with `setNextScheduleTimeOverride`) | Yes | 1 run/day |
| **FGS:type** | Foreground service of that type | n/a, user-visible | High; Play declaration needed |
| **OPEN** | Only while an Activity is visible (or during the user's explicit action) | No | Only while in use |

Why there is no "always-on" mode:
- Android 9+ background sensor restrictions [A9][SENSORS].
- Android 12+ FGS background-start restrictions [FGS-BG].
- Android 14+ FGS types [FGS-TYPES].
- Android 15+ `dataSync` 6 h/24 h limits [FGS-TIMEOUT].
- Android 16+ job quota enforcement, even with an FGS running [A16-ALL].
- Vitals: excessive wake lock threshold 5 % [VITALS].

Together these make a persistent collector service both a policy risk and a battery risk. Nothing Agentle collects needs one: every source has either a push mechanism or a queryable history.

### 1.2 Collector classification (complete list)

Notes:
- "Snapshot" means the value is read at the start of an already scheduled worker run (piggyback, no extra wakeup).
- Permissions and Play policy for each source are owned by doc 01. Only the background aspects are stated here.

#### A. Usage and screen

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| A1 | App usage (per-app time, sessions) | **PW** `sweep.local` | `UsageStatsManager.queryEvents(begin,end)` from a persisted cursor (last event time). `queryEvents(UsageEventsQuery)` (API 35) filters event types [REF-USM] | No push API exists. Requires the usage access special permission. **History is short**: "Events are only kept by the system for a few days", and `queryEvents` returns `null` while the user is locked (R+) [REF-USM]. The sweep therefore runs at least daily in every profile and treats `null` as "retry later", not "no data". |
| A2 | Foreground app activity | **PW** `sweep.local` | `UsageEvents` `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` (API 29) [REF-UE] | Same source as A1, parsed into foreground intervals. |
| A3 | Screen state; screen on/off | **EV-R** + **PW** backfill | Runtime receiver for `ACTION_SCREEN_ON`/`OFF`. These "cannot be received through components declared in manifests" [REF-INTENT]. Register with `RECEIVER_EXPORTED` (section 6.11). `PowerManager.isInteractive()` snapshot. Authoritative history: `SCREEN_INTERACTIVE`/`SCREEN_NON_INTERACTIVE` (API 28) [REF-UE] | The receiver exists only while the process is alive, and Android 14 defers it while cached [BCAST]. Receiver rows are stored as `source=receiver` hints. The sweep reconciles them against `UsageEvents` (`source=usage_events`), which wins. |
| A4 | Unlock events | **EV-R** + **PW** backfill | Runtime `ACTION_USER_PRESENT` (**`RECEIVER_EXPORTED` is mandatory**, because SystemUI is the sender) + `KEYGUARD_HIDDEN`/`KEYGUARD_SHOWN` (API 28) [REF-UE] | As A3. `USER_PRESENT` is not on the manifest exception list [BCAST-EXC]. |
| A5 | Keyguard and display state | **PW** snapshot + A3/A4 events | `KeyguardManager.isDeviceLocked()`/`isKeyguardLocked()`, `DisplayManager` display state | Low-value state. Snapshots plus A3/A4 intervals are enough. |

#### B. Notifications

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| B1 | Notification metadata, content, posting time, package, category, removal (with reason) | **EV-S** | `NotificationListenerService.onNotificationPosted` and `onNotificationRemoved(sbn, rankingMap, reason)`. The system binds the listener while access is granted [REF-NLS]. | Only public API, and no scheduling is needed. On `onListenerConnected()`, take a `getActiveNotifications()` snapshot and diff it against open rows to close missed removals (`removal_reason=UNKNOWN_GAP`). Writes go through a single in-process writer coroutine (batched Room transactions), **never one WorkManager request per notification**. OTP content is redacted for untrusted listeners from Android 15 [A15-ALL]. Android 17 "restricted message access" means "most apps now cannot access end-to-end encrypted messages" (summary only; details UNVERIFIED) [A17-SUM]. |

#### C. Location, activity and steps

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| C1 | Location (places, visits) | **EV-M** geofences + **PW** snapshot | `GeofencingClient.addGeofences(request, pi)`. The PendingIntent is explicit and `FLAG_MUTABLE`. Limit: 100 per app per user. Radius 100-150 m or more. Prefer `DWELL`. Responsiveness 5 min or more [GEOFENCE]. Sweep snapshot via `getLastLocation()`. | Geofencing is the documented alternative to a location FGS [FGS-TYPES]. Background apps get location "only a few times each hour", and geofence events about "every couple of minutes" [LOC-LIMITS]. Needs `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` (target 29+) [GEOFENCE] and Play's background-location review [LOC-BG]. Without background location, C1 degrades to OPEN snapshots. Re-register after reboot, data clear, `GEOFENCE_NOT_AVAILABLE` and force-stop (section 5). |
| C2 | Activity recognition / physical activity | **EV-M** | `ActivityRecognitionClient.requestActivityTransitionUpdates(request, pi)`. Explicit, `FLAG_MUTABLE` PendingIntent (section 6.9) [AR] | Push-style and battery-efficient. Requires `ACTIVITY_RECOGNITION` (runtime on API 29+). An AR transition event is an FGS-start exemption [FGS-BG], but no FGS is needed. Nothing to backfill: steps come from C3. |
| C3 | Step counter | **PW** `sync.hc` (or `sync.recording`); **OPEN** for a live counter | Health Connect `StepsRecord` reads in the background with `READ_HEALTH_DATA_IN_BACKGROUND`. HC "provides access to on-device steps natively on Android 14 (API level 34) and higher" [RECAPI][HC-READ]. Fallback: Recording API on mobile, `LocalRecordingClient` `TYPE_STEP_COUNT_DELTA`, which keeps up to 10 days, "use WorkManager to periodically collect" [RECAPI]. | Raw `TYPE_STEP_COUNTER` is an on-change sensor, and those "don't receive events" in the background on Android 9+ [A9][SENSORS]. Use it only while the app is open, for a live number. |

#### D. Motion and environment sensors

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| D1 | Accelerometer, gyroscope, orientation, device motion | **OPEN** | `SensorManager.registerListener` while an Activity is resumed. Short windows (for example 10 s at `SENSOR_DELAY_NORMAL`). Unregister in `onPause` [SENSORS] | Continuous-reporting sensors deliver no events to background apps on Android 9+ [A9]. The only background route would be an FGS (type `health`, which needs `ACTIVITY_RECOGNITION` or `HIGH_SAMPLING_RATE_SENSORS` [FGS-TYPES]). That is not justified for v1: C2 and C3 give the motion signal at a fraction of the cost. Rates are capped at 200 Hz without `HIGH_SAMPLING_RATE_SENSORS` (target 31+) [SENSORS]. |
| D2 | Ambient light, proximity | **OPEN** | Same as D1 | On-change sensors are also blocked in the background [A9]. |

#### E. Battery and power

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| E1 | Battery % | **PW** snapshot (every worker run) | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`, or the sticky `ACTION_BATTERY_CHANGED` via `registerReceiver(null, filter)` | `ACTION_BATTERY_CHANGED` cannot be received via the manifest [REF-INTENT]. Snapshots on existing runs cost nothing extra. |
| E2 | Charging state and source; charging events | **EV-R** + **PW** snapshot; optional **charging-trigger worker** | Runtime `ACTION_POWER_CONNECTED`/`DISCONNECTED` (protected, system-sent [REF-INTENT]); `EXTRA_PLUGGED` snapshot. Optional `agentle.trigger.charging` (one-time, `setRequiresCharging(true)`): it records "charging started at or before T" and kicks heavy daily work. The next sweep re-arms it with `KEEP` once it sees the device unplugged. | Not on the manifest exception list [BCAST-EXC]. The constraint-trigger gives a near-real-time "plugged in" event at zero cost, and charging lifts job limits for every bucket except `restricted` [PWR]. |
| E3 | Power-save and idle state | **EV-R** + **PW** snapshot | `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED`, `ACTION_DEVICE_IDLE_MODE_CHANGED` (runtime), `isPowerSaveMode()`, `isDeviceIdleMode()` | Also feeds the automatic "Battery Saver means Low profile" rule (section 2.4). |
| E4 | Low-battery events | **EV-R** + **PW** snapshot | Runtime `ACTION_BATTERY_LOW`/`OKAY` (protected [REF-INTENT]); sticky intent `EXTRA_BATTERY_LOW` at each run | Gaps while the process is dead are accepted; snapshots bound the error. |

#### F. Bluetooth

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| F1 | Bluetooth adapter state | **EV-R** + **PW** snapshot | `BluetoothAdapter.ACTION_STATE_CHANGED` runtime receiver with **`RECEIVER_EXPORTED`** (sent by the Bluetooth stack, which is not the system UID; section 6.11 [BCAST]) | Snapshot at each sweep. |
| F2 | Connected devices | **EV-M** | Manifest receiver for `BluetoothDevice.ACTION_ACL_CONNECTED`/`DISCONNECTED`, `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED`, `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`. All are on the exception list [BCAST-EXC]. **The receiver must be `android:exported="true"`**, because the sender is the Bluetooth UID (section 6.11). It does `goAsync()` + one Room insert + `finish()`. | Push-style, user-driven frequency. Needs `BLUETOOTH_CONNECT` on API 31+ (doc 01). |
| F3 | Nearby devices (scan) | **OPEN** | `BluetoothLeScanner` scan started by the user while the app is visible | Background scanning has a continuous battery cost and needs `BLUETOOTH_SCAN`. There is no v1 product need. |

#### G. Network

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| G1 | Wi-Fi metadata | **PW** snapshot + **EV-R** | `ConnectivityManager.getNetworkCapabilities(active).transportInfo` (`WifiInfo`) at each sweep. SSID/BSSID visibility depends on location permission (doc 01). **Never trigger Wi-Fi scans.** | The passive read costs nothing. |
| G2 | Connectivity state, network type, transitions | **EV-R** + **EV-M** + **PW** | `registerDefaultNetworkCallback` while the process lives. `registerNetworkCallback(NetworkRequest, PendingIntent)` "may outlive the calling application", fires on available, and is limited to 100 outstanding requests per UID [REF-CM]. Snapshot at each sweep. | A lost network is inferred from the next snapshot. The PendingIntent registration is lost on reboot and force-stop: Android 15 cancels all PendingIntents on stop [A15-ALL]. The reconciler re-registers it. |
| G3 | Airplane mode | **EV-R** + **PW** snapshot | Runtime `ACTION_AIRPLANE_MODE_CHANGED` (protected [REF-INTENT]); `Settings.Global.AIRPLANE_MODE_ON` read at each sweep | Not on the manifest exception list [BCAST-EXC]. |
| G4 | Data usage (NetworkStatsManager) | **PW** (`sweep.local`, rate-limited to every 6 h) | `NetworkStatsManager.querySummary`/`queryDetailsForUid` per bucket | History is kept by the system, so gaps backfill. Needs usage access. |

#### H. Personal content (providers)

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| H1 | Calendar | **CT** + **DW** fallback | `Constraints.addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)` + update delay / max delay (section 2) [AX-WORK Constraints.kt]. Diff by row hash. | Triggers are hints. They are not persisted and are one-shot (section 6.1), so a daily full reconcile is the safety net. |
| H2 | Call metadata | **CT** + **PW** | `CallLog.Calls.CONTENT_URI` trigger; cursor on `_ID`/`DATE` | Call Log policy is in doc 01. |
| H3 | SMS metadata | **CT** + **PW** | `Telephony.Sms` trigger; cursor on `_ID`/`DATE` **with a 6 h re-read window** | Android 17 withholds OTP-bearing SMS for 3 h: "SMS provider database queries are filtered" (WebOTP for all apps; standard SMS for target 37) [A17-ALL][A17-TGT]. Re-reading the window and upserting by `_ID` captures late-appearing rows. |
| H4 | Contacts | **CT** + **DW** | `ContactsContract.Contacts.CONTENT_URI` trigger; `CONTACT_LAST_UPDATED_TIMESTAMP` cursor; `DeletedContacts` cursor | Android 17 removes `ACCOUNT_NAME`/`ACCOUNT_TYPE` from the `Data` view for target 37 (read them via `RawContacts`) [A17-TGT]. |
| H5 | MediaStore metadata | **CT** + **DW** | `MediaStore.*.EXTERNAL_CONTENT_URI` triggers; `GENERATION_MODIFIED` cursor (API 30). A change of `MediaStore.getVersion()` forces a rescan. | `getVersion()` is per-app unique for target 36+ [A16-TGT]. Store and compare it; never parse it. Page cursors (Android 17 memory limits, section 6.5). |

#### I. Settings and audio

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| I1 | Timezone changes | **EV-M** | Manifest `ACTION_TIMEZONE_CHANGED` + `TIME_SET` (exception list [BCAST-EXC]); `ACTION_TIMEZONE_OFFSET_CHANGED` (API 37, protected [REF-INTENT]) registered at runtime | Whether the Android 17 offset broadcast reaches manifest receivers is UNVERIFIED. The sweep also compares `ZoneId` and offset with the stored value, so a missed broadcast is caught. The same receiver re-pins daily jobs (section 5). |
| I2 | Locale | **EV-M** | Manifest `ACTION_LOCALE_CHANGED`: "Can be received by manifest-declared receivers" [REF-INTENT] | — |
| I3 | Do Not Disturb | **EV-S** + **EV-R** + **PW** | `NotificationListenerService.onInterruptionFilterChanged` (when B1 is granted) [REF-NLS]; `NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED` runtime; `getCurrentInterruptionFilter()` snapshot | DND also gates JITAI delivery (section 3). |
| I4 | Volume | **PW** snapshot | `AudioManager.getStreamVolume` per stream | The Android 17 background audio hardening covers playback, focus and volume *changes*, not reads [A17-ALL]. Agentle never plays audio from the background. |
| I5 | Headset connection | **EV-M** (Bluetooth) + **EV-R** (wired) + **PW** | F2 receivers (Bluetooth headset); runtime `AudioManager.ACTION_HEADSET_PLUG`; `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` snapshot; `AudioDeviceCallback` while alive | — |
| I6 | Ringer mode | **EV-R** + **PW** | Runtime `AudioManager.RINGER_MODE_CHANGED_ACTION`; `getRingerMode()` snapshot | — |

#### J. System events and state

| # | Collector | Mode | Mechanism | Justification, gaps and backfill |
|---|---|---|---|---|
| J1 | Boot events | **EV-M** + **PW** backfill | Manifest `BOOT_COMPLETED` (exception list [BCAST-EXC]). On Android 15+ it is also delivered when the user takes the app out of the stopped state [A15-ALL]. `DEVICE_STARTUP`/`DEVICE_SHUTDOWN` (API 29) [REF-UE] give exact times. | The receiver only enqueues `agentle.reconcile` (section 5). |
| J2 | Health Connect | **PW** `sync.hc` + expedited sync on app open | Changes API with a token per record type. "An unused Changes token expires within 30 days" [HC-SYNC]. `READ_HEALTH_DATA_IN_BACKGROUND` + `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` [HC-READ]. Background rate limits are "stricter than foreground" [HC-RATE]. | No FGS needed. Without the background permission, reads happen only while the app is open [HC-READ]. Details are in DOC05. |
| J3 | Thermal status | **PW** snapshot + **EV-R** | `PowerManager.getCurrentThermalStatus()` (API 29); `addThermalStatusListener` while alive | — |
| J4 | Storage | **DW** snapshot | `StatFs` / `StorageStatsManager` | — |
| J5 | App standby bucket | **PW** snapshot (every worker run) | `UsageStatsManager.getAppStandbyBucket()` (own app, no permission [REF-USM]); `ActivityManager.isBackgroundRestricted()` | Diagnostics plus adaptive behavior. Android 14 recommends logging it [A14-ALL]. |
| J6 | NetworkStatsManager data usage | see G4 | — | — |
| J7 | Installed apps | **DW** | `PackageManager.getChangedPackages(sequenceNumber)` (API 26) for daily diffs; a full list weekly | `PACKAGE_ADDED`/`REMOVED` are not manifest-exempt. Only `PACKAGE_DATA_CLEARED`/`PACKAGE_FULLY_REMOVED` are [BCAST-EXC]. Package visibility is in doc 01. |
| J8 | Alarm clock info | **EV-M** + **PW** | Manifest `ACTION_NEXT_ALARM_CLOCK_CHANGED` (exception list [BCAST-EXC]); `AlarmManager.getNextAlarmClock()` snapshot | Useful for JITAI sleep windows. |
| J9 | Time format | **DW** snapshot (+ on `TIME_SET`) | `DateFormat.is24HourFormat(context)` | — |
| J10 | Keyguard/display | see A5 | — | — |

#### K. Cloud, derived data and JITAI

| # | Collector / job | Mode | Mechanism | Justification |
|---|---|---|---|---|
| K1 | Google Health API (Fitbit) sync | **PW** `sync.ghealth` + expedited `sync.ghealth.now` on app open | Interval per profile (1 h default, matching DOC05). `NetworkType.CONNECTED` (`UNMETERED` in Low). Exponential backoff. 30-day cold-load chunks per run. 300 req/min/user ceiling [DOC05]. | Server-side history means no data is lost when runs are skipped. In rare/restricted buckets, background network may be unavailable [PWR], so the expedited sync on app open is essential. |
| K2 | AI insight generation ("Sign in with ChatGPT") | **DW** `daily.insights` + **OPEN** (expedited while visible) | Network + `batteryNotLow` (Low profile: `UNMETERED` + charging). Prefetches the JITAI message pool (section 3). | LLM calls are never on a JITAI critical path. |
| K3 | Feature derivation | Chained after syncs + **DW** `daily.features` | Local CPU; `batteryNotLow` (Low profile: charging) | Recompute incrementally per touched day. |
| K4 | JITAI evaluation | Section 3 | — | — |
| K5 | Maintenance (retention, `VACUUM`, gap report) | Weekly periodic | `requiresCharging` + `requiresDeviceIdle` | — |

### 1.3 The WorkManager graph (unique names, policies, constraints)

Naming: `agentle.<domain>.<source>[.<variant>]`. Every enqueue goes through one `WorkScheduler` class (Hilt singleton), so policies cannot drift.

| Unique name | Kind | Interval / trigger | Constraints | Expedited | Policy (reconcile / settings change) |
|---|---|---|---|---|---|
| `agentle.sweep.local` | Periodic | profile (6 h / 2 h / 30 min), flex = interval/3 | none (Low: `batteryNotLow`) | — | `KEEP` / `UPDATE` |
| `agentle.sync.hc` | Periodic | 6 h / 1 h / 30 min | none (local IPC) | — | `KEEP` / `UPDATE` |
| `agentle.sync.ghealth` | Periodic | 6 h / 1 h / 30 min | `CONNECTED` (Low: `UNMETERED`) | — | `KEEP` / `UPDATE` |
| `agentle.sync.<src>.now` | One-time | app open, pull-to-refresh | network for cloud sources | yes, `RUN_AS_NON_EXPEDITED_WORK_REQUEST` | `KEEP` |
| `agentle.trigger.<provider>` (calendar, calllog, sms, contacts, media) | One-time, content-URI trigger | provider change; `setTriggerContentUpdateDelay` / `setTriggerContentMaxDelay` per profile | content trigger (not combinable with expedited [AX-WORK WorkRequest.kt]) | — | `KEEP` on reconcile; `REPLACE` when re-armed by its sync |
| `agentle.sync.<provider>` | One-time | enqueued by the trigger worker | none | — | `KEEP` + change-generation loop (section 4.1) |
| `agentle.reconcile.<provider>` | Periodic 24 h | full provider reconcile | `batteryNotLow` | — | `KEEP` / `UPDATE` |
| `agentle.jitai.tick` | Periodic | 60 / 30 / 15 min | none | — | `KEEP` / `UPDATE` |
| `agentle.jitai.check` | One-time | events (section 3) | none (expedited allows only network/storage constraints [AX-WORK]) | yes, `RUN_AS_NON_EXPEDITED_WORK_REQUEST` | `KEEP` + dirty flag |
| `agentle.daily.features` | Periodic 24 h + override (03:30 local) | — | `batteryNotLow` (Low: charging) | — | `KEEP` / `UPDATE` |
| `agentle.daily.insights` | Periodic 24 h + override (06:30 local) | — | `CONNECTED` + `batteryNotLow` (Low: `UNMETERED` + charging) | — | `KEEP` / `UPDATE` |
| `agentle.daily.summary` | Periodic 24 h + override (user time, default 08:30) | — | none | — | `KEEP` / `UPDATE` |
| `agentle.maint.weekly` | Periodic 7 d | — | charging + device idle | — | `KEEP` |
| `agentle.reconcile` | One-time | process start, boot, update, time/zone, permission change | none | yes, `RUN_AS_NON_EXPEDITED_WORK_REQUEST` | `KEEP` |
| `agentle.trigger.charging` | One-time | `setRequiresCharging(true)` | charging | — | `KEEP` (re-armed by sweep) |

Rules:
- **Never `REPLACE` / `CANCEL_AND_REENQUEUE` a periodic work on reconcile.** It "cancel[s] and delete[s]" pending work [REF-EPWP] and resets the period, which turns every app start into a fresh full period (drift) and, worse, a run-now.
- **`UPDATE` for settings changes.** It "preserves enqueue time", does not interrupt a running instance, and applies "on the next iteration" [REF-EPWP].
- Each periodic `doWork()` checks `WorkInfo`-independent preconditions (permission granted, feature enabled) and returns `Result.success()` when they fail. It does not return `failure()`. Periodic work is re-run regardless, and a failure would only add noise.
- One-time workers return `Result.retry()` only for transient errors (network, 5xx, 429, HC quota). Backoff: exponential, 30 s default, 5 h cap [AX-WORK].

### 1.4 Foreground-service usage (types the brief asked about)

| FGS type | Manifest permission (+ `FOREGROUND_SERVICE`) | Runtime prerequisite [FGS-TYPES] | Limits | Agentle v1 decision |
|---|---|---|---|---|
| `dataSync` | `FOREGROUND_SERVICE_DATA_SYNC` | none | 6 h per 24 h shared by all `dataSync` FGS, then `onTimeout(int,int)` and a few seconds to stop; cannot start from `BOOT_COMPLETED` (target 35+) [FGS-TIMEOUT][A15-TGT] | **Optional only.** "Import now" for the first large Google Health / HC backfill, started **while the app is visible** via `setForeground()` from the import worker (section 1.5). The default backfill is chunked periodic work and needs no FGS. |
| `health` | `FOREGROUND_SERVICE_HEALTH` | `HIGH_SAMPLING_RATE_SENSORS` declared, or one of `ACTIVITY_RECOGNITION`, `READ_HEART_RATE`, `READ_SKIN_TEMPERATURE`, `READ_OXYGEN_SATURATION` (`BODY_SENSORS` only on API 35 and lower). Starting from the background with body-sensor permissions needs `READ_HEALTH_DATA_IN_BACKGROUND` (API 36) [FGS-TYPES][A16-TGT] | Subject to while-in-use (WIU) rules [FGS-BG] | **Not in v1.** Candidate for a future user-started "record a session" with live motion sensors. |
| `location` | `FOREGROUND_SERVICE_LOCATION` | location services on + `ACCESS_COARSE_LOCATION`/`FINE`. WIU: from the background only with `ACCESS_BACKGROUND_LOCATION` [FGS-TYPES] | WIU | **Not in v1** (geofencing instead). Candidate for a future user-started "trip tracking". |
| `connectedDevice` | `FOREGROUND_SERVICE_CONNECTED_DEVICE` | one of `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`, `TRANSMIT_IR` declared, or `BLUETOOTH_CONNECT`/`ADVERTISE`/`SCAN`/`UWB_RANGING` granted, or USB permission [FGS-TYPES] | — | **Never.** No peripheral is driven. The docs point to companion device presence instead [FGS-TYPES]. |
| `specialUse` | `FOREGROUND_SERVICE_SPECIAL_USE` | none. Needs `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>`, which is reviewed in Play Console [FGS-TYPES] | — | **Never.** No valid use case; review risk. |
| `shortService` | none (only `FOREGROUND_SERVICE`) | none | about 3 min; then `onTimeout()`, then ANR if not stopped; cannot start other FGS; not sticky [FGS-TYPES] | **Not used.** Expedited work covers "finish quickly". |
| `mediaProcessing` | `FOREGROUND_SERVICE_MEDIA_PROCESSING` | none | 6 h per 24 h (Android 15+) [FGS-TYPES][FGS-TIMEOUT] | **Never.** No transcoding. |

Play: apps targeting 34+ "need to declare [their] foreground service types in the Play Console's app content page (Policy > App content)" [FGS-TYPES].
- With the v1 decisions, Agentle declares **no FGS at all**, unless the optional `dataSync` "Import now" ships. Then it declares only `dataSync`.
- The Help Center wording of the declaration form is **UNVERIFIED** (support.google.com was not reachable).

**Alternative considered for "Import now": a user-initiated data transfer (UIDT) job.**
- What it is:
  - API 34+, JobScheduler only, `RUN_USER_INITIATED_JOBS`.
  - It must be scheduled while the app is visible, and it requires a notification [UIDT].
  - These jobs "are exempt from the ordinary job quotas" [FGS-CHANGES].
- Why v1 does not use it:
  - The UIDT page itself recommends staying on WorkManager for transfers that are "short duration and interruptible" [UIDT]. The chunked import is interruptible.
  - WorkManager has no user-initiated job API: there is no `setUserInitiated` in `work-runtime` at the inspected commit [AX-WORK].
- Revisit it only if a single import chunk cannot fit in the `dataSync` limits.

### 1.5 WorkManager + FGS mechanics (for the optional `dataSync` import)

```xml
<!-- app/src/main/AndroidManifest.xml: only if "Import now" ships -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```

```kotlin
// Inside a CoroutineWorker that the user started from a visible screen.
override suspend fun doWork(): Result {
    try {
        setForeground(
            ForegroundInfo(
                NOTIF_ID_IMPORT,
                importNotification(progress = 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ),
        )
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException is a subclass (API 31+).
        // The user already left: fall back to chunked, quota-bound background work.
        return runChunkedImport(maxDuration = 8.minutes)
    }
    return runImportUntilDoneOrStopped()   // checks isStopped / cancellation; commits per chunk
}
```

Sources for this pattern:
- Manifest merge and the runtime type: [WM-LONG].
- "Wrap `setForeground()` in a try/catch … In Android 12 and higher you can use the more detailed `ForegroundServiceStartNotAllowedException`" [WM-DEFINE].
- When the 6 h `dataSync` budget runs out, the worker is stopped with `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`. That stop reason was added in WorkManager 2.10.0-alpha04 [WM-REL]. Handle it like any stop: commit, then resume next run.

---

## 2. Battery budget and collection-frequency profiles (low / balanced / high-detail)

### 2.1 The system budgets Agentle must fit

App standby bucket limits, quoted from [PWR]. The page notes these values "are not a guarantee … also subject to change":

| Bucket | Regular jobs | Expedited jobs | Alarms | Network |
|---|---|---|---|---|
| Active | up to 20 min per rolling 60 min (enforced from Android 16) | up to 30 min per 24 h (Android 16) | no limits | no restrictions |
| Working set | up to 10 min per rolling 4 h | up to 15 min per 24 h | 10 per hour | no restrictions |
| Frequent | up to 10 min per rolling 12 h | up to 10 min per 24 h | 2 per hour | no restrictions |
| Rare | up to 10 min per rolling 24 h | up to 10 min per 24 h | 1 per hour | **Disabled** |
| Restricted | once per day for up to 10 min | up to 5 min per 24 h | 1 per day | **Disabled** |

Other system rules that bound the design:

- **Device state.**
  - Charging: "no execution limits except for restricted standby bucket" for jobs.
  - Doze: jobs are deferred to maintenance windows, and while-idle alarms are "Limited to 7 per hour" [PWR].
- **App state.**
  - A visible app has no job limits.
  - An app running an FGS: on Android 16+ its jobs follow the bucket quota. "Prior to Android 16 there was no execution limit when the app was running a foreground service" [PWR][A16-ALL].
  - Jobs started while the app is TOP and still running after it leaves follow the quota on Android 16 [A16-ALL].
  - AOSP confirms the Android 16 rule. `QuotaController.getProcessStateQuotaFreeThreshold()` returns `PROCESS_STATE_BOUND_TOP` when `enforceQuotaPolicyToFgsJobs` is on, and `PROCESS_STATE_FOREGROUND_SERVICE` before [AOSP-x QuotaController.java].
- **AOSP alarm quotas** [AOSP-x AlarmManagerService.java]:
  - Per hour, by bucket: `{720, 10, 2, 1, 0}` (active, working, frequent, rare, never).
  - Restricted: 1 per day.
  - Allow-while-idle: 72 per hour for permitted exact alarms; a "compat" quota of 7 per hour for inexact `setAndAllowWhileIdle` and for listener alarms.
  - Minimum alarm window 10 min (`DEFAULT_MIN_WINDOW`).
- **Restricted bucket entry** [STANDBY][A14-ALL]:
  - The app enters it after 8 days without user interaction (Android 13+), after excessive broadcasts or bindings, or (Android 14) after repeated job ANRs.
  - Exemptions include apps with active widgets, apps granted `USE_EXACT_ALARM` or `ACCESS_BACKGROUND_LOCATION`, and apps the user set to "unrestricted".
- **Wake locks.** Jobs and WorkManager create wake locks that are attributed to the app [WL]. The Play vitals "excessive partial wake locks" bad-behavior threshold is 5 % [VITALS]. Agentle never acquires its own `WakeLock`, and short job runtimes keep the attributed wake-lock time low.

**Design rules derived from these limits:**
1. **Essential set within the rare budget.** The usage sweep, a Health Connect token-preserving sync and the provider reconcile must finish in **3 min per day or less, off charger**. They then still run in `rare` (10 min per 24 h) and in `restricted` (one batched 10 min session per day).
2. **Balanced profile at 10 min per day or less of background job runtime, off charger.** That fits `frequent` (about 20 min per day) with margin.
3. **High-detail profile at about 25 min per day or less.** It needs `working set` or better, and degrades gracefully below that.
4. **Heavy CPU and network work prefers charging** (features, insights, `VACUUM`). Charging lifts job limits for every bucket except restricted [PWR].
5. **Network time counts as job runtime.**
   - Every network call has a call timeout (30 s), and every sync run has a wall-clock budget (60 s incremental, 8 min chunked).
   - Each run commits progress per page, so a quota stop (`STOP_REASON_QUOTA`) loses nothing.

### 2.2 Per-collector budget (Balanced profile, not charging)

These are **design targets**, not measurements. Section 7.3 says how to measure them:
- `dumpsys batterystats`, `dumpsys jobscheduler`.
- The experimental `androidx.work:work-analytics` `WorkMetricsInfo` (`workerDurationMillis`, `totalRuntimeMillis`, `stopReasonCounts`) in debug builds [WM-REL].

| Collector / job | Runs per day | Target runtime per run | Network | Wakeup source | Essential? |
|---|---|---|---|---|---|
| `sweep.local` (A1-A5, E1-E4, F1, G1-G4, I3-I6, J3-J5 snapshots) | 12 | 4 s or less | none | periodic | yes (at least 1 per day) |
| `sync.hc` (C3, J2) | 24 | 2 s or less | none (IPC) | periodic | yes (at least 1 per day; tokens die after 30 days unused [HC-SYNC]) |
| `sync.ghealth` (K1) incremental | 24 | 5 s or less | about 1 request per type per page | periodic | no (server keeps history) |
| `sync.ghealth` cold-load chunk (first weeks only) | at most 24 | 60 s or less | at most 300 req/min per user [DOC05] | periodic | no |
| Provider triggers and syncs (H1-H5) | 20 or fewer in total | 2 s or less | none | provider change | no |
| Provider daily reconcile (H1-H5) | 5 | 10 s or less | none | daily | yes (light) |
| `jitai.tick` | 48 | 1 s or less | none | periodic | no |
| `jitai.check` (expedited) | 48 or fewer (cap) | 1 s or less | none | event | no |
| `daily.features` | 1 | 30 s or less | none | daily | no |
| `daily.insights` | 1 | 60 s or less | 1-3 LLM calls | daily | no |
| `daily.summary` | 1 | 1 s or less | none | daily | no |
| Notification listener writes (B1) | per notification | about 50 ms CPU or less, batched | none | system binding | n/a |
| Manifest receivers (F2, I1, I2, J1, J8) | user-driven | 100 ms or less | none | broadcast | n/a |
| PendingIntent events (C1, C2, G2) | user-driven | 200 ms or less (+ optional `jitai.check`) | none | Play services / connectivity | n/a |
| Runtime receivers (A3, A4, E2-E4, F1, G3, I5, I6) | free | 20 ms or less | none | none (process already alive) | n/a |

The Balanced target sum is about 8 min per day:
`12×4 + 24×2 + 24×5 + 20×2 + 5×10 + 48×1 + 48×1 + 30 + 60 + 1 ≈ 493 s`.

The essential set is about 1.5 min per day.

### 2.3 Profiles (user setting in DataStore; default = Balanced)

| Knob | Low | Balanced (default) | High-detail |
|---|---|---|---|
| `sweep.local` interval | 6 h | 2 h | 30 min |
| `jitai.tick` interval | 60 min | 30 min | 15 min (WorkManager floor [AX-WORK]) |
| `sync.hc` interval | 6 h | 1 h | 30 min |
| `sync.ghealth` interval | 6 h, `UNMETERED` | 1 h, `CONNECTED` | 30 min, `CONNECTED` |
| Provider content triggers (H1-H5) | off (daily reconcile only) | update delay 5 min, max delay 1 h | update delay 1 min, max delay 15 min |
| Provider full reconcile | daily, charging | daily | every 6 h |
| `NetworkStatsManager` (G4) | daily | every 6 h (inside the sweep) | every sweep |
| Geofences (C1) | up to 5 (home, work), responsiveness 15 min | up to 20, responsiveness 5 min | up to 50 (hard cap 100 [GEOFENCE]), responsiveness 2 min |
| Location snapshot | none | `getLastLocation()` per sweep | `getCurrentLocation(PRIORITY_BALANCED_POWER_ACCURACY)` per sweep (background limits still apply [LOC-LIMITS]) |
| Activity transitions (C2) | `IN_VEHICLE`, `STILL` | + `WALKING`, `RUNNING`, `ON_BICYCLE` | all supported types |
| Network PendingIntent callback (G2) | off | on | on |
| Runtime receivers, notification listener | on (free) | on | on |
| `jitai.check` cap per day | 12 | 48 | 96 |
| JITAI notifications per day (cap) | 2 | 4 | 6 |
| `daily.features` | 1 per day, charging | 1 per day, `batteryNotLow` | 1 per day + incremental after each sync |
| `daily.insights` | 1 per day, `UNMETERED` + charging | 1 per day, `CONNECTED` + `batteryNotLow` | 1 per day + on demand while visible |
| Raw sensors (D1, D2) | off | while app open, 10 s windows | while app open, continuous at `SENSOR_DELAY_NORMAL` |
| Target background runtime per day (off charger) | about 2-3 min | about 8 min | about 20 min |

**Applying a profile change.** One call, `WorkScheduler.apply(profile)`, does all of the following:
- `enqueueUniquePeriodicWork(name, UPDATE, newRequest)` for every periodic job. `UPDATE` keeps the enqueue time and does not interrupt a running worker [REF-EPWP].
- Re-enqueues the content-trigger workers with `REPLACE`. They are hints, and the reconcile covers any change missed during the swap.
- Re-requests activity transitions and re-adds geofences. `addGeofences` with the same request IDs replaces them.

### 2.4 Automatic adaptation (never fight the system)

- **Battery Saver.**
  - When `isPowerSaveMode()` is on, workers immediately run their "essential-only" path.
  - If Saver stays on for 30 min or more, `WorkScheduler.apply(LOW)` is applied with `UPDATE`. It is reverted the same way after Saver has been off for 30 min. This debounce avoids churn.
  - Cloud syncs pause by themselves while Saver is on. In AOSP, a regular job cannot bypass the Battery Saver network block, so its network constraint stays unmet. The expedited `sync.<src>.now` run on app open can bypass it (section 6.2).
  - Test hook: `settings put global low_power 1`, which is honored only while unplugged. In AOSP, `BatterySaverStateMachine` observes `Settings.Global.LOW_POWER_MODE` and disables Saver while powered [AOSP-x BatterySaverStateMachine.java].
- **Bucket `rare` or `restricted`** (J5 snapshot): run only the essential set.
  - Never try to raise the bucket with notifications. The docs explicitly warn: "Don't spam the user with notifications to try to keep your app in the active bucket" [STANDBY].
- **Background-restricted by the user** (`ActivityManager.isBackgroundRestricted()`):
  - Show one status card: "Background collection is limited by system settings".
  - It can open `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, which "Most apps can invoke" [DOZE].
  - Never nag.
- **Thermal status `SEVERE` or worse** (J3): skip `daily.features` and `daily.insights` until it is back below that level.
- **Low storage:** `setRequiresStorageNotLow(true)` on heavy jobs.

### 2.5 Battery-optimization exemption (decision: do not request it)

- [DOZE] says "Google Play policies prohibit apps from requesting direct exemption from Power Management features—Doze and App Standby—… unless the core function of the app is adversely affected".
  - Its table lists the acceptable cases: chat or VoIP that cannot use FCM, safety apps, task automation, and peripheral companions with persistent connections.
  - Agentle's core function tolerates deferral by design: every collector has history or a backfill, and JITAIs are opportunistic. So `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is **not declared**. The Help Center policy text itself is UNVERIFIED (unreachable).
- The benefit would be small anyway.
  - A partially exempt app "can use the network and hold partial wake locks during Doze and App Standby. However, other restrictions still apply" [DOZE].
  - From Android 16, jobs of user-unrestricted apps get a "generous", not unlimited, execution limit [PWR].
- If the user marks the app "Unrestricted" on their own, Agentle simply benefits. Note that this setting is also an FGS background-start exemption [FGS-BG], but Agentle starts no FGS from the background.
- Side effects to know, but never to use as levers:
  - Active widgets and an `ACCESS_BACKGROUND_LOCATION` grant both exempt the app from the restricted bucket [STANDBY].
  - A home-screen widget is fine as a product feature, never as a bucket hack.

---

## 3. JITAI evaluation scheduling

### 3.1 Principles

1. **Decisions are local and fast.**
   - A JITAI check reads Room, evaluates the rules (and an on-device model if any), and decides in 1 s of CPU or less.
   - It **never** waits on the network or an LLM.
   - Message texts come from a pool that `daily.insights` prefetches. If the pool is empty, templated text is used.
2. **Delivery is a notification only.**
   - No activity starts from the background: background-activity-launch hardening applies in 14, 15 and 17 [A14-TGT][A15-TGT][A17-TGT].
   - No full-screen intents, no sounds started by the app (Android 17 background audio hardening [A17-ALL]), and channel importance `DEFAULT`.
3. **No aggressive wakeups.**
   - No exact alarms, no `setAlarmClock`, no wake locks, no FCM, no FGS to stay alive, and no polling loops or `TIME_TICK`.
   - Expedited work only for event-driven checks, which are time-sensitive. Google's guidance: "only mark a task as expedited if it's time-sensitive", because "expedited tasks can drain more power". It also says to combine similar tasks so "the device only gets woken up once", which is why one `sweep.local` serves all local collectors [OPT-BATT].
4. **Bounded by the profile caps** (section 2.3), quiet hours, DND and per-rule cooldowns.

### 3.2 Trigger paths

**(1) Event-driven expedited checks.**

Event sources:
- NotificationListener (B1, I3).
- Geofence and activity-transition PendingIntents (C1, C2).
- Network-available PendingIntent (G2).
- Bluetooth connect manifest receivers (F2; for example car or headphones).
- `NEXT_ALARM_CLOCK_CHANGED` (J8).
- Sync completion with new data (K1, J2).
- The charging trigger (E2).
- Runtime screen, unlock and charging receivers (A3, A4, E2), **only while the process happens to be alive**. After `USER_PRESENT` the app may well be cached, and Android 14 then queues the broadcast [A14-ALL]. Unlock-moment JITAIs are therefore best-effort by design.

```kotlin
// Called from receivers (inside goAsync()), the listener service, and sync workers.
class JitaiScheduler @Inject constructor(
    private val wm: WorkManager,
    private val state: JitaiStateStore, // Room/DataStore: dirty flag, lastCheckAt, daily counters
    private val clock: Clock,
) {
    suspend fun requestCheck(reason: String) {
        state.markDirty(reason)                                   // always record intent
        if (!state.tryConsumeCheckBudget(clock.instant())) return // per-day cap + 2 min minimum gap
        val req = OneTimeWorkRequestBuilder<JitaiCheckWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("jitai")
            .build()                                              // no initial delay, no constraints
        wm.enqueueUniqueWork("agentle.jitai.check", ExistingWorkPolicy.KEEP, req)
    }
}

class JitaiCheckWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        repeat(3) {                              // fold events that arrive while running
            if (!state.clearDirtyIfSet()) return Result.success()
            engine.evaluateAndMaybeDeliver(now = clock.instant())  // idempotent, see 4.3
        }
        return Result.success()                  // the periodic tick catches any remainder
    }
    // Needed only when minSdk < 31: WorkManager may run expedited work as an FGS there.
    override suspend fun getForegroundInfo(): ForegroundInfo = jitaiForegroundInfo()
}
```

Why it is built this way:
- **Expedited** gives the lowest latency. Constraints are limited to network and storage, and no initial delay is allowed [AX-WORK WorkRequest.kt]. Neither is used here.
  - "While your app is in the foreground, quotas won't limit the execution of expedited work" [WM-DEFINE].
  - In the background, the expedited quota (30 / 15 / 10 / 10 / 5 min per 24 h by bucket [PWR]) covers 48 checks of 1 s or less many times over.
  - `RUN_AS_NON_EXPEDITED_WORK_REQUEST` degrades to a normal job when the quota is exhausted instead of dropping it [WM-DEFINE].
- **`KEEP` + dirty flag** coalesces bursts (for example 20 notifications in a minute) into one or two checks without unbounded `APPEND` chains. Missed edges are caught by the tick.
- **minSdk below 31:** `getForegroundInfo()` is mandatory for expedited work, because WorkManager "might run a foreground service on platform versions older than Android 12" [WM-DEFINE]. This is one more reason for minSdk 31.

**(2) Periodic tick.**
- `agentle.jitai.tick` is a `PeriodicWorkRequest` at 60 / 30 / 15 min, with flex = interval/3. The 5 min minimum flex [AX-WORK] spreads runs into batches.
- It evaluates time-based rules: sedentary bouts, late-night usage, upcoming calendar gaps, "no steps since X".
- In Doze it is deferred to maintenance windows [DOZE]. That is accepted: a stationary, screen-off device means the user is not receptive anyway.
- **15 min is the hard floor.** `MIN_PERIODIC_INTERVAL_MILLIS = 15 min` [AX-WORK], and the alarm docs give the same minimum for WorkManager [ALARMS]. Nothing in Agentle goes below it.

**(3) Daily jobs pinned to local time, without drift.**

```kotlin
fun dailyRequest(id: UUID?, localTime: LocalTime, zone: ZoneId, now: Instant): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
        .apply { if (id != null) setId(id) }
        .setNextScheduleTimeOverride(nextLocalOccurrence(localTime, zone, now).toEpochMilli())
        .build()

// nextLocalOccurrence: ZonedDateTime.of(date, localTime, zone) resolves DST gaps forward
// (02:30 in a spring-forward gap becomes 03:30) and takes the earlier offset in an overlap.

class DailySummaryWorker(...) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        deliverDailySummary()
        // Re-pin the next run (the override is cleared after each run):
        wm.updateWork(dailyRequest(id, settings.summaryTime(), ZoneId.systemDefault(), clock.instant()))
        return Result.success()
    }
}
```

- The source javadoc describes exactly this use case: "making a newsfeed worker run before the user wakes up every morning without drift. `ExistingPeriodicWorkPolicy.UPDATE` should be used … to avoid cancelling a currently-running worker".
- The same javadoc warns: "Work will almost never run at this exact time" [AX-WORK PeriodicWorkRequest.kt].
- The API exists since 2.9.0 [WM-REL].
- The reconciler re-pins all three daily jobs on `TIME_SET`, `TIMEZONE_CHANGED` and `TIMEZONE_OFFSET_CHANGED` (section 5). WorkManager 2.12's own `RescheduleReceiver` listens only to `BOOT_COMPLETED` [AX-WORK AndroidManifest.xml].

**(4) User-set reminders (optional feature): inexact alarms.**
- Use `AlarmManager.setWindow(RTC_WAKEUP, t, 15 min, pi)`.
  - For target 31+, windows under 10 min "are typically clipped to 600000" [ALARMS], and AOSP `DEFAULT_MIN_WINDOW` is 10 min.
  - The PendingIntent is immutable and explicit, to `ReminderReceiver`, which posts the notification directly.
- Use `setAndAllowWhileIdle` only for a reminder the user explicitly wants delivered during idle. It is inexact, and AOSP gives it the 7 per hour compat quota without the exact-alarm permission [AOSP-x AlarmManagerService.java].
- Alarms do not survive reboot or force-stop, so the reconciler re-arms them from Room. They do survive app updates ("This package is being updated; don't kill its alarms") [AOSP-x AlarmManagerService.java].

**Exact alarms: not in v1.**
- `USE_EXACT_ALARM` is "Granted automatically", has "Limited use cases" and is "Subject to an upcoming Google Play policy" [ALARMS].
  - The docs' examples of legitimate exact-alarm apps are alarm clocks and calendars. Agentle is neither, so it should not declare it.
  - The Help Center eligibility list is UNVERIFIED.
- `SCHEDULE_EXACT_ALARM` is user-granted and denied by default on fresh installs of apps targeting 33+ [ALARMS][A14-ALL]. If an opt-in "precise reminders" toggle is ever added:
  - Gate it on `canScheduleExactAlarms()` and fall back to `setWindow`.
  - Re-arm on `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.
  - Note that revoking the permission stops the app and cancels its exact alarms [ALARMS].
- The Android 17 `setExactAndAllowWhileIdle(type, t, tag, executor, OnAlarmListener)` [SDK37][A17-FEAT] is **not useful for Agentle**.
  - Listener-based exact alarms need no permission [ALARMS].
  - But AOSP drops exact listener alarms once the app becomes cached (`EXACT_LISTENER_ALARMS_DROPPED_ON_CACHED`, 10 s grace) [AOSP-x AlarmManager.java, AlarmManagerService.java].
  - It is designed for apps with a live process, such as socket keep-alive. Its exact Android 17 quota and permission behavior is UNVERIFIED, because the mirror predates Android 17.

### 3.3 Delivery gates (evaluated in order inside `evaluateAndMaybeDeliver`)

1. User kill switch or snooze (DataStore).
2. Notifications allowed and the JITAI channel not blocked (`NotificationManagerCompat.areNotificationsEnabled()`, channel importance). Without them, the nudge goes to an in-app inbox only.
3. Quiet hours (default 22:00-08:00 local; refined by J8 next alarm and sleep data).
4. Do Not Disturb: `getCurrentInterruptionFilter() != INTERRUPTION_FILTER_ALL` means **defer**, not drop.
5. Context: last activity transition `IN_VEHICLE` means defer.
6. Caps: per-profile daily cap, a 90 min minimum spacing between JITAIs, and per-rule cooldowns.
7. Idempotent delivery (section 4.3).

### 3.4 Scheduling summary

| Need | Mechanism | Never |
|---|---|---|
| React to a state change | expedited one-time `agentle.jitai.check` (`KEEP` + dirty flag), quota-degrading | exact alarm, FGS, wake lock |
| Time-based rules | periodic `agentle.jitai.tick`, 15 min or more | `Handler` loops, `TIME_TICK` |
| Daily summary / insights / features | 24 h periodic + `setNextScheduleTimeOverride` + `UPDATE` | `setRepeating` exact alarms |
| User reminder at a time | `setWindow` (10 min or more), optional `setAndAllowWhileIdle` | `setExact*`, `USE_EXACT_ALARM` |
| Text generation for nudges | prefetched by `daily.insights` | LLM call inside the check |

---

## 4. Duplicate-work prevention and token-refresh storm prevention

### 4.1 Layer 1: unique work and per-source mutual exclusion

- **One gateway.**
  - Every `enqueue*` call goes through `WorkScheduler`, using the names and policies in section 1.3. A lint-style unit test (section 7.1, T-WM-01) fails the build if any other class calls `WorkManager.enqueue*`.
- **Policies** [REF-EPWP][REF-EWP]:
  - `KEEP` on reconcile. "If there is existing pending (uncompleted) work with the same unique name, do nothing."
  - `UPDATE` for settings. It keeps the enqueue time and does not interrupt a running worker.
  - `CANCEL_AND_REENQUEUE` only when a periodic work must be reset on purpose (never on startup). `REPLACE` is deprecated for periodic work.
  - For one-time chains, use `APPEND_OR_REPLACE`, never `APPEND`. "When using APPEND with failed or cancelled prerequisites, newly enqueued work will also be marked as failed or cancelled."
- **Periodic and one-time runs of the same source have different unique names.** For example, `sync.ghealth` and `sync.ghealth.now` can overlap.
  - `SyncCoordinator` holds a per-source `Mutex`.
  - A periodic run that finds it locked returns `Result.success()` at once, because the running sync covers it.
  - A `*.now` run waits up to 30 s, because the user is looking at a spinner.
  - Agentle is single-process (no `android:process` on any component), so an in-process mutex is sufficient. This is an architectural invariant; see section 4.4.
- **Content-trigger chain.** The trigger re-arms itself, and a generation counter means no change is lost.

```kotlin
class ProviderTriggerWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        syncState.bumpChangeGeneration(provider, triggeredContentUris) // Room, atomic increment
        wm.enqueueUniqueWork("agentle.sync.$provider", ExistingWorkPolicy.KEEP, providerSyncRequest(provider))
        return Result.success()
    }
}

class ProviderSyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        // Re-arm FIRST, with REPLACE: if the trigger worker is still finishing (RUNNING), KEEP would
        // silently no-op and the trigger would be lost. Cancelling a finishing trigger is harmless:
        // it has already enqueued us.
        scheduler.armTrigger(provider, ExistingWorkPolicy.REPLACE)
        do {
            val gen = syncState.changeGeneration(provider)
            ingestIncrementally(provider)            // cursor + upsert, committed per page (4.2)
            syncState.markProcessed(provider, gen)
        } while (syncState.changeGeneration(provider) > gen && !isStopped)
        return Result.success()
    }
}
```

Rationale and limits for the trigger chain:
- WorkManager supports content triggers only on API 24+ and only for one-time work. JobScheduler documents that "trigger URIs can not be used in combination with `setPeriodic(long)` or `setPersisted(boolean)`" [REF-JIB].
- The platform's no-loss pattern, where changes are propagated to the next job "using the same job ID" [REF-JIB], does not carry over to a new WorkManager request. That is why Agentle relies on the generation counter plus the daily reconcile.
- WorkManager never persists any of its jobs ("We don't want to persist these jobs because we reschedule these jobs on BOOT_COMPLETED"). Content-trigger workers count against `contentUriTriggerWorkersLimit`, default **8** (`setContentUriTriggerWorkersLimit` raises it) [AX-WORK SystemJobInfoConverter.java, Configuration.kt]. Five providers fit.
- The triggering URIs are available as `triggeredContentUris` / `triggeredContentAuthorities` on `WorkerParameters` [AX-WORK].

### 4.2 Layer 2: idempotent writes and transactional state

- **Natural keys + upsert** (Room `@Upsert` or `INSERT … ON CONFLICT`). Examples:

  | Table | Unique key | Note |
  |---|---|---|
  | `notification_event` | `(sbn_key, post_time_ms, event)` | `event` ∈ posted/updated/removed |
  | `usage_event` | `(ts_ms, type, package, class)` | Identical tuples are true duplicates |
  | `screen_interval` | `(start_ms, source)` | `source` ∈ receiver/usage_events; usage_events wins |
  | `provider_row` | `(provider, provider_id)` + `content_hash` | Hash detects edits where the provider has no modified column (Calendar) |
  | `geofence_transition` | `(request_id, transition, trigger_ts_ms)` | |
  | `activity_transition` | `(activity, transition, event_ts_ms)` | `elapsedRealtimeNanos` converted once to wall time |
  | `snapshot` | `(kind, bucket_start_ms)` with 5 min buckets | Re-running a sweep in the same bucket upserts |
  | Health tables | as in DOC05 section 7.5 | Replace-window semantics |

- **Watermarks in the same transaction as the data**. Never advance a cursor in a separate transaction:

```kotlin
db.withTransaction {
    dao.upsertAll(page.rows)
    syncStateDao.advance(source, page.nextCursor)
}
```

- **Small pages** (500 rows or fewer) keep transactions short, survive quota stops (`STOP_REASON_QUOTA`, `STOP_REASON_TIMEOUT`) and process death, and keep memory flat under the Android 17 memory limits [A17-ALL].
  - Re-processing an uncommitted page is a no-op.
- **Event time, not "now".** Keys use the source's timestamp. "Now" is only used for `ingested_at`.
- **A single writer for high-rate streams** (notification listener). Use `Dispatchers.IO.limitedParallelism(1)` + batched transactions, so concurrent receivers cannot cause `SQLITE_BUSY` bursts.

### 4.3 Layer 3: idempotent JITAI decisions and notifications

```kotlin
val window = windowStart(rule, now)                              // e.g. floor to the rule's window size
val decisionId = "${rule.id}:${window.epochSecond}"              // deterministic
val row = decisions.get(decisionId)
if (row?.status == DELIVERED) return
if (row == null && !decisions.insertIfAbsent(decisionId, DECIDED)) return // UNIQUE(decision_id), lost race
notificationManager.notify("jitai", notificationIdFor(decisionId), build(rule, window))
decisions.markDelivered(decisionId)
```

How this behaves on re-runs and crashes:
- Re-running the same check, or retrying after a crash between `notify` and `markDelivered`, re-posts **the same tag and ID**. The system replaces the notification instead of duplicating it.
- `notificationIdFor()` uses a Room-allocated integer per decision. It avoids `hashCode()` collisions across rules.
- Caps and cooldowns are counted from `decisions`, which is transactional, not from in-memory counters.

### 4.4 Token-refresh storm prevention (single-flight)

**Why it matters here:**
- After a Doze maintenance window or a boot, the sweep, Health Connect sync, Google Health sync and insights jobs can all start at once with an expired access token.
- **ChatGPT refresh tokens are single-use and rotate.** Each refresh returns "a replacement refresh token", and reuse yields the terminal `refresh_token_reused` error [DOC06].
  - Two parallel refreshes therefore **log the user out**. They do not merely waste a request.
- Google Health flow A (`AuthorizationClient`) keeps no refresh token on the device. Each sync calls `authorize()` [DOC05].
  - Parallel silent authorizations are wasteful.
  - Whether `authorize()` works from a worker without an Activity is **UNVERIFIED** [DOC05].
  - A result with a resolution means "user action needed", never "retry".
- Retry amplification can multiply this: OkHttp connection retries, `Authenticator` loops and WorkManager retries.

**Design.** One `TokenManager` per provider (Hilt `@Singleton`):

```kotlin
@Singleton
class TokenManager @Inject constructor(
    private val store: EncryptedTokenStore,   // one encrypted blob, atomic replace (DOC06)
    private val api: RefreshApi,              // dedicated OkHttpClient: retryOnConnectionFailure(false), callTimeout 30 s
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun accessToken(): String = mutex.withLock {
        val t = store.read() ?: throw NeedsReauth
        if (t.needsReauth) throw NeedsReauth
        if (Duration.between(clock.instant(), t.expiresAt) > SKEW) return t.access   // SKEW = 120 s
        refreshLocked(t).access
    }

    /** From the OkHttp Authenticator, with the token that just got 401. */
    suspend fun onUnauthorized(failedAccess: String): String? = mutex.withLock {
        val t = store.read() ?: return null
        if (t.needsReauth) return null
        if (t.access != failedAccess) return t.access   // someone already refreshed: just retry with it
        refreshLocked(t).access
    }

    private suspend fun refreshLocked(t: Tokens): Tokens = when (val r = api.refresh(t.refresh)) {
        is RefreshResult.Ok        -> t.rotated(r).also { store.writeAtomically(it) } // persist BEFORE use
        is RefreshResult.Terminal  -> { store.markNeedsReauth(r.code); throw NeedsReauth } // invalid_grant, refresh_token_reused, …
        is RefreshResult.Transient -> throw RetryableAuthError(r)                          // network / 5xx / 429
    }
}
```

The rules around it:

1. **Single-flight.** Every caller waits on the same `Mutex`. The waiters then see the rotated token through the version check (`t.access != failedAccess`), so N concurrent 401s produce **one** refresh.
2. **Persist before use.** Store the rotated set atomically before any request uses it [DOC06].
3. **One retry per request.** The `Authenticator` returns `null` when `response.priorResponse != null`.
4. **The refresh POST is never auto-retried.** `retryOnConnectionFailure(false)` on its client [DOC06].
5. **Circuit breaker.**
   - A terminal error persists `NEEDS_REAUTH`.
   - Every worker checks it first and returns `Result.success()`, **not** `retry()` and not `failure()`, so no WorkManager retry storm can start.
   - One deduplicated "Reconnect" notification is posted with a fixed ID.
6. **Transient errors.**
   - Bounded in-run retries: at most 3, full jitter, `Retry-After` honored [DOC05].
   - Then `Result.retry()` (exponential backoff 30 s → 5 h [AX-WORK]).
   - Periodic workers may instead set `setNextScheduleTimeOverride(now + retryAfter)` via `UPDATE`. The javadoc names "custom retry behavior" as a use [AX-WORK].
7. **Rate limiter per provider.** A token bucket in front of the HTTP client (Google Health: 300 req/min/user ceiling [DOC05]).
8. **No proactive refresh timers.**
   - Refresh happens lazily, when a job needs a token.
   - A ChatGPT refresh token lives 30 days and is renewed on use [DOC06]. If the app is unused for more than 30 days, re-auth is expected. No keep-alive job runs just to extend a credential.
9. **Single-process invariant.** If a second process is ever added (for example `work-multiprocess`), the mutex must become cross-process (file lock), or token handling must move behind one process.

### 4.5 Other duplicate-work traps (checklist)

- `updateWork` while running: the current run keeps the old spec, and the next uses the new one [REF-EPWP].
  - Use **2.12.0 or later**. 2.12.0-rc01 fixed lifecycle hooks and telemetry shifting generation during `updateWork` [WM-REL].
- **WorkManager on-demand init.**
  - Use `Configuration.Provider` with `HiltWorkerFactory`, and remove the default initializer.
  - `Application.onCreate()` **must touch WorkManager** (the reconciler does). `ForceStopRunnable` runs at init and reschedules after a force-stop [AX-WORK ForceStopRunnable.java].
  - Set `setInitializationExceptionHandler` and `setSchedulingExceptionHandler` to log. 2.12 also "retries initialization on generic SQLiteException" [WM-REL].
- **Network constraint correctness on Android 15+.** 2.11.1 and 2.12.0 fixed constraints passing "while connectivity is blocked on Android 15 and above" and background network failures [WM-REL]. Pin 2.12.0.
- **Never fire-and-forget network calls from receivers or `GlobalScope`.** On Android 15, "apps that start a network request outside of a valid process lifecycle receive an exception" [A15-ALL]. All network runs inside workers.
- **Never use self-broadcasts as IPC or as a wake mechanism.** On Android 17 they "no longer raise the process's priority or keep a cached process from being frozen" [A17-SUM]. Use in-process `Flow`s.

---

## 5. Boot / upgrade / process-death recovery

### 5.1 What survives what

| Event | WorkManager work | Alarms | Geofences | Activity-transition requests | Network PendingIntent callback | Runtime receivers | Notification listener |
|---|---|---|---|---|---|---|---|
| **Reboot** | Rescheduled by WorkManager. Its jobs are `setPersisted(false)`; `RescheduleReceiver` handles `BOOT_COMPLETED` [AX-WORK] | **Lost**: "all alarms are canceled when a device shuts down" [ALARMS] | **Lost**: "re-register … after … boot" [GEOFENCE] | UNVERIFIED (docs silent): treat as lost, re-request | UNVERIFIED (docs silent): treat as lost, re-register | Lost (new process) | Rebound by the system if access is granted |
| **App update** (`MY_PACKAGE_REPLACED`) | Kept. AOSP JobScheduler cancels jobs on `PACKAGE_FULLY_REMOVED` and force-stop, not on replace [AOSP-x JobSchedulerService.java] | Kept ("This package is being updated; don't kill its alarms"); exact alarms dropped if the permission was lost [AOSP-x AlarmManagerService.java] | Kept (not in the re-register list [GEOFENCE]); re-add anyway (idempotent) | Re-request (idempotent) | Re-register (idempotent) | Lost (process killed) | Rebound |
| **Force-stop** (user or `am force-stop`) | **Cancelled.** Rescheduled at next WorkManager init by `ForceStopRunnable`, which detects it via `ApplicationExitInfo` `REASON_USER_REQUESTED` on API 30+ [AX-WORK] | **Cancelled** (`ACTION_PACKAGE_RESTARTED`) [AOSP-x AlarmManagerService.java] | **Dead**. Android 15 "cancels all pending intents when the app enters the stopped state" [A15-ALL] | Dead (same) | Dead (same) | Lost | AOSP: NMS handles `ACTION_PACKAGE_RESTARTED` by cancelling the app's own posted notifications and calling `rebindServices`, and `onBindingDied` re-registers after 10 s [AOSP-x NotificationManagerService.java, ManagedServices.java]. Expect a rebind; confirm in E5 |
| **Leaving the stopped state** (user opens the app or a widget) | `ForceStopRunnable` reschedules | Re-arm | Re-add | Re-request | Re-register | Re-register | — |
| **Process death** (low-memory kill, `am kill`) | Kept. A running worker is stopped and re-run later, so idempotency matters | Kept | Kept | Kept | Kept | **Lost** → re-registered in `Application.onCreate` | System rebinds. AOSP `onBindingDied` → rebind after 10 s, one attempt pending at a time [AOSP-x ManagedServices.java] |
| **Task Manager "Stop"** (Android 13+; test with `adb shell cmd activity stop-app`) | Kept: "Scheduled jobs execute at their scheduled time" [FGS-STOP]. On the next start, `ForceStopRunnable` sees `REASON_USER_REQUESTED` and reschedules everything anyway, which is harmless [AX-WORK] | Kept: "Alarms go off at their scheduled time or time window" [FGS-STOP] | UNVERIFIED (docs silent) | UNVERIFIED | UNVERIFIED | Lost ("The system removes your app from memory") | UNVERIFIED |
| **Clear data** | Gone (database deleted) | Gone | **Lost** [GEOFENCE] | Lost | Lost | Lost | Access remains; data gone |
| **Time / time-zone change** | Not rescheduled by WorkManager (2.12 `RescheduleReceiver` listens to `BOOT_COMPLETED` only [AX-WORK]) → Agentle re-pins daily overrides | Elapsed-realtime alarms unaffected; local-time reminders recomputed | — | — | — | — | — |
| **Exact-alarm permission revoked** (only with the opt-in) | — | App stopped and exact alarms cancelled [ALARMS] | — | — | — | — | — |

Notes:
- On Android 15+, leaving the stopped state also delivers `BOOT_COMPLETED`, "providing an opportunity to re-register any pending intents" [A15-ALL].
- `ApplicationStartInfo.wasForceStopped()` (API 35) confirms a force-stop for logging [A15-ALL].
- The Task Manager "Stop" button appears only while an app runs an FGS. For Agentle that means only during the optional "Import now" (section 1.4).
  - The system sends no callback. The exit reason is `REASON_USER_REQUESTED` [FGS-STOP], the same as a force-stop.
  - So gap labelling uses `wasForceStopped()` on API 35+ to tell them apart (`FORCE_STOP` vs `USER_STOP`). Below 35, the label is `USER_STOP_OR_FORCE_STOP`.
  - WorkManager makes the same conflation: on API 30+, any `REASON_USER_REQUESTED` exit newer than its last check triggers `rescheduleEligibleWork()` [AX-WORK ForceStopRunnable.java].

### 5.2 Entry points

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Senders are system_server (protected broadcasts): exported="false" is enough, because AOSP
     lets ROOT/SYSTEM uids reach unexported components (ActivityManager.canAccessUnexportedComponents).
     WorkManager's own RescheduleReceiver is exported="false" with BOOT_COMPLETED. -->
<receiver android:name=".platform.SystemEventReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        <action android:name="android.intent.action.TIME_SET" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
        <action android:name="android.intent.action.LOCALE_CHANGED" />
        <action android:name="android.app.action.NEXT_ALARM_CLOCK_CHANGED" />
    </intent-filter>
</receiver>

<!-- The sender is the Bluetooth stack UID (not system): the receiver must be exported.
     Only the protected ACL actions are accepted. Headset/A2DP state is read at event time via
     BluetoothAdapter.getProfileConnectionState(), because those CONNECTION_STATE_CHANGED actions
     are not declared protected in frameworks/base core/res/AndroidManifest.xml. -->
<receiver android:name=".platform.BluetoothEventReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
        <action android:name="android.bluetooth.device.action.ACL_DISCONNECTED" />
    </intent-filter>
</receiver>
```

Why these receiver settings:
- `canAccessUnexportedComponents`: [AOSP-x ActivityManager.java]. WorkManager's `RescheduleReceiver`: [AX-WORK AndroidManifest.xml].
- Protected status: `ACL_CONNECTED`/`DISCONNECTED`, `SCREEN_ON`/`OFF`, `USER_PRESENT`, power, battery, airplane, ringer, `HEADSET_PLUG`, `INTERRUPTION_FILTER_CHANGED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `LOCALE_CHANGED` and `NEXT_ALARM_CLOCK_CHANGED` are all `<protected-broadcast>` in AOSP `core/res/AndroidManifest.xml` [AOSP-x].
- The docs warn: "If you export the broadcast receiver, other apps could send unprotected broadcasts to your app" [BCAST]. Every receiver therefore checks `intent.action` against its allow-list.

```kotlin
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Reason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> Reason.UPDATE
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Reason.CLOCK
            Intent.ACTION_LOCALE_CHANGED -> Reason.LOCALE
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> Reason.NEXT_ALARM
            else -> return                                   // allow-list
        }
        val pending = goAsync()
        appScope.launch {
            try {
                eventLog.record(reason, intent)             // one small Room insert
                reconciler.request(reason)                  // enqueues agentle.reconcile (expedited, KEEP)
            } finally { pending.finish() }
        }
    }
}
```

Rules for these entry points:
- **No FGS from `BOOT_COMPLETED`.** On target 35+, `BOOT_COMPLETED` receivers cannot start `dataSync`, camera, `mediaPlayback`, `phoneCall`, `mediaProjection` or microphone FGS [A15-TGT]. Agentle starts none.
- **No `LOCKED_BOOT_COMPLETED` / direct boot.** Room, DataStore and WorkManager live in credential-encrypted storage. WorkManager's components are `directBootAware="false"` [AX-WORK]. Agentle waits for `BOOT_COMPLETED`.
- **Process start (`Application.onCreate`).**
  - Re-register all runtime receivers and callbacks (A3, A4, E2-E4, F1, G2, G3, I1 offset, I5, I6, J3).
  - Then call `reconciler.request(PROCESS_START)`. This also initializes WorkManager, which lets `ForceStopRunnable` reschedule after a force-stop.

```kotlin
// Application.onCreate (main process only; Agentle has one process)
val screenFilter = IntentFilter().apply {
    addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
}
// RECEIVER_EXPORTED: USER_PRESENT is sent by SystemUI (not a system uid) and would be dropped
// for a NOT_EXPORTED receiver (section 6.11). All three actions are protected broadcasts.
ContextCompat.registerReceiver(this, screenReceiver, screenFilter, ContextCompat.RECEIVER_EXPORTED)
```

### 5.3 `ScheduleReconciler` (idempotent; safe to run any number of times)

1. **Read the desired state.** Profile, enabled collectors, granted permissions and special access, and user times from DataStore.
2. **Work.**
   - For every desired periodic work: `enqueueUniquePeriodicWork(name, KEEP, request)`.
   - For daily works, re-pin the override with `UPDATE` when the reason is `CLOCK`, `BOOT` or `UPDATE`.
   - Cancel works whose collector was disabled or whose permission is gone (`cancelUniqueWork`).
   - Arm content triggers (`KEEP`), and the charging trigger (`KEEP`) if the device is not charging.
3. **PendingIntent registrations** (when permitted; all idempotent):
   - Geofences: `addGeofences` with stable request IDs replaces existing ones.
   - Activity transitions: `requestActivityTransitionUpdates` with the same PendingIntent. Replace-on-re-request is UNVERIFIED; [AR] only documents removal by the same PendingIntent. If tests show duplicate callbacks, call `removeActivityTransitionUpdates(pi)` first.
   - Network callback: `registerNetworkCallback(request, pi)`. "If there is already a request for this Intent registered … it will be removed and replaced by this one" [REF-CM].
4. **Alarms.** Re-arm stored user reminders (`setWindow`).
5. **Notification listener.**
   - If access is granted but no `onListenerConnected` has been seen for more than 10 min, call `NotificationListenerService.requestRebind(component)`. It "is the only one that is safe to call before `onListenerConnected()` or after `onListenerDisconnected()`" [REF-NLS].
   - Rate-limit this to once per hour.
6. **Gap detection.**
   - Each collector writes a `heartbeat(collector, last_success_at)`. The reconciler opens a `collection_gap(collector, from, to, cause)` row when `now - last_success_at > 2 × interval`.
   - `cause` comes from the boot, force-stop, Task Manager stop (see the section 5.1 notes) or update reason, `ApplicationExitInfo`, or the bucket.
   - Feature derivation treats gaps as **missing**, never as zero.
7. **Backfill where history exists.**
   - `UsageEvents` (A1-A4; a few days only [REF-USM]); `DEVICE_SHUTDOWN`/`DEVICE_STARTUP` for boot gaps (API 29).
   - Health Connect (30 days without the history permission [HC-READ]); the Google Health API (unlimited [DOC05]).
   - `NetworkStatsManager`, and the providers (stateful, diffed).
   - Non-backfillable: receiver hints, battery events, sensors. These stay marked as gaps.
8. **Exit diagnostics.**
   - `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 16)` (API 30). Persist new entries: reason, description, timestamp.
   - Android 17 memory-limit kills show as `REASON_OTHER` with the description containing `"MemoryLimiter:AnonSwap"` [A17-ALL].
   - Surface counts on a debug screen.

---

## 6. Platform facts (reference digest with sources)

### 6.1 WorkManager 2.12.0

| Fact | Value | Source |
|---|---|---|
| Latest stable | **2.12.0**, released 2026-09-23. No newer alpha, beta or RC listed. Requires compileSdk 33+ | [WM-REL] |
| minSdk | 24 (raised from 23 in 2.12.0) | [WM-REL] |
| New in 2.12 | `androidx.work:work-analytics` (experimental `WorkMetricsInfo`: durations, stop-reason counts, run attempts); experimental execution and scheduling event listeners on `Configuration.Builder`; initialization retries on `SQLiteException`; fix for periodic work not rescheduled after an uncaught exception; Android 15 background-network fixes | [WM-REL] |
| Periodic minimum | interval 15 min (`MIN_PERIODIC_INTERVAL_MILLIS`), flex 5 min (`MIN_PERIODIC_FLEX_MILLIS`). Values are coerced up, not rejected | [AX-WORK PeriodicWorkRequest.kt, WorkSpec.kt] |
| Backoff | default 30 s, min 10 s, max 5 h; exponential or linear | [AX-WORK WorkRequest.kt] |
| Expedited | `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST \| DROP_WORK_REQUEST)`. "Expedited jobs only support network and storage constraints"; "Expedited jobs cannot be delayed"; "PeriodicWorkRequests cannot be expedited"; retries and delayed runs are not submitted as expedited | [AX-WORK WorkRequest.kt, PeriodicWorkRequest.kt, SystemJobInfoConverter.java] |
| Expedited before API 31 | may run as an FGS, so `getForegroundInfo()` is required | [WM-DEFINE] |
| Expedited quota | separate from regular jobs; bucket-based; not applied while the app is in the foreground | [WM-DEFINE][PWR] |
| Unique policies, one-time | `REPLACE`, `KEEP`, `APPEND` (failure propagates), `APPEND_OR_REPLACE` | [REF-EWP][WM-MANAGE] |
| Unique policies, periodic | `KEEP`, `UPDATE` (keeps enqueue time; no interruption), `CANCEL_AND_REENQUEUE`, `REPLACE` (deprecated, same as `CANCEL_AND_REENQUEUE`) | [REF-EPWP] |
| API history | `updateWork`/`UPDATE`/generation (2.8.0); `setNextScheduleTimeOverride`, `getStopReason`, `WorkInfo.nextScheduleTimeMillis` (2.9.0); `Constraints.setRequiredNetworkRequest` (2.10.0, honored on API 28+); `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` (2.10.0-alpha04); `TestDriver.stopRunningWorkWithReason` (2.11.0-beta01) | [WM-REL][AX-WORK Constraints.kt][WM-UPDATE] |
| Content-URI triggers | `addContentUriTrigger` (API 24), `setTriggerContentUpdateDelay`, `setTriggerContentMaxDelay`; at most `contentUriTriggerWorkersLimit` (default 8) enqueued at once; never persisted | [AX-WORK Constraints.kt, Configuration.kt, SystemJobInfoConverter.java][REF-JIB] |
| `setForeground` | must pass the FGS type at runtime (target 34+) and declare it on `SystemForegroundService` via a `tools:node="merge"` manifest entry; can throw `ForegroundServiceStartNotAllowedException` / `IllegalStateException` | [WM-LONG][WM-DEFINE] |
| Persistence and rescheduling | jobs are `setPersisted(false)`. `RescheduleReceiver` (`BOOT_COMPLETED` only, `enabled="false"` until work exists, `directBootAware="false"`). `ForceStopRunnable` detects a force-stop via `ApplicationExitInfo REASON_USER_REQUESTED` (API 30+) or a sentinel alarm, then reschedules | [AX-WORK AndroidManifest.xml, ForceStopRunnable.java] |
| Diagnostics | `adb shell am broadcast -a "androidx.work.diagnostics.REQUEST_DIAGNOSTICS" -p "<pkg>"` (receiver guarded by `android.permission.DUMP`); `adb shell dumpsys jobscheduler` | [WM-DEBUG][AX-WORK AndroidManifest.xml] |
| Test APIs | `WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)`, `SynchronousExecutor`, `TestDriver.setAllConstraintsMet` / `setInitialDelayMet` / `setPeriodDelayMet` / `stopRunningWorkWithReason`, `TestListenableWorkerBuilder` (`setRunAttemptCount`, `setTriggeredContentUris`, `setTriggeredContentAuthorities`, `setNetwork`, `setForegroundUpdater`, `setProgressUpdater`, `setWorkerFactory`), `Configuration.Builder.setClock` (public; `TestClock` is `@RestrictTo(LIBRARY_GROUP)`, so don't use it) | [WM-TESTINT][WM-TESTIMPL][REF-TD][REF-TLWB][AX-WORK] |

### 6.2 JobScheduler, standby buckets and Doze

- **Bucket quotas:** see the table in section 2.1 [PWR].
- **Doze restrictions** [DOZE]:
  - Network is suspended, wake locks are ignored, and standard alarms (including `setExact()` and `setWindow()`) are deferred to the next maintenance window.
  - Jobs and syncs are deferred.
  - Maintenance windows become "less frequent" over time.
  - Use `setAndAllowWhileIdle()` for an alarm that must fire in Doze.
  - FCM high-priority messages are the push path (not used: Agentle has no backend).
- **Android 16** [A16-ALL][A16-FEAT]:
  - Quota now applies in the active bucket, to TOP-started jobs that continue after the app leaves, and to jobs running alongside an FGS. Test overrides: `am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_TOP_STARTED_JOBS|OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS <pkg>`.
  - New `STOP_REASON_TIMEOUT_ABANDONED`. "If you're using WorkManager … you aren't impacted".
  - `setImportantWhileForeground` is ignored.
  - New `JobScheduler.getPendingJobReasons` / `getPendingJobReasonsHistory`.
- **Android 17:** `getPendingJobReasonStats(jobId)` returns `Map<Integer, Duration>` [A17-FEAT][SDK37].
  - WorkManager hides job IDs, so these are debug-build tools. Use them with `JobScheduler.getAllPendingJobs()` for Agentle's own jobs.
- **Android 14** [A14-TGT][A14-ALL]:
  - `onStartJob`/`onStopJob` timeouts cause ANRs, and repeated job ANRs put the app in the restricted bucket.
  - `ACCESS_NETWORK_STATE` is required for network constraints. WorkManager's manifest declares it [AX-WORK].
- **Network in `rare` and `restricted`.** [PWR] lists network as "Disabled" for these buckets. In AOSP this is the app-standby network block, and it does not stop network-constrained jobs:
  - `ConnectivityController.evaluateStateLocked` asks NetworkPolicyManager to exempt the UID from the standby chain (`setAppIdleWhitelist(uid, true)`) when a job with a network constraint "would be ready" apart from connectivity. It revokes the exemption when no such job is ready. The source comment says: "Tell NetworkPolicyManager not to block a UID's network connection if that's the only thing stopping a job from running" [AOSP-x ConnectivityController.java].
  - Regular jobs can bypass only `BLOCKED_REASON_APP_BACKGROUND` (`UNBYPASSABLE_BG_BLOCKED_REASONS`). Doze and Battery Saver network blocks therefore keep a regular network job waiting.
  - Expedited jobs can also bypass the app-standby, Battery Saver, app-background and Doze blocks (`UNBYPASSABLE_EJ_BLOCKED_REASONS`) [AOSP-x ConnectivityController.java].
  - Consequence: a WorkManager job with `NetworkType.CONNECTED` can still sync in `rare`, within its quota. Network calls made outside workers get nothing, which matches the Android 15 rule [A15-ALL].
  - Agentle limits `rare` and `restricted` to the essential set (section 2.4) as a choice to save quota, not because network is impossible.
  - The mirror is about Android 16 QPR level, so Android 17 behavior is assumed to be the same (UNVERIFIED).
- **Restricted bucket outside quota.** AOSP adds dynamic constraints to every job of a `restricted` app: charging, battery-not-low, device idle and, for network jobs, connectivity (`DYNAMIC_RESTRICTED_CONSTRAINTS`) [AOSP-x JobStatus.java].
  - A job is ready when it is within quota *or* all dynamic constraints are met (`isReady`) [AOSP-x JobStatus.java].
  - A restricted job that is out of quota must use an unmetered network (`isStrictSatisfied`) [AOSP-x ConnectivityController.java].
  - So charging-gated heavy local jobs (`daily.features`, `maint.weekly`) can still run overnight in `restricted`, while the phone is idle and charging with a battery that is not low. Network jobs also need an unmetered network.
- **Flex policy (`FlexibilityController`).** The AOSP shell exposes `cmd jobscheduler enable-flex-policy --option <battery-not-low|charging|connectivity|idle>` (the option can repeat), `disable-flex-policy` and `reset-flex-policy` [AOSP-x JobSchedulerShellCommand.java]. The source shows what the policy does [AOSP-x FlexibilityController.java, JobStatus.java]:
  - **Flexible constraints:** charging, battery-not-low and idle. For jobs that require a network without naming a transport, it adds a transport preference: prefer Wi-Fi or Ethernet, avoid cellular or satellite.
  - **Which jobs it holds:** a job gets an implicit `CONSTRAINT_FLEXIBLE` if it lacks any of those constraints. It is skipped for expedited jobs, user-initiated jobs and a job's first reschedule.
  - **How the hold relaxes:** the number of flexible constraints required drops as the job ages, at 50/60/70/80 % of its window for default priority.
  - **Window for jobs without a deadline:** a fallback window of 12 h at default priority (1 h max, 6 h high, 24 h low, 48 h min), extended by a per-app score.
  - **Exempt jobs:** jobs of the TOP app, and default-or-higher priority jobs of apps at bound-foreground-service level or above.
  - WorkManager calls neither `JobInfo.Builder.setPriority` nor `setOverrideDeadline` [AX-WORK SystemJobInfoConverter.java]. Its regular work therefore falls into the default-priority 12 h fallback class.
  - **The AOSP default is off** (`DEFAULT_APPLIED_CONSTRAINTS = 0`; device-config key `fc_applied_constraints`). `adb shell dumpsys jobscheduler` prints `fc_applied_constraints=…` under `FlexibilityController:`, so a tester can check any device. Whether Android 16/17 production devices enable the policy is **UNVERIFIED**.
  - **If a device enables it:** a regular periodic tick may slip by hours on an unplugged phone in use. JITAI checks are event-driven *expedited* work and are exempt, so the tick stays a fallback.
  - Section 7.2 disables the policy for deterministic runs, and E1c runs with it enabled.

### 6.3 Foreground services (targetSdk 34+)

- **Types are required.** Declare the type in the manifest, the per-type `FOREGROUND_SERVICE_*` permission and `FOREGROUND_SERVICE`, and the runtime prerequisites. The prerequisites are checked at `startForeground()`/`setForeground()` [FGS-TYPES][WM-LONG]. Per-type details are in section 1.4.
- **Background-start restriction** (target 31+): `ForegroundServiceStartNotAllowedException`, except for the listed exemptions [FGS-BG]. Exemptions relevant to Agentle:
  - The app is visible.
  - The user acts on a notification or widget.
  - An exact alarm the user requested fires.
  - A geofencing or activity-recognition transition event arrives.
  - `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` or `MY_PACKAGE_REPLACED` (with the type restrictions below).
  - `TIMEZONE_CHANGED`, `TIME_CHANGED`, `LOCALE_CHANGED`.
  - The user turned off battery optimization.
  - `SYSTEM_ALERT_WINDOW`, which on target 35+ also needs a visible overlay.
- **While-in-use (WIU) types** (location, camera, microphone; `health` with body sensors):
  - These cannot be created from the background even when an exemption applies, except in the WIU exemption list: system component, widget or notification interaction, PendingIntent from a visible app, and others.
  - A location FGS started from the background needs `ACCESS_BACKGROUND_LOCATION` [FGS-BG].
- **Time limits:**
  - `dataSync` and `mediaProcessing`: 6 h per 24 h each. Then `Service.onTimeout(int,int)` (API 35) gives a few seconds to stop, after which the app ANRs.
  - Further `dataSync` starts fail ("Time limit already exhausted for foreground service type dataSync") until the user brings the app to the foreground.
  - Test: `adb shell device_config put activity_manager data_sync_fgs_timeout_duration <ms>` with `am compat enable FGS_INTRODUCE_TIME_LIMITS <pkg>` [FGS-TIMEOUT][A15-TGT].
  - `shortService`: about 3 min [FGS-TYPES].
- **`BOOT_COMPLETED`** (target 35+): cannot start `dataSync`, camera, `mediaPlayback`, `phoneCall`, `mediaProjection` or microphone FGS. Test: `am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS <pkg>` [A15-TGT].
- **Android 16:** jobs running alongside an FGS consume job quota [A16-ALL]. An FGS is no longer a way to make jobs "free".

### 6.4 Battery-optimization exemption

See section 2.5 (decision: not requested). Test hook: `adb shell dumpsys deviceidle whitelist +<pkg>` / `-<pkg>` [AOSP-x DeviceIdleController.java].

### 6.5 Android 14 / 15 / 16 / 17 changes that affect background execution

| Version | Change | Scope | Impact on Agentle | Source |
|---|---|---|---|---|
| 14 | `SCHEDULE_EXACT_ALARM` denied by default (fresh installs, target 33+) | all | No exact alarms in v1 | [A14-ALL][ALARMS] |
| 14 | Context-registered broadcasts queued while cached; may be merged | all | Receiver events are hints; backfill from `UsageEvents` | [A14-ALL][BCAST] |
| 14 | Cached-app enforcement: background work disallowed shortly after caching | all | All work in WorkManager or bound services | [A14-ALL] |
| 14 | Repeated job ANRs lead to the restricted bucket | all | Keep `onStartJob` paths trivial (WorkManager does) | [A14-ALL] |
| 14 | FGS types required; `ACCESS_NETWORK_STATE` for network constraints; mutable PendingIntent with implicit intent throws; runtime receivers must declare their export state (exception: system broadcasts only) | target 34 | Sections 1.4, 6.9, 6.11 | [A14-TGT] |
| 15 | Force-stop cancels **all** PendingIntents; `BOOT_COMPLETED` is redelivered on leaving the stopped state; `ApplicationStartInfo.wasForceStopped()` | all | Reconciler re-registers geofences, transitions and network callbacks | [A15-ALL] |
| 15 | Network requests outside a valid lifecycle throw | all | Network only inside workers | [A15-ALL] |
| 15 | OTP redaction for untrusted notification listeners | all | B1 loses OTP text (fine) | [A15-ALL] |
| 15 | `dataSync`/`mediaProcessing` 6 h/24 h; `BOOT_COMPLETED` FGS-type restrictions; `SYSTEM_ALERT_WINDOW` needs a visible overlay; DND changes go through `AutomaticZenRule` | target 35 | Section 1.4; Agentle never changes DND | [A15-TGT] |
| 16 | Job quota optimizations (active bucket, TOP-started, FGS-concurrent); `STOP_REASON_TIMEOUT_ABANDONED`; `setImportantWhileForeground` ignored; ordered-broadcast priority no longer global | all | Budgets (section 2) assume quota everywhere | [A16-ALL] |
| 16 | `scheduleAtFixedRate` runs at most one missed execution on return | target 36 | Do not use `ScheduledExecutorService` for collection | [A16-TGT] |
| 16 | `BODY_SENSORS` replaced by `android.permission.health.*`; `READ_HEALTH_DATA_IN_BACKGROUND` replaces `BODY_SENSORS_BACKGROUND`; `health` FGS needs the granular permission | target 36 | Only relevant if a `health` FGS ever ships | [A16-TGT][FGS-TYPES] |
| 16 | `MediaStore.getVersion()` unique per app | target 36 | Store and compare; never parse (H5) | [A16-TGT] |
| 17 | App memory limits based on device RAM; kills reported as `REASON_OTHER` with "MemoryLimiter:AnonSwap"; `am memory-limiter ignore\|manual\|status` | all | Page every query; log `ApplicationExitInfo` | [A17-ALL] |
| 17 | Background audio hardening (playback, focus and volume changes fail silently or with `AUDIOFOCUS_REQUEST_FAILED`; target 37 needs a WIU FGS or exact-alarm + `USAGE_ALARM`) | all / target 37 | Never touch audio from the background; reading volume is fine | [A17-ALL][A17-TGT] |
| 17 | SMS OTP protection: 3 h delay with broadcast withheld and provider queries filtered (WebOTP for all apps; standard SMS for target 37) | all / target 37 | H3 re-reads a 6 h window | [A17-ALL][A17-TGT] |
| 17 | "Optimized delivery of self-broadcasts": no longer raise priority or prevent freezing | all | No self-broadcast IPC | [A17-SUM] (details UNVERIFIED) |
| 17 | "Restricted message access": "Most apps now cannot access end-to-end encrypted messages" | all | Limits B1 and H3 content | [A17-SUM] (details UNVERIFIED) |
| 17 | BAL hardening extended to `IntentSender`; move to `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` | target 37 | JITAIs never start activities | [A17-TGT] |
| 17 | `ACCESS_LOCAL_NETWORK` mandatory for LAN access | target 37 | Not applicable (no LAN traffic) | [A17-TGT] |
| 17 | CP2: `ACCOUNT_NAME`/`ACCOUNT_TYPE` removed from the `Data` view; strict SQL without `READ_CONTACTS` | target 37 | H4 query changes | [A17-TGT] |
| 17 | New: `setExactAndAllowWhileIdle(…, OnAlarmListener)`; `JobDebugInfo` `getPendingJobReasonStats`; `ACTION_TIMEZONE_OFFSET_CHANGED` | feature | Sections 3.2, 6.2, 5 | [A17-FEAT][A17-RN][SDK37][REF-INTENT] |

### 6.6 Exact alarms

- `SCHEDULE_EXACT_ALARM`:
  - User-granted; denied by default on fresh installs of apps targeting 33+, and denied after a backup-restore to Android 14.
  - Check `canScheduleExactAlarms()`. The value "stays valid for the entire lifecycle of your app", because revocation stops the app.
  - Re-arm on `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` [ALARMS].
- `USE_EXACT_ALARM`: "Granted automatically", "Cannot be revoked", "Subject to an upcoming Google Play policy", "Limited use cases" [ALARMS].
  - Holders are exempt from the restricted bucket [STANDBY].
  - Not declared by Agentle. The Help Center wording is UNVERIFIED.
- Exact alarms set with an `OnAlarmListener` need no permission [ALARMS]. In AOSP they are dropped when the app becomes cached [AOSP-x AlarmManager.java, AlarmManagerService.java].
- **AOSP permission check** (PendingIntent alarms, target 31+):
  - Exact without permission and without allow-list membership throws `SecurityException` ("needs to hold SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM").
  - Apps on the power-save allow-list may set exact alarms. Their allow-while-idle alarms get the lower compat quota [AOSP-x AlarmManagerService.java].
- **Inexact alternatives:**
  - `set()`, `setWindow()`, `setInexactRepeating()`. From API 31, "apps should not pass in a window of less than 10 minutes"; a smaller window may be "elongated to 10 minutes" [REF-AM].
  - `setAndAllowWhileIdle()` for Doze. The system "will not dispatch these alarms more than about every minute", and in low-power idle modes "this duration may be significantly longer, such as 15 minutes" [REF-AM].
  - WorkManager for anything longer: "schedule it using WorkManager or JobScheduler from your alarm's BroadcastReceiver" [ALARMS].

### 6.7 Background location and geofencing

- **Permissions.**
  - Geofencing needs `ACCESS_FINE_LOCATION` and, for target 29+, `ACCESS_BACKGROUND_LOCATION` [GEOFENCE].
  - Play restricts background location to apps that "need it for their core functionality and meet related policy requirements" [LOC-BG].
  - The Play Console declaration form details are UNVERIFIED (Help Center unreachable).
- **Geofence limits.**
  - At most 100 geofences per app per device user.
  - Responsiveness is configurable: a higher value lowers power use but increases latency.
  - Minimum radius 100-150 m recommended. Use `DWELL` to reduce alert spam.
  - Uses network location "on most devices". Wi-Fi off can prevent alerts [GEOFENCE].
- **Persistence.**
  - Geofences survive Play services updates and crashes.
  - They must be re-registered after a reboot, a reinstall, clearing app data, clearing Play services data, or `GEOFENCE_NOT_AVAILABLE` [GEOFENCE].
  - On Android 15, also after a force-stop, because PendingIntents are cancelled [A15-ALL].
- **Android 8+ background limits.**
  - Location updates "only a few times each hour".
  - The batched FLP and passive listeners deliver history in batches.
  - Geofencing responds "every couple of minutes" on average [LOC-LIMITS].
- **Low-power ladder** (cheapest first):
  1. Geofences (`DWELL`, responsiveness 5 min or more).
  2. `getLastLocation()` at existing job runs.
  3. Passive or batched updates.
  4. `getCurrentLocation(BALANCED)` in high-detail.
  5. Never a location FGS in v1.

### 6.8 Process death and restoration

- The system kills processes by importance (foreground, visible, service, cached). Cached processes are the first to go [PROCLIFE].
- Agentle has no in-memory state that matters:
  - Every collector's position is a persisted cursor.
  - Runtime registrations are re-created in `Application.onCreate`.
  - UI state uses `SavedStateHandle`, which is outside this report.
- Running workers that are stopped (process death, quota, constraint loss) are retried by WorkManager. `getStopReason()` (2.9+) distinguishes the causes [WM-REL]. On Android 16+, an abandoned-job stop reason does not apply to WorkManager [A16-ALL].
- `ApplicationExitInfo` (API 30) reports why the previous process died. Android 17 memory-limit kills are identifiable [A17-ALL].

### 6.9 Activity Recognition Transition API: PendingIntent mutability

- **The rule.** For apps targeting 34+, "creation of a PendingIntent with `FLAG_MUTABLE` and an implicit Intent within will throw an `IllegalArgumentException`".
  - The bypass, `FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT`, is "strongly recommended" against [REF-PI].
  - The Android 14 page puts it as: an intent "that doesn't specify a component or package" [A14-TGT].
- **Why it must be mutable.** Play services fills in the `ActivityTransitionResult` / `GeofencingEvent` extras when it sends the intent. So the PendingIntent must be mutable on 31+.
  - The geofencing guide's snippet adds `FLAG_MUTABLE` on API 31+ [GEOFENCE].
  - The transitions guide only says "A PendingIntent callback where your app receives events" [AR].
  - The exact rule: **explicit component + `FLAG_MUTABLE`**.
- **The official sample is wrong for target 34+.**
  - `UserActivityTransitionManager.kt` in android/platform-samples builds `PendingIntent.getBroadcast(context, CUSTOM_REQUEST_CODE_USER_ACTION, Intent(CUSTOM_INTENT_USER_ACTION), FLAG_MUTABLE)` on API 31+. That is an implicit intent with no component or package [PS-AR-SAMPLE].
  - The sample's app module targets 37 (its `samples/location` module declares targetSdk 35).
  - Under the documented rule this throws on Android 14+. That was not executed here, because no device was available. **Do not copy that snippet.**

```kotlin
fun transitionsPendingIntent(ctx: Context): PendingIntent =
    PendingIntent.getBroadcast(
        ctx, RC_TRANSITIONS,
        Intent(ctx, ActivityTransitionReceiver::class.java),          // explicit component
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // Play services adds extras
    )
// Alarms and notification content intents: FLAG_IMMUTABLE (nothing needs to fill them in).
```

- **Permission.**
  - `android.permission.ACTIVITY_RECOGNITION` has been a runtime permission since Android 10 [A10-PRIV].
  - The transitions guide still says to declare `com.google.android.gms.permission.ACTIVITY_RECOGNITION` [AR]. For apps targeting 28 or lower, the system uses that declaration to auto-grant the runtime permission [A10-PRIV].
  - Declare both, and request the runtime one.
- **Registration lifetime.** Removal uses `removeActivityTransitionUpdates(pi)` then `pi.cancel()` [AR]. The reconciler re-requests after boot, update and force-stop. Persistence across reboot is not documented, so it is treated as lost.

### 6.10 NotificationListenerService lifecycle

- **Declaration.** Declared with `BIND_NOTIFICATION_LISTENER_SERVICE` and the `NotificationListenerService` intent filter.
  - Optional meta-data: `default_filter_types` and `disabled_filter_types` (for example `ongoing|silent`), plus `META_DATA_DEFAULT_AUTOBIND`.
  - "Wait for `onListenerConnected()` before performing any operations"; `requestRebind(ComponentName)` "is the only one that is safe to call before `onListenerConnected()` or after `onListenerDisconnected()`" [REF-NLS].
  - Listeners "cannot get notification access or be bound by the system on low-RAM devices running Android Q (and below)" [REF-NLS].
- **AOSP binding.**
  - Third-party listeners are bound with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE | BIND_NOT_PERCEPTIBLE | BIND_ALLOW_WHITELIST_MANAGEMENT`. `BIND_NOT_PERCEPTIBLE` was added "because too many 3P apps could be kept in memory as notification listeners" [AOSP-x NotificationManagerService.java].
  - So while access is granted, the process is usually kept alive, but **below perceptible importance**. It can still be killed under memory pressure, and it is not a quota exemption: `QuotaController` treats only `FOREGROUND_SERVICE` (Android 16+: `BOUND_TOP`) or better as quota-free [AOSP-x QuotaController.java].
- **Crash or death.** `onBindingDied` leads to unbind, then `reregisterService` after 10 s, with only one pending rebind attempt per component and user [AOSP-x ManagedServices.java].
- **Agentle's handling.**
  - Track `connected` in memory plus `last_connected_at` in Room.
  - On `onListenerDisconnected`, record a gap.
  - The reconciler calls `requestRebind` if access is granted but the listener has been disconnected for more than 10 min (section 5.3).
  - Never do heavy work on the binder callback thread: hand off to the single writer.
- **Test hooks.** Robolectric `ShadowNotificationListenerService.addActiveNotification(...)`, `getRebindRequestCount()` [ROBO]. Real binding behavior is only verifiable on a device (section 7.2, E9).

### 6.11 Runtime receivers for `SCREEN_ON` / `SCREEN_OFF` / `USER_PRESENT` (AOSP-verified)

1. **Manifest receivers cannot get `SCREEN_ON`/`SCREEN_OFF`** ("You cannot receive this through components declared in manifests") [REF-INTENT]. `USER_PRESENT` is not on the exception list [BCAST-EXC]. So runtime registration is the only option.
2. **The export flag decides who can deliver.**
   - In `BroadcastController.registerReceiverWithFeature`, a receiver for **only protected broadcasts** without `RECEIVER_NOT_EXPORTED` is **forced to exported**. A `RECEIVER_NOT_EXPORTED` receiver stays unexported [AOSP-x BroadcastController.java].
   - `BroadcastSkipPolicy` then skips delivery to unexported receivers ("Exported Denial … not specifying RECEIVER_EXPORTED") unless `checkComponentPermission` passes [AOSP-x BroadcastSkipPolicy.java].
   - That check passes only for ROOT/SYSTEM UIDs (`canAccessUnexportedComponents`: `appId == ROOT_UID || appId == SYSTEM_UID`) or the same app [AOSP-x ActivityManager.java].
3. **Who sends each action.**
   - `SCREEN_ON`/`SCREEN_OFF` are sent by `system_server` (`power/Notifier.java`): SYSTEM_UID, so either flag works [AOSP-x Notifier.java].
   - **`USER_PRESENT` is sent by SystemUI** (`KeyguardViewMediator`, `mContext.sendBroadcastAsUser(USER_PRESENT_INTENT, …)`) [AOSP-x KeyguardViewMediator.java]. SystemUI runs with `sharedUserId="android.uid.systemui"` [AOSP-x packages/SystemUI/AndroidManifest.xml], which is an app UID, not `SYSTEM_UID` (1000) [AOSP-x core/java/android/os/Process.java].
   - Therefore a `RECEIVER_NOT_EXPORTED` receiver **never gets `USER_PRESENT`**.
   - Bluetooth broadcasts come from `BLUETOOTH_UID` (1002), which is not a reserved UID either.
   - The developer guide states the general rule: "Some system broadcasts come from highly privileged apps, such as Bluetooth and telephony, that … don't run under the system's unique process ID (UID). To receive all system broadcasts … flag your receiver with RECEIVER_EXPORTED" [BCAST].
4. **Safety.** All three actions are `<protected-broadcast>` [AOSP-x core/res/AndroidManifest.xml], so exporting does not let other apps spoof them.
   - Use **`RECEIVER_EXPORTED`** (or no flag, which the Android 14 "system broadcasts only" exception allows [A14-TGT]). Prefer the explicit flag, because `ContextCompat.registerReceiver` expects one.
   - Keep a separate `RECEIVER_NOT_EXPORTED` receiver for any app-internal actions; the guide says to "partition the broadcasts among different broadcast receivers" [BCAST].
   - Validate `intent.action` inside `onReceive`.
5. **Delivery caveat.** While the app is cached, these broadcasts are deferred and may be merged (Android 14) [A14-ALL][BCAST]. They are hints (section 1.2 A3/A4).

### 6.12 Background sensor restrictions

- Android 9+ (all apps): background apps get **no events** from continuous sensors (accelerometer, gyroscope), on-change sensors or one-shot sensors, and cannot access the microphone or camera. "Use a foreground service" [A9][SENSORS].
- Target 31+: `registerListener` is capped at 200 Hz, and `SensorDirectChannel` at `RATE_NORMAL` (about 50 Hz), unless the app declares `HIGH_SAMPLING_RATE_SENSORS`. With the microphone toggle off, rates are always capped [SENSORS].
- Agentle: raw sensors only while the app is open (D1, D2). Steps come from Health Connect or the Recording API (C3). Activity comes from the AR Transition API (C2).

### 6.13 Health Connect background reads

- **Foreground reads** are normal. **Background reads** need `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` plus `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` availability.
  - Google's example uses a `PeriodicWorkRequest` of **1 h** with `enqueueUniquePeriodicWork(..., KEEP, ...)` [HC-READ].
  - Without the background permission, the app "should still run" with what was granted [HC-READ].
- Reads go back 30 days unless `PERMISSION_READ_HEALTH_DATA_HISTORY` is granted [HC-READ].
- Changes tokens are per type and "expire within 30 days" if unused [HC-SYNC].
- Rate limits: periodic and daily caps on reads and changelog calls, and background limits are "stricter than foreground" [HC-RATE].
- Prefer the changelog over raw reads [HC-RATE]. Exact numbers are unpublished; see DOC05.
- No FGS is needed for any of this.

### 6.14 Play Console declarations implied by this design

| Declaration | Needed by v1? | Basis |
|---|---|---|
| FGS types (Policy > App content) | **No**. Only `dataSync` if the optional "Import now" ships | [FGS-TYPES] |
| Background location | **Yes**, if geofencing (C1) ships | [LOC-BG]; form details UNVERIFIED |
| `USE_EXACT_ALARM` | **No** | [ALARMS] |
| Battery-optimization exemption | **No** | [DOZE] |
| Health Connect / health permissions | Yes (see DOC05 and doc 01) | [HC-READ] |
| Usage access, notification access, SMS/Call Log | See doc 01 | — |

---

## 7. Test plan

### 7.0 Infrastructure and seams

- **JVM tests run under Robolectric 4.17** (JDK 21; SDK 37 "CINNAMON_BUN" is a known SDK) [ROBO].
  - `@Config(sdk = [29, 31, 34, 35, 36, 37])` for anything that depends on the API level (receivers, PendingIntents, alarms). Add 26-28 only if the minSdk decision lands there.
- **WorkManager under test** [WM-TESTINT][AX-WORK]:
  - Use `androidx.work:work-testing:2.12.0`, `WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)` and `WorkManagerTestInitHelper.getTestDriver(ctx)`.
  - The config uses `setExecutor(SynchronousExecutor())`, `setMinimumLoggingLevel(Log.DEBUG)` and `setClock(androidx.work.Clock { fake.millis() })` (`Clock` is a public single-method Java interface).
  - Do not use `TestClock`: it is `@RestrictTo(LIBRARY_GROUP)` [AX-WORK TestClock.kt].
- **Other test tools:**
  - Coroutines: `kotlinx-coroutines-test` `runTest` + `StandardTestDispatcher` (virtual time for backoff and jitter).
  - Room: in-memory database.
  - HTTP: MockWebServer for the token tests.
- **Production seams, injected with Hilt and faked in tests:**
  - `java.time.Clock` and `ZoneProvider`.
  - `WorkScheduler`, `SyncCoordinator`, `TokenManager`.
  - `PendingIntentFactory`.
  - `GeofenceRegistrar` / `TransitionRegistrar` / `NetworkCallbackRegistrar`, which wrap Play services and `ConnectivityManager`.
  - `ExitInfoSource`, which wraps `ActivityManager.getHistoricalProcessExitReasons`.
- **Robolectric hooks used** (method names verified by `javap` of `shadows-framework-4.17.jar` [ROBO]):

  | Shadow | Methods |
  |---|---|
  | `ShadowAlarmManager` | `getScheduledAlarms`, `peekNextScheduledAlarm`, `fireAlarm`, `setCanScheduleExactAlarms` |
  | `ShadowPowerManager` | `setIsPowerSaveMode`, `setIsDeviceIdleMode`, `setIsDeviceLightIdleMode`, `setIgnoringBatteryOptimizations`, `setCurrentThermalStatus`, `setIsInteractive`, `getLatestWakeLock` |
  | `ShadowUsageStatsManager` | `setCurrentAppStandbyBucket`, `addEvent`, `simulateTimeChange` |
  | `ShadowJobScheduler` | `failExpeditedJob`, `failOnJob` |
  | `ShadowNotificationListenerService` | `addActiveNotification`, `getRebindRequestCount` (static) |
  | `ShadowApplication` | `getRegisteredReceivers` (`Wrapper.flags`, `intentFilter`) |
  | `ShadowService` | `setThrowInStartForeground`, `getLastForegroundNotification` |
  | `ShadowSystemClock` | `advanceBy` |

- **What Robolectric cannot prove:** real JobScheduler quotas, Doze maintenance windows, bucket transitions, real system binding of the notification listener, Play services geofence/AR delivery, and protected-broadcast sender UIDs. Those are covered by the emulator matrix in section 7.2.

### 7.1 Robolectric / JVM tests (one or more per required item)

| ID | Item | Test | Key assertion |
|---|---|---|---|
| T-ARCH-01 | 1 | Manifest audit: `packageManager.getPackageInfo(pkg, GET_RECEIVERS or GET_SERVICES or GET_PERMISSIONS)` on the merged manifest | No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `USE_EXACT_ALARM` or `SCHEDULE_EXACT_ALARM`. No FGS type other than `dataSync` on `SystemForegroundService` (and only if the import flag is on). `SystemEventReceiver.exported == false`; `BluetoothEventReceiver.exported == true` with only `ACL_*` actions; no component has `processName != applicationInfo.processName` (single process) |
| T-ARCH-02 | 1, 6.11 | Robolectric starts the `Application` → `shadowOf(app).registeredReceivers` | The wrapper whose filter has `SCREEN_ON`/`SCREEN_OFF`/`USER_PRESENT` has `flags and Context.RECEIVER_EXPORTED != 0` and `flags and Context.RECEIVER_NOT_EXPORTED == 0` (SDK 34-37) |
| T-ARCH-03 | 1 | Collector registry parity | Every collector declares a mode ∈ {EV-M, EV-S, EV-R, CT, PW, DW, OPEN}. Every CT/PW/DW collector maps to a unique name in `WorkScheduler`'s table. A new collector without a mode fails the build |
| T-ARCH-04 | 1, 6.9 | `PendingIntentFactory` contract | Geofence/AR/network PendingIntents: `intent.component != null` and `FLAG_MUTABLE`. Alarm/notification PendingIntents: `FLAG_IMMUTABLE`. A static check (detekt/lint rule or unit test over the factory) forbids `PendingIntent.get*` outside the factory. This contract test is the only JVM guard: Robolectric 4.17's `ShadowPendingIntent.getBroadcast` goes straight to its own `create(...)`, with no implicit-intent or mutability check, so an implicit `FLAG_MUTABLE` PendingIntent does **not** throw under Robolectric [ROBO]. The runtime check is the emulator run on API 37 (E7) |
| T-WM-01 | 1, 4 | `WorkScheduler.reconcile()` twice; `apply(HIGH)` | `getWorkInfosForUniqueWork(name)` has exactly one entry with the same `id`. After `apply`, the same `id`, `generation` + 1, and `periodicityInfo.repeatIntervalMillis` matches the profile. A lint-style test fails if any class other than `WorkScheduler` calls `enqueue*` |
| T-WM-02 | 1, 2 | Constraints per profile | `WorkInfo.constraints` (`requiredNetworkType`, `requiresBatteryNotLow`, `requiresCharging`) match the section 2.3 table for Low/Balanced/High |
| T-WM-03 | 3 | Expedited `jitai.check`: 10 `requestCheck()` calls with the tick budget available | Exactly one unique work (`KEEP`). The worker loop runs at most 3 iterations and clears the dirty flag. `ShadowJobScheduler.failExpeditedJob(true)` still completes (non-expedited fallback) |
| T-WM-04 | 3 | Periodic tick with `TestDriver.setPeriodDelayMet(id)` | Rules are evaluated once per period. Nothing posts in quiet hours or when DND ≠ ALL (`NotificationManager` shadow) |
| T-WM-05 | 3, 5 | Daily pinning: pure JVM `nextLocalOccurrence()` plus a worker run that calls `updateWork` | America/New_York, 2027-03-14 02:30 (gap) → 03:30 EDT. 2027-11-07 01:30 (overlap) → the first occurrence (EDT). Asia/Kolkata (no DST). Zone change Europe/Berlin → America/Los_Angeles re-pins `WorkInfo.nextScheduleTimeMillis` to 08:30 local |
| T-WM-06 | 4 | Content-trigger chain: `TestListenableWorkerBuilder<ProviderTriggerWorker>().setTriggeredContentUris(listOf(uri))` then `ProviderSyncWorker` | Generation incremented once; sync enqueued with `KEEP`; the trigger is re-armed with `REPLACE` (new id, `ENQUEUED`). A second bump during sync causes exactly one extra loop |
| T-WM-07 | 4 | Retry semantics with `setRunAttemptCount(n)` | Transient error → `Result.retry()`; `NeedsReauth` → `Result.success()` + connection state `NEEDS_REAUTH` + exactly one "Reconnect" notification (fixed ID) across 5 runs |
| T-WM-08 | 4, 5 | Stops: `TestDriver.stopRunningWorkWithReason(id, STOP_REASON_QUOTA \| STOP_REASON_TIMEOUT \| STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)` mid-ingest | The committed cursor equals the last committed page. The rerun produces identical row counts (no duplicates) |
| T-WM-09 | 1 | Optional import `setForeground`: `setForegroundUpdater(recordingUpdater)`; `ShadowService.setThrowInStartForeground(true)` variant | `ForegroundInfo.foregroundServiceType == FOREGROUND_SERVICE_TYPE_DATA_SYNC`. On a throw, the worker falls back to chunked mode and returns success |
| T-DUP-01 | 4 | Ingest the same page twice; inject an exception after half a batch | Row count unchanged after the replay. The transaction rolls back, so the cursor does not advance |
| T-DUP-02 | 4 | JITAI decision idempotency: run `evaluateAndMaybeDeliver` twice; also crash between `notify` and `markDelivered` | `shadowOf(notificationManager).allNotifications.size == 1` with the same tag and ID. The decision row ends `DELIVERED` |
| T-TOK-01 | 4 | Single-flight: 50 coroutines call `accessToken()` with an expired token (MockWebServer) | `server.requestCount == 1` for the refresh endpoint. All callers get the rotated token. The store is written before the first use |
| T-TOK-02 | 4 | Stale 401: an Authenticator call with an old token after a refresh | No new refresh. The retry uses the current token. `priorResponse != null` → `null` (no loop) |
| T-TOK-03 | 4 | Terminal (`invalid_grant`, `refresh_token_reused`) and transient (503 with `Retry-After`) | Terminal: `NEEDS_REAUTH` persisted, 0 further network calls. Transient: at most 3 in-run attempts with jittered delays (virtual time), then `RetryableAuthError` |
| T-RCV-01 | 5 | `SystemEventReceiver.onReceive` for each action (and an unknown action) | `agentle.reconcile` is enqueued once (`KEEP`). An unknown action is ignored. `goAsync` finishes |
| T-RCV-02 | 5 | `ScheduleReconciler` with fakes after `BOOT` / `UPDATE` / `PROCESS_START` | Registrars called once each with stable request IDs. Alarms re-armed (`ShadowAlarmManager.getScheduledAlarms` window ≥ 10 min, `RTC_WAKEUP`). A second run creates no duplicates |
| T-RCV-03 | 5 | Gap detection: heartbeat older than 2× interval, plus `ExitInfoSource` returning `REASON_USER_REQUESTED` (with `StartInfoSource.wasForceStopped()` true, false, or unavailable below API 35) / `REASON_OTHER` with "MemoryLimiter:AnonSwap" | `collection_gap` rows with the correct cause (`FORCE_STOP`, `USER_STOP`, `USER_STOP_OR_FORCE_STOP`, `MEMORY_LIMITER`). Features treat the gap as missing |
| T-NLS-01 | 1, 5 | `ShadowNotificationListenerService.addActiveNotification(...)` then `onListenerConnected()` | Snapshot diff closes rows missing from the active list (`UNKNOWN_GAP`). The reconciler with "disconnected > 10 min" calls `requestRebind` → `getRebindRequestCount() == 1`, rate-limited |
| T-ALM-01 | 3 | Reminders with `ShadowAlarmManager.setCanScheduleExactAlarms(false)` | Only `setWindow` alarms (window ≥ 10 min). `fireAlarm` → the receiver posts a notification. No `SecurityException` path |
| T-PWR-01 | 2 | `ShadowPowerManager.setIsPowerSaveMode(true)`; `setCurrentThermalStatus(THERMAL_STATUS_SEVERE)` | Workers take the essential path. After 30 min of virtual time, `apply(LOW)` with `UPDATE`. Heavy jobs are skipped while SEVERE |
| T-PWR-02 | 2 | `ShadowUsageStatsManager.setCurrentAppStandbyBucket(STANDBY_BUCKET_RARE / RESTRICTED)` | Essential-only mode. Diagnostics records the bucket. No notification is posted only to "re-engage" |
| T-USG-01 | 1 | `ShadowUsageStatsManager.addEvent(...)` with `SCREEN_INTERACTIVE`/`NON_INTERACTIVE`/`KEYGUARD_HIDDEN` + receiver hint rows | Reconciled intervals prefer `usage_events`. Hints within ±5 s are merged. A `null` `queryEvents` result (locked user) leaves the cursor unchanged |

Example: the WorkManager setup and two of the tests.

```kotlin
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31, 34, 36, 37])
class WorkSchedulerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val fake = MutableClock(Instant.parse("2027-03-13T12:00:00Z"), ZoneId.of("America/New_York"))

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(Log.DEBUG)
                .setClock(androidx.work.Clock { fake.millis() })
                .build(),
        )
    }

    @Test fun reconcileIsIdempotentAndUpdateKeepsId() {
        val wm = WorkManager.getInstance(ctx)
        val scheduler = WorkScheduler(wm, Profile.BALANCED)
        scheduler.reconcile(); scheduler.reconcile()
        val first = wm.getWorkInfosForUniqueWork("agentle.jitai.tick").get().single()
        scheduler.apply(Profile.HIGH)
        val updated = wm.getWorkInfosForUniqueWork("agentle.jitai.tick").get().single()
        assertThat(updated.id).isEqualTo(first.id)
        assertThat(updated.generation).isEqualTo(first.generation + 1)
        assertThat(updated.periodicityInfo!!.repeatIntervalMillis).isEqualTo(15.minutes.inWholeMilliseconds)
    }

    @Test fun tickRunsOncePerPeriod() {
        val wm = WorkManager.getInstance(ctx)
        WorkScheduler(wm, Profile.BALANCED).reconcile()
        val id = wm.getWorkInfosForUniqueWork("agentle.jitai.tick").get().single().id
        val driver = WorkManagerTestInitHelper.getTestDriver(ctx)!!
        driver.setPeriodDelayMet(id)
        assertThat(engineSpy.evaluations).isEqualTo(1)
    }
}
```

### 7.2 Emulator matrix (adb)

Setup (once per run):
- An API 37 Google APIs image (Play services is needed for geofencing and AR), plus one API 34/35 image for the target-behavior differences.
- `PKG=<applicationId>`.
- Debug build with `setMinimumLoggingLevel(DEBUG)`. Logcat filter: tags with prefix `WM-` (WorkManager's tag prefix [AX-WORK Logger.java]) and `Agentle`.
- The app's debug "Background diagnostics" screen shows: unique works with `WorkInfo` state, `nextScheduleTimeMillis`, `stopReason`, `generation`; bucket; power-save; heartbeats; gaps; exit reasons; token state.

```sh
adb shell cmd jobscheduler disable-flex-policy      # remove flex-policy timing variance [AOSP-x JobSchedulerShellCommand]
adb shell am set-standby-bucket $PKG active
adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p "$PKG"   # [WM-DEBUG]
adb shell dumpsys jobscheduler | grep -A6 "$PKG"    # find JOB IDs (WorkManager assigns them)
```

Command syntax is verified against AOSP shell-command sources [AOSP-x ActivityManagerShellCommand, JobSchedulerShellCommand, DeviceIdleController, BatteryService, PowerManagerShellCommand, AlarmManagerService, Time(Zone)DetectorShellCommand, svc.sh] or the developer docs [DOZE][A15-TGT][A16-ALL][FGS-TIMEOUT][A17-ALL], unless marked UNVERIFIED.

| ID | Scenario (items) | Commands | Pass criteria |
|---|---|---|---|
| E1 | Deep Doze (1, 2, 3) | `adb shell dumpsys battery unplug` · `adb shell dumpsys deviceidle force-idle` · `adb shell dumpsys deviceidle get deep` (expect `IDLE`) · wait at least 2 tick intervals · `adb shell dumpsys deviceidle step deep` (maintenance) · `adb shell dumpsys deviceidle unforce` · `adb shell dumpsys battery reset` | No Agentle job runs while forced idle except in the stepped maintenance window. After `unforce`, the deferred sweep and tick run once each (no catch-up burst). Heartbeat gap recorded only if more than 2× the interval. No ANR or crash |
| E1b | Light Doze (1) | `adb shell dumpsys deviceidle force-idle light` … `unforce` | Same, with shorter deferral |
| E1c | Flex policy on (2, 3) | `adb shell dumpsys jobscheduler \| grep -A2 FlexibilityController` (record the device's own `fc_applied_constraints`) · `adb shell cmd jobscheduler enable-flex-policy --option charging --option battery-not-low --option idle --option connectivity` · `adb shell dumpsys battery unplug` · app in the background for 3 h with periodic triggers · `adb shell cmd jobscheduler reset-flex-policy` · `adb shell dumpsys battery reset` [AOSP-x JobSchedulerShellCommand.java, FlexibilityController.java] | Expedited `jitai.check` still runs within minutes of each trigger. `jitai.tick` and the sweep may slip, and the slip is recorded in diagnostics. No catch-up burst afterwards. No gap beyond the backfill windows |
| E2 | Standby buckets (2) | for B in `active working_set frequent rare restricted`: `adb shell am set-standby-bucket $PKG $B`; `adb shell am get-standby-bucket $PKG`; `adb shell cmd jobscheduler reset-execution-quota $PKG`; observe 2 h with the app closed. Restore `active` | Diagnostics records the bucket on each run. In `rare`/`restricted`, only essential steps run, and the daily essential set still completes. `jitai.check` degrades to non-expedited without errors. No "engagement" notifications |
| E2b | Android 16 quota enforcement (2) | `adb shell am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_TOP_STARTED_JOBS $PKG` and `… OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS $PKG` (then `disable`) [A16-ALL] | Behavior identical with and without overrides, because the budgets assume quota |
| E3 | Battery Saver (2) | `adb shell dumpsys battery unplug` · `adb shell settings put global low_power 1` (or `adb shell cmd power set-mode 1`) · wait 35 min · `adb shell settings put global low_power 0` · `adb shell dumpsys battery reset` | Essential path immediately. After 30 min, `WorkInfo.periodicityInfo` equals Low (same IDs, generation + 1). After Saver off + 30 min, back to the user profile. (`low_power` is ignored while powered [AOSP-x BatterySaverStateMachine.java]) |
| E4 | Process death (5) | Open the app, press Home · `adb shell am kill $PKG` · `adb shell pidof $PKG` (empty) · `adb shell cmd jobscheduler run -f $PKG <sweepJobId>` | Process restarts. Runtime receivers are re-registered (log). Reconcile creates no new work IDs. The sweep resumes from the cursor. Row counts are stable when re-run |
| E5 | Force-stop (5) | `adb shell am force-stop $PKG` · `adb shell dumpsys jobscheduler \| grep -c "$PKG"` · `adb shell dumpsys alarm \| grep -c "$PKG"` · inspect `adb shell dumpsys notification` for the listener · launch the app | While stopped: Agentle jobs and alarms show 0 counts; no geofence/AR callbacks (Android 15 PendingIntents cancelled). On launch: WorkManager reschedules (diagnostics), the reconciler re-registers geofences/AR/network/alarms, `BOOT_COMPLETED` is received on 15+, `wasForceStopped()` is true (API 35+), a gap row has cause `FORCE_STOP`. Record whether the listener stayed bound (UNVERIFIED) |
| E5b | Task Manager stop (5) | Start the optional "Import now" (FGS), then `adb shell cmd activity stop-app $PKG` [FGS-STOP] (or tap Stop in the Quick Settings "Active apps" list) · `adb shell dumpsys jobscheduler \| grep -c "$PKG"` · `adb shell dumpsys alarm \| grep -c "$PKG"` · relaunch | Jobs and alarms kept (non-zero counts) [FGS-STOP]. The import resumes as background chunks from the cursor. The gap row has cause `USER_STOP` on API 35+. Record listener, geofence and AR state (UNVERIFIED) |
| E6 | App update (5) | Bump `versionCode` · `adb install -r app-debug.apk` | `MY_PACKAGE_REPLACED` logged once. The `dumpsys jobscheduler` job set is unchanged before and after. Alarms are still present. No duplicate unique works |
| E7 | Reboot (5) | `adb reboot` · `adb wait-for-device` · poll `adb shell getprop sys.boot_completed` until `1` · diagnostics broadcast. Fast variant: `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED $PKG` [A15-TGT] | Reconcile reason `BOOT`. Geofences and transitions re-registered. Reminders re-armed. The listener reconnects. The gap is labelled using `DEVICE_SHUTDOWN`/`DEVICE_STARTUP`. No FGS start attempted from boot |
| E8 | Time and time zone (3, 5) | `adb shell cmd time_detector set_auto_detection_enabled false` · `adb shell cmd time_zone_detector set_auto_detection_enabled false` · `adb shell cmd alarm set-timezone America/Los_Angeles` · `adb shell cmd alarm set-time <epochMs>` · DST run: zone `America/New_York`, time `2027-03-14T06:58:00Z` (01:58 EST), wait past 02:00 local · restore both `set_auto_detection_enabled true` | `CLOCK` reconcile each time. Daily works' `nextScheduleTimeMillis` = next local 08:30 / 06:30 / 03:30 in the new zone. Quiet hours recomputed. On API 37, `TIMEZONE_OFFSET_CHANGED` observed at the DST jump if delivered (record result; manifest delivery UNVERIFIED) |
| E9 | Notification listener (1, 5) | `adb shell cmd notification allow_listener $PKG/<listener class>` (a flattened `ComponentName`; optional user ID) · `adb shell cmd notification post -t "T1" tag1 "hello"` (posted as the shell package) · `adb shell am kill $PKG` · post again · `adb shell cmd notification disallow_listener $PKG/<listener class>` · post again · `allow_listener` again [AOSP-x NotificationShellCmd.java] | Posts and removals recorded once each. After a kill, the listener rebinds within about 10-30 s (measure). Missed removals are closed by the snapshot diff on reconnect. After `disallow_listener`: `onListenerDisconnected`, the status card shows "notification access off", and nothing is recorded until `allow_listener` again |
| E10 | Network loss (2, 4) | `adb shell svc wifi disable` (= `cmd wifi set-wifi-enabled disabled`) · `adb shell svc data disable` (= `cmd phone data disable`) [AOSP-x svc.sh] · `adb shell cmd connectivity airplane-mode enable` (**UNVERIFIED**: confirm with `adb shell cmd connectivity help`; otherwise toggle in Quick Settings) · wait 1 h · re-enable all | Cloud syncs stay `ENQUEUED` (constraint unmet). No retries counted, no `FAILED` state. JITAI ticks continue. After re-enable: the G2 PendingIntent callback fires, each provider refreshes its token at most once, and the sync completes once |
| E11 | Token storm (4) | Debug fake-server mode with an expired access token · `adb shell dumpsys deviceidle force-idle` for 30 min to stack jobs · `unforce` · also `cmd jobscheduler run -f` on 4 network jobs back-to-back | The fake server logs exactly **one** refresh per provider. No `refresh_token_reused`. The token store is written before use |
| E12 | Optional `dataSync` import (1) | `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS $PKG` · `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000` [FGS-TIMEOUT] · start "Import now" · wait 70 s. Then `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS $PKG` + the boot broadcast [A15-TGT] | Worker stopped with `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` (API 35+), the chunk committed, no ANR, the import continues as background chunks. No FGS start from boot |
| E13 | Android 17 memory limiter (5) | `adb shell am memory-limiter status` · `adb shell am memory-limiter manual <pid> 64` · run a cold-load sync · `adb shell am memory-limiter manual <pid> none` [A17-ALL] | If the process is killed: `ApplicationExitInfo` `REASON_OTHER` with "MemoryLimiter:AnonSwap" recorded; the work resumes from the cursor; no duplicates |
| E14 | User background restriction (2) | `adb shell am set-bg-restriction-level $PKG background_restricted` · `adb shell am get-bg-restriction-level $PKG` · restore `adaptive_bucket` | Status card shown once. Essential-only. No crash |
| E15 | Expedited exhaustion (3) | `adb shell am set-standby-bucket $PKG restricted` · post 30 notifications (or trigger 30 events) · `adb shell dumpsys jobscheduler \| grep -A3 $PKG` | Checks are capped per day, coalesced by `KEEP` + dirty flag, and run as regular jobs when out of quota. Never more JITAI notifications than the cap |
| E16 | Stop reasons (4, 5) | `adb shell cmd jobscheduler stop -s <reason> $PKG <jobId>` · `adb shell cmd jobscheduler timeout $PKG <jobId>` | Worker sees `stopReason`. Cursor committed. The next run continues without duplicates |
| E17 | Whitelist sanity (2) | `adb shell dumpsys deviceidle whitelist +$PKG` … `-$PKG` | Agentle does not *require* the allow-list. Behavior differs only in latency |
| E18 | Battery measurement (2) | `adb shell dumpsys batterystats --reset` · run a 4 h scripted day (high-detail) or 24 h (balanced) unplugged · `adb shell dumpsys batterystats > bs.txt` · `adb shell dumpsys jobscheduler > js.txt` · debug export of `work-analytics` `WorkMetricsInfo` | Total job runtime per day ≤ the section 2.3 target per profile. No Agentle-held wake locks (only job ones). Job count within ±20 % of plan. Output format of `batterystats` sections UNVERIFIED (parse defensively) |

### 7.3 Measurement and evidence

- Each emulator scenario exports:
  - `adb logcat -d` filtered to `WM-*` and `Agentle*`.
  - The diagnostics JSON, written by a debug-only `adb shell am broadcast` to an exported-false receiver triggered via `run-as`, or by "Export diagnostics" in the debug menu.
  - The `dumpsys jobscheduler` and `dumpsys alarm` text.
- Budget verification (E18):
  - `WorkMetricsInfo` (`workerDurationMillis`, `totalRuntimeMillis`, `stopReasonCounts`) [WM-REL] is the per-worker source of truth in debug builds. It is experimental and requires opt-in `@ExperimentalWorkMetricsApi`, so it is not shipped in release.
  - `batterystats` gives the system view.
- CI:
  - All section 7.1 tests run on the JVM (this container has no emulator).
  - Section 7.2 runs on the developer's machine or on Gradle Managed Devices (doc 08 owns that wiring).

---

## 8. Uncertainties and UNVERIFIED items

Every item below is either marked UNVERIFIED in the body or is an assumption the design depends on. "Design assumption / fallback" says what Agentle does if the assumption turns out wrong. Items are ordered by impact on v1.

| ID | Item | Why it is open | Design assumption / fallback | How to close it | Impact |
|---|---|---|---|---|---|
| U1 | Real battery cost | The numbers in sections 2.2-2.3 are design targets, not measurements. [PWR] says its quotas "are not a guarantee" and "subject to change" | Balanced uses 10 min/day or less of job runtime off charger; the essential set uses 3 min/day or less. If E18 measures more, lengthen the Balanced intervals before dropping collectors | E18 on an API 37 image and on at least two physical devices; `WorkMetricsInfo` in debug builds (section 7.3) | High |
| U2 | Android 17 internals | The AOSP GitHub mirror is at about Android 16 QPR level (section 0). Everything tagged [AOSP-x] was read from that code, not from Android 17: QuotaController thresholds, the standby network exception and restricted-bucket dynamic constraints (section 6.2), alarm quotas, `BroadcastSkipPolicy`, `ManagedServices` rebinds, NMS force-stop handling | Android 17 keeps these behaviors unless an [A17-*] page says otherwise | Re-read the same files when Android 17 sources reach the mirror; run section 7.2 on an API 37 image | Medium |
| U3 | OEM builds | Under Battery Saver "the device manufacturer determines the precise restrictions imposed", and "there are other, device-specific power optimizations" [A9-PWR]. No OEM documentation was read | Agentle relies only on documented AOSP behavior and degrades by bucket. Gaps are recorded and shown in diagnostics (sections 5.3, 7.3). Agentle still never requests an exemption (section 2.5) | Run E1-E5, E9 and E18 on the OEM devices most used by early users | Medium to high (unknown) |
| U4 | Flex policy on production devices | AOSP ships it off (`fc_applied_constraints = 0`); devices can turn it on through device config (section 6.2) | Expedited JITAI checks are exempt. Ticks and sweeps may slip by hours, and nothing in the design has a hard deadline | `adb shell dumpsys jobscheduler` on Pixel and other Android 16/17 devices; E1c | Medium |
| U5 | Google Health API `authorize()` without an Activity | Not confirmed [DOC05] | Background sync never starts UI. If consent is needed again, the worker records `NEEDS_REAUTH`, shows a status card, and syncs after the user re-consents in the app | Device test with an expired grant (doc 05 owner) | Medium |
| U6 | Robolectric and the WorkManager artifact at runtime | Shadow methods were confirmed from the 4.17 jar bytecode [ROBO], but no Android test was compiled or run here. Google Maven redirects to `dl.google.com`, which is blocked, so there is no Android build in this container. The WorkManager 2.12.0 version comes from the release notes [WM-REL]; the artifact was not downloaded. `ShadowPendingIntent` does not enforce the Android 14 implicit + mutable rule (T-ARCH-04) | The section 7.1 tests work as written on SDK 37 with JDK 21 (docs 07 and 08 make the same assumption) | First CI run of section 7.1 | Medium |
| U7 | Activity-transition requests and `ConnectivityManager` PendingIntent callbacks after reboot | [AR] and [REF-CM] do not say whether they persist. [AR] documents removal by the same PendingIntent, but not whether re-requesting replaces the old request | Treated as lost: re-requested on every boot and process start (idempotent). Transition rows are deduplicated by natural key (section 4.2). If E7 shows duplicate callbacks, call `removeActivityTransitionUpdates` before re-requesting | E7 with callback counters | Medium (activity recognition only) |
| U8 | Geofence, activity-recognition and listener state after a Task Manager stop, and the real listener rebind after a force-stop | [FGS-STOP] covers only jobs and alarms. For force-stop, the AOSP code suggests the listener is rebound (section 5.1), but no device was tested | The reconciler runs on every process start, and a watchdog requests a listener rebind (section 5.3, step 5) | E5, E5b | Low to medium |
| U9 | Play Console and Help Center policy texts | `support.google.com` returned 403. This affects the FGS type declaration (`dataSync`), the background-location declaration, `USE_EXACT_ALARM` eligibility and the battery-optimization exemption policy | The developer-docs summaries are cited instead. v1 declares no exact alarm, no exemption, and no FGS except the optional `dataSync` import. Background location is declared only if C1 ships (section 6.14) | Read the Help Center pages before the first Play submission | Medium (review risk, not runtime) |
| U10 | Network in the `rare` and `restricted` buckets | [PWR] says "Disabled". AOSP grants a standby exception to network jobs that are ready to run (section 6.2), but that comes from mirror code only | Cloud syncs can still run as jobs in `rare`, within quota. Agentle limits `rare` to the essential set anyway | E2 with a cloud sync enqueued in `rare`: it should complete | Low |
| U11 | `ACTION_TIMEZONE_OFFSET_CHANGED` (API 37) reaching manifest receivers | It is not on the [BCAST-EXC] page that was read, and the sender is not on the mirror | Registered at runtime only. Manifest `TIMEZONE_CHANGED` and `TIME_SET` receivers, plus the reconcile at every process start, re-pin daily jobs | E8 on API 37 | Low |
| U12 | Android 17 items known only from the summary page | "Optimized delivery of self-broadcasts" and "Restricted message access" [A17-SUM] | Agentle uses no self-broadcast IPC. Notification content from end-to-end-encrypted messaging apps may be missing, so B1 and H3 never depend on it | Read the full Android 17 pages; test B1 with a messaging app on API 37 | Low |
| U13 | Android 17 listener variant of `setExactAndAllowWhileIdle` | The API is in [A17-FEAT] and [SDK37]; its quota and permission rules are not in the mirror | Not used in v1, because listener alarms are dropped when the app is cached (section 3) | Only needed if exact timing ever becomes a requirement | None for v1 |
| U14 | Test-tool syntax and output | `cmd connectivity airplane-mode` (the Connectivity module is not on the mirror); the `dumpsys batterystats` output format | Use the Quick Settings toggle as the fallback; parse `batterystats` defensively | Run `adb shell cmd connectivity help` once | Low (tooling) |
| U15 | minSdk | Owned by docs 01 and 07. Doc 07's catalog uses 29 as PROVISIONAL [DOC07] | Doc 02 recommends 31 and accepts 29. Below 31, every expedited worker needs `getForegroundInfo()`, the restricted bucket does not exist, and the FGS-start rules differ (TL;DR item 11) | Decision in docs 01 and 07 | Medium (code paths) |

**What would change the design:**
- **E1c or U4 shows the tick slipping often on real devices.** Nothing changes structurally, because JITAI already rests on expedited event checks. Only the user-facing promise for "check at least every N min" in the high-detail profile is relaxed.
- **E18 or U1 exceeds the targets.** Lengthen Balanced intervals and move more work behind `setRequiresCharging(true)` before removing any collector.
- **U3 shows OEM background killers.** Add an in-app "background health" explainer that links to system settings. Still no exemption request.
- **U7 shows duplicate activity transitions.** Switch to remove-then-request on reconcile.

---

## 9. Hand-off

For the integrator, in this order:
1. **Unique-work table.** Implement `WorkScheduler` as the table in section 1.3, with the names and policies exactly as listed. Write T-WM-01 and T-WM-02 first, because they pin the unique names, policies and per-profile constraints.
2. **Manifest.**
   - `SystemEventReceiver` with `exported="false"`.
   - `BluetoothEventReceiver` with `exported="true"`, ACL actions only.
   - Remove `WorkManagerInitializer` (doc 07, W6) and use on-demand init with `HiltWorkerFactory`.
   - Merge `SystemForegroundService` with `foregroundServiceType="dataSync"` only if the "Import now" flag ships.
3. **Runtime receivers.** Register `SCREEN_ON`/`SCREEN_OFF`/`USER_PRESENT` with `RECEIVER_EXPORTED` in `Application.onCreate` (section 6.11), with T-ARCH-02 as the guard.
4. **Shared building blocks before any collector code.**
   - `PendingIntentFactory` + T-ARCH-04.
   - `TokenManager` single-flight (section 4.4), one implementation shared by the doc 05 and doc 06 clients.
   - Natural-key upserts with same-transaction watermarks (section 4.2).
5. **One scheduling entry point.** Every enqueue goes through `WorkScheduler` (sections 1.3 and 4.1; T-WM-01 enforces it). `ScheduleReconciler` (section 5.3) calls `WorkScheduler.reconcile()` on each trigger, and settings changes call `WorkScheduler.apply(profile)`.
6. **Diagnostics early.** Build the debug "Background diagnostics" screen early, because every emulator scenario in section 7.2 reads from it.
7. **Owners of other docs.**
   - Docs 01 and 07: settle minSdk (U15).
   - Doc 08: put section 7.1 on JDK 21 CI with `@Config(sdk = [29, 31, 34, 35, 36, 37])`, and run section 7.2 on Gradle Managed Devices or physical devices.
   - Doc 10 (JITAI engine), which consumes section 3:
     - `agentle.jitai.check` (expedited, `KEEP` + dirty flag) and `agentle.jitai.tick`.
     - Deterministic decision IDs (section 4.3).
     - Delivery gates (section 3.3).
     - No exact alarms.
