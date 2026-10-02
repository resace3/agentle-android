package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.analysis.LeafInfo
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.model.wireType

/** Lengths, lint and placeholders of rule text (R10 §11.2 "Content", §11.6). */
internal class ContentChecks(
    private val sink: IssueSink,
    private val view: RuleView,
    private val limits: RuleLimits,
    private val mediaLibrary: MediaLibrary?,
) {
    private val placeholders = ArrayList<Pair<String, String>>()

    fun check() {
        text(view.name, view.path("name"), 1..MAX_NAME, Placeholders.IGNORED)
        text(view.description, view.path("description"), limits.minDescriptionLength..MAX_DESCRIPTION, Placeholders.IGNORED)
        val content = view.content
        if (content != null) content(content)
        if (view.kind == JitaiKind.INTERVENTION && content != null) channelMatch(content)
        placeholders()
    }

    private fun content(content: ContentStrategy) {
        when (content) {
            is ContentStrategy.Static -> {
                text(content.title, view.path("content", "title"), 1..MAX_TITLE, Placeholders.FORBIDDEN)
                text(content.body, view.path("content", "body"), 1..MAX_BODY, Placeholders.FORBIDDEN)
            }

            is ContentStrategy.Template -> template(content, view.path("content"))

            is ContentStrategy.Variants -> {
                val n = content.items.size
                if (n !in RuleLimits.MIN_VARIANTS..RuleLimits.MAX_VARIANTS) {
                    add(IssueCode.E058, view.path("content", "items"), "n" to n.toString())
                }
                content.items.forEachIndexed { index, item ->
                    text(item.title, view.path("content", "items", index, "title"), 1..MAX_TITLE, Placeholders.ALLOWED)
                    text(item.body, view.path("content", "items", index, "body"), 1..MAX_BODY, Placeholders.ALLOWED)
                }
            }

            is ContentStrategy.AiText -> {
                text(content.goal, view.path("content", "goal"), 1..MAX_GOAL, Placeholders.FORBIDDEN)
                template(content.fallback, view.path("content", "fallback"))
            }

            is ContentStrategy.LocalMedia -> {
                // A null library (pure re-validation of stored rules) checks the id syntax only.
                val known = mediaLibrary?.contains(content.assetId) ?: true
                if (!ASSET_ID.matches(content.assetId) || !known) {
                    add(IssueCode.E068, view.path("content", "assetId"), "assetId" to content.assetId)
                }
                template(content.caption, view.path("content", "caption"))
            }
        }
    }

    private fun template(template: ContentStrategy.Template, path: String) {
        text(template.title, "$path/title", 1..MAX_TITLE, Placeholders.ALLOWED)
        text(template.body, "$path/body", 1..MAX_BODY, Placeholders.ALLOWED)
    }

    /** E069: IMAGE and VIDEO need `local_media`; NOTIFICATION and VOICE cannot use it. */
    private fun channelMatch(content: ContentStrategy) {
        val media = content is ContentStrategy.LocalMedia
        val mismatch = when (view.channel) {
            DeliveryChannel.IMAGE, DeliveryChannel.VIDEO -> !media
            DeliveryChannel.NOTIFICATION, DeliveryChannel.VOICE -> media
            DeliveryChannel.NONE -> false
        }
        if (mismatch) add(IssueCode.E069, view.path("content", "type"), "type" to content.wireType, "channel" to view.channel.name)
    }

    /**
     * Length (E060), lint (E061, E062, E066; AI text only except L5) and placeholders: parsed in template text,
     * rejected (E067) in `static` text and the `ai_text` goal, which are delivered or sent as they are.
     */
    private fun text(value: String, path: String, range: IntRange, placeholderMode: Placeholders) {
        lintAndLength(sink, value, path, range, limits.origin)
        when (placeholderMode) {
            Placeholders.ALLOWED -> parsePlaceholders(value, path)
            Placeholders.FORBIDDEN -> forbidPlaceholders(value, path)
            Placeholders.IGNORED -> Unit
        }
    }

    private fun parsePlaceholders(text: String, path: String) {
        var i = 0
        while (i < text.length) {
            val open = text.indexOf(OPEN, i)
            val close = text.indexOf(CLOSE, i)
            if (open < 0 && close < 0) return
            if (open < 0 || (close in 0 until open)) return syntax(text, close, path)
            val end = text.indexOf(CLOSE, open + OPEN.length)
            if (end < 0) return syntax(text, open, path)
            val name = text.substring(open + OPEN.length, end)
            if (!NAME.matches(name)) return syntax(text, open, path)
            placeholders += name to path
            i = end + CLOSE.length
        }
    }

    private fun forbidPlaceholders(text: String, path: String) {
        val open = text.indexOf(OPEN)
        val close = text.indexOf(CLOSE)
        val at = listOf(open, close).filter { it >= 0 }.minOrNull() ?: return
        syntax(text, at, path)
    }

    private fun syntax(text: String, at: Int, path: String) {
        add(IssueCode.E067, path, "snippet" to TextRules.snippet(text, at))
    }

    /** E063-E065: each placeholder names a feature with exactly one leaf in the rule, and that leaf is determining. */
    private fun placeholders() {
        if (placeholders.isEmpty()) return
        val leaves: List<LeafInfo> = listOfNotNull(view.conditions, view.contextRequirements).flatMap { RuleAnalysis.leaves(it) }
        val byFeature = leaves.groupBy { it.featureId }
        for ((name, path) in placeholders) {
            val matching = byFeature[name].orEmpty()
            when {
                RealtimeFeatureCatalog[name] == null -> add(IssueCode.E063, path, "name" to name)
                matching.isEmpty() -> add(IssueCode.E064, path, "name" to name)
                matching.size > 1 || !matching.single().determining -> add(IssueCode.E065, path, "name" to name)
            }
        }
    }

    private fun add(code: IssueCode, path: String, vararg params: Pair<String, String>) {
        sink.add(code, Stage.S6, path, params.toMap())
    }

    enum class Placeholders { ALLOWED, FORBIDDEN, IGNORED }

    companion object {
        const val MAX_NAME = 60
        const val MAX_DESCRIPTION = 280
        const val MAX_TITLE = 60
        const val MAX_BODY = 240
        const val MAX_GOAL = 200
        const val MAX_SHORT_TEXT = 200
        const val MAX_OPTION = 60
        private const val OPEN = "{{"
        private const val CLOSE = "}}"
        private val NAME = Regex("^[a-z0-9_]+$")

        /** R10 §13.3 `local_media.assetId` pattern; anything else cannot be in the media library either. */
        private val ASSET_ID = Regex("^[a-z0-9_]{1,40}$")

        /**
         * E060 by code points of the stored form, then lint: L1-L4, L6, L7 for AI text (R10 §11.6), L5 for every
         * origin (control and bidi characters would corrupt notifications whoever wrote them).
         */
        fun lintAndLength(sink: IssueSink, value: String, path: String, range: IntRange, origin: RuleOrigin) {
            val n = TextRules.length(value)
            if (n !in range) {
                val params = mapOf("min" to range.first.toString(), "max" to range.last.toString(), "n" to n.toString())
                sink.add(IssueCode.E060, Stage.S6, path, params)
                return // No lint over a text that is already rejected for its length (review R2-7).
            }
            val findings = if (origin == RuleOrigin.AI) TextLint.check(value) else listOfNotNull(TextLint.invisible(value))
            for (finding in findings) {
                val code = finding.check.code ?: continue
                val params = when (code) {
                    IssueCode.E066 -> mapOf("hex" to finding.hex.orEmpty())
                    IssueCode.E062 -> mapOf("category" to finding.check.category.orEmpty(), "match" to TextRules.snippet(finding.match, 0))
                    else -> mapOf("match" to TextRules.snippet(finding.match, 0))
                }
                sink.add(code, Stage.S6, path, params)
            }
        }
    }
}
