package dev.agentle.app.wiring

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.agentle.core.model.PersonalEvent
import dev.agentle.data.events.EventRepository
import dev.agentle.data.events.TimelineKey
import dev.agentle.feature.hub.port.DayCoverage
import dev.agentle.feature.hub.port.TimelineFilter
import dev.agentle.feature.hub.port.TimelinePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The Timeline over the stored events: keyset pages from [EventRepository.timeline], bounded by the instant the screen
 * opened. Type filters go to SQL; source and date filters are applied to each page (a page can come back short).
 * Day coverage is not wired yet: the map stays empty, which the screen shows as "no coverage record".
 */
internal class RoomTimelinePort @Inject constructor(private val events: EventRepository) : TimelinePort {
    override fun events(filter: TimelineFilter, upperBound: Instant): Flow<PagingData<PersonalEvent>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            TimelinePagingSource(events, filter, upperBound)
        }.flow

    override fun dayCoverage(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DayCoverage>> = flowOf(emptyMap())

    override val sources: Flow<List<String>> =
        events.changes().map { events.countsPerSource().filter { stream -> stream.rows > 0 }.map { stream -> stream.name }.sorted() }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

private class TimelinePagingSource(
    private val events: EventRepository,
    private val filter: TimelineFilter,
    private val upperBound: Instant,
) : PagingSource<TimelineKey, PersonalEvent>() {
    override suspend fun load(params: LoadParams<TimelineKey>): LoadResult<TimelineKey, PersonalEvent> = try {
        val types = filter.eventType?.let { setOf(it) }
        val page = events.timeline(upperBound, params.loadSize, params.key, types)
        val rows = page.events.map { it.event }.filter { event ->
            (filter.source == null || event.source.value == filter.source) &&
                (filter.date == null || event.localDate() == filter.date)
        }
        LoadResult.Page(rows, prevKey = null, nextKey = page.next)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<TimelineKey, PersonalEvent>): TimelineKey? = null
}

private fun PersonalEvent.localDate(): LocalDate? = try {
    startTime.toLocalDateTime(TimeZone.of(zoneId)).date
} catch (_: IllegalTimeZoneException) {
    null
}
