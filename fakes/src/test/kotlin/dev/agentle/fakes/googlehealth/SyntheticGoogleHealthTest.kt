package dev.agentle.fakes.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.fakes.synth.SynthEventType
import dev.agentle.fakes.synth.SynthHealth
import dev.agentle.fakes.synth.SynthSpec
import dev.agentle.fakes.synth.SyntheticUser
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class SyntheticGoogleHealthTest {
    @Test
    fun `R08 6 every synthetic quantity becomes the matching Google Health data type`() {
        fun sum(type: GhDataType, platform: String = "FITBIT") =
            dataset.of(type).filter { it.source?.platform == platform }.sumOf { it.amount ?: 0.0 }
        val trackerSteps = events.filter { it.type == SynthEventType.STEPS_MINUTE }.sumOf { it.value }
        assertThat(sum(GhDataTypes.STEPS)).isEqualTo(trackerSteps)
        assertThat(sum(GhDataTypes.STEPS, "HEALTH_CONNECT")).isEqualTo(
            events.filter {
                it.type == SynthEventType.PHONE_STEPS_MINUTE
            }.sumOf { it.value },
        )
        assertThat(sum(GhDataTypes.DISTANCE)).isEqualTo(trackerSteps * SynthHealth.STRIDE_MILLIMETERS)
        assertThat(dataset.of(GhDataTypes.HEART_RATE)).hasSize(events.count { it.type == SynthEventType.HEART_RATE_SAMPLE })
        assertThat(dataset.of(GhDataTypes.SLEEP)).hasSize(events.count { it.type == SynthEventType.SLEEP_SESSION })
        assertThat(dataset.of(GhDataTypes.EXERCISE)).hasSize(events.count { it.type == SynthEventType.EXERCISE_SESSION })
        assertThat(dataset.of(GhDataTypes.RESTING_HEART_RATE)).hasSize(88)
        assertThat(dataset.of(GhDataTypes.WEIGHT)).hasSize(26)
        assertThat(dataset.of(GhDataTypes.BODY_FAT)).hasSize(26)
        // 90 days from 2026-08-10 in New York include the 25-hour day of 2026-11-01.
        assertThat(dataset.of(GhDataTypes.TOTAL_CALORIES)).hasSize(90 * 24 + 1)
        assertThat(dataset.devices.map { it.deviceVersion }).containsExactly("Charge 6", "Aria Air").inOrder()
        val sleep = dataset.of(GhDataTypes.SLEEP).first().toListJson().getValue("sleep").jsonObject
        val summary = sleep.getValue("summary").jsonObject
        val asleep = summary.getValue("minutesAsleep").jsonPrimitive.content.toLong()
        val awake = summary.getValue("minutesAwake").jsonPrimitive.content.toLong()
        assertThat(asleep + awake).isEqualTo(summary.getValue("minutesInSleepPeriod").jsonPrimitive.content.toLong())
    }

    @Test
    fun `R08 6 dailyRollUp of synthetic steps is the tracker where worn and the phone elsewhere`() {
        val clock = TestAgentleClock(start = spec.windowEnd + 1.days)
        val fake = FakeGoogleHealthServer(clock, dataset = dataset)
        val body = """{"range":{"start":{"date":{"year":2026,"month":10,"day":18}},"end":{"date":{"year":2026,"month":10,"day":24}}}}"""
        val response = fake.call(
            "POST",
            "/v4/users/me/dataTypes/steps/dataPoints:dailyRollUp",
            mapOf("Authorization" to "Bearer ${FakeTokens.VALID}"),
            body,
        )
        val served = Json.parseToJsonElement(response.body).jsonObject.getValue("rollupDataPoints").jsonArray.associate { p ->
            val date = p.jsonObject.getValue("civilStartTime").jsonObject.getValue("date").jsonObject
            val key =
                LocalDate(
                    date.getValue("year").jsonPrimitive.content.toInt(),
                    date.getValue("month").jsonPrimitive.content.toInt(),
                    date.getValue("day").jsonPrimitive.content.toInt(),
                )
            key to p.jsonObject.getValue("steps").jsonObject.getValue("countSum").jsonPrimitive.content.toLong()
        }
        val tracker = events.filter { it.type == SynthEventType.STEPS_MINUTE }.associateBy { it.start }
        val expected = HashMap<LocalDate, Long>()
        events.filter { it.type == SynthEventType.PHONE_STEPS_MINUTE }.forEach { phone ->
            val minute = tracker[phone.start] ?: phone
            val date = minute.start.toLocalDateTime(minute.zone).date
            if (date >= LocalDate(2026, 10, 18) &&
                date <= LocalDate(2026, 10, 24)
            ) {
                expected[date] = (expected[date] ?: 0L) + minute.value.toLong()
            }
        }
        assertThat(served).isEqualTo(expected)
        assertThat(served.keys).hasSize(7)
    }

    @Test
    fun `R08 6 data recorded after now or after the tracker's last sync is not served yet`() {
        val now = SynthSpec(seed = 42).windowStart + 3.days
        val clock = TestAgentleClock(start = now)
        val fake = FakeGoogleHealthServer(clock, FakeGoogleHealthConfig(deviceSyncLag = 30.minutes), dataset)
        val response = fake.call(
            "GET",
            "/v4/users/me/dataTypes/heart-rate/dataPoints?pageSize=1",
            mapOf("Authorization" to "Bearer ${FakeTokens.VALID}"),
        )
        val newest = Json.parseToJsonElement(response.body).jsonObject.getValue("dataPoints").jsonArray.single().jsonObject
        val time = kotlin.time.Instant.parse(
            newest.getValue("heartRate").jsonObject.getValue("sampleTime").jsonObject.getValue("physicalTime").jsonPrimitive.content,
        )
        assertThat(time).isAtMost(now - 30.minutes)
        assertThat(time).isGreaterThan(now - 90.minutes)
    }

    companion object {
        private val spec = SynthSpec(seed = 42)
        private val events by lazy { SyntheticUser.generate(spec) }
        private val dataset by lazy { SyntheticGoogleHealth.dataset(spec) }
    }
}
