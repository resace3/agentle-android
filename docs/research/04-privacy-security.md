# 04 - Privacy and security engineering review (Agentle)

Status: IN PROGRESS (skeleton written 2026-10-01 by Agent 4; sections are filled in as research completes).
Scope: threat model, risk register, token storage, database encryption, backup rules, manifest hardening,
logging sanitizer, deletion semantics, OAuth redirect design, LLM prompt-injection defenses, Play Data safety,
automated privacy/security tests. Android 16 (API 36) and Android 17 (API 37) behaviour changes are called out explicitly.

## 0. Sources and verification method
(pending)

## 1. Threat model
(pending)

## 2. Risk register (P0-P4)
(pending)

## 3. Decisions
### 3.1 Token storage design
(pending)
### 3.2 Database encryption decision
(pending)
### 3.3 Backup rules (XML)
(pending)
### 3.4 Manifest hardening checklist
(pending)
### 3.5 OAuth redirect and browser decisions
(pending)
### 3.6 Logging sanitizer rules
(pending)
### 3.7 Data-deletion semantics
(pending)
### 3.8 AI / LLM boundary (prompt injection, context minimization, structured output)
(pending)
### 3.9 Network security config and pinning
(pending)
### 3.10 Play Data safety mapping
(pending)

## 4. Automated privacy/security test list
(pending)

## 5. Android 16 / 17 privacy and security behaviour changes that affect Agentle
(pending)

## 6. Uncertainties and UNVERIFIED items
(pending)

---
## Interim research log (will be folded into the sections above)

Cache root for this document: `/tmp/claude-0/-home-claude-agentle-android/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/a4/` (`raw/*.html` = page as fetched with curl on 2026-10-01/02, `txt/*.txt` = text extraction). Earlier-run cache: `/tmp/claude-0/-home-claude/cbfd770e-d1df-54bb-aee8-7b662673d25c/scratchpad/pages/`.

- security-crypto: 1.1.0 stable 2025-07-30; 1.1.0-alpha07/beta01 "Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore"; cryptography page: "There won't be any subsequent releases of this library." [AX-SEC][CRYPTO]
- Security tips (updated 2026-09-01): "use the Android Keystore, and encrypt stored keys using a robust tool such as Tink Java." [TIPS]
- tink-android 1.23.0 (Maven Central lastUpdated 2026-07-09); class `com.google.crypto.tink.integration.android.AndroidKeystore` has `generateNewAes256GcmKey`, `generateNewKeyWithSpec`, `getAead`, `deleteKey`, `hasKey`; AEAD = AES/GCM/NoPadding, 12-byte IV prefix, 128-bit tag (javap of the 1.23.0 jar). [MVN-TINK][TINK-JAR]
- sqlcipher-android 4.19.1 (Maven Central lastUpdated 2026-09-29); README: API 23+, Room 2 via `SupportOpenHelperFactory`, Room 3 via `SQLCipherDriver`; Java logging goes to Logcat unless `Logger.setTarget(NoopTarget())`. AAR: all four ABIs have ELF LOAD alignment 0x4000 (16 KB) (readelf); `SQLCipherDriver implements androidx.sqlite.SQLiteDriver`; `SQLCipherConnection.changePassword(byte[])`; AAR minCompileSdk 36; POM deps kotlin-stdlib 2.2.10, androidx.sqlite 2.7.0. Legacy `android-database-sqlcipher` last 4.5.4 (2023-04-27). [MVN-SQLC][SQLC-README][SQLC-AAR]
- AppAuth `net.openid:appauth` last 0.11.1 (2021-12-22). [MVN-APPAUTH]
- Keystore guide (2026-03-06): StrongBox "For most apps, StrongBox is not necessary"; StrongBoxUnavailableException -> regenerate without StrongBox. [KS]
- setUnlockedDeviceRequired: keys unusable while locked; bugs on Android 12-14 (keys deleted when lock screen removed, etc.). Unsuitable for background workers. [KGPS]
- setUserAuthenticationRequired: key irreversibly invalidated when secure lock screen disabled/reset; per-use keys invalidated on biometric enrollment changes. [KGPS][KPIE]
- Android 17 per-app keystore limit: 50,000 keys for apps targeting 37 (ERROR_TOO_MANY_KEYS). [A17-ALL]
- Auto Backup (2026-02-26): 25 MB; excluded dirs: cache, code cache, noBackupFilesDir; `<cross-platform-transfer platform="ios">` since Android 16 QPR2 (36.1); "If there are no rules for a particular backup mode ... that mode is fully enabled"; allowBackup=false does not disable D2D on some OEMs for apps targeting 31+. [AB][APP-EL][A12-T]
- NSC (2026-08-28): CT default-on for apps targeting 37 (opt-in on 36); ECH `<domainEncryption>` default enabled for target 37; Android 17 implicit localhost config allows cleartext unless the app defines localhost config; pin-set guidance (backup pin, expiration). OkHttp 5.5.0 supports ECH via AndroidDns/DnsOverHttps (ECH page 2026-09-16). [NSC][ECH]
- usesCleartextTraffic: ignored for apps targeting API 38+ (application element page 2026-08-21). [APP-EL]
- Content capture: apps targeting 37 can no longer disable it with setContentCaptureEnabled(false); use FLAG_SECURE. [A17-T]
- Recents: Activity.setRecentsScreenshotEnabled(false) (API 33) only affects the Overview thumbnail. [REF-ACT]
- Screen share: View.setContentSensitivity (API 35); Compose `Modifier.sensitiveContent()` since compose-ui 1.8.0. [REF-VIEW][COMPOSE-SC]
- Auth Tab (androidx.browser 1.9.0+): redirect to custom scheme or HTTPS host+path verified by Digital Asset Links; result returned to the launching activity. [AUTHTAB-REF]
