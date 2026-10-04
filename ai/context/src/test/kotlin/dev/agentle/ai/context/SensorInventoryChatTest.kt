package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.connectors.api.sensors.DeviceSensor
import dev.agentle.connectors.api.sensors.SensorCatalog
import dev.agentle.connectors.api.sensors.SensorReportingMode
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.fakes.ai.FakeAiProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** "Tell me about the sensors on this phone" from the chat tab, through the real engine and guard, to the provider. */
class SensorInventoryChatTest {
    private val clock = TestAgentleClock(START, KATHMANDU)

    private fun sensor(type: Int, name: String, stringType: String? = null) = DeviceSensor(
        id = DeviceSensor.baseId(type, name, "Bosch", wakeUp = false),
        kind = SensorCatalog.kind(type, stringType),
        name = name,
        vendor = "Bosch",
        mode = SensorReportingMode.CONTINUOUS,
        wakeUp = false,
        maxRange = 78.4532f,
    )

    private val sensors = listOf(
        sensor(1, "BMI160 Accelerometer"),
        sensor(1, "BMI160 Accelerometer Wakeup"),
        sensor(5, "LTR578 Light"),
        sensor(19, "Step Counter"),
        sensor(65_601, "MTK Pocket", "com.mediatek.pocket"),
    )

    private val usage = object : PhoneUsageLoader {
        override suspend fun screenSessions(range: ClosedOpenRange) = listOf(ScreenSession(START - 2.hours, 40 * 60_000L))

        override suspend fun unlocks(range: ClosedOpenRange): List<Instant> = listOf(START - 1.hours)

        override suspend fun appSessions(range: ClosedOpenRange) = listOf(AppSession("YouTube", 50 * 60_000L))
    }

    private val inventory = object : SensorInventoryLoader {
        override suspend fun sensors() = sensors

        override suspend fun recordedIds() = setOf(sensors[3].id)
    }

    private var fake: FakeAiProvider? = null

    private val context = AiContext(
        dataSource = CompositeAiContextDataSource(
            listOf(PhoneUsageDataSource(usage) { clock.now() }, SensorInventoryDataSource(inventory)),
        ),
        consentStore = InMemoryConsentStore(),
        account = FakeAccount(),
        auditLog = InMemoryAuditLog(),
        clock = clock,
        providerFactory = { verifier -> FakeAiProvider(verifier).also { fake = it } },
        options = AiContextOptions(accountSalt = "install-salt", random = Random(7)),
    )

    private suspend fun ask(): String {
        val envelope = context.engine.build(AiPurpose.GENERAL_QUESTION, QUESTION).getOrThrow()
        context.guard.analyze(envelope).getOrThrow()
        return fake!!.journal.last().sentDataInput
    }

    @Test
    fun `after consent the question sends the sensor count, each type and the recorded ones`() = runTest {
        context.consent.grant(SHARED, AiPurpose.GENERAL_QUESTION).getOrThrow()
        fake!!.respondWith(AiPurpose.GENERAL_QUESTION, null, "This phone has 5 sensors.")

        val sent = ask()

        assertThat(fake!!.journal.single().categories).contains(AiDataCategory.DEVICE_STATE)
        listOf("sensors.count", "sensors.type_count", "sensors.recorded_count", "sensors.recorded_type").forEach {
            assertThat(sent).contains(it)
        }
        listOf("ACCELEROMETER", "LIGHT", "STEP_COUNTER", "VENDOR").forEach { assertThat(sent).contains(it) }
        assertThat(sent).contains("sensors.motion")
        assertThat(sent).contains("sensors.environment")
        assertThat(sent).contains("sensors.other")
        assertThat(sent).contains("screen.minutes_daily_avg")
    }

    @Test
    fun `no readings, ranges, vendors or vendor-written names are sent`() = runTest {
        context.consent.grant(SHARED, AiPurpose.GENERAL_QUESTION).getOrThrow()
        fake!!.respondWith(AiPurpose.GENERAL_QUESTION, null, "This phone has 5 sensors.")

        val sent = ask()

        listOf("BMI160", "LTR578", "Bosch", "MTK", "mediatek", "Pocket", "78.45", "Wakeup").forEach { assertThat(sent).doesNotContain(it) }
    }

    @Test
    fun `with only phone usage granted the sensor inventory is not sent`() = runTest {
        context.consent.grant(PHONE_USAGE, AiPurpose.GENERAL_QUESTION).getOrThrow()
        fake!!.respondWith(AiPurpose.GENERAL_QUESTION, null, "You used your phone for 40 minutes.")

        val sent = ask()

        assertThat(sent).contains("screen.minutes_daily_avg")
        assertThat(sent).doesNotContain("sensors.")
        assertThat(sent).doesNotContain("ACCELEROMETER")
        assertThat(fake!!.journal.single().categories).doesNotContain(AiDataCategory.DEVICE_STATE)
    }

    @Test
    fun `the data source returns nothing when the query lacks DEVICE_STATE or is not a chat question`() = runTest {
        val source = SensorInventoryDataSource(inventory)
        val base = AiDataQuery(
            purpose = AiPurpose.GENERAL_QUESTION,
            mode = AiRequestMode.USER_INITIATED,
            categories = PHONE_USAGE,
            sourceFamilies = setOf(SourceFamily.ON_DEVICE),
            kinds = ItemKind.entries.toSet(),
            range = null,
            zone = KATHMANDU,
            fields = null,
            subject = null,
        )

        assertThat(source.aggregates(base).getOrThrow()).isEmpty()
        assertThat(source.aggregates(base.copy(purpose = AiPurpose.SCREEN_TIME_INSIGHT, categories = SHARED)).getOrThrow()).isEmpty()
        assertThat(source.aggregates(base.copy(categories = SHARED)).getOrThrow()).isNotEmpty()
    }

    private companion object {
        const val QUESTION = "tell me about the sensors on this phone like how many are there and what's being recorded"
        val PHONE_USAGE = setOf(AiDataCategory.SCREEN_TIME_TOTALS, AiDataCategory.APP_IDENTITY)
        val SHARED = PHONE_USAGE + AiDataCategory.DEVICE_STATE
    }
}
