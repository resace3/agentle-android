package dev.agentle.ai.context

import dev.agentle.ai.api.AiProvider
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.AiSendVerifier
import dev.agentle.core.common.AppError
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.Severity
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.fakes.ai.FakeAiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant

const val ACCOUNT: String = "acct-test-one"

/** The per-install salt of test worlds. */
const val SALT: String = "test-install-salt"

/** A zone with a 45-minute offset and no DST, so a JVM-default-zone bug cannot pass by accident (testing-build-04). */
val KATHMANDU: TimeZone = TimeZone.of("Asia/Kathmandu")

/** 17:45 in Kathmandu, engine day 2026-10-01 (which began at 2026-09-30T22:15Z). */
val START: Instant = Instant.parse("2026-10-01T12:00:00Z")

/** In-memory consent store with fault injection. */
class InMemoryConsentStore : AiConsentStore {
    @Volatile
    var document: String? = null

    @Volatile
    var loadFailure: (() -> Throwable)? = null

    @Volatile
    var saveFailure: (() -> Throwable)? = null

    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    override val changes: Flow<Unit> = events.asSharedFlow()

    override suspend fun load(): String? {
        loadFailure?.let { throw it() }
        return document
    }

    override suspend fun save(document: String) {
        saveFailure?.let { throw it() }
        this.document = document
        events.emit(Unit)
    }

    /** Changes the stored text behind the repository (another process, a bad write); [notify] emits a change. */
    fun overwrite(text: String?, notify: Boolean = true) {
        document = text
        if (notify) events.tryEmit(Unit)
    }
}

/** Mirrors `androidx.datastore.core.CorruptionException` (an IOException) without the Android dependency. */
class CorruptionException(message: String) : IOException(message)

class FakeAccount(@Volatile var sub: String? = ACCOUNT) : AiAccountSource {
    override suspend fun activeAccountSub(): String? = sub
}

class InMemoryAuditLog : AiAuditLog {
    private val byId = LinkedHashMap<String, AiRequestRecord>()

    @Volatile
    var failWrites: Boolean = false

    @Volatile
    var failReads: Boolean = false

    val all: List<AiRequestRecord> get() = synchronized(byId) { byId.values.toList() }

    operator fun get(requestId: String): AiRequestRecord? = synchronized(byId) { byId[requestId] }

    override suspend fun record(record: AiRequestRecord): Outcome<Unit> {
        if (failWrites) return Outcome.Failure(AppError.DatabaseError("disk_full"))
        synchronized(byId) { byId[record.requestId] = record }
        return Outcome.Success(Unit)
    }

    override suspend fun records(purpose: AiPurpose, mode: AiRequestMode, since: Instant): Outcome<List<AiRequestRecord>> {
        if (failReads) return Outcome.Failure(AppError.DatabaseError("disk_io"))
        return Outcome.Success(all.filter { it.purpose == purpose && it.mode == mode && it.createdAt >= since })
    }
}

class CapturingSink : LogSink {
    val records: MutableList<LogRecord> = CopyOnWriteArrayList()

    override fun write(record: LogRecord) {
        records += record
    }

    /** Everything a log reader could see. */
    fun text(): String = records.joinToString("\n") { "${it.component} ${it.message} ${it.eventId} ${it.errorCode} ${it.fields}" }
}

/**
 * A scripted feature layer. Honest, it returns only facts inside the query (fields usable for the purpose, categories,
 * families, kinds and standing fields), as the port requires. Not honest, it returns everything it has: a buggy producer.
 */
class ScriptedDataSource(
    var aggregates: List<AggregateFact> = emptyList(),
    var apps: List<AppUsageFact> = emptyList(),
    var texts: List<UserTextFact> = emptyList(),
    var raw: List<RawEventFact> = emptyList(),
    var honest: Boolean = true,
) : AiContextDataSource {
    val queries: MutableList<Pair<String, AiDataQuery>> = CopyOnWriteArrayList()

    @Volatile
    var failure: AppError? = null

    @Volatile
    var throwing: Boolean = false

    override suspend fun aggregates(query: AiDataQuery): Outcome<List<AggregateFact>> =
        answer("aggregates", query) { aggregates.filter { fits(query, it.field, it.lineage, kindOf(it.value)) } }

    override suspend fun appUsage(query: AiDataQuery): Outcome<List<AppUsageFact>> =
        answer("appUsage", query) { apps.filter { fits(query, it.field, it.lineage, ItemKind.APP_USAGE) } }

    override suspend fun userTexts(query: AiDataQuery): Outcome<List<UserTextFact>> =
        answer("userTexts", query) { texts.filter { fits(query, it.field, it.lineage, ItemKind.TEXT) } }

    override suspend fun rawEvents(query: AiDataQuery): Outcome<List<RawEventFact>> = answer("rawEvents", query) {
        raw.filter { event ->
            val lineage = AiLineageTables.forEvent(event.type, event.connectorId) + event.lineage
            fits(query, AiFieldRegistry.EVENT_FIELD, lineage, ItemKind.EVENT)
        }
    }

    fun asked(name: String): Boolean = queries.any { it.first == name }

    fun query(name: String): AiDataQuery = queries.last { it.first == name }.second

    private fun <T> answer(name: String, query: AiDataQuery, select: () -> List<T>): Outcome<List<T>> {
        queries += name to query
        check(!throwing) { "producer bug with a secret in its message" }
        return failure?.let { Outcome.Failure(it) } ?: Outcome.Success(select())
    }

    private fun fits(query: AiDataQuery, field: String, lineage: DataLineage, kind: ItemKind): Boolean = !honest ||
        (
            query.categories.containsAll(lineage.categories) &&
                query.sourceFamilies.containsAll(lineage.sources) &&
                kind in query.kinds &&
                AiFieldRegistry[field]?.purposes?.contains(query.purpose) != false &&
                (query.fields?.contains(field) ?: true)
            )

    private fun kindOf(value: AggregateValue): ItemKind = when (value) {
        is AggregateValue.Quantity -> ItemKind.QUANTITY
        is AggregateValue.TimeOfDay -> ItemKind.TIME_OF_DAY
        is AggregateValue.Code -> ItemKind.CODE
    }
}

/** A wrong sharing policy: it allows every category, every source family and [standing], whatever the store says. */
class AllowEverythingPolicy(private val standing: StandingConsent? = null) : AiSharingPolicy {
    override fun allowedCategories(consent: ConsentSnapshot, purpose: AiPurpose, accountSub: String?): Set<AiDataCategory> =
        AiDataCategory.entries.toSet()

    override fun standingConsent(consent: ConsentSnapshot, purpose: AiPurpose, accountSub: String?): StandingConsent? = standing

    override fun allowedSources(): Set<SourceFamily> = SourceFamily.entries.toSet()
}

/**
 * Wraps the guard's send verifier, the hook a provider calls immediately before its network send. It keeps the exact
 * strings of every request the hook approved, and checks that the digest it was given covers exactly those strings.
 */
class SendHook(private val inner: AiSendVerifier) : AiSendVerifier {
    val approved: MutableList<String> = CopyOnWriteArrayList()
    val refused: MutableList<AppError> = CopyOnWriteArrayList()

    override suspend fun verifyBeforeSend(envelope: AiRequestEnvelope, sentInputSha256: String): Outcome<Unit> {
        val digest = AiRequestEnvelope.inputDigest(envelope.instructions, envelope.dataInputJson, envelope.userInputJson)
        check(digest == sentInputSha256) { "the provider's digest does not cover the strings it sends" }
        val verdict = inner.verifyBeforeSend(envelope, sentInputSha256)
        when (verdict) {
            is Outcome.Success ->
                approved +=
                    envelope.instructions + "\n" + envelope.dataInputJson + "\n" + envelope.userInputJson.orEmpty()

            is Outcome.Failure -> refused += verdict.error
        }
        return verdict
    }
}

fun lineage(vararg categories: AiDataCategory, source: SourceFamily = SourceFamily.ON_DEVICE): DataLineage =
    DataLineage(categories.toSet(), setOf(source))

fun quantity(field: String, value: Double, unit: String, lineage: DataLineage): AggregateFact =
    AggregateFact(field, AggregateValue.Quantity(value, unit), lineage)

fun code(field: String, code: String, lineage: DataLineage): AggregateFact = AggregateFact(field, AggregateValue.Code(code), lineage)

fun appUsage(label: String, minutes: Long, origin: TextOrigin = TextOrigin.APP_LABEL): AppUsageFact =
    AppUsageFact(UntrustedText(label, origin), minutes, null, lineage(AiDataCategory.APP_IDENTITY, AiDataCategory.SCREEN_TIME_TOTALS))

fun note(text: String, origin: TextOrigin = TextOrigin.USER_NOTE, category: AiDataCategory = AiDataCategory.USER_TEXT): UserTextFact =
    UserTextFact(if (category == AiDataCategory.GOALS) "goal.text" else "user.note", UntrustedText(text, origin), lineage(category))

/** Sleep from Health Connect, steps and screen time from the phone. */
fun sleepData(): ScriptedDataSource = ScriptedDataSource(
    aggregates = listOf(
        quantity("sleep.minutes_avg", 412.0, "min", lineage(AiDataCategory.SLEEP, source = SourceFamily.HEALTH_CONNECT)),
        quantity("steps.daily_avg", 6250.0, "steps", lineage(AiDataCategory.STEPS)),
        quantity("screen.minutes_late_evening_avg", 48.0, "min", lineage(AiDataCategory.SCREEN_TIME_TOTALS)),
    ),
)

/** The blocks of a data input item as sent. */
fun sentBlocks(dataInputJson: String): List<JsonObject> =
    Json.parseToJsonElement(dataInputJson).jsonObject.getValue("blocks").jsonArray.map { it.jsonObject }

/** The union of the `categories` every sent block states (SEC-AI-06). */
fun sentCategories(dataInputJson: String): Set<AiDataCategory> = sentBlocks(dataInputJson).flatMapTo(sortedSetOf()) { block ->
    block.getValue("categories").jsonArray.map { AiDataCategory.valueOf(it.jsonPrimitive.content) }
}

/** Every string value inside [element], paired with the key it sits under (array members keep their array's key). */
fun stringValues(element: JsonElement, key: String = ""): List<Pair<String, String>> = when (element) {
    is JsonObject -> element.entries.flatMap { (name, value) -> stringValues(value, name) }
    is JsonArray -> element.flatMap { stringValues(it, key) }
    is JsonPrimitive -> if (element.isString) listOf(key to element.content) else emptyList()
}

/** Grants of [categories] for [purpose] under version 1 for [ACCOUNT]. */
fun grants(purpose: AiPurpose, categories: Collection<AiDataCategory>): List<ConsentGrant> =
    categories.map { ConsentGrant(it, purpose, AiConsentDisclosure.VERSION, START, ACCOUNT) }

/** A complete test world: consent store, account, audit log, engine, guard and the fake provider behind it. */
class World(
    val data: AiContextDataSource = ScriptedDataSource(),
    zone: TimeZone = KATHMANDU,
    healthConnectToAi: Boolean = true,
    enginePolicy: AiSharingPolicy? = null,
    guardPolicy: AiSharingPolicy? = null,
    providerFactory: ((AiSendVerifier) -> AiProvider)? = null,
    currentVersion: Int = AiConsentDisclosure.VERSION,
    val instructions: AiInstructionSet = AiInstructionSet(),
    hooked: Boolean = false,
) {
    val clock: TestAgentleClock = TestAgentleClock(START, zone)
    val store: InMemoryConsentStore = InMemoryConsentStore()
    val account: FakeAccount = FakeAccount()
    val sink: CapturingSink = CapturingSink()
    val logger: Logger = Logger(listOf(sink), { clock.now().toEpochMilliseconds() }, Severity.VERBOSE)
    val audit: InMemoryAuditLog = InMemoryAuditLog()
    val ledger: RawEventsConsentLedger = RawEventsConsentLedger(clock, Random(7))
    val consent: AiConsentRepository = AiConsentRepository(store, account, clock, logger, currentVersion)
    val sources: SourceFamilyPolicy = SourceFamilyPolicy(healthConnectToAi)
    val engine: ContextSelectionEngine = ContextSelectionEngine(
        dataSource = data,
        consent = consent,
        account = account,
        clock = clock,
        rawEventsLedger = ledger,
        policy = enginePolicy ?: DenyByDefaultSharingPolicy(currentVersion, sources),
        instructions = instructions,
        logger = logger,
        random = Random(11),
    )
    private var fake: FakeAiProvider? = null
    private var sendHook: SendHook? = null
    val guard: EgressGuard = EgressGuard(
        providerFactory = providerFactory ?: { verifier ->
            val checked = if (hooked) SendHook(verifier).also { sendHook = it } else verifier
            FakeAiProvider(checked).also { fake = it }
        },
        consent = consent,
        account = account,
        auditLog = audit,
        clock = clock,
        rawEventsLedger = ledger,
        sourcePolicy = sources,
        policy = guardPolicy ?: DenyByDefaultSharingPolicy(currentVersion, sources),
        instructions = instructions,
        logger = logger,
        accountSalt = SALT,
    )

    val provider: FakeAiProvider get() = checkNotNull(fake) { "this world has a custom provider" }

    /** The send-time hook of a world built with `hooked = true`. */
    val hook: SendHook get() = checkNotNull(sendHook) { "this world has no send hook" }

    val scripted: ScriptedDataSource get() = data as ScriptedDataSource

    suspend fun grant(purpose: AiPurpose, vararg categories: AiDataCategory) {
        consent.grant(categories.toSet(), purpose).getOrThrow()
    }

    /** The stored consent state; fails the test when it is unreadable. */
    suspend fun snapshot(): ConsentSnapshot = (consent.read() as ConsentState.Readable).snapshot

    /** Writes [grants] and [standing] straight into the store, as another writer would (no change is emitted). */
    fun writeConsent(grants: List<ConsentGrant>, standing: List<StandingConsent> = emptyList()) {
        store.overwrite(ConsentDocuments.encode(ConsentSnapshot(grants, standing)), notify = false)
    }

    /** Sends [envelope] through the guard the way the purpose expects (structured output when it has a schema). */
    suspend fun send(envelope: AiRequestEnvelope): Outcome<Any> {
        val schema = PurposePolicy.spec(envelope.purpose).outputSchema
        return if (schema == null) guard.analyze(envelope) else guard.generateStructuredResult(envelope, schema)
    }

    /** Every string the provider sent (only calls its send-time check approved). */
    fun sentText(): String = provider.journal.filter { it.sent }.joinToString("\n") {
        it.sentInstructions + "\n" + it.sentDataInput + "\n" + it.sentUserInput.orEmpty()
    }
}

/** The SplitMix64 generator (Steele, Lea and Flood 2014): small, seeded and the same on every JVM. */
class SplitMix64(private var state: Long) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()

    fun nextBoolean(): Boolean = nextLong() < 0

    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    /** A string of [length] lower-case ASCII letters. */
    fun letters(length: Int): String = buildString { repeat(length) { append('a' + nextInt(LETTERS)) } }

    private companion object {
        const val LETTERS = 26
    }
}

/** The string of the given Unicode code points, so non-ASCII test data stays readable as numbers in the source. */
fun cp(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)
