package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.FeatureArg
import dev.agentle.analytics.features.FeatureArgKind
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.TypedLiterals

/** What is wrong with one arg (R10 §4.5); mapped to E011-E013/E024/E081 for leaves and E072/E024/E081 for outcomes. */
internal enum class ArgProblemType { MISSING, UNEXPECTED, INVALID, PACKAGE, TIME }

internal data class ArgProblem(val type: ArgProblemType, val arg: String, val reason: String = "", val value: String = "")

/** The arg syntax of R10 §4.5, shared by feature leaves and outcome metrics. */
internal object ArgRules {
    const val PACKAGE = "package"
    const val APP_LABEL = "appLabel"
    const val MAX_APP_LABEL_LENGTH = 60
    private const val CATEGORY_PREFIX = "category:"

    /**
     * Problems of [args] (non-null values only) against the args a feature or metric [takes]. `appLabel` stands in for
     * `package` in proposal drafts ([draft]); a `jitai` arg may name an existing rule ([isKnownId]) only in USER rules.
     */
    fun check(
        takes: List<FeatureArg>,
        args: Map<String, String>,
        draft: Boolean,
        origin: RuleOrigin,
        isKnownId: (String) -> Boolean,
    ): List<ArgProblem> {
        val problems = ArrayList<ArgProblem>()
        val takesPackage = takes.any { it.kind == FeatureArgKind.PACKAGE }
        for (name in args.keys.sorted()) {
            val accepted = takes.any { it.name == name } || (draft && takesPackage && name == APP_LABEL)
            if (!accepted) problems += ArgProblem(ArgProblemType.UNEXPECTED, name)
        }
        for (arg in takes) {
            when (arg.kind) {
                FeatureArgKind.PACKAGE -> problems += packageProblems(arg.name, args, draft)

                FeatureArgKind.SINCE -> args[arg.name].let { value ->
                    when {
                        value == null -> problems += ArgProblem(ArgProblemType.MISSING, arg.name)
                        !ClockTime.isValid(value) -> problems += ArgProblem(ArgProblemType.TIME, arg.name, value = value)
                    }
                }

                FeatureArgKind.APP_CATEGORY -> args[arg.name].let { value ->
                    when {
                        value == null -> problems += ArgProblem(ArgProblemType.MISSING, arg.name)

                        value !in RealtimeFeatureCatalog.APP_CATEGORIES -> problems += ArgProblem(
                            ArgProblemType.INVALID,
                            arg.name,
                            "must be one of ${RealtimeFeatureCatalog.APP_CATEGORIES.joinToString(", ")}",
                        )
                    }
                }

                FeatureArgKind.JITAI_REF -> args[arg.name].let { value ->
                    when {
                        value == null -> problems += ArgProblem(ArgProblemType.MISSING, arg.name)

                        !isJitaiRef(value, origin, isKnownId) -> {
                            problems += ArgProblem(ArgProblemType.INVALID, arg.name, jitaiReason(origin))
                        }
                    }
                }
            }
        }
        return problems
    }

    /** `self`, `any`, `category:<JitaiCategory>`; for USER rules also the id of an existing rule (R10 §4.5, E013). */
    fun isJitaiRef(value: String, origin: RuleOrigin, isKnownId: (String) -> Boolean): Boolean = when {
        value == "self" || value == "any" -> true
        value.startsWith(CATEGORY_PREFIX) -> JitaiCategory.entries.any { it.name == value.removePrefix(CATEGORY_PREFIX) }
        else -> origin == RuleOrigin.USER && isKnownId(value)
    }

    private fun jitaiReason(origin: RuleOrigin): String = if (origin == RuleOrigin.AI) {
        "must be self, any or category:<CATEGORY>"
    } else {
        "must be self, any, category:<CATEGORY> or the id of an existing rule"
    }

    private fun packageProblems(name: String, args: Map<String, String>, draft: Boolean): List<ArgProblem> {
        val pkg = args[name]
        val label = if (draft) args[APP_LABEL] else null
        return when {
            pkg == null && label == null -> listOf(ArgProblem(ArgProblemType.MISSING, name))

            pkg != null && label != null -> {
                listOf(ArgProblem(ArgProblemType.INVALID, APP_LABEL, "set either package or appLabel, not both"))
            }

            label != null -> if (TextRules.length(label) in 1..MAX_APP_LABEL_LENGTH) {
                emptyList()
            } else {
                listOf(ArgProblem(ArgProblemType.INVALID, APP_LABEL, "must be 1-$MAX_APP_LABEL_LENGTH characters"))
            }

            pkg != null && !TypedLiterals.isPackageName(pkg) -> listOf(ArgProblem(ArgProblemType.PACKAGE, name, value = pkg))

            else -> emptyList()
        }
    }
}
