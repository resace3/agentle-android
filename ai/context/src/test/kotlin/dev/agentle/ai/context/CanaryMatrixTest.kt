package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.AiDataCategory.ACTIVITY
import dev.agentle.core.model.AiDataCategory.APP_IDENTITY
import dev.agentle.core.model.AiDataCategory.BODY
import dev.agentle.core.model.AiDataCategory.CALENDAR_BUSY
import dev.agentle.core.model.AiDataCategory.CALENDAR_TEXT
import dev.agentle.core.model.AiDataCategory.DEVICE_STATE
import dev.agentle.core.model.AiDataCategory.GOALS
import dev.agentle.core.model.AiDataCategory.HEART
import dev.agentle.core.model.AiDataCategory.INTERVENTION_HISTORY
import dev.agentle.core.model.AiDataCategory.LOCATION_CLASS
import dev.agentle.core.model.AiDataCategory.NOTIFICATION_COUNTS
import dev.agentle.core.model.AiDataCategory.NOTIFICATION_TEXT
import dev.agentle.core.model.AiDataCategory.SCREEN_TIME_TOTALS
import dev.agentle.core.model.AiDataCategory.SELF_REPORTS
import dev.agentle.core.model.AiDataCategory.SETTINGS
import dev.agentle.core.model.AiDataCategory.SLEEP
import dev.agentle.core.model.AiDataCategory.STEPS
import dev.agentle.core.model.AiDataCategory.USER_TEXT
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.EventType
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * SEC-AI-01 (round 4 correction 1, testing-build-11). Every purpose runs with no category enabled, each category alone,
 * every pair of categories and all categories, with an honest and with a buggy producer, user-initiated, in the
 * background (purposes that allow it) and with confirmed individual events (purposes that allow them). Every category
 * has canary markers in the data. The test reads the exact request bytes the send-time hook approved (the three
 * strings the provider sends, whose SHA-256 the hook checked) and asserts that a marker appears only when every
 * category of its lineage is enabled for the purpose and on its allow-list, and its source family, kind, mode and
 * standing consent allow it. With an honest producer every marker that may appear does appear, so the test cannot pass
 * by sending nothing. Every sent request's audit record states exactly the categories of its body (SEC-AI-06).
 */
class CanaryMatrixTest {
    /** A marker in the data: [marker] is searched in the sent bytes; [categories] and [sources] are its whole lineage. */
    private data class Canary(
        val marker: String,
        val field: String,
        val kind: ItemKind,
        val categories: Set<AiDataCategory>,
        val sources: Set<SourceFamily>,
        val fact: Any,
    ) {
        override fun toString(): String = marker
    }

    private data class Run(
        val purpose: AiPurpose,
        val granted: Set<AiDataCategory>,
        val mode: AiRequestMode = AiRequestMode.USER_INITIATED,
        val rawEvents: Boolean = false,
        val honest: Boolean = true,
        val healthConnect: Boolean = true,
    ) {
        override fun toString(): String =
            "$purpose $mode rawEvents=$rawEvents honest=$honest healthConnect=$healthConnect granted=${granted.map { it.name }.sorted()}"
    }

    private class RunResult(val sent: Boolean, val found: Set<Canary>, val eligible: Set<Canary>)

    @Test
    fun `user-initiated requests carry only the markers of enabled categories`() = runTest(timeout = 10.minutes) {
        val failures = mutableListOf<String>()
        AiPurpose.entries.forEach { purpose ->
            SUBSETS.forEach { granted ->
                listOf(true, false).forEach { honest -> verify(Run(purpose, granted, honest = honest), failures) }
            }
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun `background requests carry only the markers inside their standing consent`() = runTest(timeout = 10.minutes) {
        val failures = mutableListOf<String>()
        AiPurpose.entries.filter { PurposePolicy.spec(it).background != null }.forEach { purpose ->
            SUBSETS.forEach { granted ->
                listOf(true, false).forEach { honest ->
                    verify(Run(purpose, granted, mode = AiRequestMode.BACKGROUND, honest = honest), failures)
                }
            }
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun `requests with confirmed individual events carry only the markers of enabled categories`() = runTest(timeout = 10.minutes) {
        val failures = mutableListOf<String>()
        AiPurpose.entries.filter { PurposePolicy.spec(it).rawEvents }.forEach { purpose ->
            SUBSETS.forEach { granted ->
                listOf(true, false).forEach { honest -> verify(Run(purpose, granted, rawEvents = true, honest = honest), failures) }
            }
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun `builds without Health Connect sharing never send a Health Connect marker`() = runTest(timeout = 10.minutes) {
        val failures = mutableListOf<String>()
        AiPurpose.entries.forEach { purpose ->
            (listOf(AiDataCategory.entries.toSet()) + AiDataCategory.entries.map { setOf(it) }).forEach { granted ->
                listOf(true, false).forEach { honest -> verify(Run(purpose, granted, honest = honest, healthConnect = false), failures) }
            }
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun `with everything enabled, every purpose sends every marker it may, so the matrix is not vacuous`() = runTest {
        val all = AiDataCategory.entries.toSet()
        AiPurpose.entries.forEach { purpose ->
            val spec = PurposePolicy.spec(purpose)
            val runs = listOfNotNull(
                Run(purpose, all),
                Run(purpose, all, mode = AiRequestMode.BACKGROUND).takeIf { spec.background != null },
                Run(purpose, all, rawEvents = true).takeIf { spec.rawEvents },
            )
            runs.forEach { run ->
                val result = execute(run)
                assertThat(result.sent).isTrue()
                assertThat(result.eligible).isNotEmpty()
                assertThat(result.found).isEqualTo(result.eligible)
            }
        }
        val question = execute(Run(AiPurpose.GENERAL_QUESTION, all, rawEvents = true)).found.map { it.marker }
        assertThat(question).containsAtLeast(
            "Canaryappidentity",
            "Canaryusernote",
            "Canarygoaltext",
            "canaryRawSteps",
            "canaryRawSleep",
            "\"field\":\"body.weight_latest\"",
        )
        assertThat(question).containsNoneOf("Canarynotificationtext", "Canarycalendartitle", "canaryRawCall", "canaryRawGh")
    }

    private suspend fun verify(run: Run, failures: MutableList<String>) {
        val result = execute(run)
        val spec = PurposePolicy.spec(run.purpose)
        val enabled = run.granted.intersect(spec.categories)
        result.found.forEach { canary ->
            val disabled = canary.categories - enabled
            if (disabled.isNotEmpty()) failures += "$run: $canary sent although ${disabled.map { it.name }.sorted()} is not enabled"
            if (canary !in result.eligible) failures += "$run: $canary sent although it is not allowed"
        }
        if (run.honest && result.sent && result.found != result.eligible) {
            failures += "$run: sent ${result.found} but expected ${result.eligible}"
        }
    }

    private suspend fun execute(run: Run): RunResult {
        val world = World(data = universe(run.honest), healthConnectToAi = run.healthConnect, hooked = true)
        val standing = if (run.mode == AiRequestMode.BACKGROUND) standingFor(world, run) else null
        world.writeConsent(grants(run.purpose, run.granted), listOfNotNull(standing))
        val confirmation = if (run.rawEvents) world.engine.confirmRawEvents(run.purpose).getOrThrow() else null
        val question = QUESTION.takeIf { PurposePolicy.spec(run.purpose).userText == UserTextRule.REQUIRED }
        val built = world.engine.build(AiContextRequest(run.purpose, question, run.mode, confirmation))
        if (built is Outcome.Success) world.send(built.value)

        val approved = world.hook.approved.toList()
        val journal = world.provider.journal.filter { it.sent }.map {
            it.sentInstructions + "\n" + it.sentDataInput + "\n" +
                it.sentUserInput.orEmpty()
        }
        check(approved == journal) { "$run: the provider sent other bytes than the hook approved" }
        val sentCall = world.provider.journal.singleOrNull { it.sent }
        if (sentCall != null) {
            val record = checkNotNull(world.audit[sentCall.requestId]) { "$run: no record" }
            check(record.categories == sentCategories(sentCall.sentDataInput)) { "$run: record categories differ from the body" }
            check(record.status == AiRequestStatus.SENT) { "$run: status ${record.status}" }
        }
        val bytes = approved.joinToString("\n")
        return RunResult(
            sent = approved.isNotEmpty(),
            found = CANARIES.filterTo(LinkedHashSet()) { bytes.contains(it.marker) },
            eligible = CANARIES.filterTo(LinkedHashSet()) { eligible(it, run, standing) },
        )
    }

    private fun standingFor(world: World, run: Run): StandingConsent? {
        val template = world.engine.standingConsentTemplate(run.purpose).getOrThrow().restrictedTo(run.granted)
        if (template.categories.isEmpty() || template.fields.isEmpty()) return null
        return StandingConsent(
            purpose = run.purpose,
            fields = template.fields,
            categories = template.categories,
            sourceFamilies = template.sourceFamilies,
            lookbackDays = template.lookbackDays,
            cadence = template.cadence,
            dailyBudget = template.dailyBudget,
            consentVersion = AiConsentDisclosure.VERSION,
            grantedAt = START,
            accountSub = ACCOUNT,
        )
    }

    /** The rules, restated independently of the engine: when may [canary] leave the phone in [run]? */
    private fun eligible(canary: Canary, run: Run, standing: StandingConsent?): Boolean {
        val spec = PurposePolicy.spec(run.purpose)
        val enabled = run.granted.intersect(spec.categories)
        val families = if (run.healthConnect) setOf(SourceFamily.ON_DEVICE, SourceFamily.HEALTH_CONNECT) else setOf(SourceFamily.ON_DEVICE)
        val field = AiFieldRegistry[canary.field]
        val userInitiated = run.mode == AiRequestMode.USER_INITIATED
        val lineageOk = canary.categories.isNotEmpty() &&
            canary.categories.none { it.thirdPartyText } &&
            enabled.containsAll(canary.categories) &&
            families.containsAll(canary.sources)
        val kindOk = canary.kind in spec.itemKinds &&
            field != null &&
            field.purposes?.contains(run.purpose) != false &&
            (canary.kind in ContextSelectionEngine.AGGREGATE_KINDS || userInitiated) &&
            (canary.kind != ItemKind.EVENT || (run.rawEvents && spec.rawEvents))
        val standingOk = standing == null ||
            (
                canary.field in standing.fields &&
                    standing.categories.containsAll(canary.categories) &&
                    standing.sourceFamilies.containsAll(canary.sources)
                )
        return lineageOk && kindOk && standingOk
    }

    private companion object {
        const val QUESTION = "Canaryquestion how did my week go"
        val OD: SourceFamily = SourceFamily.ON_DEVICE
        val HC: SourceFamily = SourceFamily.HEALTH_CONNECT
        val GH: SourceFamily = SourceFamily.GH_API
        val EVENT_TIME: Instant = Instant.parse("2026-09-30T08:00:00Z")

        /** No category, all categories, each category alone and every pair (pairwise coverage, round 4 correction 1). */
        val SUBSETS: List<Set<AiDataCategory>> = buildList {
            val all = AiDataCategory.entries
            add(emptySet())
            add(all.toSet())
            all.forEach { add(setOf(it)) }
            for (i in all.indices) for (j in i + 1 until all.size) add(setOf(all[i], all[j]))
        }

        fun aggregate(
            field: String,
            value: AggregateValue,
            categories: Set<AiDataCategory>,
            sources: Set<SourceFamily> = setOf(OD),
        ): Canary {
            val kind = when (value) {
                is AggregateValue.Quantity -> ItemKind.QUANTITY
                is AggregateValue.TimeOfDay -> ItemKind.TIME_OF_DAY
                is AggregateValue.Code -> ItemKind.CODE
            }
            return Canary(
                "\"field\":\"$field\"",
                field,
                kind,
                categories,
                sources,
                AggregateFact(field, value, DataLineage(categories, sources)),
            )
        }

        fun q(field: String, unit: String, vararg categories: AiDataCategory, source: SourceFamily = OD): Canary =
            aggregate(field, AggregateValue.Quantity(CANARY_VALUE, unit), categories.toSet(), setOf(source))

        fun text(marker: String, field: String, origin: TextOrigin, category: AiDataCategory): Canary {
            val floor = AiFieldRegistry[field]?.categories.orEmpty()
            val fact = UserTextFact(field, UntrustedText(marker, origin), DataLineage(setOf(category), setOf(OD)))
            return Canary(marker, field, ItemKind.TEXT, floor + category, setOf(OD), fact)
        }

        fun event(marker: String, type: EventType, connector: String): Canary {
            val fact = RawEventFact(type, EVENT_TIME, null, mapOf(marker to 1.0), connector)
            val categories = AiLineageTables.EVENT_CATEGORIES.getValue(type)
            val source = SourceFamily.ofConnector(connector)
            val sources = if (source == null ||
                categories.size == AiDataCategory.entries.size
            ) {
                SourceFamily.entries.toSet()
            } else {
                setOf(source)
            }
            return Canary(marker, AiFieldRegistry.EVENT_FIELD, ItemKind.EVENT, categories, sources, fact)
        }

        private const val CANARY_VALUE = 7.25

        val CANARIES: List<Canary> = listOf(
            q("screen.minutes_daily_avg", "min", SCREEN_TIME_TOTALS),
            q("apps.category_minutes_daily_avg.social", "min", SCREEN_TIME_TOTALS, APP_IDENTITY),
            q("notifications.daily_avg", "count", NOTIFICATION_COUNTS),
            q("calendar.busy_minutes_daily_avg", "min", CALENDAR_BUSY),
            q("place.home_minutes_daily_avg", "min", LOCATION_CLASS),
            q("activity.exercise_minutes", "min", ACTIVITY),
            q("steps.daily_avg", "steps", STEPS),
            q("sleep.minutes_avg", "min", SLEEP, source = HC),
            q("heart.resting_bpm_avg", "bpm", HEART, source = HC),
            q("body.weight_latest", "kg", BODY, source = HC),
            q("goal.target", "steps", GOALS),
            q("self_report.mood_avg", "score", SELF_REPORTS),
            q("device.battery_pct_now", "pct", DEVICE_STATE),
            aggregate("history.last_response", AggregateValue.Code("OPENED"), setOf(INTERVENTION_HISTORY)),
            q("history.deliveries_7d", "count", INTERVENTION_HISTORY),
            aggregate("settings.tone", AggregateValue.Code("WARM"), setOf(SETTINGS)),
            aggregate("settings.quiet_hours_start", AggregateValue.TimeOfDay(LocalTime(22, 30)), setOf(SETTINGS)),
            q("steps.daily_max", "steps", STEPS, source = GH),
            aggregate(
                "pattern.effect_minutes",
                AggregateValue.Quantity(CANARY_VALUE, "min"),
                setOf(SLEEP, SCREEN_TIME_TOTALS),
                setOf(HC, OD),
            ),
            aggregate("evidence.count", AggregateValue.Quantity(CANARY_VALUE, "count"), setOf(STEPS, INTERVENTION_HISTORY)),
            Canary(
                "Canaryappidentity",
                AppUsageFact.APP_USAGE_FIELD,
                ItemKind.APP_USAGE,
                setOf(APP_IDENTITY, SCREEN_TIME_TOTALS),
                setOf(OD),
                AppUsageFact(
                    UntrustedText("Canaryappidentity", TextOrigin.APP_LABEL),
                    42,
                    3,
                    DataLineage(setOf(APP_IDENTITY, SCREEN_TIME_TOTALS), setOf(OD)),
                ),
            ),
            text("Canaryusernote", "user.note", TextOrigin.USER_NOTE, USER_TEXT),
            text("Canarygoaltext", "goal.text", TextOrigin.USER_GOAL, GOALS),
            text("Canarynotificationtext", "user.note", TextOrigin.NOTIFICATION, NOTIFICATION_TEXT),
            text("Canarycalendartitle", "user.note", TextOrigin.CALENDAR, CALENDAR_TEXT),
            event("canaryRawSteps", EventType.STEP_SAMPLE, ConnectorIds.ANDROID),
            event("canaryRawSleep", EventType.SLEEP_SESSION, ConnectorIds.HEALTH_CONNECT),
            event("canaryRawCall", EventType.CALL_EVENT, ConnectorIds.ANDROID),
            event("canaryRawGh", EventType.STEP_SAMPLE, ConnectorIds.GOOGLE_HEALTH),
        )

        /** Every category's markers; an honest producer answers within the query, a buggy one returns all of them. */
        fun universe(honest: Boolean): ScriptedDataSource = ScriptedDataSource(
            aggregates = CANARIES.mapNotNull { it.fact as? AggregateFact },
            apps = CANARIES.mapNotNull { it.fact as? AppUsageFact },
            texts = CANARIES.mapNotNull { it.fact as? UserTextFact },
            raw = CANARIES.mapNotNull { it.fact as? RawEventFact },
            honest = honest,
        )
    }
}
