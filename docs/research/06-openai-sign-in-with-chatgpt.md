# 06 - OpenAI "Sign in with ChatGPT" (SIWC) for Agentle Android

Research date: 2026-10-01. Author: Agent 6 (OpenAI authentication specialist).
Scope: let the Agentle user power AI analysis with their own ChatGPT plan, without pasting an API key, from a native Android app.

Conventions: every claim carries a source key from section 1. **UNVERIFIED** = not confirmed by an official source. **UNDOCUMENTED** = official sources are silent. Third-party code is evidence of observed behaviour, never authority.

---

## 0. Verdict

1. **It exists and needs no API key or client secret.** "ChatGPT plan usage" is an optional capability of SIWC. The app registers itself *during* sign-in (`client_id=dynamic_agent_client`), receives its own issued `client_id` (`oaiapp_...`) on the redirect, redeems the code with PKCE, then calls `POST https://api.openai.com/v1/responses` with the user's bearer access token, `store:false`, `stream:true`. [D-OV] [D-SI] [D-MI]
2. **Eligibility is narrow.** Plan usage is offered to "open-source projects, personal projects that run locally, and selected private apps"; "If you're building a paid or remotely hosted app, join the waitlist to request access before offering it to users." [D-CB] Only "Eligible ChatGPT Plus and Pro users" can fund requests [D-QS] [H-PLAN]. Agentle qualifies as long as it stays Nick's personal, locally running app (or is published open-source). Any paid, closed or remotely hosted distribution needs OpenAI's interest form first.
3. **Android is UNDOCUMENTED.** The SIWC pages, the official DevKit and the cookbook never mention mobile, Android, iOS, custom URL schemes or App Links. The only documented redirect for this flow is `http://127.0.0.1:<port>/<fixed path>`; "only the port may vary" and "Do not substitute with localhost". [D-SI] On Android that means an in-process loopback HTTP listener plus a Custom Tab, which is the RFC 8252 section 7.3 pattern. It conforms to the standard and works on one device, but OpenAI has neither confirmed nor prohibited it for mobile. Treat it as a **launch risk**, and ask OpenAI (interest form) before any public release.
4. **Capabilities (section 4.4):**
   - SUPPORTED: streamed text; image and file inputs (inline) when the model accepts them; function or custom tools, but only grouped in a `namespace` tool or an `additional_tools` input item.
   - Policy-dependent: web search.
   - UNSUPPORTED: image generation, audio (input, TTS, transcription), video, background mode, file search, code interpreter, hosted MCP/connectors, the Files upload API, `previous_response_id` over HTTP.
   - UNDOCUMENTED: structured outputs (`text.format` json_schema). Probe it, and always keep a plain-text fallback. [D-PL]
5. **JITAI media cannot come from SIWC.** Use SIWC only to generate intervention text. Render voice with Android `TextToSpeech`, and build visuals or video from local assets and templates.
6. **Usage cost lands on the user.** Requests count toward the user's "ChatGPT Work and Codex usage", and the user can set per-app weekly caps. [H-PLAN] Background JITAI generation therefore needs a strict daily budget.
7. **Never reuse another product's OAuth client** (for example the Codex CLI client). That is impersonation (section 7).

---

## 1. Sources inspected

**How the sources were read.** `developers.openai.com` is denied to `curl` by this container's egress proxy (403 `connect_rejected`), so the SIWC pages were read through WebFetch on 2026-10-01. WebFetch returns model-extracted text and declines long verbatim reproduction, so quotes are short. Every protocol identifier was cross-checked against the OpenAI-authored DevKit source, which is cached locally.

**Cache location.** All paths below starting `agent6/`, `pages/` or `rel/` are relative to this directory:
`/tmp/claude-0/-home-claude/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/`

### 1.1 OpenAI documentation pages

| Key | Source | What it covers |
|---|---|---|
| D-OV | https://developers.openai.com/siwc/token-sharing-open-source | Plan-usage overview, client vs host, `ext_agent_host_id` formats, first-launch flow |
| D-SI | https://developers.openai.com/siwc/token-sharing-open-source/sign-in | Authorize and token endpoints, parameters, scopes, redirect rules, callback, ID-token checks, storage |
| D-PS | https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions | Accounts, refresh, revocation, credential security, usage link |
| D-ER | https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery | Error codes and recovery |
| D-MI | https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference | `GET /v1/models`, `POST /v1/responses`, stream events |
| D-PL | https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations | Unsupported fields, tools, inputs |
| D-TR | https://developers.openai.com/siwc/token-sharing-open-source/token-reference | Token fields, lifetimes, access-token claims |
| D-VM | https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms | Host transfer; loopback reaches the browser's machine |
| D-QS | https://developers.openai.com/siwc/quickstart | Integration options, availability, scopes, button label |
| D-RC | https://developers.openai.com/siwc/request-client-id | Registered client IDs: commercial partners only |
| D-WS | https://developers.openai.com/siwc/website | Discovery URL, JWKS URL, client auth methods, button formats |
| D-UX | https://developers.openai.com/siwc/ui-ux-guidelines | Required UI copy and placements |
| D-IDX | https://developers.openai.com/siwc/llms.txt and https://developers.openai.com/siwc/llms-full.txt | Page index; full-text searches for mobile, structured outputs, rate-limit headers |
| D-CB | https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt | Paste Perfect walkthrough; eligibility; "Usage policy and terms" |
| H-PLAN | https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites | Plus/Pro only, shared usage pool, per-app limits, disconnect path |
| H-SIWC | https://help.openai.com/en/articles/20001410-sign-in-with-chatgpt | Identity sign-in availability; what apps receive |
| BRAND | https://openai.com/brand/ | Name and logo rules |
| POL | https://openai.com/policies/ and https://openai.com/policies/usage-policies/ (effective 2025-10-29) | Terms index; usage-policy clauses relevant to health data |

### 1.2 OpenAI code and specifications

| Key | Source | What it covers |
|---|---|---|
| DK | OpenAI DevKit, https://github.com/openai/sign-in-with-chatgpt-devkit, commit `f723814` (2026-09-29). Cached at `agent6/devkit/`: `packages/local/src/{oauth,errors,index,models,responses,storage,types}.ts`, `test/*.mjs`, `docs/security.md`, `LICENSE`, `assets/README.md`, `examples/paste-perfect/electron/main.ts` | Reference implementation of the full protocol |
| OAS | OpenAI OpenAPI spec v2.3.0, cached at `agent6/openapi-main.yaml` | `NamespaceToolParam`, `AdditionalToolsItemParam`, stream events, `input_image`, `input_file`, `json_schema` |

### 1.3 Standards and platform documentation

| Key | Source |
|---|---|
| RFC8252 | https://www.rfc-editor.org/rfc/rfc8252 (sections 7.3, 8.3, 8.12, B.2) |
| AUTHTAB | https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab (updated 2025-01-31) |
| LNA | https://developer.chrome.com/blog/local-network-access (updated 2025-09-29) and https://github.com/WICG/local-network-access/blob/main/explainer.md |
| A17 | https://developer.android.com/about/versions/17/behavior-changes-all (cached `pages/a17_all.txt`, last updated 2026-10-01) |
| LNP | https://developer.android.com/privacy-and-security/local-network-permission (cached `pages/g_local_network.txt`) and `pages/a16_target.txt` |
| FGS | https://developer.android.com/develop/background-work/services/fgs/service-types (cached `pages/g_fgs_types.txt`) |
| AX-BROWSER | https://developer.android.com/jetpack/androidx/releases/browser: 1.10.0 stable, 2026-03-25 |
| AX-SEC | https://developer.android.com/jetpack/androidx/releases/security (cached `rn_security.txt`): security-crypto deprecated |
| MVN | https://repo.maven.apache.org/maven2/ metadata, read 2026-10-01: nimbus-jose-jwt 10.10, tink-android 1.23.0, okhttp, okhttp-sse and mockwebserver3 5.5.0 |

### 1.4 Third-party evidence only (cached under `agent6/`, all commits dated 2026-10-01)

| Key | Source | What it shows |
|---|---|---|
| TP-OC | `openclaw_openclaw/extensions/openai/token-sharing*.ts` and `docs/providers/openai/setup.md` | SIWC client plus a limitations list |
| TP-PI | `earendil-works_pi/packages/ai/src/auth/oauth/openai-chatgpt.ts`, `src/utils/retry.ts`, `test/openai-responses-usage-limit.test.ts` | Another SIWC client and its error fixtures |
| TP-T3 | `pingdotgg_t3code/apps/server/src/provider/CodexChatGptAuth.ts`, `CodexManagedErrors.ts` | Another SIWC client and its error classifier |
| TP-OCODE | `anomalyco_opencode/packages/opencode/src/plugin/openai/codex.ts` | **Counter-example**: reuses the Codex CLI client id |

---

## 2. Wire protocol (implementable spec)

### 2.1 Endpoints and constants

| Item | Value | Source |
|---|---|---|
| Issuer | `https://auth.openai.com` | D-WS; DK `oauth.ts:9` |
| Discovery | `https://auth.openai.com/.well-known/openid-configuration` | D-WS; DK `oauth.ts:28` |
| Authorization endpoint | `https://auth.openai.com/api/accounts/authorize` ("Open the system browser at" this URL) | D-SI, D-WS |
| Token endpoint | `https://auth.openai.com/api/accounts/oauth/token` | D-SI, D-PS, D-WS |
| JWKS | `https://auth.openai.com/.well-known/jwks.json` | D-WS |
| Revocation endpoint | Read `revocation_endpoint` from discovery. The path is not published. | D-PS |
| Resource indicator | `https://api.openai.com/v1` (sent on authorize, code exchange and refresh) | D-SI, D-PS |
| API base | `https://api.openai.com/v1` | D-MI |
| Client type | Public client, no secret: "This direct flow needs neither a client secret nor a partner API key." | D-SI |
| Bootstrap client id | `dynamic_agent_client` | D-SI |
| Issued client id | `oaiapp_...` (returned in the callback) | D-SI; TP-OC regex `^oaiapp_[A-Za-z0-9_-]+$` |
| Usage page for users | `https://chatgpt.com/settings/usage` | D-PS, D-ER; DK `index.ts:13` |

**Discovery validation** (DK `oauth.ts:26-46`):
- `issuer` must equal `https://auth.openai.com` exactly.
- `authorization_endpoint`, `token_endpoint` and `jwks_uri` must have the issuer's origin. So must `revocation_endpoint`, which is optional.

Recommendation: use discovery, apply the same origin checks, and log a diagnostic if a discovered endpoint differs from the documented constants above.

### 2.2 "Dynamic client registration" is not RFC 7591

There is no `POST /register`. Registration happens inside the authorization request: [D-OV] [D-SI]
- The app sends `client_id=dynamic_agent_client` plus `agent_name_hint=<app name>`.
- The user approves and can edit the name: "it is display metadata, not identity".
- The redirect then carries `client_id=<issued id>`.

Binding of the issued id:
- "each issued `client_id` is bound to the authenticated user and the workspace selected during registration" [D-OV]
- "The client stays bound to its registered workspace." [D-SI]

The redirect URI's scheme, host and path become part of the registration. A `localhost` registration cannot later switch to `127.0.0.1`:
- DK `index.ts:191`: "This connection was registered with the older localhost callback. Add an account to register the new 127.0.0.1 callback."
- TP-T3: "Older development registrations used localhost, which cannot be changed on reauth."
- D-SI: "Keep the scheme, host, and path unchanged."

Persistence rules:
- Persist the issued `client_id` **before** redeeming the code (DK `oauth.ts:288-290`, `index.ts:204-225`).
- "Later sign-ins reuse the saved client ID." [D-OV]
- If the code exchange fails with `invalid_grant` after registration, start a fresh authorization with the issued id. Never re-register. [D-SI]; DK `index.ts:228-233`
- Never store `dynamic_agent_client` as a connection's client id (D-SI; DK `storage.ts` validation).

**Registration request** (browser GET; wrapped here for readability):

```
https://auth.openai.com/api/accounts/authorize
  ?client_id=dynamic_agent_client
  &agent_name_hint=Agentle
  &ext_agent_host_id=urn%3Auuid%3A<uuidv4>
  &response_type=code
  &redirect_uri=http%3A%2F%2F127.0.0.1%3A<port>%2Fauth%2Fcallback
  &scope=openid%20profile%20email%20offline_access%20resource.invoke%20chatgpt.tokens.use.direct
  &resource=https%3A%2F%2Fapi.openai.com%2Fv1
  &state=<b64url 32B>
  &nonce=<b64url 32B>
  &code_challenge=<b64url(SHA-256(verifier))>
  &code_challenge_method=S256
```

**Registration response** (302 to the loopback listener). This is the scope order documented on D-SI:

```
http://127.0.0.1:<port>/auth/callback
  ?code=<code>
  &scope=chatgpt.tokens.use.direct+email+offline_access+openid+profile+resource.invoke
  &state=<state>
  &client_id=oaiapp_<...>
```

### 2.3 Authorization request parameters

| Parameter | Value / rule | First registration | Re-auth | Source |
|---|---|---|---|---|
| `client_id` | `dynamic_agent_client`, or the saved issued id | `dynamic_agent_client` | issued id | D-SI |
| `agent_name_hint` | App display name; the user may edit it | yes | omit | D-SI; DK `oauth.ts:281` |
| `ext_agent_host_id` | "Required host identifier, distinct for each host." | yes | yes (same value) | D-SI, D-OV; D-CB "Set `sendHostId: true`" |
| `response_type` | `code` | yes | yes | D-SI |
| `redirect_uri` | `http://127.0.0.1:<port>/auth/callback`. Identical in the authorize and token requests. | yes | yes | D-SI |
| `scope` | `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct` | yes | yes | D-SI; DK `oauth.ts:11` |
| `resource` | `https://api.openai.com/v1` | yes | yes | D-SI |
| `state` | Fresh random value per attempt | yes | yes | D-SI |
| `nonce` | Fresh random value per attempt | yes | yes | D-SI |
| `code_challenge_method` | `S256` | yes | yes | D-SI |
| `code_challenge` | base64url (no padding) of SHA-256 of the verifier | yes | yes | D-SI |
| `login_hint` | Saved email; pre-selects the account | optional | recommended | D-SI; DK `oauth.ts:284` |
| `id_token_hint` | Saved ID token ("may be expired"); skips the account selector | do not use (see below) | optional | D-SI, D-PS |
| `prompt=consent` | Explicit re-enable of plan usage after the user asks | no | only on explicit user action | D-ER; DK `oauth.ts:285` |
| `force_reconsent=true` | Only "after OpenAI confirms deployment for your integration" | no | no | D-ER |

Notes:
- **`id_token_hint`.** The DevKit deliberately keeps saved tokens out of browser URLs: "Keep saved tokens out of browser URLs" (DK `oauth.ts:282`). On Android a Custom Tab URL can land in browser history. Use `login_hint` instead. If `id_token_hint` is ever used, "redact authorization URLs containing that hint from logs and diagnostics" [D-PS].
- **Forcing consent.** "Your app should not force consent on every ordinary sign-in." [D-ER]
- **`ext_agent_host_id` conflict.** The DevKit library defaults `sendHostId` to false ("Enable only when the authorization provider supports ext_agent_host_id", DK `types.ts:65`), and its Paste Perfect example does not set it. The docs and the cookbook say the parameter is required and tell you to set `sendHostId: true`. **Decision: always send it.**

### 2.4 PKCE, state, nonce

- **Generation.** The DevKit draws 32 random bytes each and base64url-encodes them (43 characters), giving `state`, `nonce` and `code_verifier` (DK `oauth.ts:13`, `261-263`).
- **Challenge.** `code_challenge = BASE64URL-NOPAD(SHA-256(ASCII(code_verifier)))`, per RFC 7636 and D-SI.
- **State.** Require exactly one `state` parameter and compare it in constant time. Unrelated or forged callbacks must not consume the pending attempt: answer `400` and keep listening (DK `oauth.ts:188-194`).
- **Nonce.** It must equal the ID token's `nonce` claim (D-SI; DK `oauth.ts:91-92`).
- **Freshness.** Generate new values for every attempt, including every retry (D-SI).

### 2.5 Callback handling (loopback listener)

Accept only requests that meet all of these (DK `oauth.ts:177-211`):
- Method is `GET`.
- `Host` header equals `127.0.0.1:<port>`.
- Path equals the fixed callback path (`/auth/callback`).
- No attempt has already settled for this listener.

Then process in this order:
1. **State.** Validate `state` as in section 2.4. On mismatch, return 400 and keep waiting.
2. **Error.** If `error` is present, stop and do not exchange. `error=access_denied` means the user declined [D-SI]. The DevKit message is "Sign-in was not completed. You can try again when you are ready." (DK `errors.ts:60`).
3. **Code.** Require exactly one `code`.
4. **Client id.**
   - New registration: require `client_id`, matching `^[A-Za-z0-9_-]{1,200}$`, not equal to `dynamic_agent_client`, expected prefix `oaiapp_`. If it is missing: "treat registration as incomplete" [D-SI].
   - Re-auth: `client_id` may be absent. If present, it must equal the saved id; otherwise do not replace the registration [D-SI].
5. **Persist** the issued `client_id` (pending registration), then redeem the code.

The callback also carries `scope`. Use the **token response** `scope` for decisions [D-SI].

An `iss` callback parameter (RFC 9207) is UNDOCUMENTED [D-IDX]. If one is present, require it to equal the issuer.

Response page: the DevKit returns a no-store HTML page "Return to your app" with a strict CSP and `Referrer-Policy: no-referrer`, then rewrites the URL (`history.replaceState`) to drop the code from the address bar (DK `oauth.ts:175-196`). Do the same.

### 2.6 Code exchange

```
POST https://auth.openai.com/api/accounts/oauth/token
Content-Type: application/x-www-form-urlencoded
Accept: application/json

grant_type=authorization_code&client_id=<issued>&code=<code>&code_verifier=<verifier>
&redirect_uri=<identical redirect_uri>&resource=https%3A%2F%2Fapi.openai.com%2Fv1
```

Sources: D-SI; DK `oauth.ts:291-298`.

Success is `200` JSON with these fields [D-SI] [D-TR]:
- `access_token`, `refresh_token`, `id_token`
- `token_type: "Bearer"`
- `expires_in` (3600)
- `scope` (space-separated)
- `earliest_refresh_at`

The meaning of `earliest_refresh_at` is UNDOCUMENTED. The DevKit accepts a string (ISO date) or a number (Unix seconds) (DK `oauth.ts:141`, `index.ts:100-101`).

Validation (DK `oauth.ts:125-147`, `299`):
- `token_type` equals "bearer", case-insensitive.
- `expires_in` is a finite number greater than 0.
- `access_token` is non-empty.
- `refresh_token` is present whenever `offline_access` was granted.
- `scope` is a string.
- `id_token` is present.

### 2.7 ID-token verification

Checks (D-SI; DK `oauth.ts:50-108`):
- RS256 signature against the JWKS (fetched from discovery `jwks_uri`).
- `iss == https://auth.openai.com`.
- `aud` contains the issued `client_id`.
- If `azp` is present, it must equal `client_id`. If `aud` has more than one value, `azp` must equal `client_id`.
- Required claims: `iss`, `aud`, `exp`, `iat`, `sub`. Allow 5 s of clock skew.
- `nonce` equals the value sent.

Identity:
- Account identity is `sub`. Use `email` and `name` only as labels (DK `index.ts:45`: "email is only a label").
- On re-auth the verified `sub` must equal the stored `sub`, otherwise `account_mismatch` [D-SI].
- TP-T3 says "Subjects are client-scoped". That is UNVERIFIED, so always compare `sub` within the same registration.

Outages:
- A JWKS or discovery outage is **not** an invalid identity. Return a retryable `identity_verification_unavailable` and preserve the connection (DK `oauth.ts:48-80`).

### 2.8 Plan-usage grant check

- "Check the token response's granted scopes for `chatgpt.tokens.use.direct` before proceeding to inference." [D-SI]
- "A valid ID token alone does not authorize ChatGPT plan usage." [D-SI]

If the scope is missing, keep the sign-in, disable plan usage, and offer an explicit choice: enable plan usage, or use another billing path [D-ER].

The DevKit's `sharing` flag is computed exactly this way (DK `index.ts:24`). TP-OC additionally requires `resource.invoke`. Recommendation: require both `resource.invoke` and `chatgpt.tokens.use.direct`.

### 2.9 Scopes

| Scope | Meaning | Source |
|---|---|---|
| `openid` | OIDC ID token | D-QS, D-SI |
| `profile` | Name and picture | D-QS, D-SI |
| `email` | Email | D-QS, D-SI |
| `offline_access` | Refresh token | D-SI |
| `resource.invoke` | Plan-interaction permission | D-SI |
| `chatgpt.tokens.use.direct` | Direct use of the plan for Responses requests | D-SI |

Notes:
- "Identity scopes don't grant access to ChatGPT conversations or OpenAI API resources." [D-QS]
- Apps receive "your name, email address, and profile picture", and not "your ChatGPT conversations or memories" or an "OpenAI API key" [H-PLAN].
- Obsolete preview spelling: `chatpass.enable.request.direct` (TP-OC). Do not send it.

### 2.10 Access token

- Lifetime: one hour, `expires_in: 3600` [D-TR].
- It is a JWT with these claims [D-TR]: `iss` (`https://auth.openai.com`), `aud` (`https://api.openai.com/v1`), `sub`, `client_id`, `scope`, `iat`, `exp`, `nbf`, `jti`, plus an opaque `https://api.openai.com/auth` object (`per_user_salt`, `encrypted_auth_metadata`).
- "Treat the OpenAI authentication metadata as opaque" [D-TR].
- Schedule refreshes from `expires_in`. Do not parse the token to make decisions.

### 2.11 Refresh

```
POST <token_endpoint>   (form-encoded)
grant_type=refresh_token&client_id=<issued>&refresh_token=<saved>&resource=https%3A%2F%2Fapi.openai.com%2Fv1
```

Omit `scope` [D-PS].

**When.**
- "Use standard OAuth refresh near access-token expiry." [D-PS] The DevKit refreshes when 60 s or less remain (DK `index.ts:99`).
- Honour `earliest_refresh_at`. If the token has expired but refresh is not yet allowed, return a retryable `refresh_not_ready` (DK `index.ts:100-127`). This is the DevKit's behaviour; the docs do not define the field.

**Result and rotation.**
- `200`: "a new access token and a replacement refresh token" [D-TR].
- The refresh token lives 30 days; each refresh issues a replacement "with a fresh 30-day lifetime" [D-TR].
- The response may omit `scope`: keep the previous scopes (DK `oauth.ts:338-339`).
- The response may omit `id_token`. If one is present, verify it; its `sub` must equal the stored `sub` (DK `oauth.ts:340-354`).

**Concurrency and persistence.**
- "Store and use the latest replacement, and serialize refreshes for the same session" [D-PS].
- Persist the rotated set durably **before** using it. The DevKit writes an encrypted `pendingRefresh` checkpoint before ID-token verification, so a JWKS outage or crash never loses the only valid refresh token (DK `oauth.ts:343-348`; `docs/security.md`).

**Terminal errors** clear the tokens and require a fresh OAuth run with the saved issued client id [D-ER]: `invalid_grant`, `invalid_refresh_token`, `token_expired`, `refresh_token_expired`, `refresh_token_invalidated`, `refresh_token_reused`. `invalid_client` means "Fix the client configuration." [D-ER]

**Network or 5xx failure:** "Do not erase credentials solely because of a temporary network or infrastructure failure." [D-ER]

### 2.12 Revocation, sign-out and remote disconnect

```
POST <revocation_endpoint>   (form-encoded)
token=<refresh_token>&token_type_hint=refresh_token&client_id=<issued>
```

Sources: D-PS; DK `oauth.ts:365-392`.

**Responses.**
- "An empty HTTP `200` is the revocation success response, including for an already-invalid token." [D-PS]
- "For a network failure or `5xx`, retry with backoff while the refresh token is still available." [D-PS] The DevKit tries twice, 300 ms apart, and revokes only the refresh token.

**Local cleanup.** Clear tokens locally whatever happens. Keep the account-to-client mapping and `ext_agent_host_id` [D-PS].

**When revocation cannot be confirmed**, tell the user to disconnect the app in ChatGPT Settings (DK: "Local credentials were removed, but remote disconnection could not be confirmed. Disconnect the app in ChatGPT Settings.").
- Help-center path: Settings > Security and login > Login connections > Sign in with ChatGPT > (app) > Manage connection > Disconnect [H-PLAN].

**Remote disconnect.** "OpenAI does not currently notify your tool when a user disconnects the app in ChatGPT settings. When a request or refresh confirms the disconnection, stop using the invalid token set and ask the user to sign in again." [D-ER]

### 2.13 `ext_agent_host_id`

Accepted formats [D-OV]:
- `urn:ietf:params:oauth:jwk-thumbprint:...` (RFC 9278; "Recommended")
- `urn:uuid:<UUIDv4>` ("Supported alternative")
- `did:key:<key>`

Rules [D-OV]:
- Choose and persist it before the first sign-in.
- It must be opaque: never an email or user id.
- "A host ID is not an authentication credential."
- "OpenAI does not verify possession of the private key as part of this flow."
- One issued client may serve several hosts of the same user and workspace. Each host needs its own id.

Android recommendation:
- Generate `urn:uuid:` + `UUID.randomUUID()` once per installation (DK `storage.ts:227-248` does exactly this).
- Store it in DataStore.
- **Exclude it from Auto Backup and device transfer** (see section 8.6), so a restored phone never shares a host id with the old one.
- A JWK-thumbprint id from a non-exportable AndroidKeyStore EC key is a valid later upgrade. It adds nothing functional today.

### 2.14 Accounts and profiles

Rules [D-PS]:
- Keep each registration's issued client id and credentials separate.
- "Never overwrite another account registration's credentials or combine one registration's client ID with another registration's tokens."
- Re-auth uses the selected account's `client_id` with fresh state, nonce and PKCE, and the same host id.

Recommendation:
- Agentle v1 should show **one** active ChatGPT connection.
- The storage model should still be a list of registrations (like the DevKit's `profiles` and `pendingRegistrations`), so "Use a different account or workspace" creates a new registration instead of overwriting.

---

## 3. Redirect URI rules and Android feasibility

### 3.1 What OpenAI documents

**Redirect format** [D-SI]:
- Only HTTP loopback on the IPv4 literal is allowed. Example: `http://127.0.0.1:1455/auth/callback`.
- "Later sign-ins may use another available port, such as `http://127.0.0.1:54321/auth/callback`; only the port may vary."
- "Do not substitute with `localhost`."
- The port is chosen per attempt, and the listener starts before the browser opens (D-SI; D-OV flow description).

**User agent.** "Open the system browser at `https://auth.openai.com/api/accounts/authorize`" [D-SI].

**Remote machines.** "A `127.0.0.1` callback reaches the computer running the browser, not the remote VM." [D-VM] On a phone, the browser and the app share the device, so the redirect reaches the app's own listener.

**Registered (non-dynamic) clients** use the "exact registered callback URL for each environment" [D-WS]. Those client ids go only to "a select group of commercial partners" via https://openai.com/form/sign-in-with-chatgpt-interest/ [D-RC].

**Not documented anywhere:** custom URL schemes, Android App Links, claimed https redirects for the dynamic flow, and any mention of mobile, Android or iOS. Full-text searches of `llms-full.txt` for mobile, Android, iOS, deep link, custom scheme and App Links found nothing [D-IDX]. **There is no prohibition either.**

### 3.2 RFC 8252 assessment

- **Section 7.3:** "Native apps that are able to open a port on the loopback network interface without needing special permissions (typically, those on desktop operating systems) can use the loopback interface to receive the OAuth redirect."
  - Android apps can bind `127.0.0.1` with only the normal, install-time `INTERNET` permission, so they meet the criterion.
  - "typically ... desktop" describes the usual case; it is not a restriction.
- **Section 8.3:** use the IP literal, not `localhost`; "Clients should listen on the loopback network interface only"; "The authorization server MUST allow any port to be specified at the time of the request for loopback IP redirect URIs." This matches D-SI ("only the port may vary").
- **Section 8.12:** "Native apps MUST NOT use embedded user-agents". So: **no WebView**. Use Custom Tabs or the default browser.
- **Appendix B.2** (Android) recommends Custom Tabs and lists private-use schemes and App Links as the usual receivers. Loopback is not listed for Android, but section 7.3 does not exclude it.

**Conclusion:** an in-process loopback listener plus a Custom Tab is a legitimate RFC 8252 section 7.3 flow on Android. Its only weakness is that OpenAI has not stated mobile support. **Status: permitted by the standard; UNVERIFIED with OpenAI.**

### 3.3 Android-specific constraints and risks

| # | Topic | Finding | Action |
|---|---|---|---|
| 1 | Auth Tab | "http schemes are not allowed with Auth Tab" [AUTHTAB]. | Use a plain Custom Tab (`androidx.browser:browser:1.10.0` [AX-BROWSER]). If no Custom Tabs browser exists, fall back to `ACTION_VIEW`. Never use WebView. |
| 2 | Local network permission | Android 17 enforces `ACCESS_LOCAL_NETWORK` for apps targeting API 37 [A17] [LNP]. LNP's local-network list (RFC1918, link-local, CGNAT, multicast and others) does not include loopback `127.0.0.0/8` (`pages/a16_target.txt`). | No local-network permission is needed. Bind only `127.0.0.1`. |
| 3 | Cross-profile loopback | "Beginning with Android 17, cross-profile loopback traffic is no longer permitted by default. Loopback traffic within the same profile is not affected." Applies to all apps on Android 17+ [A17]. | If Agentle runs in a work profile or Private Space and the browser runs in another profile, the redirect fails. Detect a timeout with no callback and explain this. Launch the Custom Tab from the same profile. |
| 4 | Chrome Local Network Access | Prompts apply to "`fetch()` API, subresource loading, and subframe navigation" (Chrome 142) [LNA]. Top-level navigations "remain a risk" and are not restricted yet (WICG explainer). | The 302 from `auth.openai.com` to `127.0.0.1` is a top-level navigation, so it works today. A future interstitial is possible: watch for it. Behaviour on Chrome for Android is UNVERIFIED; test on device. |
| 5 | Process death | While the Custom Tab is in front, Agentle's process is stopped and can be killed. The browser then hits a dead port and the issued `client_id` of a *new* registration is lost, leaving an orphan entry in the user's ChatGPT settings. | Keep the listener process-scoped (not Activity-scoped) with a 10-minute timeout (DK uses 10 min, TP-OC 5 min). Optionally host it in a `shortService` foreground service (about 3 minutes, notification required [FGS]). Tell the user how to remove orphan connections (section 2.12 path). |
| 6 | Port squatting and other local apps | Any app can connect to Agentle's loopback port or pre-bind a fixed port. | Use an ephemeral port (`new ServerSocket(0, 50, 127.0.0.1)`). With PKCE and `state`, an intercepted code is useless without the verifier. Reject wrong Host, path or method. Answer forged callbacks with 400 without consuming the attempt. |
| 7 | Speculative connections | Browsers open idle spare connections, so a one-shot `accept()` can hang on an empty socket (TP-PI comment). | Use an accept loop with per-connection read timeouts. Close all connections when done. |
| 8 | Returning to the app | Android limits background activity starts, and Auth Tab cannot capture `http` redirects. | Serve a "Return to Agentle" page with a button linking to `intent://siwc-done#Intent;scheme=agentle;package=<applicationId>;end` (no secrets in it). Also re-check state in `onResume`. An automatic 302 to that intent is UNVERIFIED; test it. |
| 9 | Cleartext | The browser, not the app, makes the `http://127.0.0.1` request. The app's network security config can keep cleartext disabled for its own outbound traffic. | Only instrumented tests that talk to MockWebServer over http need a debug-only `127.0.0.1` cleartext exception. |
| 10 | Chrome "HTTPS by default" | Web-search result titles (Google Security Blog post https://security.googleblog.com/2025/10/https-by-default.html, plus press coverage) report that Chrome 154 (October 2026) turns on "Always Use Secure Connections" by default and warns before insecure public HTTP sites. The post itself could not be read: the egress proxy blocks it. Treatment of `http://127.0.0.1` is UNVERIFIED; loopback is normally a potentially trustworthy origin. | Test the redirect on Chrome 154+ for Android, including the "warn for private sites too" setting. If an interstitial appears, explain it in the "Return to Agentle" troubleshooting text. |

---

## 4. Inference with ChatGPT plan usage

### 4.1 Model discovery

```
GET https://api.openai.com/v1/models
Authorization: Bearer <access_token>
```

Source: D-MI.

The response is `{"models":[{"slug":"...","display_name":"...","visibility":"list"}, ...]}` [D-MI]. **This is not the API-key `{"object":"list","data":[...]}` shape.**

Rules:
- Show only `visibility == "list"` and keep the server's order (D-MI; DK `models.ts`).
- "Refresh these choices when the user switches ChatGPT accounts." [D-MI]
- The example model in the docs is `gpt-6.1-sol` [D-MI]. Availability depends on the account's catalog.

### 4.2 Responses request

```
POST https://api.openai.com/v1/responses
Authorization: Bearer <access_token>
Content-Type: application/json
Accept: text/event-stream        (DevKit sends this; docs do not require it)

{"model":"<slug>","input":[{"role":"user","content":"..."}],"instructions":"...","store":false,"stream":true}
```

Requirements:
- "Set `store: false` and `stream: true`. Send `input` as an array containing the context needed for each request." [D-PL]
- "Use `instructions` or developer messages; explicit `{type: "message", role: "system"}` items are rejected." [D-PL]
- The DevKit allows only the roles `user`, `assistant` and `developer` (DK `responses.ts:9-14`).
- The OpenAI Python example sets `max_retries=0` [D-MI]. Disable automatic client retries and apply the bounded policy in section 8.

### 4.3 Stream handling

| Event | Action | Source |
|---|---|---|
| `response.output_text.delta` | Append `delta` | D-MI; OAS `ResponseTextDeltaEvent` |
| `response.completed` | The only success signal: "Treat inference as successful only after receiving `response.completed`." | D-MI |
| `response.failed` | Read `response.error.code`. `subscription_sharing_usage_limit_exceeded` or `..._usage_unavailable` "can arrive mid-stream as `response.failed`" | D-MI |
| `response.incomplete` | Handle separately from success | D-MI |
| `error` | `{type, code, message, param, sequence_number}` | OAS `ResponseErrorEvent`; DK `responses.ts:61-63` |
| Stream ends without `completed` | Interrupted. Retryable, partial text kept apart | D-MI; DK `responses.ts:105` |

Parser rules:
- Parse SSE by the `data:` lines and dispatch on the JSON `type`.
- Ignore `[DONE]`.
- Accept LF, CRLF and CR line endings.
- Cap event size (DevKit: 4 MiB per event, 16 MiB of text).
- Tolerate a **missing `Content-Type`**: "The direct route can return valid SSE without Content-Type" (DK `responses.ts:34-36`). If a Content-Type is present and it is not `text/event-stream`, fail.

### 4.4 Capability table (direct route, as of 2026-10-01)

| Capability | Status | Evidence / notes |
|---|---|---|
| Text output (streamed Responses) | **SUPPORTED** | D-MI, D-PL |
| Non-streaming (`stream:false`) | **UNSUPPORTED** | "Set ... `stream: true`" [D-PL] |
| Stored responses (`store:true`), `previous_response_id` over HTTP, `conversation` | **UNSUPPORTED** | D-PL. Resend history in `input`. |
| `instructions` / developer messages | **SUPPORTED** | D-PL. System-role message items are rejected. |
| Structured outputs (`text.format` `{type:"json_schema",name,schema,strict}`, OAS `TextResponseFormatJsonSchema`) | **UNDOCUMENTED** | No mention on any SIWC page [D-IDX]. TP-OC passes `text.format json_object` through (UNVERIFIED). Probe once per model. On `subscription_sharing_unsupported_capability` with `param` `text...`, switch to "JSON in plain text plus local schema validation". |
| Function tools | **SUPPORTED, with a constraint** | "Group function/custom tools in namespaces or supply them through `additional_tools` input items." [D-PL] Shapes: OAS `NamespaceToolParam {type:"namespace",name,description,tools:[function or custom]}`; `AdditionalToolsItemParam {type:"additional_tools",role:"developer",tools:[...]}`. A plain top-level `{"type":"function"}` is UNDOCUMENTED (TP-OC sends it). TP-T3 has seen `unsupported_capability` errors naming "tool 'namespace'" and "additional_tools". Verify live. |
| `programmatic_tool_calling`, `tool_search` | **UNSUPPORTED** | D-PL |
| Web search tool | **Policy-dependent** | "Web search remains subject to model and account/workspace policy." [D-PL] |
| Image input (`input_image`, base64 data URL) | **SUPPORTED when the model accepts it** | "Text, images, and files are supported when the selected model accepts them." [D-PL] Inline the image as a data URL (OAS `InputImageContent.image_url`), because the Files upload API is unsupported. |
| File input (`input_file` with `file_data`) | **SUPPORTED when the model accepts it; inline only** | D-PL. `file_id` needs the unsupported Files API. |
| Files upload API | **UNSUPPORTED** | D-PL |
| Image generation (tool or Images API) | **UNSUPPORTED** | D-PL |
| Audio input, transcription API | **UNSUPPORTED** | "Audio/video input, the Files upload API, and the transcription API are not supported by this flow." [D-PL] |
| Audio output / TTS | **UNSUPPORTED (by route)** | Only `POST /v1/responses` is documented; other routes return `subscription_sharing_route_not_supported` [D-ER]. TP-OC: "SIWC credentials do not authorize image generation, audio transcription, speech synthesis, or memory embeddings." |
| Video (input or generation) | **UNSUPPORTED** | Video input [D-PL]; generation routes are not documented [D-ER]. |
| Background mode (`background`) | **UNSUPPORTED** | Listed field to omit [D-PL] |
| File search, Code Interpreter, computer use, hosted MCP/connectors | **UNSUPPORTED** | D-PL |
| Embeddings, Chat Completions, Realtime, Batch, Moderations | **UNSUPPORTED (by route)** | Only `POST /v1/responses` and `GET /v1/models` are documented. "Use `POST /v1/responses`" [D-ER] |
| WebSocket transport | **UNDOCUMENTED for Android** | "WebSocket continuation can reference only responses from the same authenticated connection" [D-PL]. Use HTTP SSE. |
| `service_tier` override | **UNSUPPORTED** | The `unsupported_capability` action names "service-tier override" [D-ER]. Do not send it. |
| `reasoning`, `include`, `tool_choice`, `parallel_tool_calls`, `text.verbosity` | **UNDOCUMENTED** | Not mentioned [D-IDX]. Omit in v1. |

**Unsupported request fields** (exact list): "Omit `background`, `conversation`, `max_output_tokens`, `max_tool_calls`, `metadata`, `moderation`, `multi_agent`, `prompt`, `prompt_cache_retention`, `safety_identifier`, `temperature`, `top_logprobs`, `top_p`, `truncation`, and `user`." Also omit `previous_response_id` over HTTP. [D-PL]

**Consequence:** there is no `max_output_tokens`. Control length through `instructions` and a small input, and treat `response.incomplete` as "retry with less context".

### 4.5 Request template for Agentle (JITAI text)

```json
{
  "model": "<slug chosen from /v1/models>",
  "instructions": "You are a wellbeing coach... Reply with JSON {\"title\":..,\"body\":..,\"tone\":..} only.",
  "input": [
    {"role": "developer", "content": "Minimized context: sleep_h=5.9 (7d avg 7.1); steps_today=1200; ..."},
    {"role": "user", "content": "Suggest one 2-minute intervention for now."}
  ],
  "store": false,
  "stream": true
}
```

Only user-selected, minimized features go into `input`. No raw records, no identifiers.

---

## 5. Eligibility, limits and approval

### 5.1 Plans

- "Eligible ChatGPT Plus and Pro users can use their ChatGPT plan for AI requests in participating apps" [D-QS].
- "the option to use your ChatGPT plan is only available with Plus and Pro." [H-PLAN]
- Identity-only SIWC is "Available globally to authenticated ChatGPT users, including users in Enterprise organizations" [H-SIWC].
- **Free, Go, Business, Enterprise and Edu plans cannot fund requests.** A workspace user may receive `subscription_sharing_user_not_eligible`.

### 5.2 App categories

- "ChatGPT plan usage is available to all open-source partners and selected private clients." [D-QS]
- "ChatGPT plan usage is available to open-source projects, personal projects that run locally, and selected private apps" [D-CB].
- "The ChatGPT plan usage integration described here is available for open-source tools and personal projects that run locally. If you're building a paid or remotely hosted app, join the waitlist to request access before offering it to users." [D-CB]
- "If you're interested in offering it in a paid or remotely hosted app, complete the interest form" (https://openai.com/form/sign-in-with-chatgpt-interest/) [D-OV].
- Identity-only website sign-in "is currently available to selected commercial partners through a limited trial." [D-QS]

### 5.3 Registration and approval

- No pre-registration is documented for the dynamic flow: the client is created at first sign-in [D-SI].
- Server-side gating still exists. The DevKit maps a legacy `subscription_sharing_v2_client_not_enabled` to "This app is not enabled for token sharing. Contact the app developer." (DK `errors.ts:138`).
- TP-OC's docs say "Your account and workspace must have SIWC registration and token sharing enabled by OpenAI" (third-party, UNVERIFIED).

### 5.4 Usage limits

- Usage counts toward "ChatGPT Work and Codex usage included in your plan" [H-PLAN].
- "For ChatGPT Plus users, the five-hour usage limit is shared across all apps where they use their ChatGPT plan, including private and open-source clients." [D-PS]
- "Usage in one app contributes to the same five-hour usage total, and no app receives a separate allowance." [D-PS]
- "The five-hour usage limit does not apply for Pro users." [D-PS]
- Users can set each app's weekly limit "from 10% to 100%" ("a cap, not a separate pool of usage") in Settings > Usage [D-PS illustration] [H-PLAN].
- "OpenAI does not silently switch the request to another billing path." [D-ER]
- No rule restricts scheduled, background or unattended requests, and request frequency is not limited by rule (UNDOCUMENTED [D-IDX]). A JITAI worker that spends a Plus user's shared five-hour allowance in the background would be a poor experience, so keep a local budget (section 8.5).
- Apps can draw on credits only if the user "explicitly allowed apps to use credits" [H-PLAN].
- There is no usage API: "Link from your tool's usage-tracking pages to ChatGPT Settings → Usage" [D-PS].
- No rate-limit headers or `Retry-After` are documented [D-IDX].

### 5.5 Error catalog

The HTTP status is the documented one. "—" means the docs give none. RFC 6749 section 5.2 implies 400 (401 for `invalid_client`), but the real status is UNVERIFIED, so classify OAuth errors by the `error` string, never by status.

**Callback errors**

| Code | Status | Where | Meaning | Recovery | Source |
|---|---|---|---|---|---|
| `access_denied` | (redirect) | callback | User declined | Do not exchange; offer retry | D-SI; DK |

**Admission errors before the stream opens.** The body may be `{"detail":"..."}`.

| Code | Status | Where | Meaning | Recovery | Source |
|---|---|---|---|---|---|
| (none) | 401 | before stream | "The required signed identity or direct permission was not accepted." | "Check the selected ChatGPT account and granted scopes." | D-ER |
| (none) | 403 | before stream | "A policy or permission check, such as the permitted serving region, prevented admission." | "Surface the restriction and verify the integration." | D-ER |
| (none) | 503 | before stream | "Direct routing is unavailable or not enabled." | "Preserve credentials and use bounded backoff for temporary failures." | D-ER |

**Structured Responses errors** (`error.code`, `error.param`)

| Code | Status | Where | Meaning | Recovery | Source |
|---|---|---|---|---|---|
| `subscription_sharing_user_not_eligible` | 403 | Responses | "ChatGPT plan usage is unavailable for the selected user, workspace, or policy." | Explain. Do not repeat the request or loop OAuth. | D-ER |
| `subscription_sharing_usage_limit_exceeded` | 429 (also mid-stream `response.failed`) | Responses | Plan or app limit reached | "Pause new requests that use the user's ChatGPT plan and link to ChatGPT settings → Usage." Do not "infer a reset time from this code alone; an app-specific limit can also apply." | D-ER, D-MI |
| `subscription_sharing_usage_unavailable` | 503 (also mid-stream) | Responses | "Usage availability could not be checked." | Preserve credentials; bounded backoff | D-ER, D-MI |
| `subscription_sharing_unsupported_capability` | 400 | Responses | Unsupported input, tool, execution feature, model or service-tier override | "Inspect `error.param` and remove the unsupported ..."; do not retry the same body | D-ER |
| `subscription_sharing_route_not_supported` | 403 | any non-supported route | Wrong method or endpoint | "Check the exact HTTP method and endpoint." Use `POST /v1/responses` | D-ER |
| `subscription_sharing_invalid_user` | 401 | Responses | "The subscriber context could not be validated." | Keep the request id. "Ask the user to sign in again after confirmed revocation or terminal refresh error." | D-ER |
| `subscription_sharing_user_unavailable` | 503 | Responses | "User/workspace information is temporarily unavailable." | Bounded backoff | D-ER |
| `chatpass_v2_scope_not_authorized` | 403 | Responses | "The signed permission context does not authorize the operation." | Check client and grant config; no retry, no billing change | D-ER |
| `chatpass_v2_invalid_authorization_context` | 403 | Responses | Same as above | Same as above | D-ER |
| `model_not_found` | — | Responses | Model not available to this connection | Choose a listed model | DK `errors.ts:61` |

**Token endpoint errors**

| Code | Status | Where | Meaning | Recovery | Source |
|---|---|---|---|---|---|
| `invalid_grant` | — | token (exchange) | Bad, expired or reused code; PKCE or redirect mismatch | Restart authorization with the issued client id; never re-register | D-SI |
| `invalid_grant`, `invalid_refresh_token`, `token_expired`, `refresh_token_expired`, `refresh_token_invalidated`, `refresh_token_reused` | — | token (refresh) | Unusable refresh token | Clear tokens; OAuth again with the saved client id | D-ER |
| `invalid_client` | — | token | Client configuration rejected | "Fix the client configuration." | D-ER |

**Legacy codes** (DevKit compatibility, not in the docs): `subscription_sharing_v2_user_not_eligible`, `subscription_sharing_v2_route_not_supported`, `subscription_sharing_v2_invalid_user`, `subscription_sharing_v2_user_unavailable`, `subscription_sharing_v2_client_not_enabled` (DK `errors.ts:65-70`, `138`). Map each to its successor.

**Diagnostics:** "Preserve the actual HTTP status, response body shape, error code when present, and request ID." [D-ER] The DevKit reads `x-request-id` and never logs server messages, which can echo input (DK `errors.ts:103-145`).

---

## 6. Terms, policy and branding for a personal Android app

**Distribution**
- "Review OpenAI's terms and policies before distributing your integration." (https://openai.com/policies/) [D-CB]
- Personal, locally run use is in scope. Paid or remote distribution needs the waitlist (section 5.2).

**Terms that apply to the user's account**
- The user's own OpenAI Terms of Use (https://openai.com/policies/terms-of-use/) and Privacy Policy.
- The Usage Policies, effective 2025-10-29 [POL]. Relevant clauses for Agentle:
  - No "tailored advice that requires a license, such as legal or medical advice, without appropriate involvement by a licensed professional".
  - No "automation of high-stakes decisions in sensitive areas without human review" (medical included).
  - No "disordered eating promotion or facilitation".
  - Protections for minors.
- Agentle prompts must therefore produce wellbeing nudges, not diagnoses or treatment.

**Data handling**
- `store:false` is mandatory [D-PL].
- Whether plan-usage requests follow the user's ChatGPT data controls, or are used for training, is **UNDOCUMENTED** (no SIWC page or help article says).
- Agentle must disclose before first use that "selected, minimized context is sent to OpenAI using your ChatGPT account", and let the user preview it.

**DevKit licence**
- The "Sign-in with ChatGPT DevKit Noncommercial License v1.0" allows use only for "Noncommercial Purpose".
- It grants "no rights to OpenAI names, trademarks, logos, or branded visual assets" and "no access to ChatGPT, the OpenAI API, or any other OpenAI service" [DK-LIC].
- Agentle must be an **independent Kotlin implementation**. Do not port DevKit code or ship DevKit files. Independently written software that only talks to the service is not a Modified Work.

**Brand** [BRAND]
- "We do not permit model names in app titles". Never name the app or features "...GPT".
- "Do not feature our Marks more prominently than your own company's name or marks."
- Do not imply endorsement.
- Do not alter the wordmark or the Blossom.

**SIWC UI requirements**
- Button label "Continue with ChatGPT", placed "with your other sign-in options, give it comparable visual prominence, and use approved OpenAI branding." [D-QS]
- Four approved formats: "Continue with ChatGPT" or "Sign in with ChatGPT", each on a black or white background with the ChatGPT logo [D-WS].
- First sign-in only: modal "You're using your ChatGPT plan", button "Got it" [D-UX].
- Settings card "Use your ChatGPT plan", with text "Complete eligible AI requests in this app with usage included in your ChatGPT plan or credits balance." and a "Continue with ChatGPT" action [D-UX].
- Indicator "Using ChatGPT plan" near the composer or model selector, plus a "Manage usage" link to https://chatgpt.com/settings/usage [D-UX].
- On a limit: "Usage limit reached", with primary action "Manage usage" (secondary "Buy app credits" only if the app sells credits; Agentle does not) [D-UX].
- No guidance exists for "not eligible" or temporarily unavailable states, nor for mobile layouts (UNDOCUMENTED). Section 8 proposes copy.

---

## 7. Impersonation: never reuse another product's OAuth client

- Agentle must obtain its **own** client id: either through the documented dynamic registration (`dynamic_agent_client` → issued `oaiapp_...`), or through an OpenAI-issued client id after the interest form.
- Using any other product's registered client id would present Agentle to OpenAI and to the user as that product. Examples: the Codex CLI client `app_EMoamEEZ73f0CkXaXp7hrann`, which some open-source tools embed (TP-OCODE `codex.ts:10`, also TP-OC `openai-chatgpt-oauth-*.ts`).
- That is impersonation. It misrepresents the relationship with OpenAI [BRAND], bypasses OpenAI's per-client consent, limits and revocation, and **must not be done**.
- The same applies to private, undocumented endpoints such as `https://chatgpt.com/backend-api/codex/responses` (TP-OCODE `codex.ts:12`), and to copying another app's `agent_name_hint` or redirect port to look like it.
- **Guard test:** a unit test should fail the build if the source tree contains a hard-coded OAuth client id other than `dynamic_agent_client`. Check at least the pattern `app_[A-Za-z0-9]{20,}` and the host `chatgpt.com/backend-api`.

---

## 8. App state mapping and recommended Android implementation

### 8.1 States

The persisted states are: `CONNECTED`, `DISCONNECTED`, `NOT_ELIGIBLE`, `PLAN_USAGE_UNAVAILABLE`, `RATE_LIMITED`, `REAUTH_REQUIRED`, `SERVER_ERROR`, `NETWORK_UNAVAILABLE`.
- Each carries a `reason` enum and an optional `requestId`.
- `Connecting` is a transient UI overlay, not a persisted state.
- `NOT_ELIGIBLE` covers every case where the plan cannot fund requests until something changes; `reason` picks the action.

**Sign-in and token signals**

| Signal | State (reason) | Credentials | Retry / UI action |
|---|---|---|---|
| Exchange OK, ID token valid, both plan scopes granted | `CONNECTED` | Store | Show first-sign-in modal once |
| Exchange OK but `chatgpt.tokens.use.direct` missing | `NOT_ELIGIBLE` (PLAN_USAGE_NOT_GRANTED) | Keep identity tokens | "Use your ChatGPT plan" → Continue with ChatGPT (`prompt=consent`) |
| Callback `error=access_denied`, or user closes the tab or times out | unchanged (`DISCONNECTED` or `REAUTH_REQUIRED`) | None | "Sign-in was not completed"; retry button |
| Callback with wrong `state` | unchanged | None | 400 page; keep waiting until timeout |
| New registration without `client_id`, or mismatched `client_id` | unchanged + error (REGISTRATION_INCOMPLETE / CONNECTION_CHANGED) | None | Retry sign-in |
| Exchange `invalid_grant` | unchanged | Keep issued client id | Auto-restart authorization once with the issued id |
| `invalid_client` (exchange or refresh) | `REAUTH_REQUIRED` (REGISTRATION_INVALID) | Clear tokens; mark registration unusable | Offer a new registration. UNVERIFIED: the docs only say "Fix the client configuration". |
| ID token invalid at sign-in | unchanged + error (INVALID_ID_TOKEN) | Discard | Retry |
| ID token `sub` differs from the saved account | unchanged + error (ACCOUNT_MISMATCH) | Discard | "Choose the original account or add a separate connection" |
| JWKS or discovery unavailable | `SERVER_ERROR` (IDENTITY_VERIFICATION_UNAVAILABLE) | Keep, including the pending rotation | Bounded backoff |
| Refresh 200 | `CONNECTED` | Persist rotation atomically before use | — |
| Refresh terminal code (section 5.5) | `REAUTH_REQUIRED` (REFRESH_REJECTED) | Clear tokens; keep client id, `sub` and host id | Reconnect banner; one notification per day from background |
| Refresh I/O error | `NETWORK_UNAVAILABLE` | Keep | Retry when the network returns |
| Refresh 5xx | `SERVER_ERROR` (AUTH_SERVER) | Keep | Bounded backoff |
| Token expired and `earliest_refresh_at` in the future | `SERVER_ERROR` (REFRESH_NOT_READY) | Keep | Retry at `earliest_refresh_at` |

**API signals**

| Signal | State (reason) | Credentials | Retry / UI action |
|---|---|---|---|
| API 401, any body | (try once) | Force one refresh, then retry once. If `earliest_refresh_at` is still in the future, do not force: surface `SERVER_ERROR` (REFRESH_NOT_READY). | If the refresh fails terminally, see the refresh rows. If the retry is 401 again: `REAUTH_REQUIRED` (CREDENTIAL_REJECTED), keep tokens until the user reconnects. |
| 403 `subscription_sharing_user_not_eligible` | `NOT_ELIGIBLE` (ACCOUNT_NOT_ELIGIBLE) | Keep | No retry. "Your ChatGPT plan can't be used here (Plus or Pro required, or a workspace or policy restriction)." |
| 403 `chatpass_v2_*` | `NOT_ELIGIBLE` (GRANT_NOT_AUTHORIZED) | Keep | Offer re-consent once; no retry |
| Legacy `subscription_sharing_v2_client_not_enabled` (DK `errors.ts:138`) | `NOT_ELIGIBLE` (CLIENT_NOT_ENABLED) | Keep | No retry. "Plan usage isn't enabled for this app yet." |
| 403 `{"detail"}` or another 403 | `NOT_ELIGIBLE` (POLICY_RESTRICTED) | Keep | Show the restriction (for example region) |
| 403 `subscription_sharing_route_not_supported` | `SERVER_ERROR` (APP_BUG) | Keep | No retry; log |
| 429 `subscription_sharing_usage_limit_exceeded`, pre-stream or mid-stream | `RATE_LIMITED` (PLAN_LIMIT) | Keep | Pause plan-funded jobs; no auto-retry; "Usage limit reached" with "Manage usage". Background JITAIs fall back to local templates. |
| 429 with another code | `RATE_LIMITED` (TOO_MANY_REQUESTS) | Keep | Exponential backoff; honour `Retry-After` if present (UNVERIFIED that it is sent) |
| 503 `subscription_sharing_usage_unavailable` or `..._user_unavailable`, or mid-stream | `PLAN_USAGE_UNAVAILABLE` | Keep | Bounded backoff, for example 30 s, 2 min, 10 min, then manual |
| 503 `{"detail"}` (direct routing unavailable or not enabled) | `PLAN_USAGE_UNAVAILABLE` (ROUTING) | Keep | Bounded backoff. If still failing after 24 h: "may not be enabled for this app" |
| 400 `subscription_sharing_unsupported_capability` | `SERVER_ERROR` (UNSUPPORTED_CAPABILITY, `param`) | Keep | Never retry the same body; feature-flag off (for example `json_schema`) |
| Other 400/422, `model_not_found` | `SERVER_ERROR` (INVALID_REQUEST / MODEL_UNAVAILABLE) | Keep | Reload models; pick another |
| 5xx (other) | `SERVER_ERROR` (UPSTREAM) | Keep | Bounded backoff |
| DNS, connect or TLS failure; timeout before headers | `NETWORK_UNAVAILABLE` | Keep | Retry on connectivity (WorkManager `NetworkType.CONNECTED`) |
| Stream ends without `response.completed` | `SERVER_ERROR` (STREAM_INTERRUPTED) | Keep | Discard partial text for JITAIs; retry once |
| `response.incomplete` | `SERVER_ERROR` (INCOMPLETE) | Keep | Retry with less context |

**Local signals**

| Signal | State (reason) | Credentials | Retry / UI action |
|---|---|---|---|
| User taps Disconnect | `DISCONNECTED` | Revoke the refresh token; clear tokens; keep client id and host id | If revocation is unconfirmed, show the Settings path from section 2.12 |
| Keystore key lost or ciphertext unreadable (restore or new device) | `REAUTH_REQUIRED` (LOCAL_CREDENTIALS_UNREADABLE) | Wipe the unreadable blob | Reconnect |

### 8.2 Components (Kotlin, Hilt singletons)

- **`SiwcConfig`**
  - Constants from section 2.1, `appName = "Agentle"`, `callbackPath = "/auth/callback"`.
  - `authBaseUrl` and `apiBaseUrl` are **injectable**, so tests can point them at a fake.
  - Release builds must assert the production values.
- **`HostIdStore`**
  - Creates `urn:uuid:<v4>` once (DataStore, excluded from backup).
- **`SiwcDiscovery`**
  - Fetches and validates discovery (section 2.1) and caches it in memory.
  - Fetches JWKS with a cache, refetching on an unknown `kid`.
- **`LoopbackCallbackServer`**
  - Hand-rolled HTTP/1.1 listener on `127.0.0.1:0`, about 150 lines, auditable.
  - Accept loop with per-connection read timeouts; request line ≤ 8 KiB; GET only.
  - Exact path and Host checks; constant-time `state` check; single settle.
  - Strict response headers (`Cache-Control: no-store`, `Referrer-Policy: no-referrer`, CSP).
  - Returns `CallbackResult(code, clientId?, grantedScopeHint)` or `CallbackError(error)`.
- **`SiwcAuthorizer`**
  - Builds the authorize URL (section 2.3) and launches a `CustomTabsIntent` (fallback `ACTION_VIEW`).
  - Awaits the callback with a 10-minute timeout.
  - Persists the issued client id, exchanges the code, verifies the ID token with Nimbus JOSE+JWT (`com.nimbusds:nimbus-jose-jwt`, RS256 only) and checks scopes.
- **`SiwcCredentialStore`**
  - One encrypted blob holding registrations, tokens, `earliest_refresh_at`, scopes, `sub`, label and the pending rotation.
  - Encrypted with AES-256-GCM using a non-exportable **Android Keystore** key, *without* user-authentication requirements, so background refresh works.
  - Written through DataStore (atomic replace). Do not use the deprecated `security-crypto` [AX-SEC]. Tink (`tink-android` 1.23.0) is an acceptable alternative.
  - Excluded from backup.
- **`SiwcSessionManager`**
  - `suspend fun <T> withAccessToken(block: suspend (String) -> T): T` under a `Mutex`.
  - Refreshes at 60 s or less to expiry or after a 401 (once), honouring `earliest_refresh_at`.
  - Persists the rotation before use.
  - Publishes `StateFlow<SiwcState>`.
- **`ModelCatalogRepository`**
  - `GET /v1/models`; filters `visibility=="list"`; keeps server order; cached per client id; refreshed on account switch.
- **`ResponsesClient`**
  - OkHttp 5.x with `okhttp-sse` or a manual SSE parser (section 4.3).
  - Applies the request whitelist from section 4.4. It must refuse to send forbidden fields.
- **`SiwcErrorMapper`**
  - Pure function: (HTTP status, body, stream event, phase) → state and reason (section 8.1).
  - Parses both OAuth `{"error":"x"}` and API `{"error":{"code":"x"}}` and `{"detail":"..."}`, and recognizes the legacy `v2` codes.

### 8.3 OkHttp settings

- **Auth client** (token and revocation endpoints):
  - `followRedirects(false)` (the DevKit uses `redirect:"error"`).
  - **`retryOnConnectionFailure(false)`**, so a silently replayed refresh POST can never trip `refresh_token_reused`.
  - Timeouts: connect 15 s, call 30 s.
- **API client**:
  - No automatic retries (the docs' example uses `max_retries=0`).
  - `callTimeout` 180 s for streams (DevKit value), `readTimeout` 60 s between events.
  - Send `Accept: text/event-stream`.
- System CAs only; no certificate pinning.

### 8.4 Sign-in sequence on Android

1. The UI calls `signIn(profile?)`.
2. Prepare: ensure the host id, load discovery, create state, nonce and PKCE.
3. Start `LoopbackCallbackServer` → port.
4. Build the URL and launch the Custom Tab.
5. In the browser, the user authenticates and consents (and may edit the app name).
6. The 302 reaches `http://127.0.0.1:port/auth/callback?...`. The server validates it and responds with the "Return to Agentle" page.
7. Persist the issued `client_id` as a pending registration.
8. `POST` the token request.
9. Verify the ID token and check scopes.
10. Store everything encrypted, then promote it to the active registration.
11. Close the listener and emit `CONNECTED`, or `NOT_ELIGIBLE(PLAN_USAGE_NOT_GRANTED)`.
12. The user taps "Return to Agentle" (`intent:` with the package), or closes the tab. `onResume` re-reads the state.

### 8.5 Background (WorkManager) behaviour

- JITAI workers call `withAccessToken`. Refresh works without UI.
- Sign-in never starts from the background.
- On `REAUTH_REQUIRED`, `NOT_ELIGIBLE` or `RATE_LIMITED`: do not call the API; deliver a non-AI fallback intervention; post at most one "Reconnect ChatGPT" or "Usage limit reached" notification per day.
- Enforce a local daily budget for plan-funded generations (user-configurable), because usage draws from the user's Work/Codex allowance [H-PLAN].

### 8.6 Backup and multi-profile

Exclude these from `android:dataExtractionRules` (cloud backup and device transfer) and from `fullBackupContent`:
- the credential DataStore file;
- the host-id DataStore file.

Reasons: Keystore keys do not transfer, host ids must stay unique per host [D-OV], and a restored refresh token would race the original.

Document the Android 17 cross-profile loopback limitation (section 3.3, item 3).

---

## 9. Fake-server contract for tests

### 9.1 Topology

- One `mockwebserver3.MockWebServer` (OkHttp 5.5.0 [MVN]) with a `Dispatcher` serves both "hosts":
  - Inject `authBaseUrl = http://127.0.0.1:<mock>/auth`.
  - Inject `apiBaseUrl = http://127.0.0.1:<mock>/api/v1`.
  - The fake discovery `issuer` equals `authBaseUrl` exactly.
- A test-only scenario switch is set by the test (`fake.scenario = Scenario.X`), not by request content.
- RS256 test key pair: `kid = "fake-key-1"`.
- Constants: `CLIENT = oaiapp_fakeA1`, `SUB = user_fake_sub_1`.
- **Browser simulation.** The test plays the browser, so no device or emulator is needed (JVM or Robolectric):
  1. Capture the URL the app hands to its `BrowserLauncher` interface. Production uses Custom Tabs; tests use a fake launcher.
  2. Send it with `followRedirects=false` to the fake authorize endpoint.
  3. Read the `Location` header and send that GET to the app's real `LoopbackCallbackServer`.
- Fakes must assert every field the real server documents. They must also **reject** the forbidden fields from section 4.4, so the client's whitelist stays honest.

### 9.2 Endpoints

**`GET {auth}/.well-known/openid-configuration`** → `200`:

```json
{"issuer":"{auth}","authorization_endpoint":"{auth}/api/accounts/authorize","token_endpoint":"{auth}/api/accounts/oauth/token",
 "jwks_uri":"{auth}/.well-known/jwks.json","revocation_endpoint":"{auth}/api/accounts/oauth/revoke",
 "response_types_supported":["code"],"code_challenge_methods_supported":["S256"],
 "token_endpoint_auth_methods_supported":["none"],"id_token_signing_alg_values_supported":["RS256"]}
```

The revocation path is fake: the real one must be read from discovery. Variants:
- `issuer` mismatch → client must fail closed (`discovery_failed`).
- An endpoint on a foreign origin → fail closed.
- `revocation_endpoint` absent → disconnect reports "revocation unconfirmed".

**`GET {auth}/.well-known/jwks.json`** → `200 {"keys":[{"kty":"RSA","kid":"fake-key-1","use":"sig","alg":"RS256","n":"<b64url>","e":"AQAB"}]}`. Outage variants:
- `503`
- `{"keys":[]}`
- unknown `kid`
- malformed JSON

Every outage variant must map to IDENTITY_VERIFICATION_UNAVAILABLE and keep credentials.

**`GET {auth}/api/accounts/authorize?...`**

Required: `client_id`, `response_type=code`, `redirect_uri`, `scope` ⊇ `openid`, `resource=https://api.openai.com/v1`, `state`, `nonce`, `code_challenge`, `code_challenge_method=S256`, `ext_agent_host_id` matching `^(urn:uuid:[0-9a-f-]{36}|urn:ietf:params:oauth:jwk-thumbprint:.+|did:key:.+)$`.

`redirect_uri` must match `^http://127\.0\.0\.1:[1-9][0-9]{0,4}/auth/callback$`. For an issued client it must also keep the registered scheme, host and path (the port may differ).

The fake auto-consents and returns `302` with a `Location` header. On success it stores `code → {client_id, challenge, redirect_uri, nonce, scope, used=false, exp=now+60s}`.

**`POST {auth}/api/accounts/oauth/token`**

Requires `Content-Type: application/x-www-form-urlencoded`.
- `authorization_code` requires `client_id`, `code`, `code_verifier`, `redirect_uri`, `resource`.
- `refresh_token` requires `client_id`, `refresh_token`, `resource`, and no `scope`.

Each refresh token is single-use (rotation). Reuse → `refresh_token_reused`.

**`POST {auth}/api/accounts/oauth/revoke`**: form `token`, `token_type_hint=refresh_token`, `client_id` → `200` with an empty body, even for an unknown token.

**`GET {api}/models`**: Bearer required →

```json
{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},{"slug":"fake-hidden","display_name":"Hidden","visibility":"hide"}]}
```

Only `"list"` is documented. The `"hide"` value is a fake to prove filtering.

**`POST {api}/responses`**: Bearer required; JSON body.
- Rejects `stream != true`, `store != false`, non-array `input`, any forbidden field, and system-role message items.
- Each rejection is `400 {"error":{"code":"subscription_sharing_unsupported_capability","param":"<field>","message":"...","type":"invalid_request_error"}}`.

**Any other route** (for example `POST {api}/chat/completions`, `GET {api}/responses/{id}`, `POST {api}/audio/speech`, `POST {api}/images/generations`) → `403 {"error":{"code":"subscription_sharing_route_not_supported","message":"...","param":null,"type":"invalid_request_error"}}`.

### 9.3 Success payloads

**Code exchange** `200`:

```json
{"access_token":"at_1","refresh_token":"rt_1","id_token":"<JWT>","token_type":"Bearer","expires_in":3600,
 "scope":"chatgpt.tokens.use.direct email offline_access openid profile resource.invoke","earliest_refresh_at":<unix_s_now+3000>}
```

The ID token is RS256 with `kid=fake-key-1` and claims `iss={auth}`, `aud=oaiapp_fakeA1`, `sub=user_fake_sub_1`, `nonce=<sent>`, `iat=now`, `exp=now+3600`, `email=fake@example.invalid`, `name=Fake User`. The `earliest_refresh_at` format is UNVERIFIED: run a second fixture with an ISO-8601 string and a third without it.

**Refresh** `200`:

```json
{"access_token":"at_2","refresh_token":"rt_2","token_type":"Bearer","expires_in":3600}
```

Variants: with `scope`; with a new `id_token` (same `sub`); with an `id_token` whose `sub` differs (→ account mismatch, tokens cleared).

**Stream** `200` (`Content-Type: text/event-stream`; also replay **without** Content-Type):

```
event: response.created
data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_f1","object":"response","status":"in_progress","model":"gpt-6.1-sol","output":[]}}

event: response.output_text.delta
data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg_f1","output_index":0,"content_index":0,"delta":"Take a 2-minute ","logprobs":[]}

event: response.output_text.delta
data: {"type":"response.output_text.delta","sequence_number":2,"item_id":"msg_f1","output_index":0,"content_index":0,"delta":"walk outside.","logprobs":[]}

event: response.completed
data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp_f1","object":"response","status":"completed","model":"gpt-6.1-sol","store":false,"output":[{"type":"message","id":"msg_f1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Take a 2-minute walk outside.","annotations":[]}]}]}}

```

### 9.4 Failure scenarios (HTTP status + body)

**Sign-in and token scenarios**

| Scenario | Trigger | Fake response | Expected client outcome |
|---|---|---|---|
| Login cancelled (consent denied) | authorize | `302 Location: {redirect_uri}?error=access_denied&state=<state>` (no code, no client_id) | Unchanged state; "Sign-in was not completed"; no token call |
| Login cancelled (tab closed) | none | No callback | Timeout or cancel → unchanged state; listener closed; port released |
| Invalid redirect | authorize with `localhost`, `https`, another path, or another host for an issued client | `400 {"error":"invalid_request","error_description":"redirect_uri is not allowed for this client"}`. **No redirect** (RFC 6749 section 4.1.2.1). | The client must never produce such a URL. Unit-test the URL builder; integration test sees no callback → timeout |
| Invalid state | authorize | `302 {redirect_uri}?code=c1&scope=...&state=TAMPERED&client_id=oaiapp_fakeA1` | Listener answers `400`, does **not** settle, does not exchange; later valid callback still accepted |
| Registration incomplete | authorize (new registration) | `302 ...?code=c1&scope=...&state=<state>` (no client_id) | REGISTRATION_INCOMPLETE; no token call |
| PKCE mismatch | token (`authorization_code` with wrong verifier) | `400 {"error":"invalid_grant","error_description":"code_verifier does not match code_challenge"}` | One automatic re-authorization with the issued client id, then error; no tokens stored |
| Code reused or expired | token | `400 {"error":"invalid_grant","error_description":"authorization code is invalid or expired"}` | Same as above |
| Redirect mismatch at exchange | token | `400 {"error":"invalid_grant","error_description":"redirect_uri mismatch"}` | Same as above |
| `dynamic_agent_client` used at exchange | token | `401 {"error":"invalid_client"}` | Client bug: the test asserts it never happens |
| Expired access token | `POST {api}/responses` with `at_1` after its expiry | `401 {"error":{"message":"Access token expired.","type":"invalid_request_error","param":null,"code":"token_expired"}}` (exact body UNVERIFIED; also test `401 {"detail":"Unauthorized"}`) | One refresh (`rt_1` → `rt_2`), one retry → success; `rt_1` never reused |
| Refresh success | token `refresh_token=rt_1` | `200` refresh payload above | `CONNECTED`; rotation persisted before the retry |
| Refresh failure (terminal) | token | `400 {"error":"invalid_grant"}`. Also each of `invalid_refresh_token`, `token_expired`, `refresh_token_expired`, `refresh_token_invalidated`, `refresh_token_reused`, in both `{"error":"<code>"}` and `{"error":{"code":"<code>","message":"..."}}` forms | `REAUTH_REQUIRED`; tokens cleared; client id, `sub` and host id kept |
| Refresh failure (transient) | token | `503 {"error":"temporarily_unavailable"}`, an HTML 502, or socket closed | `SERVER_ERROR` or `NETWORK_UNAVAILABLE`; tokens kept; exactly one POST per attempt (no silent replay) |

**Inference scenarios**

| Scenario | Trigger | Fake response | Expected client outcome |
|---|---|---|---|
| Not eligible | `POST {api}/responses` | `403 {"error":{"message":"ChatGPT plan usage is unavailable for this user.","type":"invalid_request_error","param":null,"code":"subscription_sharing_user_not_eligible"}}` | `NOT_ELIGIBLE(ACCOUNT_NOT_ELIGIBLE)`; no retry; no OAuth |
| Plan usage not granted | code exchange | `200` with `"scope":"openid profile email offline_access"` (exact declined scope string UNVERIFIED) | `NOT_ELIGIBLE(PLAN_USAGE_NOT_GRANTED)`; inference blocked locally |
| Plan usage unavailable | `POST {api}/responses` | `503 {"error":{"message":"Usage availability could not be checked.","type":"server_error","param":null,"code":"subscription_sharing_usage_unavailable"}}`. Mid-stream variant: `data: {"type":"response.failed","sequence_number":2,"response":{"id":"resp_f2","status":"failed","error":{"code":"subscription_sharing_usage_unavailable","message":"..."}}}` | `PLAN_USAGE_UNAVAILABLE`; backoff; partial text discarded |
| Rate limited | `POST {api}/responses` | `429 {"error":{"message":"Usage limit reached.","type":"rate_limit_error","param":null,"code":"subscription_sharing_usage_limit_exceeded"}}` (shape as TP-PI fixture). Mid-stream `response.failed` variant. Generic variant: `429 {"error":{"code":"rate_limit_exceeded",...}}` + `Retry-After: 20` | `RATE_LIMITED(PLAN_LIMIT)`, no auto-retry, "Manage usage"; generic → backoff honouring Retry-After |
| Account disconnected (in ChatGPT Settings) | `POST {api}/responses`, then refresh | API: `401 {"error":{"message":"The subscriber context could not be validated.","type":"invalid_request_error","param":null,"code":"subscription_sharing_invalid_user"}}`; then token: `400 {"error":"invalid_grant","error_description":"refresh token revoked"}` | `REAUTH_REQUIRED(REFRESH_REJECTED)`; tokens cleared |
| Admission errors | `POST {api}/responses` | `401 {"detail":"Unauthorized"}` / `403 {"detail":"Forbidden"}` / `503 {"detail":"Service Unavailable"}` | 401 → refresh-and-retry path; 403 → `NOT_ELIGIBLE(POLICY_RESTRICTED)`; 503 → `PLAN_USAGE_UNAVAILABLE(ROUTING)` |
| Grant not authorized | `POST {api}/responses` | `403 {"error":{"code":"chatpass_v2_scope_not_authorized","message":"...","param":null,"type":"invalid_request_error"}}` | `NOT_ELIGIBLE(GRANT_NOT_AUTHORIZED)` |
| Unsupported capability | body with `"temperature":0.2`, a top-level `image_generation` tool, or `json_schema` (probe) | `400 {"error":{"code":"subscription_sharing_unsupported_capability","param":"temperature","message":"...","type":"invalid_request_error"}}` | `SERVER_ERROR(UNSUPPORTED_CAPABILITY)`; same body never resent; `json_schema` probe flips the feature flag off |
| Server error | `POST {api}/responses` or `GET {api}/models` | `500 {"error":{"message":"The server had an error while processing your request.","type":"server_error","param":null,"code":null}}`; `502` `text/html`; `504` empty | `SERVER_ERROR(UPSTREAM)`; bounded backoff |
| Stream interrupted | `POST {api}/responses` | Headers + one delta, then socket closed (`SocketEffect.CloseSocket`) | `SERVER_ERROR(STREAM_INTERRUPTED)`; no partial JITAI delivered |
| Stream incomplete | `POST {api}/responses` | `data: {"type":"response.incomplete","sequence_number":2,"response":{"id":"resp_f3","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}` | `SERVER_ERROR(INCOMPLETE)` |
| `error` event | `POST {api}/responses` | `event: error` / `data: {"type":"error","code":"server_error","message":"...","param":null,"sequence_number":1}` | Mapped by `code` |

**Revocation scenarios**

| Scenario | Trigger | Fake response | Expected client outcome |
|---|---|---|---|
| Revocation OK | revoke | `200` (empty body) | `DISCONNECTED` |
| Revocation failure | revoke | `503`, twice | `DISCONNECTED` + "Disconnect in ChatGPT Settings" warning; tokens still wiped |

### 9.5 Loopback listener contract (the app's own server, tested with a plain HTTP client)

| Request | Response |
|---|---|
| `GET /auth/callback?code=c&state=S&client_id=oaiapp_fakeA1`, `Host: 127.0.0.1:P` | `200 text/html` "Return to Agentle"; settles with success |
| Same, but `error=access_denied&state=S` | `200 text/html` "Sign-in was not completed"; settles with error |
| Wrong `state`, two `state` params, or no `state` | `400`; does not settle |
| Wrong path, non-GET, wrong `Host`, or any request after settling | `404`; does not settle |
| Idle connection that never sends a request | Closed after the read timeout; other connections still served |

### 9.6 Corrections to the earlier prototype

The prototype at `proto/testing/fixtures/.../fake/FakeChatGptServer.kt` (in the cache) disagrees with the documented contract in these ways:

| Prototype | Correct contract |
|---|---|
| Uses `/oauth/authorize` and `/oauth/token` | `/api/accounts/authorize` and `/api/accounts/oauth/token` |
| No dynamic registration or `client_id` in the callback | Callback carries the issued `client_id` (section 2.2) |
| Issues `scope "openid profile email"` | Issues all six scopes |
| Allows `stream:false` | Must reject `stream:false` |
| Does not check `resource`, `nonce`, `ext_agent_host_id` or `redirect_uri` | Checks all four |
| No revocation endpoint and no `/v1/models` | Provides both |
| Single-shape error bodies | Must also return `{"detail":...}` and OAuth string errors |

Replace it with section 9.

---

## 10. Gaps and UNVERIFIED items

1. Android or mobile support for loopback redirects: UNDOCUMENTED. Confirm with OpenAI via the interest form before any public release.
2. Structured outputs, `reasoning`, `include`, `tool_choice` and plain top-level function tools: UNDOCUMENTED. Probe on a real Plus or Pro account.
3. `earliest_refresh_at` meaning and format: UNDOCUMENTED (the DevKit accepts an ISO string or Unix seconds).
4. Exact bodies and HTTP statuses: for OAuth errors, for expired access tokens on `/v1/responses`, and for a user disconnect. UNVERIFIED (shapes in section 9 are modelled on RFC 6749 and third-party fixtures).
5. `Retry-After` or rate-limit headers: UNDOCUMENTED.
6. Whether plan-usage requests follow the user's ChatGPT data controls (training): UNDOCUMENTED.
7. Scope string returned when the user declines plan usage but approves identity: UNVERIFIED.
8. Chrome for Android behaviour of a top-level redirect to `127.0.0.1` (including under Chrome 154's HTTPS-by-default warnings), and of an automatic 302 to an `intent:` URL: UNVERIFIED; test on device.
9. `invalid_client` for a dynamic registration (deleted registration?): semantics UNDOCUMENTED.
10. Real discovery document contents (the `revocation_endpoint` path, extra fields): not fetched. `auth.openai.com` is outside this container's allowed hosts.
11. Whether background or scheduled (unattended) plan-funded requests are acceptable: UNDOCUMENTED. "Eligible AI requests" is not defined [D-IDX].

---

## 11. Next steps for the integration agent

1. **Implement the components in section 8.2**, in a `:core:ai-auth` module (or similar). Every base URL must be injectable. Release builds must assert `https://auth.openai.com` and `https://api.openai.com/v1`.
2. **Replace the prototype fake** with the section 9 contract on `mockwebserver3`. Write JVM tests for every row of 9.4 and 9.5, plus the guard test in section 7.
3. **Build the UI from section 6:**
   - "Continue with ChatGPT" button, using approved assets per https://openai.com/brand/.
   - First-run modal.
   - "Using ChatGPT plan" indicator.
   - "Manage usage" link that opens https://chatgpt.com/settings/usage in a Custom Tab.
   - Usage-limit dialog.
   - Disconnect control, plus help text with the ChatGPT Settings disconnect path.
4. **Gate all AI features on `CONNECTED`**, and keep non-AI JITAI fallbacks for every other state. Add a user-visible daily budget for background generations.
5. **Run a manual device test plan** (Chrome stable on Android 15, 16 and 17; Plus and Pro accounts). Cover:
   - the 127.0.0.1 redirect and the "Return to Agentle" intent;
   - process death during sign-in;
   - work-profile placement;
   - `json_schema` and namespace-tool probes;
   - the real error bodies for an expired token and a user-side disconnect.
   Record the results in this document, replacing UNVERIFIED rows.
6. **Before any distribution beyond Nick's own device:** submit https://openai.com/form/sign-in-with-chatgpt-interest/ describing an Android app with an in-process loopback redirect, and wait for confirmation (sections 0.2 and 3.2).
