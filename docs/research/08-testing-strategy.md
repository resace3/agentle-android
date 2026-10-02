# 08 - Automated testing strategy (Agentle)

Status: COMPLETE v1. Research 2026-10-01, updated 2026-10-02 (aligned with docs 01, 02, 05, 06, 07 and 10; prototype
re-run). Author: Agent 8 (testing architect).

Scope: compileSdk/targetSdk 37 (Android 17); minSdk 29 PROVISIONAL (the [DOC07] catalog value; candidates 26-31);
Kotlin, Compose, Room 3, Hilt, WorkManager, DataStore, OkHttp/Retrofit, kotlinx.serialization. Toolchain as pinned by
[DOC07]: Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.20, KSP 2.3.12, JDK 21 (bytecode 17). Module names, the `backend` flavor
dimension (`prod`/`fake`) and the package root `dev.agentle` follow [DOC07].

Owner decision honored throughout: the health connector targets the **Google Health API** (the successor of the Fitbit
Web API). Nothing here emulates or tests the legacy Fitbit Web API.

Conventions: every version or API claim carries a source tag (section 0.2). **UNVERIFIED** = no current primary source
confirmed it. **[PROTO]** and **[PROTO-J6]** = demonstrated by code that ran in this container (section 16). Every test
count, coverage figure and timing in this document was produced by the evidence script (section 14); none were typed
by hand.

---

## Key findings (TL;DR)

1. **Only the pure-JVM tier can run in this container.** Google Maven (`maven.google.com` redirects to the blocked
   `dl.google.com`) is unreachable, so AGP, AndroidX, Compose, Hilt-Android and even Robolectric (its POM depends on
   `androidx.test:monitor` and `espresso-idling-resource`) cannot resolve. There is no KVM and no system image, so no
   emulator. The test architecture therefore keeps as much logic as possible in plain Kotlin/JVM modules, which
   [DOC07] already does (16 of 31 modules are JVM). Everything Android-specific is designed here from current sources
   and runs on the user's machine or CI. [PROTO][ROBO-POM][DOC07]
2. **A runnable prototype proves the JVM tier: 72 tests in 5 suites, 72 passed, 0 failed, 0 skipped**, measured with
   the build cache off and a freshness gate (section 14). It covers both fake servers, the 90-day synthetic user,
   per-package Kover gates, virtual-time JITAI scheduling and a 1M-event SQLite ladder. A second build proves that JVM
   modules on **JUnit 6** use the same fakes unchanged: **53 tests, 53 passed**. [PROTO][PROTO-J6]
3. **Robolectric 4.17** (2026-09-10) runs SDK 23-37 (SDK 36/37 need Java 21), defaults to native SQLite (`SQLiteMode`
   is deprecated) and the PAUSED looper. It is the "virtual device matrix": the SDK axis comes from `@Config`
   (`sdk`, or `minSdk`/`maxSdk`), intersected with the `robolectric.enabledSdks` system property, so the matrix is
   defined once in build logic; the device, theme and font axes come from qualifiers,
   `RuntimeEnvironment.setQualifiers("+night")` and `RuntimeEnvironment.setFontScale(...)`.
   [ROBO-REL][ROBO-SRC][ROBO-CFG]
4. **Per-SDK evidence needs one flag:** Robolectric appends `[api]` to test names for every SDK *except the last one*
   unless `-Drobolectric.alwaysIncludeVariantMarkersInTestName=true`. Set it on every Android unit-test task, or the
   per-SDK counts in reports are wrong. [ROBO-SRC]
5. **Compose tests must use the v2 APIs** (`androidx.compose.ui.test.junit4.v2.*`): v1 is deprecated, and v2 runs
   composition on `StandardTestDispatcher`, which matches `runTest`. Accessibility checks come from the separate
   `ui-test-junit4-accessibility` artifact, which is not in the Compose BOM. [CMP-V2][CMP-A11Y][BOM]
6. **Screenshots:** Roborazzi 1.76.0 with `@GraphicsMode(NATIVE)` (native graphics needs SDK >= 26), qualifier presets
   `SmallPhone`/`MediumPhone`/`MediumTablet`, `+night`, font scale 1.0/1.3/2.0: 18 images per key screen, about 216
   in total for 12 screens. [RZ][ROBO-SRC]
7. **Coverage: Kover 0.9.11 for JVM and Android unit tests; it cannot measure on-device tests.** Per-package thresholds
   use `copyVariant` plus package filters. Variant verify tasks do **not** run on `check` unless `verify.onCheck = true`
   (verified in Kover source and in the prototype, including a deliberately failing rule). On Android modules the Kover
   variant is the AGP variant, so the tasks are `koverVerifyDebug` (libraries) and `koverVerifyProdDebug` (app).
   [KOVER-SRC][KOVER-DOC][PROTO]
8. **Fakes are one shared pure-JVM module** on MockWebServer 3 (OkHttp 5.5.0), used by JVM tests, Robolectric,
   instrumentation and the `fakeDebug` app variant. DOC05 section 8 and DOC06 section 9 are the normative contracts;
   the prototype now implements them (DOC06 9.6's list of corrections is resolved): **19 Google Health scenarios**
   mapped to DOC05 rows and **28 ChatGPT scenarios** mapped to DOC06 rows. The 21 DOC05 fixture bodies it serves are
   extracted byte-for-byte from DOC05 by a script that also detects drift. [PROTO][DOC05][DOC06][MWS]
9. **Synthetic data is deterministic across Kotlin versions** because it uses its own SplitMix64, not
   `kotlin.random.Random(seed)` (only stable "within the same version of Kotlin runtime"). The 90-day user covers a
   New York -> Berlin trip that spans the EU DST end (a 25-hour day abroad), the US DST end at home, wearable gap days,
   nightly off-wrist periods, exercise and sedentary days. Golden: seed 42 -> 57,519 events, SHA-256 `cc78bf36...`.
   [PROTO][KRANDOM]
10. **Room tests belong on the JVM, not under Robolectric.** Google's Room testing page now says: "We don't recommend
    Android local unit tests with Robolectric. Use local JVM tests using Room KMP instead", with `BundledSQLiteDriver`
    so host and devices run the same SQLite. Recommendation for [DOC07]: build `:core:database` with the
    `com.android.kotlin.multiplatform.library` plugin plus a `jvm()` target, so DAO and migration tests run as plain JVM
    tests (`Path`-based `MigrationTestHelper`). Device SQLite differs a lot (3.18 on API 26, 3.22 on API 28-29, 3.50
    on API 37), which also argues for the bundled driver in production. At 1M events, indexed 7-day queries took under
    1 ms (median) on the JVM. [ROOM-TEST][KMP-AND][A-SQLITE][MTH3][PROTO]
11. **Emulator matrix for the user's machine:** Gradle Managed Devices at API 29 (provisional minSdk), 30, 34 and 37;
    API 26 only if minSdk drops to 26, as a hand-made AVD (managed devices support API 27+). ATD images (API 30 only)
    remove Chrome, the Settings app and SystemUI, so journeys that need the permission dialog, the notification shade,
    Settings or a Custom Tab run on `google` images. Profiles: `Small Phone` (720x1280, 1 GiB RAM: also the low-memory
    device), `Pixel 9`, `Medium Tablet`. [GMD][SDK-DEV]
12. **State injection on emulators uses verified shell commands**: deviceidle, battery, standby buckets, appops,
    uimode, `cmd alarm set-timezone/set-time`, time-zone detector, storage monitor, notification listener and DND,
    jobscheduler, netpolicy (Data Saver), `wm size/density/user-rotation`, `settings put system font_scale`, and
    `pm grant/revoke/set-permission-flags`. `svc wifi` and `svc data` are verified wrappers around module commands;
    airplane mode via `cmd connectivity` stays **UNVERIFIED**, so every run captures `cmd <service> help` as a
    preflight. [AOSP][ADB][SET-SYS][DOC01][DOC02]
13. **Gradle's build cache can fake a green run.** With `org.gradle.caching=true`, `cleanTest test` restores test XML
    FROM-CACHE: the reports carry old timestamps and nothing re-ran. Evidence runs use `--no-build-cache`, and the
    aggregation script fails any suite whose XML is older than the run start (`--fresh-after`). Both the trap and the
    fix were reproduced in the prototype. [PROTO]
14. **JUnit 6 naming detail that matters for evidence:** Jupiter's default display names for parameterized tests
    (`[1] "happy"`) carry no method name, so two parameterized methods produce identical test names in the JUnit XML.
    One line in `junit-platform.properties` makes every name unique (proven: 53 of 53 unique). [PROTO-J6]

---

## 0. Sources and method

### 0.1 Method and constraints

- No WebFetch/WebSearch was used (owner request: no permission prompts for reading public pages). Pages were fetched
  with `curl` from reachable hosts (developer.android.com, repo.maven.apache.org, raw.githubusercontent.com) or reused
  from caches written earlier in this task. A few facts (Robolectric release notes, the Kover documentation page) were
  read in the first run on 2026-10-01 from hosts that refuse connections now (`github.com` 403; `kotlin.github.io`
  refused on 2026-10-02). They are cited with the URL as read, and the Kover claims were re-checked against the Kover
  sources jar on Maven Central.
- Blocked or refused from this container: `maven.google.com`/`dl.google.com` (Google Maven), `github.com` and
  `api.github.com` (HTTP 403), `developers.openai.com`, `android.googlesource.com`, `support.google.com`,
  `kotlin.github.io`, `sqlite.org`. No mirror, cache or proxy was used to reach a blocked host.
- Sibling research is reused, not duplicated: permission states and per-state Robolectric/adb recipes ([DOC01]
  section 7); the background test plan T-* and emulator scenarios E1-E18 ([DOC02] section 7); the Google Health API
  fake contract ([DOC05] section 8); the Sign in with ChatGPT fake contract ([DOC06] section 9); toolchain, modules,
  flavors and the clock ([DOC07]); JITAI design and DST test vectors ([DOC10]).
- Prototype: `/tmp/claude-0/-home-claude-agentle-android/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/proto`
  (Gradle 9.7.1 wrapper, JDK 21.0.11, Kotlin 2.4.20, Maven Central only) and the JUnit 6 check in
  `.../scratchpad/a8/j6check`. See section 16.
- Machine limits respected: one Gradle build at a time, `org.gradle.jvmargs=-Xmx3g`, Kotlin daemon `-Xmx2g`, daemons
  stopped after every run.

### 0.2 Source tags

"LU" = the page's "Last updated" date as fetched.

| Tag | Source |
|---|---|
| [MC] | Maven Central `maven-metadata.xml`, fetched 2026-10-01/02: `https://repo.maven.apache.org/maven2/<group path>/<artifact>/maven-metadata.xml` |
| [ROBO-REL] | https://github.com/robolectric/robolectric/releases/tag/robolectric-4.17 (read 2026-10-01: "Robolectric 4.17 supports SDK 37"; "Deprecate `SQLiteMode`") |
| [ROBO-README] | https://github.com/robolectric/robolectric (read 2026-10-01: "15 different versions of Android, ranging from M (API level 23) to Cinnamon Bun (API level 37)") |
| [ROBO-SRC] | Robolectric 4.17 sources, `https://raw.githubusercontent.com/robolectric/robolectric/robolectric-4.17/` + `robolectric/src/main/java/org/robolectric/plugins/DefaultSdkProvider.java`, `.../plugins/DefaultSdkPicker.java` (`selectSdks` intersects the configured SDKs with `robolectric.enabledSdks`), `.../RobolectricTestRunner.java` (lines 118, 230-242, 599-612), `.../ParameterizedRobolectricTestRunner.java`, `.../RobolectricTestParameterInjector.java`, `shadows/framework/src/main/java/org/robolectric/RuntimeEnvironment.java` (`setQualifiers`, `setFontScale`), `.../shadows/ShadowNativeBitmap.java` (`minSdk = O`), `annotations/.../SQLiteMode.java`, `GraphicsMode.java`, `LooperMode.java` |
| [ROBO-SHADOWS] | `javap` of `shadows-framework-4.17.jar` from Maven Central (method names quoted in sections 8 and 11) |
| [ROBO-CFG] | https://robolectric.org/configuring/ (`@Config(sdk=...)`, `minSdk/maxSdk`, `robolectric.properties`, `-Drobolectric.enabledSdks`, `robolectric.offline`, `robolectric.dependency.dir`) |
| [ROBO-POM] | https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.17/robolectric-4.17.pom (depends on `androidx.test:monitor:1.8.0`, `androidx.test.espresso:espresso-idling-resource:3.7.0`, which live on Google Maven) |
| [ROBO-AND] | https://developer.android.com/training/testing/local-tests/robolectric (`isIncludeAndroidResources = true`) |
| [TPI] | https://raw.githubusercontent.com/google/TestParameterInjector/main/README.md (JUnit 4 runner, JUnit 5 `@TestParameterInjectorTest`, artifact `test-parameter-injector-junit5`) |
| [RZ] | https://raw.githubusercontent.com/takahirom/roborazzi/1.76.0/README.md (tasks, `roborazzi { outputDir }`, `robolectric.pixelCopyRenderMode`, `changeThreshold`), `.../roborazzi/src/main/java/com/github/takahirom/roborazzi/RobolectricDeviceQualifiers.kt`, `.../roborazzi-accessibility-check/README.md` |
| [CMP-TEST] | https://developer.android.com/develop/ui/compose/testing |
| [CMP-V2] | https://developer.android.com/develop/ui/compose/testing/migrate-v2 |
| [CMP-SYNC] | https://developer.android.com/develop/ui/compose/testing/synchronization (LU 2026-10-01: `mainClock.autoAdvance`, `advanceTimeByFrame`, `advanceTimeBy`, `advanceTimeUntil`, `registerIdlingResource`, `waitUntil*`) |
| [CMP-A11Y] | https://developer.android.com/develop/ui/compose/accessibility/testing |
| [CMP-REL] | https://developer.android.com/jetpack/androidx/releases/compose-ui |
| [BOM] | https://developer.android.com/develop/ui/compose/bom/bom-mapping |
| [AXT] | https://developer.android.com/jetpack/androidx/releases/test (LU 2026-01-14) |
| [RUNNER] | https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner (LU 2026-03-05: Orchestrator, `clearPackageData`, `execution 'ANDROIDX_TEST_ORCHESTRATOR'`, `numShards`/`shardIndex`, `-Pandroid.testInstrumentationRunnerArguments.size`) |
| [AJR] | https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner (`-e annotation`, `-e notAnnotation`, `-e class`, `-e package`, `-e size`, `-e numShards/-e shardIndex`, `-e listener`) |
| [ESP-INT] | https://developer.android.com/training/testing/espresso/intents (LU 2026-03-05: `intended()`, `intending(...).respondWith(ActivityResult)`) |
| [TEST-CLI] | https://developer.android.com/studio/test/command-line (LU 2026-09-25: `testVariantNameUnitTest`, `connectedVariantNameAndroidTest`, results in `build/test-results/` and `build/outputs/androidTest-results/connected/`) |
| [UIA-REL] | https://developer.android.com/jetpack/androidx/releases/test-uiautomator |
| [UIA] | https://developer.android.com/training/testing/other-components/ui-automator (LU 2026-09-21: `uiAutomator { }`, `onElement`, `onElementOrNull`, `onElements`, `watchFor(PermissionDialog) { clickAllow() / clickDeny() }`, `startApp`, `startActivity`, `startIntent`, `clearAppData`, `waitForAppToBeVisible`, `waitForStable`, `takeScreenshot`) |
| [UIDEV] | https://developer.android.com/reference/androidx/test/uiautomator/UiDevice (LU 2026-08-06: `openNotification()`, `openQuickSettings()`, `executeShellCommand` marked `@Discouraged` in favor of `UiAutomation#executeShellCommandRwe`) |
| [HILT-T] | https://developer.android.com/training/dependency-injection/hilt-testing (LU 2026-10-01: `HiltAndroidRule`, `HiltTestApplication`, `@TestInstallIn`, `@UninstallModules`, `@BindValue`, `@Config(application = HiltTestApplication::class)`, custom runner `newApplication`) |
| [WM-REL] | https://developer.android.com/jetpack/androidx/releases/work |
| [WM-IT] | https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing (LU 2026-02-26: `WorkManagerTestInitHelper.initializeTestWorkManager`, `SynchronousExecutor`, `TestDriver.setAllConstraintsMet/setInitialDelayMet/setPeriodDelayMet`, `TestWorkerBuilder`) |
| [CORO-TEST] | https://developer.android.com/kotlin/coroutines/test (LU 2026-09-22: `runTest`, `StandardTestDispatcher`, `UnconfinedTestDispatcher`, shared `testScheduler`, `Dispatchers.setMain/resetMain`, `MainDispatcherRule`) |
| [ROOM-REL] / [ROOM3-REL] | https://developer.android.com/jetpack/androidx/releases/room and https://developer.android.com/jetpack/androidx/releases/room3 |
| [ROOM-TEST] | https://developer.android.com/training/data-storage/room/testing-db (LU 2026-09-08: "We don't recommend Android local unit tests with Robolectric. Use local JVM tests using Room KMP instead."; in-memory builders with `BundledSQLiteDriver` on device and on the JVM) |
| [MTH3] | https://developer.android.com/reference/kotlin/androidx/room3/testing/MigrationTestHelper |
| [SQLITE-REL] | https://developer.android.com/jetpack/androidx/releases/sqlite |
| [KMP-SQLITE] | https://developer.android.com/kotlin/multiplatform/sqlite |
| [KMP-AND] | https://developer.android.com/kotlin/multiplatform/plugin (LU 2026-10-01: `com.android.kotlin.multiplatform.library` is "the officially" supported plugin; `withHostTestBuilder {}`, `withDeviceTestBuilder {}`; "Build types and product flavors are not supported") |
| [A-SQLITE] | https://developer.android.com/reference/android/database/sqlite/package-summary (LU 2026-08-03: SQLite version per API level) |
| [GMD] | https://developer.android.com/studio/test/managed-devices (redirect target of `.../gradle-managed-devices`; LU 2026-01-16: "Use only API levels 27 and higher", ATD "currently support only API level 30", `systemImageSource` `aosp`/`google`/`aosp-atd`/`google-atd`, groups, `swiftshader_indirect`, `numManagedDeviceShards`, the list of what ATD images remove) |
| [ADB] | https://developer.android.com/tools/adb (LU 2026-09-25: `am start/force-stop/kill/broadcast/instrument`, `pm clear/grant/revoke`, `screencap`, `exec-out`) |
| [EMU-CON] | https://developer.android.com/studio/run/emulator-console (`adb emu power ...`, `adb emu gsm data ...`) |
| [DOZE] / [PWR] | https://developer.android.com/training/monitoring-device-state/doze-standby and https://developer.android.com/topic/performance/power/test-power |
| [AOSP] | `https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/` + `services/core/java/com/android/server/am/ActivityManagerShellCommand.java`, `apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java`, `.../server/BatteryService.java`, `.../server/UiModeManagerService.java`, `.../server/power/PowerManagerShellCommand.java`, `.../server/storage/DeviceStorageMonitorService.java`, `apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java`, `.../timezonedetector/TimeZoneDetectorShellCommand.java`, `.../notification/NotificationShellCommand.java`, `.../pm/PackageManagerShellCommand.java`, `apex/jobscheduler/service/java/com/android/server/job/JobSchedulerShellCommand.java`, and (fetched 2026-10-02) `cmds/svc/svc.sh`, `services/core/java/com/android/server/wm/WindowManagerShellCommand.java`, `packages/SettingsProvider/src/com/android/providers/settings/SettingsService.java`, `services/core/java/com/android/server/net/NetworkPolicyManagerShellCommand.java`. Mirror of AOSP `main`; Android 17 images may differ, hence the preflight in 13.5 |
| [SET-SYS] | https://developer.android.com/reference/android/provider/Settings.System (`FONT_SCALE` = `"font_scale"`, `TIME_12_24` = `"time_12_24"`, `USER_ROTATION`, all API 1) |
| [KOVER-DOC] | https://kotlin.github.io/kotlinx-kover/gradle-plugin/ (read 2026-10-01: "Instrumented (on-device) tests are not being modified by Kover and coverage for them cannot be collected.") |
| [KOVER-SRC] | https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kover-gradle-plugin/0.9.11/kover-gradle-plugin-0.9.11-sources.jar (`KoverVariantConfig.kt` `copyVariant`; `ReportsImpl.kt` `verify.onCheck.convention(variantName == TOTAL_VARIANT_NAME)`; `Naming.kt` `koverVerify<Variant>`; `Paths.kt` `report<Variant>.xml`; `locators/Android.kt` matches `<variant>UnitTest`; `KotlinMultiPlatformLocator.kt` matches `<target>HostTest`) |
| [KOVER-CL] | https://raw.githubusercontent.com/Kotlin/kotlinx-kover/main/CHANGELOG.md ("0.9.11 / 2026-09-29") |
| [AGP-REL] | https://developer.android.com/build/releases/gradle-plugin ("Android Gradle plugin 9.4.0 (September 2026)") |
| [AGP-DSL] | https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/BuildType (`enableUnitTestCoverage`, added 7.2.0; `enableAndroidTestCoverage`, added 7.3.0) |
| [MWS] | https://raw.githubusercontent.com/square/okhttp/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/ (`MockResponse.kt`: `throttleBody`, `onResponseStart`, `onResponseBody`; `SocketEffect.kt`: `CloseSocket`, `ShutdownConnection`, `CloseStream`, `Stall`; `MockWebServer.kt`: the socket effect fires at half of the body length) |
| [MWS-J5] | https://raw.githubusercontent.com/square/okhttp/parent-5.5.0/mockwebserver-junit5/src/main/kotlin/mockwebserver3/junit5/StartStop.kt (artifact `mockwebserver3-junit5`) |
| [RETROFIT] | https://raw.githubusercontent.com/square/retrofit/3.0.0/retrofit/src/main/java/retrofit2/Retrofit.java (`Builder.baseUrl`: "Base URLs should always end in /"; "Endpoint values which contain a leading / are absolute") |
| [KRANDOM] | https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.20/libraries/stdlib/src/kotlin/random/Random.kt, lines 317/332 |
| [SDK-DEV] | Local SDK: `/opt/android-sdk/cmdline-tools/latest` (cmdline-tools 22.0), `lib/sdklib` jars, `com/android/sdklib/devices/devices.xml` and `nexus.xml` |
| [DOC01] [DOC02] [DOC05] [DOC06] [DOC07] [DOC10] | Sibling research documents in `docs/research/` |
| [PROTO] | The prototype in section 16, run in this container (final run 2026-10-02T05:06:48Z) |
| [PROTO-J6] | The JUnit 6 consumer check in section 16, run in this container on 2026-10-02 |

---

## 1. Verified versions and capabilities

Versions are what testing needs; the authoritative catalog is owned by [DOC07]. "Latest stable" means the newest
non-pre-release in the cited source on the fetch date.

| Component | Version | Why it matters for testing | Source |
|---|---|---|---|
| Toolchain | Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.20, KSP 2.3.12, JDK 21 (bytecode 17) | Pinned by DOC07. AGP 9.4.0 (September 2026) is newer but outside KGP 2.4.20's supported AGP range (8.5.2-9.3.1). JDK 21 is required by Robolectric SDK 36/37 | [DOC07][AGP-REL] |
| Robolectric | 4.17 (2026-09-10) | SDK 23-37; SDK 34-35 need Java 17, SDK 36-37 need Java 21; `SQLiteMode` deprecated, NATIVE is the default; PAUSED looper is the default and LEGACY "is not supported on Android SDKs > Baklava"; SDK 37 jar `android-all-instrumented:17-robolectric-15733970-i7` | [ROBO-REL][ROBO-README][ROBO-SRC][MC] |
| Roborazzi | 1.76.0 (2026-09-29) | `captureRoboImage`, `record/compare/verify/verifyAndRecordRoborazzi<Variant>`, `roborazzi { outputDir }`, `RobolectricDeviceQualifiers`, `roborazzi-accessibility-check` (ATF) | [MC][RZ] |
| Compose UI / BOM | 1.12.1 / 2026.09.00 | v2 test APIs (StandardTestDispatcher); `mainClock`; `enableAccessibilityChecks()` in `ui-test-junit4-accessibility` (not listed in the BOM); `ComposeUiTestConfig` only from 1.13.0-alpha01 | [CMP-REL][BOM][CMP-V2][CMP-SYNC][CMP-A11Y] |
| androidx.test | core/runner/rules 1.7.0, ext.junit 1.3.0, ext.truth 1.7.0, espresso 3.7.0 (incl. espresso-intents), orchestrator 1.6.1, services 1.6.0, monitor 1.8.0 | Runner, Orchestrator (process isolation, `clearPackageData`), Espresso-Intents | [AXT][RUNNER][ESP-INT] |
| UI Automator | 2.4.0 (2026-07-01) | `uiAutomator { }` DSL, `onElement { }`, `watchFor(PermissionDialog)`, `clearAppData`, `waitForStable`; `UiDevice.openNotification()` | [UIA-REL][UIA][UIDEV] |
| Hilt testing | 2.60.1 | `HiltAndroidRule`, `HiltTestApplication`, `@TestInstallIn`, `@UninstallModules`, `@BindValue` (the guide's snippet still shows 2.57.1) | [MC][HILT-T] |
| WorkManager | 2.12.0 (2026-09-23), minSdk 24 | `work-testing`: `WorkManagerTestInitHelper`, `SynchronousExecutor`, `TestDriver`, `TestListenableWorkerBuilder`; `Configuration.Builder.setClock()` | [WM-REL][WM-IT][DOC07] |
| Room 3 | 3.0.3 (`androidx.room3`, 2026-09-09) | KSP only, coroutine-only, `SQLiteDriver` required; `room3-testing` `MigrationTestHelper` with an Android (`Instrumentation`) and a JVM (`Path`/`String`) constructor; `createDatabase`/`runMigrationsAndValidate` are `suspend` | [ROOM3-REL][MTH3][DOC07] |
| androidx.sqlite | 2.7.1 (2026-09-09) | `BundledSQLiteDriver` runs on Android and on the JVM | [SQLITE-REL][KMP-SQLITE][ROOM-TEST] |
| Android-KMP library plugin | `com.android.kotlin.multiplatform.library` (part of AGP) | Host tests via `withHostTestBuilder {}`, device tests via `withDeviceTestBuilder {}`; single variant (no build types, no flavors), which suits `:core:database` | [KMP-AND] |
| kotlinx-coroutines-test | 1.11.0 | `runTest`, `StandardTestDispatcher`, virtual time, shared `testScheduler`, `Dispatchers.setMain` | [MC][CORO-TEST] |
| JUnit | 6.1.3 (Jupiter) and 4.13.2 | JUnit 6 in JVM modules (DOC07); JUnit 4 for Robolectric and AndroidJUnitRunner | [MC][DOC07][PROTO-J6] |
| TestParameterInjector | 1.24 (2026-09-28): `test-parameter-injector` and `test-parameter-injector-junit5` | Combinatorial tests on both runners; Robolectric ships `RobolectricTestParameterInjector` | [MC][TPI][ROBO-SRC][PROTO-J6] |
| Turbine / Truth | 1.2.1 / 1.4.5 | Flow assertions, assertions | [MC] |
| OkHttp / mockwebserver3 (+ `-junit4`, `-junit5`) | 5.5.0 | Fake servers with throttling and socket faults; `@StartStop` JUnit 5 extension | [MC][MWS][MWS-J5][PROTO-J6] |
| Kover | 0.9.11 (2026-09-29) | JVM + Android unit-test coverage, per-variant verify | [KOVER-CL][MC] |
| JaCoCo | 0.8.15 | Only for optional instrumented coverage through AGP | [MC][AGP-DSL] |
| sqlite-jdbc | 3.53.4.0 (2026-08-26) | JVM-only DB scale ladder (bundles SQLite 3.53.4) | [MC][PROTO] |
| Health Connect testing | `connect-testing` 1.0.0-alpha04 (alpha) | `FakeHealthConnectClient`, `FakePermissionController`; alpha is acceptable because it is test-only | [DOC05][DOC01] |

---

## 2. Test pyramid

```
            L5  Manual / live (1%)        real Google Health + SIWC accounts, TalkBack, OEM devices, soak
          L4  Emulator instrumentation (4%)   10 journeys, system dialogs, shade, Doze, reboot, DST via adb
        L3  Robolectric component + UI (15%)  Compose screens, ViewModels+Hilt, shadows, WorkManager, screenshots
      L2  JVM integration (10%)               HTTP clients vs fakes, Room on the JVM, DB scale, migrations
    L1  Pure JVM unit (70%)                   JITAI, features, normalization, OAuth/sync state machines, DTOs
  L0  Static: Android Lint, detekt, ktlint, dependency checks, verifyNoFakesInProd (owned by DOC07)
```

| Layer | What it proves | Runs where | Budget per PR |
|---|---|---|---|
| L1 | Pure logic: JITAI DSL and engine (quiet hours, caps, cooldown, snooze, three-valued evaluation; [DOC10]), derived features, normalization (offsets, DST, dedupe, source fusion), SIWC and token state machines, error-policy tables, DTO decoding against fake and recorded payloads | Container, user machine, CI | < 2 min |
| L2 | The real OkHttp/Retrofit clients against the fakes (retries, backoff, paging, timeouts, cancellation, SSE); Room DAOs and migrations on the JVM; DB scale ladder (100k/500k/1M) | Container (HTTP, scale); user machine/CI for Room | < 3 min (ladder nightly) |
| L3 | Android behavior without a device: permission state model with shadows, notifications and channels, alarms, WorkManager scheduling and constraints, Compose screens with v2 rules + Hilt + fakes, screenshots, accessibility checks, SDK matrix | User machine, CI (not this container) | < 8 min |
| L4 | What only a real system shows: runtime-permission dialogs, notification shade, Settings special-access screens, Doze/standby, reboot, real time-zone and DST changes, Custom Tab hop, low-memory kills | User machine (managed devices) | nightly / pre-release |
| L5 | Live services and human judgment | Developer with accounts | pre-release |

Rules of thumb:
- A bug found at L4 gets a regression test at the lowest layer that can reproduce it.
- No L1-L3 test may touch the network: the fakes bind to `127.0.0.1` only; CI denies egress for test tasks.
- No test may read the wall clock or the default time zone: inject `AgentleClock` ([DOC07] 8.6); the prototype's
  scheduler takes `clock: () -> Instant`. [PROTO]

---

## 3. Where each suite runs

Module names are [DOC07]'s. The prototype's names map as follows: `:core:jitai` -> `:jitai:engine`, `:core:features`
-> `:features:engine` (normalization is a package there), `:testing:fixtures` -> `:fakes:servers` (section 5.1),
`:testing:db-scale` -> the JVM tests of `:core:database` (or a JVM-only `:tools:db-scale` if `:core:database` stays
Android-only).

| Suite | Kind | This container | User machine | CI | Trigger |
|---|---|---|---|---|---|
| `:jitai:dsl`, `:jitai:engine`, `:features:engine`, `:insights`, `:ai:api`, `:ai:context`, `:connectors:api`, `:core:time`, `:core:model`, `:core:common` | JVM, JUnit 6 | YES (prototype equivalents proved) | yes | yes | every PR |
| Client integration: `:connectors:googlehealth`, `:ai:openai`, `:core:network` against the fakes | JVM, JUnit 6 | YES | yes | yes | every PR |
| `:fakes:servers` self-tests, generator tests | JVM | YES (proved) | yes | yes | every PR |
| DB scale ladder | JVM | YES (proved with sqlite-jdbc) | yes | yes | 100k every PR, ladder nightly |
| `:core:database` DAOs + migrations | JVM (`jvm()` target, `BundledSQLiteDriver`), else instrumented | NO (Google Maven) | yes | yes | every PR |
| Android unit tests incl. Robolectric: `testDebugUnitTest` (libraries), `:app:testProdDebugUnitTest`, `:app:testFakeDebugUnitTest` | Robolectric | NO | yes | yes | every PR (SDK 37 + matrix tests) |
| Whole Robolectric suite at minSdk | Robolectric | NO | yes | yes | nightly |
| Roborazzi verify | Robolectric | NO | yes | yes | every PR; record on demand |
| Journeys on managed devices (`fakeDebug`) | Emulator | NO (no KVM) | yes | optional (needs KVM runners) | nightly / pre-release |
| Live checks | Device + accounts | NO | yes | no | pre-release |

### 3.1 Robolectric "virtual device matrix"

Robolectric gives each test a configured SDK and resource qualifiers; it does not boot an OS. The matrix has two axes.

**SDK axis (behavior).** Robolectric selects the SDKs configured on the test (`sdk`, a `minSdk`/`maxSdk` range, or
`ALL_SDKS`) and then intersects them with the `robolectric.enabledSdks` system property [ROBO-SRC]
(`DefaultSdkPicker.selectSdks`). So the matrix lives in one place, build logic, and tests only opt in:

```kotlin
// :core:testing-android
object PlatformMatrix {
    const val MIN_SDK = 29   // = minSdk in the version catalog; a unit test compares the two
    // Build logic sets robolectric.enabledSdks to this list (4.4). Add 26 and 28 only if minSdk goes below 29.
    val SDKS = intArrayOf(29, 30, 31, 33, 34, 35, 36, 37)
}

@RunWith(AndroidJUnit4::class)
@Config(minSdk = PlatformMatrix.MIN_SDK)   // every known SDK >= 29, intersected with enabledSdks = the 8 levels above
class NotificationCapabilityResolverTest { /* ... */ }
```

Tests without `minSdk` run once, at the default SDK 37 (`sdk=37` in the generated `robolectric.properties`, 4.4).
[DOC02]'s background tests may keep their explicit `@Config(sdk = [29, 31, 34, 35, 36, 37])`, a subset of the
enabled levels.

| SDK | Why it is in the matrix (behavior owners: [DOC01] 7.5, [DOC02] 7.0) |
|---|---|
| 26, 28 | Only if minSdk drops below 29: notification channels and background-service limits (26); App Standby Buckets, start of the Health Connect APK range (28) |
| 29 | Provisional minSdk; `ACTIVITY_RECOGNITION` runtime permission; separate background location; low-RAM listener rule |
| 30 | Package visibility; one-time permissions; auto-reset |
| 31 | Exact-alarm permission; FGS start restrictions; Bluetooth runtime permissions; approximate location; below 31, expedited work needs `getForegroundInfo()`. Not in DOC07's list (`[MIN_SDK, 30, 33, 34, 35, 36, 37]`); added because DOC01 and DOC02 both depend on it |
| 33 | `POST_NOTIFICATIONS` runtime permission; granular media |
| 34 | FGS types required; Health Connect in the framework; exact alarms denied by default; partial media access |
| 35 | FGS `dataSync` time limits; edge-to-edge enforcement; stopped state |
| 36 | Android 16 health permissions (`android.permission.health.*`); JobScheduler quota changes |
| 37 | targetSdk 37 behavior (Android 17): memory limiter, `ACTION_TIMEZONE_OFFSET_CHANGED` |

Nightly, the whole suite runs once more at minSdk (4.3). Native graphics (screenshots) needs SDK >= 26
(`ShadowNativeBitmap` is `minSdk = O`). [ROBO-SRC]

**Configuration axis (presentation).** Device size, theme and font scale come from qualifiers and runtime setters
[RZ][ROBO-SRC]:

```kotlin
@RunWith(RobolectricTestParameterInjector::class)            // Robolectric 4.17 + TestParameterInjector [ROBO-SRC][TPI]
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class DashboardScreenshotTest(
    @TestParameter private val device: Device,               // SMALL_PHONE, MEDIUM_PHONE, MEDIUM_TABLET
    @TestParameter private val theme: Theme,                 // LIGHT, DARK
    @TestParameter private val font: FontScale,              // F1_0, F1_3, F2_0
) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()   // androidx.compose.ui.test.junit4.v2 [CMP-V2]

    @Before fun configure() {
        RuntimeEnvironment.setQualifiers(device.qualifiers)  // RobolectricDeviceQualifiers.SmallPhone etc. [RZ]
        if (theme == Theme.DARK) RuntimeEnvironment.setQualifiers("+night")
        RuntimeEnvironment.setFontScale(font.scale)          // 1.0f, 1.3f, 2.0f [ROBO-SRC]
    }
}
```

`RobolectricDeviceQualifiers` values used (Roborazzi 1.76.0): `SmallPhone = "w360dp-h640dp-normal-long-notround-any-
xhdpi-keyshidden-nonav"`, `MediumPhone = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav"`,
`MediumTablet = "w1280dp-h800dp-xlarge-notlong-notround-any-xhdpi-keyshidden-nonav"`. [RZ]

What Robolectric cannot model (and therefore goes to L4): the real permission dialog and its "don't ask again"
heuristics, the notification shade, Settings screens, Doze/standby enforcement, process death by the low-memory killer,
real reboot, OEM behavior, real Chrome Custom Tabs. Robolectric only lets tests *set* these states through shadows.

---

## 4. Exact Gradle tasks

Task names follow AGP's `test<Variant>UnitTest` and `connected<Variant>AndroidTest` patterns [TEST-CLI]. Libraries
have the variants `debug`/`release`; the app has `prodDebug`, `prodRelease` and `fakeDebug` (`fakeRelease` is
disabled, [DOC07] 8.4).

### 4.1 Every PR (CI) and before pushing (developer)

```bash
START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
# L0 (owned by DOC07)
./gradlew lintProdDebug detekt spotlessCheck verifyNoFakesInProd
# L1 + L2: JVM modules (JUnit 6). `test` in every JVM module; the only tier that runs in this container.
./gradlew :jitai:dsl:test :jitai:engine:test :features:engine:test :insights:test :ai:api:test :ai:context:test \
          :ai:openai:test :connectors:api:test :connectors:googlehealth:test :core:network:test :core:time:test \
          :core:model:test :core:common:test :fakes:servers:test :core:testing:test
# Room on the JVM (Android-KMP :core:database with a jvm() target, section 7.1)
./gradlew :core:database:jvmTest
# L3: Android unit tests incl. Robolectric (libraries, then both app flavors)
./gradlew testDebugUnitTest :app:testProdDebugUnitTest :app:testFakeDebugUnitTest
# Screenshots (UI library modules; the app variant only if it owns screens)
./gradlew verifyRoborazziDebug
# Coverage gates (section 10)
./gradlew koverXmlReport koverVerify :features:engine:koverVerifyNormalization :ai:openai:koverVerifyOauthState \
          :core:network:koverVerifyOauthState :connectors:googlehealth:koverVerifyOauthState \
          :data:events:koverVerifyRepository
# Evidence (section 14): fails on any failure, any missing suite or any stale XML
python3 tools/junit_summary.py --root . --fresh-after "$START" \
        --expect :jitai:engine:test --expect :app:testProdDebugUnitTest \
        --json build/evidence/evidence.json --markdown build/evidence/evidence.md
```

In CI the Gradle invocations above run with `--no-build-cache` (section 14.1), or the evidence step must accept only
fresh XML. Tasks verified in the prototype: `test`, `koverXmlReport`, `koverVerify`, `koverVerify<Variant>`,
`koverXmlReport<Variant>` and the custom `dbScaleTest`. Roborazzi tasks are from [RZ]. The Android task names follow
[TEST-CLI] and [GMD] and could not be executed here. The KMP JVM test task name `jvmTest` is the standard Kotlin
Multiplatform name (UNVERIFIED here; no KMP build was run).

### 4.2 Screenshots (record on purpose, never in CI)

```bash
./gradlew recordRoborazziDebug          # or: testDebugUnitTest -Proborazzi.test.record=true
./gradlew compareRoborazziDebug         # writes comparison images for review
./gradlew verifyRoborazziDebug          # CI gate
```
[RZ]

### 4.3 Nightly

```bash
./gradlew :core:database:dbScaleTest -PdbScaleSizes=100000,500000,1000000     # [PROTO] (prototype: :testing:db-scale)
./gradlew testDebugUnitTest :app:testFakeDebugUnitTest -ProbolectricDefaultSdk=29   # whole Robolectric suite at minSdk
./gradlew :app:journeysGroupFakeDebugAndroidTest                                # journeys on the managed devices (13.2)
```

### 4.4 Build-logic snippets these commands rely on

```kotlin
// agentle.android.library / agentle.android.application convention plugins
android {
    testOptions {
        unitTests.isIncludeAndroidResources = true                    // required by Robolectric [ROBO-AND]
        unitTests.all { test ->
            test.systemProperty("robolectric.alwaysIncludeVariantMarkersInTestName", "true")  // exact per-SDK names [ROBO-SRC]
            test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")                  // screenshot accuracy [RZ]
            test.systemProperty("robolectric.enabledSdks",                                      // the SDK matrix [ROBO-CFG][ROBO-SRC]
                providers.gradleProperty("robolectricSdks").getOrElse("29,30,31,33,34,35,36,37"))
            test.systemProperty("robolectric.offline", "true")                                  // hermetic CI [ROBO-CFG]
            test.systemProperty("robolectric.dependency.dir", rootProject.file(".robolectric-jars").path)
        }
    }
}
// robolectric.properties is GENERATED into a test resource directory (not checked in), so one property switches the
// default SDK: sdk=${robolectricDefaultSdk ?: 37}. `-ProbolectricSdks=37` gives a fast single-SDK local loop.

// agentle.jvm.library: JUnit 6 on the JUnit Platform [DOC07][PROTO-J6]
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
```

Every JVM module also gets `src/test/resources/junit-platform.properties` (generated or shared) with
`junit.jupiter.params.displayname.default = {displayName}[{index}] {argumentsWithNames}`, so parameterized test names
are unique in the XML (section 14.1). [PROTO-J6]

Offline mode needs the `android-all-instrumented` jars for every SDK in the matrix pre-fetched into `.robolectric-jars`
(or the Gradle cache in CI); without them Robolectric downloads about 10 jars (90-227 MB each, [DOC07] section 6) on
the first run. [ROBO-CFG]

What happens when `-ProbolectricSdks=37` removes the only SDK a test pins (for example `@Config(sdk = [29])`) is
UNVERIFIED: the picker returns an empty list [ROBO-SRC], and whether the runner then skips or fails was not tested.

### 4.5 Instrumented

```bash
./gradlew :app:connectedFakeDebugAndroidTest                      # any running emulator/device, including an API 26 AVD
./gradlew :app:pixel9Api37FakeDebugAndroidTest                    # one managed device: <device><Variant>AndroidTest
./gradlew :app:journeysGroupFakeDebugAndroidTest                  # a group: <group>Group<Variant>AndroidTest
./gradlew :app:journeysGroupFakeDebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
./gradlew :app:journeysGroupFakeDebugAndroidTest -Pandroid.experimental.androidTest.numManagedDeviceShards=2
./gradlew :app:connectedFakeDebugAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.annotation=dev.agentle.testing.Journey
```
[GMD][RUNNER][AJR][TEST-CLI]

Journeys run on `fakeDebug` because that variant already contains the in-process fakes and the debug-only token
issuers (section 5.6); `prodDebug` gets one smoke test (the app starts, the DI graph resolves, every bound base URL is
`https` and not loopback, as [DOC07] 8.4 guard 2 requires).

### 4.6 Running tests in this container

```bash
cd <prototype root> && ./gradlew --no-build-cache cleanTest check koverXmlReport \
    :core:features:koverXmlReportNormalization :core:features:koverXmlReportDaily \
    :testing:db-scale:dbScaleTest -PdbScaleSizes=100000,500000,1000000
./gradlew --stop
```
This is the exact command of the final prototype run (section 14.5). [PROTO]

---

## 5. Fake servers

### 5.1 Packaging: one module, every tier

**Decision (recommended to [DOC07]):** one JVM module `:fakes:servers` (the prototype's `:testing:fixtures`), package
root `dev.agentle.fakes`.

| Package | Content (prototype file in section 16) |
|---|---|
| `dev.agentle.fakes` | `ScenarioDispatcher` (scenario routing, per-(scenario, path) attempt counters, request journal) and `FakeServers` (one loopback `MockWebServer` hosting both fakes) |
| `dev.agentle.fakes.googlehealth` | `FakeGoogleHealthServer`, the V2 filter parser, `GoogleHealthHygiene` (H-checks) |
| `dev.agentle.fakes.chatgpt` | `FakeChatGptServer` (discovery, JWKS, authorize, token, revoke, models, Responses SSE), RS256 signing |
| `dev.agentle.fakes.synth` | `SplitMix64`, `SyntheticUser` (section 6) |
| resources `googlehealth/` | the 21 DOC05 fixtures, one file per fixture id, lower-cased, as DOC05 8.1 asks (`f-identity.json`, `e404-html.html`, ...) |

Dependencies: `api(mockwebserver3)` and `api(kotlinx-serialization-json)`. No JUnit, no Android, no client DTOs: the
fakes encode the documented contract independently, so a DTO bug shows up as a failing test instead of being mirrored
([DOC07] 8.5).

| Consumer | Configuration | Purpose |
|---|---|---|
| `:connectors:googlehealth`, `:ai:openai`, `:core:network`, `:insights` | `testImplementation` | contract and client tests (5.7) |
| `:core:testing` | `api` | re-exports the fakes and the generator to every test |
| Android modules (Robolectric) | `testImplementation(project(":core:testing"))` | screens and workers against loopback fakes |
| `:app` | `fakeImplementation` | in-app fake mode (5.6) |
| `:app` instrumentation | nothing extra on `fakeDebug` (the app already contains the fakes) | journeys (section 12) |

Why one module instead of DOC07's `:fakes:googlehealth-server` + `:fakes:chatgpt-server`: both fakes share the
dispatcher base, router and transport-fault helpers; DOC06 9.1 already expects one MockWebServer for two hosts; the
fake flavor needs both anyway; and DOC07 asks for few modules on a 4-CPU machine. If the integrator keeps the split,
add `:fakes:core` for the shared base and the generator (three modules instead of one; nothing else changes).

Why the generator lives here and not in `:core:testing`: the Google Health fake serves synthetic data at runtime in
the `fake` flavor, and `:core:testing` carries test libraries (coroutines-test, Turbine, Truth) that must not land on
the `fakeDebug` runtime classpath.

**Amend DOC07's forbidden-dependency rule.** It currently says "No module except `:app` depends on `:fakes:*`, and
`:app` only through `fakeImplementation`", which also forbids the test use that DOC07 8.4 prescribes. Proposed text:
`:fakes:*` may appear only on (a) test configurations of any module (`testImplementation`,
`androidTestImplementation`) and (b) `fake*` configurations of `:app`; it must never appear on any `prod*` runtime
classpath (`verifyNoFakesInProd`) or in a non-test `implementation`/`api` of any other module.

DOC05 8.8 also sketches an OkHttp `Interceptor` for the in-app mode. That is fine for Google Health alone, but the
in-app mode needs real sockets for the SIWC loopback hop and for transport faults (5.5), so MockWebServer is used in
every tier.

### 5.2 Topology and scenario selection

Each fake extends `ScenarioDispatcher`; `FakeServers` binds one `MockWebServer` to `127.0.0.1` and routes by the first
path segment. Two ways to pick a scenario, both implemented and tested [PROTO]:

| Mode | Base URL given to the client | Scenario comes from | Used by |
|---|---|---|---|
| Scenario prefix | `http://127.0.0.1:P/fake-googlehealth/scenario/rate-limited/` (requests continue with `v4/...`); ChatGPT `http://127.0.0.1:P/fake-chatgpt/scenario/<name>/`, auth base `<that>auth`, API base `<that>api/v1` | the URL path | JVM and Robolectric tests that exercise many scenarios on one server |
| Production-shaped | `http://127.0.0.1:P/` (paths `/v4/...`, `/auth/...`, `/api/v1/...`), as DOC05 8.1 and DOC06 9.1 prescribe | `fake.defaultScenario`, set by the test or the debug menu | DOC05/DOC06 contract suites, instrumentation, in-app fake mode |

`GET /fake-googlehealth/scenarios` and `GET /fake-chatgpt/scenarios` list the names; an unknown scenario returns 400;
an unknown service returns 404. Attempts are counted per (scenario, path), so "fail the first N calls" stays
deterministic when tests share a server. Every call is journaled (`requestsFor(scenario)`, `reset()`).

Client wiring rules, so the same production client works in both modes:
- The base URL is injected (`@Named("googleHealthBaseUrl")`, DOC05 8.1; `authBaseUrl`/`apiBaseUrl`, DOC06 9.1) and
  ends with `/`: Retrofit rejects a base URL without the trailing slash. [RETROFIT]
- Retrofit paths are relative (`@GET("v4/users/me/identity")`, no leading `/`): "Endpoint values which contain a
  leading / are absolute" and keep only the host, which would drop the scenario prefix. [RETROFIT]

### 5.3 Google Health API fake ([DOC05] section 8)

**Order of checks** (DOC05 8.1, implemented): route (no match: E404-HTML with the path substituted) -> injected scenario
faults -> auth (401) -> scope (403) -> account state (400 not linked, 412, 403 legacy) -> V9 rate limit (300 requests per
token per rolling 60 s, E429) -> validation (400) -> data. `transport()` then applies the slow, disconnect and stall
faults to 200 responses.

| DOC05 item | Prototype status |
|---|---|
| Endpoints 1-3 (identity, settings, profile; `{user}` = `me` or `1234567890`) | Yes, fixtures served verbatim |
| Endpoint 6 list, rules V1-V5 | Yes, model mode over the synthetic user (steps, heart rate, sleep, exercise; 14 v1 types routed; floors and total-calories not listable); page sizes (default 1440/25, max 10000/25, negative -> 400); `dataSourceFamily` (invalid, or on sleep -> 400); V2 filter grammar with one detailed reason per rule; V4 page tokens bound to method, path and query (mismatch -> E400-PAGE-TOKEN); newest first; empty -> `{}` |
| Endpoint 9 dailyRollUp, rules V5 and V7 | Yes for steps: inclusive end date, at most 90 days (14 for heart-rate, total-calories, active-minutes, calories-in-heart-rate-zone), `windowSizeDays`, civil days in the user's zone, leading-zero JSON -> E400-BAD-JSON; non-POST -> HTML 404 after auth (MODELED, DOC05 U25) |
| Endpoint 12 (writes, `/devices`, `POST ...:reconcile`) | Yes: E404-HTML; H11 flags write methods |
| Endpoints 4, 5, 7, 8, 10, 11 (pairedDevices, device by id, reconcile, rollUp, data point by id, TCX) | Not yet (5.8) |
| Token conventions: `fake-valid`, `fake-scope-<suffix>[,...]`, `fake-expired`, `fake-revoked`, unknown, `fake-not-linked`, `fake-no-profile`, `fake-legacy`, no header, `key=` | Yes |
| Replay mode and the `inject(times, fixtureId, ...)` API | Not yet; scenarios cover what the client suite needs first |
| Request journal and hygiene checks | H1, H2, H3, H4, H5, H6, H10, H11 (H7-H9 not yet) |

**Scenario catalog (19)**, each mapped to DOC05's scenario matrix (8.7) and robustness fixtures (8.6). The expected
client behavior is DOC05's; the table only says what the fake does.

| Scenario | DOC05 rows | What the fake does |
|---|---|---|
| `happy` | S01, S07, S13/S16/S17 | Model mode over the synthetic user |
| `empty` | S37, R3 | `{}` as a valid empty result |
| `small-pages` | S07, R8h, R8b | Pages capped at 50 points; the end marker is omitted |
| `rate-limited` | S29 | E429-B without `Retry-After`, from the 3rd request on |
| `rate-limited-retry-after` | S28 | E429-A with `Retry-After: 7` once, then 200 |
| `token-expired` | S21 | E401-INVALID once, then 200 |
| `token-revoked` | S22 | E401-INVALID always (client must reach NeedsReauth) |
| `permission-denied` | S23 | E403-SCOPE-A on steps only; other types succeed |
| `server-flaky` | S32, R8e | E503 twice, then 200 |
| `bad-gateway-html` | S32 | E502 (HTML) once, then 200 |
| `captive-portal` | R2, S36 | HTTP 200 `text/html` |
| `throttled` | (none) | Correct body, delivered at 1 KiB per 100 ms |
| `disconnect-mid-body` | R1b, S36 | Socket closed mid-body on the 1st attempt |
| `timeout` | S33 | Headers never arrive |
| `malformed` | R1a, S36 | Truncated JSON with HTTP 200 |
| `sleep-filter-rejected` | S26 | Sleep filter -> E400-FILTER-B; unfiltered sleep still works |
| `account-not-linked` | S02, S35 | E400-ACCOUNT-NOT-LINKED on every call |
| `profile-not-ready` | S03 | E412 |
| `legacy-fitbit-account` | S04 | E403-LEGACY-A |

**Hygiene checks** (`fakes.health.hygieneViolations()`, run in `@After`/`@AfterEach` of every client test; DOC05
8.7): H1 Bearer token, `Accept: application/json`, no API key; H2 no `startTime`/`endTime` parameters; H3 filters in
snake_case with only `>=` and `<` and no `OR`; H4 no list call on floors or total-calories; H5 no `dataSourceFamily` on
the sleep list; H6 `pageSize` at most 25 for sleep and exercise; H10 at most N requests per access token in any 60 s
window (default 200); H11 no write methods. The prototype test feeds deliberately bad requests and expects exactly
H1, H2, H3, H4, H5, H6, H11, and H10 with a budget of 5. [PROTO]

**Fixtures.** `tools/extract_doc05_fixtures.py --doc docs/research/05-google-health-and-health-connect.md --out
<module>/src/main/resources/googlehealth [--check]` copies the fenced bodies of 21 fixtures byte-for-byte: F-IDENTITY,
F-SETTINGS, F-PROFILE, E400-INVALID-ARGUMENT, E400-FILTER-B, E400-ACCOUNT-NOT-LINKED, E400-PAGE-TOKEN, E400-BAD-JSON,
E401-MISSING, E401-INVALID, E401-APIKEY, E403-SCOPE-A, E403-LEGACY-A, E403-LEGACY-B, E404-HTML (1,575 bytes), E404-JSON,
E412, E429, E500, E502, E503. `--check` fails if DOC05 and the resources differ; it ran clean on 2026-10-02 ("checked
21 fixtures"). Success list bodies (F-STEPS-P1 ...) are not copied, because model mode generates the same shapes from
the synthetic user; replay mode would add them. DOC05's test rule applies everywhere: assert status, `error.status`,
`ErrorInfo.reason` and metadata, never the `message` of a DOC or MODELED body. [PROTO][DOC05]

Resources are loaded with `getResourceAsStream`, so the same module works inside an APK (in-app fake mode).

### 5.4 Sign in with ChatGPT fake ([DOC06] section 9)

| Endpoint (DOC06 9.2) | Fake behavior |
|---|---|
| `GET {auth}/.well-known/openid-configuration` | `issuer` = the auth base (or `https://auth.openai.com` in `discovery-issuer-mismatch`); `token_endpoint_auth_methods_supported ["none"]`, `id_token_signing_alg_values_supported ["RS256"]` |
| `GET {auth}/.well-known/jwks.json` | RSA 2048 key `kid = fake-key-1` (503 in `jwks-unavailable`) |
| `GET {auth}/api/accounts/authorize` | Validates `redirect_uri` (bad: 400, no `Location`), `client_id` (`dynamic_agent_client` or `oaiapp_fakeA1`), `response_type`, `scope` with `openid`, `resource`, `state`, `nonce`, S256 `code_challenge`, `ext_agent_host_id`; 302 to the loopback callback with `code`, `state` and the issued `client_id` (omitted in `registration-incomplete`); codes expire after 60 s |
| `POST {auth}/api/accounts/oauth/token` | Form encoding required; code exchange checks client, code (single use), PKCE and redirect; issues `at_n`, `rt_n` (only with `offline_access`), an RS256 `id_token` (iss, aud, sub `user_fake_sub_1`, nonce, exp), all six scopes, `earliest_refresh_at`. Refresh rotates the pair; reuse of an old refresh token -> `refresh_token_reused` |
| `POST {auth}/api/accounts/oauth/revoke` | 200 with an empty body (503 in `revocation-failure`) |
| `GET {api}/v1/models` | Bearer required; 500 in `server-error` |
| `POST {api}/v1/responses` | Enforces `stream: true`, `store: false`, `input` as an array, the forbidden-field list, no `system` role, no image-generation tool, no `json_schema` format (each with `param`); streams `response.created`, two `response.output_text.delta` events, `response.completed` |

**Scenario catalog (28)**, mapped to DOC06's rows (9.3-9.4):

| Scenario | DOC06 row |
|---|---|
| `happy` | 9.2/9.3 success payloads (registration, exchange, refresh, stream, models, revoke) |
| `consent-denied` | Login cancelled (consent denied) |
| `invalid-state` | Invalid state (the first callback carries `state=TAMPERED`) |
| `registration-incomplete` | Registration incomplete (no `client_id` on the callback) |
| `plan-scope-declined` | Plan usage not granted (token scope without `chatgpt.tokens.use.direct`) |
| `discovery-issuer-mismatch` | Discovery variant: issuer mismatch, fail closed |
| `jwks-unavailable` | JWKS outage (503), `identity_verification_unavailable` |
| `refresh-invalid-grant` | Refresh failure (terminal): `invalid_grant` |
| `refresh-transient` | Refresh failure (transient): 503 `temporarily_unavailable` once |
| `expired-access-token` | Expired access token: 401 `token_expired` once, then 200 |
| `usage-limit` | Rate limited: 429 `subscription_sharing_usage_limit_exceeded` |
| `not-eligible` | Not eligible: 403 `subscription_sharing_user_not_eligible` |
| `unavailable` | Plan usage unavailable: 503 `subscription_sharing_usage_unavailable` |
| `invalid-user` | Account disconnected: 401 `subscription_sharing_invalid_user`, then refresh `invalid_grant` |
| `grant-not-authorized` | Grant not authorized: 403 `chatpass_v2_scope_not_authorized` |
| `unsupported-capability` | Unsupported capability: 400 with `param` |
| `admission-401`, `admission-403`, `admission-503` | Admission errors with a `{"detail": ...}` body |
| `server-error` | Server error: 500 on `/responses` and `/models` |
| `mid-stream-usage-limit` | Rate limited, mid-stream `response.failed` variant |
| `incomplete` | Stream ends with `response.incomplete` |
| `error-event` | An `error` event in the stream |
| `stream-cut` | Socket closed mid-body |
| `slow-stream` | Slow but complete stream |
| `stall` | Headers never arrive (client read timeout) |
| `no-content-type` | Valid SSE without `Content-Type` (DOC06 4.3) |
| `revocation-failure` | Revocation failure: 503 |

**Browser hop** (DOC06 9.1): tests capture the URL the app hands to its `BrowserLauncher`, call
`FakeChatGptServer.browserHop(client, authorizeUrl)` (GET with redirects off, returns `Location`) and send that GET to
the app's real `LoopbackCallbackServer`. No device or browser is needed (JVM or Robolectric). The loopback listener is
app code; its own contract (DOC06 9.5: 200 on a valid callback, 400 on a wrong, duplicate or missing `state`, 404 on a
wrong path, method or `Host` or after settling, idle connections closed) is tested against the app class with a plain
HTTP client in `:ai:openai`.

### 5.5 Transport faults (both fakes)

| Fault | mockwebserver3 mechanism [MWS] | Scenarios |
|---|---|---|
| Slow body | `throttleBody(bytes, period, unit)` | `throttled` (1 KiB / 100 ms), `slow-stream` (64 B / 50 ms) |
| Disconnect mid-body | `onResponseBody(SocketEffect.CloseSocket())`; fires at half of the body | `disconnect-mid-body` (1st attempt only), `stream-cut` |
| Headers never arrive | `onResponseStart(SocketEffect.Stall)` | `timeout`, `stall` |
| Truncated JSON with 200 | body cut by the fake | `malformed` |
| Captive portal | 200 `text/html` | `captive-portal` |
| Missing content type | header omitted | `no-content-type` |

Tests use short client timeouts (the prototype uses a 2 s read timeout, and 300 ms in the stall tests), so stall
scenarios cost under a second. [PROTO]

### 5.6 In-app fake mode (`fakeDebug`)

- **Variant:** DOC07's `fake` flavor (`applicationIdSuffix ".fake"`, own label and icon; `fakeRelease` disabled;
  cleartext allowed only for `127.0.0.1` in `src/fake/res/xml/network_security_config.xml`).
- **Boot:** the `src/fake` `EndpointsModule` starts `FakeServers().start()` off the main thread (it opens a socket) and
  binds every base URL to `fakes.rootUrl()`, so the app sends production-shaped paths. Scenarios change at runtime
  through `defaultScenario`, without rebuilding Retrofit.
- **Tokens:** Google's `AuthorizationClient` is not used in the fake flavor. A debug token issuer hands out DOC05's
  tokens (`fake-valid`, `fake-scope-sleep.readonly`, `fake-revoked`, ...), chosen in the debug menu ([DOC07] 8.4:
  "fake token issuers").
- **SIWC:** the default `BrowserLauncher` in `fakeDebug` performs the browser hop in-process (works on images without
  Chrome); a debug toggle switches to a real Custom Tab for manual checks. Whether Chrome on the device reaches the
  app's `127.0.0.1` port is UNVERIFIED.
- **Clock:** the synthetic user lives in 2026-08-10..2026-11-07 (section 6). The debug time travel of [DOC07] 8.4
  defaults to 2026-11-07 21:00 America/New_York in the fake flavor, so the hub shows all 90 days.
- **Debug menu:** pick scenarios per fake (from `GET /fake-*/scenarios`), the token, and the "now" instant; show the
  request journal and hygiene violations.
- **Instrumentation:** a `@HiltAndroidTest` injects `FakeServers` from the app graph and sets `defaultScenario` in
  `@Before`. A scenario can also come from the command line, `-Pandroid.testInstrumentationRunnerArguments.
  fakeHealthScenario=rate-limited`, read with `InstrumentationRegistry.getArguments()` (custom argument pass-through is
  UNVERIFIED; [RUNNER] documents the pattern for `size`).

### 5.7 Contract suites the real clients must pass

| Suite | Module | Content |
|---|---|---|
| Google Health contract | `:connectors:googlehealth` (JVM, JUnit 6) | One test per DOC05 S-row (S01-S40) and R-fixture where a scenario exists; fixed clock `2026-10-01T12:00:00Z`; page sizes 3 (DOC05 8.1); every test ends with the hygiene check |
| SIWC contract | `:ai:openai` (JVM, JUnit 6) | DOC06 9.2-9.4 rows: discovery, browser hop, exchange, `id_token` verified against the fake JWKS, refresh rotation, all failure scenarios, SSE parsing of every stream variant; the loopback listener contract (9.5) |
| Single-flight refresh | `:core:network` | DOC02 T-TOK-01..03: 50 concurrent callers with an expired token -> exactly one refresh (`fakes.chatGpt.refreshCount() == 1`); stale 401 after a refresh -> no second refresh; reuse of the old refresh token -> `refresh_token_reused` -> terminal `NEEDS_REAUTH` |
| Fakes self-tests | `:fakes:servers` | The prototype's 40 tests (section 16) |

The prototype also proves a JUnit 6 module can drive the fakes through the `@StartStop` extension, TestParameterInjector
and `@ParameterizedTest` (53 tests) [PROTO-J6].

### 5.8 Gaps to close before the client suites need them

- Google Health: replay mode; `inject(times, fixtureId, retryAfter?, delay?, matcher)`; endpoints 4, 5, 7, 8, 10, 11;
  hygiene H7-H9; `OR` handling for sleep filters; types beyond steps, heart rate, sleep and exercise; E429-C/D variants;
  versioned datasets for R7 (data changing between syncs).
- ChatGPT: `id_token` on refresh; account-mismatch variants.
- Both: a recorded-response (sanitized) golden set once live access exists (DOC05 7.9).

---

## 6. Synthetic data generator

### 6.1 Design

| Requirement | How the generator meets it |
|---|---|
| Same data on every JVM, Kotlin version and device | Own `SplitMix64`; never `kotlin.random.Random(seed)`, which is only stable "within the same version of Kotlin runtime" [KRANDOM]; a SHA-256 fingerprint is pinned as a golden value |
| Changing one stream or day never shifts others | The RNG is forked by label: `fork("days")`, then `fork("day-<local date>")`, then `fork("minutes")` |
| Travel and DST are exact | Time is walked in absolute minutes; the zone is a function of the instant (home or trip zone), so a trip never duplicates or drops instants, and DST days naturally have 23 or 25 local hours; each date's routine is planned in the zone where the user is at local noon |
| Session events carry the right offset | Sleep, exercise and off-wrist sessions carry the zone in force when they start, as a device would record them |

### 6.2 The default 90-day user (`SynthSpec(seed = 42)`)

| Aspect | Value |
|---|---|
| Window | 2026-08-10 .. 2026-11-07, home zone America/New_York |
| Weekday / weekend sleep | Bed about 23:30, wake about 06:45 / bed about 00:30, wake about 08:30 (jitter -25..+25 and -20..+20 minutes) |
| Exercise days | Monday, Wednesday, Saturday; 30-60 min at 18:00 (10:00 on Saturday); 150 +/- 12 steps/min; heart rate 140 +/- 10 |
| Sedentary days | 35% of non-exercise days; 6% of minutes carry 25 +/- 8 steps |
| Heart rate | Per-day resting rate N(60, 3); asleep: rest - 6 (sd 2); awake: rest + 15 (sd 6); one sample every 5 minutes |
| Missing wearable periods | Day indexes 20-21 (2026-08-30 and 2026-08-31): no wearable data at all; phone steps continue |
| Device not worn | Nightly off-wrist for 60-90 min, starting 19:00-21:00 local |
| Phone vs wearable | The phone counts about 90% of steps, also while the watch is off or missing (fusion tests) |
| Travel | Departs Tue 2026-10-20 18:00 New York, returns Thu 2026-10-29 10:00 Berlin (days 71-80) |
| DST | EU DST ends 2026-10-25 while the user is in Berlin (25-hour local day); US DST ends 2026-11-01 at home (25-hour day); spring-forward (23-hour day) is tested with a separate spec on 2027-03-14 |

These dates agree with DOC10's verified transition instants (Berlin 2026-10-25T01:00Z, New York 2026-11-01T06:00Z).
[DOC10]

### 6.3 Profiles and golden values

| Profile | Definition | Events (seed 42) |
|---|---|---|
| `TYPICAL` | as above | **57,519** (golden), SHA-256 `cc78bf369e8bed969dd426ff0f364bfa350a3a1fead9d4c686c9b501bda124f2` |
| `EMPTY` | no events (a new user, or a user who connects nothing) | 0 |
| `SPARSE` | heart rate hourly; about 10% of days produce any data | 1,556 events on 6 days |
| `HIGH_VOLUME` | heart rate every minute | 153,505 |

The DB ladder (7.4) draws its 100k/500k/1M events from `HIGH_VOLUME` with seed 99 and a longer window. Golden policy: a
change to the generator that moves the golden count or hash is a reviewed change; it also invalidates downstream
goldens (feature values, screenshots), which are re-recorded in the same PR. [PROTO]

### 6.4 Invariant tests (8, all passing)

Same seed gives the same fingerprint, a different seed a different one; golden count and hash; both fall-back days have
25 local hours of samples minus off-wrist time, in the zone the user is in; the spring-forward day has 23; a trip never
duplicates or reorders phone-minute instants and uses only the trip zone between departure and return; gap days have
phone steps but no wearable data; weekend wake-up is at least 60 minutes later and exercise happens only on Monday,
Wednesday and Saturday; profiles scale as designed (empty is empty, sparse covers 3-25 days, high volume has more than
twice the typical count). [PROTO]

### 6.5 Consumers

- L1: feature and JITAI tests adapt `SynthEvent` to domain types (the prototype's `SynthAdapter`), for example "steps
  on the 25-hour Berlin day equal the fused per-minute sums".
- L2/L3: the Google Health fake maps synthetic events to DOC05 data-point shapes (steps with an interval and a string
  count; heart rate with `sampleTime.physicalTime` and `utcOffset` like `"-14400s"`; sleep `CLASSIC` with
  `minutesInSleepPeriod`; exercise `WALKING`), and dailyRollUp sums civil days in the user's zone.
- Screenshots: the same seed gives the same pixels, so goldens stay stable.
- In-app fake mode: the clock pin of 5.6.

### 6.6 Extensions

DOC10's other zones (America/Santiago midnight gap, Australia/Lord_Howe 30-minute shift) as extra specs; more types
(distance, active energy, weight, daily resting heart rate, HRV) when the connector reads them; duplicate devices for
dedupe tests; late-arriving data for DOC05 R7.

---

## 7. Room, SQLite and database scale

### 7.1 Where DAO tests run

| Option | How | For | Against |
|---|---|---|---|
| **A (recommended)** | `:core:database` built with `com.android.kotlin.multiplatform.library` plus `jvm()`; DAO and migration tests in `jvmTest` with `BundledSQLiteDriver` | Google's recommendation [ROOM-TEST]; the same SQLite as production ([DOC07] driver policy); fast; no Robolectric | Module uses the KMP plugin (single variant, which is fine for a library) [KMP-AND]; Room 3 KSP per target; UNVERIFIED end to end here (Google Maven) |
| B | Android library, Robolectric, `AndroidSQLiteDriver` ([DOC07] W7) | Simple build | Google advises against it [ROOM-TEST]; tests run Robolectric's host SQLite, not the production driver |
| C | Instrumented DAO tests: in-memory DB with `BundledSQLiteDriver` [ROOM-TEST] | Real device, real native library | Slow; needs an emulator |

Use A on every PR, and C nightly on the managed devices (one test class that opens the bundled driver and runs a
migration and a few DAO calls), because only a device proves the native library loads on each API level and ABI.
Robolectric tests elsewhere that need a database (for example a worker test) use the in-memory factory of
`:core:testing-android` with `AndroidSQLiteDriver`, as DOC07 W7 says.

### 7.2 Migrations and exported schemas

- `room3 { schemaDirectory("$projectDir/schemas") }` exports one JSON per version ([DOC07] 8.3); the files are committed.
- JVM constructor: `MigrationTestHelper(schemaDirectoryPath, databasePath, driver, databaseClass, ...)`; Android
  constructor: `MigrationTestHelper(instrumentation, file, driver, databaseClass, ...)`. `createDatabase(version)` and
  `runMigrationsAndValidate(version, migrations)` are `suspend`, so tests wrap them in `runTest`. [MTH3][DOC07]
- Tests: every released version migrates to the latest and validates; rows inserted at the old version (taken from the
  synthetic user) survive; auto-migrations included; a check fails CI if a version in the `@Database` history has no
  schema file.
- For option C, the schema directory is added to the instrumented test assets (exact AGP 9 DSL UNVERIFIED, as [DOC07]
  section 4 notes).

### 7.3 SQLite version per API level

From [A-SQLITE] (a level missing from the table uses the next lower one listed):

| API | 26 | 27 | 28 | 29 | 30 | 31-33 | 34 | 35 | 36.1 | 37 | JVM test (sqlite-jdbc) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| SQLite | 3.18 | 3.19 | 3.22 | 3.22 | 3.28 | 3.32 | 3.39 / 3.42 | 3.44 | 3.50 | 3.50 | 3.53.4 [PROTO] |

With the framework driver, SQL must run on 3.22 (the minSdk 29 floor). Which newer syntax that rules out is
UNVERIFIED here (`sqlite.org` is unreachable); the bundled driver avoids the question. The SQLite version inside
`androidx.sqlite:sqlite-bundled` 2.7.1 is UNVERIFIED.

### 7.4 Scale ladder

Design: the planned events-table shape (`UNIQUE(source, type, start_ms)` natural key, index on `(type, start_ms)`, WAL,
`synchronous=NORMAL`), batched `INSERT OR IGNORE` in transactions of 10,000 rows, then re-ingest of the first 10% (the
Google Health API has no change feed, so overlapping windows are re-read). The suite asserts **query plans and
invariants** (index used, no full scan, no temp B-tree for "latest heart rate", row count unchanged after re-ingest)
and records timings with a loose 250 ms budget; timings vary between runs and are not gates. [PROTO]

Results (JVM 21.0.11, SQLite 3.53.4, run of 2026-10-02T05:06Z; median of repeated queries):

| Events | Insert ms | Events/s | Re-ingest 10% ms | 7-day steps ms | Latest HR ms | 7-day HR range ms | Full scan ms | DB MiB |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100,000 | 1903 | 52,548 | 57 | 0.705 | 0.027 | 1.631 | 4.935 | 14.7 |
| 500,000 | 2289 | 218,435 | 116 | 0.631 | 0.025 | 1.710 | 27.985 | 73.6 |
| 1,000,000 | 4407 | 226,911 | 260 | 0.624 | 0.016 | 1.579 | 58.454 | 147.9 |

(The 100k insert time includes JIT warm-up; earlier runs measured 532-976 ms for the same step.) On devices the same
ladder runs nightly as an instrumented test on the API 29 and API 37 managed devices, reported but not gated.

---

## 8. Coroutines, WorkManager, Hilt, Compose and the clock

### 8.1 One time source

`AgentleClock` (wall clock + zone + monotonic `elapsed()`) is the only time API ([DOC07] 8.6). Tests use
`TestAgentleClock` (`advanceBy`, `setWallClock`, `setZone`), backed by `TestCoroutineScheduler.currentTime` in
coroutine-heavy tests so there is one virtual time source. WorkManager gets the same time through
`Configuration.Builder.setClock()`; do not use WorkManager's own `TestClock`, which is `@RestrictTo` ([DOC02] 7.0).
detekt `ForbiddenMethodCall` keeps wall-clock calls out of other modules ([DOC07] 8.6). The prototype's virtual-day
JITAI test delivers at most once per 90 minutes over a simulated day without sleeping. [PROTO]

### 8.2 Coroutines and Flow

- `runTest` for every coroutine test; it uses `StandardTestDispatcher` by default and skips delays. [CORO-TEST]
- Inject dispatchers; create every `TestDispatcher` with the test's `testScheduler` so `advanceUntilIdle()` drives all
  of them. [CORO-TEST]
- `MainDispatcherRule` (a `TestWatcher` calling `Dispatchers.setMain`/`resetMain`) for ViewModel tests; create it first
  and reuse its scheduler. [CORO-TEST]
- `UnconfinedTestDispatcher` only for simple collection; concurrency tests use `StandardTestDispatcher`. [CORO-TEST]
- Turbine for Flow assertions. [MC]

### 8.3 WorkManager

`WorkManagerTestInitHelper.initializeTestWorkManager(context, config)` with `SynchronousExecutor`, then the
`TestDriver` (`setAllConstraintsMet`, `setInitialDelayMet`, `setPeriodDelayMet`) to release work deterministically;
`TestListenableWorkerBuilder` / `TestWorkerBuilder` for single workers. [WM-IT] The concrete tests are DOC02's T-WM-01
to T-WM-09 (unique work, constraints per profile, expedited checks, periodic ticks, DST pinning, content triggers,
retries, stop reasons, foreground info) and run under Robolectric at the DOC02 SDK list. [DOC02]

### 8.4 Hilt

`@HiltAndroidTest` + `HiltAndroidRule`; Robolectric tests use `@Config(application = HiltTestApplication::class)`;
instrumentation uses a custom runner that overrides `newApplication` to return `HiltTestApplication`. [HILT-T]
Replacements:

| Binding | Test replacement |
|---|---|
| `EndpointsModule` (base URLs) | `@TestInstallIn(components = [SingletonComponent::class], replaces = [EndpointsModule::class])` pointing at `FakeServers` |
| `ClockModule` | `@TestInstallIn` with `TestAgentleClock` ([DOC07] 8.6 rule 6) |
| Per-test values (a token provider, a feature flag) | `@BindValue` fields; `@UninstallModules` for one-off replacements [HILT-T] |
| Dispatchers | a `@TestInstallIn` module that binds the `MainDispatcherRule` dispatcher |

Hilt's compiler for tests is added with `kspTest`/`kspAndroidTest` (the guide's snippet shows `kspTest(...:2.57.1)`;
use DOC07's 2.60.1). [HILT-T]

### 8.5 Compose UI tests under Robolectric

- v2 rules only (`androidx.compose.ui.test.junit4.v2`). [CMP-V2]
- Time: `mainClock.autoAdvance = false`, then `advanceTimeByFrame()`, `advanceTimeBy(ms)` or
  `advanceTimeUntil(timeoutMs) { condition }`; for real asynchronous work `waitUntil(timeoutMs) { ... }` and the
  `waitUntilAtLeastOneExists`/`waitUntilDoesNotExist`/`waitUntilExactlyOneExists`/`waitUntilNodeCount` helpers, never
  `CountDownLatch`. [CMP-SYNC]
- Streaming text (SSE insight, J6): drive the fake's `slow-stream` scenario and step the main clock between deltas.
- Accessibility: `enableAccessibilityChecks()` from `ui-test-junit4-accessibility` on every screen test, and
  `roborazzi-accessibility-check` on screenshot tests. [CMP-A11Y][RZ]

---

## 9. Screenshot matrix (Roborazzi)

| Axis | Values | Mechanism |
|---|---|---|
| Device | Small phone, medium phone, medium tablet | `RobolectricDeviceQualifiers.SmallPhone/MediumPhone/MediumTablet` [RZ] |
| Theme | light, dark | `RuntimeEnvironment.setQualifiers("+night")` [ROBO-SRC] |
| Font scale | 1.0, 1.3, 2.0 | `RuntimeEnvironment.setFontScale(...)` [ROBO-SRC] |

3 x 3 x 2 = 18 images per screen. Key screens (12): consent, permission primer, capability summary, connect sources,
hub timeline (normal day), hub timeline (25-hour Berlin day), hub timeline (wearable gap day), insights list, insight
detail (stream finished), JITAI card, settings sources, privacy (export/delete): **216 images**, all at SDK 37,
`@GraphicsMode(NATIVE)`, `robolectric.pixelCopyRenderMode=hardware`. [RZ]

- Goldens live in the module (`roborazzi { outputDir.set(file("src/screenshots")) }`) and are reviewed like code. [RZ]
- Data comes from the synthetic user (seed 42) and a fixed `TestAgentleClock`; animations are stopped with the Compose
  main clock.
- `verifyRoborazziDebug` gates PRs; `recordRoborazziDebug` is run on purpose; `compareRoborazziDebug` produces diff
  images for review. Tolerance: start with Roborazzi's defaults; use `changeThreshold` only per screen with a comment.
  [RZ]
- Native graphics output can differ between host OSes; record and verify on the same OS image (CI's Linux image).
  This is a common Roborazzi practice, UNVERIFIED as a statement about 1.76.0.

---

## 10. Coverage gates

Kover 0.9.11 measures JVM tests and Android unit tests (Robolectric) and cannot measure on-device tests [KOVER-DOC].
Per-package rules use `copyVariant(<name>, <provided variant>)` with a package filter. The provided variant is `jvm` in
JVM modules and the AGP variant name in Android modules (`debug`, `prodDebug`) [KOVER-SRC]. Variant rules run on
`check` only with `verify.onCheck = true` (the convention is true only for the total variant) [KOVER-SRC][PROTO].

| Area (task brief) | Module / package | Rule | Task |
|---|---|---|---|
| JITAI | `:jitai:engine`, `:jitai:dsl` (whole module) | line >= 95%, branch >= 90% | `koverVerify` |
| Features | `:features:engine` (whole module) | line >= 90% | `koverVerify` |
| Normalization | `:features:engine`, package `dev.agentle.features.normalization` | line >= 90%, branch >= 85% | `koverVerifyNormalization` |
| OAuth state | `dev.agentle.ai.openai.auth` (SIWC state machine), `dev.agentle.core.network.auth` (single-flight token manager, authenticator), `dev.agentle.connectors.googlehealth.auth` (connection state) | line >= 90% | `koverVerifyOauthState` in each of the three modules |
| Repository | `:data:events`, package `dev.agentle.data.events.repository` | line >= 85% | `koverVerifyRepository` (copy of the `debug` variant) |
| Fakes | `:fakes:servers` | line >= 80% | `koverVerify` |

```kotlin
// :features:engine (JVM). Same shape as the prototype's :core:features, which ran green.
kover {
    currentProject { copyVariant("normalization", "jvm") }
    reports {
        verify { rule("features line >= 90%") { minBound(90, CoverageUnit.LINE) } }
        variant("normalization") {
            filters { includes { packages("dev.agentle.features.normalization") } }
            verify {
                onCheck = true
                rule("normalization line >= 90%") { minBound(90, CoverageUnit.LINE) }
                rule("normalization branch >= 85%") { minBound(85, CoverageUnit.BRANCH) }
            }
        }
    }
}
// :data:events (Android): copyVariant("repository", "debug"), same filter/verify blocks (UNVERIFIED: no Android build here).
```

Prototype results (Kover XML, run of 2026-10-02): JITAI 100% line / 92.86% branch; normalization 100% / 89.29%; daily
features 100% / 100%; fakes 95.99% / 79.87%; generator 98.03% / 97.87%. The gates bite: raising the normalization
branch rule to 95% failed the build with "Rule 'normalization branch >= 95%' violated: branches covered percentage is
89.285700, but expected minimum is 95". [PROTO]

JaCoCo 0.8.15 through AGP's `enableAndroidTestCoverage` [AGP-DSL] can report instrumented coverage for information; it
is not gated. AGP 9.2's experimental unified coverage ([DOC07] 7.2) is an alternative for Android modules
(UNVERIFIED here).

---

## 11. Emulator state matrix (Robolectric vs emulator-only)

Per-state recipes are in [DOC01] 7.2-7.3 (permission resolvers) and [DOC02] 7.0-7.2 (background work); this matrix
says which tier can create each state and what only an emulator shows. Robolectric method names were checked with
`javap` on `shadows-framework-4.17.jar` [ROBO-SHADOWS]; shell commands come from [AOSP] sources unless noted.

| # | State | Robolectric (L3) | Emulator (L4) | Emulator-only aspect |
|---|---|---|---|---|
| 1 | Runtime permission granted, denied, never asked, permanently denied | `shadowOf(app).grantPermissions/denyPermissions`; `shadowOf(packageManager).setShouldShowRequestPermissionRationale`; `shadowOf(activity).getLastRequestedPermission()` | `pm grant/revoke`; `pm set-permission-flags <pkg> <perm> user-set user-fixed`; `pm clear-permission-flags` [ADB][DOC01] | The real dialog, "don't ask again" behavior, one-time grants |
| 2 | Notifications blocked (app or channel) | `shadowOf(nm).setNotificationsEnabled(false)`; channel with `IMPORTANCE_NONE` / `setImportance` | `pm revoke <pkg> android.permission.POST_NOTIFICATIONS` (33+) | The shade, heads-up, channel settings UI |
| 3 | Usage access | `shadowOf(appOps).setMode(...)` (unset ops return `MODE_ALLOWED`: always set `MODE_IGNORED` or `MODE_DEFAULT` in "denied" tests) | `cmd appops set <pkg> android:get_usage_stats allow\|ignore\|default` | Settings screen round trip |
| 4 | Notification listener access, binding, rebind | `setNotificationListenerAccessGranted`; `Robolectric.buildService(...)`; `ShadowNotificationListenerService.addActiveNotification`, `getRebindRequestCount` | `cmd notification allow_listener/disallow_listener <pkg>/<class>`; `cmd notification post ...` | Real binding and rebind after kill ([DOC02] E9) |
| 5 | Exact alarms | `ShadowAlarmManager.setCanScheduleExactAlarms(false)` (static); `getScheduledAlarms` | `cmd appops set <pkg> SCHEDULE_EXACT_ALARM allow\|default` | Alarm delivery under Doze |
| 6 | DND | `setNotificationPolicyAccessGranted(true)` then `nm.setInterruptionFilter(...)` (shadowed) | `cmd notification set_dnd on\|priority\|alarms\|off` [DOC01] | Real suppression of the JITAI notification |
| 7 | Doze (deep, light) | `ShadowPowerManager.setIsDeviceIdleMode`, `setIsLightDeviceIdleMode` | `dumpsys battery unplug`; `dumpsys deviceidle force-idle [light\|deep]`, `step`, `unforce` [DOZE][PWR] | Deferral and maintenance windows ([DOC02] E1) |
| 8 | Battery saver, thermal | `setIsPowerSaveMode`; `setCurrentThermalStatus` | `cmd power set-mode 1`; `cmd thermalservice override-status <0-6>` [DOC01] | Real job throttling |
| 9 | Standby bucket, background restriction | `ShadowUsageStatsManager.setCurrentAppStandbyBucket`; `ShadowActivityManager.setBackgroundRestricted` | `am set-standby-bucket <pkg> rare`; `am set-bg-restriction-level <pkg> background_restricted` | Quotas and bucket decay ([DOC02] E2, E14) |
| 10 | Battery level, charging | `ShadowBatteryManager.setIsCharging/setIntProperty` + sticky `ACTION_BATTERY_CHANGED` | `dumpsys battery set level 15`, `unplug`, `reset`; `adb emu power capacity 15` [EMU-CON] | Constraint scheduling by the system |
| 11 | Battery optimization exemption | `setIgnoringBatteryOptimizations` | `dumpsys deviceidle whitelist +<pkg>` / `-<pkg>` | - |
| 12 | Low-RAM device, memory pressure | `ShadowActivityManager.setIsLowRamDevice`, `setMemoryInfo`, `addApplicationExitInfo` | Small Phone profile (1 GiB); `am memory-limiter ...` on API 37 [DOC01] | Low-memory kills, ANRs ([DOC02] E13) |
| 13 | Process death | Recreate the activity/ViewModel with saved state | `am kill <pkg>` (background) [ADB] | Real restart path, receivers re-registered ([DOC02] E4) |
| 14 | Force-stop | - (only the restart logic, with fakes for exit info) | `am force-stop <pkg>` [ADB] | Pending intents and alarms cancelled ([DOC02] E5) |
| 15 | Reboot | Send `BOOT_COMPLETED` to the receiver | `adb reboot`; fast variant `am broadcast -a android.intent.action.BOOT_COMPLETED <pkg>` [DOC02] | Real boot ordering ([DOC02] E7) |
| 16 | Time zone change, travel | `TestAgentleClock.setZone` + `ACTION_TIMEZONE_CHANGED` | `cmd time_zone_detector set_auto_detection_enabled false`; `cmd alarm set-timezone Europe/Berlin` | System broadcast delivery |
| 17 | DST transition | Clock across DOC10's transition instants | `cmd alarm set-time <epochMs>` just before the transition, zone America/New_York ([DOC02] E8) | `ACTION_TIMEZONE_OFFSET_CHANGED` delivery on API 37 (UNVERIFIED) |
| 18 | Manual clock change | `ShadowSystemClock.advanceBy` + `ACTION_TIME_CHANGED` | `cmd time_detector set_auto_detection_enabled false`; `cmd alarm set-time` [DOC02] | Effect on scheduled work |
| 19 | 12/24-hour format | `ShadowSettings.set24HourTimeFormat` | `settings put system time_12_24 24` [SET-SYS][AOSP] | - |
| 20 | Offline | `ShadowConnectivityManager.setActiveNetworkInfo(null)`, `setDefaultNetworkActive(false)` | `svc wifi disable` and `svc data disable` (wrappers for `cmd wifi set-wifi-enabled disabled` and `cmd phone data disable`); `adb emu gsm data off` [AOSP][EMU-CON] | Constraint-based deferral of real jobs ([DOC02] E10) |
| 21 | Airplane mode | `ShadowSettings.setAirplaneMode(true)` | `cmd connectivity airplane-mode enable` **UNVERIFIED**; fallback Quick Settings via UI Automator | - |
| 22 | Metered network, Data Saver | `ShadowNetworkCapabilities.addTransportType/addCapability`; `ShadowConnectivityManager.setRestrictBackgroundStatus` | `cmd netpolicy set restrict-background true`; `cmd netpolicy set metered-network <id> true` [AOSP] | - |
| 23 | Dark theme | `RuntimeEnvironment.setQualifiers("+night")`; `ShadowUIModeManager.setNightMode` | `cmd uimode night yes\|no` | - |
| 24 | Font scale | `RuntimeEnvironment.setFontScale(2.0f)` | `settings put system font_scale 2.0` [SET-SYS][AOSP] | - |
| 25 | Screen size, density, rotation | Qualifiers (`RobolectricDeviceQualifiers`, `land`) | `wm size 720x1280`, `wm density 320`, `wm size reset`, `wm user-rotation lock 1` [AOSP] | Real window insets and cutouts |
| 26 | Screen off, keyguard | `ShadowPowerManager.setIsInteractive(false)`; `ShadowKeyguardManager.setKeyguardLocked`, `setIsDeviceLocked` | `cmd power sleep/wakeup`, `input keyevent KEYCODE_SLEEP`; `locksettings set-pin 1234` [DOC01] | Notification behavior on the lock screen |
| 27 | Low storage | `ShadowStatFs.registerStats`; `ShadowStorageStatsManager.setStorageDeviceFreeAndTotalBytes` | `cmd devicestoragemonitor force-low`, `reset` | Real write failures |
| 28 | Health Connect permissions | `FakeHealthConnectClient` + `FakePermissionController` [DOC01] | Health Connect permission UI only (`pm grant` for health permissions UNVERIFIED) | Everything about the provider app |
| 29 | Wearable gap, not worn | Synthetic generator (gap days, off-wrist) through the fake | Same, through the in-app fake | - |
| 30 | App update with data | `MigrationTestHelper` (7.2) | `adb install -r` an older build, then the new one | Real upgrade path |

Rows 1-12 and 16-27 can be set at L3, so their app logic is tested on every PR; L4 adds only the column "Emulator-only
aspect". Rows 13-15 and 30 need a real system for anything beyond the restart logic.

---

## 12. Ten critical UI journeys

All journeys run at L3 on every PR (Robolectric, `fakeDebug` or Hilt replacements, the `PlatformMatrix` SDKs where
behavior differs) and at L4 nightly on the managed devices with the `fakeDebug` variant. Instrumented journeys carry an
`@Journey` annotation so they can be selected with `-e annotation` [AJR]. Orchestrator with `clearPackageData` isolates
them [RUNNER].

| ID | Journey | L1-L3 (every PR) | L4 (nightly, 13.1 devices) | Pass criteria |
|---|---|---|---|---|
| J1 | First run: consent -> source picker -> permission primer -> system dialogs (notifications on 33+, activity recognition on 29+) -> capability summary | Compose v2 + Hilt; `grantPermissions/denyPermissions`; `getLastRequestedPermission()`; screenshots + accessibility checks | UI Automator `watchFor(PermissionDialog) { clickAllow() }`, and a deny-twice run with `clickDeny()`; `google` images on API 29, 34, 37 [UIA] | Summary matches DOC01's resolver states; a second denial leads to the Settings deep link, no crash |
| J2 | Special access round trip: usage access and notification listener via Settings | `setMode`/`setNotificationListenerAccessGranted`; assert the Settings intent with `shadowOf(app).getNextStartedActivity()` | `intended(hasAction(...))` for the Settings action [ESP-INT]; grant with `cmd appops set` / `cmd notification allow_listener`; return to the app | State flips on resume; the listener starts recording |
| J3 | Connect Google Health (fake) and first sync | `:connectors:googlehealth` contract (S01, S07, S13-S17); Robolectric hub with `EndpointsModule` replaced and `fake-valid`; WorkManager `TestDriver` runs the sync | Debug token issuer + in-app fake `happy`; hub shows 90 days | Per-day totals equal the generator's; no duplicate rows after a second sync |
| J4 | Google Health failures and recovery | Contract S21-S37; status screen parameterized over `token-revoked`, `rate-limited`, `account-not-linked`, `profile-not-ready`, `legacy-fitbit-account`, `captive-portal`, `malformed` | Debug-menu scenario switch; one run per scenario family | One "Reconnect" notification (fixed ID); backoff honored; no data lost or duplicated |
| J5 | Sign in with ChatGPT | `:ai:openai` contract (all 28 scenarios); Robolectric with a fake `BrowserLauncher` + `browserHop` + the app's loopback server | Stub the Custom Tab with `intending(hasAction(Intent.ACTION_VIEW)).respondWith(...)` and do the hop from the test [ESP-INT]; one `google`-image run with a real Custom Tab | Connected state; tokens stored; denied and tampered-state flows end cleanly |
| J6 | Insight with streamed AI text | SSE parsing of every stream scenario; Compose with `mainClock.autoAdvance = false` stepping through `slow-stream` deltas [CMP-SYNC] | In-app fake, `happy` and `mid-stream-usage-limit` | Text appears incrementally; failures show a retry state, never a half-saved insight |
| J7 | JITAI intervention delivered and answered | `:jitai:engine` (quiet hours, caps, cooldown, snooze, virtual day); `ShadowNotificationManager.getAllNotifications()`; action `PendingIntent.send()`; TTS via `ShadowTextToSpeech.getLastSpokenText()`; DOC02 T-DUP-02 | `UiDevice.openNotification()`, click the action [UIDEV]; DND via `cmd notification set_dnd priority` | Exactly one notification per decision; response recorded; nothing in quiet hours or DND; in-app card when notifications are denied |
| J8 | Hub across travel, DST and gaps | Feature tests; Roborazzi on the 25-hour Berlin day, the US fall-back day and the gap day | Device zone set to `Asia/Kolkata` with `cmd alarm set-timezone`: days still follow the records' own offsets | Day boundaries and totals match section 6; gap days are labeled |
| J9 | Settings: sync profile, export and delete | Profile change re-configures work (DOC02 T-WM-01/02); export via `ACTION_CREATE_DOCUMENT` answered with `shadowOf(activity).receiveResult(...)`; delete clears DB, DataStore and tokens, and both fakes' journals show the revoke calls | `intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(...)` [ESP-INT]; one `google`-image run with the real picker | Export file decrypts to the expected rows; after delete the app is back at J1 with nothing stored |
| J10 | Background resilience | DOC02 T-RCV-01..03, T-WM-05, T-PWR-01/02; activity recreation with saved state | DOC02 E1, E2, E4, E5, E7, E8 scripts; assertions read the diagnostics export ([DOC02] 7.3) and `fakes.chatGpt.refreshCount()` | No duplicate notifications or rows; one refresh per provider; schedules re-pinned after zone changes |

Cross-cutting: accessibility checks on every L3 screen test; font scale 2.0 in screenshots; J3 and J6 also run on the
1 GiB Small Phone (no ANR, no OOM); TalkBack walkthroughs stay manual (L5).

---

## 13. Device matrix, managed devices, UI Automator and adb

### 13.1 Device matrix

| Device | API | Image | Why |
|---|---|---|---|
| `smallPhoneApi29` | 29 | `google` | Provisional minSdk; 1 GiB RAM low-memory device; permission dialog of Android 10 |
| `pixel9Api30Atd` | 30 | `aosp-atd` | Fast headless runs of journeys that need no Chrome, Settings app or SystemUI (ATD is API 30 only) [GMD] |
| `pixel9Api34` | 34 | `google` | Exact alarms denied by default, FGS types, Health Connect in the framework |
| `pixel9Api37` | 37 | `google` | targetSdk; memory limiter; the main nightly device |
| `mediumTabletApi37` | 37 | `google` | Large-screen layouts (J3, J8 smoke) |
| API 26 AVD (only if minSdk 26) | 26 | hand-made AVD | Managed devices support API 27+ only [GMD] |

Device profile names (`Small Phone`, `Pixel 9`, `Medium Tablet`) are from the SDK's device definitions [SDK-DEV]. ATD
images remove Chrome, Messages, the Play Store, the Settings app and SystemUI, among others [GMD].

### 13.2 Managed devices DSL

```kotlin
// :app (convention plugin agentle.android.application); Kotlin DSL as on the managed-devices page [GMD]
android {
    defaultConfig {
        testInstrumentationRunner = "dev.agentle.testing.HiltTestRunner"       // newApplication -> HiltTestApplication [HILT-T]
        testInstrumentationRunnerArguments["clearPackageData"] = "true"       // Orchestrator runs "pm clear" after each test [RUNNER]
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"                               // [RUNNER]
        managedDevices {
            localDevices {
                create("smallPhoneApi29") { device = "Small Phone"; apiLevel = 29; systemImageSource = "google" }
                create("pixel9Api30Atd") { device = "Pixel 9"; apiLevel = 30; systemImageSource = "aosp-atd" }
                create("pixel9Api34") { device = "Pixel 9"; apiLevel = 34; systemImageSource = "google" }
                create("pixel9Api37") { device = "Pixel 9"; apiLevel = 37; systemImageSource = "google" }
                create("mediumTabletApi37") { device = "Medium Tablet"; apiLevel = 37; systemImageSource = "google" }
            }
            groups {
                create("journeys") {
                    targetDevices.add(devices["smallPhoneApi29"])
                    targetDevices.add(devices["pixel9Api34"])
                    targetDevices.add(devices["pixel9Api37"])
                }
            }
        }
    }
}
dependencies { androidTestUtil(libs.androidx.test.orchestrator) }   // 1.6.1 [AXT]
```

The page's group example uses `devices[...]`; whether AGP 9.3 prefers another accessor is UNVERIFIED. Managed devices
cache test results and "rerun only tests that are likely to provide different results" [GMD], so the evidence
freshness gate (14.1) applies to instrumented XML too. Results land in `build/outputs/androidTest-results/` (verified
for `connected` runs [TEST-CLI]; the managed-device subfolder is UNVERIFIED, so the evidence script globs the whole
folder). On a 4-CPU, 15 GB machine run one emulator at a time (no sharding) and keep the Gradle heap at 3 GB.

### 13.3 Runner arguments and sharding

`-Pandroid.testInstrumentationRunnerArguments.<key>=<value>` passes runner arguments from Gradle [RUNNER]. Useful keys
[AJR]: `annotation` / `notAnnotation` (select `@Journey` tests), `class`, `package`, `size`, `numShards` +
`shardIndex`, `listener`.

### 13.4 UI Automator and Espresso-Intents patterns

- `uiAutomator { startApp(pkg); watchFor(PermissionDialog) { clickAllow() }; onElement { textAsString() == "Continue" }.click() }`;
  `clearAppData(pkg)` between scenarios; `waitForAppToBeVisible`, `activeWindow().waitForStable()` before screenshots.
  [UIA]
- Notification shade: `UiDevice.openNotification()`, then find the notification and its action button. [UIDEV]
- Outgoing intents (Settings, Custom Tab, document picker): `intended(...)` to assert, `intending(...).respondWith(...)`
  to stub, so journeys do not depend on apps that ATD images remove. [ESP-INT][GMD]
- Shell from a test: `UiDevice.executeShellCommand` is `@Discouraged` (no error handling, no pipes); use a small
  `Shell` helper in `:core:testing-android` over `UiAutomation.executeShellCommand` that checks the output.
  `executeShellCommandRwe` is the documented alternative; its minimum API level is UNVERIFIED. [UIDEV][DOC01]

### 13.5 adb runbook and preflight

Before a nightly run, a preflight script records the environment and fails early if a command is missing:

```bash
adb shell getprop ro.build.version.sdk; adb shell getprop ro.product.model
for s in appops notification alarm time_zone_detector time_detector deviceidle jobscheduler uimode power \
         netpolicy devicestoragemonitor thermalservice connectivity wifi; do
  adb shell cmd $s help > preflight/cmd-$s.txt 2>&1 || echo "MISSING: cmd $s"
done
adb shell wm size; adb shell settings get system font_scale
```

The state recipes are the commands of section 11 plus [DOC01] 7.3 and [DOC02] 7.2 (E1-E18). Restore every changed
setting in the test's teardown (`dumpsys battery reset`, `dumpsys deviceidle unforce`, `wm size reset`, `wm density
reset`, `cmd uimode night no`, `settings put system font_scale 1.0`, `cmd time_zone_detector
set_auto_detection_enabled true`), because managed devices are reused between tasks in one run (UNVERIFIED for
snapshots between runs).

---

## 14. Evidence format

### 14.1 Rules (so a report can never invent or reuse numbers)

1. Every number comes from files on disk: JUnit XML (`build/test-results/**`, `build/outputs/androidTest-results/**`),
   Kover XML and the scale-test JSON. Each input file is listed with its SHA-256.
2. Counts are exact per suite (Gradle task) and, for `test*UnitTest` tasks, per Robolectric SDK (the `[NN]` suffix that
   `alwaysIncludeVariantMarkersInTestName` guarantees).
3. **Freshness:** a suite whose newest `<testsuite timestamp>` is older than the run start is STALE, and the script
   fails. Reason: with the build cache on, `cleanTest` + `test` restored all four prototype test suites FROM-CACHE in
   10 s (one XML even carried the previous day's timestamp). Evidence runs therefore use `--no-build-cache`, and the
   gate catches any other path to stale XML. [PROTO]
4. **Unique names:** JUnit 6 modules set `junit.jupiter.params.displayname.default = {displayName}[{index}]
   {argumentsWithNames}`; with Jupiter's default, two parameterized methods in the prototype check produced the
   identical name `[1] "happy"`. After the change all 53 names were unique (`everyDoc05ScenarioIsServed(String)[1]
   "happy"`). [PROTO-J6]
5. The script exits 1 on any failure or error, on no XML at all, on a missing `--expect`ed suite, and on any stale
   suite.

### 14.2 Script

`tools/junit_summary.py [--root DIR ...] [--expect SUITE ...] [--fresh-after ISO8601] [--json OUT.json]
[--markdown OUT.md]` (203 lines, Python 3 standard library only; section 16).

### 14.3 JSON fields

| Field | Content |
|---|---|
| `generatedAt`, `gitCommit`, `roots` | When, which commit (null outside git), which roots were scanned |
| `totals` | `tests`, `passed`, `failed`, `error`, `skipped` |
| `suites.<task>` | `kind` (`jvm-or-robolectric` or `instrumented`), `by_sdk` (counts per SDK, `jvm` when unsuffixed), counts, `time_s`, `firstStartedAt`, `lastStartedAt` |
| `failures` | Suite, class, test name and message of each failure or error |
| `missingExpectedSuites`, `freshAfter`, `staleSuites` | The gate inputs and results |
| `files` | Each XML file with its SHA-256 |
| `coverage.<report path>` | Line and branch percentages per package from each Kover XML |
| `dbScale` | One object per size: SQLite version, insert/re-ingest times, query medians, DB bytes, budget, JVM, `measuredAt` |

The Markdown report has one row per suite (and per SDK) with Tests / Passed / Failed / Errors / Skipped / "Ran (UTC)"
(with a STALE marker), a TOTAL row, coverage tables and the DB-scale table.

### 14.4 CI wiring

Record `START` before the first Gradle call, run all suites with `--no-build-cache --continue`, run the script with
`--fresh-after "$START"` and an `--expect` for every suite the pipeline must produce, upload `evidence.json`,
`evidence.md` and the raw XML as artifacts, and fail the job on a non-zero exit. Nightly device runs produce their own
evidence file with `kind = instrumented` suites.

### 14.5 Evidence from this container

Final prototype run: `START=2026-10-02T05:06:48Z`, command in 4.6, `BUILD SUCCESSFUL in 35s`, script exit 0,
`staleSuites: []`, 9 JUnit XML files. [PROTO]

| Suite | Tests | Passed | Failed | Errors | Skipped | Ran (UTC) |
|---|---:|---:|---:|---:|---:|---|
| `:core:features:test` | 11 | 11 | 0 | 0 | 0 | 2026-10-02T05:06:56Z |
| `:core:jitai:test` | 13 | 13 | 0 | 0 | 0 | 2026-10-02T05:06:56Z |
| `:testing:db-scale:dbScaleTest` | 6 | 6 | 0 | 0 | 0 | 2026-10-02T05:06:54Z |
| `:testing:db-scale:test` | 2 | 2 | 0 | 0 | 0 | 2026-10-02T05:06:50Z |
| `:testing:fixtures:test` | 40 | 40 | 0 | 0 | 0 | 2026-10-02T05:07:22Z |
| **TOTAL** | **72** | **72** | **0** | **0** | **0** | |

JUnit 6 consumer check: `:consumer:test` 53 tests, 53 passed, 0 failed (run at 2026-10-02T05:19:50Z, script exit 0).
[PROTO-J6]

Negative checks that prove the gates work:
- Freshness: the script run with `--fresh-after 2026-10-02T05:05:30Z` over the cache-restored XML of the previous run
  exited 1 and listed 4 stale suites (`:core:features:test`, `:core:jitai:test`, `:testing:db-scale:test`,
  `:testing:fixtures:test`). [PROTO]
- Coverage: the 95% branch rule on normalization failed the build (section 10). [PROTO]
- Tests: an earlier run caught a real bug in the fake (the transient-refresh scenario used a global counter: "expected:
  503 but was: 200"); it was fixed with a per-scenario counter before the final run. [PROTO]

---

## 15. UNVERIFIED items and open decisions

| Item | Why open | What to do |
|---|---|---|
| Every Android build step: Robolectric runtime per SDK, Compose v2 under Robolectric, Hilt testing, WorkManager testing, Roborazzi, Kover on Android variants, managed devices | Google Maven unreachable here | First CI run on a machine with Google Maven; keep the evidence JSON |
| `:core:database` as an Android-KMP module with `jvm()`, Room 3 KSP per target, task name `jvmTest` | Not buildable here | Spike in DOC07's catalog build first; fall back to option C (instrumented DAO tests) + B |
| Empty SDK list when `robolectric.enabledSdks` excludes a test's only SDK | Behavior after an empty pick not tested | Avoid pinning single SDKs in matrix tests; test once in CI |
| `cmd connectivity airplane-mode`; `cmd wifi`/`cmd phone` effects behind `svc` | Module sources not on the AOSP mirror | Preflight `cmd <svc> help`; Quick Settings fallback |
| `pm grant` for `android.permission.health.*` | Not verified | Use the Health Connect UI in L4 |
| Chrome on the device reaching the app's `127.0.0.1` port | Not tested | In-process hop by default (5.6) |
| Custom instrumentation-argument pass-through (`fakeHealthScenario`) | Only `size` is documented by example | Hilt-injected scenario switch is the primary path |
| Managed-device result folder; `devices[...]` accessor in AGP 9.3; snapshot reuse between runs | Not in the fetched page | Glob `androidTest-results/**`; check the DSL in the first build |
| `UiAutomation.executeShellCommandRwe` minimum API | Not fetched | Use `executeShellCommand` on API 29-30 |
| SQLite version in `sqlite-bundled` 2.7.1; SQL features above 3.22 | `sqlite.org` unreachable | Use the bundled driver; read `sqlite_version()` in a device test |
| Roborazzi rendering differences between host OSes | Common practice, not verified for 1.76.0 | Record and verify on the CI Linux image only |
| Activity recreation API name for process-death tests | Not checked in this session | Confirm in androidx.test core 1.7.0 |
| AGP 9.2 unified coverage as a Kover alternative | From [DOC07], not tested | Optional |
| Android 17 changes to the shell commands | AOSP mirror is at about Android 16 QPR | Preflight on the API 37 image |

Open decisions for the integrator (owners in brackets):
1. Module naming: `:fakes:servers` (one module, recommended) or DOC07's split plus `:fakes:core` [DOC07].
2. The forbidden-dependency rule amendment of 5.1 [DOC07].
3. `:core:database` as Android-KMP with `jvm()` (recommended) [DOC07].
4. minSdk: 29 provisional; if 26, add SDKs 26 and 28 to `robolectric.enabledSdks` and a hand-made API 26 AVD [DOC01,
   DOC07].
5. Package root `dev.agentle` everywhere (the prototype still uses `com.agentle`).

---

## 16. Prototype

Root: `/tmp/claude-0/-home-claude-agentle-android/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/proto`. Gradle 9.7.1
wrapper, JDK 21.0.11, Kotlin 2.4.20, Maven Central only, JUnit 4 (written before DOC07 fixed JUnit 6 for JVM modules;
the JUnit 6 path is proven separately below). `gradle.properties`: `-Xmx3g`, Kotlin daemon `-Xmx2g`,
`org.gradle.parallel=false`, caching and configuration cache on.

| Module (DOC07 equivalent) | Files | Tests |
|---|---|---|
| `:core:jitai` (`:jitai:engine`) | `JitaiPolicy.kt` (quiet hours that wrap midnight and follow the travel zone, snooze, daily cap, cooldown, notification fallback), `JitaiScheduler.kt` (virtual time) | 13 (one parameterized over quiet-hour cases); Kover line >= 95%, branch >= 90% |
| `:core:features` (`:features:engine`) | `daily/DailyFeatures.kt`, `normalization/Normalizer.kt` (offsets, local days from the record's own offset, dedupe last-write-wins, wearable-first fusion) | 11; Kover variants `normalization` and `daily` with `onCheck = true` |
| `:testing:fixtures` (`:fakes:servers`) | `fake/ScenarioDispatcher.kt` (87 lines), `fake/FakeServers.kt` (42), `fake/FakeGoogleHealthServer.kt` (510), `fake/FakeChatGptServer.kt` (384), `synth/SplitMix64.kt`, `synth/SyntheticUser.kt`, resources `googlehealth/` (21 files) | 40: 19 Google Health, 13 ChatGPT, 8 generator |
| `:testing:db-scale` (`:core:database` JVM tests) | `DbScaleTest.kt` (sqlite-jdbc) | 2 in `test` (100k), 6 in `dbScaleTest` (100k/500k/1M) |
| `tools/` | `junit_summary.py` (evidence), `extract_doc05_fixtures.py` (DOC05 fixtures + drift check) | - |

JUnit 6 check: `/tmp/claude-0/-home-claude-agentle-android/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/a8/j6check`
(a copy of the fakes' main sources as `:fixtures` and a `:consumer` module on JUnit 6.1.3 with
`test-parameter-injector-junit5` 1.24 and `mockwebserver3-junit5` 5.5.0; test `FakesOnJUnit6Test.kt`: one `@StartStop`
test, five TestParameterInjector cases, 19 + 28 `@ParameterizedTest` cases over both scenario catalogs).

Reproduce:

```bash
cd <proto root>
START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
./gradlew --no-build-cache cleanTest check koverXmlReport :core:features:koverXmlReportNormalization \
    :core:features:koverXmlReportDaily :testing:db-scale:dbScaleTest -PdbScaleSizes=100000,500000,1000000
python3 tools/junit_summary.py --root . --fresh-after "$START" --expect :core:jitai:test \
    --expect :core:features:test --expect :testing:fixtures:test --expect :testing:db-scale:test \
    --expect :testing:db-scale:dbScaleTest --json ../evidence.json --markdown ../evidence.md
python3 tools/extract_doc05_fixtures.py --doc <repo>/docs/research/05-google-health-and-health-connect.md \
    --out testing/fixtures/src/main/resources/googlehealth --check
./gradlew --stop
```

To move the prototype into the repository: rename packages to `dev.agentle.*`, move the modules to DOC07's names,
switch JVM modules to JUnit 6 (the XML format and the evidence script are unchanged), and keep `tools/` at the
repository root.

---

## 17. Next steps for the integrator

1. Create `:fakes:servers` from the prototype (5.1), with the fixture script in `tools/` and its `--check` in CI.
2. Amend DOC07's forbidden-dependency rule (5.1) before the first fake module lands; keep `verifyNoFakesInProd`.
3. Write the `:connectors:googlehealth` and `:ai:openai` contract suites against DOC05 8.7 and DOC06 9.4 (5.7), with
   the hygiene check in every test's teardown.
4. Put the build-logic pieces of 4.4 into the convention plugins: `robolectric.enabledSdks`, variant markers, pixel copy
   mode, offline jars, generated `robolectric.properties`, JUnit 6 + `junit-platform.properties`.
5. Decide `:core:database` (7.1) and add the migration tests with committed schemas (7.2).
6. Add the Kover variants and rules of section 10 to `agentle.kover`.
7. Add the managed devices of 13.2 and the `@Journey` annotation; implement J1-J10 starting with J1, J3 and J7.
8. Wire the evidence step of 14.4 into CI and the nightly device job.
