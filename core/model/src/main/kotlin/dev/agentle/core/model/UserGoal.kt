package dev.agentle.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
public data class UserGoal(
    val id: String,
    val text: String,
    /** Feature id from the feature catalog this goal tracks, if any (e.g. "sleep_duration_minutes"). */
    val metric: String? = null,
    val target: Double? = null,
    val createdAt: Instant,
    val active: Boolean = true,
)
