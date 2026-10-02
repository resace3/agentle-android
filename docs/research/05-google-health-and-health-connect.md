# 05 - Google Health API ("Fitbit" connector) and Health Connect

Status: COMPLETE for this research pass (2026-10-01). Open questions are collected in section 9.

Owner decision (Nick): the production "Fitbit" connector targets the Google Health API ONLY (the successor to the Fitbit Web API). Nothing in this document targets the legacy Fitbit Web API contract. Legacy Fitbit endpoints are mentioned only where Google's own migration pages mention them.

Conventions used below:
- Every claim about current APIs, limits or policies carries a source tag such as `[DISC]`. Each tag resolves to a URL in section 0.
- **UNVERIFIED** means no primary source or live authenticated call confirms the claim.
- **undocumented** means the sources are silent on the point.
- **3P-observed** means a third-party library observed the behavior against the live API (django-google-health, see `[3P-DGH]`). That is evidence, not Google documentation.
- All JSON fixture values in section 8 are synthetic. Their shapes come from the cited sources.

---

## Key findings (TL;DR)

1. **API identity and status.**
   - The Google Health API v4 is served at `https://health.googleapis.com/v4/`, over both REST and gRPC.
   - The discovery directory lists `health:v4` as `preferred: true` (revision `20261001`). A `v4beta` also exists [DISC][DISC-DIR].
   - Legacy Fitbit Web API support ended on 2026-09-30, and the API is turned off on 2026-10-30 [GH-HOME][GH-MIG].
2. **Biggest blocker: new projects are not being onboarded.**
   - Google states: "While we are not onboarding new projects at this time, we are actively working to open access to more developers" [GH-HOME][GH-SETUP][GH-SUP][GH-VER].
   - Do not assume a new Agentle Google Cloud project can obtain working `googlehealth.*` tokens.
   - Until it can, the REST connector can only be built and tested against a fake server (section 8). Health Connect is the path a developer can test live today (sections 6 and 7.8).
3. **Authorization is Google OAuth 2.0, and the client type for Android is unresolved.**
   - Every Google Health guide and codelab uses a **Web application** client [GH-SETUP][GH-CL][GH-CL-PG][HC-FIT].
   - Android's native mechanism is `AuthorizationClient` (`play-services-auth:22.0.0`). It uses an Android OAuth client and needs no redirect URI [AND-AUTHZ][GMS-REL].
   - No Google Health page confirms that `googlehealth.*` scopes can be granted to an Android-type client. This is the top integration risk and is **UNVERIFIED**.
   - Custom URI schemes are "no longer supported on Android" [OAUTH-NATIVE].
   - "Most scopes for the Google Health API are restricted." Going beyond 100 users requires OAuth verification and an annual CASA assessment [GH-VER][GH-SETUP].
   - While the app is in Testing mode, refresh tokens expire after 7 days [OAUTH-WEB][GH-CL].
4. **Data model.** There is a single generic collection, `users/{user}/dataTypes/{data-type}/dataPoints`, with these read methods:
   - `list`: raw data from all sources, ordered by interval start time, newest first;
   - `:reconcile`: a deduplicated single stream;
   - `:rollUp`: windows in physical (UTC) time;
   - `:dailyRollUp`: civil (local) days;
   - `get`: one data point, for identifiable types only.

   Data type ids are kebab-case in paths and snake_case in filters. int64 values are JSON strings [DISC][GH-FILT][GH-VITALS].
5. **No change feed.** There is no changes API and no "updated since" filter. Filters accept only time-range restrictions with `>=` and `<`, joined by `AND` [DISC][GH-FILT]. Webhooks exist but need a public HTTPS subscriber endpoint [GH-WH], which a local-only phone app does not have. Incremental sync therefore means re-reading overlapping time windows and upserting idempotently.
6. **Limits.**
   - Requests: 300 per minute per user, 120,000 per minute per project, 86.4M per day per project. Exceeding them returns HTTP 429 [GH-RATE].
   - Page size: default 1440, maximum 10000. `sleep` and `exercise` default to 25 and cap at 25 [DISC].
   - Rollup range: 14 days maximum for `heart-rate`, `total-calories`, `active-minutes` and `calories-in-heart-rate-zone`; 90 days for every other type [DISC].
7. **Errors.**
   - All errors use the `google.rpc.Status` JSON shape (`{"error":{"code","message","status","details"}}`). Live 401 bodies were captured, and an unknown path returns an **HTML** 404 [PROBE].
   - Documented API-specific errors: 400 `FAILED_PRECONDITION`/`ACCOUNT_NOT_LINKED`, 412 "Precondition check failed.", 403 for a legacy Fitbit account, and filter reasons such as `INVALID_DATA_POINT_FILTER` [GH-MIG][GH-TRBL][GH-FILT].
   - `Retry-After` is not documented [GH-RATE].
8. **Health Connect.**
   - `androidx.health.connect:connect-client` latest stable is **1.1.0**. The latest alpha is 1.2.0-alpha06 (2026-08-26) [HC-REL].
   - The Google Health app (formerly the Fitbit app; Android package `com.fitbit.FitbitMobile`) **writes** these to Health Connect [GHAPP-HC][GHAPP-NEW]:
     - activity: steps, distance, floors, total calories burned, exercise and exercise route, VO2 max, speed, step cadence, elevation gained;
     - sleep sessions and stages;
     - vitals: heart rate, resting heart rate, HRV, respiratory rate, skin temperature, blood glucose;
     - body: weight, body fat;
     - other: hydration, nutrition, some cycle data.
   - It does **not** write oxygen saturation. It reads active calories but does not write them.
   - Without `READ_HEALTH_DATA_HISTORY`, reads are limited to 30 days before the first grant. Background reads need `READ_HEALTH_DATA_IN_BACKGROUND`. Changes tokens expire after 30 days [HC-READ][HC-SYNC][HC-PERM-REF].
9. **Recommendation: two sources behind one `HealthSource` interface (7.2).**
   - `HealthConnectSource`: ship first; it can be tested live now.
   - `GoogleHealthApiSource`: build it and test it in CI against the fake server. Keep it behind a feature flag until Google onboards the project and a live spike confirms the OAuth client type.
   - For both: keep provenance on every record, store UTC times plus UTC offsets, and choose one canonical source per data type. That prevents double counting around the Fitbit device -> Google Health cloud -> Health Connect loop (section 7.7).
   - Section 8 is the fake-server contract for CI: the endpoint table and validation rules, success fixtures machine-checked against the discovery schemas, error fixtures (verbatim where captured live), robustness cases R1 to R9, and a 40-scenario test matrix. Section 9 lists 26 open items.
10. **Policy flag for the product owner.**
    - Sending Google Health API data to a third party counts as a transfer. That includes OpenAI, for the "Sign in with ChatGPT" AI features.
    - Transfers are allowed only "to provide or improve your appropriate use case or features that are clear from the requesting application's user interface and only with the user's consent".
    - A periodic CASA assessment applies "if your product transfers data off the user's own device" [GH-POLICY].
    - The cited policies do not mention AI or LLMs at all (undocumented).
    - Google Play's health policy prohibits "Sharing health data with third parties without explicit, informed user consent" [PLAY-HEALTH].

---

## 0. Sources and method

### 0.1 Method

- **No WebFetch or WebSearch in this pass.** The owner asked to stop permission prompts for reading public documentation, so this pass used only:
  - the raw v4 discovery document, fetched with `curl` from `health.googleapis.com`;
  - raw developer.android.com HTML pages cached in this container on 2026-10-01;
  - extracts of developers.google.com/health pages captured by an earlier run of this task (2026-10-01). That run used a fetch tool that returns a model-written rendering of the page, so prose quotes tagged `GH-*` are near-verbatim. JSON blocks were requested verbatim, but treat the exact wording as best-effort;
  - unauthenticated `curl` probes of `health.googleapis.com`, with responses recorded verbatim;
  - a `git clone` of one third-party open-source client (`[3P-DGH]`).
- **No authenticated call to the Google Health API was made.** There is no onboarded project and there are no credentials. Every statement about authenticated behavior therefore comes from documentation or 3P observation.
- **Not reachable:**
  - `maven.google.com` redirects to `dl.google.com`, which is blocked here. Artifact versions come from official release-notes pages instead, and no mirrors were used.
  - Legacy Fitbit hosts were not used, by owner decision.
- **Local cache (ephemeral, this container only):**
  - `/tmp/claude-0/-home-claude/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/agent5/`: HC and Android pages as `*.html` and `*.txt`, and `gh/disc_v4.json`;
  - `/tmp/claude-0/-home-claude-agentle-android/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/prev_fitbit_results.txt`: the earlier doc extracts and probe output;
  - `.../scratchpad/gh3p/django-google-health/`: the 3P clone.

### 0.2 Source tags

| Tag | Source |
|---|---|
| DISC | Google Health API discovery document v4, revision 20261001: `https://health.googleapis.com/$discovery/rest?version=v4` (fetched raw 2026-10-01) |
| DISC-DIR | `https://www.googleapis.com/discovery/v1/apis?name=health` (lists `health:v4` preferred and `health:v4beta`) |
| PROBE | Unauthenticated `curl` probes of `https://health.googleapis.com/...` on 2026-10-01 (raw status, headers and bodies recorded) |
| GH-HOME | https://developers.google.com/health |
| GH-ABOUT | https://developers.google.com/health/about |
| GH-GS | https://developers.google.com/health/get-started |
| GH-SETUP | https://developers.google.com/health/setup |
| GH-SCOPES | https://developers.google.com/health/scopes |
| GH-DT | https://developers.google.com/health/data-types |
| GH-ENDP | https://developers.google.com/health/endpoints |
| GH-FILT | https://developers.google.com/health/filters |
| GH-DM | https://developers.google.com/health/data-management |
| GH-ZEROS | https://developers.google.com/health/data-presence-and-true-zeros |
| GH-RATE | https://developers.google.com/health/rate-limits |
| GH-TRBL | https://developers.google.com/health/troubleshooting |
| GH-WH | https://developers.google.com/health/webhooks |
| GH-MIG | https://developers.google.com/health/migration |
| GH-MIG-DA | https://developers.google.com/health/migration/data-access |
| GH-MIG-API | https://developers.google.com/health/migration/api-specifications |
| GH-CL | https://developers.google.com/health/codelabs/make-your-first-api-call |
| GH-CL-PG | https://developers.google.com/health/codelabs/make-your-first-api-call-using-oauth2-playground |
| GH-SLEEP | https://developers.google.com/health/data-types/sleep |
| GH-VITALS | https://developers.google.com/health/data-types/vitals |
| GH-WORK | https://developers.google.com/health/data-types/workouts |
| GH-CAL | https://developers.google.com/health/data-types/calories |
| GH-PROFILE | https://developers.google.com/health/profile |
| GH-LIBS | https://developers.google.com/health/libraries |
| GH-UAL | https://developers.google.com/health/universal-app-links |
| GH-VER | https://developers.google.com/health/app-verification |
| GH-LAUNCH | https://developers.google.com/health/launch |
| GH-CHECK | https://developers.google.com/health/developer-checklist |
| GH-SUP | https://developers.google.com/health/support |
| GH-RN | https://developers.google.com/health/release-notes |
| GH-LIST-REF | https://developers.google.com/health/reference/rest/v4/users.dataTypes.dataPoints/list |
| GH-POLICY | https://developers.google.com/health/policies/health-api-developer-user-data-policy |
| GAPI-POLICY | https://developers.google.com/terms/api-services-user-data-policy |
| OAUTH-NATIVE | https://developers.google.com/identity/protocols/oauth2/native-app |
| OAUTH-WEB | https://developers.google.com/identity/protocols/oauth2/web-server |
| AND-AUTHZ | https://developer.android.com/identity/authorization (developers.google.com/identity/authorization/android redirects here) |
| GMS-REL | https://developers.google.com/android/guides/releases |
| AIP-193 | https://google.aip.dev/193 (error model) |
| AIP-194 | https://google.aip.dev/194 (retry guidance) |
| GERR-SCOPE | https://developers.google.com/google-ads/api/docs/get-started/common-errors (Google-wide `ACCESS_TOKEN_SCOPE_INSUFFICIENT` convention, used only as a shape analogue) |
| GCQ | https://docs.cloud.google.com/docs/quotas/troubleshoot ("Google Cloud returns an HTTP 429 TOO MANY REQUESTS status code") |
| HC-REL | https://developer.android.com/jetpack/androidx/releases/health-connect |
| HC-GS | https://developer.android.com/health-and-fitness/health-connect/get-started |
| HC-AVAIL | https://developer.android.com/health-and-fitness/health-connect/availability |
| HC-FEAT | https://developer.android.com/health-and-fitness/health-connect/features/availability |
| HC-DT | https://developer.android.com/health-and-fitness/health-connect/data-types |
| HC-READ | https://developer.android.com/health-and-fitness/health-connect/read-data |
| HC-SYNC | https://developer.android.com/health-and-fitness/health-connect/sync-data |
| HC-RATE | https://developer.android.com/health-and-fitness/health-connect/rate-limiting |
| HC-PUB | https://developer.android.com/health-and-fitness/health-connect/publish |
| HC-FIT | https://developer.android.com/health-and-fitness/health-connect/migration/fit |
| HC-TEST | https://developer.android.com/health-and-fitness/health-connect/test/test-cases |
| HC-CLIENT-REF | https://developer.android.com/reference/kotlin/androidx/health/connect/client/HealthConnectClient |
| HC-PERM-REF | https://developer.android.com/reference/kotlin/androidx/health/connect/client/permission/HealthPermission |
| HC-CHANGES-REF | https://developer.android.com/reference/kotlin/androidx/health/connect/client/response/ChangesResponse |
| HC-ORIGIN-REF | https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/metadata/DataOrigin |
| HC-META-REF | https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/metadata/Metadata |
| HC-AGG | https://developer.android.com/health-and-fitness/health-connect/aggregate-data |
| GHAPP-HC | https://support.google.com/googlehealth/answer/14506680 ("How do I use Health Connect with the Google Health app?") |
| GHAPP-NEW | https://support.google.com/googlehealth/answer/17068213 (Fitbit app becomes the Google Health app on 2026-05-19) |
| GHAPP-3P | https://support.google.com/googlehealth/answer/14236613 (third-party connections) |
| PLAY-LISTING | https://play.google.com/store/apps/details?id=com.fitbit.FitbitMobile (title "Google Health (Fitbit)", developer Google LLC, updated 2026-09-24) |
| PLAY-HEALTH | https://support.google.com/googleplay/android-developer/answer/12991134 (Android health permissions policy) |
| PROTO-JSON | https://protobuf.dev/programming-guides/json/ (the ProtoJSON mapping, including default-value omission). Cited from prior knowledge; **not fetched in this pass**, so its exact wording is UNVERIFIED |
| 3P-DGH | https://github.com/django-health/django-google-health at commit `fbd79a3` (2026-09-28). A third-party Django library whose comments, fixtures and nightly live tests record live-API calibration from 2026-07-30 to 2026-08-07. Used as data only. |

---

## 1. Google Health API: what it is and its status

**What it is**
- It is "the strategic evolution of the legacy Fitbit Web API, rebuilt from the ground up on Google's modern infrastructure", with "a reconciled data stream", consolidated endpoints, webhooks and Google OAuth 2.0 [GH-ABOUT].
- Device support: "All Fitbit devices and Google Pixel watches are supported" [GH-ABOUT]. "Fitbit Ace devices are not supported by the Health API" [GH-DT].
- Protocols are REST and gRPC [GH-GS]. The gRPC services are `google.devicesandservices.health.v4.DataPointsService` and `google.devicesandservices.health.v4.HealthProfileService`; the method names show up in error metadata [PROBE].

**Status and timeline**
- The release notes date the launch to 2026-03-24. Later milestones [GH-RN]:
  - 2026-04-14: single-point `get`.
  - 2026-05-26: `ecg`/`irn` scopes; existing scopes split into explicit `.writeonly` variants; pairedDevices and subscription endpoints.
  - 2026-06-22: status dashboard.
  - 2026-08-17: women's-health write-only scopes and data types.
- Discovery directory: `health:v4` is `preferred: true` and `health:v4beta` is `preferred: false` [DISC-DIR]. The v4 discovery revision is `20261001` [DISC]. Requests to `v4alpha`, `v1` and `v3` discovery return 404 [PROBE].
- Fitbit Web API: "Support for the legacy Fitbit Web API ends on September 30, 2026 ... On October 30, 2026, the Fitbit Web API will be turned off" [GH-HOME][GH-MIG]. Today is 2026-10-01, so the legacy API is unsupported and has 29 days left.
- **Onboarding: "While we are not onboarding new projects at this time, we are actively working to open access to more developers and will share updates here as availability expands."** The same statement appears on [GH-HOME], [GH-SETUP], [GH-SUP] and [GH-VER].
  - What happens when a non-onboarded project enables the API or requests the scopes is **undocumented**.
  - The exact error is **UNVERIFIED**. It could be a consent-screen error, a 403 at the API, or the scope not appearing on the Data Access page.

**Prerequisites for an end user**
- "A user must sign in to the Google Health mobile app to link Google Health to their Google Account before your app can sync data through the Google Health API" [GH-MIG].
- Users still on a legacy Fitbit account get 403 "The caller does not have permission" with the note "Could not mint UberMint from GaiaMint" until they move the account to Google [GH-TRBL].
- From 2026-05-19, "the Fitbit app will become the Google Health app", and a Fitbit-account login has to be moved to Google [GHAPP-NEW].

**Prerequisites for a developer** [GH-SETUP][GH-VER][GH-LAUNCH][GH-CHECK]
- A Google Cloud project with the API enabled at `console.developers.google.com/apis/library/health.googleapis.com`.
- An OAuth consent screen: user type External, publishing status Testing, test users added on the Audience page, scopes added on the Data Access page under "Google Health API".
- Unverified apps are "limited to 100 users". "Supporting more than 100 users with the Google Health API requires completion of a third party security review."
- Verification has two parts:
  - OAuth app verification by Trust & Safety.
  - A CASA security assessment for restricted scopes. It is required annually, takes 2-3 weeks for tier 2 or 4-6 weeks for tier 3, and costs USD 500-4,500 paid to the assessor.
- An in-app disclosure is required "within the application itself".
- Policies to accept: Google Health API Developer Terms, Developer and User Data Policy, Research Pledge, User Data and Health Research Policy, Google OAuth 2.0 Policies, and Google API Services User Data Policy [GH-LAUNCH].

**Client libraries**
- Google publishes generated API client libraries for Go, Java, JavaScript, .NET, Node.js, Objective-C, PHP, Python and Ruby. None is specific to Android or Kotlin, and the libraries page does not mention mobile at all [GH-LIBS].
- Recommendation: use Retrofit, OkHttp and kotlinx.serialization with hand-written DTOs (section 7). Do not pull the generated Java client into the APK.

**Support**
- Issue tracker: `https://issuetracker.google.com/issues/new?component=1914241`.
- Developer forum, and a status dashboard at `https://status.healthapp.google.com/` [GH-SUP].

---

## 2. Authorization (Google OAuth 2.0 for an Android app)

### 2.1 Endpoints and token facts

| Item | Value | Source |
|---|---|---|
| Authorization endpoint | `https://accounts.google.com/o/oauth2/v2/auth` | [GH-MIG-DA][GH-CL] |
| Token endpoint | `https://oauth2.googleapis.com/token` | [GH-MIG-DA][GH-CL][OAUTH-NATIVE] |
| Revocation endpoint | `https://oauth2.googleapis.com/revoke` (POST `token=...`; HTTP 200 on success) | [OAUTH-NATIVE][OAUTH-WEB] |
| Access token lifetime | 1 hour (`expires_in: 3599` in examples) | [GH-MIG-DA][GH-CL][AND-AUTHZ] |
| Access token size | up to 2048 bytes (vs 1024 for legacy Fitbit) | [GH-MIG-DA] |
| Refresh token | Requires `access_type=offline`. "Refresh tokens can be timed-based. They will expire if they have not been used in 6 months, the user granted time-based access, or the app is in 'Testing' mode." | [GH-MIG-DA] |
| Testing-mode refresh lifetime | External user type with Testing publishing status gives 7-day refresh tokens. The codelab token response shows `"refresh_token_expires_in": 604799`. | [OAUTH-WEB][GH-CL]; 3P-observed nightly `invalid_grant` after about 7 days [3P-DGH `.github/workflows/live.yml`] |
| Refresh token cap | "maximum of 100 refresh tokens per client per account" | [OAUTH-WEB] |
| Forcing the consent screen again | `prompt=consent` | [GH-CL] |
| Legacy token migration | "existing access and refresh tokens cannot be transferred, requiring users to re-consent" | [GH-MIG] |

Example token response from the codelab (shape) [GH-CL]:
```json
{"access_token":"ya29.a0ATi6K2uasci7FyyIClNLtQou6z...","expires_in":3599,"refresh_token":"1//05EuqYpEXjJCHCgYIA...","scope":"https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly","token_type":"Bearer","refresh_token_expires_in":604799}
```

### 2.2 Scopes

Every scope has the form `https://www.googleapis.com/auth/googlehealth.<suffix>` [GH-SCOPES][DISC].

| Suffix | In [GH-SCOPES] | In [DISC] | Agentle needs it for |
|---|---|---|---|
| `activity_and_fitness.readonly` | yes | yes | steps, distance, floors, active-energy-burned, total-calories, exercise, active/zone minutes, VO2 max [GH-DT] |
| `health_metrics_and_measurements.readonly` | yes | yes | heart-rate, daily-resting-heart-rate, HRV, SpO2, weight, body-fat, height [GH-DT] |
| `sleep.readonly` | yes | yes | sleep [GH-DT] |
| `settings.readonly` | yes | yes | `users.getSettings` (IANA time zone, units) and `users.pairedDevices.list/get` [DISC] |
| `profile.readonly` | yes | yes | `users.getProfile` (optional; 3P-observed to carry only `age` [3P-DGH ingest.py]) |
| `location.readonly` | yes | yes | Only for `exportExerciseTcx` (GPS). Not needed in v1 [DISC][GH-WORK] |
| `ecg.readonly`, `irn.readonly` | yes | yes | Not in v1 |
| `nutrition.readonly` | yes | **no** (only `nutrition.writeonly` is in discovery) | Not in v1 |
| `logged_symptoms.readonly`, `mindfulness.readonly`, `reproductive_health.readonly` | **no** | yes | Do not request; the docs and discovery disagree |
| `*.writeonly` variants | yes | yes | Not requested (Agentle is read-only) |
| `https://www.googleapis.com/auth/cloud-platform` | n/a | yes | Only for webhook subscriber management. Not used |

Notes:
- `users.getIdentity` accepts any one of the read scopes [DISC]. The minimal v1 request is `activity_and_fitness.readonly`, `health_metrics_and_measurements.readonly`, `sleep.readonly` and `settings.readonly`.
- The consent screen is granular. Users may grant only some scopes, and apps must "Never crash, show a generic technical error, or render a blank screen if a user withholds one or more requested scopes". If no scope is granted, the app must route the user to a "Missing Permissions" screen [GH-SCOPES]. After consent, the granted scope list in the token response (or `AuthorizationResult.getGrantedScopes()`, UNVERIFIED on that exact method name) is the source of truth.
- Scope sensitivity: "Most scopes for the Google Health API are restricted" [GH-VER]. Which individual scopes are sensitive and which are restricted is **undocumented**.

### 2.3 OAuth client type: the evidence

| Evidence | What it says | Source |
|---|---|---|
| Google Health setup page | "Select **Web Server** when it asks 'Where are you calling from?'"; redirect URI `https://www.google.com` | [GH-SETUP] |
| Both REST codelabs | Client type "Web Application"; redirects `https://www.google.com` and `https://developers.google.com/oauthplayground` | [GH-CL][GH-CL-PG] |
| Google Fit migration (developer.android.com) | Table row "OAuth Configuration": Fit API = "Android or Web application type"; **Google Health API = "Web application type"**. Fitness-tracker companion apps are routed to the Google Health API as a "Web-centric platform requiring OAuth" | [HC-FIT] (cached raw HTML) |
| Google Health migration, data access page | Lists "Application Types: Web application, Android, Chrome Extension, iOS, TV, Desktop app, Universal Windows platform"; "Always launch Google OAuth flows using system browser contexts such as Chrome Custom Tabs (Android) or ASWebAuthenticationSession (iOS) ... Don't use embedded WebViews" | [GH-MIG-DA] |
| Android authorization guide | Create an **Android** client (package name and SHA-1). Optionally create a **Web** client "used to identify your backend server". Use `Identity.getAuthorizationClient(...).authorize(AuthorizationRequest)`. "call AuthorizationClient.authorize() once; in subsequent sessions ... call the same method to obtain an access token ... without any user interaction". "it is strongly discouraged to store refresh tokens on the device" | [AND-AUTHZ] |
| Native-app OAuth guide | "Custom URI schemes are no longer supported on Android and Chrome apps"; "The loopback IP address redirect option is DEPRECATED for Android, Chrome app and iOS OAuth client types"; Android developers are pointed to [AND-AUTHZ] | [OAUTH-NATIVE] |
| Third-party library | Stores tokens "minted by a platform client (iOS/Android)" and refreshes them with `client_id` and no secret ("Those are public clients"). This weakly suggests native clients have been used with this API, but it is not proof | [3P-DGH googlehealth/oauth.py] |

**Conclusion.** Whether an Android-type OAuth client (and therefore `AuthorizationClient`) can be granted `googlehealth.*` scopes is **UNVERIFIED**. The official docs only demonstrate Web clients. This is the first thing the live spike must test once the project is onboarded (section 7.9).

### 2.4 Recommended Android flows, in priority order

**A. Primary (preferred if the spike passes): `AuthorizationClient` with an Android OAuth client** [AND-AUTHZ]
- Dependency: `com.google.android.gms:play-services-auth:22.0.0`, the latest release (2026-08-26). Its release note: "Removed the APIs related to Google Sign-in. Use Android Credential Manager instead." [GMS-REL][AND-AUTHZ]
- Flow: `Identity.getAuthorizationClient(activity).authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(...))).build())`.
  - If `hasResolution()` is true, launch `pendingIntent.intentSender` through `ActivityResultContracts.StartIntentSenderForResult`.
  - Read the result with `getAuthorizationResultFromIntent(...)` and take `accessToken` [AND-AUTHZ].
- The flow uses no redirect URI and no PKCE handling in app code, and it keeps no refresh token on the device. Each sync calls `authorize()` again to get a fresh one-hour access token silently.
  - Token cache: "If you receive an IllegalStateException when using a token, clear the local cache" with `clearToken(ClearTokenRequest...)` [AND-AUTHZ].
  - Disconnect: `revokeAccess(RevokeAccessRequest)`. It "revokes all scopes previously granted" [AND-AUTHZ].
- Background use: whether `authorize()` returns a token without UI when called from a WorkManager worker (no Activity) is **UNVERIFIED**; the guide does not discuss background contexts.
  - If the result has a resolution, the worker must stop and post a "Reconnect Google Health" notification.
- Fit with the product: this matches "local-only persistent data". Nothing long-lived is stored, and Play services manages the grant.

**B. Fallback (only if A fails): Custom Tab, Web client, PKCE (S256) and a token broker**
- The app opens `https://accounts.google.com/o/oauth2/v2/auth` with the following in a Chrome Custom Tab [GH-MIG-DA][OAUTH-NATIVE]:
  - `client_id=<web client>`, `response_type=code`, `scope=...`, `access_type=offline`, `prompt=consent`, `include_granted_scopes=true`, `state`;
  - `code_challenge=BASE64URL(SHA256(verifier))` and `code_challenge_method=S256`.
- The redirect must be an https URI registered on the Web client. It is either:
  - a verified Android App Link (`https://<agentle-domain>/oauth2/callback` with `assetlinks.json`), or
  - a broker callback that 302s to the app. This is the pattern 3P-DGH uses: "Google then sends the user to the public callback ... ASWebAuthenticationSession / Chrome Custom Tab" [3P-DGH README/oauth.py].
- The code exchange and every refresh need the Web client's `client_secret`. The secret must not ship in the APK.
  - That means a small **stateless token broker**: an HTTPS endpoint holding only the client secret that exchanges codes and refreshes tokens. The refresh token could stay on the device (encrypted) and pass through the broker, or stay on the broker.
  - Both options conflict with "strongly discouraged to store refresh tokens on the device" and/or with local-only. **Product decision required.**
- In Testing mode the refresh token dies after 7 days, so developers must re-consent weekly [OAUTH-WEB][GH-CL].

**C. Never use**
- Embedded WebViews: "disallowed by Google OAuth policies" [GH-MIG-DA].
- Custom URI schemes: not supported on Android [OAUTH-NATIVE].
- Loopback redirects: deprecated for Android [OAUTH-NATIVE].
- A client secret embedded in the APK.
- Any legacy Fitbit OAuth: excluded by owner decision.

### 2.5 After consent: mandatory checks

These follow Google's own linking sequence [GH-MIG][GH-SCOPES][GH-TRBL]:
1. Check the granted scopes. If no `googlehealth` scope was granted, show the "Missing Permissions" screen. If some were granted, enable only the matching data types.
2. Call `GET /v4/users/me/identity` **before** marking the account linked.
   - `{healthUserId, legacyUserId}` maps the user. `legacyUserId` "must not be used for any other purpose" [DISC]. Agentle should store only `healthUserId`.
3. Handle the account-state errors:
   - **400 `FAILED_PRECONDITION` / `ACCOUNT_NOT_LINKED`**, with `metadata.redirect_uri = https://fitbit.google.com/auth/signup`. Show Google's suggested text: "Your Google Account isn't linked to Google Health yet. Open the Google Health mobile app and sign in with your Google Account ..." [GH-MIG].
   - **412 "Precondition check failed."**: the user "has not yet set up a Google Health profile in the Google Health app". Show "It looks like your Google Health profile isn't ready ..." [GH-TRBL].
   - **403 "The caller does not have permission." (UberMint/GaiaMint)**: the account is a legacy Fitbit account and must be moved to Google [GH-TRBL].
   - To send the user to the Google Health app from these screens, Google documents one App Link: `https://www.fitbit.com/in-app/today`, which "Opens the Fitbit mobile app to the Today page. If the app is not installed, the user is directed to Fitbit's website" [GH-UAL]. That page does not cover OAuth redirects. Agentle only hands the link to the system; it never calls a Fitbit host itself.
4. Only then save the connection state: account email, `healthUserId`, granted scopes and connected-at time, in DataStore or Room.

---

## 3. Base URL, resources and methods

**Base URL:** `https://health.googleapis.com/` with paths under `v4/` [DISC][GH-ENDP]. `{user}` may be the literal `me` [DISC]. Make the base URL injectable so tests can point it at MockWebServer (section 8).

### 3.1 Methods (from [DISC]; gRPC names from [PROBE])

| Method | HTTP and path (relative to base) | Query params / body | Response | Scope family |
|---|---|---|---|---|
| `users.dataTypes.dataPoints.list` (`ListDataPoints`) | `GET v4/users/{u}/dataTypes/{type}/dataPoints` | `filter`, `pageSize`, `pageToken`, `dataSourceFamily` | `ListDataPointsResponse` | read scopes |
| `...reconcile` (`ReconcileDataPoints`) | `GET v4/users/{u}/dataTypes/{type}/dataPoints:reconcile` | `filter`, `pageSize`, `pageToken`, `dataSourceFamily` | `ReconcileDataPointsResponse` | read scopes |
| `...rollUp` (`RollUpDataPoints`) | `POST v4/users/{u}/dataTypes/{type}/dataPoints:rollUp` | body `RollUpDataPointsRequest` | `RollUpDataPointsResponse` | read scopes |
| `...dailyRollUp` (`DailyRollUpDataPoints`) | `POST v4/users/{u}/dataTypes/{type}/dataPoints:dailyRollUp` | body `DailyRollUpDataPointsRequest` | `DailyRollUpDataPointsResponse` | read scopes |
| `...get` (`GetDataPoint`) | `GET v4/users/{u}/dataTypes/{type}/dataPoints/{id}` | none | `DataPoint` | read scopes; identifiable types only |
| `...exportExerciseTcx` | `GET v4/users/{u}/dataTypes/exercise/dataPoints/{id}:exportExerciseTcx?alt=media` | `partialData` (bool) | raw TCX (with `alt=media`) or JSON `{tcxData}` | needs both `activity_and_fitness` **and** `location` scopes |
| `...create` / `...patch` / `...batchDelete` | `POST .../dataPoints`, `PATCH .../dataPoints/{id}`, `POST .../dataPoints:batchDelete` | `DataPoint` / `{names[]}` (max 10000) | `Operation` | write scopes. **Not used by Agentle** |
| `users.getIdentity` (`GetIdentity`) | `GET v4/users/me/identity` | none | `Identity` | any read scope |
| `users.getSettings` (`GetSettings`) | `GET v4/users/me/settings` | none | `Settings` | `settings.readonly` |
| `users.getProfile` (`GetProfile`) | `GET v4/users/me/profile` | none | `Profile` | `profile.readonly` |
| `users.pairedDevices.list` (`ListPairedDevices`) | `GET v4/users/{u}/pairedDevices` | `pageSize` (default 5, max 100), `pageToken` | `ListPairedDevicesResponse` | `settings.readonly` |
| `users.pairedDevices.get` | `GET v4/users/{u}/pairedDevices/{d}` | none | `PairedDevice` | `settings.readonly` |
| `users.getIrnProfile`, `users.updateProfile`, `users.updateSettings` | `GET .../irnProfile`, `PATCH .../profile`, `PATCH .../settings` | | | not used |
| `projects.subscribers.*`, `projects.subscribers.subscriptions.*` | `v4/projects/{p}/subscribers[...]` | | | webhooks, `cloud-platform`. Not used (section 5.11) |

Notes:
- There is **no** `devices` resource: `GET v4/users/me/devices` returns an HTML 404 [PROBE]. The resource is `pairedDevices`.
- Some wrong-verb requests also return the HTML 404: unauthenticated `POST ...:reconcile` gave 404 `text/html`, but unauthenticated `GET ...:dailyRollUp` was routed and gave 401 [PROBE]. What an authenticated wrong-verb request returns is UNVERIFIED (section 9, U25).
- Retrofit can use the colon paths as-is. A relative URL such as `v4/users/me/dataTypes/{type}/dataPoints:reconcile` is safe because the colon is not in the first path segment.

### 3.2 Data types Agentle v1 needs

The path id is kebab-case, and the same type is snake_case in filters [GH-FILT][DISC].

| Agentle concept | Path id | Category | Operations (docs) | `list` filter fields [DISC] | Scope | Notes |
|---|---|---|---|---|---|---|
| Steps | `steps` | interval | list, reconcile, rollUp, dailyRollUp | `steps.interval.start_time` (RFC 3339) / `.civil_start_time` | activity_and_fitness | 1-minute resolution; true zeros [GH-DT][GH-ZEROS] |
| Distance | `distance` | interval | list, reconcile, rollUp, dailyRollUp | `distance.interval.*start_time` | activity_and_fitness | `millimeters` (int64 string); true zeros |
| Floors | `floors` | interval | **reconcile, rollUp, dailyRollUp (no list)** | reconcile only | activity_and_fitness | 3P-observed: "Google rejects `list`" [3P-DGH ingest.py] |
| Active energy | `active-energy-burned` | interval | list, reconcile, rollUp, dailyRollUp | `active_energy_burned.interval.*` | activity_and_fitness | `kcal` double; 3P-observed live with per-minute data since about 2026-08-07 |
| Total calories | `total-calories` | interval | **rollUp, dailyRollUp only** | n/a | activity_and_fitness | rollup range max 14 days; true zeros. "For daily calorie tracking, request daily rollup of `total-calories`" [DISC][GH-DT][GH-CAL] |
| Heart rate | `heart-rate` | sample | list, reconcile, rollUp, dailyRollUp | `heart_rate.sample_time.physical_time` / `.civil_time` | health_metrics_and_measurements | 1-second resolution; rollup max 14 days [GH-DT][GH-VITALS] |
| Resting heart rate | `daily-resting-heart-rate` | daily | list, reconcile | `daily_resting_heart_rate.date` (`YYYY-MM-DD`) | health_metrics_and_measurements | civil `date` only, no offset |
| HRV (daily) | `daily-heart-rate-variability` | daily | list, reconcile | `daily_heart_rate_variability.date` | health_metrics_and_measurements | optional for v1 |
| SpO2 (daily) | `daily-oxygen-saturation` | daily | list, reconcile | `daily_oxygen_saturation.date` | health_metrics_and_measurements | "Sleep-derived only" [GH-MIG-API] |
| Sleep | `sleep` | session | list, get, reconcile (+ writes) | `sleep.interval.end_time` / `.civil_end_time`. **Live support conflicts; see 5.1** | sleep | page size max 25; `dataSourceFamily` not supported on list [DISC] |
| Exercise | `exercise` | session | list, get, reconcile (+ writes) | `exercise.interval.civil_start_time` only | activity_and_fitness | page size max 25; TCX needs `location.readonly` |
| Weight | `weight` | sample | list, get, reconcile, rollUp, dailyRollUp (+ writes) | `weight.sample_time.*` | health_metrics_and_measurements | `weightGrams` double |
| Body fat | `body-fat` | sample | list, get, reconcile, rollUp, dailyRollUp (+ writes) | `body_fat.sample_time.*` | health_metrics_and_measurements | `percentage` double |
| Height | `height` | sample | list, get, reconcile (+ writes) | `height.sample_time.*` | health_metrics_and_measurements | `heightMillimeters` int64 string |
| Devices | `pairedDevices` (not a data type) | n/a | list, get | n/a | settings.readonly | battery, `lastSyncTime`, `deviceVersion` |

Other ids exist in discovery and the data types table but are out of v1 scope [DISC][GH-DT]:
- activity and fitness: `active-minutes`, `active-zone-minutes`, `activity-level`, `altitude`, `sedentary-period`, `swim-lengths-data`, `time-in-heart-rate-zone`, `calories-in-heart-rate-zone`, `vo2-max`, `run-vo2-max`, `daily-vo2-max`;
- vitals and body: `heart-rate-variability`, `oxygen-saturation`, `blood-glucose`, `core-body-temperature`, `respiratory-rate-sleep-summary`, `daily-respiratory-rate`, `daily-heart-rate-zones`, `daily-sleep-temperature-derivations`, `electrocardiogram`, `irregular-rhythm-notification`;
- nutrition: `hydration-log`, `nutrition-log`, `food`, `food-measurement-unit`;
- write-only: `menstrual-period`, `moods`, `ovulation-test`, `symptoms`;
- schema only: `basal-energy-burned` is in the DataPoint union but not in the docs table. It was 3P-observed to return zero points for Fitbit accounts on 2026-08-07.

---

## 4. Request/response shapes

### 4.1 JSON conventions (the decoder must follow all of these)

- **int64 values are JSON strings.** "In JSON responses, 64-bit integer values (like `beatsPerMinute`) are serialized as strings to preserve precision" [GH-VITALS].
  - Discovery marks these as `type: string, format: int64` [DISC]: `Steps.count`, `HeartRate.beatsPerMinute`, `Distance.millimeters`, `Floors.count`, `Height.heightMillimeters`, `MetricsSummary.steps`, `SleepSummary.minutes*` and the rollup `countSum`/`millimetersSum`.
  - Live 3P fixtures agree: `"count": "1"`, `"beatsPerMinute": "72"`, `"millimeters": "700"` [3P-DGH tests/fixtures].
  - Recommendation: decode these fields with a small custom serializer that accepts a JSON string **or** a JSON number (read `JsonPrimitive.content`), then convert with `toLongOrNull()`. Do not rely on the decoder to coerce quoted numbers, and do not fail a page when a number arrives unquoted (fixture R4 in 8.6).
- **Doubles are JSON numbers.** Examples: `kcal`, `weightGrams`, `percentage`, `caloriesKcal`, `averagePaceSecondsPerMeter`, `kcalSum`.
  - Watch out: `MetricsSummary.distanceMillimeters` is a **double**, while `Distance.millimeters` is an int64 string [DISC].
- **Durations** are strings of seconds with an `s` suffix, for example `"-14400s"`, `"900s"`, `"0s"` [GH-CL][GH-SLEEP][DISC]. Parse with a regex such as `^(-?\d+)(\.\d+)?s$`.
- **Timestamps** are RFC 3339 in UTC with a `Z` suffix. Fractional seconds can appear, for example `"2026-08-05T14:57:17.841848Z"` [3P-DGH exercise fixture][GH-CL]. Parse with `Instant.parse`.
- **Civil date-times** use the `CivilDateTime` shape `{"date":{"year","month","day"},"time":{"hours","minutes","seconds","nanos"}}`.
  - Zero-valued members are omitted, for example `"time":{"hours":8,"minutes":1}` [3P-DGH steps fixture].
  - `time` "Defaults to the start of the day, at midnight if omitted" [DISC]. Midnight is 3P-observed as `"time": {}` [3P-DGH ingest.py].
- **Output-only fields may be absent.** These are `civil*`, `createTime`, `updateTime`, `summary`, `shortAwakenings`, `platform` and `application`.
  - Official examples omit the civil fields [GH-CL][GH-SLEEP].
  - Live 3P payloads include civil times for interval and sample types, but the exercise and sleep `interval` blocks have none [3P-DGH fixtures].
- **Value union.** Each data point carries exactly one camelCase value key named after its type: `steps`, `heartRate`, `dailyRestingHeartRate`, `sleep`, `exercise`, `distance`, `floors`, `activeEnergyBurned`, `weight`, `bodyFat`, `height`, ... [DISC].
- **Enums are strings.** Unknown values must decode to an `UNKNOWN` bucket, because the docs show values that are not in discovery (section 4.8).

### 4.2 Envelope and provenance [DISC]

```
ListDataPointsResponse      { dataPoints: [DataPoint],           nextPageToken: string }  // "empty if the response is complete"
ReconcileDataPointsResponse { dataPoints: [ReconciledDataPoint], nextPageToken: string }
DataPoint           { name?: string, dataSource?: DataSource, <exactly one value-union field> }
ReconciledDataPoint { dataPointName?: string, <exactly one value-union field> }           // NO dataSource
DataSource  { recordingMethod?: MANUAL|PASSIVELY_MEASURED|DERIVED|ACTIVELY_MEASURED|UNKNOWN,
              platform?(output only): FITBIT|HEALTH_CONNECT|HEALTH_KIT|FIT|FITBIT_WEB_API|NEST|GOOGLE_WEB_API|GOOGLE_PARTNER_INTEGRATION,
              device?: { displayName?, manufacturer?, formFactor?: FITNESS_BAND|WATCH|PHONE|RING|CHEST_STRAP|SCALE|TABLET|HEAD_MOUNTED|SMART_DISPLAY },
              application?(output only): { packageName?, webClientId? (legacy Fitbit client id), googleWebClientId? } }
```
- `name` is "only supported for the subset of identifiable data types. For the majority of the data types ... this field would be empty". Its format is `users/{user}/dataTypes/{data_type}/dataPoints/{data_point}` [DISC].
  - Live payloads **with** `name`: sleep, exercise, weight [3P-DGH][GH-CL].
  - Live payloads **without** `name`: steps, heart-rate, distance, active-energy-burned, daily-resting-heart-rate, daily-oxygen-saturation [3P-DGH].
- `application.packageName` is "system-populated when the data is uploaded from the Fitbit mobile application, Health Connect or Health Kit" [DISC].

### 4.3 Time containers [DISC]

```
ObservationTimeInterval (interval types)  { startTime, startUtcOffset, endTime, endUtcOffset, civilStartTime(ro), civilEndTime(ro) }
ObservationSampleTime   (sample types)    { physicalTime, utcOffset, civilTime(ro) }
SessionTimeInterval     (sleep, exercise) { startTime, startUtcOffset, endTime, endUtcOffset, civilStartTime(ro), civilEndTime(ro) }
Daily types                               { date: { year, month, day } }   // "Date (in the user's timezone)"; no offset, no time
```

### 4.4 Value shapes for the v1 types [DISC] (live-confirmed by 3P fixtures where marked)

| Union key | Shape |
|---|---|
| `steps` | `{ interval, count: "int64" }`, count range [0, 1000000]. Live-confirmed. |
| `distance` | `{ interval, millimeters: "int64" }`, range [0, 1e9]. Live-confirmed. |
| `floors` | `{ interval, count: "int64" }` |
| `activeEnergyBurned` | `{ interval, kcal: double }`, "excluding the basal energy burn". Live-confirmed (`"kcal": 0.7933980000000002`). |
| `heartRate` | `{ sampleTime, beatsPerMinute: "int64" [1,300], metadata?: { motionContext?: ACTIVE\|SEDENTARY, sensorLocation?: CHEST\|WRIST\|FINGER\|HAND\|EAR_LOBE\|FOOT } }`. Live-confirmed without `metadata`. |
| `dailyRestingHeartRate` | `{ date, beatsPerMinute: "int64", dailyRestingHeartRateMetadata?: { calculationMethod: WITH_SLEEP\|ONLY_WITH_AWAKE_DATA } }`. Live-confirmed. |
| `dailyOxygenSaturation` | `{ date, averagePercentage, lowerBoundPercentage, upperBoundPercentage, standardDeviationPercentage? }` (doubles). Live-confirmed. |
| `sleep` | `{ interval: SessionTimeInterval, type?: CLASSIC\|STAGES, stages?: [SleepStage], shortAwakenings?(ro): [SleepStage], outOfBedSegments?: [{startTime,startUtcOffset,endTime,endUtcOffset}], summary?(ro): SleepSummary, metadata?: SleepMetadata, createTime?(ro), updateTime?(ro) }`. Live-confirmed. |
| `SleepStage` | `{ startTime, startUtcOffset, endTime, endUtcOffset, type: AWAKE\|LIGHT\|DEEP\|REM\|ASLEEP\|RESTLESS, createTime?(ro), updateTime?(ro) }`. Stages are "non-overlapping contiguous ... segments". `shortAwakenings` "can overlap with sleep stages". 3P-observed: the stage label lives at `stages[].type`. |
| `SleepSummary` (ro) | `{ minutesAsleep, minutesAwake, minutesInSleepPeriod, minutesToFallAsleep, minutesAfterWakeUp, stagesSummary: [{type, minutes, count}] }` (all int64 strings) |
| `SleepMetadata` | `{ processed(ro), mainSleep(ro), nap(ro), manuallyEdited(ro), stagesStatus(ro): SUCCEEDED\|REJECTED_COVERAGE\|REJECTED_MAX_GAP\|REJECTED_START_GAP\|REJECTED_END_GAP\|REJECTED_NAP\|REJECTED_SERVER\|TIMEOUT\|PROCESSING_INTERNAL_ERROR, externalId? }`. `processed=false` means "sleep period is detected but sleep stages is still processing". `mainSleep` is "the longest sleep session with stages within one day". |
| `exercise` | `{ interval: SessionTimeInterval, exerciseType: <182-value enum, e.g. WALKING>, displayName, activeDuration?, exerciseEvents?: [{eventTime, eventUtcOffset, exerciseEventType: START\|STOP\|PAUSE\|RESUME\|AUTO_PAUSE\|AUTO_RESUME}], metricsSummary, splitSummaries?, splits?, exerciseMetadata?: {poolLengthMillimeters?, hasGps?}, notes?, createTime?(ro), updateTime?(ro) }`. Live-confirmed. |
| `MetricsSummary` | `{ caloriesKcal?, distanceMillimeters?, steps?: "int64", averageHeartRateBeatsPerMinute?: "int64", activeZoneMinutes?: "int64", averagePaceSecondsPerMeter?, averageSpeedMillimetersPerSecond?, elevationGainMillimeters?, heartRateZoneDurations?: {lightTime, moderateTime, vigorousTime, peakTime}, mobilityMetrics?, runVo2Max?, totalSwimLengths? }` |
| `weight` | `{ sampleTime, weightGrams: double, notes? }`. Live-confirmed (`"weightGrams": 87090`). |
| `bodyFat` | `{ sampleTime, percentage: double }` |
| `height` | `{ sampleTime, heightMillimeters: "int64" }` |

`Exercise.displayName`: "For all exercise types other than `OTHER`, the system ignores client input and overrides this field with a generated name" [DISC].

### 4.5 `rollUp` and `dailyRollUp`

Request bodies, from the [GH-ENDP] examples:
```text
POST v4/users/me/dataTypes/steps/dataPoints:rollUp
{"range":{"startTime":"2026-02-17T17:00:00Z","endTime":"2026-02-17T17:59:59Z"},"windowSize":"60s"}

POST v4/users/me/dataTypes/steps/dataPoints:dailyRollUp
{"range":{"start":{"date":{"year":2026,"month":2,"day":26}},"end":{"date":{"year":2026,"month":2,"day":26}}},"windowSizeDays":1}
```

Request and response rules [DISC]:
- **Range semantics.** `range` is "Closed-open".
  - `windowSize` "Must be at least 1 second". When the range is not an exact multiple of the window, "the final bucket ... will be truncated".
  - `dailyRollUp`: "The start time must be aligned with the aggregation window", and `windowSizeDays` defaults to 1.
  - Maximum range is 14 days for `calories-in-heart-rate-zone`, `heart-rate`, `active-minutes` and `total-calories`, and 90 days for every other type.
  - **Conflict on the dailyRollUp end date.** Discovery calls `range` closed-open. But the official one-day example sends the same date as start and end [GH-ENDP], and 3P code (shapes "verified live 2026-08-07") states "The range is inclusive of both dates" [3P-DGH client.py `iter_daily_roll_up`].
    - Treat the dailyRollUp end date as **inclusive**. Key every returned point by its own `civilStartTime.date`, never by its position in the response, so an off-by-one day causes no harm. **UNVERIFIED**.
    - The fake server implements inclusive end dates.
- **Responses.**
  - `rollUp` returns `{rollupDataPoints:[{startTime, endTime, <union>}], nextPageToken?}` ("If this field is omitted, there are no subsequent pages").
  - `dailyRollUp` returns `{rollupDataPoints:[{civilStartTime, civilEndTime, <union>}]}`. It has **no `nextPageToken` field**, even though the request accepts `pageSize` and `pageToken`.
- **Off-wrist exclusion.** A rollup value aggregates "reconciled data points from all data sources, excluding those data points that are identified as recorded by wearables in intervals when they were not actually worn".
- **Union values for the v1 types:**

  | Union key | Value |
  |---|---|
  | `steps` | `{countSum: "int64"}` |
  | `distance` | `{millimetersSum: "int64"}` |
  | `floors` | `{countSum: "int64"}` |
  | `totalCalories` | `{kcalSum: double}` |
  | `activeEnergyBurned` | `{kcalSum: double}` |
  | `heartRate` | `{beatsPerMinuteMin, beatsPerMinuteAvg, beatsPerMinuteMax}` (doubles) |
  | `weight` | `{weightGramsAvg}` |
  | `bodyFat` | `{bodyFatPercentageAvg}` |

  `dailyRollUp` adds `restingHeartRatePersonalRange` and `heartRateVariabilityPersonalRange`.
- **3P-observed (live, 2026-08-07):** dailyRollUp points carry `civilStartTime`/`civilEndTime` as `{date:{...}, time:{}}` with the same `*RollupValue` blocks as rollUp [3P-DGH ingest.py].
- **Leading zeros.** The dailyRollUp `month`/`day` values must not have leading zeros. "Invalid JSON payload received. Octal/hex numbers are not valid JSON values" [GH-TRBL]. This only matters for hand-written fixtures.

### 4.6 Identity, settings, profile and paired devices [DISC]

- **Identity:** `{ name: "users/me/identity", healthUserId, legacyUserId }`. 3P-observed live keys are exactly these three, and "googleUserId does not exist despite the older docs' naming" [3P-DGH oauth.py]. `healthUserId` is 1-63 characters from `[A-Za-z0-9-]`.
- **Settings:**
  - `timeZone`: IANA ("Updates to this field are currently not supported");
  - `utcOffset` (duration);
  - units: `distanceUnit`, `weightUnit`, `heightUnit`, `temperatureUnit`, `waterUnit`, `glucoseUnit`, `swimUnit`;
  - other: `languageLocale`, stride settings, `foodLanguageCode`.
- **Profile:** `age`, `membershipStartDate`, stride lengths. 3P-observed live: "the live profile payload carries `age` only" [3P-DGH ingest.py]. The profile guide has no `getProfile` or `getSettings` example (it shows a People API birthday call instead), so these shapes come from [DISC] alone [GH-PROFILE].
- **PairedDevice:** `{ name: "users/{u}/pairedDevices/{d}", deviceVersion, deviceType: TRACKER|SCALE, batteryStatus ("High | Medium | Low | Empty"), batteryLevel: int32, lastSyncTime, macAddress, features: [string] }`.
  - The list response is `{pairedDevices: [...], nextPageToken?}`.
  - Troubleshooting suggests using `deviceVersion` ("Charge 6") and `lastSyncTime` to explain missing data [GH-TRBL].

### 4.7 Write responses (not used by Agentle)

`create`, `patch` and `batchDelete` return a long-running `Operation` `{name?, done, response | error, metadata?}`. The sleep create example returns `{"done":true,"response":{"@type":"type.googleapis.com/google.devicesandservices.health.v4.DataPoint", ...}}` [GH-SLEEP][DISC]. Agentle is read-only and must not request write scopes.

### 4.8 Documentation discrepancies (why the client must be lenient)

| Topic | One source says | Another source says | Handling |
|---|---|---|---|
| Exercise distance key | Codelab JSON: `"distanceMillimiters"` [GH-CL][GH-CL-PG] | `distanceMillimeters` [DISC]; 3P live fixture uses `distanceMillimeters` | Use the discovery spelling. Optionally accept the typo as an alias. |
| `recordingMethod` value | Vitals page: `"MANUALLY_ENTERED"` [GH-VITALS] | Enum has `MANUAL` only [DISC] | Unknown enum maps to `UNKNOWN`. |
| list time params | Vitals page: `...dataPoints?startTime=...&endTime=...` [GH-VITALS] | No such params; time ranges go in `filter` [DISC][GH-LIST-REF] | Never send `startTime`/`endTime` query params. |
| Sleep filters | Filters guide: sleep uses `.interval.civil_end_time`, and OR "Not supported" in general [GH-FILT] | Discovery: `sleep.interval.end_time`/`civil_end_time` with `AND`, `OR` [DISC]. 3P-observed: sleep "rejects every member tried" (2026-07-30) | Try the documented filter and fall back to unfiltered recency paging (5.1). **UNVERIFIED** |
| Interval end-time filters | Not offered for interval types [DISC] | 3P-observed: `end_time`/`civil_end_time` rejected (steps) | Filter on start time only. Clamp the upper bound client-side. |
| pairedDevices.get name | Method doc: `users/{user}/devices/{device}` [DISC] | Path and `PairedDevice.name`: `users/{u}/pairedDevices/{d}` [DISC]; `/v4/users/me/devices` is a 404 [PROBE] | Use the `name` returned by list. |
| dailyRollUp paging | Request has `pageSize`/`pageToken` | Response has no `nextPageToken` [DISC] | Keep each request at or below 1440 windows (well within 90 days). |
| Read scopes | `logged_symptoms`/`mindfulness`/`reproductive_health` `.readonly` absent; `nutrition.readonly` present [GH-SCOPES] | The opposite in [DISC] | Agentle requests none of them. |
| Scope for heart-rate, HRV, resting HR | `health_metrics_and_measurements.readonly` [GH-DT] | 3P-DGH maps them to `activity_and_fitness.readonly`. That is not live evidence, because 3P-DGH requests both scopes by default [3P-DGH ingest.py, constants.py] | Request both scopes; gate types per [GH-DT]; a 403 still maps to `ScopeMissing(type)` |
| HR `name` | Example `users/me/dataTypes/heart-rate/dataPoints/hr-123456789` [GH-VITALS] | heart-rate points carry no `name` live [3P-DGH] | Never key HR on `name`. |
| Client-assigned ids | "client-assigned custom IDs (not yet supported)" [GH-DM] | "The `{data_point}` ID can be client-provided" [DISC] | Irrelevant for read-only. |

## 5. Time ranges, pagination, reconciliation, sync, quotas, errors, provenance, time zones

### 5.1 Time filters (the `filter` query parameter on `list` and `:reconcile`)

**Syntax** [DISC][GH-FILT]:
- The syntax is a subset of AIP-160. Only `>=` (inclusive lower bound) and `<` (exclusive upper bound) are allowed, joined with `AND`.
- `OR` is rejected with `INVALID_DATA_POINT_FILTER_EXPRESSION_STRUCTURE`. Discovery documents `OR` only for the sleep end-time fields.
- The data type name in the filter is **snake_case**. A hyphenated name returns 400 `INVALID_DATA_POINT_FILTER`.

**Fields by category** [DISC]:

| Category | Field patterns | Literal |
|---|---|---|
| interval | `{t}.interval.start_time`; `{t}.interval.civil_start_time` | physical: RFC 3339 (`"2026-03-01T00:00:00Z"`); civil: `YYYY-MM-DD[THH:mm:ss]` |
| sample | `{t}.sample_time.physical_time`; `{t}.sample_time.civil_time` | same |
| daily | `{t}.date` | `YYYY-MM-DD` |
| session (exercise) | `exercise.interval.civil_start_time` only ("Excluding Sleep and ECG") | civil |
| sleep | `sleep.interval.end_time`, `sleep.interval.civil_end_time` ("AND, OR") | physical or civil |
| ECG | `electrocardiogram.interval.start_time >=` only | physical |

**Validation errors** (all HTTP 400) [GH-FILT]:
- Mixing physical and civil bounds: `INVALID_DATA_POINT_FILTER_MIXED_TIME_RESTRICTIONS` ("Filter cannot contain both physical and civil time ranges").
- A field whose type does not match the path: `INVALID_DATA_POINT_FILTER_COLLECTION_MISMATCH`.
- Upper bound not greater than lower bound: `INVALID_TIME_RANGE` ("Query end time must be strictly larger than start time").
- A wrong operator: `INVALID_DATA_POINT_FILTER_RESTRICTION_COMPARATOR`.

**Encoding.** `steps.interval.civil_start_time >= "2026-03-04T00:00:00"` is sent as `steps.interval.civil_start_time%20%3E%3D%20%222026-03-04T00%3A00%3A00%22` [GH-FILT]. Retrofit's `@Query` encodes this correctly.

**Live 3P calibration** (2026-07-30) [3P-DGH ingest.py]. Rejections came back with reason `INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER`, which is not in Google's docs.
- **Samples:** `<key>.sample_time.civil_time >=` works. "Without this filter a sample fetch walks the account's ENTIRE history (observed: a heart-rate sync that never finished)."
- **Daily:** `<key>.date >=` works.
- **Interval:** `<key>.interval.civil_start_time >=` works. `end_time`/`civil_end_time` are rejected (verified against steps).
- **Sleep:** "rejects every member tried (`sample_time.*`, `session.civil_start_time`, `date`, `civil_time`, `interval.*`)". It is fetched unfiltered, and recency ordering bounds the result.

**Agentle filter strategy.** This is a design recommendation. Every result is also clamped client-side to the requested window.

| Type family | Try first (documented) | On HTTP 400 with a filter reason, fall back to (3P-verified) |
|---|---|---|
| interval (steps, distance, active-energy-burned) | `t.interval.start_time >= "<utcStart>" AND t.interval.start_time < "<utcEnd>"` | `t.interval.civil_start_time >= "<civilStart - 14h>"`, then clamp |
| floors, total-calories | no list; use `:rollUp` / `:dailyRollUp` (or `:reconcile` for floors with the interval filter) | n/a |
| sample (heart-rate, weight, body-fat, height) | `t.sample_time.physical_time >= ... AND t.sample_time.physical_time < ...` | `t.sample_time.civil_time >= "<civilStart - 14h>"`, then clamp |
| daily (daily-resting-heart-rate, ...) | `t.date >= "YYYY-MM-DD" AND t.date < "YYYY-MM-DD"` | `t.date >= "YYYY-MM-DD"`, then clamp |
| exercise | `exercise.interval.civil_start_time >= "<civil - 14h>" AND exercise.interval.civil_start_time < "<civil + 14h>"` | lower bound only, then clamp |
| sleep | `sleep.interval.end_time >= "<utcStart>" AND sleep.interval.end_time < "<utcEnd>"` | no filter. Page newest-first and stop once a page's oldest `interval.startTime` is earlier than `utcStart - 24h`. |

The ±14h widening covers every possible UTC offset when a UTC window is turned into civil bounds.

### 5.2 Pagination

- **`list` and `:reconcile`:**
  - `pageSize` defaults to 1440 and maxes at 10000; "values above that will be truncated". For `exercise` and `sleep`, the default and maximum are both 25 [DISC][GH-LIST-REF].
  - Pass the previous `nextPageToken` as `pageToken`. `nextPageToken` is "empty if the response is complete", and the codelab shows `"nextPageToken": ""` [DISC][GH-CL].
- **`:rollUp`:** same page sizes. "All other request fields need to be the same as in the initial request when the page token is specified". `nextPageToken` is omitted when done [DISC].
- **`pairedDevices.list`:** default 5, max 100. "When paginating, all other parameters ... must match" [DISC].
- **Ordering.** `list`: "Data points in the response will be ordered by the interval start time in descending order", i.e. newest first [DISC]. Ordering for `:reconcile`, `:rollUp` and `:dailyRollUp` is undocumented, so the client must not depend on it.
- **Client rules (design):**
  - Treat an absent, `null` or `""` token as the end.
  - Resend identical parameters with the token.
  - Abort the type for this run if a token repeats, or after 500 pages.
  - Commit a window only after **all** its pages succeed.
- **Page sizes (design):** use 10000 for interval and sample types. A day of per-minute steps is 1440 points, but raw heart rate at 1-second resolution can be about 86,400 points per day [GH-DT], so prefer `:rollUp` with 60 s windows for heart-rate history. Use 25 for sleep and exercise.

### 5.3 `list` vs `:reconcile` vs `:rollUp` vs `:dailyRollUp`

- **`list`** "returns all stored records as uploaded without deduplication" [GH-DM] and "returns records from all available data sources, which might contain overlapping intervals" [GH-VITALS].
  - It is the only method that carries `dataSource` (provenance).
- **`:reconcile`** "resolves conflicts and deduplicates overlapping records across devices and sync sessions into a single continuous stream" [GH-DM].
  - Its points have no `dataSource` [DISC].
  - Use it for charts and for floors.
- **`:rollUp`** aggregates "based on a window in seconds, over the datetime range based on the users physical time (in UTC)" [GH-ENDP].
  - It excludes off-wrist periods [DISC].
- **`:dailyRollUp`** "automatically attributes data to the calendar day on which it was recorded according to the user's local time" [GH-DT].
  - Use it as the source of truth for the daily totals shown to the user. Whether it equals the Google Health app's displayed totals is **UNVERIFIED**.
- **`dataSourceFamily`** applies to list, reconcile, rollUp and dailyRollUp. It must be the full resource name; "short identifiers cause `400 Bad Request` with `INVALID_ARGUMENT`" [GH-FILT]. Values [DISC]:

  | Value | Includes |
  |---|---|
  | `users/me/dataSourceFamilies/all-sources` | Everything. This is the default. |
  | `users/me/dataSourceFamilies/google-wearables` | "Google and Fitbit tracker devices ... Excludes manually logged data" |
  | `users/me/dataSourceFamilies/google-sources` | "first-party Google data, such as data from tracker devices, manually logged data, and Health Connect" |
  | `users/me/dataSourceFamilies/self-sources` | Only data this client wrote |

  It is **not supported for `sleep` list**: the request fails with `INVALID_ARGUMENT` when set explicitly. "For `sleep`, use ReconcileDataPoints instead" [DISC].

### 5.4 True zeros, on-wrist filtering and data presence [GH-ZEROS]

- **Definition.** A true zero is "an explicit data point that indicates a user was wearing their device and actively tracking, but recorded a value of zero". It applies to **altitude, distance, floors, steps, total calories**.
- **`list` behavior.** It returns only worn periods, and gaps mean off-wrist or not synced. "Empty records (omitting the metric-specific field like `count`) represent true zeros and should be interpreted as zero values".
  - The page has no JSON example, so the exact empty-record shape is **UNVERIFIED**. The fake server models it as `"steps":{"interval":{...}}` with no `count`.
  - That model is also what ProtoJSON produces for a zero-valued scalar, which it omits by default [PROTO-JSON]. Discovery marks `Steps.count` as Required [DISC], which fits a field that is present but zero. This is an inference.
- **`rollUp` behavior.** A zero value is a true zero. "No data returned for intervals when the device wasn't worn".
- **Agentle (design):** store "no data" (not worn) as absence and a true zero as `0`. Never fill gaps with zeros. This matters for averages and for AI summaries.

### 5.5 Data latency

- "Updates to the user's data is only available after they sync their activity tracker or manually enter new data". The "Fitbit device and Google Health app can automatically sync every 15 minutes" [GH-DT].
- Sleep gets rewritten after detection:
  - `metadata.processed=false` means "sleep period is detected but sleep stages is still processing" [DISC].
  - One live 3P sleep record has `createTime` 03:13Z and `updateTime` 11:30Z [3P-DGH fixture].
- How long a tracker can stay unsynced, for example while the phone app is closed, is undocumented. Hence the overlap windows below.

### 5.6 Incremental sync without a change feed

- **What the API offers:** no changes API, and no `updateTime` or modified-since filter field. All filters are on observation time [DISC][GH-FILT].
- **Data management guide:** it describes a read/write sync lifecycle and server-generated ids, but no change detection [GH-DM].
- **Google's suggested backfill:** "phased data sync" with an initial "hot load" of 7-14 days, then a background "cold load". "You can query a user's data as far back as it has been recorded; the API imposes no limitations" [GH-DT].
- **Webhooks** (5.11) are Google's push mechanism, but they need a public HTTPS server.
- **Recommended algorithm** (design; section 7.4 has pseudo-code):
  1. Keep a `SyncState` per data type: `syncedThrough` (Instant), `backfilledFrom` (Instant), `lastDeepResyncAt`, `consecutiveFailures`, `nextAllowedAt`.
  2. On each run, fetch `[syncedThrough - overlap(type), now)`:
     - overlap = 48 h for interval and sample types;
     - overlap = 7 days for sleep and exercise (stage processing, user edits);
     - overlap = 7 days for daily types.
  3. Read every page, clamp to the window, then commit in **one Room transaction**:
     - **Non-identifiable types** (no `name`): delete the stored rows for that window, insert the fetched rows, and advance `syncedThrough`. This handles corrections and deletions.
     - **Identifiable types** (sleep, exercise, weight, body-fat, height): upsert by `name`. Tombstone any stored `name` whose start falls in the window but was not returned, but only when every page succeeded.
  4. Fetch `:dailyRollUp` for the last 7 civil days for steps, distance, floors, total-calories and active-energy-burned. That is one request per type and gives the user-facing daily totals.
  5. Run a weekly deep re-sync of the last 30 days to catch very late device syncs.
  6. Backfill: first the hot load (14 days), then older 30-day chunks in background runs, down to a configurable horizon (default 1 year).

### 5.7 Quotas and rate limits

- **Documented limits** [GH-RATE]:

  | Scope | Limit |
  |---|---|
  | Per project, per day | 86.4M requests ("~1,000 QPS sustained") |
  | Per project, per minute | 120,000 requests |
  | Per user, per minute | **300** requests ("5 QPS per user"; unverified apps "Max 250 QPS total") |

  - "Rate limits are evaluated across daily, minutely, and per-user intervals".
  - Exceeding them returns "the error `429 Too Many Requests`", and apps should "backoff sending requests and implement retry logic".
  - Increases are requested "through the Google Cloud Console".
- **Undocumented:** whether `Retry-After` is sent, the error body, and the `status` string [GH-RATE]. Google Cloud in general: "Google Cloud returns an HTTP `429 TOO MANY REQUESTS` status code" [GCQ].
- **Agentle budget (design):** use a client-side token bucket of 4 requests/s and 200/min per account.
  - A normal sync run is about 15 types x 1-3 pages, plus 5 dailyRollUps, plus settings and devices: roughly 25-50 requests.
  - The heart-rate history backfill uses `:rollUp` with `windowSize: "60s"` over 14-day ranges. That is 20,160 windows, or 3 pages at `pageSize: 10000`.

### 5.8 Error model and catalog

**Shape** [AIP-193][DISC `Status`][PROBE]:
- Every error body is `{"error":{"code": <HTTP status>, "message": "...", "status": "<CANONICAL_CODE>", "details": [ {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"...","domain":"...","metadata":{...}} ]}}`.
- "The `code` field in the JSON is an HTTP status code, *not* the direct value of `google.rpc.Status.code`" [AIP-193].
- A reason matches `[A-Z][A-Z0-9_]+[A-Z0-9]` and has at most 63 characters [AIP-193].
- Non-JSON errors exist: **404 for an unknown path or verb is an HTML page** [PROBE]. Classify by HTTP status first, and parse the JSON body only when `Content-Type` is `application/json`.

| HTTP | `status` | `reason` / message (verbatim where known) | Source | Agentle action (design) |
|---|---|---|---|---|
| 400 | INVALID_ARGUMENT | "Request contains an invalid argument" (unsupported data type id) | [GH-TRBL] | Disable that type for the run; log; no retry |
| 400 | INVALID_ARGUMENT | `INVALID_DATA_POINT_FILTER`, `..._RESTRICTION_COMPARATOR`, `..._EXPRESSION_STRUCTURE`, `..._COLLECTION_MISMATCH`, `..._MIXED_TIME_RESTRICTIONS`, `INVALID_TIME_RANGE`. "Check `error.details[].metadata.detailedReasons`". 3P-observed: `INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER` | [GH-FILT][3P-DGH] | Switch to the fallback filter once (5.1); otherwise fail the type |
| 400 | INVALID_ARGUMENT | bad `dataSourceFamily` (short id, or set on sleep list) | [GH-FILT][DISC] | Bug; fail the type |
| 400 | (UNVERIFIED) | "Invalid JSON payload received. Octal/hex numbers are not valid JSON values" (dailyRollUp with leading zeros) | [GH-TRBL] | Bug |
| 400 | (UNVERIFIED) | "Your client has issued a malformed or illegal request" (wrong verb or syntax) | [GH-TRBL] | Bug |
| 400 | FAILED_PRECONDITION | reason `ACCOUNT_NOT_LINKED`, domain `health.googleapis.com`, `metadata.redirect_uri: "https://fitbit.google.com/auth/signup"`, message "The account is not linked to Google Health." | [GH-MIG] (also 3P live fixture) | State `ACCOUNT_NOT_LINKED`; stop syncing; show Google's text and link |
| 401 | UNAUTHENTICATED | "Request is missing required authentication credential. ..." with reason `CREDENTIALS_MISSING`, domain `googleapis.com`, metadata `{method, service}`; header `www-authenticate: Bearer realm="https://accounts.google.com/"` | [PROBE] | Bug: no token was attached |
| 401 | UNAUTHENTICATED | "Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project." with **no details**; header `www-authenticate: Bearer realm="https://accounts.google.com/", error="invalid_token"`. The troubleshooting page labels this "INVALID_AUTHENTICATOR: Token expired" | [PROBE][GH-TRBL] | `clearToken`, get a token again, retry **once**; a second 401 sets state `NEEDS_REAUTH` |
| 401 | UNAUTHENTICATED | "API keys are not supported by this API. ..." | [PROBE] | Never send API keys |
| 403 | PERMISSION_DENIED (UNVERIFIED) | Missing scope: Google Health docs name `MISSING_OAUTH_SCOPE`. The Google-wide convention is "Request had insufficient authentication scopes." with `ACCESS_TOKEN_SCOPE_INSUFFICIENT`. The exact body for this API is UNVERIFIED | [GH-FILT][GERR-SCOPE] | Mark the type "scope not granted"; offer re-consent; no retry |
| 403 | PERMISSION_DENIED (UNVERIFIED) | "The caller does not have permission." with "Could not mint UberMint from GaiaMint" (legacy Fitbit account) | [GH-TRBL] | State `LEGACY_FITBIT_ACCOUNT`; show migration help |
| 404 | n/a (HTML) | `Error 404 (Not Found)!!1` page, "The requested URL `/v4/...` was not found on this server." | [PROBE][GH-TRBL] | Bug or unsupported endpoint; never retry; do not parse as JSON |
| 404 | NOT_FOUND (UNVERIFIED body) | `get` on an unknown data point id | undocumented | Treat as deleted |
| 412 | (UNVERIFIED; probably FAILED_PRECONDITION) | "Precondition check failed." (no Google Health profile yet) | [GH-TRBL] | State `PROFILE_NOT_READY`; show Google's text |
| 429 | RESOURCE_EXHAUSTED (UNVERIFIED) | quota exceeded; body and `Retry-After` undocumented | [GH-RATE][GCQ] | Back off (7.6) |
| 500, 502, 503, 504 | INTERNAL, -, UNAVAILABLE, DEADLINE_EXCEEDED (UNVERIFIED) | undocumented for this API | - | Bounded retry with backoff, then `Result.retry()` |
| OAuth token endpoint 400 | `{"error":"invalid_grant", ...}` | expired or revoked refresh token or code. Only applies to fallback flow B | [OAUTH-WEB] | `NEEDS_REAUTH` |

- **Retry policy note.** AIP-194 recommends automatic retry only for `UNAVAILABLE`, and says `RESOURCE_EXHAUSTED` and `INTERNAL` "should not" be retried automatically [AIP-194].
  - Agentle's read calls are idempotent. `:rollUp` and `:dailyRollUp` are POSTs but read-only.
  - So bounded retries with exponential backoff and jitter on 429, 500, 502, 503 and 504 are acceptable, and they must honor `Retry-After` when present.
  - 3P-DGH does the same: it retries `{429, 500, 502, 503, 504}` up to 3 times with exponential backoff from 1 s, honors `Retry-After`, and refreshes and retries once on 401 [3P-DGH client.py].

### 5.9 Provenance

- Every `list` point carries `dataSource` [DISC]. Fields:
  - `recordingMethod`;
  - `platform`;
  - `device` with `displayName`, `manufacturer` and `formFactor`;
  - `application` with `packageName`, `webClientId` and `googleWebClientId`.
- Live examples [3P-DGH fixtures]:
  - device data: `{"recordingMethod":"PASSIVELY_MEASURED","device":{"displayName":"Charge 5"},"platform":"FITBIT"}`;
  - exercise also has `"formFactor":"FITNESS_BAND"`;
  - a manual weight: `{"recordingMethod":"MANUAL","platform":"FITBIT"}`;
  - derived values (daily resting HR, sleep, active energy): `"recordingMethod":"DERIVED"`.
- `:reconcile` and the rollups drop provenance [DISC].
- `platform: HEALTH_CONNECT` marks data the Google Health app imported **from** Health Connect. When Agentle also reads Health Connect directly, skip these points on the API side to avoid double counting (7.7).
- **Design:** store a normalized provenance per record: `connector`, `platform`, `deviceName`, `formFactor`, `recordingMethod`, `appPackage` and `upstreamId` (the `name`, if any). Optionally store a hash of the raw JSON for audits.

### 5.10 Time zones

- Each record carries a physical UTC time **plus the user's UTC offset at that moment** (`utcOffset`, or `startUtcOffset`/`endUtcOffset`), and optional output-only civil times [DISC].
  - Store an `Instant` plus the offset seconds.
  - Render each record in its **own** offset, not the phone's current zone, so travel and DST do not shift history.
  - Sleep that crosses a DST change has different start and end offsets. Keep both.
- Daily types carry only a civil `date` "in the user's timezone" [DISC]. 3P notes that "without a UTC offset in the payload the user-local day is unknowable" [3P-DGH ingest.py]. Store these as `LocalDate` keys and never convert them to an `Instant`.
- Daily totals come from `:dailyRollUp`, which "attributes data to the calendar day on which it was recorded according to the user's local time" [GH-DT].
- `Settings.timeZone` (IANA) and `Settings.utcOffset` give the account's current zone [DISC]. Use them for display defaults and to build civil filter bounds.
- Civil filter literals carry no zone (`YYYY-MM-DD[THH:mm:ss]`) [DISC]. When translating a UTC window into civil bounds, widen it by ±14 h and clamp after the fetch.

### 5.11 Webhooks: documented, not used [GH-WH][DISC]

- **Setup.** A project-level subscriber is created with `POST v4/projects/{project-number}/subscribers?subscriberId=...`. Its body holds:
  - an HTTPS `endpointUri`;
  - `endpointAuthorization.secret`;
  - per-type `subscriptionCreatePolicy`, either `AUTOMATIC` or `MANUAL`.

  Subscriber management needs the `cloud-platform` scope. Use the project **number**, not the project id; the id returns 400 or 403 [GH-TRBL].
- **Payload.** `{"data":{"version":"1","clientProvidedSubscriptionName":...,"healthUserId":...,"operation":"UPSERT",...,"dataType":"steps","intervals":[{"physicalTimeInterval":{...},...}]}}`. It is signed in the `GOOGLE-HEALTH-API-SIGNATURE` header (Tink, ECDSA P-256 with SHA-256; keyset at `https://www.gstatic.com/googlehealthapi/webhooks/webhooks_public_keyset.json`).
- **Delivery.** The receiver must reply `204` "immediately". Undelivered notifications are retried "for up to 7 days ... with exponential backoff".
- **Why Agentle does not use them.** A local-only phone app has no public HTTPS endpoint, and a notification only names intervals, so the app would still have to fetch the data. Revisit only if a backend is ever added; it could relay an FCM push to wake the sync.

## 6. Health Connect (on-device path)

### 6.1 What it is, availability and versions

- **Model.** Health Connect is an on-device store. It needs "No OAuth required (on-device permissions)". Registration is a "Play Store project and health apps declaration", and permissions are "Android manifest permissions" [HC-FIT].
- **Availability** [HC-AVAIL][HC-GS]:
  - It "requires a mobile device running Android 9 (API 28) or higher with Google Play services installed".
  - From Android 14 it is part of the system (Settings > Security & Privacy > Privacy > Health Connect). On Android 13 and lower it is a Play Store app, package `com.google.android.apps.healthdata`.
  - "Health Connect is not supported on devices with work profiles."
  - "The Health Connect SDK supports Android 8 (API level 26) or higher, while the Health Connect app is only compatible with Android 9 (API level 28)".
- **Library** `androidx.health.connect:connect-client` [HC-REL]:

  | Version | Notes |
  |---|---|
  | **1.1.0** | Latest stable. Released October 08, 2025, "promoted to its first stable release". |
  | **1.2.0-alpha06** | Latest alpha, 2026-08-26. The get-started page snippet uses it [HC-GS]. |
  | 1.2.0-alpha05 | Library minSdk raised to 24. |
  | 1.2.0-alpha04 | Matchmaking APIs. |
  | 1.2.0-alpha03 | `getChanges(changesToken, pageSize)`, with pageSize 1..5000 per [HC-CLIENT-REF]. |

  - Test artifact: `androidx.health.connect:connect-testing:1.0.0-alpha04` (2026-08-12), which provides `FakeHealthConnectClient` and `FakePermissionController` [HC-REL].
  - Recommendation: pin **1.1.0** for production. v1 needs no alpha-only API. The versions could not be cross-checked on maven.google.com because `dl.google.com` is blocked here, so confirm resolution at first build.
- **Availability check** [HC-CLIENT-REF][HC-GS]: `HealthConnectClient.getSdkStatus(context)` returns one of:

  | Constant | Value | Meaning |
  |---|---|---|
  | `SDK_UNAVAILABLE` | 1 | "Apps should hide any integration points to Health Connect in this case" |
  | `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` | 2 | The Health Connect provider must be updated |
  | `SDK_AVAILABLE` | 3 | Ready to use |

  `getOrCreate` throws `UnsupportedOperationException` ("SDK version too low or running in a profile") or `IllegalStateException` ("if the SDK is not available").
- **Manifest** [HC-GS]:
  - Add `<queries><package android:name="com.google.android.apps.healthdata" /></queries>`.
  - Add a rationale activity handling `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android 13 and lower).
  - Add an `activity-alias` with action `android.intent.action.VIEW_PERMISSION_USAGE`, category `android.intent.category.HEALTH_PERMISSIONS` and `android:permission="android.permission.START_VIEW_PERMISSION_USAGE"` (Android 14+).
  - Request permissions with `PermissionController.createRequestPermissionResultContract()`.
  - An onboarding activity is optional (`androidx.health.ACTION_SHOW_ONBOARDING` / `android.health.connect.action.SHOW_ONBOARDING`).
- **Feature flags.** Check with `healthConnectClient.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == FEATURE_STATUS_AVAILABLE`. Other flags include `FEATURE_READ_HEALTH_DATA_HISTORY`, `FEATURE_SKIN_TEMPERATURE` and `FEATURE_MATCHMAKING` [HC-FEAT].

### 6.2 What the Google Health app (formerly Fitbit) writes to Health Connect

**Facts** [GHAPP-HC][GHAPP-NEW][PLAY-LISTING]:
- The Fitbit app became the "Google Health app" on 2026-05-19. Its Play listing (package `com.fitbit.FitbitMobile`) is titled "Google Health (Fitbit)".
- The user turns sync on with: Google Health app > Connections > Partner apps > "Sync your favorite health apps" > Set up > Accept > choose data > grant permissions.
- Requirements: Android 9+ and an adult Google Account.
- Writing is **not automatic**. The user has to set up the connection.

| Written by Google Health [GHAPP-HC] | Health Connect record | Read permission (`android.permission.health.`) [HC-DT][HC-PERM-REF] |
|---|---|---|
| Steps | `StepsRecord` | `READ_STEPS` |
| Step cadence | `StepsCadenceRecord` | `READ_STEPS` |
| Distance | `DistanceRecord` | `READ_DISTANCE` |
| Floors | `FloorsClimbedRecord` | `READ_FLOORS_CLIMBED` |
| Elevation gained | `ElevationGainedRecord` | `READ_ELEVATION_GAINED` |
| Speed | `SpeedRecord` | `READ_SPEED` |
| Total calories burned | `TotalCaloriesBurnedRecord` | `READ_TOTAL_CALORIES_BURNED` |
| Exercise, exercise route | `ExerciseSessionRecord` (+ `ExerciseRoute`) | `READ_EXERCISE` (routes: see 6.3) |
| VO2 max | `Vo2MaxRecord` | `READ_VO2_MAX` |
| Sleep session, sleep stages | `SleepSessionRecord` (stages inside) | `READ_SLEEP` |
| Heart rate | `HeartRateRecord` | `READ_HEART_RATE` |
| Resting heart rate | `RestingHeartRateRecord` | `READ_RESTING_HEART_RATE` |
| Heart rate variability | `HeartRateVariabilityRmssdRecord` | `READ_HEART_RATE_VARIABILITY` |
| Respiratory rate | `RespiratoryRateRecord` | `READ_RESPIRATORY_RATE` |
| Skin temperature | `SkinTemperatureRecord` | `READ_SKIN_TEMPERATURE` (`FEATURE_SKIN_TEMPERATURE`) |
| Body temperature | `BodyTemperatureRecord` | `READ_BODY_TEMPERATURE` |
| Blood glucose | `BloodGlucoseRecord` | `READ_BLOOD_GLUCOSE` |
| Weight; body fat % | `WeightRecord`; `BodyFatRecord` | `READ_WEIGHT`; `READ_BODY_FAT` |
| Hydration; nutrition | `HydrationRecord`; `NutritionRecord` | `READ_HYDRATION`; `READ_NUTRITION` |
| Periods, flow, intermenstrual bleeding | menstruation records | `READ_MENSTRUATION`, `READ_INTERMENSTRUAL_BLEEDING` |
| Medical records | personal health records (FHIR) | medical permissions (out of scope) |

**Not written** (read only by Google Health): **oxygen saturation**, **active calories burned**, mental wellbeing session duration, cervical mucus, ovulation test and sexual activity [GHAPP-HC]. So active calories and SpO2 from a Fitbit device are available only through the Google Health API.

**Undocumented / UNVERIFIED:**
- The `dataOrigin.packageName` on records Google Health writes. It is expected to be `com.fitbit.FitbitMobile`; verify on a device.
- The `metadata.device` and `recordingMethod` values it sets.
- How often it writes, and the latency after a tracker sync.
- How much history it writes when sync is first enabled. The support page only says the user is asked whether Google Health may access "historic data and data in the background", and that permission concerns Google Health reading Health Connect.

**Consequence:** Agentle can get Fitbit and Pixel Watch data **today**, with no Google Health API onboarding, through Health Connect. This is limited to the types in the table and to Health Connect's history rules (6.3).

### 6.3 Permissions, the 30-day window and background reads

- **Declaration.** Declare every `android.permission.health.READ_*` that Agentle uses in the manifest, and request them at runtime with the contract [HC-GS][HC-DT].
- **Proposed v1 read set:**
  - activity: `READ_STEPS`, `READ_DISTANCE`, `READ_FLOORS_CLIMBED`, `READ_ACTIVE_CALORIES_BURNED`, `READ_TOTAL_CALORIES_BURNED`, `READ_EXERCISE`, `READ_VO2_MAX`;
  - vitals: `READ_HEART_RATE`, `READ_RESTING_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`, `READ_OXYGEN_SATURATION`, `READ_RESPIRATORY_RATE`;
  - sleep and body: `READ_SLEEP`, `READ_WEIGHT`, `READ_BODY_FAT`, `READ_HEIGHT`;
  - access: `READ_HEALTH_DATA_HISTORY` and `READ_HEALTH_DATA_IN_BACKGROUND`.
- **Exercise routes** are out of scope for v1, and the docs disagree. The data-types page lists `android.permission.health.READ_EXERCISE_ROUTE` [HC-DT]. The API reference defines `PERMISSION_READ_EXERCISE_ROUTES` = `android.permission.health.READ_EXERCISE_ROUTES`, which "can't be granted via the standard permission request mechanism, and can only be granted by a user in Settings, or via the dialog launched by ... ExerciseRouteRequestContract" [HC-PERM-REF].
- **History (30-day rule)** [HC-READ][HC-FEAT]:
  - "By default, all applications can read data from Health Connect for up to 30 days prior to when any permission was first granted."
  - To read further back, request `PERMISSION_READ_HEALTH_DATA_HISTORY` (`android.permission.health.READ_HEALTH_DATA_HISTORY`). "Otherwise, without this permission, an attempt to read records older than 30 days results in an error."
  - Reinstalling the app revokes everything, and the 30-day window restarts from the new grant.
  - Gate the request on `FEATURE_READ_HEALTH_DATA_HISTORY`.
- **Background reads** [HC-READ][HC-FEAT]:
  - Foreground reads work "when your app is in the foreground". Background reads need `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` and `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND`.
  - Google's sample schedules a 1-hour `PeriodicWorkRequest`.
  - "If the user doesn't grant all of the permissions that are required for background reads, your app should still run". Without the background permission, Agentle syncs only when the app is in the foreground.

### 6.4 Reading

- **Paging.** `readRecords(ReadRecordsRequest(recordType, timeRangeFilter, dataOriginFilter, pageSize, pageToken))` has a default `pageSize` of **1000**. Iterate with `pageToken`.
  - The token "can return an empty string "" instead of null when pagination is complete", so "use isNullOrEmpty()". Google's own sample loops on `!= null`, which is a doc inconsistency [HC-READ].
- **Aggregates.** `aggregate`, `aggregateGroupByDuration` and `aggregateGroupByPeriod` exist [HC-AGG].
  - "When you perform an aggregate read, the Aggregate API accounts for any duplicate data and keeps only the data from the app with the highest priority."
  - "Only the Activity and Sleep data types are deduped by Health Connect ... For other types of data, the aggregated results combine all data of the type in Health Connect from all apps which wrote the data."
  - Users set the app priority themselves.
- **On-device steps:** "Starting with the June 2026 update, steps tracked natively by Health Connect are attributed to a Synthetic Package Name (SPN)", which is device-specific and app-scoped.
  - Before that, they were attributed to `android`.
  - Do not hardcode SPNs. Use `HealthConnectManager.getCurrentDeviceDataSource()` (framework API on Android 14 with SDK extension 11 or higher; "not yet available in the Health Connect Jetpack library") [HC-READ].
- **Provenance.** `Record.metadata` has `id`, `dataOrigin` (package), `lastModifiedTime`, `clientRecordId`, `clientRecordVersion`, `device` and `recordingMethod` (0 unknown, 1 actively recorded, 2 automatically recorded, 3 manual entry) [HC-META-REF][HC-ORIGIN-REF].
- **Exceptions** [HC-READ][HC-CLIENT-REF]:

  | Exception | Meaning |
  |---|---|
  | `IllegalStateException` | Service unavailable or invalid request. Google's sample also catches it as the **quota** error: `catch (quotaError: IllegalStateException) { // Backoff }`. |
  | `IOException` | Disk issues |
  | `RemoteException` | Service or IPC issues |
  | `SecurityException` | Permission not granted, or the data types were not declared for a published app |

### 6.5 Changes API (incremental sync)

Sources: [HC-SYNC][HC-CHANGES-REF][HC-CLIENT-REF].
- **Getting a token.** Call `getChangesToken(ChangesTokenRequest(recordTypes = setOf(X::class), dataOriginFilters = ...))`. "We recommend getting separate tokens per data type".
- **Reading changes.** `getChanges(token)` returns `ChangesResponse{changes, changesTokenExpired, hasMore, nextChangesToken}`.
  - Loop while `hasMore`.
  - An `UpsertionChange` carries the full record. Skip those whose `metadata.dataOrigin.packageName` is Agentle's own package.
  - A `DeletionChange` "only provide[s] the record id".
- **Expiry.** "an unused Changes token expires within 30 days". With `changesTokenExpired`, "clients should generate a new changes-token via getChangesToken".
  - Google's recommended recovery is "Read and dedupe all data": re-read from the last read timestamp, or the last 30 days, and dedupe by record id.
- **Design:** keep one token per record type in Room. Run a full re-read of the last 30 days (with the history permission, from `backfilledFrom`) on expiry or after reinstall. Use the HC record `id` as the upstream key.

### 6.6 Rate limits [HC-RATE]

- For reads and changes there are two limits: "A periodic limit on the number of API calls" and "A daily limit". The numbers are **undocumented**.
- "background rate limiting is stricter than foreground rate limiting".
- Google recommends using the changelog "rather than over-relying on raw read requests".

### 6.7 Google Play requirements [HC-PUB][PLAY-HEALTH]

- **Before publishing on Play:**
  - complete the **Data safety** section;
  - complete the **Health apps declaration** form (App content page). Declare which health features use which data types, with a justification for each;
  - post the privacy policy on the store page. It must be the same one Health Connect links to.
- **Updates.** "The data type accesses are allow-listed for a package name regardless of app version". A changed data-type set needs a re-declaration, and a new app version "may be reviewed again".
- **Without the declaration:** a public Play release that never declared access shows end users a blocking dialog when they try to link with Health Connect [HC-PUB]. Whether unpublished, sideloaded or debug builds are blocked is not stated. The wording only addresses apps "published in the Play store and released to the public", so they are presumably not blocked. This is an inference, **UNVERIFIED**.
- **Policy limits:** "Transferring or selling user health or fitness data to third parties like advertising platforms, data brokers, or any information resellers" and "Sharing health data with third parties without explicit, informed user consent" are prohibited. Permitted uses include "Fitness, wellness and coaching" [PLAY-HEALTH].

### 6.8 Testing Health Connect

- **Unit tests:** `androidx.health.connect:connect-testing:1.0.0-alpha04` [HC-REL]. `FakeHealthConnectClient` replaces `HealthConnectClient` and has "full stubs for records and changes". `FakePermissionController` (with `FakePermissionControllerOverrides`) emulates permission checks and revocations.
- **Device tests:** install the Google Health app on a test phone and pair a Fitbit or Pixel Watch. Turn on Health Connect sync, then verify in Agentle:
  - the `dataOrigin` values;
  - the sleep stages;
  - the exercise sessions;
  - the 30-day window behavior;
  - the changes tokens.
- Google's test-case catalog lists the required states: "All permissions must be granted prior to testing" and "All permissions must be revoked prior to testing" [HC-TEST].

### 6.9 Google Health API vs Health Connect for Agentle

| Aspect | Google Health API | Health Connect |
|---|---|---|
| Can be used today by a new developer | **No** (not onboarding new projects) [GH-SETUP] | **Yes** |
| Auth | Google OAuth; restricted scopes; verification and CASA above 100 users [GH-VER] | Runtime permissions plus a Play declaration [HC-PUB] |
| Fitbit data coverage | All Fitbit and Pixel Watch types, including SpO2, active energy, AZM, daily HRV and RHR summaries, and device battery [GH-DT] | Only what Google Health writes (6.2). No SpO2, no active calories, no devices |
| History | Unlimited [GH-DT] | 30 days before the first grant, unless `READ_HEALTH_DATA_HISTORY` [HC-READ] |
| Change detection | None (time windows) [DISC] | Changes tokens (30-day expiry) [HC-SYNC] |
| Dedup / aggregation | `:reconcile`, rollups (off-wrist excluded) [DISC] | Aggregate API dedupes activity and sleep by user priority [HC-AGG] |
| Network and privacy | Network calls to Google | On-device only |
| Rate limits | 300 requests/min/user [GH-RATE] | Undocumented numbers; stricter in background [HC-RATE] |

## 7. Recommended connector architecture

Everything in this section is a design recommendation. API facts it relies on are cited in sections 2-6.

### 7.1 Shape

```
           Compose UI (connect screens, per-metric source picker, status)
                         |
                 ConnectorRegistry  (Hilt singleton)
                 /                 \
   HealthConnectSource          GoogleHealthApiSource ---- TokenProvider (A: AuthorizationClient | B: broker | Fake)
   (androidx connect-client)    (Retrofit + OkHttp + kotlinx.serialization; base URL injectable)
                 \                 /
                  SyncEngine (per source, per metric type; isolated failures)
                         |
          Room (local only): observations, sessions, daily summaries, sync_state, connection_state
                         |
          WorkManager: periodic sync per source + expedited one-off on app open / pull-to-refresh
```

- **Ship order:**
  1. `HealthConnectSource`. It can be tested live now and covers Fitbit data through the Google Health app (6.2).
  2. `GoogleHealthApiSource`. Build it fully, test it against the fake server (section 8), and keep it behind a remote or debug flag until Google onboards the project and the 7.9 spike passes.
- **Isolation.** A failing data type must not fail the run (3P-DGH does the same: "Failure isolation: one data type failing ... "). A failing source must not affect the other source.

### 7.2 Core interfaces (sketch)

```kotlin
enum class SourceId { HEALTH_CONNECT, GOOGLE_HEALTH_API }
enum class MetricType { STEPS, DISTANCE, FLOORS, ACTIVE_ENERGY, TOTAL_ENERGY, HEART_RATE, RESTING_HR_DAILY,
                        HRV_DAILY, SPO2_DAILY, SLEEP, EXERCISE, WEIGHT, BODY_FAT, HEIGHT }

interface HealthSource {
    val id: SourceId
    suspend fun availability(): SourceAvailability      // NotInstalled, UpdateRequired, NotOnboarded, NeedsConsent, Ready
    suspend fun grantedTypes(): Set<MetricType>
    suspend fun sync(type: MetricType, window: SyncWindow, state: SyncState): SyncOutcome
}

sealed interface ConnectorError {
    data object NeedsReauth : ConnectorError
    data class ScopeMissing(val type: MetricType) : ConnectorError
    data class AccountNotLinked(val signupUrl: String?) : ConnectorError     // 400 ACCOUNT_NOT_LINKED
    data object ProfileNotReady : ConnectorError                            // 412
    data object LegacyFitbitAccount : ConnectorError                        // 403 UberMint/GaiaMint
    data class RateLimited(val retryAfter: Duration?) : ConnectorError      // 429 / HC IllegalStateException quota
    data class Transient(val cause: Throwable?) : ConnectorError            // 5xx, IO, timeouts, malformed body
    data class Unsupported(val type: MetricType, val detail: String) : ConnectorError  // 400 filter/arg, HTML 404
}

data class Provenance(val source: SourceId, val platform: String?, val deviceName: String?, val formFactor: String?,
                      val recordingMethod: String?, val appPackage: String?, val upstreamId: String?)
```

Time model: every observation keeps `startUtc: Instant`, `endUtc: Instant` (or `timeUtc`), and `startOffsetSec` / `endOffsetSec`. Daily values keep `localDate: LocalDate`. A true zero is stored as `0`, and "not worn" is stored as no row (5.4).

### 7.3 Google Health API client stack

- **Retrofit interface.** Return the raw `Response<ResponseBody>` so the client controls JSON vs HTML parsing (the 404 is HTML, 5.8). Parse it with one shared `Json` instance.

```kotlin
interface GoogleHealthService {
    @GET("v4/users/me/dataTypes/{type}/dataPoints")
    suspend fun list(@Path("type") type: String, @Query("filter") filter: String?, @Query("pageSize") pageSize: Int?,
                     @Query("pageToken") pageToken: String?, @Query("dataSourceFamily") family: String?): Response<ResponseBody>
    @GET("v4/users/me/dataTypes/{type}/dataPoints:reconcile")
    suspend fun reconcile(@Path("type") type: String, @Query("filter") filter: String?, @Query("pageSize") pageSize: Int?,
                          @Query("pageToken") pageToken: String?, @Query("dataSourceFamily") family: String?): Response<ResponseBody>
    @POST("v4/users/me/dataTypes/{type}/dataPoints:rollUp")
    suspend fun rollUp(@Path("type") type: String, @Body body: RollUpRequest): Response<ResponseBody>
    @POST("v4/users/me/dataTypes/{type}/dataPoints:dailyRollUp")
    suspend fun dailyRollUp(@Path("type") type: String, @Body body: DailyRollUpRequest): Response<ResponseBody>
    @GET("v4/users/me/dataTypes/{type}/dataPoints/{id}")
    suspend fun get(@Path("type") type: String, @Path("id") id: String): Response<ResponseBody>
    @GET("v4/users/me/identity") suspend fun identity(): Response<ResponseBody>
    @GET("v4/users/me/settings") suspend fun settings(): Response<ResponseBody>
    @GET("v4/users/me/pairedDevices")
    suspend fun pairedDevices(@Query("pageSize") pageSize: Int?, @Query("pageToken") pageToken: String?): Response<ResponseBody>
}
```

- **JSON configuration:** `Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }`.
  - All enums are `String` fields, mapped to Kotlin enums with an `UNKNOWN` default.
  - All int64 fields use the lenient string-or-number serializer from 4.1 and expose `Long?`.
  - Durations are parsed from `"<n>s"`.
  - Each value-union key is a nullable field. A point with zero or several non-null union fields is dropped and counted as "skipped" (8.6).
- **OkHttp:**
  - `AuthInterceptor` adds `Authorization: Bearer <token>` and `Accept: application/json`.
  - An OkHttp `Authenticator` handles 401: one retry after `TokenProvider.invalidate(token)` and `get()`, and no retry when `response.priorResponse` already had a 401.
  - A token-bucket `RateLimitInterceptor` allows 4 requests/s and 200/min.
  - Timeouts: connect 15 s, read 30 s.
- **429 and 5xx retries** live in the sync engine as coroutine `delay`s, not in an interceptor. They can then be cancelled with the Worker and tested with virtual time.
- **Base URLs** come from Hilt: `@Named("googleHealthBaseUrl") HttpUrl` (production `https://health.googleapis.com/`). Fallback B also gets `@Named("googleOAuthTokenUrl")` and `@Named("googleOAuthRevokeUrl")`. Tests point all of them at MockWebServer.
- **`TokenProvider`:**

```kotlin
interface TokenProvider {
    suspend fun accessToken(): String              // may throw NeedsUserInteraction
    suspend fun invalidate(token: String)          // A: AuthorizationClient.clearToken(...)
    suspend fun grantedScopes(): Set<String>
    suspend fun revoke()                           // A: revokeAccess(...)   B: POST {revokeUrl} token=...
}
```

### 7.4 Sync algorithm (pseudo-code)

```
syncSource(source):
  for type in source.grantedTypes() ordered by priority (sleep, steps, heart-rate, exercise, daily types, body):
     st = syncStateDao.get(source, type)
     if now < st.nextAllowedAt: continue
     window = if st.syncedThrough == null: [now - 14d, now)                      // hot load (GH-DT "7-14 days")
              else: [st.syncedThrough - overlap(type), now)                     // 48h / 7d (5.6)
     outcome = fetchAll(source, type, window)       // filter strategy 5.1, pagination guard 5.2, retries 7.6
     when outcome:
        Success(items, complete=true) -> db.withTransaction {
             if type.identifiable: upsertByUpstreamId(items); tombstoneMissing(type, window, items)
             else: deleteWindow(source, type, window); insertAll(items)
             syncStateDao.advance(source, type, syncedThrough = window.end, failures = 0)
          }
        Failure(err) -> syncStateDao.recordFailure(source, type, err, nextAllowedAt = backoff(err, st.failures))
  dailyTotals(source, last 7 civil days)            // GH: :dailyRollUp ; HC: aggregateGroupByPeriod(Period.ofDays(1))
  if now - lastDeepResync > 7d: enqueue deep re-sync [now - 30d, now)
  if backfill horizon not reached: enqueue one cold-load chunk of 30 days (older first: backfilledFrom - 30d)
```

- **WorkManager** (details are in `02-background-execution.md`):
  - one unique periodic work per source, every 1 h. Google's HC sample uses 1 h, and Fitbit syncs at most every 15 min [HC-READ][GH-DT];
  - `NetworkType.CONNECTED` for the API source only;
  - exponential backoff;
  - an expedited one-off sync when the app opens.

### 7.5 Storage and idempotency keys (Room)

| Table | Primary key | Notes |
|---|---|---|
| `interval_obs` (steps, distance, floors, active energy) | `(source, type, startEpochMs, endEpochMs, sourceKey)` | `value` (Long or Double); offsets; provenance columns |
| `sample_obs` (heart rate, weight, body fat, height) | `(source, type, timeEpochMs, sourceKey)` | `upstreamId` nullable (unique when present: weight, body-fat, height) |
| `daily_summary` | `(source, type, localDate)` | From `:dailyRollUp`, daily-* types, or HC aggregates |
| `sleep_session` / `sleep_stage` | `(source, upstreamId)` / `(sessionRowId, startEpochMs)` | `upstreamId` = API `name`, or HC `metadata.id`. Keep `processed`, `mainSleep`, `nap`, `updateTime` |
| `exercise_session` | `(source, upstreamId)` | `exerciseType` stored as a string |
| `paired_device` | `(source, name)` | API only |
| `sync_state` | `(source, type)` | `syncedThrough`, `backfilledFrom`, `hcChangesToken`, `lastDeepResyncAt`, `failures`, `nextAllowedAt`, `lastError` |
| `connection_state` | `(source)` | Status enum, account email, `healthUserId`, granted scopes, last success. **No tokens** with flow A |

- **`sourceKey`** = a stable hash of `(platform, deviceName, formFactor, recordingMethod, appPackage)` for the API, or of `(dataOrigin.packageName, device.manufacturer, device.model)` for HC. Non-identifiable API points have no id, and `list` returns overlapping rows from different sources (5.3). A key without the source would merge distinct readings.
- **Replace-window semantics** (delete then insert in one transaction) make re-fetching idempotent. They also apply upstream corrections and deletions without needing a change feed.

### 7.6 Error policy (maps 5.8 and 6.4 to behavior)

| Condition | Classified as | In-run action | Persisted state / user signal |
|---|---|---|---|
| 401 (first) | auth refresh | `invalidate` + new token, retry once | none |
| 401 (second), `invalid_grant`, `NeedsUserInteraction` | `NeedsReauth` | Stop the source | "Reconnect Google Health" notification and UI |
| 403 with `ACCESS_TOKEN_SCOPE_INSUFFICIENT`, `MISSING_OAUTH_SCOPE` or `error="insufficient_scope"` | `ScopeMissing(type)` | Skip the type | Type shown as "not shared"; offer re-consent |
| 403 whose message mentions UberMint/GaiaMint | `LegacyFitbitAccount` | Stop the source | Link to Google's account-migration help |
| other 403 | `Unsupported(type)` | Skip the type | Log |
| 400 `ACCOUNT_NOT_LINKED` | `AccountNotLinked(redirect_uri)` | Stop the source | Google's text plus the signup link (2.5) |
| 412 | `ProfileNotReady` | Stop the source | "Open the Google Health app to complete setup" |
| 400 filter reasons | none at first | Switch to the fallback filter once (5.1) | If it still fails: `Unsupported(type)` for this run |
| other 400, HTML 404 | `Unsupported(type)` | Skip the type; never retry | Log the method, path and reason (no PII) |
| 429 | `RateLimited` | Honor `Retry-After` (delta-seconds or HTTP-date) up to 60 s; else backoff 2 s, 4 s (jitter), at most 2 in-run retries | `nextAllowedAt = now + max(Retry-After, 2^failures min)`; `Result.retry()` |
| 500, 502, 503, 504, `IOException`, timeout | `Transient` | Up to 3 in-run retries (1 s, 2 s, 4 s plus jitter; honor `Retry-After`) | `Result.retry()`; keep `syncedThrough` |
| 2xx with a malformed or non-JSON body | `Transient` | Retry once, then fail the type for this run | Never commit a partial window |
| HC `SecurityException` | `ScopeMissing(type)` | Skip the type | Re-request permissions |
| HC `IllegalStateException` | `RateLimited` (quota) or `Transient` | Back off [HC-READ] | `nextAllowedAt` |
| HC `changesTokenExpired` | none | New token plus a 30-day re-read (6.5) | none |
| Unknown JSON fields or enum values | not an error | Ignore, or map to `UNKNOWN` | Count in debug metrics |

### 7.7 Avoiding double counting between the two sources

**How the data loops** [GHAPP-HC][DISC]:
- The Google Health app writes Fitbit and Pixel Watch data into Health Connect.
- It also reads other apps' Health Connect data into the Google Health cloud. The API then serves those points with `dataSource.platform = "HEALTH_CONNECT"`, and the `google-sources` family "Includes ... Health Connect".
- Google Health also connects to "Health Connect (Android phones) and Apple Health (iPhones)" and to "other direct connections with third-party apps", and "Data from third-party apps or devices may not be available for all Google Health metrics" [GHAPP-3P].

**Policy** (design):
1. **One canonical source per metric.** The user chooses it per metric. The default when both sources are connected: Google Health API for Fitbit-device metrics, Health Connect for everything else.
2. **API side**, when Health Connect is also connected:
   - For `list` results, drop points whose `dataSource.platform == "HEALTH_CONNECT"`. This works for every type, including sleep, where `dataSourceFamily` is not allowed.
   - For daily totals from `:dailyRollUp`, which carry no provenance, pass `dataSourceFamily=users/me/dataSourceFamilies/google-wearables`. That excludes HC imports, but also manual logs, which is acceptable for steps, distance, floors and calories.
3. **HC side**, when the API source is connected: skip records whose `dataOrigin.packageName` is the Google Health app. The package is expected to be `com.fitbit.FitbitMobile` (UNVERIFIED, 6.2).
4. **Never sum across sources.** Show one daily total per metric, from the canonical source.

### 7.8 What a developer without Google Health onboarding can live-test today

| # | What | Needs | Verifies |
|---|---|---|---|
| 1 | **Health Connect source, end to end** | Android 14+ phone (or 9-13 with the HC APK), the Google Health app with a Fitbit or Pixel Watch and HC sync on (6.2). Alternatively, a debug-only build that writes test records with `WRITE_*` permissions | Permission UI and rationale; 30-day window and history permission; background worker; changes tokens and expiry handling; `dataOrigin` and device metadata; sleep stages; exercise sessions |
| 2 | **Google Health REST client** | MockWebServer plus the section 8 contract, in CI | Parsing, pagination, filters, the error policy (7.6), idempotent re-sync, time zones |
| 3 | **OAuth plumbing (flow A)** | A Cloud project, an Android OAuth client (debug SHA-1), consent screen in Testing, and a **non-health** scope such as `https://www.googleapis.com/auth/userinfo.email` | Consent UI; silent re-authorize; `clearToken`; `revokeAccess`; calling `authorize()` from a Worker (the UNVERIFIED item in 2.4) |
| 3b | Optional experiment | Same project, requesting a `googlehealth.*` scope | Captures the exact failure for a non-onboarded project. Outcome UNVERIFIED; add it to the fake server |
| 4 | **Unauthenticated probes** | None (no token) | TLS and routing; parsing of the real 401 JSON and 404 HTML (8.5 uses the captured bodies) |
| 5 | **Discovery drift guard** | None | Fetch `https://health.googleapis.com/$discovery/rest?version=v4`, compare `revision` and the schemas and fields the DTOs use against a checked-in snapshot, and warn on change |

**Blocked until onboarding:** every authenticated data call; the client-type question (2.3); real 403, 412 and 429 bodies; filter behavior (5.1); dailyRollUp end-date semantics (4.5); real latency.

### 7.9 Spike checklist for the day onboarding opens (in order)

1. Android client, `AuthorizationClient`, `googlehealth.activity_and_fitness.readonly`, then `GET v4/users/me/identity`. Does it return 200? This settles 2.3.
2. If step 1 fails: a Web client with Custom Tab and PKCE. The client secret then has to live on a broker, so that needs a product decision first (2.4 B).
3. Capture real bodies, and remove PII before committing them as fixtures:
   - 403 for a missing scope (request steps with only `sleep.readonly`);
   - 400 `ACCOUNT_NOT_LINKED` (a test account with no Google Health profile);
   - 412;
   - an expired-token 401;
   - one 429 if it can be triggered safely within the documented limits.
4. Filters: sleep `end_time`; interval `start_time` with two bounds; sample `physical_time` (5.1).
5. dailyRollUp end date: inclusive or exclusive (4.5). True-zero record shape (5.4). Whether 429 carries `Retry-After`.
6. Compare `:dailyRollUp` totals with the Google Health app UI for 3 days.
7. Record the `platform` and `application.packageName` values on Health Connect-imported points.

### 7.10 Dependencies relevant to this connector

| Artifact | Version | Source |
|---|---|---|
| `androidx.health.connect:connect-client` | **1.1.0** (stable; 1.2.0-alpha06 only if an alpha API is needed) | [HC-REL] |
| `androidx.health.connect:connect-testing` | 1.0.0-alpha04 (tests only) | [HC-REL] |
| `com.google.android.gms:play-services-auth` | 22.0.0 (`AuthorizationClient`; Google Sign-In APIs removed) | [GMS-REL][AND-AUTHZ] |
| Retrofit, OkHttp, MockWebServer, kotlinx.serialization | not researched here; see `07-architecture-and-versions.md` | n/a |
| `androidx.browser` (Custom Tabs) | only if fallback B is adopted; not researched | n/a |

**Do not add:**
- the generated Google API Java client for Health (server-oriented; [GH-LIBS] lists no Android client);
- AppAuth with custom schemes ([OAUTH-NATIVE]);
- any Fitbit Web API library (owner decision).

### 7.11 Policy and compliance notes (for the product owner)

- **Google Health API Developer and User Data Policy** [GH-POLICY]:
  - **Transfers:** "Only transfer user data to third parties: To provide or improve your appropriate use case or features that are clear from the requesting application's user interface and only with the user's consent; If necessary for security purposes ...; To comply with applicable laws ...; or, As part of a merger ...".
  - **Human reading:** "Do not allow humans to read user data, unless ..." the user explicitly consents, for security, to comply with law, or the data is aggregated and anonymized.
  - **Security assessment:** "we will require that your application or service follow the Cloud Application Security Assessment (CASA) ... if your product transfers data off the user's own device".
  - **AI and LLMs:** no mention. **Sending Google Health API data to OpenAI for Agentle's AI features is a transfer off the device. It needs explicit, feature-specific user consent and probably triggers CASA.** Get a legal review.
- **Google API Services User Data Policy, Limited Use:** "Limit your use of data to providing or improving user-facing features that are prominent in the requesting application's user interface". It also prohibits transfers to ad platforms and data brokers [GAPI-POLICY].
- **Google Play health policy / Health Connect:** no sharing "without explicit, informed user consent". The Data safety form must disclose the sharing [PLAY-HEALTH][HC-PUB].
- **In-app disclosure** must be "within the application itself" [GH-VER]. Show it before the OAuth consent and before the Health Connect permission request.
- **Local-only storage:** health data stays in Room. With flow A, no OAuth secrets or refresh tokens are persisted by Agentle.

## 8. Fake-server contract (for MockWebServer tests)

This section specifies the fake Google Health API that `GoogleHealthApiSource` is built and tested against until Google onboards the project (sections 1 and 7.8). It is a design artifact, and every value in it is synthetic.

Every fixture carries one of these labels:

| Label | Meaning |
|---|---|
| **VERBATIM** | Byte-for-byte copy of a live response [PROBE] or of a body Google documents [GH-MIG] |
| **DOC** | The documented message or values, placed in the standard error envelope [AIP-193] |
| **SHAPE** | Synthetic values in a structure that follows [DISC] and the live 3P fixtures [3P-DGH `tests/fixtures`]. All success fixtures below were machine-checked against the discovery schemas of revision 20261001 (property names, int64-as-string, RFC 3339 times, durations, enum values), and the sleep and exercise totals are internally consistent |
| **MODELED** | Undocumented. The fake's best guess, to be replaced by captured bodies after the spike (7.9 step 3) |

Test rule: assert on the HTTP status, `error.status`, `ErrorInfo.reason` and `metadata`, and on parsed data. **Never assert on the `message` text of a DOC or MODELED body.**

### 8.1 Conventions

- **Base URL.** `mockWebServer.url("/")`, injected as `@Named("googleHealthBaseUrl")` (7.3). Paths are the production paths (`/v4/...`).
- **Clock.** Inject a fixed `Clock` at `2026-10-01T12:00:00Z`.
- **Page sizes.** The client reads its page sizes from an injectable config. Production uses 10000 for interval and sample types and 25 for sleep and exercise (5.2). Tests use small sizes (for example 3) so that small fixtures still span several pages.
- **Synthetic account:**

  | Field | Value |
  |---|---|
  | `healthUserId` | `1234567890` (the example id used in [DISC] resource names) |
  | `legacyUserId` | `A1B2C3` |
  | Account time zone | `America/New_York`, offset `-14400s` (EDT) |
  | Fixture day | civil date 2026-09-30; 08:00 local is 12:00Z |
  | Paired devices | a Charge 6 tracker and an Aria Air scale |

- **`{user}` path segment.** The fake accepts both `me` and `1234567890`; both are valid per [DISC]. Returned `name` fields use the numeric id, except the identity resource, which is `users/me/identity` [DISC].
- **Tokens.** The fake reads `Authorization: Bearer <token>`:

  | Token | Behavior |
  |---|---|
  | `fake-valid` | Grants the v1 scopes: `activity_and_fitness`, `health_metrics_and_measurements`, `sleep`, `settings`, `profile` (all `.readonly`, section 2.2). It does not grant `location.readonly` |
  | `fake-scope-<suffix>[,<suffix>...]` | Grants only the listed scopes, for example `fake-scope-sleep.readonly,settings.readonly`. Requests for other types get E403-SCOPE-A |
  | `fake-expired`, `fake-revoked`, or any unknown token | E401-INVALID |
  | `fake-not-linked` | E400-ACCOUNT-NOT-LINKED on every routed call |
  | `fake-no-profile` | E412 on every routed call |
  | `fake-legacy` | E403-LEGACY-A on every routed call |
  | No `Authorization` header | E401-MISSING, or E401-APIKEY when a `key` query parameter is present |

- **Scope gate per data type.** This follows [GH-DT]. 3P-DGH maps heart-rate to a different scope (see 4.8).

  | Scope (`googlehealth.*.readonly`) | Types and resources |
  |---|---|
  | `activity_and_fitness` | steps, distance, floors, active-energy-burned, total-calories, exercise |
  | `health_metrics_and_measurements` | heart-rate, daily-resting-heart-rate, daily-heart-rate-variability, daily-oxygen-saturation, weight, body-fat, height |
  | `sleep` | sleep |
  | `settings` | settings, pairedDevices |
  | `profile` | profile |
  | any one read scope | identity [DISC] |

- **Order of checks** in the dispatcher:
  1. **Route** on method and path template. No match returns E404-HTML.
  2. **Auth** (401).
  3. **Scope** (403).
  4. **Account state** (400 `ACCOUNT_NOT_LINKED`, 412, 403 legacy).
  5. **Request validation** (400; rules in 8.3).
  6. **Data.**

  Evidence for the first two steps: unauthenticated `GET /v4/nonexistent`, `GET /v4/users/me/devices` and `POST .../steps/dataPoints:reconcile` returned the HTML 404, while `GET .../dataTypes/not-a-type/dataPoints` returned 401 [PROBE]. So routing runs before auth, and data-type validation runs after it. The order of steps 3 to 5 is MODELED.
- **Response headers.** JSON responses carry `Content-Type: application/json; charset=UTF-8` and HTML responses carry `Content-Type: text/html; charset=UTF-8`, as live [PROBE].
- **Two modes.**
  - **Replay mode** maps an exact request (method, path, decoded and sorted query, or canonical JSON body) to a canned body from 8.4 to 8.6. Use it for decoder and error-policy tests.
  - **Model mode** holds an in-memory dataset per type and applies the 8.3 rules: filtering, ordering, paging, rollups, `dataSourceFamily` and the per-token rate limit. Datasets are versioned (`V1`, `V2`, ...) so a test can change upstream data between sync runs (8.6 R7). Use it for sync-engine tests.
- **Fault injection.** `inject(times, fixtureId, retryAfter?, delay?, matcher)` makes the next N matching requests return an error fixture or a delayed response. The injection is applied after routing, so it can simulate any status on any routed request.
- **Request journal.** The fake records every request (method, path, decoded query, body, headers). Tests use it for the hygiene checks H1 to H11 in 8.7.
- **Where the fixture files go.** Put one file per fixture id, lower-cased (for example `googlehealth/f-steps-p1.json`), in the shared test-fixtures module that `08-testing-strategy.md` section 5 defines. Until real captures replace the MODELED bodies, this section is the source of truth for their content.

### 8.2 Endpoints

| # | Request | Query or body | Scope gate | Fake rules (8.3) | Fixtures |
|---|---|---|---|---|---|
| 1 | `GET /v4/users/me/identity` | none | any read scope | none | F-IDENTITY |
| 2 | `GET /v4/users/{u}/settings` | none | settings | none | F-SETTINGS |
| 3 | `GET /v4/users/{u}/profile` | none | profile | none | F-PROFILE |
| 4 | `GET /v4/users/{u}/pairedDevices` | `pageSize` (default 5; values above 100 become 100), `pageToken` | settings | V3, V4 | F-DEVICES-P1, then F-DEVICES-P2 (with `pageSize=1`) |
| 5 | `GET /v4/users/{u}/pairedDevices/{d}` | none | settings | unknown id: E404-JSON | the matching device object from 4 |
| 6 | `GET /v4/users/{u}/dataTypes/{type}/dataPoints` (list) | `filter`, `pageSize`, `pageToken`, `dataSourceFamily` | per type | V1 to V5 | F-STEPS-P1/P2, F-DISTANCE, F-AEB, F-HR, F-RHR, F-SLEEP, F-EXERCISE, F-WEIGHT, F-BODYFAT |
| 7 | `GET .../dataPoints:reconcile` | same as list | per type | V1 to V5 (floors allowed) | F-STEPS-RECONCILE, F-FLOORS-RECONCILE |
| 8 | `POST .../dataPoints:rollUp` | `{range:{startTime,endTime}, windowSize, pageSize?, pageToken?, dataSourceFamily?}` | per type | V5, V6 | F-STEPS-ROLLUP, F-HR-ROLLUP |
| 9 | `POST .../dataPoints:dailyRollUp` | `{range:{start:{date},end:{date}}, windowSizeDays?, pageSize?, pageToken?, dataSourceFamily?}` | per type | V5, V7 | F-DAILY-STEPS, F-DAILY-TOTALCAL |
| 10 | `GET .../dataPoints/{id}` | none | per type | Identifiable types only (sleep, exercise, weight, body-fat, height); other types get E400-INVALID-ARGUMENT. Unknown id: E404-JSON | the matching data point from the list fixtures |
| 11 | `GET .../dataPoints/{id}:exportExerciseTcx` | `alt=media` | activity_and_fitness **and** location | `fake-valid` lacks `location`, so the result is E403-SCOPE-A | none; not used in v1 |
| 12 | Anything else: unknown paths, `GET /v4/users/me/devices`, `POST ...:reconcile`, and the write methods (`POST .../dataPoints`, `PATCH`, `:batchDelete`), which are deliberately left unrouted | | | | E404-HTML. Hygiene check H11 also fails any test that calls a write method |
| 13 | `POST {tokenUrl}` (fallback flow B only) | form: `grant_type=authorization_code`, `code`, `code_verifier`, `redirect_uri`, `client_id`; or `grant_type=refresh_token`, `refresh_token`, `client_id` | none | V8 | F-TOKEN, F-TOKEN-PARTIAL, F-TOKEN-REFRESH, F-TOKEN-REFRESH-ROTATED, E-OAUTH-INVALID-GRANT |
| 14 | `POST {revokeUrl}` (fallback flow B only) | form: `token=...` | none | none | 200 with an empty body; 400 E-OAUTH-REVOKE |

If flow B is adopted with a token broker, the app calls the broker instead of rows 13 and 14. The broker's contract should mirror these bodies.

### 8.3 Validation rules the fake enforces

- **V1. Data type.**
  - The path type must be a known kebab-case id (3.2). Otherwise: E400-INVALID-ARGUMENT.
  - `list` on `floors` or `total-calories` also returns E400-INVALID-ARGUMENT. [GH-TRBL] gives "Request contains an invalid argument" for "Unsupported data type ID referenced; verify the endpoint supports that data type".
- **V2. Filter.**
  - Grammar: `restriction ( " AND " restriction )*`, where a restriction is `<snake_type>.<member> <op> "<literal>"` [DISC][GH-FILT].
  - Checks, in this order. The first failure returns E400-FILTER-A (or E400-FILTER-B when the knob `filterErrorStyle = REASON_ONLY` is set) with the given `detailedReasons` value:

    | # | Condition | `detailedReasons` |
    |---|---|---|
    | 1 | Contains ` OR `. Exception: sleep end-time members when `sleepFilter = DOCUMENTED` | `INVALID_DATA_POINT_FILTER_EXPRESSION_STRUCTURE` |
    | 2 | A restriction does not parse, or its literal is malformed. Physical literals are RFC 3339; civil literals are `YYYY-MM-DD[THH:mm:ss]`; daily literals are `YYYY-MM-DD` | `INVALID_DATA_POINT_FILTER` |
    | 3 | Operator other than `>=` or `<` | `INVALID_DATA_POINT_FILTER_RESTRICTION_COMPARATOR` |
    | 4 | The type name contains `-` | `INVALID_DATA_POINT_FILTER` |
    | 5 | The type name is not the snake_case of the path type | `INVALID_DATA_POINT_FILTER_COLLECTION_MISMATCH` |
    | 6 | The member is not allowed for the type's category (5.1 table), or it is listed in the `rejectFilterMembers` knob, or the type is sleep and `sleepFilter = REJECT_ALL` | `INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER` (3P-observed reason) |
    | 7 | Physical and civil members are mixed | `INVALID_DATA_POINT_FILTER_MIXED_TIME_RESTRICTIONS` |
    | 8 | Both bounds are present and upper <= lower | `INVALID_TIME_RANGE` |

  - **Matching.**
    - Physical members compare instants.
    - Civil members compare the point's civil time, computed from its UTC time plus its own offset, against the literal. A date-only literal means midnight.
    - `date` compares `LocalDate`.
    - Sleep end-time members use `interval.endTime`.
    - A point matches when every restriction holds.
- **V3. Page size.** An absent or `0` page size means the default: 1440, or 25 for sleep and exercise; 5 for pairedDevices. Values above the maximum are clamped to 10000, 25 or 100, and clamping is not an error [DISC]. A negative value returns E400-INVALID-ARGUMENT (MODELED).
- **V4. Page token.** It must be a token the fake issued for the same method, path and other parameters (for `rollUp`, the same body apart from `pageToken`) [DISC]. Otherwise: E400-PAGE-TOKEN (MODELED). Ordering:
  - `list` returns points ordered by start time (or sample time, or date), newest first [DISC].
  - Reconcile and the rollups return ascending order in the fake. Their real ordering is undocumented, so the client must not depend on it.
- **V5. `dataSourceFamily`.**
  - It must match `users/me/dataSourceFamilies/(all-sources|google-wearables|google-sources|self-sources)`. Otherwise: E400-INVALID-ARGUMENT [GH-FILT].
  - Setting it on the sleep `list` also returns E400-INVALID-ARGUMENT [DISC].
  - `google-wearables` drops points whose `recordingMethod` is `MANUAL` or whose `platform` is not `FITBIT` [DISC].
  - `self-sources` returns `{}`, because Agentle never writes. An empty match is an empty list, not an error [DISC].
- **V6. `rollUp` body.**
  - `range.startTime` and `range.endTime` are required, both RFC 3339, with end > start. Otherwise: E400-INVALID-ARGUMENT (MODELED).
  - `windowSize` must be a duration of at least `1s`.
  - The range may be at most 14 days for `heart-rate`, `total-calories`, `active-minutes` and `calories-in-heart-rate-zone`, and at most 90 days for every other type. Otherwise: E400-INVALID-ARGUMENT (MODELED message) [DISC].
  - Windows start at `range.startTime`, and the last one is truncated at the range end [DISC]. The response omits `nextPageToken` on the last page.
- **V7. `dailyRollUp` body.**
  - The start and end dates are required. `windowSizeDays` must be at least 1 and defaults to 1.
  - The end date is **inclusive** by default (4.5). The knob `dailyRollUpEnd = EXCLUSIVE` switches to the closed-open reading in [DISC], so both readings can be tested.
  - The same maximum ranges as V6 apply, counted in days.
  - A raw body containing a number with a leading zero (`"month": 09`) returns E400-BAD-JSON [GH-TRBL].
  - The response never contains `nextPageToken` [DISC].
- **V8. OAuth (flow B only).**
  - `code_verifier` must hash with S256 to the `code_challenge` the test registered. Otherwise: E-OAUTH-INVALID-GRANT.
  - A refresh with `1//fake-refresh-expired` also returns E-OAUTH-INVALID-GRANT.
- **V9. Rate limit (model mode).** More than 300 requests from one token in a rolling 60 s returns E429 without `Retry-After` [GH-RATE]. The client's own bucket of 200 per minute (5.7) should never trigger it.

### 8.4 Success fixtures (SHAPE unless stated)

**F-IDENTITY.** `GET /v4/users/me/identity`. Shape per [DISC]; 3P-observed live keys are exactly these three.
```json
{"name": "users/me/identity", "healthUserId": "1234567890", "legacyUserId": "A1B2C3"}
```

**F-SETTINGS.** `GET /v4/users/me/settings`. The `languageLocale` format and the returned `name` form are MODELED.
```json
{
  "name": "users/1234567890/settings",
  "timeZone": "America/New_York",
  "utcOffset": "-14400s",
  "distanceUnit": "DISTANCE_UNIT_MILES",
  "weightUnit": "WEIGHT_UNIT_POUNDS",
  "heightUnit": "HEIGHT_UNIT_INCHES",
  "temperatureUnit": "TEMPERATURE_UNIT_FAHRENHEIT",
  "waterUnit": "WATER_UNIT_FL_OZ",
  "glucoseUnit": "GLUCOSE_UNIT_MG_DL",
  "swimUnit": "SWIM_UNIT_YARDS",
  "languageLocale": "en_US",
  "autoStrideEnabled": true,
  "strideLengthWalkingType": "STRIDE_LENGTH_TYPE_AUTO",
  "strideLengthRunningType": "STRIDE_LENGTH_TYPE_AUTO",
  "foodLanguageCode": "en-US"
}
```

**F-PROFILE.** `GET /v4/users/me/profile`. 3P-observed: the live payload carries `age` and no other health field.
```json
{"name": "users/1234567890/profile", "age": 34}
```

**F-DEVICES-P1** (`GET /v4/users/me/pairedDevices?pageSize=1`) and **F-DEVICES-P2** (`...?pageSize=1&pageToken=pd-2`). The last page omits `nextPageToken` [DISC]. The scale has no `features` key, so the decoder must accept an absent list (MODELED). The `macAddress` format is MODELED.
```json
{
  "pairedDevices": [
    {
      "name": "users/1234567890/pairedDevices/2718281828",
      "deviceVersion": "Charge 6",
      "deviceType": "TRACKER",
      "batteryStatus": "High",
      "batteryLevel": 82,
      "lastSyncTime": "2026-10-01T11:58:03Z",
      "macAddress": "A0:B1:C2:D3:E4:F5",
      "features": ["STEPS", "HEART_RATE", "SLEEP", "SMART_SLEEP", "SPO2", "ACTIVE_ZONE_MINUTES", "CONNECTED_GPS"]
    }
  ],
  "nextPageToken": "pd-2"
}
```
```json
{
  "pairedDevices": [
    {
      "name": "users/1234567890/pairedDevices/3141592653",
      "deviceVersion": "Aria Air",
      "deviceType": "SCALE",
      "batteryStatus": "Low",
      "batteryLevel": 12,
      "lastSyncTime": "2026-09-28T07:15:40Z",
      "macAddress": "A0:B1:C2:D3:E4:F6"
    }
  ]
}
```

**F-STEPS-P1** and **F-STEPS-P2** (list).
- Request 1: `GET /v4/users/me/dataTypes/steps/dataPoints?filter=steps.interval.start_time >= "2026-09-30T11:59:00Z" AND steps.interval.start_time < "2026-09-30T12:05:00Z"&pageSize=3`. On the wire the filter is URL-encoded.
- Request 2: the same parameters plus `&pageToken=pt-steps-2`.
- Contents:
  - Points are newest first.
  - One point comes from a phone through Health Connect (`platform: HEALTH_CONNECT`).
  - The last point is a true zero, with no `count` (5.4, UNVERIFIED shape).
  - The last page carries `"nextPageToken": ""`, as in [GH-CL].
```json
{
  "dataPoints": [
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "steps": {
        "interval": {
          "startTime": "2026-09-30T12:02:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:03:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 2}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 3}}
        },
        "count": "112"
      }
    },
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "steps": {
        "interval": {
          "startTime": "2026-09-30T12:01:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:02:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 2}}
        },
        "count": "98"
      }
    },
    {
      "dataSource": {
        "recordingMethod": "PASSIVELY_MEASURED",
        "device": {"formFactor": "PHONE", "manufacturer": "Google", "displayName": "Pixel 9"},
        "platform": "HEALTH_CONNECT",
        "application": {"packageName": "com.example.phonesteps"}
      },
      "steps": {
        "interval": {
          "startTime": "2026-09-30T12:00:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:05:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 5}}
        },
        "count": "240"
      }
    }
  ],
  "nextPageToken": "pt-steps-2"
}
```
```json
{
  "dataPoints": [
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "steps": {
        "interval": {
          "startTime": "2026-09-30T12:00:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:01:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1}}
        },
        "count": "87"
      }
    },
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "steps": {
        "interval": {
          "startTime": "2026-09-30T11:59:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:00:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 7, "minutes": 59}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8}}
        }
      }
    }
  ],
  "nextPageToken": ""
}
```

**F-STEPS-RECONCILE.** `GET .../steps/dataPoints:reconcile` with the same filter. Points have no `dataSource` and, for steps, no `dataPointName` [DISC]. The phone point has been deduplicated away.
```json
{
  "dataPoints": [
    {
      "steps": {
        "interval": {
          "startTime": "2026-09-30T12:02:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:03:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 2}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 3}}
        },
        "count": "112"
      }
    },
    {
      "steps": {
        "interval": {
          "startTime": "2026-09-30T12:01:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:02:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 2}}
        },
        "count": "98"
      }
    },
    {
      "steps": {
        "interval": {
          "startTime": "2026-09-30T12:00:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:01:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1}}
        },
        "count": "87"
      }
    },
    {
      "steps": {
        "interval": {
          "startTime": "2026-09-30T11:59:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:00:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 7, "minutes": 59}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8}}
        }
      }
    }
  ],
  "nextPageToken": ""
}
```

**F-STEPS-ROLLUP.** `POST .../steps/dataPoints:rollUp` with body `{"range":{"startTime":"2026-09-30T11:59:00Z","endTime":"2026-09-30T12:03:00Z"},"windowSize":"60s"}`. The first window is a true zero (`"0"`), not an absent window [GH-ZEROS]. There is no `nextPageToken`.
```json
{
  "rollupDataPoints": [
    {"startTime": "2026-09-30T11:59:00Z", "endTime": "2026-09-30T12:00:00Z", "steps": {"countSum": "0"}},
    {"startTime": "2026-09-30T12:00:00Z", "endTime": "2026-09-30T12:01:00Z", "steps": {"countSum": "87"}},
    {"startTime": "2026-09-30T12:01:00Z", "endTime": "2026-09-30T12:02:00Z", "steps": {"countSum": "98"}},
    {"startTime": "2026-09-30T12:02:00Z", "endTime": "2026-09-30T12:03:00Z", "steps": {"countSum": "112"}}
  ]
}
```

**F-DAILY-STEPS.** `POST .../steps/dataPoints:dailyRollUp` with body `{"range":{"start":{"date":{"year":2026,"month":9,"day":29}},"end":{"date":{"year":2026,"month":9,"day":30}}},"windowSizeDays":1}`. The inclusive end gives two days. Midnight is `"time": {}`, as 3P observed.
```json
{
  "rollupDataPoints": [
    {
      "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 29}, "time": {}},
      "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {}},
      "steps": {"countSum": "10412"}
    },
    {
      "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {}},
      "civilEndTime": {"date": {"year": 2026, "month": 10, "day": 1}, "time": {}},
      "steps": {"countSum": "8530"}
    }
  ]
}
```

**F-DAILY-TOTALCAL.** The same request on `total-calories`.
```json
{
  "rollupDataPoints": [
    {
      "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 29}, "time": {}},
      "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {}},
      "totalCalories": {"kcalSum": 2315.5}
    },
    {
      "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {}},
      "civilEndTime": {"date": {"year": 2026, "month": 10, "day": 1}, "time": {}},
      "totalCalories": {"kcalSum": 2198.25}
    }
  ]
}
```

**F-DISTANCE** (list on `distance`, filter on `distance.interval.start_time`). **F-AEB** (list on `active-energy-burned`). F-AEB keeps the float noise seen live (`0.7933980000000002` in 3P).
```json
{
  "dataPoints": [
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "distance": {
        "interval": {
          "startTime": "2026-09-30T12:02:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:03:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 2}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 3}}
        },
        "millimeters": "84000"
      }
    },
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "distance": {
        "interval": {
          "startTime": "2026-09-30T12:01:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:02:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 2}}
        },
        "millimeters": "73500"
      }
    }
  ],
  "nextPageToken": ""
}
```
```json
{
  "dataPoints": [
    {
      "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "activeEnergyBurned": {
        "interval": {
          "startTime": "2026-09-30T12:02:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:03:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 2}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 3}}
        },
        "kcal": 4.25
      }
    },
    {
      "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "activeEnergyBurned": {
        "interval": {
          "startTime": "2026-09-30T12:01:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T12:02:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 2}}
        },
        "kcal": 3.6800000000000006
      }
    }
  ],
  "nextPageToken": ""
}
```

**F-FLOORS-RECONCILE.** `GET .../floors/dataPoints:reconcile?filter=floors.interval.start_time >= "2026-09-30T00:00:00Z" AND floors.interval.start_time < "2026-10-01T00:00:00Z"`. Floors has no `list` (V1).
```json
{
  "dataPoints": [
    {
      "floors": {
        "interval": {
          "startTime": "2026-09-30T14:31:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T14:32:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 10, "minutes": 31}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 10, "minutes": 32}}
        },
        "count": "2"
      }
    },
    {
      "floors": {
        "interval": {
          "startTime": "2026-09-30T14:30:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T14:31:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 10, "minutes": 30}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 10, "minutes": 31}}
        },
        "count": "1"
      }
    }
  ],
  "nextPageToken": ""
}
```

**F-HR.** `GET .../heart-rate/dataPoints?filter=heart_rate.sample_time.physical_time >= "2026-09-30T12:01:00Z" AND heart_rate.sample_time.physical_time < "2026-09-30T12:02:00Z"`. Only one sample carries the optional `metadata`.
```json
{
  "dataPoints": [
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "heartRate": {
        "sampleTime": {
          "physicalTime": "2026-09-30T12:01:30Z",
          "utcOffset": "-14400s",
          "civilTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1, "seconds": 30}}
        },
        "beatsPerMinute": "74"
      }
    },
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "heartRate": {
        "sampleTime": {
          "physicalTime": "2026-09-30T12:01:25Z",
          "utcOffset": "-14400s",
          "civilTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1, "seconds": 25}}
        },
        "beatsPerMinute": "73",
        "metadata": {"motionContext": "SEDENTARY", "sensorLocation": "WRIST"}
      }
    },
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "heartRate": {
        "sampleTime": {
          "physicalTime": "2026-09-30T12:01:20Z",
          "utcOffset": "-14400s",
          "civilTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 8, "minutes": 1, "seconds": 20}}
        },
        "beatsPerMinute": "72"
      }
    }
  ],
  "nextPageToken": ""
}
```

**F-HR-ROLLUP.** `POST .../heart-rate/dataPoints:rollUp` with body `{"range":{"startTime":"2026-09-30T12:00:00Z","endTime":"2026-09-30T12:02:00Z"},"windowSize":"60s"}`. Window 2 is consistent with F-HR: min 72, average 73, max 74.
```json
{
  "rollupDataPoints": [
    {
      "startTime": "2026-09-30T12:00:00Z",
      "endTime": "2026-09-30T12:01:00Z",
      "heartRate": {"beatsPerMinuteMin": 68, "beatsPerMinuteAvg": 71.4, "beatsPerMinuteMax": 76}
    },
    {
      "startTime": "2026-09-30T12:01:00Z",
      "endTime": "2026-09-30T12:02:00Z",
      "heartRate": {"beatsPerMinuteMin": 72, "beatsPerMinuteAvg": 73, "beatsPerMinuteMax": 74}
    }
  ]
}
```

**F-RHR.** `GET .../daily-resting-heart-rate/dataPoints?filter=daily_resting_heart_rate.date >= "2026-09-29" AND daily_resting_heart_rate.date < "2026-10-01"`.
```json
{
  "dataPoints": [
    {
      "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "dailyRestingHeartRate": {
        "date": {"year": 2026, "month": 9, "day": 30},
        "beatsPerMinute": "54",
        "dailyRestingHeartRateMetadata": {"calculationMethod": "WITH_SLEEP"}
      }
    },
    {
      "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "dailyRestingHeartRate": {
        "date": {"year": 2026, "month": 9, "day": 29},
        "beatsPerMinute": "55",
        "dailyRestingHeartRateMetadata": {"calculationMethod": "ONLY_WITH_AWAKE_DATA"}
      }
    }
  ],
  "nextPageToken": ""
}
```

**F-SLEEP.** `GET .../sleep/dataPoints?pageSize=25&filter=sleep.interval.end_time >= "2026-09-29T12:00:00Z" AND sleep.interval.end_time < "2026-10-01T12:00:00Z"`. With `sleepFilter = REJECT_ALL`, the unfiltered request `?pageSize=25` returns the same body.
- The list holds a nap (newest) and the main sleep.
- Main sleep:
  - 9 contiguous stages that sum to the 492-minute period;
  - `stagesSummary` matches the stages;
  - one short awakening overlaps a LIGHT stage, which [DISC] allows.
- Nap:
  - `CLASSIC` type with `REJECTED_NAP`;
  - no `mainSleep` key. Absent booleans are false: the live 3P main-sleep record likewise omits `nap` and `manuallyEdited`.
- How `RESTLESS` minutes count in `minutesAwake` is undocumented, so the client stores the summary as given and never recomputes it.
```json
{
  "dataPoints": [
    {
      "name": "users/1234567890/dataTypes/sleep/dataPoints/7821966286120953002",
      "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "sleep": {
        "interval": {
          "startTime": "2026-09-30T18:10:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T18:35:00Z",
          "endUtcOffset": "-14400s"
        },
        "type": "CLASSIC",
        "stages": [
          {"startTime": "2026-09-30T18:10:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T18:20:00Z", "endUtcOffset": "-14400s", "type": "ASLEEP"},
          {"startTime": "2026-09-30T18:20:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T18:23:00Z", "endUtcOffset": "-14400s", "type": "RESTLESS"},
          {"startTime": "2026-09-30T18:23:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T18:35:00Z", "endUtcOffset": "-14400s", "type": "ASLEEP"}
        ],
        "metadata": {"stagesStatus": "REJECTED_NAP", "processed": true, "nap": true},
        "summary": {
          "minutesInSleepPeriod": "25",
          "minutesAfterWakeUp": "0",
          "minutesToFallAsleep": "0",
          "minutesAsleep": "22",
          "minutesAwake": "3",
          "stagesSummary": [{"type": "ASLEEP", "minutes": "22", "count": "2"}, {"type": "RESTLESS", "minutes": "3", "count": "1"}]
        },
        "createTime": "2026-09-30T18:47:12.310577Z",
        "updateTime": "2026-09-30T18:47:12.310577Z"
      }
    },
    {
      "name": "users/1234567890/dataTypes/sleep/dataPoints/7821966286120953001",
      "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "sleep": {
        "interval": {
          "startTime": "2026-09-30T02:40:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T10:52:00Z",
          "endUtcOffset": "-14400s"
        },
        "type": "STAGES",
        "stages": [
          {"startTime": "2026-09-30T02:40:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T02:45:00Z", "endUtcOffset": "-14400s", "type": "AWAKE"},
          {"startTime": "2026-09-30T02:45:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T03:30:00Z", "endUtcOffset": "-14400s", "type": "LIGHT"},
          {"startTime": "2026-09-30T03:30:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T04:20:00Z", "endUtcOffset": "-14400s", "type": "DEEP"},
          {"startTime": "2026-09-30T04:20:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T05:00:00Z", "endUtcOffset": "-14400s", "type": "REM"},
          {"startTime": "2026-09-30T05:00:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T07:30:00Z", "endUtcOffset": "-14400s", "type": "LIGHT"},
          {"startTime": "2026-09-30T07:30:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T08:00:00Z", "endUtcOffset": "-14400s", "type": "DEEP"},
          {"startTime": "2026-09-30T08:00:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T09:10:00Z", "endUtcOffset": "-14400s", "type": "REM"},
          {"startTime": "2026-09-30T09:10:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T10:40:00Z", "endUtcOffset": "-14400s", "type": "LIGHT"},
          {"startTime": "2026-09-30T10:40:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T10:52:00Z", "endUtcOffset": "-14400s", "type": "AWAKE"}
        ],
        "metadata": {"stagesStatus": "SUCCEEDED", "processed": true, "mainSleep": true},
        "summary": {
          "minutesInSleepPeriod": "492",
          "minutesAfterWakeUp": "0",
          "minutesToFallAsleep": "0",
          "minutesAsleep": "475",
          "minutesAwake": "17",
          "stagesSummary": [
            {"type": "AWAKE", "minutes": "17", "count": "2"},
            {"type": "LIGHT", "minutes": "285", "count": "3"},
            {"type": "DEEP", "minutes": "80", "count": "2"},
            {"type": "REM", "minutes": "110", "count": "2"}
          ]
        },
        "createTime": "2026-09-30T11:02:10.123456Z",
        "updateTime": "2026-09-30T11:35:44.654321Z",
        "shortAwakenings": [
          {"startTime": "2026-09-30T05:31:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T05:31:30Z", "endUtcOffset": "-14400s", "type": "AWAKE"}
        ]
      }
    }
  ],
  "nextPageToken": ""
}
```

**F-EXERCISE.** `GET .../exercise/dataPoints?pageSize=25&filter=exercise.interval.civil_start_time >= "2026-09-29T20:00:00" AND exercise.interval.civil_start_time < "2026-10-01T00:00:00"`.
- The run's figures are consistent:
  - `activeDuration` 2050 s = 36 min 10 s minus a 2-minute pause;
  - the heart-rate zones sum to 2050 s;
  - 2050 s / 5012 m is about 0.409 s/m.
- The walk was logged manually: no device, and only calories in `metricsSummary`.
- As live, the session `interval` blocks carry no civil times.
```json
{
  "dataPoints": [
    {
      "name": "users/1234567890/dataTypes/exercise/dataPoints/6661252888799707001",
      "dataSource": {
        "recordingMethod": "ACTIVELY_MEASURED",
        "device": {"formFactor": "FITNESS_BAND", "displayName": "Charge 6"},
        "platform": "FITBIT"
      },
      "exercise": {
        "interval": {
          "startTime": "2026-09-30T21:05:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T21:41:10Z",
          "endUtcOffset": "-14400s"
        },
        "exerciseType": "RUNNING",
        "metricsSummary": {
          "caloriesKcal": 402.5,
          "distanceMillimeters": 5012000,
          "steps": "5120",
          "averagePaceSecondsPerMeter": 0.409,
          "averageHeartRateBeatsPerMinute": "152",
          "activeZoneMinutes": "48",
          "elevationGainMillimeters": 23000,
          "heartRateZoneDurations": {"lightTime": "300s", "moderateTime": "900s", "vigorousTime": "780s", "peakTime": "70s"}
        },
        "exerciseMetadata": {"hasGps": true},
        "displayName": "Run",
        "activeDuration": "2050s",
        "exerciseEvents": [
          {"eventTime": "2026-09-30T21:05:00Z", "eventUtcOffset": "-14400s", "exerciseEventType": "START"},
          {"eventTime": "2026-09-30T21:20:00Z", "eventUtcOffset": "-14400s", "exerciseEventType": "PAUSE"},
          {"eventTime": "2026-09-30T21:22:00Z", "eventUtcOffset": "-14400s", "exerciseEventType": "RESUME"},
          {"eventTime": "2026-09-30T21:41:10Z", "eventUtcOffset": "-14400s", "exerciseEventType": "STOP"}
        ],
        "updateTime": "2026-09-30T21:52:03.481516Z",
        "createTime": "2026-09-30T21:52:03.481516Z"
      }
    },
    {
      "name": "users/1234567890/dataTypes/exercise/dataPoints/6661252888799707002",
      "dataSource": {"recordingMethod": "MANUAL", "platform": "FITBIT"},
      "exercise": {
        "interval": {
          "startTime": "2026-09-30T16:10:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T16:25:00Z",
          "endUtcOffset": "-14400s"
        },
        "exerciseType": "WALKING",
        "metricsSummary": {"caloriesKcal": 61},
        "exerciseMetadata": {},
        "displayName": "Walk",
        "activeDuration": "900s",
        "exerciseEvents": [
          {"eventTime": "2026-09-30T16:10:00Z", "eventUtcOffset": "-14400s", "exerciseEventType": "START"},
          {"eventTime": "2026-09-30T16:25:00Z", "eventUtcOffset": "-14400s", "exerciseEventType": "STOP"}
        ],
        "notes": "Lunch walk",
        "updateTime": "2026-09-30T16:30:00.000100Z",
        "createTime": "2026-09-30T16:30:00.000100Z"
      }
    }
  ],
  "nextPageToken": ""
}
```

**F-WEIGHT** and **F-BODYFAT** (list on `weight` and `body-fat`, filter on `<type>.sample_time.physical_time`). The scale reading has fractional seconds, which also appear in `civilTime.nanos` (as in the live 3P weight fixture).
```json
{
  "dataPoints": [
    {
      "name": "users/1234567890/dataTypes/weight/dataPoints/6685309773198399001",
      "dataSource": {
        "recordingMethod": "ACTIVELY_MEASURED",
        "device": {"formFactor": "SCALE", "manufacturer": "Fitbit", "displayName": "Aria Air"},
        "platform": "FITBIT"
      },
      "weight": {
        "sampleTime": {
          "physicalTime": "2026-09-30T11:15:22.500000Z",
          "utcOffset": "-14400s",
          "civilTime": {
            "date": {"year": 2026, "month": 9, "day": 30},
            "time": {"hours": 7, "minutes": 15, "seconds": 22, "nanos": 500000000}
          }
        },
        "weightGrams": 72450
      }
    },
    {
      "name": "users/1234567890/dataTypes/weight/dataPoints/6685309773198399002",
      "dataSource": {"recordingMethod": "MANUAL", "platform": "FITBIT"},
      "weight": {
        "sampleTime": {
          "physicalTime": "2026-09-28T23:00:00Z",
          "utcOffset": "-14400s",
          "civilTime": {"date": {"year": 2026, "month": 9, "day": 28}, "time": {"hours": 19}}
        },
        "weightGrams": 72800,
        "notes": "after dinner"
      }
    }
  ],
  "nextPageToken": ""
}
```
```json
{
  "dataPoints": [
    {
      "name": "users/1234567890/dataTypes/body-fat/dataPoints/5551234567890123001",
      "dataSource": {
        "recordingMethod": "ACTIVELY_MEASURED",
        "device": {"formFactor": "SCALE", "manufacturer": "Fitbit", "displayName": "Aria Air"},
        "platform": "FITBIT"
      },
      "bodyFat": {
        "sampleTime": {
          "physicalTime": "2026-09-30T11:15:22.500000Z",
          "utcOffset": "-14400s",
          "civilTime": {
            "date": {"year": 2026, "month": 9, "day": 30},
            "time": {"hours": 7, "minutes": 15, "seconds": 22, "nanos": 500000000}
          }
        },
        "percentage": 18.4
      }
    }
  ],
  "nextPageToken": ""
}
```

### 8.5 Error fixtures

Every row below is one fixture. JSON bodies are shown after the table. Headers that are not listed are `Content-Type: application/json; charset=UTF-8` only.

| Id | HTTP | Extra headers | Label | Client classification (7.2, 7.6) |
|---|---|---|---|---|
| E400-INVALID-ARGUMENT | 400 | none | DOC message [GH-TRBL], MODELED envelope | `Unsupported(type)`; no retry |
| E400-FILTER-A | 400 | none | MODELED structure; reason names and messages DOC [GH-FILT] | Filter error: one fallback (5.1) |
| E400-FILTER-B | 400 | none | MODELED; reason 3P-observed | Filter error: one fallback (5.1) |
| E400-ACCOUNT-NOT-LINKED | 400 | none | VERBATIM [GH-MIG] (identical to the 3P live fixture) | `AccountNotLinked(metadata.redirect_uri)` |
| E400-PAGE-TOKEN | 400 | none | MODELED | Restart the window from page 1 once, then `Transient` |
| E400-BAD-JSON | 400 | none | DOC message [GH-TRBL] | Bug; `Unsupported(type)` |
| E401-MISSING | 401 | `www-authenticate: Bearer realm="https://accounts.google.com/"` | VERBATIM [PROBE] | Bug: the client sent no token (hygiene check H1) |
| E401-INVALID | 401 | `www-authenticate: Bearer realm="https://accounts.google.com/", error="invalid_token"` | VERBATIM [PROBE], captured with a bogus token. Serving it for **expired** tokens is UNVERIFIED (GH-TRBL lists the same message as "Token expired") | First: invalidate and retry once. Second: `NeedsReauth` |
| E401-APIKEY | 401 | `www-authenticate: Bearer realm="https://accounts.google.com/"` | VERBATIM [PROBE] | Bug: never send API keys |
| E403-SCOPE-A | 403 | `www-authenticate: Bearer realm="https://accounts.google.com/", error="insufficient_scope", scope="https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly"` | MODELED on the Google-wide convention [GERR-SCOPE] | `ScopeMissing(type)` |
| E403-SCOPE-B | 403 | none | MODELED on the reason name in [GH-FILT] | `ScopeMissing(type)` |
| E403-LEGACY-A | 403 | none | DOC message [GH-TRBL], MODELED envelope | `LegacyFitbitAccount` |
| E403-LEGACY-B | 403 | none | DOC message [GH-TRBL]. The same text is also returned for the webhook project-id mistake | Other 403: `Unsupported(type)` |
| E404-HTML | 404 | `Content-Type: text/html; charset=UTF-8` | VERBATIM [PROBE], 1575 bytes for `/v4/nonexistent`. The fake substitutes the request path inside `<code>` | `Unsupported`; never parsed as JSON; never retried |
| E404-JSON | 404 | none | MODELED (`get` on an unknown id) | Treat the point as deleted |
| E412 | 412 | none | DOC message [GH-TRBL]; the `status` value is MODELED | `ProfileNotReady` |
| E429 | 429 | variant A: `Retry-After: 7`; B: none; C: `Retry-After: Thu, 01 Oct 2026 12:00:30 GMT`; D: `Retry-After: 3600` | MODELED (Google Cloud quota shape); `Retry-After` is undocumented [GH-RATE] | `RateLimited(retryAfter)` (7.6) |
| E500 | 500 | none | MODELED | `Transient` |
| E502 | 502 | `Content-Type: text/html; charset=UTF-8` | MODELED on the layout of the captured 404 page | `Transient`; not parsed as JSON |
| E503 | 503 | optional `Retry-After: 2` | MODELED | `Transient` |
| E504 | 504 | none | MODELED | `Transient` |

**Filter-error detection rule (client).** A 400 is a filter error when `ErrorInfo.reason` or `ErrorInfo.metadata.detailedReasons` starts with `INVALID_DATA_POINT_FILTER`, or equals `INVALID_TIME_RANGE`. [GH-FILT] only says "Check `error.details[].metadata.detailedReasons`". The client must therefore work with both A (generic reason plus a detailed reason in metadata) and B (specific reason, no metadata). `ErrorInfo.metadata` is a string-to-string map, so `detailedReasons` is one string.

E400-INVALID-ARGUMENT:
```json
{"error": {"code": 400, "message": "Request contains an invalid argument.", "status": "INVALID_ARGUMENT"}}
```
E400-FILTER-A. The fake sets `message` from the [GH-FILT] text for the detailed reason:
- `..._EXPRESSION_STRUCTURE`: "The filter must be a conjunction or sequence of restrictions. Found: DISJUNCTION";
- `..._COLLECTION_MISMATCH`: "Data type in filter does not match parent data type collection";
- `..._MIXED_TIME_RESTRICTIONS`: "Filter cannot contain both physical and civil time ranges";
- `INVALID_TIME_RANGE`: "Query end time must be strictly larger than start time";
- every other reason: "Invalid filter".
```json
{
  "error": {
    "code": 400,
    "message": "Filter cannot contain both physical and civil time ranges",
    "status": "INVALID_ARGUMENT",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "INVALID_DATA_POINT_FILTER",
        "domain": "health.googleapis.com",
        "metadata": {"detailedReasons": "INVALID_DATA_POINT_FILTER_MIXED_TIME_RESTRICTIONS"}
      }
    ]
  }
}
```
E400-FILTER-B:
```json
{
  "error": {
    "code": 400,
    "message": "Invalid filter",
    "status": "INVALID_ARGUMENT",
    "details": [
      {"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER", "domain": "health.googleapis.com"}
    ]
  }
}
```
E400-ACCOUNT-NOT-LINKED (VERBATIM):
```json
{
  "error": {
    "code": 400,
    "message": "The account is not linked to Google Health.",
    "status": "FAILED_PRECONDITION",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "ACCOUNT_NOT_LINKED",
        "domain": "health.googleapis.com",
        "metadata": {
          "redirect_uri": "https://fitbit.google.com/auth/signup"
        }
      }
    ]
  }
}
```
E400-PAGE-TOKEN, then E400-BAD-JSON:
```json
{"error": {"code": 400, "message": "Invalid page token.", "status": "INVALID_ARGUMENT"}}
```
```json
{
  "error": {
    "code": 400,
    "message": "Invalid JSON payload received. Octal/hex numbers are not valid JSON values",
    "status": "INVALID_ARGUMENT"
  }
}
```
E401-MISSING (VERBATIM, `GET /v4/users/me/dataTypes/steps/dataPoints` with no credentials). For other methods, `metadata.method` names the method, for example `google.devicesandservices.health.v4.HealthProfileService.GetProfile` [PROBE].
```json
{
  "error": {
    "code": 401,
    "message": "Request is missing required authentication credential. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project.",
    "status": "UNAUTHENTICATED",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "CREDENTIALS_MISSING",
        "domain": "googleapis.com",
        "metadata": {
          "method": "google.devicesandservices.health.v4.DataPointsService.ListDataPoints",
          "service": "health.googleapis.com"
        }
      }
    ]
  }
}
```
E401-INVALID (VERBATIM; no `details`):
```json
{
  "error": {
    "code": 401,
    "message": "Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project.",
    "status": "UNAUTHENTICATED"
  }
}
```
E401-APIKEY (VERBATIM):
```json
{
  "error": {
    "code": 401,
    "message": "API keys are not supported by this API. Expected OAuth2 access token or other authentication credentials that assert a principal. See https://cloud.google.com/docs/authentication",
    "status": "UNAUTHENTICATED",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "CREDENTIALS_MISSING",
        "domain": "googleapis.com",
        "metadata": {
          "service": "health.googleapis.com",
          "method": "google.devicesandservices.health.v4.DataPointsService.ListDataPoints"
        }
      }
    ]
  }
}
```
E403-SCOPE-A, then E403-SCOPE-B:
```json
{
  "error": {
    "code": 403,
    "message": "Request had insufficient authentication scopes.",
    "status": "PERMISSION_DENIED",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
        "domain": "googleapis.com",
        "metadata": {
          "service": "health.googleapis.com",
          "method": "google.devicesandservices.health.v4.DataPointsService.ListDataPoints"
        }
      }
    ]
  }
}
```
```json
{
  "error": {
    "code": 403,
    "message": "The caller does not have permission.",
    "status": "PERMISSION_DENIED",
    "details": [
      {"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "MISSING_OAUTH_SCOPE", "domain": "health.googleapis.com"}
    ]
  }
}
```
E403-LEGACY-A, then E403-LEGACY-B:
```json
{
  "error": {
    "code": 403,
    "message": "The caller does not have permission. Could not mint UberMint from GaiaMint",
    "status": "PERMISSION_DENIED"
  }
}
```
```json
{"error": {"code": 403, "message": "The caller does not have permission.", "status": "PERMISSION_DENIED"}}
```
E404-HTML (VERBATIM, with a trailing newline):
```html
<!DOCTYPE html>
<html lang=en>
  <meta charset=utf-8>
  <meta name=viewport content="initial-scale=1, minimum-scale=1, width=device-width">
  <title>Error 404 (Not Found)!!1</title>
  <style>
    *{margin:0;padding:0}html,code{font:15px/22px arial,sans-serif}html{background:#fff;color:#222;padding:15px}body{margin:7% auto 0;max-width:390px;min-height:180px;padding:30px 0 15px}* > body{background:url(//www.google.com/images/errors/robot.png) 100% 5px no-repeat;padding-right:205px}p{margin:11px 0 22px;overflow:hidden}ins{color:#777;text-decoration:none}a img{border:0}@media screen and (max-width:772px){body{background:none;margin-top:0;max-width:none;padding-right:0}}#logo{background:url(//www.google.com/images/branding/googlelogo/1x/googlelogo_color_150x54dp.png) no-repeat;margin-left:-5px}@media only screen and (min-resolution:192dpi){#logo{background:url(//www.google.com/images/branding/googlelogo/2x/googlelogo_color_150x54dp.png) no-repeat 0% 0%/100% 100%;-moz-border-image:url(//www.google.com/images/branding/googlelogo/2x/googlelogo_color_150x54dp.png) 0}}@media only screen and (-webkit-min-device-pixel-ratio:2){#logo{background:url(//www.google.com/images/branding/googlelogo/2x/googlelogo_color_150x54dp.png) no-repeat;-webkit-background-size:100% 100%}}#logo{display:inline-block;height:54px;width:150px}
  </style>
  <a href=//www.google.com/><span id=logo aria-label=Google></span></a>
  <p><b>404.</b> <ins>That’s an error.</ins>
  <p>The requested URL <code>/v4/nonexistent</code> was not found on this server.  <ins>That’s all we know.</ins>
```
E404-JSON, then E412:
```json
{"error": {"code": 404, "message": "Requested entity was not found.", "status": "NOT_FOUND"}}
```
```json
{"error": {"code": 412, "message": "Precondition check failed.", "status": "FAILED_PRECONDITION"}}
```
E429 (the same body for every variant):
```json
{
  "error": {
    "code": 429,
    "message": "Quota exceeded for quota metric 'Requests' and limit 'Requests per minute per user' of service 'health.googleapis.com' for consumer 'project_number:000000000000'.",
    "status": "RESOURCE_EXHAUSTED",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "RATE_LIMIT_EXCEEDED",
        "domain": "googleapis.com",
        "metadata": {
          "service": "health.googleapis.com",
          "consumer": "projects/000000000000",
          "quota_metric": "health.googleapis.com/requests",
          "quota_limit": "RequestsPerMinutePerUser",
          "quota_limit_value": "300"
        }
      }
    ]
  }
}
```
E500, E503, E504:
```json
{"error": {"code": 500, "message": "Internal error encountered.", "status": "INTERNAL"}}
```
```json
{"error": {"code": 503, "message": "The service is currently unavailable.", "status": "UNAVAILABLE"}}
```
```json
{"error": {"code": 504, "message": "The request timed out. Please try again.", "status": "DEADLINE_EXCEEDED"}}
```
E502 (MODELED HTML):
```html
<!DOCTYPE html>
<html lang=en>
  <meta charset=utf-8>
  <meta name=viewport content="initial-scale=1, minimum-scale=1, width=device-width">
  <title>Error 502 (Server Error)!!1</title>
  <a href=//www.google.com/><span id=logo aria-label=Google></span></a>
  <p><b>502.</b> <ins>That’s an error.</ins>
  <p>The server encountered a temporary error and could not complete your request.<p>Please try again in 30 seconds.  <ins>That’s all we know.</ins>
```

**OAuth fixtures (fallback flow B only).**
- Token shapes follow [GH-CL] (which includes `refresh_token_expires_in: 604799` in Testing mode) and [OAUTH-WEB] (a refresh response has no `refresh_token`).
- The rotated variant follows the 3P comment "Google may rotate the refresh token; keep the newest one" [3P-DGH].
- The `error_description` strings are MODELED. [OAUTH-WEB] documents only the `invalid_grant` code.

F-TOKEN, then F-TOKEN-PARTIAL (the user granted only sleep; section 2.2):
```json
{
  "access_token": "ya29.fake-access-1",
  "expires_in": 3599,
  "refresh_token": "1//fake-refresh-1",
  "scope": "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly https://www.googleapis.com/auth/googlehealth.sleep.readonly https://www.googleapis.com/auth/googlehealth.settings.readonly",
  "token_type": "Bearer",
  "refresh_token_expires_in": 604799
}
```
```json
{
  "access_token": "ya29.fake-access-p",
  "expires_in": 3599,
  "refresh_token": "1//fake-refresh-p",
  "scope": "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
  "token_type": "Bearer",
  "refresh_token_expires_in": 604799
}
```
F-TOKEN-REFRESH, then F-TOKEN-REFRESH-ROTATED:
```json
{
  "access_token": "ya29.fake-access-2",
  "expires_in": 3599,
  "scope": "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly https://www.googleapis.com/auth/googlehealth.sleep.readonly https://www.googleapis.com/auth/googlehealth.settings.readonly",
  "token_type": "Bearer"
}
```
```json
{
  "access_token": "ya29.fake-access-3",
  "expires_in": 3599,
  "scope": "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly https://www.googleapis.com/auth/googlehealth.sleep.readonly https://www.googleapis.com/auth/googlehealth.settings.readonly",
  "token_type": "Bearer",
  "refresh_token": "1//fake-refresh-2"
}
```
E-OAUTH-INVALID-GRANT (HTTP 400), then E-OAUTH-REVOKE (HTTP 400 from the revoke endpoint):
```json
{"error": "invalid_grant", "error_description": "Token has been expired or revoked."}
```
```json
{"error": "invalid_token", "error_description": "Token expired or revoked"}
```

### 8.6 Robustness fixtures (R1 to R9)

These are synthetic stress cases. None is a claim about live behavior. Expected outcomes refer to the error policy in 7.6.

**R1. Malformed JSON with HTTP 200.** Expected for every variant: retry once, then `Transient` for that type in this run. Nothing from the window is committed, and `syncedThrough` is unchanged.

| Variant | Body |
|---|---|
| R1a truncated | The compact serialization of F-STEPS-P1, cut inside the second point. The body ends with `...,"steps":{"interval":{"startTime":"2026-09`. `Content-Length` matches the truncated length, so HTTP succeeds and JSON parsing fails |
| R1b disconnect | A valid body, with the connection dropped mid-body. Use MockWebServer's disconnect-during-body socket policy; the client sees an `IOException` |
| R1c | `null` |
| R1d | `{"dataPoints": {"steps": "oops"}}` (wrong type) |
| R1e | A zero-length body with `Content-Length: 0` |

**R2. Non-JSON 2xx.** HTTP 200 with `Content-Type: text/html` and the body `<html><head><title>Sign in to network</title></head><body><a href="http://10.0.0.1/login">Sign in</a></body></html>` (a captive portal). Expected: the same as R1. It must never be treated as an empty result. The client checks `Content-Type` before parsing (5.8).

**R3. Valid empty results.** Bodies: `{}`; `{"dataPoints": []}`; `{"dataPoints": [], "nextPageToken": ""}`; for rollUp `{}`; for dailyRollUp `{"rollupDataPoints": []}`.
- `{}` must be accepted: every response field is optional in [DISC], and an empty match is "an empty list rather than an error" [DISC].
- Expected: zero items. The window is committed.
- For non-identifiable types, stored rows inside the window are deleted, because the window is replaced (5.6).

**R4. Unexpected fields and values** (a `steps` list page). Expected: 2 rows stored (A, and D with count 42), and 2 points skipped and counted (B, C). Details:
- **Point A:**
  - Unknown keys at the point level (`qualityFlags`), in `dataSource` (`dataSourceId`), in `device` (`firmware`) and in `interval` (`timeZoneId`) are ignored.
  - Unknown enum values (`MANUALLY_ENTERED`, `WEAR_OS`, `GLASSES`) map to `UNKNOWN`, and the raw strings are kept.
- **Point B:** only an unknown union key (`stepsV2`), so it is skipped.
- **Point C:** two union keys, so it is skipped (7.3).
- **Point D:** an int64 sent as a bare JSON number. It is accepted because the client decodes int64 from a string **or** a number (4.1).
- **Top level:** the unknown key `unreachableSources` is ignored.
```json
{
  "dataPoints": [
    {
      "dataSource": {
        "recordingMethod": "MANUALLY_ENTERED",
        "device": {"formFactor": "GLASSES", "displayName": "Charge 6", "firmware": "1.2"},
        "platform": "WEAR_OS",
        "dataSourceId": "ds-9"
      },
      "steps": {
        "interval": {
          "startTime": "2026-09-30T13:02:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T13:03:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "minutes": 2}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "minutes": 3}},
          "timeZoneId": "America/New_York"
        },
        "count": "61"
      },
      "qualityFlags": ["INTERPOLATED"]
    },
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "stepsV2": {
        "interval": {
          "startTime": "2026-09-30T13:01:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T13:02:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "minutes": 1}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "minutes": 2}}
        },
        "count": "5"
      }
    },
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "steps": {
        "interval": {
          "startTime": "2026-09-30T13:00:30Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T13:01:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "seconds": 30}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "minutes": 1}}
        },
        "count": "7"
      },
      "distance": {
        "interval": {
          "startTime": "2026-09-30T13:00:30Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T13:01:00Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "seconds": 30}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "minutes": 1}}
        },
        "millimeters": "5000"
      }
    },
    {
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "steps": {
        "interval": {
          "startTime": "2026-09-30T13:00:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T13:00:30Z",
          "endUtcOffset": "-14400s",
          "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9}},
          "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {"hours": 9, "seconds": 30}}
        },
        "count": 42
      }
    }
  ],
  "nextPageToken": "",
  "unreachableSources": ["NEST"]
}
```
More R4 cases:
- An exercise with an unknown `exerciseType` and the codelab typo `distanceMillimiters` (4.8). Expected: type `UNKNOWN` (raw `FUTURE_SPORT_X` kept), and distance 1284300 mm accepted through the alias, because the canonical key is absent.
```json
{
  "dataPoints": [
    {
      "name": "users/1234567890/dataTypes/exercise/dataPoints/6661252888799707003",
      "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "exercise": {
        "interval": {
          "startTime": "2026-09-29T14:26:33Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-29T14:43:38Z",
          "endUtcOffset": "-14400s"
        },
        "exerciseType": "FUTURE_SPORT_X",
        "metricsSummary": {"distanceMillimiters": 1284300, "steps": "1606"},
        "displayName": "Workout",
        "activeDuration": "1025s"
      }
    }
  ],
  "nextPageToken": ""
}
```
- A sleep stage with an unknown type, appended to the main sleep's `stages`. Expected: the stage is stored as `UNKNOWN`. Stage-based minutes for known types are unchanged.
```json
{
  "startTime": "2026-09-30T05:31:00Z",
  "startUtcOffset": "-14400s",
  "endTime": "2026-09-30T05:32:00Z",
  "endUtcOffset": "-14400s",
  "type": "MICRO_AWAKE"
}
```

**R5. Partial and invalid data.** Each item is one point inside an otherwise valid page.

| Id | What is missing or wrong | Expected |
|---|---|---|
| R5a | Sleep still processing: no `type`, `stages`, `summary` or `processed` (an absent boolean is false) | Store the session with `processed = false` and no stages. The 7-day sleep overlap re-fetches it, and the processed version later replaces it by `name` |
| R5b | True-zero steps (no `count`), already in F-STEPS-P2 | Value `0`, not absent (5.4) |
| R5c | Heart-rate sample with no `civilTime` and no `metadata` | Stored. Civil time is derived from `physicalTime` + `utcOffset` |
| R5d | Heart-rate sample with no `utcOffset` (a Required field [DISC]) and no `dataSource` | Stored with an unknown offset; display falls back to `Settings.utcOffset`; provenance unknown |
| R5e | Exercise with `metricsSummary: {}`, no events, no `activeDuration`, no `displayName` | Stored with nulls; duration comes from `interval` |
| R5f | Resting heart rate without `dailyRestingHeartRateMetadata` | `calculationMethod = null` |
| R5g | Interval without `endUtcOffset` | Use `startUtcOffset` |
| R5h | dailyRollUp for 2026-09-28 to 09-30 returns only the 28th and the 30th | No row for the 29th. Absent is not 0 |
| R5i | Point without its time container (`"steps": {"count": "5"}`) | Skipped and counted |
| R5j | Invalid values: impossible timestamp `2026-09-31T25:00:00Z`; `count` `"-3"` (range is [0, 1000000] [DISC]); `beatsPerMinute` `"400"` (range is [1, 300] [DISC]); `endTime` before `startTime` | Each point is skipped and counted. The page is still committed |
| R5k | `list` point without `dataSource` | Stored with empty provenance; its own `sourceKey` (7.5) |

R5a:
```json
{
  "name": "users/1234567890/dataTypes/sleep/dataPoints/7821966286120953003",
  "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "sleep": {
    "interval": {
      "startTime": "2026-10-01T03:05:00Z",
      "startUtcOffset": "-14400s",
      "endTime": "2026-10-01T10:40:00Z",
      "endUtcOffset": "-14400s"
    },
    "metadata": {"mainSleep": true},
    "createTime": "2026-10-01T10:44:51.000412Z",
    "updateTime": "2026-10-01T10:44:51.000412Z"
  }
}
```
R5c, R5d, R5f, R5g, R5i:
```json
{
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "heartRate": {"sampleTime": {"physicalTime": "2026-09-30T12:03:05Z", "utcOffset": "-14400s"}, "beatsPerMinute": "77"}
}
```
```json
{"heartRate": {"sampleTime": {"physicalTime": "2026-09-30T12:03:10Z"}, "beatsPerMinute": "78"}}
```
```json
{
  "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "dailyRestingHeartRate": {"date": {"year": 2026, "month": 9, "day": 28}, "beatsPerMinute": "56"}
}
```
```json
{
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "steps": {
    "interval": {"startTime": "2026-09-30T12:04:00Z", "startUtcOffset": "-14400s", "endTime": "2026-09-30T12:05:00Z"},
    "count": "33"
  }
}
```
```json
{
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "steps": {"count": "5"}
}
```
R5e:
```json
{
  "name": "users/1234567890/dataTypes/exercise/dataPoints/6661252888799707004",
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "exercise": {
    "interval": {
      "startTime": "2026-09-30T19:00:00Z",
      "startUtcOffset": "-14400s",
      "endTime": "2026-09-30T19:20:00Z",
      "endUtcOffset": "-14400s"
    },
    "exerciseType": "WALKING",
    "metricsSummary": {}
  }
}
```
R5h:
```json
{
  "rollupDataPoints": [
    {
      "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 28}, "time": {}},
      "civilEndTime": {"date": {"year": 2026, "month": 9, "day": 29}, "time": {}},
      "steps": {"countSum": "9120"}
    },
    {
      "civilStartTime": {"date": {"year": 2026, "month": 9, "day": 30}, "time": {}},
      "civilEndTime": {"date": {"year": 2026, "month": 10, "day": 1}, "time": {}},
      "steps": {"countSum": "8530"}
    }
  ]
}
```
R5j (four points):
```json
{
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "steps": {
    "interval": {
      "startTime": "2026-09-31T25:00:00Z",
      "startUtcOffset": "-14400s",
      "endTime": "2026-09-30T12:07:00Z",
      "endUtcOffset": "-14400s"
    },
    "count": "12"
  }
}
```
```json
{
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "steps": {
    "interval": {
      "startTime": "2026-09-30T12:07:00Z",
      "startUtcOffset": "-14400s",
      "endTime": "2026-09-30T12:08:00Z",
      "endUtcOffset": "-14400s"
    },
    "count": "-3"
  }
}
```
```json
{
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "heartRate": {"sampleTime": {"physicalTime": "2026-09-30T12:03:15Z", "utcOffset": "-14400s"}, "beatsPerMinute": "400"}
}
```
```json
{
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "steps": {
    "interval": {
      "startTime": "2026-09-30T12:09:00Z",
      "startUtcOffset": "-14400s",
      "endTime": "2026-09-30T12:08:00Z",
      "endUtcOffset": "-14400s"
    },
    "count": "9"
  }
}
```
R5k:
```json
{
  "steps": {
    "interval": {
      "startTime": "2026-09-30T12:10:00Z",
      "startUtcOffset": "-14400s",
      "endTime": "2026-09-30T12:11:00Z",
      "endUtcOffset": "-14400s"
    },
    "count": "14"
  }
}
```

**R6. Duplicates.**

| Id | Setup | Expected |
|---|---|---|
| R6a | Overlapping intervals from two sources: the tracker 12:00-12:01 and the HC phone 12:00-12:05 (in F-STEPS) | Both rows are kept (different `sourceKey`). With Health Connect connected, the `HEALTH_CONNECT` row is dropped (7.7). Daily totals come only from `:dailyRollUp` |
| R6b | The same point on two pages: page 2 repeats page 1's last point (tracker 12:01-12:02, `"98"`) | One row (primary key, 7.5) |
| R6c | The same sleep `name` twice in one response, the older copy without stages (body below) | Keep the copy with the newest `updateTime` (11:35:44); its 9 stages win |
| R6d | `:reconcile` weight whose `dataPointName` equals a `list` `name` (body below) | Same `upstreamId`, so no second row |
| R6e | The identical heart-rate sample (12:01:25, `"73"`, same source) twice | One row |

R6c (the second element stands for the F-SLEEP main record):
```text
{
  "dataPoints": [
    {
      "name": "users/1234567890/dataTypes/sleep/dataPoints/7821966286120953001",
      "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
      "sleep": {
        "interval": {
          "startTime": "2026-09-30T02:40:00Z",
          "startUtcOffset": "-14400s",
          "endTime": "2026-09-30T10:52:00Z",
          "endUtcOffset": "-14400s"
        },
        "metadata": {"mainSleep": true},
        "createTime": "2026-09-30T11:02:10.123456Z",
        "updateTime": "2026-09-30T11:02:10.123456Z"
      }
    },
    "<F-SLEEP main record, updateTime 2026-09-30T11:35:44.654321Z>"
  ],
  "nextPageToken": ""
}
```
R6d:
```json
{
  "dataPoints": [
    {
      "dataPointName": "users/1234567890/dataTypes/weight/dataPoints/6685309773198399001",
      "weight": {
        "sampleTime": {
          "physicalTime": "2026-09-30T11:15:22.500000Z",
          "utcOffset": "-14400s",
          "civilTime": {
            "date": {"year": 2026, "month": 9, "day": 30},
            "time": {"hours": 7, "minutes": 15, "seconds": 22, "nanos": 500000000}
          }
        },
        "weightGrams": 72450
      }
    }
  ],
  "nextPageToken": ""
}
```

**R7. Out-of-order and late records** (model mode; datasets change between runs).

| Id | Dataset change | Run and window | Expected |
|---|---|---|---|
| R7a | Points within a page in ascending order instead of newest first | any | Same stored result. Within a page, order is ignored. The sleep stop rule uses the oldest start on the page (5.1) |
| R7b | V2 adds steps `2026-09-30T23:10:00Z-23:11:00Z` count `"45"` (a late tracker sync) | Run 2 at 13:00Z on 10-01. Window from `syncedThrough - 48h` = 2026-09-29T12:00Z | Inserted |
| R7c | V3 adds steps `2026-09-24T10:00:00Z-10:01:00Z` count `"30"` | Normal run 3 (window from 2026-09-29T13:00Z), then the weekly deep re-sync `[now-30d, now)` | The normal run never asks for that time (journal); the deep re-sync inserts it |
| R7d | V4 removes the steps point 12:01-12:02 and the nap | Run 4 | The steps row is deleted by window replace, and the nap is tombstoned. When E503 is injected on the last sleep page, nothing is deleted or tombstoned |
| R7e | V5 returns the main sleep with the same `name`, `updateTime` 2026-10-01T15:00:00Z, and the 05:00-07:30 LIGHT stage split into LIGHT 05:00-06:00, REM 06:00-06:20, LIGHT 06:20-07:30 | Run 5 (inside the 7-day overlap) | The stage set is replaced as a whole, with no stale stage rows |
| R7f | Sleep 2026-09-28T03:00Z-10:00Z when the window starts at 2026-09-28T05:00Z | any | Included, because the sleep filter is on end time. Stored once |

**R8. Pagination.**

| Id | Setup | Expected |
|---|---|---|
| R8a | Three pages: tokens `pt-a` and `pt-b`; the last page omits `nextPageToken` | All points read. Requests 2 and 3 repeat every parameter and add `pageToken` |
| R8b | The end of the stream marked by `""`, by an omitted key, or by `null` | All three end the loop |
| R8c | Page 2 returns `pt-a` again (a repeated token) | Stop. `Transient` for the type; no commit |
| R8d | E400-PAGE-TOKEN on page 2 | Restart the window from page 1 once. A second failure gives `Transient` |
| R8e | E503 once on page 2 | Retried in-run; completes |
| R8f | E503 four times on page 2 | No commit (same as S34 in 8.7) |
| R8g | The client sends `pageSize=100` for sleep | The fake clamps to 25 (not an error). Hygiene check H6 still fails the test, because the client must send at most 25 |
| R8h | Fewer points than `pageSize` together with a non-empty token | Keep paging. "The service may return fewer than this value" [DISC] |
| R8i | rollUp page 2 whose body differs from page 1 apart from `pageToken` | E400-PAGE-TOKEN from the fake; the test fails, because the client must resend the identical body |
| R8j | An endless chain of distinct tokens | Stop after 500 pages (5.2). `Transient`; no commit |

**R9. Time zones.**
- **R9a. Sleep across the end of US daylight saving time.** DST ends on Sunday 2026-11-01 at 02:00 local. The session starts with offset `-14400s` and ends with `-18000s`. One stage spans the change and carries both offsets.
  - Expected: the duration from instants is 520 minutes. The civil clock difference (23:30 to 07:10) would wrongly suggest 460 minutes.
  - Both offsets are stored, and each stage renders in its own offsets.
```json
{
  "name": "users/1234567890/dataTypes/sleep/dataPoints/7821966286120953004",
  "dataSource": {"recordingMethod": "DERIVED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "sleep": {
    "interval": {
      "startTime": "2026-11-01T03:30:00Z",
      "startUtcOffset": "-14400s",
      "endTime": "2026-11-01T12:10:00Z",
      "endUtcOffset": "-18000s"
    },
    "type": "STAGES",
    "stages": [
      {"startTime": "2026-11-01T03:30:00Z", "startUtcOffset": "-14400s", "endTime": "2026-11-01T05:59:00Z", "endUtcOffset": "-14400s", "type": "LIGHT"},
      {"startTime": "2026-11-01T05:59:00Z", "startUtcOffset": "-14400s", "endTime": "2026-11-01T06:30:00Z", "endUtcOffset": "-18000s", "type": "DEEP"},
      {"startTime": "2026-11-01T06:30:00Z", "startUtcOffset": "-18000s", "endTime": "2026-11-01T12:10:00Z", "endUtcOffset": "-18000s", "type": "LIGHT"}
    ],
    "metadata": {"stagesStatus": "SUCCEEDED", "processed": true, "mainSleep": true}
  }
}
```
- **R9b. Travel.** A steps interval with offset `+3600s` (London, BST). Expected: stored with `+3600s`; the civil date and time are 2026-10-20 23:30 in that offset, not the phone's current zone (5.10).
```json
{
  "dataSource": {"recordingMethod": "PASSIVELY_MEASURED", "device": {"displayName": "Charge 6"}, "platform": "FITBIT"},
  "steps": {
    "interval": {
      "startTime": "2026-10-20T22:30:00Z",
      "startUtcOffset": "3600s",
      "endTime": "2026-10-20T22:31:00Z",
      "endUtcOffset": "3600s",
      "civilStartTime": {"date": {"year": 2026, "month": 10, "day": 20}, "time": {"hours": 23, "minutes": 30}},
      "civilEndTime": {"date": {"year": 2026, "month": 10, "day": 20}, "time": {"hours": 23, "minutes": 31}}
    },
    "count": "64"
  }
}
```
- **R9c. Daily values.** Daily values (F-RHR, F-DAILY-STEPS) are stored by `LocalDate` and never converted through any time zone.

### 8.7 Scenario matrix (expected client behavior)

| # | Fake setup | Expected |
|---|---|---|
| S01 | F-IDENTITY | Connected. Only `healthUserId` is stored; `legacyUserId` is not persisted |
| S02 | identity returns E400-ACCOUNT-NOT-LINKED | `AccountNotLinked("https://fitbit.google.com/auth/signup")`; no sync work is enqueued |
| S03 | identity returns E412 | `ProfileNotReady` |
| S04 | identity returns E403-LEGACY-A | `LegacyFitbitAccount` |
| S05 | A data call returns E403-LEGACY-B | `Unsupported(type)`, not `LegacyFitbitAccount` |
| S06 | Granted scopes are only `sleep.readonly` (F-TOKEN-PARTIAL, or `grantedScopes()` with flow A) | Only SLEEP is enabled, and other types show "not shared". The journal has no request for other types |
| S07 | F-STEPS-P1, then P2 (`pageSize=3`) | 5 rows: 112, 98, 240 (HC phone), 87, and 0 (true zero). Exactly 2 requests; the second differs only by `pageToken=pt-steps-2`. One Room transaction |
| S08 | S07 with Health Connect also connected | The `HEALTH_CONNECT` row is dropped, leaving 4 rows (7.7) |
| S09 | F-STEPS-RECONCILE | 4 rows with no provenance |
| S10 | F-STEPS-ROLLUP | 4 windows. The first is 0, not absent |
| S11 | F-DAILY-STEPS; then again with `dailyRollUpEnd = EXCLUSIVE` | 2026-09-29 = 10412 and 2026-09-30 = 8530, keyed by `civilStartTime.date`. In exclusive mode only 09-29 returns, and nothing is mis-dated |
| S12 | F-DAILY-TOTALCAL | 2315.5 and 2198.25 kcal |
| S13 | F-HR | 3 samples. Only the 73 bpm sample has `SEDENTARY`/`WRIST` |
| S14 | F-HR-ROLLUP | Window 2 = 72/73/74, equal to the F-HR samples |
| S15 | F-RHR | 09-30 = 54 (`WITH_SLEEP`), 09-29 = 55 (`ONLY_WITH_AWAKE_DATA`), as `LocalDate` keys |
| S16 | F-SLEEP | 2 sessions keyed by `name`. Main: 9 stages; summary stored as given (asleep 475, awake 17, period 492); 1 short awakening stored separately, without splitting stages. Nap: `nap = true`, `mainSleep = false`, `CLASSIC`, `REJECTED_NAP` |
| S17 | F-EXERCISE | Run: active 2050 s; 5.012 km from the double `distanceMillimeters`; 4 events; `hasGps = true`. Walk: `MANUAL`, notes kept, no device |
| S18 | F-WEIGHT, F-BODYFAT | 72450 g (scale) and 72800 g (manual), keyed by `name`; `11:15:22.5Z` keeps its fraction; body fat 18.4 % |
| S19 | F-DEVICES-P1, then P2 (`pageSize=1`) | 2 devices, battery 82 (High) and 12 (Low). `macAddress` is not persisted |
| S20 | F-SETTINGS | Time zone `America/New_York`, offset -4 h. Unknown settings fields are ignored |
| S21 | E401-INVALID once, then 200 | Exactly one `invalidate` and one retry; success |
| S22 | E401-INVALID twice | `NeedsReauth`. No third request; a reconnect notification is posted |
| S23 | E403-SCOPE-A (and, separately, E403-SCOPE-B) on steps; everything else OK | `ScopeMissing(STEPS)`. Other types are committed; the run is a partial success |
| S24 | E400-INVALID-ARGUMENT on one type | `Unsupported(type)`; no retry; the other types continue |
| S25 | `rejectFilterMembers = {"interval.start_time"}` on steps (E400-FILTER-A and -B) | One fallback request with `steps.interval.civil_start_time >= "<civil start - 14h>"`; results are clamped to the window (5.1) |
| S26 | `sleepFilter = REJECT_ALL` | E400-FILTER-B, then one unfiltered `pageSize=25` request; paging stops by the recency rule (5.1) |
| S27 | E404-HTML on any call | `Unsupported`. No JSON exception escapes; no retry |
| S28 | E429-A (`Retry-After: 7`), then 200 | Waits 7 s in virtual time, then succeeds |
| S29 | E429-B three times | Retries after about 2 s and 4 s (with jitter), then `RateLimited(null)`. `nextAllowedAt` is set; the Worker returns `Result.retry()` |
| S30 | E429-C (HTTP-date 30 s ahead), then 200 | Waits 30 s, then succeeds |
| S31 | E429-D (`Retry-After: 3600`) | No in-run wait, because of the 60 s cap; `nextAllowedAt = now + 3600 s` |
| S32 | E500, E502 (HTML), E503 or E504 once, then 200 | Retried after 1 s plus jitter; success. The E502 HTML is never parsed as JSON |
| S33 | Response delayed past the read timeout | `Transient`; retried in-run like a 5xx |
| S34 | E503 four times on page 2 of steps | Nothing committed for steps; `syncedThrough` unchanged; `Transient`; the other types continue |
| S35 | E400-ACCOUNT-NOT-LINKED during a sync (the link was removed later) | The source stops with `AccountNotLinked` |
| S36 | R1a to R1e, R2 | Retry once, then `Transient`; no commit |
| S37 | R3 variants | Valid empty result; the window is committed |
| S38 | R4 | 2 rows stored, 2 points skipped; `UNKNOWN` enums keep their raw values; the alias is accepted |
| S39 | R5, R6, R7, R8, R9 | As stated in each table in 8.6 |
| S40 | Whole suite | Hygiene checks H1 to H11 below hold for every recorded request (run as an `@After` check) |

**Hygiene checks on the request journal:**

| # | Check |
|---|---|
| H1 | Every request has `Authorization: Bearer ...` and `Accept: application/json`, and no `key` parameter |
| H2 | `list` and `:reconcile` never carry `startTime` or `endTime` query parameters (4.8) |
| H3 | Filters use snake_case type names, only `>=` and `<`, and never `OR` |
| H4 | `list` is never called for `floors` or `total-calories` |
| H5 | `dataSourceFamily` is never sent on the sleep `list` |
| H6 | `pageSize` is at most 25 for sleep and exercise |
| H7 | A page-token request repeats every other parameter, or the whole rollUp body, unchanged |
| H8 | rollUp ranges stay within 14 days (`heart-rate`, `total-calories`) or 90 days (other types), and `windowSize` is at least `1s` |
| H9 | dailyRollUp `month` and `day` are JSON numbers with no leading zeros, and `windowSizeDays` is 1 |
| H10 | At most 200 requests per minute per token (5.7) |
| H11 | No write method is ever called (`POST .../dataPoints`, `PATCH`, `:batchDelete`) |

### 8.8 Dispatcher sketch (Kotlin)

The core is a pure function, so it serves two uses: MockWebServer in JVM tests, and an OkHttp `Interceptor` for the debug-only in-app fake mode that `08-testing-strategy.md` section 5 plans. The adapter below uses the `okhttp3.mockwebserver` API of OkHttp 4.x. With `mockwebserver3` (OkHttp 5), use `MockResponse.Builder()` and `RecordedRequest.url` instead; pin the version in `07-architecture-and-versions.md`.

```kotlin
data class FakeRequest(val method: String, val path: String, val query: Map<String, String>,
                       val headers: Map<String, String>, val body: String)
data class FakeResponse(val code: Int, val body: String,
                        val contentType: String = "application/json; charset=UTF-8",
                        val headers: Map<String, String> = emptyMap())

data class FakeConfig(
    val sleepFilter: SleepFilter = SleepFilter.DOCUMENTED,          // or REJECT_ALL (3P-observed)
    val rejectFilterMembers: Set<String> = emptySet(),              // e.g. "interval.start_time"
    val filterErrorStyle: FilterErrorStyle = FilterErrorStyle.DETAILED_REASONS,   // E400-FILTER-A, or REASON_ONLY (-B)
    val dailyRollUpEnd: DailyEnd = DailyEnd.INCLUSIVE,              // or EXCLUSIVE (4.5)
    val endOfPages: EndMarker = EndMarker.EMPTY_STRING,             // or OMITTED (R8b)
    val ratePerMinute: Int = 300,                                   // GH-RATE
)

// Omitted for brevity: the enums above, Routes, Tokens, Validation, FixtureStore, Injection, takeInjection(),
// wwwAuthInsufficient() and the WWW_AUTH* header constants.
class FakeGoogleHealth(private val store: FixtureStore, private val config: FakeConfig, private val clock: Clock) {
    val journal = mutableListOf<FakeRequest>()
    private val injections = ArrayDeque<Injection>()

    fun inject(times: Int, fixtureId: String, retryAfter: String? = null, delayMs: Long = 0,
               match: (FakeRequest) -> Boolean = { true }) { injections += Injection(times, fixtureId, retryAfter, delayMs, match) }

    fun handle(req: FakeRequest): FakeResponse {
        journal += req
        val route = Routes.match(req.method, req.path) ?: return store.html404(req.path)            // 1. route (E404-HTML)
        takeInjection(req)?.let { return it }                                                       //    fault injection
        val auth = req.headers["authorization"]
            ?: return store.error(if ("key" in req.query) "E401-APIKEY" else "E401-MISSING", WWW_AUTH)   // 2. auth
        val grant = Tokens.parse(auth.removePrefix("Bearer ")) ?: return store.error("E401-INVALID", WWW_AUTH_INVALID)
        if (!grant.allows(route)) return store.error("E403-SCOPE-A", wwwAuthInsufficient(route.scope))   // 3. scope
        grant.accountStateError?.let { return store.error(it) }                                     // 4. account state
        Validation.check(route, req, config)?.let { return it }                                     // 5. V1-V9 (8.3)
        return route.serve(req, store, config, clock)                                               // 6. replay or model
    }
}

// Adapter for okhttp3.mockwebserver (OkHttp 4.x)
class FakeDispatcher(private val fake: FakeGoogleHealth) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val res = fake.handle(FakeRequest(
            method = request.method!!,
            path = url.encodedPath,                                  // keeps ":reconcile" etc.
            query = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() },
            headers = request.headers.associate { (k, v) -> k.lowercase() to v },
            body = request.body.readUtf8()))
        return MockResponse().setResponseCode(res.code).setHeader("Content-Type", res.contentType)
            .apply { res.headers.forEach { (k, v) -> setHeader(k, v) } }
            .setBody(res.body)
    }
}
```

Notes:
- Implement replay mode first. It covers S01 to S38 with the fixtures above. Model mode (datasets V1 to V5, V9 rate limiting) is needed for R7 and S39.
- Keep the fixture JSON in files, not in Kotlin strings, so captured live bodies can replace them after the spike (7.9) without touching code.

## 9. Uncertainties / UNVERIFIED items

These need live access, a device, or a decision. The "Resolve by" column points to the 7.9 spike checklist or to the owner.

| # | Item | Where | Current handling | Resolve by |
|---|---|---|---|---|
| U1 | **Onboarding.** When new projects will be onboarded, and what a non-onboarded project sees (consent error, 403, or missing scopes) | 1, 7.8 #3b | `GoogleHealthApiSource` stays behind a flag; Health Connect ships first | Watch [GH-SETUP]/[GH-SUP]; run 7.8 #3b once |
| U2 | **OAuth client type.** Whether an Android-type client (`AuthorizationClient`) can be granted `googlehealth.*` scopes. The docs only show Web clients | 2.3 | Flow A is primary with a fallback; flow B needs a broker (a product decision) | 7.9 step 1 |
| U3 | Whether `AuthorizationClient.authorize()` returns a token silently from a WorkManager worker | 2.4 | Workers stop and notify when a resolution is needed | 7.8 #3 (testable today with a non-health scope) |
| U4 | The exact accessor for granted scopes on `AuthorizationResult` (expected `getGrantedScopes()`). It was not in the cached guide and was deliberately not fetched | 2.2, 7.3 | `TokenProvider.grantedScopes()` abstracts it | Check the class reference or the IDE at implementation time |
| U5 | Which scopes are "sensitive" and which "restricted" ("Most scopes ... are restricted") | 2.2 | Plan for restricted: verification plus CASA above 100 users | The Cloud console Data Access page during verification |
| U6 | The scope for heart-rate, HRV and resting HR: [GH-DT] says `health_metrics_and_measurements`; 3P-DGH maps them to `activity_and_fitness` (not live-verified, since it requests both) | 4.8, 8.1 | Request both scopes; a 403 maps to `ScopeMissing(type)` either way | 7.9 step 3 (request HR with only `activity_and_fitness`) |
| U7 | **Sleep filters.** Documented `sleep.interval.end_time`, versus the 3P observation that every sleep filter member is rejected | 5.1 | Try the documented filter, then fall back to unfiltered recency paging | 7.9 step 4 |
| U8 | Two-sided physical filters (`start_time >= ... AND start_time < ...`, `sample_time.physical_time`) are documented but not live-verified; 3P used only civil lower bounds | 5.1 | Fallback to a civil lower bound plus clamping | 7.9 step 4 |
| U9 | **dailyRollUp end date**: inclusive (3P, live) or closed-open ([DISC]) | 4.5 | Key by `civilStartTime.date`; the fake supports both | 7.9 step 5 |
| U10 | The true-zero record shape (`"steps": {"interval": ...}` with no `count`). It matches how ProtoJSON omits a zero-valued scalar [PROTO-JSON], but that is an inference | 5.4 | Absent `count` is read as 0 | 7.9 step 5 |
| U11 | Real bodies for 403 (missing scope), 412, 429 (and whether `Retry-After` is sent), 5xx, 404 JSON, filter errors (`reason` vs `detailedReasons`), page-token errors and rollup-range errors | 5.8, 8.5 | MODELED fixtures; classification relies on status and reason, never on message text | 7.9 step 3 |
| U12 | Whether an expired access token gets the same 401 body as the captured invalid-token probe | 5.8 | One retry after `invalidate`, regardless of body | 7.9 step 3 |
| U13 | Ordering of `:reconcile`, `:rollUp` and `:dailyRollUp` results, and whether page tokens expire | 5.2 | The client never depends on order; a page-token 400 restarts the window once | Spike observation |
| U14 | Whether the end of pagination is `""` or an omitted key | 5.2 | Both treated as the end | Spike observation |
| U15 | Whether `:dailyRollUp` totals equal the totals in the Google Health app UI | 5.3 | `dailyRollUp` is the source of user-facing totals | 7.9 step 6 |
| U16 | How late tracker data can arrive (unsynced devices) | 5.5 | 48 h / 7 day overlaps plus a weekly 30-day deep re-sync | Field data after launch |
| U17 | `platform` and `application.packageName` on API points that Google Health imported from Health Connect | 5.9, 7.7 | Drop `platform == HEALTH_CONNECT` when HC is connected | 7.9 step 7 |
| U18 | The `dataOrigin.packageName` Google Health uses when writing to Health Connect (expected `com.fitbit.FitbitMobile`), and its `device` and `recordingMethod` values | 6.2, 7.7 | Configurable package filter | Device test (7.8 #1) |
| U19 | How often Google Health writes to Health Connect, the latency after a tracker sync, and how much history it writes on first enable | 6.2 | Changes API plus a 30-day re-read on token expiry | Device test (7.8 #1) |
| U20 | Whether debug, sideloaded or internal-track builds can read Health Connect without the Play health declaration | 6.7 | The test plan assumes debug builds work. Submit the declaration early | Device test; Play Console |
| U21 | The exercise route permission name (`READ_EXERCISE_ROUTE` vs `READ_EXERCISE_ROUTES`) | 6.3 | Out of v1 scope | Reference docs or SDK constant at implementation time |
| U22 | Health Connect's numeric rate limits | 6.6 | Changes API first; back off on `IllegalStateException` | Not documented; observe in device tests |
| U23 | Artifact versions (`connect-client` 1.1.0, `connect-testing` 1.0.0-alpha04, `play-services-auth` 22.0.0) were not cross-checked on maven.google.com (`dl.google.com` is blocked here) | 6.1, 7.10 | Taken from official release-notes pages | First Gradle sync |
| U24 | **Policy.** Whether sending Google Health API data to OpenAI for AI features is allowed, and what consent and CASA scope it needs. The policies do not mention AI or LLMs | 7.11 | Treat it as a transfer: explicit per-feature consent, disclosed in Data safety, CASA expected | Product owner and legal review |
| U25 | Behavior of an authenticated wrong-verb request (unauthenticated `POST :reconcile` gave an HTML 404, but `GET :dailyRollUp` gave 401) | 3.1 | Not relevant if the client uses the documented verbs; both classify as `Unsupported` | Spike observation (optional) |
| U26 | Fixture details that are MODELED: the `macAddress` format, the `languageLocale` format, the `name` form returned for settings and profile, and the device `features` subset | 8.4 | The client treats them as opaque strings and does not persist `macAddress` | Spike capture |
