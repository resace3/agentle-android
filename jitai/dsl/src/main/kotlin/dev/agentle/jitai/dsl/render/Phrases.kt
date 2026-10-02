package dev.agentle.jitai.dsl.render

import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.WeekDay
import java.util.Locale

/**
 * Fixed English phrases of the renderer (R10 §13.5). Event phrases and category labels are this module's wording:
 * R10 lists the sentence structure but not these words.
 */
public object Phrases {
    /** "When {event}": the phrase for one trigger event. */
    public fun event(event: JitaiEventType): String = when (event) {
        JitaiEventType.LOCATION_CLASS_CHANGED -> "you arrive at or leave Home or Work"
        JitaiEventType.ACTIVITY_STATE_CHANGED -> "your activity changes"
        JitaiEventType.HEALTH_SYNC_COMPLETED -> "new health data arrives"
        JitaiEventType.SLEEP_SESSION_AVAILABLE -> "new sleep data arrives"
        JitaiEventType.NOTIFICATION_POSTED -> "a notification arrives"
        JitaiEventType.POWER_CONNECTED -> "the phone starts charging"
        JitaiEventType.POWER_DISCONNECTED -> "the phone stops charging"
        JitaiEventType.SCREEN_INTERACTIVE -> "the screen turns on"
        JitaiEventType.USER_PRESENT -> "you unlock the phone"
    }

    /** "Physical activity", "Sleep wind-down", ... */
    public fun category(category: JitaiCategory): String = when (category) {
        JitaiCategory.PHYSICAL_ACTIVITY -> "Physical activity"
        JitaiCategory.SLEEP_WIND_DOWN -> "Sleep wind-down"
        JitaiCategory.DIGITAL_WELLBEING -> "Digital wellbeing"
        JitaiCategory.STRESS_BREAK -> "Stress break"
        JitaiCategory.GENERAL -> "General"
    }

    /** "Monday" ... "Sunday". */
    public fun day(day: WeekDay): String = day.dayOfWeek.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }

    /** `a`, `a and b`, `a, b and c` (or with [conjunction] "or"). */
    public fun list(items: List<String>, conjunction: String = "and"): String = when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> items.dropLast(1).joinToString(", ") + " $conjunction " + items.last()
    }

    /** `3,000`: digits grouped by three with commas (en-US, independent of the default locale). */
    public fun grouped(value: Long): String {
        val digits = kotlin.math.abs(value).toString()
        val out = StringBuilder()
        digits.forEachIndexed { index, c ->
            if (index > 0 && (digits.length - index) % GROUP == 0) out.append(',')
            out.append(c)
        }
        return if (value < 0) "-$out" else out.toString()
    }

    /** `45 min` below an hour, `2 h`, `1 h 30 min` (R10 §13.5 durations). */
    public fun duration(minutes: Long): String {
        if (minutes < MINUTES_PER_HOUR) return "$minutes min"
        val h = minutes / MINUTES_PER_HOUR
        val m = minutes % MINUTES_PER_HOUR
        return if (m == 0L) "$h h" else "$h h $m min"
    }

    /** `10:00 PM` (12-hour) or `22:00` (24-hour) for a minute of day. */
    public fun time(minuteOfDay: Int, use24HourClock: Boolean): String {
        val h = minuteOfDay / MINUTES_PER_HOUR.toInt()
        val m = minuteOfDay % MINUTES_PER_HOUR.toInt()
        if (use24HourClock) return "%02d:%02d".format(Locale.ROOT, h, m)
        val suffix = if (h < NOON) "AM" else "PM"
        val hour = if (h % NOON == 0) NOON else h % NOON
        return "%d:%02d %s".format(Locale.ROOT, hour, m, suffix)
    }

    private const val GROUP = 3
    private const val MINUTES_PER_HOUR = 60L
    private const val NOON = 12
}
