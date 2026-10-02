package dev.agentle.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.Secret
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStoreException

/**
 * Token vault (round 2 correction 7; R04 §4 SEC-TOK-01 and the Robolectric part of SEC-BAK-03): round trip, AAD
 * binding, permanent failures wipe and ask for sign-in, transient failures change nothing, no keyset in shared prefs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SecretVaultTest {
    @get:Rule val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var kek: InMemoryKekProvider
    private lateinit var paths: SecurityPaths
    private lateinit var vault: TinkSecretVault

    private val tokens = Secret("""{"access_token":"at_CANARY_1","refresh_token":"rt_CANARY_2"}""")

    @Before
    fun setUp() {
        kek = InMemoryKekProvider()
        paths = SecurityPaths(temp.newFolder("nobackup"), File(temp.newFolder("databases"), SecurityPaths.DATABASE_NAME))
        vault = TinkSecretVault(kek, paths, "dev.agentle.app")
    }

    @Test
    fun `construction touches neither the Keystore nor the disk`() {
        TinkSecretVault(kek, paths, "dev.agentle.app")
        assertThat(kek.hasKey(KekAlias.VAULT)).isFalse()
        assertThat(paths.vaultDir.exists()).isFalse()
    }

    @Test
    fun `round trip keeps the record version and never stores plaintext`() = runBlocking {
        assertThat(vault.read(VaultEntry.SIWC_CREDENTIALS)).isEqualTo(VaultRead.Absent)
        assertThat(vault.write(VaultEntry.SIWC_CREDENTIALS, tokens, recordVersion = 3)).isEqualTo(VaultWrite.Written)

        val read = vault.read(VaultEntry.SIWC_CREDENTIALS) as VaultRead.Present
        assertThat(read.secret.value).isEqualTo(tokens.value)
        assertThat(read.recordVersion).isEqualTo(3)
        val blob = paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).readBytes()
        assertThat(String(blob, Charsets.ISO_8859_1)).doesNotContain("CANARY")
    }

    @Test
    fun `a blob moved to another record is rejected, wiped and asks for sign-in`() = runBlocking {
        vault.write(VaultEntry.SIWC_CREDENTIALS, tokens)
        val blob = paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).readBytes()
        paths.vaultFile(VaultEntry.SIWC_PENDING_ROTATION).writeBytes(blob)

        val read = vault.read(VaultEntry.SIWC_PENDING_ROTATION)
        assertThat(read).isEqualTo(VaultRead.Unreadable(VaultFailureReason.BLOB_REJECTED))
        assertThat(paths.vaultFile(VaultEntry.SIWC_PENDING_ROTATION).exists()).isFalse()
        assertThat(VaultRead.Unreadable.REAUTH_CODE).isEqualTo("LOCAL_CREDENTIALS_UNREADABLE")
        // The original record is untouched.
        assertThat(vault.read(VaultEntry.SIWC_CREDENTIALS)).isInstanceOf(VaultRead.Present::class.java)
    }

    @Test
    fun `another package or record version does not open the blob`() = runBlocking {
        vault.write(VaultEntry.SIWC_CREDENTIALS, tokens, recordVersion = 1)
        val otherPackage = TinkSecretVault(kek, paths, "com.example.other")
        assertThat(otherPackage.read(VaultEntry.SIWC_CREDENTIALS)).isEqualTo(VaultRead.Unreadable(VaultFailureReason.BLOB_REJECTED))

        vault.write(VaultEntry.SIWC_CREDENTIALS, tokens, recordVersion = 1)
        val file = paths.vaultFile(VaultEntry.SIWC_CREDENTIALS)
        val bytes = file.readBytes()
        bytes[4] = 2 // record version 1 -> 2 in the plaintext header
        file.writeBytes(bytes)
        assertThat(vault.read(VaultEntry.SIWC_CREDENTIALS)).isEqualTo(VaultRead.Unreadable(VaultFailureReason.BLOB_REJECTED))
    }

    @Test
    fun `every flipped byte is rejected without a crash`() = runBlocking {
        vault.write(VaultEntry.SIWC_CREDENTIALS, tokens)
        val original = paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).readBytes()
        for (index in original.indices) {
            val bytes = original.copyOf()
            bytes[index] = (bytes[index].toInt() xor 0x40).toByte()
            paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).writeBytes(bytes)
            val read = vault.read(VaultEntry.SIWC_CREDENTIALS)
            assertThat(read).isInstanceOf(VaultRead.Unreadable::class.java)
            assertThat(paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).exists()).isFalse()
        }
    }

    @Test
    fun `a deleted alias gives sign-in required, wipes the blob, and a new write works`() = runBlocking {
        vault.write(VaultEntry.SIWC_CREDENTIALS, tokens)
        vault.write(VaultEntry.SIWC_CLIENT_REGISTRATION, Secret("client"))
        kek.deleteKey(KekAlias.VAULT)

        assertThat(vault.read(VaultEntry.SIWC_CREDENTIALS)).isEqualTo(VaultRead.Unreadable(VaultFailureReason.ALIAS_MISSING))
        assertThat(paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).exists()).isFalse()

        // A write first removes ciphertext the missing key sealed, then creates a new key.
        assertThat(vault.write(VaultEntry.SIWC_CREDENTIALS, tokens)).isEqualTo(VaultWrite.Written)
        assertThat(paths.vaultFile(VaultEntry.SIWC_CLIENT_REGISTRATION).exists()).isFalse()
        assertThat((vault.read(VaultEntry.SIWC_CREDENTIALS) as VaultRead.Present).secret.value).isEqualTo(tokens.value)
    }

    @Test
    fun `a transient Keystore failure keeps the blob for a later retry`() = runBlocking {
        vault.write(VaultEntry.SIWC_CREDENTIALS, tokens)
        kek.failNext(1) { KeyStoreException("busy") }
        val read = vault.read(VaultEntry.SIWC_CREDENTIALS)
        assertThat(read).isEqualTo(VaultRead.Unavailable(VaultFailureReason.KEYSTORE_ERROR, "KeyStoreException"))
        assertThat(paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).exists()).isTrue()
        assertThat(vault.read(VaultEntry.SIWC_CREDENTIALS)).isInstanceOf(VaultRead.Present::class.java)
    }

    @Test
    fun `wipe removes every record and the key`() = runBlocking {
        VaultEntry.entries.forEach { vault.write(it, Secret(it.id)) }
        assertThat(vault.wipe()).isTrue()
        VaultEntry.entries.forEach { assertThat(paths.vaultFile(it).exists()).isFalse() }
        assertThat(kek.hasKey(KekAlias.VAULT)).isFalse()
    }

    @Test
    fun `no keyset material is written to shared preferences`() = runBlocking {
        vault.write(VaultEntry.SIWC_CREDENTIALS, tokens)
        vault.read(VaultEntry.SIWC_CREDENTIALS)
        val sharedPrefs = File(context.applicationInfo.dataDir, "shared_prefs")
        val files = sharedPrefs.listFiles().orEmpty()
        assertThat(files.filter { it.readText().contains("keyset", ignoreCase = true) }).isEmpty()
    }
}
