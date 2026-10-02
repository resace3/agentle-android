package dev.agentle.core.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.AEADBadTagException

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
 * KEKs as plain Tink AES-256-GCM (raw output) keysets held in memory, for Robolectric and JVM tests: there is no
 * AndroidKeyStore provider there (docs/research/04 §4, "JCE fake"). Never bind it in production: keys vanish with the
 * process. [failNext] injects Keystore failures into the next [aead] calls.
 */
class InMemoryKekProvider : KeyEncryptionKeyProvider {
    private val keys = ConcurrentHashMap<KekAlias, Aead>()
    private val pendingFailures = ConcurrentLinkedQueue<() -> Throwable>()

    /** The next [count] calls of [aead] throw what [failure] creates. */
    fun failNext(count: Int = 1, failure: () -> Throwable) {
        repeat(count) { pendingFailures.add(failure) }
    }

    override fun hasKey(alias: KekAlias): Boolean = keys.containsKey(alias)

    override fun createKey(alias: KekAlias) {
        AeadConfig.register()
        val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_RAW"))
        keys[alias] = handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun aead(alias: KekAlias): Aead {
        pendingFailures.poll()?.let { throw it() }
        return keys[alias] ?: throw InvalidKeyException("no key for alias")
    }

    override fun deleteKey(alias: KekAlias) {
        keys.remove(alias)
    }
}

/** Whether retrying (later, or after a reboot) can succeed. */
enum class FailureKind { PERMANENT, TRANSIENT }

/**
 * Classification of Keystore and unwrap failures (red team lifecycle-battery-08). Permanent:
 * [KeyPermanentlyInvalidatedException] and [AEADBadTagException] anywhere in the cause chain. Everything else
 * (KeyStoreException, ProviderException, I/O) is transient. Missing aliases and "file is not a database" are
 * classified by the callers that can see them.
 */
object KeystoreFailures {
    fun classify(error: Throwable): FailureKind =
        if (causes(error).any { it is KeyPermanentlyInvalidatedException || it is AEADBadTagException }) {
            FailureKind.PERMANENT
        } else {
            FailureKind.TRANSIENT
        }

    internal fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)

    private const val MAX_CAUSE_DEPTH = 8
}
