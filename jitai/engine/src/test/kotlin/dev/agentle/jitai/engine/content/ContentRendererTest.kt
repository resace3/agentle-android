package dev.agentle.jitai.engine.content

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.DataCategory
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.Provenance
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.TextPair
import dev.agentle.jitai.dsl.model.Tone
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.ports.AiTextConsent
import dev.agentle.jitai.engine.ports.DisplaySettings
import dev.agentle.jitai.engine.ports.PooledText
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** R10 §3.3 content: choice, rendering, placeholder formatting and the pooled AI text checks of red team privacy-ai-01. */
class ContentRendererTest {
    private val at = F0.local("2026-10-01T17:00")
    private val renderer = ContentRenderer()
    private val consent = AiTextConsent(version = 3, allowedCategories = setOf(DataCategory.ACTIVITY))

    private val stepsRule = Rules.R2
    private val snapshot = FeatureSnapshot.of(at, F0.BERLIN.id, mapOf(FeatureRef(Leaves.STEPS) to FeatureValue.Known(FeatureScalar.IntValue(2_500), at)))
    private val hash = SnapshotHashes.contextHash(stepsRule, snapshot)

    private fun pooled(
        id: String,
        createdAt: kotlin.time.Instant = at - 1.hours,
        jitaiId: String = "AI",
        consentVersion: Int = 3,
        categories: Set<DataCategory> = setOf(DataCategory.ACTIVITY),
        snapshotHash: String? = hash,
    ) = PooledText(id, jitaiId, "Pooled $id", "Body $id", createdAt, consentVersion, categories, snapshotHash)

    private val aiRule = stepsRule.copy(
        id = "AI",
        content = ContentStrategy.AiText(
            goal = "walk",
            tone = Tone.WARM,
            fallback = ContentStrategy.Template("Walk?", "Only {{steps_today}} steps."),
        ),
    )

    private fun input(definition: dev.agentle.jitai.dsl.model.JitaiDefinition, ref: ContentRef, pooled: PooledText? = null, snapshot: FeatureSnapshot? = this.snapshot) =
        ContentRenderer.RenderInput(
            definition = definition,
            decisionKey = "v1|${definition.id}|D|2026-10-01|17:00",
            channel = definition.delivery.channel,
            snapshot = snapshot,
            snapshotHash = hash,
            ref = ref,
            pooled = pooled,
            nonce = "nonce-1",
            now = at,
            consent = consent,
        )

    // -- choice ------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "delivery {0} -> variant {1}")
    @CsvSource("0, 0", "1, 1", "2, 2", "3, 0", "7, 1")
    fun `variants rotate by deliveryCount mod n`(previous: Long, index: Int) {
        val rule = stepsRule.copy(content = ContentStrategy.Variants(listOf(TextPair("A", "a"), TextPair("B", "b"), TextPair("C", "c"))))

        assertThat(renderer.choose(rule, previous, emptyList(), hash, at, consent)).isEqualTo(ContentRef.Variant(index))
    }

    @Test
    fun `static, template, missing content and media map to their refs`() {
        val media = stepsRule.copy(content = ContentStrategy.LocalMedia("walk_01", ContentStrategy.Template("t", "b")))

        assertThat(renderer.choose(Rules.R1, 0, emptyList(), hash, at, consent)).isEqualTo(ContentRef.StaticText)
        assertThat(renderer.choose(stepsRule, 0, emptyList(), hash, at, consent)).isEqualTo(ContentRef.TemplateText)
        assertThat(renderer.choose(stepsRule.copy(content = null), 0, emptyList(), hash, at, consent)).isEqualTo(ContentRef.TemplateText)
        assertThat(renderer.choose(media, 0, emptyList(), hash, at, consent)).isEqualTo(ContentRef.Media("walk_01"))
        assertThat(renderer.choose(stepsRule.copy(content = ContentStrategy.Variants(emptyList())), 5, emptyList(), hash, at, consent))
            .isEqualTo(ContentRef.Variant(0))
    }

    @Test
    fun `ai_text picks the oldest usable pooled item, else the fallback`() {
        val pool = listOf(pooled("new", at - 1.hours), pooled("old", at - 2.hours), pooled("stale", at - 25.hours))

        assertThat(renderer.choose(aiRule, 0, pool, hash, at, consent)).isEqualTo(ContentRef.AiPooled("old"))
        assertThat(renderer.choose(aiRule, 0, listOf(pooled("stale", at - 25.hours)), hash, at, consent)).isEqualTo(ContentRef.AiFallback)
        assertThat(renderer.choose(aiRule, 0, emptyList(), hash, at, consent)).isEqualTo(ContentRef.AiFallback)
    }

    @Test
    fun `pooled text is checked at delivery - JITAI, age, consent version, categories and context hash`() {
        fun usable(item: PooledText, decisionHash: String? = hash) = ContentRenderer.usablePooledText(item, "AI", decisionHash, at, consent)

        assertThat(usable(pooled("ok"))).isTrue()
        assertThat(usable(pooled("exactly-24h", at - 24.hours))).isTrue()
        assertThat(usable(pooled("too-old", at - 24.hours - 1.minutes))).isFalse()
        assertThat(usable(pooled("other-jitai", jitaiId = "R1"))).isFalse()
        assertThat(usable(pooled("old-consent", consentVersion = 2))).isFalse()
        assertThat(usable(pooled("revoked-category", categories = setOf(DataCategory.ACTIVITY, DataCategory.HEART)))).isFalse()
        assertThat(usable(pooled("other-context", snapshotHash = "0".repeat(64)))).isFalse()
        assertThat(usable(pooled("no-hash", snapshotHash = null))).isFalse()
        assertThat(usable(pooled("ok"), decisionHash = null)).isFalse()
    }

    // -- rendering ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a template is filled from the decision's snapshot`() {
        val rendered = renderer.render(input(stepsRule, ContentRef.TemplateText))

        assertThat(rendered.title).isEqualTo("Time for a walk")
        assertThat(rendered.body).isEqualTo("Only 2,500 steps so far today.")
        assertThat(rendered.notificationTag).isEqualTo("v1|R2|D|2026-10-01|17:00")
        assertThat(rendered.nonce).isEqualTo("nonce-1")
        assertThat(rendered.contentRef).isEqualTo(ContentRef.TemplateText)
        assertThat(rendered.speak).isFalse()
        assertThat(rendered.snoozeOptions).isEqualTo(SnoozePolicy.DEFAULT.options)
    }

    @Test
    fun `a missing value renders as a dash, never as zero`() {
        val empty = FeatureSnapshot.of(at, F0.BERLIN.id, mapOf(FeatureRef(Leaves.STEPS) to FeatureValue.Missing(MissingReason.NO_DATA)))

        assertThat(renderer.render(input(stepsRule, ContentRef.TemplateText, snapshot = empty)).body).isEqualTo("Only — steps so far today.")
        assertThat(renderer.render(input(stepsRule, ContentRef.TemplateText, snapshot = null)).body).isEqualTo("Only — steps so far today.")
        val unknownPlaceholder = stepsRule.copy(content = ContentStrategy.Template("{{battery_pct}}", "x"))
        assertThat(renderer.render(input(unknownPlaceholder, ContentRef.TemplateText)).title).isEqualTo("—")
    }

    @Test
    fun `variants render the stored index, an out-of-range index falls back to the first`() {
        val rule = stepsRule.copy(content = ContentStrategy.Variants(listOf(TextPair("A {{steps_today}}", "a"), TextPair("B", "b"))))

        assertThat(renderer.render(input(rule, ContentRef.Variant(1))).title).isEqualTo("B")
        assertThat(renderer.render(input(rule, ContentRef.Variant(9))).title).isEqualTo("A 2,500")
        assertThat(renderer.render(input(rule, ContentRef.TemplateText)).contentRef).isEqualTo(ContentRef.Variant(0))
        assertThat(renderer.render(input(stepsRule.copy(content = ContentStrategy.Variants(emptyList())), ContentRef.Variant(0))).title).isEmpty()
    }

    @Test
    fun `ai_text renders the pooled item only while it is still usable, else the template fallback`() {
        val item = pooled("p1")

        val shown = renderer.render(input(aiRule, ContentRef.AiPooled("p1"), item))
        val tooOld = renderer.render(input(aiRule, ContentRef.AiPooled("p1"), item.copy(createdAt = at - 30.hours)))
        val otherItem = renderer.render(input(aiRule, ContentRef.AiPooled("p2"), item))
        val gone = renderer.render(input(aiRule, ContentRef.AiPooled("p1"), null))

        assertThat(shown.title).isEqualTo("Pooled p1")
        assertThat(shown.contentRef).isEqualTo(ContentRef.AiPooled("p1"))
        listOf(tooOld, otherItem, gone).forEach {
            assertThat(it.title).isEqualTo("Walk?")
            assertThat(it.body).isEqualTo("Only 2,500 steps.")
            assertThat(it.contentRef).isEqualTo(ContentRef.AiFallback)
        }
    }

    @Test
    fun `local_media renders its caption and asset, and a downgrade drops the asset`() {
        val media = stepsRule.copy(
            content = ContentStrategy.LocalMedia("walk_01", ContentStrategy.Template("Walk", "{{steps_today}} so far")),
            delivery = stepsRule.delivery.copy(channel = DeliveryChannel.IMAGE, notificationTimeoutMinutes = 45),
            snooze = SnoozePolicy(SnoozeMode.SUPPRESS_ONLY, listOf(SnoozeOption.MINUTES_30)),
        )

        val rendered = renderer.render(input(media, ContentRef.Media("walk_01")))
        val downgraded = rendered.downgraded("MEDIA_UNAVAILABLE")

        assertThat(rendered.body).isEqualTo("2,500 so far")
        assertThat(rendered.assetId).isEqualTo("walk_01")
        assertThat(rendered.channel).isEqualTo(DeliveryChannel.IMAGE)
        assertThat(rendered.timeoutMinutes).isEqualTo(45)
        assertThat(rendered.snoozeOptions).containsExactly(SnoozeOption.MINUTES_30)
        assertThat(downgraded.channel).isEqualTo(DeliveryChannel.NOTIFICATION)
        assertThat(downgraded.decidedChannel).isEqualTo(DeliveryChannel.IMAGE)
        assertThat(downgraded.assetId).isNull()
        assertThat(downgraded.downgradeReason).isEqualTo("MEDIA_UNAVAILABLE")
    }

    @Test
    fun `a rule without content renders its name, VOICE speaks`() {
        val voice = stepsRule.copy(content = null, delivery = stepsRule.delivery.copy(channel = DeliveryChannel.VOICE))

        val rendered = renderer.render(input(voice, ContentRef.TemplateText))

        assertThat(rendered.title).isEqualTo("Rule R2")
        assertThat(rendered.body).isEmpty()
        assertThat(rendered.speak).isTrue()
    }

    @Test
    fun `placeholders with args resolve through the leaf that names them, with the app label`() {
        val leaf = Condition.Gte("app_minutes_last_60m", mapOf("package" to "com.example.video"), RuleLiteral.of(30))
        val rule = stepsRule.copy(
            conditions = Leaves.all(leaf, Condition.Eq("foreground_app", value = RuleLiteral.of("com.example.video"))),
            content = ContentStrategy.Template("{{foreground_app}}", "{{app_minutes_last_60m}} min"),
            provenance = Provenance(appLabels = mapOf("com.example.video" to "VideoApp")),
        )
        val values = FeatureSnapshot.of(
            at,
            F0.BERLIN.id,
            mapOf(
                leaf.ref to FeatureValue.Known(FeatureScalar.IntValue(42), at),
                FeatureRef("foreground_app") to FeatureValue.Known(FeatureScalar.PackageValue("com.example.video"), at),
            ),
        )

        val rendered = renderer.render(input(rule, ContentRef.TemplateText, snapshot = values))

        assertThat(rendered.title).isEqualTo("VideoApp")
        assertThat(rendered.body).isEqualTo("42 min")
    }

    // -- refs and formatting -----------------------------------------------------------------------------------------------

    @Test
    fun `content refs round-trip and malformed refs are rejected`() {
        val refs = listOf(ContentRef.StaticText, ContentRef.TemplateText, ContentRef.Variant(2), ContentRef.AiPooled("p9"), ContentRef.AiFallback, ContentRef.Media("m1"))

        refs.forEach { assertThat(ContentRef.parse(it.encoded)).isEqualTo(it) }
        listOf("variant:-1", "variant:x", "ai:", "media:", "other").forEach { assertThat(ContentRef.parse(it)).isNull() }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "en-US 24h int, en-US, true, int, 12500, '12,500'",
        "de-DE int, de-DE, true, int, 12500, '12.500'",
        "24h time, en-US, true, time, 1325, '22:05'",
        "12h time, en-US, false, time, 1325, '10:05 PM'",
        "night time, en-US, true, night, 30, '00:30'",
        "bool, en-US, true, bool, 1, 'yes'",
        "enum, en-US, true, enum, 0, 'moderate or vigorous'",
        "day, en-US, true, day, 3, 'Thursday'",
        "never, en-US, true, never, 0, '—'",
        "no package, en-US, true, none, 0, '—'",
    )
    fun `placeholder values are formatted by locale and clock setting`(id: String, locale: String, h24: Boolean, kind: String, raw: Int, expected: String) {
        val formatter = DefaultPlaceholderFormatter(DisplaySettings(locale, h24))
        val scalar = when (kind) {
            "int" -> FeatureScalar.IntValue(raw.toLong())
            "time" -> FeatureScalar.LocalTimeValue(raw)
            "night" -> FeatureScalar.NightTimeValue(raw)
            "bool" -> FeatureScalar.BoolValue(raw == 1)
            "enum" -> FeatureScalar.EnumValue("MODERATE_OR_VIGOROUS")
            "day" -> FeatureScalar.DayOfWeekValue(DayOfWeek.entries[raw])
            "never" -> FeatureScalar.Never
            else -> FeatureScalar.NoPackage
        }

        assertThat(formatter.format("x", FeatureValue.Known(scalar, at), emptyMap())).isEqualTo(expected)
    }

    @Test
    fun `a stale value renders its last value and an unlabelled package its name`() {
        val formatter = DefaultPlaceholderFormatter()

        assertThat(formatter.format("steps_today", FeatureValue.Stale(FeatureScalar.IntValue(3_200), at, MissingReason.NOT_SYNCED), emptyMap()))
            .isEqualTo("3,200")
        assertThat(formatter.format("foreground_app", FeatureValue.Known(FeatureScalar.PackageValue("org.sample"), at), emptyMap()))
            .isEqualTo("org.sample")
        assertThat(formatter.format("x", null, emptyMap())).isEqualTo(DefaultPlaceholderFormatter.MISSING_VALUE)
    }

    @Test
    fun `the context hash ignores clock features and asOf but not values`() {
        val rule = stepsRule.copy(conditions = Leaves.all(Leaves.lt(Leaves.STEPS, 3_000), Leaves.gte("local_time", "17:00")))
        fun hashOf(steps: FeatureValue, time: Int) = SnapshotHashes.contextHash(
            rule,
            FeatureSnapshot.of(
                at,
                F0.BERLIN.id,
                mapOf(FeatureRef(Leaves.STEPS) to steps, FeatureRef("local_time") to FeatureValue.Known(FeatureScalar.LocalTimeValue(time), at)),
            ),
        )

        val base = hashOf(FeatureValue.Known(FeatureScalar.IntValue(2_500), at), 1_020)

        assertThat(hashOf(FeatureValue.Known(FeatureScalar.IntValue(2_500), at - 5.minutes), 1_030)).isEqualTo(base)
        assertThat(hashOf(FeatureValue.Known(FeatureScalar.IntValue(2_600), at), 1_020)).isNotEqualTo(base)
        assertThat(hashOf(FeatureValue.Missing(MissingReason.NO_DATA), 1_020)).isNotEqualTo(base)
        assertThat(hashOf(FeatureValue.Stale(FeatureScalar.IntValue(2_500), at, MissingReason.NOT_SYNCED), 1_020)).isNotEqualTo(base)
        assertThat(SnapshotHashes.contextHash(rule, FeatureSnapshot(at, F0.BERLIN.id, emptyMap()))).hasLength(64)
    }
}
