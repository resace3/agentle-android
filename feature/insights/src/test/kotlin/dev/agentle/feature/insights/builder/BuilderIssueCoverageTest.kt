package dev.agentle.feature.insights.builder

import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.feature.insights.testing.Fixtures
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.rule.Operator
import org.junit.Test

/**
 * Every validator issue a form can produce lands on the field that caused it, with its code. Each case changes one
 * field of the valid walk form.
 */
class BuilderIssueCoverageTest {
    private data class Case(val name: String, val field: String, val code: String, val change: (BuilderForm) -> BuilderForm)

    private fun validation(form: BuilderForm): BuilderValidation {
        val context = Fixtures.context()
        return validate(form.toDefinition(Fixtures.NOW, Fixtures.ZONE), context, context.renderOptions(emptyList())).second
    }

    private val cases = listOf(
        Case("empty name", Fields.NAME, "E060") { it.copy(name = "") },
        Case("long name", Fields.NAME, "E060") { it.copy(name = "x".repeat(200)) },
        Case("empty title", Fields.CONTENT_TITLE, "E060") { it.copy(content = it.content.copy(title = "")) },
        Case("no daily time", Fields.TIMES, "E035") { it.copy(trigger = it.trigger.copy(dailyTimes = emptyList())) },
        Case("short interval", Fields.INTERVAL, "E033") {
            it.copy(trigger = it.trigger.copy(type = TriggerType.INTERVAL, intervalMinutes = "1"))
        },
        Case("no events", Fields.EVENTS, "E031") { it.copy(trigger = it.trigger.copy(type = TriggerType.EVENT, events = emptyList())) },
        Case("zero per day", Fields.MAX_PER_DAY, "E042") { it.copy(frequency = it.frequency.copy(maxPerDay = "0")) },
        Case("too many per day", Fields.MAX_PER_DAY, "E042") { it.copy(frequency = it.frequency.copy(maxPerDay = "99")) },
        Case("week below day", Fields.MAX_PER_WEEK, "E044") { it.copy(frequency = it.frequency.copy(maxPerDay = "3", maxPerWeek = "2")) },
        Case("short cooldown", Fields.COOLDOWN, "E041") { it.copy(frequency = it.frequency.copy(cooldownMinutes = "1")) },
        Case("empty window", Fields.WINDOW, "E025") { it.copy(window = it.window.copy(enabled = true, start = "10:00", end = "10:00")) },
        Case("value out of range", Fields.row(Fields.TREE_CONDITIONS, 0, RowPart.VALUE), "E016") { form ->
            form.copy(conditions = form.conditions.copy(rows = listOf(form.conditions.rows[0].copy(value = "-5"))))
        },
        Case("between reversed", Fields.row(Fields.TREE_CONDITIONS, 0, RowPart.ROW), "E017") { form ->
            val row = form.conditions.rows[0].copy(operator = Operator.BETWEEN, value = "5000", secondValue = "100")
            form.copy(conditions = form.conditions.copy(rows = listOf(row)))
        },
        Case("unknown feature", Fields.row(Fields.TREE_CONDITIONS, 0, RowPart.FEATURE), "E010") { form ->
            form.copy(conditions = form.conditions.copy(rows = listOf(form.conditions.rows[0].copy(featureId = "nope"))))
        },
        Case("media without asset", Fields.CONTENT_ASSET, "E068") {
            it.copy(delivery = it.delivery.copy(channel = DeliveryChannel.IMAGE), content = it.content.copy(type = ContentType.MEDIA))
        },
    )

    @Test
    fun `each form change reports its code on its field`() {
        val misses = cases.mapNotNull { case ->
            val codes = validation(case.change(Fixtures.walkForm())).issuesFor(case.field).mapNotNull { it.code?.name }
            "${case.name}: expected ${case.code} on ${case.field}, got $codes".takeUnless { case.code in codes }
        }
        assertWithMessage(misses.joinToString("\n")).that(misses).isEmpty()
    }
}
