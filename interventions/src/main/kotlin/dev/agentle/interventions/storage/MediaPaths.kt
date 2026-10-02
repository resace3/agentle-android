package dev.agentle.interventions.storage

import android.content.Context
import android.content.res.AssetManager
import dev.agentle.core.model.MediaKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Where generated media lives (R09 §9.1): `noBackupFilesDir/media/{image,audio,video}` (never in cloud backup, never
 * evicted by the system like the cache) and share copies in `cacheDir/share`. A row stores the path relative to
 * `noBackupFilesDir` (`media/audio/<id>.wav`).
 */
class MediaPaths(private val noBackupDir: () -> File, private val cacheDir: () -> File) {
    val base: File get() = noBackupDir()
    val root: File get() = File(base, ROOT)
    val shareDir: File get() = File(cacheDir(), SHARE)

    fun dir(kind: MediaKind): File = File(root, subdir(kind))

    fun relativePath(file: File): String = file.relativeTo(base).invariantSeparatorsPath

    /** The file a row points at, or null when the stored path would leave the media root. */
    fun fileOf(localUri: String): File? {
        val file = File(base, localUri).canonicalFile
        val rootPath = root.canonicalFile.path + File.separator
        return file.takeIf { it.path.startsWith(rootPath) }
    }

    companion object {
        const val ROOT: String = "media"
        const val SHARE: String = "share"

        fun subdir(kind: MediaKind): String = when (kind) {
            MediaKind.IMAGE -> "image"
            MediaKind.VOICE -> "audio"
            MediaKind.VIDEO -> "video"
        }

        fun from(context: Context): MediaPaths = MediaPaths({ context.noBackupFilesDir }, { context.cacheDir })
    }
}

/** A delivery's media, as `PreparedDelivery.mediaRef` carries it. */
sealed interface MediaRef {
    val encoded: String

    /** A generated or previously composed file with a `media_artifact` row. */
    data class Stored(val id: String) : MediaRef {
        override val encoded: String get() = "$STORED_PREFIX$id"
    }

    /** A file bundled in the APK under `assets/media/` (the media catalog); read-only, never copied or deleted. */
    data class Bundled(val assetPath: String) : MediaRef {
        override val encoded: String get() = "$BUNDLED_PREFIX$assetPath"
    }

    companion object {
        private const val STORED_PREFIX = "media:"
        private const val BUNDLED_PREFIX = "asset:"

        fun parse(value: String?): MediaRef? = when {
            value == null -> null
            value.startsWith(STORED_PREFIX) -> value.removePrefix(STORED_PREFIX).takeIf { it.isNotBlank() }?.let(::Stored)
            value.startsWith(BUNDLED_PREFIX) -> value.removePrefix(BUNDLED_PREFIX).takeIf { it.isNotBlank() }?.let(::Bundled)
            else -> null
        }
    }
}

/**
 * The bundled media catalog: `assets/media/<assetId>.<ext>`. v1 ships no bundled files, so every lookup misses and
 * IMAGE or VIDEO deliveries that name one downgrade to text; adding files here needs no code change.
 */
class BundledMedia(private val assets: AssetManager) {
    /** The asset path of [assetId] for [kind], or null when the catalog has none (or the id is not a plain name). */
    fun find(assetId: String, kind: MediaKind): String? {
        if (!ASSET_ID.matches(assetId)) return null
        return extensions(kind).map { "$DIR/$assetId.$it" }.firstOrNull(::exists)
    }

    fun open(path: String): InputStream = assets.open(path)

    fun exists(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (expected: IOException) {
        false
    }

    companion object {
        const val DIR: String = "media"

        /** A plain file stem: no separators, so a rule can never point outside `assets/media/`. */
        val ASSET_ID: Regex = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")

        fun extensions(kind: MediaKind): List<String> = when (kind) {
            MediaKind.IMAGE -> listOf("png", "webp", "jpg")
            MediaKind.VOICE -> listOf("wav")
            MediaKind.VIDEO -> listOf("mp4")
        }
    }
}

/** Temp file, fsync, rename: a reader never sees a half-written file (R09 §9.4 step 4). */
internal object AtomicFiles {
    fun tmpFor(target: File): File = File(target.parentFile, target.name + MediaReconciler.TMP_SUFFIX)

    /** Writes [target] through [write]; on any failure the temp file is deleted and the exception propagates. */
    fun write(target: File, write: (FileOutputStream) -> Unit): File {
        target.parentFile?.mkdirs()
        val tmp = tmpFor(target)
        try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
            return target
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
