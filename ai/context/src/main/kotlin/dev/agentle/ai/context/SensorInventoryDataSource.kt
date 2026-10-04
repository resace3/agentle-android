package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.connectors.api.sensors.DeviceSensor
import dev.agentle.connectors.api.sensors.SensorCatalog
import dev.agentle.connectors.api.sensors.SensorGroup
import dev.agentle.connectors.api.sensors.SensorKind
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.SourceFamily

/** Lists the phone's sensors (the app implements it over the sensor gateway). It never returns readings. */
public interface SensorInventoryLoader {
    /** Every sensor the phone lists to apps. */
    public suspend fun sensors(): List<DeviceSensor>

    /** The [DeviceSensor.id]s whose readings Agentle stores on the phone. */
    public suspend fun recordedIds(): Set<String>
}

/**
 * The [AiContextDataSource] for the sensor inventory of chat questions: how many sensors the phone has, the type of each
 * (one `sensors.<group>` code per distinct type) and which types Agentle records. Only closed type codes and counts are
 * sent: vendor-written names, vendors, ranges and readings never are. It needs [AiDataCategory.DEVICE_STATE].
 */
public class SensorInventoryDataSource(private val loader: SensorInventoryLoader) : AiContextDataSource {
    override suspend fun aggregates(query: AiDataQuery): Outcome<List<AggregateFact>> {
        val allowed = query.purpose == AiPurpose.GENERAL_QUESTION && SourceFamily.ON_DEVICE in query.sourceFamilies &&
            AiDataCategory.DEVICE_STATE in query.categories && ItemKind.CODE in query.kinds && ItemKind.QUANTITY in query.kinds
        if (!allowed) return Outcome.Success(emptyList())
        val sensors = loader.sensors()
        if (sensors.isEmpty()) return Outcome.Success(emptyList())
        val recorded = loader.recordedIds()
        val kinds = sensors.map { it.kind }
        val recordedKinds = sensors.filter { it.id in recorded }.map { it.kind }
        val facts = buildList {
            add(count("sensors.count", sensors.size))
            add(count("sensors.type_count", kinds.distinctBy { it.type }.size))
            add(count("sensors.recorded_count", recordedKinds.size))
            recordedKinds.distinctBy { it.type }.forEach { add(code("sensors.recorded_type", typeCode(it))) }
            kinds.groupBy { it.type }.values.sortedByDescending { it.size }.forEach { same ->
                add(code(groupField(same.first().group), typeCode(same.first())))
            }
        }.filter { query.fields == null || it.field in query.fields }
        return Outcome.Success(facts)
    }

    override suspend fun appUsage(query: AiDataQuery): Outcome<List<AppUsageFact>> = Outcome.Success(emptyList())

    override suspend fun userTexts(query: AiDataQuery): Outcome<List<UserTextFact>> = Outcome.Success(emptyList())

    override suspend fun rawEvents(query: AiDataQuery): Outcome<List<RawEventFact>> = Outcome.Success(emptyList())

    private fun count(field: String, value: Int) = AggregateFact(field, AggregateValue.Quantity(value.toDouble(), COUNT), LINEAGE)

    private fun code(field: String, value: String) = AggregateFact(field, AggregateValue.Code(value), LINEAGE)

    public companion object {
        private const val COUNT = "count"
        private val LINEAGE = DataLineage(setOf(AiDataCategory.DEVICE_STATE), setOf(SourceFamily.ON_DEVICE))

        /** The code of a type that is not in [SensorCatalog.KINDS] (a vendor's own sensor); its name is never sent. */
        public const val VENDOR: String = "VENDOR"

        /** `android.sensor.step_counter` -> `STEP_COUNTER`; every type outside the catalog is [VENDOR]. */
        public fun typeCode(kind: SensorKind): String =
            if (SensorCatalog.KINDS.any { it.type == kind.type }) kind.key.removePrefix("android.sensor.").uppercase() else VENDOR

        /** The field that lists the types of [group]: `sensors.motion`, `sensors.environment` and so on. */
        public fun groupField(group: SensorGroup): String = "sensors.${group.name.lowercase()}"

        /** The closed vocabulary of the type codes of [group]. */
        public fun typeCodes(group: SensorGroup): Set<String> {
            val codes = SensorCatalog.KINDS.filter { it.group == group }.mapTo(LinkedHashSet(), ::typeCode)
            if (group == SensorGroup.OTHER) codes += VENDOR
            return codes
        }
    }
}

/** Asks each of [sources] in turn and joins their facts in order; the first failure fails the request. */
public class CompositeAiContextDataSource(private val sources: List<AiContextDataSource>) : AiContextDataSource {
    override suspend fun aggregates(query: AiDataQuery): Outcome<List<AggregateFact>> = join { source -> source.aggregates(query) }

    override suspend fun appUsage(query: AiDataQuery): Outcome<List<AppUsageFact>> = join { source -> source.appUsage(query) }

    override suspend fun userTexts(query: AiDataQuery): Outcome<List<UserTextFact>> = join { source -> source.userTexts(query) }

    override suspend fun rawEvents(query: AiDataQuery): Outcome<List<RawEventFact>> = join { source -> source.rawEvents(query) }

    private suspend fun <T> join(ask: suspend (AiContextDataSource) -> Outcome<List<T>>): Outcome<List<T>> {
        val all = mutableListOf<T>()
        for (source in sources) {
            when (val outcome = ask(source)) {
                is Outcome.Success -> all += outcome.value
                is Outcome.Failure -> return outcome
            }
        }
        return Outcome.Success(all)
    }
}
