# 01 - Android permissions and special-access matrix (Agentle)

Status: COMPLETE for v1 planning (2026-10-02). Author: Agent 1 (Android permissions specialist).
Machine-readable companion: `docs/research/capabilities.json` (one object per capability, same `id`s as
the tables below).

Scope: every on-device data source Agentle (Kotlin, Compose, Room, Hilt, WorkManager, DataStore) wants
to collect, with the public API, runtime permission, special access, background limits, Google Play
policy, minimum API level, Android 16 (API 36) and Android 17 (API 37) behavior changes, collection mode,
how to detect each permission state with public APIs, which Settings screen to open, and how to test
each state. The Fitbit (Google Health API) cloud connector and Sign in with ChatGPT are covered in
`05-google-health-and-health-connect.md` and `06-openai-sign-in-with-chatgpt.md`. This file covers only
the Android-side permissions they touch.

Ground rules: only public SDK APIs, documented special access and documented adb or shell commands are
used. No hidden APIs, reflection, root, exploits, or accessibility-service scraping. One example: the
secure setting `enabled_notification_listeners` that androidx `NotificationManagerCompat` reads
internally is not in the public SDK; `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` is absent from
[SDK]. Agentle therefore uses `NotificationManager.isNotificationListenerAccessGranted()` instead
[R-NM][AOSP-NMC].

---

## 0. Conventions

- **API levels** come from the local SDK database [SDK]
  (`/opt/android-sdk/platforms/android-37.1/data/api-versions.xml`; 37.2 items from `android-37.2-beta1`).
  They are cross-checked against the "Added in API level" lines of the developer.android.com reference.
  - Android 16 is API 36 (36.1 is QPR2).
  - Android 17 is API 37 (37.0; 37.1 is QPR1; 37.2 is the QPR2 beta).
  - "ext N" means SDK extension level N.
- **Verification markers.**
  - No marker: the claim was checked against the cited source on 2026-10-01 or 2026-10-02.
  - **UNVERIFIED**: plausible, but not confirmed in a source this session.
  - **undocumented**: the official docs are silent.
- **Citations** use the bracketed keys listed in section 11, for example [R-USM] or [BC17T]. Each key
  maps to a URL plus the path of the cached copy that was read.
- **Enum meanings** (they apply to `capabilities.json` and to the tables):
  - `backgroundSupport`:
    - `NONE`: only a user action can produce the data.
    - `FOREGROUND_ONLY`: collectable only while the app is visible (or the grant is while-in-use only).
    - `WITH_FOREGROUND_SERVICE`: background collection needs a typed foreground service (FGS).
    - `YES`: collectable in the background through WorkManager polling, manifest receivers,
      system-bound services, or system-kept history.
  - `collectionModes`:
    - `EVENT`: callbacks or broadcasts.
    - `PERIODIC`: polled from a worker.
    - `HISTORICAL`: the system keeps history that can be backfilled.
    - `CONTINUOUS`: a sensor stream.
  - `playPolicy`:
    - `NONE`: no Play policy beyond the general ones.
    - `SENSITIVE`: personal or sensitive data, or a sensitive API, under the Play User Data or
      Permissions policies. Needs prominent disclosure, Data safety entries and restricted use, but no
      declaration form.
    - `DECLARATION_REQUIRED`: a Play Console declaration and review are needed.
    - `RESTRICTED_DEFAULT_HANDLER_ONLY`: only the default SMS, Phone or Assistant handler may use it.
  - `plannedStatus`:
    - `IMPLEMENT`: in v1.
    - `IMPLEMENT_DEBUG_ONLY`: behind an internal or debug flag, never in the Play release.
    - `DOCUMENT_UNAVAILABLE`: cannot or must not ship. Document why, and show the capability as
      unavailable in the Permission Center.
    - `DEFER`: possible, but not in v1.
  - `minSdk` (JSON): the lowest API level at which the capability can be collected or used through the
    public APIs named in its matrix row, including the older fallback APIs named there. For Jetpack
    Health Connect it is 28, the lowest level the Health Connect app runs on [G-HC-START]. The app-wide
    minSdk from section 1.1 still applies:
    - A value of 29 or lower needs no `SDK_INT` gate under the recommended minSdk 29.
    - A higher value must be gated with `Build.VERSION.SDK_INT` or an SDK-extension check. Below that
      level, report UNSUPPORTED_ON_DEVICE.
    - Runtime feature checks, such as the Health Connect `getFeatureStatus`, still apply on top.

---

## 1. Summary

1. **Usage access and notification access carry most of the value.**
   - **Usage access** (`PACKAGE_USAGE_STATS` app-op) provides:
     - foreground app transitions;
     - screen-interactive and keyguard history (screen on/off, unlock-related);
     - device startup and shutdown;
     - per-app data usage;
     - daily usage aggregates.
     Sources: [R-USM][R-UE][R-NSM].
   - **Notification access** (`NotificationListenerService`) provides:
     - post and remove events with package, time, category, channel and removal reason;
     - notification content;
     - DND change callbacks;
     - active media sessions.
     Sources: [R-NLS][R-NM][R-MSM].
   - **Sideloaded installs.** Both accesses are "restricted settings". The user must first use
     **App info > More > Allow restricted settings** [H-RESTRICTED][AOSP-ECM].
   - **Play policy.** Neither the current nor the preview Play "Permissions and APIs that Access
     Sensitive Information" page mentions usage access or notification listeners [P-PERM][P-PREVIEW].
     The User Data policy (prominent disclosure, Data safety) still applies. That no declaration form
     exists is **UNVERIFIED**.
2. **Screen and unlock history.**
   - **Only reliable source:** `UsageEvents.Event.SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`,
     `KEYGUARD_SHOWN` and `KEYGUARD_HIDDEN` (API 28, usage access).
   - **`ACTION_SCREEN_ON` and `ACTION_SCREEN_OFF`** are registered-receiver-only [R-INT]. On Android 14+
     they are deferred while the app is cached [BC14A][G-BCAST].
   - **`ACTION_USER_PRESENT`** is not on the manifest exemption list [G-BCASTEX].
   - **Keyguard listeners** need the privileged `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE` [R-MP].
3. **Usage events are kept "only ... for a few days"** [R-USM].
   - Agentle must poll them, persist them in Room, and keep a cursor.
   - Since Android 11 the queries return `null` while the user is locked (direct boot) [R-USM].
4. **SMS and the call log cannot ship on Play.**
   - **Play rule:** only the default SMS, Phone or Assistant handler may use them, plus a fixed exception
     list. "Research" and "Social graph and personality profiling" are named invalid uses
     [P-PERM][P-SMSCL].
   - **Platform:** the permissions are hard-restricted [R-MP] and ECM-protected for sideloads
     [AOSP-ECM].
   - **Android 17** withholds OTP SMS for 3 hours [BC17T][BC17A].
   - **Status:** `DOCUMENT_UNAVAILABLE`.
   - **Substitute:** call state through `READ_PHONE_STATE`, or the permissionless
     `AudioManager.getMode()`.
5. **Steps:**
   - Health Connect on-device steps: API 34 + ext 20 [G-HC-READ].
   - Health Connect background reads: `READ_HEALTH_DATA_IN_BACKGROUND`, API 35, or 34 + ext 13
     [R-HP].
   - The Play services Recording API for older devices: 10-day buffer [G-REC].
   - The raw `TYPE_STEP_COUNTER` gets no events while the app is in the background (Android 9+)
     [G-SENS].
6. **Location:**
   - Approximate, foreground location ships.
   - Background location is `DEFER`: it needs a Play declaration for a core feature [P-PERM].
   - Android 17's location button is for one-time precise use [G-LOCBTN][P-PREVIEW].
   - Wi-Fi SSID and BSSID need fine location, location turned on, and `FLAG_INCLUDE_LOCATION_INFO`
     (31) [R-WI][R-NCB].
7. **Media and contacts need Play declarations.**
   - `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` need a declaration when the picker is insufficient
     [P-PERM]. Status: debug-only.
   - `READ_CONTACTS` for apps targeting Android 17 is restricted from 2027-01-27 to cases where the
     Contact Picker is insufficient [P-PREVIEW]. Status: `DEFER`.
8. **Many device-state signals need no permission at all:**
   - battery (the sticky `ACTION_BATTERY_CHANGED`);
   - power save, Doze and low-power standby;
   - thermal status;
   - display state;
   - volume, ringer and audio mode;
   - audio output devices;
   - airplane mode;
   - connectivity;
   - time zone and locale;
   - next alarm clock;
   - storage.
   Sources: [R-BAT][R-PWR][R-AUD][R-CM][R-ALM][R-SSM].
9. **Event delivery is the hard part, not permissions.** Most interesting broadcasts are
   registered-only, or are not exempt from the Android 8 implicit-broadcast ban. Each of the following
   can be received in a manifest receiver:
   - `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED`;
   - `TIMEZONE_CHANGED`, `TIME_SET` and `NEXT_ALARM_CLOCK_CHANGED`;
   - `LOCALE_CHANGED`;
   - Bluetooth `ACL_*` and A2DP/Headset `CONNECTION_STATE_CHANGED`;
   - `PHONE_STATE`;
   - `EVENT_REMINDER`;
   - `PACKAGE_FULLY_REMOVED` and `PACKAGE_DATA_CLEARED`.
   Source: [G-BCASTEX]. Everything else needs WorkManager polling, a live process, a
   `PendingIntent`-based API, or a system-bound service (the notification listener).
10. **Android 16 and 17 changes that bite** (details in section 8):
    - **Android 16:**
      - JobScheduler/WorkManager quotas now count top-started and FGS-concurrent jobs [BC16A].
      - `BODY_SENSORS` becomes the granular `android.permission.health.*` permissions [BC16T].
    - **Android 17:**
      - SMS OTP withholding [BC17T][BC17A].
      - CP2 PII column removal [BC17T].
      - Background audio hardening (setters only, so reads are unaffected) [A17-AUDIO].
      - `ACTION_TIMEZONE_OFFSET_CHANGED` [R-INT][A17-RN].
      - Contact picker [G-PICKER].
      - Location button [G-LOCBTN].
      - App memory limits [BC17A].
      - `ACCESS_LOCAL_NETWORK`, which Agentle does not need [G-LAN].
    - **Android 17 QPR2 beta (37.2):** adds `UsageStatsManager.queryAppUsageDuration()`, but it requires
      `QUERY_APP_USAGE`, which is `internal|role` and so not available to Agentle [R-USM][R-MP].
11. **Testing:**
    - Robolectric 4.17 supports API 37 [ROBO-SDK].
    - `ShadowAppOpsManager` returns `MODE_ALLOWED` for unset ops [ROBO], so tests must set denial
      explicitly.
    - 4.17 has no shadow for `NetworkStatsManager`, `HealthConnectManager` or `StatusBarNotification`
      [ROBO-JAR]. Wrap the first two behind interfaces and use `FakeHealthConnectClient` [G-HC-UNIT].
      Tests can build `StatusBarNotification` objects with its public constructor (section 7.1).

### 1.1 minSdk recommendation: **minSdk 29 (Android 10)**, compileSdk/targetSdk 37

**Why not lower:**
- Core collectors depend on API 28 and 29 features:
  - `UsageEvents` screen and keyguard events, `queryEventStats` and `getAppStandbyBucket` are 28.
  - `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`, `FOREGROUND_SERVICE_START/STOP` and
    `DEVICE_STARTUP/SHUTDOWN` are 29. `MOVE_TO_FOREGROUND` was deprecated in 29 [R-UE][SDK].
  - The `ACTIVITY_RECOGNITION` runtime permission is 29 [R-MP].
  - The thermal API is 29 [R-PWR].
  - The split `ACCESS_BACKGROUND_LOCATION` is 29 [R-MP].
  - `AppOpsManager.MODE_FOREGROUND` is 29 [SDK].
  - The `MediaColumns.DATE_TAKEN` and `OWNER_PACKAGE_NAME` columns are 29 [SDK].
- At 28, the foreground-app collector needs the deprecated `MOVE_TO_*` path. Activity recognition also
  works differently:
  - Android 9 has no runtime `ACTIVITY_RECOGNITION` permission; it was added in API 29 [R-MP].
  - The Transition API relies there on the manifest-declared GMS permission
    `com.google.android.gms.permission.ACTIVITY_RECOGNITION` [G-AR]. How that permission is granted is
    **UNVERIFIED**.
  - So on Android 9 the in-app consent screen would be the only consent step. Android 10 documents the
    auto-grant rules for older apps [A10-PRIV].
- Below 28 the Health Connect app does not run: the "SDK supports Android 8 (API level 26) ... the
  Health Connect app is only compatible with Android 9 (API level 28) or higher" [G-HC-START].

**Why not higher by default:**
- No library forces it:
  - The AndroidX default minSdk is 24 [G-ANDROIDX].
  - WorkManager 2.12.0 needs 24 [RN-WORK].
  - Health Connect client 1.2.0-alpha05 needs 24 [RN-HC].
  - Compose, Room and Lifecycle moved their default to 23 [RN-COMPOSE-UI][RN-ROOM].
- Raising to 31 removes these branches:
  - legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` with `maxSdkVersion=30` [G-BTPERM];
  - Wi-Fi link info through `WifiManager.getConnectionInfo()` on 29-30. `NetworkCapabilities.getTransportInfo()`
    exists from 29, but `WifiInfo` implements `TransportInfo` only from 31, and the reference says
    "Starting with `Build.VERSION_CODES.S`, WifiInfo retrieval is moved to `ConnectivityManager`"
    [SDK][R-WM];
  - no exact-alarm permission on ≤30 [G-ALARMS];
  - no approximate-location choice on 29-30 [BC12T];
  - no FGS background-start limits on ≤30 [G-FGSBG];
  - NLS unavailable on low-RAM Android 10 devices [R-NLS];
  - `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` missing on 29 [R-SET].
- None of these branches is large. All of them are testable with Robolectric `@Config(sdk=[29,30])`.

**Device share:** **UNVERIFIED**. The platform distribution data (served from `dl.google.com`) is blocked
from this environment. AndroidX states only that its default minSdk 24 "is meant to cover 99% of
Android users" [G-ANDROIDX]. Confirm with Android Studio's API distribution chart before freezing.

**Alternatives:**
- **minSdk 31:** choose this if Agentle runs only on the owner's and testers' recent phones. It gives
  the smallest test matrix. Doc 02 (background execution) recommends 31 from its angle and accepts 29
  as the floor, so the two docs agree that 29 is the lowest acceptable value.
- **minSdk 28:** choose this only if reach to Android 9 matters. It costs the `ACTIVITY_*` events and
  the activity-recognition consent consistency.
- **minSdk 26-27:** not recommended. There are no screen or keyguard usage events, and the Health
  Connect app does not run.

### 1.2 Coverage map (task item to capability ids)

| Requested item | Capability id(s) |
|---|---|
| App usage, foreground app activity | `app_usage_events`, `app_usage_aggregates` |
| Screen state, screen on/off, unlock-related events | `screen_interactive_events`, `unlock_keyguard_events`, `display_state` |
| Notification metadata, content, post time, package, category, removal | `notification_events_metadata`, `notification_content` |
| Location | `location_foreground`, `location_background` |
| Activity recognition, physical activity, device motion | `activity_recognition_transitions`, `motion_sensors` |
| Step counter | `health_connect_on_device_steps`, `step_count_recording_api`, `step_counter_sensor` |
| Accelerometer, gyroscope, orientation sensors | `motion_sensors` |
| Ambient light, proximity | `ambient_proximity_sensors` |
| Battery %, charging state and source, charging and low-battery events | `battery_state` |
| Power state (power save, idle) | `power_save_idle_state` |
| Bluetooth state, connected devices, nearby devices | `bluetooth_adapter_state`, `bluetooth_connected_devices`, `bluetooth_nearby_scan` |
| Wi-Fi connection metadata | `wifi_connection_metadata`, `wifi_network_identity` |
| Connectivity state, network type, connectivity transitions | `network_connectivity` |
| Airplane mode | `airplane_mode` |
| Calendar | `calendar_events` |
| Call metadata | `call_state`, `call_log_metadata` |
| SMS metadata | `sms_metadata` |
| Contacts | `contacts_metadata`, `contacts_picker_selection` |
| MediaStore photo, video, audio metadata | `media_images_video_metadata`, `media_audio_metadata` |
| Timezone changes | `timezone_time_changes` |
| Locale, time format | `locale_time_format` |
| DND state | `dnd_state` |
| Volume state, ringer mode | `audio_volume_ringer` |
| Headset connection | `audio_output_devices` (+ `bluetooth_connected_devices`) |
| Boot events | `boot_shutdown_events` |
| Health Connect | `health_connect_records`, `health_connect_background_read`, `health_connect_history_read`, `health_connect_on_device_steps`, `body_sensor_heart_rate` |
| Thermal status | `thermal_status` |
| Storage | `storage_stats` |
| App standby bucket | `app_standby_bucket` |
| Data usage (NetworkStatsManager) | `network_data_usage` |
| Keyguard state | `unlock_keyguard_events` |
| Display state | `display_state` |
| Package visibility | `installed_apps_inventory` |
| Next alarm clock | `next_alarm_clock` |
| JITAI delivery prerequisites (not collection) | `post_notifications_jitai`, `exact_alarm_jitai_scheduling`, `background_execution_exemption` |
| Extra signals found (from public APIs) | `media_sessions_now_playing`, `accessibility_event_stream` (documented as unavailable) |

---

## 2. Master matrix

Notes on the columns:
- "Runtime" lists only dangerous permissions requested through a dialog. Normal or app-op permissions
  that are only declared in the manifest are in the per-capability notes.
- "Special" uses the `specialAccess` enum.
- BG means `backgroundSupport`.

| # | id | Group | Primary public API (API level) | Runtime | Special | BG | Modes | Play | Plan |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `app_usage_events` | Apps | `UsageStatsManager.queryEvents` (21); `Event.ACTIVITY_RESUMED/PAUSED/STOPPED` (29) | none | usage_access | YES | PERIODIC, HISTORICAL | SENSITIVE | IMPLEMENT |
| 2 | `app_usage_aggregates` | Apps | `queryUsageStats`, `queryAndAggregateUsageStats` (21); `UsageStats.getTotalTimeVisible` (29) | none | usage_access | YES | PERIODIC, HISTORICAL | SENSITIVE | IMPLEMENT |
| 3 | `app_standby_bucket` | Apps | `UsageStatsManager.getAppStandbyBucket` (28, own app); `Event.STANDBY_BUCKET_CHANGED` (28) | none | null (other apps: usage_access) | YES | PERIODIC, EVENT | NONE | IMPLEMENT |
| 4 | `installed_apps_inventory` | Apps | `PackageManager.getInstalledApplications` (1), `getChangedPackages` (26), `ApplicationInfo.category` (26), `getInstallSourceInfo` (30), `<queries>` | none | null | YES | PERIODIC, EVENT | SENSITIVE | IMPLEMENT |
| 5 | `network_data_usage` | Apps | `NetworkStatsManager.querySummaryForDevice`, `querySummary`, `queryDetailsForUid` (23) | none | usage_access | YES | PERIODIC, HISTORICAL | SENSITIVE | IMPLEMENT |
| 6 | `accessibility_event_stream` | Apps | `AccessibilityService` (4) | none | null (accessibility; not in enum) | YES | EVENT | DECLARATION_REQUIRED | DOCUMENT_UNAVAILABLE |
| 7 | `screen_interactive_events` | Device State | `Event.SCREEN_INTERACTIVE/NON_INTERACTIVE` (28), `queryEventStats` (28); `PowerManager.isInteractive` (20) | none | usage_access | YES | EVENT, PERIODIC, HISTORICAL | SENSITIVE | IMPLEMENT |
| 8 | `unlock_keyguard_events` | Device State | `Event.KEYGUARD_SHOWN/HIDDEN` (28); `ACTION_USER_PRESENT` (3); `KeyguardManager.isKeyguardLocked` (16), `isDeviceLocked` (22) | none | usage_access | YES | EVENT, PERIODIC, HISTORICAL | SENSITIVE | IMPLEMENT |
| 9 | `display_state` | Device State | `Display.getState` (20); `DisplayManager.DisplayListener` (17; event mask 36) | none | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 10 | `battery_state` | Device State | sticky `ACTION_BATTERY_CHANGED` (1; `EXTRA_*` 5); `BatteryManager.getIntProperty` (21), `isCharging` (23) | none | null | YES | PERIODIC, EVENT | NONE | IMPLEMENT |
| 11 | `power_save_idle_state` | Device State | `PowerManager.isPowerSaveMode` (21), `isDeviceIdleMode` (23), `isDeviceLightIdleMode` (33), `isLowPowerStandbyEnabled` (33) | none | null | YES | PERIODIC, EVENT | NONE | IMPLEMENT |
| 12 | `thermal_status` | Device State | `PowerManager.getCurrentThermalStatus`, `addThermalStatusListener` (29), `getThermalHeadroom` (30) | none | null | YES | PERIODIC, EVENT | NONE | IMPLEMENT |
| 13 | `storage_stats` | Device State | `StorageStatsManager.getTotalBytes/getFreeBytes` (26); `StatFs.getAvailableBytes/getTotalBytes` (18) | none | null | YES | PERIODIC | NONE | IMPLEMENT |
| 14 | `boot_shutdown_events` | Device State | `ACTION_BOOT_COMPLETED` (1), `ACTION_LOCKED_BOOT_COMPLETED` (24); `Event.DEVICE_STARTUP/SHUTDOWN` (29); `Settings.Global.BOOT_COUNT` (24) | none | null (history: usage_access) | YES | EVENT, HISTORICAL | NONE | IMPLEMENT |
| 15 | `network_connectivity` | Device State | `ConnectivityManager.registerDefaultNetworkCallback` (24), `registerNetworkCallback(PendingIntent)` (23); `getRestrictBackgroundStatus` (24); `TelephonyManager.getDataNetworkType` (24) | none | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 16 | `wifi_connection_metadata` | Device State | `NetworkCapabilities.getTransportInfo` (29) returning `WifiInfo` (31+); `WifiManager.getConnectionInfo` (1, deprecated 31) on 29-30; `WifiManager.isWifiEnabled` (1) | none | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 17 | `wifi_network_identity` | Location | `WifiInfo.getSSID/getBSSID` through `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` (31); `WifiManager.getConnectionInfo` (1) on 29-30 | ACCESS_FINE_LOCATION (+BACKGROUND for background) | null | FOREGROUND_ONLY | EVENT | SENSITIVE | DEFER |
| 18 | `airplane_mode` | Device State | `Settings.Global.AIRPLANE_MODE_ON` (17); `ACTION_AIRPLANE_MODE_CHANGED` (1) | none | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 19 | `bluetooth_adapter_state` | Bluetooth | `BluetoothAdapter.isEnabled/getState`, `ACTION_STATE_CHANGED` (5) | none (target 31+) | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 20 | `bluetooth_connected_devices` | Bluetooth | `getBondedDevices` (5); `getProfileProxy` + `BluetoothProfile.getConnectedDevices` (11); `ACTION_ACL_CONNECTED/DISCONNECTED` (5) | BLUETOOTH_CONNECT | null | YES | EVENT, PERIODIC | SENSITIVE | IMPLEMENT |
| 21 | `bluetooth_nearby_scan` | Bluetooth | `BluetoothLeScanner.startScan` (21); `BluetoothAdapter.startDiscovery` (5) | BLUETOOTH_SCAN (+ACCESS_FINE_LOCATION unless `neverForLocation`) | null | WITH_FOREGROUND_SERVICE | EVENT, PERIODIC | SENSITIVE | DEFER |
| 22 | `audio_volume_ringer` | Device State | `AudioManager.getStreamVolume`, `getRingerMode`, `getMode` (1); `isStreamMute` (23); `addOnModeChangedListener` (31) | none | null | YES | PERIODIC, EVENT | NONE | IMPLEMENT |
| 23 | `audio_output_devices` | Device State | `AudioManager.getDevices`, `registerAudioDeviceCallback` (23); `Intent.ACTION_HEADSET_PLUG` (1); `ACTION_AUDIO_BECOMING_NOISY` (3) | none | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 24 | `timezone_time_changes` | Device State | `ACTION_TIMEZONE_CHANGED`, `TIME_SET` (1); `ACTION_TIMEZONE_OFFSET_CHANGED` (37) | none | null | YES | EVENT | NONE | IMPLEMENT |
| 25 | `locale_time_format` | Device State | `ACTION_LOCALE_CHANGED` (7); `LocaleManager.getSystemLocales` (33); `DateFormat.is24HourFormat` (3) | none | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 26 | `next_alarm_clock` | Device State | `AlarmManager.getNextAlarmClock`, `ACTION_NEXT_ALARM_CLOCK_CHANGED` (21) | none | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 27 | `background_execution_exemption` | Device State | `PowerManager.isIgnoringBatteryOptimizations` (23); `ActivityManager.isBackgroundRestricted` (28); `PackageManagerCompat.getUnusedAppRestrictionsStatus` | none | battery_optimization | YES | PERIODIC | SENSITIVE | IMPLEMENT |
| 28 | `activity_recognition_transitions` | Activity | Play services `ActivityRecognitionClient.requestActivityTransitionUpdates` (no platform level; see 3.28) | ACTIVITY_RECOGNITION | null | YES | EVENT | SENSITIVE | IMPLEMENT |
| 29 | `step_count_recording_api` | Activity | Play services `LocalRecordingClient` (`play-services-fitness` 21.2.0; no platform level stated) | ACTIVITY_RECOGNITION | null | YES | PERIODIC, HISTORICAL | SENSITIVE | IMPLEMENT |
| 30 | `step_counter_sensor` | Activity | `Sensor.TYPE_STEP_COUNTER/TYPE_STEP_DETECTOR` (19) | ACTIVITY_RECOGNITION | null | WITH_FOREGROUND_SERVICE | CONTINUOUS | SENSITIVE | DEFER |
| 31 | `location_foreground` | Location | `LocationManager.getCurrentLocation` (30); `requestSingleUpdate` (9, deprecated 30) on 29; or Play services Fused Location | ACCESS_COARSE_LOCATION (FINE optional) | null | FOREGROUND_ONLY | EVENT, PERIODIC | SENSITIVE | IMPLEMENT |
| 32 | `location_background` | Location | same, in the background | COARSE/FINE + ACCESS_BACKGROUND_LOCATION | null | YES | PERIODIC, EVENT | DECLARATION_REQUIRED | DEFER |
| 33 | `notification_events_metadata` | Notifications | `NotificationListenerService` (18): `onNotificationPosted/Removed` (removal reason 26), `getActiveNotifications` | none | notification_listener | YES | EVENT, PERIODIC | SENSITIVE | IMPLEMENT |
| 34 | `notification_content` | Notifications | `Notification.extras` (`EXTRA_TITLE`, `EXTRA_TEXT`, ...) through the NLS | none | notification_listener | YES | EVENT | SENSITIVE | IMPLEMENT (opt-in) |
| 35 | `dnd_state` | Notifications | `NotificationManager.getCurrentInterruptionFilter` (23), `getConsolidatedNotificationPolicy` (30); `NLS.onInterruptionFilterChanged` (21) | none | null | YES | EVENT, PERIODIC | NONE | IMPLEMENT |
| 36 | `post_notifications_jitai` | Notifications | `NotificationManager.notify` (1), `areNotificationsEnabled` (24); channels (26) | POST_NOTIFICATIONS (33+) | null | YES | EVENT | NONE | IMPLEMENT |
| 37 | `exact_alarm_jitai_scheduling` | Notifications | `AlarmManager.setExactAndAllowWhileIdle` (23); `canScheduleExactAlarms` (31); `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (31) | none | exact_alarm | YES | EVENT | NONE | IMPLEMENT |
| 38 | `media_sessions_now_playing` | Media | `MediaSessionManager.getActiveSessions(ComponentName)`, `addOnActiveSessionsChangedListener` (21) | none | notification_listener | YES | EVENT | SENSITIVE | DEFER |
| 39 | `media_images_video_metadata` | Media | `MediaStore.Images/Video` (1); `MediaStore.getGeneration` (30); content-URI job triggers (24) | READ_MEDIA_IMAGES, READ_MEDIA_VIDEO (33+), READ_MEDIA_VISUAL_USER_SELECTED (34+), READ_EXTERNAL_STORAGE (≤32) | null | YES | PERIODIC, EVENT, HISTORICAL | DECLARATION_REQUIRED | IMPLEMENT_DEBUG_ONLY |
| 40 | `media_audio_metadata` | Media | `MediaStore.Audio` (1) | READ_MEDIA_AUDIO (33+), READ_EXTERNAL_STORAGE (≤32) | null | YES | PERIODIC, HISTORICAL | SENSITIVE | DEFER |
| 41 | `calendar_events` | Calendar | `CalendarContract.Instances` (14); `JobInfo.TriggerContentUri` (24) | READ_CALENDAR | null | YES | PERIODIC, EVENT, HISTORICAL | SENSITIVE | IMPLEMENT |
| 42 | `health_connect_records` | Health | `HealthConnectClient` (Jetpack; Health Connect app on 28-33) / `HealthConnectManager` (34) | `android.permission.health.READ_*` per type | health_connect | FOREGROUND_ONLY | PERIODIC, HISTORICAL | DECLARATION_REQUIRED | IMPLEMENT |
| 43 | `health_connect_background_read` | Health | `HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` | READ_HEALTH_DATA_IN_BACKGROUND (35; 34 ext 13) | health_connect | YES | PERIODIC | DECLARATION_REQUIRED | IMPLEMENT |
| 44 | `health_connect_history_read` | Health | reads older than 30 days | READ_HEALTH_DATA_HISTORY (35; 34 ext 13) | health_connect | FOREGROUND_ONLY | HISTORICAL | DECLARATION_REQUIRED | IMPLEMENT |
| 45 | `health_connect_on_device_steps` | Health | Health Connect on-device steps (34 + ext 20); `HealthConnectManager.getCurrentDeviceDataSource` (37.0 or 34 ext 22 in [SDK]; the guide says 34 ext 11, see 3.45) | READ_STEPS | health_connect | YES | PERIODIC, HISTORICAL | DECLARATION_REQUIRED | IMPLEMENT |
| 46 | `body_sensor_heart_rate` | Health | `Sensor.TYPE_HEART_RATE` (20) | READ_HEART_RATE (target 36+), BODY_SENSORS (≤35) | null | WITH_FOREGROUND_SERVICE | CONTINUOUS | DECLARATION_REQUIRED | DEFER |
| 47 | `call_state` | Communication | `TelephonyCallback.CallStateListener` (31); `ACTION_PHONE_STATE_CHANGED` (3); permissionless `AudioManager.getMode` (1) | READ_PHONE_STATE | null | YES | EVENT | SENSITIVE | IMPLEMENT |
| 48 | `call_log_metadata` | Communication | `CallLog.Calls` (1) | READ_CALL_LOG (hard restricted) | default_dialer | YES | PERIODIC, HISTORICAL | RESTRICTED_DEFAULT_HANDLER_ONLY | DOCUMENT_UNAVAILABLE |
| 49 | `sms_metadata` | Communication | `Telephony.Sms`, `Sms.Intents.SMS_RECEIVED_ACTION` (19) | READ_SMS, RECEIVE_SMS (hard restricted) | default_sms_handler | YES | EVENT, HISTORICAL | RESTRICTED_DEFAULT_HANDLER_ONLY | DOCUMENT_UNAVAILABLE |
| 50 | `contacts_metadata` | Communication | `ContactsContract` (5; `CONTACT_LAST_UPDATED_TIMESTAMP`, `DeletedContacts` 18) | READ_CONTACTS | null | YES | PERIODIC, HISTORICAL | DECLARATION_REQUIRED | DEFER |
| 51 | `contacts_picker_selection` | Communication | `ContactsPickerSessionContract.ACTION_PICK_CONTACTS` (37) | none | null | NONE | EVENT | NONE | DEFER |
| 52 | `motion_sensors` | Sensors | `TYPE_ACCELEROMETER`, `TYPE_GYROSCOPE`, `TYPE_MAGNETIC_FIELD` (3); `TYPE_GRAVITY`, `TYPE_LINEAR_ACCELERATION`, `TYPE_ROTATION_VECTOR` (9); `TYPE_SIGNIFICANT_MOTION` (18) | none | null | WITH_FOREGROUND_SERVICE | CONTINUOUS | NONE | IMPLEMENT_DEBUG_ONLY |
| 53 | `ambient_proximity_sensors` | Sensors | `TYPE_LIGHT`, `TYPE_PROXIMITY`, `TYPE_PRESSURE` (3) | none | null | FOREGROUND_ONLY | CONTINUOUS | NONE | IMPLEMENT_DEBUG_ONLY |

---

## 3. Per-capability notes

Each entry gives:
- the APIs;
- how access is granted and which states can occur (see section 5 for the detection code);
- delivery and background behavior;
- Android 16 and 17 notes;
- the Play position;
- test hooks.

Detailed adb and Robolectric recipes are in section 7.

### 3.A Apps

#### 3.1 `app_usage_events`: foreground app and app-activity events
- **APIs**:
  - Queries: `UsageStatsManager.queryEvents(begin, end)` (21). `queryEvents(UsageEventsQuery)` (35) adds
    the `UsageEventsQuery.Builder.setEventTypes/setPackageNames` filters [SDK][R-USM].
  - Event types:
    - `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED` (29).
    - `MOVE_TO_FOREGROUND` and `MOVE_TO_BACKGROUND` (21, deprecated in 29).
    - `FOREGROUND_SERVICE_START` and `FOREGROUND_SERVICE_STOP` (29).
    - `USER_INTERACTION` (23), `CONFIGURATION_CHANGE` (21), `SHORTCUT_INVOCATION` (25).
    - `STANDBY_BUCKET_CHANGED` (28).
  - `Event.getExtras()` (35).
  - Sources: [R-UE][SDK].
- **Manifest**: declare `android.permission.PACKAGE_USAGE_STATS`. Its protection level is
  `signature|privileged|development|appop|retailDemo` [R-MP]. The user grants the app-op in Settings.
  The doc says: "declaring the permission implies intention to use the API and the user of the device
  still needs to grant permission through the Settings application" [R-USM].
- **States**:
  - ALLOWED / REQUIRES_SETTINGS.
  - RESTRICTED_BY_ANDROID for ECM-guarded sideloads: `OPSTR_GET_USAGE_STATS` is in ECM
    `PROTECTED_SETTINGS` [AOSP-ECM].
  - UNAVAILABLE while the user is locked: "if the user's device is not in an unlocked state ... then
    `null` will be returned" [R-USM].
  - UNSUPPORTED_ON_DEVICE if no activity resolves `ACTION_USAGE_ACCESS_SETTINGS`: "a matching Activity
    may not exist" [R-SET].
- **Retention**: "Events are only kept by the system for a few days" [R-USM].
  - Poll from WorkManager every 1-6 h and on each `ProcessLifecycleOwner` `ON_START`.
  - Persist with a high-water-mark timestamp and an idempotent key (timestamp, package, class, type).
  - Re-query an overlap window of about 10 min, because event ordering at the window edge is
    **undocumented**.
- **Filtering** (AOSP `UsageStatsService`) [AOSP-USS]:
  - Instant-app events are obfuscated.
  - `SHORTCUT_INVOCATION` is hidden unless the caller is the shortcut host.
  - Locus events need `ACCESS_LOCUS_ID_USAGE_STATS`.
  - `NOTIFICATION_SEEN` and `NOTIFICATION_INTERRUPTION` are obfuscated without `MANAGE_NOTIFICATIONS`.
  - No package-visibility filtering of event package names was found in that code. OEM behavior is
    **UNVERIFIED**.
- **Foreground-app derivation**: a session runs from `ACTIVITY_RESUMED` to the next
  `ACTIVITY_PAUSED`/`ACTIVITY_STOPPED` for the same task instance. On API < 29, use `MOVE_TO_*`. That
  case cannot occur with minSdk 29.
- **Android 16/17**:
  - No change to these APIs appears in [BC16T][BC16A][BC17T][BC17A].
  - 37.2 (QPR2 beta) adds `queryAppUsageDuration(AppUsageDurationQuery)`. It "Requires
    `Manifest.permission.QUERY_APP_USAGE`", whose protection level is `internal|role`, so Agentle
    cannot use it [R-USM][R-MP].
- **Play**: not mentioned in [P-PERM] or [P-PREVIEW]. The User Data policy applies, which means
  prominent disclosure and Data safety entries. Classed as SENSITIVE.
- **Test**:
  - Robolectric: `ShadowUsageStatsManager.addEvent(pkg, ts, type)` / `addEvent(Event)` and
    `ShadowAppOpsManager.setMode(...)` [ROBO].
  - adb: `cmd appops set <pkg> android:get_usage_stats allow|ignore|default` [AOSP-AOS].

#### 3.2 `app_usage_aggregates`: per-app daily totals
- **APIs**:
  - `queryUsageStats(INTERVAL_DAILY|WEEKLY|MONTHLY|YEARLY|BEST, begin, end)` and
    `queryAndAggregateUsageStats` (21).
  - `UsageStats.getTotalTimeInForeground` (21).
  - `getTotalTimeVisible`, `getLastTimeVisible` and `getTotalTimeForegroundServiceUsed` (29).
  - Sources: [SDK][R-USM].
- **Use**:
  - Daily summaries and backfill after install. Data is "aggregated into time intervals: days, weeks,
    months, and years" [R-USM].
  - Aggregate retention per interval is **undocumented**.
  - Cross-check the durations derived from 3.1.
- **States, Play and tests**: as in 3.1. Robolectric: `ShadowUsageStatsManager.addUsageStats(...)`
  [ROBO].

#### 3.3 `app_standby_bucket`: standby buckets (own app and others)
- **Own app** (no permission): `UsageStatsManager.getAppStandbyBucket()` (28). Buckets:
  `STANDBY_BUCKET_ACTIVE/WORKING_SET/FREQUENT/RARE` (28) and `STANDBY_BUCKET_RESTRICTED` (30)
  [SDK][R-USM].
  - The reference documents the effects: "Restrictions will apply ... deferral of jobs and alarms",
    and the RARE bucket's network limits [R-USM].
  - Agentle shows its own bucket in a "collector health" panel.
- **Other apps**: `STANDBY_BUCKET_CHANGED` usage events (28) through usage access. AOSP does not filter
  them [AOSP-USS]. That other apps' changes are actually returned on devices is **UNVERIFIED**.
- **Test**:
  - adb: `am set-standby-bucket <pkg> active|working_set|frequent|rare|restricted` and
    `am get-standby-bucket <pkg>` [BC16A][AOSP-AMSC].
  - Robolectric: `ShadowUsageStatsManager.setCurrentAppStandbyBucket(bucket)` and
    `setAppStandbyBucket(pkg, bucket)` [ROBO].

#### 3.4 `installed_apps_inventory`: installed apps, labels, categories (package visibility)
- **Visibility**: apps targeting 30+ see a filtered package list. Declare `<queries>` entries;
  `QUERY_ALL_PACKAGES` (normal) is governed by Play [G-PKGVIS][R-MP].
- **Recommended**:
  - Declare a `<queries><intent>` for `ACTION_MAIN` + `CATEGORY_LAUNCHER`. That makes launcher apps
    visible, which covers almost every app that appears in usage events.
  - Do **not** declare `QUERY_ALL_PACKAGES`.
  - Play restricts "alternative methods to approximate ... broad visibility" to "user-facing core app
    functionality" [P-PERM]. Agentle's app-usage dashboard is user-facing, which keeps this compliant.
    The Play listing must say so.
- **Incremental sync**:
  - `PackageManager.getChangedPackages(sequenceNumber)` (26).
  - `ApplicationInfo.category` and `getCategoryTitle` (26).
  - `PackageInfo.firstInstallTime` (9) and `lastUpdateTime`.
  - `getInstallSourceInfo` (30).
  - Source: [SDK].
- **Events**:
  - `ACTION_PACKAGE_FULLY_REMOVED` and `ACTION_PACKAGE_DATA_CLEARED` are manifest-exempt [G-BCASTEX].
  - `PACKAGE_ADDED`/`REPLACED`/`REMOVED` are not exempt. Use runtime receivers, or poll
    `getChangedPackages` from WorkManager.
- **Package names**: usage events carry package names even for packages that are not visible
  [AOSP-USS]. Store the raw name and resolve the label only when it is visible.
- **Play**: SENSITIVE. "App inventory data ... may never be sold nor shared for analytics or ads
  monetization purposes" [P-PERM].
- **Test**: Robolectric `ShadowPackageManager.installPackage(...)` and `addResolveInfoForIntent(...)`
  [ROBO].

#### 3.5 `network_data_usage`: per-app and device data usage
- **APIs**: `NetworkStatsManager` (23):
  - `querySummaryForDevice`, `querySummaryForUser`, `querySummary`, `queryDetailsForUid` (23);
  - `queryDetailsForUidTag` (24), `queryDetailsForUidTagState` (28);
  - `registerUsageCallback` (24).
  - Sources: [SDK][R-NSM][R-NS].
- **Grant**:
  - Device-wide and other-app stats need `PACKAGE_USAGE_STATS` (usage access).
  - The app's own UID needs no permission on 24+.
  - On 29+ pass `null` as `subscriberId` for mobile, so `READ_PHONE_STATE` is not needed [R-NSM].
- **Retention**: **undocumented**. Poll daily and persist per-day buckets.
- **States**: same as 3.1.
- **Test**: Robolectric 4.17 has **no** `ShadowNetworkStatsManager` [ROBO-JAR]. Put a `NetworkUsageSource`
  interface in front of it and fake it in tests.

#### 3.6 `accessibility_event_stream`: DOCUMENT_UNAVAILABLE
- **Why it is excluded**:
  - Play says "The Accessibility API is not designed and cannot be requested for ... an app that
    autonomously initiates, plans, and executes actions or decisions". Non-accessibility tools need
    prominent disclosure and affirmative consent, and the Accessibility API is on the declaration form
    list [P-PERM][P-PREVIEW].
  - For sideloads, `OPSTR_BIND_ACCESSIBILITY_SERVICE` is ECM-protected [AOSP-ECM].
  - Agentle's AI/JITAI design is close to the "autonomous agent" exclusion.
  - The data is already covered by 3.1 and 3.33.
- **Action**: list it in the Permission Center as "Not used by Agentle" and do not build it.

### 3.B Device State

#### 3.7 `screen_interactive_events`: screen on/off
- **History** (authoritative):
  - `UsageEvents.Event.SCREEN_INTERACTIVE` and `SCREEN_NON_INTERACTIVE` (28) [R-UE].
  - `UsageStatsManager.queryEventStats(interval, begin, end)` (28) aggregates `SCREEN_INTERACTIVE`,
    `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN` and `KEYGUARD_HIDDEN` [R-USM].
- **Live**:
  - `ACTION_SCREEN_ON/OFF`: "You cannot receive this through components declared in manifests, only by
    explicitly registering for it with `Context.registerReceiver()`" [R-INT].
  - On Android 14+, context-registered broadcasts are queued and may be merged while the app is cached,
    and `ACTION_SCREEN_ON` is deferred [BC14A][G-BCAST].
  - Snapshot: `PowerManager.isInteractive()` (20) [R-PWR].
- **Design**:
  - Screen sessions come from the usage events.
  - The runtime receiver is only a low-latency JITAI trigger while the process is alive.
  - Without usage access the capability is PARTIALLY_ALLOWED: live events only, with gaps.
- **Test**:
  - adb: `input keyevent KEYCODE_SLEEP` / `KEYCODE_WAKEUP` (keycodes added in API 20)
    [AOSP-INSC][SDK].
  - Robolectric: `ShadowPowerManager.setIsInteractive(...)`, plus `ShadowUsageStatsManager.addEvent(...)`
    with `SCREEN_INTERACTIVE`.

#### 3.8 `unlock_keyguard_events`: keyguard and unlock-related events
- **History**: `KEYGUARD_SHOWN` and `KEYGUARD_HIDDEN` (28) [R-UE].
- **Live**:
  - `ACTION_USER_PRESENT` (3) is implicit and not on the exemption list, so it needs a runtime receiver
    [R-INT][G-BCASTEX].
  - `ACTION_USER_UNLOCKED` (24) marks the first unlock after boot and is "only sent to registered
    receivers" [R-INT].
- **Snapshot**:
  - `KeyguardManager.isKeyguardLocked()` (16), `isDeviceLocked()` (22), `isDeviceSecure()` (23)
    [R-KGM].
  - The listener APIs `addKeyguardLockedStateListener` (33) and `addDeviceLockedStateListener` (36.1)
    require `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE` (`signature|privileged|module|role`), so they are not
    available to Agentle [R-KGM][R-MP].
- **Semantics**: whether `KEYGUARD_HIDDEN` means "authenticated unlock" or also "trust-agent or swipe
  dismissal" is **undocumented**. Label it "keyguard dismissed", not "unlock".
- **Test**:
  - adb: `locksettings set-pin 1234` / `locksettings clear --old 1234` [AOSP-LSSC];
    `wm dismiss-keyguard` [AOSP-WMSC].
  - Robolectric: `ShadowKeyguardManager.setKeyguardLocked`, `setIsDeviceLocked`, `setIsDeviceSecure`
    [ROBO].

#### 3.9 `display_state`: display power state and brightness
- **APIs**:
  - `Display.getState()` (20) returns `STATE_OFF/ON/UNKNOWN` (20), `STATE_DOZE/DOZE_SUSPEND` (21),
    `STATE_VR` (26) or `STATE_ON_SUSPEND` (28) [R-DISP][SDK].
  - `DisplayManager.registerDisplayListener(listener, handler)` (17). The Executor variant with an event
    mask (36) supports `EVENT_TYPE_DISPLAY_STATE` (36) and `EVENT_TYPE_DISPLAY_BRIGHTNESS` (36.1)
    [R-DM][SDK].
  - `Settings.System.SCREEN_BRIGHTNESS`, `SCREEN_BRIGHTNESS_MODE` (8) and `SCREEN_OFF_TIMEOUT` are
    readable [R-SET].
- **Background**: the listener works only while the process is alive. Sample `getState()` from workers.
- **Test**: Robolectric `ShadowDisplay.setState(...)` [ROBO].

#### 3.10 `battery_state`: level, charging state and source, charging and low-battery events
- **Snapshot**: `registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))` returns the sticky intent
  without registering a receiver [G-BATMON]. Extras:
  - `EXTRA_LEVEL/SCALE/PLUGGED/STATUS/HEALTH/TEMPERATURE/VOLTAGE/TECHNOLOGY/PRESENT` (5);
  - `EXTRA_BATTERY_LOW` (28);
  - `EXTRA_CHARGING_STATUS` and `EXTRA_CYCLE_COUNT` (34);
  - `EXTRA_CAPACITY_LEVEL` (36).
  - Sources: [R-BAT][SDK].
- **BatteryManager**:
  - `getIntProperty(BATTERY_PROPERTY_CAPACITY|CHARGE_COUNTER|CURRENT_NOW|CURRENT_AVERAGE)` (21);
  - `BATTERY_PROPERTY_STATUS` (26);
  - `isCharging()` (23);
  - `computeChargeTimeRemaining()` (28).
- **Charging source**: `EXTRA_PLUGGED` is `BATTERY_PLUGGED_AC`/`USB` (1), `WIRELESS` (17) or `DOCK`
  (33) [SDK].
- **Events**:
  - Actions: `ACTION_POWER_CONNECTED`/`DISCONNECTED` (4), `ACTION_BATTERY_LOW` (1) / `OKAY` (4), and
    `BatteryManager.ACTION_CHARGING`/`DISCHARGING` (23) [SDK][R-BAT].
  - **Doc conflict.** The `ACTION_BATTERY_CHANGED` reference says the low, okay, connected and
    disconnected broadcasts "can be received through manifest receivers" [R-INT]. They are **not** on
    the implicit-broadcast exemption list [G-BCASTEX]. The Android 8 page uses `ACTION_POWER_CONNECTED`
    as its example of a receiver to migrate to a job [G-A8BG].
  - **Decision**: treat them as runtime-only, and sample battery from workers. Manifest delivery on
    current devices is **UNVERIFIED**.
- **Background**: YES, through periodic sampling. `ACTION_BATTERY_CHANGED` itself is registered-only
  [R-INT].
- **Test**:
  - adb: `dumpsys battery set level 15`, `set ac|usb|wireless|dock 1`, `unplug`, `reset` [AOSP-BAT].
  - Emulator console: `power capacity`, `power ac on|off`, `power status charging|discharging|full`
    [G-EMU].
  - Robolectric: `ShadowBatteryManager.setIntProperty`, `setIsCharging` [ROBO]. Seed the sticky intent
    with `context.sendStickyBroadcast(Intent(ACTION_BATTERY_CHANGED).putExtra(...))`. In 4.17,
    `ShadowContextImpl.sendStickyBroadcast` stores it, and `registerReceiver(null, filter)` returns it
    [ROBO].

#### 3.11 `power_save_idle_state`: battery saver, Doze, light idle, low-power standby
- **APIs**:
  - `PowerManager.isPowerSaveMode()` (21), `isDeviceIdleMode()` (23), `isDeviceLightIdleMode()` (33),
    `isLowPowerStandbyEnabled()` (33), `getLocationPowerSaveMode()` (28) [SDK][R-PWR].
  - `isAllowedInLowPowerStandby` / `isExemptFromLowPowerStandby` (34) [SDK].
- **Events**: `ACTION_POWER_SAVE_MODE_CHANGED` (21), `ACTION_DEVICE_IDLE_MODE_CHANGED` (23) and
  `ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED` (33). Each "is only sent to registered receivers" [R-PWR].
  Sample from workers. The sample itself tells whether the worker ran during idle.
- **Test**:
  - adb: `cmd power set-mode 1|0` [AOSP-PWRSC]; `dumpsys deviceidle force-idle [light|deep]`,
    `unforce`, `step [light|deep]` [AOSP-DIC][G-DOZE].
  - Robolectric: `ShadowPowerManager.setIsPowerSaveMode`, `setIsDeviceIdleMode`,
    `setIsDeviceLightIdleMode`, `setLowPowerStandbyEnabled` [ROBO].

#### 3.12 `thermal_status`
- **APIs**:
  - `PowerManager.getCurrentThermalStatus()` and `addThermalStatusListener()` (29), with
    `THERMAL_STATUS_NONE..SHUTDOWN` (29) [SDK][R-PWR].
  - `getThermalHeadroom(forecastSeconds)` (30) and `getThermalHeadroomThresholds()` (35) [SDK]
    [G-THERM].
- **States**: ALLOWED, or UNSUPPORTED_ON_DEVICE when the device reports no thermal data. The return
  values on such devices are **UNVERIFIED**.
- **Test**:
  - adb: `cmd thermalservice override-status <0-6>` and `cmd thermalservice reset` [AOSP-THERM].
  - Robolectric: `ShadowPowerManager.setCurrentThermalStatus(...)` [ROBO].

#### 3.13 `storage_stats`: device storage
- **APIs**: `StorageStatsManager.getTotalBytes/getFreeBytes(StorageManager.UUID_DEFAULT)` (26) and
  `StatFs.getAvailableBytes/getTotalBytes` (18). Own-app `queryStatsForUid`/`queryStatsForPackage` (26)
  needs no permission [R-SSM][R-STATFS][SDK]. Querying other packages needs `PACKAGE_USAGE_STATS`
  [R-SSM].
- **Collection**: periodic (daily). Device-level storage is low-sensitivity.
- **Test**: Robolectric `ShadowStorageStatsManager.setStorageDeviceFreeAndTotalBytes(...)` [ROBO].

#### 3.14 `boot_shutdown_events`
- **Boot**: `ACTION_BOOT_COMPLETED` (needs `RECEIVE_BOOT_COMPLETED`, normal) and
  `ACTION_LOCKED_BOOT_COMPLETED` (24). Both are manifest-exempt [G-BCASTEX][R-MP].
  - Android 15: an app in the stopped state gets `BOOT_COMPLETED` only after it leaves that state
    [BC15A].
  - Apps targeting 35 cannot start `dataSync`, `camera`, `mediaPlayback`, `phoneCall`,
    `mediaProjection` or `microphone` FGS from `BOOT_COMPLETED` [BC15T].
  - The boot receiver should only enqueue WorkManager work.
- **Shutdown**: `ACTION_SHUTDOWN` is registered-only (since P) [R-INT]. `UsageEvents.Event.DEVICE_STARTUP`
  and `DEVICE_SHUTDOWN` (29), through usage access, give history [R-UE][SDK].
- **Boot counter**: `Settings.Global.BOOT_COUNT` (24) [SDK]. Boot time =
  `System.currentTimeMillis() - SystemClock.elapsedRealtime()`.
- **Test**: `adb reboot` on an emulator. Robolectric: deliver the intent to the receiver directly.

#### 3.15 `network_connectivity`: connectivity state, network type, transitions, data saver
- **APIs**:
  - `ConnectivityManager.registerDefaultNetworkCallback` (24; Handler variant 26) [SDK][R-CM].
  - `registerNetworkCallback(NetworkRequest, PendingIntent)` (23). The request "may outlive the calling
    application" and can be delivered to a manifest receiver. There is a limit of 100 outstanding
    requests per UID [R-CM].
  - `NetworkCapabilities`:
    - transports `WIFI/CELLULAR/BLUETOOTH/ETHERNET/VPN` (21), `WIFI_AWARE` (26), `USB` (31),
      `THREAD` (34), `SATELLITE` (35);
    - capabilities `NOT_METERED` (21), `VALIDATED` (23), `NOT_ROAMING` (28),
      `TEMPORARILY_NOT_METERED` (30), `NOT_BANDWIDTH_CONSTRAINED` (36);
    - `getLinkDownstreamBandwidthKbps` (21) and `getSignalStrength` (29).
    - Sources: [SDK][R-NCAP].
  - Data saver: `getRestrictBackgroundStatus()` and `ACTION_RESTRICT_BACKGROUND_CHANGED` (24; "only
    sent to registered receivers") [R-CM].
  - Cellular generation: `TelephonyManager.getDataNetworkType()` (24) needs `READ_PHONE_STATE`, or
    `READ_BASIC_PHONE_STATE` (33, normal) [R-TM][R-MP]. `TelephonyCallback.DisplayInfoListener` (31)
    needs no permission and reports 5G display types [R-TCB].
- **Manifest**: `ACCESS_NETWORK_STATE` (normal) [R-MP]. `CONNECTIVITY_ACTION` is deprecated (28) [SDK].
- **On 29-32**: cellular generation needs dangerous `READ_PHONE_STATE`. Record "cellular" without the
  generation unless 3.47 is granted.
- **Test**:
  - Robolectric: `ShadowConnectivityManager.setNetworkCapabilities`, `setDefaultNetworkActive`,
    `setRestrictBackgroundStatus`; `ShadowNetworkCapabilities.newInstance()` [ROBO].
  - Emulator: `network speed` and `gsm data` console commands [G-EMU]. Toggling Wi-Fi or data from
    adb is in section 7.

#### 3.16 `wifi_connection_metadata`: Wi-Fi link info without identity
- **APIs**:
  - On 31+, `NetworkCapabilities.getTransportInfo()` returns a `WifiInfo` for Wi-Fi networks. The method
    exists from 29, but `WifiInfo` implements `TransportInfo` only from 31 [SDK]. The reference says
    "Starting with `Build.VERSION_CODES.S`, WifiInfo retrieval is moved to `ConnectivityManager` API
    surface" [R-WM]. On 29-30, use `WifiManager.getConnectionInfo()`.
  - Non-location fields: `getRssi`, `getFrequency` (21), `getLinkSpeed`, `getWifiStandard` (30),
    `getCurrentSecurityType` (31) [SDK][R-WI].
  - `WifiManager.isWifiEnabled()` (1) needs `ACCESS_WIFI_STATE` (normal).
  - `WIFI_STATE_CHANGED_ACTION` "is not delivered to manifest receivers in applications that target
    API version 26 or later" [R-WM].
  - `WifiManager.getConnectionInfo()` is deprecated in 31 [SDK].
- **Identity redaction**: without location permission, "access to location sensitive fields requires
  the same permissions as `WifiManager.getScanResults`". `getSSID()` returns `<unknown ssid>` and
  `getBSSID()` returns `02:00:00:00:00:00` [R-WI].
- **Test**: Robolectric `ShadowWifiInfo.newInstance()` with `setRssi`/`setFrequency`, and
  `ShadowWifiManager.setWifiState` [ROBO].

#### 3.18 `airplane_mode`
- **APIs**: `Settings.Global.AIRPLANE_MODE_ON` (17) is readable; `Settings.System.AIRPLANE_MODE_ON` is
  deprecated [SDK]. `Intent.ACTION_AIRPLANE_MODE_CHANGED` (1) is not exempt, so it needs a runtime
  receiver [G-BCASTEX].
- **Background**: poll the setting from workers.
- **Test**: Robolectric `ShadowSettings.setAirplaneMode(true)` [ROBO]. For adb see section 7.

#### 3.22 `audio_volume_ringer`: stream volumes, mute, ringer mode, audio mode
- **APIs**:
  - `AudioManager.getStreamVolume`, `getStreamMaxVolume`, `getRingerMode`, `getMode` (1);
    `isStreamMute` (23); `getStreamMinVolume`/`getStreamVolumeDb` (28) [SDK][R-AUD].
  - `addOnModeChangedListener` (31).
  - `RINGER_MODE_CHANGED_ACTION` is a "Sticky broadcast" [R-AUD] and not exempt [G-BCASTEX].
  - `STREAM_ASSISTANT` (37) is Android 17's dedicated Assistant volume [SDK][A17-FEAT].
- **No volume broadcast**: the SDK has no public volume-changed broadcast or setting (`Settings.System`
  `VOLUME_*` keys were removed in 23 [SDK]). This is **undocumented** as a design statement. Poll.
- **Android 17**: background audio hardening covers volume **setters** (`setStreamVolume`,
  `adjustStreamVolume`, `setRingerMode`, ...), playback and audio focus [A17-AUDIO][BC17T]. Getters are
  not listed. Agentle only reads.
- **Test**:
  - Robolectric: call the framework setters (`audioManager.setStreamVolume(...)`,
    `setRingerMode(...)`, `setMode(...)`). `ShadowAudioManager` implements them as `@Implementation`
    methods [ROBO].
  - adb: `cmd media_session volume --stream 3 --set 11` and `--stream 3 --get`. The `VolumeCtrl.USAGE`
    examples spell it `adb shell media volume ...` [AOSP-MEDIASC][AOSP-VOLCTRL].

#### 3.23 `audio_output_devices`: headset and output-route changes
- **APIs**:
  - `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` and `registerAudioDeviceCallback` (23) [SDK][R-AUD].
  - `AudioDeviceInfo` types: `TYPE_WIRED_HEADSET`, `TYPE_BLUETOOTH_A2DP` (23), `TYPE_USB_HEADSET` (26),
    `TYPE_HEARING_AID` (28), `TYPE_BLE_HEADSET` (31), `TYPE_DOCK_ANALOG` (34), `TYPE_BLE_HEARING_AID`
    (37) [SDK][R-ADI].
  - `getAudioDevicesForAttributes` (33).
  - `ACTION_HEADSET_PLUG`: "You cannot receive this through components declared in manifests" [R-AUD].
  - `ACTION_AUDIO_BECOMING_NOISY` (3) [R-AUD].
- **Permission**: none for `AudioManager`. Bluetooth headset connect events through
  `BluetoothA2dp/BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED` are manifest-exempt [G-BCASTEX], but
  need `BLUETOOTH_CONNECT` (see 3.20).
- **Test**: Robolectric `ShadowAudioManager.addOutputDevice(...)`, `removeOutputDevice(...)` [ROBO].

#### 3.24 `timezone_time_changes`
- **APIs**:
  - `ACTION_TIMEZONE_CHANGED` and `ACTION_TIME_CHANGED` (`android.intent.action.TIME_SET`) are
    manifest-exempt [G-BCASTEX][R-INT].
  - New in Android 17: `Intent.ACTION_TIMEZONE_OFFSET_CHANGED` (37), with
    `EXTRA_OLD_TIMEZONE_OFFSET`/`EXTRA_NEW_TIMEZONE_OFFSET` in seconds. It means "the system's time zone
    offset has changed without the time zone having changed ... during seasonal clock changes"
    [R-INT][A17-RN].
  - That action is **not** on the exemption list, which was last updated 2026-02-26 [G-BCASTEX].
    Whether manifest delivery works is **UNVERIFIED**. Register it at runtime, and also detect DST
    shifts on the next worker run by comparing `TimeZone.getDefault().getOffset(now)`.
  - `Settings.Global.AUTO_TIME` and `AUTO_TIME_ZONE` (17) [SDK].
- **FGS**: `TIMEZONE_CHANGED`, `TIME_CHANGED` and `LOCALE_CHANGED` are FGS background-start exemptions
  [G-FGSBG].
- **Test**: adb `cmd alarm set-timezone America/New_York` and `cmd alarm set-time <ms>` [AOSP-ALARM].

#### 3.25 `locale_time_format`
- **APIs**:
  - `ACTION_LOCALE_CHANGED` (7) is manifest-exempt [G-BCASTEX].
  - `LocaleManager.getSystemLocales()` (33); `LocaleList.getDefault()` otherwise [R-LOCM][SDK].
  - `DateFormat.is24HourFormat(Context)` (3) and `Settings.System.TIME_12_24` [R-DF][R-SET].
- **No 12/24 h broadcast**: a public broadcast for 12/24-hour changes is **undocumented**. Poll.
- **Test**: Robolectric `ShadowSettings.set24HourTimeFormat(...)` [ROBO].

#### 3.26 `next_alarm_clock`
- **APIs**: `AlarmManager.getNextAlarmClock()` (21) returns `AlarmClockInfo`.
  `ACTION_NEXT_ALARM_CLOCK_CHANGED` (21) is manifest-exempt [SDK][G-BCASTEX][R-ALM].
- **Use**: a wake-time signal for JITAI timing. Needs no permission.

#### 3.27 `background_execution_exemption`: battery-optimization, hibernation and restriction status
- **Battery optimization**:
  - Check with `PowerManager.isIgnoringBatteryOptimizations(pkg)` (23).
  - Preferred route: open `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (23, a list screen).
  - `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (23) needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
    (normal). The reference says "most applications should not use this" [R-SET][R-PWR].
  - Play prohibits requesting a direct exemption "unless the core function of the app is adversely
    affected". Acceptable cases include messaging without FCM, safety, task automation and peripheral
    companions [G-DOZE]. Agentle does not clearly fit, so **do not declare**
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Link the list screen instead.
  - A granted exemption is also an FGS background-start exemption [G-FGSBG].
- **Hibernation** (31+):
  - Effects: permissions reset, jobs and alarms do not run, FCM is blocked, the cache is cleared.
  - Jobs, implicit broadcasts and alarms do **not** count as usage [G-HIB].
  - Check: `PackageManagerCompat.getUnusedAppRestrictionsStatus()` returns `ERROR`,
    `FEATURE_NOT_AVAILABLE`, `DISABLED` or `API_30_BACKPORT`/`API_30`/`API_31` [G-HIB].
  - Fix: `IntentCompat.createManageUnusedAppRestrictionsIntent()` [G-HIB].
  - `PackageManager.isAutoRevokeWhitelisted()` (30) [SDK].
- **User background restriction**: `ActivityManager.isBackgroundRestricted()` (28) [SDK].
- **Standby bucket**: see 3.3.
- **Test**:
  - adb: `dumpsys deviceidle whitelist +<pkg>` / `-<pkg>` [AOSP-DIC];
    `am set-bg-restriction-level <pkg> restricted_bucket|background_restricted|...` [AOSP-AMSC]; the
    hibernation steps in section 7 [G-HIB].
  - Robolectric: `ShadowPowerManager.setIgnoringBatteryOptimizations(pkg, true)`,
    `ShadowActivityManager.setBackgroundRestricted(true)` [ROBO].

### 3.C Location (includes Wi-Fi identity)

#### 3.31 `location_foreground`
- **APIs**: `LocationManager.getCurrentLocation()` (30; Executor variant 31); on 29,
  `requestSingleUpdate()` (9, deprecated in 30) [SDK]. Also `FUSED_PROVIDER` (31),
  `isLocationEnabled()` (28), `Location.isMock()` (31) [SDK][R-LM]. The Play services Fused Location
  Provider also works. Agentle chooses one implementation.
- **Permissions**: `ACCESS_COARSE_LOCATION`, optionally `ACCESS_FINE_LOCATION`, both dangerous [R-MP].
  - Android 12+: users can grant only approximate location, even when FINE is requested [BC12T][G-LOC].
  - Approximate location is about 3 km² [G-LOC].
  - "Foreground" means a visible activity, or an FGS of type `location` (required on 29+) [G-LOC].
  - One-time ("Only this time") grants exist on 30+ [G-RUNTIME].
- **Android 17**:
  - The **location button** (`USE_LOCATION_BUTTON`, normal, API 37) gives precise, session-scoped
    access with no dialog [G-LOCBTN][R-MP].
  - Play: "If your use case requires precise location only for one-time, user-initiated actions; you
    must implement the use of the Android location button, utilizing the `onlyForLocationButton`
    permission flag" [P-PREVIEW], effective 2027-01-27.
  - Agentle's foreground snapshot is not a one-time precise action. Request COARSE by default, and use
    the location button only if a "tag precise place now" feature is added.
- **States**:
  - PARTIALLY_ALLOWED: coarse granted where precise was wanted.
  - FOREGROUND_ONLY.
  - UNAVAILABLE: location services off.
  - RESTRICTED_BY_ANDROID: `UserManager.DISALLOW_SHARE_LOCATION` (18) or `DISALLOW_CONFIG_LOCATION`
    (28) [SDK].
- **Test**:
  - adb: `cmd location set-location-enabled true|false` [AOSP-LOCSC].
  - Emulator: `geo fix <lon> <lat>` [G-EMU].
  - Robolectric: `ShadowLocationManager.setLocationEnabled`, `simulateLocation` [ROBO].

#### 3.32 `location_background`: DEFER
- **Permission**: `ACCESS_BACKGROUND_LOCATION` (29) is hard-restricted [R-MP].
  - On Android 11+ the dialog does not offer "Allow all the time". The user must pick it on the
    settings page; show the label from `PackageManager.getBackgroundPermissionOptionLabel()` (30)
    [G-LOCBG][SDK].
  - When the user chose approximate, background access is also approximate [G-LOCBG].
- **Play**: "Background location may only be used to provide features beneficial to the user and
  relevant to the core functionality". It needs the declaration form [P-PERM][P-PREVIEW].
- **FGS**: while-in-use restrictions mean a location FGS cannot start from the background without
  `ACCESS_BACKGROUND_LOCATION` [G-FGSBG].
- **Decision**: DEFER until product decides that place-based JITAIs are core and prepares the Play
  Console declaration. The policy text read does not list what evidence the form asks for, such as a
  demo video (**UNVERIFIED**).

#### 3.17 `wifi_network_identity`: SSID/BSSID, DEFER
- **Requirements** [R-NCB][R-WI][G-WIFIPERM]:
  - `ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` (31);
  - `ACCESS_FINE_LOCATION` and location turned on;
  - `ACCESS_BACKGROUND_LOCATION` as well, for SSID while in the background.
  - `NEARBY_WIFI_DEVICES` (33) covers P2P, Aware, RTT and hotspot, **not** the SSID of the current
    connection, and `getScanResults` still needs fine location [G-WIFIPERM].
  - On 29-30 the path is `WifiManager.getConnectionInfo()`. Its location-sensitive fields need "the
    same permissions as `WifiManager.getScanResults`" [R-WI][R-WM].
- **Value**: "home/work Wi-Fi" place inference.
- **Status**: DEFER together with 3.32, because the background use needs the background-location
  declaration.

### 3.D Bluetooth

#### 3.19 `bluetooth_adapter_state`
- **APIs**: `BluetoothAdapter.isEnabled()`, `getState()` and `ACTION_STATE_CHANGED` (5).
  - For apps targeting R or lower these need `BLUETOOTH` (normal). No runtime permission is listed for
    S+ [R-BTA].
  - Declare `BLUETOOTH` with `android:maxSdkVersion="30"` [G-BTPERM].
  - `ACTION_STATE_CHANGED` is not exempt, so it needs a runtime receiver. Poll
    `Settings.Global.BLUETOOTH_ON` (17) or `isEnabled()` from workers.
- **States**: ALLOWED; UNSUPPORTED_ON_DEVICE without `FEATURE_BLUETOOTH` (8); RESTRICTED_BY_ANDROID
  with `UserManager.DISALLOW_BLUETOOTH` (26) [SDK].
- **Test**: Robolectric `ShadowBluetoothAdapter.setEnabled`, `setState` [ROBO].

#### 3.20 `bluetooth_connected_devices`: bonded and connected devices (car, headphones, watch)
- **APIs**:
  - `getBondedDevices()` (5). On S+ it "requires the `BLUETOOTH_CONNECT` permission" [R-BTA].
  - `getProfileProxy()` + `BluetoothProfile.getConnectedDevices()` (11) for A2DP and HEADSET.
  - `getProfileConnectionState()` needs `BLUETOOTH_CONNECT` on S+ [R-BTA].
- **Events**:
  - `BluetoothDevice.ACTION_ACL_CONNECTED`/`DISCONNECTED` (5) and
    `BluetoothA2dp`/`BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED` (11) are manifest-exempt
    [G-BCASTEX].
  - On S+ they require `BLUETOOTH_CONNECT` [R-BTD].
- **Identity**: store a salted hash of the MAC plus the device class and type. Names are user data.
- **Android 16/17**: bond-loss intents and autonomous re-pairing change only bonding UX
  [BC16T][BC16A][BC17A]. RFCOMM `read()` returns -1 on target 37 [BC17T]. Agentle does not use RFCOMM.
- **Test**: Robolectric `ShadowBluetoothAdapter.setBondedDevices`, `setProfileConnectionState`;
  `ShadowBluetoothDevice.newInstance(...)` [ROBO].

#### 3.21 `bluetooth_nearby_scan`: DEFER
- **Permissions**: `BLUETOOTH_SCAN` (31), declared with `usesPermissionFlags="neverForLocation"` if
  results are not used for location. Otherwise `ACCESS_FINE_LOCATION` is also needed, and it is always
  needed on ≤30 [G-BTPERM].
- **Why deferred**: low JITAI value, battery cost, and a privacy risk because nearby devices reveal
  co-location.

### 3.E Activity and Sensors

#### 3.28 `activity_recognition_transitions`
- **API**: Play services `ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(request,
  pendingIntent)`. It delivers enter and exit transitions for `IN_VEHICLE`, `ON_BICYCLE`, `RUNNING`,
  `STILL` and `WALKING` to a `PendingIntent` broadcast [G-AR].
- **Manifest**: the guide says to declare `com.google.android.gms.permission.ACTIVITY_RECOGNITION`
  [G-AR]. On 29+ the runtime `android.permission.ACTIVITY_RECOGNITION` is also needed: "Some libraries
  within Google Play services, such as the Activity Recognition API ... don't provide results unless
  the user has granted your app this permission" [A10-PRIV].
- **Background**: YES, through the `PendingIntent`. Transition events are also an FGS background-start
  exemption [G-FGSBG].
- **PendingIntent**:
  - The official sample builds a broadcast `PendingIntent` with `FLAG_MUTABLE` on 31+ around an
    implicit `Intent(action)` [SAMPLE-AR].
  - Apps targeting 34 get an exception for "a mutable pending intent with an intent that doesn't
    specify a component or package" [BC14T]. Agentle keeps it mutable but makes the intent explicit
    (its receiver class).
  - On Android 15 a force-stop cancels all of the app's pending intents [BC15A]. Re-register on
    `BOOT_COMPLETED` and on process start (section 8.4).
- **States**: UNAVAILABLE without up-to-date Google Play services, checked with
  `GoogleApiAvailability.isGooglePlayServicesAvailable` (**UNVERIFIED** API name in this session).
- **Test**: Robolectric `grantPermissions(ACTIVITY_RECOGNITION)` plus a fake receiver intent. On an
  emulator, transitions do not occur naturally; deliver `ActivityTransitionResult` extras through a
  debug-only receiver (**UNVERIFIED**).

#### 3.29 `step_count_recording_api`
- **API**: Recording API on mobile (`com.google.android.gms:play-services-fitness:21.2.0`),
  `FitnessLocal.getLocalRecordingClient(ctx).subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)` and
  `readData(...)`. `TYPE_DISTANCE_DELTA` and `TYPE_CALORIES_EXPENDED` are also available [G-REC].
- **Facts**:
  - Accountless, on-device.
  - "data since the latest subscription - for up to 10 days - is accessible".
  - Needs `ACTIVITY_RECOGNITION` and Play services ≥ `LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE`.
  - Use WorkManager to read periodically.
  - Source: [G-REC].
- **Device range**: the page states no minimum API. **UNVERIFIED**; treat it as the app minSdk.
- **Role**: the step source on devices without Health Connect on-device steps (API < 34 or ext < 20).
  "If your app has significant users on Android 13 and lower, we recommend also maintaining or adding
  an integration with the local Recording API" [G-HC-READ].

#### 3.30 `step_counter_sensor`: DEFER
- **API**: `Sensor.TYPE_STEP_COUNTER`/`TYPE_STEP_DETECTOR` (19). Needs `ACTIVITY_RECOGNITION` on 29+:
  "The only built-in sensors on the device that require you to declare this permission are the step
  counter and step detector sensors" [A10-PRIV][SDK].
- **Background**: on 28+, background apps get no events from continuous, on-change or one-shot sensors
  [G-SENS]. Background counting therefore needs a `health` FGS. `ACTIVITY_RECOGNITION` is a valid
  prerequisite for that type [G-FGSTYPES].
- **Decision**: DEFER. Health Connect on-device steps already use `TYPE_STEP_COUNTER` [G-HC-READ], and
  the Recording API covers older devices.

#### 3.52 `motion_sensors`: accelerometer, gyroscope, orientation (debug only)
- **APIs**: `SensorManager.registerListener(listener, sensor, samplingPeriodUs[, maxReportLatencyUs])`
  (3/19) for:
  - `TYPE_ACCELEROMETER`, `TYPE_GYROSCOPE`, `TYPE_MAGNETIC_FIELD` (3);
  - `TYPE_GRAVITY`, `TYPE_LINEAR_ACCELERATION`, `TYPE_ROTATION_VECTOR` (9);
  - `TYPE_GAME_ROTATION_VECTOR`, `TYPE_SIGNIFICANT_MOTION` (18; trigger sensor through
    `requestTriggerSensor`);
  - `TYPE_GEOMAGNETIC_ROTATION_VECTOR` (19).
  - `TYPE_ORIENTATION` is deprecated since 15. Use the rotation vector with
    `SensorManager.getOrientation`.
  - Sources: [SDK][G-SENSMOT][G-SENSPOS].
- **Limits**:
  - No events in the background on 28+ [G-SENS].
  - Target 31+: rate limited to 200 Hz unless `HIGH_SAMPLING_RATE_SENSORS` (normal) is declared. The
    microphone toggle forces the limit regardless [G-SENS][BC12T].
- **Why debug only**: production background sampling needs an FGS whose type matches the purpose
  (`health` for fitness only) [G-FGSTYPES]. Activity recognition (3.28) covers "device motion" for
  JITAIs.
- **Test**: emulator console `sensor set acceleration x:y:z`, `sensor status` [G-EMU]. Robolectric
  `ShadowSensorManager.addSensor`, `sendSensorEventToListeners`, `ShadowSensor.newInstance` [ROBO].

#### 3.53 `ambient_proximity_sensors`: light and proximity (debug only)
- **APIs**: `TYPE_LIGHT`, `TYPE_PROXIMITY`, `TYPE_PRESSURE` (3). Light and proximity are on-change
  sensors, so they get no background events (28+) [G-SENS][G-SENSENV][G-SENSPOS].
- **FGS**: no FGS type fits passive light or proximity logging. `specialUse` would need Play review
  [G-FGSTYPES].
- **Use**: foreground-only snapshots while the app is open. Debug only for evaluation.

### 3.F Notifications

#### 3.33 `notification_events_metadata`: post and removal events, package, time, category
- **Service**:
  - `NotificationListenerService` (18), declared with
    `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"` and the
    `android.service.notification.NotificationListenerService` intent filter [R-NLS].
  - Callbacks: `onNotificationPosted(sbn, rankingMap)` (21) and `onNotificationRemoved(sbn, rankingMap,
    reason)` (26). `REASON_*` codes are 26, plus `REASON_ASSISTANT_CANCEL` (33) and `REASON_LOCKDOWN`
    (34). Also `onListenerConnected()` (21), `getActiveNotifications()` (18/21), `requestRebind()` (24)
    and `onInterruptionFilterChanged()` (21) [SDK][R-NLS].
  - Event filtering: `META_DATA_DEFAULT_FILTER_TYPES` and `FLAG_FILTER_TYPE_*` (31) [SDK].
- **Fields**:
  - `StatusBarNotification.getPackageName`, `getPostTime` (18), `getKey` (20), `getUser` (21),
    `getOpPkg` (29) [R-SBN][SDK].
  - `Notification.category` (21), `getChannelId` (26), `getShortcutId` (26) [R-NOTIF][SDK].
  - Categories include `CATEGORY_MESSAGE` (21), `CATEGORY_REMINDER` (23), `CATEGORY_NAVIGATION` (28),
    `CATEGORY_WORKOUT`/`STOPWATCH`/`LOCATION_SHARING`/`MISSED_CALL` (31) and `CATEGORY_VOICEMAIL` (35)
    [SDK].
  - `Ranking.getImportance` (24), `getChannel` (26), `isConversation` (31), `getSummarization` (36.1)
    [SDK].
- **Grant and states**:
  - The user enables it in Notification access.
  - Check with `NotificationManager.isNotificationListenerAccessGranted(ComponentName)` (27) [R-NM].
  - UNSUPPORTED_ON_DEVICE: "Notification listeners cannot get notification access or be bound by the
    system on low-RAM devices running Android Q (and below). The system also ignores notification
    listeners running in a work profile" [R-NLS]. Detect with `ActivityManager.isLowRamDevice()` and
    SDK ≤ 29, or with `UserManager.isManagedProfile()` (30).
  - RESTRICTED_BY_ANDROID: ECM-guarded sideload, because `OPSTR_ACCESS_NOTIFICATIONS` is protected
    [AOSP-ECM].
  - Health: if access is granted but `onListenerConnected` has not run since process start, call
    `requestRebind(component)` (24) and show "reconnecting".
- **Background**: YES. The system binds the service, so Agentle gets events without its own FGS.
- **Test**:
  - adb: `cmd notification allow_listener <pkg>/<ServiceClass>`, `disallow_listener ...`,
    `post [flags] <tag> <text>`, `list`, `snooze --for <ms> <key>` [AOSP-NSC].
  - Robolectric: `ShadowNotificationManager.setNotificationListenerAccessGranted(component, true)`,
    `ShadowNotificationListenerService.addActiveNotification(...)` [ROBO]. Robolectric does not bind
    the service, so call the callbacks directly.

#### 3.34 `notification_content`: titles and text (opt-in)
- **Data**: `Notification.extras` (`EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_MESSAGES`),
  from the same service as 3.33 [R-NOTIF].
- **Limits**:
  - Android 15+: "Android will stop untrusted apps that implement a `NotificationListenerService` from
    reading unredacted content from notifications where an OTP has been detected. Trusted apps such as
    companion device manager associations are exempt" [BC15A].
  - `RECEIVE_SENSITIVE_NOTIFICATIONS` (36.1) is `signature|preinstalled|knownSigner|role`, so it is not
    available [R-MP].
  - The state is therefore permanently PARTIALLY_ALLOWED on 35+ for OTP-bearing notifications.
    Detecting redaction per notification is **undocumented**.
- **Policy and design**:
  - SENSITIVE: message content is personal data under the User Data policy.
  - Off by default, with a per-app allowlist.
  - Never sent to the AI connector without an explicit per-feature consent.
  - Strip it from backups.
- **SMS and dialer apps**:
  - Play forbids using "alternative methods ... to derive data attributed to Call Log or SMS"
    [P-PERM]. Whether reading the default SMS app's notifications counts is **UNVERIFIED**.
  - By default, exclude the default SMS package (`Telephony.Sms.getDefaultSmsPackage`, 19) and the
    default dialer (`TelecomManager.getDefaultDialerPackage`, 23) from content capture [SDK].
  - Android 17 adds "restricted message access" for end-to-end encrypted messages [A17-SUM]; see 8.3.
- **Status**: IMPLEMENT as an opt-in. If product prefers less review risk for v1, downgrade to
  IMPLEMENT_DEBUG_ONLY.

#### 3.35 `dnd_state`: Do Not Disturb, modes, interruption filter
- **APIs**:
  - `NotificationManager.getCurrentInterruptionFilter()` (23) needs no access to read [R-NM].
  - `getConsolidatedNotificationPolicy()` (30) [SDK].
  - `ACTION_INTERRUPTION_FILTER_CHANGED` (23): "only sent to registered receivers and (starting from
    Q) receivers in packages that have been granted Do Not Disturb access" [R-NM].
  - `ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED` (35) [SDK].
  - If 3.33 is granted, `NotificationListenerService.onInterruptionFilterChanged()` (21) delivers
    changes in the background [R-NLS].
- **DND access** (`ACCESS_NOTIFICATION_POLICY`, normal; `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`)
  is **not required** to read. It only adds manifest delivery of the change broadcast. "Managed profiles
  cannot grant Notification Policy access" [R-SET].
- **Recommendation**: do not request DND access. Use the listener callback plus polling.
- **Android 15+**: if an app targeting 35+ calls `setInterruptionFilter`, the system creates an implicit
  `AutomaticZenRule` [BC15T]. Agentle never writes DND.
- **Test**:
  - adb: `cmd notification set_dnd on|priority|alarms|none|off`,
    `cmd notification allow_dnd <pkg>` [AOSP-NSC].
  - Robolectric: call `notificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)`. The
    shadow stores the value without checking access, and `getCurrentInterruptionFilter()` returns it.
    Use `ShadowNotificationManager.setNotificationPolicyAccessGranted(true)` for the access path [ROBO].

#### 3.36 `post_notifications_jitai`: delivering JITAIs (prerequisite, not collection)
- **Permission**: `POST_NOTIFICATIONS` (33, dangerous) [R-MP][G-NOTIFPERM].
- **States**:
  - `NotificationManager.areNotificationsEnabled()` (24).
  - Per-channel blocking through `getNotificationChannel(id).importance == IMPORTANCE_NONE` (26):
    PARTIALLY_ALLOWED.
  - `areNotificationsPaused()` (29), which is true when the app is suspended: UNAVAILABLE [SDK].
- **Events**: `ACTION_APP_BLOCK_STATE_CHANGED` and `ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED`
  (28) [SDK][R-NM]. Agentle logs its own JITAI posted, opened and dismissed events through content and
  delete intents.
- **Promoted notifications**: `POST_PROMOTED_NOTIFICATIONS` (36.1, `normal|appops`) is "required in
  addition to (not instead of) `POST_NOTIFICATIONS`" [R-MP]. Not needed for v1.
- **Test**: the documented adb recipes for new install, upgrade and user-disabled [G-NOTIFPERM] are in
  section 7.

#### 3.37 `exact_alarm_jitai_scheduling`: exact JITAI timing (prerequisite)
- **Permissions**:
  - `SCHEDULE_EXACT_ALARM` (31; `signature|privileged|appop`) is user-granted. Check
    `AlarmManager.canScheduleExactAlarms()` (31). Request through
    `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with a `package:` URI. The result is `RESULT_OK` if
    granted [R-SET][G-SPECIAL][R-MP].
  - Android 14: denied by default for new installs targeting 33+ [BC14A].
  - On grant the system sends `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. On revocation
    "your app stops, and all future exact alarms are canceled" [G-ALARMS].
- **Play**: `USE_EXACT_ALARM` is only for alarm or timer apps and calendar apps; others "should evaluate
  if using `SCHEDULE_EXACT_ALARM`" [P-PERM]. Agentle uses `SCHEDULE_EXACT_ALARM`, optionally, with
  inexact `setWindow` as the fallback.
- **Android 17**: `setExactAndAllowWhileIdle` with an `OnAlarmListener` (37) [A17-FEAT].
- **Test**:
  - adb: `cmd appops set <pkg> SCHEDULE_EXACT_ALARM allow|default`. The shell accepts the public op
    string, the op number or the debug name [AOSP-AOS]. `SCHEDULE_EXACT_ALARM` is the debug name, and
    `android:schedule_exact_alarm` is `OPSTR_SCHEDULE_EXACT_ALARM` [AOSP-AOM].
  - Robolectric: `ShadowAlarmManager.setCanScheduleExactAlarms(false)` [ROBO].

### 3.G Media

#### 3.38 `media_sessions_now_playing`: DEFER (cheap add-on to 3.33)
- **API**: `MediaSessionManager.getActiveSessions(ComponentName notificationListener)` and
  `addOnActiveSessionsChangedListener(...)` (21). Read `MediaController.getMetadata()` and
  `getPlaybackState()`.
- **Access**: "You may also retrieve this list if your app is an enabled notification listener ... in
  which case you must pass the `ComponentName` of your enabled listener" [R-MSM].
- **Value**: "listening to music or podcast" context for JITAIs. Ship after 3.33 is stable.

#### 3.39 `media_images_video_metadata`: debug only
- **Permissions** [G-PHOTO][G-MEDIA][R-MP]:
  - `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` (33).
  - `READ_MEDIA_VISUAL_USER_SELECTED` (34).
  - `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`.
  - `ACCESS_MEDIA_LOCATION` (29) for EXIF GPS.
  - Partial access means USER_SELECTED granted while IMAGES/VIDEO are denied, which maps to
    PARTIALLY_ALLOWED.
  - Apps targeting 34 that do not declare USER_SELECTED get compatibility behavior [G-PHOTO].
  - Android 16 (target 36): the picker pre-selects photos the app owns [BC16T].
- **Data**:
  - `MediaColumns.DATE_TAKEN` and `OWNER_PACKAGE_NAME` (29) [SDK].
  - `OWNER_PACKAGE_NAME` is redacted on 14+ unless the owner is visible or the caller has
    `QUERY_ALL_PACKAGES` [BC14A].
  - `MediaStore.getGeneration` (30) and `GENERATION_MODIFIED` (30) for incremental sync [SDK].
  - `MediaStore.getVersion()` is unique per app for apps targeting 36 [BC16T].
- **Triggers**: `JobInfo.Builder.addTriggerContentUri` (24), or WorkManager content-URI triggers.
- **Play**: "may only request ... if system pickers are not sufficient"; a declaration is required
  [P-PERM]. A photo-count signal is unlikely to justify that, hence debug only.

#### 3.40 `media_audio_metadata`: DEFER
- **Permissions**: `READ_MEDIA_AUDIO` (33) or `READ_EXTERNAL_STORAGE` (≤32) [R-MP].
- **Play**: no audio-specific rule appears in the fetched policy pages [P-PERM]. Classed as SENSITIVE
  (**UNVERIFIED** that no declaration applies).
- **Why deferred**: low value.

### 3.H Calendar

#### 3.41 `calendar_events`
- **API**: `CalendarContract.Instances.CONTENT_URI` (14) with a time range, which expands recurring
  events [R-CAL][G-CALP]. Store busy/free blocks, titles only when the user opts in, and attendee
  counts.
- **Permission**: `READ_CALENDAR` (dangerous) [R-MP].
- **Change detection**: a content-URI job trigger (`JobInfo.TriggerContentUri`, 24) on
  `CalendarContract.Events.CONTENT_URI`, plus a daily sync. `CalendarContract.ACTION_EVENT_REMINDER` (14) is manifest-exempt [G-BCASTEX], but
  delivery to non-calendar apps is **UNVERIFIED**.
- **Test**: Robolectric with a fake `ContentProvider` registered for the calendar authority through
  `Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)`.
  That overload exists in 4.17 [ROBO-CORE]. `ShadowContentResolver` is the lower-level alternative
  [ROBO].

### 3.I Health

#### 3.42 `health_connect_records`
- **Availability**: `HealthConnectClient.getSdkStatus(ctx)` returns `SDK_UNAVAILABLE`,
  `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` or `SDK_AVAILABLE` [R-HCC][G-HC-START]. Map them to
  UNSUPPORTED_ON_DEVICE, UNAVAILABLE and check-permissions respectively.
  - Framework on 34+; Health Connect app from Play on 28-33 [G-HC-START].
- **Permissions**: `android.permission.health.READ_*`, dangerous on 34+ [R-HP]. All 34 unless noted:
  - Activity: `READ_STEPS`, `READ_DISTANCE`, `READ_EXERCISE`, `READ_ACTIVE_CALORIES_BURNED`,
    `READ_TOTAL_CALORIES_BURNED`.
  - Heart: `READ_HEART_RATE`, `READ_RESTING_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`.
  - Sleep and vitals: `READ_SLEEP`, `READ_OXYGEN_SATURATION`, `READ_RESPIRATORY_RATE`.
  - Body: `READ_WEIGHT`.
  - Newer: `READ_SKIN_TEMPERATURE` (35; 34 ext 13), `READ_MINDFULNESS` (36; ext 15),
    `READ_ACTIVITY_INTENSITY` (36; ext 16), `READ_MEDICAL_DATA_*` (36; ext 16), `READ_SYMPTOM_*` and
    `READ_ALCOHOL_CONSUMPTION` (37.0; ext 21), `READ_MENSTRUAL_CYCLE_PHASE` (37.0; ext 22).
  - Sources: [SDK][R-HP].
- **Grant UI**: `PermissionController.createRequestPermissionResultContract()` [G-HC-START].
- **Manifest**:
  - A privacy-policy activity for `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`.
  - A `VIEW_PERMISSION_USAGE` activity-alias with the `HEALTH_PERMISSIONS` category, guarded by
    `START_VIEW_PERMISSION_USAGE` [G-HC-START].
  - Details are in `05-google-health-and-health-connect.md`.
- **States**: PARTIALLY_ALLOWED when only a subset is granted, from
  `permissionController.getGrantedPermissions()`. FOREGROUND_ONLY unless 3.43 is granted.
- **Settings**: `HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS` and
  `getHealthConnectManageDataIntent(ctx)` (Jetpack 1.1.0) [R-HCC]. The framework action is
  `HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS` (34) [SDK].
- **Play**: "In the Play Console, declare access to the Health Connect data types" [G-HC-START]. The
  health apps declaration [G-HC-TYPES] makes this DECLARATION_REQUIRED. Approved uses, no headless
  apps [P-PERM].
- **Test**: `androidx.health.connect:connect-testing` gives `FakeHealthConnectClient` and
  `FakePermissionController(grantAll = false)` [G-HC-UNIT][RN-HC]. Emulator: the Health Connect
  Toolbox app writes test data [G-HC-CASES].

#### 3.43 `health_connect_background_read`
- **Permission**: `READ_HEALTH_DATA_IN_BACKGROUND` (35; 34 ext 13), "Allows an application to read
  health data (of any type) in background" [R-HP].
- **Feature gate**: `healthConnectClient.features.getFeatureStatus(FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)
  == FEATURE_STATUS_AVAILABLE` [G-HC-READ].
- **Without it**: reads only while the app is in the foreground. The guide suggests an FGS for long
  foreground reads [G-HC-READ].
- **States**: BACKGROUND_ALLOWED vs FOREGROUND_ONLY.
- **Devices on 28-33** (the Health Connect app from Play): whether this feature, or the history
  permission in 3.44, can be available there is **UNVERIFIED**. Gate only on `getFeatureStatus`, never
  on the SDK level.
- **Android 16**: the same permission replaces `BODY_SENSORS_BACKGROUND` for apps targeting 36 [BC16T].

#### 3.44 `health_connect_history_read`
- **Permission**: `READ_HEALTH_DATA_HISTORY` (35; 34 ext 13) [R-HP][SDK]. By default apps read "up to 30
  days prior to when any permission was first granted". Reinstalling resets this [G-HC-READ].
- **States**: PARTIALLY_ALLOWED, a 30-day window, without it.

#### 3.45 `health_connect_on_device_steps`
- **Requirements**:
  - API 34 with SDK extension 20 or higher, checked with
    `SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 20` (30).
  - Active only when some app holds `READ_STEPS`.
  - Data is batched about once per minute.
  - Before June 2026 it is attributed to `DataOrigin("android")`; after that to an app-scoped synthetic
    package name.
  - Get the package name from `HealthConnectManager.getCurrentDeviceDataSource()`. Its level is a doc
    conflict:
    - The guide says it "is available on Android 14 (API level 34) with SDK extension version 11 or
      higher", and its sample checks `>= 11` [G-HC-READ].
    - The SDK databases for 37.0, 37.1 and 37.2-beta1 list it as `since="37.0"` with
      `sdks="34:22,35:22,36:22,37:22"`, that is U extension 22 (sdk id 34 is "U Extensions"). The 36.1
      database does not list it [SDK].
    - Gate it on `SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 22`, which
      is also what lint expects. Which value holds on devices is **UNVERIFIED**.
    - Without the method, read step aggregates with no `DataOrigin` filter. The guide says on-device
      steps are then "automatically included in the total" [G-HC-READ].
  - Source: [G-HC-READ].
- **Otherwise**: UNSUPPORTED_ON_DEVICE. Fall back to 3.29.

#### 3.46 `body_sensor_heart_rate`: DEFER
- **Sensor**: `Sensor.TYPE_HEART_RATE` (20).
- **Permission change**: for apps targeting 36, "any API previously requiring `BODY_SENSORS` or
  `BODY_SENSORS_BACKGROUND` requires the corresponding `android.permissions.health` permission instead"
  (`READ_HEART_RATE`). Mobile apps also need the privacy-policy activity: "Failure to provide the
  rationale for mobile apps will result in the permission being revoked" [BC16T].
- **Play**: "All requests for body sensor permissions (both legacy and new granular permissions) will
  be reviewed" [P-PERM].
- **Why deferred**: phones rarely have the sensor. Wearable data comes through Health Connect or
  Fitbit.

### 3.J Communication

#### 3.47 `call_state`: in call, ringing, idle (no numbers)
- **APIs**:
  - `TelephonyCallback.CallStateListener` (31) via `TelephonyManager.registerTelephonyCallback` (31)
    needs `READ_PHONE_STATE` [R-TCB][SDK].
  - `TelephonyManager.ACTION_PHONE_STATE_CHANGED` is manifest-exempt [G-BCASTEX] and needs
    `READ_PHONE_STATE`. `EXTRA_INCOMING_NUMBER` needs `READ_CALL_LOG`, which Agentle does not request
    [R-TM].
  - `getCallState()` is deprecated (31). On 31+ use `getCallStateForSubscription` [SDK][R-TM].
- **Permissionless signal**: `AudioManager.getMode()` (1) returns `MODE_IN_CALL` or
  `MODE_IN_COMMUNICATION` (11) for VoIP. `addOnModeChangedListener` (31) [SDK][R-AUD]. Whether every
  VoIP app sets `MODE_IN_COMMUNICATION` is **UNVERIFIED**.
- **States**: UNSUPPORTED_ON_DEVICE without `FEATURE_TELEPHONY_CALLING` (33), or `FEATURE_TELEPHONY`
  before 33 [SDK].
- **Play**: `READ_PHONE_STATE` is dangerous but not a restricted Play permission, so SENSITIVE.
- **Test**:
  - Emulator console: `gsm call <number>`, `gsm accept`, `gsm cancel` [G-EMU].
  - Robolectric: `ShadowTelephonyManager.setCallState(...)` [ROBO].

#### 3.48 `call_log_metadata`: DOCUMENT_UNAVAILABLE
- **Permission**: `READ_CALL_LOG` is hard-restricted: "cannot be held by an app until the installer on
  record allowlists the permission" [R-MP].
- **Play**: "must be actively registered as the default Phone or Assistant handler", unless a listed
  exception applies [P-PERM][P-SMSCL]. "Research" and "Social graph and personality profiling" are
  listed as invalid [P-SMSCL].
- **Sideload**: `ROLE_DIALER` is ECM-protected [AOSP-ECM].
- **Default-handler duty**: a default handler "must be able to perform the functionality for which
  it's a default handler" [G-DEFHANDLER]. Agentle is not a dialer.

#### 3.49 `sms_metadata`: DOCUMENT_UNAVAILABLE
- **Permissions**: `READ_SMS` and `RECEIVE_SMS` are hard-restricted [R-MP]. The SMS permissions and
  `ROLE_SMS` are ECM-protected [AOSP-ECM].
- **Play**: default SMS handler only, and "a default SMS handler must be able to send text messages"
  [G-DEFHANDLER][P-PERM].
- **Android 17**: OTP-bearing SMS are withheld for 3 hours. "the `SMS_RECEIVED_ACTION` broadcast is
  withheld and SMS provider database queries are filtered" [BC17T][BC17A].
- **Test only**: the emulator console has `sms send <number> <text>` [G-EMU].

#### 3.50 `contacts_metadata`: DEFER
- **Permission**: `READ_CONTACTS` (dangerous). Columns: `ContactsContract.ContactsColumns.CONTACT_LAST_UPDATED_TIMESTAMP`
  (18) and `ContactsContract.DeletedContacts` (18) [SDK][G-CONTP].
- **Android 17, target 37**:
  - CP2 removes `ACCOUNT_NAME`, `ACCOUNT_TYPE` and `ACCOUNT_TYPE_AND_DATA_SET` from the
    `ContactsContract.Data` view. Join `RawContacts` instead.
  - `Data` queries without `READ_CONTACTS` get strict SQL checks.
  - Source: [BC17T].
- **Play**: from 2027-01-27, apps targeting 17+ "may only request the `READ_CONTACTS` permission if the
  Android Contact Picker is not sufficient". A declaration is required [P-PREVIEW].

#### 3.51 `contacts_picker_selection`: DEFER
- **API**: `ContactsPickerSessionContract.ACTION_PICK_CONTACTS` (37) with
  `EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS`, `EXTRA_PICK_CONTACTS_SELECTION_LIMIT` and
  `EXTRA_PICK_CONTACTS_MATCH_ALL_DATA_FIELDS` [SDK].
  - It returns a session URI with temporary read access.
  - "Persist data immediately".
  - "Session URIs don't support custom `selection` and `selectionArgs`".
  - Before 37, `ACTION_PICK` with `EXTRA_USE_SYSTEM_CONTACTS_PICKER` can be used for testing.
  - Source: [G-PICKER].
- **Why deferred**: use it only if a "key people" feature appears. It needs no permission.

---

## 4. Permission Center grouping

The in-app Permission Center shows one card per group. Each card lists its capabilities with a
state chip (section 5) and one primary action. A single grant often unlocks capabilities in other
groups. Show those as "also enables ..." links.

| Group | User-facing grant(s) | Capabilities | Primary action / notes |
|---|---|---|---|
| Activity | "Physical activity" (`ACTIVITY_RECOGNITION`) | `activity_recognition_transitions`, `step_count_recording_api`, `step_counter_sensor` (deferred) | Runtime dialog. Also needs Google Play services. |
| Location | "Location": approximate/precise, while in use / all the time; device location toggle | `location_foreground`, `location_background` (deferred), `wifi_network_identity` (deferred) | Runtime dialog. "All the time" only through Settings on 30+ [G-LOCBG]. On Android 17 the location button gives one-time precise access. |
| Notifications | "Notification access" (special); "Notifications" (`POST_NOTIFICATIONS`); "Alarms & reminders" (exact alarms) | `notification_events_metadata`, `notification_content`, `dnd_state`, `post_notifications_jitai`, `exact_alarm_jitai_scheduling` | Notification access opens Settings (listener detail page on 30+). It also enables `media_sessions_now_playing` (Media) and background DND events. |
| Apps | "Usage access" (special); package visibility (no grant) | `app_usage_events`, `app_usage_aggregates`, `app_standby_bucket`, `installed_apps_inventory`, `network_data_usage`, `accessibility_event_stream` (not used) | Usage access opens Settings. It also enables history for `screen_interactive_events`, `unlock_keyguard_events` and `boot_shutdown_events` (Device State). |
| Bluetooth | "Nearby devices" (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) | `bluetooth_adapter_state` (no grant), `bluetooth_connected_devices`, `bluetooth_nearby_scan` (deferred) | Runtime dialog (`NEARBY_DEVICES` group). On Android 17 the same group holds `ACCESS_LOCAL_NETWORK` [G-LAN]. |
| Media | "Photos and videos", "Music and audio" | `media_images_video_metadata` (debug), `media_audio_metadata` (deferred), `media_sessions_now_playing` (needs Notification access) | Runtime dialog; partial selection on 34+. |
| Calendar | "Calendar" (`READ_CALENDAR`) | `calendar_events` | Runtime dialog. |
| Health | Health Connect permissions; "Body sensors" | `health_connect_records`, `health_connect_background_read`, `health_connect_history_read`, `health_connect_on_device_steps`, `body_sensor_heart_rate` (deferred) | Health Connect permission contract. Its settings intents are in section 6. |
| Communication | "Phone" (`READ_PHONE_STATE`); "Contacts"; Call logs and SMS (unavailable) | `call_state`, `call_log_metadata` (unavailable), `sms_metadata` (unavailable), `contacts_metadata` (deferred), `contacts_picker_selection` (deferred) | Show call logs and SMS as "Not available on Google Play builds" with the reason. |
| Device State | none for most; "Battery optimization" (special) | `screen_interactive_events`, `unlock_keyguard_events`, `display_state`, `battery_state`, `power_save_idle_state`, `thermal_status`, `storage_stats`, `boot_shutdown_events`, `network_connectivity`, `wifi_connection_metadata`, `airplane_mode`, `audio_volume_ringer`, `audio_output_devices`, `timezone_time_changes`, `locale_time_format`, `next_alarm_clock`, `background_execution_exemption` | Mostly ALLOWED by default. The card shows collector-health diagnostics: standby bucket, battery optimization, background restriction, hibernation. |
| Sensors | none | `motion_sensors` (debug), `ambient_proximity_sensors` (debug) | Show only in debug builds. |

---

## 5. Permission state model

### 5.1 States

| State | Meaning | User can fix in-app? | Typical primary action |
|---|---|---|---|
| `ALLOWED` | Every grant the capability needs is held, and it can collect now. | n/a | none |
| `DENIED` | A runtime permission is not held, and the system dialog can still be shown (never asked, or asked and `shouldShowRequestPermissionRationale()` is true). | yes | Show rationale, then launch the request. |
| `DENIED_PERMANENTLY` | A runtime permission is not held, was requested before, and the dialog is suppressed (user-fixed "don't ask again"). | no | Open `ACTION_APPLICATION_DETAILS_SETTINGS`. |
| `REQUIRES_SETTINGS` | Only a Settings screen can grant it: special access (usage access, notification access, exact alarm on 31+, battery optimization), "Allow all the time" location (30+), or user-applied background restriction. | no | Open the specific Settings screen from section 6. |
| `RESTRICTED_BY_ANDROID` | The platform or distribution policy blocks the grant regardless of the user's per-app choice. | no | Explain. Where possible deep-link to the escape hatch, such as "Allow restricted settings". |
| `UNAVAILABLE` | Granted (or no grant needed), but a transient condition blocks collection. | sometimes | Name the toggle or condition. |
| `PARTIALLY_ALLOWED` | Some but not all of the needed grants or data scope are held. | yes | Offer an "upgrade" request. |
| `FOREGROUND_ONLY` | Granted only while in use, so background collection is blocked. | yes | Explain, and offer "Allow all the time" or Health Connect background access. |
| `BACKGROUND_ALLOWED` | The background variant is granted (location "all the time", Health Connect background reads). Only used for capabilities with a foreground/background split. | n/a | none |
| `UNSUPPORTED_ON_DEVICE` | The hardware, OS level, SDK extension, device type or form factor lacks the capability. | no | Hide or grey out. |

Details by state:
- **RESTRICTED_BY_ANDROID** covers:
  - ECM / restricted settings for sideloads [H-RESTRICTED][AOSP-ECM];
  - hard-restricted permissions not allowlisted by the installer [R-MP];
  - device-policy or `UserManager` restrictions;
  - a work profile;
  - a capability withheld from this build flavor for Play policy reasons.
- **UNAVAILABLE** covers:
  - location services off;
  - Bluetooth off;
  - user locked (direct boot; usage queries return `null`) [R-USM];
  - Health Connect provider update required;
  - Google Play services missing or outdated;
  - the listener granted but not yet connected;
  - the app suspended (`areNotificationsPaused()`).
- **PARTIALLY_ALLOWED** examples:
  - coarse location only;
  - Selected Photos access on 34+;
  - a subset of Health Connect types;
  - no Health Connect history permission (30-day window);
  - notification content with OTP redaction on 35+;
  - screen events without usage access;
  - some notification channels blocked.
- **FOREGROUND_ONLY** examples: location without `ACCESS_BACKGROUND_LOCATION`; Health Connect without
  `READ_HEALTH_DATA_IN_BACKGROUND`; sensors without an FGS.
- **UNSUPPORTED_ON_DEVICE** examples:
  - missing `FEATURE_TELEPHONY_CALLING`;
  - no step counter sensor;
  - Health Connect `SDK_UNAVAILABLE`;
  - SDK extension below 20 for on-device steps;
  - low-RAM device on API ≤ 29 for notification listeners;
  - no activity resolves the special-access Settings screen.

Each capability resolves to **one primary state plus a list of blockers**, for example
`FOREGROUND_ONLY` with blockers `[LOCATION_SERVICES_OFF]`. The UI can then show every fix.

**Resolution precedence:**
1. UNSUPPORTED_ON_DEVICE
2. RESTRICTED_BY_ANDROID
3. DENIED_PERMANENTLY / DENIED / REQUIRES_SETTINGS. These are the grant problems. If several grants
   are missing, report the first missing mandatory grant.
4. UNAVAILABLE
5. PARTIALLY_ALLOWED
6. FOREGROUND_ONLY
7. ALLOWED / BACKGROUND_ALLOWED

Grant problems outrank toggles because the user can grant while a toggle is off, but not the other way
round.

### 5.2 Kotlin shape (sketch for the integrator)

```kotlin
enum class PermissionState { ALLOWED, DENIED, DENIED_PERMANENTLY, UNAVAILABLE, RESTRICTED_BY_ANDROID,
  REQUIRES_SETTINGS, PARTIALLY_ALLOWED, FOREGROUND_ONLY, BACKGROUND_ALLOWED, UNSUPPORTED_ON_DEVICE }

enum class Blocker { LOCATION_SERVICES_OFF, BLUETOOTH_OFF, USER_LOCKED, HC_UPDATE_REQUIRED,
  PLAY_SERVICES_MISSING, LISTENER_DISCONNECTED, ECM_RESTRICTED_SETTINGS, HARD_RESTRICTED_NOT_ALLOWLISTED,
  USER_RESTRICTION, MANAGED_PROFILE, BACKGROUND_RESTRICTED_BY_USER, HIBERNATION_ENABLED,
  STANDBY_BUCKET_RESTRICTED, OTP_REDACTION, NOT_IN_THIS_BUILD }

data class CapabilityStatus(
  val capabilityId: String,            // matches capabilities.json "id"
  val state: PermissionState,
  val blockers: List<Blocker> = emptyList(),
  val settingsIntent: Intent? = null,  // section 6
  val evaluatedAtMillis: Long,
)

interface CapabilityStateResolver {   // one per mechanism; Hilt multibinding by capability id
  suspend fun resolve(activity: Activity?): CapabilityStatus   // activity needed for rationale checks
}
```

Persist one flag per runtime permission, "requested at least once", in DataStore. Set it when the
request is launched, but not when the result array is empty, which "means cancellation"
[G-RUNTIME].

### 5.3 Detection with public APIs, by mechanism

**A. Runtime (dangerous) permissions**:
- **ALLOWED**: `ContextCompat.checkSelfPermission(ctx, p) == PERMISSION_GRANTED`
  (`Context.checkSelfPermission`, 23) [SDK].
- If not granted:
  - **DENIED**: the permission was never requested (the DataStore flag is false), or
    `ActivityCompat.shouldShowRequestPermissionRationale(activity, p)` is true. The method is
    `Activity` API 23; 35 adds a `deviceId` variant [SDK][R-ACTCOMPAT].
  - **DENIED_PERMANENTLY**: requested before, and the rationale call returns false.
- Why the rationale method decides it: the platform treats a permission as permanently denied after
  the user denies it more than once. The flags are `USER_SET` after the first denial and `USER_FIXED`
  after the permanent one, as shown by `adb shell dumpsys package <pkg>` [G-RUNTIME]. There is no public
  API for these flags (**undocumented** for apps), so this heuristic is the documented approach.
- **Re-check in `onResume`**:
  - Auto-reset of unused apps (30+) and hibernation (31+) revoke grants [G-RUNTIME][G-HIB].
  - One-time grants on 30+ expire. The process is killed when they are revoked [G-RUNTIME].
  - `revokeSelfPermissionOnKill` (33) exists for Agentle's own "disconnect" button [G-RUNTIME].
- **Hard-restricted permissions** (`READ_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`,
  `ACCESS_BACKGROUND_LOCATION`, `BODY_SENSORS_BACKGROUND`) [R-MP]:
  - `PackageManager.getWhitelistedRestrictedPermissions()` (29) "Can be accessed by pre-installed holders
    of a dedicated permission or the installer on record" [R-PKM]. Agentle cannot use it.
  - **Heuristic**: a request that returns denied immediately, with no dialog shown and no rationale,
    while the permission is hard-restricted means RESTRICTED_BY_ANDROID with blocker
    `HARD_RESTRICTED_NOT_ALLOWLISTED`.
  - Play installs allowlist the restricted permissions of approved apps (**UNVERIFIED** mechanism).
    Default `pm install` allowlists all restricted permissions unless `--restrict-permissions` is
    passed [AOSP-PMSC].
- **Location composite**:
  - Precise = `ACCESS_FINE_LOCATION` granted. Approximate-only = COARSE granted, FINE not. That is
    PARTIALLY_ALLOWED when precise is required.
  - Background = `ACCESS_BACKGROUND_LOCATION` granted (BACKGROUND_ALLOWED), otherwise FOREGROUND_ONLY.
  - Location toggle: `LocationManager.isLocationEnabled()` (28) gives blocker `LOCATION_SERVICES_OFF`
    [SDK]. Changes arrive on `LocationManager.MODE_CHANGED_ACTION` (19) / `PROVIDERS_CHANGED_ACTION` (9)
    with runtime receivers.
  - The background request on 30+ is REQUIRES_SETTINGS. Label it with `getBackgroundPermissionOptionLabel()`
    (30) [G-LOCBG].
  - Restrictions: `UserManager.hasUserRestriction(DISALLOW_SHARE_LOCATION | DISALLOW_CONFIG_LOCATION)`
    gives RESTRICTED_BY_ANDROID [SDK].
- **Selected Photos (34+)**: `READ_MEDIA_VISUAL_USER_SELECTED` granted while `READ_MEDIA_IMAGES` and
  `READ_MEDIA_VIDEO` are denied means PARTIALLY_ALLOWED [G-PHOTO].

**B. Usage access** (app-op `OPSTR_GET_USAGE_STATS`):
```kotlin
val appOps = ctx.getSystemService(AppOpsManager::class.java)
val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) // API 19/21, not deprecated
val granted = if (mode == AppOpsManager.MODE_DEFAULT)
  ctx.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
else mode == AppOpsManager.MODE_ALLOWED
```
- The `MODE_DEFAULT` fallback mirrors AOSP `UsageStatsService.hasQueryPermission()`. It calls
  `checkCallingPermission(PACKAGE_USAGE_STATS)` when the op mode is `MODE_DEFAULT` [AOSP-USS].
- `unsafeCheckOpNoThrow` (29) is deprecated in 36 [SDK]. The `checkOpNoThrow(String,int,String)`
  overload is not deprecated [SDK][R-AOM].
- Live updates: `AppOpsManager.startWatchingMode(OPSTR_GET_USAGE_STATS, packageName, listener)` (19)
  [SDK].
- State mapping:
  - Not granted: REQUIRES_SETTINGS.
  - Not granted on a likely ECM-guarded install (see E): RESTRICTED_BY_ANDROID, with the instruction
    "App info > More > Allow restricted settings".
  - `UserManager.isUserUnlocked()` (24) false: UNAVAILABLE (`USER_LOCKED`) [R-USM].
  - `ACTION_USAGE_ACCESS_SETTINGS` resolves to no activity: UNSUPPORTED_ON_DEVICE [R-SET].

**C. Notification listener**:
- **Granted**: `NotificationManager.isNotificationListenerAccessGranted(ComponentName(ctx,
  AgentleNotificationListener::class.java))` (27) [R-NM].
- **Connected**: set a flag in `onListenerConnected()` and clear it in `onListenerDisconnected()`. If
  granted but disconnected, call `NotificationListenerService.requestRebind(component)` (24). Blocker:
  `LISTENER_DISCONNECTED`.
- **UNSUPPORTED_ON_DEVICE** [R-NLS]:
  - `ActivityManager.isLowRamDevice()` (19) && `SDK_INT <= 29`;
  - `UserManager.isManagedProfile()` (30), because work-profile listeners are ignored.
- **ECM**: same heuristic as B.
- **notification_content on 35+**: PARTIALLY_ALLOWED with blocker `OTP_REDACTION` [BC15A].

**D. Other special accesses**:
- **DND access**: `NotificationManager.isNotificationPolicyAccessGranted()` (23).
  `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` (23) arrives on registered receivers [SDK][R-NM].
  Agentle does not request it.
- **Exact alarms**:
  - `AlarmManager.canScheduleExactAlarms()` (31); always ALLOWED on ≤30.
  - Not granted: REQUIRES_SETTINGS.
  - Listen for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` [G-ALARMS][SDK].
- **Battery optimization**:
  - `PowerManager.isIgnoringBatteryOptimizations(pkg)` (23). Not exempt: REQUIRES_SETTINGS
    (diagnostic, not a hard block).
  - `ActivityManager.isBackgroundRestricted()` (28) true: REQUIRES_SETTINGS with blocker
    `BACKGROUND_RESTRICTED_BY_USER`.
  - `UsageStatsManager.getAppStandbyBucket() == STANDBY_BUCKET_RESTRICTED` (30): blocker
    `STANDBY_BUCKET_RESTRICTED`.
  - `PackageManagerCompat.getUnusedAppRestrictionsStatus()` in `API_30*` or `API_31`: blocker
    `HIBERNATION_ENABLED` [G-HIB].
- **Health Connect**:
  - `getSdkStatus`: `SDK_UNAVAILABLE` gives UNSUPPORTED_ON_DEVICE; `..._PROVIDER_UPDATE_REQUIRED`
    gives UNAVAILABLE [R-HCC].
  - Granted set: `healthConnectClient.permissionController.getGrantedPermissions()`. All requested
    gives ALLOWED; a subset gives PARTIALLY_ALLOWED; none gives DENIED.
  - Background: `getFeatureStatus(FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)` plus the permission gives
    BACKGROUND_ALLOWED, otherwise FOREGROUND_ONLY [G-HC-READ].
  - On-device steps: `SdkExtensions.getExtensionVersion(UPSIDE_DOWN_CAKE) >= 20`, otherwise
    UNSUPPORTED_ON_DEVICE [G-HC-READ].
  - Health Connect's own "don't ask again" behavior is **undocumented** in the pages read. Treat a
    repeated empty grant result as REQUIRES_SETTINGS, which opens Health Connect settings.
- **Roles** (reported, never requested): `RoleManager.isRoleAvailable/isRoleHeld(ROLE_SMS |
  ROLE_DIALER)` (29) [SDK]. On Play builds `call_log_metadata` and `sms_metadata` are always
  RESTRICTED_BY_ANDROID with blocker `NOT_IN_THIS_BUILD`. The permissions are not declared in the
  manifest.

**E. ECM ("restricted settings") heuristic**:
- `EnhancedConfirmationManager` is not in the public SDK [SDK]. The decision logic is in
  `EnhancedConfirmationService.isPackageEcmGuarded()`. Allowlisted and preinstalled installers are
  trusted. `PACKAGE_SOURCE_LOCAL_FILE` and `PACKAGE_SOURCE_DOWNLOADED_FILE` are always guarded.
  Otherwise the installer's trust decides [AOSP-ECM].
- Agentle reads `packageManager.getInstallSourceInfo(packageName).packageSource` (33). If it is one of
  those two sources, and a special access is still off after the user returned from its Settings page,
  report RESTRICTED_BY_ANDROID with blocker `ECM_RESTRICTED_SETTINGS`. Link
  `ACTION_APPLICATION_DETAILS_SETTINGS` and the text "More > Allow restricted settings" [H-RESTRICTED].
- On 30-32 use `getInstallSourceInfo().installingPackageName`; restricted settings start in Android 13
  [H-RESTRICTED].

**F. Toggles and hardware**:
- `PackageManager.hasSystemFeature(...)`:
  - `FEATURE_TELEPHONY_CALLING` (33) / `FEATURE_TELEPHONY` (7);
  - `FEATURE_BLUETOOTH` (8);
  - `FEATURE_WIFI` (8);
  - `FEATURE_LOCATION` (8);
  - `FEATURE_SENSOR_STEP_COUNTER` (19);
  - `FEATURE_SENSOR_LIGHT` / `PROXIMITY` (7);
  - `FEATURE_SENSOR_HEART_RATE` (20);
  - `FEATURE_RAM_LOW` (27).
  - Source: [SDK].
- `SensorManager.getDefaultSensor(type) == null` gives UNSUPPORTED_ON_DEVICE.
- `BluetoothAdapter.isEnabled() == false` gives UNAVAILABLE (`BLUETOOTH_OFF`).
- `UserManager.hasUserRestriction` (21) with `DISALLOW_BLUETOOTH` (26) gives RESTRICTED_BY_ANDROID [SDK].

### 5.4 When to re-evaluate

- **Lifecycle**:
  - `onResume` of the Permission Center and of the screen that launched a Settings intent. Special
    permissions "Check the user's response ... in the `onResume()` method" [G-SPECIAL].
  - `ProcessLifecycleOwner` `ON_START`.
  - Before every collector run in WorkManager. The worker records a "skipped: state X" event instead of
    failing.
- **Push signals**:
  - `AppOpsManager.OnOpChangedListener` for usage access;
  - listener connect and disconnect callbacks;
  - `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`;
  - `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` (registered);
  - `LocationManager.MODE_CHANGED_ACTION` (registered);
  - `BluetoothAdapter.ACTION_STATE_CHANGED` (registered);
  - `ACTION_APP_BLOCK_STATE_CHANGED` (28).
  - Sources: [SDK][R-NM][G-ALARMS].
- **After the activity result** of `RequestPermission`, the Health Connect permission contract, and
  `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (which returns `RESULT_OK` when granted) [R-SET].

### 5.5 Possible states per capability

Legend: AL = ALLOWED, D = DENIED, DP = DENIED_PERMANENTLY, RS = REQUIRES_SETTINGS,
RA = RESTRICTED_BY_ANDROID, UA = UNAVAILABLE, PA = PARTIALLY_ALLOWED, FO = FOREGROUND_ONLY,
BA = BACKGROUND_ALLOWED, US = UNSUPPORTED_ON_DEVICE.

| Capability | Possible states |
|---|---|
| `app_usage_events`, `app_usage_aggregates`, `network_data_usage` | AL, RS, RA (ECM), UA (user locked), US |
| `screen_interactive_events`, `unlock_keyguard_events`, `boot_shutdown_events` | AL, PA (live only, no usage access), RA, UA |
| `app_standby_bucket` | AL (own), PA (other apps need usage access) |
| `installed_apps_inventory` | AL (launcher-visible apps) |
| `notification_events_metadata`, `media_sessions_now_playing` | AL, RS, RA (ECM, managed profile), UA (disconnected), US (low-RAM ≤29) |
| `notification_content` | AL, PA (OTP redaction 35+), RS, RA, UA, US |
| `dnd_state` | AL (polling, NLS optional) |
| `post_notifications_jitai` | AL, D, DP, PA (channels blocked), UA (paused/suspended) |
| `exact_alarm_jitai_scheduling` | AL, RS |
| `background_execution_exemption` | AL, RS (+ diagnostic blockers) |
| `location_foreground` | AL, D, DP, PA (coarse only), FO, UA (location off), RA |
| `location_background`, `wifi_network_identity` | BA, FO, RS, D, DP, UA, RA |
| `activity_recognition_transitions`, `step_count_recording_api` | AL, D, DP, UA (Play services) |
| `step_counter_sensor` | FO, D, DP, US (no sensor) |
| `health_connect_records` | AL, PA, D, RS, UA (update required), US (SDK unavailable), FO |
| `health_connect_background_read` | BA, FO, US (feature unavailable) |
| `health_connect_history_read` | AL, PA (30-day window) |
| `health_connect_on_device_steps` | BA, FO, D, US (API < 34 or ext < 20) |
| `body_sensor_heart_rate` | AL, D, DP, US (no sensor) |
| `bluetooth_adapter_state` | AL, UA (off), US, RA |
| `bluetooth_connected_devices`, `bluetooth_nearby_scan` | AL, D, DP, UA (off), US, RA |
| `media_images_video_metadata` | AL, PA (selected photos), D, DP, RA (not in Play build) |
| `media_audio_metadata`, `calendar_events`, `contacts_metadata` | AL, D, DP |
| `call_state` | AL, D, DP, US (no telephony) |
| `call_log_metadata`, `sms_metadata` | RA (not in Play build; hard-restricted; ECM), US (no telephony) |
| `contacts_picker_selection` | AL (37+), US (< 37) |
| `motion_sensors`, `ambient_proximity_sensors` | FO, US (no sensor), PA (rate-limited) |
| device-state capabilities with no grant (`display_state`, `battery_state`, `power_save_idle_state`, `thermal_status`, `storage_stats`, `network_connectivity`, `wifi_connection_metadata`, `airplane_mode`, `audio_volume_ringer`, `audio_output_devices`, `timezone_time_changes`, `locale_time_format`, `next_alarm_clock`) | AL, US (rare: missing hardware or API) |
| `accessibility_event_stream` | RA (`NOT_IN_THIS_BUILD`) |

---

## 6. Settings screens to open

Every Settings intent must be guarded with `intent.resolveActivity(packageManager) != null` or by
catching `ActivityNotFoundException`. The reference warns "In some cases, a matching Activity may not
exist" [R-SET]. Fall back to `ACTION_APPLICATION_DETAILS_SETTINGS`, then `Settings.ACTION_SETTINGS`.

| Need | Intent (API) | Input | Fallback / notes |
|---|---|---|---|
| Any runtime permission permanently denied | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (9) | data `package:<pkg>` | Tell the user "Permissions > <group>". [R-SET] |
| Usage access | `Settings.ACTION_USAGE_ACCESS_SETTINGS` (21) | "Input: Nothing" | Lists all apps. A `package:` URI is **undocumented**. [R-SET] |
| Notification access (30+) | `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (30) | `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` = flattened `ComponentName` | Opens the app's own toggle. [R-SET] |
| Notification access (29) | `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (22) | none | List screen. [SDK] |
| Restricted settings (ECM) | `ACTION_APPLICATION_DETAILS_SETTINGS` | `package:<pkg>` | User taps More > Allow restricted settings. [H-RESTRICTED] |
| DND access (not requested) | `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` (23) | none | "Managed profiles cannot grant Notification Policy access". [R-SET] |
| Exact alarms | `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (31) | data `package:<pkg>` | With a package URI the result is `RESULT_OK` if granted. [R-SET] |
| Battery optimization list | `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (23) | none | Preferred over the direct request. [R-SET][G-DOZE] |
| Battery optimization direct request (not used) | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (23) | `package:<pkg>` | Needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; Play-restricted. [R-SET][G-DOZE] |
| Hibernation / unused-app restrictions | `IntentCompat.createManageUnusedAppRestrictionsIntent(ctx, pkg)` | | Use with `startActivityForResult`. [G-HIB] |
| Background location (30+) | `ACTION_APPLICATION_DETAILS_SETTINGS` | `package:<pkg>` | Label from `getBackgroundPermissionOptionLabel()`. [G-LOCBG] |
| Location services off | `Settings.ACTION_LOCATION_SOURCE_SETTINGS` (1) | none | [SDK] |
| Bluetooth off | `Settings.ACTION_BLUETOOTH_SETTINGS` (1) | none | Or `BluetoothAdapter.ACTION_REQUEST_ENABLE` (5). For apps targeting S or higher it "requires the `BLUETOOTH_CONNECT` permission"; the result is `RESULT_OK` when Bluetooth was turned on. [SDK][R-BTA] |
| Wi-Fi | `Settings.ACTION_WIFI_SETTINGS` (1) | none | [SDK] |
| Airplane mode | `Settings.ACTION_AIRPLANE_MODE_SETTINGS` (3) | none | Read-only signal; rarely needed. [SDK] |
| App notifications (JITAIs) | `Settings.ACTION_APP_NOTIFICATION_SETTINGS` (26) | `EXTRA_APP_PACKAGE` | Per channel: `ACTION_CHANNEL_NOTIFICATION_SETTINGS` (26). [R-SET][SDK] |
| Health Connect permissions | `HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS` (Jetpack); framework `HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS` (34) | | `getHealthConnectManageDataIntent(ctx)` (Jetpack 1.1.0) for data management. [R-HCC][SDK] |
| Health Connect install/update | Play Store page for `com.google.android.apps.healthdata` | | When `getSdkStatus` is `..._PROVIDER_UPDATE_REQUIRED`. See 05 doc. [R-HCC] |
| Data saver | `Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` (24) | data `package:<pkg>` | Per-app background data screen. Read-only signal, so optional. [R-SET] |
| Date and time / time zone | `Settings.ACTION_DATE_SETTINGS` (1) | | [SDK] |
| Locale | `Settings.ACTION_LOCALE_SETTINGS` (1); app locale `ACTION_APP_LOCALE_SETTINGS` (33) | | [SDK] |
| Sound / DND | `Settings.ACTION_SOUND_SETTINGS` (1); `ACTION_ZEN_MODE_PRIORITY_SETTINGS` (26); `ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` (35) | | [SDK] |
| Default apps (SMS or dialer) | `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (24) | | Informational only. Agentle never requests roles. [SDK] |
| Data usage | `Settings.ACTION_DATA_USAGE_SETTINGS` (28) | | [SDK] |
| Storage | `Settings.ACTION_INTERNAL_STORAGE_SETTINGS` (3) | | [SDK] |

---

## 7. Testing each state (Robolectric and emulator)

Doc 08 (`08-testing-strategy.md`) owns the test architecture: modules, CI tiers and the full SDK
matrix. This section gives the per-state recipes that the permission resolvers need.

### 7.1 Robolectric setup and traps

- **Version**:
  - Robolectric 4.17 knows SDKs 23 (M) through 37 (`CINNAMON_BUN`).
  - Running SDK 36 or 37 needs Java 21: `DefaultSdkProvider` sets `requiredJavaVersion` to 21 for
    both [ROBO-SDK].
  - The unit-test JVM toolchain must therefore be 21 or newer, even if the app compiles for Java 17.
- **SDK axis for the resolvers**:
  - Use `@Config(sdk = [29, 30, 31, 33, 34, 35, 36, 37])`. These are the levels inside Agentle's range
    where a permission rule changes (section 7.5).
  - Add 26 and 28 only if minSdk goes below 29. Doc 08's `PlatformMatrix.SDKS` already contains them.
- **Seams**: each `CapabilityStateResolver` reads the platform through small injected wrappers. Tests
  use real shadows where they exist and fakes where they do not. Fakes are needed for:
  - `NetworkStatsManager` and `HealthConnectManager`: no shadow class in `shadows-framework-4.17.jar`
    [ROBO-JAR].
  - `SdkExtensions.getExtensionVersion`: no shadow class [ROBO-JAR]. Wrap it in an
    `SdkExtensionSource` interface.
  - Health Connect: `HealthConnectClient.getSdkStatus` is a static call, so wrap it. Use
    `FakeHealthConnectClient` for the client itself [G-HC-UNIT][AX-HCFAKE].
  - Play services (Activity Recognition, Recording API, `GoogleApiAvailability`): no shadows; wrap
    them.
  - ECM state: `EnhancedConfirmationManager` is not in the public SDK [SDK]. Only Agentle's heuristic
    (section 5.3 E) can be tested.
- **Traps**:
  - **AppOps default.** `ShadowAppOpsManager` returns `MODE_ALLOWED` for any op a test did not set:
    `unsafeCheckOpRawNoThrow` falls through to `MODE_ALLOWED` [ROBO].
    - A "usage access denied" test must call `setMode(...)` with `MODE_IGNORED` or `MODE_DEFAULT`.
      Otherwise it passes without testing anything.
    - `setMode` notifies listeners registered with `startWatchingMode`, so live op-change handling is
      testable [ROBO].
  - **Notification listener binding.** Robolectric does not bind a `NotificationListenerService`.
    - Build the service with `Robolectric.buildService(AgentleNotificationListener::class.java)`
      [ROBO-CORE].
    - Call `onListenerConnected()`, `onNotificationPosted(...)` and `onNotificationRemoved(..., reason)`
      directly.
    - `StatusBarNotification` has no shadow, but its public constructor (18, deprecated in 26) runs as
      real framework code, so tests can build instances [SDK][ROBO-JAR].
  - **Receiver export flags.** Robolectric enforces the Android 14 rule that runtime receivers pass
    `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED`, but only when the system property
    `robolectric.validateReceiverExportFlags` is `true`.
    - It does not model the platform's exemption for receivers that listen only to system broadcasts
      [ROBO][BC14T].
    - Leave the property off for collector-receiver tests.
  - **Sticky broadcasts.** `registerReceiver(null, filter)` returns a sticky intent seeded with
    `sendStickyBroadcast` [ROBO].

### 7.2 Robolectric recipes by state

`shadowOf(...)` is the static import `org.robolectric.Shadows.shadowOf`. Every API named
below was checked in the 4.17 sources or jar [ROBO][ROBO-JAR][ROBO-CORE] unless marked.

| State | Arrange | Example capability |
|---|---|---|
| ALLOWED (runtime) | `shadowOf(application).grantPermissions(READ_CALENDAR)`. `ShadowApplication` extends `ShadowContextWrapper`, which defines `grantPermissions`/`denyPermissions`. | `calendar_events` |
| DENIED, never asked | No grant; DataStore "requested" flag false. After the request, `shadowOf(activity).getLastRequestedPermission()` shows what was asked. | any runtime permission |
| DENIED, rationale | Flag true; `shadowOf(packageManager).setShouldShowRequestPermissionRationale(p, true)`. | any runtime permission |
| DENIED_PERMANENTLY | Flag true; `denyPermissions(p)`; `setShouldShowRequestPermissionRationale(p, false)`. | any runtime permission |
| REQUIRES_SETTINGS, usage access | `shadowOf(appOps).setMode(OPSTR_GET_USAGE_STATS, Process.myUid(), pkg, MODE_IGNORED)`. Second case: `MODE_DEFAULT` plus `denyPermissions(PACKAGE_USAGE_STATS)`, which exercises the AOSP-style fallback. | `app_usage_events` |
| REQUIRES_SETTINGS, notification access | `shadowOf(notificationManager).setNotificationListenerAccessGranted(component, false)`. | `notification_events_metadata` |
| REQUIRES_SETTINGS, exact alarms | `@Config(sdk = [31, ...])`; `ShadowAlarmManager.setCanScheduleExactAlarms(false)` (static). | `exact_alarm_jitai_scheduling` |
| REQUIRES_SETTINGS, background restricted | `shadowOf(activityManager).setBackgroundRestricted(true)`. | `background_execution_exemption` |
| Diagnostic: not battery-exempt | `shadowOf(powerManager).setIgnoringBatteryOptimizations(pkg, false)`. | `background_execution_exemption` |
| RESTRICTED_BY_ANDROID, ECM heuristic | `@Config(sdk >= 33)`; `shadowOf(packageManager).setInstallSourceInfo(pkg, initiating, null, null, installer, null, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)` (seven-argument overload); op `MODE_IGNORED`; "returned from Settings" flag set. | `app_usage_events` |
| RESTRICTED_BY_ANDROID, user restriction | `shadowOf(userManager).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_SHARE_LOCATION, true)`. | `location_foreground` |
| RESTRICTED_BY_ANDROID, managed profile | `shadowOf(userManager).setManagedProfile(true)`. | `notification_events_metadata` |
| RESTRICTED_BY_ANDROID, not in this build | A Play-flavor test asserts that the `sms_metadata` resolver returns `NOT_IN_THIS_BUILD`. A manifest check asserts that the merged Play manifest has no `READ_SMS`. | `sms_metadata` |
| UNAVAILABLE, user locked | `shadowOf(userManager).setUserUnlocked(false)`. | `app_usage_events` |
| UNAVAILABLE, location off | `shadowOf(locationManager).setLocationEnabled(false)`. | `location_foreground` |
| UNAVAILABLE, Bluetooth off | `shadowOf(bluetoothAdapter).setEnabled(false)` or `setState(STATE_OFF)`. | `bluetooth_connected_devices` |
| UNAVAILABLE, listener disconnected | Grant access but skip `onListenerConnected()`. Assert that `ShadowNotificationListenerService.getRebindRequestCount()` grows after the resolver runs. | `notification_events_metadata` |
| UNAVAILABLE, Health Connect update needed | The availability wrapper returns `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`. | `health_connect_records` |
| PARTIALLY_ALLOWED, coarse only | Grant COARSE; `denyPermissions(ACCESS_FINE_LOCATION)`. | `location_foreground` |
| PARTIALLY_ALLOWED, selected photos | `@Config(sdk = [34, ...])`; grant only `READ_MEDIA_VISUAL_USER_SELECTED`. | `media_images_video_metadata` |
| PARTIALLY_ALLOWED, Health Connect subset | `FakePermissionController(grantAll = false)`, then `grantPermission(...)` or `grantPermissions(setOf(...))`. Pass it as `FakeHealthConnectClient(permissionController = ...)` [AX-HCFAKE]. | `health_connect_records` |
| PARTIALLY_ALLOWED, channel blocked | `notificationManager.createNotificationChannel(NotificationChannel(id, name, IMPORTANCE_NONE))`. The shadow stores channels. | `post_notifications_jitai` |
| FOREGROUND_ONLY and BACKGROUND_ALLOWED, location | Grant FINE or COARSE; then also grant `ACCESS_BACKGROUND_LOCATION`. | `location_background` |
| FOREGROUND_ONLY and BACKGROUND_ALLOWED, Health Connect | `FakeHealthConnectFeatures().setFeatureStatus(FEATURE_READ_HEALTH_DATA_IN_BACKGROUND, FEATURE_STATUS_AVAILABLE)`; all features are unavailable by default. Grant or revoke the background permission on the fake controller. `FakeHealthConnectFeatures` is on `androidx-main`; whether `connect-testing` 1.0.0-alpha04 ships it is **UNVERIFIED** [AX-HCFAKE][RN-HC]. | `health_connect_background_read` |
| UNSUPPORTED_ON_DEVICE, missing feature | `shadowOf(packageManager).setSystemFeature(FEATURE_TELEPHONY_CALLING, false)`. | `call_state` |
| UNSUPPORTED_ON_DEVICE, no Bluetooth | `ShadowBluetoothAdapter.setIsBluetoothSupported(false)` (static); the resolver must handle a null adapter. | `bluetooth_adapter_state` |
| UNSUPPORTED_ON_DEVICE, low-RAM listener | `@Config(sdk = [29])`; `shadowOf(activityManager).setIsLowRamDevice(true)`. | `notification_events_metadata` |
| UNSUPPORTED_ON_DEVICE, missing sensor | Add no sensor. Positive case: `shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(TYPE_STEP_COUNTER))`; events through `SensorEventBuilder` and `sendSensorEventToListeners`. | `step_counter_sensor` |
| UNSUPPORTED_ON_DEVICE, SDK extension too low | The `SdkExtensionSource` fake returns 19. | `health_connect_on_device_steps` |
| UNSUPPORTED_ON_DEVICE, Settings screen missing | Positive case: `shadowOf(packageManager).addResolveInfoForIntent(Intent(ACTION_USAGE_ACCESS_SETTINGS), resolveInfo)`. Whether 4.17 resolves Settings actions without such a registration is **UNVERIFIED**, so tests should set it either way. | `app_usage_events` |

Collector fixtures, such as fake usage events, battery extras or thermal status, are listed under
"Test" in each section 3 entry. Useful builders: `ShadowUsageStatsManager.EventBuilder.buildEvent()`
(`setPackage`, `setClass`, `setTimeStamp`, `setEventType`) [ROBO-JAR], and
`Robolectric.setupContentProvider(...)` for calendar and contacts [ROBO-CORE].

### 7.3 Emulator and adb recipes

Run each command as `adb shell <command>` unless it starts with `adb`. From an instrumented test, run
the same commands with `UiAutomation.executeShellCommand(...)` (21). Grant or revoke runtime
permissions with `UiAutomation.grantRuntimePermission` / `revokeRuntimePermission` (28) [SDK].

**Runtime permissions**

| Goal | Command | Source |
|---|---|---|
| Grant or revoke | `pm grant <pkg> <perm>`; `pm revoke <pkg> <perm>` | [AOSP-PMSC] |
| Back to "never asked" | `pm revoke <pkg> <perm>`; `pm clear-permission-flags <pkg> <perm> user-set user-fixed` | [G-NOTIFPERM][AOSP-PMSC] |
| DENIED with rationale (denied once) | `pm revoke ...`; `pm set-permission-flags <pkg> <perm> user-set`; `pm clear-permission-flags <pkg> <perm> user-fixed` | [G-NOTIFPERM] |
| DENIED_PERMANENTLY | `pm revoke ...`; `pm set-permission-flags <pkg> <perm> user-set user-fixed` | [AOSP-PMSC][G-RUNTIME] |
| Inspect flags | `dumpsys package <pkg>` prints lines such as `granted=false, flags=[ USER_SET\|USER_FIXED ...]` | [G-RUNTIME] |
| Install with every runtime permission granted | `adb install -g app.apk` | [AOSP-PMSC] |
| Hard-restricted permission not allowlisted | `adb install --restrict-permissions app.apk`. Without the flag, a shell install allowlists all restricted permissions. | [AOSP-PMSC] |

**Special access and background state**

| Goal | Command | Source |
|---|---|---|
| Usage access on, off or default | `cmd appops set <pkg> android:get_usage_stats allow\|ignore\|default`; check with `cmd appops get <pkg> android:get_usage_stats`. Mode names: `allow`, `ignore`, `deny`, `default`, `foreground`. | [AOSP-AOS][AOSP-AOM] |
| Notification access | `cmd notification allow_listener <pkg>/<listener class>`; `cmd notification disallow_listener <pkg>/<listener class>` | [AOSP-NSC] |
| A notification from another package (the shell) | `cmd notification post -t "Title" -S bigtext <tag> "<text>"`; list with `cmd notification list`; snooze with `cmd notification snooze --for <ms> <key>` | [AOSP-NSC] |
| DND state and DND access | `cmd notification set_dnd on\|priority\|alarms\|off`; `cmd notification allow_dnd <pkg>` / `disallow_dnd <pkg>` | [AOSP-NSC] |
| Exact alarms (31+) | `cmd appops set <pkg> SCHEDULE_EXACT_ALARM allow\|default` | [AOSP-AOS][AOSP-AOM] |
| Battery-optimization exemption | `dumpsys deviceidle whitelist +<pkg>`; remove with `-<pkg>` | [AOSP-DIC] |
| "Restricted" background usage | `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore`. In this state FGS cannot start, alarms do not fire and jobs do not run. Or `am set-bg-restriction-level <pkg> unrestricted\|exempted\|adaptive_bucket\|restricted_bucket\|background_restricted\|hibernation`, read back with `am get-bg-restriction-level <pkg>`. | [BC13A][AOSP-AMSC] |
| Standby bucket | `am set-standby-bucket <pkg> active\|working_set\|frequent\|rare\|restricted`; `am get-standby-bucket <pkg>` | [BC16A][AOSP-AMSC] |
| App Standby (inactive) | `am set-inactive <pkg> true\|false`; `am get-inactive <pkg>` | [G-DOZE][AOSP-AMSC] |
| Hibernation, documented path | `device_config put app_hibernation app_hibernation_enabled true`. Save `device_config get permissions auto_revoke_unused_threshold_millis2`, then set it to `1000`. Run `am wait-for-broadcast-idle`, then `cmd jobscheduler run -u 0 -f com.google.android.permissioncontroller 2`. Check `cmd app_hibernation get-state <pkg>`, then restore the threshold. | [G-HIB] |
| Hibernation, direct | `cmd app_hibernation set-state [--global] <pkg> true\|false`; `get-state` | [AOSP-HIBSC] |
| Force-stop (Android 15 cancels the app's pending intents) | `am force-stop <pkg>` | [AOSP-AMSC][BC15A] |
| ECM "restricted settings" | `adb install --package-source 4 app.apk` (4 = `PACKAGE_SOURCE_DOWNLOADED_FILE`; 3 = `PACKAGE_SOURCE_LOCAL_FILE`). ECM always guards those two sources. A plain shell install records `PACKAGE_SOURCE_OTHER` with the preinstalled shell as installer, so by the same code it is not guarded. Device behavior is **UNVERIFIED**. Android 13-14 used an older restricted-settings mechanism, so these flags may behave differently there (**UNVERIFIED**). | [AOSP-PMSC][AOSP-ECM][SDK] |

**Device state**

| Goal | Command | Source |
|---|---|---|
| Screen off and on | `input keyevent KEYCODE_SLEEP` / `KEYCODE_WAKEUP`; or `cmd power sleep` / `cmd power wakeup` | [AOSP-INSC][AOSP-PWRSC] |
| Secure keyguard and unlock | `locksettings set-pin 1234`; remove with `locksettings clear --old 1234`. To unlock: `input keyevent KEYCODE_WAKEUP`, `wm dismiss-keyguard`, then `input text 1234` and `input keyevent KEYCODE_ENTER`. | [AOSP-LSSC][AOSP-WMSC][AOSP-INSC] |
| Battery level and plug type | `dumpsys battery set level 15`; `dumpsys battery set ac\|usb\|wireless\|dock 1`; `dumpsys battery unplug`; `dumpsys battery reset`. `-f` forces a battery-changed broadcast. | [AOSP-BAT] |
| Battery, emulator console | `adb emu power capacity 15`; `adb emu power ac off`; `adb emu power status discharging` | [G-EMU] |
| Doze | `dumpsys battery unplug`; `dumpsys deviceidle force-idle [light\|deep]` or `dumpsys deviceidle step [light\|deep]`; then `dumpsys deviceidle unforce` and `dumpsys battery reset` | [G-DOZE][AOSP-DIC] |
| Battery saver | `cmd power set-mode 1`, and `0` to turn it off | [AOSP-PWRSC] |
| Thermal status | `cmd thermalservice override-status <0-6>` (codes from `android.os.Temperature`); `cmd thermalservice reset`; `cmd thermalservice headroom <seconds>` | [AOSP-THERM] |
| Location toggle | `cmd location set-location-enabled true\|false`; `cmd location is-location-enabled` | [AOSP-LOCSC] |
| Location fixes | Emulator: `adb emu geo fix <lon> <lat>`. Any device: `cmd location providers add-test-provider gps`, `cmd location providers set-test-provider-enabled gps true`, `cmd location providers set-test-provider-location gps --location <lat>,<lon>`. The test provider needs `appops set <uid> android:mock_location allow`. | [G-EMU][AOSP-LOCSC] |
| Time zone and clock | `cmd alarm set-timezone Europe/Berlin`; `cmd alarm set-time <epoch ms>` | [AOSP-ALARM] |
| Stream volume | `cmd media_session volume --stream 3 --set 5`; `cmd media_session volume --stream 3 --get` | [AOSP-MEDIASC][AOSP-VOLCTRL] |
| Sensors (emulator) | `adb emu sensor set acceleration 0:9.8:0`; `adb emu sensor status` | [G-EMU] |
| Calls and SMS (emulator) | `adb emu gsm call 5551234`; `adb emu gsm accept 5551234`; `adb emu gsm cancel 5551234`; `adb emu sms send 5551234 hello` | [G-EMU] |
| Cellular data and speed (emulator) | `adb emu gsm data off\|on`; `adb emu network speed <spec>` | [G-EMU] |
| Boot events | `adb reboot` | n/a |
| FGS time limit (35+) | `am compat enable FGS_INTRODUCE_TIME_LIMITS <pkg>`; `device_config put activity_manager data_sync_fgs_timeout_duration <ms>` | [G-FGSTIMEOUT] |
| Job quota (36) | `am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_TOP_STARTED_JOBS <pkg>` and `am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS <pkg>` turn the new enforcement off, for A/B runs | [BC16A] |
| Memory limiter (37) | `am memory-limiter status`; `am memory-limiter ignore <uid>\|none\|all`; `am memory-limiter manual <pid> <MB>\|max\|none` | [BC17A] |
| Health Connect test data | The Health Connect Toolbox app writes records. It needs the write permission for each data type. | [G-HC-CASES] |

**Not verified (do not script these without checking on a device first)**

- **Wi-Fi and Bluetooth toggles.** The `svc` tool says that `svc wifi` and `svc bluetooth` "has been
  migrated to" the Wi-Fi and Bluetooth module shell commands [AOSP-SVC]. Those module sources were not
  reachable, so `cmd wifi set-wifi-enabled enabled|disabled` and `cmd bluetooth_manager enable|disable`
  are **UNVERIFIED**.
- **Airplane mode.** `cmd connectivity airplane-mode enable|disable` is **UNVERIFIED** for the same
  reason.
- **Health permissions.** `pm grant <pkg> android.permission.health.READ_STEPS` on 34+ is
  **UNVERIFIED**. Use the Health Connect permission UI.
- **Ringer mode.** No shell command was verified. Use Quick Settings on the device, or Robolectric.
- **`ACTION_TIMEZONE_OFFSET_CHANGED`.** Set a DST zone and a clock just before a transition with
  `cmd alarm`, then wait. Whether the broadcast fires on emulators is **UNVERIFIED**.
- **Android 17 freshness.** The AOSP shell sources come from the GitHub `aosp-mirror` `main` branch.
  That copy lacks Android 17 additions: `ActivityManagerShellCommand` has no `memory-limiter`. Changes
  that Android 17 made to these commands are therefore **UNVERIFIED**.

### 7.4 What only a device can show

- **Play services features.** Activity Recognition transitions, the Recording API and Health Connect
  on-device steps need Google Play services and real motion. Use a physical phone for end-to-end
  checks. Which emulator system images support the Recording API is **UNVERIFIED**.
- **SDK extensions.** On-device steps need extension 20 on API 34 [G-HC-READ]. Show
  `SdkExtensions.getExtensionVersion(UPSIDE_DOWN_CAKE)` on a debug screen so testers can report it.
- **Collector health over days.** Retention gaps (usage events "kept ... for a few days" [R-USM]),
  Doze and standby effects only show in multi-day runs. Log the standby bucket, `isBackgroundRestricted`
  and the `ApplicationExitInfo` (30) history in each collector run [SDK][BC17A].

### 7.5 API-level test matrix

| API | Android | Why it is in the resolver matrix | Notes |
|---|---|---|---|
| 29 | 10 | minSdk; low-RAM listener rule; runtime `ACTIVITY_RECOGNITION`; "Allow all the time" still in the dialog; Wi-Fi through `getConnectionInfo`; no exact-alarm permission; `requestSingleUpdate` location path | Emulator image with Google Play for Play services features |
| 30 | 11 | One-time grants; background location only in Settings; auto-reset; package visibility; listener detail settings | |
| 31 | 12 | Exact-alarm permission; approximate location; Bluetooth runtime permissions; hibernation; FGS start limits; `WifiInfo` through `getTransportInfo`; `FLAG_INCLUDE_LOCATION_INFO` | |
| 33 | 13 | `POST_NOTIFICATIONS`; granular media; restricted settings; `NEARBY_WIFI_DEVICES`; `getPackageSource` | |
| 34 | 14 | Exact alarms denied by default; partial photo access; FGS types; Health Connect in the framework; on-device steps (extension 20); broadcasts queued while cached | Check the extension level |
| 35 | 15 | OTP redaction; ECM restricted settings; `dataSync` time limit; stopped state; Health Connect background and history permissions in the platform | |
| 36 | 16 | Health permissions replace `BODY_SENSORS`; JobScheduler quota changes; MediaStore version lockdown | |
| 37 | 17 | targetSdk 37 behavior: SMS OTP, CP2 changes, background audio hardening, memory limiter, `ACTION_TIMEZONE_OFFSET_CHANGED`, location button, contact picker | 37.1 (QPR1) is the stable target; 37.2 (QPR2) is beta, for smoke tests only |

---

## 8. Android 12 to 17 behavior changes that affect Agentle

Agentle targets API 37, so every "target" change below applies on any device that runs that Android
version. "All apps" changes apply whatever the target. Only changes that touch collection, permissions,
background delivery or testing are listed, each with what Agentle does about it.

**Minor releases.** Releases such as 36.1, 37.1 and 37.2 "can add new APIs, and have stricter guarantees
around backwards compatibility (e.g. no changes gated by `targetSdkVersion`) compared to major
releases" [R-BUILDV].
- Gate minor-release APIs with `Build.VERSION.SDK_INT_FULL` (36) against `Build.VERSION_CODES_FULL`
  constants: `BAKLAVA_1` is 36.1 and `CINNAMON_BUN_1` is 37.1 [R-BUILDV][SDK].
- `SDK_INT` alone cannot tell 36 from 36.1.

### 8.1 Android 12 to 15 (API 31 to 35)

| Android (API) | Scope | Change | What Agentle does | Source |
|---|---|---|---|---|
| 12 (31) | target | Users can grant approximate location only. "On some releases of Android 12, this change always affects your app, regardless of target SDK version." | `location_foreground` requests COARSE. When precise was wanted, the state is PARTIALLY_ALLOWED. | [BC12T][G-LOC] |
| 12 (31) | target | Exact alarms need the "Alarms & reminders" special access (`SCHEDULE_EXACT_ALARM`). | `exact_alarm_jitai_scheduling` checks `canScheduleExactAlarms()` and falls back to inexact windows. | [BC12T][G-ALARMS] |
| 12 (31) | target | Apps "can't start foreground services while running in the background, except for a few special cases". | v1 collectors use no FGS, only WorkManager. | [BC12T][G-FGSBG] |
| 12 (31) | target | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` replace the legacy permissions. | Declare `BLUETOOTH` with `maxSdkVersion="30"`. Request `BLUETOOTH_CONNECT` for connected devices. | [BC12T][G-BTPERM] |
| 12 (31) | target | Motion and position sensors are rate-limited (200 Hz unless `HIGH_SAMPLING_RATE_SENSORS` is declared). | The debug-only sensor rows sample far below 200 Hz. | [BC12T][G-SENS] |
| 12 (31) | target | App hibernation: after a few months without use, the system resets permissions and hibernates the app. | Hibernation blocker in `background_execution_exemption` (5.3 D). | [BC12T][G-HIB] |
| 12 (31) | target | Every `PendingIntent` must state its mutability. | See the Android 14 row on mutable implicit intents. | [BC12T] |
| 12 (31) | all | The restricted App Standby Bucket is active by default. | Log `getAppStandbyBucket()` in collector health (3.3). | [BC12A] |
| 12 (31) | all | Foreground location can continue under Battery Saver with the screen off; check `getLocationPowerSaveMode()`. | Store the location power-save mode with each fix. | [BC12A] |
| 13 (33) | all | Runtime notification permission `POST_NOTIFICATIONS`. | `post_notifications_jitai`. | [BC13A][G-NOTIFPERM] |
| 13 (33) | all | Task Manager: users can stop apps that run a foreground service. | Matters only if an FGS ships later. | [BC13A] |
| 13 (33) | all | In the user-set "restricted" background state the app "Can't launch foreground services", existing FGS lose foreground status, "Alarms aren't triggered" and "Jobs aren't executed". | `ActivityManager.isBackgroundRestricted()` (28) gives REQUIRES_SETTINGS with blocker `BACKGROUND_RESTRICTED_BY_USER`. Test with `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore`. | [BC13A][SDK] |
| 13 (33) | target | In that restricted state, `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED` are not delivered "until the app is started for other reasons". | Re-arm work on every process start, not only at boot. | [BC13T] |
| 13 (33) | target | Granular media permissions, `NEARBY_WIFI_DEVICES`, and the hard-restricted `BODY_SENSORS_BACKGROUND`. | Matrix rows 39, 40, 17 and 46. | [BC13T] |
| 13 (33) | target | `BluetoothAdapter.enable()` and `disable()` are deprecated and always return `false`. | Agentle never toggles Bluetooth. It opens Settings or `ACTION_REQUEST_ENABLE` (section 6). | [BC13T] |
| 13 (33) | all | Restricted settings for sideloaded apps, "Android 13 and up". | ECM heuristic (5.3 E). | [H-RESTRICTED] |
| 14 (34) | all | `SCHEDULE_EXACT_ALARM` is "denied by default" for most new installs targeting 33+. | Exact alarms start as REQUIRES_SETTINGS. | [BC14A] |
| 14 (34) | all | Context-registered broadcasts are queued while the app is cached, and "Multiple instances of certain broadcasts might be merged". Manifest broadcasts are not queued. | Runtime receivers are only low-latency hints. History comes from usage events and polling. | [BC14A] |
| 14 (34) | all | New restricted-bucket trigger: jobs that repeatedly time out in `onStartJob`, `onStopJob` or `onBind`. | Keep workers short, and log the bucket on each run as the page recommends. | [BC14A] |
| 14 (34) | all | Data safety information appears in more places. | Keep the Data safety form in step with section 9. | [BC14A] |
| 14 (34) | all | `OWNER_PACKAGE_NAME` is redacted unless the owner is visible or the caller holds `QUERY_ALL_PACKAGES`. | Media rows store the owner only when present. | [BC14A] |
| 14 (34) | target | Every foreground service needs a type. `health` and `remoteMessaging` are new types. | Any later FGS, such as a `health` FGS for steps, declares its type. | [BC14T][G-FGSTYPES] |
| 14 (34) | target | `BLUETOOTH_CONNECT` is enforced for `BluetoothAdapter.getProfileConnectionState()`. | The permission is checked before the call (3.20). | [BC14T] |
| 14 (34) | target | Runtime receivers must pass `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED`, except receivers that get only system broadcasts. | Use `ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)` (androidx) for anything that is not system-only. The platform overload with flags is 26; the flag constants are 33. | [BC14T][AX-CTXCOMPAT][SDK] |
| 14 (34) | target | "If an app creates a mutable pending intent with an intent that doesn't specify a component or package, the system throws an exception." The official Activity Recognition sample passes `FLAG_MUTABLE` on 31+ with an implicit intent. | Agentle's transition `PendingIntent` stays mutable, as in the sample, but names its receiver class (or at least the package). | [BC14T][SAMPLE-AR] |
| 14 (34) | target | Partial photo and video access (`READ_MEDIA_VISUAL_USER_SELECTED`). | PARTIALLY_ALLOWED for row 39. | [G-PHOTO] |
| 14 (34) | platform | Health Connect "is part of the Android Framework". | `HealthConnectClient` works without the Play app on 34+. | [G-HC-START] |
| 15 (35) | all | When an app enters the stopped state, "the system also cancels all pending intents". `ACTION_BOOT_COMPLETED` is delivered when user action takes the app out of that state. `ApplicationStartInfo.wasForceStopped()` (35) reports it. | Re-register activity transitions, `PendingIntent` network callbacks and alarms in the boot receiver and on each process start. Log `wasForceStopped()`. | [BC15A][SDK] |
| 15 (35) | all | OTP redaction for untrusted notification listeners. | `notification_content` is PARTIALLY_ALLOWED with blocker `OTP_REDACTION`. | [BC15A] |
| 15 (35) | all | Network requests started "outside of a valid process lifecycle receive an exception". | Uploads and AI calls run in WorkManager or from a visible screen. | [BC15A] |
| 15 (35) | all | Private space: a separate profile whose apps have "restricted visibility". | Whether usage events and package queries include private-space apps is **UNVERIFIED**. Label inventories "main profile". | [BC15A] |
| 15 (35) | all | Restricted settings extended for sideloads to notification access, usage access, overlays, device admin, the SMS permissions and the dialer and SMS roles. The ECM source lists these settings; the Android 15 dating comes from a secondary source. | ECM heuristic (5.3 E). | [AOSP-ECM][N-9TO5] |
| 15 (35) | target | `dataSync` and `mediaProcessing` FGS may run 6 hours per 24 hours, then `Service.onTimeout(int, int)` (35) is called. | Not used. Any later FGS implements `onTimeout`. | [BC15T][G-FGSTIMEOUT][SDK] |
| 15 (35) | target | `BOOT_COMPLETED` receivers cannot start `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` or `microphone` FGS. | The boot receiver only enqueues WorkManager work. | [BC15T] |
| 15 (35) | target | DND changes made by apps become an implicit `AutomaticZenRule`. | Agentle only reads DND. | [BC15T] |
| 15 (35) | target | Audio focus only for the top app or an app running an FGS. | Agentle requests no audio focus. | [BC15T] |
| 15 (35) | platform | `READ_HEALTH_DATA_IN_BACKGROUND` and `READ_HEALTH_DATA_HISTORY` (35; 34 ext 13). | Matrix rows 43 and 44. | [R-HP] |

### 8.2 Android 16 (API 36; QPR2 is 36.1)

**All apps**

| Change | What Agentle does | Source |
|---|---|---|
| **JobScheduler quotas.** "Active standby buckets will start being enforced by a generous runtime quota." Jobs that start while the app is visible and continue after it is hidden, and jobs that run alongside a foreground service, "will adhere to the job runtime quota". "This change impacts tasks scheduled using WorkManager, JobScheduler, and DownloadManager." | Collectors are short, cursor-based and resumable: one bounded unit of work per run. Log `WorkInfo.getStopReason()`. The debug screen lists `JobScheduler.getAllPendingJobs()` (21) with `getPendingJobReasonsHistory(jobId)` (36). Whether WorkManager's jobs show up in that list is **UNVERIFIED**. A/B runs use `am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_TOP_STARTED_JOBS <pkg>` and `OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS`. | [BC16A][SDK] |
| New stop reason `STOP_REASON_TIMEOUT_ABANDONED` (36). "If you're using WorkManager ... you aren't impacted." | None. | [BC16A][SDK] |
| `JobInfo.Builder.setImportantWhileForeground` is ignored. | Not used. | [BC16A] |
| Ordered-broadcast priority holds only within one process. | None; Agentle does not rely on cross-process priority. | [BC16A] |
| Bond loss: the system disconnects, keeps the bond and shows a re-pair dialog. | `bluetooth_connected_devices` records the disconnect. No permission change. | [BC16A] |

**Apps targeting 36**

| Change | What Agentle does | Source |
|---|---|---|
| **Health permissions replace `BODY_SENSORS`.** "Any API previously requiring `BODY_SENSORS` or `BODY_SENSORS_BACKGROUND` requires the corresponding `android.permissions.health` permission instead." This covers `Sensor.TYPE_HEART_RATE` and the `health` FGS type. Background access uses `READ_HEALTH_DATA_IN_BACKGROUND`. Mobile apps need the privacy-policy activity: "Failure to provide the rationale for mobile apps will result in the permission being revoked." | `body_sensor_heart_rate` stays DEFER. The rationale activity that Health Connect already requires (3.42, doc 05) also covers this. | [BC16T] |
| `scheduleAtFixedRate` runs at most one missed execution when the app returns (compat flag `STPE_SKIP_MULTIPLE_MISSED_PERIODIC_TASKS`). | No in-process periodic executors for sampling. | [BC16T] |
| New Bluetooth intents `ACTION_KEY_MISSING` and `ACTION_ENCRYPTION_CHANGE` (36). | Not collected in v1. | [BC16T][SDK] |
| `MediaStore#getVersion()` is unique to each app. | Compare versions only with Agentle's own earlier value. | [BC16T] |
| Photos the app owns are pre-selected in the picker. | No effect on the debug-only media row. | [BC16T] |
| Local network permission as an opt-in. | Agentle does not use the LAN. | [BC16T] |

**New APIs at 36 and 36.1 that Agentle uses or must know about**

- `AdvancedProtectionManager` (36). Its `isAdvancedProtectionEnabled()` requires
  `QUERY_ADVANCED_PROTECTION_MODE` (36). The reference page states no protection level for that
  permission (**UNVERIFIED**). Advanced Protection blocks "app installation from unknown sources
  (sideloading)" [R-APM][R-MP][A17-FEAT]. Testers who turn it on cannot install sideloaded builds.
- `DisplayManager` listener with an event mask and `EVENT_TYPE_DISPLAY_STATE` (36);
  `EVENT_TYPE_DISPLAY_BRIGHTNESS` (36.1) [SDK].
- `BatteryManager.EXTRA_CAPACITY_LEVEL` (36) and `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED` (36) [SDK].
- `AppOpsManager.unsafeCheckOpNoThrow` is deprecated in 36. Agentle uses `checkOpNoThrow` (19) [SDK].
- 36.1 additions [SDK][R-MP][R-KGM]:
  - `RECEIVE_SENSITIVE_NOTIFICATIONS`, protection level `signature|preinstalled|knownSigner|role`. Not
    available, so OTP redaction stays.
  - `POST_PROMOTED_NOTIFICATIONS`, `normal|appops`. Not needed in v1.
  - `NotificationListenerService.Ranking.getSummarization()`. Store it when present.
  - `KeyguardManager.addDeviceLockedStateListener`. It needs `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE`, so it is
    not available.
  - `AudioManager.MODE_ASSISTANT_CONVERSATION`. Map it in the audio-mode enum.

### 8.3 Android 17 (API 37; QPR1 is 37.1; the QPR2 beta is 37.2)

**All apps**

| Change | What Agentle does | Source |
|---|---|---|
| **App memory limits** based on device RAM. An affected session exits with `REASON_OTHER` and a description containing "MemoryLimiter:AnonSwap". `am memory-limiter` adjusts the limits for testing. The summary also lists stricter "App memory runtime limits". | Collector health records `ActivityManager.getHistoricalProcessExitReasons` (30). Workers stream Room writes instead of loading whole histories. | [BC17A][A17-SUM][SDK] |
| **SMS OTP protection** now covers WebOTP-format messages: for apps that are not the intended recipient they become readable three hours after receipt. "During this three hour delay, the `SMS_RECEIVED_ACTION` broadcast is withheld and SMS provider database queries are filtered." This applies "regardless of their target API level". | `sms_metadata` stays DOCUMENT_UNAVAILABLE. A debug SMS reader would see OTP messages three hours late. | [BC17A] |
| **Restricted message access:** "Most apps now cannot access end-to-end encrypted messages." | The detail page was not read. Its effect on notification listeners is **UNVERIFIED**. Recheck `notification_content` on Android 17 devices. | [A17-SUM] |
| **Self-broadcasts:** "Broadcasts that an app sends to receivers in its own process no longer raise the process's priority or keep a cached process from being frozen." | Never use self-broadcasts as a keep-alive. Scheduling stays in WorkManager. | [A17-SUM] |
| **Background audio hardening:** audio playback, audio focus and volume-change APIs "fail silently" outside a valid lifecycle. | Agentle only reads volume, and the page does not list getters. | [BC17A][A17-AUDIO] |
| **Autonomous Bluetooth re-pairing.** `EXTRA_PAIRING_CONTEXT` (37) marks system-initiated re-pairing. `ACTION_KEY_MISSING` "is now broadcast only if the autonomous re-pairing attempt fails". | None. | [BC17A][SDK] |
| Per-app keystore limit: 50,000 keys for non-system apps targeting 37. | None; Agentle uses few keys. | [BC17A] |
| Cross-profile loopback traffic is blocked. | None. | [BC17A] |
| Announced plans: `usesCleartextTraffic` will be deprecated, and implicit URI grants for `ACTION_SEND`, `ACTION_SEND_MULTIPLE` and `ACTION_IMAGE_CAPTURE` end "Starting in Android 18". | Data exports grant URI permissions explicitly. | [BC17A] |

**Apps targeting 37**

| Change | What Agentle does | Source |
|---|---|---|
| OTP protection extends to standard SMS: "For most apps targeting Android 17 (API level 37) or higher, these SMS messages do not become available until three hours after receipt." | Same as the all-apps row. | [BC17T] |
| **CP2 data view.** `ACCOUNT_NAME`, `ACCOUNT_TYPE` and `ACCOUNT_TYPE_AND_DATA_SET` are removed from `ContactsContract.Data`; join `ContactsContract.RawContacts` on `RAW_CONTACT_ID` instead. Without `READ_CONTACTS`, `StrictColumns` and `StrictGrammar` apply to `Data` queries. | `contacts_metadata` (DEFER) is designed around both rules. | [BC17T] |
| `ACCESS_LOCAL_NETWORK` (dangerous, in the `NEARBY_DEVICES` group) is required for LAN access. | Agentle does not use the LAN. The Permission Center notes the shared group (section 4). | [BC17T][G-LAN][R-MP] |
| Stricter background audio: the app also needs an FGS with while-in-use capabilities, or the exact-alarm permission with `USAGE_ALARM` streams. | None; Agentle plays no audio. | [BC17T] |
| Memory limits for bitmaps and icons in `RemoteViews` (widgets), and "stricter memory usage checks for notifications using custom views". | JITAI notifications use standard templates, never custom `RemoteViews`. | [BC17T][A17-SUM] |
| ECH is used when the networking library supports it, and certificate transparency is on by default. | No permission impact. The networking setup (OkHttp to Fitbit and OpenAI) must not break with CT on. | [BC17T] |
| `static final` fields cannot be changed through reflection. A new lock-free `MessageQueue` "may break clients that reflect on `MessageQueue` private fields". | Agentle uses no reflection. | [BC17T] |
| RFCOMM `read()` returns -1 when the socket closes. | Not used. | [BC17T] |

**New APIs in 37.0 that matter to Agentle** [SDK]:
- `USE_LOCATION_BUTTON` (normal). The Jetpack location button is "an experimental Jetpack library and
  is subject to change". The `onlyForLocationButton` flag on `ACCESS_FINE_LOCATION` limits precise
  access to the button [R-MP][G-LOCBTN][A17-RN].
- `ContactsPickerSessionContract.ACTION_PICK_CONTACTS` [G-PICKER].
- `Intent.ACTION_TIMEZONE_OFFSET_CHANGED` [R-INT][A17-RN].
- `AudioManager.STREAM_ASSISTANT`, the dedicated Assistant volume. `audio_volume_ringer` reads it on 37+
  [A17-FEAT].
- `AlarmManager.setExactAndAllowWhileIdle(type, time, tag, executor, OnAlarmListener)` [A17-FEAT].
  "Listener based alarms may be canceled by the Android system whenever the calling process no longer
  has any components running" [R-ALM]. JITAIs therefore keep the `PendingIntent` variant.
- `JobScheduler.getPendingJobReasonStats(jobId)` for the debug screen [A17-FEAT].
- `ProfilingTrigger.TRIGGER_TYPE_ANOMALY`. It fires before the system enforces memory limits, which
  makes it an optional debug aid [A17-FEAT].
- Health Connect Device Data Providers, which separate app-written data from system-verified hardware
  data [A17-RN].
- Health permissions: `READ_SYMPTOM_*` and `READ_ALCOHOL_CONSUMPTION` (ext 21),
  `READ_MENSTRUAL_CYCLE_PHASE` (ext 22).
- `AudioDeviceInfo.TYPE_BLE_HEARING_AID`.

**QPR1 (37.1, stable).**
- The release notes cover Betas 1 to 9, from 2026-04-22 to 2026-08-17. "Android 17 QPR1 reached
  Platform Stability as of Beta 6" [A17-QPR1].
- The per-beta notes list fixed issues only. No 37.1 behavior change that affects Agentle was found.
  As a minor release, 37.1 has no targetSdk-gated changes [R-BUILDV].
- Agentle compiles against `android-37.1` [SDK].

**QPR2 (37.2, beta).**
- `UsageStatsManager.queryAppUsageDuration(AppUsageDurationQuery)` is "Added in version 37.2".
  - It returns data for "the past 30 days" only.
  - It requires `QUERY_APP_USAGE` (`internal|role`), so Agentle cannot use it [R-USM][R-MP].
  - It is missing from the local `android-37.2-beta1` `api-versions.xml`, but that platform's
    `framework.aidl` declares the `AppUsageDuration` and `AppUsageDurationQuery` parcelables [SDK].
- The QPR2 page (last updated 2026-09-29) announces Beta 6.1 and lists no behavior changes [A17-QPR2].

### 8.4 Integrator checklist from sections 8.1 to 8.3

1. Re-register every `PendingIntent`-based registration (activity transitions, `PendingIntent` network
   callbacks, alarms) on `BOOT_COMPLETED`, on each process start, and after `wasForceStopped()`
   [BC15A][BC13T].
2. Every mutable `PendingIntent` names its component or package [BC14T][SAMPLE-AR].
3. Runtime receivers pass `RECEIVER_NOT_EXPORTED` unless they get only system broadcasts [BC14T].
4. Workers stay short and resumable. Log stop reasons, the standby bucket and process exit reasons
   [BC16A][BC14A][BC17A].
5. JITAI notifications use standard templates [BC17T][A17-SUM].
6. Never rely on self-broadcasts or in-process timers for scheduling [A17-SUM][BC16T].
7. Gate 36.1 and 37.1 APIs with `SDK_INT_FULL` [R-BUILDV].

---

## 9. Google Play policy

**How the policy text was obtained.**
- The Play Console Help pages on `support.google.com` cannot be reached from this environment's shell.
- [P-PERM], [P-PREVIEW], [P-SMSCL] and [H-RESTRICTED] were read earlier in this session through a fetch
  tool that returns an extraction, not the raw page. That was before the user asked for no more such
  fetches.
- Quotes below are as close to verbatim as those extractions were. Re-check the exact wording in Play
  Console Help before the Play submission.
- Not read at all (**UNVERIFIED**):
  - the User Data policy;
  - the Data safety form help (`answer/10787469`);
  - the Spyware policy page (`answer/14745000`);
  - the Play Console declaration forms.

### 9.1 Rules by area

| Area | Policy text (extracted) | Capabilities affected | Agentle decision | Source |
|---|---|---|---|---|
| SMS and call log | Only an app "actively registered as the default SMS, Phone, or Assistant handler". "Apps lacking default SMS, Phone, or Assistant handler capability may not declare use of the above permissions in the manifest." Apps "May not use alternative methods ... to derive data attributed to Call Log or SMS". The SMS and call log policy lists 17 permitted exceptions. "Research (including market research based on SMS)" and "Social graph and personality profiling" are invalid uses. | `sms_metadata`, `call_log_metadata` | DOCUMENT_UNAVAILABLE. The Play manifest never declares `READ_SMS`, `RECEIVE_SMS` or `READ_CALL_LOG`. Also see the notification row. | [P-PERM][P-PREVIEW][P-SMSCL] |
| Notification content from SMS and dialer apps | The "alternative methods ... to derive" clause above. | `notification_content` | How Play applies that clause to a notification listener that reads SMS-app notifications is **UNVERIFIED**. By default, exclude the default SMS app (`Telephony.Sms.getDefaultSmsPackage`, 19) and the default dialer (`TelecomManager.getDefaultDialerPackage`, 23) from content capture. | [P-PERM][SDK] |
| Location | Request "the minimum scope necessary (for example, coarse instead of fine, and foreground instead of background)". "Never request location permissions from users for the sole purpose of advertising or analytics." "Background location may only be used to provide features beneficial to the user and relevant to the core functionality". A location FGS must be "user-initiated" and "terminated immediately after the intended use case ... is completed". Background location needs the declaration form. | `location_foreground`, `location_background`, `wifi_network_identity` | Foreground COARSE ships. Background location and Wi-Fi identity are DEFER until product commits to a core place-based feature. | [P-PERM][P-PREVIEW] |
| Location button (from 2027-01-27) | "If your use case requires precise location only for one-time, user-initiated actions; you must implement the use of the Android location button, utilizing the `onlyForLocationButton` permission flag." | `location_foreground` | Use the button only for a future "tag this place" action. Routine sampling stays COARSE. | [P-PREVIEW][G-LOCBTN] |
| Package visibility | `QUERY_ALL_PACKAGES` is "restricted to specific use cases where awareness of and/or interoperability with any and all apps on the device are required". "Use of alternative methods to approximate ... broad visibility ... are also restricted to user-facing core app functionality." "App inventory data ... may never be sold nor shared for analytics or ads monetization purposes." | `installed_apps_inventory`, `app_usage_events` | No `QUERY_ALL_PACKAGES`. Use a launcher-intent `<queries>` entry. Inventory is used only in user-facing dashboards and never shared. | [P-PERM] |
| Accessibility | Not for "An app that autonomously initiates, plans, and executes actions or decisions". Non-accessibility tools need "clear in-app disclosure ... and obtain affirmative user consent", and the use "must be documented in the Google Play listing". | `accessibility_event_stream` | DOCUMENT_UNAVAILABLE. | [P-PERM] |
| Photos and videos | Apps targeting 13+ "May only request the `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` permissions if system pickers ... are not sufficient", plus a Play Console declaration. | `media_images_video_metadata` | IMPLEMENT_DEBUG_ONLY. Not in the Play manifest. | [P-PERM] |
| Contacts (from 2027-01-27) | Apps targeting 17+ "may only request the `READ_CONTACTS` permission if the Android Contact Picker is not sufficient for core functionality", plus a declaration. "Apps may not disclose or publish non-public Contacts data unless authorized by the individual data subjects." | `contacts_metadata`, `contacts_picker_selection` | Both DEFER. If a contacts feature comes, start with the picker. | [P-PREVIEW] |
| Health Connect | "Only applications with one or more features designed to benefit users' health and fitness are permitted." Approved uses include "fitness and wellness ... fitness coaching ... health research". "Do not access data ... using headless apps." "Data use should be limited to providing or improving the appropriate use case or features visible in the application's user interface." Other transfers, ads use and sale are prohibited. A privacy policy is required. The guide adds: "In the Play Console, declare access to the Health Connect data types". | `health_connect_*` | DECLARATION_REQUIRED. Health data sent to the AI connector must serve a user-visible insight feature, with consent, and appear in Data safety. Whether the full policy allows a third-party AI processor in that role is **UNVERIFIED**; legal must review it (doc 05 and doc 06). | [P-PERM][P-PREVIEW][G-HC-START][G-HC-TYPES] |
| Body sensors | "All requests for body sensor permissions (both legacy and new granular permissions) will be reviewed." | `body_sensor_heart_rate` | DEFER. | [P-PERM] |
| Exact alarms | `USE_EXACT_ALARM` only if "The app is an alarm or timer app" or "a calendar app that shows event notifications". "Those that do not meet the acceptable use case criteria will be disallowed from publishing." Others "should evaluate if using `SCHEDULE_EXACT_ALARM` as an alternative is an option". | `exact_alarm_jitai_scheduling` | Use `SCHEDULE_EXACT_ALARM` (user-granted). Never declare `USE_EXACT_ALARM`. | [P-PERM] |
| Battery optimization | "Google Play policies prohibit apps from requesting direct exemption from Power Management features ... unless the core function of the app is adversely affected." Acceptable cases: messaging that cannot use FCM, safety apps, task automation, peripheral companions that keep a persistent connection. The Play permissions page does not mention it. | `background_execution_exemption` | Do not declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Link the list screen (section 6). | [G-DOZE][P-PERM] |
| Foreground service types | Apps targeting 14+ "declare your app's foreground service types in the Play Console's app content page (Policy > App content)". `specialUse` use cases "are reviewed". | none in v1 | Declare only if an FGS ships, such as a `health` FGS for steps. | [G-FGSTYPES] |
| Usage access, notification access, activity recognition | Not mentioned on either permissions page. | `app_usage_*`, `screen_*`, `unlock_*`, `network_data_usage`, `notification_*`, `media_sessions_now_playing`, `activity_recognition_transitions`, `step_count_recording_api` | SENSITIVE: prominent disclosure, consent, Data safety entries. That no declaration form exists is **UNVERIFIED**. | [P-PERM][P-PREVIEW] |
| Disclosure and incremental requests | "Sensitive data must never be misused, under-disclosed, or accessed unnecessarily." "Request permissions ... to access data in context (via incremental requests), so that users understand why." | all | The Permission Center asks per capability, in context, after a rationale screen (sections 4 and 5). | [P-PERM] |
| Data safety | Android 14 shows the Play Data safety information in more places. | all | Fill in Data safety for every IMPLEMENT row. The form's exact fields are **UNVERIFIED** (page not read). | [BC14A] |
| Not used | `MANAGE_EXTERNAL_STORAGE` (access review), `REQUEST_INSTALL_PACKAGES`, `USE_FULL_SCREEN_INTENT` (auto-granted only for alarm and calling apps), `VpnService`. | none | Never declared. | [P-PERM][P-PREVIEW] |

### 9.2 Declarations Agentle would file (Play flavor)

- **Health apps declaration** with the Health Connect data types read, plus a privacy policy that matches
  the rationale activity [G-HC-START][G-HC-TYPES][P-PERM].
- **Foreground service types**: only if an FGS ships [G-FGSTYPES].
- **Data safety form**: usage, notifications, location, calendar, phone state, health and the AI
  connector transfers (fields **UNVERIFIED**).
- **None** for SMS or call log, `QUERY_ALL_PACKAGES`, accessibility, background location, photos and
  videos, `USE_EXACT_ALARM` or body sensors, because none of them is declared.

### 9.3 Build flavors and sideloaded builds

- **Play release manifest** must not contain:
  - `READ_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`;
  - `QUERY_ALL_PACKAGES`;
  - an `AccessibilityService`;
  - `ACCESS_BACKGROUND_LOCATION`;
  - `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`;
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `USE_EXACT_ALARM`;
  - `BODY_SENSORS`, `BODY_SENSORS_BACKGROUND`.
  - `android.permission.health.READ_HEART_RATE` is the exception: it stays, because Health Connect reads
    use it (3.42). `body_sensor_heart_rate` stays out of the Play build because Agentle never registers
    for `Sensor.TYPE_HEART_RATE` there, not because the permission is missing [BC16T][R-HP].
- **Debug-only permissions** (media images and video, motion sensors) go in a debug-only source-set
  manifest. That AGP merges source-set manifests per variant was not re-checked this session
  (**UNVERIFIED**).
- **Build check.** A unit test and a CI step assert that the merged Play release manifest has none of
  the permissions above (section 7.2, "not in this build").
- **Sideloaded test builds** meet three platform gates:
  - ECM "restricted settings" for usage access, notification access and the SMS and dialer roles, when
    the APK comes from a local or downloaded file [AOSP-ECM][H-RESTRICTED];
  - the installer allowlist for hard-restricted permissions. A shell `pm install` allowlists them unless
    `--restrict-permissions` is passed [AOSP-PMSC][R-MP];
  - Advanced Protection, which blocks sideloading altogether [A17-FEAT].
- **Play testing tracks.** Distributing test builds through a Play testing track avoids the
  local-file install source. How ECM treats such installs on devices is **UNVERIFIED**.

---

## 10. Open questions and the UNVERIFIED register

### 10.1 Decisions for product and the integrator

1. **minSdk 29 or 31.**
   - This doc recommends 29 (1.1).
   - Doc 02 prefers 31 for background execution, and accepts 29 as the floor.
   - Pick 31 if the device fleet is known to be recent. It removes the 29-30 branches listed in 1.1.
2. **`notification_content` in v1.** Opt-in IMPLEMENT, or IMPLEMENT_DEBUG_ONLY?
   - It carries the most privacy and Play-review risk.
   - The SMS "derive" clause applies (9.1).
   - Android 17's restricted message access is still unclear (8.3).
3. **Place-based JITAIs.** If they are core, `location_background` and `wifi_network_identity` move from
   DEFER to IMPLEMENT. That brings the Play declaration, the Settings-only grant on 30+ and a
   hard-restricted permission (3.32).
4. **AI connector scope.**
   - Which collected categories may be sent to the AI connector (doc 06), and under which consent?
   - Health Connect data is the most constrained case (9.1).
5. **Tester distribution: Play testing track or sideloaded APK?** Sideloads meet ECM restricted
   settings, installer allowlisting of hard-restricted permissions, and Advanced Protection (9.3).
6. **Exact JITAI timing.** Is it a v1 requirement? If not, inexact `setWindow` alarms avoid the
   special-access step (3.37).
7. **A `health` FGS for step counting** on devices without Health Connect on-device steps. It is not in
   v1 (3.30), and would need an FGS type declaration (9.1).

### 10.2 UNVERIFIED and undocumented items

| # | Item | Where | Status | How to close it |
|---|---|---|---|---|
| 1 | Usage access and notification access need no Play declaration form | 1, 9.1 | UNVERIFIED | Read the User Data policy and the Play Console declaration list. |
| 2 | How the GMS `ACTIVITY_RECOGNITION` permission is granted on Android 9 | 1.1 | UNVERIFIED | Only matters if minSdk drops below 29. |
| 3 | Android version distribution (device share) | 1.1 | UNVERIFIED | Android Studio's distribution chart. |
| 4 | Usage-event ordering at the query-window edge | 3.1 | undocumented | Keep the 10-minute overlap and idempotent keys. |
| 5 | OEM package-visibility filtering of usage-event package names | 3.1 | UNVERIFIED | Device lab check. |
| 6 | Retention of usage aggregates and NetworkStats buckets | 3.2, 3.5 | undocumented | Persist daily. |
| 7 | Other apps' `STANDBY_BUCKET_CHANGED` events reach Agentle | 3.3 | UNVERIFIED | Device check. |
| 8 | What `KEYGUARD_HIDDEN` means (authenticated unlock or any dismissal) | 3.8 | undocumented | Label it "keyguard dismissed". |
| 9 | Manifest delivery of battery low, okay, power connected and disconnected | 3.10 | UNVERIFIED (doc conflict) | Treat as runtime-only. |
| 10 | Thermal API return values on devices without thermal data | 3.12 | UNVERIFIED | Device lab check. |
| 11 | No public volume-changed broadcast (design statement) | 3.22 | undocumented | Poll. |
| 12 | Manifest delivery of `ACTION_TIMEZONE_OFFSET_CHANGED`, and whether it fires on emulators | 3.24, 7.3 | UNVERIFIED | Device test at a DST transition. |
| 13 | A public 12/24-hour change broadcast | 3.25 | undocumented | Poll. |
| 14 | What evidence the background-location declaration asks for | 3.32 | UNVERIFIED | Read the Play Console form. |
| 15 | `GoogleApiAvailability.isGooglePlayServicesAvailable` name and behavior; injecting transitions on an emulator | 3.28 | UNVERIFIED | Check the Play services reference. |
| 16 | Minimum Android version of the Recording API, and emulator image support | 3.29, 7.4 | UNVERIFIED | Check the Play services fitness reference; test images. |
| 17 | Detecting OTP redaction per notification | 3.34 | undocumented | Show a generic "may be redacted" note. |
| 18 | No Play declaration for `READ_MEDIA_AUDIO` | 3.40 | UNVERIFIED | Only matters if audio is un-deferred. |
| 19 | `EVENT_REMINDER` delivery to non-calendar apps | 3.41 | UNVERIFIED | Device check; not relied on. |
| 20 | Every VoIP app sets `MODE_IN_COMMUNICATION` | 3.47 | UNVERIFIED | Spot-check common apps. |
| 21 | Permission flags (`USER_SET`, `USER_FIXED`) have no public API | 5.3 A | undocumented for apps | Use the DataStore "requested" flag heuristic. |
| 22 | How Play installs allowlist hard-restricted permissions | 5.3 A | UNVERIFIED | Not needed while those permissions stay out of the Play build. |
| 23 | Health Connect's own "don't ask again" behavior | 5.3 D | undocumented | Fall back to Health Connect settings. |
| 24 | `ACTION_USAGE_ACCESS_SETTINGS` with a `package:` URI | 6 | undocumented | Use the list screen. |
| 25 | `FakeHealthConnectFeatures` ships in `connect-testing` 1.0.0-alpha04 | 7.2 | UNVERIFIED | Check the artifact when adding the dependency. |
| 26 | Robolectric 4.17 resolves Settings actions without a registered `ResolveInfo` | 7.2 | UNVERIFIED | Register one in tests either way. |
| 27 | ECM behavior of `adb install --package-source`, and of plain `adb install`, on devices; Android 13-14 mechanism | 7.3 | UNVERIFIED | Device test on 33, 34, 35 and 37. |
| 28 | `cmd wifi`, `cmd bluetooth_manager` and `cmd connectivity airplane-mode` syntax | 7.3 | UNVERIFIED | Module sources were not reachable; check `cmd <service> help` on a device. |
| 29 | `pm grant` for `android.permission.health.*` on 34+ | 7.3 | UNVERIFIED | Device test. |
| 30 | Android 17 changes to shell commands (the AOSP mirror predates 17) | 7.3 | UNVERIFIED | Run `help` on a 37 emulator. |
| 31 | Whether usage events and package queries include private-space apps | 8.1 | UNVERIFIED | Device test on 35+. |
| 32 | Protection level of `QUERY_ADVANCED_PROTECTION_MODE` | 8.2 | UNVERIFIED | Read the full reference entry. |
| 33 | WorkManager jobs appear in `JobScheduler.getAllPendingJobs()` | 8.2 | UNVERIFIED | Debug-screen check. |
| 34 | Effect of Android 17 "restricted message access" on notification listeners | 8.3 | UNVERIFIED | Read the linked page; device test. |
| 35 | Exact wording of the Play pages (read as extractions) | 9 | UNVERIFIED | Re-read in Play Console Help. |
| 36 | User Data policy, Data safety fields and Spyware policy | 9 | UNVERIFIED (not read) | Read before the store listing. |
| 37 | How Play reads SMS-app notification content against the SMS "derive" clause | 9.1 | UNVERIFIED | Exclude those packages by default; ask Play policy support if needed. |
| 38 | A third-party AI processor as a permitted Health Connect data use | 9.1 | UNVERIFIED | Legal review with docs 05 and 06. |
| 39 | Per-variant manifest merging for debug-only permissions | 9.3 | UNVERIFIED this session | Confirm in the build doc. |
| 40 | ECM treatment of installs from Play testing tracks | 9.3 | UNVERIFIED | Device test. |
| 41 | `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` and history on Health Connect APK devices (28-33) | 3.43, 3.44 | UNVERIFIED | Gate only on `getFeatureStatus`, never on SDK level. |
| 42 | AOSP mirror freshness for all `AOSP-*` sources | 11 | known gap | The mirror's `main` predates Android 17. Recheck any AOSP-based claim against a 37 device. |
| 43 | API level of `HealthConnectManager.getCurrentDeviceDataSource` (guide: 34 ext 11; SDK database: 34 ext 22 or 37.0) | 3.45 | UNVERIFIED (doc conflict) | Gate on extension 22. Check on a 34-36 device with a current Health Connect module. |

---

## 11. Sources

Cached copies live in two session scratch directories. They are working copies, not part of the
repo, and may not survive the session. The URL is the authority.
- **C1** = `/tmp/claude-0/-home-claude/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad`
- **C2** = `/tmp/claude-0/-home-claude-agentle-android/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad`

Pages were converted from HTML to text with a small parser (`C1/agent1/h2t.py`, `C2/w/h2t.py`). Unless
a row says otherwise, everything was fetched with `curl` from developer.android.com,
raw.githubusercontent.com or repo.maven.apache.org on 2026-10-01 or 2026-10-02.

### Android release pages and behavior changes (developer.android.com)

| Key | What | URL | Cached copy |
|---|---|---|---|
| [A10-PRIV] | Android 10 privacy changes | https://developer.android.com/about/versions/10/privacy/changes | `C1/agent1/pages/g_a10_privacy.txt` |
| [BC12T] | Behavior changes, apps targeting Android 12 | https://developer.android.com/about/versions/12/behavior-changes-12 | `C1/agent1/pages/g_a12_target.txt` |
| [BC12A] | Behavior changes, all apps, Android 12 | https://developer.android.com/about/versions/12/behavior-changes-all | `C1/agent1/pages/g_a12_all.txt` |
| [BC13T] | Behavior changes, apps targeting Android 13 | https://developer.android.com/about/versions/13/behavior-changes-13 | `C1/agent1/pages/g_a13_target.txt` |
| [BC13A] | Behavior changes, all apps, Android 13 | https://developer.android.com/about/versions/13/behavior-changes-all | `C1/agent1/pages/g_a13_all.txt` |
| [BC14T] | Behavior changes, apps targeting Android 14 | https://developer.android.com/about/versions/14/behavior-changes-14 | `C1/agent1/pages/g_a14_target.txt` |
| [BC14A] | Behavior changes, all apps, Android 14 | https://developer.android.com/about/versions/14/behavior-changes-all | `C1/agent1/pages/g_a14_all.txt` |
| [BC15T] | Behavior changes, apps targeting Android 15 | https://developer.android.com/about/versions/15/behavior-changes-15 | `C1/agent1/pages/g_a15_target.txt` |
| [BC15A] | Behavior changes, all apps, Android 15 | https://developer.android.com/about/versions/15/behavior-changes-all | `C1/agent1/pages/g_a15_all.txt` |
| [BC16T] | Behavior changes, apps targeting Android 16 (last updated 2026-09-16) | https://developer.android.com/about/versions/16/behavior-changes-16 | `C1/agent1/pages/a16_target.txt` |
| [BC16A] | Behavior changes, all apps, Android 16 (last updated 2026-09-16) | https://developer.android.com/about/versions/16/behavior-changes-all | `C1/agent1/pages/a16_all.txt` |
| [BC17T] | Behavior changes, apps targeting Android 17 (last updated 2026-09-16) | https://developer.android.com/about/versions/17/behavior-changes-17 | `C1/agent1/pages/a17_target.txt` |
| [BC17A] | Behavior changes, all apps, Android 17 (last updated 2026-10-01) | https://developer.android.com/about/versions/17/behavior-changes-all | `C1/agent1/pages/a17_all.txt` |
| [A17-SUM] | Android 17 features and changes list (last updated 2026-10-01) | https://developer.android.com/about/versions/17/summary | `C1/agent1/pages/a17_summary.txt` |
| [A17-FEAT] | Android 17 features and APIs | https://developer.android.com/about/versions/17/features | `C1/agent1/pages/a17_features.txt` |
| [A17-RN] | Android 17 release notes (betas) | https://developer.android.com/about/versions/17/release-notes | `C1/agent1/pages/a17_release-notes.txt` |
| [A17-AUDIO] | Android 17 background audio hardening | https://developer.android.com/about/versions/17/changes/bg-audio | `C1/agent1/pages/a17_changes_bg-audio.txt` |
| [A17-QPR1] | Android 17 QPR1 release notes (last updated 2026-10-01) | https://developer.android.com/about/versions/17/qpr1/release-notes | `C1/agent1/pages/a17_qpr1_release-notes.txt` |
| [A17-QPR2] | Android 17 QPR2 beta page (last updated 2026-09-29) | https://developer.android.com/about/versions/17/qpr2 | `C1/agent1/pages/a17_qpr2.txt` |
| [G-A8BG] | Android 8 background execution limits | https://developer.android.com/about/versions/oreo/background | `C2/w/pages/a8_background.txt` |
| [G-PHOTO] | Android 14 partial photo and video access | https://developer.android.com/about/versions/14/changes/partial-photo-video-access | `C1/agent1/pages/g_partial_photo.txt` |
| [G-PICKER] | Android 17 contacts picker | https://developer.android.com/about/versions/17/features/contact-picker | `C1/agent1/pages/g_contact_picker.txt` |

### Developer guides (developer.android.com)

| Key | What | URL | Cached copy |
|---|---|---|---|
| [G-ALARMS] | Schedule alarms (exact-alarm permission) | https://developer.android.com/develop/background-work/services/alarms/schedule | `C1/agent1/pages/g_exact_alarms.txt` |
| [G-ANDROIDX] | AndroidX versions (default minSdk) | https://developer.android.com/jetpack/androidx/versions | `C1/agent1/pages/g_minsdk_androidx.txt` |
| [G-AR] | Activity Recognition Transition API | https://developer.android.com/develop/sensors-and-location/location/transitions | `C1/agent1/pages/g_activity_recognition.txt` |
| [G-BATMON] | Monitor the battery level and charging state | https://developer.android.com/training/monitoring-device-state/battery-monitoring | `C1/agent1/pages/g_battery_monitor.txt` |
| [G-BCAST] | Broadcasts overview | https://developer.android.com/develop/background-work/background-tasks/broadcasts | `C1/agent1/pages/g_bcast_overview.txt` |
| [G-BCASTEX] | Implicit broadcast exceptions (last updated 2026-02-26) | https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions | `C1/agent1/pages/g_bcast_exceptions.txt` |
| [G-BTPERM] | Bluetooth permissions | https://developer.android.com/develop/connectivity/bluetooth/bt-permissions | `C1/agent1/pages/g_bt_perms.txt` |
| [G-CALP] | Calendar provider | https://developer.android.com/identity/providers/calendar-provider | `C1/agent1/pages/g_calendar_provider.txt` |
| [G-CONTP] | Contacts provider | https://developer.android.com/identity/providers/contacts-provider | `C1/agent1/pages/g_contacts_provider.txt` |
| [G-DEFHANDLER] | Permissions used only in default handlers | https://developer.android.com/guide/topics/permissions/default-handlers | `C2/w/pages/default_handlers.txt` |
| [G-DOZE] | Optimize for Doze and App Standby (Play exemption policy) | https://developer.android.com/training/monitoring-device-state/doze-standby | `C1/agent1/pages/g_battery_opt.txt` |
| [G-EMU] | Send emulator console commands | https://developer.android.com/studio/run/emulator-console | `C2/w/pages/emu_console.txt` |
| [G-FGSBG] | Restrictions on starting a foreground service from the background | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start | `C1/agent1/pages/g_fgs_bgstart.txt` |
| [G-FGSTIMEOUT] | Foreground service timeouts | https://developer.android.com/develop/background-work/services/fgs/timeout | `C1/agent1/pages/g_fgs_timeout.txt` |
| [G-FGSTYPES] | Foreground service types (Play Console declaration) | https://developer.android.com/develop/background-work/services/fgs/service-types | `C1/agent1/pages/g_fgs_types.txt` |
| [G-HC-CASES] | Health Connect: test top use cases (Toolbox) | https://developer.android.com/health-and-fitness/guides/health-connect/test/test-cases | `C2/w/pages/hc_test_cases.txt` |
| [G-HC-READ] | Health Connect: read data (background, history, on-device steps) | https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data | `C1/agent1/pages/g_hc_read.txt` |
| [G-HC-START] | Health Connect: get started | https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started | `C1/agent1/pages/g_hc_getstarted.txt` |
| [G-HC-TYPES] | Health Connect: data types and health apps declaration | https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types | `C1/agent1/pages/g_hc_datatypes.txt` |
| [G-HC-UNIT] | Health Connect: unit tests with the testing library | https://developer.android.com/health-and-fitness/guides/health-connect/test/unit-tests | `C2/w/pages/hc_unit_tests.txt` |
| [G-HIB] | App hibernation | https://developer.android.com/topic/performance/app-hibernation | `C1/agent1/pages/g_hibernation.txt` |
| [G-LAN] | Local network permission | https://developer.android.com/privacy-and-security/local-network-permission | `C1/agent1/pages/g_local_network.txt` |
| [G-LOC] | Location permissions | https://developer.android.com/develop/sensors-and-location/location/permissions | `C1/agent1/pages/g_loc_perms.txt` |
| [G-LOCBG] | Request background location | https://developer.android.com/develop/sensors-and-location/location/permissions/background | `C1/agent1/pages/g_loc_perm_bg.txt` |
| [G-LOCBTN] | Location button (session-based precise location) | https://developer.android.com/guide/topics/permissions/private-alternatives/location-button | `C1/agent1/pages/g_location_button.txt` |
| [G-MEDIA] | Access media files from shared storage | https://developer.android.com/training/data-storage/shared/media | `C1/agent1/pages/g_storage_media.txt` |
| [G-NOTIFPERM] | Notification runtime permission | https://developer.android.com/develop/ui/views/notifications/notification-permission | `C1/agent1/pages/g_notif_perm.txt` |
| [G-PKGVIS] | Package visibility filtering | https://developer.android.com/training/package-visibility | `C1/agent1/pages/g_pkg_visibility.txt` |
| [G-REC] | Recording API on mobile | https://developer.android.com/health-and-fitness/recording-api | `C2/w/pages/recording_api.txt` |
| [G-RUNTIME] | Request runtime permissions | https://developer.android.com/training/permissions/requesting | `C1/agent1/pages/g_runtime_perms.txt` |
| [G-SENS] | Sensors overview (background limits, rate limiting) | https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview | `C1/agent1/pages/g_sensors_overview.txt` |
| [G-SENSENV] | Environment sensors | https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment | `C1/agent1/pages/g_sensors_env.txt` |
| [G-SENSMOT] | Motion sensors | https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion | `C1/agent1/pages/g_sensors_motion.txt` |
| [G-SENSPOS] | Position sensors | https://developer.android.com/develop/sensors-and-location/sensors/sensors_position | `C1/agent1/pages/g_sensors_position.txt` |
| [G-SPECIAL] | Request special permissions | https://developer.android.com/training/permissions/requesting-special | `C1/agent1/pages/g_special_perms.txt` |
| [G-THERM] | Thermal API (ADPF) | https://developer.android.com/games/optimize/adpf/thermal | `C1/agent1/pages/g_thermal.txt` |
| [G-WIFIPERM] | Wi-Fi permissions | https://developer.android.com/develop/connectivity/wifi/wifi-permissions | `C1/agent1/pages/g_wifi_perms.txt` |

### API reference (developer.android.com) and the local SDK

| Key | What | URL | Cached copy |
|---|---|---|---|
| [R-ACTCOMPAT] | androidx ActivityCompat | https://developer.android.com/reference/androidx/core/app/ActivityCompat | `C1/agent1/pages/ref_ActivityCompat.txt` |
| [R-ADI] | AudioDeviceInfo | https://developer.android.com/reference/android/media/AudioDeviceInfo | `C1/agent1/pages/ref_AudioDeviceInfo.txt` |
| [R-ALM] | AlarmManager | https://developer.android.com/reference/android/app/AlarmManager | `C1/agent1/pages/ref_AlarmManager.txt` |
| [R-AOM] | AppOpsManager | https://developer.android.com/reference/android/app/AppOpsManager | `C1/agent1/pages/ref_AppOpsManager.txt` |
| [R-APM] | AdvancedProtectionManager | https://developer.android.com/reference/android/security/advancedprotection/AdvancedProtectionManager | `C1/agent1/pages/ref_AdvancedProtectionManager.txt` |
| [R-AUD] | AudioManager | https://developer.android.com/reference/android/media/AudioManager | `C1/agent1/pages/ref_AudioManager.txt` |
| [R-BAT] | BatteryManager | https://developer.android.com/reference/android/os/BatteryManager | `C1/agent1/pages/ref_BatteryManager.txt` |
| [R-BTA] | BluetoothAdapter | https://developer.android.com/reference/android/bluetooth/BluetoothAdapter | `C1/agent1/pages/ref_BluetoothAdapter.txt` |
| [R-BTD] | BluetoothDevice | https://developer.android.com/reference/android/bluetooth/BluetoothDevice | `C1/agent1/pages/ref_BluetoothDevice.txt` |
| [R-BUILDV] | Build.VERSION (SDK_INT_FULL, minor releases) | https://developer.android.com/reference/android/os/Build.VERSION | `C2/a1/pages/ref_Build_VERSION.txt` |
| [R-CAL] | CalendarContract | https://developer.android.com/reference/android/provider/CalendarContract | `C1/agent1/pages/ref_CalendarContract.txt` |
| [R-CM] | ConnectivityManager | https://developer.android.com/reference/android/net/ConnectivityManager | `C1/agent1/pages/ref_ConnectivityManager.txt` |
| [R-DF] | DateFormat | https://developer.android.com/reference/android/text/format/DateFormat | `C1/agent1/pages/ref_DateFormat.txt` |
| [R-DISP] | Display | https://developer.android.com/reference/android/view/Display | `C1/agent1/pages/ref_Display.txt` |
| [R-DM] | DisplayManager | https://developer.android.com/reference/android/hardware/display/DisplayManager | `C1/agent1/pages/ref_DisplayManager.txt` |
| [R-HCC] | Jetpack HealthConnectClient | https://developer.android.com/reference/kotlin/androidx/health/connect/client/HealthConnectClient | `C2/w/pages/ref_HealthConnectClient.txt` |
| [R-HP] | HealthPermissions | https://developer.android.com/reference/android/health/connect/HealthPermissions | `C1/agent1/pages/g_HealthPermissions.txt` |
| [R-INT] | Intent | https://developer.android.com/reference/android/content/Intent | `C1/agent1/pages/ref_Intent.txt` |
| [R-KGM] | KeyguardManager | https://developer.android.com/reference/android/app/KeyguardManager | `C1/agent1/pages/ref_KeyguardManager.txt` |
| [R-LM] | LocationManager | https://developer.android.com/reference/android/location/LocationManager | `C1/agent1/pages/ref_LocationManager.txt` |
| [R-LOCM] | LocaleManager | https://developer.android.com/reference/android/app/LocaleManager | `C1/agent1/pages/ref_LocaleManager.txt` |
| [R-MP] | Manifest.permission (protection levels) | https://developer.android.com/reference/android/Manifest.permission | `C1/agent1/pages/ref_Manifest_permission.txt` |
| [R-MSM] | MediaSessionManager | https://developer.android.com/reference/android/media/session/MediaSessionManager | `C2/w/pages/ref_MediaSessionManager.txt` |
| [R-NCAP] | NetworkCapabilities | https://developer.android.com/reference/android/net/NetworkCapabilities | `C1/agent1/pages/ref_NetworkCapabilities.txt` |
| [R-NCB] | ConnectivityManager.NetworkCallback | https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback | `C1/agent1/pages/ref_NetworkCallback.txt` |
| [R-NLS] | NotificationListenerService | https://developer.android.com/reference/android/service/notification/NotificationListenerService | `C1/agent1/pages/ref_NotificationListenerService.txt` |
| [R-NM] | NotificationManager | https://developer.android.com/reference/android/app/NotificationManager | `C1/agent1/pages/ref_NotificationManager.txt` |
| [R-NOTIF] | Notification | https://developer.android.com/reference/android/app/Notification | `C1/agent1/pages/ref_Notification.txt` |
| [R-NS] | NetworkStats | https://developer.android.com/reference/android/app/usage/NetworkStats | `C1/agent1/pages/g_networkstats.txt` |
| [R-NSM] | NetworkStatsManager | https://developer.android.com/reference/android/app/usage/NetworkStatsManager | `C1/agent1/pages/ref_NetworkStatsManager.txt` |
| [R-PKM] | PackageManager | https://developer.android.com/reference/android/content/pm/PackageManager | `C1/agent1/pages/ref_PackageManager.txt` |
| [R-PWR] | PowerManager | https://developer.android.com/reference/android/os/PowerManager | `C1/agent1/pages/ref_PowerManager.txt` |
| [R-SBN] | StatusBarNotification | https://developer.android.com/reference/android/service/notification/StatusBarNotification | `C1/agent1/pages/ref_StatusBarNotification.txt` |
| [R-SET] | Settings | https://developer.android.com/reference/android/provider/Settings | `C1/agent1/pages/ref_Settings.txt` |
| [R-SSM] | StorageStatsManager | https://developer.android.com/reference/android/app/usage/StorageStatsManager | `C1/agent1/pages/ref_StorageStatsManager.txt` |
| [R-STATFS] | StatFs | https://developer.android.com/reference/android/os/StatFs | `C1/agent1/pages/ref_StatFs.txt` |
| [R-TCB] | TelephonyCallback | https://developer.android.com/reference/android/telephony/TelephonyCallback | `C1/agent1/pages/ref_TelephonyCallback.txt` |
| [R-TM] | TelephonyManager | https://developer.android.com/reference/android/telephony/TelephonyManager | `C1/agent1/pages/ref_TelephonyManager.txt` |
| [R-UE] | UsageEvents.Event | https://developer.android.com/reference/android/app/usage/UsageEvents.Event | `C1/agent1/pages/ref_UsageEvents_Event.txt` |
| [R-USM] | UsageStatsManager | https://developer.android.com/reference/android/app/usage/UsageStatsManager | `C1/agent1/pages/ref_UsageStatsManager.txt` |
| [R-WI] | WifiInfo | https://developer.android.com/reference/android/net/wifi/WifiInfo | `C1/agent1/pages/ref_WifiInfo.txt` |
| [R-WM] | WifiManager | https://developer.android.com/reference/android/net/wifi/WifiManager | `C1/agent1/pages/ref_WifiManager.txt` |
| [SDK] | Android SDK API database (API levels, deprecations, SDK extensions); 37.2 items from the QPR2 beta platform | local: /opt/android-sdk/platforms/android-37.1/data/api-versions.xml; /opt/android-sdk/platforms/android-37.2-beta1/data/api-versions.xml and framework.aidl | local SDK; query helper C2/w/apiq.py |

### Jetpack release notes (developer.android.com)

| Key | What | URL | Cached copy |
|---|---|---|---|
| [RN-COMPOSE-UI] | Compose UI releases (minSdk) | https://developer.android.com/jetpack/androidx/releases/compose-ui | `C1/rn_compose-ui.txt` |
| [RN-HC] | Health Connect client and connect-testing releases | https://developer.android.com/jetpack/androidx/releases/health-connect | `C1/rn_health-connect.txt` |
| [RN-ROOM] | Room releases (minSdk) | https://developer.android.com/jetpack/androidx/releases/room | `C1/rn_room.txt` |
| [RN-WORK] | WorkManager releases (minSdk) | https://developer.android.com/jetpack/androidx/releases/work | `C1/rn_work.txt` |

### Source code (AOSP mirror `main`, androidx `androidx-main`, samples, Robolectric 4.17)

| Key | What | URL | Cached copy |
|---|---|---|---|
| [AOSP-ALARM] | AlarmManagerService (`cmd alarm`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java | `C2/w/aosp/AlarmManagerService.java` |
| [AOSP-AMSC] | ActivityManagerShellCommand (`am`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java | `C2/w/aosp/ActivityManagerShellCommand.java` |
| [AOSP-AOM] | AppOpsManager (op strings, mode names) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/app/AppOpsManager.java | `C2/w/aosp/AppOpsManager.java` |
| [AOSP-AOS] | AppOpsService (`cmd appops`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/appop/AppOpsService.java | `C1/agent1/src_AppOpsService.java` |
| [AOSP-BAT] | BatteryService (`dumpsys battery`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/BatteryService.java | `C2/w/aosp/BatteryService.java` |
| [AOSP-DIC] | DeviceIdleController (`dumpsys deviceidle`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java | `C2/w/aosp/DeviceIdleController.java` |
| [AOSP-ECM] | EnhancedConfirmationService (`isPackageEcmGuarded`, `PROTECTED_SETTINGS`). Read through a fetch-tool extraction; the aosp-mirror raw copy returned 404 (`C1/agent1/src_ECMService.java`) | https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/service/java/com/android/ecm/EnhancedConfirmationService.java | `C2/prev_webfetch.txt lines 440-577` |
| [AOSP-HIBSC] | AppHibernationShellCommand (`cmd app_hibernation`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/apphibernation/AppHibernationShellCommand.java | `C2/w/aosp/AppHibernationShellCommand.java` |
| [AOSP-INSC] | InputShellCommand (`input`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/input/InputShellCommand.java | `C2/w/aosp/InputShellCommand.java` |
| [AOSP-LOCSC] | LocationShellCommand (`cmd location`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/location/LocationShellCommand.java | `C2/w/aosp/LocationShellCommand.java` |
| [AOSP-LSSC] | LockSettingsShellCommand (`locksettings`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/locksettings/LockSettingsShellCommand.java | `C2/w/aosp/LockSettingsShellCommand.java` |
| [AOSP-MEDIASC] | MediaShellCommand (`cmd media_session`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/media/MediaShellCommand.java | `C2/w/aosp/MediaShellCommand.java` |
| [AOSP-NMC] | androidx NotificationManagerCompat (reads `enabled_notification_listeners`); the AOSP Settings constant is in `https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/provider/Settings.java` (`C1/agent1/src_Settings.java`) | https://raw.githubusercontent.com/androidx/androidx/androidx-main/core/core/src/main/java/androidx/core/app/NotificationManagerCompat.java | `C1/agent1/src_NMC.java` |
| [AOSP-NSC] | NotificationShellCmd (`cmd notification`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/notification/NotificationShellCmd.java | `C1/agent1/src_NotifShell.java` |
| [AOSP-PMSC] | PackageManagerShellCommand (`pm`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java | `C1/agent1/src_PMShell.java` |
| [AOSP-PWRSC] | PowerManagerShellCommand (`cmd power`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/power/PowerManagerShellCommand.java | `C2/w/aosp/PowerManagerShellCommand.java` |
| [AOSP-SVC] | Svc (`svc wifi` and `svc bluetooth` migration notes) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/cmds/svc/src/com/android/commands/svc/Svc.java | `C1/SvcWifi.java` |
| [AOSP-THERM] | ThermalManagerService (`cmd thermalservice`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/power/ThermalManagerService.java | `C2/w/aosp/ThermalManagerService.java` |
| [AOSP-USS] | UsageStatsService (`hasQueryPermission`, event filtering) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/usage/java/com/android/server/usage/UsageStatsService.java | `C1/agent1/src_UsageStatsService.java` |
| [AOSP-VOLCTRL] | VolumeCtrl (`media volume`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/media/VolumeCtrl.java | `C2/w/aosp/VolumeCtrl.java` |
| [AOSP-WMSC] | WindowManagerShellCommand (`wm dismiss-keyguard`) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/wm/WindowManagerShellCommand.java | `C2/w/aosp/WindowManagerShellCommand.java` |
| [AX-CTXCOMPAT] | androidx ContextCompat (`registerReceiver` with export flags) | https://raw.githubusercontent.com/androidx/androidx/androidx-main/core/core/src/main/java/androidx/core/content/ContextCompat.java | `C2/a1/ContextCompat.java` |
| [AX-HCFAKE] | androidx connect-testing fakes: `FakeHealthConnectClient`, `FakePermissionController`, `FakeHealthConnectFeatures` | https://raw.githubusercontent.com/androidx/androidx/androidx-main/health/connect/connect-testing/src/main/java/androidx/health/connect/client/testing/ (the three `.kt` files) | `C2/a1/FakeHealthConnectClient.kt, C2/a1/FakePermissionController.kt, C2/a1/FakeHealthConnectFeatures.kt` |
| [SAMPLE-AR] | Official platform sample: `UserActivityTransitionManager.kt` | https://raw.githubusercontent.com/android/platform-samples/main/samples/location/src/main/java/com/example/platform/location/useractivityrecog/UserActivityTransitionManager.kt | `C2/a2/ar_check.kt` |
| [ROBO] | Robolectric 4.17 shadow sources (`ShadowHealthConnectManager`, `ShadowNetworkStatsManager` and `ShadowStatusBarNotification` returned 404) | https://raw.githubusercontent.com/robolectric/robolectric/robolectric-4.17/shadows/framework/src/main/java/org/robolectric/shadows/ | `C1/agent1/robo/` |
| [ROBO-JAR] | shadows-framework 4.17 jar (class list checked) | https://repo.maven.apache.org/maven2/org/robolectric/shadows-framework/4.17/shadows-framework-4.17.jar | `C1/shadows-framework-4.17.jar (extracted in C1/robox/)` |
| [ROBO-CORE] | robolectric 4.17 jar (`Robolectric.buildService`, `setupContentProvider`) | https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.17/robolectric-4.17.jar | `C1/robo417.jar (extracted in C1/robocore/)` |
| [ROBO-SDK] | Robolectric 4.17 `DefaultSdkProvider` (SDK 23-37, Java 21 for 36 and 37) | https://raw.githubusercontent.com/robolectric/robolectric/robolectric-4.17/robolectric/src/main/java/org/robolectric/plugins/DefaultSdkProvider.java | `C2/w/DefaultSdkProvider.java` |

### Google Play and Google help pages (fetch-tool extractions, not raw text; see section 9)

| Key | What | URL | Cached copy |
|---|---|---|---|
| [P-PERM] | Play policy: Permissions and APIs that Access Sensitive Information (current; notes changes effective 2027-01-27) | https://support.google.com/googleplay/android-developer/answer/16558241 | `C2/prev_webfetch.txt lines 203-389` |
| [P-PREVIEW] | Play policy preview: the same page as of 2027-01-27 | https://support.google.com/googleplay/android-developer/answer/16909972 | `C2/prev_webfetch.txt lines 1-191` |
| [P-SMSCL] | Play Console Help: use of SMS or call log permission groups (exceptions, invalid uses) | https://support.google.com/googleplay/android-developer/answer/10208820 | `C2/prev_webfetch.txt lines 614-665` |
| [H-RESTRICTED] | Android Help: learn about restricted settings ("Android 13 and up") | https://support.google.com/android/answer/12623953 | `C2/prev_webfetch.txt lines 401-428` |
| [N-9TO5] | Secondary source (news): 9to5Google, "Android 15 further restricts what you can do with sideloaded apps", 2024-09-12 | https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/ | `C2/prev_webfetch.txt lines 578-613` |
