package dev.agentle.jitai.dsl.testing

import dev.agentle.core.common.Logger
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.nl.AppLabelResolver
import dev.agentle.jitai.dsl.nl.InstalledApp
import dev.agentle.jitai.dsl.validation.FeatureAccess
import dev.agentle.jitai.dsl.validation.IdGenerator
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.MediaLibrary
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.ValidationContext
import dev.agentle.jitai.dsl.validation.ValidationInput
import dev.agentle.jitai.dsl.validation.ValidationIssue
import dev.agentle.jitai.dsl.validation.ValidationReport
import dev.agentle.jitai.dsl.validation.ValidationRequest
import dev.agentle.jitai.dsl.validation.ValidationSettings
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** Shared fixtures: R10 §12 F0, the R10 example documents, a fake app list and deterministic ids. */
internal object Fixtures {
    val BERLIN: TimeZone = TimeZone.of("Europe/Berlin")

    /** F0 (R10 §12): Thursday 2026-10-01, 12:00 in Europe/Berlin. */
    val F0_NOW: Instant = Instant.parse("2026-10-01T10:00:00Z")

    val INSTAGRAM = InstalledApp("com.instagram.android", "Instagram", 300)
    val YOUTUBE = InstalledApp("com.google.android.youtube", "YouTube", 120)
    val MAPS = InstalledApp("com.google.android.apps.maps", "Maps", 10)
    val INSTALLED: List<InstalledApp> = listOf(INSTAGRAM, YOUTUBE, MAPS)

    val apps: AppLabelResolver = AppLabelResolver { INSTALLED }

    /** R10 §12 F0: quiet hours off, global caps 6 / 30. */
    val F0_SETTINGS = ValidationSettings(quietHours = null)

    /** R10 §13.6: default settings, quiet hours 22:00-07:00 on, 12-hour clock. */
    val DEFAULT_SETTINGS = ValidationSettings()

    /** Media ids of the fake bundled library. */
    val MEDIA: MediaLibrary = MediaLibrary.of(setOf("sunset_walk_01", "breathing_clip_02"))

    const val REQUEST_1 = "Remind me to wind down if I use Instagram too much after 10 PM."
    const val REQUEST_2 = "Encourage me to walk when I have fewer than 3,000 steps by 5 PM."
    const val REQUEST_3 = "If I slept under six hours, do not bother me with an exercise reminder before 9 AM."

    val example1: String get() = resource("r10/example-13-6-1.json")
    val example2: String get() = resource("r10/example-13-6-2.json")
    val example3: String get() = resource("r10/example-13-6-3.json")
    val discovered: String get() = resource("r10/discovered-14-7.json")
    val definition31: String get() = resource("r10/definition-3-1.json")

    fun clock(now: Instant = F0_NOW, zone: TimeZone = BERLIN): TestAgentleClock = TestAgentleClock(now, zone)

    fun context(
        settings: ValidationSettings = F0_SETTINGS,
        apps: AppLabelResolver? = this.apps,
        existing: List<JitaiDefinition> = emptyList(),
        featureAccess: FeatureAccess = FeatureAccess.ALL_READY,
        media: MediaLibrary = MEDIA,
        ids: IdGenerator = SequentialIds(),
        clock: TestAgentleClock = clock(),
        logger: Logger = Logger.NONE,
    ): ValidationContext = ValidationContext(
        clock = clock,
        apps = apps,
        existingJitais = existing,
        settings = settings,
        featureAccess = featureAccess,
        mediaLibrary = media,
        ids = ids,
        logger = logger,
    )

    fun resource(name: String): String {
        val stream = checkNotNull(Fixtures::class.java.getResourceAsStream("/$name")) { "missing test resource $name" }
        return stream.use { it.readBytes().decodeToString() }
    }

    /** Validates a model reply (origin from [createdBy] unless [origin] is given). */
    fun validateText(
        text: String,
        context: ValidationContext = context(),
        createdBy: CreatedBy = CreatedBy.AI_NATURAL_LANGUAGE,
        origin: RuleOrigin? = null,
        nlRequest: String? = null,
        appSelections: Map<String, String> = emptyMap(),
    ): ValidationReport = RuleValidator.validate(
        ValidationRequest(ValidationInput.ProposalText(text, createdBy), origin, appSelections, nlRequest),
        context,
    )

    fun validateDefinition(
        definition: JitaiDefinition,
        context: ValidationContext = context(),
        origin: RuleOrigin? = null,
    ): ValidationReport = RuleValidator.validate(ValidationRequest(ValidationInput.Definition(definition), origin), context)
}

/** Lowercase UUID-shaped ids `00000000-0000-4000-8000-00000000000n`. */
internal class SequentialIds : IdGenerator {
    private var n = 0

    override fun newId(): String {
        n++
        return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    }
}

internal val ValidationReport.errorCodes: List<IssueCode> get() = errors.map { it.code }

internal val ValidationReport.confirmCodes: List<IssueCode> get() = confirmItems.map { it.code }

internal val ValidationReport.warningCodes: List<IssueCode> get() = warnings.map { it.code }

/** Every issue with [code], in report order. */
internal fun ValidationReport.issues(code: IssueCode): List<ValidationIssue> = (errors + confirmItems + warnings).filter { it.code == code }
