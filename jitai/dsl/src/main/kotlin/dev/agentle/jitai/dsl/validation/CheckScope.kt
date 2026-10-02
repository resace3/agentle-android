package dev.agentle.jitai.dsl.validation

/**
 * What the S6 checks may assume about the world outside the rule.
 *
 * @property existingRulesKnown the user's other rules are known, so `jitai` args and suppression targets must name an
 *   existing rule (E013, E056). False for the pure re-validation of stored rules
 *   ([RuleValidator.revalidate]), which then accepts any lowercase UUID and skips the existence checks.
 * @property mediaLibrary the bundled media catalog; null checks only the `assetId` syntax (E068).
 */
internal data class CheckScope(val existingRulesKnown: Boolean, val mediaLibrary: MediaLibrary?) {
    companion object {
        val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun of(context: ValidationContext): CheckScope = CheckScope(existingRulesKnown = true, mediaLibrary = context.mediaLibrary)
    }
}
