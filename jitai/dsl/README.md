# :jitai:dsl

The JITAI rule language of [R10](../../docs/research/10-jitai-engine-design.md): the stored model, the strict JSON
codec, static analysis, the one validator, the deterministic renderer and the natural-language contract `jitai-nl-v1`.

- Pure Kotlin/JVM, no Android dependency; explicit API, bytecode 17, kotlinx.serialization, kotlinx-datetime.
- Time comes only from `AgentleClock`; the zone and the clock format are always passed in, never read from the JVM
  default (tests run the same documents under `America/St_Johns` + `tr-TR` and `Pacific/Kiritimati` + `ar-EG`).
- Nothing throws on input. Decode failures are `Outcome.Failure(AppError.ValidationError(codes))`, rule problems are
  `ValidationIssue`s, and an internal fault is `E099` with the stage only (no exception text anywhere: issues, errors
  and log lines carry codes, paths and stage names).
- Model output is data: it is decoded into the closed classes below and never executed (R10 §11.7).

`:jitai:engine` compiles against the public names below. They change additively only.

## Contract types

| Type | Package | What it is |
|---|---|---|
| `JitaiDefinition` | `model` | The stored rule (R10 §2.1, §3.1-3.4) with `Trigger`, `ActiveWindow`, `Delivery`, `ContentStrategy` (sealed: `Static`, `Template`, `Variants`, `AiText`, `LocalMedia`), `SnoozePolicy`, `OutcomeSpec`, `SuppressionTarget`, `ExperimentSpec`, `Provenance`, `CreatedBy` |
| `JitaiLifecycle` | `model` | The lifecycle of §3.4 as pure functions: `next(status, event)`, `apply(...)`, `expiresAt`, `pauseIfInvalid` |
| `Condition` | `rule` | Sealed rule tree: `AllOf`, `AnyOf`, `Not`, `LocalTimeIn` and the leaves `Gt`, `Gte`, `Lt`, `Lte`, `Eq`, `Neq`, `Between`, `In` (`FeatureLeaf`, `Comparison`), each leaf with `onUnknown` |
| `RuleLiteral`, `TypedLiteral` | `rule` | A literal as written (`NumberToken` keeps `45`, `45.0`, `4.5e1` apart; `Text`; `Bool`) and as converted by the catalog type; `TypedLiterals` (conversion, operator legality), `OperatorSemantics` |
| `RuleCodec` | `codec` | Strict reader, closed schema walk (discriminator `type`, unknown keys rejected), canonical writer, `contentHash` |
| `FeatureAliases` | `codec` | Alias table for renamed catalog ids: stored definitions and conditions are read with old ids replaced (model output is not migrated) |
| `RuleValidator` | `validation` | The one entry point for every rule source (pipeline S0-S9 of §11.1); `revalidate` for stored rules |
| `ValidationReport`, `ValidationIssue`, `IssueCode` | `validation` | The result: `ValidationIssue(code, path, message, params)` with the fixed templates of §11.2-11.3; errors, confirm items, warnings, normalized definition, rendering, dependencies |
| `ValidationContext` | `validation` | Clock, `AppLabelResolver`, the other rules, settings, `FeatureAccess`, `MediaLibrary`, `IdGenerator`, `Logger` |
| `RuleAnalysis` | `analysis` | Leaves with polarity, depth, node count, dependency set (`FeatureRef`), `onUnknown` effect (§6.4), sound but incomplete unsatisfiability (E027) |
| `RuleRenderer` | `render` | The deterministic English sentence of §13.5 and the content texts with placeholder and app-name parts marked |
| `NlContract` | `nl` | Prompt `jitai-nl-v1`, catalog lines, JitaiProposalSchema v1 (`jitai-proposal-schema-v1.json` resource), the `input` items of each round, `decide` |
| `AppLabelResolver` | `nl` | Port to the launcher-visible apps, with `AppMatcher` (§13.4) and the fixed-list fake `AppLabelResolver.of(apps)` |

## Examples

Every example below runs in `src/test/.../ReadmeExamplesTest.kt`.

### JitaiDefinition (and JitaiLifecycle)

```kotlin
val walk = JitaiDefinition(
    id = "6f1c2b9e-4a7d-4c1e-9b3a-2d5e8f0a1c47",
    name = "Afternoon walk",
    category = JitaiCategory.PHYSICAL_ACTIVITY,
    trigger = Trigger.DailyAt(times = listOf("17:00")),
    conditions = Condition.Lt(feature = "steps_today", value = RuleLiteral.of(3000)),
    content = ContentStrategy.Static(title = "Time for a short walk?", body = "A 10-minute walk now would get you moving."),
    cooldownMinutes = 60,
    maxPerDay = 1,
    maxPerWeek = 7,
    outcome = OutcomeSpec(proximal = OutcomeMetricRef(OutcomeMetric.STEPS_AFTER, windowMinutes = 30)),
    snooze = SnoozePolicy.DEFAULT_DAILY_AT,
    createdBy = CreatedBy.USER_MANUAL,
    createdAt = clock.now(),
    modifiedAt = clock.now(),
)
JitaiLifecycle.apply(walk, LifecycleEvent.SAVE, clock)        // Success: status ACTIVE, enabled = true
JitaiLifecycle.next(JitaiStatus.DRAFT, LifecycleEvent.APPROVE) // null: only PROPOSED rules are approved
```

### Condition

```kotlin
val evening = Condition.AllOf(
    listOf(
        Condition.LocalTimeIn(start = "22:00", end = "02:00"),
        Condition.Gte(
            feature = "app_minutes_since",
            args = mapOf("package" to "com.instagram.android", "since" to "22:00"),
            value = RuleLiteral.of(30),
        ),
    ),
)
RuleCodec.encodeCondition(evening)
// {"type":"all","of":[{"type":"local_time_in","start":"22:00","end":"02:00"},{"type":"gte","feature":"app_minutes_since",
//  "args":{"package":"com.instagram.android","since":"22:00"},"value":30,"onUnknown":null}]}
```

### TypedLiteral

```kotlin
val steps = checkNotNull(RealtimeFeatureCatalog["steps_today"])
TypedLiterals.convert(steps, RuleLiteral.NumberToken("45"))    // Converted(TypedLiteral(IntValue(45)))
TypedLiterals.convert(steps, RuleLiteral.NumberToken("45.0"))  // Rejected(TYPE_MISMATCH) -> E015; same for 4.5e1 and "45"
TypedLiterals.isAllowed(FeatureType.BOOL, Operator.GT)         // false -> E014
```

### RuleCodec

```kotlin
val stored = RuleCodec.decodeDefinition(text).getOrThrow()   // strict read + closed schema walk + decode
RuleCodec.encodeDefinition(stored)                            // canonical form; encode(decode(x)) is a fixed point
RuleCodec.decodeCondition("""{"type":"lt","feature":"steps_today","args":{},"value":45.0,"onUnknown":null}""")
// Success(Lt(value = NumberToken("45.0"))): the token is kept and written back as 45.0 (the validator rejects it, E015)
RuleCodec.decodeCondition("""{"type":"lt","feature":"steps_today","args":{},"value":3,"onUnknown":null,"colour":1}""")
// Failure(ValidationError(codes = [E006], detail = "E006 /colour"))
RuleCodec.contentHash(stored) == RuleCodec.contentHash(stored.copy(id = "other", version = 2)) // true
```

### RuleValidator, ValidationReport, ValidationIssue

```kotlin
val context = ValidationContext(clock = clock, apps = AppLabelResolver.of(installedApps), settings = ValidationSettings())
val report = RuleValidator.validateProposalText(modelReply, context, nlRequest = "Encourage me to walk ...")
report.isValid      // true (R10 §13.6.2)
report.codes        // [C04, W07, W10]: one assumption to confirm, synced step data, generic notification text
report.rendering    // "Every day at 5:00 PM: if your step count today is less than 3,000 steps, send a notification.
                    //  At most 1 per day and 7 per week, at least 1 h apart."
report.definition   // the normalized stored definition, status PROPOSED

val wrong = RuleValidator.validateProposalText(replyWithMaxPerDay4, context)
wrong.errors.single().line // "E042 /jitai/maxPerDay: maxPerDay must be 1-3 for AI rules; got 4."
wrong.repairLines          // the same line, for the single repair round (E099 is never sent to the model)

RuleValidator.revalidate(storedDefinition) // StoredRuleVerdict(jitaiId, catalogVersion, errors): pure, for app upgrades
```

### RuleAnalysis

```kotlin
RuleAnalysis.dependencies(evening)  // [local_time, app_minutes_since{package=com.instagram.android,since=22:00}]
val never = Condition.AllOf(
    listOf(
        Condition.Lt(feature = "steps_today", value = RuleLiteral.of(3000)),
        Condition.Gte(feature = "steps_today", value = RuleLiteral.of(3000)),
    ),
)
RuleAnalysis.unsatisfiable(never)   // Unsatisfiability(path = "", explanation = "steps_today < 3000 and steps_today >= 3000") -> E027
RuleAnalysis.unsatisfiable(evening) // null
```

### RuleRenderer

```kotlin
RuleRenderer.render(walk)
// "Every day at 5:00 PM: if your step count today is less than 3,000 steps, send a notification.
//  At most 1 per day and 7 per week, at least 1 h apart."
RuleRenderer.render(walk, RenderOptions(use24HourClock = true))  // "Every day at 17:00: ..."
RuleRenderer.condition(evening, RenderOptions(appLabels = mapOf("com.instagram.android" to "Instagram")))
// "the time is between 10:00 PM and 2:00 AM and time in Instagram since 10:00 PM is at least 30 min"
```

### NlContract

```kotlin
NlContract.PROMPT_VERSION                                    // "jitai-nl-v1"
val items = NlContract.firstRound(NlSettings(quietHours = null), request) // [DEVELOPER instructions, USER "USER_REQUEST\n..."]
NlContract.decide(wrong, NlRoundState())
// Repair("VALIDATION_ERRORS\nE042 /jitai/maxPerDay: maxPerDay must be 1-3 for AI rules; got 4.\nReturn the corrected proposal only.")
```

### AppLabelResolver

```kotlin
val apps = AppLabelResolver.of(
    listOf(
        InstalledApp("com.instagram.android", "Instagram", usageMinutesLast7Days = 300),
        InstalledApp("com.instagram.barcelona", "Threads, an Instagram app", usageMinutesLast7Days = 20),
    ),
)
apps.resolve("instagram app")              // Resolved(Instagram)
apps.resolve("Threads")                    // Ambiguous([Threads, an Instagram app]) -> confirm item C01
AppLabelResolver.NONE.resolve("Instagram") // NotFound -> C01 with the full app picker
```

### FeatureAliases

```kotlin
FeatureAliases.canonical("steps_so_far", mapOf("steps_so_far" to "steps_today")) // "steps_today"
FeatureAliases.problems() // []: no old id is still a catalog id, every target is one, no chains
```

## Codes added by integrator corrections

| Code | Template | Correction |
|---|---|---|
| E028 | `{kind} "{name}" at {path} cannot be used: capability unavailable: {capability}.` (`location_class`, `LOCATION_CLASS_CHANGED`: `location_background`) | lifecycle-battery-06 |
| E029 | `since` before the active window start, 1 minute to 12 hours before a daily time, or needs a window or daily times | jitai-correctness-17 |
| W08 | `Reacting when {event} is best effort: ...` for USER_PRESENT, SCREEN_INTERACTIVE, POWER_CONNECTED, POWER_DISCONNECTED | lifecycle-battery-07 |
| W09 | `"local_time {op} {value}" at {path} is false from midnight on; ...` | jitai-correctness-17 |
| W10 | `Notifications show a general text unless detailed notifications are on; ...` | jitai-correctness-18 |

Extra template variants for stored-definition fields a proposal cannot set: E048 (`deliveryDeadlineMinutes`,
`experiment.deliverProbability`), E092 (assumption path outside `/jitai`), C02 (video), C03 (quiet hours off).

## Integrator corrections applied

1. Set 1: no exception text in issues, errors or logs (privacy-ai-11); full R10 §2.1 field set and the §13.6.3
   SUPPRESSION golden (privacy-ai-12); `location_class` and `LOCATION_CLASS_CHANGED` rejected with E028 and left out of
   the prompt catalog and schema enums (lifecycle-battery-06); W08 (lifecycle-battery-07); lint ids exactly L1-L8
   (privacy-ai-06).
2. Set 2: E029 and W09 (jitai-correctness-17); W10 and marked placeholder parts in `RuleRenderer.content`
   (jitai-correctness-18); snooze default `RE_EVALUATE_AFTER` for `daily_at` (jitai-correctness-14); `revalidate` is a
   pure function of (definition, catalog version) and `JitaiLifecycle.pauseIfInvalid` pauses failing rules with a
   notice; goldens 13.6.1 and 13.6.3 compile, validate and render as §12.M expects (M4, M5, M7, M9;
   jitai-correctness-01).
3. Set 3: golden corpus `src/test/resources/r10/*.json` for every R10 example, decoded and validated by
   `GoldenCorpusTest` (testing-build-09); catalog renames go through `FeatureAliases` (tested); validation and
   rendering use the zone they are given, never the JVM default (`ZoneIndependenceTest`, testing-build-04).

## Deviations from R10

- `contentHash` also leaves out `id`, `createdBy`, `createdAt`, `expiresAt`, `userConfirmedUnknownOverrides` and
  `provenance`; otherwise two rules never share a hash and W01 could never fire.
- `provenance` has two more fields: `appLabels` (package -> the app name the request used, for display) and
  `expiresInDays` (trial length, turned into `expiresAt` at approval and reused for renewals).
- `enabled` is true exactly in ACTIVE (PAUSED is `enabled = false`), so `enabled && status == ACTIVE` holds only for
  running rules.
- Lint L1-L4, L6, L7 apply to AI-written text only; L5 (E066) to every origin. `{{` in `static` text or an `ai_text`
  goal is E067. E060 counts code points after NFC and trimming.
- SUPPRESSION rules with `contextRequirements` are E053 (a SUPPRESSION never evaluates them, §6.5).
- E027 also covers `local_time` comparison leaves and `not` of a time leaf, and propagates through nested `all`/`any`
  (still sound).
- Decoded inputs (`Proposal`, `Discovered`, `Definition`) are encoded canonically and re-run from S1, so every input
  form gives the same report; trees deeper than 64 are E020 before encoding.
- A C01 `appLabel` that is not resolved yet leaves `definition` null; W02 is reported once per data label;
  re-validation only requires lowercase-UUID block targets and `jitai` args (it does not know the other rules).
- Wording that R10 leaves open is this module's own: event phrases, category labels, typical values, metric and
  channel texts, `At {times} on {days}` schedules and `Ends after Month d, yyyy`.

## Tests

| R10 | Test |
|---|---|
| §12.B operator semantics | `rule/OperatorSemanticsTest` |
| §12.C C5-C10 unknown overrides | `validation/UnknownOverridesTest` |
| §12.M rule side (M4, M5, M7, M9) | `model/JitaiLifecycleTest`, `validation/SafetyGateRulesTest`, `analysis/MinuteMaskTest` |
| §12.Q validation matrix | `validation/ValidationMatrixTest` |
| §12.R R1-R5 natural language | `nl/NlGoldensTest`, `nl/NlContractTest` |
| §3, §4.7, §13.6, §14.7 examples | `GoldenCorpusTest` |
| 2,000 random valid trees (seeded) | `property/RuleTreePropertyTest` |
| 10,000 fuzzed documents (seeded) | `property/ValidatorFuzzTest` |

```
AGENTLE_JVM_ONLY=true ./gradlew :jitai:dsl:test :jitai:dsl:detekt :jitai:dsl:spotlessCheck --max-workers=2
AGENTLE_JVM_ONLY=true ./gradlew :jitai:dsl:koverXmlReport --max-workers=2
```
