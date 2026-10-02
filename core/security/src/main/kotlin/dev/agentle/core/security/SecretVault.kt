package dev.agentle.core.security

import dev.agentle.core.common.AppDispatchers
import dev.agentle.core.common.Secret
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

/** Records in the token vault (docs/ARCHITECTURE.md §14). Each is one blob with its own AAD. */
enum class VaultEntry(val id: String) {
    /** SIWC tokens, serialized by the SIWC session. */
    SIWC_CREDENTIALS("siwc-credentials"),

    /** A refresh-token rotation in flight, written next to the tokens before the refresh call (red team oauth-security). */
    SIWC_PENDING_ROTATION("siwc-pending-rotation"),

    /** The SIWC client registration issued to this install. */
    SIWC_CLIENT_REGISTRATION("siwc-client-registration"),
}

enum class VaultFailureReason(val kind: FailureKind) {
    /** A blob exists but the vault key alias is gone. */
    ALIAS_MISSING(FailureKind.PERMANENT),

    /** The Keystore invalidated the vault key. */
    KEY_INVALIDATED(FailureKind.PERMANENT),

    /** The vault key rejected the blob (`AEADBadTagException`): another key, another record, or a damaged file. */
    BLOB_REJECTED(FailureKind.PERMANENT),

    /** The blob has an unknown format or the wrong size. */
    BLOB_MALFORMED(FailureKind.PERMANENT),

    /** Reading or writing the blob failed. */
    STORAGE_ERROR(FailureKind.TRANSIENT),

    /** Any other Keystore or provider error. */
    KEYSTORE_ERROR(FailureKind.TRANSIENT),
}

sealed interface VaultRead {
    data class Present(val secret: Secret, val recordVersion: Int) : VaultRead

    data object Absent : VaultRead

    /**
     * Permanently unreadable. The blob has been wiped; the owner signs in again and maps this to
     * `REAUTH_REQUIRED(`[REAUTH_CODE]`)`.
     */
    data class Unreadable(val reason: VaultFailureReason) : VaultRead {
        companion object {
            const val REAUTH_CODE: String = "LOCAL_CREDENTIALS_UNREADABLE"
        }
    }

    /** A transient failure; nothing was changed, so a later read can succeed. [errorClass] never carries a message. */
    data class Unavailable(val reason: VaultFailureReason, val errorClass: String? = null) : VaultRead
}

sealed interface VaultWrite {
    data object Written : VaultWrite

    data class Failed(val reason: VaultFailureReason, val errorClass: String? = null) : VaultWrite
}

/**
 * Sealed storage for credentials (round 2 correction 7). Implementations never throw for Keystore or storage
 * failures, never fall back to plaintext and never store a key next to the data.
 */
interface SecretVault {
    suspend fun read(entry: VaultEntry): VaultRead

    suspend fun write(entry: VaultEntry, secret: Secret, recordVersion: Int = 1): VaultWrite

    /** Removes one record. Returns true if it no longer exists. */
    suspend fun delete(entry: VaultEntry): Boolean

    /** Crypto-erasure: every record, then the vault key. Returns true if nothing is left. */
    suspend fun wipe(): Boolean
}

/**
 * [SecretVault] on Tink's Android Keystore AEAD (`AndroidKeystore.generateNewAes256GcmKey("agentle.kek.vault.v1")`
 * and `getAead`), never `AndroidKeysetManager`, so no keyset ever lands in shared preferences. Each record is one
 * blob under `noBackupFilesDir/vault`, replaced atomically: `0x01 || recordVersion(4, big-endian) || IV || ct || tag`,
 * with AAD `agentle/<entry>/v1|<package>|<recordVersion>`.
 *
 * Construction does no work and never throws; the key is touched on first use. A key is generated only when there is
 * no ciphertext it would orphan: blobs sealed by a missing or invalidated key are unreadable forever and are removed
 * first. A permanent failure wipes that record and reports [VaultRead.Unreadable]; a transient one changes nothing.
 */
class TinkSecretVault(
    private val kek: KeyEncryptionKeyProvider,
    private val paths: SecurityPaths,
    private val packageName: String,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : SecretVault {
    private val mutex = Mutex()

    override suspend fun read(entry: VaultEntry): VaultRead = locked { readLocked(entry) }

    override suspend fun write(entry: VaultEntry, secret: Secret, recordVersion: Int): VaultWrite = locked {
        writeLocked(entry, secret, recordVersion, allowKeyReplacement = true)
    }

    override suspend fun delete(entry: VaultEntry): Boolean = locked { AtomicFiles.delete(paths.vaultFile(entry)) }

    override suspend fun wipe(): Boolean = locked {
        val blobsGone = deleteAllBlobs()
        val keyGone = runCatching {
            kek.deleteKey(KekAlias.VAULT)
            !kek.hasKey(KekAlias.VAULT)
        }.getOrDefault(false)
        blobsGone && keyGone
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(dispatchers.io) { mutex.withLock { block() } }

    private fun readLocked(entry: VaultEntry): VaultRead {
        val file = paths.vaultFile(entry)
        if (!file.exists()) return VaultRead.Absent
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return VaultRead.Unavailable(VaultFailureReason.STORAGE_ERROR, e.javaClass.simpleName)
        }
        if (bytes.size < HEADER_BYTES + MIN_SEALED_BYTES || bytes[0] != FORMAT_V1) {
            return wipeAndReport(entry, VaultFailureReason.BLOB_MALFORMED)
        }
        return open(entry, bytes)
    }

    // Keystore implementations throw arbitrary runtime exceptions, so RuntimeException is caught next to the checked ones.
    private fun open(entry: VaultEntry, bytes: ByteArray): VaultRead {
        val recordVersion = ByteBuffer.wrap(bytes, 1, Int.SIZE_BYTES).int
        return try {
            if (!kek.hasKey(KekAlias.VAULT)) return wipeAndReport(entry, VaultFailureReason.ALIAS_MISSING)
            val plain = kek.aead(KekAlias.VAULT).decrypt(bytes.copyOfRange(HEADER_BYTES, bytes.size), aad(entry, recordVersion))
            val secret = Secret(String(plain, Charsets.UTF_8))
            plain.fill(0)
            VaultRead.Present(secret, recordVersion)
        } catch (e: GeneralSecurityException) {
            readFailure(entry, e)
        } catch (e: RuntimeException) {
            readFailure(entry, e)
        }
    }

    private fun readFailure(entry: VaultEntry, error: Throwable): VaultRead = when (KeystoreFailures.reasonOf(error)) {
        UnlockFailureReason.KEY_INVALIDATED -> wipeAndReport(entry, VaultFailureReason.KEY_INVALIDATED)
        UnlockFailureReason.WRAPPED_KEY_REJECTED -> wipeAndReport(entry, VaultFailureReason.BLOB_REJECTED)
        else -> VaultRead.Unavailable(VaultFailureReason.KEYSTORE_ERROR, error.javaClass.simpleName)
    }

    private fun wipeAndReport(entry: VaultEntry, reason: VaultFailureReason): VaultRead {
        AtomicFiles.delete(paths.vaultFile(entry))
        return VaultRead.Unreadable(reason)
    }

    private fun writeLocked(entry: VaultEntry, secret: Secret, recordVersion: Int, allowKeyReplacement: Boolean): VaultWrite {
        val failure: Pair<VaultFailureReason, String> = try {
            if (!kek.hasKey(KekAlias.VAULT)) {
                // Blobs without their key can never be read again; remove them so no key is generated over ciphertext.
                deleteAllBlobs()
                kek.createKey(KekAlias.VAULT)
            }
            val plain = secret.value.toByteArray(Charsets.UTF_8)
            val sealed = try {
                kek.aead(KekAlias.VAULT).encrypt(plain, aad(entry, recordVersion))
            } finally {
                plain.fill(0)
            }
            AtomicFiles.write(paths.vaultFile(entry)) { out ->
                writeHeader(out, recordVersion)
                out.write(sealed)
            }
            return VaultWrite.Written
        } catch (e: IOException) {
            VaultFailureReason.STORAGE_ERROR to e.javaClass.simpleName
        } catch (e: GeneralSecurityException) {
            writeFailureReason(e) to e.javaClass.simpleName
        } catch (e: RuntimeException) {
            writeFailureReason(e) to e.javaClass.simpleName
        }
        if (failure.first == VaultFailureReason.KEY_INVALIDATED && allowKeyReplacement) {
            // The key can never open or seal anything again: drop its blobs and the key, then write under a new key.
            deleteAllBlobs()
            runCatching { kek.deleteKey(KekAlias.VAULT) }
            return writeLocked(entry, secret, recordVersion, allowKeyReplacement = false)
        }
        return VaultWrite.Failed(failure.first, failure.second)
    }

    private fun writeFailureReason(error: Throwable): VaultFailureReason =
        if (KeystoreFailures.reasonOf(error) == UnlockFailureReason.KEY_INVALIDATED) {
            VaultFailureReason.KEY_INVALIDATED
        } else {
            VaultFailureReason.KEYSTORE_ERROR
        }

    private fun deleteAllBlobs(): Boolean = VaultEntry.entries.map { AtomicFiles.delete(paths.vaultFile(it)) }.all { it }

    private fun writeHeader(out: OutputStream, recordVersion: Int) {
        out.write(FORMAT_V1.toInt())
        out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(recordVersion).array())
    }

    private fun aad(entry: VaultEntry, recordVersion: Int): ByteArray =
        vaultAadText(entry, packageName, recordVersion).toByteArray(Charsets.UTF_8)

    private companion object {
        const val FORMAT_V1: Byte = 1
        const val HEADER_BYTES = 1 + Int.SIZE_BYTES

        /** IV(12) + tag(16): the smallest sealed value (empty plaintext). */
        const val MIN_SEALED_BYTES = 12 + 16
    }
}

/**
 * The AAD of one vault record: `agentle/<entry>/v1|<package>|<recordVersion>` (round 2 correction 7), for example
 * `agentle/siwc-credentials/v1|dev.agentle|1`. Pinned by a golden test: changing it orphans every stored record.
 */
internal fun vaultAadText(entry: VaultEntry, packageName: String, recordVersion: Int): String =
    "agentle/${entry.id}/v1|$packageName|$recordVersion"
