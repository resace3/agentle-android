package dev.agentle.feature.settings.notifications

import dev.agentle.feature.settings.port.DeliveryLimitBounds
import dev.agentle.feature.settings.port.DeliveryLimits
import dev.agentle.feature.settings.port.InterventionChannel
import kotlinx.datetime.LocalTime

/** Step sizes of the notification-settings controls. */
internal object LimitSteps {
    const val DAILY_CAP: Int = 1
    const val WEEKLY_CAP: Int = 5
    const val MIN_GAP_MINUTES: Int = 15
    const val CHANNEL_CAP: Int = 1
    const val QUIET_HOURS_MINUTES: Int = 30
}

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/**
 * These limits moved inside the hard ceilings ([DeliveryLimitBounds]): every value the screen sends passes through
 * here, so no control can raise a cap above 12 a day or 60 a week, or bring the gap under 15 minutes.
 */
internal fun DeliveryLimits.coercedIntoBounds(): DeliveryLimits {
    val daily = dailyCap.coerceIn(DeliveryLimitBounds.dailyCapRange)
    val channelRange = DeliveryLimitBounds.channelCapRange(daily)
    return DeliveryLimits(
        dailyCap = daily,
        weeklyCap = weeklyCap.coerceIn(DeliveryLimitBounds.weeklyCapRange),
        minGapMinutes = minGapMinutes.coerceIn(DeliveryLimitBounds.minGapRange),
        channelCaps = channelCaps.mapValues { (_, cap) -> cap.coerceIn(channelRange) },
    )
}

/** A new daily cap; channel caps above it come down with it (a channel cap is never above the daily cap). */
internal fun DeliveryLimits.withDailyCap(cap: Int): DeliveryLimits = copy(dailyCap = cap).coercedIntoBounds()

internal fun DeliveryLimits.withWeeklyCap(cap: Int): DeliveryLimits = copy(weeklyCap = cap).coercedIntoBounds()

internal fun DeliveryLimits.withMinGap(minutes: Int): DeliveryLimits = copy(minGapMinutes = minutes).coercedIntoBounds()

/** A new cap for [channel], from its effective cap (a channel without its own cap follows the daily cap). */
internal fun DeliveryLimits.withChannelCap(channel: InterventionChannel, cap: Int): DeliveryLimits =
    copy(channelCaps = channelCaps + (channel to cap)).coercedIntoBounds()

/** [minutes] later (or earlier when negative), wrapping around midnight. */
internal fun LocalTime.plusMinutesWrapped(minutes: Int): LocalTime {
    val minuteOfDay = (hour * MINUTES_PER_HOUR + minute + minutes).mod(MINUTES_PER_DAY)
    return LocalTime(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)
}
