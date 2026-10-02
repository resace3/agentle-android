package dev.agentle.analytics.features

import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A typed feature value. Every numeric feature is an integer with a stated unit, so rule boundaries are exact. */
@Serializable
public sealed interface FeatureScalar {
    @Serializable
    @SerialName("int")
    public data class IntValue(val value: Long) : FeatureScalar

    @Serializable
    @SerialName("bool")
    public data class BoolValue(val value: Boolean) : FeatureScalar

    @Serializable
    @SerialName("enum")
    public data class EnumValue(val value: String) : FeatureScalar

    @Serializable
    @SerialName("day_of_week")
    public data class DayOfWeekValue(val value: DayOfWeek) : FeatureScalar

    /** Minute of day 0..1439, ordered 00:00 < ... < 23:59. */
    @Serializable
    @SerialName("local_time")
    public data class LocalTimeValue(val minuteOfDay: Int) : FeatureScalar {
        init {
            require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay out of range: $minuteOfDay" }
        }
    }

    /** Clock minute 0..1439 ordered on the night clock 12:00 < ... < 23:59 < 00:00 < ... < 11:59 (R10 §4.3). */
    @Serializable
    @SerialName("night_time")
    public data class NightTimeValue(val minuteOfDay: Int) : FeatureScalar {
        init {
            require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay out of range: $minuteOfDay" }
        }

        /** Position on the night clock: `(minuteOfDay - 720) mod 1440`. */
        val nightOrder: Int get() = Math.floorMod(minuteOfDay - NOON, MINUTES_PER_DAY)
    }

    @Serializable
    @SerialName("package")
    public data class PackageValue(val packageName: String) : FeatureScalar

    /** `foreground_app` when no app is in the foreground: equals no package (`eq` FALSE, `neq` TRUE, `in` FALSE). */
    @Serializable
    @SerialName("no_package")
    public data object NoPackage : FeatureScalar

    /** `minutes_since_last_delivery` with no matching delivery: compares as +infinity (R10 §5.4 I). */
    @Serializable
    @SerialName("never")
    public data object Never : FeatureScalar

    public companion object {
        public const val MINUTES_PER_DAY: Int = 1440
        private const val NOON = 720
    }
}

@Serializable
public enum class Quality { FINAL, PROVISIONAL }

/** Why a value is unknown (R10 §5.2). Shown in decision traces and the "why didn't it fire" view. */
@Serializable
public enum class MissingReason {
    NO_PERMISSION,
    COLLECTOR_INACTIVE,
    COVERAGE_GAP,
    SOURCE_DISCONNECTED,
    NOT_SYNCED,
    NO_DATA,
    NOT_YET_AVAILABLE,
    INVALID_VALUE,
    API_LEVEL,
    API_UNAVAILABLE,
    LOCKED_AFTER_BOOT,
}

/**
 * The value of one [FeatureRef] at one evaluation instant. `Stale` and `Missing` evaluate to UNKNOWN except under the
 * monotone lower-bound rule. Absence is never zero: no data is `Missing(NO_DATA)`.
 */
@Serializable
public sealed interface FeatureValue {
    @Serializable
    @SerialName("known")
    public data class Known(val value: FeatureScalar, val asOf: Instant, val quality: Quality = Quality.FINAL) : FeatureValue

    @Serializable
    @SerialName("stale")
    public data class Stale(val lastValue: FeatureScalar, val asOf: Instant, val reason: MissingReason) : FeatureValue

    @Serializable
    @SerialName("missing")
    public data class Missing(val reason: MissingReason) : FeatureValue
}
