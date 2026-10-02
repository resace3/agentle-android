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
  val id: EventId,                 // UUIDv7-like, generated locally (stored, not indexed)
  val type: EventType,             // closed enum (APP_FOREGROUND ... VIDEO_GENERATED, DAILY_TOTAL, plus device-state types)
  val source: DataSourceId,        // connector + stream, e.g. android.usage, googlehealth.steps
  val startTime: Instant,
  val endTime: Instant?,
  val zoneId: String,              // zone at capture time
  val payload: EventPayload,       // sealed, typed, @Serializable with a "kind" discriminator
  val confidence: Double?,
  val dedupKey: String,            // deterministic natural key (source-specific, account-bound for Google Health), see 5.3
  val metadata: EventMetadata,     // ingestedAt, schemaVersion, provenance (package/device/platform), upstreamId,
                                   // upstreamUpdatedAt, payloadHash, sensitivity
)
```

Payloads are typed classes (`AppSessionPayload(packageName, durationMs)`, `NotificationPayload(package,
category, channelHash, keyHash, flags (ongoing, foreground service, group summary, local only), updateCount,
lastUpdateEpochMs, hasContent, title?/text? only if notification_content is enabled)`, `StepsPayload(count)`,
`HeartRatePayload(bpm, minBpm?, maxBpm?)` (min and max for 60-s roll-ups), `SleepSessionPayload(stages)`,
`RestingHeartRatePayload` and `DailyTotalPayload` keyed by a civil `LocalDate`, `BatteryPayload(level, plugType,
status)`, ...). Schema evolution: each payload class has a `schemaVersion`; decoders accept older versions and upgrade
them in code; unknown future kinds decode to `UnknownPayload(raw)` and are kept, never dropped.

### 5.2 Room schema (v1)

| Table | Key columns | Notes |
|---|---|---|
| `event` | `seq` INTEGER PK, `id` (not indexed), `type` and `source` (small integers), `account_id` (Google Health account; none for device sources), `start_ms`, `end_ms`, `start_offset_s`, `end_offset_s`, `local_date` (civil-date records only), `zone_id`, `dedup_hash` INTEGER UNIQUE + `dedup_key`, `upstream_id`, `upstream_update_ms`, `payload_hash`, `subject` (package / category / device hash), `value_num`, `payload_json`, `payload_version`, `confidence`, `ingested_ms`, `sensitivity`, `change_seq` INTEGER NOT NULL | Indexes: (`type`,`start_ms`), (`type`,`end_ms`), (`source`,`start_ms`), (`subject`,`start_ms`), (`start_ms`), (`change_seq`); partial indexes where a column is mostly NULL; no indexed TEXT id. `value_num`/`subject` are typed projections so features aggregate in SQL without parsing JSON. `payload_hash` hashes a canonical, versioned projection of the normalized fields, never the encoded JSON. `change_seq` comes from one counter: every insert, semantic update and tombstone (a deleted row leaves one) takes the next value inside the ingest transaction. It is the change watermark for event-driven JITAI checks (§5.6). |
| `raw_source_record` | (`source`, `external_id`) PK, `body_hash` | Hash and provenance only (no raw bodies); optional, the table may be dropped. |
| `engine_day_summary` | (`engine_day`, `metric`) PK, `value`, `coverage`, `lineage`, `catalog_version`, `computed_ms` | Daily features by engine day (04:00 rollover); renamed from `daily_summary` so it is never mistaken for civil-date values. |
| `upstream_daily` | (`source`, `account_id`, `metric`, `local_date`), `value` | Daily values the source computed (Google Health daily roll-ups, daily resting heart rate), keyed by civil date and never mapped to an engine day. May be a view over civil-date `event` rows. |
| `derived_feature` | (`feature_id`, `window`, `anchor_date`) PK, `value`, `status` (OK/UNKNOWN/STALE), `lineage`, `catalog_version`, `computed_ms` | Rolling windows. |
| `dirty_day` | `engine_day` PK, `generation` | Written in the ingest transaction for every engine day that an inserted, updated or deleted interval overlaps; feature refresh clears a day by compare-and-clear on `generation`. |
| `metric_source_policy` | `metric`, `source`, `priority`, `valid_from_ms`, `valid_to_ms` | Which source is canonical for a metric over time; the query-time fusion reads it (§10). |
| `source_coverage` | (`connector_id`, `stream`, `account_id`) PK, `coverage_through_ms`, `device_last_sync_ms`, `updated_ms` | How far a synced stream is complete. Advanced in the same transaction as the data it covers; never moves backward. |
| `collector_coverage` | `collector`, `from_ms`, `to_ms` (NULL while open), `cause` | Intervals in which an Android collector was running (§6.5). |
| `insight` | `id`, `kind`, `title`, `finding`, `support_json`, `period_start/end`, `strength`, `origin` (LOCAL/AI), `lineage`, `created_ms`, `state` | AI-worded text is stored only after `AiTextPolicy` accepts it (§9.5). |
| `jitai_definition` | `id` PK, `version` (current), `kind` (INTERVENTION/SUPPRESSION), `category`, `json` (canonical `JitaiDefinition`), `enabled`, `state`, `created_by` (USER_MANUAL/AI_NATURAL_LANGUAGE/AI_DISCOVERED/RULE_TEMPLATE), `created/modified_ms`, `expires_ms` | One row per JITAI, holding its current version; only these rows are evaluation candidates. |
| `jitai_definition_history` | (`id`, `version`) PK, `json` | Every saved version; immutable. |
| `jitai_runtime` | `jitai_id` PK, `snoozed_until_ms`, `snoozed_until_elapsed_ms`, `boot_count`, `snooze_mode`, `consecutive_ignored` | Snooze and backoff state (§11.6). |
| `jitai_timer` | `id` PK, `due_at_ms` (indexed), `kind` (SLOT/PREFETCH/OUTCOME/SNOOZE/BACKSTOP), `jitai_id`, `version`, `slot`, `created_ms` | Planned decision points and follow-ups (§11.7). Changing, disabling, deleting or expiring a definition deletes its rows in the same transaction. |
| `jitai_decision` | `decision_key` UNIQUE, `jitai_id`, `jitai_version`, `trigger_type`, `engine_day`, `zone_id`, `local_date_time`, `decided_ms`, `decided_elapsed_ms`, `boot_count`, `state` (R10 §8.3 enum plus CARD_PENDING; §11.5), `gate_reason`, lease (elapsed ms + boot count), `channel`, `content_ref`, `delivery_nonce`, `snapshot_json`, `trace_json`, `delivered_ms`, `response`, `responded_ms` | Two-phase delivery record [R10 §8]. Indexes: (`jitai_id`,`state`,`decided_ms`), (`state`,`decided_ms`), (`engine_day`,`channel`,`state`). The content-free columns (key, JITAI id, engine day, decided wall/elapsed/boot, state, channel, response) are the delivery ledger: kept 400 days and exempt from retention and from "delete intervention history" (§5.5). |
| `intervention_outcome` | `decision_key` PK, `response` (the catalog's `last_response` enum), `at_ms`, `metric_json` | The first response wins (`UPDATE ... WHERE response = 'NONE'`). |
| `jitai_eval_log` | `id`, `at_ms`, `trigger`, `result`, `trace_json` | Retention 30 d. A trace is written only when the result changes. |
| `ai_text_pool` | `id`, `jitai_id`, `content_hash`, `text`, `consent_version`, `categories`, `snapshot_hash`, `created_ms`, `expires_ms` (at most 24 h after creation) | Pooled AI text for `ai_text` content (§11.5). Purged on every consent change, rule edit, retention run and deletion. |
| `connector_state` | `connector_id` PK, `enabled`, `connection`, `permission_summary`, `last_success_ms`, `last_attempt_ms`, `last_error_code`, `sync_state` | |
| `sync_cursor` | (`connector_id`, `account_id`, `stream`) PK, `synced_through_ms`, `synced_through_elapsed_ms`, `synced_through_boot_count`, `backfilled_from_ms`, `next_allowed_at_ms`, `consecutive_failures`, `fetch_generation`, `import_floor_ms` | Written in the same transaction as the data it covers, with `max()` and a fetch-generation compare-and-set (§5.6). `import_floor_ms` = max(now - retention, last deletion instant). |
| `google_health_state` | `health_user_id`, `account_hint`, `granted_scopes`, `connected_ms`, `disconnected_ms`, `last_full_resync_ms` | No tokens. |
| `ai_request` | `id`, `purpose`, `categories`, `time_range`, `raw_events_sent` (bool), `aggregates_sent` (bool), `model`, `status`, `error_code`, `created_ms`, `bytes_sent` | Metadata only; no payload copy. One row per send, background sends included (the user-visible log of §9.3 reads them); `categories` equal the categories present in the request body. |
| `ai_result_meta` | `request_id`, `schema`, `valid`, `validation_errors`, `produced_entity_id` | |
| `media_artifact` | `id`, `created_ms`, `source_jitai_id`, `decision_key`, `method` (LOCAL_RENDER/TTS/MEDIA3), `local_uri`, `mime`, `size_bytes`, `expires_ms` | Files live in app-private storage; cleanup by quota and age. |
| `user_goal` | `id`, `text`, `metric`, `target`, `created_ms`, `active` | |
| `user_log` | `id`, `at_ms`, `kind` (mood/energy/note), `value`, `note` | Manual input; also mirrored as `USER_LOG` events. |
| `permission_snapshot` | `capability_id`, `state`, `blockers`, `at_ms` | Written on change only. |
| `engine_state` | `key` PK, `value` | The JITAI evaluation watermark, a dirty flag set by every trigger-relevant ingest, a random `db_generation`. |
| `diagnostic_log` | `id`, `at_ms`, `severity`, `component`, `event_code`, `fields` | Ring buffer, 5,000 rows. Structured, allow-listed entries: closed-enum keys, values limited to enums, numbers, durations and HTTP status. No free-text message column (§14). |

Rows that come from Google Health carry the account id; feature, insight and AI-context queries read only the active
account's rows (§7.2). Derived rows (`engine_day_summary`, `derived_feature`, `insight`, proposal evidence) carry
`lineage`, the union of their inputs' data categories and source families (§9.2), so consent gating and deletion can
follow them.

Settings that are not records live in DataStore (retention choice, quiet hours, global caps, collection profile,
onboarding state, install id). AI consent is a dedicated store of `ConsentGrant(category, purpose, consentVersion,
grantedAt, accountSub)` records (§9.1), not a set of toggles.

Migrations: schema exported to `core/database/schemas/`; every version bump ships a `Migration` (or
`AutoMigration`) and a migration test. Migrations add columns or indexes only: a changed projection becomes a new
column, never a rewrite inside a `Migration`. Destructive fallback is never enabled.

### 5.3 Identity and deduplication

`dedupKey` is deterministic per source. It is stored as a 64-bit hash (`dedup_hash` INTEGER UNIQUE), with a
collision check against the stored key.
- Android usage: (timestampMs, type, package, class) plus an occurrence index among identical tuples.
- Notifications: `notif|<keyHash>|<firstPostMs>`, one POSTED row per notification key; later updates fold into that
  row's `updateCount` and `lastUpdateEpochMs`; REMOVED is recorded with its reason.
- Battery: transitions `battery|<kind>|<eventMs>`; snapshots `battery|sample|<5-min bucket>`.
- Calendar `cal|<event_id>|<begin>`; activity recognition `ar|<activity>|<transition>|<eventMs>`; user logs
  `log|<id>`.
- Health Connect: `hc|<metadata.id>`; a `DeletionChange` deletes by that key.
- Google Health: `gh|<accountId>|<stream>|<dataPoint.name>` for identifiable points, otherwise the interval or
  sample time in place of the name. Optional fields such as `dataSource.platform` or the application never enter the
  key or the payload hash.

Two write paths (§5.6). `commit()` serves append-only Android sources: it inserts new keys and updates a row only when
its payload hash changed. `replaceWindow(source, start, end, events, cursor, coverage, account)` serves windowed
upstream sources and has diff semantics: afterwards the stored set for that source and account in [start, end) equals
`events`. It dedupes the batch by key (the newest upstream update wins), deletes stored keys that were not returned,
inserts new keys and updates only rows whose payload hash changed and whose upstream update time is not older;
unchanged rows stay untouched (no `change_seq` or dirty-day churn). The same sync twice, overlapping windows, partial
retries, late or corrected records, upstream deletions and re-segmentation therefore converge to the upstream state.

### 5.4 Encryption at rest

Decision [R04 §3.2]: the database is encrypted with SQLCipher for Android 4.19.1 through Room 3's `SQLCipherDriver`
in every app variant (JVM and Robolectric tests use `BundledSQLiteDriver`). Reason: credential-encrypted storage is
readable from first unlock until reboot, so file-based encryption alone does not protect a copied lifelog database,
and a wrapped key gives crypto-erasure on deletion.
- Key: a random 32-byte data key, sealed by an AES-256-GCM Android Keystore key under alias `agentle.kek.db.v1`
  (Tink `AndroidKeystore.getAead`) with AAD `agentle/db-dek/v1|agentle.db`, written atomically (tmp, fsync, rename)
  to `noBackupFilesDir/keys/db-dek.v1.bin`, passed to SQLCipher as a raw `x'<64 hex>'` key (no PBKDF2).
- Keystore keys never require user authentication or an unlocked device: workers run while the phone is locked.
- Unwrap failures are classified. Permanent: `KeyPermanentlyInvalidatedException`; a missing alias while the wrapped
  key file exists; `AEADBadTagException` on the wrapped key; "file is not a database" after a successful unwrap.
  Everything else (for example `KeyStoreException`, or `InvalidKeyException`/`ProviderException` wrapping a Keystore
  system error, timeouts) is transient.
- The key is unwrapped lazily, off the main thread, in the database provider, never in `Application.onCreate`. A
  background component never deletes anything: workers retry later, receivers finish, collectors skip the write and
  record a coverage gap (§6.5), and a consecutive-failure counter lives in `noBackupFilesDir`. The "local data
  unreadable" reset runs only from a visible activity after explicit user confirmation, and only for a permanent
  failure or failures across at least two boots; it quarantines the data key file by renaming it. Never a plaintext
  fallback.
- SQLCipher's SQL statement logging is disabled before its first class loads. Every connection sets
  `PRAGMA secure_delete=ON`, `cipher_log_level=NONE`, WAL, `synchronous=NORMAL`, `journal_size_limit` and
  `busy_timeout` explicitly.
- Tokens and the SIWC client registration live in a separate vault sealed under `agentle.kek.vault.v1`, never in
  the database (§14).

### 5.5 Retention and deletion

Retention: keep indefinitely (default) / 30 d / 90 d / 1 y, applied per event family by a daily worker. Exempt:
JITAI definitions, user goals and the content-free delivery ledger (§5.2), which is kept 400 days because caps,
cooldowns, decision keys and intervention-history features count on it. A retention run writes the affected streams'
`import_floor_ms` and purges `ai_text_pool`. A day removed by retention or deletion reads as Missing, never as zero.

Deletion follows lineage. `DataCategoryRegistry` lists every (table, category predicate) pair that can hold a
category: every `@Entity`, every derived table and every JSON column; a test fails when a table in the exported Room
schema is not registered. Every deletion increments a DataEpoch, and every writer checks it inside its transaction and
aborts if it changed, so a write that started before a deletion cannot put deleted data back. Deletion actions:
- Per category or source family (for example wearable data, Android-collected data, one AI data category): runs in
  chunks of at most 500 rows per transaction under the epoch guard, with progress kept in the deletion marker, so a
  stopped worker resumes. It deletes the primary rows and every derived row whose lineage includes the category,
  rewrites JITAI snapshot, trace and outcome JSON with a deleted marker (the engine's scrub function, §11.4), purges
  `ai_text_pool`, writes `import_floor_ms` for the category's streams and revokes the category's AI consent grants.
  It ends with `wal_checkpoint(TRUNCATE)`, checking the busy flag and retrying. The flow asks "Also stop
  collecting/syncing?"; if collection continues, only data from now on comes back.
- Delete insights: insight rows, including their AI text.
- Delete intervention history: removes traces, snapshots, content, `ai_text_pool` rows and outcome metrics. The
  delivery ledger stays, so caps, cooldowns and decision keys keep counting and today's nudges cannot fire again.
- Delete generated media: rows, files and share copies.
- Delete everything, in this order: (1) write a `deletion_in_progress` marker file in `noBackupFilesDir`; (2) stop
  producers: cancel work and disable the notification listener component (`setComponentEnabledSetting(...,
  DISABLED)`); (3) revoke remote sessions through a port while credentials exist (ChatGPT through
  `SiwcSessionManager.disconnect()`, §8.2; Google through `GoogleHealthAuthorizer.revoke()`, §7.1); if revocation
  fails, report "remote disconnection could not be confirmed" with the manual path; (4) drain writers through the
  epoch guard; (5) close Room; (6) delete the database, `-wal`, `-shm` and `-journal` files; (7) delete the data key
  file and both Keystore aliases (crypto-erasure); (8) delete media and DataStore files; (9) verify counts; (10)
  `clearApplicationUserData()`. No row deletes and no VACUUM on this path. A marker found at app start resumes the
  flow.
- Verification counts every registry pair, numeric derived rows included, and the counts are shown to the user. Tests
  add an independent checker that walks `sqlite_master` and scans every column, JSON included, for seeded markers
  (§17).
- Never delete `-wal`/`-shm` of an open database. VACUUM runs only as charging+idle maintenance, after a free-space
  check (at least 2.2 times the database size), with `temp_store=FILE` and `SQLITE_TMPDIR=cacheDir`.

Disconnecting the wearable never deletes data.

### 5.6 Write path, change tracking and coverage

- Every event write goes through one serialized writer with transactions of at most 500 rows; collectors and the
  notification listener batch into it in process.
- `commit(events, cursor, coverage)` and `replaceWindow(...)` (§5.3) write the data, the stream's cursor and its
  coverage in one transaction. A cursor write carries the fetch generation it read; the sink applies `max()` and
  stores generation + 1, or rejects the whole call (`CommitResult.rejected`) if another run moved the cursor, so an
  older window never moves a cursor backward. Records older than the stream's `import_floor_ms` are dropped silently;
  a dropped record is normal, never an error or a reason to retry.
- The same transaction takes `change_seq` values for inserts, semantic updates and tombstones, sets the
  `engine_state` dirty flag when a trigger-relevant event changed, and writes `dirty_day` rows for every engine day a
  changed interval overlaps. Unchanged rows produce none of these.
- `clampFutureCursors(now)`: any cursor later than now + 5 min (a bad clock at boot) is clamped to now - overlap,
  and a coverage gap is recorded. The reconciler calls it at boot, on `TIME_SET` and before each sync run (§13).
- JITAI decisions commit through a decision-commit runner: a process-wide mutex plus one IMMEDIATE write
  transaction in which the caller re-reads the gate counts, inserts the decision and advances the evaluation
  watermark (§11.4). A read-transaction runner gives each feature resolve one consistent database snapshot.

## 6. Connectors and the Permission Center

### 6.1 Connector SPI

```kotlin
interface Connector {
  val metadata: StateFlow<ConnectorMetadata>   // id, name, connection, permission summary, last success/attempt,
                                               // last error (AppError code), supported event types, sync state,
                                               // stream permissions, coverage
  val capabilityIds: List<String>              // ids from the capability registry
  val streamIds: Set<String>                   // streams that syncStream accepts
  suspend fun sync(trigger: SyncTrigger): SyncResult                        // idempotent; data + cursor atomic
  suspend fun syncStream(stream: String, trigger: SyncTrigger): SyncResult  // one stream now: staleness retry, prefetch
  suspend fun setEnabled(enabled: Boolean)                                  // disabling never deletes data
}

interface EventSink {
  suspend fun commit(events, cursor?, coverage?): CommitResult              // insert new keys, update changed payloads
  suspend fun replaceWindow(source, windowStart, windowEnd, events, cursor?, coverage?, accountId?): CommitResult
  suspend fun importFloor(source): Instant?                                 // every window start is clamped to it
}
```

`SyncCursor` carries the account id and a fetch generation (compare-and-set, §5.6); `StreamCoverage`
(`coverageThrough`, account id, `deviceLastSync`) is stored in the same transaction as the data it covers;
`SyncResult.Status` includes `ACCOUNT_CHANGED`. `replaceWindow` has diff semantics (§5.3).

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
access, `NotificationManagerCompat.getEnabledListenerPackages`, `AlarmManager.canScheduleExactAlarms` (reported for
information only: Agentle never schedules exact alarms), Health Connect `PermissionController`,
`LocationManager.isLocationEnabled`, `BluetoothAdapter.isEnabled`, ...). States are re-evaluated on `onResume`, on
app-op/listener callbacks, and before every collection run. A missing permission yields a state, never an exception:
every collector catches `SecurityException` and reports `PermissionDenied`.

Each resolver is split into context-free signals, which workers can read, and a UI-only refinement
(`shouldShowRequestPermissionRationale` needs an Activity); background runs keep the last UI-derived `DENIED` vs
`DENIED_PERMANENTLY` value. The notifications capability reports the app-level permission, any Agentle channel with
importance `NONE` and `NotificationManager.areNotificationsPaused()`; a JITAI delivery counts as possible only when
notifications are enabled, the channel is not blocked and notifications are not paused (§11.5). A hibernation
capability reports `PackageManagerCompat.getUnusedAppRestrictionsStatus` and, while at least one JITAI is active,
offers `IntentCompat.createManageUnusedAppRestrictionsIntent`. Screen, unlock and charging event sources are shown as
best effort: they are reliable only while notification access keeps the process running.

### 6.4 Android collectors (v1 = registry entries marked IMPLEMENT)

| Collector | Mechanism | Cadence |
|---|---|---|
| Usage events + foreground sessions + screen interactive/keyguard (28) | `UsageStatsManager.queryEvents` with a high-water mark stored as (wall ms, elapsed ms, boot count) and a 10-minute overlap; a mark later than now + 5 min is clamped to now - overlap and a coverage gap is recorded; never a query with begin >= end | WorkManager every 1-6 h by profile + on app start + before JITAI evaluation |
| Notifications (metadata; content opt-in per app, default SMS/dialer excluded; Agentle's own notifications dropped before any write) | `NotificationListenerService` (bound by the system): one POSTED row per notification key with later updates folded in, REMOVED with its reason, flags recorded (ongoing, foreground service, group summary, local only); `default_filter_types` without ongoing on API 31+, filtering in code on 29-30; batched into the serialized writer, never one work request per notification | Real time |
| Screen on/off, user present | Runtime receivers registered with `RECEIVER_EXPORTED` while the process lives; the live state is confirmed at receipt (`PowerManager.isInteractive()` and an unlocked keyguard), otherwise only a hint row is stored; the event carries its time; usage events are truth [R02] | Real time while alive (best effort) |
| Battery, charging, power save, thermal | Sticky `ACTION_BATTERY_CHANGED` read + runtime receivers + periodic sample; `POWER_*` events only when `BatteryManager.isCharging()` matches; at most one sample row per 5 minutes | 15-60 min |
| Connectivity, network type, Wi-Fi metadata, airplane mode | `ConnectivityManager.NetworkCallback` while alive + periodic snapshot | Change-driven + periodic |
| Bluetooth adapter + connected devices (hashed addresses) | Manifest receiver for ACL events, the only exported receiver; extras are hints, never trusted | Event-driven |
| Audio: volume/ringer, output devices, headset | `AudioManager` snapshot + `AudioDeviceCallback` while alive | Periodic + change |
| Time zone / time / locale changes, boot | Manifest receivers with `exported="false"` (`TIMEZONE_CHANGED`, `TIME_SET`, `LOCALE_CHANGED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`); each dispatches through an action allow-list and ignores everything else | Event-driven |
| Location (foreground, coarse default) | Fused/`LocationManager` current location while app is visible; places classified locally (home/work/other) for display only: foreground fixes never create trigger events or dwell time, and `location_class` is unavailable to rules in v1 | Foreground only (background DEFER) |
| Activity recognition | Activity Recognition Transition API via explicit mutable PendingIntent | Event-driven |
| Steps | Recording API (Play services) when available; Health Connect on-device steps | Periodic |
| Calendar | `CalendarContract.Instances` query (READ_CALENDAR) | Periodic |
| Health Connect | `HealthConnectClient` read + changes tokens (steps, sleep, HR, RHR, exercise, weight), stored even while Google Health is connected (fusion happens at query time, §10); rows keyed by `metadata.id`; a re-read after a changes-token expiry is clamped to the import floor | Periodic; background read only with permission |
| DND state, next alarm, standby bucket, storage | System service snapshots | Periodic |
| Call state | `TelephonyCallback` on API 31+ (API 29-30 handled explicitly or reported unavailable); READ_PHONE_STATE requested only when the user enables the capability | Real time while alive |
| Motion / ambient sensors | Debug-only sampling sessions started by the user | Explicit sessions |

Sensing tiers [R03 §11]: Tier 0 is the default and needs no foreground service (activity transitions, canonical
steps from Health Connect on-device steps on API 34 with extension 20+ or the Recording API, a sensor inventory).
Tier 1 (debug flag in v1) samples sensors only while an Agentle screen is visible. Tier 2 is an opt-in
"High-detail sensing" `health` foreground service with duty-cycled 10 s windows, user-started, at most 24 h per
session, capped at 288 windows and 45 min of wake lock per day, internal/debug builds only until the Play
foreground-service declaration is accepted. Tier 2 schedules its windows with `setAndAllowWhileIdle` only, never an
exact alarm (not even the API 37 listener overload of `setExactAndAllowWhileIdle`). Sensing stores summaries, never
raw streams; `TYPE_STEP_COUNTER` is never summed into step totals.

Not available (documented with the reason): accessibility event stream, call log and SMS metadata (hard-restricted,
Play-forbidden, default-handler only), background location (DEFER until a place-based feature needs it), nearby BT
scanning (DEFER), contacts (DEFER), media sessions (DEFER), Wi-Fi network identity (needs location).

### 6.5 Coverage recording

Absence of records means "no data" only where a source was running or synced. Every collector records coverage
intervals through the `CoverageRecorder` port (`collector_coverage`): it opens or closes an interval at process start,
`onListenerConnected`/`onListenerDisconnected`, activity-transition registration, permission-state changes and every
sweep heartbeat. At process start, an interval left open by a dead process is closed at its last heartbeat, with the
cause from `ApplicationExitInfo` (API 30+) or `UNKNOWN`. While the database is unavailable (for example a transient
Keystore failure), collectors skip the write and record a gap; they never crash and never retry in a tight loop.
Connectors advance `source_coverage` in the same `EventSink` call as their data (§5.6); the wearable's last upload
time (`deviceLastSync`) bounds it. Features intersect coverage with their window: an uncovered window gives
Missing(COVERAGE_GAP) or Stale, never zero (§10).

## 7. Google Health API connector ("Fitbit")

Base `https://health.googleapis.com/v4/`, collection `users/me/dataTypes/{type}/dataPoints` with `list`,
`:reconcile`, `:rollUp`, `:dailyRollUp` [R05 §3-4]. v1 types: steps, distance, floors (rollups only), total calories,
heart rate (60-s `rollUp` windows by default; raw samples only behind an opt-in flag), resting heart rate, sleep,
exercise, weight/body fat (if authorized), paired devices. The real API source sits behind a feature flag that is off
by default until the live spike (R05 §7.9 step 1) passes; the fake flavor runs the same client against
`FakeGoogleHealthServer`.

### 7.1 Authorization

Production authorization uses Google Identity Services `AuthorizationClient` (play-services-auth 22.0.0) with an
Android OAuth client. No refresh token and no client secret is stored on the device, and there is no token broker.
Whether an Android client can be granted the `googlehealth.*` scopes is UNVERIFIED and may block launch (§18). JVM
code sees only the `GoogleHealthAuthorizer` port, modelled on `AuthorizationClient` and independent of the flow:
- `token(interactive: Boolean)` returns `Token(value, grantedScopes)`, `NeedsResolution` (an opaque handle; JVM code
  never starts UI), `Denied` or `Failure(statusCode)`. The port also has `invalidate(token)` (clear the cached token),
  `grantedScopes()` and `revoke()`.
- On a 401: invalidate, call `token(interactive = false)`, retry once, then report NEEDS_REAUTH.
- A background sync that gets `NeedsResolution` persists NEEDS_REAUTH, returns a result that does not retry and never
  starts an Activity; the resolution is launched only from the app.
- Granted scopes are checked after consent; missing scopes produce `PARTIALLY_ALLOWED` per data type.
- Disconnect calls `revoke()` (Play services `revokeAccess`) and clears local grant state.
- The Play services adapter is an Android class behind the port; the fake flavor binds the scripted
  `FakeGoogleAuthorizer` (§4).

Live status: Google states it is "not onboarding new projects"; the production path is implemented against the
documented contract and is **not live tested**.

### 7.2 Account identity

The connector calls `GET /v4/users/me/identity` on connect and before every sync, and `google_health_state` stores
`healthUserId`. An account id (a hash of `healthUserId`) goes into every dedup key, `SyncCursor` and
`StreamCoverage`. If the stored account differs from the one the API returns, syncing stops with `ACCOUNT_CHANGED`;
a new account starts with fresh cursors. Features, insights and AI aggregates read only the active account's rows.
What happens to the previous account's rows (delete or keep) is not decided; until it is, they stay stored and unread.

### 7.3 Sync windows

- A per-(connector, account, stream) mutex serializes the runs of a stream (periodic, on demand, deep re-sync,
  backfill). A run passes the fetch generation it started from, so its cursor write is rejected if another run moved
  the cursor (§5.6).
- Windows are capped per stream (at most 6 h of raw samples, 24 h of intervals, 7 days of sessions) and walked forward
  chunk by chunk. Each chunk is written with `replaceWindow` together with its cursor and coverage, and only when all
  of its pages succeeded; if a page fails, nothing is committed for that chunk. No unbounded window is buffered, and
  no window has start >= end.
- Overlap re-read: 48 h for samples/intervals, 7 days for sleep/exercise/daily, bounded by the device's last sync
  time from `pairedDevices` when known and skipped when the stream's previous sync started less than 15 minutes ago.
  Weekly 30-day deep re-sync; backfill 14 days hot, then 30-day chunks to 90 days, never beyond retention.
- Every window start (incremental, overlap, deep re-sync, backfill) is clamped to the stream's import floor
  (`EventSink.importFloor`), so deleted or expired data is never fetched again. A stored cursor later than now + 5 min
  becomes now - overlap.
- Each record carries `upstreamId` (the data point name, when present), the upstream update time and a payload hash
  built only from normalized, always-present fields; each batch is deduped by key, keeping the newest update time.
- Rate limit: strict sliding windows (at most 4 requests in any second and 200 in any minute per user; a token bucket
  would allow bursts above the cap); 429 and 5xx retried with exponential backoff and jitter as coroutine delays
  (max 3), then the window is marked failed and retried next run. Malformed bodies (HTML 404/502, non-JSON 2xx,
  unknown fields, int64-as-string, invalid values) are tolerated per [R05 §4.1, §8.6]: skip and count the bad point,
  never crash, never commit a half window.
- `syncStream(stream)` syncs one stream at once: staleness retries and a prefetch 10-15 minutes before a scheduled
  rule reads wearable metrics (§11.7). The periodic cadence follows the collection profile (§13).

### 7.4 Sources and fusion

Every source is stored whatever else is connected: Health Connect data is kept while Google Health is connected, and
no row is dropped because another source exists. Google Health keeps `Provenance.platform`, so the fusion can ignore
points that came from Health Connect when Health Connect is the selected source. Fusion happens at query time (§10):
per minute, the canonical source of `metric_source_policy` where it has coverage, else the next by priority; no query
sums a metric across sources. Daily values that the API computes (daily roll-ups, daily resting heart rate) are
civil-date values: they keep their `LocalDate` (`upstream_daily`) and are never mapped to the 04:00 engine day.

## 8. Sign in with ChatGPT

Implements [R06 §2, §8], with the decisions below.

### 8.1 Sign-in attempt

- `SignInCoordinator`, a singleton in `:ai:chatgpt`, owns at most one sign-in attempt, in the app scope, never in a
  ViewModel. `signIn()` is single-flight: a repeat tap re-opens the current attempt's URL, and `cancel()` closes the
  listener. `CONNECTING` is a UI-only flag derived from the live attempt; it is never persisted.
- Only a non-secret attempt marker (createdAt, firstRegistration) is persisted, through `CredentialStore`. On a cold
  start with a marker and no live attempt, sign-in returns `INTERRUPTED` and clears the marker; the UI tells the user
  to remove a possible extra "Agentle" entry under ChatGPT Login connections.
- `LoopbackCallbackServer` on `127.0.0.1:0`, `GET /auth/callback` only, exact Host/path, constant-time state compare,
  single settle, 10-minute timeout, returns a no-store "Return to Agentle" page with a package-scoped `intent://`.
- Authorization request: PKCE S256, `state`, `nonce`, `resource=https://api.openai.com/v1`, scopes
  `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`, first-time
  `client_id=dynamic_agent_client` + `agent_name_hint=Agentle`, `ext_agent_host_id=urn:uuid:<per-install>`.
  The issued `oaiapp_…` client id is persisted (encrypted) before the code is redeemed and reused afterwards; while a
  registration exists, Agentle never registers again.
- Plain Custom Tab (never WebView, never Auth Tab); `ACTION_VIEW` fallback. When no browser resolves, sign-in returns
  `NO_BROWSER`, never a crash.
- Plan usage needs both `resource.invoke` and `chatgpt.tokens.use.direct` in the token response's granted scopes
  [R06 §2.8]; otherwise the sign-in is kept and the state is `NOT_ELIGIBLE(PLAN_USAGE_NOT_GRANTED)`.

### 8.2 Tokens, identity and refresh

- ID token verified with Nimbus JOSE+JWT (RS256, JWKS with refetch on unknown `kid`): `iss`, `aud`, `azp`, `nonce`,
  `exp`, `iat` and `sub`, with 5 s skew. The claims verifier takes its time from `AgentleClock` (a
  `DefaultJWTClaimsVerifier` subclass overriding `currentTime()`); time-claim failures consistent with a wrong device
  clock map to `DEVICE_CLOCK_WRONG`. Access-token expiry comes from `expires_in` against the monotonic clock at
  receipt, never from `exp` against wall time.
- Account: on re-authentication, and on ID tokens returned by a refresh, `sub` is compared with the stored value; a
  mismatch gives `ACCOUNT_MISMATCH` and the new tokens are discarded. Consent grants carry the account (§9.1).
- Tokens are always written into the same record as the client id that obtained them, and a callback may update only
  the registration bound to its attempt's `state`. A monotonically increasing credential generation: sign-in
  completion, refresh and disconnect take the one session Mutex, and each applies its result by compare-and-set on
  the generation it started from.
- `SiwcSessionManager.withAccessToken {}` refreshes at <= 60 s remaining or once after a 401. `SiwcSessionManager`
  alone owns 401 -> one refresh -> one retry; `ResponsesClient` never wraps it again. `earliest_refresh_at` is
  honoured with a retryable `REFRESH_NOT_READY`.
- A refresh is one non-cancellable unit. The session manager owns an app-scoped `CoroutineScope(SupervisorJob() +
  io)`; a refresh runs as `async { withContext(NonCancellable) { POST; write the raw response as pendingRotation;
  verify the ID token if present; promote (write the tokens, clear the pending record) } }`, and callers await the
  shared `Deferred`, so a cancelled caller abandons only its own wait. A stored `pendingRotation` always wins over the
  old refresh token, at startup and before any refresh; a JWKS or discovery failure after rotation keeps it. With a
  scope, `SingleFlight` runs the shared work detached from every caller.
- Tokens are cleared only on terminal error codes, never on network errors, 5xx, captive portals (HTML with 200) or
  TLS interception; those keep the tokens and the state shows unavailable.
- `disconnect()` runs under the session Mutex: it cancels in-flight calls, revokes the refresh token, clears tokens
  in the vault and in memory and marks `DISCONNECTED`, keeping the issued client id and host id unless the user asks
  to forget the registration. A refresh already in flight never writes tokens afterwards (generation check). A failed
  revocation is reported as "remote disconnection could not be confirmed", never swallowed.
- Tokens, codes and verifiers are a `Secret` value class with a masked `toString()`.

### 8.3 HTTP clients and ResponsesClient

- Auth client (token, revoke, discovery, JWKS): `followRedirects(false)`, `followSslRedirects(false)`,
  `retryOnConnectionFailure(false)`, connect 15 s, call 30 s, never wrapped in the retry policy. JWKS and discovery
  are fetched through it (a custom `JWKSource`), and every discovered endpoint must share the issuer's origin.
- API client: `retryOnConnectionFailure(false)`. SSE client: call timeout 180 s. Every client sets `allowedHosts`
  (§1 rule 2), so none follows redirects.
- `ResponsesClient`: `POST /v1/responses` with `store:false`, `stream:true`, array input, `instructions` (no system
  role), a request-field whitelist, success only on `response.completed`, `response.failed` mid-stream handled,
  missing Content-Type tolerated. A `beforeSend` hook receives the exact request body bytes just before they are
  written and can abort the call with an `AppError`; `ChatGptAiProvider` uses it for the send-time consent check
  (§9.3). Request bodies are never persisted. Models from `GET /v1/models` (`visibility=="list"`).

### 8.4 States and errors

- SIWC persists the states of [R06 §8.1] with a reason: `CONNECTED`, `DISCONNECTED`, `NOT_ELIGIBLE`,
  `PLAN_USAGE_UNAVAILABLE`, `RATE_LIMITED`, `REAUTH_REQUIRED`, `SERVER_ERROR`, `NETWORK_UNAVAILABLE`. Reasons include
  `PLAN_USAGE_NOT_GRANTED`, `LOCAL_CREDENTIALS_UNREADABLE` (the vault could not be read, §14), `ACCOUNT_MISMATCH`,
  `REFRESH_NOT_READY` and `DEVICE_CLOCK_WRONG`. A sign-in attempt can also end `INTERRUPTED` or `NO_BROWSER` without
  changing the state.
- The UI and the AI layer read a coarse provider state: `DISCONNECTED`, `CONNECTING` (UI-only), `CONNECTED`,
  `NEEDS_REAUTH` (from `REAUTH_REQUIRED`), `NOT_ELIGIBLE(reason)`, `USAGE_LIMITED(until?)`, `UNAVAILABLE`, `ERROR`,
  mapped by `SiwcErrorMapper` from every documented error code.
- Capabilities (introspectable `AiCapabilities`): `TEXT_REASONING=SUPPORTED`, `STRUCTURED_OUTPUT=PROMPTED_JSON`
  (json_schema is undocumented; probe once per model, otherwise plain-text JSON + local validation),
  `IMAGE_GENERATION=UNSUPPORTED` (local renderer instead), `VOICE_GENERATION=LOCAL_TTS`,
  `VIDEO_GENERATION=LOCAL_COMPOSITION`, `BACKGROUND_INFERENCE=USER_BUDGETED` (undocumented by OpenAI: background
  generations only within a standing consent and its daily budget (§9.1), and never start sign-in from the
  background).
- Never: another product's client id, the private `chatgpt.com/backend-api`, an API-key fallback.
- Required UI copy: "Continue with ChatGPT", first-run "You're using your ChatGPT plan", "Using ChatGPT plan"
  indicator, "Manage usage" link, "Usage limit reached" dialog.

## 9. AI layer

`AiProvider { capabilities; analyze(); generateStructured(schema); generateImage() }`, each call taking a sealed
`AiRequestEnvelope`. Implementations: `ChatGptAiProvider` (prod; in the fake flavor it runs against
`FakeChatGptServer`), `FakeAiProvider` (deterministic, unit tests), `UnavailableAiProvider` (disconnected: every call
returns `AppError.AuthenticationRequired` without network).

### 9.1 Consent

- Consent is an allow-list: a dedicated store of `ConsentGrant(category, purpose, consentVersion, grantedAt,
  accountSub)`. Data of a category may leave only under a current grant for that category and the request's purpose.
  Absent, unknown, unreadable or corrupted state denies; every category defaults to off; `DataCategory.sensitiveByDefault`
  never sets a default; no corruption handler, deletion action or backup restore can create a grant, and a restore
  clears all grants. A grant applies only to the ChatGPT account (`sub`) it was given under.
- A `consentVersion` bump (categories, purposes or recipient disclosure changed) requires fresh grants. The
  disclosure names OpenAI and says that requests are linked to the user's ChatGPT account.
- Deleting a category revokes its grants in the same flow (§5.5); consent changes cancel in-flight AI calls.
- Background purposes (for example refilling a JITAI's `ai_text_pool`) need a `StandingConsent`: the user previews and
  approves a template listing the exact fields and features, categories, time range, cadence and daily budget. A
  background envelope must be a field-wise subset of it, or the send fails with `ConsentViolation`. Background
  purposes are aggregates-only. Every background send is recorded and shown in a user-visible log.

### 9.2 Categories and lineage

- A normative table maps every capability id (`docs/research/capabilities.json`) and every catalog feature to data
  categories (such as SCREEN_TIME_TOTALS, APP_IDENTITY, NOTIFICATION_COUNTS, NOTIFICATION_TEXT, CALENDAR_BUSY,
  CALENDAR_TEXT, LOCATION_CLASS, ACTIVITY, STEPS, SLEEP, HEART, BODY, USER_TEXT, GOALS; reconciled additively with
  `DataCategory` in `:core:model`) and to source families (`GH_API`, `HEALTH_CONNECT`, `ON_DEVICE`).
- The lineage of a derived artifact (daily row, rolling window, insight, proposal evidence, snapshot value) is the
  union of its inputs' categories and families; unknown lineage counts as every category. The gate requires every
  category and every source family in the lineage to be allowed.
- `GH_API` values are denied to AI in v1, because Google's terms for sending Health API data to a third party are
  unresolved (§18).
- Aggregates come only from the active Google Health account's rows and never sum a metric across sources.

### 9.3 Building, sealing and sending

- `ContextSelectionEngine.build(purpose, userQuestion)`: the purpose maps to a fixed allow-list of categories and a
  time range; current grants intersect it. Aggregates by default; raw events only for purposes that need them and
  only with per-request user confirmation, so never in the background.
- The result is a sealed `AiRequestEnvelope` that only `:ai:context` can create (an internal constructor plus an
  opt-in annotation, and a test that fails if another module builds request content). It carries the
  `consentVersion` and the SHA-256 of the canonical serialized input.
- Send-time check: in `ResponsesClient`'s `beforeSend` hook, `ChatGptAiProvider` verifies the consent version and the
  hash against the exact body bytes about to be written, reading the consent store independently of the policy
  object that built the envelope; a mismatch or a revoked grant aborts the call with `ConsentViolation`. Envelopes
  are never persisted; a retry re-runs `ContextSelectionEngine`.
- `EgressGuard` is the only path to the OpenAI client: it re-checks every block against the granted categories
  (deny by default), truncates items to 200 characters and 20 items, and fails closed.
- `AiRequestPreview` (purpose, categories, time range, raw events yes/no, aggregates yes/no, byte estimate) is shown
  before user-initiated requests and stored as `ai_request` metadata; payloads are not stored or logged. The audit
  record's categories equal the categories present in the request body.

### 9.4 Untrusted text

- Every string the app did not write is `UntrustedText` (`:core:model`): app labels, package names, every
  notification and calendar field, user goals and notes, NL requests, every AI output. The envelope serializer emits
  it only as a JSON string value inside a data item, never in `instructions` and never by concatenation.
- Third-party text (notification text, calendar titles, contact, Wi-Fi and Bluetooth names) is never sent in v1
  [R04 §3.8]. The only free text sent is the user's own (questions, goals, logs) and app labels reduced to a safe
  character set (letters, digits, spaces, `.-&'`, at most 40 characters); codes are preferred to names.
- Stored AI output carries an `aiGenerated` taint and is left out of later contexts unless a purpose needs it. AI
  output is rendered as plain text only.

### 9.5 Output policy

- Structured output: parse as `JsonElement`, walk the schema, decode, then semantic checks (enums, max lengths,
  operators, feature references, timestamps, delivery limits); failures are recorded as codes without content.
- `AiTextPolicy` (`:ai:api`) applies to every AI-produced string in every schema, insight and media text included:
  R10 §11.6 L1-L8 with the same ids, number provenance (every number appears in the envelope or the template), a
  maximum length in sentences, and no medical imperatives. It runs before storage and again before display or
  delivery; on failure the local template text is used and only the check id is recorded.
- Pooled intervention text (`ai_text`, §11.5) may contain no digit and no number word ("three", "ten", "half",
  "dozen" and so on): it is generated hours ahead, so a number in it would be stale or invented. Numbers come only
  from template placeholders filled at delivery.

## 10. Feature and insight engines

- Feature catalog (single source for validator, prompt catalog, renderer, schema enum): screen time (daily, by app,
  late-night 22:00-04:00, last 60 min), unlocks (keyguard hidden count), steps (daily, by hour, since midnight),
  sedentary periods, sleep (duration, midpoint, bedtime, wake time, regularity), heart rate (resting, mean, max),
  notification counts (total, by app, late-night), charging start/end, time at place class, activity duration,
  exercise days, local time, weekday/weekend, recent intervention history. Every feature declares its
  `DataCategory`, its coverage source and its availability. `location_class` (time at place class) is
  `Unavailable("location_background")` in v1: rules cannot reference it, the NL catalog and schema enum leave it out,
  and resolving it returns Missing(API_UNAVAILABLE).
- Coverage: a window that its coverage source does not fully cover gives Missing(COVERAGE_GAP) or Stale, never a count
  of 0 (§6.5). For example `notifications_last_60m` while the listener was disconnected is UNKNOWN, and `steps_today`
  with lagging source coverage is Stale.
- Fusion: wearable and phone metrics are read as a fused series through the `FeatureDataSource` port: per minute, the
  canonical source where it has coverage, else the next source by priority (`metric_source_policy`). No feature sums
  a metric across sources. When the canonical API source has no coverage for recent minutes, the freshest local copy
  (Health Connect Fitbit-origin steps or on-device steps) fills those minutes as a provisional value; daily totals stay
  API-canonical once covered. So a step rule works for a wearable user even when the API source syncs rarely.
- Civil dates: daily values the source computed (resting heart rate, the wearable's daily totals) are read by civil
  date in the user's zone, never shifted onto the 04:00 engine day. `resting_hr_today` at 01:30 reads today's civil
  date; if there is none, it is Missing, never yesterday's value.
- Notification counts are distinct notification keys whose first POSTED falls in the window, from other packages,
  excluding ongoing notifications and group summaries; updates never count as new posts.
- Queries: window queries find intervals that span the window start (`start < windowEnd AND end > windowStart`),
  never start-only filters. One `resolve()` reads from one consistent snapshot (`FeatureDataSource.readSnapshot`).
  Only the active Google Health account's rows are read. Every `FeatureValue` traces to its `DataCategory`.
- Daily features are computed per engine day, idempotently, for every engine day an interval overlaps. Dirty days are
  persisted (`dirty_day`, §5.6); a refresh takes the set of dirty days and never assumes it sees each change once. A
  daily feature whose engine day or civil date is dirty is resolved on demand, never served from a stale stored value.
  A day removed by retention or deletion is Missing, never zero. Derived rows carry `catalogVersion` and lineage; a
  catalog-version or time-zone change triggers a bounded recompute, and a coverage change marks days dirty. Rolling
  windows aggregate daily rows; intra-day features run indexed range queries over `event`. No feature scans the whole
  table.
- Insights: local candidates from pre-registered hypotheses, stratified tests, minimum support, non-causal templates
  ("associated with", "coincided with", "tended to occur together"); AI interpretation optional and receives only
  the candidate's aggregates. AI-worded insight text passes `AiTextPolicy` (§9.5) before it is stored and shown.

## 11. JITAI engine

Per [R10]. R10 §2.1 (every `JitaiDefinition` field) and R10 §9.1 (safety gates G01-G16 in R10's order) are
normative; the subsections below record the decisions that refine or override R10.

### 11.1 Definitions and validation

- `JitaiDefinition` has R10 §2.1's full field set: id, name, description, enabled, trigger, conditions,
  contextRequirements, delivery (channel, `quietHoursPolicy`, `notificationTimeoutMinutes`,
  `deliveryDeadlineMinutes`), content, activeWindow, cooldownMinutes, maxPerDay, maxPerWeek, priority, snooze
  (`SnoozePolicy`), expiresAt, createdBy (USER_MANUAL, AI_NATURAL_LANGUAGE, AI_DISCOVERED, RULE_TEMPLATE),
  createdAt/modifiedAt, outcome, and schemaVersion, version, `kind` (INTERVENTION | SUPPRESSION), `category`,
  status, `suppression` targets, `experiment` and `userConfirmedUnknownOverrides`. R10 §13.6.3's SUPPRESSION example
  is a golden test.
- Rule DSL: `all`, `any`, `not`, `gt`, `gte`, `lt`, `lte`, `eq`, `neq`, `between`, `in`, `local_time_in`, plus
  feature leaves with optional `args`; strict decoding, no polymorphic fallback; AI rules limited to depth 4,
  16 nodes, 8 children.
- Three-valued evaluation; only TRUE fires; missing/stale/denied data is UNKNOWN; `onUnknown` overrides that
  increase delivery are rejected for AI proposals and need confirmation for user rules.
- The validator is a pure function of (definition, catalog version). It runs at approval and again for stored rules
  after an app upgrade; a stored rule that fails becomes PAUSED with a notice. Beyond R10 §11:
  - a rule or proposal that references an unavailable feature (`location_class`) or the `LOCATION_CLASS_CHANGED`
    trigger is rejected with a capability-unavailable error;
  - error: every `since` argument must equal or precede the active-window start of the same window instance, or each
    `daily_at` time must fall within 12 h after the `since` time;
  - warning: `local_time` with `gt`/`gte`, an evening literal and no midnight-crossing window ("after 10 PM" is FALSE
    after midnight), suggesting `local_time_in`;
  - E025 stays: a window with start == end is invalid;
  - warning: the event triggers `USER_PRESENT`, `SCREEN_INTERACTIVE`, `POWER_CONNECTED` and `POWER_DISCONNECTED` are
    best effort, reliable only while notification access keeps the app running;
  - warning: a template that uses placeholders or app labels (the posted text stays generic, §11.5);
  - renaming a catalog id needs an alias table.
- Creation modes: manual builder; NL (prompt contract `jitai-nl-v1` -> proposal JSON -> validator -> deterministic
  rendering -> user activation); AI-discovered (local discovery pipeline proposes; AI optional for wording; always
  requires approval; autonomous mode does not exist in v1). AI-created limits are stricter (cooldown >= 60 min,
  maxPerDay 1-3, expiry 1-90 days; R10 §9.2).

### 11.2 Evaluation, gates and arbitration

- Only current definitions (`jitai_definition`: enabled, ACTIVE, not expired) are candidates, so an old version can
  never fire after an edit.
- Safety gates in R10 §9.1's order: G01 NOT_EFFECTIVE, G02 EXPIRED, G03 SNOOZED, G04 GLOBAL_PAUSE, G05
  NOTIFICATIONS_BLOCKED, G06 QUIET_HOURS (except `ALLOW_WHEN_INTERACTIVE` while the phone is in use), G07 DND, G08
  SUPPRESSED_BY_RULE, G09 COOLDOWN, G10 DAILY_CAP, G11 WEEKLY_CAP, G12 GLOBAL_MIN_GAP, G13 GLOBAL_DAILY_CAP, G14
  GLOBAL_WEEKLY_CAP, G15 CHANNEL_CAP (VOICE 2, VIDEO 1, IMAGE 3 per engine day), G16 LOST_ARBITRATION. Defaults:
  global daily cap 6, global weekly cap 30, minimum gap 30 min (R10 §9.2).
- G05 prerequisite: notifications enabled AND the channel's importance is not NONE AND notifications are not paused,
  checked at decision and again at claim. A blocked decision is SUPPRESSED(NOTIFICATIONS_BLOCKED) and does not count
  toward global caps; the in-app card fallback is CARD_PENDING (§11.5).
- DND suppresses under G07, except that scheduled slots defer within `maxLatenessMinutes`.
- SUPPRESSION rules (`kind = SUPPRESSION`, targeting categories or JITAI ids) block their targets under G08.
- Arbitration covers every decision point the serialized evaluator gathers (§11.4): priority, then the older last
  delivery, then `createdAt`, then id. For scheduled triggers, LOST_ARBITRATION and GLOBAL_MIN_GAP defer within
  `maxLatenessMinutes` instead of consuming the slot. DECIDED and DELIVERING rows count toward caps.

### 11.3 Decision points, keys and triggers

- Event triggers come from R10 §7.2's closed set, emitted by collectors and sync rather than derived from stored event
  types: ACTIVITY_STATE_CHANGED, HEALTH_SYNC_COMPLETED, SLEEP_SESSION_AVAILABLE, NOTIFICATION_POSTED,
  POWER_CONNECTED, POWER_DISCONNECTED, SCREEN_INTERACTIVE, USER_PRESENT. LOCATION_CLASS_CHANGED does not exist in v1.
- A trigger event carries its event time. An event older than `maxEventAgeMinutes` (default 10, set per event type)
  when it is evaluated is not delivered, so events that usage polling, sync replay or backfill insert late never fire
  a nudge out of context.
- Event dispatch reads `change_seq > watermark` (§5.6): inserts and semantic updates both move `change_seq`;
  unchanged re-fetches do not.
- Decision keys follow R10 §8.2 per trigger: events `v1|<jitaiId>|E|<floor(epochSecond / 900)>` (a UTC 15-minute
  bucket); intervals `v1|<jitaiId>|I|<window-instance start date>|<slot>`; daily_at
  `v1|<jitaiId>|D|<local date>|<HH:mm>`; snooze re-evaluation `v1|<jitaiId>|R|<first 16 hex of SHA-256 of the
  original key>`. Each decision also stores zone id, local date-time, trigger type, engine day, elapsed time and boot
  count. The delivery deadline is anchored at the nominal decision time (slot start or event time).
- An event pass resolves the daily features of dirty days on demand (through `FeatureResolver`) before it builds the
  snapshot, so an evaluation right after a sleep ingest sees the new value.
- `daily_at` staleness retry (R10 §8.2): a result that is UNKNOWN only because a remote feature is stale requests a
  sync of that stream and re-evaluates the slot at +10 and +20 minutes, within `maxLatenessMinutes`.

### 11.4 Serialized evaluation and commit

- Every due decision point (daily_at, interval, event, snooze follow-up) goes through one serialized evaluator, which
  gathers all points due within a 2-minute coalescing window and arbitrates across them (§11.2).
- The commit runs in the decision-commit runner (§5.6): a process-wide mutex plus one IMMEDIATE transaction that
  re-reads the gate counts (cooldowns, caps, global gap), inserts the decision and advances the evaluation watermark.
  The unique decision key is the final guard.
- Elapsed time between two recorded events follows R10 §8.6: the elapsed-realtime difference within one boot,
  otherwise the wall-clock difference clamped at 0. Cooldowns and gaps use this rule.
- Every snapshot value and trace leaf carries its `DataCategory`. A pure scrub function replaces a deleted category's
  values with a deleted marker; the category delete calls it (§5.5).
- Eval-log traces are written only when the result changes.

### 11.5 Delivery, content and recovery

- States: R10 §8.3's NOT_TRIGGERED, NOT_AVAILABLE, UNKNOWN, MISSED (scheduled triggers only), SUPPRESSED(gate),
  NOT_RANDOMIZED, DECIDED, DELIVERING, DELIVERED, DELIVERY_UNCERTAIN, FAILED(reason), EXPIRED and CANCELLED, plus
  CARD_PENDING: an in-app card waiting to be shown, which expires after `notificationTimeoutMinutes` and is excluded
  from global caps and from `consecutive_ignored` until it is displayed.
- Rendering (text, image, TTS) happens while the row is still DECIDED; the claim wraps only the post. The claim is one
  transaction that re-evaluates G01-G08 and writes CANCELLED or SUPPRESSED instead of DELIVERING when one fails. A
  cancellation before the post reverts DELIVERING to DECIDED in `NonCancellable`. The 2-minute lease is measured in
  elapsed time plus boot count.
- Rendering produces two texts: the in-app text (placeholders, app labels) and the posted text. The posted text is
  generic unless the user turned on "detailed notifications" (off by default); the renderer marks placeholder-derived
  parts. Posts are local-only unless the user opted in (§12).
- Crash recovery follows R10 §8.5: tag lookup through `getActiveNotifications()`; an uncertain delivery is never
  re-posted and counts toward caps. Crash points after commit, after claim, during render and after the post each
  recover with no double delivery and no lost cap.
- `ai_text` content draws from `ai_text_pool`: items are keyed by content hash, generated under a standing consent
  (§9.1), contain no number (§9.5), expire within 24 h and are purged on rule edit, consent change, retention and
  deletion. AI text always has a local template fallback.

### 11.6 Snooze, responses and backoff

- Snooze actions come from the rule's `SnoozePolicy` options; snooze state (wall time, elapsed time and boot count)
  lives in `jitai_runtime`. daily_at rules default to `RE_EVALUATE_AFTER`, with one R-keyed follow-up per original
  decision (a SNOOZE timer row). "Not now" snoozes until the window ends. UNTIL_TOMORROW = max(next engine-day
  rollover, quiet-hours end, next window start).
- The response enum equals the feature catalog's `last_response` enum; the first response wins (§5.2).
- Backoff counts only IGNORED, and DISMISSED without a positive proximal outcome. Nothing backs off or auto-pauses
  while the G05 delivery prerequisite is not met.

### 11.7 Scheduling

- The planner outputs timer rows (`jitai_timer`: due time, kind SLOT | PREFETCH | OUTCOME | SNOOZE | BACKSTOP, JITAI
  id, version, slot). One unique one-time work, `jitai-timer`, targets the earliest due time and re-arms itself
  (§13). There are no per-rule or per-time works and no exact alarms.
- Editing, disabling, deleting or expiring a definition deletes its timer rows in the same transaction as the change.
- A firing timer verifies that the definition exists, is enabled and has the same version, that the time is one of
  the rule's times and that it is within lateness; otherwise it re-plans.
- A pure `replan(reason = CLOCK | TIMEZONE | OFFSET | BOOT | PACKAGE_REPLACED, now, zone)` rebuilds every timer row
  from the current definitions; the reconciler calls it (§13).
- Interval slots follow min(everyMinutes) of the active interval rules (at least 15 minutes), independent of the
  collection profile.
- Ingesting a trigger-relevant event sets the dirty flag and enqueues `jitai-eval-events`. While any event or
  interval rule is enabled, a BACKSTOP row exists that drains the change watermark, so an event that arrives during a
  run is never lost.
- PREFETCH rows run the single-stream sync (§7.3) 10-15 minutes before a scheduled rule reads wearable metrics;
  OUTCOME rows compute proximal outcomes (R10 §8.7); SNOOZE rows are the R-keyed follow-ups.

## 12. Interventions

- Notification (full): one channel per intervention category (R10 §9.7), actions (Open, the rule's snooze options,
  Not now, Stop this JITAI), `tag = decisionKey` for idempotency, `setOnlyAlertOnce(true)`, delete intent records
  dismissal. The content intent targets a non-exported entry activity that deep-links to the JITAI history entry, and
  action buttons target a non-exported receiver; outcomes are recorded only through these, and each must present the
  decision's random `delivery_nonce`.
- Lock screen, watch and other surfaces: the posted text is generic unless detailed notifications are on, so by
  default it carries no snapshot value, placeholder or app label. Notifications use `VISIBILITY_PRIVATE` with a
  neutral public version ("Agentle has a suggestion") and a matching channel `lockscreenVisibility`; notification
  images draw no metric unless detailed notifications are on; posts are local-only (`setLocalOnly(true)`, no wearable
  bridging) unless the user opted in.
- Notifications blocked (permission denied, channel importance NONE or notifications paused): the decision is
  SUPPRESSED(NOTIFICATIONS_BLOCKED), outside global caps, an in-app card waits in CARD_PENDING (§11.5), and the
  Permission Center shows the blocked state.
- In-app card: dashboard card for pending interventions.
- Image: no v1 provider can generate images (SIWC is text-only; no verified on-device generator), so
  `imageGeneration = false` everywhere and `generateImage()` returns `UnsupportedFeature`. Image interventions use
  bundled pictures or `TemplateRenderer` cards drawn locally (Canvas: background, icon, text, the user's metric only
  when detailed notifications are on), cached as media artifacts [R09].
- Voice: the WAV is synthesized with `TextToSpeech.synthesizeToFile` while the decision is still DECIDED (§11.5), then
  a notification with a Listen action is posted; playback (ExoPlayer, `USAGE_MEDIA`/`CONTENT_TYPE_SPEECH`, audio
  focus, becoming-noisy handling) happens only on a visible screen, because background playback and focus requests
  fail on targetSdk 35+/Android 17. This replaces R10 §8.5 step 4's background `speak()`. Handles TTS engine missing,
  init failure/timeout, muted stream, Bluetooth output, user cancel; on failure the delivery downgrades to a plain
  notification (`TTS_UNAVAILABLE`). Network TTS voices off by default.
- Video: Media3 1.11.1 Transformer composes PNG slides + the TTS WAV into an H.264/AAC MP4 (720x1280, 30 fps,
  1.5 Mbit/s + 64 kbit/s, at most 90 s, platform diagnostics off), foreground and user-initiated only, written to
  `.tmp` then renamed, cancellable; JITAI VIDEO deliveries use bundled clips or a previously composed file. Encoder
  errors fall back to audio + slides in the app.
- Media storage: `noBackupFilesDir/media`, `media_artifact` rows, 200 MB quota with LRU eviction, 14-day default
  expiry, orphan sweep at app start and daily, share copies via `cacheDir/share` + FileProvider with explicit grants;
  "delete generated media" removes rows, files and share copies.

## 13. Background scheduling

`WorkScheduler` is the only place that enqueues work. Unique names:
- `collect-usage` (periodic), `collect-device` (periodic);
- `sync-googlehealth` (periodic, cadence by profile below) and `sync-googlehealth-now` (one-time, KEEP; expedited on
  31+), which runs the single-stream sync for staleness retries and on demand;
- `features-refresh` (periodic, after sync chains);
- `jitai-timer` (one-time, re-armed to the earliest `jitai_timer` row: slots, prefetches, outcomes, snooze
  follow-ups and the backstop; §11.7);
- `jitai-eval-events` (one-time KEEP; expedited on 31+, plain one-time on 29-30), enqueued when a trigger-relevant
  event is ingested;
- `retention` (daily), `media-cleanup` (daily), `insights-weekly`.

Policies: periodic work uses KEEP on reconcile and UPDATE on profile change, never REPLACE; `jitai-timer` is re-armed
after every replan. Collection profiles (Low/Balanced/High) set cadences [R02 §2.3]: `sync-googlehealth` runs every
6 h on unmetered networks (Low), every hour (Balanced, the default) or every 30 minutes (High), with a network
constraint; battery saver for 30 min drops to Low.

`ScheduleReconciler` runs on process start, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET` and
`TIMEZONE_CHANGED`. It calls `clampFutureCursors(now)` (which also runs before each sync), calls the JITAI
`replan(reason)` and re-arms `jitai-timer`, re-registers PendingIntents (activity transitions), and records gaps with
`ApplicationExitInfo` (API 30+). The receivers that trigger it are not exported, dispatch through an action
allow-list, and debounce the trigger except on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. After an app update,
stored definitions are re-validated against the new catalog; rules that fail become PAUSED with a notice.

The app runs in one process (§1 rule 10); the session, stream-sync and decision-commit mutexes and `SingleFlight`
assume it.

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
