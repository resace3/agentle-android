package dev.agentle.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Source of an event: `<connector>.<stream>`, for example `android.usage`, `android.notifications`,
 * `googlehealth.steps`, `healthconnect.sleep`, `user.log`, `agentle.jitai`.
 */
@Serializable
@JvmInline
public value class DataSourceId(public val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid data source id '$value'" }
    }

    public val connectorId: String get() = value.substringBefore('.')
    public val stream: String get() = value.substringAfter('.')

    override fun toString(): String = value

    public companion object {
        private val PATTERN = Regex("""[a-z][a-z0-9_]*\.[a-z][a-z0-9_.]*""")

        public fun of(connectorId: String, stream: String): DataSourceId = DataSourceId("$connectorId.$stream")
    }
}

/** Well-known connector ids. */
public object ConnectorIds {
    public const val ANDROID: String = "android"
    public const val GOOGLE_HEALTH: String = "googlehealth"
    public const val HEALTH_CONNECT: String = "healthconnect"
    public const val USER: String = "user"
    public const val AGENTLE: String = "agentle"

    /** Sources whose data came from the user's wearable (deleted together by "delete wearable data"). */
    public val WEARABLE: Set<String> = setOf(GOOGLE_HEALTH, HEALTH_CONNECT)
}
