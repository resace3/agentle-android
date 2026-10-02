package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureArgKind
import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import kotlinx.datetime.LocalTime

/** Values and helpers for the `jitai` arg of the intervention-history features (R10 §4.5, §5.4 I). */
public object JitaiArgs {
    public const val SELF: String = "self"
    public const val ANY: String = "any"
    public const val CATEGORY_PREFIX: String = "category:"

    /** `JitaiCategory` members (R10 §2.1). */
    public val CATEGORIES: List<String> =
        listOf("PHYSICAL_ACTIVITY", "SLEEP_WIND_DOWN", "DIGITAL_WELLBEING", "STRESS_BREAK", "GENERAL")

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * [ref] with `jitai=self` replaced by [jitaiId]. The caller binds `self` before resolving: refs are memoized per
     * pass by their canonical key, so an unbound `self` in two rules would share one value. The resolver therefore
     * answers an unbound `self` with `Missing(INVALID_VALUE)`.
     */
    public fun bindSelf(ref: FeatureRef, jitaiId: String): FeatureRef =
        if (ref.args["jitai"] == SELF) ref.copy(args = ref.args + ("jitai" to jitaiId)) else ref

    /** The selector for a `jitai` arg value, or null when the value is not valid for the resolver. */
    public fun selectorOf(value: String): JitaiSelector? = when {
        value == ANY -> JitaiSelector.AnyJitai

        value.startsWith(CATEGORY_PREFIX) ->
            value.removePrefix(CATEGORY_PREFIX).takeIf { it in CATEGORIES }?.let { JitaiSelector.Category(it) }

        UUID.matches(value) -> JitaiSelector.Id(value)

        else -> null
    }
}

/** The validated args of one ref. Fields are null when the feature does not take that arg. */
internal data class ParsedArgs(
    val packageName: String? = null,
    val appCategory: String? = null,
    val since: LocalTime? = null,
    val jitai: JitaiSelector? = null,
)

/** Arg validation (R10 §4.5): unknown names, missing args and bad syntax make the whole ref invalid. */
internal object FeatureArgParser {
    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val HH_MM = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private const val MAX_PACKAGE_LENGTH = 255

    /** The parsed args, or null when any arg is unknown, missing or malformed. */
    fun parse(definition: FeatureDefinition, args: Map<String, String>): ParsedArgs? {
        if (args.keys != definition.args.map { it.name }.toSet()) return null
        var parsed = ParsedArgs()
        for (arg in definition.args) {
            val raw = args.getValue(arg.name)
            parsed = when (arg.kind) {
                FeatureArgKind.PACKAGE -> parsePackage(raw)?.let { parsed.copy(packageName = it) }

                FeatureArgKind.APP_CATEGORY ->
                    raw.takeIf { it in RealtimeFeatureCatalog.APP_CATEGORIES }?.let { parsed.copy(appCategory = it) }

                FeatureArgKind.SINCE -> parseLocalTime(raw)?.let { parsed.copy(since = it) }

                FeatureArgKind.JITAI_REF -> JitaiArgs.selectorOf(raw)?.let { parsed.copy(jitai = it) }
            } ?: return null
        }
        return parsed
    }

    fun parsePackage(raw: String): String? = raw.takeIf { it.length <= MAX_PACKAGE_LENGTH && PACKAGE.matches(it) }

    /** `HH:mm`, 24-hour, exactly as the rule DSL writes it (R10 §4.4). */
    fun parseLocalTime(raw: String): LocalTime? =
        if (HH_MM.matches(raw)) LocalTime(raw.substring(0, 2).toInt(), raw.substring(3, 5).toInt()) else null
}
