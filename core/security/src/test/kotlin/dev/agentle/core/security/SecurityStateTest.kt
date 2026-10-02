package dev.agentle.core.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.Secret
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import kotlin.time.Duration.Companion.hours

/** Unlock-failure counting, install identity, crypto-erasure and failure classification (red team lifecycle-battery-08). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SecurityStateTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var paths: SecurityPaths
    private val clock = TestAgentleClock()
    private var boot: Int? = 7

    @Before
    fun setUp() {
        paths = SecurityPaths(temp.newFolder("nobackup"), File(temp.newFolder("databases"), SecurityPaths.DATABASE_NAME))
    }

    private fun tracker() = UnlockFailureTracker(paths, { boot }, clock)

    @Test
    fun `a permanent failure allows a reset at once`() {
        val status = tracker().recordFailure(UnlockFailureReason.WRAPPED_KEY_REJECTED)
        assertThat(status.permanent).isTrue()
        assertThat(status.resetAllowed).isTrue()
    }

    @Test
    fun `transient failures allow a reset only after two different boots`() {
        tracker().recordFailure(UnlockFailureReason.KEYSTORE_ERROR)
        val sameBoot = tracker().recordFailure(UnlockFailureReason.KEYSTORE_ERROR)
        assertThat(sameBoot.consecutiveFailures).isEqualTo(2)
        assertThat(sameBoot.resetAllowed).isFalse()

        boot = 8
        val secondBoot = tracker().recordFailure(UnlockFailureReason.KEY_FILE_MISSING)
        assertThat(secondBoot.distinctBoots).isEqualTo(2)
        assertThat(secondBoot.resetAllowed).isTrue()
    }

    @Test
    fun `without a boot counter the boot instant distinguishes boots`() {
        boot = null
        tracker().recordFailure(UnlockFailureReason.KEYSTORE_ERROR)
        clock.advanceBy(1.hours)
        assertThat(tracker().recordFailure(UnlockFailureReason.KEYSTORE_ERROR).distinctBoots).isEqualTo(1)
        // A reboot: the wall clock moves on while elapsed time restarts (here: elapsed stays, wall jumps a day).
        clock.setWallClock(clock.now() + 24.hours)
        assertThat(tracker().recordFailure(UnlockFailureReason.KEYSTORE_ERROR).distinctBoots).isEqualTo(2)
    }

    @Test
    fun `a successful open clears the counter and the state survives a new tracker`() {
        tracker().recordFailure(UnlockFailureReason.ALIAS_MISSING)
        assertThat(tracker().status().lastReason).isEqualTo(UnlockFailureReason.ALIAS_MISSING)
        tracker().clear()
        assertThat(tracker().status()).isEqualTo(UnlockFailureStatus())
        assertThat(tracker().status().resetAllowed).isFalse()
    }

    @Test
    fun `the install id is stable and pseudonyms depend on purpose and install`() {
        val ids = InstallIdProvider(paths)
        val id = ids.installId()
        assertThat(id).matches("[0-9a-f]{32}")
        assertThat(InstallIdProvider(paths).installId()).isEqualTo(id)

        val pseudonym = ids.pseudonymize("bluetooth", "AA:BB:CC:DD:EE:FF")
        assertThat(pseudonym).hasLength(64)
        assertThat(InstallIdProvider(paths).pseudonymize("bluetooth", "AA:BB:CC:DD:EE:FF")).isEqualTo(pseudonym)
        assertThat(ids.pseudonymize("account", "AA:BB:CC:DD:EE:FF")).isNotEqualTo(pseudonym)

        val otherInstall = SecurityPaths(temp.newFolder("other"), paths.databaseFile)
        assertThat(InstallIdProvider(otherInstall).installId()).isNotEqualTo(id)
        assertThat(InstallIdProvider(otherInstall).pseudonymize("bluetooth", "AA:BB:CC:DD:EE:FF")).isNotEqualTo(pseudonym)

        val seed = ids.derivedSalt("jitai-randomization")
        assertThat(seed).hasLength(16)
        assertThat(InstallIdProvider(paths).derivedSalt("jitai-randomization")).isEqualTo(seed)
        assertThat(ids.derivedSalt("other-purpose")).isNotEqualTo(seed)
        assertThat(InstallIdProvider(otherInstall).derivedSalt("jitai-randomization")).isNotEqualTo(seed)
    }

    @Test
    fun `crypto-erasure removes the wrapped key, vault blobs, quarantine and both aliases`() = runBlocking {
        val kek = InMemoryKekProvider()
        KeystoreDatabaseKeyManager(kek, paths).obtainKey()
        TinkSecretVault(kek, paths, "dev.agentle.app").write(VaultEntry.SIWC_CREDENTIALS, Secret("t"))
        File(paths.quarantineDir, "old/agentle.db").apply { parentFile?.mkdirs() }.writeText("x")

        val report = CryptoEraser(kek, paths).eraseAll()
        assertThat(report.complete).isTrue()
        assertThat(paths.databaseKeyFile.exists()).isFalse()
        assertThat(paths.vaultFile(VaultEntry.SIWC_CREDENTIALS).exists()).isFalse()
        assertThat(paths.quarantineDir.exists()).isFalse()
        assertThat(kek.hasKey(KekAlias.DATABASE)).isFalse()
        assertThat(kek.hasKey(KekAlias.VAULT)).isFalse()
    }

    @Test
    fun `failure classification follows the cause chain`() {
        assertThat(KeystoreFailures.classify(KeyPermanentlyInvalidatedException())).isEqualTo(FailureKind.PERMANENT)
        assertThat(KeystoreFailures.classify(GeneralSecurityException("wrapped", AEADBadTagException()))).isEqualTo(FailureKind.PERMANENT)
        assertThat(KeystoreFailures.classify(KeyStoreException("busy"))).isEqualTo(FailureKind.TRANSIENT)
        assertThat(KeystoreFailures.classify(IllegalStateException("odd OEM"))).isEqualTo(FailureKind.TRANSIENT)
    }
}
