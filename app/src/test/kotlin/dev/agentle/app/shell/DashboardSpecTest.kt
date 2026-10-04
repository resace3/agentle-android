package dev.agentle.app.shell

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.validation.ChatReplySchema
import org.junit.Test

class DashboardSpecTest {
    @Test
    fun `every metric ChatGPT may pick is one the app can chart`() {
        assertThat(DashboardMetric.entries.map { it.name }).containsExactlyElementsIn(ChatReplySchema.METRICS.keys)
    }

    @Test
    fun `a model's dashboard becomes a spec only when it is usable`() {
        val spec = DashboardSpec.validated("id", "  Sleep  ", listOf("SLEEP_MINUTES", "SLEEP_MINUTES", "CAFFEINE"), 14)

        assertThat(spec).isEqualTo(DashboardSpec("id", "Sleep", listOf(DashboardMetric.SLEEP_MINUTES), 14))
        assertThat(DashboardSpec.validated("id", " ", listOf("STEPS"), 7)).isNull()
        assertThat(DashboardSpec.validated("id", "Steps", listOf("CAFFEINE"), 7)).isNull()
        assertThat(DashboardSpec.validated("id", "Steps", listOf("STEPS"), DashboardSpec.MAX_DAYS + 1)).isNull()
    }
}
