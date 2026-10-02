package dev.agentle.core.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden corpora of the persisted store documents (round 4 correction 4, testing-build-09): files written by version 1
 * must keep decoding with the current code. A change that breaks one of these needs a migration, not a new golden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreGoldenTest {
    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("golden/$name")) { "missing golden $name" }.readText()

    @Test
    fun `settings v1 decodes with the current code`() {
        val settings = StoreJson.decodeFromString(AppSettings.serializer(), golden("settings.v1.json")).sanitized()

        assertThat(settings.retention).isEqualTo(
            RetentionSettings(RetentionPeriod.DAYS_90, RetentionPeriod.DAYS_30, RetentionPeriod.FOREVER, RetentionPeriod.YEAR_1),
        )
        assertThat(settings.quietHours).isEqualTo(QuietHours("21:30", "06:45", enabled = true))
        assertThat(settings.jitai.maxPerDay).isEqualTo(4)
        assertThat(settings.jitai.maxPerWeek).isEqualTo(20)
        assertThat(settings.jitai.minGapMinutes).isEqualTo(45)
        assertThat(settings.jitai.channelCaps).containsExactly("VOICE", 1, "VIDEO", 0, "IMAGE", 2)
        assertThat(settings.jitai.pauseUntilMs).isEqualTo(1_790_000_000_000L)
        assertThat(settings.jitai.maxEventAgeMinutes).containsExactly("SCREEN_ON", 5)
        assertThat(settings.jitai.weekendDays).containsExactly(5, 6)
        assertThat(settings.collectionProfile).isEqualTo(CollectionProfile.LOW)
        assertThat(settings.onboarding).isEqualTo(OnboardingState(2, 1_789_000_000_000L))
        assertThat(settings.permissions.settingsVisited).containsExactly("usage_access", "notification_listener")
        assertThat(settings.collection.disabledConnectors).containsExactly("healthconnect")
        assertThat(settings.collection.preciseLocation).isTrue()
        assertThat(settings.notices.isAccepted("privacy", 3)).isTrue()
        assertThat(settings.notices.isAccepted("privacy", 4)).isFalse()
        assertThat(settings.debug.fakeScenario).isEqualTo("sleepy-week")
    }

    @Test
    fun `ai consent v1 decodes with the current code`() {
        val record = StoreJson.decodeFromString(AiConsentRecord.serializer(), golden("ai-consent.v1.json"))

        assertThat(record.revision).isEqualTo(12)
        assertThat(record.installId).isEqualTo("0123456789abcdef0123456789abcdef")
        assertThat(record.grants).containsExactly(
            ConsentGrant("SLEEP", "SLEEP_INSIGHT", 1, 1_789_000_000_000L, "account-sub-1"),
            ConsentGrant("STEPS", "ACTIVITY_INSIGHT", 1, 1_789_000_100_000L, null),
        )
    }

    @Test
    fun `the current encoder's output decodes to the same documents`() {
        val settings = StoreJson.decodeFromString(AppSettings.serializer(), golden("settings.v1.json"))
        val consent = StoreJson.decodeFromString(AiConsentRecord.serializer(), golden("ai-consent.v1.json"))

        assertThat(StoreJson.decodeFromString(AppSettings.serializer(), StoreJson.encodeToString(AppSettings.serializer(), settings)))
            .isEqualTo(settings)
        assertThat(
            StoreJson.decodeFromString(AiConsentRecord.serializer(), StoreJson.encodeToString(AiConsentRecord.serializer(), consent)),
        )
            .isEqualTo(consent)
    }
}
