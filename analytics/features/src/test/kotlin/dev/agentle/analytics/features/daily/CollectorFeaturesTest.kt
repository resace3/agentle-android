package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.PlaceClass
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TransitionKind
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** On-device collector features (usage, notifications, charging, activity, place) and calendar/history features. */
class CollectorFeaturesTest {
    private suspend fun compute(
        events: List<PersonalEvent>,
        now: Instant = LATER,
        config: DailyFeatureConfig = DailyFeatureConfig(),
        day: kotlinx.datetime.LocalDate = D,
        timeline: ZoneTimeline = ZoneTimeline.fixed(UTC),
    ): List<DailySummaryRow> {
        val coverage = Col.ALL.associateWith { listOf(around(day)) }
        return DailyFeatureCalculator(InMemoryDailyInputs(events, timeline, coverage), config).compute(day, now)
    }

    @Test
    fun `screen minutes clip sessions to each window, including one that spans the window start`() = runTest {
        val rows = compute(
            listOf(
                Ev.screen(at(D, 3, 30), at(D, 4, 20)),
                Ev.screen(at(D, 8), at(D, 9)),
                Ev.screen(at(D, 22, 30), at(D, 23, 30)),
                Ev.screen(at(D.plusDays(1), 1), at(D.plusDays(1), 1, 30)),
                Ev.screen(at(D.plusDays(1), 3, 50), at(D.plusDays(1), 4, 10)),
            ),
        )

        assertThat(rows.row("screen_minutes").value).isEqualTo(20.0 + 60 + 60 + 30 + 10)
        assertThat(rows.row("screen_minutes_late_night").value).isEqualTo(100.0)
        assertThat(rows.row("screen_minutes_22_24").value).isEqualTo(60.0)
        assertThat(rows.row("screen_minutes").lineage.categories).containsExactly(DataCategory.SCREEN)
        assertThat(rows.row("screen_minutes").lineage.sourceFamilies).containsExactly(SourceFamily.ON_DEVICE)
    }

    @Test
    fun `app and category minutes merge short gaps, intersect screen time and union a category`() = runTest {
        val config = DailyFeatureConfig(appCategoryOverrides = mapOf("com.work" to "productivity"))
        val rows = compute(
            listOf(
                Ev.screen(at(D, 8), at(D, 9)),
                Ev.screen(at(D, 22), at(D, 23, 30)),
                Ev.app("com.social.a", at(D, 22, 30), at(D, 22, 50), "social"),
                Ev.app("com.social.a", at(D, 22, 50) + 1_500.milliseconds, at(D, 22, 55), "social"),
                Ev.app("com.social.b", at(D, 22, 40), at(D, 23), "SOCIAL"),
                Ev.app("com.video", at(D, 12), at(D, 12, 30), "video"),
                Ev.app("com.work", at(D, 8, 10), at(D, 8, 40), "social"),
                Ev.app("com.none", at(D, 8, 45), at(D, 8, 50)),
            ),
            config = config,
        )

        assertThat(rows.row("app_minutes{package=com.social.a}").value).isEqualTo(25.0)
        assertThat(rows.row("app_minutes{package=com.social.b}").value).isEqualTo(20.0)
        assertThat(rows.rowOrNull("app_minutes{package=com.video}")).isNull()
        assertThat(rows.row("app_category_minutes{category=SOCIAL}").value).isEqualTo(30.0)
        assertThat(rows.row("social_minutes_22_24").value).isEqualTo(30.0)
        assertThat(rows.row("app_category_minutes{category=PRODUCTIVITY}").value).isEqualTo(30.0)
        assertThat(rows.row("app_category_minutes{category=UNDEFINED}").value).isEqualTo(5.0)
        assertThat(rows.row("app_minutes{package=com.work}").lineage.categories).containsExactly(DataCategory.APP_USAGE)
    }

    @Test
    fun `unlocks count the engine day only`() = runTest {
        val rows = compute(listOf(at(D, 3), at(D, 9), at(D, 12), at(D.plusDays(1), 3, 59)).map { Ev.unlock(it) })

        assertThat(rows.row("unlocks").value).isEqualTo(3.0)
        assertThat(rows.row("unlocks").status).isEqualTo(DailyRowStatus.FINAL)
    }

    @Test
    fun `notifications count distinct keys from other apps and skip ongoing, summaries and services`() = runTest {
        val rows = compute(
            listOf(
                Ev.notification("com.chat", at(D, 3), "k9"),
                Ev.notification("com.chat", at(D, 10), "k1"),
                Ev.notification("com.chat", at(D, 10, 5), "k1", key = "older-collector-row"),
                Ev.notification("com.chat", at(D, 22, 30), "k2"),
                Ev.notification("com.mail", at(D.plusDays(1), 1), "k3"),
                Ev.notification("com.mail", at(D, 21, 30), "k4"),
                Ev.notification("dev.agentle.app", at(D, 11), "k5"),
                Ev.notification("com.music", at(D, 12), "k6", ongoing = true),
                Ev.notification("com.chat", at(D, 12, 1), "k7", groupSummary = true),
                Ev.notification("com.nav", at(D, 12, 2), "k8", foregroundService = true),
                Ev.notification("com.other", at(D, 13), null, key = "n-13"),
            ),
        )

        assertThat(rows.row("notifications").value).isEqualTo(5.0)
        assertThat(rows.row("notifications_late_night").value).isEqualTo(2.0)
        assertThat(rows.row("notifications_21_24").value).isEqualTo(2.0)
        assertThat(rows.row("app_notifications{package=com.chat}").value).isEqualTo(2.0)
        assertThat(rows.row("app_notifications{package=com.mail}").value).isEqualTo(2.0)
        assertThat(rows.row("app_notifications{package=com.other}").value).isEqualTo(1.0)
        assertThat(rows.rowOrNull("app_notifications{package=dev.agentle.app}")).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chargingCases")
    fun `charging follows start and stop events with a lookback for the state at the window start`(
        row: String,
        events: List<PersonalEvent>,
        minutes: Double,
        starts: Double,
        lastStart: Double?,
        firstEnd: Double?,
    ) = runTest {
        val rows = compute(events)

        assertThat(rows.row("charging_minutes").value).isEqualTo(minutes)
        assertThat(rows.row("charging_starts").value).isEqualTo(starts)
        assertThat(rows.row("charging_last_start").value).isEqualTo(lastStart)
        assertThat(rows.row("charging_first_end").value).isEqualTo(firstEnd)
        if (lastStart == null) assertThat(rows.row("charging_last_start").missingReason).isEqualTo(MissingReason.NO_DATA)
    }

    @Test
    fun `activity minutes pair transitions and a new activity ends the previous one`() = runTest {
        val rows = compute(
            listOf(
                Ev.activity(ActivityKind.RUNNING, TransitionKind.ENTER, at(D.plusDays(-1), 23)),
                Ev.activity(ActivityKind.RUNNING, TransitionKind.EXIT, at(D, 4, 30)),
                Ev.activity(ActivityKind.WALKING, TransitionKind.ENTER, at(D, 9)),
                Ev.activity(ActivityKind.WALKING, TransitionKind.EXIT, at(D, 9, 30)),
                Ev.activity(ActivityKind.IN_VEHICLE, TransitionKind.EXIT, at(D, 9, 31)),
                Ev.activity(ActivityKind.ON_BICYCLE, TransitionKind.ENTER, at(D, 9, 30)),
                Ev.activity(ActivityKind.STILL, TransitionKind.ENTER, at(D, 10)),
            ),
        )

        assertThat(rows.row("activity_minutes{activity=RUNNING}").value).isEqualTo(30.0)
        assertThat(rows.row("activity_minutes{activity=WALKING}").value).isEqualTo(30.0)
        assertThat(rows.row("activity_minutes{activity=ON_BICYCLE}").value).isEqualTo(30.0)
        assertThat(rows.row("activity_minutes{activity=STILL}").value).isEqualTo(18 * 60.0)
    }

    @Test
    fun `place minutes are unavailable in v1 unless explicitly included`() = runTest {
        val visits = listOf(Ev.visit(PlaceClass.HOME, at(D, 0), at(D, 8)), Ev.visit(PlaceClass.WORK, at(D, 9), at(D, 17)))

        assertThat(compute(visits).filter { it.featureId == "place_minutes" }).isEmpty()
        val included = compute(visits, config = DailyFeatureConfig(includeUnavailable = true))
        assertThat(included.row("place_minutes{place=HOME}").value).isEqualTo(240.0)
        assertThat(included.row("place_minutes{place=WORK}").value).isEqualTo(480.0)
        assertThat(included.row("place_minutes{place=WORK}").lineage.categories).containsExactly(DataCategory.LOCATION)
    }

    @Test
    fun `weekend follows the configured weekend days`() = runTest {
        assertThat(compute(emptyList()).row("is_weekend").value).isEqualTo(0.0)
        assertThat(compute(emptyList(), day = date("2026-09-19")).row("is_weekend").value).isEqualTo(1.0)
        val friday = compute(emptyList(), day = date("2026-09-18"), config = DailyFeatureConfig(weekendDays = setOf(DayOfWeek.FRIDAY)))
        assertThat(friday.row("is_weekend").value).isEqualTo(1.0)
        assertThat(friday.row("is_weekend").lineage.isEmpty).isTrue()
    }

    @Test
    fun `intervention history counts the engine day`() = runTest {
        val rows = compute(
            listOf(
                Ev.jitai(EventType.JITAI_DELIVERED, at(D, 3, 59)),
                Ev.jitai(EventType.JITAI_DELIVERED, at(D, 10)),
                Ev.jitai(EventType.JITAI_DELIVERED, at(D.plusDays(1), 3)),
                Ev.jitai(EventType.JITAI_OPENED, at(D, 10, 5)),
            ),
        )

        assertThat(rows.row("interventions_delivered").value).isEqualTo(2.0)
        assertThat(rows.row("interventions_opened").value).isEqualTo(1.0)
        assertThat(rows.row("interventions_dismissed").value).isEqualTo(0.0)
        assertThat(rows.row("interventions_dismissed").lineage.categories).containsExactly(DataCategory.INTERVENTIONS)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dstCases")
    fun `a continuous screen session fills each engine day exactly, DST included`(row: String, day: String, minutes: Double) = runTest {
        val d = date(day)
        val session = Ev.screen(at(d.plusDays(-2), 0, zone = NEW_YORK), at(d.plusDays(3), 0, zone = NEW_YORK), NEW_YORK)

        val rows = compute(listOf(session), day = d, timeline = ZoneTimeline.fixed(NEW_YORK), now = at(d.plusDays(5), 0))

        assertThat(rows.row("screen_minutes").value).isEqualTo(minutes)
        assertThat(rows.row("screen_minutes").status).isEqualTo(DailyRowStatus.FINAL)
    }

    @Test
    fun `a local window skipped by an eastbound flight is unknown, never zero`() = runTest {
        // New York 22:00-24:00 of 09-15 is 02:00-04:00Z on 09-16, Berlin's is 20:00-22:00Z: switching at 22:00Z skips both.
        val timeline = ZoneTimeline(NEW_YORK, listOf(ZoneChange(Instant.parse("2026-09-15T22:00:00Z"), BERLIN)))
        val day = date("2026-09-15")
        val start = Instant.parse("2026-09-15T00:00:00Z")
        val inputs = InMemoryDailyInputs(
            listOf(Ev.screen(start, start + 2.days), Ev.jitai(EventType.JITAI_DELIVERED, Instant.parse("2026-09-15T21:00:00Z"))),
            timeline,
            Col.ALL.associateWith { listOf(range(start - 2.days, start + 4.days)) },
        )

        val rows = DailyFeatureCalculator(inputs).compute(day, LATER)

        assertThat(DailyWindow.EVENING_22_24.ranges(day, timeline)).isEmpty()
        assertThat(rows.row("screen_minutes_22_24").status).isEqualTo(DailyRowStatus.MISSING)
        assertThat(rows.row("screen_minutes_22_24").missingReason).isEqualTo(MissingReason.NO_DATA)
        assertThat(rows.row("social_minutes_22_24").value).isNull()
        assertThat(rows.row("screen_minutes").value).isEqualTo(18 * 60.0)
        assertThat(rows.row("interventions_delivered").value).isEqualTo(1.0)
    }

    @Test
    fun `a trip never counts a minute twice or drops one`() = runTest {
        val change = Instant.parse("2026-09-15T20:00:00Z")
        val timeline = ZoneTimeline(NEW_YORK, listOf(ZoneChange(change, BERLIN)))
        val start = Instant.parse("2026-09-12T00:00:00Z")
        val end = Instant.parse("2026-09-21T00:00:00Z")
        val inputs = InMemoryDailyInputs(listOf(Ev.screen(start, end)), timeline, mapOf(Col.USAGE to listOf(range(start, end))))
        val days = (13..18).map { date("2026-09-$it") }

        val minutes = days.map { DailyFeatureCalculator(inputs).compute(it, LATER + 10.days).row("screen_minutes").value!! }

        assertThat(minutes).containsExactly(1440.0, 1440.0, 1080.0, 1440.0, 1440.0, 1440.0).inOrder()
        val first = DailyWindow.ENGINE_DAY.ranges(days.first(), timeline).first().start
        val last = DailyWindow.ENGINE_DAY.ranges(days.last(), timeline).last().end
        assertThat(minutes.sum()).isEqualTo((last - first).inWholeMinutes.toDouble())
    }

    companion object {
        private val D = date("2026-09-14")
        private val LATER = at(D.plusDays(4), 12)

        @JvmStatic
        fun chargingCases(): List<Arguments> = listOf(
            Arguments.of(
                "C1 overnight charge from the previous evening and a new one at night",
                listOf(
                    Ev.charging(true, at(D.plusDays(-1), 23)),
                    Ev.charging(false, at(D, 7)),
                    Ev.charging(true, at(D, 22, 30)),
                    Ev.charging(false, at(D.plusDays(1), 6)),
                ),
                510.0,
                1.0,
                630.0,
                420.0,
            ),
            Arguments.of("C2 a stop without a start in the lookback", listOf(Ev.charging(false, at(D, 5))), 60.0, 0.0, null, 300.0),
            Arguments.of("C3 no events in a covered day is a true 0", emptyList<PersonalEvent>(), 0.0, 0.0, null, null),
            Arguments.of(
                "C4 a repeated start keeps the first start",
                listOf(Ev.charging(true, at(D, 10)), Ev.charging(true, at(D, 10, 30)), Ev.charging(false, at(D, 11))),
                60.0,
                2.0,
                1350.0,
                660.0,
            ),
        )

        @JvmStatic
        fun dstCases(): List<Arguments> = listOf(
            Arguments.of("T1 normal day", "2026-09-14", 1440.0),
            Arguments.of("T2 engine day across spring forward (23 h)", "2026-03-07", 1380.0),
            Arguments.of("T3 engine day across fall back (25 h)", "2026-10-31", 1500.0),
        )
    }
}
