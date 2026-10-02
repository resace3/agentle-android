package dev.agentle.feature.connections.aisharing

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.ui.Tone
import dev.agentle.feature.connections.ui.categoryTitle
import dev.agentle.feature.connections.ui.errorText
import dev.agentle.feature.connections.ui.purposeList

/** The text and tone of an AI Data Sharing message. */
@Composable
internal fun aiSharingNoticeText(notice: AiSharingNotice): Pair<String, Tone> = when (notice) {
    is AiSharingNotice.TurnedOn ->
        stringResource(R.string.connections_ai_notice_on, stringResource(categoryTitle(notice.category))) to Tone.POSITIVE

    is AiSharingNotice.TurnedOff -> {
        val title = stringResource(categoryTitle(notice.category))
        if (notice.cancelled.isEmpty()) {
            stringResource(R.string.connections_ai_notice_off, title) to Tone.NEUTRAL
        } else {
            pluralStringResource(
                R.plurals.connections_ai_notice_off_cancelled,
                notice.cancelled.size,
                title,
                notice.cancelled.size,
                purposeList(notice.cancelled),
            ) to Tone.NEUTRAL
        }
    }

    is AiSharingNotice.ChangeFailed -> stringResource(
        R.string.connections_ai_notice_change_failed,
        stringResource(categoryTitle(notice.category)),
        errorText(notice.error),
    ) to Tone.ERROR
}
