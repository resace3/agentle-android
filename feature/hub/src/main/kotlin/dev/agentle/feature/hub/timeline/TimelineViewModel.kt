package dev.agentle.feature.hub.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.hub.port.DayCoverage
import dev.agentle.feature.hub.port.HubClockPort
import dev.agentle.feature.hub.port.TimelineFilter
import dev.agentle.feature.hub.port.TimelinePort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** One row of the Timeline list. */
internal sealed interface TimelineRow {
    /** A local day; [gapAfter] is the range of empty days between this day and the newer day above it. */
    data class DayHeader(val date: LocalDate, val gapAfter: ClosedRange<LocalDate>? = null) : TimelineRow

    data class Event(val event: PersonalEvent, val date: LocalDate) : TimelineRow
}

/** Groups newest-first events by local day in [zone] and labels the empty days between them. */
internal fun PagingData<PersonalEvent>.toRows(zone: TimeZone): PagingData<TimelineRow> =
    map { TimelineRow.Event(it, it.startTime.toLocalDateTime(zone).date) }
        .insertSeparators { before: TimelineRow.Event?, after: TimelineRow.Event? ->
            when {
                after == null -> null

                before == null -> TimelineRow.DayHeader(after.date)

                before.date == after.date -> null

                else -> {
                    val from = after.date.plus(DatePeriod(days = 1))
                    val to = before.date.minus(DatePeriod(days = 1))
                    TimelineRow.DayHeader(after.date, gapAfter = if (from <= to) from..to else null)
                }
            }
        }

private const val COVERAGE_DAYS = 60

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = TimelineViewModel.Factory::class)
internal class TimelineViewModel @AssistedInject constructor(
    @Assisted route: AppRoute.Timeline,
    port: TimelinePort,
    clockPort: HubClockPort,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(route: AppRoute.Timeline): TimelineViewModel
    }

    val clock = clockPort.clock
    val zone: TimeZone = clock.zone()

    /** Captured once: rows ingested while the user scrolls do not shift pages (ARCHITECTURE §16). */
    private val upperBound = clock.now()
    val today: LocalDate = clock.today()

    private val filterState = MutableStateFlow(
        TimelineFilter(
            source = route.source,
            eventType = route.eventType?.let { name -> EventType.entries.firstOrNull { it.name == name } },
            date = route.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        ),
    )
    val filter: StateFlow<TimelineFilter> = filterState.asStateFlow()

    private val selectedState = MutableStateFlow<PersonalEvent?>(null)
    val selected: StateFlow<PersonalEvent?> = selectedState.asStateFlow()

    val rows: Flow<PagingData<TimelineRow>> = filterState
        .flatMapLatest { port.events(it, upperBound) }
        .cachedIn(viewModelScope)
        .map { it.toRows(zone) }

    val coverage: StateFlow<Map<LocalDate, DayCoverage>> =
        port.dayCoverage(today.minus(DatePeriod(days = COVERAGE_DAYS)), today)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val sources: StateFlow<List<String>> = port.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSource(source: String?) = filterState.update { it.copy(source = source) }

    fun setEventType(type: EventType?) = filterState.update { it.copy(eventType = type) }

    fun setDate(date: LocalDate?) = filterState.update { it.copy(date = date) }

    fun clearFilters() = filterState.update { TimelineFilter() }

    fun select(event: PersonalEvent?) {
        selectedState.value = event
    }

    private fun MutableStateFlow<TimelineFilter>.update(change: (TimelineFilter) -> TimelineFilter) {
        value = change(value)
    }
}
