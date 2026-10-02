package dev.agentle.connectors.api

import dev.agentle.core.model.CapabilityCategory
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PlannedStatus
import kotlinx.serialization.json.Json

/**
 * The machine-readable capability registry (spec §5). Source of truth: docs/research/capabilities.json, copied as a
 * resource of this module. Ids are stable; connectors and the Permission Center refer to them.
 *
 * Construction validates the ids: each must be unique and snake_case (`^[a-z][a-z0-9]*(_[a-z0-9]+)*$`), because they
 * are persisted (permission snapshots, rules) and used as keys across modules.
 */
public class CapabilityRegistry(public val all: List<DataCapability>) {
    private val byId: Map<String, DataCapability> = all.associateBy { it.id }
    private val categories: Map<CapabilityCategory, List<DataCapability>> = all.groupBy { it.category }

    init {
        val invalid = all.map { it.id }.filterNot { ID_PATTERN.matches(it) }
        require(invalid.isEmpty()) { "Capability ids must be snake_case: $invalid" }
        val duplicates = all.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate capability ids: $duplicates" }
    }

    /** Every id, in registry order. */
    public val ids: List<String> get() = all.map { it.id }

    public operator fun get(id: String): DataCapability? = byId[id]

    public operator fun contains(id: String): Boolean = id in byId

    public fun require(id: String): DataCapability = byId[id] ?: error("Unknown capability '$id'")

    public fun byCategory(): Map<CapabilityCategory, List<DataCapability>> = categories

    /** Capabilities of one Permission Center group, in registry order; empty if the group has none. */
    public fun byCategory(category: CapabilityCategory): List<DataCapability> = categories[category].orEmpty()

    /** Capabilities with the given planned status, in registry order. */
    public fun byStatus(status: PlannedStatus): List<DataCapability> = all.filter { it.plannedStatus == status }

    public fun implemented(includeDebugOnly: Boolean): List<DataCapability> = all.filter {
        it.plannedStatus == PlannedStatus.IMPLEMENT || (includeDebugOnly && it.plannedStatus == PlannedStatus.IMPLEMENT_DEBUG_ONLY)
    }

    public companion object {
        /** Capability id syntax: lower snake_case, starting with a letter. */
        public val ID_PATTERN: Regex = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

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
