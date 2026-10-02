package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.model.AiDataCategory.APP_IDENTITY
import dev.agentle.core.model.AiDataCategory.GOALS
import dev.agentle.core.model.AiDataCategory.SCREEN_TIME_TOTALS
import dev.agentle.core.model.AiDataCategory.USER_TEXT
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes

/**
 * Seeded property (privacy-ai-05, SEC-AI-02): whatever origins, taint, lineage claims, grants and producer honesty a
 * case has, text of a third party never reaches the model. A request whose producer returned any such text fails with
 * THIRD_PARTY and sends nothing. A sent request holds the user's own untainted text only as `text` values and app
 * labels only as `app` values.
 */
class ThirdPartyTextPropertyTest {
    /** Keeps everything the producer returned to the engine. */
    private class Recording(private val inner: AiContextDataSource) : AiContextDataSource by inner {
        val texts: MutableList<UserTextFact> = CopyOnWriteArrayList()
        val apps: MutableList<AppUsageFact> = CopyOnWriteArrayList()

        override suspend fun appUsage(query: AiDataQuery): Outcome<List<AppUsageFact>> =
            inner.appUsage(query).also { if (it is Outcome.Success) apps += it.value }

        override suspend fun userTexts(query: AiDataQuery): Outcome<List<UserTextFact>> =
            inner.userTexts(query).also { if (it is Outcome.Success) texts += it.value }
    }

    /** One planted string: [token] is unique, survives reduction and appears nowhere else. */
    private data class Planted(val token: String, val origin: TextOrigin, val aiGenerated: Boolean, val app: Boolean) {
        /** Whether the engine may send it at all: the user's own text, or an app label in the app slot; never AI-written. */
        val sendable: Boolean get() = !aiGenerated && if (app) origin == TextOrigin.APP_LABEL else !origin.thirdParty
    }

    @Test
    fun `third-party text never reaches a model, whatever the producer returns (seeded property)`() = runTest(timeout = 5.minutes) {
        val random = SplitMix64(20_261_002L)
        var sent = 0
        var refused = 0
        repeat(CASES) { case ->
            val planted = LinkedHashMap<String, Planted>()
            val texts = List(random.nextInt(MAX_TEXTS + 1)) {
                val origin = if (random.nextBoolean()) random.pick(USER_ORIGINS) else random.pick(TextOrigin.entries)
                val tainted = origin == TextOrigin.AI_OUTPUT || random.nextInt(TAINT_ONE_IN) == 0
                val token = "zq" + random.letters(TOKEN_LETTERS)
                val goal = random.nextBoolean()
                val floor = if (goal) GOALS else USER_TEXT
                val claimed = if (random.nextInt(MISCLAIM_ONE_IN) == 0) random.pick(listOf(USER_TEXT, GOALS)) else floor
                val raw = "Note $token here"
                planted[raw] = Planted(token, origin, tainted, app = false)
                UserTextFact(if (goal) "goal.text" else "user.note", UntrustedText(raw, origin, tainted), lineage(claimed))
            }
            val apps = List(random.nextInt(MAX_APPS + 1)) {
                val origin = if (random.nextBoolean()) TextOrigin.APP_LABEL else random.pick(TextOrigin.entries)
                val tainted = origin == TextOrigin.AI_OUTPUT || random.nextInt(TAINT_ONE_IN) == 0
                val raw = "App zq" + random.letters(TOKEN_LETTERS)
                planted[raw] = Planted(raw.removePrefix("App "), origin, tainted, app = true)
                AppUsageFact(UntrustedText(raw, origin, tainted), 5L + random.nextInt(60), null, lineage(APP_IDENTITY, SCREEN_TIME_TOTALS))
            }
            val granted = GRANTABLE.filter { random.nextBoolean() }
            val data = Recording(
                ScriptedDataSource(
                    aggregates = listOf(quantity("screen.minutes_daily_avg", 120.0, "min", lineage(SCREEN_TIME_TOTALS))),
                    apps = apps,
                    texts = texts,
                    honest = random.nextInt(DISHONEST_ONE_IN) != 0,
                ),
            )
            val world = World(data = data, hooked = true)
            if (granted.isNotEmpty()) world.grant(AiPurpose.GENERAL_QUESTION, *granted.toTypedArray())

            val built = world.engine.build(AiPurpose.GENERAL_QUESTION, "What did I note")

            val message = "case $case"
            val returnedBad = data.texts.any { it.text.origin.thirdParty } || data.apps.any { it.label.origin != TextOrigin.APP_LABEL }
            val error = built.errorOrNull()
            if (returnedBad) {
                assertWithMessage(message).that(error).isInstanceOf(AppError.ConsentViolation::class.java)
                assertWithMessage(message).that((error as AppError.ConsentViolation).detail).isEqualTo(GateCodes.THIRD_PARTY)
                refused++
            } else if (error is AppError.ConsentViolation) {
                assertWithMessage(message).that(error.detail).isNotEqualTo(GateCodes.THIRD_PARTY)
            }

            val bytes = if (built is Outcome.Success) {
                assertWithMessage(message).that(world.send(built.value).errorOrNull()).isNull()
                sent++
                world.hook.approved.single()
            } else {
                assertWithMessage(message).that(world.hook.approved).isEmpty()
                ""
            }
            val logs = world.sink.text()
            planted.values.forEach { p ->
                assertWithMessage("$message: logs").that(logs).doesNotContain(p.token)
                if (!p.sendable) assertWithMessage("$message: $p").that(bytes).doesNotContain(p.token)
            }
            if (built is Outcome.Success) {
                val call = world.provider.journal.single { it.sent }
                val values = stringValues(Json.parseToJsonElement(call.sentDataInput))
                planted.values.forEach { p ->
                    values.filter { (_, value) -> p.token in value }.forEach { (key, _) ->
                        assertWithMessage("$message: $p").that(key).isEqualTo(if (p.app) "app" else "text")
                    }
                }
                val returned = data.texts.map { it.text.raw } + data.apps.map { it.label.raw }
                returned.map(planted::getValue).filter { it.sendable }.forEach { p ->
                    assertWithMessage("$message: returned and sendable, so sent: $p").that(bytes).contains(p.token)
                }
            }
        }
        assertThat(sent).isAtLeast(CASES / 10)
        assertThat(refused).isAtLeast(CASES / 10)
    }

    private companion object {
        const val CASES = 400
        const val MAX_TEXTS = 3
        const val MAX_APPS = 2
        const val TOKEN_LETTERS = 10
        const val TAINT_ONE_IN = 5
        const val MISCLAIM_ONE_IN = 5
        const val DISHONEST_ONE_IN = 4
        val USER_ORIGINS = listOf(TextOrigin.USER_NOTE, TextOrigin.USER_GOAL, TextOrigin.USER_REQUEST)
        val GRANTABLE = listOf(USER_TEXT, GOALS, APP_IDENTITY, SCREEN_TIME_TOTALS)
    }
}
