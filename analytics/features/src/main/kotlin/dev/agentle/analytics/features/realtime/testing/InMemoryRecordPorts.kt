package dev.agentle.analytics.features.realtime.testing

import dev.agentle.analytics.features.realtime.CollectorCoveragePort
import dev.agentle.analytics.features.realtime.CoverageInterval
import dev.agentle.analytics.features.realtime.DeliveryRecord
import dev.agentle.analytics.features.realtime.InterventionHistoryPort
import dev.agentle.analytics.features.realtime.JitaiSelector
import dev.agentle.core.common.Outcome
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Test support: `collector_coverage` in memory. A collector without intervals was never healthy. */
public class InMemoryCollectorCoverage(log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    CollectorCoveragePort {
    private val rows = mutableMapOf<String, MutableList<CoverageInterval>>()

    /** Adds a healthy interval of [collectorId]. */
    public fun add(collectorId: String, interval: CoverageInterval) {
        rows.getOrPut(collectorId) { mutableListOf() } += interval
    }

    /** Marks [collectorId] healthy from [from] until now (an open interval). */
    public fun healthySince(collectorId: String, from: Instant) {
        add(collectorId, CoverageInterval(from))
    }

    /** Removes every interval of [collectorId]. */
    public fun clear(collectorId: String) {
        rows.remove(collectorId)
    }

    override suspend fun intervals(collectorId: String, range: ClosedOpenRange): Outcome<List<CoverageInterval>> =
        plain("collectorCoverage.intervals") {
            rows[collectorId].orEmpty().filter { it.from < range.end && (it.to == null || it.to > range.start) }
        }
}

/**
 * Test support: delivery rows in memory, in the order they were recorded ([rows], oldest first). Only `DELIVERED` and
 * `DELIVERY_UNCERTAIN` rows exist here, as the port contract requires.
 */
public class InMemoryInterventionHistory(log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    InterventionHistoryPort {
    public val rows: MutableList<DeliveryRecord> = mutableListOf()

    override suspend fun latest(selector: JitaiSelector, limit: Int): Outcome<List<DeliveryRecord>> =
        plain("history.latest") { rows.filter { selector.matches(it) }.asReversed().take(limit) }

    override suspend fun inEngineDays(selector: JitaiSelector, first: LocalDate, last: LocalDate): Outcome<List<DeliveryRecord>> =
        plain("history.inEngineDays") { rows.filter { selector.matches(it) && it.engineDay >= first && it.engineDay <= last } }
}
