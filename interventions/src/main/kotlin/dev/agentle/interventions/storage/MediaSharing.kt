package dev.agentle.interventions.storage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.onSuccess
import dev.agentle.core.common.outcomeOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The share-copy provider: `cacheDir/share/` only (`res/xml/interventions_share_paths.xml`). Its own subclass, so its
 * manifest entry never collides with another module's FileProvider.
 */
class InterventionsFileProvider : FileProvider() {
    companion object {
        fun authority(context: Context): String = "${context.packageName}.interventions.share"
    }
}

/** content:// URIs for share copies (a seam: tests check the copy and the grant without a provider). */
fun interface ShareUris {
    fun uriFor(file: File): Uri
}

class FileProviderShareUris(private val context: Context) : ShareUris {
    override fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, InterventionsFileProvider.authority(context), file)
}

/**
 * "Share" for generated media: the file is copied to `cacheDir/share/` (FileProvider has no no-backup root) and sent
 * with an explicit read grant, `ClipData` plus `FLAG_GRANT_READ_URI_PERMISSION` (Android 18 stops granting implicitly
 * for ACTION_SEND, R09 §9.1). The daily maintenance pass deletes copies older than a day.
 */
class MediaSharing(
    private val library: MediaLibrary,
    private val paths: MediaPaths,
    private val uris: ShareUris,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** An ACTION_SEND intent for [id]; the caller wraps it in `Intent.createChooser` from a visible screen. */
    suspend fun shareIntent(id: String): Outcome<Intent> {
        val record = library.record(id) ?: return Outcome.failure(AppError.Unexpected(MEDIA_MISSING))
        val source = library.fileOf(record) ?: return Outcome.failure(AppError.Unexpected(MEDIA_MISSING))
        return outcomeOf(mapError = { AppError.Unexpected("share:${it::class.simpleName}") }) {
            val copy = withContext(io) {
                AtomicFiles.write(File(paths.shareDir, "${record.artifact.id}.${source.extension}")) { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
            }
            val uri = uris.uriFor(copy)
            Intent(Intent.ACTION_SEND)
                .setType(record.artifact.mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("", uri) }
        }.onSuccess { library.touch(id) }
    }

    private companion object {
        const val MEDIA_MISSING = "media_missing"
    }
}

/** Decodes a delivery picture for BigPictureStyle, at most [MAX_WIDTH] wide (R09 §6.3). Null when it cannot be read. */
class MediaPictures(
    private val library: MediaLibrary,
    private val bundled: BundledMedia,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(ref: String?): Bitmap? = withContext(io) {
        when (val parsed = MediaRef.parse(ref)) {
            is MediaRef.Stored -> library.record(parsed.id)?.let(library::fileOf)?.let { file -> decode { file.inputStream() } }
            is MediaRef.Bundled -> decode { bundled.open(parsed.assetPath) }
            null -> null
        }
    }

    private fun decode(open: () -> InputStream): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (expected: IOException) {
        null
    }

    companion object {
        const val MAX_WIDTH: Int = 1024
    }
}
