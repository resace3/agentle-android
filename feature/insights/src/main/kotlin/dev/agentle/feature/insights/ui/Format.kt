package dev.agentle.feature.insights.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The label of an enum value shown on screen; falls back to the readable name. */
@Composable
internal fun label(value: Enum<*>): String =
    ENUM_LABELS[value]?.let { stringResource(it) } ?: value.name.lowercase(Locale.ROOT).replace('_', ' ')

/** The plain name of a catalog feature; the id for one this build does not know. */
@Composable
internal fun featureLabel(featureId: String): String = FEATURE_LABELS[featureId]?.let { stringResource(it) } ?: featureId

/** Dates and times are already in the user's zone (converted with the port's zone); this only formats them. */
internal fun formatDate(date: LocalDate): String = date.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

internal fun formatDateTime(dateTime: LocalDateTime, use24HourClock: Boolean): String {
    val time = DateTimeFormatter.ofPattern(if (use24HourClock) "HH:mm" else "h:mm a", Locale.getDefault())
    val javaDateTime = dateTime.toJavaLocalDateTime()
    return javaDateTime.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) + ", " + javaDateTime.format(time)
}
