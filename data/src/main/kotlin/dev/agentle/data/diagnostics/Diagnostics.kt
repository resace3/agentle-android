package dev.agentle.data.diagnostics

import dev.agentle.core.common.AppException
import dev.agentle.core.database.LineageCodec
import dev.agentle.core.database.entity.DiagnosticLogEntity
import dev.agentle.core.datastore.StoreDiagnostics
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import dev.agentle.data.Tx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** Severity of a stored diagnostic. */
enum class DiagnosticSeverity { INFO, WARN, ERROR }

/**
 * The closed set of diagnostics `:data` stores (round 1 correction 7): a component, a code and a severity. There is no
 * free-text column; context goes into allow-listed [DiagnosticField]s.
 */
enum class DiagnosticCode(val component: String, val severity: DiagnosticSeverity) {
    SYNC_CURSOR_CLAMPED("sync", DiagnosticSeverity.WARN),
    SYNC_COVERAGE_CLAMPED("sync", DiagnosticSeverity.WARN),
    STORE_RESET("store", DiagnosticSeverity.WARN),
    STORE_IO_FAILURE("store", DiagnosticSeverity.WARN),
    DELETION_COMPLETED("deletion", DiagnosticSeverity.INFO),
    DELETION_RESUMED("deletion", DiagnosticSeverity.INFO),
    DELETION_UNVERIFIED("deletion", DiagnosticSeverity.ERROR),
    CHECKPOINT_BUSY("deletion", DiagnosticSeverity.WARN),
    RETENTION_COMPLETED("retention", DiagnosticSeverity.INFO),
    MEDIA_FILE_NOT_DELETED("media", DiagnosticSeverity.WARN),
}

/** The keys a diagnostic may carry. Values are numbers or short identifiers (enum names, ids, class names). */
enum class DiagnosticField(val key: String) {
    CONNECTOR("connector"),
    STREAM("stream"),
    STORE("store"),
    OPERATION("operation"),
    ERROR_CLASS("error_class"),
    FUTURE_MS("future_ms"),
    ROWS("rows"),
    TABLE("table"),
    SCOPE("scope"),
    STEP("step"),
    DURATION_MS("duration_ms"),
    COUNT("count"),
    HTTP_STATUS("http_status"),
    REASON("reason"),
    COLLECTOR("collector"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun of(key: String): DiagnosticField? = byKey[key]
    }
}

/** One stored diagnostic. */
data class DiagnosticEntry(
    val id: Long,
    val at: Instant,
    val severity: String,
    val component: String,
    val code: String,
    val fields: Map<String, String>,
)

/** The diagnostics ring buffer (`diagnostic_log`, newest [DiagnosticWriter.CAPACITY] rows). */
interface DiagnosticsRepository {
    /** Best effort: a database that cannot be opened drops the entry. */
    suspend fun record(code: DiagnosticCode, fields: Map<String, Any?> = emptyMap())

    suspend fun recent(limit: Int): List<DiagnosticEntry>

    fun observe(limit: Int): Flow<List<DiagnosticEntry>>

    suspend fun count(): Long
}

/**
 * Writes allow-listed diagnostics. Keys outside [DiagnosticField] are dropped; a string value that is not a short
 * identifier (`[A-Za-z0-9_.:-]`, at most 64 characters) is stored as `REDACTED`, so no message, exception text or
 * personal value can reach the table. The ring buffer keeps the newest [CAPACITY] rows.
 */
internal class DiagnosticWriter(private val clock: AgentleClock) {
    suspend fun write(tx: Tx, code: DiagnosticCode, fields: Map<String, Any?> = emptyMap()) {
        tx.db.systemDao().insertDiagnostic(
            DiagnosticLogEntity(
                atMs = clock.now().toEpochMilliseconds(),
                severity = code.severity.name,
                component = code.component,
                code = code.name,
                fieldsJson = encode(fields),
                lineage = LineageCodec.EMPTY,
            ),
        )
        tx.db.systemDao().trimDiagnostics(CAPACITY)
    }

    companion object {
        const val CAPACITY: Int = 5_000
        const val REDACTED: String = "REDACTED"
        private const val MAX_VALUE_LENGTH = 64
        private val IDENTIFIER = Regex("[A-Za-z0-9_.:-]{1,$MAX_VALUE_LENGTH}")

        fun encode(fields: Map<String, Any?>): String {
            val allowed = fields.entries.mapNotNull { (key, value) ->
                val field = DiagnosticField.of(key) ?: return@mapNotNull null
                val primitive = when (value) {
                    null -> return@mapNotNull null
                    is Int -> JsonPrimitive(value)
                    is Long -> JsonPrimitive(value)
                    is Double -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    is Enum<*> -> JsonPrimitive(value.name)
                    is String -> JsonPrimitive(if (IDENTIFIER.matches(value)) value else REDACTED)
                    else -> JsonPrimitive(REDACTED)
                }
                field.key to primitive
            }
            return JsonObject(allowed.toMap().toSortedMap()).toString()
        }

        fun decode(json: String): Map<String, String> = try {
            (Json.parseToJsonElement(json) as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
        } catch (expected: IllegalArgumentException) {
            emptyMap()
        }
    }
}

internal class RoomDiagnosticsRepository(private val access: DataAccess, private val writer: DiagnosticWriter) : DiagnosticsRepository {
    override suspend fun record(code: DiagnosticCode, fields: Map<String, Any?>) {
        try {
            access.write { writer.write(this, code, fields) }
        } catch (expected: AppException) {
            // The database is unavailable: a diagnostic is never worth failing the caller.
        }
    }

    override suspend fun recent(limit: Int): List<DiagnosticEntry> = access.read { db.systemDao().recentDiagnostics(limit).map(::entryOf) }

    override fun observe(limit: Int): Flow<List<DiagnosticEntry>> = flow {
        emitAll(access.database().systemDao().observeDiagnostics(limit).map { rows -> rows.map(::entryOf) })
    }

    override suspend fun count(): Long = access.read { db.systemDao().diagnosticCount() }

    private fun entryOf(row: DiagnosticLogEntity): DiagnosticEntry = DiagnosticEntry(
        id = row.id,
        at = Instant.fromEpochMilliseconds(row.atMs),
        severity = row.severity,
        component = row.component,
        code = row.code,
        fields = DiagnosticWriter.decode(row.fieldsJson),
    )
}

/**
 * Reports of the settings and consent stores (a reset after corruption, an I/O failure) as diagnostics. The stores call
 * it from their own threads, so the write is launched in [scope]; it carries the store name, the operation and the error
 * class only.
 */
internal class DiagnosticStoreReporter(
    private val diagnostics: DiagnosticsRepository,
    private val scope: CoroutineScope,
) : StoreDiagnostics {
    override fun onReset(store: String, errorClass: String) {
        scope.launch {
            diagnostics.record(DiagnosticCode.STORE_RESET, mapOf(DiagnosticField.STORE.key to store, DiagnosticField.ERROR_CLASS.key to errorClass))
        }
    }

    override fun onIoFailure(store: String, operation: String, errorClass: String) {
        scope.launch {
            diagnostics.record(
                DiagnosticCode.STORE_IO_FAILURE,
                mapOf(
                    DiagnosticField.STORE.key to store,
                    DiagnosticField.OPERATION.key to operation,
                    DiagnosticField.ERROR_CLASS.key to errorClass,
                ),
            )
        }
    }
}
