package dev.agentle.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The deletion registry covers the whole schema (round 4 correction 2; R04 §4 SEC-DEL-05): every table Room creates and
 * every table of the exported schema has a rule, every JSON column is declared, and exempt tables never get steps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaRegistryTest {
    private lateinit var database: AgentleDatabase

    @Before
    fun setUp() {
        database = TestDatabases.inMemory()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `every table in the database has a deletion rule`() = runTest {
        assertThat(tables()).containsExactlyElementsIn(DataCategoryRegistry.tables)
    }

    @Test
    fun `every table of the exported Room schema has a deletion rule`() {
        val schema = File("schemas/dev.agentle.core.database.AgentleDatabase/${AgentleDatabase.VERSION}.json")
        assumeTrue("exported schema not committed yet", schema.exists())
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        val names = entities.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
        assertThat(names).containsExactlyElementsIn(DataCategoryRegistry.tables)
    }

    @Test
    fun `every JSON column is declared by its table rule`() = runTest {
        val transactions = TestDatabases.transactions(database)
        tables().forEach { table ->
            val columns = transactions.read { query("PRAGMA table_info($table)") { it.text(1) } }
            val json = columns.filter { it == "json" || it.endsWith("_json") || it in KNOWN_JSON_COLUMNS }.toSet()
            val rule = DataCategoryRegistry.rule(table)
            assertThat(rule).isNotNull()
            assertThat(rule!!.jsonColumns).containsExactlyElementsIn(json)
        }
    }

    @Test
    fun `exempt tables never get steps and the others get steps for some scope`() {
        val ids = TermIds(
            types = dev.agentle.core.model.EventType.entries.associate { it.name to it.ordinal.toLong() + 1 },
            sources = mapOf("googlehealth.steps" to 100L, "android.usage" to 101L, "user.log" to 102L),
        )
        val scopes = DeletionScope.ALL
        DataCategoryRegistry.rules.forEach { rule ->
            val steps = scopes.flatMap { rule.steps(it, ids) }
            if (rule.exemption != null) {
                assertThat(steps).isEmpty()
            } else {
                assertThat(steps).isNotEmpty()
                assertThat(steps.map { it.table }.toSet()).containsExactly(rule.table)
            }
        }
    }

    @Test
    fun `scope codes round-trip`() {
        val scopes = DeletionScope.ALL
        scopes.forEach { assertThat(DeletionScope.parse(it.code)).isEqualTo(it) }
        assertThat(DeletionScope.parse("X:WHAT")).isNull()
    }

    private suspend fun tables(): List<String> = TestDatabases.transactions(database).read {
        queryTexts(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata')",
        )
    }

    private companion object {
        /** JSON columns whose names do not end in `_json`. */
        val KNOWN_JSON_COLUMNS = setOf("stream_permissions")
    }
}
