package dev.agentle.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LineageTest {
    @Test
    fun `connectors map to their source family`() {
        assertThat(DataSourceId("googlehealth.steps").family).isEqualTo(SourceFamily.GH_API)
        assertThat(DataSourceId("healthconnect.sleep").family).isEqualTo(SourceFamily.HEALTH_CONNECT)
        assertThat(DataSourceId("android.usage").family).isEqualTo(SourceFamily.ON_DEVICE)
        assertThat(DataSourceId("user.log").family).isEqualTo(SourceFamily.ON_DEVICE)
        assertThat(DataSourceId("agentle.jitai").family).isEqualTo(SourceFamily.ON_DEVICE)
    }

    @Test
    fun `lineage unions categories and families`() {
        val lineage = Lineage.union(
            listOf(
                Lineage.of(setOf(DataCategory.SLEEP), listOf(DataSourceId("googlehealth.sleep"))),
                Lineage.of(setOf(DataCategory.APP_USAGE), listOf(DataSourceId("android.usage"))),
            ),
        )
        assertThat(lineage.sourceFamilies).containsExactly(SourceFamily.GH_API, SourceFamily.ON_DEVICE)
        assertThat(lineage.categories).containsExactly(DataCategory.SLEEP, DataCategory.APP_USAGE)
        assertThat(Lineage.NONE.isEmpty).isTrue()
        assertThat(Lineage.UNKNOWN.categories).containsExactlyElementsIn(DataCategory.entries)
        assertThat(Lineage.UNKNOWN.sourceFamilies).containsExactlyElementsIn(SourceFamily.entries)
    }
}
