package dev.agentle.connectors.api

import dev.agentle.core.model.CapabilityCategory
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PlannedStatus
import kotlinx.serialization.json.Json

/**
 * The machine-readable capability registry (spec §5). Source of truth: docs/research/capabilities.json, copied as a
 * resource of this module. Ids are stable; connectors and the Permission Center refer to them.
 */
public class CapabilityRegistry(public val all: List<DataCapability>) {
    private val byId: Map<String, DataCapability> = all.associateBy { it.id }

    init {
        require(byId.size == all.size) { "Duplicate capability ids" }
    }

    public operator fun get(id: String): DataCapability? = byId[id]

    public fun require(id: String): DataCapability = byId[id] ?: error("Unknown capability '$id'")

    public fun byCategory(): Map<CapabilityCategory, List<DataCapability>> = all.groupBy { it.category }

    public fun implemented(includeDebugOnly: Boolean): List<DataCapability> = all.filter {
        it.plannedStatus == PlannedStatus.IMPLEMENT || (includeDebugOnly && it.plannedStatus == PlannedStatus.IMPLEMENT_DEBUG_ONLY)
    }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the bundled registry. */
        public fun load(): CapabilityRegistry {
            val text = CapabilityRegistry::class.java.getResourceAsStream("capabilities.json")
                ?.bufferedReader()?.use { it.readText() }
                ?: error("capabilities.json resource missing")
            return parse(text)
        }

        public fun parse(text: String): CapabilityRegistry {
            // The research file uses "None" for "no special access"; normalize to null.
            val caps = json.decodeFromString<List<DataCapability>>(text).map { cap ->
                if (cap.specialAccess.equals("none", ignoreCase = true)) cap.copy(specialAccess = null) else cap
            }
            return CapabilityRegistry(caps)
        }
    }
}
