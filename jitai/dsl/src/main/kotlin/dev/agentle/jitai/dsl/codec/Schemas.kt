package dev.agentle.jitai.dsl.codec

import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.ExperimentMode
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.Tone
import dev.agentle.jitai.dsl.model.VariantSelection
import dev.agentle.jitai.dsl.model.WeekDay
import dev.agentle.jitai.dsl.nl.DiscoveryTier
import dev.agentle.jitai.dsl.nl.ProposalStatus
import dev.agentle.jitai.dsl.nl.QuestionId
import dev.agentle.jitai.dsl.nl.UnsupportedReason
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.validation.IssueCode

/**
 * The closed schemas walked in S4: JitaiProposalSchema v1 (R10 §13.3, mirrored from the published resource and
 * cross-checked against it in tests), the discovered-proposal envelope (R10 §14.7) and the stored definition (R10 §3.1,
 * used by S8 and by [RuleCodec] when reading stored rules).
 */
internal object Schemas {
    private inline fun <reified E : Enum<E>> enumOf(): EnumSpec = EnumSpec(enumValues<E>().map { it.name })

    private val hhmm: Spec = StringSpec
    private val jitaiCategory = enumOf<JitaiCategory>()

    /** All event names decode (stored rules keep decoding); availability is checked in S6 (E028). */
    private val eventType = EnumSpec(JitaiEventType.entries.map { it.name }, IssueCode.E030)

    private val window = ObjectSpec("window") {
        mapOf("start" to hhmm, "end" to hhmm, "days" to NullableSpec(ArraySpec(enumOf<WeekDay>())))
    }

    private val trigger = UnionSpec("trigger") {
        mapOf(
            "event" to ObjectSpec("trigger.event") {
                mapOf("type" to DiscriminatorSpec, "events" to ArraySpec(eventType), "debounceSeconds" to IntSpec)
            },
            "interval" to ObjectSpec("trigger.interval") { mapOf("type" to DiscriminatorSpec, "everyMinutes" to IntSpec) },
            "daily_at" to ObjectSpec("trigger.daily_at") {
                mapOf("type" to DiscriminatorSpec, "times" to ArraySpec(hhmm), "maxLatenessMinutes" to IntSpec)
            },
        )
    }

    private fun condition(args: Spec): UnionSpec {
        lateinit var self: UnionSpec
        val group = ObjectSpec("group") { mapOf("type" to DiscriminatorSpec, "of" to ArraySpec(self)) }
        val not = ObjectSpec("not") { mapOf("type" to DiscriminatorSpec, "of" to self) }
        val leafBase = { extra: Map<String, Spec> ->
            mapOf("type" to DiscriminatorSpec, "feature" to FeatureIdSpec, "args" to args) + extra +
                ("onUnknown" to NullableSpec(enumOf<OnUnknown>()))
        }
        val compare = ObjectSpec("compare") { leafBase(mapOf("value" to LiteralSpec)) }
        val between = ObjectSpec("between") { leafBase(mapOf("min" to LiteralSpec, "max" to LiteralSpec)) }
        val inList = ObjectSpec("in") { leafBase(mapOf("values" to ArraySpec(LiteralSpec))) }
        val timeWindow = ObjectSpec("timeWindow") { mapOf("type" to DiscriminatorSpec, "start" to hhmm, "end" to hhmm) }
        self = UnionSpec("condition") {
            mapOf(
                "all" to group,
                "any" to group,
                "not" to not,
                "gt" to compare,
                "gte" to compare,
                "lt" to compare,
                "lte" to compare,
                "eq" to compare,
                "neq" to compare,
                "between" to between,
                "in" to inList,
                "local_time_in" to timeWindow,
            )
        }
        return self
    }

    private val template = ObjectSpec("template") {
        mapOf("type" to DiscriminatorSpec, "title" to StringSpec, "body" to StringSpec)
    }
    private val templateOnly = UnionSpec("templateOnly") { mapOf("template" to template) }

    private val content = UnionSpec("content") {
        mapOf(
            "static" to ObjectSpec("content.static") {
                mapOf("type" to DiscriminatorSpec, "title" to StringSpec, "body" to StringSpec)
            },
            "template" to template,
            "variants" to ObjectSpec("content.variants") {
                mapOf(
                    "type" to DiscriminatorSpec,
                    "items" to ArraySpec(ObjectSpec("textPair") { mapOf("title" to StringSpec, "body" to StringSpec) }),
                    "selection" to enumOf<VariantSelection>(),
                )
            },
            "ai_text" to ObjectSpec("content.ai_text") {
                mapOf("type" to DiscriminatorSpec, "goal" to StringSpec, "tone" to enumOf<Tone>(), "fallback" to templateOnly)
            },
            "local_media" to ObjectSpec("content.local_media") {
                mapOf("type" to DiscriminatorSpec, "assetId" to StringSpec, "caption" to templateOnly)
            },
        )
    }

    private val snooze = ObjectSpec("snooze") {
        mapOf("mode" to enumOf<SnoozeMode>(), "options" to ArraySpec(enumOf<SnoozeOption>()))
    }

    private fun outcome(args: Spec): ObjectSpec {
        val metricRef = ObjectSpec("metricRef") {
            mapOf("metric" to enumOf<OutcomeMetric>(), "args" to args, "windowMinutes" to NullableSpec(IntSpec))
        }
        return ObjectSpec("outcome") { mapOf("proximal" to metricRef, "distal" to NullableSpec(metricRef)) }
    }

    // ---------------------------------------------------------------- proposal (R10 §13.3)

    /** Proposal args: every key nullable; an absent key reads as null (R10 §13.3 note 1). Values are checked in S6. */
    private val proposalArgs = ObjectSpec("args", optionalKeys = true) {
        listOf("package", "appLabel", "category", "since", "jitai").associateWith { NullableSpec(StringSpec) }
    }

    /** Fields owned by the app (E005, R10 §11.2). */
    val DRAFT_FORBIDDEN: Set<String> = setOf(
        "id", "version", "status", "enabled", "createdBy", "createdAt", "modifiedAt", "expiresAt", "experiment",
        "userConfirmedUnknownOverrides", "provenance", "contentHash", "schemaVersion",
    )

    val proposalCondition: UnionSpec = condition(proposalArgs)

    val draft: ObjectSpec = ObjectSpec("draft", forbidden = DRAFT_FORBIDDEN) {
        mapOf(
            "name" to StringSpec,
            "description" to StringSpec,
            "kind" to enumOf<JitaiKind>(),
            "category" to jitaiCategory,
            "trigger" to NullableSpec(trigger),
            "activeWindow" to NullableSpec(window),
            "conditions" to NullableSpec(proposalCondition),
            "contextRequirements" to NullableSpec(proposalCondition),
            "delivery" to ObjectSpec("delivery", forbidden = setOf("deliveryDeadlineMinutes")) {
                mapOf(
                    "channel" to enumOf<DeliveryChannel>(),
                    "quietHoursPolicy" to enumOf<QuietHoursPolicy>(),
                    "notificationTimeoutMinutes" to NullableSpec(IntSpec),
                )
            },
            "content" to NullableSpec(content),
            "cooldownMinutes" to NullableSpec(IntSpec),
            "maxPerDay" to NullableSpec(IntSpec),
            "maxPerWeek" to NullableSpec(IntSpec),
            "priority" to IntSpec,
            "snooze" to NullableSpec(snooze),
            "expiresInDays" to NullableSpec(IntSpec),
            "outcome" to NullableSpec(outcome(proposalArgs)),
            "suppression" to NullableSpec(
                ObjectSpec("suppression", forbidden = setOf("jitaiIds")) { mapOf("categories" to ArraySpec(jitaiCategory)) },
            ),
        )
    }

    val proposal: ObjectSpec = ObjectSpec("proposal") {
        mapOf(
            "schemaVersion" to ConstIntSpec(1, IssueCode.E004),
            "status" to enumOf<ProposalStatus>(),
            "unsupported" to NullableSpec(
                ObjectSpec("unsupported") { mapOf("reason" to enumOf<UnsupportedReason>(), "detail" to NullableSpec(StringSpec)) },
            ),
            "questions" to ArraySpec(
                ObjectSpec("question") {
                    mapOf("id" to EnumSpec(QuestionId.entries.map { it.wire }), "text" to StringSpec, "options" to ArraySpec(StringSpec))
                },
            ),
            "assumptions" to ArraySpec(ObjectSpec("assumption") { mapOf("path" to StringSpec, "text" to StringSpec) }),
            "jitai" to NullableSpec(draft),
        )
    }

    // ---------------------------------------------------------------- discovered proposal (R10 §14.7)

    val discovered: ObjectSpec = ObjectSpec("discovered") {
        mapOf(
            "proposalId" to StringSpec,
            "patternId" to StringSpec,
            "hypothesisId" to StringSpec,
            "exposure" to StringSpec,
            "outcome" to StringSpec,
            "createdAt" to InstantSpec,
            "tier" to enumOf<DiscoveryTier>(),
            "approvalRequired" to TrueSpec,
            "whyProposed" to ObjectSpec("whyProposed") { mapOf("text" to StringSpec, "evidence" to AnyObjectSpec) },
            "jitai" to draft,
            "expectedOutcome" to StringSpec,
            "dataRequired" to ArraySpec(StringSpec),
            "trial" to ObjectSpec("trial") {
                mapOf(
                    "days" to IntSpec,
                    "experimentOffer" to NullableSpec(
                        ObjectSpec("experimentOffer") {
                            mapOf(
                                "mode" to enumOf<ExperimentMode>(),
                                "deliverProbability" to NullableSpec(NumberSpec),
                                "requiresConsent" to BooleanSpec,
                            )
                        },
                    ),
                )
            },
        )
    }

    // ---------------------------------------------------------------- stored definition (R10 §3.1)

    /** Stored args: sparse, non-null strings, no `appLabel` (resolved to `package` before storing, R10 §11.4). */
    private val storedArgs = ObjectSpec("storedArgs", optionalKeys = true) {
        listOf("package", "category", "since", "jitai").associateWith { StringSpec }
    }

    val storedCondition: UnionSpec = condition(storedArgs)

    val definition: ObjectSpec = ObjectSpec("definition") {
        mapOf(
            "schemaVersion" to ConstIntSpec(1, IssueCode.E004),
            "id" to StringSpec,
            "version" to IntSpec,
            "name" to StringSpec,
            "description" to StringSpec,
            "kind" to enumOf<JitaiKind>(),
            "category" to jitaiCategory,
            "status" to enumOf<JitaiStatus>(),
            "enabled" to BooleanSpec,
            "trigger" to NullableSpec(trigger),
            "activeWindow" to NullableSpec(window),
            "conditions" to NullableSpec(storedCondition),
            "contextRequirements" to NullableSpec(storedCondition),
            "delivery" to ObjectSpec("storedDelivery") {
                mapOf(
                    "channel" to enumOf<DeliveryChannel>(),
                    "quietHoursPolicy" to enumOf<QuietHoursPolicy>(),
                    "notificationTimeoutMinutes" to NullableSpec(IntSpec),
                    "deliveryDeadlineMinutes" to IntSpec,
                )
            },
            "content" to NullableSpec(content),
            "cooldownMinutes" to NullableSpec(IntSpec),
            "maxPerDay" to NullableSpec(IntSpec),
            "maxPerWeek" to NullableSpec(IntSpec),
            "priority" to IntSpec,
            "snooze" to NullableSpec(snooze),
            "expiresAt" to NullableSpec(InstantSpec),
            "createdBy" to enumOf<CreatedBy>(),
            "createdAt" to InstantSpec,
            "modifiedAt" to InstantSpec,
            "outcome" to NullableSpec(outcome(storedArgs)),
            "suppression" to NullableSpec(
                ObjectSpec("storedSuppression") {
                    mapOf("categories" to ArraySpec(jitaiCategory), "jitaiIds" to ArraySpec(StringSpec))
                },
            ),
            "experiment" to ObjectSpec("experiment") {
                mapOf("mode" to enumOf<ExperimentMode>(), "deliverProbability" to NullableSpec(NumberSpec))
            },
            "userConfirmedUnknownOverrides" to BooleanSpec,
            "provenance" to NullableSpec(provenance),
        )
    }

    private val provenance: ObjectSpec
        get() = ObjectSpec("provenance") {
            val optionalText = NullableSpec(StringSpec)
            mapOf(
                "nlRequest" to optionalText,
                "proposalId" to optionalText,
                "patternId" to optionalText,
                "templateId" to optionalText,
                "evidence" to NullableSpec(AnyObjectSpec),
                "promptVersion" to optionalText,
                "catalogVersion" to optionalText,
                "appLabels" to MapSpec(StringSpec),
                "expiresInDays" to NullableSpec(IntSpec),
                "approvedRendering" to optionalText,
            )
        }
}
