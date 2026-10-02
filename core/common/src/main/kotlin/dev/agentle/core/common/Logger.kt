package dev.agentle.core.common

/** Log severity, lowest to highest. */
public enum class Severity { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** One sanitized log record. [message] and [fields] values have already passed through [Redactor]. */
public data class LogRecord(
    val atEpochMs: Long,
    val severity: Severity,
    val component: String,
    val message: String,
    val eventId: String? = null,
    val errorCode: String? = null,
    val fields: Map<String, String> = emptyMap(),
)

/** Destination for sanitized records (logcat, the diagnostic_log table, test capture). */
public fun interface LogSink {
    public fun write(record: LogRecord)
}

/**
 * The only logging entry point. Everything is redacted before it reaches any sink, so tokens, codes, emails,
 * precise coordinates and long digit runs never reach logcat, the database or a diagnostics export
 * (docs/ARCHITECTURE.md §14). Callers should pass structured fields instead of interpolating values.
 */
public class Logger(
    private val sinks: List<LogSink>,
    private val nowEpochMs: () -> Long,
    private val minSeverity: Severity = Severity.INFO,
    private val redactor: Redactor = Redactor,
) {
    public fun log(
        severity: Severity,
        component: String,
        message: String,
        eventId: String? = null,
        error: AppError? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (severity < minSeverity) return
        val record = LogRecord(
            atEpochMs = nowEpochMs(),
            severity = severity,
            component = component,
            message = redactor.redact(message),
            eventId = eventId,
            errorCode = error?.code,
            fields = fields.mapValues { (_, v) -> redactor.redact(v?.toString() ?: "null") },
        )
        sinks.forEach { sink -> runCatching { sink.write(record) } }
    }

    public fun d(component: String, message: String, fields: Map<String, Any?> = emptyMap()): Unit =
        log(Severity.DEBUG, component, message, fields = fields)

    public fun i(component: String, message: String, fields: Map<String, Any?> = emptyMap()): Unit =
        log(Severity.INFO, component, message, fields = fields)

    public fun w(component: String, message: String, error: AppError? = null, fields: Map<String, Any?> = emptyMap()): Unit =
        log(Severity.WARN, component, message, error = error, fields = fields)

    public fun e(component: String, message: String, error: AppError? = null, fields: Map<String, Any?> = emptyMap()): Unit =
        log(Severity.ERROR, component, message, error = error, fields = fields)

    public companion object {
        /** A logger that drops everything; for tests and previews. */
        public val NONE: Logger = Logger(emptyList(), { 0L })
    }
}
