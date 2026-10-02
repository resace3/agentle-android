package dev.agentle.core.oauth

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import okhttp3.HttpUrl

/**
 * Opens an authorization URL in the user's browser (RFC 8252 §8.12: never an embedded WebView).
 *
 * The Android implementation launches a plain Custom Tab (`androidx.browser`; not Auth Tab, which rejects http
 * redirects) and falls back to `ACTION_VIEW`; the fake flavor and tests use `FakeBrowserLauncher`, which follows the
 * authorization redirect in-process. [launch] returns once the browser was asked to open [url]; the result of the
 * authorization arrives separately, through the [LoopbackCallbackServer].
 */
public fun interface BrowserLauncher {
    /**
     * Failure only if no browser could be started: [NO_BROWSER_ERROR] when no installed and enabled browser can
     * handle [url] (red team oauth-security-16: a distinct outcome, never a crash), another [AppError] otherwise.
     */
    public suspend fun launch(url: HttpUrl): Outcome<Unit>

    public companion object {
        /** The [AppError.UnsupportedFeature.feature] of [NO_BROWSER_ERROR]. */
        public const val NO_BROWSER: String = "no_browser"

        /** What a launcher returns when no browser resolves the URL (Android: `ActivityNotFoundException`). */
        public val NO_BROWSER_ERROR: AppError = AppError.UnsupportedFeature(NO_BROWSER)

        public fun isNoBrowser(error: AppError): Boolean = error is AppError.UnsupportedFeature && error.feature == NO_BROWSER
    }
}

/** A [BrowserLauncher] that only records the URLs it was asked to open (unit tests that play the browser themselves). */
public class RecordingBrowserLauncher : BrowserLauncher {
    private val urls = java.util.concurrent.CopyOnWriteArrayList<HttpUrl>()

    public val launched: List<HttpUrl> get() = urls.toList()

    override suspend fun launch(url: HttpUrl): Outcome<Unit> {
        urls += url
        return Outcome.Success(Unit)
    }
}
