package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.time.ClosedOpenRange
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** [StepFusion]: per minute one source, canonical first (database-sync-02), provisional fills (jitai-correctness-03). */
class StepFusionTest {
    private val t0 = Instant.parse("2026-10-01T14:00:00Z")

    private fun at(minute: Int) = t0 + minute.minutes

    private fun window(from: Int, until: Int) = ClosedOpenRange(at(from), at(until))

    private fun minute(m: Int, count: Long) = StepInterval(at(m), at(m + 1), count)

    @Test
    fun `without sources there is nothing and no coverage`() {
        assertThat(StepFusion.fuse(window(0, 60), emptyList())).isEqualTo(FusedStepSeries(emptyList(), null))
    }

    @Test
    fun `the canonical source owns the minutes it reported and a zero-omitting source fills the rest up to its coverage`() {
        val watch = StepFusion.Source("watch", reportsTrueZeros = true, listOf(minute(0, 120), minute(1, 0), minute(5, 80)), at(10))
        val phone = StepFusion.Source("phone", reportsTrueZeros = false, listOf(minute(1, 90), minute(2, 70)), at(8))

        val fused = StepFusion.fuse(window(0, 10), listOf(watch, phone))

        assertThat(fused.coverageThrough).isEqualTo(at(10))
        assertThat(fused.segments.map { Triple(it.range, it.source, it.provisional) }).containsExactly(
            Triple(window(0, 2), "watch", false),
            Triple(window(2, 5), "phone", false),
            Triple(window(5, 6), "watch", false),
            Triple(window(6, 8), "phone", false),
        ).inOrder()
        // Minute 1 is the watch's true zero: the phone's 90 steps there are never added.
        assertThat(fused.segments[0].intervals).containsExactly(minute(0, 120), minute(1, 0)).inOrder()
        assertThat(fused.segments[1].intervals).containsExactly(minute(2, 70))
        assertThat(StepMath.prorated(fused, window(0, 10)).floor()).isEqualTo(120 + 0 + 70 + 80)
    }

    @Test
    fun `minutes after the canonical coverage that another source fills are provisional (jitai-correctness-03)`() {
        val api = StepFusion.Source("api", reportsTrueZeros = true, listOf(minute(0, 100)), at(3))
        val phone = StepFusion.Source("phone", reportsTrueZeros = false, listOf(minute(2, 30), minute(4, 40)), at(6))

        val fused = StepFusion.fuse(window(0, 6), listOf(api, phone))

        assertThat(fused.coverageThrough).isEqualTo(at(6))
        assertThat(fused.segments.map { Triple(it.range, it.source, it.provisional) }).containsExactly(
            Triple(window(0, 1), "api", false),
            Triple(window(1, 3), "phone", false),
            Triple(window(3, 6), "phone", true),
        ).inOrder()
    }

    @Test
    fun `without canonical coverage every fill is provisional`() {
        val api = StepFusion.Source("api", reportsTrueZeros = true, emptyList(), coverageThrough = null)
        val phone = StepFusion.Source("phone", reportsTrueZeros = false, listOf(minute(0, 10)), at(2))

        val fused = StepFusion.fuse(window(0, 2), listOf(api, phone))

        assertThat(fused.segments.single().provisional).isTrue()
        assertThat(fused.segments.single().range).isEqualTo(window(0, 2))
    }

    @Test
    fun `a source that reports true zeros covers only its reported minutes, whatever its coverage`() {
        val api = StepFusion.Source("api", reportsTrueZeros = true, listOf(minute(1, 5)), at(60))

        val fused = StepFusion.fuse(window(0, 3), listOf(api))

        assertThat(fused.segments.map { it.range }).containsExactly(window(1, 2))
    }

    @Test
    fun `the series spans the whole minutes around an unaligned window, point records cover their minute`() {
        val point = StepInterval(at(3) + 20.seconds, at(3) + 20.seconds, 7)
        val long = StepInterval(at(0) + 30.seconds, at(2) + 30.seconds, 40)
        val api = StepFusion.Source("api", reportsTrueZeros = true, listOf(long, point, minute(9, 1)), at(10))

        val fused = StepFusion.fuse(ClosedOpenRange(at(0) + 45.seconds, at(3) + 30.seconds), listOf(api))

        assertThat(fused.segments.map { it.range }).containsExactly(window(0, 4))
        assertThat(fused.segments.single().intervals).containsExactly(long, point).inOrder()
    }

    @Test
    fun `an interval ending on a minute boundary does not report the next minute`() {
        val api = StepFusion.Source("api", reportsTrueZeros = true, listOf(StepInterval(at(0) + 30.seconds, at(1), 3)), at(5))

        assertThat(StepFusion.fuse(window(0, 5), listOf(api)).segments.map { it.range }).containsExactly(window(0, 1))
    }
}
