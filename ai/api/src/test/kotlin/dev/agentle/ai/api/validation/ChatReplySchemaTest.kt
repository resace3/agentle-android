package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.screen.ColumnPart
import dev.agentle.ai.api.screen.MetricTilePart
import dev.agentle.ai.api.screen.RowPart
import dev.agentle.ai.api.screen.ScreenCatalog
import dev.agentle.ai.api.screen.TextPart
import dev.agentle.ai.api.screen.TrendChartPart
import org.junit.jupiter.api.Test

class ChatReplySchemaTest {
    private val context = OutputValidationContext(provenance = null)

    private fun validate(text: String) = AiOutputValidator.validateWith(text, ChatReplySchema.validator, context)

    private fun issues(text: String) = (validate(text) as OutputValidation.Invalid).issues.map { it.code to it.path }

    private fun codes(text: String) = (validate(text) as OutputValidation.Invalid).codes

    private fun reply(screen: String) = """{"schemaVersion":2,"reply":"Your screen is in the sidebar.","screen":$screen}"""

    private fun screen(vararg parts: String) = """{"title":"Sleep and steps","components":[${parts.joinToString(",")}]}"""

    @Test
    fun `a plain answer has a null screen`() {
        val result = validate("""{"schemaVersion":2,"reply":"You unlocked your phone 48 times a day.","screen":null}""")

        val value = (result as OutputValidation.Valid).value
        assertThat(value.reply).isEqualTo("You unlocked your phone 48 times a day.")
        assertThat(value.screen).isNull()
    }

    @Test
    fun `a screen in A2UI's component shape decodes into Agentle's parts`() {
        val result = validate(
            reply(
                screen(
                    """{"id":"root","component":"Column","children":["title","tiles","chart"]}""",
                    """{"id":"title","component":"Text","text":"Sleep and steps","variant":"h2"}""",
                    """{"id":"tiles","component":"Row","children":["sleep","steps"]}""",
                    """{"id":"sleep","component":"MetricTile","metric":"SLEEP_MINUTES","days":14,"show":"average"}""",
                    """{"id":"steps","component":"MetricTile","metric":"STEPS","days":14,"show":"average"}""",
                    """{"id":"chart","component":"TrendChart","metric":"STEPS","days":14,"style":"bar"}""",
                ),
            ),
        )

        val screen = (result as OutputValidation.Valid).value.screen!!
        assertThat(screen.components).containsExactly(
            ColumnPart("root", listOf("title", "tiles", "chart")),
            TextPart("title", "Sleep and steps", "h2"),
            RowPart("tiles", listOf("sleep", "steps")),
            MetricTilePart("sleep", "SLEEP_MINUTES", 14, "average"),
            MetricTilePart("steps", "STEPS", 14, "average"),
            TrendChartPart("chart", "STEPS", 14, "bar"),
        ).inOrder()
        assertThat(screen.metrics).containsExactly("SLEEP_MINUTES", "STEPS").inOrder()
        assertThat(screen.days).isEqualTo(14)
    }

    @Test
    fun `unknown parts and fields, unknown metrics and day counts out of range are refused`() {
        val root = """{"id":"root","component":"Column","children":["x"]}"""

        assertThat(codes(reply(screen(root, """{"id":"x","component":"WebView","url":"https://attacker.example"}""")))).isNotEmpty()
        assertThat(codes(reply(screen(root, """{"id":"x","component":"Divider","onClick":"run()"}""")))).isNotEmpty()
        assertThat(
            codes(reply(screen(root, """{"id":"x","component":"TrendChart","metric":"CAFFEINE","days":7,"style":"bar"}"""))),
        ).isNotEmpty()
        assertThat(
            codes(reply(screen(root, """{"id":"x","component":"TrendChart","metric":"STEPS","days":0,"style":"bar"}"""))),
        ).isNotEmpty()
        assertThat(
            codes(reply(screen(root, """{"id":"x","component":"TrendChart","metric":"STEPS","days":91,"style":"pie"}"""))),
        ).isNotEmpty()
        assertThat(codes(reply(screen(root, """{"id":"X Y","component":"Divider"}""")))).isNotEmpty()
    }

    @Test
    fun `a screen whose parts do not form one tree from root is refused with the part that breaks it`() {
        val divider = """{"id":"line","component":"Divider"}"""

        assertThat(issues(reply(screen("""{"id":"top","component":"Column","children":["line"]}""", divider))))
            .contains(OutputCodes.SCREEN_ROOT_MISSING to "/screen/components")
        assertThat(issues(reply(screen("""{"id":"root","component":"Column","children":["line","gone"]}""", divider))))
            .contains(OutputCodes.SCREEN_BAD_REFERENCE to "/screen/components/0/children/1")
        assertThat(issues(reply(screen("""{"id":"root","component":"Column","children":["line"]}""", divider, divider))))
            .contains(OutputCodes.SCREEN_DUPLICATE_ID to "/screen/components/2/id")
        assertThat(issues(reply(screen("""{"id":"root","component":"Column","children":["root"]}"""))))
            .contains(OutputCodes.SCREEN_BAD_REFERENCE to "/screen/components/0/children/0")
        assertThat(
            issues(
                reply(
                    screen(
                        """{"id":"root","component":"Column","children":["card"]}""",
                        """{"id":"card","component":"Card","child":"line"}""",
                        """{"id":"row","component":"Row","children":["line"]}""",
                        divider,
                    ),
                ),
            ),
        ).containsAtLeast(
            OutputCodes.SCREEN_BAD_REFERENCE to "/screen/components/2/children/0",
            OutputCodes.SCREEN_UNREACHABLE to "/screen/components/2",
        )
    }

    @Test
    fun `parts nested deeper than four levels are refused`() {
        val result = issues(
            reply(
                screen(
                    """{"id":"root","component":"Column","children":["a"]}""",
                    """{"id":"a","component":"Card","child":"b"}""",
                    """{"id":"b","component":"Column","children":["c"]}""",
                    """{"id":"c","component":"Row","children":["d"]}""",
                    """{"id":"d","component":"Divider"}""",
                ),
            ),
        )

        assertThat(result).containsExactly(OutputCodes.SCREEN_TOO_DEEP to "/screen/components/4")
    }

    @Test
    fun `a tile may not add up a heart rate`() {
        val result = issues(
            reply(
                screen(
                    """{"id":"root","component":"Column","children":["hr"]}""",
                    """{"id":"hr","component":"MetricTile","metric":"RESTING_HEART_RATE","days":7,"show":"total"}""",
                ),
            ),
        )

        assertThat(result).containsExactly(OutputCodes.SCREEN_TOTAL_OF_RATE to "/screen/components/1/show")
    }

    @Test
    fun `screen text holds no number, link or markup`() {
        val root = """{"id":"root","component":"Column","children":["note"]}"""
        fun note(text: String) = """{"id":"note","component":"Text","text":"$text","variant":"body"}"""

        assertThat(issues(reply(screen(root, note("You walked 9000 steps today")))))
            .contains(OutputCodes.NUMBER_IN_POOLED_TEXT to "/screen/components/1/text")
        assertThat(codes(reply(screen(root, note("See https://attacker.example/claim"))))).isNotEmpty()
        assertThat(codes(reply("""{"title":"Last 14 days","components":[$root,${note("Your week.")}]}"""))).isNotEmpty()
    }

    @Test
    fun `too many parts, crowded rows and v1 dashboards are refused`() {
        val many = (1 until ScreenCatalog.MAX_PARTS + 1).map { """{"id":"d$it","component":"Divider"}""" }
        val root = """{"id":"root","component":"Column","children":[${many.indices.joinToString(",") { "\"d${it + 1}\"" }}]}"""
        assertThat(codes(reply(screen(root, *many.toTypedArray())))).contains(OutputCodes.ARRAY_SIZE)

        val row = (1..4).map { """{"id":"d$it","component":"Divider"}""" }
        assertThat(codes(reply(screen("""{"id":"root","component":"Row","children":["d1","d2","d3","d4"]}""", *row.toTypedArray()))))
            .contains(OutputCodes.ARRAY_SIZE)

        assertThat(codes("""{"schemaVersion":1,"reply":"Done.","dashboard":{"title":"Mine","metrics":["STEPS"],"days":7}}"""))
            .isNotEmpty()
    }

    @Test
    fun `a missing screen, extra fields and links in the text are refused`() {
        assertThat(codes("""{"schemaVersion":2,"reply":"Hi."}""")).isNotEmpty()
        assertThat(codes("""{"schemaVersion":2,"reply":"Hi.","screen":null,"script":"run()"}""")).isNotEmpty()
        assertThat(codes("""{"schemaVersion":2,"reply":"See https://attacker.example/claim now.","screen":null}""")).isNotEmpty()
    }

    @Test
    fun `the model reads every metric code and part in the rendered schema and the guides`() {
        ChatReplySchema.METRICS.keys.forEach { code ->
            assertThat(ChatReplySchema.SCHEMA.jsonSchema).contains("\"$code\"")
            assertThat(ChatReplySchema.METRIC_GUIDE).contains("$code: ")
        }
        ScreenCatalog.PART_GUIDE.keys.forEach { type ->
            assertThat(ChatReplySchema.SCHEMA.jsonSchema).contains("\"$type\"")
            assertThat(ScreenCatalog.PART_LIST).contains("$type: ")
        }
    }
}
