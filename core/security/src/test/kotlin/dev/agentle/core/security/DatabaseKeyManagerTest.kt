package dev.agentle.core.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * Database key wrapping (docs/ARCHITECTURE.md §5.4; red team lifecycle-battery-08; R04 §4 SEC-DB-02 and SEC-BAK-03,
 * Robolectric part with the JCE fake behind the KEK port).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DatabaseKeyManagerTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var kek: InMemoryKekProvider
    private lateinit var paths: SecurityPaths
    private lateinit var manager: DatabaseKeyManager

    @Before
    fun setUp() {
        kek = InMemoryKekProvider()
        paths = SecurityPaths(temp.newFolder("nobackup"), File(temp.newFolder("databases"), SecurityPaths.DATABASE_NAME))
        manager = KeystoreDatabaseKeyManager(kek, paths)
    }

    @Test
    fun `first start creates a wrapped key and later starts unwrap the same key`() {
        val first = manager.obtainKey() as DatabaseKeyResult.Available
        assertThat(first.created).isTrue()
        val file = paths.databaseKeyFile
        assertThat(file.length()).isEqualTo(DatabaseKeyFormat.WRAPPED_FILE_BYTES.toLong())
        assertThat(file.readBytes()[0]).isEqualTo(DatabaseKeyFormat.FORMAT_V1)
        assertThat(File(file.path + AtomicFiles.TEMP_SUFFIX).exists()).isFalse()

        val second = KeystoreDatabaseKeyManager(kek, paths).obtainKey() as DatabaseKeyResult.Available
        assertThat(second.created).isFalse()
        assertThat(second.key.copyBytes()).isEqualTo(first.key.copyBytes())
    }

    @Test
    fun `the wrapped file never contains the raw key`() {
        val key = (manager.obtainKey() as DatabaseKeyResult.Available).key
        val raw = key.copyBytes()
        val stored = paths.databaseKeyFile.readBytes()
        val containsRaw = (0..stored.size - raw.size).any { offset -> raw.indices.all { stored[offset + it] == raw[it] } }
        assertThat(containsRaw).isFalse()
    }

    @Test
    fun `the SQLCipher passphrase is the raw key literal`() {
        val key = (manager.obtainKey() as DatabaseKeyResult.Available).key
        val passphrase = String(key.sqlCipherPassphrase(), Charsets.US_ASCII)
        val hex = key.copyBytes().joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        assertThat(passphrase).hasLength(67)
        assertThat(passphrase).isEqualTo("x'$hex'")
        assertThat(key.toString()).doesNotContain(hex)
    }

    @Test
    fun `a closed key is wiped`() {
        val key = (manager.obtainKey() as DatabaseKeyResult.Available).key
        key.close()
        assertThat(key.copyBytes().all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `a key sealed with another AAD is rejected permanently`() {
        manager.obtainKey()
        val dek = ByteArray(DatabaseKeyFormat.DEK_BYTES) { 7 }
        val other = kek.aead(KekAlias.DATABASE).encrypt(dek, "agentle/db-dek/v1|other.db".toByteArray())
        paths.databaseKeyFile.writeBytes(byteArrayOf(DatabaseKeyFormat.FORMAT_V1) + other)

        val result = manager.obtainKey() as DatabaseKeyResult.Unavailable
        assertThat(result.reason).isEqualTo(UnlockFailureReason.WRAPPED_KEY_REJECTED)
        assertThat(result.kind).isEqualTo(FailureKind.PERMANENT)
    }

    @Test
    fun `a flipped byte is rejected permanently and the file is left in place`() {
        manager.obtainKey()
        val bytes = paths.databaseKeyFile.readBytes()
        bytes[20] = (bytes[20].toInt() xor 0x01).toByte()
        paths.databaseKeyFile.writeBytes(bytes)

        val result = manager.obtainKey() as DatabaseKeyResult.Unavailable
        assertThat(result.reason).isEqualTo(UnlockFailureReason.WRAPPED_KEY_REJECTED)
        assertThat(paths.databaseKeyFile.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `a truncated file is malformed and an unknown version is transient`() {
        manager.obtainKey()
        val bytes = paths.databaseKeyFile.readBytes()
        paths.databaseKeyFile.writeBytes(bytes.copyOf(30))
        assertThat((manager.obtainKey() as DatabaseKeyResult.Unavailable).reason).isEqualTo(UnlockFailureReason.WRAPPED_KEY_MALFORMED)

        paths.databaseKeyFile.writeBytes(byteArrayOf(9) + bytes.copyOfRange(1, bytes.size))
        val unknown = manager.obtainKey() as DatabaseKeyResult.Unavailable
        assertThat(unknown.reason).isEqualTo(UnlockFailureReason.UNKNOWN_KEY_FORMAT)
        assertThat(unknown.kind).isEqualTo(FailureKind.TRANSIENT)
    }

    @Test
    fun `a missing alias with an existing wrapped key is permanent and no new key is generated`() {
        manager.obtainKey()
        kek.deleteKey(KekAlias.DATABASE)
        val before = paths.databaseKeyFile.readBytes()

        val result = manager.obtainKey() as DatabaseKeyResult.Unavailable
        assertThat(result.reason).isEqualTo(UnlockFailureReason.ALIAS_MISSING)
        assertThat(kek.hasKey(KekAlias.DATABASE)).isFalse()
        assertThat(paths.databaseKeyFile.readBytes()).isEqualTo(before)
    }

    @Test
    fun `a database without its key file is not re-keyed`() {
        paths.databaseFile.writeBytes(ByteArray(4096))
        val result = manager.obtainKey() as DatabaseKeyResult.Unavailable
        assertThat(result.reason).isEqualTo(UnlockFailureReason.KEY_FILE_MISSING)
        assertThat(result.kind).isEqualTo(FailureKind.TRANSIENT)
        assertThat(paths.databaseKeyFile.exists()).isFalse()
    }

    @Test
    fun `one transient Keystore failure is retried`() {
        manager.obtainKey()
        kek.failNext(1) { KeyStoreException("busy") }
        assertThat(manager.obtainKey()).isInstanceOf(DatabaseKeyResult.Available::class.java)
    }

    @Test
    fun `two transient Keystore failures report a transient error with the class name only`() {
        manager.obtainKey()
        kek.failNext(2) { ProviderException("Keystore operation failed: secret detail") }
        val result = manager.obtainKey() as DatabaseKeyResult.Unavailable
        assertThat(result.reason).isEqualTo(UnlockFailureReason.KEYSTORE_ERROR)
        assertThat(result.kind).isEqualTo(FailureKind.TRANSIENT)
        assertThat(result.errorClass).isEqualTo("ProviderException")
        assertThat(result.toString()).doesNotContain("secret detail")
    }

    @Test
    fun `an invalidated key-encryption key is permanent`() {
        manager.obtainKey()
        kek.failNext(2) { KeyPermanentlyInvalidatedException() }
        val result = manager.obtainKey() as DatabaseKeyResult.Unavailable
        assertThat(result.reason).isEqualTo(UnlockFailureReason.KEY_INVALIDATED)
        assertThat(result.kind).isEqualTo(FailureKind.PERMANENT)
    }

    @Test
    fun `a failed write leaves no key file and no temp file`() {
        // The keys "directory" is a file, so the atomic write cannot create its temp file.
        paths.noBackupDir.mkdirs()
        paths.keysDir.writeText("not a directory")
        val result = manager.obtainKey() as DatabaseKeyResult.Unavailable
        assertThat(result.reason).isEqualTo(UnlockFailureReason.STORAGE_ERROR)
        assertThat(paths.databaseKeyFile.exists()).isFalse()
    }

    @Test
    fun `quarantine moves the wrapped key aside`() {
        manager.obtainKey()
        val bytes = paths.databaseKeyFile.readBytes()
        assertThat(manager.quarantineWrappedKey("reset-1")).isTrue()
        assertThat(paths.databaseKeyFile.exists()).isFalse()
        assertThat(File(File(paths.quarantineDir, "reset-1"), paths.databaseKeyFile.name).readBytes()).isEqualTo(bytes)
    }
}
