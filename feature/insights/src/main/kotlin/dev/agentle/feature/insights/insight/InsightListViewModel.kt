package dev.agentle.feature.insights.insight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.model.EvidenceStrength
import dev.agentle.core.model.Insight
import dev.agentle.core.model.InsightOrigin
import dev.agentle.core.model.SupportItem
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.port.InsightFeed
import dev.agentle.feature.insights.port.InsightsPort
import dev.agentle.feature.insights.port.MissingInput
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/** The insight list (spec §22 "Insights"). */
@HiltViewModel
internal class InsightListViewModel @Inject constructor(private val port: InsightsPort) : ViewModel() {
    private val reload = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<InsightListUiState> = reload
        .flatMapLatest {
            port.insightFeed()
                .map<InsightFeed, InsightListUiState> { feed -> feed.toUiState() }
                .onStart { emit(InsightListUiState.Loading) }
                .catch { emit(InsightListUiState.Error(LoadError.of(it))) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), InsightListUiState.Loading)

    /** Collects the feed again after an error. */
    fun retry() {
        reload.update { it + 1 }
    }

    private fun InsightFeed.toUiState(): InsightListUiState = InsightListUiState.Content(
        insights = insights.map { it.toCard(zone, it.id in interpreted) }.toImmutableList(),
        missingInputs = missingInputs.toImmutableList(),
        computing = computing,
    )

    companion object {
        const val STOP_TIMEOUT_MILLIS: Long = 5_000
    }
}

internal sealed interface InsightListUiState {
    data object Loading : InsightListUiState

    /**
     * Loaded. Empty when [insights] is empty; partial when [missingInputs] is not empty (some sources are missing and
     * the screen names them with their fix).
     */
    data class Content(
        val insights: ImmutableList<InsightCard>,
        val missingInputs: ImmutableList<MissingInput> = persistentListOf(),
        val computing: Boolean = false,
    ) : InsightListUiState {
        val isEmpty: Boolean get() = insights.isEmpty()
        val isPartial: Boolean get() = missingInputs.isNotEmpty()
    }

    data class Error(val error: LoadError) : InsightListUiState
}

/**
 * One insight card. [periodStart] and [periodEnd] are the first and last local days of the insight's period in the
 * user's zone (the stored end is exclusive).
 */
internal data class InsightCard(
    val id: String,
    val title: String,
    val finding: String,
    val supportingData: ImmutableList<SupportItem>,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val strength: EvidenceStrength,
    val origin: InsightOrigin,
    val hasInterpretation: Boolean,
)

internal fun Insight.toCard(zone: TimeZone, hasInterpretation: Boolean): InsightCard = InsightCard(
    id = id,
    title = title,
    finding = finding,
    supportingData = supportingData.toImmutableList(),
    periodStart = periodStart.localDate(zone),
    periodEnd = lastDay(periodStart, periodEnd, zone),
    strength = strength,
    origin = origin,
    hasInterpretation = hasInterpretation,
)

internal fun Instant.localDate(zone: TimeZone): LocalDate = toLocalDateTime(zone).date

/** The last local day of the half-open period `[start, end)`. */
internal fun lastDay(start: Instant, end: Instant, zone: TimeZone): LocalDate =
    (if (end > start) end - 1.nanoseconds else start).localDate(zone)
