package dev.agentle.core.security

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Why the database key could not be obtained, and whether that can heal by itself (red team lifecycle-battery-08). */
enum class UnlockFailureReason(val kind: FailureKind) {
    /** The Keystore invalidated the key-encryption key (`KeyPermanentlyInvalidatedException`). */
    KEY_INVALIDATED(FailureKind.PERMANENT),

    /** The wrapped key file exists, but the Keystore alias that sealed it is gone. */
    ALIAS_MISSING(FailureKind.PERMANENT),

    /** The key-encryption key rejected the wrapped key (`AEADBadTagException`): another key or a damaged file. */
    WRAPPED_KEY_REJECTED(FailureKind.PERMANENT),

    /** The wrapped key file has the wrong size (truncated or overwritten). */
    WRAPPED_KEY_MALFORMED(FailureKind.PERMANENT),

    /** The key unwrapped, but SQLCipher reports "file is not a database". */
    NOT_A_DATABASE(FailureKind.PERMANENT),

    /** The wrapped key file has a format version this build does not know. */
    UNKNOWN_KEY_FORMAT(FailureKind.TRANSIENT),

    /** The database exists but its wrapped key file does not. */
    KEY_FILE_MISSING(FailureKind.TRANSIENT),

    /** Reading or writing a file failed. */
    STORAGE_ERROR(FailureKind.TRANSIENT),

    /** Any other Keystore or provider error (`KeyStoreException`, `ProviderException`, ...). */
    KEYSTORE_ERROR(FailureKind.TRANSIENT),
}

/**
 * The SQLCipher data key. [toString] is masked. SQLCipher keeps the passphrase array to key every pooled connection,
 * so the owner wipes it ([close]) only after the database has been closed.
 */
class DatabaseKey internal constructor(private val dek: ByteArray) : AutoCloseable {
    @Volatile private var closed = false

    init {
        require(dek.size == DatabaseKeyManager.DEK_BYTES) { "data key must be ${DatabaseKeyManager.DEK_BYTES} bytes" }
    }

    /**
     * The raw-key passphrase SQLCipher expects: the 67 ASCII bytes `x'<64 uppercase hex>'`, which makes SQLCipher use
     * the key as is, without PBKDF2 (docs/ARCHITECTURE.md §5.4). A new array on every call.
     */
    fun sqlCipherPassphrase(): ByteArray {
        check(!closed) { "database key is closed" }
        val out = ByteArray(RAW_KEY_PREFIX.size + dek.size * 2 + 1)
        RAW_KEY_PREFIX.copyInto(out)
        dek.forEachIndexed { index, byte ->
            val value = byte.toInt() and BYTE_MASK
            out[RAW_KEY_PREFIX.size + index * 2] = HEX[value ushr NIBBLE_BITS]
            out[RAW_KEY_PREFIX.size + index * 2 + 1] = HEX[value and NIBBLE_MASK]
        }
        out[out.size - 1] = QUOTE
        return out
    }

    /** A copy of the raw key bytes, for tests of this module. */
    internal fun copyBytes(): ByteArray = dek.copyOf()

    override fun close() {
        closed = true
        dek.fill(0)
    }

    override fun toString(): String = "DatabaseKey(***)"

    private companion object {
        const val QUOTE: Byte = '\''.code.toByte()
        val RAW_KEY_PREFIX = byteArrayOf('x'.code.toByte(), QUOTE)
        val HEX = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)
        const val BYTE_MASK = 0xFF
        const val NIBBLE_MASK = 0x0F
        const val NIBBLE_BITS = 4
    }
}

sealed interface DatabaseKeyResult {
    /** [created] is true when this call generated a new key (first start, or after a reset). */
    data class Available(val key: DatabaseKey, val created: Boolean) : DatabaseKeyResult

    /**
     * No key; there is never a plaintext fallback. [errorClass] is the simple class name of the underlying exception
     * for diagnostics (never its message).
     */
    data class Unavailable(val reason: UnlockFailureReason, val errorClass: String? = null) : DatabaseKeyResult {
        val kind: FailureKind get() = reason.kind
    }
}

/**
 * The SQLCipher data key (docs/ARCHITECTURE.md §5.4): a random 32-byte key sealed by the Keystore AEAD
 * [KekAlias.DATABASE] with AAD `agentle/db-dek/v1|agentle.db`, stored atomically in
 * `noBackupFilesDir/keys/db-dek.v1.bin` as `0x01 || IV(12) || ct(32) || tag(16)`.
 *
 * [obtainKey] retries a transient Keystore or storage failure once, then reports a typed failure. It never deletes or
 * replaces existing key material: a new key is generated only when neither the wrapped key nor the database exists.
 * Blocking; call it off the main thread (the database provider unwraps lazily on its I/O dispatcher).
 */
class DatabaseKeyManager(
    private val kek: KeyEncryptionKeyProvider,
    private val paths: SecurityPaths,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = ReentrantLock()

    fun obtainKey(): DatabaseKeyResult = lock.withLock {
        val first = attempt()
        if (first is DatabaseKeyResult.Unavailable && first.reason in RETRYABLE) attempt() else first
    }

    fun hasWrappedKey(): Boolean = paths.databaseKeyFile.exists()

    /**
     * Moves the wrapped key into the quarantine directory. Only a user-confirmed local data reset calls this; nothing
     * in the background ever does (red team lifecycle-battery-08).
     */
    fun quarantineWrappedKey(tag: String): Boolean = lock.withLock {
        Quarantine.move(paths.databaseKeyFile, paths.quarantineDir, tag)
    }

    private fun attempt(): DatabaseKeyResult {
        val file = paths.databaseKeyFile
        return when {
            file.exists() -> unwrap(file)
            paths.databaseFile.exists() -> DatabaseKeyResult.Unavailable(UnlockFailureReason.KEY_FILE_MISSING)
            else -> create(file, allowAliasReplacement = true)
        }
    }

    // Keystore implementations throw arbitrary runtime exceptions, so RuntimeException is caught next to the checked ones.
    private fun unwrap(file: File): DatabaseKeyResult {
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return unavailable(UnlockFailureReason.STORAGE_ERROR, e)
        }
        formatProblem(bytes)?.let { return DatabaseKeyResult.Unavailable(it) }
        return try {
            if (!kek.hasKey(KekAlias.DATABASE)) return DatabaseKeyResult.Unavailable(UnlockFailureReason.ALIAS_MISSING)
            val dek = kek.aead(KekAlias.DATABASE).decrypt(bytes.copyOfRange(1, bytes.size), AAD)
            if (dek.size == DEK_BYTES) {
                DatabaseKeyResult.Available(DatabaseKey(dek), created = false)
            } else {
                dek.fill(0)
                DatabaseKeyResult.Unavailable(UnlockFailureReason.WRAPPED_KEY_MALFORMED)
            }
        } catch (e: GeneralSecurityException) {
            unavailable(KeystoreFailures.reasonOf(e), e)
        } catch (e: RuntimeException) {
            unavailable(KeystoreFailures.reasonOf(e), e)
        }
    }

    private fun formatProblem(bytes: ByteArray): UnlockFailureReason? = when {
        bytes.isEmpty() -> UnlockFailureReason.WRAPPED_KEY_MALFORMED
        bytes[0] != FORMAT_V1 -> UnlockFailureReason.UNKNOWN_KEY_FORMAT
        bytes.size != WRAPPED_FILE_BYTES -> UnlockFailureReason.WRAPPED_KEY_MALFORMED
        else -> null
    }

    private fun create(file: File, allowAliasReplacement: Boolean): DatabaseKeyResult {
        val dek = ByteArray(DEK_BYTES).also(random::nextBytes)
        var errorClass: String? = null
        val failure: UnlockFailureReason = try {
            if (!kek.hasKey(KekAlias.DATABASE)) kek.createKey(KekAlias.DATABASE)
            val aead = kek.aead(KekAlias.DATABASE)
            val wrapped = aead.encrypt(dek, AAD)
            // Read back before anything depends on the key: some Keystore implementations encrypt but cannot decrypt.
            val readBack = aead.decrypt(wrapped, AAD)
            val matches = readBack.contentEquals(dek)
            readBack.fill(0)
            if (!matches) {
                UnlockFailureReason.KEYSTORE_ERROR
            } else {
                AtomicFiles.write(file) { out ->
                    out.write(FORMAT_V1.toInt())
                    out.write(wrapped)
                }
                return DatabaseKeyResult.Available(DatabaseKey(dek), created = true)
            }
        } catch (e: IOException) {
            errorClass = e.javaClass.simpleName
            UnlockFailureReason.STORAGE_ERROR
        } catch (e: GeneralSecurityException) {
            errorClass = e.javaClass.simpleName
            KeystoreFailures.reasonOf(e)
        } catch (e: RuntimeException) {
            errorClass = e.javaClass.simpleName
            KeystoreFailures.reasonOf(e)
        }
        dek.fill(0)
        // A leftover alias from before a reset may be invalidated. No ciphertext exists yet (neither the wrapped key nor
        // the database), so replacing that alias destroys nothing.
        if (allowAliasReplacement && failure == UnlockFailureReason.KEY_INVALIDATED && !file.exists()) {
            runCatching { kek.deleteKey(KekAlias.DATABASE) }
            return create(file, allowAliasReplacement = false)
        }
        return DatabaseKeyResult.Unavailable(failure, errorClass)
    }

    private fun unavailable(reason: UnlockFailureReason, error: Throwable): DatabaseKeyResult.Unavailable =
        DatabaseKeyResult.Unavailable(reason, error.javaClass.simpleName)

    companion object {
        const val DEK_BYTES: Int = 32
        const val FORMAT_V1: Byte = 1

        /** Version byte + IV(12) + sealed key(32) + tag(16). */
        const val WRAPPED_FILE_BYTES: Int = 1 + 12 + DEK_BYTES + 16

        val AAD: ByteArray = "agentle/db-dek/v1|agentle.db".toByteArray(Charsets.UTF_8)

        private val RETRYABLE = setOf(UnlockFailureReason.KEYSTORE_ERROR, UnlockFailureReason.STORAGE_ERROR)
    }
}
