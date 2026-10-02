# 07 - Architecture and verified versions (Agent 7)

Status: COMPLETE for this pass (versions verified; JVM half of the spike PASS; Android half blocked by the
environment; architecture written). Research date: 2026-10-01.
Author: Agent 7 (Android architecture + dependency verification).
Companion file: [`libs.versions.toml.draft`](libs.versions.toml.draft) (the verified version catalog).
Scope: compileSdk/targetSdk 37 (Android 17), minSdk 26-31 candidates, Kotlin + Compose + Room + DataStore + Hilt +
WorkManager + OkHttp/Retrofit + kotlinx.serialization, Gradle Kotlin DSL + version catalog, Lint + detekt + ktlint.

## 0. Verdict in one screen

1. **Toolchain to adopt now: Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.20, KSP 2.3.12, JDK 21 to run Gradle and tests
   (bytecode target 17), compileSdk = targetSdk = 37.** This is the newest combination that sits inside every vendor's
   documented support matrix today: KGP 2.4.20 fully supports Gradle 7.6.3-9.7.0 and AGP 8.5.2-9.3.1 [KGP-COMPAT];
   AGP 9.3 needs Gradle >= 9.5.0 and supports API 37 [AGP-93]; Google's own compose-samples build Kotlin 2.4.20 +
   KSP 2.3.12 + Compose BOM 2026.09.00 + compileSdk 37 on AGP 9.3.1 [CS]. AGP 9.4.0 (September 2026) and Gradle 9.8.0
   (2026-09-24) are newer but outside KGP 2.4.20's tested range, and AGP 9.4.0's R8 already broke one popular library
   (Coil had to ship 3.6.3 to fix "builds using Android Gradle Plugin 9.4.0 and R8") [COIL-CL]. Move to AGP 9.4.x once
   the Kotlin matrix lists it (Kotlin 2.5.0 is planned for December 2026 [KOTLIN-REL]).
2. **This container cannot build an Android app.** Google Maven is unreachable: `maven.google.com` answers 301 to
   `dl.google.com`, and the egress proxy refuses `dl.google.com` (HTTP 403). The Android spike fails at plugin
   resolution (`com.android.application:9.3.3`), before any compatibility question is reached (section 5.1). That is an
   environment limit, not an incompatibility. assembleDebug, assembleRelease, testDebugUnitTest and lintDebug were
   therefore NOT RUN here; the spike sources are complete and ready to run on a machine with Google Maven.
3. **Everything that does not need Google Maven was built and tested here (JVM-only check, section 5.2): PASS.**
   Kotlin 2.4.20 + KSP 2.3.12 + Dagger 2.60.1 (the processor stack Hilt uses), kotlin.time.Clock (stable since 2.3),
   kotlinx-datetime 0.8.0, coroutines 1.11.0 virtual time, Turbine 1.2.1, OkHttp 5.5.0 + mockwebserver3 5.5.0 (JUnit 5
   extension), Retrofit 3.0.0 + kotlinx-serialization converter, kotlinx.serialization 1.11.0, MockK 1.14.11, Truth
   1.4.5, AssertK 0.28.1, JUnit 6.1.3, detekt 2.0.0-alpha.6, detekt 1.23.8, Spotless 8.10.3 + ktlint 1.8.0 and Kover
   0.9.11, on Gradle 9.7.1 and again on Gradle 9.8.0.
4. **Room 3.0.3 (stable since 2026-07-01) for a greenfield app**, with BundledSQLiteDriver in production and
   AndroidSQLiteDriver under Robolectric; SQLCipher for Android 4.19.1 ships a Room 3 driver (`SQLCipherDriver`)
   [ROOM3-RN][SQLCIPHER]. Room 2.8.5 remains the fallback.
5. **detekt must be 2.0.0-alpha.6 (pre-release, justified):** detekt 1.23.8's Gradle plugin is compiled against the
   legacy AGP API (`com.android.build.gradle.BaseExtension`, `BaseVariant`), which AGP 9 hides by default
   (`android.newDsl=true`); 2.0.0-alpha.6 uses only the new Variant API (bytecode inspection, section 7).
6. **Robolectric 4.17 knows SDK 23 to 37; SDK 36 and 37 need JDK 21** (read from `DefaultSdkProvider` bytecode in the
   published jar; the jar's SHA-1 matches Maven Central). Runtime execution of the SDK matrix is UNVERIFIED here because
   Robolectric itself depends on `androidx.test:monitor` from Google Maven.
7. **Architecture: 1 included build (`build-logic`) + 31 Gradle modules (including `:app`), 16 of them pure
   Kotlin/JVM** (cheap to build and test, no Robolectric). Fake and production backends are separated by a `backend`
   product-flavor dimension (`prod`/`fake`), not only by DI, so the fake servers are absent from every `prod*`
   classpath; a build-logic check fails the build if a `:fakes:*` project ever appears on
   `prodReleaseRuntimeClasspath`. One `AgentleClock` (wall clock + zone + monotonic elapsed time) is the only time
   source (section 8.6).
8. **Catalog: [`libs.versions.toml.draft`](libs.versions.toml.draft) (54 versions, 91 libraries, 21 plugins)** parses
   with Gradle 9.7.1, and every Maven Central / Plugin Portal coordinate in it exists at the pinned version (checked
   2026-10-01). Google Maven coordinates are checked against the official release and reference pages only.
   `minSdk = "29"` in it is PROVISIONAL (section 8.7).

## 1. Method and sources

* Fetching: only `curl` from the shell and `git clone`; no WebFetch (the user asked not to be prompted again).
* Maven Central and the Gradle Plugin Portal: `maven-metadata.xml` and POM `Last-Modified` headers.
* Google Maven artifacts (AndroidX, AGP, Play services): metadata is unreachable here, so versions come from the official
  developer.android.com release pages, the Compose BOM mapping page, reference docs and Google's own sample catalogs.
  These are marked accordingly; anything not visible in an official page is marked UNVERIFIED.
* Spike sources: `/tmp/claude-0/spike` (Android, AGP) and `/tmp/claude-0/spike/jvm-check` (JVM-only). Logs are in this
  session's scratchpad (`jvm_*.log`, `android_spike_help.log`).

| Key | Source |
|---|---|
| [MC] | Maven Central metadata `https://repo.maven.apache.org/maven2/<group>/<artifact>/maven-metadata.xml` and POM `Last-Modified` dates (fetched 2026-10-01) |
| [GPP] | Gradle Plugin Portal metadata `https://plugins.gradle.org/m2/<plugin-marker>/maven-metadata.xml` |
| [GRADLE-CUR] | `https://services.gradle.org/versions/current` (9.8.0, build 2026-09-24); `/versions/release-candidate` = `{}`; `/versions/nightly` = 9.9.0-20261001 |
| [AGP-94] | `https://developer.android.com/build/releases/gradle-plugin` (AGP 9.4.0, September 2026) |
| [AGP-93] | `https://developer.android.com/build/releases/agp-9-3-0-release-notes` (9.3.0 July 2026; patches 9.3.1-9.3.3) |
| [AGP-92] | `https://developer.android.com/build/releases/agp-9-2-0-release-notes` |
| [AGP-90] | `https://developer.android.com/build/releases/agp-9-0-0-release-notes` (new DSL, built-in Kotlin, property defaults) |
| [AGP-ABOUT] | `https://developer.android.com/build/releases/about-agp` (AGP/Gradle and AGP/Studio tables) |
| [STUDIO] | `https://developer.android.com/studio/preview/features` ("Android Studio Rabbit 1 Stable, Android Gradle plugin 9.4.0 Stable, Android Studio Rabbit 2 Canary") |
| [KGP-COMPAT] | `https://kotlinlang.org/docs/gradle-configure-project.html` (KGP 2.4.20: Gradle 7.6.3-9.7.0, AGP 8.5.2-9.3.1) |
| [KOTLIN-REL] | `https://kotlinlang.org/docs/releases.html` (2.5.0 planned for December 2026) |
| [KSP-QS] | `https://kotlinlang.org/docs/ksp-quickstart.html` (Kotlin 2.4.20 with KSP 2.3.x) |
| [AX:<lib>] | `https://developer.android.com/jetpack/androidx/releases/<lib>` |
| [BOM-MAP] | `https://developer.android.com/develop/ui/compose/bom/bom-mapping` (BOM 2026.09.00 mapping) |
| [ROOM3-RN] | `https://developer.android.com/jetpack/androidx/releases/room3` |
| [ROOM3-REF] | `https://developer.android.com/reference/kotlin/androidx/room3/testing/MigrationTestHelper` and sibling pages (artifact names) |
| [AUTHZ] | `https://developer.android.com/identity/authorization` (`play-services-auth:22.0.0` snippet) |
| [SIWG] | `https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation` |
| [HC-GS] | `https://developer.android.com/health-and-fitness/health-connect/get-started` |
| [PS] | `https://raw.githubusercontent.com/android/platform-samples/main/gradle/libs.versions.toml` |
| [CS] | `https://github.com/android/compose-samples` at fe26402 (2026-10-01): JetNews, Jetchat, Reply, Jetsnack, JetLagged catalogs = AGP 9.3.1, Kotlin 2.4.20, KSP 2.3.12, Hilt 2.60.1, BOM 2026.09.00, compileSdk 37, Gradle 9.5.0; Jetcaster = AGP 9.2.1 + Kotlin 2.3.21 + KSP 2.3.9 + Hilt 2.59.2 + Room 2.8.4 with ksp/hilt plugins applied |
| [NIA] | `https://github.com/android/nowinandroid` at a49ed253 (2026-09-22): AGP 9.3.2, Gradle 9.7.1, Hilt 2.59 + `ksp(kotlin-metadata-jvm)`, Room, Robolectric 4.16, Roborazzi, build-logic convention plugins |
| [ROBO-JAR] | `robolectric-4.17.jar` from Maven Central (SHA-1 31a19773cc1daf1be0f77769497371ddf18a518f = Central `.sha1`), class `org.robolectric.plugins.DefaultSdkProvider` |
| [ROBO-POM] | `https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.17/robolectric-4.17.pom` |
| [SQLCIPHER] | `https://raw.githubusercontent.com/sqlcipher/sqlcipher-android/master/README.md` |
| [COIL-CL] | `https://raw.githubusercontent.com/coil-kt/coil/main/CHANGELOG.md` |
| [KTLINT-CL] | `https://raw.githubusercontent.com/pinterest/ktlint/master/CHANGELOG.md` |
| [RETROFIT-CL] | `https://raw.githubusercontent.com/square/retrofit/trunk/CHANGELOG.md` |
| [KSER-CL] | `https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/master/CHANGELOG.md` |
| [KDT-CL] | `https://raw.githubusercontent.com/Kotlin/kotlinx-datetime/master/CHANGELOG.md` |
| [KOVER-CL] | `https://raw.githubusercontent.com/Kotlin/kotlinx-kover/main/CHANGELOG.md` |
| [TURBINE-CL] | `https://raw.githubusercontent.com/cashapp/turbine/trunk/CHANGELOG.md` |
| [LEAK-CL] | `https://raw.githubusercontent.com/square/leakcanary/main/docs/changelog.md` |
| [DESUGAR-CL] | `https://raw.githubusercontent.com/google/desugar_jdk_libs/master/CHANGELOG.md` |
| [OSV] | `https://raw.githubusercontent.com/google/osv-scanner/main/docs/supported_languages_and_lockfiles.md` |
| [ROBORAZZI] | `https://raw.githubusercontent.com/takahirom/roborazzi/main/README.md` |
| [GIT] | `git clone --depth 1` of the named repository on 2026-10-01 (HEAD commit dates quoted) |

## 2. Version table (Part 1)

"Recommended" is what goes into `libs.versions.toml.draft`. Dates are publication dates.

### 2.1 Build toolchain

| Component | Latest stable | Newest pre-release | Recommended | Source / notes |
|---|---|---|---|---|
| Gradle | 9.8.0 (2026-09-24) | nightly 9.9.0-20261001 (no active RC) | **9.7.1** (wrapper) | [GRADLE-CUR]. 9.7.x is the newest line inside KGP 2.4.20's fully supported range (max 9.7.0) [KGP-COMPAT]. 9.8.0 also passed the JVM check (one Kover deprecation). |
| Android Gradle Plugin | 9.4.0 (Sept 2026) | 9.5 previews ship with Studio Rabbit 2 Canary (exact alpha UNVERIFIED) | **9.3.3** | [AGP-94][AGP-93][STUDIO]. 9.3 min Gradle 9.5.0, max API 37; 9.4 min Gradle 9.6.0, max API 37 [AGP-ABOUT]. 9.3.3 fixes 10 R8/D8 issues incl. "R8 writes `.kotlin_module` file with `:` in its name" [AGP-93]. |
| Android Studio | Rabbit 1, 2026.2.1 | Rabbit 2 Canary | Rabbit 1 | [STUDIO] |
| Kotlin (KGP, compose + serialization compiler plugins) | 2.4.20 (2026-09-07) | 2.5.0-Beta1 (2026-09-23) | **2.4.20** | [MC][KOTLIN-REL] |
| KSP | 2.3.12 (2026-09-09) | none newer | **2.3.12** | [MC]. KSP2 versions are independent of Kotlin since 2.3.0; kotlinlang quickstart pairs Kotlin 2.4.20 with KSP 2.3.x [KSP-QS]. |
| JDK | AGP needs 17+ | - | **21** for Gradle/tests, bytecode 17 | [AGP-94]; Robolectric SDK 36/37 require Java 21 [ROBO-JAR]. |
| SDK Build Tools | 36.0.0 minimum for AGP 9.x | - | default | [AGP-94]. Local SDK has 35.0.0-37.0.0. |
| foojay toolchain resolver | 1.0.0 | - | 1.0.0 | [GPP] |

### 2.2 AndroidX and Compose (Google Maven: versions from official release pages)

| Component | Latest stable | Newest pre-release | Recommended | Source / notes |
|---|---|---|---|---|
| Compose BOM | 2026.09.00 | - | **2026.09.00** | [BOM-MAP]: compose ui/foundation/runtime/animation/material 1.12.1, material3 1.4.0, material3-adaptive 1.3.0, material-icons 1.7.8. Alpha line: compose 1.13.0-alpha03, material3 1.5.0-alpha29 [AX:compose]. |
| activity-compose | 1.13.0 (2026-03-11) | 1.14.0-alpha03 | 1.13.0 | [AX:activity] |
| lifecycle (incl. `lifecycle-viewmodel-navigation3`) | 2.11.0 (2026-06-17) | 2.12.0-alpha04 | 2.11.0 | [AX:lifecycle] |
| navigation-compose (Nav 2) | 2.10.2 (2026-09-23) | - | not used | [AX:navigation] |
| Navigation 3 (`navigation3-runtime`, `navigation3-ui`) | 1.2.0 (2026-09-23) | 1.3.0-alpha01 | **1.2.0** | [AX:navigation3]. 1.2.0 adds a KMP deep-link API (`DeepLinkRequest`, `DeepLinkMatcher`, `BackStackMatcher`), useful for notification-to-card deep links. |
| core-ktx | 1.19.1 (2026-09-23) | - | 1.19.1 | [AX:core] |
| androidx.hilt (`hilt-work`, `hilt-compiler`, `hilt-lifecycle-viewmodel-compose`) | 1.4.0 (2026-07-01) | - | 1.4.0 | [AX:hilt]. Since 1.3.0 `hiltViewModel()` lives in `hilt-lifecycle-viewmodel-compose` (no Navigation dependency); 1.4.0 adds `rememberHiltViewModelFactory()` for Nav3's `rememberViewModelStoreOwner`. `hilt-navigation-compose` is only for Nav 2. |
| Room 3 (`androidx.room3:*`) | 3.0.3 (2026-09-09) | 3.1.0-alpha01 | **3.0.3** | [ROOM3-RN] (section 4) |
| Room 2 (`androidx.room:*`) | 2.8.5 (2026-09-09) | - | fallback only | [AX:room] |
| androidx.sqlite (`sqlite-bundled`, `sqlite-framework`) | 2.7.1 (2026-09-09) | 2.8.0-alpha01 | 2.7.1 | [AX:sqlite][ROOM3-REF] |
| DataStore | 1.2.1 (2026-03-11) | 1.3.0-alpha11 | 1.2.1 | [AX:datastore] |
| WorkManager (`work-runtime`, `work-testing`) | 2.12.0 (2026-09-23) | - | 2.12.0 | [AX:work]. 2.12.0 raised minSdk 23 -> 24. `Configuration.Builder.setClock()` exists since 2.9.0. |
| Paging 3 (`paging-runtime`, `paging-compose`) | 3.5.1 (2026-08-12) | - | 3.5.1 | [AX:paging]; Room 3 PagingSource needs `room3-paging` + `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)` [ROOM3-RN] |
| Media3 (`media3-transformer`, `-effect`, `-exoplayer`, `-ui`, `-ui-compose`) | 1.11.1 (2026-09-10) | - (no alpha listed) | 1.11.1 | [AX:media3]; minSdk 23 |
| androidx.browser | 1.10.0 (2026-03-25) | - | 1.10.0 | [AX:browser]; `AuthTabIntent` (Auth Tab) public; minSdk 23. Needed for the ChatGPT Custom Tab flow (doc 06). |
| Health Connect `connect-client` | 1.1.0 (2025-10-08) | 1.2.0-alpha06 (2026-08-26) | 1.1.0 (1.2.0-alpha only if doc 05 needs new APIs) | [AX:health-connect]. 1.2.0-alpha05 raised minSdk to 24. The get-started page snippet shows 1.2.0-alpha06 [HC-GS]. |
| Credentials (`credentials`, `credentials-play-services-auth`) | 1.6.0 (2026-04-08) | 1.7.0-alpha03 | only if Sign in with Google is needed | [AX:credentials][SIWG] |
| AndroidX Test | core/runner/rules 1.7.0, ext.junit 1.3.0, ext.truth 1.7.0, espresso 3.7.0, monitor 1.8.0, orchestrator 1.6.1, services 1.6.0 (2025-07-30) | monitor 1.9.0-alpha01 | as listed | [AX:test] |
| Compose ui-test (`ui-test-junit4`, `ui-test-manifest`) | 1.12.1 via BOM | - | via BOM | [BOM-MAP] |
| security-crypto | 1.1.0 (2025-07-30) | - | **do not use** | [AX:security]: "Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore." Use Tink + Keystore. |
| profileinstaller / benchmark / tracing | 1.4.1 / 1.5.0 / 2.0.3 | - | optional | [AX:profileinstaller][AX:benchmark][AX:tracing] |

### 2.3 Google Play services and other Google Maven-only artifacts

| Component | Version | Status | Source / notes |
|---|---|---|---|
| `play-services-auth` (AuthorizationClient) | 22.0.0 | Shown on the official authorization guide; doc 05 cites the GMS release notes (2026-08-26). Latest-ness not checkable from metadata here. | [AUTHZ]; doc 05 [GMS-REL] |
| `play-services-location` | 21.4.0 | UNVERIFIED as latest (seen in Google's platform-samples catalog) | [PS] |
| `com.google.android.libraries.identity.googleid:googleid` | UNVERIFIED | The official page literally says `<latest version>`; only needed for Sign in with Google | [SIWG] |
| `com.android.tools:desugar_jdk_libs` | 2.1.5 (2025-02-14) | latest in the project's CHANGELOG; JetNews uses 2.1.5 | [DESUGAR-CL][CS]. With minSdk >= 26, java.time is native; enable core-library desugaring only if a needed API requires it. |
| AGP 9.4.x / 9.5 previews, AndroidX alphas | - | metadata unreachable here | release pages only |

### 2.4 DI, networking, serialization, coroutines (Maven Central)

| Component | Latest stable | Newest pre-release | Recommended | Source / notes |
|---|---|---|---|---|
| Dagger / Hilt (`hilt-android`, `hilt-compiler`, `hilt-android-testing`, Gradle plugin `com.google.dagger.hilt.android`) | 2.60.1 (2026-07-06) | none newer | **2.60.1** + `ksp(kotlin-metadata-jvm:<kotlin>)` | [MC][GPP]. `dagger-compiler`/`hilt-compiler` 2.60.1 depend on `kotlin-metadata-jvm` 2.3.21 and KSP API 2.3.7 (POMs); the override is the nowinandroid pattern [NIA] and was verified in the JVM check (2.3.21 -> 2.4.20, tests pass). |
| Retrofit + `converter-kotlinx-serialization` | 3.0.0 (2025-05-15) | - | 3.0.0 | [MC][RETROFIT-CL]. POM depends on OkHttp 4.12.0 and kotlinx-serialization-core 1.8.1; resolution upgrades both (verified). |
| OkHttp (+ `logging-interceptor`, `mockwebserver3`, `mockwebserver3-junit4`, `-junit5`, `okhttp-tls`) | 5.5.0 (2026-08-16) | - | 5.5.0 | [MC]. Use `mockwebserver3` (package `mockwebserver3`), not the legacy `mockwebserver` artifact. |
| kotlinx.serialization | 1.11.0 (2026-04-09) | 1.12.0-RC (2026-09-04, "based on Kotlin 2.4.10") | 1.11.0 | [MC][KSER-CL] |
| kotlinx.coroutines | 1.11.0 (2026-05-07) | - | 1.11.0 | [MC] |
| kotlinx-datetime | 0.8.0 (2026-05-07) | - | 0.8.0 | [MC][KDT-CL]: `Instant`/`Clock` now come from `kotlin.time` (stdlib), `TimeZone` serialization deprecated. Do not use the `-0.6.x-compat` artifacts. |
| kotlinx-collections-immutable | 0.5.2 | - | 0.5.2 | [MC] (Compose stability for lists) |
| Ktor client (alternative) | 3.6.0 | - | not used | [MC] |
| Metro DI (alternative) | 1.4.5 | - | not used (Hilt is the requirement) | [GPP] |

### 2.5 Testing

| Component | Latest stable | Newest pre-release | Recommended | Source / notes |
|---|---|---|---|---|
| Robolectric | 4.17 (2026-09-10) | none newer (4.17-beta-1..4 preceded it) | **4.17** | [MC]. Known SDKs 23-37; SDK 36 and 37 need Java 21 [ROBO-JAR]; depends on `androidx.test:monitor` 1.8.0 and `espresso-idling-resource` 3.7.0 (Google Maven) [ROBO-POM]. |
| Roborazzi (+ `-compose`, `-junit-rule`, Gradle plugin) | 1.76.0 (2026-09-29) | - | 1.76.0 | [MC][ROBORAZZI]; needs `@GraphicsMode(NATIVE)`; tasks `recordRoborazziDebug`, `verifyRoborazziDebug`, `compareRoborazziDebug`. |
| JUnit 4 | 4.13.2 | - | 4.13.2 for Android/Robolectric tests | [MC]; Robolectric and AndroidX Test run on JUnit 4. |
| JUnit Jupiter / Platform (JUnit 6) | 6.1.3 (2026-08-07) | - | 6.1.3 for JVM modules | [MC]; verified in the JVM check. |
| android-junit5 Gradle plugin | 2.0.1 (2026-01-14) | - | not recommended | [MC]; avoid two runners in Android modules. |
| Turbine | 1.2.1 (2025-06-11) | - | 1.2.1 | [MC][TURBINE-CL] |
| Truth / AssertK | 1.4.5 (2025-09-10) / 0.28.1 (2024-04-17) | - | **Truth** (pick one) | [MC]. AssertK has had no release since April 2024; Truth is maintained and pairs with `androidx.test.ext:truth`. Both work with Kotlin 2.4.20 (JVM check). |
| MockK | 1.14.11 (2026-05-29) | - | 1.14.11 (boundaries only; prefer fakes) | [MC]; verified on JDK 21 + Kotlin 2.4.20. |
| mockito-kotlin (alternative) | 6.4.0 | - | not used | [MC] |
| work-testing / room3-testing / hilt-android-testing | 2.12.0 / 3.0.3 / 2.60.1 | - | as listed | [AX:work][ROOM3-REF][MC] |
| Paparazzi (alternative to Roborazzi) | 1.3.5 | 2.0.0-alpha05 | not used | [GPP] |

### 2.6 Quality, security, images, leaks

| Component | Latest stable | Newest pre-release | Recommended | Source / notes |
|---|---|---|---|---|
| detekt | 1.23.8 (2025-02-21; group `io.gitlab.arturbosch.detekt`) | 2.0.0-alpha.6 (2026-08-04; group/plugin id `dev.detekt`) | **2.0.0-alpha.6** | [MC][GPP]; reason in section 7.1. detekt 2.0.0-alpha.6 embeds kotlin-compiler 2.4.10 (detekt-core POM). |
| Compose detekt rules (`io.nlopez.compose.rules:detekt`) | 0.6.7 (2026-09-24) | - | optional | [MC]; compatibility with detekt 2.0 alphas UNVERIFIED. |
| ktlint | 1.8.0 (2025-11-14) | 2.0.0-ALPHA-4 (2026-08-20, moving to `io.github.ktlint`, not on Central yet) | **1.8.0** | [MC][KTLINT-CL] |
| Spotless Gradle plugin | 8.10.3 (2026-09-25) | - | **8.10.3** | [MC] |
| ktlint-gradle (`org.jlleitschuh.gradle.ktlint`) | 14.2.0 (2026-03-12) | - | alternative to Spotless | [GPP] |
| Kover | 0.9.11 (2026-09-29) | - | **0.9.11** | [MC][KOVER-CL]. 0.9.9 fixed a Gradle 9.6 deprecation and "0% coverage for classes in com.android.* packages". |
| Tink (`tink-android`) | 1.23.0 (2026-07-09) | - | 1.23.0 | [MC]; repo active (HEAD 2026-09-24) [GIT] |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) | 4.19.1 (2026-09-29) | - | 4.19.1 (only if DB encryption is required) | [MC][SQLCIPHER]: API 23+, `SQLCipherDriver` for Room 3. The old `android-database-sqlcipher` (4.5.4, 2023) is superseded. |
| AppAuth-Android (`net.openid:appauth`) | 0.11.1 (2021-12-22) | - | **avoid** | [MC]; no release for 4.75 years; repo HEAD 2026-03-22 is a small merge [GIT]. Use AuthorizationClient (Google) and a small in-house PKCE + Custom Tab client (ChatGPT, doc 06). |
| Coil 3 (`io.coil-kt.coil3:*`) | 3.6.3 (2026-09-18) | - | 3.6.3 | [MC][COIL-CL] |
| LeakCanary | 2.14 (2024-04-18) | 3.0-alpha-9 (2026-06-24; raises minSdk to 26) | 2.14 (`debugImplementation`) | [MC][LEAK-CL]; repo active (HEAD 2026-09-29) [GIT]. |
| OWASP dependency-check plugin | 13.0.0 (portal, 2026-08-03) | - | optional | [GPP]; needs an NVD API key. |
| OSV-Scanner | CLI, active (HEAD 2026-10-01) | - | **recommended** | [OSV]: reads `gradle.lockfile`, `buildscript-gradle.lockfile`, `gradle/verification-metadata.xml`. |
| CycloneDX Gradle plugin | 3.4.1 (2026-08-11) | - | recommended (SBOM) | [GPP] |
| gradle-versions-plugin / version-catalog-update | 0.64.0 / 1.1.1 | - | optional | [GPP]; JetNews uses both [CS]. |
| dependency-analysis plugin | 3.19.2 | - | optional | [MC] |

## 3. AGP 9 specifics that the project must follow

Sources: [AGP-90] unless noted.

1. **Built-in Kotlin is on by default** (`android.builtInKotlin=true`). Do not apply `org.jetbrains.kotlin.android`; it
   "is not compatible with the new DSL". Configure Kotlin with the `kotlin { compilerOptions { ... } }` extension
   (type `KotlinAndroidProjectExtension`), as nowinandroid's convention plugins do [NIA].
2. **AGP has a runtime dependency on KGP** (2.2.10 for AGP 9.0/9.1; AGP 9.2 moved to 2.3.10 per its issue list
   [AGP-92]). To use Kotlin 2.4.20, put KGP on the root build classpath: `alias(libs.plugins.kotlin.android) apply false`
   or `alias(libs.plugins.kotlin.jvm) apply false` in the root `plugins {}` (or `buildscript` classpath as the release notes
   show). The about-agp page itself shows `id("org.jetbrains.kotlin.android") version "2.4.10" apply false` [AGP-ABOUT].
   The KGP version AGP 9.3/9.4 pulls in by default is UNVERIFIED here (needs the AGP POM). The catalog draft has no
   `kotlin-android` alias at all, so nobody can apply it by mistake: the root declares `kotlin-jvm` with `apply false`,
   which loads the same KGP artifact (nowinandroid's root build does the same [NIA]).
3. **New DSL only** (`android.newDsl=true`): `BaseExtension`, `applicationVariants` and the legacy variant API are gone.
   Build logic must use `com.android.build.api.dsl.ApplicationExtension` / `LibraryExtension` / `CommonExtension` and
   `androidComponents { onVariants/beforeVariants }`. Opt-outs exist (`android.newDsl=false`; per module
   `android.newDsl.optOut=:module` since AGP 9.4 [AGP-94]) but disappear in AGP 10. Agentle must not use any opt-out.
4. **kapt is incompatible with built-in Kotlin**: everything uses KSP (Room 3 is KSP-only anyway [ROOM3-RN]). AGP
   upgrades KSP below 2.2.10-2.0.2; use KSP 2.3.x.
5. Changed defaults to rely on: `android.uniquePackageNames=true` (every module needs its own `namespace`), R class
   non-final in app modules, `targetSdk` defaults to `compileSdk`, Java source/target default 11 (set 17 explicitly),
   NDK r28c default, libraries require consumers to have the same or higher compileSdk.
6. `getDefaultProguardFile("proguard-android.txt")` is no longer supported; use `proguard-android-optimize.txt`.
   Global options (for example `-dontoptimize`) in library consumer rules are rejected by default
   (`android.r8.globalOptionsInConsumerRules.disallowed`).
7. AGP 9.3 adds `:app:analyzeReleaseR8Config` (R8 configuration analyzer) and a new `optimization { enable = true }`
   DSL; 9.3.3 fixed a bug where that DSL "does not set isMinifyEnabled in the ApplicationVariant object" [AGP-93].
   Use classic `isMinifyEnabled = true` until the new DSL settles.
8. AGP 9.2 adds experimental unified coverage/test reports (`android.experimental.reportAggregationSupport=true`) and
   "Allow higher compileSdk for tests than main" [AGP-92].
9. AGP 9.4 checks strict flavor-dimension parity between app and dynamic-feature modules [AGP-94]; Agentle has no
   dynamic features, so the `backend` flavor dimension is unaffected.
10. Gradle properties worth setting from the start: `org.gradle.configuration-cache=true`, `org.gradle.caching=true`,
    `ksp.project.isolation.enabled=true` (nowinandroid also enables isolated projects [NIA]; adopt later, after every
    plugin is verified), and on this 4-CPU machine `org.gradle.jvmargs=-Xmx3g`, `kotlin.daemon.jvmargs=-Xmx2g`.

## 4. Room 3 vs Room 2.x

Source: [ROOM3-RN] unless noted.

| Topic | Room 3.0.3 | Room 2.8.5 |
|---|---|---|
| Packages / coordinates | `androidx.room3.*`, group `androidx.room3` (`room3-runtime`, `room3-compiler`, `room3-common`, `room3-testing`, `room3-paging`, `room3-sqlite-wrapper`, `-rxjava3`, `-guava`, `-livedata`) [ROOM3-REF] | `androidx.room.*` |
| Processor | KSP only, Kotlin codegen only (no kapt, no Java AP) | KSP or kapt/Java AP |
| Gradle plugin | `id("androidx.room3")` + `room3 { schemaDirectory("$projectDir/schemas") }` (required, writes per-variant schema JSON for validation and auto-migrations) | `androidx.room` + `room { }` |
| Storage access | `SQLiteDriver` required: `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`) or `AndroidSQLiteDriver` (`sqlite-framework`) [ROOM3-REF]; no SupportSQLite (only through `room3-sqlite-wrapper`) | SupportSQLite by default, drivers optional |
| API style | Coroutines-first: DAO functions must be `suspend` or return `Flow`/custom types; `useReaderConnection`/`useWriterConnection`/`withWriteTransaction`; Flow-based `InvalidationTracker.createFlow()`; migrations receive `SQLiteConnection` | blocking + suspend, Cursor APIs |
| Paging | `PagingSourceDaoReturnTypeConverter` from `room3-paging`, registered with `@DaoReturnTypeConverters` | `room-paging` |
| Migrations | `@Database(autoMigrations = [...])` + `AutoMigrationSpec` (3.1.0-alpha01 supports Kotlin `object` specs); manual `Migration` with `SQLiteConnection` | same concepts |
| Migration tests | `room3-testing` `MigrationTestHelper(instrumentation, file, driver, databaseClass, ...)` on Android; `(schemaDirectoryPath, databasePath, driver, ...)` on JVM/native; `createDatabase(version)` / `runMigrationsAndValidate(version, migrations)` are `suspend` [ROOM3-REF] | `room-testing` |
| Coexistence | New package so it can coexist with libraries that depend on Room 2 (for example WorkManager) | - |
| SQLCipher | `SQLCipherDriver(password, ...)` + `.setDriver(driver)` [SQLCIPHER] | `SupportOpenHelperFactory` [SQLCIPHER] |

Decision: **Room 3.0.3** (stable, the future direction, KSP-only matches AGP 9). Driver policy:
`BundledSQLiteDriver` in production (same SQLite version on every device), `AndroidSQLiteDriver` in Robolectric tests
(the bundled driver's Android `.so` files cannot load in a host JVM; the JVM artifact of `sqlite-bundled` can be used in
pure-JVM tests - reasoning, UNVERIFIED at runtime here), `SQLCipherDriver` only if doc 01/security review requires
at-rest DB encryption beyond file-based encryption. Studio Database Inspector support for the bundled driver is
UNVERIFIED. For Robolectric migration tests, add the schema directory to the unit-test assets
(`sourceSets.test.assets.srcDir("$projectDir/schemas")`; exact AGP 9 DSL UNVERIFIED here).

## 5. Compatibility spike (Part 2): commands and results

### 5.1 Android spike `/tmp/claude-0/spike` (AGP 9.3.3) - BLOCKED BY ENVIRONMENT

Contents (complete, reused from the interrupted run): `:app` (Compose `CounterScreen`, `@HiltAndroidApp` app implementing
`Configuration.Provider` with `HiltWorkerFactory`, `@AndroidEntryPoint` activity, `@HiltWorker SyncWorker`, DataStore
`SettingsRepository`, `ClockModule` providing `kotlin.time.Clock`), `:core` (Room 3 `EventEntity`/`EventDao`/`SpikeDatabase`
with `BundledSQLiteDriver`, kotlinx.serialization payload), and the five requested tests:
`CounterScreenTest` (Compose click), `EventDaoTest` (Room 3 in-memory with `AndroidSQLiteDriver` + Turbine),
`HiltWorkManagerTest` (`HiltAndroidRule` + `HiltTestApplication` + `@TestInstallIn` clock + `WorkManagerTestInitHelper`
TestDriver), `SyncWorkerTest` (`TestListenableWorkerBuilder` with a `WorkerFactory`), `CounterScreenshotTest`
(Roborazzi `captureRoboImage`, `@GraphicsMode(NATIVE)`, Pixel 5 qualifiers). compileSdk/targetSdk 37, minSdk 28,
`robolectric.properties` sdk=37, AGP 9 defaults untouched (no `kotlin-android`, no opt-outs).

| Command | Result |
|---|---|
| `cd /tmp/claude-0/spike && ./gradlew help --console=plain --max-workers=1` | **FAIL (environment)**: `Plugin [id: 'com.android.application', version: '9.3.3', apply: false] was not found`. `--info` shows the cause: `HTTP 403: https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/9.3.3/...pom`. `maven.google.com` 301-redirects to `dl.google.com`, which the egress proxy denies; Maven Central and the Plugin Portal do not host AGP/AndroidX. |
| `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug detekt spotlessCheck` | **NOT RUN** (cannot configure without AGP). |

Run these unchanged on a machine with Google Maven access (developer laptop or CI):

```
cd spike && ./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug detekt spotlessCheck --console=plain
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true   # writes the Roborazzi PNG
```

Evidence that the Android half should work, short of running it here:
* Kotlin 2.4.20 + KSP 2.3.12 + Compose BOM 2026.09.00 + compileSdk 37 on AGP 9.3.1 with built-in Kotlin: five
  compose-samples apps [CS].
* AGP 9 + KSP + Hilt + Room (Room 2.8.4, Kotlin 2.3.21): Jetcaster [CS]; AGP 9.3.2 + Hilt + Room + Robolectric 4.16 +
  Roborazzi + build-logic: nowinandroid [NIA].
* Dagger 2.60.1 on Kotlin 2.4.20 + KSP 2.3.12: passes here (section 5.2).
* UNVERIFIED end to end: Hilt Android 2.60.1 + Room 3.0.3 + Robolectric 4.17 on AGP 9.3.3 in one build.

### 5.2 JVM-only check `/tmp/claude-0/spike/jvm-check` - PASS

Same catalog as the Android spike (`../gradle/libs.versions.toml`); resolves only from Maven Central and the Plugin
Portal. Modules mirror the proposed pure-Kotlin modules:
* `:time` - `AgentleClock` (wall `kotlin.time.Clock` + `TimeZone` + monotonic `elapsed()`), DST-safe `dayBounds()`,
  `ticks()` flow, `TestAgentleClock`; 4 JUnit 6 tests (23 h / 25 h DST days in Europe/Berlin, manual wall-clock change
  does not move elapsed time, Turbine + `runTest` virtual time, zone change moves `today()`).
* `:di` - Dagger 2.60.1 through KSP: production component + test component swapping the connector and the clock
  (the JVM analogue of Hilt `@TestInstallIn`); 2 tests.
* `:network` - Retrofit 3 + kotlinx-serialization converter + OkHttp 5.5.0 bearer interceptor and a 401-refresh
  `Authenticator`; tests against `mockwebserver3` with the `@StartStop` JUnit 5 extension, MockK verifies exactly one
  refresh; 2 tests (pagination with unknown fields ignored, 401 -> refresh -> retry).

| Command (from `/tmp/claude-0/spike/jvm-check`) | Result |
|---|---|
| `./gradlew test --console=plain --max-workers=1` (Gradle 9.7.1) | First attempts: **FAIL (environment)** with `Received status code 429 from server: Too Many Requests` from Maven Central (shared machine). Workaround: `systemProp.org.gradle.internal.repository.max.retries=12` and `...initial.backoff=2000` in `gradle.properties`, `--max-workers=1`, retry after a pause. Then one real failure: `testScheduler.currentTime` needs `@OptIn(ExperimentalCoroutinesApi::class)` under `allWarningsAsErrors`; fixed. **PASS: 8/8 tests.** |
| `./gradlew detekt` (detekt 2.0.0-alpha.6) | First run found 2 genuine issues (`ReturnCount`, `MaxLineLength`); fixed. **PASS.** |
| `./gradlew detekt -PdetektLegacy=true` (detekt 1.23.8) | **PASS** on JVM modules, with a Gradle deprecation: `ReportingExtension.file(String)` "scheduled to be removed in Gradle 10". |
| `./gradlew spotlessCheck` (Spotless 8.10.3 + ktlint 1.8.0) | Found a real import-order violation; `spotlessApply`; **PASS**. ktlint 1.8.0 parses the Kotlin 2.4.20 sources used here. |
| `./gradlew koverLog koverXmlReport` (Kover 0.9.11) | **PASS**; merged line coverage 80.3% (time 75.9%, di 61.0%, network 92.3%). |
| `./gradlew :di:test :di:dependencies` with `ksp(kotlin-metadata-jvm 2.4.20)` | **PASS**; `kspKotlinProcessorClasspath`: `kotlin-metadata-jvm:2.3.21 -> 2.4.20`. |
| `gradle-9.8.0/bin/gradle -p jvm-check clean test detekt spotlessCheck koverXmlReport --warning-mode all --no-configuration-cache` | **PASS** (1 min 11 s incl. daemon start); one deprecation from Kover 0.9.11: `Configuration.setVisible(boolean)` "scheduled to be removed in Gradle 11". |
| `jvm-check/gradlew -p <scratchpad>/catalog-check printCatalog --no-configuration-cache` (the catalog draft copied to `gradle/libs.versions.toml`, a build script printing accessors) | **PASS**: Gradle 9.7.1 parses it (54 versions, 91 libraries, 21 plugins, 0 bundles), for example `androidx.room3:androidx.room3.gradle.plugin:3.0.3`, `dev.detekt:2.0.0-alpha.6`, `minSdk=29`. An alias that is a prefix of another (`okhttp`, `androidx-datastore`) needs `.asProvider()` outside a `dependencies {}` block; `implementation(libs.okhttp)` works as is. |
| `curl -I` of the POM of every non-Google coordinate in the catalog draft (Maven Central; plugin markers on the Plugin Portal) | **PASS**: 50/50 exist at the pinned version (5 needed a retry after HTTP 429). |

Facts established by the JVM check:
* `kotlin.time.Clock` and `kotlin.time.Instant` compile under `-Werror` without opt-in: the stdlib 2.4.20 bytecode
  carries `@SinceKotlin("2.3")` + `@WasExperimental(ExperimentalTime::class)`, i.e. stable since Kotlin 2.3.
* Dagger's KSP processor works on Kotlin 2.4.20 metadata with or without the `kotlin-metadata-jvm` override.

### 5.3 Workarounds recorded

| # | Problem | Workaround |
|---|---|---|
| W1 | Google Maven blocked in this container | None allowed (no mirrors); run Android builds elsewhere. |
| W2 | Maven Central HTTP 429 under parallel agents | `systemProp.org.gradle.internal.repository.max.retries` / `initial.backoff`, `--max-workers=1`, retry. |
| W3 | Kotlin newer than Dagger's bundled `kotlin-metadata-jvm` (matters from Kotlin 2.5) | `ksp(libs.kotlin.metadata.jvm)` with `version.ref = "kotlin"` in the Hilt convention plugin [NIA]. |
| W4 | KGP version pulled by AGP | Declare KGP in the root `plugins {}` with `apply false`. |
| W5 | detekt 1.23.8 vs AGP 9 legacy API | Use detekt 2.0.0-alpha.6 (`dev.detekt`). |
| W6 | WorkManager auto-init vs Hilt worker factory | Remove `androidx.work.WorkManagerInitializer` via `tools:node="remove"` in the manifest; app implements `Configuration.Provider`. |
| W7 | Bundled SQLite `.so` cannot load under Robolectric | `AndroidSQLiteDriver` in Robolectric tests (driver injected through the database factory). |
| W8 | Gradle 9.8 / Kover deprecation | none needed before Gradle 11. |

## 6. Robolectric SDK levels

From `DefaultSdkProvider` in robolectric-4.17.jar [ROBO-JAR] (API level -> android-all version, minimum Java):

| SDK | android-all-instrumented | Java | Jar size |
|---|---|---|---|
| 23 M | 6.0.1_r3-robolectric-r1-i7 | 8 | - |
| 24, 25 | 7.0.0_r1 / 7.1.0_r7 -robolectric-r1-i7 | 8 | - |
| 26, 27 | 8.0.0_r4-robolectric-r1-i7 / 8.1.0-robolectric-4611349-i7 | 8 | 90 MB (26) |
| 28 | 9-robolectric-4913185-2-i7 | 8 | 101 MB |
| 29, 30 | 10-robolectric-5803371-i7 / 11-robolectric-6757853-i7 | 9 | 121 MB (30) |
| 31, 32 | 12-robolectric-7732740-i7 / 12.1-robolectric-8229987-i7 | 9 | - |
| 33 | 13-robolectric-9030017-i7 | 9 | 155 MB |
| 34 | 14-robolectric-10818077-i7 | 17 | 144 MB |
| 35 | 15-robolectric-13954326-i7 | 17 | 191 MB |
| 36 | 16-robolectric-13921718-i7 | **21** | 203 MB |
| 37 | 17-robolectric-15733970-i7 | **21** | 227 MB |

All of these jars exist on Maven Central [MC]. Robolectric's own HEAD commit on 2026-10-01 is "Test repeated Roborazzi
captures on SDK 36 and 37" [GIT]. Runtime status here: UNVERIFIED (Robolectric needs `androidx.test:monitor` from
Google Maven [ROBO-POM]). Recommendation: default `sdk=37` in `src/test/resources/robolectric.properties`; a small
platform-behaviour matrix `@Config(sdk = [MIN_SDK, 30, 33, 34, 35, 36, 37])` only on tests of version-dependent code
(permissions, notifications, alarms, FGS), because each level costs a 90-227 MB download and a cold sandbox.

## 7. Static analysis, coverage, vulnerability scanning

### 7.1 detekt

* 1.23.8's `DetektAndroid`/`DetektMultiplatform` classes reference `com/android/build/gradle/BaseExtension` (10x),
  `api/BaseVariant` (33x), `AppExtension`, `LibraryExtension`, `TestExtension` (bytecode strings of
  `detekt-gradle-plugin-1.23.8.jar`). AGP 9 makes these unavailable by default and documents the resulting
  `ClassCastException ... cannot be cast to class com.android.build.gradle.BaseExtension` [AGP-90].
* 2.0.0-alpha.6 references only `com/android/build/api/variant/*`, `AndroidComponentsExtension`,
  `AndroidPluginVersion` and `api/dsl/CommonExtension`.
* Both run on Kotlin 2.4.20 sources in pure JVM modules (section 5.2). Pin 2.0.0-alpha.6, keep
  `buildUponDefaultConfig = true`, and use `ForbiddenMethodCall`/`ForbiddenImport` to enforce the clock rule (8.6).

### 7.2 Formatting, Lint, coverage

* ktlint 1.8.0 through Spotless 8.10.3 (`kotlin { ktlint("1.8.0") }`, `kotlinGradle { ktlint(...) }`), as JetNews
  does [CS]. Revisit ktlint 2.x once it is on Maven Central under `io.github.ktlint`.
* Android Lint from AGP (`lintDebug`), with a shared `lint.xml` and `warningsAsErrors` for release-critical checks.
* Kover 0.9.11 for JVM modules (verified) and Android unit tests (Kover Android support is documented; UNVERIFIED
  here). AGP 9.2's experimental unified coverage is an alternative for Android modules [AGP-92].

### 7.3 Dependency vulnerabilities and supply chain

1. Gradle dependency locking (`gradle.lockfile`) + dependency verification (`gradle/verification-metadata.xml`).
2. OSV-Scanner in CI on those files (no API key; [OSV]).
3. CycloneDX SBOM per release (plugin 3.4.1).
4. Optional: OWASP dependency-check 13.0.0 (needs an NVD API key, slower, more false positives).
5. If hosted on GitHub: dependency submission + Dependabot alerts.

## 8. Architecture (Part 3)

### 8.1 Principles

* **Pure Kotlin first.** Anything that does not touch an Android API is a `kotlin("jvm")` module: no AGP tasks, no R
  classes, no manifest merge, plain JUnit 6 tests in milliseconds. Connector clients, the AI client, the feature
  engine, the JITAI DSL and engine, and both fake servers are JVM modules.
* **Android modules only at the edges:** persistence, OS collectors, notification/TTS/video delivery, background
  scheduling, UI, app wiring.
* **Interfaces point inward.** Domain modules declare ports (`EventSink`, `AccessTokenProvider`, `LlmClient`,
  `InterventionDeliverer`); Android modules implement them; `:app` binds them with Hilt.
* **Few modules, coarse features.** 4-CPU machine with 15 GB shared: 31 modules, 16 of them JVM. Split a module
  only when its compile time or dependency set hurts.

### 8.2 Module map

`JVM` = `agentle.jvm.library`; `AND` = `agentle.android.library`; `AND+C` = with Compose; `H` = Hilt (KSP);
`R` = Room 3 (KSP).

| Module | Type | Responsibility | May depend on |
|---|---|---|---|
| `build-logic/convention` | included build | Convention plugins (8.3) | AGP, KGP, KSP, Room3, Hilt, detekt, Spotless, Kover plugin artifacts (`compileOnly`) |
| `:app` | Android app, H | Hilt root (`@HiltAndroidApp`), `MainActivity`, Navigation 3 graph, WorkManager `Configuration.Provider`, flavor-specific endpoint/clock bindings, manifest + permissions | everything below; `:fakes:*` only as `fakeImplementation` |
| `:core:model` | JVM | Domain types: `Event`, `DataSourceId`, `Feature`, `Insight`, `InterventionOption`, consent records; `@Serializable` value types | kotlinx-serialization, kotlinx-datetime |
| `:core:common` | JVM | `Outcome`/error model, dispatcher qualifiers, logging facade with PII-redaction contract | coroutines |
| `:core:time` | JVM | `AgentleClock`, local-day/time-window math (DST-safe), schedule primitives | kotlinx-datetime, coroutines |
| `:core:network` | JVM | OkHttp client factory, JSON config, retry/backoff, auth interceptor + refreshing `Authenticator` | `:core:common`, OkHttp, Retrofit, serialization |
| `:core:database` | AND, R, H | Room 3 database, entities, DAOs, migrations, schema export; driver selection | `:core:model`, `:core:time`, `:core:security` |
| `:core:datastore` | AND, H | DataStore preferences/proto: settings, consent flags, sync cursors | `:core:model`, `:core:security` |
| `:core:security` | AND, H | Keystore + Tink AEAD: token vault, DB key, export encryption | `:core:common`, Tink |
| `:core:ui` | AND+C | Theme, design system, shared composables, card renderer | `:core:model` |
| `:core:testing` | JVM | `TestAgentleClock`, `MainDispatcherRule`/`runTest` helpers, synthetic data generator, in-memory fakes of ports | `:core:*` JVM modules, coroutines-test, Turbine, Truth |
| `:core:testing-android` | AND | `HiltTestRunner`, Robolectric defaults, Room in-memory factory with `AndroidSQLiteDriver`, WorkManager test init | `:core:testing`, `:core:database`, hilt-android-testing, work-testing, room3-testing |
| `:data:events` | AND, H | Event store and ingestion pipeline (dedupe, idempotent upserts, retention), implements `EventSink` | `:core:database`, `:core:model`, `:core:time`, `:connectors:api` |
| `:connectors:api` | JVM | Connector SPI: `Connector`, `SyncCursor`, capability + permission descriptors, `EventSink` | `:core:model`, `:core:time` |
| `:connectors:android` | AND, H | On-device collectors (usage, notifications listener, activity recognition, location, battery, screen, sensors, calendar; docs 01/02) | `:connectors:api`, `:core:common`, `:core:time` |
| `:connectors:googlehealth` | JVM | Google Health API client (Retrofit), DTOs, mapping, paging, incremental sync; asks `AccessTokenProvider` for tokens (doc 05) | `:connectors:api`, `:core:network` |
| `:connectors:healthconnect` | AND, H | Optional Health Connect reader (doc 05 decides) | `:connectors:api`, `:core:time` |
| `:ai:api` | JVM | `LlmClient` port, prompt/response models, budget and rate-limit policy | `:core:model` |
| `:ai:openai` | JVM | Sign in with ChatGPT protocol (PKCE, loopback listener, token exchange/refresh) and Responses API streaming client (doc 06); Custom Tab launch is injected | `:ai:api`, `:core:network` |
| `:ai:context` | JVM | Builds the privacy-filtered context for prompts (feature selection, redaction, token budget) | `:ai:api`, `:core:model`, `:core:time` |
| `:features:engine` | JVM | Derived features (daily aggregates, baselines, trends) from events | `:core:model`, `:core:time` |
| `:insights` | JVM | Insight generation: rules + LLM, scheduling policy, dedupe | `:ai:api`, `:ai:context`, `:features:engine`, `:core:model`, `:core:time` |
| `:jitai:dsl` | JVM | JITAI definition schema (decision points, tailoring variables, options, rules), JSON format, validation | `:core:model`, serialization |
| `:jitai:engine` | JVM | Decision-point evaluation, burden/rate limits, randomization, audit log model | `:jitai:dsl`, `:features:engine`, `:core:time`, `:core:model` |
| `:interventions` | AND, H | Delivery: notifications, in-app cards, images, TTS, Media3 Transformer video composition | `:core:model`, `:core:ui`, `:core:time`, `:core:datastore`, Media3 |
| `:background` | AND, H | WorkManager workers (`@HiltWorker`), alarm/boot receivers, FGS if any (doc 02); orchestrates sync -> features -> JITAI -> delivery | `:data:events`, `:connectors:*`, `:features:engine`, `:insights`, `:jitai:engine`, `:interventions`, `:core:datastore` |
| `:feature:onboarding` | AND+C, H | Consent, permissions, Google authorization (`AuthorizationClient`), ChatGPT Custom Tab launch | `:core:ui`, `:core:model`, `:core:security`, port interfaces |
| `:feature:hub` | AND+C, H | Data hub: timeline, sources, explorer | `:core:ui`, `:data:events` (read API), `:features:engine` |
| `:feature:insights` | AND+C, H | Insights, JITAI cards, history, feedback | `:core:ui`, `:insights`, `:jitai:engine` (read API) |
| `:feature:settings` | AND+C, H | Sources, schedules, privacy, export/delete, debug menu (fake flavor only) | `:core:ui`, `:core:datastore`, `:data:events` |
| `:fakes:googlehealth-server` | JVM | `mockwebserver3` fake of the Google Health API subset + OAuth token endpoint, scenario dispatcher | `:core:model` (no client DTOs, see 8.5), mockwebserver3, serialization |
| `:fakes:chatgpt-server` | JVM | Fake auth (authorize/token/refresh) + Responses API SSE streaming | same |

Count: 31 modules including `:app` (16 JVM, 15 Android) + `build-logic`. If even that is too heavy at the start,
merge `:core:datastore` into `:core:security`, `:ai:context` into `:insights`, and the four `:feature:*` modules into
two; nothing else changes. Naming note: `:features:engine` (derived features, JVM) and `:feature:*` (UI screens) differ
by one letter; renaming the former to `:derived:features` would avoid mix-ups.

Forbidden dependencies (enforced by a build-logic check that inspects `project.configurations` at configuration time):
* No module except `:app` depends on `:fakes:*`, and `:app` only through `fakeImplementation`.
* `:feature:*` never depend on connector implementations, `:ai:openai`, `:core:database` or `:background`.
* JVM modules never depend on Android modules (enforced by Gradle anyway).
* `:jitai:engine` does not depend on `:interventions` (it decides; delivery is behind a port).
* Only `:core:time` and the `:app` clock binding may call wall-clock APIs (8.6).

### 8.3 Convention plugins (`build-logic/convention`)

Modelled on nowinandroid's AGP 9 build logic [NIA] (they use `CommonExtension`, `ApplicationExtension`,
`LibraryExtension`, `KotlinAndroidProjectExtension` with built-in Kotlin, and `compileOnly` plugin artifacts):

| Plugin id | Applies / configures |
|---|---|
| `agentle.android.application` | `com.android.application`; compileSdk 37, targetSdk 37, minSdk from catalog; Java 17; Kotlin `compilerOptions` (jvmTarget 17, `allWarningsAsErrors` via property); `backend` flavors; release R8 (`isMinifyEnabled`, `isShrinkResources`, `proguard-android-optimize.txt`); disables `fakeRelease`; fakes-on-prod check |
| `agentle.android.library` | `com.android.library`; same SDK/Kotlin settings; `namespace` = `dev.agentle.<path>`; `unitTests.isIncludeAndroidResources = true`; Robolectric + JUnit 4 deps |
| `agentle.android.compose` | `org.jetbrains.kotlin.plugin.compose`; `buildFeatures.compose`; Compose BOM; ui-tooling (debug); ui-test-junit4 + ui-test-manifest; Roborazzi plugin for UI modules |
| `agentle.jvm.library` | `org.jetbrains.kotlin.jvm`; toolchain 21, jvmTarget 17; JUnit 6 platform |
| `agentle.hilt` | `com.google.devtools.ksp`; `ksp(hilt-compiler)` + `ksp(kotlin-metadata-jvm)`; on Android modules also `com.google.dagger.hilt.android` + `hilt-android`; on JVM modules `hilt-core` (nowinandroid's `HiltConventionPlugin` does exactly this [NIA]) |
| `agentle.room` | `androidx.room3` + KSP; `room3 { schemaDirectory("$projectDir/schemas") }`; room3-runtime/compiler, sqlite-bundled, sqlite-framework (tests), room3-testing |
| `agentle.quality` | detekt 2.0.0-alpha.6 (`dev.detekt`), Spotless + ktlint 1.8.0, Lint config |
| `agentle.kover` | Kover with per-module rules; root merges reports |

### 8.4 Debug/release and fake/prod separation

**Variants.** Build types `debug` and `release`; one flavor dimension `backend` with flavors `prod` and `fake`
(`applicationIdSuffix = ".fake"`, different label/icon so both install side by side). `fakeRelease` is disabled
with `androidComponents { beforeVariants(selector().withFlavor("backend" to "fake").withBuildType("release")) { it.enable = false } }`.
Shipping artifact: `prodRelease` only. The `.fake` suffix changes the package name, so nothing may hard-code it:
intent URIs and redirect pages (doc 06) use `context.packageName`, and the Google OAuth Android clients (doc 05) are
registered for the prod package (debug and release signing SHA-1s); the fake flavor never talks to Google.

**Why flavors and not DI alone.** A DI or runtime switch keeps the fake code (and its base URLs and canned tokens) inside
the release APK; one wrong flag reaches a fake server. With flavors the fake modules are not on the `prod*` classpath at
all, so prod code cannot reference them (compile error) and R8 cannot accidentally keep them. DI is still used for the
binding itself and for tests:

| Concern | `prod` flavor (`src/prod/kotlin`) | `fake` flavor (`src/fake/kotlin`) | Tests |
|---|---|---|---|
| Endpoints | Hilt `EndpointsModule`: Google Health API, Google OAuth, `auth.openai.com`, `api.openai.com` (HTTPS only) | `EndpointsModule` starting the in-process fake servers on 127.0.0.1 (or `10.0.2.2` for an external fake) | `@TestInstallIn` replacing `EndpointsModule` with a `MockWebServer` URL |
| Auth | `AuthorizationClient` + SIWC | fake token issuers in `:fakes:*` | fakes |
| Clock | `SystemAgentleClock` | system clock + debug-menu time travel | `TestAgentleClock` |
| Network security config | `res/xml/network_security_config.xml` in `src/prod`: no cleartext | `src/fake`: cleartext only for 127.0.0.1 / 10.0.2.2 | - |
| Dependencies | none extra | `fakeImplementation(project(":fakes:googlehealth-server"))`, `fakeImplementation(project(":fakes:chatgpt-server"))` | `testImplementation` of the same fakes |

Guards: (1) a `verifyNoFakesInProd` task (wired into `check` and `assembleProdRelease`) that walks
`prodReleaseRuntimeClasspath` and fails on any `project(":fakes:*")`; (2) a release unit test asserting that every bound
base URL is `https` and not loopback; (3) no `BuildConfig.FAKE` branches in shared code. Debug-only tooling
(LeakCanary, StrictMode, debug menu) goes into `debugImplementation` / `src/debug`.

### 8.5 Fakes and contracts

* Fake servers are JVM modules on `mockwebserver3` (the same engine verified in 5.2), so they run in plain JVM tests,
  under Robolectric, and in the `fake` app flavor.
* Fakes must not import the client DTOs. They encode the documented contract independently (docs 05/06), so a DTO bug
  in the client shows up as a failing contract test instead of being mirrored by the fake. Recorded real responses
  (sanitized) become golden files for both sides.
* The testing agent (doc 08, still being written when this report was finished) prototyped `FakeHealthServer` and
  `FakeChatGptServer` in a `:testing:fixtures` module (package `com.agentle.testing.fixtures`, in this session's
  scratchpad `proto/`). The names here (`:fakes:googlehealth-server`, `:fakes:chatgpt-server`, package root
  `dev.agentle`) are the same idea split per backend so the app can include them per flavor. The integration agent
  should pick one module naming and one package root (it fixes every `namespace` and the applicationId).

### 8.6 The Clock abstraction

`AgentleClock` lives in `:core:time` (implemented and tested in the JVM check):

```kotlin
interface AgentleClock {
    val wall: kotlin.time.Clock          // stable since Kotlin 2.3
    fun zone(): kotlinx.datetime.TimeZone // user's current zone; changes at runtime
    fun elapsed(): kotlin.time.Duration   // monotonic; Android: SystemClock.elapsedRealtimeNanos()
    fun now(): Instant = wall.now()
    fun today(): LocalDate = now().toLocalDateTime(zone()).date
}
```

Rules:
1. Store instants as UTC epoch millis plus the zone id at capture time; compute local days with `dayBounds()` (23 h /
   25 h DST days are tested).
2. Durations, timeouts, rate limits and JITAI burden windows use `elapsed()`, so a user changing the wall clock cannot
   break them (tested).
3. React to `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` by re-planning schedules (doc 02).
4. Tests use `TestAgentleClock` (`advanceBy`, `setWallClock`, `setZone`). For coroutine-heavy engine tests, back the clock
   by `TestCoroutineScheduler.currentTime` (needs `@OptIn(ExperimentalCoroutinesApi::class)`) so there is one virtual
   time source.
5. WorkManager: adapt `AgentleClock` to `androidx.work.Clock` via `Configuration.Builder.setClock()` (since 2.9.0
   [AX:work]) so WorkManager tests and production share the same time.
6. Hilt: production binding in `:app`; tests replace it with `@TestInstallIn(replaces = [ClockModule::class])`
   (spike `FixedClockModule`).
7. Enforcement: detekt `ForbiddenMethodCall` for `java.lang.System.currentTimeMillis`, `kotlin.time.Clock.System.now`,
   `java.time.Instant.now`, `java.time.LocalDate.now`, `java.util.Calendar.getInstance`,
   `android.os.SystemClock.elapsedRealtime`, with `:core:time` and the `:app` binding excluded.

### 8.7 minSdk (dependency view only; docs 01/02/05 decide)

Library floors: WorkManager 2.12 and Health Connect 1.2 alphas need 24, Media3/browser/SQLCipher 23, LeakCanary 3 alphas
26 [AX:work][AX:health-connect][AX:media3][AX:browser][SQLCIPHER][LEAK-CL]. Robolectric covers 23-37. With minSdk >= 26,
java.time is native and core-library desugaring is optional. Nothing in the recommended dependency set needs more than
24, so the choice inside 26-31 rests on platform APIs: doc 01 recommends 29, doc 02 prefers 31 and accepts 29. The
catalog draft therefore uses `minSdk = "29"`, marked PROVISIONAL; the spike used 28 as a placeholder.

## 9. Uncertainties

* UNVERIFIED (environment): every Android build step of the spike, including Hilt Android + Room 3 + Robolectric 4.17 +
  Roborazzi on AGP 9.3.3, Lint, R8 release build; Robolectric runtime per SDK level.
* UNVERIFIED (metadata unreachable): latest `play-services-location`, `play-services-auth` beyond 22.0.0, `googleid`,
  `desugar_jdk_libs` beyond 2.1.5, AGP 9.5 preview numbers, the KGP version AGP 9.3/9.4 depends on.
* AGP 9.4.0 + Kotlin 2.4.20 is untested by JetBrains' matrix; the R8 `.kotlin_module` naming issue fixed in AGP 9.3.3
  resurfaced for Coil on 9.4.0 [COIL-CL][AGP-93].
* detekt 2.0 is alpha (config keys and rule names may change before 2.0.0); Compose detekt rules on detekt 2 UNVERIFIED.
* Studio Database Inspector with `BundledSQLiteDriver`; AGP 9 DSL for adding Room schemas to unit-test assets.
* Retrofit's changelog links now point to `github.com/lysine-dev/retrofit`; the reason is not visible here.
* Coil 3.6.3's `coil-network-okhttp` is compiled against OkHttp 4.12.0 (POM); with OkHttp 5.5.0 at runtime it is
  UNVERIFIED. Retrofit 3.0.0, also compiled against 4.12.0, works with 5.5.0 in the JVM check.
* `androidx.health.connect:connect-testing` has only alphas (1.0.0-alpha04, 2026-08-12) [AX:health-connect].
* The Room 3 Gradle plugin's implementation artifact name; the catalog uses the plugin-marker coordinates
  (`androidx.room3:androidx.room3.gradle.plugin`) for build-logic.
* Gradle 9.7.1 `distributionSha256Sum` comes from nowinandroid's wrapper [NIA]; the spike wrapper carries the same value
  and its download on 2026-10-01 completed (`.ok` marker). The `.sha256` file on services.gradle.org redirects to
  `downloads.gradle.org`, which the proxy refused when re-checked.

## 10. What the integration agent should do next

1. Copy `docs/research/libs.versions.toml.draft` to `gradle/libs.versions.toml`, set the wrapper to Gradle 9.7.1
   (`distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`, see section 9), and
   create `build-logic` with the plugins in 8.3.
2. Run the Android spike on a machine with Google Maven (`./gradlew assembleDebug assembleRelease testDebugUnitTest
   lintDebug detekt spotlessCheck`, then `recordRoborazziDebug`) and paste the results into section 5.1. Run the
   5 tests once with `@Config(sdk = [26, 30, 33, 34, 35, 36, 37])` to fill the runtime column of section 6.
3. Create the modules in 8.2 in this order: `:core:model`, `:core:common`, `:core:time` (copy the JVM-check code),
   `:core:testing`, `:core:network` (copy), `:connectors:api`, `:core:database`, `:data:events`, then the rest.
4. Add `verifyNoFakesInProd` and the forbidden-dependency check before the first fake module lands.
5. Resolve naming with doc 08 (`:testing:fixtures` vs `:fakes:*`) and minSdk with doc 01.
6. Re-check AGP 9.4.x and Kotlin 2.5.0 compatibility in December 2026 (both catalog entries carry a comment).
