package dev.agentle.core.security

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.security.testing.DatabaseKeyFault
import dev.agentle.core.security.testing.FaultInjectingDatabaseKeyManager
import dev.agentle.core.security.testing.JceDatabaseKeyManager
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Golden values that existing installs depend on (round 4 correction 1, testing-build-06): changing any of them orphans
 * the wrapped database key or the vault records on every device. Also covers the JCE fake and the fault-injecting
 * decorator other modules test with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SecurityGoldenTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `key aliases, AADs and the wrapped key format are pinned`() {
        assertThat(KekAlias.DATABASE.alias).isEqualTo("agentle.kek.db.v1")
        assertThat(KekAlias.VAULT.alias).isEqualTo("agentle.kek.vault.v1")
        assertThat(DatabaseKeyFormat.AAD_TEXT).isEqualTo("agentle/db-dek/v1|agentle.db")
        assertThat(String(DatabaseKeyFormat.AAD, Charsets.UTF_8)).isEqualTo("agentle/db-dek/v1|agentle.db")
        assertThat(DatabaseKeyFormat.DEK_BYTES).isEqualTo(32)
        assertThat(DatabaseKeyFormat.FORMAT_V1).isEqualTo(1.toByte())
        assertThat(DatabaseKeyFormat.WRAPPED_FILE_BYTES).isEqualTo(61)
        assertThat(vaultAadText(VaultEntry.SIWC_CREDENTIALS, "dev.agentle", 1)).isEqualTo("agentle/siwc-credentials/v1|dev.agentle|1")
        assertThat(VaultEntry.entries.map { it.id })
            .containsExactly("siwc-credentials", "siwc-pending-rotation", "siwc-client-registration")
            .inOrder()
    }

    @Test
    fun `key, vault and state files live at pinned paths under noBackupFilesDir`() {
        val noBackup = temp.newFolder("no_backup")
        val database = File(temp.newFolder("databases"), SecurityPaths.DATABASE_NAME)
        val paths = SecurityPaths(noBackup, database)

        assertThat(SecurityPaths.DATABASE_NAME).isEqualTo("agentle.db")
        assertThat(paths.databaseKeyFile.relativeTo(noBackup).path).isEqualTo("keys/db-dek.v1.bin")
        assertThat(paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).relativeTo(noBackup).path).isEqualTo("vault/siwc-credentials.v1.bin")
        assertThat(paths.stateDir.relativeTo(noBackup).path).isEqualTo("state")
        assertThat(paths.quarantineDir.relativeTo(noBackup).path).isEqualTo("quarantine")
        assertThat(paths.databaseFiles.map { it.name })
            .containsExactly("agentle.db", "agentle.db-wal", "agentle.db-shm", "agentle.db-journal")
            .inOrder()
    }

    @Test
    fun `the JCE fake issues one stable key until it is quarantined`() {
        val fake = JceDatabaseKeyManager()
        val first = fake.obtainKey() as DatabaseKeyResult.Available
        val second = fake.obtainKey() as DatabaseKeyResult.Available

        assertThat(first.created).isTrue()
        assertThat(second.created).isFalse()
        assertThat(second.key.copyBytes()).isEqualTo(first.key.copyBytes())
        assertThat(fake.hasWrappedKey()).isTrue()

        assertThat(fake.quarantineWrappedKey("reset")).isTrue()
        assertThat(fake.hasWrappedKey()).isFalse()
        assertThat((fake.obtainKey() as DatabaseKeyResult.Available).created).isTrue()
    }

    @Test
    fun `injected faults are reported like the production manager reports them, then calls reach the delegate`() {
        val decorator = FaultInjectingDatabaseKeyManager(JceDatabaseKeyManager())
        val expected = mapOf(
            DatabaseKeyFault.TRANSIENT_KEYSTORE to (UnlockFailureReason.KEYSTORE_ERROR to FailureKind.TRANSIENT),
            DatabaseKeyFault.BAD_TAG to (UnlockFailureReason.WRAPPED_KEY_REJECTED to FailureKind.PERMANENT),
            DatabaseKeyFault.KEY_FILE_MISSING to (UnlockFailureReason.KEY_FILE_MISSING to FailureKind.TRANSIENT),
            DatabaseKeyFault.KEY_FILE_TRUNCATED to (UnlockFailureReason.WRAPPED_KEY_MALFORMED to FailureKind.PERMANENT),
            DatabaseKeyFault.ALIAS_MISSING to (UnlockFailureReason.ALIAS_MISSING to FailureKind.PERMANENT),
        )
        expected.forEach { (fault, outcome) ->
            decorator.inject(fault)
            val result = decorator.obtainKey() as DatabaseKeyResult.Unavailable
            assertThat(result.reason).isEqualTo(outcome.first)
            assertThat(result.kind).isEqualTo(outcome.second)
            assertThat(result.errorClass).isEqualTo(fault.errorClass)
        }
        val real = decorator.obtainKey() as DatabaseKeyResult.Available
        assertThat(decorator.calls).isEqualTo(expected.size + 1)

        decorator.inject(DatabaseKeyFault.NOT_A_DATABASE)
        val wrong = decorator.obtainKey() as DatabaseKeyResult.Available
        assertThat(decorator.servedWrongKey).isTrue()
        assertThat(wrong.key.copyBytes()).isNotEqualTo(real.key.copyBytes())
    }

    @Test
    fun `a fault can be repeated and cleared`() {
        val decorator = FaultInjectingDatabaseKeyManager(JceDatabaseKeyManager())
        decorator.inject(DatabaseKeyFault.TRANSIENT_KEYSTORE, times = 2)
        assertThat(decorator.obtainKey()).isInstanceOf(DatabaseKeyResult.Unavailable::class.java)
        decorator.clearFaults()
        assertThat(decorator.obtainKey()).isInstanceOf(DatabaseKeyResult.Available::class.java)
    }
}
