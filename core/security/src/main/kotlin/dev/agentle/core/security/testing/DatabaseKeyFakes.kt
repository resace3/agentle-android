package dev.agentle.core.security.testing

import dev.agentle.core.security.DatabaseKey
import dev.agentle.core.security.DatabaseKeyFormat
import dev.agentle.core.security.DatabaseKeyManager
import dev.agentle.core.security.DatabaseKeyResult
import dev.agentle.core.security.UnlockFailureReason
import java.security.SecureRandom

/**
 * A [DatabaseKeyManager] without the Android Keystore, for tests of other modules (round 4 correction 1): the data key
 * comes from JCE [SecureRandom] and stays in memory. [quarantineWrappedKey] forgets it, like a user-confirmed reset.
 * Production code never uses it (the Hilt module binds the Keystore implementation).
 */
class JceDatabaseKeyManager(private val random: SecureRandom = SecureRandom()) : DatabaseKeyManager {
    private val lock = Any()
    private var key: ByteArray? = null

    override fun obtainKey(): DatabaseKeyResult = synchronized(lock) {
        val existing = key
        if (existing != null) return DatabaseKeyResult.Available(DatabaseKey(existing.copyOf()), created = false)
        val fresh = ByteArray(DatabaseKeyFormat.DEK_BYTES).also(random::nextBytes)
        key = fresh
        DatabaseKeyResult.Available(DatabaseKey(fresh.copyOf()), created = true)
    }

    override fun hasWrappedKey(): Boolean = synchronized(lock) { key != null }

    override fun quarantineWrappedKey(tag: String): Boolean = synchronized(lock) {
        val existing = key ?: return false
        existing.fill(0)
        key = null
        true
    }
}

/**
 * The failures a fault-injecting key manager can produce (round 4 correction 1), each as the production manager reports
 * it: the reason and the simple class name of the underlying exception.
 */
enum class DatabaseKeyFault(val reason: UnlockFailureReason?, val errorClass: String?) {
    /** A transient `KeyStoreException` (the production manager has already retried once). */
    TRANSIENT_KEYSTORE(UnlockFailureReason.KEYSTORE_ERROR, "KeyStoreException"),

    /** `AEADBadTagException`: the key-encryption key rejected the wrapped key. */
    BAD_TAG(UnlockFailureReason.WRAPPED_KEY_REJECTED, "AEADBadTagException"),

    /** The database exists but its wrapped key file does not. */
    KEY_FILE_MISSING(UnlockFailureReason.KEY_FILE_MISSING, null),

    /** The wrapped key file is truncated. */
    KEY_FILE_TRUNCATED(UnlockFailureReason.WRAPPED_KEY_MALFORMED, null),

    /** The Keystore alias that sealed the wrapped key is gone. */
    ALIAS_MISSING(UnlockFailureReason.ALIAS_MISSING, null),

    /**
     * The key unwraps but is not the database's key: a different random key is served, so SQLCipher reports "file is not
     * a database" when it opens the file (the database layer classifies that as [UnlockFailureReason.NOT_A_DATABASE]).
     */
    NOT_A_DATABASE(null, null),
}

/**
 * Wraps a [DatabaseKeyManager] and makes the next calls fail with injected [DatabaseKeyFault]s, in order; once they are
 * used up, calls reach the delegate again.
 */
class FaultInjectingDatabaseKeyManager(private val delegate: DatabaseKeyManager, private val random: SecureRandom = SecureRandom()) :
    DatabaseKeyManager {
    private val lock = Any()
    private val faults = ArrayDeque<DatabaseKeyFault>()

    /** How many times [obtainKey] was called. */
    @Volatile var calls: Int = 0
        private set

    /** True after a [DatabaseKeyFault.NOT_A_DATABASE] fault served a wrong key. */
    @Volatile var servedWrongKey: Boolean = false
        private set

    /** Makes the next [times] calls of [obtainKey] fail with [fault]. */
    fun inject(fault: DatabaseKeyFault, times: Int = 1) {
        require(times > 0) { "times must be positive" }
        synchronized(lock) { repeat(times) { faults.addLast(fault) } }
    }

    fun clearFaults() {
        synchronized(lock) { faults.clear() }
    }

    override fun obtainKey(): DatabaseKeyResult {
        val fault = synchronized(lock) {
            calls++
            faults.removeFirstOrNull()
        } ?: return delegate.obtainKey()
        val reason = fault.reason
        if (reason != null) return DatabaseKeyResult.Unavailable(reason, fault.errorClass)
        servedWrongKey = true
        val wrong = ByteArray(DatabaseKeyFormat.DEK_BYTES).also(random::nextBytes)
        return DatabaseKeyResult.Available(DatabaseKey(wrong), created = false)
    }

    override fun hasWrappedKey(): Boolean = delegate.hasWrappedKey()

    override fun quarantineWrappedKey(tag: String): Boolean = delegate.quarantineWrappedKey(tag)
}
