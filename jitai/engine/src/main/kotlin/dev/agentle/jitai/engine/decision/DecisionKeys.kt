package dev.agentle.jitai.engine.decision

import dev.agentle.jitai.dsl.rule.ClockTime
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale
import kotlin.time.Instant

/** The kind of decision point a key names; [code] is its letter in the key (R10 §8.2). */
@Serializable
public enum class TriggerKind(public val code: String) {
    EVENT("E"),
    INTERVAL("I"),
    DAILY_AT("D"),
    SNOOZE_FOLLOW_UP("R"),
    ;

    /**
     * Scheduled decision points write a row for every resolved slot (the MRT-style denominator); event decision points
     * write rows only for delivery-eligible outcomes, so a later event in the same bucket can still fire (R10 §8.2). A
     * snooze follow-up happens at most once per original decision, so it always consumes its key.
     */
    public val writesEveryResolution: Boolean get() = this != EVENT
}

/**
 * Decision-point keys (R10 §8.2). A key identifies a decision point, not a delivery attempt, so retries and duplicate
 * workers map to the same key and the store's UNIQUE index makes the second insert a no-op. Keys contain the JITAI id but
 * not its version, so editing a rule cannot cause a second delivery for the same decision point.
 *
 * Formats (`|`-separated, prefix `v1`):
 * - event: `v1|<jitaiId>|E|<floor(epochSecond / 900)>` (UTC 15-minute bucket, immune to zone and DST changes);
 * - interval: `v1|<jitaiId>|I|<window-instance start date>|<slot index>`;
 * - daily_at: `v1|<jitaiId>|D|<local date>|<HH:mm>` (no zone: a westward flight cannot repeat a slot);
 * - snooze re-evaluation: `v1|<jitaiId>|R|<first 16 hex of SHA-256(original key)>`.
 */
public object DecisionKeys {
    public const val VERSION: String = "v1"
    public const val EVENT_BUCKET_SECONDS: Long = 900
    private const val SEPARATOR = "|"
    private const val FOLLOW_UP_HEX_CHARS = 16

    /** The UTC 15-minute bucket of [instant]. */
    public fun eventBucket(instant: Instant): Long = Math.floorDiv(instant.epochSeconds, EVENT_BUCKET_SECONDS)

    public fun event(jitaiId: String, eventAt: Instant): String = join(jitaiId, TriggerKind.EVENT, eventBucket(eventAt).toString())

    public fun interval(jitaiId: String, instanceStartDate: LocalDate, slot: Int): String {
        require(slot >= 0) { "slot index must not be negative" }
        return join(jitaiId, TriggerKind.INTERVAL, instanceStartDate.toString(), slot.toString())
    }

    public fun dailyAt(jitaiId: String, localDate: LocalDate, time: String): String {
        require(ClockTime.isValid(time)) { "time must be HH:mm" }
        return join(jitaiId, TriggerKind.DAILY_AT, localDate.toString(), time)
    }

    public fun snoozeFollowUp(jitaiId: String, originalKey: String): String =
        join(jitaiId, TriggerKind.SNOOZE_FOLLOW_UP, sha256Hex(originalKey).take(FOLLOW_UP_HEX_CHARS))

    /** The trigger kind of [key], or null when [key] is not a v1 decision key. */
    public fun kindOf(key: String): TriggerKind? {
        val parts = key.split(SEPARATOR)
        if (parts.size < MIN_PARTS || parts[0] != VERSION) return null
        return TriggerKind.entries.firstOrNull { it.code == parts[2] }
    }

    /** The JITAI id inside [key], or null when [key] is not a v1 decision key. */
    public fun jitaiIdOf(key: String): String? = key.split(SEPARATOR).takeIf { kindOf(key) != null }?.get(1)

    /** Lower-case hex SHA-256 of the UTF-8 bytes of [text]. */
    public fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private const val MIN_PARTS = 4

    private fun join(jitaiId: String, kind: TriggerKind, vararg parts: String): String {
        require(jitaiId.isNotEmpty() && SEPARATOR !in jitaiId) { "jitai id must be non-empty and contain no '|'" }
        return (listOf(VERSION, jitaiId, kind.code) + parts).joinToString(SEPARATOR)
    }
}
