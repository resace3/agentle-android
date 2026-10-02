package dev.agentle.feature.hub.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.sensitiveContent
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.ui.component.AgentleScaffold
import dev.agentle.core.ui.component.EmptyState
import dev.agentle.core.ui.component.ErrorState
import dev.agentle.core.ui.component.LoadingState
import dev.agentle.core.ui.component.SecureWindowEffect
import dev.agentle.core.ui.component.StateAction
import dev.agentle.core.ui.format.dateTimeText
import dev.agentle.core.ui.format.dayLength
import dev.agentle.core.ui.format.rememberTimeFormatter
import dev.agentle.core.ui.theme.AgentleSpacing
import dev.agentle.feature.hub.R
import dev.agentle.feature.hub.port.DayCoverage
import dev.agentle.feature.hub.port.TimelineFilter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.hours

private const val MILLIS_PER_DAY = 86_400_000L

@Composable
internal fun TimelineRoute(viewModel: TimelineViewModel, onBack: () -> Unit) {
    SecureWindowEffect()
    val rows = viewModel.rows.collectAsLazyPagingItems()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val coverage by viewModel.coverage.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    TimelineScreen(
        rows = rows,
        filter = filter,
        sources = sources,
        coverage = coverage,
        zone = viewModel.zone,
        selected = selected,
        actions = TimelineActions(
            back = onBack,
            setSource = viewModel::setSource,
            setType = viewModel::setEventType,
            setDate = viewModel::setDate,
            clear = viewModel::clearFilters,
            select = viewModel::select,
        ),
    )
}

internal data class TimelineActions(
    val back: () -> Unit = {},
    val setSource: (String?) -> Unit = {},
    val setType: (EventType?) -> Unit = {},
    val setDate: (LocalDate?) -> Unit = {},
    val clear: () -> Unit = {},
    val select: (PersonalEvent?) -> Unit = {},
)

@Composable
internal fun TimelineScreen(
    rows: LazyPagingItems<TimelineRow>,
    filter: TimelineFilter,
    sources: List<String>,
    coverage: Map<LocalDate, DayCoverage>,
    zone: TimeZone,
    selected: PersonalEvent?,
    actions: TimelineActions,
) {
    AgentleScaffold(title = stringResource(R.string.hub_timeline_title), onBack = actions.back) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Filters(filter, sources, actions)
            val refresh = rows.loadState.refresh
            when {
                refresh is LoadState.Loading && rows.itemCount == 0 -> LoadingState()

                refresh is LoadState.Error -> ErrorState(
                    error = (refresh.error as? AppException)?.error ?: AppError.Unexpected(),
                    onRetry = rows::retry,
                )

                rows.itemCount == 0 && filter != TimelineFilter() -> EmptyState(
                    title = stringResource(R.string.hub_timeline_filtered_empty),
                    action = StateAction(stringResource(R.string.hub_clear_filters), actions.clear),
                )

                rows.itemCount == 0 -> EmptyState(
                    title = stringResource(R.string.hub_timeline_empty),
                    message = stringResource(R.string.hub_timeline_empty_body),
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = AgentleSpacing.l)) {
                    items(rows.itemCount, key = rows.itemKey { it.key() }) { index ->
                        when (val row = rows[index]) {
                            is TimelineRow.DayHeader -> DayHeader(row, coverage[row.date], zone)
                            is TimelineRow.Event -> EventRow(row.event, zone) { actions.select(row.event) }
                            null -> Box(Modifier.heightIn(min = AgentleSpacing.minTouchTarget))
                        }
                    }
                }
            }
        }
    }
    selected?.let { EventSheet(it, zone) { actions.select(null) } }
}

private fun TimelineRow.key(): String = when (this) {
    is TimelineRow.DayHeader -> "d-$date"
    is TimelineRow.Event -> "e-${event.id.value}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Filters(filter: TimelineFilter, sources: List<String>, actions: TimelineActions) {
    var menu by remember { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = AgentleSpacing.screenGutter),
        horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
    ) {
        Box {
            FilterChip(
                selected = filter.source != null,
                onClick = { menu = "source" },
                label = { Text(filter.source ?: stringResource(R.string.hub_filter_source)) },
            )
            DropdownMenu(expanded = menu == "source", onDismissRequest = { menu = null }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.hub_filter_all)) }, onClick = {
                    menu = null
                    actions.setSource(null)
                })
                sources.forEach { source ->
                    DropdownMenuItem(text = { Text(source) }, onClick = {
                        menu = null
                        actions.setSource(source)
                    })
                }
            }
        }
        Box {
            FilterChip(
                selected = filter.eventType != null,
                onClick = { menu = "type" },
                label = { Text(filter.eventType?.label() ?: stringResource(R.string.hub_filter_type)) },
            )
            DropdownMenu(expanded = menu == "type", onDismissRequest = { menu = null }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.hub_filter_all)) }, onClick = {
                    menu = null
                    actions.setType(null)
                })
                EventType.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(type.label()) }, onClick = {
                        menu = null
                        actions.setType(type)
                    })
                }
            }
        }
        FilterChip(
            selected = filter.date != null,
            onClick = { if (filter.date != null) actions.setDate(null) else menu = "date" },
            label = { Text(filter.date?.toString() ?: stringResource(R.string.hub_filter_date)) },
        )
    }
    if (menu == "date") {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { menu = null },
            confirmButton = {
                TextButton(onClick = {
                    menu = null
                    pickerState.selectedDateMillis?.let { actions.setDate(LocalDate.fromEpochDays((it / MILLIS_PER_DAY).toInt())) }
                }) { Text(stringResource(R.string.hub_ok)) }
            },
        ) { DatePicker(state = pickerState) }
    }
}

/** "SCREEN_ON" -> "Screen on". */
internal fun EventType.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun DayHeader(row: TimelineRow.DayHeader, coverage: DayCoverage?, zone: TimeZone) {
    val formatter = rememberTimeFormatter()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AgentleSpacing.screenGutter, vertical = AgentleSpacing.s)) {
        row.gapAfter?.let { gap ->
            Text(
                stringResource(
                    R.string.hub_gap_between,
                    formatter.date(gap.start),
                    formatter.date(gap.endInclusive),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AgentleSpacing.s),
            )
            HorizontalDivider()
        }
        Text(
            formatter.fullDate(row.date),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }.padding(top = AgentleSpacing.s),
        )
        when (dayLength(row.date, zone)) {
            23.hours -> Note(stringResource(R.string.hub_day_23h))
            25.hours -> Note(stringResource(R.string.hub_day_25h))
            else -> Unit
        }
        when (coverage) {
            DayCoverage.PARTIAL -> Note(stringResource(R.string.hub_day_partial))
            DayCoverage.NONE -> Note(stringResource(R.string.hub_day_gap))
            else -> Unit
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EventRow(event: PersonalEvent, zone: TimeZone, onClick: () -> Unit) {
    val formatter = rememberTimeFormatter()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AgentleSpacing.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .sensitiveContent()
            .padding(horizontal = AgentleSpacing.screenGutter, vertical = AgentleSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.m),
    ) {
        Text(formatter.time(event.startTime, zone), style = MaterialTheme.typography.labelLarge)
        Column(modifier = Modifier.weight(1f)) {
            Text(event.type.label(), style = MaterialTheme.typography.bodyLarge)
            Text(event.source.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventSheet(event: PersonalEvent, zone: TimeZone, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(
            modifier = Modifier.sensitiveContent().padding(AgentleSpacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
        ) {
            Text(
                stringResource(R.string.hub_event_detail),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    heading()
                },
            )
            Text(event.type.label(), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.hub_event_time, dateTimeText(event.startTime, zone)))
            event.endTime?.let { Text(stringResource(R.string.hub_event_until, dateTimeText(it, zone))) }
            Text(stringResource(R.string.hub_event_source, event.source.value))
            event.metadata.origin?.let { Text(stringResource(R.string.hub_event_origin, it)) }
            Text(stringResource(R.string.hub_event_collected, dateTimeText(event.metadata.ingestedAt, zone)))
            event.confidence?.let { Text(stringResource(R.string.hub_event_confidence, (it * 100).toInt())) }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget)) {
                Text(stringResource(R.string.hub_close))
            }
        }
    }
}
