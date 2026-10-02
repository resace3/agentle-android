package dev.agentle.jitai.dsl.validation

import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.nl.AppResolution
import dev.agentle.jitai.dsl.nl.InstalledApp
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.TypedLiterals

/**
 * Local app-label resolution (R10 §13.4) and confirm item C01: an `appLabel` resolves only on exactly one exact match
 * (or the user's choice in [selections]); a `package` must be installed and launcher-visible. The installed-app list is
 * read on the device only and never reaches a model.
 */
internal class AppResolutionStep(
    private val sink: IssueSink,
    private val view: RuleView,
    private val context: ValidationContext,
    private val selections: Map<String, String>,
) {
    /** `appLabel` (or replaced package) -> package to store. */
    val packages = LinkedHashMap<String, String>()

    /** package -> the label the request used, for `provenance.appLabels`. */
    val labelsByPackage = LinkedHashMap<String, String>()

    val choices = ArrayList<AppChoice>()

    /** True while an `appLabel` has no package yet: normalization waits for the user's choice. */
    var unresolved: Boolean = false
        private set

    private val handled = HashSet<String>()

    fun run() {
        for ((argsPath, args) in occurrences()) {
            args[ArgRules.APP_LABEL]?.let { label(it, "$argsPath/${ArgRules.APP_LABEL}", args) }
            args[ArgRules.PACKAGE]?.let { pkg(it, "$argsPath/${ArgRules.PACKAGE}") }
        }
    }

    private fun label(label: String, path: String, args: Map<String, String>) {
        if (args.containsKey(ArgRules.PACKAGE) || TextRules.length(label) !in 1..ArgRules.MAX_APP_LABEL_LENGTH) return
        if (!handled.add(label)) return
        val apps = context.apps
        val chosen = selections[label]
        if (chosen != null && TypedLiterals.isPackageName(chosen) && (apps == null || apps.isLauncherVisible(chosen))) {
            resolved(label, chosen)
            return
        }
        when (val resolution = apps?.resolve(label) ?: AppResolution.NotFound) {
            is AppResolution.Resolved -> resolved(label, resolution.app.packageName)
            is AppResolution.Ambiguous -> ask(label, path, resolution.candidates)
            AppResolution.NotFound -> ask(label, path, emptyList())
        }
    }

    private fun pkg(pkg: String, path: String) {
        val apps = context.apps ?: return
        if (!TypedLiterals.isPackageName(pkg) || apps.isLauncherVisible(pkg) || !handled.add(pkg)) return
        val chosen = selections[pkg]
        if (chosen != null && TypedLiterals.isPackageName(chosen) && apps.isLauncherVisible(chosen)) {
            packages[pkg] = chosen
            return
        }
        sink.add(IssueCode.C01, Stage.S6, path, mapOf("appLabel" to pkg))
        choices += AppChoice(pkg, path, emptyList())
    }

    private fun resolved(label: String, packageName: String) {
        packages[label] = packageName
        labelsByPackage.putIfAbsent(packageName, label)
    }

    private fun ask(label: String, path: String, candidates: List<InstalledApp>) {
        unresolved = true
        sink.add(IssueCode.C01, Stage.S6, path, mapOf("appLabel" to label))
        choices += AppChoice(label, path, candidates)
    }

    /** Every args object of the rule with its JSON pointer: leaves of both trees, then outcome metrics. */
    private fun occurrences(): List<Pair<String, Map<String, String>>> = buildList {
        val trees = listOf(RuleChecks.CONDITIONS to view.conditions, RuleChecks.CONTEXT_REQUIREMENTS to view.contextRequirements)
        for ((name, tree) in trees) {
            if (tree == null) continue
            val root = view.path(name)
            RuleAnalysis.leaves(tree).forEach { info ->
                val leaf = info.node as? Condition.FeatureLeaf ?: return@forEach
                add("$root${info.path}/args" to leaf.args)
            }
        }
        view.outcome?.let { outcome ->
            add(view.path("outcome", "proximal", "args") to outcome.proximal.args)
            outcome.distal?.let { add(view.path("outcome", "distal", "args") to it.args) }
        }
    }
}
