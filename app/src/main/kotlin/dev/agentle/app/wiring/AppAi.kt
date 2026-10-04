package dev.agentle.app.wiring

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.validation.AiOutputValidator
import dev.agentle.ai.api.validation.ChatReplyOutput
import dev.agentle.ai.api.validation.ChatReplySchema
import dev.agentle.ai.api.validation.toOutcome
import dev.agentle.ai.context.AiAuditLog
import dev.agentle.ai.context.AiConsentStore
import dev.agentle.ai.context.AiContext
import dev.agentle.ai.context.AiContextOptions
import dev.agentle.ai.context.AiRequestRecord
import dev.agentle.ai.context.AppSession
import dev.agentle.ai.context.CompositeAiContextDataSource
import dev.agentle.ai.context.ConsentState
import dev.agentle.ai.context.PhoneUsageDataSource
import dev.agentle.ai.context.PhoneUsageLoader
import dev.agentle.ai.context.PurposePolicy
import dev.agentle.ai.context.ScreenSession
import dev.agentle.ai.context.SensorInventoryDataSource
import dev.agentle.ai.context.SensorInventoryLoader
import dev.agentle.connectors.android.collectors.sensors.SensorGateway
import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.api.sensors.DeviceSensor
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.flatMap
import dev.agentle.core.common.map
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.data.events.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * The app's AI pipeline: chat questions are built by the ContextSelectionEngine from phone-usage aggregates and the
 * sensor inventory the user agreed to share, checked by the EgressGuard, and only then sent to ChatGPT by the SIWC provider.
 */
@Singleton
internal class AppAi @Inject constructor(
    @ApplicationContext context: Context,
    private val siwc: AppSiwc,
    events: EventRepository,
    sensors: SensorGateway,
    clock: AgentleClock,
) {
    private val prefs = context.getSharedPreferences("ai_install", Context.MODE_PRIVATE)
    private val consentStore = PrefsConsentStore(context)

    private val ai = AiContext(
        dataSource = CompositeAiContextDataSource(
            listOf(
                PhoneUsageDataSource(EventPhoneUsageLoader(events, context.packageManager)) { clock.now() },
                SensorInventoryDataSource(GatewaySensorInventoryLoader(sensors, events)),
            ),
        ),
        consentStore = consentStore,
        account = { siwc.accountSub() },
        auditLog = InMemoryAuditLog(),
        clock = clock,
        providerFactory = { verifier ->
            siwc.verifier = verifier
            siwc.graph.provider
        },
        options = AiContextOptions(accountSalt = salt()),
    )

    /** True while this account has agreed to share phone-usage summaries and the sensor list with ChatGPT for chat questions. */
    val phoneUsageShared: Flow<Boolean> =
        combine(consentStore.changes.onStart { emit(Unit) }, siwc.graph.signIn.snapshot) { _, _ -> shared() }

    suspend fun sharePhoneUsage(): Outcome<Unit> = ai.consent.grant(PHONE_USAGE, AiPurpose.GENERAL_QUESTION)

    /** ChatGPT's answer to [question], checked against ChatReplySchema: the text and, when asked for, a dashboard. */
    suspend fun ask(question: String): Outcome<ChatReplyOutput> =
        ai.engine.build(AiPurpose.GENERAL_QUESTION, question).flatMap { envelope ->
            ai.guard.generateStructuredResult(envelope, ChatReplySchema.SCHEMA).flatMap { result ->
                AiOutputValidator.validate(result, ChatReplySchema.validator, PurposePolicy.outputContext(envelope)).toOutcome()
            }
        }

    private suspend fun shared(): Boolean {
        val sub = siwc.accountSub() ?: return false
        val grants = (ai.consent.read() as? ConsentState.Readable)?.snapshot?.grants.orEmpty()
        return PHONE_USAGE.all { category ->
            grants.any { it.category == category && it.purpose == AiPurpose.GENERAL_QUESTION && it.accountSub == sub }
        }
    }

    private fun salt(): String =
        prefs.getString(SALT, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(SALT, it).apply() }

    private companion object {
        const val SALT = "account_salt"

        /** Phone usage plus DEVICE_STATE for the sensor inventory; an account that granted only the first two is asked again. */
        val PHONE_USAGE = setOf(AiDataCategory.SCREEN_TIME_TOTALS, AiDataCategory.APP_IDENTITY, AiDataCategory.DEVICE_STATE)
    }
}

/** The consent document in app-private preferences. */
private class PrefsConsentStore(context: Context) : AiConsentStore {
    private val prefs = context.getSharedPreferences("ai_consent", Context.MODE_PRIVATE)
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val changes: Flow<Unit> = mutableChanges.asSharedFlow()

    override suspend fun load(): String? = prefs.getString(KEY, null)

    override suspend fun save(document: String) {
        prefs.edit().putString(KEY, document).commit()
        mutableChanges.tryEmit(Unit)
    }

    private companion object {
        const val KEY = "consent_document"
    }
}

/** Request metadata for this session (the AI log screen is not wired yet). */
private class InMemoryAuditLog : AiAuditLog {
    private val records = LinkedHashMap<String, AiRequestRecord>()

    override suspend fun record(record: AiRequestRecord): Outcome<Unit> {
        synchronized(records) { records[record.requestId] = record }
        return Outcome.success(Unit)
    }

    override suspend fun records(purpose: AiPurpose, mode: AiRequestMode, since: Instant): Outcome<List<AiRequestRecord>> = Outcome.success(
        synchronized(records) { records.values.filter { it.purpose == purpose && it.mode == mode && it.createdAt >= since } },
    )
}

/** Phone-usage events from the event store; apps are named by their launcher label. */
private class EventPhoneUsageLoader(private val events: EventRepository, private val packages: PackageManager) : PhoneUsageLoader {
    override suspend fun screenSessions(range: ClosedOpenRange): List<ScreenSession> =
        events.range(EventType.SCREEN_SESSION, range.start, range.end).mapNotNull { stored ->
            (stored.event.payload as? ScreenPayload)?.durationMs?.let { ScreenSession(stored.event.startTime, it) }
        }

    override suspend fun unlocks(range: ClosedOpenRange): List<Instant> =
        events.range(EventType.DEVICE_UNLOCK, range.start, range.end).map { it.event.startTime }

    override suspend fun appSessions(range: ClosedOpenRange): List<AppSession> =
        events.range(EventType.APP_SESSION, range.start, range.end).mapNotNull { stored ->
            val payload = stored.event.payload as? AppUsagePayload ?: return@mapNotNull null
            payload.durationMs?.let { AppSession(label(payload.packageName), it) }
        }

    private fun label(packageName: String): String = try {
        packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}

/**
 * The phone's sensors from the sensor gateway. Agentle reads no sensor directly (the Phone sensors tab only checks them
 * live); the one it records is the step counter, whose counts the Recording API stores as the phone's steps.
 */
private class GatewaySensorInventoryLoader(private val gateway: SensorGateway, private val events: EventRepository) :
    SensorInventoryLoader {
    override suspend fun sensors(): List<DeviceSensor> = gateway.sensors()

    override suspend fun recordedIds(): Set<String> {
        val stepsStored = events.countsPerSource().any { it.name == AndroidSources.STEPS.value && it.rows > 0 }
        return if (stepsStored) sensors().filter { it.kind.type == STEP_COUNTER }.mapTo(HashSet()) { it.id } else emptySet()
    }

    private companion object {
        /** `Sensor.TYPE_STEP_COUNTER`. */
        const val STEP_COUNTER = 19
    }
}
