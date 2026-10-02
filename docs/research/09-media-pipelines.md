# 09 - Intervention media pipelines (voice, video, images, media notifications)

Status: DRAFT FOR REVIEW. All sections complete (2026-10-02). Open decisions are in section 13 and unverified points
in section 14.
Research date: 2026-10-01/02. Author: media pipelines specialist.
Scope: TTS voice, Media3 Transformer video, image cards and image generation options, media storage, and media-rich
notifications for Agentle (Kotlin/Compose, compileSdk/targetSdk 37, minSdk 26-31 undecided, Room, Hilt, WorkManager,
Media3). Module: `:interventions` (doc 07).
Related docs: 01 (permissions), 02 (background execution), 06 (Sign in with ChatGPT), 07 (architecture and versions),
08 (testing), 10 (JITAI engine: `delivery.channel`, `local_media`, delivery protocol). Changes requested in those docs
are listed in section 13.2.

## Summary

1. **Voice is synthesized ahead of time, and played only on a visible screen.**
   - A worker calls `TextToSpeech.synthesizeToFile(CharSequence, Bundle, File, String)` (API 21). It writes an
     app-private WAV and plays nothing.
   - The WAV is played only from a visible activity with ExoPlayer, using `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`.
   - Workers never call `speak()` and never request audio focus. Android 17 silences background playback and returns
     `AUDIOFOCUS_REQUEST_FAILED` for focus requests without a visible activity or FGS [A17-BGA]. Since target 35, focus
     requests from an app that is not the top app already fail [AF].
   - **Change request for doc 10 section 8.5 step 4** ("VOICE first posts a silent companion notification with the same
     tag, then speaks with `utteranceId = decisionKey`"). VOICE becomes: "synthesize the WAV while rendering (step 3),
     then post a normal notification with a Listen action that opens the app". See section 2.1.
2. **Video comes from Media3 Transformer 1.11.1, in the foreground only.**
   - Transformer composes PNG slides and a narration WAV into an H.264/AAC MP4. It uses two sequences:
     `EditedMediaItemSequence.withVideoFrom(slides)` and `withAudioFrom(listOf(wav))`.
   - It runs only while a "Preparing video" screen is visible. There is no `mediaProcessing` FGS (doc 02).
   - JITAI VIDEO deliveries use bundled clips (doc 10 `local_media.assetId`), so generation is never on the delivery
     path.
   - The fallback is a raw `MediaCodec` + `MediaMuxer` encoder, which compiles here (section 4).
3. **Images are bundled pictures or offline template cards; AI image generation is off in v1.**
   - Cards (quote, sparkline, stat) are drawn with `android.graphics` (StaticLayout, LinearGradient, Path) and saved as
     PNG. This works in a worker, and in Robolectric with Roborazzi `bitmap.captureRoboImage()`.
   - Doc 10 currently allows JITAI IMAGE deliveries only with `local_media` (a bundled catalog image). Template cards
     are used for video slides and share cards. A proposed doc 10 `card` content strategy would also allow them in
     notifications (sections 6.3 and 13.2).
   - No v1 route exists for AI image generation:
     - Sign in with ChatGPT has no image generation [R06].
     - ML Kit GenAI (Gemini Nano) lists no image generation [AI-NANO].
     - Android 37 has no framework API for it [SDK37].
     - MediaPipe `ImageGenerator` exists: Stable Diffusion 1.x, fixed 512x512, GPU JNI, weights the app must ship
       itself [MP]. It is not a v1 option.
   - So the capability flag `imageGeneration = false` for every v1 provider.
4. **Storage lives in `noBackupFilesDir/media/{image,audio,video}`, tracked in a Room `media_asset` table.**
   - That directory is always excluded from Auto Backup. Media therefore cannot push the app over the 25 MB backup
     quota, which would otherwise stop all backups [AUTOBACKUP].
   - Caps per kind plus LRU eviction (pure Kotlin, tested).
   - Atomic temp-then-rename writes, and an orphan sweep.
   - Sharing copies the file into `cacheDir/share/`, because `FileProvider` has no no-backup root [AX-FP].
5. **Notifications** use:
   - `NotificationCompat.BigPictureStyle` with a 2:1 card;
   - `CATEGORY_REMINDER`, and `VISIBILITY_PRIVATE` with a public version;
   - Done/Snooze actions that broadcast to a non-exported receiver;
   - Listen/Watch actions that open an activity directly (no trampolines [A12-TGT]);
   - `FLAG_IMMUTABLE` and an explicit component on every `PendingIntent`;
   - no full-screen intent.

   Before posting, the app checks `POST_NOTIFICATIONS`, then `areNotificationsEnabled()`, then channel importance.
6. **Testing.**
   - What was verified here:
     - The framework-only sketches compile against android.jar 37.
     - 21 JVM tests pass (WAV, text chunking, slide timeline, eviction policy).
   - Robolectric limits:
     - Media3 Transformer cannot run under Robolectric with image inputs or video transcoding
       (`TestTransformerBuilder` javadoc), so video tests run on an emulator or device.
     - TTS, audio and notification code can use Robolectric 4.17 shadows, with known gaps: `ShadowTextToSpeech` never
       calls `onInit`, writes the text instead of audio, and cannot return `LANG_MISSING_DATA`.

## 0. Sources and method

- **Network rules for this run.** No WebFetch was used.
  - Worked: `curl` to developer.android.com, git over HTTPS to github.com, raw.githubusercontent.com, Maven Central.
  - Blocked (no mirror, cache or proxy of a blocked host was used):
    - maven.google.com redirects to dl.google.com (HTTP 403);
    - android.googlesource.com (403);
    - developers.google.com, ai.google.dev, firebase.google.com and developers.openai.com (curl HTTP 000).
- **Media3 sources.**
  - `git ls-remote https://github.com/androidx/media.git` shows tag `1.11.1` =
    `8c6678b657ede1e7883fc164ef73ed483c7796c3` (commit date 2026-09-08). It is also the head of branch `release`, and
    there is no 1.12 tag.
  - A sparse clone at that tag provided `libraries/{common,effect,exoplayer,muxer,transformer,ui_compose,
    ui_compose_material3,test_utils,test_utils_robolectric}`, `docsamples/` and `RELEASENOTES.md`, plus
    `datasource/.../{DefaultDataSource,AssetDataSource}.java`.
  - The release page [M3-REL] lists 1.11.1 (September 10, 2026) as the latest stable, with no release candidate, beta
    or alpha.
- **Media3 code was not compiled.** Google Maven is blocked. Every Media3 symbol used in sections 3 and 5 was checked
  against the 1.11.1 sources (file:line references are relative to `libraries/`) and the official docsamples.
- **Framework-only sketches were compiled** [MC]. This covers TTS, audio routing, the MediaCodec fallback, image cards,
  the notification mirror and the storage policy.
  - Compiled against `/opt/android-sdk/platforms/android-37.0/android.jar` (compileOnly) with Kotlin 2.4.20 (JVM
    target 17) and kotlinx-coroutines 1.11.0.
  - The pure-Kotlin parts have JVM tests: WAV parse/concat, TTS text chunking, slide timeline, eviction policy.
    **21 tests, 0 failures** (Gradle 9.7.1 wrapper, `./gradlew test --rerun`, last run 2026-10-02; daemon stopped
    afterwards).
  - The check project lives in the session scratchpad (`mediacheck/`), not in the repo. Its code is reproduced in this
    document; the utilities and tests are in Appendix A.
- **API levels** come from platform 37 `data/api-versions.xml` [SDK37].
- **Robolectric**: `shadows-framework-4.17.jar` (javap) and `ShadowTextToSpeech.java` at tag `robolectric-4.17`
  [ROBO].
- **AndroidX core and Compose sources** come from androidx-main, fetched 2026-10-01 [AX]. The release artifacts (core
  1.19.1, Compose 1.12.1) could not be inspected because Google Maven is blocked. For core, the release notes date the
  compat notification APIs used here to 1.5-1.8 [CORE-REL] (section 1). For Compose, release presence is noted per API
  in section 6.
- **MediaPipe**: tag list via `git ls-remote` (latest `v1.0.0`) and ImageGenerator sources at `v1.0.0` [MP].
- **Citations** use `[KEY]`; section 15 lists the keys. "UNVERIFIED" marks every claim that could not be checked in
  this run.

## 1. Versions (verified)

| Component | Version | Notes | Source |
|---|---|---|---|
| Media3: `media3-transformer`, `-effect`, `-common`, `-exoplayer`, `-ui-compose`, `-ui-compose-material3`; tests also use `-inspector`, `-test-utils` | **1.11.1** (2026-09-10), stable | No pre-release in flight. Library `minSdk` 23, `compileSdk` 36. Transformer and effect APIs are `@UnstableApi`; see the opt-in note below. The full `Player(...)` overload in ui-compose-material3 is `@ExperimentalApi` (`ui_compose_material3/.../material3/Player.kt:98`). | [M3-REL][M3-GIT] |
| Compose BOM | 2026.09.00 (Compose 1.12.1, Material3 1.4.0) | - | [R07] |
| AndroidX Core (`NotificationCompat`, `NotificationManagerCompat`, `ContextCompat`, `FileProvider`) | 1.19.1 (2026-09-23) | API shapes checked on androidx-main. The core release notes date the newest compat APIs used here long before 1.19.1: `NotificationChannelCompat` / `NotificationChannelGroupCompat` 1.5.0-alpha02, `BigPictureStyle.showBigPictureWhenCollapsed` 1.7.0-alpha02, `BigPictureStyle.setContentDescription` 1.8.0-alpha01. | [R07][AX][CORE-REL] |
| Lifecycle (`LifecycleStartEffect`) | 2.11.0 | - | [R07] |
| Kotlin / kotlinx-coroutines | 2.4.20 / 1.11.0 | Used by the compile check | [R07][MC] |
| Robolectric / Roborazzi | 4.17 (SDK 23-37) / 1.76.0 | - | [R08][ROBO][RZ] |
| MediaPipe Tasks Vision, Image Generator | Source tag `v1.0.0` | Maven coordinates and version **UNVERIFIED** (Google Maven blocked) | [MP] |

**Media3 opt-in.** `@UnstableApi` is an `androidx.annotation.RequiresOptIn` marker. "By default usages of APIs annotated
with this annotation generate lint errors" (`common/.../util/UnstableApi.java`), so the Kotlin `-opt-in` compiler flag
does not apply. Put `@file:OptIn(UnstableApi::class)` (`androidx.annotation.OptIn`, as the docsamples do) only on the
few files in `:interventions` that wrap Transformer and ExoPlayer. Keep Media3 types out of public signatures, so a
Media3 upgrade touches only those files.

**Catalog additions** (doc 07 owns the catalog; this is a proposal):

```toml
[versions]
media3 = "1.11.1"

[libraries]
media3-transformer = { module = "androidx.media3:media3-transformer", version.ref = "media3" }
media3-effect = { module = "androidx.media3:media3-effect", version.ref = "media3" }
media3-common = { module = "androidx.media3:media3-common", version.ref = "media3" }
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-ui-compose = { module = "androidx.media3:media3-ui-compose", version.ref = "media3" }
media3-ui-compose-material3 = { module = "androidx.media3:media3-ui-compose-material3", version.ref = "media3" }
media3-inspector = { module = "androidx.media3:media3-inspector", version.ref = "media3" }   # androidTest: MetadataRetriever
media3-test-utils = { module = "androidx.media3:media3-test-utils", version.ref = "media3" } # androidTest
```

**Table 1.1: platform API levels used here** (api-versions.xml, platform 37 [SDK37])

| API | Since |
|---|---|
| `TextToSpeech.synthesizeToFile(CharSequence, Bundle, File, String)` | 21 |
| `TextToSpeech.synthesizeToFile(CharSequence, Bundle, ParcelFileDescriptor, String)` | 30 |
| `TextToSpeech.getMaxSpeechInputLength()` | 18 |
| `TextToSpeech.setAudioAttributes`, `getVoices`, `setVoice`; `Voice` | 21 |
| `TextToSpeech.getEngines()`; `Engine.INTENT_ACTION_TTS_SERVICE` | 14 |
| `UtteranceProgressListener.onError(String, Int)` (the 1-arg form is deprecated in 21) | 21 |
| `UtteranceProgressListener.onStop(String, Boolean)` | 23 |
| `UtteranceProgressListener.onBeginSynthesis`, `onAudioAvailable` | 24 |
| `UtteranceProgressListener.onRangeStart` | 26 |
| `Engine.KEY_FEATURE_NOT_INSTALLED` | 21 |
| `Engine.KEY_FEATURE_NETWORK_SYNTHESIS` (deprecated in 21; use `Voice.isNetworkConnectionRequired()`) | 15 |
| `AudioFocusRequest.Builder`, `AudioManager.requestAudioFocus(AudioFocusRequest)`, `abandonAudioFocusRequest` | 26 |
| `AudioAttributes.getVolumeControlStream()`, `AudioAttributes.USAGE_ASSISTANT` | 26 |
| `AudioManager.getDevices`, `isStreamMute`, `registerAudioDeviceCallback` | 23 |
| `AudioManager.getAudioDevicesForAttributes` | 33 |
| `AudioDeviceInfo.TYPE_HEARING_AID` / `TYPE_BUILTIN_SPEAKER_SAFE` / `TYPE_BLE_HEADSET`, `TYPE_BLE_SPEAKER` / `TYPE_BLE_BROADCAST` / `TYPE_BLE_HEARING_AID` (int constants, inlined at compile time) | 28 / 30 / 31 / 33 / 37 |
| `MediaCodecList.findEncoderForFormat`, `MediaCodec.getInputImage`, `COLOR_FormatYUV420Flexible` | 21 |
| `Notification.BigPictureStyle.showBigPictureWhenCollapsed`, `setContentDescription`, `bigPicture(Icon)` | 31 |
| `Notification.Builder.setTimeoutAfter` | 26 |
| `PendingIntent.FLAG_IMMUTABLE` / `FLAG_MUTABLE` | 23 / 31 |
| `Context.registerReceiver(BroadcastReceiver, IntentFilter, Int)` / `Context.RECEIVER_EXPORTED` | 26 / 33 |
| `StorageManager.getAllocatableBytes`, `allocateBytes`, `getUuidForPath`, `getCacheQuotaBytes` | 26 |
| `Context.getNoBackupFilesDir()` | 21 |
| `Bitmap.CompressFormat.WEBP_LOSSLESS` | 30 |

## 2. Voice: Android TextToSpeech pipeline

### 2.1 Where voice is produced and where it is played

| Step | Where it runs | API | Why |
|---|---|---|---|
| Synthesize | Delivery worker (doc 10 section 8.5 step 3 "Render") or an in-app action | `TextToSpeech.synthesizeToFile(text, Bundle(), file, utteranceId)` | Plays no audio, so the background audio hardening does not apply. The engine is a bound service, which a worker may bind. The WAV is ready before the user taps. |
| Notify | Delivery worker | `NotificationCompat` on `jitai_nudge` (section 10) | Doc 02: "no sounds started by the app"; only the channel's own alert sound plays |
| Play | Visible activity only | ExoPlayer on the WAV (section 5) | Target 35: focus fails unless the app "is the top app or running a foreground service" [AF]. Android 17: background "Playback is silenced" and focus returns `AUDIOFOCUS_REQUEST_FAILED` [A17-BGA]. |
| Live speak (optional) | Visible activity only | `TextToSpeech.speak` + manual `AudioFocusRequest` (2.6) | Short previews, for example "hear how this nudge sounds" in the editor |

**Proposed rewrite of doc 10 section 8.5 step 4 for VOICE.**
- While rendering (step 3), synthesize `voice/<assetId>.wav`.
- If that fails, downgrade the delivery to NOTIFICATION (text). The text is always there.
- Post the normal notification with a **Listen** action (section 10), with `utteranceId = decisionKey` for logs.
- Never speak from the worker.

The rewrite applies the doc 02 rule "Never touch audio from the background" to VOICE.

### 2.2 Manifest

```xml
<!-- Required for package visibility: "Apps targeting Android 11 that use text-to-speech should declare
     TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE in the queries elements of their manifest" [TTS] -->
<queries>
    <intent>
        <action android:name="android.intent.action.TTS_SERVICE" />
    </intent>
</queries>
```

Without this, `getEngines()` cannot see installed engines, and the pipeline reports `EngineUnavailable`. No permission
is needed for TTS or for `AudioManager` device queries.

### 2.3 Engine, language and voice selection

- **Construction.**
  - Use `TextToSpeech(Context, OnInitListener)`, or `TextToSpeech(Context, OnInitListener, String engine)` to pin an
    engine.
  - The listener "may be called immediately, before TextToSpeech instance is fully constructed" [TTS]. So the listener
    only completes a `CompletableDeferred<Int>` and never touches the instance.
  - Init has a 5 s timeout. A timeout maps to `EngineUnavailable(initStatus = null)`.
- **Engine unavailable.** Report this when `onInit(ERROR)`, when init times out, or when `engines` is empty (no engine
  installed, or the `<queries>` entry is missing). UI: "Voice is unavailable on this device", and deliver text.
- **Language.** `isLanguageAvailable(locale)` returns:

  | Constant | Value | Meaning |
  |---|---|---|
  | `LANG_COUNTRY_VAR_AVAILABLE` | 2 | Available |
  | `LANG_COUNTRY_AVAILABLE` | 1 | Available |
  | `LANG_AVAILABLE` | 0 | Available |
  | `LANG_MISSING_DATA` | -1 | Data must be downloaded |
  | `LANG_NOT_SUPPORTED` | -2 | Not supported |

- **Missing data.**
  - Offer `Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(enginePackage)` from a visible screen, and
    catch `ActivityNotFoundException`.
  - The docs warn that "the application shouldn't expect successful installation upon return from that intent" [TTS-ENG].
    So re-check on the next attempt, and never retry in a loop.
- **Voice choice.** `pickVoice` in the sketch below filters, then sorts:
  1. Same language, and the same country when the requested locale has one.
  2. `!voice.isNetworkConnectionRequired` unless the user opted in.
  3. Exclude voices whose `features` contain `Engine.KEY_FEATURE_NOT_INSTALLED`. Such a voice "may need to download
     additional data"; until then, requests "will either report TextToSpeech.ERROR_NOT_INSTALLED_YET error, or use a
     different voice" [TTS-ENG].
  4. Sort local voices first, then by `quality` descending.
- **Network voices are off by default.** A network voice sends the text off-device. Intervention text can contain
  health context, so `allowNetworkVoices` is a user setting, default false. `KEY_FEATURE_NETWORK_SYNTHESIS` is
  deprecated (API 21), so use `Voice.isNetworkConnectionRequired()` [TTS-ENG][VOICE].
- **The engine is third-party code.** The user's default engine receives the text even for local voices. The privacy
  copy must say so. Only rendered intervention text is sent, never raw health data.

### 2.4 Synthesis to an app-private WAV (compiled [MC])

- **Text limit.** Input is limited to `TextToSpeech.getMaxSpeechInputLength()` characters per request [TTS].
  `TtsTextChunker` (Appendix A, tested) splits at sentence, then clause, then word boundaries.
- **One request per chunk.** Each chunk gets its own `utteranceId` and part file. The parts are joined by `Wav.concat`
  (Appendix A, tested) with 150 ms of silence between them.
- **Output format.** The reference page does not specify the file format of the `File` overload.
  - AOSP's `FileSynthesisCallback` writes a RIFF/WAVE PCM file. This is UNVERIFIED in this run, because
    android.googlesource.com is blocked.
  - So the pipeline validates the header (`Wav.parse`: RIFF/WAVE, `fmt `, PCM 16-bit, frames > 0) and maps anything
    else to `InvalidOutput`.
  - `onBeginSynthesis(utteranceId, sampleRateInHz, audioFormat, channelCount)` (API 24) reports the engine's format.
    The parser reads it from the header instead.
- **Robust header parsing.** The parser tolerates a data size of 0 or `0xFFFFFFFF` (header written before the length was
  known), clamps to the file length, and skips `LIST` chunks (tests in Appendix A).
- **Atomic writes.** Part files and the staged file sit in the target directory, and the result is renamed into place.
  Temp files are deleted in `finally`.
- **Cancellation.** `stop()` "Interrupts the current utterance (whether played or rendered to file) and discards other
  utterances in the queue" [TTS]. It is called on coroutine cancellation, and `shutdown()` always runs in `finally`.

```kotlin
// Compiled against android.jar 37 (mediacheck/src/main/kotlin/agentle/media/voice/TtsSynthesizer.kt)
sealed interface TtsFailure {
    /** No engine installed, init returned ERROR, or init timed out (status = null). */
    data class EngineUnavailable(val initStatus: Int?) : TtsFailure
    /** LANG_MISSING_DATA: offer TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA from a visible screen. */
    data class LanguageMissingData(val locale: Locale) : TtsFailure
    data class LanguageNotSupported(val locale: Locale) : TtsFailure
    /** Only network voices exist for the locale and the user did not allow network TTS. */
    data class NoOfflineVoice(val locale: Locale) : TtsFailure
    /** synthesizeToFile() returned ERROR (request not queued). */
    data object QueueRejected : TtsFailure
    /** UtteranceProgressListener.onError(id, code): one of TextToSpeech.ERROR_*. */
    data class SynthesisError(val errorCode: Int) : TtsFailure
    /** onStop(): flushed or interrupted (stop() / QUEUE_FLUSH / engine restart). */
    data class Stopped(val interrupted: Boolean) : TtsFailure
    data object Timeout : TtsFailure
    /** File missing, not RIFF/WAVE, not 16-bit PCM, or empty. */
    data object InvalidOutput : TtsFailure
}

/** How one utterance ended (a null-free type so a timeout is never confused with success). */
private sealed interface UtteranceEnd {
    data object Done : UtteranceEnd
    data class Failed(val failure: TtsFailure) : UtteranceEnd
}

sealed interface TtsOutcome {
    data class Success(
        val file: File, val wav: WavInfo, val enginePackage: String?, val voiceName: String?,
        val locale: Locale, val networkVoice: Boolean,
    ) : TtsOutcome
    data class Failure(val failure: TtsFailure) : TtsOutcome
}

class TtsSynthesizer(
    private val context: Context,
    private val enginePackage: String? = null,       // null = user's default engine
    private val allowNetworkVoices: Boolean = false, // network voices send the text off-device
    private val initTimeoutMs: Long = 5_000,
) {
    suspend fun synthesizeToWav(text: String, locale: Locale, outFile: File): TtsOutcome {
        val initStatus = CompletableDeferred<Int>()
        val listener = TextToSpeech.OnInitListener { status -> initStatus.complete(status) }
        // The listener "may be called immediately, before TextToSpeech instance is fully constructed".
        val tts = if (enginePackage == null) TextToSpeech(context, listener)
        else TextToSpeech(context, listener, enginePackage)
        val tmpFiles = mutableListOf<File>()
        try {
            val status = withTimeoutOrNull(initTimeoutMs) { initStatus.await() }
            if (status != TextToSpeech.SUCCESS || tts.engines.isNullOrEmpty()) {
                return TtsOutcome.Failure(TtsFailure.EngineUnavailable(status))
            }
            when (tts.isLanguageAvailable(locale)) {
                TextToSpeech.LANG_MISSING_DATA -> return TtsOutcome.Failure(TtsFailure.LanguageMissingData(locale))
                TextToSpeech.LANG_NOT_SUPPORTED -> return TtsOutcome.Failure(TtsFailure.LanguageNotSupported(locale))
                else -> Unit // LANG_AVAILABLE, LANG_COUNTRY_AVAILABLE, LANG_COUNTRY_VAR_AVAILABLE
            }
            val voice = pickVoice(tts.voices.orEmpty(), locale)
            when {
                voice != null -> tts.setVoice(voice)
                allowNetworkVoices -> tts.setLanguage(locale)
                else -> return TtsOutcome.Failure(TtsFailure.NoOfflineVoice(locale))
            }

            val pending = ConcurrentHashMap<String, CompletableDeferred<UtteranceEnd>>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) { pending[utteranceId]?.complete(UtteranceEnd.Done) }
                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String) {
                    pending[utteranceId]?.complete(UtteranceEnd.Failed(TtsFailure.SynthesisError(TextToSpeech.ERROR)))
                }
                override fun onError(utteranceId: String, errorCode: Int) {
                    pending[utteranceId]?.complete(UtteranceEnd.Failed(TtsFailure.SynthesisError(errorCode)))
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    pending[utteranceId]?.complete(UtteranceEnd.Failed(TtsFailure.Stopped(interrupted)))
                }
            })

            val chunks = TtsTextChunker.split(text, TextToSpeech.getMaxSpeechInputLength())
            if (chunks.isEmpty()) return TtsOutcome.Failure(TtsFailure.InvalidOutput)
            for ((i, chunk) in chunks.withIndex()) {
                val part = File(outFile.parentFile, "${outFile.name}.part$i.tmp").also { tmpFiles += it }
                val id = UUID.randomUUID().toString()
                val done = CompletableDeferred<UtteranceEnd>().also { pending[id] = it }
                // Bundle must be non-null for the File overload ("Cannot be null").
                if (tts.synthesizeToFile(chunk, Bundle(), part, id) != TextToSpeech.SUCCESS) {
                    return TtsOutcome.Failure(TtsFailure.QueueRejected)
                }
                when (val end = withTimeoutOrNull(30_000L + 60L * chunk.length) { done.await() }) {
                    null -> return TtsOutcome.Failure(TtsFailure.Timeout)
                    is UtteranceEnd.Failed -> return TtsOutcome.Failure(end.failure)
                    UtteranceEnd.Done -> Unit
                }
            }

            val staged = File(outFile.parentFile, "${outFile.name}.tmp").also { tmpFiles += it }
            val info = if (tmpFiles.size == 2) { // one chunk + staged
                tmpFiles[0].renameTo(staged); Wav.parse(staged)
            } else {
                Wav.concat(tmpFiles.dropLast(1), staged)
            }
            if (info == null || !info.isPcm16 || info.frameCount == 0L) {
                return TtsOutcome.Failure(TtsFailure.InvalidOutput)
            }
            if (!staged.renameTo(outFile)) return TtsOutcome.Failure(TtsFailure.InvalidOutput)
            val used = tts.voice
            return TtsOutcome.Success(outFile, info, tts.defaultEngine, used?.name, locale,
                used?.isNetworkConnectionRequired == true)
        } catch (e: CancellationException) {
            tts.stop() // interrupts the current utterance "whether played or rendered to file"
            throw e
        } finally {
            tts.shutdown()
            tmpFiles.forEach { if (it.exists()) it.delete() }
        }
    }

    private fun pickVoice(voices: Set<Voice>, locale: Locale): Voice? = voices
        .asSequence()
        .filter { it.locale.language == locale.language }
        .filter { locale.country.isEmpty() || it.locale.country == locale.country }
        .filter { allowNetworkVoices || !it.isNetworkConnectionRequired }
        .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
        .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality })
        .firstOrNull()
}
```

Design notes:
- Use one instance per request. This avoids sharing state across workers. Init plus shutdown cost is small next to
  synthesis (not measured here).
- **Test seam (recommended).** Inject a `(Context, OnInitListener, String?) -> TextToSpeech` factory. A Robolectric test
  can then reach the instance and fire `onInit` (section 12.2).
- `ERROR_NOT_INSTALLED_YET` (-9) arrives through `onError(id, code)`. Treat it like `LanguageMissingData`: offer the
  install intent later, and deliver text now.
- **Timeout.** The per-chunk timeout (30 s + 60 ms per character) is a product guess. Measure it on devices
  (UNVERIFIED).

### 2.5 UtteranceProgressListener semantics used

| Callback | API | Used for |
|---|---|---|
| `onStart(id)` | 15 | Ignored for files; drives the "speaking" UI in live mode |
| `onDone(id)` | 15 | Completes the chunk. Live mode waits only for the last chunk's id. |
| `onError(id, errorCode)` | 21 | `SynthesisError(code)`. Codes: `ERROR` (-1), `ERROR_SYNTHESIS` (-3), `ERROR_SERVICE` (-4), `ERROR_OUTPUT` (-5), `ERROR_NETWORK` (-6), `ERROR_NETWORK_TIMEOUT` (-7), `ERROR_INVALID_REQUEST` (-8), `ERROR_NOT_INSTALLED_YET` (-9) [TTS] |
| `onError(id)` | 15, deprecated 21 | Overridden only because it is abstract. Maps to `ERROR`. |
| `onStop(id, interrupted)` | 23 | `Stopped(interrupted)` after `stop()` or a `QUEUE_FLUSH` |
| `onBeginSynthesis`, `onAudioAvailable`, `onRangeStart` | 24, 24, 26 | Not needed. `onRangeStart` could drive word highlighting in live mode later. |

### 2.6 Live speak on a visible screen (compiled [MC])

`SpeechPlayer` is only for an on-screen "Listen" button or editor preview. Deliveries play the WAV through ExoPlayer
(section 5).

```kotlin
// mediacheck/src/main/kotlin/agentle/media/voice/AudioOutput.kt
/** Attributes for every Agentle voice playback (TTS speak and ExoPlayer WAV/MP4 playback). */
val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    // Not USAGE_ASSISTANT: Android 17 routes that usage to a dedicated Assistant volume stream.
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

enum class OutputRoute {
    BUILT_IN_SPEAKER, WIRED, BLUETOOTH_CLASSIC, BLUETOOTH_LE, BLUETOOTH_SPEAKER, BROADCAST, HEARING_AID, USB, OTHER, UNKNOWN,
}

/** Routes that count as "not heard by bystanders". Advisory: an A2DP car kit or speaker also reports TYPE_BLUETOOTH_A2DP. */
private val PRIVATE_ROUTES = setOf(
    OutputRoute.WIRED, OutputRoute.BLUETOOTH_CLASSIC, OutputRoute.BLUETOOTH_LE, OutputRoute.HEARING_AID, OutputRoute.USB,
)

data class OutputState(val route: OutputRoute, val muted: Boolean, val volume: Int, val maxVolume: Int) {
    /** Private = audio will not be heard by bystanders (user option "speak only on headphones"). */
    val isPrivate: Boolean get() = route in PRIVATE_ROUTES
}

class AudioOutputInspector(private val audioManager: AudioManager) {
    fun current(attributes: AudioAttributes = SPEECH_ATTRIBUTES): OutputState {
        val stream = attributes.volumeControlStream // API 26: STREAM_MUSIC for USAGE_MEDIA
        val volume = audioManager.getStreamVolume(stream)
        val muted = audioManager.isStreamMute(stream) || volume <= 0
        return OutputState(route(attributes), muted, volume, audioManager.getStreamMaxVolume(stream))
    }

    private fun route(attributes: AudioAttributes): OutputRoute {
        val devices: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 33) {
            // "the devices anticipated to play sound from an AudioTrack created with the specified AudioAttributes"
            audioManager.getAudioDevicesForAttributes(attributes)
        } else {
            // Heuristic before 33: any connected external sink wins (the policy prefers it for media).
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).sortedBy { priority(it.type) }.take(1)
        }
        return devices.firstOrNull()?.let { classify(it.type) } ?: OutputRoute.UNKNOWN
    }

    private fun priority(type: Int): Int = when (classify(type)) {
        OutputRoute.BUILT_IN_SPEAKER -> 1
        OutputRoute.OTHER, OutputRoute.UNKNOWN -> 2
        else -> 0 // any connected external sink is expected to take media
    }

    private fun classify(type: Int): OutputRoute = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputRoute.BUILT_IN_SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputRoute.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputRoute.BLUETOOTH_CLASSIC
        AudioDeviceInfo.TYPE_BLE_HEADSET -> OutputRoute.BLUETOOTH_LE
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputRoute.BLUETOOTH_SPEAKER
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputRoute.BROADCAST // "a BLE broadcast group": several receivers, so not private
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEARING_AID -> OutputRoute.HEARING_AID // BLE hearing aid: API 37
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> OutputRoute.USB
        else -> OutputRoute.OTHER
    }
}
```

```kotlin
// mediacheck/src/main/kotlin/agentle/media/voice/SpeechPlayer.kt
sealed interface SpeakResult {
    data object Completed : SpeakResult
    /** AUDIOFOCUS_REQUEST_FAILED: call in progress, or app not visible (target 35+ / Android 17 hardening). */
    data object FocusDenied : SpeakResult
    data class SkippedMuted(val state: OutputState) : SpeakResult
    data class SkippedNotPrivate(val state: OutputState) : SpeakResult
    data object InterruptedByFocusLoss : SpeakResult
    data object InterruptedBecomingNoisy : SpeakResult
    data class Failed(val failure: TtsFailure) : SpeakResult
}

/** Must run while an activity is visible. [tts] must already be initialised (OnInitListener == SUCCESS). */
class SpeechPlayer(
    private val context: Context,
    private val tts: TextToSpeech,
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java),
) {
    private val inspector = AudioOutputInspector(audioManager)

    suspend fun speak(text: String, requirePrivateOutput: Boolean): SpeakResult {
        val state = inspector.current(SPEECH_ATTRIBUTES)
        if (state.muted) return SpeakResult.SkippedMuted(state)
        if (requirePrivateOutput && !state.isPrivate) return SpeakResult.SkippedNotPrivate(state)

        val result = CompletableDeferred<SpeakResult>()
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                tts.stop(); result.complete(SpeakResult.InterruptedByFocusLoss)
            }
        }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .setAcceptsDelayedFocusGain(false)
            .build()
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return SpeakResult.FocusDenied
        }
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    tts.stop(); result.complete(SpeakResult.InterruptedBecomingNoisy)
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(noisy, filter)
        try {
            tts.setAudioAttributes(SPEECH_ATTRIBUTES)
            val id = "live-" + System.nanoTime()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) { if (utteranceId == id) result.complete(SpeakResult.Completed) }
                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String) { result.complete(SpeakResult.Failed(TtsFailure.SynthesisError(TextToSpeech.ERROR))) }
                override fun onError(utteranceId: String, errorCode: Int) {
                    result.complete(SpeakResult.Failed(TtsFailure.SynthesisError(errorCode)))
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    result.complete(SpeakResult.Failed(TtsFailure.Stopped(interrupted)))
                }
            })
            val chunks = TtsTextChunker.split(text, TextToSpeech.getMaxSpeechInputLength())
            chunks.forEachIndexed { i, chunk ->
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                // Only the last chunk carries the id we wait for; earlier ids are ignored by the listener.
                val utteranceId = if (i == chunks.lastIndex) id else "$id-$i"
                if (tts.speak(chunk, mode, Bundle(), utteranceId) != TextToSpeech.SUCCESS) {
                    result.complete(SpeakResult.Failed(TtsFailure.QueueRejected))
                }
            }
            return result.await()
        } finally {
            tts.stop() // also runs on coroutine cancellation (screen left)
            context.unregisterReceiver(noisy)
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }
}
```

Facts behind the choices:
- **Usage `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`.**
  - Not `USAGE_ASSISTANT`: "Android 17 introduces a dedicated Assistant volume stream ... for playback with
    USAGE_ASSISTANT" [A17-FEAT]. A non-assistant app would then play on a stream the user does not expect.
  - Not `USAGE_NOTIFICATION`: the notification stream is often silenced.
  - ExoPlayer handles focus automatically only for `USAGE_MEDIA` and `USAGE_GAME`
    (`common/.../audio/AudioFocusManager.java:177-179`).
- **Focus type `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.** Music from other apps is ducked by the system. Automatic ducking
  is not performed when the playing app plays `CONTENT_TYPE_SPEECH` (for example a podcast); that app gets a focus
  change and usually pauses [AF].
- **The focus request is also the gate.** If it fails, nothing plays: either a call is active, or the app is not the
  top app (target 35+) [AF][A17-BGA].
- **`ACTION_AUDIO_BECOMING_NOISY`** stops speech when headphones or Bluetooth disconnect, so speech does not jump to the
  loudspeaker.
  - The receiver uses `RECEIVER_EXPORTED` on 33+. Per the broadcasts guide, "To receive all system broadcasts,
    including broadcasts from highly privileged apps, flag your receiver with RECEIVER_EXPORTED" [BCAST].
  - In production, use `ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)`, which
    also covers API 26-32.
- **Mute detection.** The stream comes from `attributes.volumeControlStream` (`STREAM_MUSIC` for `USAGE_MEDIA`).
  - Muted means `isStreamMute(stream)` or `getStreamVolume(stream) == 0`.
  - Reading the volume is allowed from anywhere. Only *changing* volume is hardened in Android 17 [A17-BGA] (doc 02 row
    I4).
  - Agentle never changes the volume.
  - When muted, show the text with a "Volume is off" hint instead of playing.
- **Do Not Disturb (product rule, not an OS fact).** Do not autoplay while
  `NotificationManager.getCurrentInterruptionFilter() != INTERRUPTION_FILTER_ALL`. An explicit tap on Listen still
  plays.

### 2.7 Output routing (Bluetooth A2DP / LE Audio / hearing aids)

- **Routes are never forced.** Agentle does not call `setCommunicationDevice`, does not set a preferred device, and does
  not use SCO. Media follows the system media route: A2DP, LE Audio (`TYPE_BLE_HEADSET` / `TYPE_BLE_SPEAKER`, 31+),
  hearing aids (`TYPE_HEARING_AID`, 28+; LE Audio hearing aids `TYPE_BLE_HEARING_AID`, 37+), wired or USB.
- **What counts as private** (the "headphones only" option):
  - Private: wired, A2DP, BLE headset, hearing aids (classic and BLE) and USB.
  - Not private: `TYPE_BLE_SPEAKER`, and `TYPE_BLE_BROADCAST`, "a Bluetooth Low Energy (BLE) broadcast group", which has
    several receivers.
  - The reference describes `TYPE_BLE_HEARING_AID` as "a Bluetooth Low Energy (BLE) hearing aid" [ADI]. Without that
    mapping, an Android 17 LE hearing aid would fall into `OTHER`, and the "headphones only" option would then skip
    speech for hearing-aid users.
  - A2DP is ambiguous. Car kits and speakers also report `TYPE_BLUETOOTH_A2DP`. Telling them apart needs
    `BluetoothDevice.getBluetoothClass()`, which the platform 37 SDK annotations (`data/annotations.zip`) mark
    `@RequiresPermission("android.permission.BLUETOOTH_CONNECT")` [SDK37]. Agentle does not request that permission, so
    label the setting honestly: "Only when headphones or Bluetooth audio is connected".
- **Detection is for UI and the privacy option only.**
  - On 33+, `getAudioDevicesForAttributes(SPEECH_ATTRIBUTES)` returns the device(s) that would play this usage.
  - Before 33, `getDevices(GET_DEVICES_OUTPUTS)` lists *connected* outputs, not the active route. The heuristic
    prefers any connected external sink. It can misreport when a Bluetooth device is connected but not active (UNVERIFIED edge
    case). So `isPrivate` is advisory before 33.
- **During playback**, register `AudioManager.registerAudioDeviceCallback` (23) to refresh the "Playing on ..." label.
  A removed device is already handled by BECOMING_NOISY.
- **No `BLUETOOTH_CONNECT` permission is requested.** Device types do not need it. Neither the reference [ADI] nor the
  SDK annotations [SDK37] list a permission for `AudioDeviceInfo.getProductName()` (API 23). Whether Bluetooth names are
  redacted at runtime without `BLUETOOTH_CONNECT` is UNVERIFIED, so the UI shows the type ("Bluetooth headphones"), not
  the product name.

### 2.8 Voice failure states (summary; the full matrix is in section 11)

| Failure | Detected by | User-visible result |
|---|---|---|
| No engine / init error / init timeout | `EngineUnavailable` | Deliver as text. In Settings: "Voice needs a text-to-speech app". |
| Language data missing | `LANG_MISSING_DATA`, `ERROR_NOT_INSTALLED_YET` | Deliver as text. Offer `ACTION_INSTALL_TTS_DATA` the next time the app is open. |
| Language not supported / only network voices | `LanguageNotSupported`, `NoOfflineVoice` | Deliver as text. Settings explain why. |
| Synthesis error / stopped / timeout / bad file | `SynthesisError`, `Stopped`, `Timeout`, `InvalidOutput` | Deliver as text. Record the reason on the delivery row. |
| Muted stream, not-private route, focus denied (live) | `SkippedMuted`, `SkippedNotPrivate`, `FocusDenied` | Show text with a hint. Never retry automatically. |
| Interrupted (focus loss, unplugged) | `InterruptedByFocusLoss`, `InterruptedBecomingNoisy` | Paused state with a Resume button |

## 3. Video: Media3 Transformer still-image + TTS composition

### 3.1 When video is produced

- **JITAI VIDEO deliveries play bundled clips.** They use `local_media.assetId` from the app's media catalog (doc 10
  section 3.3), so nothing is encoded on the delivery path.
- **Generated video is a user-initiated, in-app feature.** Example: a weekly recap of 3-6 slides plus narration. The
  export runs while a "Preparing video" screen is visible. Leaving the screen cancels the export, and the inputs (PNGs
  and WAV) are kept so a retry is fast.
- **Why foreground only.**
  - Doc 02 never uses the `mediaProcessing` FGS ("No transcoding").
  - Transformer "relies on MediaCodec for hardware-accelerated decoding and encoding, and OpenGL for processing video
    frames" [TR-TS]. A cached or frozen background process stalls an export, and the export then fails the muxer
    watchdog (`ERROR_CODE_MUXING_TIMEOUT`).
  - The default watchdog is `isRunningOnEmulator() ? 25_000 : 10_000` ms between samples
    (`transformer/.../Transformer.java:834-835`).
- **Pipeline.**
  1. Write the text per slide.
  2. Synthesize one TTS part per slide (`TtsSynthesizer`, section 2.4).
  3. Join the parts with `Wav.concat(parts, narration, silenceBetweenMs = 150, trailingSilenceMs = 300)`.
  4. Compute slide durations with `SlideTimeline.allocate(partDurationsUs, gapMs = 150, tailMs = 300)`. This is tested
     (Appendix A): the slide durations sum to the narration length within 1 ms, so each slide changes when its sentence
     starts.
  5. Render each slide to a 720x1280 PNG with `TemplateRenderer` (section 6).
  6. Run Transformer into `video/<assetId>.mp4.tmp`, then rename to the final name and insert the Room row (section 9).

### 3.2 Code sketch (Media3 1.11.1; not compiled here; symbols verified in 3.3)

```kotlin
@file:OptIn(UnstableApi::class) // androidx.annotation.OptIn; @UnstableApi is a lint-enforced opt-in

package agentle.interventions.media.video

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** One slide: a PNG rendered by TemplateRenderer at the output size, shown for [durationMs] (SlideTimeline). */
data class Slide(val png: File, val durationMs: Long)

data class VideoSpec(
    val width: Int = 720,
    val height: Int = 1280,
    val frameRate: Int = 30,
    val videoBitrate: Int = 1_500_000, // explicit; the default Kush gauge would request ~3.87 Mbit/s (3.5)
    val audioBitrate: Int = 64_000,
)

sealed interface VideoOutcome {
    data class Success(
        val file: File, val sizeBytes: Long, val approximateDurationMs: Long,
        val videoEncoder: String?, val audioEncoder: String?,
    ) : VideoOutcome
    /** [errorCode] = ExportException.ERROR_CODE_*; [useFallback] = retry with StillVideoEncoder (section 4). */
    data class Failure(val errorCode: Int, val errorName: String, val useFallback: Boolean) : VideoOutcome
}

class StillVideoComposer(private val context: Context) {

    /** Call while the "Preparing video" screen is visible; cancelling the coroutine cancels the export. */
    suspend fun compose(
        slides: List<Slide>,
        narrationWav: File,
        out: File,
        spec: VideoSpec = VideoSpec(),
        onProgress: (Int) -> Unit = {},
    ): VideoOutcome = withContext(Dispatchers.Main.immediate) { // Transformer is bound to one looper thread
        val tmp = File(out.parentFile, out.name + ".tmp")
        val main = Handler(Looper.getMainLooper())
        suspendCancellableCoroutine { cont ->
            val progress = ProgressHolder()
            var transformer: Transformer? = null
            val poll = object : Runnable { // same pattern as the official docsample (500 ms)
                override fun run() {
                    val t = transformer ?: return
                    if (t.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress)
                    main.postDelayed(this, 500)
                }
            }
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    main.removeCallbacks(poll)
                    if (!tmp.renameTo(out)) {
                        tmp.delete()
                        cont.resume(VideoOutcome.Failure(-1, "RENAME_FAILED", useFallback = false))
                        return
                    }
                    cont.resume(VideoOutcome.Success(out, result.fileSizeBytes, result.approximateDurationMs,
                        result.videoEncoderName, result.audioEncoderName))
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    main.removeCallbacks(poll)
                    tmp.delete() // Transformer never deletes its output file (3.3)
                    val code = exception.errorCode
                    cont.resume(VideoOutcome.Failure(code, ExportException.getErrorCodeName(code),
                        useFallback = code == ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
                            code == ExportException.ERROR_CODE_ENCODER_INIT_FAILED ||
                            code == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED))
                }
            }
            val t = Transformer.Builder(context) // default looper = current thread's looper (main here)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.videoBitrate).build())
                        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(spec.audioBitrate).build())
                        .setEnableFallback(true) // default; may change MIME type, resolution or bitrate
                        .build(),
                )
                .setUsePlatformDiagnostics(false) // default true on API 35+ (MediaMetricsManager; see 3.3)
                .addListener(listener)
                .build()
            transformer = t
            t.start(buildComposition(slides, narrationWav, spec), tmp.path) // throws IllegalStateException if misused
            main.postDelayed(poll, 500)
            cont.invokeOnCancellation {
                // May run on any thread, but Transformer.cancel() must run on the application thread.
                main.post { main.removeCallbacks(poll); t.cancel(); tmp.delete() }
            }
        }
    }

    private fun buildComposition(slides: List<Slide>, narrationWav: File, spec: VideoSpec): Composition {
        val images = slides.map { slide ->
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(slide.png))
                    .setMimeType(MimeTypes.IMAGE_PNG) // explicit; otherwise inferred from the extension
                    .setImageDurationMs(slide.durationMs) // required, else "may default to a very small value"
                    .build(),
            ).setFrameRate(spec.frameRate) // images have no intrinsic frame rate
                .build()
        }
        val narration = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(narrationWav))).build()
        return Composition.Builder(
            EditedMediaItemSequence.withVideoFrom(images),
            EditedMediaItemSequence.withAudioFrom(listOf(narration)),
        ).setEffects(
            Effects(
                /* audioProcessors = */ emptyList(),
                /* videoEffects = */ listOf(
                    Presentation.createForWidthAndHeight(spec.width, spec.height, Presentation.LAYOUT_SCALE_TO_FIT),
                ),
            ),
        ).build()
    }
}
```

**Optional text overlay.** Prefer baking text into the PNGs: it is deterministic, testable in Robolectric, and
localizable with `StaticLayout`. For dynamic text (for example a running timestamp), the effect API is:

```kotlin
val caption = TextOverlay.createStaticTextOverlay(
    SpannableString("Week 39"),
    StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.85f).build(), // x, y in [-1, 1]
)
val videoEffects = listOf(
    Presentation.createForWidthAndHeight(720, 1280, Presentation.LAYOUT_SCALE_TO_FIT),
    OverlayEffect(listOf(caption)), // OverlayEffect(List<TextureOverlay>)
)
```

### 3.3 Behaviour verified in the 1.11.1 sources (paths under `libraries/`)

| Fact | Evidence |
|---|---|
| Single thread. "Transformer instances must be accessed from a single application thread, and the listener methods are called on the same thread." The builder's looper defaults to `Util.getCurrentOrMainLooper()`. `cancel()` and `start()` call `verifyApplicationThread()`. | [TR-GS]; `transformer/.../Transformer.java:154`, `:1043`, `:1155` |
| `start()` throws `IllegalStateException` on the wrong thread or when an export is in progress ("Concurrent exports on the same Transformer object are not allowed"). | `Transformer.java:1023`, `:1039-1040` |
| **The output file is never deleted by Transformer**: there is no `File.delete` anywhere in `transformer/src/main` at 1.11.1, so the wrapper deletes `tmp` on error and cancel. | `grep -n '\.delete()' transformer/src/main/java/...` (no matches) |
| Image items need `MediaItem.Builder.setImageDurationMs`: "otherwise the duration of the image may default to a very small value". Default `C.TIME_UNSET`. | `common/.../MediaItem.java:574-587`; `transformer/.../DefaultAssetLoaderFactory.java:187-195` |
| `EditedMediaItem.Builder.setFrameRate`: for images, "this value determines the frame rate of the output video ... 30 fps is suitable for most use cases". Not set by default. | `transformer/.../EditedMediaItem.java:198-227` |
| Image type comes from `localConfiguration.mimeType`, otherwise from the content resolver or the URI. Decoding goes through `BitmapFactory` ("supports all the formats BitmapFactory does"). | `TransformerUtil.java:308-333`; [TR-FMT] |
| Sequence factories: `withAudioFrom`, `withVideoFrom`, `withAudioAndVideoFrom`. The vararg/list `Builder` constructors are `@Deprecated`. `Builder.setIsLooping`, `Builder.addGap(durationUs)` (its javadoc says "in milliseconds"; the parameter is microseconds; not used here). | `EditedMediaItemSequence.java:54-91`, `:129-152`, `:218`, `:236` |
| Audio rule: "All items containing audio data must output 16 bit PCM audio with the same number of channels". TTS WAVs are PCM 16-bit (validated in 2.4). | `Transformer.java:1014-1017` |
| Portrait: by default (`setPortraitEncodingEnabled(false)`) "portrait videos will be rotated by 90 degrees before being encoded, and metadata will be added ... to indicate that the video should be rotated back". The 720x1280 output is therefore stored as 1280x720 plus rotation (players honour it). | `Transformer.java:327-344`, `:1033-1035` |
| `Composition.Builder(sequence, vararg sequences)`, `setEffects(Effects)`; `Effects(List<AudioProcessor>, List<Effect>)` | `Composition.java:70`, `:128`; `Effects.java:63` |
| `Presentation.createForWidthAndHeight(w, h, layout)`; `LAYOUT_SCALE_TO_FIT` = 0, `LAYOUT_SCALE_TO_FIT_WITH_CROP` = 1, `LAYOUT_STRETCH_TO_FIT` = 2 | `effect/.../Presentation.java:77-106`, `:172` |
| `OverlayEffect(List<TextureOverlay>)`; `TextOverlay.createStaticTextOverlay(SpannableString[, StaticOverlaySettings])`; `StaticOverlaySettings.Builder.setBackgroundFrameAnchor/setOverlayFrameAnchor/setScale/setRotationDegrees/setAlphaScale` | `OverlayEffect.java:44`; `TextOverlay.java:46`, `:63`; `StaticOverlaySettings.java:55-117` |
| Encoder factory defaults: `enableFallback = true`, CodecDB Lite off, `codecPriority = C.PRIORITY_PROCESSING_FOREGROUND` (`setCodecPriority` is a no-op before API 35) | `DefaultEncoderFactory.java:78-88`, `:182-199` |
| Bitrate precedence: `VideoEncoderSettings.bitrate`, then the requested `Format` bitrate, then CodecDB Lite (if enabled), then the Kush gauge `width * height * frameRate * 0.07 * 2`. Default bitrate mode VBR. | `DefaultEncoderFactory.java:603-615`, `:947-963`; `VideoEncoderSettings.java:89-90` |
| Default muxer: `DefaultMuxer.Factory`, which wraps `InAppMp4Muxer.Factory` | `Transformer.java:153`; `DefaultMuxer.java:36-50` |
| Diagnostics: on API 35+ the builder sets `usePlatformDiagnostics = true` and reports through `MediaMetricsManager`; "This data may also be collected by Google if sharing usage and diagnostics data is enabled". Agentle sets `false` (health app; nothing leaves the device without consent). | `Transformer.java:158-162`, `:655-678` |
| Progress: `getProgress(ProgressHolder)` returns `PROGRESS_STATE_NOT_STARTED` (0), `WAITING_FOR_AVAILABILITY` (1), `AVAILABLE` (2) or `UNAVAILABLE` (3). `ProgressHolder.progress` is a percentage. The docsample polls every 500 ms. | `Transformer.java:819-828`, `:1139`; `ProgressHolder.java:28`; docsamples `GettingStarted.kt` |
| `ExportResult`: `approximateDurationMs` (`durationMs` is `@Deprecated`), `fileSizeBytes`, `averageVideoBitrate`, `averageAudioBitrate`, `videoFrameCount`, `width`, `height`, `videoEncoderName`, `audioEncoderName`, `exportException` | `ExportResult.java:446-513` |
| `ExportException.errorCode` plus `getErrorCodeName(int)`. Codes: 1000 `UNSPECIFIED`, 1001 `FAILED_RUNTIME_CHECK`, 2000-2008 IO, 3001-3003 decoder, 4001 `ENCODER_INIT_FAILED`, 4002 `ENCODING_FAILED`, 4003 `ENCODING_FORMAT_UNSUPPORTED`, 5001 `VIDEO_FRAME_PROCESSING_FAILED`, 6001 `AUDIO_PROCESSING_FAILED`, 7001 `MUXING_FAILED`, 7002 `MUXING_TIMEOUT`, 7003 `MUXING_APPEND`. `codecInfo` is set for codec errors. | `ExportException.java:120-234`, `:262`, `:356`, `:365` |
| Media3's own end-to-end tests export PNG inputs (`setImageDurationMs(1000)`, `setFrameRate(40)`) and assert `videoFrameCount == 40`. `approximateDurationMs` equals the last frame's timestamp (duration minus one frame). Another test combines `withAudioFrom(mp3 x3)` with a looping PNG video sequence. | `transformer/src/androidTest/.../TransformerEndToEndTest.java:339-365`, `:1808-1849` |
| Robolectric: "Transcoding video is unsupported in Robolectric tests ... Images are unsupported in Robolectric tests." | `test_utils/.../TestTransformerBuilder.java:46-54` |

### 3.4 Encoders on devices and emulators; output size

- **Formats.** H.264 + AAC-LC in MP4 is the most widely supported encoder pair. Transformer falls back automatically
  when the requested size, bitrate or MIME type is unsupported (`setEnableFallback(true)`).
- **Emulators** have no hardware codecs.
  - They use the software Codec2 encoders and GLES through the host GPU or SwiftShader (the GLES path is UNVERIFIED in
    this run).
    - Media3 itself special-cases `c2.android.aac.encoder` (`transformer/.../DefaultCodec.java:422`), and an
      instrumented test notes it "was added in newer android versions" (`@SdkSuppress(minSdkVersion = 30)`,
      `TransformerEndToEndTest.java:3274`). So assert AAC output only, not the encoder name, on API 26-29 images.
    - The AVC software encoder name (`c2.android.avc.encoder` is expected) is UNVERIFIED. Log
      `ExportResult.videoEncoderName` in the emulator test instead of asserting it.
  - Media3's own instrumented Transformer tests run on emulators. Some are gated "Emulator only test", and comments say
    "On emulator, API 26 always outputs one access unit (23ms) of audio more than API 33"
    (`TransformerEndToEndTest.java:1197`, `:1837`).
  - The muxer watchdog is longer on emulators (25 s vs 10 s, `Transformer.java:834-835`).
  - So a Transformer smoke test can run on an emulator. Pixel-exact output must not be asserted across API levels.
- **Size budget.**
  - Without an explicit bitrate, the Kush gauge requests 720 x 1280 x 30 x 0.07 x 2 = **3,870,720 bit/s**, about
    **14.5 MB** of video for 30 s.
  - With `setBitrate(1_500_000)` plus 64 kbit/s AAC, the target is (1.5 + 0.064) Mbit/s x 30 s / 8 = **about 5.9 MB**
    for 30 s.
  - Static slides under VBR usually come in well below the target (UNVERIFIED; measure `ExportResult.fileSizeBytes` in
    the instrumented test).
  - The admission check (section 9) reserves `(videoBitrate + audioBitrate) * seconds / 8 * 1.1` before export.
- **Length cap.** At most 90 s per generated video (product rule). Longer narrations are split into chapters.

### 3.5 Cancellation, progress and partial output

- **Cancel.**
  - Coroutine cancellation triggers `invokeOnCancellation`, which posts `Transformer.cancel()` to the main thread.
    `cancel()` releases codecs ("Resources like hardware video codecs are limited, especially on lower-end devices, so
    it's important to do this" [TR-GS]).
  - The wrapper then deletes `*.mp4.tmp`.
  - A completion racing with cancellation can leave a renamed `out` with no Room row. The orphan sweep (section 9)
    removes it.
- **Progress.**
  - The progress percentage is shown only when `PROGRESS_STATE_AVAILABLE`. `WAITING_FOR_AVAILABILITY` gets an
    indeterminate spinner.
  - `UNAVAILABLE` is not expected for local files; if it happens, keep the spinner.
- **Failure routing.**
  - `VIDEO_FRAME_PROCESSING_FAILED` (OpenGL path), `ENCODER_INIT_FAILED` and `ENCODING_FORMAT_UNSUPPORTED`: retry once
    with the MediaCodec fallback (section 4).
  - IO errors: re-render the inputs and retry once.
  - `MUXING_TIMEOUT`: usually the app went to the background; retry only when the screen is visible again.
  - All other codes: show "Couldn't make the video", keep the narration WAV, and offer audio-only playback.

## 4. Video fallback: MediaCodec + MediaMuxer

### 4.1 When it is used

Retry once with the fallback after Transformer fails with one of these:
- `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` (5001): the OpenGL frame path failed;
- `ERROR_CODE_ENCODER_INIT_FAILED` (4001) or `ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED` (4003);
- a device on a remote-config denylist.

The fallback uses no OpenGL: it converts the slide bitmap to I420 on the CPU and feeds `MediaCodec` byte-buffer/`Image`
input.

- **Scope of the sketch.** It encodes **one still frame** plus the narration WAV. For several slides, keep one
  `I420` per slide and switch at the slide boundaries from `SlideTimeline` (the same PTS arithmetic).
- **Cost.** All work runs on the calling coroutine's thread (use `Dispatchers.Default`), and it is cancellable between
  buffers. CPU cost is small for still frames (one RGB-to-YUV conversion per slide), but the encoder still receives
  every frame.

### 4.2 Code (compiled against android.jar 37 [MC])

```kotlin
// mediacheck/src/main/kotlin/agentle/media/video/StillVideoEncoder.kt
class StillVideoEncoder {
    data class Spec(
        val width: Int = 720,
        val height: Int = 1280,
        val frameRate: Int = 30,
        val videoBitrate: Int = 1_500_000,
        val iFrameIntervalSec: Int = 2,
        val audioBitrate: Int = 64_000,
        val tailMs: Long = 300,
    )

    enum class Reason { BAD_AUDIO_INPUT, NO_VIDEO_ENCODER, NO_AUDIO_ENCODER, CODEC_ERROR, MUXER_ERROR }

    sealed interface Result {
        data class Success(val file: File, val durationUs: Long, val sizeBytes: Long, val videoEncoder: String, val audioEncoder: String) : Result
        data class Failure(val reason: Reason, val cause: Throwable? = null) : Result
    }

    private class Pending(val isVideo: Boolean, val data: ByteArray, val info: MediaCodec.BufferInfo)

    suspend fun encode(frame: Bitmap, wavFile: File, out: File, spec: Spec = Spec()): Result {
        val wav = Wav.parse(wavFile)
        if (wav == null || !wav.isPcm16 || wav.channels !in 1..2 || wav.frameCount == 0L) {
            return Result.Failure(Reason.BAD_AUDIO_INPUT)
        }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var width = spec.width
        var height = spec.height
        var rotation = 0
        fun videoFormat(w: Int, h: Int) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, spec.iFrameIntervalSec)
        }
        var vFormat = videoFormat(width, height)
        var videoName = codecs.findEncoderForFormat(vFormat)
        if (videoName == null && height > width) { // many encoders only accept landscape sizes
            vFormat = videoFormat(height, width); videoName = codecs.findEncoderForFormat(vFormat)
            if (videoName != null) { width = spec.height; height = spec.width; rotation = 90 }
        }
        videoName ?: return Result.Failure(Reason.NO_VIDEO_ENCODER)
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val audioName = codecs.findEncoderForFormat(aFormat) ?: return Result.Failure(Reason.NO_AUDIO_ENCODER)

        val yuv = I420.fromBitmap(frame, width, height, rotation)
        val frameDurationUs = 1_000_000L / spec.frameRate
        val totalUs = wav.durationUs + spec.tailMs * 1000
        val frameCount = ceil(totalUs.toDouble() / frameDurationUs).toLong()

        var videoRef: MediaCodec? = null
        var audioRef: MediaCodec? = null
        var muxerRef: MediaMuxer? = null
        var muxerStarted = false
        var ok = false
        try {
            val video = MediaCodec.createByCodecName(videoName).also { videoRef = it }
            video.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            video.start()
            val audio = MediaCodec.createByCodecName(audioName).also { audioRef = it }
            audio.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audio.start()
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxerRef = it }
            muxer.setOrientationHint(rotation)
            var videoTrack = -1
            var audioTrack = -1
            val pending = ArrayList<Pending>()
            val info = MediaCodec.BufferInfo()

            fun drain(codec: MediaCodec, isVideo: Boolean): Boolean { // true once EOS was written
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val t = muxer.addTrack(codec.outputFormat)
                        if (isVideo) videoTrack = t else audioTrack = t
                        if (videoTrack >= 0 && audioTrack >= 0) {
                            muxer.start(); muxerStarted = true
                            for (p in pending) {
                                muxer.writeSampleData(if (p.isVideo) videoTrack else audioTrack, ByteBuffer.wrap(p.data), p.info)
                            }
                            pending.clear()
                        }
                        continue
                    }
                    if (index < 0) continue // INFO_OUTPUT_BUFFERS_CHANGED (deprecated) or other info codes
                    val buf = codec.getOutputBuffer(index)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        if (muxerStarted) {
                            muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
                        } else { // the other track has not reported its format yet
                            val copy = ByteArray(info.size)
                            buf.position(info.offset); buf.get(copy)
                            val copyInfo = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                            pending += Pending(isVideo, copy, copyInfo)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }

            RandomAccessFile(wavFile, "r").use { pcm ->
                pcm.seek(wav.dataOffset)
                var pcmRemaining = wav.dataSize
                var pcmFramesQueued = 0L
                var videoFramesQueued = 0L
                var videoInDone = false
                var audioInDone = false
                var videoOutDone = false
                var audioOutDone = false
                val readBuf = ByteArray(16 * 1024)
                while (!videoOutDone || !audioOutDone) {
                    currentCoroutineContext().ensureActive()
                    if (!videoInDone) {
                        val i = video.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val ptsUs = videoFramesQueued * frameDurationUs
                            if (videoFramesQueued >= frameCount) {
                                video.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                videoInDone = true
                            } else {
                                val capacity = video.getInputBuffer(i)!!.capacity()
                                yuv.writeTo(video.getInputImage(i)!!) // invalidates the ByteBuffer view
                                video.queueInputBuffer(i, 0, capacity, ptsUs, 0)
                                videoFramesQueued++
                            }
                        }
                    }
                    if (!audioInDone) {
                        val i = audio.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val inBuf = audio.getInputBuffer(i)!!
                            val n = minOf(inBuf.remaining().toLong(), readBuf.size.toLong(), pcmRemaining).toInt()
                                .let { it - it % wav.bytesPerFrame }
                            val ptsUs = pcmFramesQueued * 1_000_000L / wav.sampleRate
                            if (n <= 0) {
                                audio.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                audioInDone = true
                            } else {
                                pcm.readFully(readBuf, 0, n)
                                inBuf.put(readBuf, 0, n)
                                audio.queueInputBuffer(i, 0, n, ptsUs, 0)
                                pcmRemaining -= n
                                pcmFramesQueued += n / wav.bytesPerFrame
                            }
                        }
                    }
                    if (!videoOutDone) videoOutDone = drain(video, isVideo = true)
                    if (!audioOutDone) audioOutDone = drain(audio, isVideo = false)
                }
            }
            ok = muxerStarted
            return if (ok) Result.Success(out, totalUs, out.length(), videoName, audioName) else Result.Failure(Reason.MUXER_ERROR)
        } catch (e: IllegalStateException) { // MediaCodec.CodecException extends IllegalStateException
            return Result.Failure(Reason.CODEC_ERROR, e)
        } catch (e: java.io.IOException) {
            return Result.Failure(Reason.MUXER_ERROR, e)
        } finally {
            videoRef?.let { runCatching { it.stop() }; it.release() }
            audioRef?.let { runCatching { it.stop() }; it.release() }
            muxerRef?.let { if (muxerStarted) runCatching { it.stop() }; it.release() }
            if (!ok) out.delete()
        }
    }
}

/** Packed I420 copy of one frame, written into whatever plane layout the encoder's Image exposes. */
class I420 private constructor(val width: Int, val height: Int, private val y: ByteArray, private val u: ByteArray, private val v: ByteArray) {
    fun writeTo(image: android.media.Image) {
        val planes = image.planes
        copyPlane(y, width, height, planes[0])
        copyPlane(u, width / 2, height / 2, planes[1])
        copyPlane(v, width / 2, height / 2, planes[2])
    }

    private fun copyPlane(src: ByteArray, w: Int, h: Int, plane: android.media.Image.Plane) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (row in 0 until h) {
            val base = row * rowStride
            if (pixelStride == 1) {
                buf.position(base); buf.put(src, row * w, w)
            } else {
                for (col in 0 until w) buf.put(base + col * pixelStride, src[row * w + col])
            }
        }
    }

    companion object {
        fun fromBitmap(src: Bitmap, width: Int, height: Int, rotationDegrees: Int): I420 {
            val m = android.graphics.Matrix()
            if (rotationDegrees != 0) m.postRotate(-rotationDegrees.toFloat())
            val rotated = if (rotationDegrees == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val scaled = Bitmap.createScaledBitmap(rotated, width, height, true)
            val argb = IntArray(width * height).also { scaled.getPixels(it, 0, width, 0, 0, width, height) }
            val y = ByteArray(width * height); val u = ByteArray(width * height / 4); val v = ByteArray(width * height / 4)
            for (j in 0 until height) for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                // BT.601 limited range
                y[j * width + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val k = (j / 2) * (width / 2) + i / 2
                    u[k] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    v[k] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            }
            return I420(width, height, y, u, v)
        }
    }
}
```

### 4.3 Design notes and open points

- **Encoder selection.** `MediaCodecList(REGULAR_CODECS).findEncoderForFormat(format)` (API 21). A portrait size the
  encoder rejects is retried as landscape with `MediaMuxer.setOrientationHint(90)`. This mirrors Media3's default of
  rotating portrait frames before encoding (3.3).
- **Input.** `COLOR_FormatYUV420Flexible` with `getInputImage(index)` (API 21). The code honours each plane's
  `rowStride`/`pixelStride`, so semi-planar (NV12) and planar layouts both work.
  - **UNVERIFIED:** the `size` argument of `queueInputBuffer` after filling an `Image`. The sketch passes the input
    buffer's capacity. The alternative is `width * height * 3 / 2`. Decide with the emulator and device tests in
    section 12.
- **Timestamps.**
  - Video PTS = `frameIndex * 1e6 / fps`.
  - Audio PTS = `framesQueued * 1e6 / sampleRate`. Reads are aligned to whole PCM frames.
  - Both are deterministic, so tests can assert duration and sample count.
- **Muxer.**
  - `MediaMuxer.start()` waits until both encoders have reported `INFO_OUTPUT_FORMAT_CHANGED`. Samples produced before
    that are copied into `pending` and written after `start()`.
  - Codec-config buffers are skipped, because the muxer gets codec-specific data from the output format.
  - `stop()` is called only if the muxer started.
  - The output is deleted on any failure.
- **Exceptions.** `MediaCodec.CodecException` extends `IllegalStateException` and is mapped to `CODEC_ERROR`. IO
  exceptions map to `MUXER_ERROR`.
- **Robolectric** has `ShadowMediaCodec.addEncoder(name, CodecConfig)`, `ShadowMediaCodecList.addCodec(MediaCodecInfo)`
  with `MediaCodecInfoBuilder`, and `ShadowMediaMuxer` (javap of shadows-framework 4.17 [ROBO]). No `getInputImage`
  shadow was found, so the frame-writing path is emulator/device-only. The muxing state machine can be unit-tested if
  it is extracted behind an interface (section 12).

## 5. Playback in Compose (ExoPlayer / media3-ui-compose)

- **Libraries** [M3-COMPOSE]:
  - `media3-ui-compose` provides `PlayerSurface`, `ContentFrame` and the `remember*State` holders.
  - `media3-ui-compose-material3` adds the `Player` composable and styled controls (`PlayPauseButton`,
    `ProgressSlider`, and others).
  - Both are 1.11.1.
- **One player per screen; nothing in the background.**
  - No `MediaSession`/`MediaSessionService`. Agentle has no background playback (doc 02; Android 17 hardening
    [A17-BGA]).
  - The player pauses on `ON_STOP` and is released on dispose.
- **Focus and noisy handling.**
  - `setAudioAttributes(attrs, handleAudioFocus = true)` requests `AUDIOFOCUS_GAIN` for `USAGE_MEDIA`
    (`common/.../audio/AudioFocusManager.java:306-308`).
  - For `CONTENT_TYPE_SPEECH` the player pauses instead of ducking when it loses transient focus
    (`willPauseWhenDucked`, same file `:277-279`).
  - `setHandleAudioBecomingNoisy(true)` pauses on unplug. The default is false (`exoplayer/.../ExoPlayer.java:852-860`).
- **Formats.** ExoPlayer plays WAV and MP4 [EXO-FMT]. Generated assets are `file://` URIs in app-private storage.
  Bundled clips use `asset:///media/<name>.mp4`.
  - `ExoPlayer.Builder(context)` defaults to `DefaultMediaSourceFactory(context, ...)`
    (`exoplayer/.../ExoPlayer.java:373`).
  - Its `DefaultDataSource` sends the `asset` scheme ("e.g. `asset:///media.mp4`") and `file:///android_asset/` paths
    to `AssetDataSource` (`datasource/.../DefaultDataSource.java:42-43`, `:256-262`; sparse checkout added at the same
    1.11.1 tag).

```kotlin
@file:OptIn(UnstableApi::class)

package agentle.interventions.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.indicator.ProgressSlider

@Composable
fun InterventionMediaPlayer(
    uri: Uri,                   // file:// WAV/MP4 from the media store (section 9) or a bundled clip
    isVideo: Boolean,
    transcript: String,         // always shown: captions for muted users and TalkBack
    cover: ImageBitmap?,        // slide shown for audio-only voice deliveries
    autoplay: Boolean,          // deep link autoplay, already filtered by mute/DND rules (2.6)
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    LaunchedEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = autoplay
    }
    LifecycleStartEffect(player) {
        onStopOrDispose { player.pause() } // never keeps playing when the screen is not visible
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Column(modifier) {
        if (isVideo) {
            Player(player, Modifier.fillMaxWidth().aspectRatio(9f / 16f)) // ContentFrame + default M3 controls
        } else {
            cover?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth()) }
            Row {
                PlayPauseButton(player)
                ProgressSlider(player)
            }
        }
        Text(transcript)
    }
}
```

Verified signatures (1.11.1):
- `ContentFrame(player, modifier, surfaceType = SURFACE_TYPE_SURFACE_VIEW, contentScale = ContentScale.Fit,
  keepContentOnReset = false, shutter)`, `@UnstableApi` (`ui_compose/.../ContentFrame.kt:49-58`).
- `Player(player: Player?, modifier)`, `@UnstableApi`. The slot overload with `showControls`, `topControls`, ... is
  `@ExperimentalApi` (`ui_compose_material3/.../material3/Player.kt:55-57`, `:98-100`).
- `PlayPauseButton(player, modifier, ...)` (`material3/buttons/PlayPauseButton.kt:65-67`).
- `ProgressSlider(player, modifier)` (`material3/indicator/ProgressSlider.kt:45-47`).
- `rememberPooledPlayer(mediaItem, playerPool, playerSetup, playerTeardown)` exists (`ui_compose/.../lifecycle/player.kt:54`)
  but is not needed for a single player.

**Autoplay rule.**
- The notification tap is a user gesture, and the activity is visible, so the focus request succeeds.
- Autoplay anyway only when:
  - the stream is not muted (2.6);
  - the interruption filter is `ALL`;
  - for VOICE with the "headphones only" setting, `isPrivate` is true.
- Otherwise show the transcript and a Play button.

## 6. Images: local template rendering

### 6.1 Three ways to draw a card, and when to use each

| Approach | Runs in a worker | Robolectric | Use |
|---|---|---|---|
| **(a) `android.graphics`**: `Bitmap` + `Canvas` + `StaticLayout` + `LinearGradient` + `Path` | yes | yes, with `@GraphicsMode(NATIVE)` (SDK 26+) and Roborazzi `bitmap.captureRoboImage()` [R08][RZ] | **Default.** Notification cards (2:1), video slides (720x1280), share cards (1080x1080) |
| **(b) Compose off-screen**: `CanvasDrawScope().draw(density, layoutDirection, Canvas(imageBitmap), size) { ... }` with `ImageBitmap(w, h)`, then `imageBitmap.asAndroidBitmap()`; text through `TextMeasurer` and `drawText` | yes (no window needed) | yes | Only to reuse `DrawScope` chart code from the UI. Note: on androidx-main, `TextMeasurer(FontFamily.Resolver, Density, LayoutDirection, cacheSize)` is `@Deprecated("Replace with overload that takes a default locale list")`. The compose-ui release notes up to 1.13.0-alpha03 (2026-09-09) do not mention this change [CUI-REL], so with Compose 1.12.1 use the 4-argument constructor. Whether 1.12.1 already carries the deprecation is UNVERIFIED. |
| **(c) On-screen capture**: `rememberGraphicsLayer()` + `drawWithContent { graphicsLayer.record { drawContent() } }` + `graphicsLayer.toImageBitmap()` | no (needs a composed, attached node) | yes, in Compose UI tests | "Share this card" from a visible screen [CMP-DRAW] |

Sources:
- (b): `CanvasDrawScope.draw` (`ui-graphics/.../CanvasDrawScope.kt:525-531`); `Canvas(image: ImageBitmap)`
  (`Canvas.kt:26`); `ImageBitmap(...)` (`ImageBitmap.kt:234`); `ImageBitmap.asAndroidBitmap()`
  (`AndroidImageBitmap.android.kt:59`); `createFontFamilyResolver(context)` (`FontFamilyResolver.android.kt:38`);
  `TextMeasurer` (`TextMeasurer.kt:75-90`). All on androidx-main [AX].
- (c): the "Write contents of a composable to a bitmap" guide, available "from Compose 1.7.0-alpha07+" [CMP-DRAW].

### 6.2 Renderer (compiled against android.jar 37 [MC])

```kotlin
// mediacheck/src/main/kotlin/agentle/media/image/TemplateRenderer.kt
/** Colour theme tokens (ARGB ints); the app maps Material 3 dynamic or fixed schemes onto these. */
data class CardTheme(val backgroundTop: Int, val backgroundBottom: Int, val onBackground: Int, val accent: Int, val muted: Int)

data class SeriesPoint(val label: String, val value: Float)

sealed interface CardSpec {
    data class Quote(val text: String, val attribution: String?) : CardSpec
    data class Sparkline(val title: String, val unit: String, val points: List<SeriesPoint>, val goal: Float?) : CardSpec
}

/** Offline, deterministic image cards drawn with android.graphics only (works in a Worker, needs no window). */
object TemplateRenderer {
    /** 2:1 is the BigPictureStyle-friendly size; 9:16 (720x1280) is the video frame. */
    fun render(spec: CardSpec, theme: CardTheme, width: Int, height: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), theme.backgroundTop, theme.backgroundBottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val pad = 24f * density
        when (spec) {
            is CardSpec.Quote -> drawQuote(c, spec, theme, width, height, pad, density)
            is CardSpec.Sparkline -> drawSparkline(c, spec, theme, width, height, pad, density)
        }
        return bmp
    }

    private fun drawQuote(c: Canvas, s: CardSpec.Quote, t: CardTheme, w: Int, h: Int, pad: Float, d: Float) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.onBackground; textSize = 26f * d; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(s.text, 0, s.text.length, paint, (w - 2 * pad).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(8)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.translate(pad, (h - layout.height) / 2f)
        layout.draw(c)
        c.restore()
        s.attribution?.let {
            val small = TextPaint(paint).apply { textSize = 14f * d; color = t.muted; textAlign = Paint.Align.CENTER }
            c.drawText(it, w / 2f, h - pad, small)
        }
    }

    private fun drawSparkline(c: Canvas, s: CardSpec.Sparkline, t: CardTheme, w: Int, h: Int, pad: Float, d: Float) {
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = t.onBackground; textSize = 18f * d; isFakeBoldText = true }
        c.drawText(s.title, pad, pad + title.textSize, title)
        if (s.points.size < 2) return
        val area = RectF(pad, pad * 2 + title.textSize, w - pad, h - pad * 1.5f)
        val values = s.points.map { it.value } + listOfNotNull(s.goal)
        val min = values.min(); val max = values.max(); val span = (max - min).takeIf { it > 0f } ?: 1f
        fun x(i: Int) = area.left + area.width() * i / (s.points.size - 1)
        fun y(v: Float) = area.bottom - area.height() * (v - min) / span
        s.goal?.let { g ->
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = t.muted; strokeWidth = 1.5f * d; style = Paint.Style.STROKE
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * d, 6f * d), 0f)
            }
            c.drawLine(area.left, y(g), area.right, y(g), dash)
        }
        val path = Path().apply {
            s.points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.value)) else lineTo(x(i), y(p.value)) }
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.accent; strokeWidth = 3f * d; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        val last = s.points.last()
        c.drawCircle(x(s.points.lastIndex), y(last.value), 5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent })
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = t.muted; textSize = 12f * d }
        c.drawText(s.points.first().label, area.left, h - pad / 2, label)
        val lastLabel = "${last.label}: ${last.value} ${s.unit}"
        c.drawText(lastLabel, area.right - label.measureText(lastLabel), h - pad / 2, label)
    }

    /** PNG is lossless and needs no quality tuning; write to a temp file then rename (atomic on the same dir). */
    fun writePng(bitmap: Bitmap, target: File): Long {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { os ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) { "PNG encode failed" }
            os.fd.sync()
        }
        check(tmp.renameTo(target)) { "rename failed" }
        return target.length()
    }
}
```

### 6.3 Template rules

- **Use in JITAI deliveries.**
  - Doc 10 section 3.3 allows IMAGE (and VIDEO) only with `local_media`, an `assetId` from the bundled catalog. E069
    rejects IMAGE without it [R10].
  - So in v1 as written, a notification picture is a bundled 2:1 image from `assets/media/`. Decode it at Render time
    with `BitmapFactory` (`inJustDecodeBounds`, then `inSampleSize` to at most 1024 px wide).
  - Template cards then serve video slides, share cards and the in-app intervention screen.
  - **Proposal (13.2):** a `card` content strategy for IMAGE. Fields:
    - `templateId`: `QUOTE`, `SPARKLINE`, `STAT` or `STREAK`;
    - `fields`: with the same placeholder rules as `template`;
    - `caption`;
    - `altText`.
  - The worker renders a `card` with `TemplateRenderer` during Render, so IMAGE deliveries can show the user's own data
    without AI image generation. If doc 10 keeps v1 minimal, nothing here depends on the proposal.
- **Templates.** v1 has `Quote`, `Sparkline` (with optional goal line) and, to add, `Stat` (one big number plus label)
  and `Streak` (N of M days). Each is a sealed `CardSpec`, so AI-written rules can only pick a template id and fill
  validated fields (doc 10 section 11). AI never supplies drawing code or colours.
- **Themes.**
  - `CardTheme` takes ARGB tokens from the app's Material 3 scheme: `dynamicLightColorScheme(context)` /
    `dynamicDarkColorScheme(context)` on 31+, otherwise the fixed brand scheme.
  - Render the variant that matches the system night mode at render time, and store `themeId` with the asset
    (section 9), because a delivered notification cannot re-theme itself.
- **Sizes.**
  - Notification: **1024x512** (2:1). ARGB_8888 is 2 MiB in memory and typically far less as PNG.
  - Video slide: **720x1280**, 3.5 MiB in memory.
  - Share: 1080x1080.
  - Render serially and recycle bitmaps after encoding.
- **Text.**
  - `StaticLayout.Builder` (API 23) with max lines and an ellipsis, so text never overflows.
  - Numbers are formatted with the user's locale before drawing.
  - RTL text works through `StaticLayout`'s default text direction heuristics.
  - Use a bundled font (`ResourcesCompat.getFont`), so output does not depend on OEM fonts and screenshot tests are
    stable.
- **Accessibility.** Every card carries a text alternative (`pictureDescription`), used for
  `BigPictureStyle.setContentDescription` (31+) and `contentDescription` in the app.
- **Encoding.**
  - PNG (lossless; the quality argument is ignored), written with temp file, `fsync` and rename.
  - `Bitmap.CompressFormat.WEBP_LOSSLESS` (API 30) gives smaller files when `minSdk >= 30`. Its size advantage on
    these cards is UNVERIFIED; measure before switching.
  - Never JPEG for text cards (artifacts around glyphs).
- **Robolectric.**
  - Render with `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and compare with Roborazzi `bitmap.captureRoboImage()`
    (README 1.76.0, section "Bitmap" [RZ]).
  - Native graphics needs SDK >= 26 [R08].
  - The legacy (non-native) graphics mode records draw calls instead of pixels, so assert pixels only in NATIVE mode.

## 7. Images: provider AI generation (capability-gated)

### 7.1 Capability model

```kotlin
/** Declared by each AI provider adapter (doc 06 owns the provider layer); the engine never assumes a capability. */
data class ProviderCapabilities(
    val textGeneration: Boolean,
    val imageGeneration: Boolean,
    val speechSynthesis: Boolean,
    val maxImagePixels: Int = 0,      // 0 = not applicable
)

// v1: Sign in with ChatGPT supports streamed text only; "image generation, audio or video" are unsupported [R06 4.4].
val SIWC_CAPABILITIES = ProviderCapabilities(textGeneration = true, imageGeneration = false, speechSynthesis = false)
```

- **v1 result: there is no AI image route.**
  - Doc 10 already limits AI to text: "Why AI only writes text (from doc 06)" (doc 10 section 3.3).
  - So the DSL has no `ai_image` content strategy.
  - Images come from templates (section 6) or the bundled catalog (`local_media`).
- **If a later provider sets `imageGeneration = true`**, the contract is:
  1. **Never on the delivery path.** Pre-generate into a small per-JITAI pool while charging, like `ai_text` (doc 10
     section 3.3), within the local AI budget.
  2. **Validate the bytes.**
     - Magic number is PNG, JPEG or WebP.
     - At most 8 MB, and at most 2048x2048 after decoding.
     - Decode with `ImageDecoder` (28+) or `BitmapFactory` with `inSampleSize`.
     - **Re-encode** to PNG/WebP to strip metadata.
  3. **Store** as `media_asset(kind = IMAGE, origin = AI, provider, promptHash, createdAt)` (section 9), with an
     "AI-generated" label in the UI.
  4. **Always have a fallback**: a template card when generation is unavailable, over budget or fails validation.
  5. **Prompts** contain only minimized, user-approved context (doc 06 section 4.5): never raw health values, names or
     locations.
- **Cloud image APIs are UNVERIFIED in this run.** This covers OpenAI image endpoints and Imagen through Firebase AI
  Logic. developers.openai.com and firebase.google.com did not respond to curl (HTTP 000), and they are outside the v1
  provider set anyway.

## 8. Images: on-device generative options on Android

| Option | Generates images? | Evidence | Verdict |
|---|---|---|---|
| Android framework (android.jar 37) | **No**: none of the 6,440 classes in `android.jar` 37 matches `ImageGenerat*`, `ondeviceintelligence` or `Diffusion` | `unzip -l android.jar` [SDK37] | - |
| ML Kit GenAI APIs (Gemini Nano through AICore) | **No**. Listed features: "Prompt: Generate text content based on a custom text-only or multimodal prompt", Summarization, Proofreading, Rewriting, "Image Description: Generate a short description of a given image", Speech Recognition | [AI-NANO]; the Prompt API is described for "image understanding" [AI-OVR]; Prompt API alpha announcement [MLKIT-BLOG] | Useful later for text only (on-device `ai_text`); out of scope here |
| **MediaPipe Image Generator** (`com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator`) | **Yes**: text-to-image, optional condition image (face, edge, depth plugins) and LoRA weights | Source at tag `v1.0.0` [MP] | **Not for v1**, see below |
| Cloud (Imagen via Firebase AI Logic, OpenAI images) | Yes (cloud) | Not reachable in this run | Provider-capability path (section 7); not v1 |

**MediaPipe Image Generator facts** (from source at `v1.0.0`):
- **Model.** `ImageGeneratorOptions.ModelType` has only `SD_1`: "Stable Diffusion v1 models, including SD 1.4 and 1.5"
  (`ImageGenerator.java:539`).
- **Output size.** Fixed 512x512: `GENERATED_IMAGE_WIDTH = 512`, `GENERATED_IMAGE_HEIGHT = 512`
  (`ImageGenerator.java:75-76`).
- **API.**
  - `ImageGenerator.createFromOptions(context, ImageGeneratorOptions[, ConditionOptions])` (`:94`, `:108`).
  - `generate(prompt, iterations, seed)` (`:248`), or with a condition image (`:272`).
  - Iterative use: `setInputs(prompt, iterations, seed)` + `execute(showResult)` (`:300`, `:368`), so the UI can show
    intermediate steps.
- **Options.** `setImageGeneratorModelDirectory(String)` points at converted weights on disk;
  `setLoraWeightsFilePath(String)` (`:547-550`).
- **Native code.** It ships JNI (`libmediapipe_tasks_vision_image_generator_jni.so`) and `libimagegenerator_gpu.so`
  (`BUILD`). The AAR manifest declares `minSdkVersion 24` (`AndroidManifest.xml`).
- **Why not v1.**
  - The weights are not in the AAR; the app would have to download and convert or host a model of several hundred MB
    or more (size UNVERIFIED; the ai.google.dev guide was unreachable).
  - SD 1.x weights carry their own licence terms, which need legal review.
  - Output is a square 512x512, which does not fit the 2:1 notification or 9:16 slides without cropping.
  - Generation is GPU-heavy. The Java task API exposes no safety filter option: a grep of `ImageGenerator.java` at
    `v1.0.0` finds no safety, NSFW or filter symbol. The app would need its own filter.
  - It cannot run in Robolectric.
  - Maven coordinates and version are UNVERIFIED (Google Maven blocked).
- **If revisited:**
  - only on demand from a visible screen, while charging, after explicit opt-in;
  - a model download flow that resumes and checks a checksum;
  - a content filter;
  - a template fallback.

## 9. Media storage, metadata, quota, LRU cleanup, deletion

### 9.1 Layout

```
<noBackupFilesDir>/media/
    image/<assetId>.png
    audio/<assetId>.wav
    video/<assetId>.mp4
    (temp files <name>.tmp and <name>.partN.tmp live next to their target, so rename is atomic)
<cacheDir>/share/<assetId>.<ext>     copies made only for the share sheet (FileProvider <cache-path>)
APK assets/media/...                 bundled catalog (doc 10 local_media), read-only, never copied
```

Why each place:
- **`noBackupFilesDir`, not `filesDir`.**
  - Auto Backup includes `getFilesDir()`. Files under `getCacheDir()`, `getCodeCacheDir()` or `getNoBackupFilesDir()`
    "are always excluded even if you try to include them" [AUTOBACKUP].
  - Every app gets "up to 25 MB of backup data per app user". "If the amount of data is over 25 MB, the system calls
    onQuotaExceeded() and doesn't back up data to the cloud" [AUTOBACKUP].
  - A few videos in `filesDir` would therefore silently stop backup of everything else.
  - Media is regenerable and health-derived, so it should not go to cloud backup anyway.
- **Not `cacheDir`.** "When the device is low on internal storage space, Android may delete these cache files"
  [APPSPEC]. A JITAI notification could then point at a vanished file.
- **Sharing.**
  - `FileProvider` path tags are `root-path`, `files-path`, `cache-path`, `external-path`, `external-files-path`,
    `external-cache-path` and `external-media-path`. There is no no-backup root (`FileProvider.java:358-364` [AX-FP]).
  - So "Share" copies the file to `cacheDir/share/` and exposes it with `<cache-path name="share" path="share/"/>`.
  - Grant read access explicitly: `FLAG_GRANT_READ_URI_PERMISSION` + `ClipData`. Starting in Android 18, the system will
    no longer grant URI permissions automatically for `ACTION_SEND` [A17-ALL].
  - A daily maintenance pass deletes share copies older than 24 h.
- **Uninstall** removes all app-specific storage [APPSPEC].

### 9.2 Metadata (Room 3; packages `androidx.room3.*` per doc 07; not compiled here)

```kotlin
enum class MediaKind { IMAGE, AUDIO, VIDEO }
enum class MediaOrigin { TEMPLATE, TTS, COMPOSED, FALLBACK_ENCODER, AI }
enum class MediaState { READY, MISSING, DELETING }

@Entity(
    tableName = "media_asset",
    indices = [Index(value = ["sourceKey"], unique = true), Index(value = ["kind", "lastAccessedAt"])],
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,              // UUID, also the file name stem
    val kind: MediaKind,
    val origin: MediaOrigin,
    val relPath: String,                     // "media/audio/<id>.wav", relative to noBackupFilesDir
    val mimeType: String,                    // image/png, audio/wav, video/mp4
    val sizeBytes: Long,
    val sha256: String,                      // checked before sharing
    val width: Int?, val height: Int?,       // as stored (a portrait MP4 may be stored rotated, 3.3)
    val durationMs: Long?,
    val sampleRate: Int?, val channels: Int?,
    val sourceKey: String,                   // hash(template id, data, themeId, locale, voice): dedupe identical renders
    val description: String,                // alt text or transcript (accessibility, captions)
    val ttsEngine: String?, val ttsVoice: String?, val networkVoice: Boolean,
    val themeId: String?,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val expiresAt: Long?,                    // e.g. createdAt + 14 days for JITAI media
    val pinned: Boolean,                     // user tapped "Keep"
    val state: MediaState,
)

/** Which deliveries use which assets; pending = notification posted or scheduled and not yet expired. */
@Entity(tableName = "delivery_media", primaryKeys = ["decisionKey", "assetId"], indices = [Index("assetId")])
data class DeliveryMediaRef(val decisionKey: String, val assetId: String, val pending: Boolean)
```

The eviction policy reads a projection of these rows (`MediaAssetRecord`, with
`referencedByPendingDelivery = exists(delivery_media where pending)`).

### 9.3 Quota and LRU eviction (compiled + 5 JVM tests [MC])

```kotlin
// mediacheck/src/main/kotlin/agentle/media/storage/MediaQuota.kt
data class MediaAssetRecord(
    val id: String, val kind: MediaKind, val sizeBytes: Long, val createdAtMs: Long, val lastAccessedAtMs: Long,
    val pinned: Boolean, val referencedByPendingDelivery: Boolean, val expiresAtMs: Long?,
)

data class MediaQuota(
    val totalBytes: Long = 200L * 1024 * 1024,
    val perKindBytes: Map<MediaKind, Long> = mapOf(
        MediaKind.VIDEO to 120L * 1024 * 1024,
        MediaKind.AUDIO to 40L * 1024 * 1024,
        MediaKind.IMAGE to 60L * 1024 * 1024,
    ),
    /** Never evict something younger than this (it may be about to be shown). */
    val minAgeMs: Long = 10 * 60 * 1000L,
)

object MediaEvictionPolicy {
    /**
     * Returns the ids to delete, oldest-used first: expired rows first, then LRU until every per-kind
     * cap and the total cap hold. Pinned rows and rows referenced by an undelivered JITAI are never
     * selected, so the result may leave the store over quota (caller then stops generating new media).
     */
    fun select(records: List<MediaAssetRecord>, quota: MediaQuota, nowMs: Long): List<String> {
        val evictable = { r: MediaAssetRecord ->
            !r.pinned && !r.referencedByPendingDelivery && nowMs - r.createdAtMs >= quota.minAgeMs
        }
        val chosen = LinkedHashSet<String>()
        records.filter { evictable(it) && it.expiresAtMs != null && it.expiresAtMs <= nowMs }
            .sortedBy { it.expiresAtMs }
            .forEach { chosen += it.id }

        fun used(kind: MediaKind?) = records.filter { it.id !in chosen && (kind == null || it.kind == kind) }
            .sumOf { it.sizeBytes }

        val lru = records.filter { evictable(it) && it.id !in chosen }
            .sortedWith(compareBy<MediaAssetRecord> { it.lastAccessedAtMs }.thenBy { it.createdAtMs })
        for ((kind, cap) in quota.perKindBytes) {
            var over = used(kind) - cap
            for (r in lru) {
                if (over <= 0) break
                if (r.kind == kind && r.id !in chosen) { chosen += r.id; over -= r.sizeBytes }
            }
        }
        var overTotal = used(null) - quota.totalBytes
        for (r in lru) {
            if (overTotal <= 0) break
            if (r.id !in chosen) { chosen += r.id; overTotal -= r.sizeBytes }
        }
        return chosen.toList()
    }

    /** Eviction plan that also makes room for a new asset before it is generated. */
    data class Admission(val evictIds: List<String>, val admitted: Boolean)

    fun planAdmission(
        records: List<MediaAssetRecord>, quota: MediaQuota, kind: MediaKind, incomingBytes: Long, nowMs: Long,
    ): Admission {
        val reduced = quota.copy(
            totalBytes = quota.totalBytes - incomingBytes,
            perKindBytes = quota.perKindBytes + (kind to ((quota.perKindBytes[kind] ?: quota.totalBytes) - incomingBytes)),
        )
        val evict = select(records, reduced, nowMs)
        val kept = records.filter { it.id !in evict.toSet() }
        val kindCap = quota.perKindBytes[kind] ?: quota.totalBytes
        val fits = kept.filter { it.kind == kind }.sumOf { it.sizeBytes } + incomingBytes <= kindCap &&
            kept.sumOf { it.sizeBytes } + incomingBytes <= quota.totalBytes
        return Admission(evict, fits)
    }
}
```

Tests (Appendix A):
- under quota, only expired rows go;
- a per-kind cap evicts the least recently used item of that kind;
- pinned, pending and young rows are never evicted;
- the total cap evicts across kinds;
- admission makes room, or refuses when everything is pinned.

**Caps.** 200 MB total (video 120, audio 40, image 60) is a product default, editable in Settings. Typical sizes:
- WAV, 24 kHz mono 16-bit (sample rate assumed; engines vary): about 2.9 MB per minute.
- MP4: about 6 MB per 30 s at the section 3.4 bitrates.
- PNG card: well under 1 MB.

### 9.4 Write, admit, reconcile, delete

1. **Estimate** the size before generating.
   - WAV: `sampleRate * 2 * seconds`. Before synthesis, assume 24 kHz and about 15 characters per second.
   - Video: `(videoBitrate + audioBitrate) * seconds / 8 * 1.1`.
   - PNG: 1 MB.
2. **Admit.**
   - Run `planAdmission(...)`.
   - If not admitted: skip generation, deliver the text fallback, and record `MEDIA_QUOTA` on the delivery.
   - If admitted: delete the `evictIds`. Mark the rows `DELETING` in a transaction, delete the files, then delete the
     rows.
3. **Check free space.**
   - `val sm = context.getSystemService(StorageManager::class.java); sm.getAllocatableBytes(sm.getUuidForPath(dir))`
     (API 26) must be at least the estimate plus 50 MB. Optionally reserve with `allocateBytes(uuid, bytes)` [APPSPEC].
   - On a visible screen, a shortfall offers `Intent(StorageManager.ACTION_MANAGE_STORAGE)` [APPSPEC]. In a worker,
     it just falls back to text.
4. **Write** to `*.tmp`, validate (WAV header, PNG decode bounds, `ExportResult`), then `fsync` and rename. Insert the
   row and the `delivery_media` ref in one transaction. If the insert fails, delete the file.
5. **Touch.** Set `lastAccessedAt` when the user opens or plays the asset, not when the notification is posted. While
   a notification is live, `delivery_media.pending = true` protects the asset. Clear it when the notification times
   out (`setTimeoutAfter`), is dismissed (`setDeleteIntent`), or is opened.
6. **Reconcile.** Run at app start (one-time work) and in the daily maintenance worker (doc 02):
   - delete files under `media/` with no row whose mtime is older than 1 h (crash between rename and insert, or a
     cancelled export that renamed late);
   - delete `*.tmp` older than 1 h;
   - mark rows whose file is missing as `MISSING`, and drop their deliveries' media (they fall back to text).
7. **User deletion.**
   - Settings, "Delete generated media": remove all `media_asset` rows and their files, then `cacheDir/share/`.
     Bundled catalog assets live in the APK and have no rows.
   - Deleting a JITAI clears its refs. Its assets then become evictable at the next pass.
   - Any app-wide "delete my data" flow must include media. No sibling doc defines that flow yet; open item.

## 10. Notifications with media

### 10.1 Rules

| Topic | Rule | Source |
|---|---|---|
| Permission | `POST_NOTIFICATIONS` (API 33). "If a user installs your app on a device that runs Android 13 or higher, your app's notifications are off by default". After "Don't allow", "your app can't send notifications unless it qualifies for an exemption". Agentle asks in context (the first JITAI setup), never at launch. | [NOTIF-PERM] |
| Checks before every post | (1) runtime permission on 33+; (2) `NotificationManagerCompat.areNotificationsEnabled()` ("Before your app sends a notification, confirm whether the user has enabled notifications"); (3) channel importance is not `IMPORTANCE_NONE`. Any failure gives doc 10's `FAILED(NOTIFICATIONS_BLOCKED)` plus an in-app notice. | [NOTIF-PERM]; doc 10 section 8.5 step 2 |
| Channels | `jitai_nudge` (`IMPORTANCE_DEFAULT`, the doc 02 choice) and `jitai_quiet` (`IMPORTANCE_LOW`, no sound or vibration), in group `checkins`. Created at app start (idempotent). Users can change them, so always read the importance before posting. | [NOTIF-CH]; [R02] |
| Content | `CATEGORY_REMINDER`; `VISIBILITY_PRIVATE` with a neutral `setPublicVersion` (health content never shows on the lock screen); `setOnlyAlertOnce(true)`; `setTimeoutAfter(...)` (26) so stale nudges disappear; `setAutoCancel(true)`; `notify(tag = decisionKey, id = 1, ...)` (doc 10 idempotency) | doc 10 section 8.5 |
| Big picture | `NotificationCompat.BigPictureStyle().bigPicture(bitmap)`; `bigLargeIcon(null as Bitmap?)` hides the large icon when expanded; `showBigPictureWhenCollapsed(true)` and `setContentDescription(alt)` are `@RequiresApi(31)` in compat, so guard with `SDK_INT >= 31`; below 31, `setLargeIcon(bitmap)` shows a thumbnail when collapsed | [NOTIF-EXP][NC-BPS][AX-CORE] `NotificationCompat.java:3388-3450` |
| Actions | Done / Snooze: `PendingIntent.getBroadcast` to a receiver declared `android:exported="false"`. The receiver uses `goAsync()` and updates Room or enqueues work, then `NotificationManagerCompat.cancel(tag, 1)`, and **never starts an activity**. Listen / Watch: `PendingIntent.getActivity` straight to `MainActivity`. | Android 12 trampolines: apps "can't start activities from services or broadcast receivers that are used as notification trampolines" [A12-TGT] |
| PendingIntent flags | `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`, an explicit component, and a distinct data URI per (decisionKey, action). `filterEquals` includes the data, so PendingIntents never collide. | "If your app targets Android 12, you must specify the mutability of each PendingIntent" [A12-TGT]; [NOTIF-NAV]; [R02] T-ARCH-04 |
| Full-screen intents | **Never.** "Notifications containing full-screen intents are substantially intrusive", and `USE_FULL_SCREEN_INTENT` is for time-sensitive calls and alarms. | [NOTIF-BUILD]; [R02] |
| Custom views | None. Android 17 (target 37) enforces a RemoteViews bitmap memory limit (1.5 x screen width x screen height x 4 bytes). The page describes it for widgets (`UpdateAppWidget`); whether it also covers notification RemoteViews is UNVERIFIED. A 1024x512 big picture (2 MiB) is far below the limit either way. | [A17-TGT] |
| Sound | Only the channel's own alert. The app never plays audio from the background (doc 02; [A17-BGA]). | [R02] |

### 10.2 Notifier (production sketch with NotificationCompat; method names verified on androidx-main [AX-CORE])

The same logic written against the platform `Notification` API compiles against android.jar 37 (Appendix A,
`JitaiNotifier.kt`).

```kotlin
class JitaiNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        nm.createNotificationChannelGroup(
            NotificationChannelGroupCompat.Builder(GROUP).setName(context.getString(R.string.ch_group_checkins)).build(),
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_NUDGE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.ch_nudges))
                .setDescription(context.getString(R.string.ch_nudges_desc))
                .setGroup(GROUP)
                .build(),
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_QUIET, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.ch_quiet))
                .setGroup(GROUP)
                .setSound(null, null)
                .setVibrationEnabled(false)
                .build(),
        )
    }

    /** Called by the delivery worker (doc 10 section 8.5 step 4). */
    fun post(n: JitaiNotice, channelId: String = CH_NUDGE): NotifyOutcome {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return NotifyOutcome.PERMISSION_DENIED
        if (!nm.areNotificationsEnabled()) return NotifyOutcome.APP_NOTIFICATIONS_OFF
        if (nm.getNotificationChannelCompat(channelId)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            return NotifyOutcome.CHANNEL_BLOCKED
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_agentle)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_stat_agentle)
                    .setContentTitle(context.getString(R.string.notif_public_title)) // "Agentle check-in"
                    .build(),
            )
            .setContentIntent(activityIntent(n.decisionKey, autoplay = null))
            .setDeleteIntent(broadcast(n.decisionKey, ACTION_DISMISSED))           // clears delivery_media.pending
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(n.timeoutMs)                                             // doc 10 delivery deadline
            .addAction(R.drawable.ic_done, context.getString(R.string.action_done), broadcast(n.decisionKey, ACTION_DONE))
            .addAction(R.drawable.ic_snooze, context.getString(R.string.action_snooze_1h), broadcast(n.decisionKey, ACTION_SNOOZE))
        if (n.hasVoice || n.hasVideo) {
            val autoplay = if (n.hasVideo) "video" else "voice"
            val label = if (n.hasVideo) R.string.action_watch else R.string.action_listen
            builder.addAction(R.drawable.ic_play, context.getString(label), activityIntent(n.decisionKey, autoplay))
        }
        n.picture?.let { pic -> // 1024x512 card from TemplateRenderer
            val style = NotificationCompat.BigPictureStyle().bigPicture(pic).bigLargeIcon(null as Bitmap?)
            if (Build.VERSION.SDK_INT >= 31) {
                style.showBigPictureWhenCollapsed(true)
                n.pictureDescription?.let { style.setContentDescription(it) }
            } else {
                builder.setLargeIcon(pic)
            }
            builder.setStyle(style)
        }
        nm.notify(n.decisionKey, NOTIFICATION_ID, builder.build()) // @RequiresPermission satisfied by the check above
        return NotifyOutcome.POSTED
    }

    private fun activityIntent(decisionKey: String, autoplay: String?): PendingIntent {
        val uri = Uri.Builder().scheme("agentle").authority("intervention").appendPath(decisionKey)
            .apply { if (autoplay != null) appendQueryParameter("autoplay", autoplay) }.build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setClass(context, MainActivity::class.java) // explicit: no intent-filter, no other app can match
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun broadcast(decisionKey: String, action: String): PendingIntent {
        // Decision keys contain '|' and ':' (doc 10 section 8.2), so build the URI with appendPath (percent-encodes).
        val uri = Uri.Builder().scheme("agentle").authority("delivery").appendPath(decisionKey).appendPath(action).build()
        val intent = Intent(action).setClass(context, JitaiActionReceiver::class.java)
            .setData(uri)                                                       // distinct per (key, action)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val NOTIFICATION_ID = 1 // doc 10: tag = decisionKey, id = 1
        const val GROUP = "checkins"
        const val CH_NUDGE = "jitai_nudge"
        const val CH_QUIET = "jitai_quiet"
        const val ACTION_DONE = "agentle.action.DONE"
        const val ACTION_SNOOZE = "agentle.action.SNOOZE"
        const val ACTION_DISMISSED = "agentle.action.DISMISSED"
    }
}

enum class NotifyOutcome { POSTED, PERMISSION_DENIED, APP_NOTIFICATIONS_OFF, CHANNEL_BLOCKED }

data class JitaiNotice(
    val decisionKey: String,
    val title: String,
    val text: String,
    val picture: Bitmap?,            // <= 1024x512, 2:1
    val pictureDescription: String?,
    val hasVoice: Boolean,
    val hasVideo: Boolean,
    val timeoutMs: Long,
)
```

### 10.3 Deep link handling

- **No intent filter.** `MainActivity` has no intent filter for `agentle://`, so only the app's own explicit,
  immutable PendingIntents carry these URIs.
- **Validate anyway.** `MainActivity` is exported as the launcher, so any app can send it an explicit intent with an
  arbitrary `data` URI. Validate the URI:
  - the scheme and host are known;
  - the `decisionKey` exists in Room and is `DELIVERED`;
  - the asset is `READY`.

  Otherwise open the home screen.
- **Delivery to the activity.** Handle the URI in `onCreate` and `onNewIntent` (`FLAG_ACTIVITY_SINGLE_TOP` delivers to
  the existing instance), and push the intervention screen onto the Navigation 3 back stack.
- **Bookkeeping.** Record "opened" (doc 10 outcomes) and clear `delivery_media.pending`.
- **Autoplay** starts only after `ON_RESUME`, and only under the section 5 autoplay rule.
- **Key decoding.** Read the key with `uri.pathSegments[0]` (decoded), never by splitting `uri.toString()`. Keys contain
  `|` and `:` (doc 10 section 8.2), which `appendPath` percent-encodes.
- **Missing media.** If the asset is gone (evicted, deleted or `MISSING`), show the text and template image and offer
  "Regenerate" for voice (synthesis is cheap).

## 11. Failure-state matrix

The "Delivery" column follows a proposed rule.
- Doc 10 defines a fallback only for `ai_text`: the fallback template "is always delivered if generation is
  unavailable, over budget or fails validation". It also uses `FAILED(reason)` for permanent errors such as
  `NOTIFICATIONS_BLOCKED` [R10].
- This doc applies the same idea to media. When a media part fails, the delivery is downgraded to NOTIFICATION. The
  text comes from the VOICE text, or from the `local_media.caption` for IMAGE and VIDEO. The delivery records a reason:
  - `TTS_UNAVAILABLE` (F1-F9);
  - `MEDIA_QUOTA` (F23);
  - `IMAGE_UNAVAILABLE` (F22);
  - `VIDEO_UNAVAILABLE` (bundled clip missing; see F14).
- Media failures never block a delivery. This rule is a change request for doc 10 (section 13.2).

| # | Area | Failure | Detected by | Handling | Delivery | Lowest test level |
|---|---|---|---|---|---|---|
| F1 | TTS | No engine installed, or `<queries>` missing | `engines` empty | `EngineUnavailable(status)`; Settings card | text, reason `TTS_UNAVAILABLE` | Robolectric (no fake engine registered) |
| F2 | TTS | Init `ERROR` or init timeout (5 s) | `OnInitListener(ERROR)`; `withTimeoutOrNull` returns null | same as F1; the engine is always shut down in `finally` | text | Robolectric (fire `onInit(ERROR)`; or never fire it, for the timeout) |
| F3 | TTS | Language data missing | `LANG_MISSING_DATA` or `onError(ERROR_NOT_INSTALLED_YET)` | `LanguageMissingData`; offer `ACTION_INSTALL_TTS_DATA` next time the app is visible | text | Robolectric custom shadow (the stock shadow cannot return -1) |
| F4 | TTS | Language unsupported, or only network voices without consent | `LANG_NOT_SUPPORTED`; voice filter finds nothing | `LanguageNotSupported` / `NoOfflineVoice` | text | Robolectric (`addLanguageAvailability`, `addVoice` with `requiresNetworkConnection = true`) |
| F5 | TTS | Request not queued | `synthesizeToFile` returns `ERROR` | `QueueRejected` | text | Robolectric custom shadow |
| F6 | TTS | Synthesis error, service death or output error | `onError(id, ERROR_SYNTHESIS / SERVICE / OUTPUT / NETWORK*)` | `SynthesisError(code)`; temp files deleted | text | Robolectric (`simulateSynthesizeToFileResult(ERROR_SYNTHESIS)`) |
| F7 | TTS | Stopped or flushed | `onStop(id, interrupted)` | `Stopped`; partial files deleted | text | Robolectric custom shadow |
| F8 | TTS | Engine hangs | per-chunk timeout | `Timeout`; `stop()` + `shutdown()` | text | Robolectric (never call the listener) |
| F9 | TTS | File is not a WAV, empty, or wrong format | `Wav.parse` null, `!isPcm16`, or 0 frames | `InvalidOutput` | text | JVM (`WavTest`) + Robolectric (stock shadow writes text) |
| F10 | Playback | Stream muted / volume 0 | `isStreamMute` / `getStreamVolume` | No autoplay; text plus "Volume is off" | n/a (in-app) | Robolectric `ShadowAudioManager` |
| F11 | Playback | Not private while "headphones only" is set | route classification | `SkippedNotPrivate`; text | n/a | Robolectric (`setAudioDevicesForAttributes`, `addOutputDevice`) |
| F12 | Playback | Focus denied (call active, not the top app, Android 17 hardening) | `AUDIOFOCUS_REQUEST_FAILED` | `FocusDenied`; Play button stays | n/a | Robolectric (`setNextFocusRequestResponse(FAILED)`); emulator API 37 with `set-enable-hardening throw` |
| F13 | Playback | Focus lost, or headphones / BT unplugged | `AUDIOFOCUS_LOSS*`; `ACTION_AUDIO_BECOMING_NOISY` | Stop or pause; Resume button | n/a | Robolectric (invoke `lastAudioFocusRequest.listener`; send the broadcast) |
| F14 | Playback | Asset missing (evicted or deleted) | row `MISSING`, or file absent | Show text and template; "Regenerate" | n/a | Robolectric |
| F15 | Video | Frame processing (GL) failed | `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` (5001) | Delete tmp; retry once with `StillVideoEncoder` | n/a (in-app) | Emulator (inject a failing effect); routing also JVM (code-to-outcome mapping) |
| F16 | Video | Encoder init failed or format unsupported | 4001 / 4003 (after Transformer's own fallback) | Same as F15 | n/a | Emulator (failing encoder factory) |
| F17 | Video | Muxer stalled (app went to background) | 7002 `MUXING_TIMEOUT` | Delete tmp; retry when visible | n/a | Emulator |
| F18 | Video | IO or decode failure on inputs | 2xxx / 3xxx | Re-render inputs once; then give up | n/a | Emulator |
| F19 | Video | User left the screen | coroutine cancellation | `Transformer.cancel()` on main; delete tmp; keep inputs | n/a | Emulator |
| F20 | Video | Not enough storage | `getAllocatableBytes` < need | `ACTION_MANAGE_STORAGE` prompt; no export | n/a | Robolectric (fake `StorageChecker`) |
| F21 | Fallback | No AVC/AAC encoder; codec or muxer error | `findEncoderForFormat` null; `CodecException`; `IOException` | `Failure(reason)`; offer audio-only | n/a | Emulator (+ Robolectric `ShadowMediaCodecList` for the "no encoder" branch) |
| F22 | Images | Render, decode or encode failed (OOM, `compress` false, bundled asset missing or undecodable) | exception / `check` / `decodeStream` returns null | Plain-text notification without picture | `IMAGE` downgraded to `NOTIFICATION` | Robolectric (NATIVE graphics) |
| F23 | Storage | Over quota and nothing evictable | `planAdmission(...).admitted == false` | Skip generation; reason `MEDIA_QUOTA` | text | JVM (`MediaEvictionPolicyTest`) |
| F24 | Storage | Crash between rename and insert; late rename after cancel | Orphan sweep | Delete orphan files older than 1 h | - | Robolectric / JVM (pure reconcile function) |
| F25 | Notify | `POST_NOTIFICATIONS` denied | `checkSelfPermission` | `PERMISSION_DENIED`; in-app notice | `FAILED(NOTIFICATIONS_BLOCKED)` | Robolectric (`shadowOf(app).denyPermissions`) |
| F26 | Notify | App notifications off / channel blocked | `areNotificationsEnabled()` false / importance `NONE` | `APP_NOTIFICATIONS_OFF` / `CHANNEL_BLOCKED` | `FAILED(NOTIFICATIONS_BLOCKED)` | Robolectric (`setNotificationsEnabled(false)`; create the channel with `IMPORTANCE_NONE`) |
| F27 | Notify | Deep link to an unknown or foreign `decisionKey` | Room lookup fails | Open home; no autoplay | - | Robolectric (ActivityScenario with a crafted intent) |

## 12. Test plan (JVM / Robolectric / emulator-only / device-only)

### 12.1 Levels

| Level | What runs there | Tooling |
|---|---|---|
| **JVM** (`:interventions` pure-Kotlin parts in a JVM/KMP module, or plain unit tests) | `Wav` (parse, concat, trailing silence), `TtsTextChunker`, `SlideTimeline`, `MediaEvictionPolicy`, size estimates, the orphan-reconcile function, `ExportException` code to `VideoOutcome` mapping (pass the code as `Int`) | JUnit 4 / kotlin-test. **21 tests pass today** [MC]; Appendix A |
| **Robolectric 4.17** (`@Config(sdk = [37])`, plus `minSdk` in the nightly matrix [R08]) | `TtsSynthesizer` against `ShadowTextToSpeech` (+ custom shadows), `SpeechPlayer` and `AudioOutputInspector` against `ShadowAudioManager`, `JitaiNotifier` against `ShadowNotificationManager` / `ShadowPendingIntent` / `ShadowNotification`, `TemplateRenderer` screenshots (`@GraphicsMode(NATIVE)` + Roborazzi), deep-link validation, the action receiver (no activity start) | Robolectric shadows listed in 12.2; Roborazzi 1.76.0 |
| **Emulator** (instrumented, `connectedDebugAndroidTest` / Gradle Managed Devices [R08]) | Transformer export (images + WAV), cancellation, failure-to-fallback routing, `StillVideoEncoder`, ExoPlayer playback start/stop with focus, Android 17 audio hardening checks (`adb shell cmd audio set-enable-hardening throw` [A17-BGA]), real `TextToSpeech` if the image has an engine | The doc 08 matrix: Gradle Managed Devices at API 29, 30, 34 and 37 (managed devices support API 27+; API 26 only as a hand-made AVD if minSdk drops to 26) [R08]. This covers both sides of each media version guard (31: collapsed big picture; 33: route query and `RECEIVER_EXPORTED`) and the API 37 audio hardening. Transformer with images is unsupported in Robolectric (`TestTransformerBuilder.java:46-54`). |
| **Device only** | OEM TTS engines (Google, Samsung) and their real WAV formats, `LANG_MISSING_DATA` and download flows, network voices offline, Bluetooth A2DP / LE Audio / hearing-aid routing, BECOMING_NOISY on real disconnects, hardware encoders (portrait support, real file sizes, low-end codec limits), OEM rendering of BigPictureStyle | Manual checklist plus Firebase Test Lab or a device farm (doc 08) |

### 12.2 Robolectric specifics (verified against shadows-framework 4.17 [ROBO])

- **`ShadowTextToSpeech`.**
  - `initTts()` returns SUCCESS but **never calls `OnInitListener`**. The test must call
    `shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)`.
  - Get the instance with `ShadowTextToSpeech.getLastTextToSpeechInstance()`, or through the factory seam (2.4).
  - `speak(...)` posts `onStart`/`onDone` to the main looper: call `shadowOf(Looper.getMainLooper()).idle()`.
  - `synthesizeToFile(..., File, ...)` **writes the text with a `PrintWriter`**, not audio. It calls
    `onStart`/`onDone` (or `onError(code)`) synchronously, and **only after** `simulateSynthesizeToFileResult(code)`
    was called. It always returns SUCCESS. The `ParcelFileDescriptor` overload is not shadowed.
  - `isLanguageAvailable` returns only `LANG_*_AVAILABLE` (after `addLanguageAvailability(locale)`) or
    `LANG_NOT_SUPPORTED`, never `LANG_MISSING_DATA`.
  - `getVoices()` returns the static set built by `addVoice(voice)`.
  - Call `ShadowTextToSpeech.reset()` in `@After`.
- **`getEngines()` is not shadowed.** The real method queries `PackageManager` for `TTS_SERVICE`. Register a fake engine:
  `shadowOf(pm).addServiceIfNotPresent(cn)` +
  `addIntentFilterForService(cn, IntentFilter(INTENT_ACTION_TTS_SERVICE).apply { addCategory(Intent.CATEGORY_DEFAULT) })`.
  Whether the category is needed is UNVERIFIED. If `engines` stays empty, add an `engineAvailable` seam.
  `ShadowPackageManager.addServiceIfNotPresent(ComponentName)` and `addIntentFilterForService(ComponentName,
  IntentFilter)` exist in 4.17 (javap).
- **Custom shadow** for the success path and the missing-data path. The stock shadow's `@Implementation` methods are
  `protected` and it calls the real constructor through `Shadow.invokeConstructor`, so `getEngines()` and
  `getDefaultEngine()` run the real code [ROBO]:

```kotlin
// Not compiled here (Robolectric artifacts need Google Maven); pattern only.
@Implements(TextToSpeech::class)
class WavWritingShadowTts : ShadowTextToSpeech() {
    @Implementation
    override fun synthesizeToFile(text: CharSequence, params: Bundle, file: File, utteranceId: String): Int {
        file.writeBytes(Wav.header(24_000, 1, 16, 4_800) + ByteArray(4_800)) // 100 ms of silence
        utteranceProgressListener?.onDone(utteranceId)
        return TextToSpeech.SUCCESS
    }
    @Implementation
    override fun isLanguageAvailable(lang: Locale): Int =
        if (lang.language == "de") TextToSpeech.LANG_MISSING_DATA else super.isLanguageAvailable(lang)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], shadows = [WavWritingShadowTts::class])
class TtsSynthesizerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun fakeEngineLanguageAndVoice() {
        // Without these, TtsSynthesizer correctly returns EngineUnavailable / LanguageNotSupported / NoOfflineVoice.
        val engine = ComponentName("com.example.tts", "com.example.tts.FakeTtsService")
        shadowOf(context.packageManager).apply {
            addServiceIfNotPresent(engine)
            addIntentFilterForService(engine, IntentFilter(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                .apply { addCategory(Intent.CATEGORY_DEFAULT) }) // category need: UNVERIFIED (see above)
        }
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        ShadowTextToSpeech.addVoice(Voice("en-us-x-local", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()))
    }

    @After fun reset() = ShadowTextToSpeech.reset()

    @Test fun synthesizesValidWav() = runTest {
        val out = File(context.noBackupFilesDir, "media/audio/a.wav").apply { parentFile!!.mkdirs() }
        val job = async { TtsSynthesizer(context).synthesizeToWav("Take a short walk.", Locale.US, out) }
        runCurrent()                                                   // suspended in initStatus.await()
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        val result = job.await() as TtsOutcome.Success
        assertThat(result.wav.durationUs).isEqualTo(100_000L)
        assertThat(out.parentFile!!.listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
    }
}
```

- **`ShadowAudioManager`.**
  - Focus: `setNextFocusRequestResponse(AUDIOFOCUS_REQUEST_FAILED)` gives F12. `getLastAudioFocusRequest().listener`
    (public field) lets the test fire `AUDIOFOCUS_LOSS` (F13). `getLastAbandonedAudioFocusRequest()` proves cleanup.
  - Mute and volume: `setIsStreamMute(stream, true)` and `audioManager.setStreamVolume(STREAM_MUSIC, 0, 0)`.
  - Routes: `setAudioDevicesForAttributes(attrs, ImmutableList.of(AudioDeviceInfoBuilder.newBuilder().setType(TYPE_BLE_HEADSET).build()))`
    (33+), or `addOutputDevice(device, false)` before 33. Table-test the privacy rule:
    - `TYPE_BLE_SPEAKER` and `TYPE_BLE_BROADCAST` are not private;
    - `TYPE_BLE_HEARING_AID` is private (`@Config(sdk = [37])`);
    - the built-in speaker and unknown types are not private.
  - Becoming noisy: `context.sendBroadcast(Intent(ACTION_AUDIO_BECOMING_NOISY))` then idle the main looper.
- **Notifications.**
  - `shadowOf(application).denyPermissions(POST_NOTIFICATIONS)` / `grantPermissions(...)`.
  - `shadowOf(nm).setNotificationsEnabled(false)`.
  - `shadowOf(nm).getNotification(tag, id)`, `shadowOf(notification).bigPicture`.
  - `shadowOf(pendingIntent).isImmutable`, `isActivity`, `isBroadcast`, `savedIntent.component`, `savedIntent.data`.
  - After running the action receiver, assert `shadowOf(application).nextStartedActivity == null` (no trampoline).
  - Doc 02 notes that `ShadowPendingIntent` performs no mutability or implicit-intent checks, so the explicit/immutable
    rule is asserted by these tests, not by the platform [R02].
- **Media3 in Robolectric.**
  - Only audio-only or transmux exports work with `TestTransformerBuilder` from `media3-test-utils-robolectric`. Images
    and video transcoding are unsupported.
  - So the Robolectric layer tests the wrapper around a fake `VideoComposer` interface:
    `suspend fun compose(...): VideoOutcome`.
- **`ShadowMediaCodec` / `ShadowMediaCodecList` / `ShadowMediaMuxer`** exist (`addEncoder`, `addCodec`,
  `MediaCodecInfoBuilder`). With no codecs registered, `StillVideoEncoder` returns `NO_VIDEO_ENCODER`, which covers
  F21. The `getInputImage` path is emulator-only.

### 12.3 Emulator tests (androidTest)

1. **`StillVideoComposerTest`**, modelled on Media3's `TransformerEndToEndTest`.
   - Inputs: three 720x1280 PNG slides from `TemplateRenderer`, and a WAV built in the test (`Wav.header` + PCM, 2 s).
   - Assert:
     - `Success`;
     - `ExportResult.videoFrameCount` equals the slide durations x 30 / 1000 (+/- 1 per slide);
     - `approximateDurationMs` is within 2 frames of the timeline;
     - `fileSizeBytes` is within the budget in 3.4;
     - `media3-inspector` `MetadataRetriever` shows one AVC and one AAC track;
     - no `*.tmp` remains.
2. **Cancel test.** Start the export, cancel the coroutine after the first `PROGRESS_STATE_AVAILABLE`, then wait for the
   main looper. Assert no output, no tmp, and that a new export on a fresh `Transformer` succeeds (codecs were released).
3. **Fallback routing.**
   - Build the Transformer with `setEncoderFactory(...)` (`Transformer.java:572`), passing a `Codec.EncoderFactory`
     whose `createForVideoEncoding(format: Format, logSessionId: LogSessionId?)` (`Codec.java:115`, `throws
     ExportException`) throws `ExportException.createForCodec(IllegalStateException("test"),
     ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.CodecInfo(format.toString(), /* isVideo */ true,
     /* isDecoder */ false, /* name */ null))` (`ExportException.java:59-60`, `:293-296`). Delegate
     `createForAudioEncoding` to `DefaultEncoderFactory`.
   - Assert `useFallback == true`, then run `StillVideoEncoder` and check the output with `MediaExtractor`: 2 tracks,
     `video/avc` + `audio/mp4a-latm`, video sample count = `ceil((wav + tail) / frameDuration)`.
4. **Audio hardening (API 37 image).** With `adb shell cmd audio set-enable-hardening throw`:
   - Run the delivery worker for a VOICE JITAI. Expect no exception, because the worker touches no audio API, and a WAV
     plus a notification.
   - Then open the deep link and assert playback starts (activity visible).
5. **Real TTS.**
   - If the image has an engine (Google Play images are expected to include Speech Services by Google; UNVERIFIED),
     synthesize "Hello" and assert a valid PCM16 WAV with sample rate > 8000.
   - Otherwise assert `EngineUnavailable` (the AOSP image may have no engine; UNVERIFIED).
   - Mark the test with an `assumeTrue(engines.isNotEmpty())` split, so CI does not flake.

Do not assert pixel-exact video frames or exact AAC durations across API levels. Media3's own tests document an extra
23 ms access unit on API 26 vs 33 emulators (`TransformerEndToEndTest.java:1837`).

### 12.4 Device checklist (release gate)

- TTS:
  - default engine plus one OEM engine;
  - delete the language data, then expect F3 and recover through the install intent;
  - airplane mode with a network voice selected, then expect F4/F6.
- Bluetooth:
  - A2DP headphones, an LE Audio earbud, a hearing aid if available (on Android 17, an LE Audio hearing aid should
    report `TYPE_BLE_HEARING_AID`): "Playing on ..." label and the `isPrivate` rule;
  - an LE Audio speaker: "headphones only" must skip speech;
  - disconnect while speaking: speech stops and does not continue on the speaker.
- Video on a low-end device (2 GB RAM class): 60 s recap export time and file size, cancel and re-export, portrait
  output plays upright in Google Photos and in a third-party player.
- Notifications on Pixel and Samsung: collapsed and expanded big picture, TalkBack reads the description, lock screen
  shows the public version, Done/Snooze work with the app killed.

## 13. Recommendations

### 13.1 Decisions for v1

1. **VOICE: synthesize in the worker, play only on screen** (section 2.1).
   - The delivery worker calls `TtsSynthesizer.synthesizeToWav` during Render (doc 10 section 8.5 step 3).
   - It posts a normal notification with a **Listen** action.
   - Workers never call `speak()` and never request audio focus.
   - Any `TtsFailure` downgrades the delivery to NOTIFICATION. Store the failure type as the downgrade reason.
   - This needs the doc 10 section 8.5 step 4 change in 13.2.
2. **Use Media3 1.11.1**, the current stable release. It needs no pre-release.
   - Add the catalog entries from section 1 to doc 07.
   - Limit `@file:OptIn(UnstableApi::class)` to the two wrapper files, `StillVideoComposer` and
     `InterventionMediaPlayer`.
   - Keep Media3 types out of public signatures.
3. **Generate video only in the foreground, when the user asks for it** (section 3).
   - JITAI VIDEO deliveries play bundled clips (`asset:///media/...`).
   - Export settings: H.264/AAC, 720x1280 at 30 fps, `setBitrate(1_500_000)` video and 64 kbit/s audio,
     `setUsePlatformDiagnostics(false)`, and at most 90 s.
   - Build the narration with `Wav.concat(..., trailingSilenceMs = 300)` and the slide durations with
     `SlideTimeline.allocate(..., tailMs = 300)`. The audio and video sequences then end together (tested, Appendix A).
   - Do not use the `mediaProcessing` FGS.
4. **Gate the MediaCodec fallback** (section 4).
   - Enable `StillVideoEncoder` only after emulator test 3 (section 12.3) passes on the doc 08 emulator matrix, and
     the `queueInputBuffer` size question (U9) is settled.
   - Until then, Transformer codes 5001/4001/4003 lead to audio-only playback, with the slides shown in the app.
5. **Playback** (section 5).
   - Use one `ExoPlayer` per screen, with `USAGE_MEDIA` + `AUDIO_CONTENT_TYPE_SPEECH`, `handleAudioFocus = true` and
     `setHandleAudioBecomingNoisy(true)`.
   - Pause on `ON_STOP` and release on dispose.
   - Do not use `MediaSession` or `MediaSessionService`.
   - Autoplay only under the section 5 rule.
   - Use `SpeechPlayer` (live TTS) only for previews.
6. **Images: offline templates only** (section 6).
   - `TemplateRenderer` with `android.graphics` is the only v1 image source.
   - Use a bundled font, and give every card alt text.
   - Notification pictures are 1024x512 (2:1). Video slides are 720x1280.
   - JITAI IMAGE deliveries use bundled `local_media` images, as doc 10 defines today. Template cards appear in
     notifications only if doc 10 adopts the `card` strategy (13.2).
   - Set `imageGeneration = false` for every v1 provider. Do not use the MediaPipe Image Generator in v1.
   - Keep the section 7 contract for a later provider.
7. **Storage** (section 9).
   - Store media in `noBackupFilesDir/media/{image,audio,video}`.
   - Track it in the Room tables `media_asset` and `delivery_media`. Doc 07 owns the schema.
   - Use the `MediaQuota` defaults (200 MB in total: video 120, audio 40, image 60).
   - Plan admission before generating.
   - Write to a temp file, then rename. Run the orphan sweep at app start and in the daily maintenance worker.
   - Share through `cacheDir/share` and a FileProvider `<cache-path>`, with an explicit read grant and `ClipData`.
8. **Notifications** (section 10).
   - Use `JitaiNotifier` with three checks before posting: permission, app-level enabled, then channel importance.
   - Post with `tag = decisionKey` and `id = 1`.
   - Every PendingIntent is immutable and explicit, with a distinct data URI built with `appendPath`.
   - Done, Snooze and Dismissed go to a non-exported receiver, which never starts an activity.
   - Listen and Watch open `MainActivity` directly.
   - Show `BigPictureStyle` collapsed only when `SDK_INT >= 31`.
   - No full-screen intent and no custom `RemoteViews`.
   - Ask for `POST_NOTIFICATIONS` at the first JITAI setup, not at launch.
9. **Manifest** (app module).
   - Add the `<queries>` entry for `android.intent.action.TTS_SERVICE`.
   - Add a FileProvider with `<cache-path name="share" path="share/"/>`.
   - Declare `JitaiActionReceiver` with `android:exported="false"`.
   - `MainActivity` gets no `agentle://` intent filter.
   - Do not declare `USE_FULL_SCREEN_INTENT`, any `FOREGROUND_SERVICE_MEDIA_*` permission, or `BLUETOOTH_CONNECT`.
10. **Privacy defaults.**
    - `allowNetworkVoices = false`.
    - The privacy text says the default TTS engine, which is third-party code, receives the rendered intervention text.
    - Transformer platform diagnostics are off.
    - Generated media never goes to cloud backup: `noBackupFilesDir` is always excluded [AUTOBACKUP].
11. **Test seams.** These keep most of the logic below the emulator level:
    - a `TextToSpeech` factory;
    - a `VideoComposer` interface around Transformer;
    - a `StorageChecker` interface around `getAllocatableBytes`;
    - a pure `reconcile(files, rows, now)` function;
    - a pure `ExportException` code-to-route function.
12. **Tests.**
    - Move the Appendix A utilities and their 21 JVM tests into `:interventions`.
    - Add the Robolectric tests from section 12.2.
    - Run the emulator tests in section 12.3 nightly on the doc 08 matrix [R08]: Gradle Managed Devices at API 29,
      30, 34 and 37, plus a hand-made API 26 AVD if minSdk drops to 26.
    - Use section 12.4 as a release gate.
13. **minSdk.** Media sets no floor above 26. Media3 itself needs 23 (section 1).
    - A bytecode scan of the compiled classes [MC] found 202 references to Android members. Each was looked up in
      `api-versions.xml` [SDK37]. Only three are above API 26, and all three sit behind `SDK_INT` checks:
      - `AudioManager.getAudioDevicesForAttributes` (33);
      - `Notification.BigPictureStyle.showBigPictureWhenCollapsed` (31);
      - `Notification.BigPictureStyle.setContentDescription` (31).
    - Newer `int` constants (`Context.RECEIVER_EXPORTED` 33, `AudioDeviceInfo.TYPE_BLE_*` 31/33,
      `TYPE_BUILTIN_SPEAKER_SAFE` 30) are inlined at compile time. Lint reports them only as `InlinedApi` warnings.
    - `WEBP_LOSSLESS` (30) is not used; PNG is the default.
    - With minSdk 31, the pre-31 notification branch can go. With minSdk 33, the pre-33 route heuristic can go.
14. **Measure before tuning** (section 14):
    - TTS timeouts;
    - real MP4 sizes;
    - PNG vs WebP lossless;
    - whether emulator images include a TTS engine.

### 13.2 Changes requested in other documents

| Doc | Change | Why |
|---|---|---|
| 10 (JITAI engine), section 8.5 step 4 | Replace "VOICE first posts a silent companion notification with the same tag, then speaks with `utteranceId = decisionKey`". New text: "VOICE: the WAV is synthesized during step 3. If synthesis fails, the delivery is downgraded to NOTIFICATION. Post the normal notification with a Listen action that opens the app. Never speak from the worker." | Android 17 silences background playback and fails focus requests [A17-BGA]. Since target 35, focus requests fail unless the app is the top app or runs an FGS [AF]. Doc 02: "Never touch audio from the background". |
| 10, section 3.3 / DSL | No `ai_image` content strategy in v1. VIDEO uses `local_media` only. VOICE and VIDEO caps stay as defined (2 and 1 per day). | Sections 3.1, 7, 8 |
| 10, section 8.5 (media fallback) | Add: "If a media part fails (TTS, image decode or render, quota, missing clip), deliver as NOTIFICATION with the text form (VOICE text or `local_media.caption`) and record the reason (`TTS_UNAVAILABLE`, `IMAGE_UNAVAILABLE`, `VIDEO_UNAVAILABLE`, `MEDIA_QUOTA`)". The cap check counts the delivery under its original channel. | Mirrors the `ai_text` fallback rule. Media failures must not block or silently drop a delivery (section 11). |
| 10, section 3.3 / DSL (proposal, optional for v1) | Add a `card` content strategy for IMAGE: `templateId` (`QUOTE`, `SPARKLINE`, `STAT`, `STREAK`), `fields` (placeholder rules as for `template`), `caption`, `altText`. E069 would accept `card` for IMAGE. On a render failure the delivery downgrades to NOTIFICATION (F22). | Data-driven pictures without AI image generation (sections 6.2, 6.3) |
| 07 (architecture and versions) | Add the `media3` version and libraries (section 1). Add the `media_asset` and `delivery_media` entities (section 9.2). Place `:interventions` media code in `media/{voice,video,image,storage,notify}`. | Single owner of the catalog and the schema |
| 07 / 06 | Decide whether the Room database is in cloud backup. Auto Backup includes `getDatabasePath()` files by default [AUTOBACKUP]. A restored `media_asset` row then arrives without its file, because `noBackupFilesDir` is never restored. The reconcile pass marks such rows `MISSING`, so this is safe either way. Excluding the database avoids the churn. | Section 9.4 step 6 |
| 06 / 07 | Whoever defines an app-wide "delete my data" flow must add: delete all `media_asset` rows and their files, and `cacheDir/share/`. | Section 9.4 step 7 (open item) |
| 08 (testing) | Add Media3 test dependencies (`media3-test-utils`, `media3-inspector`) to androidTest. Add an emulator lane for the Transformer tests. Note that Media3 Transformer image and video tests cannot run in Robolectric. | Section 12 |
| 01 (permissions) | Record that media needs no permission beyond `POST_NOTIFICATIONS`: no `BLUETOOTH_CONNECT`, no `FOREGROUND_SERVICE_MEDIA_PROCESSING` or `_MEDIA_PLAYBACK`, no `USE_FULL_SCREEN_INTENT`. Record the `<queries>` entry for TTS. | Sections 2.2, 2.7, 10.1 |

## 14. Uncertainties / UNVERIFIED

Each row says why it could not be checked in this run, what the design does in the meantime, and how to close it.

| # | Item | Why unverified | Mitigation in the design | How to close |
|---|---|---|---|---|
| U1 | `synthesizeToFile(..., File, ...)` writes RIFF/WAVE PCM (AOSP `FileSynthesisCallback`) | android.googlesource.com is blocked (403). The reference page does not state the format. | `Wav.parse` validates the output. Anything else becomes `InvalidOutput`, and the delivery falls back to text. | Emulator test 5; device checklist (OEM engines) |
| U2 | Init timeout (5 s) and per-chunk timeout (30 s + 60 ms per character) | Product guesses | Timeout leads to text, never a hang | Measure on low-end devices and OEM engines |
| U3 | Emulator images include a TTS engine (Google Play images are expected to ship Speech Services by Google; AOSP images may not) | No emulator in this run | Test 5 splits on `assumeTrue(engines.isNotEmpty())` | Check the CI system images |
| U4 | The Robolectric fake engine needs `CATEGORY_DEFAULT` on its intent filter | No Robolectric run: the app build needs AGP from Google Maven, which is blocked | Fallback `engineAvailable` seam (section 12.2) | First Robolectric run in the app repo |
| U5 | The pre-33 route heuristic misreports when a Bluetooth device is connected but not active | No device | `isPrivate` is advisory before 33 | Device checklist (section 12.4) |
| U6 | Bluetooth product names from `AudioDeviceInfo.getProductName()` are redacted without `BLUETOOTH_CONNECT` | Neither the reference [ADI] nor the SDK annotations list a permission. Runtime behaviour was not observable here. | The UI shows the device type only | Device test without the permission |
| U7 | Emulator video path: AVC software encoder name (`c2.android.avc.encoder` expected) and GLES backend | No emulator. Media3 sources reference only `c2.android.aac.encoder` (section 3.4). | Tests log encoder names and do not assert them | Emulator test 1 output |
| U8 | Real MP4 size of static slides under VBR (expected well below the 1.5 Mbit/s target) | No encoder run | Admission reserves the bitrate-based upper bound plus 10% | Emulator test 1 records `fileSizeBytes`; device checklist |
| U9 | `queueInputBuffer` size after filling an input `Image` in the fallback (capacity vs `w*h*3/2`) | Not runnable here | Fallback gated (13.1 item 4) | Emulator test 3 on the doc 08 matrix |
| U10 | The Media3 sketches (sections 3.2 and 5) compile | Google Maven is blocked, so Media3 AARs were unavailable. Symbols were checked against 1.11.1 sources only. | File:line evidence in sections 3.3 and 5 | First app build with the catalog entries |
| U11 | The Robolectric and Roborazzi sketches (section 12.2) compile and pass | Same as U4 | Method names checked with javap and source [ROBO][RZ] | First Robolectric run |
| U12 | Compose 1.12.1 already deprecates the 4-argument `TextMeasurer` constructor (seen on androidx-main) | Release AARs are blocked; the release notes are silent [CUI-REL] | Affects only option (b) in section 6.1, which is not the default | Build with the BOM |
| U13 | `WEBP_LOSSLESS` is smaller than PNG for these cards | Not measured | PNG is the default | Measure on the screenshot fixtures |
| U14 | The Android 17 `RemoteViews` bitmap memory limit applies to notifications (the page describes widgets) | Page scope unclear [A17-TGT] | No custom views. A 1024x512 picture (2 MiB) is far below the limit. | Not needed for v1 |
| U15 | MediaPipe Image Generator Maven coordinates and version, model size, licence terms | Google Maven and ai.google.dev are unreachable. Maven Central has no `com.google.mediapipe:tasks-vision` metadata (HTTP 404). | Not in v1 | Revisit only if on-device image generation is requested |
| U16 | Cloud image generation APIs (OpenAI images, Imagen through Firebase AI Logic) | developers.openai.com and firebase.google.com returned HTTP 000 | Not in v1; capability flag false | Provider work after v1 |
| U17 | Product defaults: quota caps, 90 s video cap, 150 ms gap, 300 ms tail, 1.5 Mbit/s, 14-day JITAI media expiry, 1 h orphan age | Product choices, not platform facts | All are constants in one place (`MediaQuota`, `VideoSpec`, `SlideTimeline` arguments) | Tune with usage data |

## 15. Sources

All pages were fetched with `curl` (no WebFetch) on 2026-10-01/02 unless noted. Git sources were read at the stated tag
or branch. Sibling documents are in `docs/research/`.

| Key | Source | Notes |
|---|---|---|
| M3-REL | https://developer.android.com/jetpack/androidx/releases/media3 | 1.11.1 (September 10, 2026) is the latest stable. No RC, beta or alpha. |
| M3-GIT | https://github.com/androidx/media, tag `1.11.1` = `8c6678b657ede1e7883fc164ef73ed483c7796c3` | `git ls-remote` plus a sparse clone. File:line references are relative to `libraries/`. |
| M3-COMPOSE | https://developer.android.com/media/media3/ui/compose | `media3-ui-compose`, `media3-ui-compose-material3` |
| TR-GS | https://developer.android.com/media/media3/transformer/getting-started | Threading, cancel, progress |
| TR-FMT | https://developer.android.com/media/media3/transformer/supported-formats | Image inputs through `BitmapFactory` |
| TR-TS | https://developer.android.com/media/media3/transformer/troubleshooting | MediaCodec and OpenGL dependence |
| EXO-FMT | https://developer.android.com/media/media3/exoplayer/supported-formats | WAV and MP4 playback |
| TTS | https://developer.android.com/reference/android/speech/tts/TextToSpeech | Init listener timing, `stop()`, `<queries>`, error and `LANG_*` constants |
| TTS-ENG | https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine | `ACTION_INSTALL_TTS_DATA`, `KEY_FEATURE_NOT_INSTALLED`, deprecated network-synthesis key |
| VOICE | https://developer.android.com/reference/android/speech/tts/Voice | `isNetworkConnectionRequired`, `getFeatures`, quality |
| AF | https://developer.android.com/media/optimize/audio-focus | Target 35 focus rule; ducking and speech content |
| A17-BGA | https://developer.android.com/about/versions/17/changes/bg-audio | Background playback silenced; `AUDIOFOCUS_REQUEST_FAILED`; `cmd audio set-enable-hardening` |
| A17-FEAT | https://developer.android.com/about/versions/17/features | Assistant volume stream for `USAGE_ASSISTANT` |
| A17-ALL | https://developer.android.com/about/versions/17/behavior-changes-all | Android 18 drops implicit URI grants for `ACTION_SEND` |
| A17-TGT | https://developer.android.com/about/versions/17/behavior-changes-17 | `RemoteViews` bitmap memory limit |
| A12-TGT | https://developer.android.com/about/versions/12/behavior-changes-12 | Notification trampolines; PendingIntent mutability |
| BCAST | https://developer.android.com/develop/background-work/background-tasks/broadcasts | `RECEIVER_EXPORTED` for system broadcasts |
| NOTIF-BUILD | https://developer.android.com/develop/ui/compose/notifications/create-notification | Full-screen intents are "substantially intrusive" |
| NOTIF-EXP | https://developer.android.com/develop/ui/compose/notifications/expanded | `BigPictureStyle` |
| NOTIF-CH | https://developer.android.com/develop/ui/compose/notifications/channels | Channels and groups |
| NOTIF-PERM | https://developer.android.com/develop/ui/compose/notifications/notification-permission | Off by default on 13+; checks before posting |
| NOTIF-NAV | https://developer.android.com/develop/ui/views/notifications/navigation | Activity PendingIntents from notifications |
| NC-BPS | https://developer.android.com/reference/androidx/core/app/NotificationCompat.BigPictureStyle | Compat method list |
| CORE-REL | https://developer.android.com/jetpack/androidx/releases/core | core 1.19.1 (September 23, 2026); versions that added the compat notification APIs |
| CUI-REL | https://developer.android.com/jetpack/androidx/releases/compose-ui | compose-ui 1.12.1 and 1.13.0-alpha03 (September 9, 2026); no `TextMeasurer` locale-list entry |
| AX | https://github.com/androidx/androidx, branch `androidx-main` (raw files, 2026-10-01) | Compose `CanvasDrawScope.kt`, `Canvas.kt`, `ImageBitmap.kt`, `AndroidImageBitmap.android.kt`, `AndroidCanvas.android.kt`, `TextMeasurer.kt`, `FontFamilyResolver.android.kt` |
| AX-CORE | androidx-main `core/core/src/main/java/androidx/core/app/{NotificationCompat,NotificationManagerCompat,NotificationChannelCompat,NotificationChannelGroupCompat}.java` | `@RequiresApi(31)` on the collapsed big picture and content description (`NotificationCompat.java:3388-3450`) |
| AX-FP | androidx-main `core/core/src/main/java/androidx/core/content/FileProvider.java` | Path tags, `:358-364` |
| CMP-DRAW | https://developer.android.com/develop/ui/compose/graphics/draw/modifiers | Composable-to-bitmap with `rememberGraphicsLayer` |
| AUTOBACKUP | https://developer.android.com/identity/data/autobackup | Included and excluded directories; 25 MB quota; `onQuotaExceeded` |
| APPSPEC | https://developer.android.com/training/data-storage/app-specific | Cache deletion, `getAllocatableBytes`, `ACTION_MANAGE_STORAGE`, uninstall |
| AI-NANO | https://developer.android.com/ai/gemini-nano | ML Kit GenAI feature list (no image generation) |
| AI-OVR | https://developer.android.com/ai/overview | Prompt API for image understanding |
| MLKIT-BLOG | https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences | Prompt API announcement |
| MP | https://github.com/google-ai-edge/mediapipe, tag `v1.0.0`: `mediapipe/tasks/java/com/google/mediapipe/tasks/vision/imagegenerator/{ImageGenerator.java,BUILD,AndroidManifest.xml}` | Latest tag from `git ls-remote`. Maven Central has no `com.google.mediapipe:tasks-vision` metadata (HTTP 404). |
| ROBO | Robolectric 4.17: `shadows-framework-4.17.jar` (javap); `ShadowTextToSpeech.java` at tag `robolectric-4.17` (raw.githubusercontent.com) | Shadow behaviour in section 12.2 |
| RZ | https://github.com/takahirom/roborazzi/blob/1.76.0/README.md | `Bitmap.captureRoboImage()` |
| SDK37 | Local SDK: `/opt/android-sdk/platforms/android-37.0/android.jar`, `data/api-versions.xml` and `data/annotations.zip` | API levels (Table 1.1 and 13.1 item 13); class scan in section 8; `@RequiresPermission` on `BluetoothDevice` getters |
| ADI | https://developer.android.com/reference/android/media/AudioDeviceInfo (curl 2026-10-02) | `TYPE_BLE_*` descriptions, including `TYPE_BLE_HEARING_AID`; `getProductName()` lists no permission |
| MC | Compile check `mediacheck/` in the session scratchpad: Kotlin 2.4.20, Gradle 9.7.1, android.jar 37 `compileOnly`, kotlinx-coroutines 1.11.0 | 21 JVM tests, 0 failures. Code in sections 2.4, 2.6, 4.2, 6.2, 9.3 and Appendix A. |
| R02 | `docs/research/02-background-execution.md` | No `mediaProcessing` FGS; no audio from the background; T-ARCH-04; channel importance |
| R06 | `docs/research/06-openai-sign-in-with-chatgpt.md` | Sections 4.4 and 4.5: text only, data minimization; section 8.6: backup exclusions |
| R07 | `docs/research/07-architecture-and-versions.md` | Catalog versions, Room 3 packages |
| R08 | `docs/research/08-testing-strategy.md` | Robolectric 4.17 (SDK 23-37), Roborazzi 1.76.0, NATIVE graphics needs SDK >= 26, Gradle Managed Devices |
| R10 | `docs/research/10-jitai-engine-design.md` (cited as "doc 10") | Delivery protocol 8.5, decision keys 8.2, `local_media` 3.3 |

Consulted but not cited: the `UtteranceProgressListener` and `Notification.BigPictureStyle` reference pages, and the
Transformer `composition`, overview and `transformations` guides (same site, same fetch method).

Unreachable, so nothing from these hosts is used:
- `maven.google.com`, which redirects to `dl.google.com` (403);
- `android.googlesource.com` (403);
- `developers.google.com/ml-kit/genai`, `ai.google.dev/edge/mediapipe/...`, `firebase.google.com/docs/ai-logic/...`
  and `developers.openai.com/...` (HTTP 000).

No mirror, cache or proxy of a blocked host was used.

## Appendix A. Compiled utilities and JVM tests

These files are copied verbatim from the compile check [MC]. The other compiled files appear earlier without their
`package` and `import` lines:
- `agentle.media.voice`: `TtsSynthesizer` (2.4), `AudioOutput` and `SpeechPlayer` (2.6);
- `agentle.media.video`: `StillVideoEncoder` and `I420` (4.2);
- `agentle.media.image`: `TemplateRenderer` (6.2);
- `agentle.media.storage`: `MediaQuota` and `MediaEvictionPolicy` (9.3).

In the app these move to `:interventions` (doc 07). The pure-Kotlin files (`Wav`, `TtsTextChunker`, `SlideTimeline`,
`MediaEvictionPolicy`) have no Android imports and can live in a JVM source set.

### A.1 Build (JVM-only compile check)

Reproduce with `./gradlew --console=plain --max-workers=2 test --rerun`, then `./gradlew --stop`. The wrapper is
Gradle 9.7.1. The test result XMLs sum to `tests=21 failures=0 errors=0`.

```kotlin
// settings.gradle.kts
// JVM-only compile check for Agentle media sketches (framework APIs only).
// Android framework classes come from the local SDK android.jar (compileOnly); Google Maven is unreachable here.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "agentle-media-check"
```

```kotlin
// build.gradle.kts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val androidJar = providers.gradleProperty("androidJar")
    .orElse("/opt/android-sdk/platforms/android-37.0/android.jar")

dependencies {
    compileOnly(files(androidJar.get()))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
```

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx2g
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.configuration-cache=true
systemProp.org.gradle.internal.repository.max.retries=12
systemProp.org.gradle.internal.repository.initial.backoff=2000
```

### A.2 `Wav.kt`: WAV header parsing and concatenation

```kotlin
// mediacheck/src/main/kotlin/agentle/media/storage/Wav.kt
package agentle.media.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parsed RIFF/WAVE header of a PCM file (what TextToSpeech.synthesizeToFile writes). */
data class WavInfo(
    val audioFormat: Int, // 1 = PCM integer, 3 = IEEE float
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
    val frameCount: Long get() = if (bytesPerFrame == 0) 0 else dataSize / bytesPerFrame
    val durationUs: Long get() = if (sampleRate == 0) 0 else frameCount * 1_000_000L / sampleRate
    val isPcm16: Boolean get() = audioFormat == 1 && bitsPerSample == 16
}

object Wav {
    private const val MAX_HEADER_SCAN = 4096

    /**
     * Parses the header. Tolerates a 0 or 0xFFFFFFFF data size (header written before the length was
     * known, or an interrupted synthesis) by clamping to the real file length.
     */
    fun parse(header: ByteArray, fileLength: Long): WavInfo? {
        if (header.size < 12) return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (ascii(header, 0) != "RIFF" || ascii(header, 8) != "WAVE") return null
        var pos = 12
        var fmt: IntArray? = null // audioFormat, channels, sampleRate, bits
        while (pos + 8 <= header.size) {
            val id = ascii(header, pos)
            val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > header.size) return null
                    fmt = intArrayOf(
                        bb.getShort(body).toInt() and 0xFFFF,
                        bb.getShort(body + 2).toInt() and 0xFFFF,
                        bb.getInt(body + 4),
                        bb.getShort(body + 14).toInt() and 0xFFFF,
                    )
                }
                "data" -> {
                    val f = fmt ?: return null
                    val available = (fileLength - body).coerceAtLeast(0)
                    val declared = if (size == 0L || size == 0xFFFFFFFFL) available else size
                    return WavInfo(f[0], f[1], f[2], f[3], body.toLong(), minOf(declared, available))
                }
            }
            pos = body + ((size + 1) and 1L.inv()).toInt() // chunks are word aligned
            if (size > MAX_HEADER_SCAN) break
        }
        return null
    }

    fun parse(file: File): WavInfo? {
        if (!file.isFile) return null
        val len = file.length()
        val header = ByteArray(minOf(len, MAX_HEADER_SCAN.toLong()).toInt())
        RandomAccessFile(file, "r").use { it.readFully(header) }
        return parse(header, len)
    }

    /** Canonical 44-byte PCM header. */
    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataSize: Long): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + dataSize).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(channels.toShort()); putInt(sampleRate); putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort()); putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize.toInt())
        }.array()
    }

    /**
     * Concatenates PCM WAV files of identical format (chunked TTS output) into [out], with [silenceBetweenMs]
     * between inputs and [trailingSilenceMs] after the last one (so a narration can be exactly as long as the
     * slide timeline). Returns null if any input is unreadable or formats differ.
     */
    fun concat(inputs: List<File>, out: File, silenceBetweenMs: Int = 150, trailingSilenceMs: Int = 0): WavInfo? {
        val infos = inputs.map { parse(it) ?: return null }
        val first = infos.firstOrNull() ?: return null
        if (infos.any { it.sampleRate != first.sampleRate || it.channels != first.channels ||
                it.bitsPerSample != first.bitsPerSample || it.audioFormat != first.audioFormat }) return null
        val silenceBytes = (first.sampleRate.toLong() * silenceBetweenMs / 1000) * first.bytesPerFrame
        val tailBytes = (first.sampleRate.toLong() * trailingSilenceMs / 1000) * first.bytesPerFrame
        val total = infos.sumOf { it.dataSize } + silenceBytes * (infos.size - 1) + tailBytes
        out.outputStream().buffered().use { os ->
            os.write(header(first.sampleRate, first.channels, first.bitsPerSample, total))
            inputs.forEachIndexed { i, f ->
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(infos[i].dataOffset)
                    val buf = ByteArray(64 * 1024)
                    var remaining = infos[i].dataSize
                    while (remaining > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        os.write(buf, 0, n); remaining -= n
                    }
                }
                if (i < inputs.lastIndex) os.write(ByteArray(silenceBytes.toInt()))
            }
            if (tailBytes > 0) os.write(ByteArray(tailBytes.toInt()))
        }
        return parse(out)
    }

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
}
```

### A.3 `TtsTextChunker.kt`: splitting text under `getMaxSpeechInputLength()`

```kotlin
// mediacheck/src/main/kotlin/agentle/media/voice/TtsTextChunker.kt
package agentle.media.voice

/**
 * Splits text into chunks no longer than [maxLen] (TextToSpeech.getMaxSpeechInputLength()),
 * preferring sentence, then clause, then word boundaries.
 */
object TtsTextChunker {
    private val sentenceEnd = Regex("(?<=[.!?…])\\s+|\\n+")

    fun split(text: String, maxLen: Int): List<String> {
        require(maxLen > 0)
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxLen) return listOf(clean)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in clean.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }) {
            for (piece in hardSplit(sentence, maxLen)) {
                val sep = if (current.isEmpty()) 0 else 1
                if (current.length + sep + piece.length > maxLen) {
                    out += current.toString(); current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun hardSplit(s: String, maxLen: Int): List<String> {
        if (s.length <= maxLen) return listOf(s)
        val parts = mutableListOf<String>()
        var rest = s
        while (rest.length > maxLen) {
            val window = rest.substring(0, maxLen + 1)
            val cut = listOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(' '))
                .firstOrNull { it > maxLen / 2 } ?: maxLen
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }
}
```

### A.4 `SlideTimeline.kt`: slide durations that match the narration

```kotlin
// mediacheck/src/main/kotlin/agentle/media/video/SlideTimeline.kt
package agentle.media.video

/**
 * Maps narration parts (one TTS part per slide, joined by Wav.concat with [gapMs] of silence) to image
 * durations, so each slide changes when its sentence starts. Boundaries are accumulated in microseconds and
 * rounded once, so rounding never drifts the slides away from the audio.
 */
object SlideTimeline {
    /**
     * @param partDurationsUs WavInfo.durationUs of each slide's narration part, in slide order.
     * @param gapMs silence Wav.concat inserts between parts (its silenceBetweenMs).
     * @param tailMs hold on the last slide after the voice ends.
     * @return image durations in ms for MediaItem.Builder.setImageDurationMs, one per slide.
     */
    fun allocate(partDurationsUs: List<Long>, gapMs: Long = 150, tailMs: Long = 300): List<Long> {
        require(partDurationsUs.isNotEmpty()) { "at least one slide" }
        require(partDurationsUs.all { it > 0 }) { "empty narration part" }
        var boundaryUs = 0L
        var previousMs = 0L
        return partDurationsUs.mapIndexed { i, partUs ->
            boundaryUs += partUs + 1_000L * (if (i == partDurationsUs.lastIndex) tailMs else gapMs)
            val boundaryMs = (boundaryUs + 500) / 1_000
            (boundaryMs - previousMs).also { previousMs = boundaryMs }
        }
    }
}
```

### A.5 `JitaiNotifier.kt`: platform-API mirror of section 10.2

```kotlin
// mediacheck/src/main/kotlin/agentle/media/notify/JitaiNotifier.kt
package agentle.media.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build

/** Delivery result recorded on the JITAI delivery row (doc 10 section 8.5 step 2). */
enum class NotifyOutcome { POSTED, PERMISSION_DENIED, APP_NOTIFICATIONS_OFF, CHANNEL_BLOCKED }

data class JitaiNotice(
    val decisionKey: String,       // doc 10 section 8.2 key, e.g. "v1|<jitaiId>|D|2026-10-01|17:00"; the notification tag
    val title: String,
    val text: String,
    val picture: Bitmap?,          // <= 1024x512 (2:1) rendered by TemplateRenderer
    val pictureDescription: String?,
    val hasVoice: Boolean,
    val hasVideo: Boolean,
    val timeoutMs: Long,           // doc 10 delivery deadline
)

/**
 * Platform-API mirror of the NotificationCompat sketch in section 10.2, used only to compile-check the names
 * against android.jar 37: same tag/id scheme (tag = decisionKey, id = 1), same PendingIntent rules.
 */
class JitaiNotifier(
    private val context: Context,
    private val activity: ComponentName,       // the single MainActivity (no intent-filter needed)
    private val actionReceiver: ComponentName, // exported="false" receiver for Done/Snooze/Dismissed
    private val smallIcon: Int,
    private val doneIcon: Int,
    private val snoozeIcon: Int,
    private val playIcon: Int,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "Check-ins"))
        nm.createNotificationChannel(NotificationChannel(CH_NUDGE, "Nudges", NotificationManager.IMPORTANCE_DEFAULT).apply {
            group = GROUP; description = "Just-in-time suggestions"
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Quiet insights", NotificationManager.IMPORTANCE_LOW).apply {
            group = GROUP; setSound(null, null); enableVibration(false)
        })
    }

    fun post(n: JitaiNotice, channelId: String = CH_NUDGE): NotifyOutcome {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return NotifyOutcome.PERMISSION_DENIED
        if (!nm.areNotificationsEnabled()) return NotifyOutcome.APP_NOTIFICATIONS_OFF
        if (nm.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return NotifyOutcome.CHANNEL_BLOCKED

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE) // health content stays off the lock screen
            .setPublicVersion(
                Notification.Builder(context, channelId).setSmallIcon(smallIcon).setContentTitle("Agentle check-in").build(),
            )
            .setContentIntent(activityIntent(n.decisionKey, autoplay = null))
            .setDeleteIntent(broadcast(n.decisionKey, ACTION_DISMISSED)) // clears delivery_media.pending
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(n.timeoutMs)
            .addAction(action(doneIcon, "Done", broadcast(n.decisionKey, ACTION_DONE)))
            .addAction(action(snoozeIcon, "Snooze 1 h", broadcast(n.decisionKey, ACTION_SNOOZE)))
        if (n.hasVoice || n.hasVideo) {
            // Playback needs a visible activity (Android 17 audio hardening), so this opens the app; no trampoline.
            val autoplay = if (n.hasVideo) "video" else "voice"
            builder.addAction(action(playIcon, if (n.hasVideo) "Watch" else "Listen", activityIntent(n.decisionKey, autoplay)))
        }
        n.picture?.let { pic ->
            val style = Notification.BigPictureStyle().bigPicture(pic).bigLargeIcon(null as Bitmap?)
            if (Build.VERSION.SDK_INT >= 31) {
                style.showBigPictureWhenCollapsed(true)
                n.pictureDescription?.let { style.setContentDescription(it) }
            } else {
                builder.setLargeIcon(pic) // thumbnail in the collapsed view before 31
            }
            builder.setStyle(style)
        }
        nm.notify(n.decisionKey, NOTIFICATION_ID, builder.build())
        return NotifyOutcome.POSTED
    }

    private fun action(icon: Int, title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, icon), title, intent).build()

    private fun activityIntent(decisionKey: String, autoplay: String?): PendingIntent {
        // appendPath percent-encodes the '|' and ':' that decision keys contain.
        val uri = Uri.Builder().scheme("agentle").authority("intervention").appendPath(decisionKey)
            .apply { if (autoplay != null) appendQueryParameter("autoplay", autoplay) }.build()
        val intent = Intent(Intent.ACTION_VIEW, uri).setComponent(activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // The data URI differs per (decisionKey, autoplay), so Intent.filterEquals keeps these PendingIntents apart.
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun broadcast(decisionKey: String, action: String): PendingIntent {
        val uri = Uri.Builder().scheme("agentle").authority("delivery").appendPath(decisionKey).appendPath(action).build()
        val intent = Intent(action).setComponent(actionReceiver).setData(uri) // distinct per (key, action)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val NOTIFICATION_ID = 1 // doc 10: tag = decisionKey, id = 1
        const val GROUP = "checkins"
        const val CH_NUDGE = "jitai_nudge"
        const val CH_QUIET = "jitai_quiet"
        const val ACTION_DONE = "agentle.action.DONE"
        const val ACTION_SNOOZE = "agentle.action.SNOOZE"
        const val ACTION_DISMISSED = "agentle.action.DISMISSED"
    }
}
```

### A.6 JVM tests (21 tests, 0 failures)

WavTest.kt (8 tests):

```kotlin
// mediacheck/src/test/kotlin/agentle/media/storage/WavTest.kt
package agentle.media.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WavTest {
    private val dir: File = Files.createTempDirectory("wav").toFile()

    private fun pcmFile(name: String, sampleRate: Int, frames: Int, declaredSize: Long? = null): File {
        val data = ByteArray(frames * 2) { (it % 7).toByte() }
        val f = File(dir, name)
        f.writeBytes(Wav.header(sampleRate, 1, 16, declaredSize ?: data.size.toLong()) + data)
        return f
    }

    @Test fun parsesCanonicalHeader() {
        val info = assertNotNull(Wav.parse(pcmFile("a.wav", 24_000, 24_000)))
        assertEquals(24_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertTrue(info.isPcm16)
        assertEquals(44L, info.dataOffset)
        assertEquals(1_000_000L, info.durationUs)
    }

    @Test fun zeroDataSizeFallsBackToFileLength() {
        val info = assertNotNull(Wav.parse(pcmFile("z.wav", 16_000, 8_000, declaredSize = 0)))
        assertEquals(500_000L, info.durationUs)
    }

    @Test fun truncatedFileIsClamped() {
        val info = assertNotNull(Wav.parse(pcmFile("t.wav", 16_000, 100, declaredSize = 1_000_000)))
        assertEquals(200L, info.dataSize)
    }

    @Test fun rejectsNonWav() {
        val f = File(dir, "x.wav").apply { writeText("hello from ShadowTextToSpeech\n") }
        assertNull(Wav.parse(f))
    }

    @Test fun skipsListChunk() {
        val base = Wav.header(8_000, 1, 16, 16)
        // Insert a LIST chunk between fmt and data.
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        val bytes = base.copyOfRange(0, 36) + list + base.copyOfRange(36, 44) + ByteArray(16)
        val f = File(dir, "l.wav").apply { writeBytes(bytes) }
        val info = assertNotNull(Wav.parse(f))
        assertEquals(16L, info.dataSize)
        assertEquals(56L, info.dataOffset)
    }

    @Test fun concatAddsSilenceAndKeepsFormat() {
        val a = pcmFile("c1.wav", 22_050, 22_050)
        val b = pcmFile("c2.wav", 22_050, 11_025)
        val out = File(dir, "out.wav")
        val info = assertNotNull(Wav.concat(listOf(a, b), out, silenceBetweenMs = 100))
        assertEquals(22_050 + 11_025 + 2_205L, info.frameCount)
    }

    @Test fun concatAppendsTrailingSilence() {
        val a = pcmFile("t1.wav", 16_000, 16_000)
        val info = assertNotNull(Wav.concat(listOf(a), File(dir, "tail.wav"), silenceBetweenMs = 150, trailingSilenceMs = 300))
        assertEquals(16_000 + 4_800L, info.frameCount)
        assertEquals(1_300_000L, info.durationUs)
    }

    @Test fun concatRejectsMixedRates() {
        assertNull(Wav.concat(listOf(pcmFile("m1.wav", 16_000, 10), pcmFile("m2.wav", 24_000, 10)), File(dir, "m.wav")))
    }
}
```

TtsTextChunkerTest.kt (4 tests):

```kotlin
// mediacheck/src/test/kotlin/agentle/media/voice/TtsTextChunkerTest.kt
package agentle.media.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsTextChunkerTest {
    @Test fun shortTextIsOneChunk() = assertEquals(listOf("Take a short walk."), TtsTextChunker.split("  Take a short walk.  ", 4000))

    @Test fun emptyTextHasNoChunks() = assertEquals(emptyList(), TtsTextChunker.split("   ", 4000))

    @Test fun splitsOnSentencesAndRespectsLimit() {
        val text = (1..50).joinToString(" ") { "Sentence number $it is here." }
        val chunks = TtsTextChunker.split(text, 120)
        assertTrue(chunks.all { it.length <= 120 }, chunks.toString())
        assertEquals(text, chunks.joinToString(" "))
        assertTrue(chunks.all { it.endsWith(".") })
    }

    @Test fun hardSplitsVeryLongSentence() {
        val text = List(100) { "word$it" }.joinToString(" ")
        val chunks = TtsTextChunker.split(text, 50)
        assertTrue(chunks.all { it.length <= 50 })
        assertEquals(text, chunks.joinToString(" "))
    }
}
```

SlideTimelineTest.kt (4 tests):

```kotlin
// mediacheck/src/test/kotlin/agentle/media/video/SlideTimelineTest.kt
package agentle.media.video

import agentle.media.storage.Wav
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SlideTimelineTest {
    @Test fun boundariesDoNotDrift() {
        val d = SlideTimeline.allocate(listOf(1_000_400L, 2_000_400L, 500_000L), gapMs = 150, tailMs = 300)
        assertEquals(listOf(1150L, 2151L, 800L), d)
        assertEquals(4101L, d.sum())
    }

    @Test fun singleSlideGetsTail() = assertEquals(listOf(2300L), SlideTimeline.allocate(listOf(2_000_000L), tailMs = 300))

    @Test fun rejectsEmpty() {
        assertFailsWith<IllegalArgumentException> { SlideTimeline.allocate(emptyList()) }
        assertFailsWith<IllegalArgumentException> { SlideTimeline.allocate(listOf(0L)) }
    }

    @Test fun matchesConcatenatedNarration() {
        val dir: File = Files.createTempDirectory("tl").toFile()
        val rate = 24_000
        val parts = listOf(24_013, 7_211, 50_000).mapIndexed { i, frames ->
            File(dir, "p$i.wav").apply { writeBytes(Wav.header(rate, 1, 16, frames * 2L) + ByteArray(frames * 2)) }
        }
        val partUs = parts.map { assertNotNull(Wav.parse(it)).durationUs }
        val narration = assertNotNull(Wav.concat(parts, File(dir, "n.wav"), silenceBetweenMs = 150, trailingSilenceMs = 300))
        val slidesMs = SlideTimeline.allocate(partUs, gapMs = 150, tailMs = 300)
        val videoUs = slidesMs.sum() * 1_000
        // Audio (with trailing silence) and video sequences end together, within 1 ms of rounding.
        assertTrue(abs(videoUs - narration.durationUs) <= 1_000, "video=$videoUs audio=${narration.durationUs}")
    }
}
```

MediaEvictionPolicyTest.kt (5 tests):

```kotlin
// mediacheck/src/test/kotlin/agentle/media/storage/MediaEvictionPolicyTest.kt
package agentle.media.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaEvictionPolicyTest {
    private val now = 1_000_000_000L
    private val hour = 3_600_000L
    private val mb = 1024L * 1024
    private fun rec(id: String, kind: MediaKind, sizeMb: Long, usedHoursAgo: Long, pinned: Boolean = false,
                    pending: Boolean = false, expiresAt: Long? = null, createdHoursAgo: Long = usedHoursAgo) =
        MediaAssetRecord(id, kind, sizeMb * mb, now - createdHoursAgo * hour, now - usedHoursAgo * hour, pinned, pending, expiresAt)

    private val quota = MediaQuota(totalBytes = 100 * mb, perKindBytes = mapOf(MediaKind.VIDEO to 60 * mb), minAgeMs = hour)

    @Test fun underQuotaEvictsOnlyExpired() {
        val rs = listOf(rec("a", MediaKind.IMAGE, 1, 5, expiresAt = now - 1), rec("b", MediaKind.IMAGE, 1, 5))
        assertEquals(listOf("a"), MediaEvictionPolicy.select(rs, quota, now))
    }

    @Test fun perKindCapEvictsLeastRecentlyUsedOfThatKind() {
        val rs = listOf(rec("v1", MediaKind.VIDEO, 30, 10), rec("v2", MediaKind.VIDEO, 30, 2), rec("v3", MediaKind.VIDEO, 30, 3),
            rec("i1", MediaKind.IMAGE, 1, 20))
        assertEquals(listOf("v1"), MediaEvictionPolicy.select(rs, quota, now))
    }

    @Test fun pinnedPendingAndYoungAreNeverEvicted() {
        val rs = listOf(rec("p", MediaKind.VIDEO, 50, 30, pinned = true), rec("q", MediaKind.VIDEO, 50, 29, pending = true),
            rec("y", MediaKind.AUDIO, 50, 0, createdHoursAgo = 0))
        assertEquals(emptyList(), MediaEvictionPolicy.select(rs, quota, now))
    }

    @Test fun totalCapEvictsAcrossKinds() {
        val rs = listOf(rec("a", MediaKind.AUDIO, 40, 9), rec("i", MediaKind.IMAGE, 40, 8), rec("v", MediaKind.VIDEO, 40, 1))
        assertEquals(listOf("a"), MediaEvictionPolicy.select(rs, quota, now))
    }

    @Test fun admissionMakesRoomOrRefuses() {
        val rs = listOf(rec("v1", MediaKind.VIDEO, 30, 10), rec("v2", MediaKind.VIDEO, 25, 2))
        val plan = MediaEvictionPolicy.planAdmission(rs, quota, MediaKind.VIDEO, 10 * mb, now)
        assertTrue(plan.admitted); assertEquals(listOf("v1"), plan.evictIds)
        val pinned = rs.map { it.copy(pinned = true) }
        assertFalse(MediaEvictionPolicy.planAdmission(pinned, quota, MediaKind.VIDEO, 10 * mb, now).admitted)
    }
}
```
