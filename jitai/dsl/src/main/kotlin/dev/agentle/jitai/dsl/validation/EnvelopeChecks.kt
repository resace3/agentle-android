package dev.agentle.jitai.dsl.validation

import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.nl.DiscoveredProposal
import dev.agentle.jitai.dsl.nl.JitaiProposal
import dev.agentle.jitai.dsl.nl.ProposalStatus

/** Envelope checks of a natural-language reply (R10 §11.2 E090-E092, E060 and lint on its texts, C04) and of a discovered proposal. */
internal class EnvelopeChecks(private val sink: IssueSink, private val origin: RuleOrigin) {
    fun proposal(proposal: JitaiProposal) {
        status(proposal)
        questions(proposal)
        assumptions(proposal)
        proposal.unsupported?.detail?.let { text(it, "/unsupported/detail", 0..ContentChecks.MAX_SHORT_TEXT) }
    }

    fun discovered(proposal: DiscoveredProposal) {
        proposal.trial.experimentOffer?.deliverProbability?.let {
            RuleChecks.checkProbability(sink, it, "/trial/experimentOffer/deliverProbability")
        }
    }

    private fun status(proposal: JitaiProposal) {
        val status = proposal.status
        when (status) {
            ProposalStatus.OK -> {
                if (proposal.jitai == null) envelope(status, "/jitai", "a jitai object")
                if (proposal.questions.isNotEmpty()) envelope(status, "/questions", "an empty questions list")
                if (proposal.unsupported != null) envelope(status, "/unsupported", "unsupported to be null")
            }

            ProposalStatus.NEEDS_CLARIFICATION -> {
                if (proposal.questions.isEmpty()) envelope(status, "/questions", "1 to 3 questions")
                if (proposal.jitai != null) envelope(status, "/jitai", "jitai to be null")
            }

            ProposalStatus.UNSUPPORTED -> {
                if (proposal.unsupported == null) envelope(status, "/unsupported", "an unsupported object")
                if (proposal.jitai != null) envelope(status, "/jitai", "jitai to be null")
            }
        }
    }

    private fun envelope(status: ProposalStatus, path: String, requirement: String) {
        sink.add(IssueCode.E090, Stage.S4, path, mapOf("status" to status.name, "requirement" to requirement))
    }

    private fun questions(proposal: JitaiProposal) {
        val questions = proposal.questions
        if (questions.size > RuleLimits.MAX_QUESTIONS) {
            sink.add(IssueCode.E091, Stage.S4, "/questions", mapOf("reason" to "more than ${RuleLimits.MAX_QUESTIONS} questions"))
        }
        val seen = HashSet<String>()
        questions.forEachIndexed { index, question ->
            val path = "/questions/$index"
            if (!seen.add(question.id.wire)) {
                sink.add(IssueCode.E091, Stage.S4, "$path/id", mapOf("reason" to "duplicate id ${question.id.wire}"))
            }
            val n = question.options.size
            if (n !in RuleLimits.QUESTION_OPTIONS) {
                sink.add(IssueCode.E091, Stage.S4, "$path/options", mapOf("reason" to "a question needs 2-4 options; got $n"))
            }
            text(question.text, "$path/text", 1..ContentChecks.MAX_SHORT_TEXT)
            question.options.forEachIndexed { optionIndex, option ->
                text(option, "$path/options/$optionIndex", 1..ContentChecks.MAX_OPTION)
            }
        }
    }

    private fun assumptions(proposal: JitaiProposal) {
        val assumptions = proposal.assumptions
        if (assumptions.size > RuleLimits.MAX_ASSUMPTIONS) {
            sink.add(IssueCode.E092, Stage.S4, "/assumptions", mapOf("n" to assumptions.size.toString()))
        }
        assumptions.forEachIndexed { index, assumption ->
            val path = "/assumptions/$index"
            if (!ASSUMPTION_PATH.matches(assumption.path) || assumption.path.length > RuleLimits.MAX_ASSUMPTION_PATH_LENGTH) {
                sink.add(IssueCode.E092, Stage.S4, "$path/path", variant = 1)
            }
            text(assumption.text, "$path/text", 1..ContentChecks.MAX_SHORT_TEXT)
            if (proposal.status == ProposalStatus.OK) {
                sink.add(IssueCode.C04, Stage.S6, path, mapOf("text" to assumption.text, "valuePath" to assumption.path))
            }
        }
    }

    private fun text(value: String, path: String, range: IntRange) {
        ContentChecks.lintAndLength(sink, value, path, range, origin)
    }

    private companion object {
        /** R10 §13.3 `assumption.path` pattern. */
        val ASSUMPTION_PATH = Regex("^/jitai(/[A-Za-z0-9_]+)*$")
    }
}
