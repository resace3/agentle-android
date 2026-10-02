package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason

/** Group A, time and calendar (R10 §5.4 A): from the pass instant and zone; never unknown. */
internal object ClockFeatures {
    fun compute(pass: FeaturePass, featureId: String): FeatureValue {
        val scalar = when (featureId) {
            "local_time" -> FeatureScalar.LocalTimeValue(LocalTimeRules.minuteOfDay(pass.at, pass.zone))
            "day_of_week" -> FeatureScalar.DayOfWeekValue(pass.localDate.dayOfWeek)
            "day_type" -> FeatureScalar.EnumValue(if (pass.localDate.dayOfWeek in pass.config.weekendDays) "WEEKEND" else "WEEKDAY")
            "engine_day_of_week" -> FeatureScalar.DayOfWeekValue(pass.engineDay.dayOfWeek)
            else -> null
        }
        return scalar?.let { pass.known(it) } ?: FeatureValue.Missing(MissingReason.INVALID_VALUE)
    }
}

/** Group B, device state (R10 §5.4 B): live reads; a failed or unknown read is `Missing(API_UNAVAILABLE)`. */
internal object DeviceFeatures {
    private val IN_CALL_MODES = setOf(AudioMode.IN_CALL, AudioMode.IN_COMMUNICATION)
    private val HEADPHONES = setOf(
        AudioOutputType.WIRED_HEADPHONES,
        AudioOutputType.WIRED_HEADSET,
        AudioOutputType.BLUETOOTH_A2DP,
        AudioOutputType.BLE_HEADSET,
        AudioOutputType.USB_HEADSET,
    )

    suspend fun compute(pass: FeaturePass, featureId: String): FeatureValue = when (featureId) {
        "charging" -> pass.charging().orMissing { pass.known(FeatureScalar.BoolValue(it)) }

        "battery_pct" -> pass.batteryPercent().orMissing { pass.known(FeatureScalar.IntValue(it.toLong())) }

        "device_interactive" -> pass.interactive().orMissing { pass.known(FeatureScalar.BoolValue(it)) }

        "dnd_active" -> pass.interruptionFilter().orMissing { filter ->
            if (filter == InterruptionFilter.UNKNOWN) {
                FeatureValue.Missing(MissingReason.API_UNAVAILABLE)
            } else {
                pass.known(FeatureScalar.BoolValue(filter != InterruptionFilter.ALL))
            }
        }

        "in_call" -> pass.audioMode().orMissing { pass.known(FeatureScalar.BoolValue(it in IN_CALL_MODES)) }

        "headphones_connected" -> pass.audioOutputs().orMissing { outputs ->
            pass.known(FeatureScalar.BoolValue(outputs.any { it in HEADPHONES }))
        }

        else -> FeatureValue.Missing(MissingReason.INVALID_VALUE)
    }
}
