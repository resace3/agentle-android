# 03 - Sensors and activity collection strategy (Agentle, API 26 to 37)

Status: COMPLETE for v1 planning (2026-10-02). Author: Agent 3 (Android sensor specialist).

Scope: which on-device sensors and activity signals Agentle collects, how, when and at what cost, on every
Android version from the minSdk candidates (26-31) up to compileSdk/targetSdk 37 (Android 17). Covered:
the `SensorManager` framework (motion, position and environment sensors, batching, wake-up sensors,
trigger sensors, the 200 Hz cap), step counting, the Google Play services Activity Recognition
Transition API and Sleep API, Health Connect and the Recording API as step sources, background limits,
the opt-in "high-detail sensing" mode with its foreground service (FGS), the normalized events that
replace raw streams, storage and battery estimates, `UNSUPPORTED_ON_DEVICE` handling, and tests.

Related docs (read-only here): `01-android-permissions-matrix.md` (capability ids, permission state
model), `02-background-execution.md` (schedulers, FGS rules, PendingIntent mutability),
`05-google-health-and-health-connect.md` (Health Connect and Google Health API step data),
`08-testing-strategy.md` (test tiers), `10-jitai-engine-design.md` (`normalized_event`,
`collector_coverage`, `EventType`).

Ground rules: only public SDK APIs and public Google Play services APIs. No hidden sensor types, no
reflection, no root. The gesture sensors that some phones have internally (wake, pick-up, glance, tilt)
are not in the public SDK: the android-37.1 `Sensor` stub lists no such constant [SDK]. Agentle never
declares `HIGH_SAMPLING_RATE_SENSORS` just to satisfy the `health` FGS prerequisite (section 4.2).

How sources were read: every developer.android.com page was fetched with `curl` and converted to text.
No WebFetch call was made, because the user asked not to be prompted for public reads. Four hosts are
blocked at the egress proxy (HTTP 403 on CONNECT): `developers.google.com` (Play services API
reference), `source.android.com` and `android.googlesource.com` (AOSP sensor HAL docs and sources), and
`dl.google.com`, which `maven.google.com` redirects to for `maven-metadata.xml`. Facts that live only on
those hosts are either verified through Google's official sample code on GitHub (cloned with `git`) or
marked **UNVERIFIED**.

---

## 0. Conventions and sources

- **Markers.**
  - No marker: checked against the cited source on 2026-10-01 or 2026-10-02.
  - **UNVERIFIED**: plausible, not confirmed in a reachable source this session.
  - **design**: an Agentle recommendation, not a platform fact.
  - **estimate**: arithmetic on stated assumptions; numbers to be replaced by measurements.
- **API levels** come from the SDK database `/opt/android-sdk/platforms/android-37.1/data/api-versions.xml`
  [SDK]. Android 16 is API 36; Android 17 is API 37 (37.1 is QPR1, the stable target; 37.2 is the
  QPR2 beta).
- **Cache paths.** `$OLD` = `/tmp/claude-0/-home-claude/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad`
  (the previous, killed run; reused read-only). `$A3` =
  `/tmp/claude-0/-home-claude-agentle-android/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/a3` (this
  run). Both are scratch locations and may disappear; the URLs are the durable reference.

| Key | Source (URL) | Cached copy | Page "Last updated" |
|---|---|---|---|
| SENS-OV | https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview | `$OLD/pages/g_sensors_overview.txt` | 2026-09-16 |
| SENS-MOT | https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion | `$OLD/pages/g_sensors_motion.txt` | 2026-09-16 |
| SENS-POS | https://developer.android.com/develop/sensors-and-location/sensors/sensors_position | `$OLD/pages/g_sensors_position.txt` | 2026-09-16 |
| SENS-ENV | https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment | `$OLD/pages/g_sensors_env.txt` | 2024-01-03 |
| R-SENSOR | https://developer.android.com/reference/android/hardware/Sensor | `$OLD/pages/ref_Sensor.txt` | 2026-08-14 |
| R-SM | https://developer.android.com/reference/android/hardware/SensorManager | `$OLD/pages/ref_SensorManager.txt` | 2026-08-03 |
| R-SE | https://developer.android.com/reference/android/hardware/SensorEvent | `$A3/pages/ref_SensorEvent.txt` | 2026-08-03 |
| R-TEL | https://developer.android.com/reference/android/hardware/TriggerEventListener | `$A3/pages/ref_TriggerEventListener.txt` | (not shown) |
| R-PERM | https://developer.android.com/reference/android/Manifest.permission | `$OLD/pages/ref_Manifest_permission.txt` | 2026-09-16 |
| R-ALARM | https://developer.android.com/reference/android/app/AlarmManager | `$OLD/pages/ref_AlarmManager.txt` | 2026-08-03 |
| R-SG | https://developer.android.com/reference/android/provider/Settings.Global | `$OLD/pages/ref_Settings_Global.txt` | 2026-08-03 |
| R-PI | https://developer.android.com/reference/android/app/PendingIntent | `$OLD/agent2/ref_pi.txt` | (not shown) |
| SDK | Local SDK: `platforms/android-37.1/android-stubs-src.jar` and `data/api-versions.xml`; `android-34` and `android-37.2-beta1` stubs for diffs | extracted to `$A3/s34`, `$A3/s371`, `$A3/s372` | SDK 37.1 rev 2 |
| A9 | https://developer.android.com/about/versions/pie/android-9.0-changes-all | `$A3/pages/a9_all.txt` | 2026-03-03 |
| A10-PRIV | https://developer.android.com/about/versions/10/privacy/changes | `$OLD/pages/g_a10_privacy.txt` | 2026-09-16 |
| A12-TGT | https://developer.android.com/about/versions/12/behavior-changes-12 | `$OLD/pages/g_a12_target.txt` | 2026-09-16 |
| A13-TGT | https://developer.android.com/about/versions/13/behavior-changes-13 | `$OLD/pages/g_a13_target.txt` | 2026-09-16 |
| A14-TGT | https://developer.android.com/about/versions/14/behavior-changes-14 | `$OLD/pages/g_a14_target.txt` | 2026-09-16 |
| A15-TGT | https://developer.android.com/about/versions/15/behavior-changes-15 | `$OLD/pages/g_a15_target.txt` | 2026-09-16 |
| A16-ALL | https://developer.android.com/about/versions/16/behavior-changes-all | `$OLD/pages/a16_all.txt` | 2026-09-16 |
| A16-TGT | https://developer.android.com/about/versions/16/behavior-changes-16 | `$OLD/pages/a16_target.txt` | 2026-09-16 |
| A17-ALL | https://developer.android.com/about/versions/17/behavior-changes-all | `$OLD/pages/a17_all.txt` | 2026-10-01 |
| A17-TGT | https://developer.android.com/about/versions/17/behavior-changes-17 | `$OLD/pages/a17_target.txt` | 2026-09-16 |
| A17-FEAT | https://developer.android.com/about/versions/17/features and /17/release-notes | `$OLD/pages/a17_features.txt`, `a17_release-notes.txt`, `a17_qpr1_release-notes.txt` | 2026-10-01 |
| FGS-TYPES | https://developer.android.com/develop/background-work/services/fgs/service-types | `$OLD/pages/g_fgs_types.txt` | 2026-09-21 |
| FGS-BG | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start | `$OLD/pages/g_fgs_bgstart.txt` | 2026-09-16 |
| FGS-TIMEOUT | https://developer.android.com/develop/background-work/services/fgs/timeout | `$OLD/pages/g_fgs_timeout.txt` | 2026-09-16 |
| NOTIF-PERM | https://developer.android.com/develop/ui/views/notifications/notification-permission | `$OLD/pages/g_notif_perm.txt` | (not shown) |
| DOZE | https://developer.android.com/training/monitoring-device-state/doze-standby | `$OLD/agent2/doze.txt` | (not shown) |
| VITALS | https://developer.android.com/topic/performance/vitals | `$OLD/agent2/vit.txt` | (not shown) |
| VITALS-WL | https://developer.android.com/google/play/vitals/excessive-wakelock | `$A3/pages/vit_excessive_wakelock.txt` | 2026-09-21 |
| AR-TRANS | https://developer.android.com/develop/sensors-and-location/location/transitions | `$A3/pages/transitions.txt` | 2026-02-26 |
| AR-CODELAB | https://developer.android.com/codelabs/activity-recognition-transition | `$A3/pages/ar_codelab.txt` | (old: uses play-services-location 19.0.1) |
| LS-AR | https://github.com/android/location-samples `ActivityRecognition/` (commit 8110fbc, 2023-08-23) | `$A3/gh/location-samples/ActivityRecognition` | n/a |
| LS-SLEEP | https://github.com/android/location-samples `SleepSampleKotlin/` (same commit) | `$A3/gh/location-samples/SleepSampleKotlin` | n/a |
| PS-AR | https://github.com/android/platform-samples `samples/location/.../useractivityrecog/` (HEAD 0445045, 2026-10-01) | `$A3/gh/platform-samples` | n/a |
| GH-VERS | `gradle/libs.versions.toml` of android/platform-samples, android/snippets, googlemaps-samples/android-samples, googlemaps/android-maps-utils (raw.githubusercontent.com, 2026-10-02) | `$A3/gh/ps_libs.toml` | n/a |
| RECAPI | https://developer.android.com/health-and-fitness/recording-api | `$A3/pages/recording_api.txt` | 2026-01-19 |
| HC-READ | https://developer.android.com/health-and-fitness/health-connect/read-data | `$OLD/pages/g_hc_read.txt` | 2026-09-24 |
| EMU-CON | https://developer.android.com/studio/run/emulator-console | `$A3/pages/emu_console.txt` | 2026-07-17 |
| EMU-EXT | https://developer.android.com/studio/run/emulator-extended-controls | `$A3/pages/emu_extended.txt` | (not shown) |
| ROBO | Robolectric 4.17: `shadows-framework-4.17.jar` (javap) and `ShadowSensorManager.java` source; Maven Central `org/robolectric/robolectric/maven-metadata.xml` (`<release>4.17</release>`, lastUpdated 2026-09-10) | `$OLD/shadows-framework-4.17.jar`, `$OLD/agent1/robo/ShadowSensorManager.java` | n/a |
| DOC01 / DOC02 / DOC05 / DOC08 / DOC10 | Sibling research docs in this folder | - | - |

---

## 1. Summary

### 1.1 Decisions

- **D1. No raw streams are stored.** Sampling code reduces each window to a summary in memory and writes
  only normalized events (section 11). A 10 s accelerometer window becomes one row of about 0.5 KB
  instead of about 500 samples. **design**
- **D2. Three collection tiers.** **design**

  | Tier | When | FGS | Signals |
  |---|---|---|---|
  | 0 "Passive" (default) | Always, in the background | none | Play services activity transitions (`ACTIVITY`), optional Sleep API (`SLEEP_SEGMENT`), steps from Health Connect or the Recording API (`STEP_SAMPLE`), sensor inventory (`SENSOR_INVENTORY`) |
  | 1 "Live" | Only while an Agentle screen is visible | none | Live step-counter number; optional short context snapshot (light, accelerometer) |
  | 2 "High-detail sensing" (opt-in, off by default) | User-started session with an ongoing notification | `health` | Duty-cycled windows: accelerometer, gyroscope (when moving), light, proximity (screen off only), pressure, step counter, significant motion |

- **D3. Raw `SensorManager` sensors are never a background source without an FGS.** On Android 9+
  "Sensors that use the continuous reporting mode ... don't receive events" and "Sensors that use the
  on-change or one-shot reporting modes don't receive events" for background apps; "use a foreground
  service" [A9][SENS-OV].
- **D4. The FGS type for Tier 2 is `health`.** Its description is "Any long-running use cases to support
  apps in the fitness category such as exercise trackers". Its runtime prerequisite is met by the
  runtime `ACTIVITY_RECOGNITION` permission, which Tier 0 needs anyway [FGS-TYPES]. It has no FGS
  timeout; only `dataSync` and `mediaProcessing` (6 h per 24 h) and `shortService` (about 3 min) do
  [FGS-TIMEOUT][FGS-TYPES]. Play needs a Console declaration for FGS types when targeting 34+
  [FGS-TYPES]. Tier 2 therefore ships in internal and debug builds first, and in the Play build only
  after the declaration is accepted (**UNVERIFIED** outcome).
- **D5. Rates stay at or below 50 Hz.** That is `SENSOR_DELAY_GAME` (20,000 us) [SENS-OV], far below the
  200 Hz cap that applies to apps targeting 31+ [SENS-OV]. `HIGH_SAMPLING_RATE_SENSORS` is **not**
  declared.
- **D6. `TYPE_STEP_COUNTER` is not the step source of record.** In the background it gets no events
  (D3). The steps of record come, in order, from Health Connect on-device steps (API 34 with SDK
  extension 20+), then the Recording API (Play services), then Fitbit through the Google Health API
  (doc 05). Health Connect's on-device counting "utilizes the `TYPE_STEP_COUNTER` sensor" [HC-READ], so
  sensor-derived steps are never added to those totals (double counting). Sensor `STEP_SAMPLE`s exist
  for the live number (Tier 1) and for per-window cadence (Tier 2).
- **D7. Activity context comes from the Transition API**, not from Agentle's own classifier: entry and
  exit of `IN_VEHICLE`, `ON_BICYCLE`, `RUNNING`, `STILL`, `WALKING` [AR-TRANS]. The PendingIntent is
  explicit and `FLAG_MUTABLE` (doc 02 section 6.9). Registrations are re-made after boot and app update,
  as Google's samples do [LS-AR][LS-SLEEP].
- **D8. Availability is decided per sensor** with `PackageManager.hasSystemFeature(FEATURE_SENSOR_*)`
  where such a feature constant exists, then `SensorManager.getDefaultSensor(type)`. For the step
  sensors, `null` can also mean "permission not granted": `getDefaultSensor` returns a sensor only "if
  one exists and the application has the necessary permissions" [R-SM]. Section 13.
- **D9. Tier 2 budgets** (**design**, tunable): window 10 s; cadence 5 min while moving, 10 min while
  the screen is on, 30 min while still with the screen off, then paused until significant motion or a
  transition; at most 288 windows and 45 min of partial wake lock per day. Android vitals flags
  partial wake locks of "2 or more hours in a 24-hour period", counting those held "when the app is in
  the background or is running a foreground service" [VITALS-WL].
- **D10. Tests.** Robolectric 4.17 (latest on Maven Central [ROBO]) covers listener-based sensors through
  `ShadowSensorManager`, `SensorBuilder` and `SensorEventBuilder`. It does not shadow
  `requestTriggerSensor`, so trigger sensors are tested through an Agentle `SensorGateway` fake. The
  emulator covers accelerometer, magnetometer, proximity, light, pressure, humidity and temperature
  through `adb emu sensor set` and the Virtual sensors panel [EMU-CON][EMU-EXT]. Activity transitions,
  sleep and step counting need a physical phone (section 14).
- **D11. Android 16 and 17 add no sensor-framework API.** The `android.hardware.Sensor*`,
  `SensorManager` and `TriggerEvent*` stubs are identical in API 34, 37.1 and 37.2-beta1 (normalized
  diff, [SDK]). The relevant changes are elsewhere: `health` FGS and heart-rate permissions (16), job
  quota while an FGS runs (16), memory limits (17). Section 15.
- **D12. minSdk.** From the sensor side, 29 is the natural floor: runtime `ACTIVITY_RECOGNITION` exists
  from 29 [R-PERM], and Google's Sleep sample "targets the preferred minimum API level of 29"
  [LS-SLEEP]. Every framework sensor API used here exists by API 24. Section 16.

### 1.2 What changes by Android version (sensor view)

| API | Android | Change that matters here | Source |
|---|---|---|---|
| 26-27 | 8.x | Background execution limits; FGS start through `startForegroundService` (26) | [SDK] |
| 28 | 9 | Background apps get no events from continuous, on-change or one-shot sensors | [A9] |
| 29 | 10 | `ACTIVITY_RECOGNITION` runtime permission; it guards step counter and step detector only; Play services AR gives no results without it | [A10-PRIV] |
| 31 | 12 | Apps targeting 31+: 200 Hz cap on `registerListener`, `RATE_NORMAL` (about 50 Hz) on direct channels, unless `HIGH_SAMPLING_RATE_SENSORS`; microphone toggle forces the cap; `FLAG_MUTABLE` needed for Play services PendingIntents | [A12-TGT][SENS-OV][LS-AR] |
| 33 | 13 | `BODY_SENSORS_BACKGROUND` for body sensors in the background (heart rate; not used) | [A13-TGT] |
| 34 | 14 | FGS types mandatory (`health` + `FOREGROUND_SERVICE_HEALTH`); mutable PendingIntent with implicit intent throws; Health Connect on-device steps with extension 20 | [FGS-TYPES][A14-TGT][HC-READ] |
| 35 | 15 | `BOOT_COMPLETED` receivers may not start `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` FGS (not `health`); `dataSync` 6 h timeout | [A15-TGT][FGS-TIMEOUT] |
| 36 | 16 | `BODY_SENSORS` replaced by `android.permission.health.*` (affects `TYPE_HEART_RATE` and `health` FGS that relied on body sensors); jobs running alongside an FGS count against job quota | [A16-TGT][A16-ALL] |
| 37 | 17 | No sensor change; app memory limits (`MemoryLimiter:AnonSwap` exit description) | [A17-ALL][A17-TGT][A17-FEAT] |

---

## 2. Platform sensor framework facts

### 2.1 Classes and inventory

- Classes: `SensorManager`, `Sensor`, `SensorEvent`, `SensorEventListener` [SENS-OV];
  `SensorEventListener2.onFlushCompleted` (19), `SensorEventCallback` (24, adds
  `onSensorAdditionalInfo`), `TriggerEventListener`/`TriggerEvent` (18), `SensorDirectChannel` (26) [SDK].
- Three broad categories: motion, environmental, position [SENS-OV].
- Hardware vs software: "The gravity, linear acceleration, rotation vector, significant motion, step
  counter, and step detector sensors are either hardware-based or software-based. The accelerometer and
  gyroscope sensors are always hardware-based" [SENS-MOT]. The AOSP gravity, linear-acceleration and
  rotation-vector sensors "rely on a gyroscope: if a device does not have a gyroscope, these sensors do
  not show up" [SENS-MOT].
- Inventory: `getSensorList(Sensor.TYPE_ALL)`; a device "can have more than one sensor of a given type"
  and one is the default [SENS-OV]. Capabilities: `getMinDelay()`, `getMaxDelay()` (21),
  `getFifoReservedEventCount()`, `getFifoMaxEventCount()` (19), `getPower()` (mA), `getResolution()`,
  `getMaximumRange()`, `getReportingMode()` (21), `isWakeUpSensor()` (21), `isDynamicSensor()` (24),
  `getVendor()`, `getVersion()`, `getStringType()` (20), `getId()` (24) [R-SENSOR][SDK].
- "Android does not require device manufacturers to build any particular types of sensors" [SENS-OV].
  Light is the environment sensor most phones have; the others are "not always available" [SENS-ENV].

### 2.2 Reporting modes

| Mode (constant, API 21) | Meaning [R-SENSOR] | Agentle sensors |
|---|---|---|
| `REPORTING_MODE_CONTINUOUS` | "Events are reported at a constant rate which is set by the rate parameter"; faster if other apps ask for more | accelerometer, gyroscope, (pressure: **UNVERIFIED**, read `getReportingMode()` at runtime) |
| `REPORTING_MODE_ON_CHANGE` | "Events are reported only when the value changes" | step counter ("defined as an `REPORTING_MODE_ON_CHANGE` sensor" [R-SENSOR]); light and proximity (**UNVERIFIED**, AOSP HAL docs unreachable; read at runtime) |
| `REPORTING_MODE_ONE_SHOT` | "Upon detection of an event, the sensor deactivates itself and then sends a single event"; must use `requestTriggerSensor` | significant motion |
| `REPORTING_MODE_SPECIAL_TRIGGER` | "Events are reported as described in the description of the sensor" | step detector ("defined as a `REPORTING_MODE_SPECIAL_TRIGGER` sensor" [R-SENSOR]) |

`registerListener` must not be used for one-shot trigger sensors such as `TYPE_SIGNIFICANT_MOTION`; use
`requestTriggerSensor(TriggerEventListener, Sensor)` and `cancelTriggerSensor` [R-SM]. The trigger
"will be invoked once and then its request to receive trigger events will be canceled. To continue
receiving trigger events, the application must request to receive trigger events again" [R-SM][R-TEL].

### 2.3 Sampling period and rate

- `samplingPeriodUs` is "only a hint to the system. Events may be received faster or slower than the
  specified rate. Usually events are received faster" [R-SM].
- Constants: `SENSOR_DELAY_NORMAL` 200,000 us, `SENSOR_DELAY_UI` 60,000 us, `SENSOR_DELAY_GAME`
  20,000 us, `SENSOR_DELAY_FASTEST` 0 us [SENS-OV]. The constant values are 3, 2, 1 and 0 [SDK]. Since
  API 9 any period in microseconds may be passed [R-SM].
- "There is no public method for determining the rate at which the sensor framework is sending sensor
  events"; compute it from event timestamps. To change the rate, unregister and re-register
  [SENS-OV].
- Best practice: "specify the largest delay that you can" [SENS-OV]. `getMaxDelay()` is the slowest
  supported period; slower requests are served at that rate [R-SENSOR].
- Do as little as possible in `onSensorChanged()`; filter and reduce elsewhere [SENS-OV].

### 2.4 Timestamps

- `SensorEvent.timestamp`: "The time in nanoseconds at which the event happened ... using the same time
  base as `SystemClock.elapsedRealtimeNanos()`" [R-SE]. Agentle stores both `elapsedRealtimeNanos` and
  a wall-clock instant computed once per window as
  `wallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1e6`.
  That is the same conversion Google's AR sample uses for transition events [LS-AR].
- `TYPE_STEP_COUNTER`: "The timestamp of the event is set to the time when the last step for that event
  was taken" [R-SENSOR].
- `SensorEvent.firstEventAfterDiscontinuity` (33) is relevant only to `TYPE_HEAD_TRACKER` [R-SE].

### 2.5 Batching (hardware FIFO)

- `registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs[, handler])` (19): events
  "can be stored in the hardware FIFO up to `maxReportLatencyUs` microseconds. Once one of the events in
  the FIFO needs to be reported, all of the events in the FIFO are reported sequentially" [R-SM].
- `maxReportLatencyUs = 0` is the same as the 3-argument call. If `getFifoMaxEventCount()` is 0, "the
  sensor does not use a FIFO" and batching is a no-op [R-SM][R-SENSOR].
- Positive latency "allows to reduce the number of interrupts the AP (Application Processor) receives,
  hence reducing power consumption ... especially important when registering to wake-up sensors"
  [R-SM].
- `getFifoMaxEventCount()` is shared: "If other applications registered to batched sensors, the actual
  number of events that can be batched might be smaller". `getFifoReservedEventCount()` is "a guarantee
  on the minimum number of events that can be batched" [R-SENSOR]. Agentle sizes windows from the
  reserved count.
- `flush(listener)` (19) returns batched events "as if the maxReportLatency of the FIFO has expired";
  `SensorEventListener2.onFlushCompleted` fires after they are delivered. "If the hardware doesn't
  support flush, it still returns true and a trivial flush complete event is sent" [R-SM].
- `getMaxDelay()` "can be used to estimate when the batch FIFO may be full" [R-SENSOR].

### 2.6 Wake-up and non-wake-up sensors

- Non-wake-up sensors "do not wake the AP out of suspend to report data". Events wait in the FIFO; if it
  fills, "the oldest data is dropped". With no FIFO, "all events generated while the AP was in suspend
  mode are lost" [R-SENSOR, `isWakeUpSensor`].
- Wake-up sensors "wake up the AP to deliver events ... before the maximum reporting latency is elapsed
  or the hardware FIFO gets full" [R-SENSOR].
- With a non-wake-up sensor and the screen off, "the application registering to the sensor must hold a
  partial wake-lock to keep the AP awake, otherwise some events might be lost" [R-SM].
- "Registering to a wake-up sensor has very significant power implications" unless batched [R-SM].
- `getDefaultSensor(type, wakeUp)` (21) picks a variant; `TYPE_PROXIMITY` and `TYPE_SIGNIFICANT_MOTION`
  "are declared as wake-up sensors by default" [R-SM]. `TYPE_PROXIMITY` "is a wake up sensor"
  [R-SENSOR].
- The system "will not disable sensors automatically when the screen turns off" [SENS-OV]; an
  unregistered-too-late listener drains the battery.

### 2.7 Rate limiting (targetSdk 31+)

- For apps targeting 31+, accelerometer, gyroscope and geomagnetic data are limited: `registerListener`
  to 200 Hz (all overloads), `SensorDirectChannel` to `RATE_NORMAL` ("usually about 50 Hz"). Higher
  rates need `HIGH_SAMPLING_RATE_SENSORS`; without it "a `SecurityException` occurs". "If the user turns
  off microphone access using the device toggles, the motion sensors and position sensors are always
  rate-limited" [SENS-OV][A12-TGT].
- Without the permission, `getMinDelay()` "is capped at 5000 microseconds (200 Hz)" and
  `getHighestDirectReportRateLevel()` reports at most `RATE_NORMAL` [R-SENSOR].
- `HIGH_SAMPLING_RATE_SENSORS` (31): "Allows an app to access sensor data with a sampling rate greater
  than 200 Hz", protection level normal [R-PERM].
- Agentle never requests more than 50 Hz (D5), so the cap never bites; the microphone toggle cannot
  degrade it either. Tests should still assert the measured rate (section 11.4 quality fields).

### 2.8 Availability

- "If a default sensor does not exist for a given type of sensor, the method call returns null"
  [SENS-OV]. Reference wording: returns the sensor "if one exists and the application has the necessary
  permissions, or null otherwise" [R-SM].
- Play filtering: `<uses-feature android:name="android.hardware.sensor.*" android:required="false"/>`
  for sensors the app can live without [SENS-OV]. Agentle declares every sensor feature it uses with
  `required="false"` (**design**), so no device is filtered out.
- `PackageManager` feature constants and their API levels [SDK]: `FEATURE_SENSOR_ACCELEROMETER` (8),
  `FEATURE_SENSOR_COMPASS` (8), `FEATURE_SENSOR_GYROSCOPE` (9), `FEATURE_SENSOR_BAROMETER` (9),
  `FEATURE_SENSOR_LIGHT` (7), `FEATURE_SENSOR_PROXIMITY` (7), `FEATURE_SENSOR_STEP_COUNTER` (19),
  `FEATURE_SENSOR_STEP_DETECTOR` (19), `FEATURE_SENSOR_AMBIENT_TEMPERATURE` (21),
  `FEATURE_SENSOR_RELATIVE_HUMIDITY` (21), `FEATURE_SENSOR_HEART_RATE` (20), `FEATURE_HIFI_SENSORS`
  (23), `FEATURE_SENSOR_HINGE_ANGLE` (30), `FEATURE_SENSOR_HEADING` (33). There is no feature constant
  for significant motion, rotation vectors, gravity or linear acceleration.
- Dynamic sensors (24): `registerDynamicSensorCallback`, `getDynamicSensorList`,
  `isDynamicSensorDiscoverySupported` [SDK]. Not used.

### 2.9 Background restriction (all apps, Android 9+)

> "Android 9 limits the ability for background apps to access user input and sensor data. If your app
> is running in the background on a device running Android 9, the system applies the following
> restrictions to your app: ... Sensors that use the continuous reporting mode, such as accelerometers
> and gyroscopes, don't receive events. Sensors that use the on-change or one-shot reporting modes don't
> receive events. If your app needs to detect sensor events on devices running Android 9, use a
> foreground service." [A9]

The sensors overview repeats it under "Only gather sensor data in the foreground" for Android 9 (API 28)
or higher: "detect sensor events either when your app is in the foreground or as part of a foreground
service" [SENS-OV]. The docs do not define "background" more precisely. Agentle treats every state
without a visible activity or a running FGS as background, including WorkManager workers and broadcast
receivers. Whether a running job counts as background for this rule is **UNVERIFIED** (device test
E-S1, section 14.4).

### 2.10 API stability across 34, 37.1 and 37.2-beta1

A normalized, sorted comparison of the public stubs of `Sensor`, `SensorManager`, `SensorEvent`,
`SensorEventListener2`, `SensorEventCallback`, `SensorDirectChannel`, `SensorAdditionalInfo`,
`SensorPrivacyManager` and `TriggerEvent` shows **no API difference** between android-34, android-37.1
and android-37.2-beta1 [SDK]. The newest sensor constants are API 33 (`TYPE_HEADING` = 42,
`TYPE_HEAD_TRACKER`, limited-axes IMUs). The Android 16 and 17 feature pages and the 17 / 17 QPR1
release notes mention no sensor-framework change [A17-FEAT] (the only "sensor" hits are camera and
fingerprint items).

### 2.11 Privacy toggles

`SensorPrivacyManager.Sensors` has only `CAMERA` and `MICROPHONE` [SDK]; there is no public toggle state
for motion sensors. The microphone toggle forces the motion-sensor rate cap (2.7). Developer options may
offer a "Sensors off" quick-settings tile that silences sensors (**UNVERIFIED**, AOSP docs unreachable).
Agentle therefore detects silence at runtime: a window that gets zero events from a present sensor
becomes a `SENSOR_SILENT` coverage gap (section 13.3).

---

## 3. Permissions and API gates

| Need | Requirement | Source |
|---|---|---|
| `TYPE_STEP_COUNTER`, `TYPE_STEP_DETECTOR` | Runtime `android.permission.ACTIVITY_RECOGNITION` (API 29, dangerous) on Android 10+. "The only built-in sensors on the device that require you to declare this permission are the step counter and step detector sensors" | [A10-PRIV][R-PERM][R-SENSOR][SENS-MOT] |
| All other `SensorManager` sensors used here | No permission | [A10-PRIV] (by exclusion) |
| More than 200 Hz (31+) | `HIGH_SAMPLING_RATE_SENSORS` (normal). **Not used** | [SENS-OV][R-PERM] |
| Activity Recognition Transition API, Sleep API | Runtime `ACTIVITY_RECOGNITION` on 29+; manifest `com.google.android.gms.permission.ACTIVITY_RECOGNITION` for 28 and lower. "Some libraries within Google Play services, such as the Activity Recognition API ... don't provide results unless the user has granted your app this permission" | [AR-TRANS][A10-PRIV][LS-AR][LS-SLEEP] |
| Auto-grant for old targets | If the app targets 28 or lower and declares only the gms permission, the system auto-grants `ACTIVITY_RECOGNITION`. Irrelevant for target 37 | [A10-PRIV] |
| Recording API (steps) | Runtime `ACTIVITY_RECOGNITION`; Play services at least `LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE` | [RECAPI] |
| Health Connect steps | `android.permission.health.READ_STEPS`; background reads `READ_HEALTH_DATA_IN_BACKGROUND` | [HC-READ], DOC01 3.42-3.45 |
| Tier 2 FGS | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_HEALTH` (34, normal); type `health`; prerequisite: `HIGH_SAMPLING_RATE_SENSORS` declared **or** one of `BODY_SENSORS` (35 and lower), `READ_HEART_RATE`, `READ_SKIN_TEMPERATURE`, `READ_OXYGEN_SATURATION`, `ACTIVITY_RECOGNITION` granted. Agentle uses `ACTIVITY_RECOGNITION` | [FGS-TYPES][R-PERM] |
| Tier 2 notification | Not needed to start the FGS: "Apps don't need to request the `POST_NOTIFICATIONS` permission in order to launch a foreground service". If it is denied (33+), the FGS notice shows only in the Task Manager | [NOTIF-PERM] |
| Heart rate (not collected) | Target 36+: `READ_HEART_RATE` instead of `BODY_SENSORS`; background: `READ_HEALTH_DATA_IN_BACKGROUND` instead of `BODY_SENSORS_BACKGROUND`; mobile apps also need a privacy-policy activity | [A16-TGT] |
| Play | FGS types must be declared in Play Console (Policy > App content) when targeting 34+. Details of the form are **UNVERIFIED** (support.google.com unreachable) | [FGS-TYPES] |

Manifest (**design**; every element named here exists in [SDK]):

```xml
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" />
<!-- Tier 2 only (can live in a flavor/manifest overlay until Play accepts the declaration) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="false" />
<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />
<uses-feature android:name="android.hardware.sensor.light" android:required="false" />
<uses-feature android:name="android.hardware.sensor.proximity" android:required="false" />
<uses-feature android:name="android.hardware.sensor.barometer" android:required="false" />
<uses-feature android:name="android.hardware.sensor.stepcounter" android:required="false" />

<service
    android:name=".sensing.HighDetailSensingService"
    android:exported="false"
    android:foregroundServiceType="health" />
<receiver android:name=".activity.ActivityTransitionReceiver" android:exported="false" />
<receiver android:name=".activity.SleepSegmentReceiver" android:exported="false" />
<receiver android:name=".sensing.SensingWindowAlarmReceiver" android:exported="false" />
```

Non-exported receivers work for Play services callbacks because a PendingIntent lets the holder act "as
if the other application was yourself (with the same permissions and identity)", and the docs advise an
explicit component [R-PI]. Google's older samples export these receivers [LS-AR][LS-SLEEP]; that is
not required (**inference** from [R-PI]; confirm on a device, test E-AR3).

---

## 4. Background execution model for sensors

### 4.1 Which contexts receive sensor events

| Context | `SensorManager` events | Notes |
|---|---|---|
| Activity resumed (app visible) | yes | Register in `onResume`/`onStart`, unregister in `onPause`/`onStop` [SENS-OV] |
| Running FGS (any type) | yes | "use a foreground service" [A9][SENS-OV] |
| WorkManager worker, JobService, BroadcastReceiver, without an FGS | treat as **no** | "Background" is not defined further; **UNVERIFIED** for jobs (E-S1) |
| Play services callbacks (AR transitions, sleep) | n/a | Delivered by PendingIntent broadcast to a stopped or cached app; no FGS needed [AR-TRANS] |
| Health Connect / Recording API reads | n/a | System or Play services count steps; Agentle reads periodically [HC-READ][RECAPI] |

The motion guide still says "use the `JobScheduler` class to retrieve the current value from the step
counter sensor at a specific interval" [SENS-MOT]. Read together with [A9], that advice only works while
the app is visible or runs an FGS; Agentle does not rely on it.

### 4.2 Tier 2 foreground service: type `health`

- **Type and prerequisites**: see section 3. Description: "Any long-running use cases to support apps in
  the fitness category such as exercise trackers" [FGS-TYPES].
- **While-in-use rule (34+)**: an FGS that needs a while-in-use permission cannot be created from the
  background. "(This doesn't apply if it's a health service that needs different permissions, like
  `ACTIVITY_RECOGNITION`.)" [FGS-BG]. A `health` FGS backed by `ACTIVITY_RECOGNITION` can therefore be
  started from the background whenever a background-start exemption applies.
- **Background-start exemptions that Agentle can use** [FGS-BG]:
  - "Your app transitions from a user-visible state, such as an activity" (user taps "Start").
  - "The user performs an action on a UI element related to your app ... notification" (the "Resume"
    action of the paused-session notification).
  - "Your app receives an event that's related to geofencing or activity recognition transition"
    (optional auto-start on `WALKING`/`RUNNING`/`ON_BICYCLE` entry, if the user enabled it).
  - "After the device reboots and receives the `ACTION_BOOT_COMPLETED` ... or
    `ACTION_MY_PACKAGE_REPLACED` intent action in a broadcast receiver". Android 15's
    `BOOT_COMPLETED` restriction list is `dataSync`, `camera`, `mediaPlayback`, `phoneCall`,
    `mediaProjection`, `microphone`; `health` is not on it [A15-TGT]. **design**: v1 does not
    auto-resume after reboot; it posts a "Sensing paused after restart" notification with a Resume
    action instead (transparent to the user, and still an exemption).
- **No timeout**: only `dataSync` and `mediaProcessing` have the 6-hour-per-24-hour limit (target 35+),
  and `shortService` about 3 minutes [FGS-TIMEOUT][FGS-TYPES]. `health` has none. `Service.onTimeout`
  exists from 34/35 for those types only [SDK].
- **Rejected types** (**design**): `dataSync` (wrong purpose, 6 h/24 h limit); `shortService` ("Quickly
  finish critical work that cannot be interrupted or postponed", about 3 minutes, cannot start other
  FGS [FGS-TYPES]); `specialUse` (free-form use case reviewed in Play Console [FGS-TYPES]; review risk);
  `location` (needs location permission; not the purpose).
- **`HIGH_SAMPLING_RATE_SENSORS` as prerequisite**: technically satisfies the `health` prerequisite
  [FGS-TYPES] but Agentle has no >200 Hz need; declaring it only to unlock the FGS would misstate the
  app's behavior. Not used.
- **Android 16 job quota**: "jobs that are executing concurrently with a foreground service will adhere
  to the job runtime quota" [A16-ALL]. The sensing loop therefore runs inside the service (alarms plus
  wake lock), not as WorkManager work.
- **Android 17 memory limits**: limits "based on the device's total RAM"; an affected exit has
  `REASON_OTHER` with `"MemoryLimiter:AnonSwap"` in `ApplicationExitInfo.getDescription()`; test with
  `am memory-limiter` [A17-ALL]. The sensing service holds no raw buffers beyond one window (about
  500 x 4 floats), so this is a monitoring item, not a design driver.

### 4.3 Doze, alarms and wake locks

- Doze "Ignores wake locks", "Defers standard AlarmManager alarms", and allows
  `setAndAllowWhileIdle()`/`setExactAndAllowWhileIdle()` [DOZE]. App Standby does not treat an app as
  idle while it has "a process currently in the foreground, either as an activity or foreground
  service", but "Don't start a foreground service just to prevent the system from determining that
  your app is idle" [DOZE].
- `setAndAllowWhileIdle` (23, inexact, no exact-alarm permission): the app is added to "the system's
  temporary power exemption list for approximately 10 seconds to allow that application to acquire
  further wake locks". Frequency: "not ... more than about every minute" normally; "when in low-power
  idle modes this duration may be significantly longer, such as 15 minutes" [R-ALARM].
  `setExactAndAllowWhileIdle` needs `SCHEDULE_EXACT_ALARM` for apps targeting 31+ [R-ALARM]; Tier 2
  does not need exactness.
- Whether partial wake locks held by an app with a running FGS are honored during Doze is
  **UNVERIFIED** (AOSP `PowerManagerService` unreachable). Tier 2 tolerates both outcomes: if a window
  is cut short, the summary records fewer samples (quality fields).
- Android vitals: partial wake locks are "excessive" when "all of the partial wake locks, added
  together, run for 2 or more hours in a 24-hour period"; tracked only "when the app is in the
  background or is running a foreground service"; audio, location and JobScheduler user-initiated
  wake locks are exempt [VITALS-WL]. Excessive partial wake locks are a core vital with a 5% bad
  behavior threshold [VITALS]. Tier 2 budgets 45 min per day (D9).


---

## 5. Step counting

### 5.1 Platform semantics

| Property | `TYPE_STEP_COUNTER` (19) | `TYPE_STEP_DETECTOR` (19) |
|---|---|---|
| Value | Steps "since the last reboot while activated", "returned as a float (with the fractional part set to zero)", "reset to zero only on a system reboot" [R-SENSOR] | "The only allowed value to return is 1.0 and an event is generated for each step" [R-SENSOR] |
| Timestamp | "the time when the last step for that event was taken" [R-SENSOR] | When "the foot hit the ground" [R-SENSOR] |
| Reporting mode | ON_CHANGE [R-SENSOR] | SPECIAL_TRIGGER [R-SENSOR] |
| Latency | "up to 10 seconds" and "more accuracy than the step detector sensor" [SENS-MOT] | "expected to be below 2 seconds" [SENS-MOT] |
| Counting rule | "do NOT unregister for this sensor, so that it keeps counting steps in the background even when the AP is in suspend mode"; "step counter does not count steps if it is not activated" [R-SENSOR] | Per-step events only while registered |
| Permission | `ACTIVITY_RECOGNITION` on 29+ [R-SENSOR][A10-PRIV] | same |
| Background (28+) | No events without a visible activity or an FGS [A9] | same |

Consequences for Agentle (**inference** from the quotes above unless marked):

1. The counter is one device-wide value. It rises whenever *any* client keeps it activated. Health Connect's
   on-device counting "utilizes the `TYPE_STEP_COUNTER` sensor" [HC-READ], so on such phones the value keeps
   rising while Agentle is not registered. Without any active client the reference says it does not count.
2. A difference between two Agentle readings separated by an unregistered gap is therefore a **lower
   bound** with no time resolution inside the gap. It is flagged `ACROSS_GAP` (5.2).
3. Agentle cannot follow the "do NOT unregister" advice in the background without an FGS (D3). Tier 2 keeps
   the counter registered for the whole session; Tier 1 only while a screen is visible.
4. Whether a new registration immediately delivers the current value (an initial on-change event) is
   **UNVERIFIED** (AOSP HAL docs unreachable). The bookkeeping takes the first event as the baseline,
   whenever it arrives, and calls `flush()` at each Tier 2 window to drain batched events [R-SM].
5. With a non-wake-up counter registered with batching, a FIFO overflow drops the oldest events [R-SENSOR],
   but each counter event carries the running total, so the next delivered event still gives the right
   delta.
6. `TYPE_STEP_DETECTOR` is **not collected** in v1 (**design**). Per-step events add wake-ups and give no
   information that the counter's per-window delta lacks at Agentle's time resolution (minutes).

### 5.2 Delta bookkeeping across process death and reboot (design)

State `StepCounterState(bootCount, bootWallMs, lastCounter, lastWallMs)` is persisted in the same Room
transaction as the events it produces, so a crash cannot double count.

- `bootCount` comes from `Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)`. `BOOT_COUNT` is
  "Boot count since the device starts running API level 24", type int, added in API 24 [R-SG][SDK]. It is
  always present at Agentle's minSdk.
- `bootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()` is the fallback when
  `BOOT_COUNT` reads -1. It shifts when the wall clock is changed, hence a 60 s tolerance.

Per counter reading `v`:

1. `counter = v.roundToLong()`. A `Float` holds every integer only up to 2^24 = 16,777,216 (asserted by test,
   14.2). At or above that, set `precisionLoss = true`. At 10,000 steps a day that needs about 4.6 years
   without a reboot (**estimate**).
2. **Reboot** when `bootCount` changed (both readable), or else when `bootWallMs` moved by more than 60 s.
   The counter restarted at zero at boot [R-SENSOR], so emit:
   - `SENSOR_GAP(REBOOT)` from the last reading to the boot instant: steps before shutdown are lost.
   - `STEP_SAMPLE(start = max(lastWallMs, bootWallMs), end = now, steps = counter, basis = SINCE_BOOT)`.
3. **Decrease without a detected reboot** (`counter < lastCounter`): re-baseline and emit
   `SENSOR_GAP(COUNTER_RESET)`. A negative delta is never emitted.
4. **Otherwise** `delta = counter - lastCounter`. The basis is `CONTINUOUS` if Agentle was registered the
   whole time, else `ACROSS_GAP`.
5. **Plausibility**: more than 300 steps per minute of interval plus 50 marks the sample `implausible`. It
   is kept for diagnostics and excluded from use.

Sketch (compiled and unit-tested in the harness of 14.2; abridged):

```kotlin
object StepBookkeeper {
    const val EXACT_FLOAT_LIMIT: Long = 16_777_216L          // 2^24

    fun advance(prev: StepCounterState?, r: StepReading): StepAdvance {
        val counter = r.counterValue.toDouble().roundToLong()
        val precisionLoss = counter >= EXACT_FLOAT_LIMIT
        val next = StepCounterState(r.bootCount, r.bootWallMs, counter, r.eventWallMs)
        if (prev == null) return StepAdvance(next, emptyList())          // baseline only
        val rebooted = if (prev.bootCount >= 0 && r.bootCount >= 0) r.bootCount != prev.bootCount
                       else abs(r.bootWallMs - prev.bootWallMs) > 60_000L
        if (rebooted) return StepAdvance(next, listOf(
            StepOutput.Gap(prev.lastWallMs, r.bootWallMs, GapReason.REBOOT),
            sample(maxOf(prev.lastWallMs, r.bootWallMs), r.eventWallMs, counter, StepBasis.SINCE_BOOT, precisionLoss)))
        if (counter < prev.lastCounter) return StepAdvance(next,
            listOf(StepOutput.Gap(prev.lastWallMs, r.eventWallMs, GapReason.COUNTER_RESET)))
        val basis = if (r.registeredSincePrevious) StepBasis.CONTINUOUS else StepBasis.ACROSS_GAP
        return StepAdvance(next, listOf(
            sample(prev.lastWallMs, r.eventWallMs, counter - prev.lastCounter, basis, precisionLoss)))
    }
}
```

The Android side reads `event.values[0]` and converts `event.timestamp` with the 2.4 formula
(`eventWallMs()` in the harness, compiled against android-37.1).

### 5.3 Step sources of record and de-duplication

| Source | When used | Facts |
|---|---|---|
| Canonical source chosen under doc 05 7.7 (Google Health API for wearable users, or Health Connect) | Always, when connected | "Never sum across sources", one canonical source per metric (DOC05 7.7) |
| Health Connect on-device steps | Android 14 (API 34) with SDK extension 20+ | Check `SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 20`. Counting "is active only when at least one application on the device has been granted the `READ_STEPS` permission". Steps are written "no more frequently than once per minute". Attribution is `"android"` before the June 2026 update and a device-specific synthetic package name (SPN) after it. Use `aggregate()` "to avoid double counting" [HC-READ] |
| Recording API (`play-services-fitness`, `LocalRecordingClient`) | No Health Connect on-device steps (API < 34 or extension < 20) | `TYPE_STEP_COUNT_DELTA`; accountless, on-device; "data since the latest subscription - for up to 10 days - is accessible"; unsubscribing loses the data; needs `ACTIVITY_RECOGNITION` and Play services at least `LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE`; read periodically with WorkManager; the guide uses `play-services-fitness:21.2.0` [RECAPI]. Minimum Android version: **UNVERIFIED** (DOC01 3.29) |
| `TYPE_STEP_COUNTER` (this doc) | Tier 1 live display, Tier 2 per-window cadence, diagnostics | `STEP_SAMPLE` with `src = SENSOR_COUNTER`. **Never** part of canonical totals (D6) |

Rules (**design**):

- The Recording API reader writes doc 10's `interval_obs` rows (steps) with source `RECORDING_API` and
  publishes `source_coverage`. It is used only when Health Connect on-device steps are unavailable, never in
  addition to them. Both count steps from the same hardware counter (Health Connect says so [HC-READ]; for
  the Recording API this is **UNVERIFIED**).
- Tier 1's live number is the canonical total for today plus a separately labelled "+N since you opened
  this screen" taken from the sensor. The two numbers are never added into one stored value.
- A diagnostic compares sensor deltas with canonical deltas over the same Tier 2 interval. The result goes
  to the debug log only.
- A device with neither Health Connect on-device steps nor the Recording API (no Play services, API < 34)
  has no background step source in v1. The `steps_*` features then resolve to `Missing(NO_DATA)` (doc 10
  5.2); Agentle does not run a permanent FGS to fill the gap.

---

## 6. Google Play services Activity Recognition: Transition API and Sleep API

### 6.1 Library and version

- Artifact `com.google.android.gms:play-services-location`. The newest version visible in reachable sources
  is **21.4.0**. It is pinned in the version catalogs of android/platform-samples, android/snippets,
  googlemaps-samples/android-samples and googlemaps/android-maps-utils (read 2026-10-02 [GH-VERS]), and
  DOC07 pins it too.
- Whether a newer release exists is **UNVERIFIED**: `maven.google.com` metadata redirects to the blocked
  `dl.google.com`, and the release notes live on the blocked `developers.google.com`.
- The guide only requires "version 12.0.0 or higher" [AR-TRANS]. Google's samples are older:
  location-samples uses 18.0.0 and the codelab 19.0.1 [LS-AR][LS-SLEEP][AR-CODELAB].
- Calls return a Play services `Task`. Both samples await it with `kotlinx-coroutines-play-services`
  [LS-AR][PS-AR].

### 6.2 Transition API (Tier 0, `ACTIVITY` events)

- **Activities**: `IN_VEHICLE`, `ON_BICYCLE`, `RUNNING`, `STILL`, `WALKING`, each with
  `ACTIVITY_TRANSITION_ENTER` or `ACTIVITY_TRANSITION_EXIT` [AR-TRANS].
- **`DetectedActivity` integer values**, from the platform-samples mapping [PS-AR]:

  | Value | Activity |
  |---|---|
  | 0 | IN_VEHICLE |
  | 1 | ON_BICYCLE |
  | 2 | ON_FOOT |
  | 3 | STILL |
  | 4 | UNKNOWN |
  | 5 | TILTING |
  | 7 | WALKING |
  | 8 | RUNNING |

  location-samples also registers `ON_FOOT` ENTER [LS-AR], but the guide lists only the five above.
  Agentle requests only the five (**design**); whether `ON_FOOT` transitions are supported is
  **UNVERIFIED**.
- **Request**: all ten pairs (5 activities x ENTER/EXIT). The guide's example registers both directions.
  location-samples registers ENTER only, "because entering a new activity type implies exiting the old
  one" [LS-AR]. Agentle also takes EXIT so an interval can close even when the next ENTER is late
  (**design**).
- **Delivery**: `ActivityTransitionResult.hasResult(intent)` and `extractResult(intent)`. The events "are
  ordered in chronological order" [AR-TRANS]. Each event carries `activityType`, `transitionType` and
  `elapsedRealTimeNanos` [LS-AR]. "The latency of event detection might vary by device" [AR-TRANS].
- **Removal**: `removeActivityTransitionUpdates(pendingIntent)`, then `pendingIntent.cancel()` [AR-TRANS].
  Do not copy platform-samples here. It deregisters with `removeActivityUpdates` (the legacy API's remove
  call) and uses an implicit `Intent(action)` [PS-AR].
- **PendingIntent** (target 37; compiled in the harness):

  ```kotlin
  PendingIntent.getBroadcast(context, RC_TRANSITIONS,
      Intent(context, ActivityTransitionReceiver::class.java),            // explicit component
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
  ```

  - **Mutable**: "must use FLAG_MUTABLE in order for Play Services to add the result data to the intent
    starting in API level 31. Otherwise the BroadcastReceiver will be started but the Intent will have no
    data" [LS-AR].
  - **Explicit**: a mutable PendingIntent with an implicit intent throws for apps targeting 34+ [A14-TGT]
    (DOC02 6.9).
  - **`FLAG_UPDATE_CURRENT`, not location-samples' `FLAG_CANCEL_CURRENT`**: `FLAG_CANCEL_CURRENT` means "the
    current one should be canceled before generating a new one" [R-PI]. Rebuilding the PendingIntent after a
    process restart, to remove or re-register, would cancel the token Play services already holds
    (**inference**).
  - **Same token after restart**: the same request code and the same explicit Intent give back the same
    token, because "A PendingIntent itself is simply a reference to a token maintained by the system"
    [R-PI].
- **Re-registration**: on every app start, on `BOOT_COMPLETED` and on `MY_PACKAGE_REPLACED`, as
  location-samples' `BootReceiver` does. That receiver also lists the vendor `QUICKBOOT_POWERON` actions
  [LS-AR][LS-SLEEP].
  - On Android 15 a force-stop cancels all of the app's pending intents (DOC01 3.28).
  - Whether registrations survive reboot or update on their own is **UNVERIFIED** (DOC02 U7).
  - Re-registering with the same PendingIntent is treated as idempotent (**inference**).
- **Receiver**: `exported="false"` (section 3; test E-AR3). It parses the events, then persists them and the
  coverage interval inside `goAsync()` or a WorkManager job (DOC02). location-samples launches a coroutine on
  `MainScope()` from `onReceive` [LS-AR], which does not keep the process alive; Agentle does not copy
  that (**inference**).
- **Gating**: `GoogleApiAvailability.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS`,
  as location-samples' `PlayServicesAvailabilityChecker` does [LS-AR]. This settles DOC01's **UNVERIFIED**
  API name.
  - Any other result maps to `UNAVAILABLE` with blocker `PLAY_SERVICES_MISSING`.
  - A denied `ACTIVITY_RECOGNITION` maps to `DENIED` or `DENIED_PERMANENTLY`.
  - A failed `Task` maps to `UNAVAILABLE` and is retried with backoff.
- **Coverage**: after a successful registration, open `collector_coverage("activity_transitions", fromMs,
  toMs = null)`. Close it on removal, on permission loss, on a failed `Task`, or at app start when the last
  registration predates the last boot or a force-stop.
- **FGS exemption**: "Your app receives an event that's related to geofencing or activity recognition
  transition" allows an FGS start from the background [FGS-BG]. Agentle may auto-start Tier 2 on a `WALKING`,
  `RUNNING` or `ON_BICYCLE` ENTER, only if the user turned that on (off by default; **design**).
- **Not used**: the legacy periodic `requestActivityUpdates(intervalMillis, pendingIntent)` with
  per-activity confidences (**design**). Transitions already match the engine's event semantics
  (`ACTIVITY_STATE_CHANGED`, doc 10 7.2) and cost less to consume.

### 6.3 Sleep API (optional, off by default in v1)

- **Registration**: `ActivityRecognition.getClient(context).requestSleepSegmentUpdates(pendingIntent,
  SleepSegmentRequest.getDefaultSleepSegmentRequest())`. This "Registers for both [SleepSegmentEvent] and
  [SleepClassifyEvent] data". Removal is `removeSleepSegmentUpdates(pendingIntent)` [LS-SLEEP].
- **Parsing**: `SleepSegmentEvent.hasEvents/extractEvents(intent)` and
  `SleepClassifyEvent.hasEvents/extractEvents(intent)` [LS-SLEEP].
  - Segment fields: `startTimeMillis`, `endTimeMillis`, `status`.
  - Classify fields: `timestampMillis`, `confidence`, `motion`, `light`.
  - Value ranges, emission cadence and the status constants are **UNVERIFIED** (the reference is on the
    blocked `developers.google.com`). Agentle stores the raw integers.
- **API level**: the sample "targets the preferred minimum API level of 29", and its manifest says "we
  recommend only using the Sleep APIs with 29 and above" [LS-SLEEP].
- **PendingIntent**: the sample (target 30) uses `FLAG_CANCEL_CURRENT` without `FLAG_MUTABLE` [LS-SLEEP].
  At target 37 Agentle uses the transition flags (explicit, `FLAG_UPDATE_CURRENT or FLAG_MUTABLE`), because
  Play services again fills the extras (**inference** from [LS-AR]).
- **Tests**: "Constructor isn't accessible for [SleepClassifyEvent]" [LS-SLEEP], so tests go through the
  domain mapping (6.4).
- **Re-registration**: on boot, like the sample's `BootReceiver` [LS-SLEEP].
- **Role**: `SLEEP_SEGMENT` and `SLEEP_CLASSIFY` events are kept for evaluation only. The canonical sleep
  source stays doc 05's `sleep_session`/`sleep_stage`. Sleep API data does not trigger engine events in v1
  (**design**).

### 6.4 Testing seam

- **JVM**: an `ActivityRecognitionGateway` interface (register, unregister, availability) and a parser from
  `Intent` to a domain list `TransitionObservation(activityRaw, transitionRaw, elapsedNs)`. The mapping from
  domain values to `ACTIVITY` payloads is a pure JVM function.
- **Robolectric**: Play services has no shadows (DOC01). Robolectric tests use the gateway fake.
- **Constructors**: whether `ActivityTransitionEvent` and `ActivityTransitionResult` have public
  constructors usable for fake Intents is **UNVERIFIED** (the library jar is on blocked Google Maven).
- **Emulator**: "It's hard to reproduce activity changes on the emulator, so we recommend using a physical
  device" [AR-CODELAB].
- **Device**: device tests E-AR1 to E-AR4 (14.4).

---

## 7. Health Connect and the Recording API as step alternatives

| Aspect | Health Connect on-device steps | Recording API | `TYPE_STEP_COUNTER` (Agentle) |
|---|---|---|---|
| Availability | API 34 + extension 20 [HC-READ] | Play services >= `LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE` [RECAPI]; minimum Android version **UNVERIFIED** | `FEATURE_SENSOR_STEP_COUNTER` + `getDefaultSensor` |
| Permission | `READ_STEPS`; background reads need `READ_HEALTH_DATA_IN_BACKGROUND` [HC-READ] (DOC01 3.42-3.45) | `ACTIVITY_RECOGNITION` [RECAPI] | `ACTIVITY_RECOGNITION` |
| Who counts in the background | The system, using `TYPE_STEP_COUNTER` [HC-READ] | Play services | Nobody, unless Tier 2 runs |
| Granularity | Batched, written "no more frequently than once per minute" [HC-READ] | `readData` with `bucketByTime` (example: 1 day) [RECAPI]; finest bucket **UNVERIFIED** | Per event (latency up to 10 s) |
| Retention | Health Connect's own; Agentle reads within the 30-day window (DOC05 6.3) | Up to 10 days since the latest subscription [RECAPI] | Agentle's own |
| Attribution | `"android"`, then a device SPN from June 2026; `getCurrentDeviceDataSource()` to identify it [HC-READ] | Not documented on the page | Agentle |
| Double counting | `aggregate()` handles duplicates [HC-READ]; one canonical source (DOC05 7.7) | Never combined with Health Connect on-device steps (5.3) | Never in totals |

Health Connect's guide recommends the Recording API for older devices: "If your app has significant users
on Android 13 and lower, we recommend also maintaining or adding an integration with the local Recording
API" (quoted in DOC01 3.29 from [HC-READ]). Requesting `READ_STEPS` also turns on the on-device counting
itself, because counting "is active only when at least one application on the device has been granted the
`READ_STEPS` permission" [HC-READ].

---

## 8. What Android exposes about "device motion"

### 8.1 Public sensor types (android-37.1 stubs)

The public `Sensor.TYPE_*` constants are numbers 1-21, 28-31 and 34-42, plus `TYPE_DEVICE_PRIVATE_BASE`
(65536). Numbers 22-27 and 32-33 have **no public constant** [SDK]. Agentle never uses them or any vendor
type at or above 65536.

| Group | Types (API) | Notes |
|---|---|---|
| Raw motion | `ACCELEROMETER` (3), `GYROSCOPE` (3), `MAGNETIC_FIELD` (3); uncalibrated variants (`MAGNETIC_FIELD_UNCALIBRATED`, `GYROSCOPE_UNCALIBRATED` 18, `ACCELEROMETER_UNCALIBRATED` 26); `*_LIMITED_AXES` (33) | Accelerometer and gyroscope are always hardware [SENS-MOT]; flat on a table the accelerometer reads +9.81 on Z [R-SE] |
| Fused motion and orientation | `GRAVITY`, `LINEAR_ACCELERATION`, `ROTATION_VECTOR` (9); `GAME_ROTATION_VECTOR` (18); `GEOMAGNETIC_ROTATION_VECTOR` (19); `ORIENTATION` (3, deprecated in 15: "use `SensorManager.getOrientation()`") | The AOSP fused sensors need a gyroscope [SENS-MOT]; the geomagnetic rotation vector uses "lower power" but "is more noisy" [R-SENSOR] |
| High-end pose | `POSE_6DOF` (24, "expected to be a high power sensor and expected only to be used when the screen is on"); `HEADING` (33); `HEAD_TRACKER` (33, "typically not available for apps to use") | [R-SENSOR] |
| Motion detectors | `SIGNIFICANT_MOTION` (18): one-shot, wake-up, "continues to operate while the device is asleep", "does not need to hold any wake locks"; `STATIONARY_DETECT`, `MOTION_DETECT` (24): event after "at least 5 seconds" of rest or motion, "maximal latency of 5 additional seconds", value 1.0; `STEP_DETECTOR`, `STEP_COUNTER` (19) | [R-SENSOR][R-SE]. Reporting mode of stationary/motion detect: read at runtime (**UNVERIFIED**); availability on phones **UNVERIFIED** |
| Body and form factor | `LOW_LATENCY_OFFBODY_DETECT` (26, wearables), `HINGE_ANGLE` (30, foldables), `HEART_RATE` (20), `HEART_BEAT` (24) | Inventory only |

### 8.2 Motion signals outside `SensorManager`

- Play services activity transitions and the Sleep API (section 6).
- Health Connect steps and exercise sessions, the Recording API, and the Google Health API (doc 05,
  section 7).
- UI orientation, not device motion:
  - `Display.getRotation()` (8), reached through `Context.getDisplay()` (30) [SDK].
  - `OrientationEventListener` (3): built on sensors, so it is foreground-only like them (**inference**).
  - The auto-rotate setting `Settings.System.ACCELEROMETER_ROTATION` (3) [SDK].
- Screen and power state: `PowerManager.isInteractive()` (20) and `isDeviceIdleMode()` (23) [SDK]. These are
  not motion, but they gate Tier 2.
- **Not exposed**: no public API reports "in pocket", "in hand", "on table" or "picked up". The phone
  gesture sensors are not public (8.1). Agentle derives only `posture` (FACE_UP, FACE_DOWN, UPRIGHT,
  TILTED) and `moving` from its own accelerometer windows, plus a proximity state. A "pocket" heuristic
  (proximity NEAR + DARK light + UPRIGHT) is possible but unvalidated (**UNVERIFIED** accuracy; not in v1).

### 8.3 What Agentle records as "device motion"

| Signal | Tier | Normalized event |
|---|---|---|
| Activity transitions | 0 | `ACTIVITY` |
| Accelerometer and gyroscope windows | 2 | `DEVICE_MOTION_SUMMARY` (ENMO, posture, moving, gyroscope RMS) |
| Significant motion triggers | 2 | `SIGNIFICANT_MOTION` |
| Step deltas | 1, 2 | `STEP_SAMPLE` |
| Sensor capability snapshot | 0 | `SENSOR_INVENTORY` |

---

## 9. Per-sensor and per-signal plan

Tiers are defined in D2: 0 = passive, 1 = live while an Agentle screen is visible, 2 = opt-in `health` FGS
session. "Window" means a Tier 2 sampling window (10 s, section 10). Test ids are defined in 14.4.

### 9.1 Platform sensors that Agentle collects

| Sensor (API) | Availability check | Permission | Sampling plan | Background behaviour | Normalized event | Test approach |
|---|---|---|---|---|---|---|
| `TYPE_STEP_COUNTER` (19) | `FEATURE_SENSOR_STEP_COUNTER`; `getDefaultSensor` only after the grant (it returns null without "the necessary permissions" [R-SM]) | `ACTIVITY_RECOGNITION` (29+) | **Tier 1**: register while visible, `SENSOR_DELAY_NORMAL`, latency 0. **Tier 2**: registered for the whole session, `maxReportLatencyUs` 300 s, `flush()` at each window | No events without a visible activity or FGS [A9]. Non-wake-up by default; batched in the FIFO while the AP sleeps; the running total survives FIFO overflow (5.1) | `STEP_SAMPLE` (`src = SENSOR_COUNTER`, non-canonical) | JVM: `StepBookkeeper` (8 tests, 14.2). Robolectric: `SensorBuilder.setType(TYPE_STEP_COUNTER)` + `SensorEventBuilder`. Device: E-S2 (reboot), E-S6 (batching) |
| `TYPE_SIGNIFICANT_MOTION` (18) | `getDefaultSensor` (no feature constant) | none | **Tier 2 only**: `requestTriggerSensor` on entering `PAUSED_STILL`; re-armed after each trigger; cancelled at session end | Wake-up, one-shot, works while asleep without a wake lock [R-SENSOR]; delivered to the FGS process. Without an FGS (28+) one-shot sensors get no events [A9] | `SIGNIFICANT_MOTION` | JVM: `SensorGateway` fake (Robolectric does not shadow `requestTriggerSensor` [ROBO]). Device: E-S3 |
| `TYPE_ACCELEROMETER` (3) | `FEATURE_SENSOR_ACCELEROMETER` + `getDefaultSensor` | none | **Tier 2**: every window, 20,000 us (50 Hz), latency 0, partial wake lock held for 12 s. **Tier 1** (optional): one 5 s snapshot when the "Now" screen opens | FGS only. Non-wake-up: the FIFO drops the oldest events when full [R-SENSOR], so the window holds a wake lock | `DEVICE_MOTION_SUMMARY` | JVM: `MotionSummarizer` (14.2). Robolectric: inject flat, upright and walking-like sequences. Emulator: `sensor set acceleration x:y:z`. Device: E-S1, E-S4 |
| `TYPE_GYROSCOPE` (3) | `FEATURE_SENSOR_GYROSCOPE` | none | **Tier 2**: only when the policy says `MOVING`, 50 Hz in the same window | As accelerometer | `DEVICE_MOTION_SUMMARY.gyroRmsRadS` | Robolectric; emulator Virtual sensors "Device Pose" rotation [EMU-EXT]; console name `gyroscope` (14.5) |
| `TYPE_LIGHT` (3) | `FEATURE_SENSOR_LIGHT` | none | **Tier 2**: every window, `SENSOR_DELAY_NORMAL`. On-change, so a window may get no event; the summary then carries the last known value and its age | FGS only | `AMBIENT_LIGHT_SUMMARY` | JVM: `LightSummarizer`. Emulator: `sensor set light <lux>`. Robolectric |
| `TYPE_PROXIMITY` (3) | `FEATURE_SENSOR_PROXIMITY`; prefer `getDefaultSensor(TYPE_PROXIMITY, false)` (non-wake-up) and fall back to the default wake-up sensor [R-SM] | none | **Tier 2**: only while `PowerManager.isInteractive()` is false. Some under-display sensors show "a blinking dot ... if enabled while the screen is on" [SENS-POS]. `SENSOR_DELAY_NORMAL`. Near/far uses `getMaximumRange()` and a 5 cm threshold [SENS-POS][R-SE] | FGS only | `PROXIMITY_SUMMARY` | JVM: `ProximitySummarizer` (binary and distance sensors). Robolectric: `SensorBuilder.setMaximumRange(5f)`. Emulator: `sensor set proximity 0` |
| `TYPE_PRESSURE` (3) | `FEATURE_SENSOR_BAROMETER` | none | **Tier 2**: every window, `SENSOR_DELAY_NORMAL`; mean and SD in hPa; altitude via `SensorManager.getAltitude(PRESSURE_STANDARD_ATMOSPHERE, p)`, used only for altitude **differences** [R-SM] | FGS only | `PRESSURE_SUMMARY` | JVM: `PressureSummarizer`. Emulator: `sensor set pressure <hPa>` |

### 9.2 Platform sensors that Agentle does not collect in v1

| Sensor (API) | Availability check | Permission | Sampling plan | Background behaviour | Normalized event | Test approach |
|---|---|---|---|---|---|---|
| `TYPE_STEP_DETECTOR` (19) | `FEATURE_SENSOR_STEP_DETECTOR` | `ACTIVITY_RECOGNITION` | Not collected (5.1 item 6) | No events without an FGS [A9] | none (`SENSOR_INVENTORY` only) | n/a |
| `TYPE_GRAVITY`, `TYPE_LINEAR_ACCELERATION` (9) | `getDefaultSensor` (no feature constant; the AOSP versions need a gyroscope [SENS-MOT]) | none | Not collected. The gravity estimate is the accelerometer window mean | FGS only | none | n/a |
| Rotation vectors (9/18/19) | `getDefaultSensor` | none | Not collected; posture from gravity is enough. A debug-only orientation view may use `ROTATION_VECTOR` + `getRotationMatrixFromVector` + `getOrientation` while visible | Foreground only | none | Robolectric (debug view only) |
| `TYPE_MAGNETIC_FIELD` (3) | `FEATURE_SENSOR_COMPASS` | none | Not collected | FGS only | none | n/a |
| `TYPE_AMBIENT_TEMPERATURE`, `TYPE_RELATIVE_HUMIDITY` (14) | `FEATURE_SENSOR_AMBIENT_TEMPERATURE` / `FEATURE_SENSOR_RELATIVE_HUMIDITY` | none | Not collected: "not always available" [SENS-ENV], little value | FGS only | `SENSOR_INVENTORY` only | n/a |
| Stationary/motion detect (24), off-body (26), hinge (30), heading (33), pose (24), head tracker (33) | `getDefaultSensor`; `FEATURE_SENSOR_HINGE_ANGLE` (30), `FEATURE_SENSOR_HEADING` (33) | none | Not collected | n/a | `SENSOR_INVENTORY` only | n/a |
| `TYPE_HEART_RATE` (20), `TYPE_HEART_BEAT` (24) | `FEATURE_SENSOR_HEART_RATE` | `BODY_SENSORS` (35 and lower); `READ_HEART_RATE` for target 36+ [A16-TGT] | Not collected (DOC01 `body_sensor_heart_rate` DEFER) | Background needs `READ_HEALTH_DATA_IN_BACKGROUND` (36+) [A16-TGT] | none | n/a |

### 9.3 Non-sensor activity signals

| Signal | Availability check | Permission | Sampling plan | Background behaviour | Normalized event | Test approach |
|---|---|---|---|---|---|---|
| Activity Recognition Transition API | `GoogleApiAvailability...SUCCESS` [LS-AR] | `ACTIVITY_RECOGNITION` (29+); gms permission for 28 and lower [AR-TRANS][A10-PRIV] | Event-driven: 10 transitions (6.2) | Delivered by PendingIntent to a stopped app; no FGS [AR-TRANS] | `ACTIVITY`, engine event `ACTIVITY_STATE_CHANGED` | JVM: mapping. Device: E-AR1 to E-AR3 |
| Sleep API (optional) | Same | `ACTIVITY_RECOGNITION` | Event-driven, default request (6.3) | PendingIntent | `SLEEP_SEGMENT`, `SLEEP_CLASSIFY` | Device: E-AR4 |
| Health Connect on-device steps | API 34 + extension 20 | `READ_STEPS` (+ background read) | Periodic reads by the health connector (doc 05) | System counts; Agentle reads | `interval_obs` (doc 05/10), not `STEP_SAMPLE` | doc 05 6.8 |
| Recording API | Play services >= `LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE` | `ACTIVITY_RECOGNITION` | Subscribe once; read with WorkManager every 15-60 min and at app open; 10-day buffer [RECAPI] | Play services counts | `interval_obs` (source `RECORDING_API`) | JVM fake client; device |

---

## 10. Sampling plan and the Tier 2 service

### 10.1 Tier 0 and Tier 1

- **Tier 0** uses no `SensorManager` sensors. It consists of:
  - registrations: transitions and, optionally, sleep;
  - periodic reads: canonical steps (doc 05) or the Recording API;
  - a `SENSOR_INVENTORY` snapshot at first run, after `MY_PACKAGE_REPLACED`, and when `Build.FINGERPRINT`
    changes.
- **Tier 1** listens only while an Agentle screen is visible, through a lifecycle-aware collector started
  in `STARTED`:
  - the step counter (live "+N since you opened this screen");
  - an optional 5 s accelerometer + light snapshot for the "Now" card.
  - Doc 01 classifies `ambient_proximity_sensors` as foreground-only, debug only. Tier 1 snapshots ship
    behind the same debug flag in v1.

### 10.2 Tier 2 policy (implemented as `Tier2Policy` in the harness)

| Mode | Entered when | Next window | Extra sensors |
|---|---|---|---|
| `MOVING` | Last window `moving`, or the last transition ENTER was `WALKING`, `RUNNING`, `ON_BICYCLE` or `IN_VEHICLE` | 5 min | gyroscope; proximity if the screen is off |
| `SCREEN_ON` | `isInteractive()` | 10 min | none |
| `STILL` | Otherwise, fewer than 3 consecutive still windows | 30 min | proximity |
| `PAUSED_STILL` | 3 consecutive still windows | none; arm significant motion | none |
| `PAUSED_POWER` | Battery Saver on (`isPowerSaveMode`, 21), or battery <= 15% and not charging (`BATTERY_PROPERTY_CAPACITY` 21, `isCharging` 23) [SDK] | none | none |
| `PAUSED_BUDGET` | 288 windows today, or the next window would push today's wake-lock time over 45 min | none until the engine-day rollover | none |

- **Wake-ups from `PAUSED_STILL`**:
  - a significant-motion trigger;
  - an `ACTIVITY` ENTER of a moving activity;
  - `ACTION_SCREEN_ON`, through a receiver registered at runtime by the service (it cannot be
    manifest-declared, doc 10 7.2).
- **Devices without significant motion** use a 60-minute alarm instead of the pause (**design**).
- **Session limits**: a session lasts at most 24 h. It then stops and posts "Sensing session ended", so a
  forgotten session does not run for weeks (**design**, user-adjustable).

### 10.3 One window (Option A, the v1 default)

1. The alarm fires.
   - **API 37+**: `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, t, tag, executor, listener)`. This
     overload is new in API 37 [SDK][A17-FEAT]; the callback runs in the service process.
   - **API 26-36**: `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, t, pendingIntent)` with an explicit,
     immutable PendingIntent to `SensingWindowAlarmReceiver`.
   - **No exact-alarm permission on either path**. `setAndAllowWhileIdle` is inexact [R-ALARM]. For the
     listener alarm: "If the exact alarm is set using an `OnAlarmListener` object ... the
     `SCHEDULE_EXACT_ALARM` permission isn't required" [EXACT-ALARM]. That note names `setExact`; that it
     covers the API 37 overload is an **inference**, checked in E-S4.
   - **Process requirement**: listener alarms "may be canceled by the Android system whenever the calling
     process no longer has any components running" [R-ALARM]. The running FGS is such a component.
2. Acquire the partial wake lock with a timeout of `W + 2 s` (`acquire(long)`).
3. `flush()` the step-counter listener; read its delta (section 5).
4. Register the window sensors on a `HandlerThread`:
   - accelerometer at 20,000 us, latency 0;
   - gyroscope if `MOVING`;
   - light and pressure at `SENSOR_DELAY_NORMAL`;
   - proximity if the screen is off.
5. Collect for `W = 10 s` with `withTimeoutOrNull`. Cancelling the collection unregisters every listener
   (`awaitClose`). The system "will not disable sensors automatically when the screen turns off"
   [SENS-OV], so this matters.
6. Summarize in memory (section 11). Write the events and the coverage update in one Room transaction,
   release the wake lock, call `Tier2Policy.decide` and schedule the next window.

**Doze.** Wake-lock behavior for a process in foreground-service state during Doze is **UNVERIFIED**
(4.3). An allow-while-idle alarm puts the app on the temporary allowlist "for approximately 10 seconds"
[R-ALARM]. If E-S4 shows that wake locks are not honored, `W` drops to 8 s whenever `isDeviceIdleMode()`
is true, so the window fits inside that allowlist.

**Option B (evaluate in E-S5)**: when a wake-up accelerometer exists (`getDefaultSensor(TYPE_ACCELEROMETER,
true)`) and its `getFifoReservedEventCount()` is at least 1.2 x W x 50, register it with
`maxReportLatencyUs = W`. The AP can then sleep while the FIFO fills, and the sensor wakes it "before the
maximum reporting latency is elapsed or the hardware FIFO gets full" [R-SENSOR].

Sketch of the window sampler (compiled against android-37.1; abridged from the harness):

```kotlin
fun SensorManager.signals(sensor: Sensor, samplingPeriodUs: Int, maxReportLatencyUs: Int, handler: Handler):
    Flow<SensorSignal> = callbackFlow {
    val listener = object : SensorEventListener2 {
        override fun onSensorChanged(event: SensorEvent) =   // copy: the event object "may be reused" [R-SEL]
            trySend(SensorSignal.Sample(event.sensor.type, event.timestamp, event.accuracy, event.values.copyOf())).let { }
        override fun onAccuracyChanged(s: Sensor, accuracy: Int) { trySend(SensorSignal.AccuracyChanged(s.type, accuracy)) }
        override fun onFlushCompleted(s: Sensor) { trySend(SensorSignal.FlushCompleted(s.type)) }
    }
    if (!registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs, handler)) {
        close(IllegalStateException("registerListener returned false")); return@callbackFlow
    }
    awaitClose { unregisterListener(listener) }
}.buffer(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

suspend fun SensorManager.awaitTrigger(sensor: Sensor): Long = suspendCancellableCoroutine { cont ->
    val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) { if (cont.isActive) cont.resume(event.timestamp) }
    }
    if (!requestTriggerSensor(listener, sensor)) { cont.cancel(IllegalStateException("trigger refused")); return@suspendCancellableCoroutine }
    cont.invokeOnCancellation { cancelTriggerSensor(listener, sensor) }
}
```

### 10.4 The Tier 2 foreground service

| Item | Specification |
|---|---|
| Manifest | Section 3. `foregroundServiceType="health"`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_HEALTH` [FGS-TYPES] |
| Start | `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)` on 34+ (`FOREGROUND_SERVICE_TYPE_HEALTH` is API 34; the 3-argument `startForeground` is API 29) [SDK]. Check that `ACTIVITY_RECOGNITION` is granted first: it is the runtime prerequisite [FGS-TYPES]. What happens when the prerequisite is missing is not stated on the reachable pages (**UNVERIFIED**) |
| Start paths | The user taps "Start" (activity visible); the "Resume" notification action; an opt-in activity-transition auto-start. All three are background-start exemptions [FGS-BG] |
| Notification | Low-importance channel "High-detail sensing"; ongoing; Stop action; time of the last window. Without `POST_NOTIFICATIONS` (33+) it still starts, and the notice appears only in the Task Manager [NOTIF-PERM] |
| Stop | User Stop, the 24 h limit, permission loss, or `PAUSED_POWER` lasting more than 6 h (**design**). On stop: unregister listeners, `cancelTriggerSensor`, cancel alarms, close `collector_coverage("sensing_session")`, write `SENSING_SESSION.endReason` |
| Restart policy | `START_NOT_STICKY`. After process death the session ends. The next app start closes the stale coverage interval with reason `SERVICE_KILLED`. No auto-resume after reboot (4.2) |
| Threads | Sensor callbacks on a `HandlerThread`. Agentle never reflects on `MessageQueue`, so the target-37 lock-free `MessageQueue` [A17-TGT] does not affect it |
| Memory | One window holds about 500 x 3 floats plus timestamps (about 20 KB), well inside Android 17's RAM-based limits [A17-ALL] |
| Play | FGS type declaration in Play Console for target 34+ [FGS-TYPES]. Acceptance of a `health` FGS for this use is **UNVERIFIED**. To lower the review risk, the Play build's Tier 2 can restrict itself to motion and steps, the fitness purpose, and keep light, proximity and pressure in internal builds (**design**) |

### 10.5 Which modes need an FGS

| Mode | FGS | Type | Why |
|---|---|---|---|
| Tier 0 (transitions, sleep, step reads) | No | - | PendingIntent callbacks and WorkManager reads [AR-TRANS][RECAPI][HC-READ] |
| Tier 1 (visible screen) | No | - | Foreground apps receive sensor events [SENS-OV] |
| Tier 2 (background sensor windows) | **Yes** | `health` | Android 9+ background rule [A9]; `health` matches "fitness ... exercise trackers" and accepts `ACTIVITY_RECOGNITION` as its prerequisite [FGS-TYPES] |
| Rejected types | - | `dataSync`, `shortService`, `specialUse`, `location` | 4.2 |

---

## 11. Normalized events instead of raw streams

### 11.1 Envelope and mapping to doc 10

- **Storage**: every collector output goes to doc 10's `normalized_event(seq, ts, type, payload)`. `ts` is
  the event end time (UTC epoch ms). `payload` is JSON written with kotlinx.serialization (`PayloadJson`:
  `encodeDefaults = true`, `explicitNulls = false`, `ignoreUnknownKeys = true`). Each payload has a schema
  version `v`.
- **Projections and engine events**:
  - `ACTIVITY` rows are doc 10's `activity_transition(ts, activity, ENTER/EXIT)` input and raise the
    engine event `ACTIVITY_STATE_CHANGED` (doc 10 7.2).
  - No other type in this doc raises an engine event in v1.
  - The Tier 2 summaries are context for later features (for example `phone_posture_now`,
    `ambient_light_now`). They are not engine inputs yet (**design**).
- **Coverage**: collector health goes to doc 10's `collector_coverage(collector, fromMs, toMs)` with
  collectors `activity_transitions`, `sleep_api`, `step_sensor` and `sensing_session` (11.5).

### 11.2 Event catalog

Sizes are UTF-8 JSON bytes of realistic payloads, measured by `PayloadSizeTest` in the harness (14.2).

| `type` | Producer | Payload fields | Size (bytes) |
|---|---|---|---|
| `STEP_SAMPLE` | `StepBookkeeper` (Tier 1/2) | `src`, `startMs`, `endMs`, `steps`, `basis` (CONTINUOUS / ACROSS_GAP / SINCE_BOOT), `implausible`, `precisionLoss` | 151 |
| `ACTIVITY` | Transition receiver | `activity` (name or `RAW_<n>`), `activityRaw`, `transition`, `elapsedNs`, `eventMs`, `receivedMs` | 144 |
| `SLEEP_SEGMENT` | Sleep receiver | `startMs`, `endMs`, `statusRaw` | about 70 (**estimate**) |
| `SLEEP_CLASSIFY` | Sleep receiver | `tsMs`, `confidence`, `motion`, `light` (raw ints) | 65 |
| `DEVICE_MOTION_SUMMARY` | Tier 2 window | `sensor` (type, name, vendor, wakeUp), `requestedPeriodUs`, `n`, `measuredHz`, `maxGapMs`, `enmoMeanMg`, `enmoP95Mg`, `magSdMs2`, `gravity` [x, y, z], `tiltDeg`, `posture`, `moving`, `gyroRmsRadS`, `quality` | 462 |
| `AMBIENT_LIGHT_SUMMARY` | Tier 2 window | `n`, `luxMin`, `luxMedian`, `luxMax`, `bucket`, `lastKnownLux`, `lastKnownAgeMs`, `quality` | 141 |
| `PROXIMITY_SUMMARY` | Tier 2 window (screen off) | `n`, `maxRangeCm`, `nearFraction`, `state` (NEAR / FAR / MIXED / UNKNOWN), `quality` | 126 |
| `PRESSURE_SUMMARY` | Tier 2 window | `n`, `hPaMean`, `hPaSd`, `altitudeM`, `deltaAltitudeM`, `quality` | 194 |
| `SIGNIFICANT_MOTION` | Tier 2 trigger | `eventMs`, `elapsedNs` | 59 |
| `SENSOR_INVENTORY` | Tier 0 snapshot | `sdkInt`, `features` map, `sensors[]` (type, stringType, name, vendor, version, wakeUp, reportingMode, min/max delay, FIFO reserved/max, power mA, resolution, max range) | about 4-8 KB (**estimate**, 20-40 sensors) |
| `SENSING_SESSION` | Tier 2 service | `sessionId`, `startMs`, `endMs`, `startReason`, `endReason`, `windows`, `wakeLockMs` | about 200 (**estimate**) |
| `SENSOR_GAP` | Any collector | `collector`, `fromMs`, `toMs`, `reason` (REBOOT, COUNTER_RESET, BUDGET, BATTERY_SAVER, LOW_BATTERY, SENSOR_SILENT, PERMISSION_REVOKED, SERVICE_KILLED, NOT_REGISTERED) | about 120 (**estimate**) |

### 11.3 Definitions used by the summaries (design)

- **ENMO** (Euclidean norm minus one): per sample `max(0, |a| - g) / g x 1000` in milli-g, with
  `g = 9.80665` (the value of `SensorManager.GRAVITY_EARTH` and `STANDARD_GRAVITY` in the stubs [SDK]).
  Summary values are the mean and the nearest-rank p95.
- **Moving**: mean ENMO > 30 mg. This threshold is a placeholder to calibrate on devices (E-S7). A synthetic
  2 Hz, 3 m/s^2 oscillation gives about 97 mg in the unit test.
- **Gravity and posture**:
  - Gravity is the mean accelerometer vector of the window. The accelerometer includes gravity: "+9.81"
    flat on a table [R-SE].
  - `tiltDeg = acos(gz/|g|)`.
  - `FACE_UP` <= 25 degrees and `FACE_DOWN` >= 155 degrees.
  - `UPRIGHT` when `|gy|/|g| >= cos 30 degrees`, since the Y axis points up the screen [R-SE].
  - `TILTED` otherwise.
  - `UNKNOWN` when `|g|` is outside 0.5-1.5 g.
- **Light buckets** are anchored on `SensorManager` constants [SDK]: `LIGHT_FULLMOON` 0.25,
  `LIGHT_CLOUDY` 100, `LIGHT_SUNRISE` 400, `LIGHT_OVERCAST` 10,000 (other constants: `LIGHT_NO_MOON`
  0.001, `LIGHT_SHADE` 20,000, `LIGHT_SUNLIGHT` 110,000, `LIGHT_SUNLIGHT_MAX` 120,000).

  | Bucket | Range (lux) |
  |---|---|
  | `DARK` | < 1 |
  | `DIM` | < 100 |
  | `INDOOR` | < 400 |
  | `BRIGHT_INDOOR` | < 10,000 |
  | `DAYLIGHT` | otherwise |

  Values are "Ambient light level in SI lux units" [R-SE].
- **Proximity**: `near = value < min(maxRange, 5 cm)`. This covers binary sensors that report "maximum
  range value in the far state and a lesser value in the near state" [R-SE], and the guide's "far value is
  a value > 5 cm" [SENS-POS]. `nearFraction` is time-weighted over the window, with each value holding until
  the next event.
- **Pressure**: "Atmospheric pressure in hPa (millibar)" [R-SE]. Absolute altitude from
  `PRESSURE_STANDARD_ATMOSPHERE` "won't be accurate", but differences "will give good results" [R-SM]. Only
  `deltaAltitudeM` is meant for use, for example as a floors-climbed hint.

### 11.4 Quality fields

- `quality`:
  - `NO_EVENTS`: zero events in the window;
  - `PARTIAL`: the measured rate is below 50% of the requested rate, or the samples cover less than 80% of
    the window;
  - `OK` otherwise.
- `measuredHz` and `maxGapMs` record what the device actually delivered. The requested period is only "a
  hint" and "There is no public method for determining the rate" [R-SM][SENS-OV].
- `implausible` and `precisionLoss` on `STEP_SAMPLE`; `lastKnownAgeMs` on light.

### 11.5 Coverage and gaps

- **Opening and closing**: a collector opens `collector_coverage(collector, fromMs, toMs = null)` when it
  becomes healthy and sets `toMs` when it stops. Doc 10 treats any gap in the window as
  `Missing(COVERAGE_GAP)`.
- **Tier 2 gaps**: inside a session, pauses (`PAUSED_*`), budget exhaustion and silent sensors write
  `SENSOR_GAP` rows. Coverage then means "sampled at the policy cadence", not "continuously observed".
- **Step sensor**: `step_sensor` coverage is open only while the counter is registered (Tier 1 visible, or
  a Tier 2 session).

### 11.6 Retention (design)

- Window-level rows (summaries, `STEP_SAMPLE`, `SIGNIFICANT_MOTION`) are kept for 30 days. After that, an
  hourly rollup keeps:
  - window count;
  - ENMO mean and p95;
  - minutes per posture;
  - light-bucket minutes;
  - proximity-near minutes.
- `ACTIVITY`, `SLEEP_*` and `SENSOR_INVENTORY` follow the general event retention (DOC07 `:data:events`).
- Everything is deleted with the user's data deletion. None of it leaves the device unless the user
  exports it.

---

## 12. Storage and battery estimates

### 12.1 Storage

| Scenario | Arithmetic | Per day | Per 30 days |
|---|---|---|---|
| Raw accelerometer, 50 Hz, packed binary (8 B timestamp + 3 x 4 B floats) | 50 x 86,400 x 20 B | 86.4 MB | 2.6 GB |
| Raw accelerometer as Room rows (about 50 B per row with overhead, **estimate**) | 4.32 M rows x 50 B | about 216 MB | about 6.5 GB |
| Raw accelerometer + gyroscope | x 2 | 173-432 MB | 5-13 GB |
| Tier 2 summaries, budget cap (288 windows, all five events, measured 1,074 B + about 40 B row overhead per event) | 288 x (1,074 + 5 x 40) B | about 0.37 MB | about 11 MB |
| Tier 2 summaries, typical day (about 80 windows, 12.2) | 80 x 1,274 B | about 0.10 MB | about 3 MB |
| Tier 0 (`ACTIVITY` 20-60 per day at 144 B; optional `SLEEP_CLASSIFY` at 65 B, cadence **UNVERIFIED**, 144 per day assumed) | | 3-19 KB | 0.1-0.6 MB |

Summaries are about 0.1-0.4% of the raw volume. That is the main reason for D1.

### 12.2 Battery (estimate; replace with E-S5 measurements)

Model: `E_day ≈ N x (W + 2 s) x I_awake + Σ_sensors I_sensor x t_active`.

- `I_awake` (application processor awake, screen off) is assumed to be 30-100 mA (**UNVERIFIED**).
- `I_sensor` is `Sensor.getPower()` in mA, read per device into `SENSOR_INVENTORY` [R-SENSOR].
- The step counter is "expected to be low power" [R-SENSOR]. Significant motion needs no wake lock
  [R-SENSOR]. Both are treated as negligible next to the AP.

| Day profile (**design** assumptions) | Windows N | Wake lock | Charge at 30-100 mA | Share of a 4,500 mAh battery |
|---|---|---|---|---|
| Typical: 4 h screen on (24), 1.5 h moving screen off (18), 8 still episodes x 3 (24), night 3 + 4 re-arms x 3 (15) | about 81 | about 16 min | about 8-27 mAh | about 0.2-0.6% |
| Budget cap (45 min wake lock) | <= 225 at 12 s | 45 min | about 23-75 mAh | about 0.5-1.7% |
| Continuous raw logging with the AP held awake (rejected) | n/a | 24 h | 720-2,400 mAh | 16-53% |

- **Vitals**: the typical day uses about 13% of the 2-hour excessive-wake-lock threshold [VITALS-WL]; the
  cap uses 37.5%.
- **Wake-ups**: the alarm cadence adds 12 per hour at most while moving. Allow-while-idle alarms are rate
  limited anyway ("about every minute", up to "15 minutes" in idle [R-ALARM]).

### 12.3 Measurement plan

E-S5 runs on at least two devices (one Pixel-class device on 37, one mid-range device on 29-31).

- **Runs**: 24 h with Tier 2 on and 24 h with it off, the same scripted routine each time.
- **Data collected**: `adb bugreport` battery statistics, and the session's own `windows` and `wakeLockMs`.
- **Comparisons**: Option A against Option B, and the result against 12.2.
- **Outputs**: the measured `I_awake` and the per-sensor costs replace the assumptions above.

---

## 13. `UNSUPPORTED_ON_DEVICE` and availability resolution

### 13.1 Algorithm (implemented as `SensorAvailability.resolve` in the harness)

Inputs per sensor capability:

- `featureDeclared`: `hasSystemFeature(FEATURE_SENSOR_*)`, or null where no constant exists (2.8);
- the grant (`ACTIVITY_RECOGNITION` for the step sensors);
- `defaultSensorPresent`;
- the build flavor;
- consecutive silent windows;
- the measured/requested rate ratio;
- whether a receiver is active (a visible screen or a Tier 2 session).

Precedence follows doc 01 5.1.

| Order | Condition | State | Blocker |
|---|---|---|---|
| 1 | Feature flag false and no sensor reported | `UNSUPPORTED_ON_DEVICE` | `NO_FEATURE` |
| 1 | No sensor reported although the grant is present (or not needed) | `UNSUPPORTED_ON_DEVICE` | `NO_SENSOR` |
| 2 | Not in this build flavor | `RESTRICTED_BY_ANDROID` | `NOT_IN_THIS_BUILD` |
| 3 | Grant missing | `DENIED` / `DENIED_PERMANENTLY` | - |
| 4 | 3 consecutive windows with zero events from a present sensor | `UNAVAILABLE` | `SENSOR_SILENT` |
| 5 | Measured rate < 50% of requested | `PARTIALLY_ALLOWED` | `RATE_LIMITED` |
| 6 | No visible screen and no Tier 2 session | `FOREGROUND_ONLY` | `NO_SESSION` |
| 7 | Otherwise | `ALLOWED` | - |

Notes:

- For the step sensors, a null `getDefaultSensor()` with a missing grant is a **grant problem, not
  unsupported**, because the method returns a sensor only "if one exists and the application has the
  necessary permissions" [R-SM]. The feature flag decides the hardware question until the grant exists.
- A feature flag of false with a reported sensor is treated as present. `SENSOR_INVENTORY` records the
  mismatch.
- `NO_FEATURE`, `NO_SENSOR`, `SENSOR_SILENT`, `RATE_LIMITED` and `NO_SESSION` are new values for doc 01's
  `Blocker` enum (integration item, section 18).

### 13.2 Outcomes per capability (doc 01 ids)

| Capability | Typical states | Unsupported when |
|---|---|---|
| `motion_sensors` (accelerometer, gyroscope, significant motion) | `FOREGROUND_ONLY` without a session, `ALLOWED` in a session, `PARTIALLY_ALLOWED` if rate limited | No accelerometer (rare); gyroscope missing only removes `gyroRmsRadS` |
| `ambient_proximity_sensors` (light, proximity, pressure) | as above | Per sensor: the summary type is simply never produced; pressure is often absent |
| `step_counter_sensor` | `DENIED` until the grant; then `FOREGROUND_ONLY` / `ALLOWED` | `FEATURE_SENSOR_STEP_COUNTER` false (emulator AVDs, 14.5) |
| `activity_recognition_transitions` | `ALLOWED`, `DENIED`, `UNAVAILABLE` + `PLAY_SERVICES_MISSING` | Never: without Play services the state is `UNAVAILABLE` (DOC01 5.5) |
| `step_count_recording_api` | `ALLOWED`, `DENIED`, `UNAVAILABLE` (Play services too old) | Never (as above) |
| `health_connect_on_device_steps` | DOC01 5.5 | API < 34 or extension < 20 |

### 13.3 Runtime silence

The microphone toggle and a possible developer "Sensors off" tile (2.11, **UNVERIFIED**) can silence
sensors without any API saying so.

- **Detection**: a present sensor that delivers zero events in 3 consecutive windows produces `SENSOR_GAP`
  (`SENSOR_SILENT`) and state `UNAVAILABLE`.
- **Recovery**: the next window that gets events clears it.
- **Rate shortfall**: a measured rate below 50% of the request, with the microphone toggle as one possible
  cause [SENS-OV], gives `PARTIALLY_ALLOWED`.

### 13.4 UI behaviour (design)

- `UNSUPPORTED_ON_DEVICE`: the sensor row in "High-detail sensing" settings reads "Not available on this
  phone" and its toggle is disabled. If no Tier 2 sensor except the accelerometer is supported, Tier 2
  still runs (motion only).
- No accelerometer: Tier 2 is hidden.
- No significant motion: `PAUSED_STILL` falls back to the 60-minute alarm (10.2).
