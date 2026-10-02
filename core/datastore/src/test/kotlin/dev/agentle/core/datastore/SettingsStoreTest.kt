package dev.agentle.core.datastore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val diagnostics = RecordingDiagnostics()

    private fun TestScope.store(file: File, debug: Boolean = false, scope: CoroutineScope = backgroundScope) =
        DataStoreSettingsStore.create({ file }, scope, debug, diagnostics)

    @Test
    fun `defaults are the privacy-preserving choices of the brief`() = runTest {
        val settings = store(File(temp.root, "settings.json")).current()

        assertThat(settings.retention.wearable).isEqualTo(RetentionPeriod.FOREVER)
        assertThat(settings.quietHours).isEqualTo(QuietHours("22:00", "07:00", enabled = true))
        assertThat(settings.jitai.maxPerDay).isEqualTo(6)
        assertThat(settings.jitai.minGapMinutes).isEqualTo(30)
        assertThat(settings.collectionProfile).isEqualTo(CollectionProfile.BALANCED)
        assertThat(settings.onboarding.completedVersion).isEqualTo(0)
        assertThat(settings.permissions.requested).isEmpty()
        assertThat(settings.collection.notificationContentPackages).isEmpty()
        assertThat(settings.collection.preciseLocation).isFalse()
        assertThat(settings.notices.isAccepted("privacy", 1)).isFalse()
    }

    @Test
    fun `updates are atomic, persisted and survive a new store over the same file`() = runTest {
        val file = File(temp.root, "settings.json")
        val job = Job()
        val first = store(file, scope = CoroutineScope(backgroundScope.coroutineContext + job))
        assertThat(
            first.update {
                it.copy(collectionProfile = CollectionProfile.HIGH, retention = RetentionSettings(android = RetentionPeriod.DAYS_90))
            },
        )
            .isTrue()
        job.cancelAndJoin()

        val second = store(file).current()
        assertThat(second.collectionProfile).isEqualTo(CollectionProfile.HIGH)
        assertThat(second.retention.android).isEqualTo(RetentionPeriod.DAYS_90)
    }

    @Test
    fun `a corrupted file resets to the defaults and reports the error class only`() = runTest {
        val file = File(temp.root, "settings.json").apply { writeText("""{"collectionProfile": "HIGH", "secret note""") }

        val settings = store(file).current()

        assertThat(settings).isEqualTo(AppSettings())
        assertThat(diagnostics.resets).hasSize(1)
        val (name, errorClass) = diagnostics.resets.single()
        assertThat(name).isEqualTo("settings")
        assertThat(errorClass).doesNotContain("secret")
        assertThat(errorClass).matches("[A-Za-z]+")
    }

    @Test
    fun `values outside the hard limits are clamped when read`() = runTest {
        val file = File(temp.root, "settings.json").apply {
            writeText(
                """{"jitai": {"maxPerDay": 99, "maxPerWeek": -3, "minGapMinutes": 1, "channelCaps": {"VOICE": 50},
                   "rolloverMinute": 9999, "weekendDays": [0, 6, 7, 8]}, "quietHours": {"start": "25:00", "end": "07:00"}}""",
            )
        }

        val settings = store(file).current()

        assertThat(settings.jitai.maxPerDay).isEqualTo(JitaiSettings.HARD_MAX_PER_DAY)
        assertThat(settings.jitai.maxPerWeek).isEqualTo(0)
        assertThat(settings.jitai.minGapMinutes).isEqualTo(JitaiSettings.HARD_MIN_GAP_MINUTES)
        assertThat(settings.jitai.channelCaps).containsExactly("VOICE", JitaiSettings.HARD_MAX_PER_DAY)
        assertThat(settings.jitai.rolloverMinute).isEqualTo(JitaiSettings.MAX_ROLLOVER_MINUTE)
        assertThat(settings.jitai.weekendDays).containsExactly(6, 7)
        assertThat(settings.quietHours).isEqualTo(QuietHours())
    }

    @Test
    fun `an unknown enum value falls back to that field's default and keeps the rest`() = runTest {
        val file = File(temp.root, "settings.json").apply {
            writeText("""{"collectionProfile": "TURBO", "retention": {"wearable": "DAYS_30"}, "futureField": {"x": 1}}""")
        }

        val settings = store(file).current()

        assertThat(settings.collectionProfile).isEqualTo(CollectionProfile.BALANCED)
        assertThat(settings.retention.wearable).isEqualTo(RetentionPeriod.DAYS_30)
        assertThat(diagnostics.resets).isEmpty()
    }

    @Test
    fun `debug options are hidden and kept unchanged outside the fake flavor`() = runTest {
        val file = File(temp.root, "settings.json").apply { writeText("""{"debug": {"fakeScenario": "sleepy-week"}}""") }
        val store = store(file, debug = false)

        assertThat(store.current().debug).isEqualTo(DebugOptions())
        store.update { it.copy(debug = DebugOptions(fastSchedules = true), collectionProfile = CollectionProfile.LOW) }

        assertThat(file.readText()).contains("sleepy-week")
        assertThat(file.readText()).doesNotContain("\"fastSchedules\":true")
        assertThat(store.current().collectionProfile).isEqualTo(CollectionProfile.LOW)
    }

    @Test
    fun `debug options are readable and writable in the fake flavor`() = runTest {
        val store = store(File(temp.root, "settings.json"), debug = true)

        store.update { it.copy(debug = DebugOptions(fakeScenario = "sleepy-week")) }

        assertThat(store.current().debug.fakeScenario).isEqualTo("sleepy-week")
    }

    @Test
    fun `an IOException on read yields the defaults and a diagnostic, and a failed write reports false`() = runTest {
        val faulty = FaultyDataStore(
            jsonDataStore("settings", {
                File(temp.root, "settings.json")
            }, AppSettings.serializer(), AppSettings(), backgroundScope, diagnostics) {
                AppSettings()
            },
        )
        val store = DataStoreSettingsStore.over(faulty, debugOptionsAllowed = false, diagnostics = diagnostics)
        store.update { it.copy(collectionProfile = CollectionProfile.HIGH) }

        faulty.failReads = true
        assertThat(store.current()).isEqualTo(AppSettings())
        assertThat(diagnostics.ioFailures).contains(Triple("settings", "read", "IOException"))

        faulty.failReads = false
        faulty.failWrites = true
        assertThat(store.update { it.copy(collectionProfile = CollectionProfile.LOW) }).isFalse()
        assertThat(store.current().collectionProfile).isEqualTo(CollectionProfile.HIGH)
    }

    @Test
    fun `permission flags and collection choices round-trip`() = runTest {
        val settings = store(File(temp.root, "settings.json"))
        val flags = PermissionFlagStore(settings)
        val choices = CollectionChoiceStore(settings)

        flags.markRequested(listOf("android.permission.ACTIVITY_RECOGNITION"))
        flags.markRequested(listOf("android.permission.POST_NOTIFICATIONS"))
        flags.markSettingsVisited("usage_access")
        choices.update { it.copy(disabledConnectors = setOf("healthconnect"), notificationContentPackages = setOf("com.example.chat")) }

        assertThat(flags.requestedPermissions())
            .containsExactly("android.permission.ACTIVITY_RECOGNITION", "android.permission.POST_NOTIFICATIONS")
        assertThat(flags.settingsVisited()).containsExactly("usage_access")
        assertThat(settings.current().collection.disabledConnectors).containsExactly("healthconnect")
        assertThat(settings.current().collection.notificationContentFromSmsAndDialer).isFalse()
    }
}
