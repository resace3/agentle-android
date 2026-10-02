package dev.agentle.connectors.android.core

import android.content.Context
import dev.agentle.core.common.Logger
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

internal object Hashing {
    fun sha256Hex(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { b ->
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Salted SHA-256 for identifiers that must never be stored raw: Bluetooth addresses, notification keys and channel ids,
 * calendar event ids (docs/research/04 §3.8). The salt is per install, so hashes are stable on this device (dedup keys
 * converge across runs) and useless anywhere else.
 */
public class IdentifierHasher(salt: ByteArray) {
    private val salt: ByteArray = salt.copyOf()

    /** Full 64-hex-character hash. */
    public fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(value.toByteArray(Charsets.UTF_8))
        return Hashing.hex(digest.digest())
    }

    /** The first 16 hex characters (64 bits), for dedup keys. */
    public fun shortHash(value: String): String = hash(value).take(SHORT_LENGTH)

    private companion object {
        const val SHORT_LENGTH = 16
    }
}

/** Loads or creates the per-install salt in `noBackupFilesDir` (never backed up, removed with the app). */
internal object InstallSalt {
    private const val FILE_NAME = "agentle_collectors.salt"
    private const val SIZE = 32

    fun load(context: Context, logger: Logger): ByteArray {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        return try {
            if (file.isFile && file.length() == SIZE.toLong()) {
                file.readBytes()
            } else {
                val salt = ByteArray(SIZE).also { SecureRandom().nextBytes(it) }
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeBytes(salt)
                if (!tmp.renameTo(file)) throw IOException("rename failed")
                salt
            }
        } catch (e: IOException) {
            // Hashes then differ per process start: dedup keys of hashed identifiers stop converging until the file works.
            logger.w("collectors.salt", "Salt file unavailable; using a process-local salt", fields = mapOf("error" to e::class.simpleName))
            ByteArray(SIZE).also { SecureRandom().nextBytes(it) }
        }
    }
}
