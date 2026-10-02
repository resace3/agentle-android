package dev.agentle.core.security

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A random id of this installation and a per-install secret salt, both in `noBackupFilesDir/state`, so a restore or a
 * device transfer always yields a new install (and therefore drops consent grants bound to the old one).
 *
 * [pseudonymize] turns identifiers that must never be stored raw (Bluetooth addresses, account ids) into stable
 * per-install HMAC-SHA256 pseudonyms; without the salt they cannot be reversed by enumerating the input space.
 */
class InstallIdProvider(private val paths: SecurityPaths, private val random: SecureRandom = SecureRandom()) {
    @Volatile private var cachedId: String? = null

    @Volatile private var cachedSalt: ByteArray? = null

    /** 32 lowercase hex characters; created on first use. */
    @Synchronized
    fun installId(): String {
        cachedId?.let { return it }
        val file = File(paths.stateDir, ID_FILE)
        val stored = readText(file)?.trim()?.takeIf { ID_PATTERN.matches(it) }
        val id = stored ?: randomHex(ID_BYTES).also { writeQuietly(file, it.toByteArray(Charsets.US_ASCII)) }
        cachedId = id
        return id
    }

    /** HMAC-SHA256 of `purpose|value` under this install's salt, as 64 lowercase hex characters. */
    fun pseudonymize(purpose: String, value: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(salt(), HMAC))
        return mac.doFinal("$purpose|$value".toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * [bytes] secret bytes for [purpose] derived from this install's salt (for example the seed of the JITAI
     * micro-randomization draw, R10 §15.3). Stable for the install, different per purpose, never the salt itself.
     */
    fun derivedSalt(purpose: String, bytes: Int = DERIVED_SALT_BYTES): ByteArray {
        require(bytes in 1..SALT_BYTES) { "bytes must be in 1..$SALT_BYTES" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(salt(), HMAC))
        return mac.doFinal("salt|$purpose".toByteArray(Charsets.UTF_8)).copyOf(bytes)
    }

    @Synchronized
    private fun salt(): ByteArray {
        cachedSalt?.let { return it }
        val file = File(paths.stateDir, SALT_FILE)
        val stored = try {
            if (file.exists()) file.readBytes().takeIf { it.size == SALT_BYTES } else null
        } catch (expected: IOException) {
            null
        }
        val salt = stored ?: ByteArray(SALT_BYTES).also(random::nextBytes).also { writeQuietly(file, it) }
        cachedSalt = salt
        return salt
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).toHex()

    private fun readText(file: File): String? = try {
        if (file.exists()) file.readText(Charsets.US_ASCII) else null
    } catch (expected: IOException) {
        null
    }

    /** A failed write keeps the value for this process; the next start creates a new one. */
    private fun writeQuietly(file: File, bytes: ByteArray) {
        try {
            AtomicFiles.write(file, bytes)
        } catch (expected: IOException) {
            // Keep the in-memory value.
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    private companion object {
        const val ID_FILE = "install-id"
        const val SALT_FILE = "install-salt.bin"
        const val ID_BYTES = 16
        const val SALT_BYTES = 32
        const val DERIVED_SALT_BYTES = 16
        const val HMAC = "HmacSHA256"
        const val BYTE_MASK = 0xFF
        val ID_PATTERN = Regex("[0-9a-f]{32}")
    }
}

/** What [CryptoEraser.eraseAll] removed. [complete] is the verification the delete-all flow reports. */
data class CryptoEraseReport(
    val databaseKeyFileDeleted: Boolean,
    val vaultBlobsDeleted: Boolean,
    val quarantineDeleted: Boolean,
    val databaseAliasDeleted: Boolean,
    val vaultAliasDeleted: Boolean,
) {
    val complete: Boolean
        get() = databaseKeyFileDeleted && vaultBlobsDeleted && quarantineDeleted && databaseAliasDeleted && vaultAliasDeleted
}

/**
 * Crypto-erasure for "delete all personal data" (docs/ARCHITECTURE.md §14, round 2 correction 6 step 7): deletes the
 * wrapped database key, every vault blob, quarantined files and both Keystore aliases, so any copy of the encrypted
 * database or vault left on flash can no longer be decrypted. Callers close the database first.
 */
class CryptoEraser(private val kek: KeyEncryptionKeyProvider, private val paths: SecurityPaths) {
    fun eraseAll(): CryptoEraseReport = CryptoEraseReport(
        databaseKeyFileDeleted = AtomicFiles.delete(paths.databaseKeyFile),
        vaultBlobsDeleted = VaultEntry.entries.map { AtomicFiles.delete(paths.vaultFile(it)) }.all { it },
        quarantineDeleted = paths.quarantineDir.deleteRecursively() || !paths.quarantineDir.exists(),
        databaseAliasDeleted = deleteAlias(KekAlias.DATABASE),
        vaultAliasDeleted = deleteAlias(KekAlias.VAULT),
    )

    private fun deleteAlias(alias: KekAlias): Boolean = runCatching {
        kek.deleteKey(alias)
        !kek.hasKey(alias)
    }.getOrDefault(false)
}
