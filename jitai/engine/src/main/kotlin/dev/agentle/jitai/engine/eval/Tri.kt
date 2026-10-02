package dev.agentle.jitai.engine.eval

import kotlinx.serialization.Serializable

/**
 * Strong Kleene three-valued logic K3 (R10 §6.2). The order `FALSE < UNKNOWN < TRUE` makes AND/OR monotone and NOT
 * antitone, which is what lets `onUnknown` overrides be classified by polarity (R10 §6.4).
 */
@Serializable
public enum class Tri {
    TRUE,
    FALSE,
    UNKNOWN,
    ;

    /** AND (`all`): FALSE dominates, then UNKNOWN. */
    public infix fun and(other: Tri): Tri = when {
        this == FALSE || other == FALSE -> FALSE
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> TRUE
    }

    /** OR (`any`): TRUE dominates, then UNKNOWN. */
    public infix fun or(other: Tri): Tri = when {
        this == TRUE || other == TRUE -> TRUE
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> FALSE
    }

    /** NOT: TRUE <-> FALSE, UNKNOWN stays UNKNOWN. */
    public operator fun not(): Tri = when (this) {
        TRUE -> FALSE
        FALSE -> TRUE
        UNKNOWN -> UNKNOWN
    }

    public companion object {
        public fun of(value: Boolean): Tri = if (value) TRUE else FALSE

        /** N-ary `all` (R10 §6.2). An empty list is a malformed rule (E022) and yields UNKNOWN, never TRUE. */
        public fun all(values: List<Tri>): Tri = if (values.isEmpty()) UNKNOWN else values.reduce { a, b -> a and b }

        /** N-ary `any` (R10 §6.2). An empty list is a malformed rule (E022) and yields UNKNOWN. */
        public fun any(values: List<Tri>): Tri = if (values.isEmpty()) UNKNOWN else values.reduce { a, b -> a or b }
    }
}
