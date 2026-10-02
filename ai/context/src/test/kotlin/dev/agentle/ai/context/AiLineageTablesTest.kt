package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.EventType
import dev.agentle.core.model.SourceFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File

class AiLineageTablesTest {
    private val capabilityIds: Set<String> by lazy {
        val json = File(repoRoot(), "docs/research/capabilities.json").readText()
        Json.parseToJsonElement(json).jsonArray.mapTo(LinkedHashSet()) { it.jsonObject.getValue("id").jsonPrimitive.content }
    }

    @Test
    fun `every real-time feature has a lineage and the table has no other ids`() {
        assertThat(AiLineageTables.FEATURES.keys).containsExactlyElementsIn(RealtimeFeatureCatalog.ids)
    }

    @Test
    fun `every capability id and every connector named as a source has a lineage`() {
        assertThat(capabilityIds).hasSize(53)
        assertThat(AiLineageTables.SOURCES.keys).containsExactlyElementsIn(capabilityIds + ConnectorIds.GOOGLE_HEALTH)
        val featureSources = RealtimeFeatureCatalog.all.flatMap { it.sources }.toSet()
        assertThat(AiLineageTables.SOURCES.keys).containsAtLeastElementsIn(featureSources)
    }

    @Test
    fun `a feature's lineage lies within the lineage of its sources`() {
        RealtimeFeatureCatalog.all.filter { it.sources.isNotEmpty() }.forEach { feature ->
            val lineage = AiLineageTables.forFeature(feature.id)
            val sources = DataLineage.union(feature.sources.map(AiLineageTables::forSource))
            assertThat(lineage.sources).isEqualTo(sources.sources)
            assertThat(sources.categories).containsAtLeastElementsIn(lineage.categories)
        }
    }

    @Test
    fun `feature categories reconcile with the storage categories`() {
        RealtimeFeatureCatalog.all.forEach { feature ->
            val lineage = AiLineageTables.forFeature(feature.id)
            val storage = lineage.categories.map { it.storageCategory }
            if (feature.category == null) {
                assertThat(lineage.categories.all { it == AiDataCategory.SETTINGS }).isTrue()
            } else {
                assertThat(storage).contains(feature.category)
            }
        }
    }

    @Test
    fun `every event type is mapped and reconciles with its storage category`() {
        assertThat(AiLineageTables.EVENT_CATEGORIES.keys).containsExactlyElementsIn(EventType.entries)
        val unknown = setOf(
            EventType.CALL_EVENT,
            EventType.MEDIA_CREATED,
            EventType.INSIGHT_CREATED,
            EventType.IMAGE_GENERATED,
            EventType.VOICE_GENERATED,
            EventType.VIDEO_GENERATED,
        )
        EventType.entries.forEach { type ->
            val lineage = AiLineageTables.forEvent(type, ConnectorIds.ANDROID)
            if (type in unknown) {
                assertThat(lineage).isEqualTo(DataLineage.UNKNOWN)
            } else {
                assertThat(lineage.sources).containsExactly(SourceFamily.ON_DEVICE)
                assertThat(lineage.categories.map { it.storageCategory }).contains(type.category)
                assertThat(lineage.categories.none { it.thirdPartyText }).isTrue()
            }
        }
    }

    @Test
    fun `source families come from the connector and unknown ids are unknown lineage`() {
        assertThat(AiLineageTables.forEvent(EventType.STEP_SAMPLE, ConnectorIds.GOOGLE_HEALTH).sources).containsExactly(SourceFamily.GH_API)
        assertThat(AiLineageTables.forEvent(EventType.SLEEP_SESSION, ConnectorIds.HEALTH_CONNECT).sources)
            .containsExactly(SourceFamily.HEALTH_CONNECT)
        assertThat(AiLineageTables.forEvent(EventType.STEP_SAMPLE, "fitbit-legacy")).isEqualTo(DataLineage.UNKNOWN)
        assertThat(AiLineageTables.forFeature("mood_now")).isEqualTo(DataLineage.UNKNOWN)
        assertThat(AiLineageTables.forSource("smart_fridge")).isEqualTo(DataLineage.UNKNOWN)
        assertThat(AiLineageTables.forFeature("local_time")).isEqualTo(DataLineage.NONE)
    }

    @Test
    fun `capabilities that expose third-party names or content are unknown or third-party text`() {
        listOf(
            "accessibility_event_stream", "wifi_network_identity", "bluetooth_connected_devices", "bluetooth_nearby_scan",
            "media_sessions_now_playing", "call_log_metadata", "sms_metadata", "contacts_metadata", "contacts_picker_selection",
        ).forEach { assertThat(AiLineageTables.forSource(it)).isEqualTo(DataLineage.UNKNOWN) }
        assertThat(AiLineageTables.forSource("notification_content").categories).containsExactly(AiDataCategory.NOTIFICATION_TEXT)
        assertThat(AiLineageTables.forSource("calendar_events").categories).contains(AiDataCategory.CALENDAR_TEXT)
        assertThat(AiLineageTables.forSource(ConnectorIds.GOOGLE_HEALTH).sources).containsExactly(SourceFamily.GH_API)
        assertThat(AiLineageTables.forSource("health_connect_records").sources).containsExactly(SourceFamily.HEALTH_CONNECT)
    }

    @Test
    fun `derived values carry the union of their inputs`() {
        assertThat(AiLineageTables.forFeature("app_category_minutes_since").categories)
            .containsExactly(AiDataCategory.APP_IDENTITY, AiDataCategory.SCREEN_TIME_TOTALS)
        assertThat(AiLineageTables.forFeature("steps_today").sources).containsExactlyElementsIn(SourceFamily.entries)
    }

    @Test
    fun `the field registry is closed, consistent and holds no third-party text`() {
        val pattern = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$")
        AiFieldRegistry.all.forEach { field ->
            assertThat(field.code).matches(pattern.toPattern())
            assertThat(field.sources).isNotEmpty()
            assertThat(field.categories.none { it.thirdPartyText }).isTrue()
            assertThat(field.unit?.let { EnvelopeGate.UNIT.matches(it) } ?: true).isTrue()
            if (field.kind == ItemKind.CODE && field.codes != null) {
                assertThat(field.codes).isNotEmpty()
                field.codes.forEach { assertThat(EnvelopeGate.CODE.matches(it)).isTrue() }
            }
            if (field.categories.isNotEmpty()) assertThat(field.primary).isIn(field.categories)
        }
        val open = AiFieldRegistry.all.filter { it.kind == ItemKind.CODE && it.codes == null }.map { it.code }
        assertThat(open).isEmpty() // every CODE field has a closed vocabulary (fail closed)
        assertThat(AiFieldRegistry["sleep.minutes_avg"]?.sources).containsExactly(SourceFamily.GH_API, SourceFamily.HEALTH_CONNECT)
        assertThat(AiFieldRegistry["no.such_field"]).isNull()
        val covered = AiFieldRegistry.all.flatMap { it.categories }.toSet()
        assertThat(covered).containsExactlyElementsIn(AiDataCategory.entries.filterNot { it.thirdPartyText })
    }

    @Test
    fun `templates list only aggregate fields of the purpose`() {
        val fields = AiFieldRegistry.fieldsFor(
            AiPurpose.JITAI_PROPOSAL_WORDING,
            PurposePolicy.spec(AiPurpose.JITAI_PROPOSAL_WORDING).categories,
            setOf(ItemKind.QUANTITY, ItemKind.CODE),
        ).map { it.code }
        assertThat(fields).containsAtLeast("evidence.tier", "evidence.count", "steps.daily_avg")
        assertThat(fields).containsNoneOf("pattern.kind", "user.note", "apps.usage", AiFieldRegistry.EVENT_FIELD)
    }

    @Test
    fun `storage categories map back to the AI categories whose grants deletion revokes`() {
        assertThat(AiDataCategory.forStorageCategory(DataCategory.ACTIVITY)).containsExactly(AiDataCategory.ACTIVITY, AiDataCategory.STEPS)
        assertThat(AiDataCategory.forStorageCategory(DataCategory.USER_LOGS))
            .containsExactly(AiDataCategory.USER_TEXT, AiDataCategory.SELF_REPORTS)
    }

    companion object {
        /** The repository root: the nearest directory upwards that holds settings.gradle.kts. */
        fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
            return checkNotNull(dir) { "settings.gradle.kts not found above the working directory" }
        }
    }
}
