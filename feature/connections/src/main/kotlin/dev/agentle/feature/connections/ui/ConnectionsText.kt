package dev.agentle.feature.connections.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.agentle.ai.api.AiCapability
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.CapabilitySupport
import dev.agentle.core.common.AppError
import dev.agentle.core.model.EventType
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.port.AiRequestOutcome
import dev.agentle.feature.connections.port.AiSharingCategory
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** Formats instants as a medium date and a short time in [zone] (the user's zone from the clock, never the default). */
internal class InstantFormatter(zone: TimeZone, locale: Locale) {
    private val zoneId = zone.toJavaZoneId()
    private val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)

    fun format(instant: Instant): String = formatter.format(instant.toJavaInstant().atZone(zoneId))
}

@Composable
internal fun rememberInstantFormatter(zone: TimeZone): InstantFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(zone, locale) { InstantFormatter(zone, locale) }
}

/** A size in bytes as the system shows file sizes ("2.3 kB"). */
@Composable
internal fun formatBytes(bytes: Int): String {
    val context = LocalContext.current
    return remember(bytes, context) { Formatter.formatShortFileSize(context, bytes.toLong()) }
}

/** An error as a short sentence plus its stable code ("You're offline or the network failed (network_unavailable)"). */
@Composable
internal fun errorText(error: AppError): String = errorCodeText(error.code)

@Composable
internal fun errorCodeText(code: String): String =
    stringResource(R.string.connections_error_with_code, stringResource(errorMessage(code)), code)

/** Keyed by `AppError.code`, so codes added later fall back to the generic message instead of breaking the build. */
@StringRes
internal fun errorMessage(code: String): Int = when (code) {
    "network_unavailable" -> R.string.connections_error_network_unavailable
    "rate_limited" -> R.string.connections_error_rate_limited
    "remote_server_error" -> R.string.connections_error_remote_server_error
    "database_error" -> R.string.connections_error_database_error
    "authentication_required" -> R.string.connections_error_authentication_required
    "token_expired" -> R.string.connections_error_token_expired
    "unsupported_feature" -> R.string.connections_error_unsupported_feature
    "consent_violation" -> R.string.connections_error_consent_violation
    "not_eligible" -> R.string.connections_error_not_eligible
    "cancelled" -> R.string.connections_error_cancelled
    "parsing_error" -> R.string.connections_error_parsing_error
    "validation_error" -> R.string.connections_error_validation_error
    "permission_denied", "permission_permanently_denied" -> R.string.connections_error_permission_denied
    else -> R.string.connections_error_unexpected
}

/** The wearable data types a Google Health connection can share; other types fall back to "Other data". */
@StringRes
internal fun wearableTypeLabel(type: EventType): Int = when (type) {
    EventType.STEP_SAMPLE -> R.string.connections_wearable_type_steps
    EventType.DISTANCE_SAMPLE -> R.string.connections_wearable_type_distance
    EventType.FLOORS_SAMPLE -> R.string.connections_wearable_type_floors
    EventType.CALORIES_SAMPLE -> R.string.connections_wearable_type_calories
    EventType.ACTIVITY -> R.string.connections_wearable_type_activity
    EventType.EXERCISE_SESSION -> R.string.connections_wearable_type_exercise
    EventType.HEART_RATE -> R.string.connections_wearable_type_heart_rate
    EventType.RESTING_HEART_RATE -> R.string.connections_wearable_type_resting_heart_rate
    EventType.SLEEP_SESSION -> R.string.connections_wearable_type_sleep
    EventType.WEIGHT -> R.string.connections_wearable_type_weight
    EventType.BODY_FAT -> R.string.connections_wearable_type_body_fat
    EventType.WEARABLE_DEVICE -> R.string.connections_wearable_type_device
    else -> R.string.connections_wearable_type_other
}

@StringRes
internal fun purposeLabel(purpose: AiPurpose): Int = when (purpose) {
    AiPurpose.SLEEP_INSIGHT -> R.string.connections_ai_purpose_sleep_insight
    AiPurpose.ACTIVITY_INSIGHT -> R.string.connections_ai_purpose_activity_insight
    AiPurpose.SCREEN_TIME_INSIGHT -> R.string.connections_ai_purpose_screen_time_insight
    AiPurpose.GENERAL_QUESTION -> R.string.connections_ai_purpose_general_question
    AiPurpose.PATTERN_EXPLANATION -> R.string.connections_ai_purpose_pattern_explanation
    AiPurpose.JITAI_FROM_NATURAL_LANGUAGE -> R.string.connections_ai_purpose_jitai_from_natural_language
    AiPurpose.JITAI_PROPOSAL_WORDING -> R.string.connections_ai_purpose_jitai_proposal_wording
    AiPurpose.INTERVENTION_TEXT -> R.string.connections_ai_purpose_intervention_text
}

@StringRes
internal fun categoryTitle(category: AiSharingCategory): Int = when (category) {
    AiSharingCategory.SCREEN_TIME_TOTALS -> R.string.connections_ai_category_screen_time_totals
    AiSharingCategory.APP_IDENTITY -> R.string.connections_ai_category_app_identity
    AiSharingCategory.NOTIFICATION_COUNTS -> R.string.connections_ai_category_notification_counts
    AiSharingCategory.NOTIFICATION_TEXT -> R.string.connections_ai_category_notification_text
    AiSharingCategory.CALENDAR_BUSY -> R.string.connections_ai_category_calendar_busy
    AiSharingCategory.CALENDAR_TEXT -> R.string.connections_ai_category_calendar_text
    AiSharingCategory.LOCATION_CLASS -> R.string.connections_ai_category_location_class
    AiSharingCategory.ACTIVITY -> R.string.connections_ai_category_activity
    AiSharingCategory.STEPS -> R.string.connections_ai_category_steps
    AiSharingCategory.SLEEP -> R.string.connections_ai_category_sleep
    AiSharingCategory.HEART -> R.string.connections_ai_category_heart
    AiSharingCategory.BODY -> R.string.connections_ai_category_body
    AiSharingCategory.USER_TEXT -> R.string.connections_ai_category_user_text
    AiSharingCategory.GOALS -> R.string.connections_ai_category_goals
    AiSharingCategory.SELF_REPORTS -> R.string.connections_ai_category_self_reports
    AiSharingCategory.DEVICE_STATE -> R.string.connections_ai_category_device_state
    AiSharingCategory.INTERVENTION_HISTORY -> R.string.connections_ai_category_intervention_history
    AiSharingCategory.SETTINGS -> R.string.connections_ai_category_settings
}

/** What a category includes, with an example. */
@StringRes
internal fun categoryBody(category: AiSharingCategory): Int = when (category) {
    AiSharingCategory.SCREEN_TIME_TOTALS -> R.string.connections_ai_category_screen_time_totals_body
    AiSharingCategory.APP_IDENTITY -> R.string.connections_ai_category_app_identity_body
    AiSharingCategory.NOTIFICATION_COUNTS -> R.string.connections_ai_category_notification_counts_body
    AiSharingCategory.NOTIFICATION_TEXT -> R.string.connections_ai_category_notification_text_body
    AiSharingCategory.CALENDAR_BUSY -> R.string.connections_ai_category_calendar_busy_body
    AiSharingCategory.CALENDAR_TEXT -> R.string.connections_ai_category_calendar_text_body
    AiSharingCategory.LOCATION_CLASS -> R.string.connections_ai_category_location_class_body
    AiSharingCategory.ACTIVITY -> R.string.connections_ai_category_activity_body
    AiSharingCategory.STEPS -> R.string.connections_ai_category_steps_body
    AiSharingCategory.SLEEP -> R.string.connections_ai_category_sleep_body
    AiSharingCategory.HEART -> R.string.connections_ai_category_heart_body
    AiSharingCategory.BODY -> R.string.connections_ai_category_body_body
    AiSharingCategory.USER_TEXT -> R.string.connections_ai_category_user_text_body
    AiSharingCategory.GOALS -> R.string.connections_ai_category_goals_body
    AiSharingCategory.SELF_REPORTS -> R.string.connections_ai_category_self_reports_body
    AiSharingCategory.DEVICE_STATE -> R.string.connections_ai_category_device_state_body
    AiSharingCategory.INTERVENTION_HISTORY -> R.string.connections_ai_category_intervention_history_body
    AiSharingCategory.SETTINGS -> R.string.connections_ai_category_settings_body
}

@StringRes
internal fun capabilityLabel(capability: AiCapability): Int = when (capability) {
    AiCapability.TEXT_REASONING -> R.string.connections_chatgpt_capability_text_reasoning
    AiCapability.STRUCTURED_OUTPUT -> R.string.connections_chatgpt_capability_structured_output
    AiCapability.IMAGE_GENERATION -> R.string.connections_chatgpt_capability_image_generation
    AiCapability.VOICE_GENERATION -> R.string.connections_chatgpt_capability_voice_generation
    AiCapability.VIDEO_GENERATION -> R.string.connections_chatgpt_capability_video_generation
    AiCapability.BACKGROUND_INFERENCE -> R.string.connections_chatgpt_capability_background_inference
    AiCapability.IMAGE_INPUT -> R.string.connections_chatgpt_capability_image_input
}

@StringRes
internal fun supportLabel(support: CapabilitySupport): Int = when (support) {
    CapabilitySupport.SUPPORTED -> R.string.connections_chatgpt_support_supported
    CapabilitySupport.PROMPTED_JSON -> R.string.connections_chatgpt_support_prompted_json
    CapabilitySupport.LOCAL -> R.string.connections_chatgpt_support_local
    CapabilitySupport.USER_BUDGETED -> R.string.connections_chatgpt_support_user_budgeted
    CapabilitySupport.UNSUPPORTED -> R.string.connections_chatgpt_support_unsupported
}

@StringRes
internal fun outcomeLabel(outcome: AiRequestOutcome): Int = when (outcome) {
    AiRequestOutcome.IN_FLIGHT -> R.string.connections_ai_outcome_in_flight
    AiRequestOutcome.SENT -> R.string.connections_ai_outcome_sent
    AiRequestOutcome.DENIED -> R.string.connections_ai_outcome_denied
    AiRequestOutcome.NOT_SENT -> R.string.connections_ai_outcome_not_sent
    AiRequestOutcome.CANCELLED -> R.string.connections_ai_outcome_cancelled
    AiRequestOutcome.FAILED -> R.string.connections_ai_outcome_failed
}

/** Category titles joined as a list, or "None". */
@Composable
internal fun categoryList(categories: Collection<AiSharingCategory>): String {
    if (categories.isEmpty()) return stringResource(R.string.connections_none)
    val names = categories.sortedBy { it.ordinal }.map { stringResource(categoryTitle(it)) }
    return names.joinToString(stringResource(R.string.connections_list_separator))
}

/** Purpose labels joined as a list. */
@Composable
internal fun purposeList(purposes: Collection<AiPurpose>): String {
    val names = purposes.distinct().map { stringResource(purposeLabel(it)) }
    return names.joinToString(stringResource(R.string.connections_list_separator))
}
