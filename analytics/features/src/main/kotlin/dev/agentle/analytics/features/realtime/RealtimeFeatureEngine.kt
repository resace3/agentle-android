package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureGroup
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureResolver
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.common.Logger
import dev.agentle.core.time.AgentleClock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * The realtime feature engine: resolves the features of the v1 catalog (R10 §5.4) for one evaluation pass.
 *
 * - The zone and the monotonic clock are read once per pass from [clock] (R10 §10.1); the instant is the `at` of
 *   [resolve], which the caller takes from the same clock.
 * - Every port read of one [resolve] runs inside one [RealtimeFeatureInputs.readSnapshot] (database-sync-05) and is
 *   memoized, so each input is read at most once per pass and every ref sees the same data (R10 §5.2).
 * - Freshness follows R10 §5.3 and the coverage of [RealtimeFeatureCoverage]: absence is never zero.
 * - A ref with an unknown feature id or a bad arg, and an observed value outside the feature's valid range or enum,
 *   is `Missing(INVALID_VALUE)`; an unavailable feature is `Missing(API_UNAVAILABLE)`; a feature above the device's
 *   API level is `Missing(API_LEVEL)`.
 * - Nothing is cached across passes: daily values (last night's sleep, today's resting heart rate, engine-day counts)
 *   are recomputed from the ports on every [resolve], so a dirty engine day or civil date is never served from a stale
 *   persisted value (jitai-correctness-11).
 * - Every returned value traces to its data category through the catalog ([FeatureCategories], jitai-correctness-19).
 * - It never throws for data conditions. A port that throws anyway makes only the features that needed it
 *   `Missing(API_UNAVAILABLE)`; the log line names the feature, its category and the exception class, never its
 *   message.
 *
 * `jitai=self` must be bound to the evaluated rule's id with [JitaiArgs.bindSelf] before resolving.
 */
public class RealtimeFeatureEngine(
    private val inputs: RealtimeFeatureInputs,
    private val clock: AgentleClock,
    private val config: RealtimeFeatureConfig = RealtimeFeatureConfig(),
    private val logger: Logger = Logger.NONE,
) : FeatureResolver {
    override suspend fun resolve(refs: Set<FeatureRef>, at: Instant): FeatureSnapshot {
        val zone = clock.zone()
        val pass = FeaturePass(at = at, zone = zone, elapsedNow = clock.elapsed(), inputs = inputs, config = config)
        val values = try {
            inputs.readSnapshot { refs.associateWith { value(pass, it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(COMPONENT, "snapshot read failed", fields = mapOf("error" to e::class.simpleName))
            refs.associateWith { FeatureValue.Missing(MissingReason.API_UNAVAILABLE) }
        }
        return FeatureSnapshot.of(at, zone.id, values)
    }

    private suspend fun value(pass: FeaturePass, ref: FeatureRef): FeatureValue {
        val definition = RealtimeFeatureCatalog[ref.featureId] ?: return INVALID
        val args = FeatureArgParser.parse(definition, ref.args) ?: return INVALID
        val minApi = definition.minApi
        return when {
            !definition.isAvailable -> FeatureValue.Missing(MissingReason.API_UNAVAILABLE)
            minApi != null && config.apiLevel < minApi -> FeatureValue.Missing(MissingReason.API_LEVEL)
            else -> validate(definition, guarded(pass, definition, args))
        }
    }

    private suspend fun guarded(pass: FeaturePass, definition: FeatureDefinition, args: ParsedArgs): FeatureValue = try {
        dispatch(pass, definition, args)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val fields = mapOf("feature" to definition.id, "category" to definition.category?.name, "error" to e::class.simpleName)
        logger.w(COMPONENT, "feature computation failed", fields = fields)
        FeatureValue.Missing(MissingReason.API_UNAVAILABLE)
    }

    private suspend fun dispatch(pass: FeaturePass, definition: FeatureDefinition, args: ParsedArgs): FeatureValue {
        val id = definition.id
        return when (definition.group) {
            FeatureGroup.TIME -> ClockFeatures.compute(pass, id)

            FeatureGroup.DEVICE -> DeviceFeatures.compute(pass, id)

            FeatureGroup.USAGE -> UsageFeatures.compute(pass, id, args, collectorOf(id))

            FeatureGroup.NOTIFICATIONS -> NotificationFeatures.compute(pass, collectorOf(id))

            // location_class is unavailable in v1 (lifecycle-battery-06); `value` answers before dispatching.
            FeatureGroup.PLACE -> FeatureValue.Missing(MissingReason.API_UNAVAILABLE)

            FeatureGroup.ACTIVITY -> when (id) {
                "activity_state" -> ActivityFeatures.activityState(pass, collectorOf(id))
                "activity_level_last_30m" -> ActivityFeatures.activityLevel(pass, definition)
                else -> ActivityFeatures.steps(pass, definition)
            }

            FeatureGroup.SLEEP -> SleepFeatures.compute(pass, id)

            FeatureGroup.HEART -> HeartFeatures.compute(pass, id)

            FeatureGroup.HISTORY -> HistoryFeatures.compute(pass, id, checkNotNull(args.jitai) { "history feature without jitai arg" })
        }
    }

    private fun collectorOf(featureId: String): String {
        val coverage = RealtimeFeatureCoverage[featureId]
        check(coverage is FeatureCoverage.Collector) { "feature without collector coverage" }
        return coverage.collectorId
    }

    internal companion object {
        private const val COMPONENT = "RealtimeFeatureEngine"
        private val INVALID = FeatureValue.Missing(MissingReason.INVALID_VALUE)

        /** An observed value of the wrong type, outside the valid range or not a member of the enum is invalid. */
        fun validate(definition: FeatureDefinition, value: FeatureValue): FeatureValue {
            val scalar = when (value) {
                is FeatureValue.Known -> value.value
                is FeatureValue.Stale -> value.lastValue
                is FeatureValue.Missing -> return value
            }
            val valid = typeMatches(definition.type, scalar) && when (scalar) {
                is FeatureScalar.IntValue -> definition.validRange?.contains(scalar.value) ?: true
                is FeatureScalar.EnumValue -> scalar.value in definition.enumValues
                else -> true
            }
            return if (valid) value else INVALID
        }

        private fun typeMatches(type: FeatureType, scalar: FeatureScalar): Boolean = when (scalar) {
            is FeatureScalar.IntValue, FeatureScalar.Never -> type == FeatureType.INT
            is FeatureScalar.BoolValue -> type == FeatureType.BOOL
            is FeatureScalar.EnumValue -> type == FeatureType.ENUM
            is FeatureScalar.DayOfWeekValue -> type == FeatureType.DAY_OF_WEEK
            is FeatureScalar.LocalTimeValue -> type == FeatureType.LOCAL_TIME
            is FeatureScalar.NightTimeValue -> type == FeatureType.NIGHT_TIME
            is FeatureScalar.PackageValue, FeatureScalar.NoPackage -> type == FeatureType.PACKAGE
        }
    }
}
