package dev.agentle.ai.api.screen

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.validation.OutputCodes
import dev.agentle.ai.api.validation.ValidationIssue
import dev.agentle.ai.api.validation.ValidationStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class ScreenSpecTest {
    private val screen = ScreenSpec(
        "Sleep and steps",
        listOf(
            ColumnPart("root", listOf("title", "tiles", "card")),
            TextPart("title", "Sleep and steps", "h2"),
            RowPart("tiles", listOf("sleep", "steps")),
            MetricTilePart("sleep", "SLEEP_MINUTES", 14, "average"),
            MetricTilePart("steps", "STEPS", 30, "total"),
            CardPart("card", "chart"),
            TrendChartPart("chart", "STEPS", 7, "line"),
        ),
    )

    @Test
    fun `the A2UI messages create a surface on Agentle's catalog and list every part in A2UI's flat shape`() {
        val create = Json.parseToJsonElement(A2uiScreenMessages.createSurface("s1")).jsonObject
        val update = Json.parseToJsonElement(A2uiScreenMessages.updateComponents("s1", screen)).jsonObject

        assertThat(create["version"]!!.jsonPrimitive.content).isEqualTo("v0.9.1")
        assertThat(create["createSurface"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
            .containsExactly("surfaceId", "s1", "catalogId", ScreenCatalog.ID)
        val body = update["updateComponents"]!!.jsonObject
        assertThat(body["surfaceId"]!!.jsonPrimitive.content).isEqualTo("s1")
        val components = body["components"]!!.jsonArray.map { it as JsonObject }
        assertThat(components.map { it["id"]!!.jsonPrimitive.content })
            .containsExactly("root", "title", "tiles", "sleep", "steps", "card", "chart").inOrder()
        assertThat(components[3]).isEqualTo(
            Json.parseToJsonElement("""{"component":"MetricTile","id":"sleep","metric":"SLEEP_MINUTES","days":14,"show":"average"}"""),
        )
        assertThat(components[5]).isEqualTo(Json.parseToJsonElement("""{"component":"Card","id":"card","child":"chart"}"""))
    }

    @Test
    fun `a saved screen survives storage and passes its recheck`() {
        val stored = ScreenJson.encodeToString(ScreenSpec.serializer(), screen)

        assertThat(ScreenJson.decodeFromString(ScreenSpec.serializer(), stored)).isEqualTo(screen)
        assertThat(ScreenRules.recheck(screen)).isEmpty()
        assertThat(screen.metrics).containsExactly("SLEEP_MINUTES", "STEPS").inOrder()
        assertThat(screen.days).isEqualTo(30)
    }

    @Test
    fun `a stored screen that was changed on the device fails its recheck`() {
        val broken = screen.copy(components = screen.components.filterNot { it.id == "chart" })
        val unknownMetric = screen.copy(components = screen.components + TrendChartPart("extra", "CAFFEINE", 7, "bar"))

        assertThat(ScreenRules.recheck(broken).map { it.code }).contains(OutputCodes.SCREEN_BAD_REFERENCE)
        assertThat(ScreenRules.recheck(unknownMetric).map { it.code }).contains(OutputCodes.INVALID_ENUM_VALUE)
        assertThat(ScreenRules.recheck(screen.copy(title = "Ten days")).map { it.code }).contains(OutputCodes.NUMBER_IN_POOLED_TEXT)
    }

    @Test
    fun `a screen is described in plain sentences for a change request`() {
        assertThat(ScreenDescription.describe(screen)).isEqualTo(
            "The saved screen Sleep and steps. Top to bottom it shows a heading Sleep and steps. Then side by side a tile " +
                "with the average of sleep minutes over the last 14 days and a tile with the total of steps over the last 30 days. " +
                "Then a card with a line chart of steps per day over the last 7 days.",
        )
    }

    @Test
    fun `only a screen that broke the rules is retried, led by the rules in words`() {
        val badChild = ValidationIssue(OutputCodes.SCREEN_BAD_REFERENCE, "/screen/components/0/children/1", ValidationStage.S6_SEMANTIC)
        val number = ValidationIssue(OutputCodes.NUMBER_IN_POOLED_TEXT, "/screen/title", ValidationStage.S6_SEMANTIC, "L13")
        val reply = ValidationIssue(OutputCodes.TEXT_CONTAINS_CONTACT, "/reply", ValidationStage.S6_SEMANTIC, "L1")

        assertThat(ScreenRepair.applies(listOf(badChild, number))).isTrue()
        assertThat(ScreenRepair.applies(listOf(badChild, reply))).isFalse()
        assertThat(ScreenRepair.applies(emptyList())).isFalse()
        assertThat(ScreenRepair.request("make a sleep screen", listOf(badChild, number))).isEqualTo(
            "Your last screen for this request broke these rules, so design it again and change nothing else: a child id did " +
                "not name exactly one other part. a title or text had a number in it. The request: make a sleep screen",
        )
    }
}
