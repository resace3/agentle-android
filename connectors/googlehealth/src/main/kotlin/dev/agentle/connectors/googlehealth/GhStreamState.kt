package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.googlehealth.GhJson.instant
import dev.agentle.connectors.googlehealth.GhJson.long
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * The connector's own position in one stream, stored as compact JSON in [dev.agentle.connectors.api.SyncCursor]
 * (opaque to everyone else). Unreadable state reads as "never synced", which only costs a re-read.
 *
 * @property through forward position: everything recorded before it was fetched and committed.
 * @property backfilledFrom backward position of the cold load.
 * @property fetchedAt start of the run that last moved [through]; a run within 15 minutes skips the overlap.
 * @property deepResyncAt when the last 30-day deep re-sync completed.
 * @property deviceLastSync the tracker's last upload seen by that run; it bounds the next overlap re-read.
 */
internal data class GhStreamState(
    val through: Instant? = null,
    val backfilledFrom: Instant? = null,
    val fetchedAt: Instant? = null,
    val deepResyncAt: Instant? = null,
    val deviceLastSync: Instant? = null,
) {
    fun encode(): String = GhJson.encode(
        JsonObject(
            buildMap {
                through?.let { put("through", JsonPrimitive(it.toString())) }
                backfilledFrom?.let { put("backfilledFrom", JsonPrimitive(it.toString())) }
                fetchedAt?.let { put("fetchedAt", JsonPrimitive(it.toString())) }
                deepResyncAt?.let { put("deepResyncAt", JsonPrimitive(it.toString())) }
                deviceLastSync?.let { put("deviceLastSync", JsonPrimitive(it.toString())) }
            },
        ),
    )

    companion object {
        val EMPTY: GhStreamState = GhStreamState()

        fun decode(text: String?): GhStreamState {
            val json = text?.let(GhJson::parseObject) ?: return EMPTY
            return GhStreamState(
                through = json.instant("through"),
                backfilledFrom = json.instant("backfilledFrom"),
                fetchedAt = json.instant("fetchedAt"),
                deepResyncAt = json.instant("deepResyncAt"),
                deviceLastSync = json.instant("deviceLastSync"),
            )
        }
    }
}

/** Consecutive failures and, after a rate limit, the earliest next request (stored with the account binding). */
internal data class GhBackoff(val failures: Int = 0, val nextAllowedAt: Instant? = null) {
    fun encode(): String = GhJson.encode(
        JsonObject(
            buildMap {
                put("failures", JsonPrimitive(failures))
                nextAllowedAt?.let { put("nextAllowedAt", JsonPrimitive(it.toString())) }
            },
        ),
    )

    companion object {
        fun decode(text: String?): GhBackoff {
            val json = text?.let(GhJson::parseObject) ?: return GhBackoff()
            return GhBackoff(json.long("failures")?.toInt()?.coerceAtLeast(0) ?: 0, json.instant("nextAllowedAt"))
        }
    }
}
