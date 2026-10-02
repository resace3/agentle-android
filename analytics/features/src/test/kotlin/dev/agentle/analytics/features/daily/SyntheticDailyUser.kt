package dev.agentle.analytics.features.daily

import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.TransitionKind
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A deterministic synthetic user for the daily features (own fixture: the shared `dev.agentle.fakes.synth` generator is
 * not on main yet). Ninety days from 2026-09-01 in New York, with a trip to Berlin (days 40-45) and the fall-back night
 * of 2026-11-01. Every event carries the instant it becomes available, so a test can ingest the data as it would arrive:
 * phone data the next morning, wearable data the next morning with sleep first unprocessed and corrected a day later,
 * civil-date values (resting heart rate, daily step totals) the next morning. Collector coverage has random gaps and one
 * day without usage access. Randomness is a seeded [Random].
 */
internal class SyntheticDailyUser(seed: Int = 42, val first: LocalDate = date("2026-09-01"), val days: Int = 90) {
    private val random = Random(seed)

    val tripStart: Instant = at(first.plusDays(40), 18, zone = NEW_YORK)
    val tripEnd: Instant = at(first.plusDays(45), 12, zone = BERLIN)
    val timeline: ZoneTimeline = ZoneTimeline(NEW_YORK, listOf(ZoneChange(tripStart, BERLIN), ZoneChange(tripEnd, NEW_YORK)))

    /** One piece of data and when it becomes available. */
    data class Arrival(val at: Instant, val event: PersonalEvent? = null, val coverage: Pair<String, ClosedOpenRange>? = null)

    val arrivals: List<Arrival> = (0 until days).flatMap { generateDay(it) }.sortedBy { it.at }

    /** When the ingestion of day [index] happens: 09:00 local on the next day. */
    fun ingestionTime(index: Int): Instant {
        val next = first.plusDays(index + 1)
        return at(next, 9, zone = timeline.zoneAt(next.atStartOfDayIn(TimeZone.UTC) + 12.hours))
    }

    private fun generateDay(i: Int): List<Arrival> {
        val d = first.plusDays(i)
        val zone = timeline.zoneAt(d.atStartOfDayIn(TimeZone.UTC) + 12.hours)
        val midnight = d.atStartOfDayIn(zone)
        fun local(minute: Int): Instant = midnight + minute.minutes
        val available = ingestionTime(i)
        val later = ingestionTime(i + 1)
        val out = ArrayList<Arrival>()
        fun add(event: PersonalEvent, at: Instant = available) {
            out += Arrival(at, event)
        }

        // Screen, apps and unlocks.
        var cursor = 420 + random.nextInt(60)
        repeat(8 + random.nextInt(8)) {
            val start = cursor + random.nextInt(5, 90)
            val length = random.nextInt(2, 40)
            if (start + length < 1_500) {
                add(Ev.screen(local(start), local(start + length), zone))
                add(Ev.unlock(local(start), zone))
                val pkg = PACKAGES[random.nextInt(PACKAGES.size)]
                add(Ev.app(pkg.first, local(start) + 20.minutes / 60, local(start + length) - 20.minutes / 60, pkg.second, zone))
                cursor = start + length
            }
        }
        // Notifications (some ongoing, some summaries, some re-posted keys, some from Agentle itself).
        var lastKey = "k$i-0"
        repeat(10 + random.nextInt(30)) { j ->
            val pkg = NOTIFYING[random.nextInt(NOTIFYING.size)]
            val key = if (random.nextInt(10) == 0) lastKey else "k$i-$j"
            lastKey = key
            add(
                Ev.notification(
                    pkg,
                    local(random.nextInt(1_440)),
                    key,
                    ongoing = random.nextInt(10) == 0,
                    groupSummary = random.nextInt(20) == 0,
                    key = "notif|$i|$j",
                ),
            )
        }
        // Charging overnight.
        add(Ev.charging(true, local(1_350 + random.nextInt(90)), zone))
        add(Ev.charging(false, local(1_440 + 390 + random.nextInt(60)), zone))
        // Two walks; the watch is off the wrist on some days.
        val worn = random.nextInt(10) != 0
        var watchSteps = 0L
        for (walkStart in listOf(480 + random.nextInt(60), 1_050 + random.nextInt(60))) {
            val length = random.nextInt(20, 41)
            add(Ev.activity(ActivityKind.WALKING, TransitionKind.ENTER, local(walkStart)))
            add(Ev.activity(ActivityKind.WALKING, TransitionKind.EXIT, local(walkStart + length)))
            add(Ev.activity(ActivityKind.STILL, TransitionKind.ENTER, local(walkStart + length)))
            for (m in 0 until length) {
                val count = random.nextLong(80, 131)
                if (worn) {
                    add(Ev.steps(Src.GH_STEPS, local(walkStart + m), local(walkStart + m + 1), count, zone))
                    watchSteps += count
                }
                add(Ev.steps(Src.PHONE_STEPS, local(walkStart + m), local(walkStart + m + 1), count * 95 / 100, zone))
            }
        }
        repeat(20) {
            val m = random.nextInt(420, 1_380)
            add(Ev.steps(Src.PHONE_STEPS, local(m), local(m + 1), random.nextLong(5, 40), zone))
        }
        if (worn) {
            for (half in 0 until 48) add(Ev.heartRate(Src.GH_HR, local(half * 30 + 15), random.nextInt(55, 96).toDouble()))
            if (random.nextInt(5) == 0) add(Ev.heartRate(Src.GH_HR, local(600), 300.0))
            add(Ev.restingHr(Src.GH_RHR, d, random.nextInt(52, 66).toDouble(), zone))
            if (i % 3 == 0) add(Ev.dailySteps(Src.GH_DAILY, d, (watchSteps + 500).toDouble(), zone))
            // Sleep of night i: unprocessed first, corrected (same dedup key) a day later.
            val bed = local(1_380 + random.nextInt(-30, 60))
            val wake = local(1_440 + 390 + random.nextInt(0, 60))
            add(sleep(bed, wake, zone, processed = false, key = "sleep|$i"))
            add(sleep(bed, wake, zone, processed = true, key = "sleep|$i"), later)
            if (random.nextInt(10) < 3) {
                val start = 1_080 + random.nextInt(60)
                add(Ev.exercise(Src.GH_EXERCISE, local(start), local(start + random.nextInt(30, 51))))
            }
        }
        // Interventions.
        repeat(random.nextInt(3)) { j ->
            val at = local(1_200 + random.nextInt(120))
            add(Ev.jitai(EventType.JITAI_DELIVERED, at, "j$j"))
            if (random.nextBoolean()) add(Ev.jitai(EventType.JITAI_OPENED, at + 5.minutes, "j$j"))
        }
        // Collector coverage of the local day, with gaps.
        for (collector in Col.ALL) {
            if (collector == Col.USAGE && i == 30) continue
            val day = ClosedOpenRange(midnight, local(1_440))
            val parts = if (random.nextInt(10) == 0) {
                val gapStart = random.nextInt(0, 1_260)
                MinuteFusion.subtract(listOf(day), listOf(ClosedOpenRange(local(gapStart), local(gapStart + 180))))
            } else {
                listOf(day)
            }
            parts.forEach { out += Arrival(available, coverage = collector to it) }
        }
        return out
    }

    private fun sleep(bed: Instant, wake: Instant, zone: TimeZone, processed: Boolean, key: String): PersonalEvent {
        val asleepStart = bed + 15.minutes
        val stages = listOf(
            SleepStage(SleepStageKind.AWAKE, bed.toEpochMilliseconds(), asleepStart.toEpochMilliseconds()),
            SleepStage(SleepStageKind.LIGHT, asleepStart.toEpochMilliseconds(), (asleepStart + 3.hours).toEpochMilliseconds()),
            SleepStage(SleepStageKind.DEEP, (asleepStart + 3.hours).toEpochMilliseconds(), (asleepStart + 5.hours).toEpochMilliseconds()),
            SleepStage(SleepStageKind.REM, (asleepStart + 5.hours).toEpochMilliseconds(), wake.toEpochMilliseconds()),
        )
        val payload = SleepSessionPayload(
            stages = stages,
            minutesAsleep = if (processed) (wake - asleepStart).inWholeMinutes - 5 else null,
            processed = processed,
            startUtcOffsetSeconds = zone.offsetAt(bed).totalSeconds,
            endUtcOffsetSeconds = zone.offsetAt(wake).totalSeconds,
        )
        return Ev.of(EventType.SLEEP_SESSION, Src.GH_SLEEP, bed, wake, payload, zone, key)
    }

    /** Sources that publish `coverageThrough`, by metric family. */
    val claimingSources: Map<MetricFamily, DataSourceId> = mapOf(
        MetricFamily.STEPS to Src.GH_STEPS,
        MetricFamily.HEART_RATE to Src.GH_HR,
        MetricFamily.RESTING_HEART_RATE to Src.GH_RHR,
        MetricFamily.SLEEP to Src.GH_SLEEP,
        MetricFamily.EXERCISE to Src.GH_EXERCISE,
    )

    private companion object {
        val PACKAGES = listOf("com.social.a" to "social", "com.video.v" to "video", "com.mail.m" to "productivity", "com.game.g" to null)
        val NOTIFYING = listOf("com.chat", "com.mail.m", "com.social.a", "dev.agentle.app", "com.news")
    }
}
