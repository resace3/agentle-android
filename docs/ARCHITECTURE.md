# Agentle architecture

Status: v1 baseline, 2026-10-02; revised the same day to apply the architecture red team (`docs/ARCHITECTURE_ISSUES.md`
lists every finding, its resolution and what is still open). Synthesized from the research reports in
`docs/research/` (01-10). Where this document and a research report disagree, this document is the decision; the
report is the evidence. Section numbers like "[R10 §8]" point to research report 10, section 8. UNVERIFIED marks a
fact that no primary source confirms.

## 0. Product in one paragraph

Agentle is a native Android "Personal Data Hub". The phone collects user-authorized data from Android
(usage access, notification listener, device state, location, activity, calendar, Health Connect, and so on) and
from the user's wearable through the **Google Health API** (the successor of the Fitbit Web API, which shuts off
2026-10-30; product decision: no code targets the legacy Fitbit Web API). Everything is normalized into one typed
event model and stored locally in an encrypted Room database. A local feature engine derives daily and rolling
features; a local insight engine finds candidate patterns; a deterministic JITAI engine evaluates user-, NL- and
AI-created rules and delivers notifications, in-app cards, images, TTS voice and short locally composed videos.
Optional AI analysis uses **Sign in with ChatGPT (SIWC)** so the user's ChatGPT plan pays for inference. An AI request
carries only the data categories the user granted, minimized to what its purpose needs. A request the user starts is
previewed before it is sent; a background request (for example refilling a JITAI's pool of AI text) is sent only
within a standing consent that the user previewed and approved for that purpose (§9.1).

## 1. Non-negotiable rules

1. Local-first: the Room database is the only persistent store of personal data. No server, no cloud sync.
2. Nothing leaves the device except (a) Google Health API requests that fetch the user's own data and (b) AI requests
   built by `:ai:context` from granted categories (§9), which fail closed. Every HTTP client sets an egress
   allow-list, `HttpClientConfig.allowedHosts`: `HttpClientFactory` checks each request's exact host as the first
   application interceptor and again at the network layer, and a client with an allow-list never follows redirects.
   Hosts in `prod`: `health.googleapis.com` (Google Health client), `auth.openai.com` (SIWC auth client: discovery,
   JWKS, token, revocation) and `api.openai.com` (SIWC API client: Responses, models). In `fake`: `127.0.0.1` only.
   Google authorization runs inside Play services and the ChatGPT sign-in page in the browser, outside Agentle's HTTP
   clients; §14 lists every egress path. Values from the Google Health API are never sent to AI in v1 (§9.2).
3. Supported public APIs only. No root, no hidden APIs, no accessibility-service scraping, no exploits.
4. Unavailable capabilities are reported as unavailable with a reason, never faked.
5. Tokens never go to Room columns, logs, analytics, crash reports, diagnostics exports or backups. No exception
   message or `toString()` goes into an `AppError`, log line, stored row or diagnostic entry, only the exception class
   name and an error code: decode errors embed their input (§15).
6. AI output is data. It is parsed and schema-validated; it is never executed. AI-made rules and proposals become
   active only after the user approves them. AI-produced text is never shown or delivered unless the user approved
   that item or it passed `AiTextPolicy` (§9.5), and it is always labelled as AI-generated.
7. Production builds cannot reach fake servers: `:fakes`, `:core:testing` and test-only libraries are absent from
   every `prod*` runtime classpath (flavors), and build checks enforce it (§3.1, §4).
8. One time source (`AgentleClock`: wall time, zone, monotonic time). Outside `:core:time` and the app's clock
   binding, no `System.currentTimeMillis()`, `Instant.now()`, `Date()`, `ZoneId.systemDefault()`,
   `TimeZone.getDefault()`, `TimeZone.currentSystemDefault()`, `SystemClock` reads, `java.time.Clock.system*` or
   `TimeSource.Monotonic`; detekt checks this with type resolution in CI. Code uses the clock's zone, never the JVM
   default.
9. No always-on foreground service. Collection is event-driven, periodic (WorkManager) or foreground-only.
10. Single process: no component declares `android:process`, and `work-multiprocess` is not used. The process-wide
    mutexes for token refresh, stream sync and JITAI commits rely on it.

## 2. Platform and toolchain

| Item | Decision | Why |
|---|---|---|
| minSdk | **29** (Android 10) | ACTIVITY_* usage events, ACTIVITY_RECOGNITION runtime permission, background-location split, Health Connect floor 28 [R01 §9, R07 §8.7]. API 29-30 differences (legacy Bluetooth permissions, no expedited work without a foreground service, `TelephonyCallback` only on 31+) are handled by version gates. Agentle never schedules exact alarms, on any API level. |
| targetSdk / compileSdk | **37** (Android 17) | Latest stable. Behavior changes for 37 are handled (OTP SMS delay, memory limiter, strict SQL in CP2, cross-profile loopback) [R01, R02]. |
| Gradle / AGP / Kotlin / KSP | 9.7.1 / 9.3.3 / 2.4.20 / 2.3.12 | Newest set inside every vendor's support matrix [R07 §0]. AGP 9 built-in Kotlin, new DSL only, KSP only. |
| JDK | 21 runs Gradle and tests; bytecode 17 | Robolectric SDK 36/37 need Java 21. |
| UI | Jetpack Compose (BOM 2026.09.00), Material 3, Navigation 3 (1.2.0) | |
| DI | Hilt 2.60.1 (+ androidx.hilt 1.4.0 for workers and ViewModels) | |
| DB | Room 3.0.3 with `SQLCipherDriver` (SQLCipher for Android 4.19.1) in every app variant; `BundledSQLiteDriver` only in JVM and Robolectric tests (DAO and migration tests) | Encryption at rest (§5.4) [R04 §3.2]; KSP-only matches AGP 9 [R07 §4]. |
| Settings | DataStore 1.2.1 | |
| Network | OkHttp 5.5.0, Retrofit 3.0.0, kotlinx.serialization 1.11.0 (the only JSON library) | |
| Background | WorkManager 2.12.0 (on-demand init, `HiltWorkerFactory`, `Configuration.setClock`) | |
| Crypto | Tink 1.23.0 `AndroidKeystore` helper: `generateNewAes256GcmKey(alias)` and `getAead(alias)` give AES-256-GCM keys in the Android Keystore that seal the database key and the token vault directly. `AndroidKeysetManager` is banned (detekt `ForbiddenImport`) because it silently falls back to a cleartext keyset. `security-crypto` is deprecated and not used | |
| Media | Media3 1.11.1 Transformer for video; Android `TextToSpeech` for voice | |
| Quality | Android Lint, detekt 2.0.0-alpha.6 (1.23.8 is incompatible with AGP 9), Spotless + ktlint 1.8.0, Kover 0.9.11 | |
| Tests | JUnit 6.1.3 (JVM modules only), JUnit 4 + Robolectric 4.17 + Compose UI test v2 + Roborazzi 1.76.0 (Android modules; `org.junit.jupiter` imports are forbidden there, because such a test compiles and never runs), AndroidX Test + UI Automator (instrumented), mockwebserver3, Turbine, Truth (the one assertion library), MockK only at boundaries | |

Package root `dev.agentle`; applicationId `dev.agentle.app` (`dev.agentle.app.fake` for the fake flavor).

## 3. Module map

Pure Kotlin/JVM modules hold all domain logic and are built and tested on any JVM in seconds. Android modules sit
at the edges. JVM modules never depend on Android modules.

| Module | Type | Responsibility |
|---|---|---|
| `:core:model` | JVM | `PersonalEvent` + typed payloads, `EventType`, `DataSourceId`, `Insight`, `CapabilityDescriptor`, `PermissionState`, `ConnectorState`, `DataCategory` and source families, lineage, `UntrustedText`, media artifact model, user goals. kotlinx.serialization types. |
| `:core:common` | JVM | `AppError` hierarchy + `Outcome`, dispatcher qualifiers, `Logger` facade with `Redactor`, `SingleFlight` (a cancelled caller never cancels the shared run; with a scope, the run is detached from every caller), ids. |
| `:core:time` | JVM | `AgentleClock` (wall + zone + monotonic), `EngineDay` (04:00 rollover), DST-safe day bounds and windows. |
| `:core:network` | JVM | OkHttp factory with the egress allow-list (`HttpClientConfig.allowedHosts`), JSON config, error-body sniffing (Content-Type check), retry/backoff policy, `AccessTokenSource` + `withAccessToken` (one refresh after 401), sliding-window rate limiter. |
| `:core:oauth` | JVM | Generic OAuth 2.0 + PKCE + state/nonce machinery: request builder, `LoopbackCallbackServer` (RFC 8252 §7.3), callback validation (constant-time state compare), code exchange, rotating refresh, revocation. Used by SIWC. |
| `:connectors:api` | JVM | Connector SPI (`Connector`, `ConnectorMetadata`, `SyncCursor` with account id and fetch generation, `StreamCoverage`, `SyncResult`, `EventSink` with `commit`, diff-semantics `replaceWindow` and `importFloor`; §6.1), capability registry model. |
| `:connectors:googlehealth` | JVM | Google Health API v4 client (Retrofit), lenient DTOs, mapping to events, window-based incremental sync, rate limiting, error policy [R05 §7]. Authorization is a port (`GoogleHealthAuthorizer`) modelled on Play services `AuthorizationClient` (§7.1). |
| `:ai:api` | JVM | `AiProvider` + `AiCapabilities`, the sealed `AiRequestEnvelope` (only `:ai:context` can create one), response models, schemas (`InsightSchema`, `JitaiProposalSchema`, `JitaiRuleSchema`, `MediaPromptSchema`), output validation and `AiTextPolicy` (§9.5). |
| `:ai:chatgpt` | JVM | SIWC: discovery, dynamic client registration, `SignInCoordinator`, `SiwcAuthorizer`, `SiwcSessionManager` (Mutex, credential generation, non-cancellable refresh), ID-token verification (Nimbus), HTTP profiles, `ResponsesClient` (SSE, field whitelist, `beforeSend` hook), `ModelCatalog`, `SiwcErrorMapper`, `ChatGptAiProvider` [R06 §8]. `BrowserLauncher` and `CredentialStore` are ports. |
| `:ai:context` | JVM | `ContextSelectionEngine`: purpose -> granted categories and source families -> aggregates -> preview -> sealed `AiRequestEnvelope`; consent reads that fail closed; standing consents for background purposes; `UntrustedText` serialization (§9). |
| `:analytics:features` | JVM | Feature catalog (single versioned source) and feature computation: daily features + rolling windows 1/3/7/14/30/90 d + intra-day features; reads through a `FeatureDataSource` port (fused series, one consistent snapshot per resolve, active account only; §10). |
| `:analytics:insights` | JVM | Local candidate patterns (pre-registered hypotheses, stratified permutation tests, BH q-values, non-causal templates) and AI interpretation orchestration [R10 §14-15]. |
| `:jitai:dsl` | JVM | `JitaiDefinition`, rule AST (sealed, `type` discriminator), strict JSON codec, validator (S0-S10, E001-E099), deterministic renderer [R10 §3-4, §11]. |
| `:jitai:engine` | JVM | Three-valued evaluator, feature snapshot, decision keys, safety gates G01-G16, arbitration, two-phase delivery state machine, timer planner and `replan` (what to schedule; not how), snapshot scrub for deleted categories [R10 §6-9]. |
| `:fakes` | JVM | `FakeGoogleHealthServer` (API subset; validates bearer tokens only) and `FakeChatGptServer` on mockwebserver3 with scenario control, the scripted `FakeGoogleAuthorizer`, `FakeBrowserLauncher`, `FakeAiProvider` and `FakeChatGptAuthClient` (unit tests only), deterministic synthetic data generator (SplitMix64; 90-day, empty, sparse, high-volume users) [R08 §5-6]. Never on a `prod*` classpath. |
| `:core:testing` | JVM | `TestAgentleClock` (default zone Australia/Adelaide; it can run on a `TestCoroutineScheduler`, so virtual time drives wall and elapsed time), a sleeper derived from the clock, coroutine test helpers, in-memory port fakes, Truth helpers. |
| `:core:database` | Android | Room 3 database on SQLCipher, entities, DAOs, migrations, exported schemas, `DataCategoryRegistry`. |
| `:core:security` | Android | Database key manager and `SecretVault` (token blobs), both sealed with Tink `AndroidKeystore` keys; install id. |
| `:core:datastore` | Android | DataStore: settings, the AI consent grant store (§9.1), retention, quiet hours, "permission requested" flags. |
| `:core:ui` | Android+Compose | Theme, design system, status chips (icon + text, never color only), shared components, string formatting. |
| `:data` | Android | Repositories implementing the JVM ports: `EventRepository` (serialized writer, ingest, dedup, retention), `FeatureRepository`, `InsightRepository`, `JitaiRepository` (decision-commit runner), `ConnectorStateRepository`, `AiAuditRepository`, `MediaRepository`, `DeletionService`, `DiagnosticsRepository`. Exposes ports only (§3.1). |
| `:connectors:android` | Android | On-device collectors, `CoverageRecorder` use, `CapabilityStateResolver`s (Permission Center backend), Settings intents, `PendingIntentFactory`, Health Connect reader. |
| `:interventions` | Android | `NotificationDeliverer` (channels, actions, deep links, tags = decision key), in-app cards, local image renderer, `TtsVoiceRenderer`, `Media3VideoComposer`, media cleanup. |
| `:background` | Android | WorkManager workers (`@HiltWorker`), `WorkScheduler` gateway (unique names), `ScheduleReconciler`, boot/time/package receivers. |
| `:feature:onboarding` | Android+Compose | Onboarding. |
| `:feature:hub` | Android+Compose | Dashboard, Timeline (paged), Data Sources, Permission Center. |
| `:feature:insights` | Android+Compose | Insights, JITAI list (Active/Suggested/Paused/History), JITAI builder (manual + natural language), proposal review. |
| `:feature:connections` | Android+Compose | Wearable (Google Health) screen, ChatGPT screen, AI Data Sharing screen. |
| `:feature:settings` | Android+Compose | Settings, retention, deletion, background behavior, notification settings, diagnostics, debug tools (debug builds only). |
| `:app` | Android app | Hilt root, `MainActivity`, Navigation 3 graph, WorkManager `Configuration.Provider`, flavor bindings (endpoints, fakes), manifest. |

### 3.1 Forbidden dependencies

`ModuleGraphRules` (build-logic, applied with the quality conventions) fails the build, once every project is
evaluated, when:
- a JVM module depends on an Android module, in any configuration;
- a module depends on `:fakes` outside test configurations, except `:app` through its `fake*` configurations (any
  module may use `:fakes` in tests);
- a `:feature:*` module depends, outside tests, on `:core:database`, `:connectors:android`,
  `:connectors:googlehealth`, `:ai:chatgpt` or `:background` (UI reaches data only through `:data` ports);
- `:jitai:engine` depends on `:interventions` or `:ai:chatgpt` (the engine decides; delivery and AI calls live
  elsewhere).

The same rules are also checked on the resolved compile and runtime classpaths, so a transitive edge cannot bypass
them. `:data` depends on `:core:database`, `:core:security` and `:core:datastore` with `implementation`, never `api`,
so `:feature:*` modules cannot reach DAOs or the vault through it. The rule of §14 that `:ai:*` modules never reach
export, deletion, intents, network calls or settings is not yet written down as a module list or checked.

### 3.2 Fakes encode the wire contract

A fake never reuses the client it fakes. `dev.agentle.fakes.chatgpt` may implement ports declared in `:ai:chatgpt`
(such as `BrowserLauncher`), but uses no `:ai:chatgpt` DTO, serializer, parser or constant: it serves the R06
§9.3/§9.4 bodies as literal JSON fixtures, and golden tests feed the same literals to the client's parser. Planned for
the integration wave: split `:fakes` into `:fakes:servers` (mockwebserver3 and kotlinx-serialization-json only,
literal bodies) and `:fakes:ports` (port fakes that depend on API modules only); move `BrowserLauncher`,
`CredentialStore` and the `GoogleHealthAuthorizer` port into `:ai:api` and `:connectors:api`; and check in
`ModuleGraphRules` that `:fakes:servers` never resolves a client module.

## 4. Build variants and fakes

- Build types `debug`, `release`. Flavor dimension `backend`: `prod`, `fake`. `fakeRelease` is disabled.
- `prod` binds real endpoints (`https://health.googleapis.com/`, Google authorization via Play services
  `AuthorizationClient`, `https://auth.openai.com`, `https://api.openai.com/v1`), each client with its egress
  allow-list (§1 rule 2), and a `network_security_config` with no cleartext. The real Google Health API source sits
  behind a flag that stays off until the live spike passes (§7.1, §18).
- `fake` starts the in-process fake servers on `127.0.0.1` (cleartext allowed only for loopback; clients allow only
  loopback) and binds only server-side fakes plus `FakeBrowserLauncher` and the scripted `FakeGoogleAuthorizer`, so
  the real `SiwcAuthorizer`, `SiwcSessionManager`, `ChatGptAiProvider` and Google Health client run in journeys.
  `FakeAiProvider` and `FakeChatGptAuthClient` are for unit tests only; there is no Google authorization-code + PKCE
  fake, because production never runs that flow. Synthetic datasets are anchored to the device's real now; the app
  clock is never moved to match the data. The flavor exposes the debug panel: scenario selection, synthetic dataset
  generation, JITAI trigger simulator, time override, force sync / force worker, clear database, failure injection.
- Guards: `verifyNoFakesInProd` (wired into `check`) walks every `prod*` runtime classpath and rejects `:fakes`,
  `:core:testing` and test-only artifacts (mockwebserver3, coroutines-test, Turbine, Robolectric, JUnit); CI checks
  the `prodDebug` dex and the `prodRelease` R8 mapping for fake and test classes; `verifyNoSecrets` (JVM CI job)
  rejects Google OAuth client secrets (`GOCSPX-`), OpenAI and Google API keys and private keys anywhere in the tree;
  a prod unit test asserts every bound base URL is https and non-loopback; a source-tree guard test rejects foreign
  OAuth client ids (`app_[A-Za-z0-9]{20,}`) and the private `chatgpt.com/backend-api` host.
- Failure injection (`FailureInjector` port, no-op in `prod`): database exception, Google Health HTTP error,
  OpenAI HTTP error, expired token, network loss, invalid AI output, permission loss. Scripted scenarios of the fakes
  add: `FakeGoogleAuthorizer` outcomes (the R05 §8.1 tokens, partial scopes, resolution required in the background,
  revoked access, status-code failures, network error, Play services missing); `FakeChatGptServer` clock skew (an
  injected offset), captive portal and TLS interception on discovery, token and refresh, and a crash between the
  refresh response and the vault write; and a fault-injecting database key manager (transient `KeyStoreException`,
  `AEADBadTagException`, missing or truncated key file, missing alias, "file is not a database").
- Planned for the integration wave: a minified build type for the fake flavor (`initWith(release)`, debug signing)
  that runs a journey subset and the golden-corpus decode suite, and a `prodRelease` emulator smoke test (install,
  cold start, open the encrypted database, run one worker).

Local builds without Google Maven: `-Pagentle.jvmOnly=true` includes only the JVM modules and the JVM half of
build-logic, so the domain core builds and tests on any JVM.

## 5. Event model and storage

### 5.1 PersonalEvent

```kotlin
data class PersonalEvent(
  val id: EventId,                 // UUIDv7-like, generated locally
  val type: EventType,             // closed enum (APP_FOREGROUND ... VIDEO_GENERATED, plus device-state types)
  val source: DataSourceId,        // connector + stream, e.g. android.usage, googlehealth.steps
  val startTime: Instant,
  val endTime: Instant?,
  val zoneId: String,              // zone at capture time
  val payload: EventPayload,       // sealed, typed, @Serializable with a "kind" discriminator
  val confidence: Double?,
  val dedupKey: String,            // deterministic natural key (source-specific), see 5.3
  val metadata: EventMetadata,     // ingestedAt, schemaVersion, provenance (package/device), sensitivity
)
```

Payloads are typed classes (`AppSessionPayload(packageName, durationMs)`, `NotificationPayload(package,
category, channelHash, hasContent, title?/text? only if notification_content is enabled)`, `StepsPayload(count)`,
`HeartRatePayload(bpm)`, `SleepSessionPayload(stages)`, `BatteryPayload(level, plugType, status)`, ...).
Schema evolution: each payload class has a `schemaVersion`; decoders accept older versions and upgrade them
in code; unknown future kinds decode to `UnknownPayload(raw)` and are kept, never dropped.

### 5.2 Room schema (v1)

| Table | Key columns | Notes |
|---|---|---|
| `event` | `seq` INTEGER PK AUTOINCREMENT, `id` UNIQUE, `type`, `source`, `start_ms`, `end_ms`, `zone_id`, `dedup_key` UNIQUE, `subject` (package / category / device hash), `value_num`, `payload_json`, `payload_version`, `confidence`, `ingested_ms`, `sensitivity` | Indexes: (`type`,`start_ms`), (`source`,`start_ms`), (`subject`,`start_ms`), (`start_ms`). `value_num`/`subject` are typed projections so features aggregate in SQL without parsing JSON. `seq` is the ingestion watermark for event-driven JITAI checks. |
| `raw_source_record` | `source`, `external_id`, `fetched_ms`, `body_hash` | Hash and provenance only (no raw bodies) to detect changed upstream records. |
| `daily_summary` | (`date`, `metric`) PK, `value`, `coverage`, `computed_ms` | Engine-day based. |
| `derived_feature` | (`feature_id`, `window`, `anchor_date`) PK, `value`, `status` (OK/UNKNOWN/STALE), `computed_ms` | Rolling windows. |
| `insight` | `id`, `kind`, `title`, `finding`, `support_json`, `period_start/end`, `strength`, `origin` (LOCAL/AI), `created_ms`, `state` | |
| `jitai_definition` | `id`, `version`, `json` (canonical `JitaiDefinition`), `enabled`, `state`, `origin` (MANUAL/NL/AI_DISCOVERED), `created/modified_ms`, `expires_ms` | Versioned; history rows are immutable. |
| `jitai_decision` | `decision_key` UNIQUE, `jitai_id`, `jitai_version`, `state` (DECIDED/DELIVERING/DELIVERED/DELIVERY_UNCERTAIN/SUPPRESSED/EXPIRED), `lease_until_ms`, `decided_ms`, `delivered_ms`, `channel`, `trace_json` | Two-phase delivery record [R10 §8]. |
| `intervention_outcome` | `decision_key`, `outcome` (OPENED/DISMISSED/SNOOZED/ACTION/IGNORED), `at_ms`, `metric_json` | |
| `jitai_eval_log` | `id`, `at_ms`, `trigger`, `result`, `trace_json` | Ring-buffered (retention 30 d). |
| `connector_state` | `connector_id` PK, `enabled`, `connection`, `permission_summary`, `last_success_ms`, `last_attempt_ms`, `last_error_code`, `sync_state` | |
| `sync_cursor` | (`connector_id`, `stream`) PK, `last_success_cursor`, `last_attempt_cursor`, `sync_start_ms`, `sync_end_ms`, `last_error` | Committed in the same transaction as the data it covers. |
| `google_health_state` | `account_hint`, `granted_scopes`, `connected_ms`, `disconnected_ms`, `last_full_resync_ms` | No tokens. |
| `ai_request` | `id`, `purpose`, `categories`, `time_range`, `raw_events_sent` (bool), `aggregates_sent` (bool), `model`, `status`, `error_code`, `created_ms`, `bytes_sent` | Metadata only; no payload copy. |
| `ai_result_meta` | `request_id`, `schema`, `valid`, `validation_errors`, `produced_entity_id` | |
| `media_artifact` | `id`, `created_ms`, `source_jitai_id`, `decision_key`, `method` (LOCAL_RENDER/TTS/MEDIA3), `local_uri`, `mime`, `size_bytes`, `expires_ms` | Files live in app-private storage; cleanup by quota and age. |
| `user_goal` | `id`, `text`, `metric`, `target`, `created_ms`, `active` | |
| `user_log` | `id`, `at_ms`, `kind` (mood/energy/note), `value`, `note` | Manual input; also mirrored as `USER_LOG` events. |
| `permission_snapshot` | `capability_id`, `state`, `blockers`, `at_ms` | Written on change only. |
| `diagnostic_log` | `id`, `at_ms`, `severity`, `component`, `event_code`, `message` (sanitized) | Ring buffer, 5,000 rows. |

Settings that are not records live in DataStore (retention choice, AI category toggles, quiet hours, global caps,
collection profile, onboarding state, install id).

Migrations: schema exported to `core/database/schemas/`; every version bump ships a `Migration` (or
`AutoMigration`) and a migration test. Destructive fallback is never enabled.

### 5.3 Identity and deduplication

`dedupKey` is deterministic per source: Android usage `usage|<pkg>|<eventType>|<timestampMs>|<instanceHash>`;
notification `notif|<sbnKeyHash>|<postTime>|<posted/removed>`; Google Health `gh|<dataType>|<dataPoint.name>` for
identifiable types and `gh|<dataType>|<start>|<end>|<dataSourceHash>` otherwise; battery samples
`battery|<minuteBucket>`. Ingestion is `INSERT ... ON CONFLICT(dedup_key) DO UPDATE` only when the payload hash
changed (late or corrected records), otherwise ignore. Same sync twice, overlapping windows, partial retry and late
records therefore converge to the same rows.

### 5.4 Encryption at rest

Decision [R04 §3.2]: the database is encrypted with SQLCipher for Android 4.19.1 through Room 3's `SQLCipherDriver`
in every app variant (JVM and Robolectric tests use the unencrypted driver). Reason: credential-encrypted storage is
readable from first unlock until reboot, so file-based encryption alone does not protect a copied lifelog database,
and a wrapped key gives crypto-erasure on deletion.
- Key: a random 32-byte data key, sealed by a Tink `AndroidKeystore` AEAD under alias `agentle.kek.db.v1` with AAD
  `agentle/db-dek/v1|agentle.db`, written atomically (tmp, fsync, rename) to `noBackupFilesDir/keys/db-dek.v1.bin`,
  passed to SQLCipher as a raw `x'<64 hex>'` key (no PBKDF2).
- Keystore keys never require user authentication or an unlocked device: workers run while the phone is locked.
  One retry on a Keystore failure, then the "local data unreadable" reset flow; never a plaintext fallback.
- SQLCipher's SQL statement logging is disabled before its first class loads; `PRAGMA secure_delete=ON` and
  `cipher_log_level=NONE` on every open.
- Tokens and the SIWC client registration live in a separate Tink-sealed vault (`agentle.kek.vault.v1`), never in
  the database (§14).

### 5.5 Retention and deletion

Retention: keep indefinitely (default) / 30 d / 90 d / 1 y, applied per event family by a daily worker; JITAI
definitions, decision rows needed for active cooldowns/caps (last 8 days) and user goals are exempt.
Deletion actions are separate, each a single transaction plus file deletion, each verified by a post-delete count
query returned to the UI: delete wearable data, delete Android-collected data, delete insights, delete intervention
history, delete generated media (rows + files), delete all personal data (all tables except schema metadata, plus
media files, plus token vault, plus DataStore personal keys). Disconnecting the wearable never deletes data;
deleting never disconnects.

## 6. Connectors and the Permission Center

### 6.1 Connector SPI

```kotlin
interface Connector {
  val metadata: StateFlow<ConnectorMetadata>   // id, name, connection, permission summary, last success/attempt,
                                               // last error (AppError code), supported event types, sync state
  val capabilityIds: List<String>              // ids from the capability registry
  suspend fun sync(trigger: SyncTrigger): SyncResult     // idempotent; commits data + cursor atomically
  suspend fun setEnabled(enabled: Boolean)
}
```

### 6.2 Capability registry

`docs/research/capabilities.json` (53 capabilities, ids stable) is compiled into a Kotlin registry
(`CapabilityRegistry`), each with category, APIs, runtime permissions, special access, minSdk, background support,
collection modes, Play policy and planned status (`IMPLEMENT`, `IMPLEMENT_DEBUG_ONLY`, `DEFER`,
`DOCUMENT_UNAVAILABLE`). The Permission Center UI is driven by registry + live state, never hard-coded lists.

### 6.3 Permission state model

Ten states: `ALLOWED`, `DENIED`, `DENIED_PERMANENTLY`, `REQUIRES_SETTINGS`, `RESTRICTED_BY_ANDROID`,
`UNAVAILABLE`, `PARTIALLY_ALLOWED`, `FOREGROUND_ONLY`, `BACKGROUND_ALLOWED`, `UNSUPPORTED_ON_DEVICE`, plus a
blocker list, resolved with the precedence in [R01 §5.1] by per-mechanism `CapabilityStateResolver`s using only
public APIs (checkSelfPermission + rationale + "requested once" flag, `AppOpsManager.checkOpNoThrow` for usage
access, `NotificationManagerCompat.getEnabledListenerPackages`, `AlarmManager.canScheduleExactAlarms`, Health
Connect `PermissionController`, `LocationManager.isLocationEnabled`, `BluetoothAdapter.isEnabled`, ...). States are
re-evaluated on `onResume`, on app-op/listener callbacks, and before every collection run. A missing permission
yields a state, never an exception: every collector catches `SecurityException` and reports
`PermissionDenied`.

### 6.4 Android collectors (v1 = registry entries marked IMPLEMENT)

| Collector | Mechanism | Cadence |
|---|---|---|
| Usage events + foreground sessions + screen interactive/keyguard (28) | `UsageStatsManager.queryEvents` with a high-water mark and 10-minute overlap | WorkManager every 1-6 h by profile + on app start + before JITAI evaluation |
| Notifications (metadata; content opt-in per app, default SMS/dialer excluded) | `NotificationListenerService` (bound by the system) | Real time |
| Screen on/off, user present | Runtime receivers registered with `RECEIVER_EXPORTED` while the process lives; hints only, usage events are truth [R02] | Real time while alive |
| Battery, charging, power save, thermal | Sticky `ACTION_BATTERY_CHANGED` read + runtime receivers + periodic sample | 15-60 min |
| Connectivity, network type, Wi-Fi metadata, airplane mode | `ConnectivityManager.NetworkCallback` while alive + periodic snapshot | Change-driven + periodic |
| Bluetooth adapter + connected devices (hashed addresses) | Manifest receiver for ACL events (exported, sender is Bluetooth UID) | Event-driven |
| Audio: volume/ringer, output devices, headset | `AudioManager` snapshot + `AudioDeviceCallback` while alive | Periodic + change |
| Time zone / time / locale changes, boot | Manifest receivers (`TIMEZONE_CHANGED`, `TIME_SET`, `LOCALE_CHANGED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) | Event-driven |
| Location (foreground, coarse default) | Fused/`LocationManager` current location while app is visible; places classified locally (home/work/other) | Foreground only (background DEFER) |
| Activity recognition | Activity Recognition Transition API via explicit mutable PendingIntent | Event-driven |
| Steps | Recording API (Play services) when available; Health Connect on-device steps | Periodic |
| Calendar | `CalendarContract.Instances` query (READ_CALENDAR) | Periodic |
| Health Connect | `HealthConnectClient` read + changes tokens (steps, sleep, HR, RHR, exercise, weight) | Periodic; background read only with permission |
| DND state, next alarm, standby bucket, storage | System service snapshots | Periodic |
| Call state | `TelephonyCallback` (READ_PHONE_STATE) while alive | Real time while alive |
| Motion / ambient sensors | Debug-only sampling sessions started by the user | Explicit sessions |

Sensing tiers [R03 §11]: Tier 0 is the default and needs no foreground service (activity transitions, canonical
steps from Health Connect on-device steps on API 34 with extension 20+ or the Recording API, a sensor inventory).
Tier 1 (debug flag in v1) samples sensors only while an Agentle screen is visible. Tier 2 is an opt-in
"High-detail sensing" `health` foreground service with duty-cycled 10 s windows, user-started, at most 24 h per
session, capped at 288 windows and 45 min of wake lock per day, internal/debug builds only until the Play
foreground-service declaration is accepted. Sensing stores summaries, never raw streams; `TYPE_STEP_COUNTER` is
never summed into step totals.

Not available (documented with the reason): accessibility event stream, call log and SMS metadata (hard-restricted,
Play-forbidden, default-handler only), background location (DEFER until a place-based feature needs it), nearby BT
scanning (DEFER), contacts (DEFER), media sessions (DEFER), Wi-Fi network identity (needs location).

## 7. Google Health API connector ("Fitbit")

- Base `https://health.googleapis.com/v4/`, collection `users/me/dataTypes/{type}/dataPoints` with `list`,
  `:reconcile`, `:rollUp`, `:dailyRollUp` [R05 §3-4]. v1 types: steps, distance, floors (rollups only), total
  calories, heart rate, resting heart rate, sleep, exercise, weight/body fat (if authorized), paired devices.
- Authorization (production): Google Identity Services `AuthorizationClient` (play-services-auth 22.0.0) with an
  Android OAuth client: `authorize()` returns an access token; a 401 triggers one silent re-authorization; no refresh
  token is stored on the device; disconnect calls `revokeAccess` and clears local grant state. Granted scopes are
  checked after consent; missing scopes produce `PARTIALLY_ALLOWED` per data type. Live status: Google states it is
  "not onboarding new projects"; the production path is implemented against the documented contract and is **not
  live tested**.
- Authorization (fake flavor and tests): `FakeGoogleHealthServer` implements an OAuth 2.0 authorization-code + PKCE
  server (authorize, token, refresh, revoke) and the API subset; the fake authorizer drives it through `:core:oauth`,
  so every OAuth scenario in the spec (cancel, invalid/expired code, invalid state, PKCE mismatch, issuance, expiry,
  refresh success/failure, revoked, insufficient scope) is exercised by real client code.
- Sync: per stream, re-read overlapping windows (48 h for samples/intervals, 7 days for sleep/exercise/daily),
  page through `nextPageToken`, map + dedup, commit each window and its cursor in one transaction only after all
  pages succeeded. Weekly 30-day deep re-sync; backfill 14 days hot then 30-day chunks to 90 days. Rate limit:
  strict sliding windows (at most 4 requests in any second and 200 in any minute per user; a token bucket would
  allow bursts above the cap); 429 and 5xx retried with exponential backoff and jitter as coroutine
  delays (max 3), then the window is marked failed and retried next run. Malformed bodies (HTML 404/502, non-JSON
  2xx, unknown fields, int64-as-string, invalid values) are tolerated per [R05 §4.1, §8.6]: skip and count the bad
  point, never crash, never commit a half window.
- Double counting with Health Connect: one canonical source per metric (wearable API when connected); Health Connect
  records from `com.fitbit.FitbitMobile` are skipped while the API source is connected.

## 8. Sign in with ChatGPT

Implements [R06 §2, §8] exactly:
- `LoopbackCallbackServer` on `127.0.0.1:0`, `GET /auth/callback` only, exact Host/path, constant-time state compare,
  single settle, 10-minute timeout, returns a no-store "Return to Agentle" page with a package-scoped `intent://`.
- Authorization request: PKCE S256, `state`, `nonce`, `resource=https://api.openai.com/v1`, scopes
  `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`, first-time
  `client_id=dynamic_agent_client` + `agent_name_hint=Agentle`, `ext_agent_host_id=urn:uuid:<per-install>`.
  The issued `oaiapp_…` client id is persisted (encrypted) before the code is redeemed and reused afterwards.
- Plain Custom Tab (never WebView, never Auth Tab); `ACTION_VIEW` fallback.
- ID token verified with Nimbus JOSE+JWT (RS256, JWKS with refetch on unknown `kid`, iss/aud/azp/nonce/exp).
- `SiwcSessionManager.withAccessToken {}`: Mutex single-flight refresh at <=60 s remaining or once after 401;
  rotated tokens persisted before use; tokens cleared only on terminal error codes, never on network errors or 5xx.
- `ResponsesClient`: `POST /v1/responses` with `store:false`, `stream:true`, array input, `instructions` (no system
  role), a request-field whitelist, success only on `response.completed`, `response.failed` mid-stream handled,
  missing Content-Type tolerated. Models from `GET /v1/models` (`visibility=="list"`).
- States: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `NEEDS_REAUTH`, `NOT_ELIGIBLE(reason)`, `USAGE_LIMITED(until?)`,
  `UNAVAILABLE`, `ERROR` mapped by `SiwcErrorMapper` from every documented error code.
- Capabilities (introspectable `AiCapabilities`): `TEXT_REASONING=SUPPORTED`, `STRUCTURED_OUTPUT=PROMPTED_JSON`
  (json_schema is undocumented; probe once per model, otherwise plain-text JSON + local validation),
  `IMAGE_GENERATION=UNSUPPORTED` (local renderer instead), `VOICE_GENERATION=LOCAL_TTS`,
  `VIDEO_GENERATION=LOCAL_COMPOSITION`, `BACKGROUND_INFERENCE=USER_BUDGETED` (undocumented by OpenAI: background
  generations only within a user-set daily budget and never start sign-in from the background).
- Never: another product's client id, the private `chatgpt.com/backend-api`, an API-key fallback.
- Required UI copy: "Continue with ChatGPT", first-run "You're using your ChatGPT plan", "Using ChatGPT plan"
  indicator, "Manage usage" link, "Usage limit reached" dialog.

## 9. AI layer

- `AiProvider { capabilities; analyze(); generateStructured(schema); generateImage() }`. Implementations:
  `ChatGptAiProvider` (prod), `FakeAiProvider` (deterministic, fake flavor + tests), `UnavailableAiProvider`
  (disconnected: every call returns `AppError.AuthenticationRequired` without network).
- `ContextSelectionEngine.build(purpose, userQuestion)`: purpose maps to a fixed allow-list of categories and a time
  range; the user's AI category toggles intersect it; disabled categories are removed **and** a final gate re-checks
  the envelope and throws `ConsentViolation` if any disabled category is present (fail closed). Aggregates by
  default; raw events only for purposes that need them and only with per-request user confirmation. Third-party text
  (notification text, calendar titles, contact, Wi-Fi and Bluetooth names) is never sent in v1 [R04 §3.8]; the
  only free text is the user's own (questions, goals, logs) and app labels reduced to a safe character set
  (letters, digits, spaces, `.-&'`, at most 40 characters). All of it is wrapped as quoted data with an explicit
  "untrusted data" marker and never placed in `instructions`. `EgressGuard` is the only path to the OpenAI client:
  it re-checks every block against `AiSharingPolicy` (deny by default per category), truncates items to 200
  characters and 20 items, and fails closed.
- `AiRequestPreview` (purpose, categories, time range, raw events yes/no, aggregates yes/no, byte estimate) is shown
  before user-initiated requests and stored as `ai_request` metadata; payloads are not stored or logged.
- Output validation: parse as `JsonElement`, walk the schema, decode, then semantic checks (enums, max lengths,
  operators, feature references, timestamps, delivery limits); failures are recorded as codes without content.

## 10. Feature and insight engines

- Feature catalog (single source for validator, prompt catalog, renderer, schema enum): screen time (daily, by app,
  late-night 22:00-04:00, last 60 min), unlocks (keyguard hidden count), steps (daily, by hour, since midnight),
  sedentary periods, sleep (duration, midpoint, bedtime, wake time, regularity), heart rate (resting, mean, max),
  notification counts (total, by app, late-night), charging start/end, time at place class, activity duration,
  exercise days, local time, weekday/weekend, recent intervention history.
- Daily features are recomputed for dirty engine days only (an ingestion marks the days it touched) and stored in
  `daily_summary`/`derived_feature`; rolling windows aggregate daily rows; intra-day features run indexed range
  queries over `event`. No feature scans the whole table.
- Insights: local candidates from pre-registered hypotheses, stratified tests, minimum support, non-causal templates
  ("associated with", "coincided with", "tended to occur together"); AI interpretation optional and receives only
  the candidate's aggregates.

## 11. JITAI engine

Per [R10] with these fixed decisions:
- `JitaiDefinition` fields: id, name, description, enabled, trigger(s), condition rule, context requirements,
  delivery channel, content strategy, active window, cooldown, max per day/week, priority, snooze, expiry,
  createdBy (MANUAL/NL/AI_DISCOVERED), created/modified, outcome metric, version.
- Rule DSL: `all`, `any`, `not`, `gt`, `gte`, `lt`, `lte`, `eq`, `neq`, `between`, `in`, `local_time_in`, plus
  feature leaves with optional `args`; strict decoding, no polymorphic fallback; AI rules limited to depth 4,
  16 nodes, 8 children.
- Three-valued evaluation; only TRUE fires; missing/stale/denied data is UNKNOWN; `onUnknown` overrides that
  increase delivery are rejected for AI proposals and need confirmation for user rules.
- Decision key `v1|<jitaiId>|<triggerKind>|<engineDay>|<slot>` with a UNIQUE index; two-phase record (DECIDED ->
  DELIVERING with 2-minute lease -> DELIVERED; crash recovery via `getActiveNotifications()` tag lookup; uncertain
  deliveries are never re-posted and count toward caps).
- Safety gates in fixed order: disabled, paused, expired, snoozed, quiet hours/DND, cooldown, per-rule daily/weekly
  caps, global daily cap (default 6) and minimum gap (default 30 min), arbitration (one delivery per pass, highest
  priority). AI-created limits are stricter (cooldown >= 60 min, maxPerDay 1-3, expiry 1-90 days).
- Creation modes: manual builder; NL (prompt contract `jitai-nl-v1` -> proposal JSON -> validator -> deterministic
  rendering -> user activation); AI-discovered (local discovery pipeline proposes; AI optional for wording; always
  requires approval; autonomous mode does not exist in v1).
- Scheduling: one unique periodic `jitai-tick` (15-60 min by profile) with `setNextScheduleTimeOverride` to skip
  inactive windows; event-driven `jitai-eval-events` (KEEP; expedited on 31+, plain one-time on 29-30) enqueued by
  ingestion of trigger-relevant events; `daily_at` one-time unique work; no exact alarms.

## 12. Interventions

- Notification (full): channels per intervention class, actions (Open, Snooze 1 h, Not now, Stop this JITAI),
  content intent deep link to the JITAI history entry, `tag = decisionKey` for idempotency, delete intent records
  dismissal, POST_NOTIFICATIONS denied -> in-app card fallback + Permission Center state.
- In-app card: dashboard card for pending interventions.
- Image: no v1 provider can generate images (SIWC is text-only; no verified on-device generator), so
  `imageGeneration = false` everywhere and `generateImage()` returns `UnsupportedFeature`. Image interventions use
  bundled pictures or `TemplateRenderer` cards drawn locally (Canvas: background, icon, text, the user's metric),
  cached as media artifacts [R09].
- Voice: the worker synthesizes a WAV with `TextToSpeech.synthesizeToFile` and posts a notification with a Listen
  action; playback (ExoPlayer, `USAGE_MEDIA`/`CONTENT_TYPE_SPEECH`, audio focus, becoming-noisy handling) happens only
  on a visible screen, because background playback and focus requests fail on targetSdk 35+/Android 17. Handles TTS
  engine missing, init failure/timeout, muted stream, Bluetooth output, user cancel; on failure the delivery downgrades
  to a plain notification (`TTS_UNAVAILABLE`). Network TTS voices off by default.
- Video: Media3 1.11.1 Transformer composes PNG slides + the TTS WAV into an H.264/AAC MP4 (720x1280, 30 fps,
  1.5 Mbit/s + 64 kbit/s, at most 90 s, platform diagnostics off), foreground and user-initiated only, written to
  `.tmp` then renamed, cancellable; JITAI VIDEO deliveries use bundled clips or a previously composed file. Encoder
  errors fall back to audio + slides in the app.
- Media storage: `noBackupFilesDir/media`, `media_artifact` rows, 200 MB quota with LRU eviction, 14-day default
  expiry, orphan sweep at app start and daily, share copies via `cacheDir/share` + FileProvider with explicit grants;
  "delete generated media" removes rows, files and share copies.

## 13. Background scheduling

`WorkScheduler` is the only place that enqueues work. Unique names: `collect-usage` (periodic), `collect-device`
(periodic), `sync-googlehealth` (periodic daily + on demand), `features-refresh` (periodic, after sync chains),
`jitai-tick` (periodic), `jitai-eval-events` (one-time KEEP), `jitai-at-<id>-<HHmm>`, `retention` (daily),
`media-cleanup` (daily), `insights-weekly`. Policies: KEEP on reconcile, UPDATE on profile change, never REPLACE for
periodic work. `ScheduleReconciler` runs on process start, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`,
`TIMEZONE_CHANGED`: re-registers PendingIntents (activity transitions), re-pins daily work, records gaps with
`ApplicationExitInfo`. Collection profiles (Low/Balanced/High) set cadences; battery saver for 30 min drops to Low.

## 14. Security and privacy

- Token vault: `SecretVault` (Tink AEAD, Keystore-wrapped keyset, no user-auth requirement so background refresh
  works) stores SIWC tokens + issued client id; Google tokens are not stored (Play services). Excluded from backup
  (`dataExtractionRules`, `fullBackupContent`).
- Logging: `Logger` facade -> `Redactor` (token patterns `eyJ…`, `Bearer`, `refresh_token`, `code=`, emails,
  coordinates beyond 2 decimals, long digit runs) -> logcat (debug) + `diagnostic_log`. Structured fields only;
  no AI payloads, notification text or precise location.
- Exported components: only `MainActivity` (launcher), the NotificationListenerService (permission-protected by
  `BIND_NOTIFICATION_LISTENER_SERVICE`), the boot/time/package receivers (system broadcasts), the Bluetooth ACL
  receiver. Deep links are internal (`PendingIntent` with explicit component) — no browsable deep link except the
  intent:// return page target which carries no secrets and is ignored unless a sign-in is pending.
- Prompt injection [R04 §3.8]: no third-party text reaches the model in v1; the user's own text is data inside a
  delimited, escaped block; instructions are app-constant; outputs are validated against closed schemas and the
  JITAI lint L1-L8 and rendered as plain text; no tools and no code path from model output to export, deletion,
  intents, network calls or settings (`:ai:*` may not depend on those modules).
- Platform hardening [R04 §3.4, §3.10]: `intentMatchingFlags="enforceIntentFilter"`; every `PendingIntent`
  explicit and `FLAG_IMMUTABLE` except the Activity Recognition one, which Play services requires mutable (explicit
  component, non-exported receiver); `HIDE_OVERLAY_WINDOWS`; `taskAffinity=""`; Handoff off; `FLAG_SECURE` and
  `Modifier.sensitiveContent()` on raw-content screens; notifications `VISIBILITY_PRIVATE` with a generic public
  version; copies to the clipboard flagged sensitive.
- Deletion order [R04 §3.7]: block writers (epoch guard), cancel work by tag and alarms, revoke tokens remotely,
  delete rows with `secure_delete`, checkpoint and `VACUUM`, delete media, DataStore, `-wal`/`-shm` files, then the
  Keystore aliases (crypto-erasure); "delete everything" ends with `clearApplicationUserData()`.
- Release manifest excludes READ_SMS, READ_CALL_LOG, QUERY_ALL_PACKAGES, accessibility services,
  ACCESS_BACKGROUND_LOCATION, READ_MEDIA_IMAGES/VIDEO, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, USE_EXACT_ALARM,
  BODY_SENSORS(_BACKGROUND); a CI check asserts it on the merged release manifest.

## 15. Error model and observability

`AppError` sealed hierarchy: `PermissionDenied`, `PermissionPermanentlyDenied`, `AuthenticationRequired`,
`TokenExpired`, `RateLimited(retryAfter?)`, `NetworkUnavailable`, `RemoteServerError(status)`, `ParsingError`,
`DatabaseError`, `UnsupportedFeature`, `ValidationError(codes)`, `ConsentViolation`, `Cancelled`. Each has a stable
code, a user-facing message resource and a retry hint. Diagnostics screen: app version, DB version, API level,
permission states, connector states, last syncs, SIWC state, worker states (WorkManager `getWorkInfosFlow`), counts
(events/insights/JITAIs), recent sanitized errors, "Export diagnostic report" (sanitized JSON via share sheet).

## 16. UI

Screens: Onboarding, Dashboard, Data Sources, Permission Center, Timeline (Paging 3, filters source/type/date),
Insights, JITAIs (Active/Suggested/Paused/History), JITAI Builder (manual + NL), Proposal Review, Wearable
(Google Health), ChatGPT, AI Data Sharing, Settings, Diagnostics, Debug panel (fake flavor/debug only). UDF:
`ViewModel` exposes `StateFlow<UiState>`, receives `Intent`s, emits one-off effects through a channel. All screens
work with zero permissions. Status is never color-only (icon + text). Touch targets >= 48 dp, content descriptions,
font scaling to 2.0.

## 17. Testing summary

Tiers: L1 JVM unit (all JVM modules, JUnit 6), L2 JVM integration (clients vs fake servers, engine vs synthetic
users), L3 Robolectric (Room DAOs/migrations, resolvers with shadowed app-ops, workers via `TestDriver`, Compose
screens + Roborazzi screenshots, journeys J1-J10 on the fake flavor across SDK 29-37), L4 instrumented on emulators
(GitHub Actions with KVM: API 29, 31, 34, 37; phone profiles small/Pixel/large; journeys + adb state injection), L5
the 36-step final scenario. Evidence: JUnit XML aggregated by `tools/junit_summary.py` with `--no-build-cache` and a
freshness gate; reported numbers come only from its JSON. Coverage gates (Kover): JITAI >= 95% line, features
>= 90%, normalization >= 90%, OAuth state >= 90%, repository/domain >= 85%.

## 18. Known limitations and open questions

- Google Health API is not onboarding new projects: production connector cannot be live tested.
- SIWC on Android (loopback redirect from a Custom Tab) is standards-conformant but undocumented by OpenAI; needs a
  live device test with a Plus/Pro account.
- json_schema structured outputs on SIWC are undocumented: prompted JSON + validation is the baseline.
- Health data to OpenAI is a third-party transfer: explicit per-category consent, off by default.
- Emulators cannot run in the build container (no KVM); emulator tiers run on GitHub Actions or a developer
  machine.
