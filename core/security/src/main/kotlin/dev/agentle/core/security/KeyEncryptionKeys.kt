package dev.agentle.core.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.io.File
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore key-encryption keys (docs/ARCHITECTURE.md §5.4, docs/research/04 §3.1-3.2). The alias carries a
 * version: a rotation decrypts with v1, encrypts with v2, then deletes v1. Agentle never creates per-record keys
 * (Android 17 caps apps at 50,000 Keystore keys).
 */
enum class KekAlias(val alias: String) {
    DATABASE("agentle.kek.db.v1"),
    VAULT("agentle.kek.vault.v1"),
}

/**
 * Port to the key-encryption keys. Keys are AES-256-GCM and never require user authentication or an unlocked device,
 * because workers run while the phone is locked. Every method may throw [GeneralSecurityException] or a platform
 * [java.security.ProviderException]; callers classify failures with [KeystoreFailures].
 */
interface KeyEncryptionKeyProvider {
    fun hasKey(alias: KekAlias): Boolean

    /** Creates the key. Callers check [hasKey] first: generating over an existing alias would replace the key. */
    fun createKey(alias: KekAlias)

    /** The AEAD of the key; output is `IV(12) || ciphertext || tag(16)` with no prefix. */
    fun aead(alias: KekAlias): Aead

    /** Deletes the key if it exists (crypto-erasure). */
    fun deleteKey(alias: KekAlias)
}

/**
 * Production KEKs: Tink's [AndroidKeystore]. `generateNewAes256GcmKey` sets key size 256, GCM, no padding and nothing
 * else, so there is no `setUserAuthenticationRequired` and no `setUnlockedDeviceRequired` (docs/research/04 §3.1).
 */
class AndroidKeystoreKekProvider : KeyEncryptionKeyProvider {
    override fun hasKey(alias: KekAlias): Boolean = AndroidKeystore.hasKey(alias.alias)

    override fun createKey(alias: KekAlias) = AndroidKeystore.generateNewAes256GcmKey(alias.alias)

    override fun aead(alias: KekAlias): Aead = AndroidKeystore.getAead(alias.alias)

    override fun deleteKey(alias: KekAlias) {
        if (AndroidKeystore.hasKey(alias.alias)) AndroidKeystore.deleteKey(alias.alias)
    }
}

/**
 * In-memory KEKs for Robolectric and JVM tests, where there is no AndroidKeyStore provider (docs/research/04 §4, "JCE
 * fake"). Each key is a JCE AES-256-GCM key behind Tink's [Aead] interface with the same wire format and failure types
 * as Tink's Keystore AEAD: `IV(12) || ciphertext || tag(16)`, [AEADBadTagException] on a tag mismatch and
 * [GeneralSecurityException] on a short ciphertext. Never bind it in production: keys vanish with the process.
 * [failNext] injects Keystore failures into the next [aead] calls.
 */
class InMemoryKekProvider : KeyEncryptionKeyProvider {
    private val keys = ConcurrentHashMap<KekAlias, SecretKey>()
    private val pendingFailures = ConcurrentLinkedQueue<() -> Throwable>()
    private val random = SecureRandom()

    /** The next [count] calls of [aead] throw what [failure] creates. */
    fun failNext(count: Int = 1, failure: () -> Throwable) {
        repeat(count) { pendingFailures.add(failure) }
    }

    override fun hasKey(alias: KekAlias): Boolean = keys.containsKey(alias)

    override fun createKey(alias: KekAlias) {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(AES_KEY_BITS, random)
        keys[alias] = generator.generateKey()
    }

    override fun aead(alias: KekAlias): Aead {
        pendingFailures.poll()?.let { throw it() }
        val key = keys[alias] ?: throw InvalidKeyException("no key for alias")
        return JceAesGcmAead(key, random)
    }

    override fun deleteKey(alias: KekAlias) {
        keys.remove(alias)
    }

    private class JceAesGcmAead(private val key: SecretKey, private val random: SecureRandom) : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            associatedData?.let(cipher::updateAAD)
            return iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
            if (ciphertext.size < IV_BYTES + TAG_BITS / Byte.SIZE_BITS) throw GeneralSecurityException("ciphertext too short")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES))
            associatedData?.let(cipher::updateAAD)
            return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.size - IV_BYTES)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Whether retrying (later, or after a reboot) can succeed. */
enum class FailureKind { PERMANENT, TRANSIENT }

/**
 * Classification of Keystore and unwrap failures (red team lifecycle-battery-08). Permanent:
 * [KeyPermanentlyInvalidatedException] and [AEADBadTagException] anywhere in the cause chain. Everything else
 * (`KeyStoreException`, `ProviderException`, I/O) is transient. Missing aliases and "file is not a database" are
 * classified by the callers that can see them.
 */
object KeystoreFailures {
    fun classify(error: Throwable): FailureKind = reasonOf(error).kind

    fun reasonOf(error: Throwable): UnlockFailureReason {
        val chain = causes(error).toList()
        return when {
            chain.any { it is KeyPermanentlyInvalidatedException } -> UnlockFailureReason.KEY_INVALIDATED
            chain.any { it is AEADBadTagException } -> UnlockFailureReason.WRAPPED_KEY_REJECTED
            else -> UnlockFailureReason.KEYSTORE_ERROR
        }
    }

    internal fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)

    private const val MAX_CAUSE_DEPTH = 8
}

/** Moves files aside instead of deleting them, for the user-confirmed local data reset. */
object Quarantine {
    /**
     * Renames [file] (if it exists) into `quarantineDir/<tag>/`. Returns true if [file] no longer exists. A rename within
     * the app's data directory is atomic and keeps the bytes, so nothing is lost if the reset was a mistake.
     */
    fun move(file: File, quarantineDir: File, tag: String): Boolean {
        if (!file.exists()) return true
        val target = File(File(quarantineDir, tag), file.name)
        target.parentFile?.mkdirs()
        return file.renameTo(target) || !file.exists()
    }
}
