package dev.agentle.feature.insights.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.flatMap
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.port.BuilderEnvironment
import dev.agentle.feature.insights.port.JitaiBuilderPort
import dev.agentle.feature.insights.port.MediaAsset
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.LifecycleEvent
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.nl.InstalledApp
import dev.agentle.jitai.dsl.validation.RuleLimits
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.ValidationReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The manual JITAI builder (spec §18, R10 §3, §11): a form that produces a [JitaiDefinition], validated with
 * `RuleValidator` on every change (each issue on its field) and rendered with `RuleRenderer` as a live preview. Saves
 * a draft, or activates the rule when it is valid and every confirm item is resolved. Editing a stored rule creates
 * its next version (`JitaiLifecycle` EDIT, then SAVE to activate).
 */
@HiltViewModel(assistedFactory = ManualBuilderViewModel.Factory::class)
internal class ManualBuilderViewModel @AssistedInject constructor(
    @Assisted private val editJitaiId: String?,
    private val port: JitaiBuilderPort,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ManualBuilderUiState>(ManualBuilderUiState.Loading)
    private val effectChannel = Channel<ScreenEffect>(Channel.BUFFERED)
    private var report: ValidationReport? = null
    private var built: BuiltRule? = null

    val state: StateFlow<ManualBuilderUiState> = mutableState.asStateFlow()
    val effects: Flow<ScreenEffect> = effectChannel.receiveAsFlow()

    init {
        load()
    }

    fun retry() = load()

    /** Replaces the form with [form] (every field edit) and validates it. */
    fun update(form: BuilderForm) {
        val editing = mutableState.value as? ManualBuilderUiState.Editing ?: return
        mutableState.value = revalidate(editing.copy(form = form))
    }

    /** Adds a condition row to [tree] ([Fields.TREE_CONDITIONS] or [Fields.TREE_REQUIREMENTS]). */
    fun addRow(tree: String, kind: RowKind = RowKind.FEATURE) {
        val editing = mutableState.value as? ManualBuilderUiState.Editing ?: return
        val form = editing.form
        val key = (form.conditions.rows + form.requirements.rows).maxOfOrNull { it.key }?.plus(1) ?: 0
        val row = if (kind == RowKind.FEATURE) {
            ConditionRow.forFeature(key, ConditionRow.DEFAULT_FEATURE)
        } else {
            ConditionRow(key, kind = RowKind.TIME_WINDOW)
        }
        update(form.updateTree(tree) { it.copy(rows = it.rows + row) })
    }

    fun removeRow(tree: String, key: Int) {
        val editing = mutableState.value as? ManualBuilderUiState.Editing ?: return
        update(editing.form.updateTree(tree) { it.copy(rows = it.rows.filterNot { row -> row.key == key }) })
    }

    fun updateRow(tree: String, row: ConditionRow) {
        val editing = mutableState.value as? ManualBuilderUiState.Editing ?: return
        update(editing.form.updateTree(tree) { it.copy(rows = it.rows.map { old -> if (old.key == row.key) row else old }) })
    }

    /** Drops a preserved condition tree so the rows can replace it. */
    fun replacePreserved(tree: String) {
        val editing = mutableState.value as? ManualBuilderUiState.Editing ?: return
        update(editing.form.updateTree(tree) { ConditionsForm(mode = it.mode) })
    }

    /** Saves the rule as a draft (not running). */
    fun saveDraft() = save(activate = false)

    /** Validates once more and turns the rule on. */
    fun activate() = save(activate = true)

    fun fix(route: AppRoute) {
        effectChannel.trySend(ScreenEffect.Navigate(route))
    }

    private fun load() {
        mutableState.value = ManualBuilderUiState.Loading
        viewModelScope.launch {
            val result = port.environment().flatMap { environment ->
                val form = editJitaiId?.let { id -> port.definition(id).flatMap(::editableForm) }
                    ?: Outcome.success(BuilderForm.new(environment.context.ids.newId()))
                form.flatMap { Outcome.success(environment to it) }
            }
            mutableState.value = when (result) {
                is Outcome.Success -> revalidate(editingState(result.value.first, result.value.second))
                is Outcome.Failure -> ManualBuilderUiState.Error(LoadError.of(result.error))
            }
        }
    }

    private fun editableForm(definition: JitaiDefinition): Outcome<BuilderForm> = if (definition.status == JitaiStatus.ARCHIVED) {
        Outcome.failure(AppError.ValidationError(listOf(JitaiLifecycle.ILLEGAL_TRANSITION)))
    } else {
        Outcome.success(formOf(definition))
    }

    private fun editingState(environment: BuilderEnvironment, form: BuilderForm): ManualBuilderUiState.Editing {
        val apps = environment.context.apps?.launcherApps().orEmpty().sortedBy { it.label.lowercase() }
        val origin = RuleOrigin.of(form.base.createdBy)
        return ManualBuilderUiState.Editing(
            form = form,
            environment = environment,
            apps = apps.toImmutableList(),
            media = environment.mediaAssets.toImmutableList(),
            otherRules = environment.context.existingJitais
                .filter { it.id != form.base.id && it.status != JitaiStatus.ARCHIVED }
                .map { RuleChoice(it.id, it.name) }
                .toImmutableList(),
            limits = RuleLimits.of(origin),
            validation = BuilderValidation(),
            isEdit = !form.base.isNew,
        )
    }

    private fun revalidate(editing: ManualBuilderUiState.Editing): ManualBuilderUiState.Editing {
        val context = editing.environment.context
        val rule = editing.form.toDefinition(context.clock.now(), context.clock.zone())
        val (newReport, validation) = validate(rule, context, context.renderOptions(editing.apps))
        built = rule
        report = newReport
        return editing.copy(validation = validation)
    }

    private fun save(activate: Boolean) {
        val editing = mutableState.value as? ManualBuilderUiState.Editing ?: return
        if (editing.saving) return
        val current = revalidate(editing)
        val rule = built ?: return
        val validation = current.validation
        val allowed = if (activate) validation.canActivate else validation.canSaveDraft
        if (!allowed) {
            mutableState.value = current.copy(showAllIssues = true)
            effectChannel.trySend(ScreenEffect.Message(UserMessage.SAVE_BLOCKED))
            return
        }
        mutableState.value = current.copy(saving = true)
        viewModelScope.launch {
            val stored = report?.definition ?: rule.definition
            val result = transition(stored, editing.form.base, activate, current.environment).flatMap { definition ->
                port.save(definition)
            }
            mutableState.update { state -> (state as? ManualBuilderUiState.Editing)?.copy(saving = false) ?: state }
            when (result) {
                is Outcome.Success -> {
                    effectChannel.send(ScreenEffect.Message(if (activate) UserMessage.ACTIVATED else UserMessage.SAVED_AS_DRAFT))
                    effectChannel.send(ScreenEffect.Back)
                }

                is Outcome.Failure -> effectChannel.send(ScreenEffect.Message(saveFailure(result.error)))
            }
        }
    }

    /**
     * The lifecycle of the save: a new rule stays DRAFT or is saved (SAVE -> ACTIVE); a stored rule becomes its next
     * version (EDIT -> DRAFT), then SAVE when activating. An activated rule is re-validated once more.
     */
    private fun transition(
        definition: JitaiDefinition,
        base: RuleBase,
        activate: Boolean,
        environment: BuilderEnvironment,
    ): Outcome<JitaiDefinition> {
        val clock = environment.context.clock
        val draft = if (base.isNew) {
            Outcome.success(definition.copy(status = JitaiStatus.DRAFT, enabled = false))
        } else {
            JitaiLifecycle.apply(definition.copy(status = base.status), LifecycleEvent.EDIT, clock)
        }
        if (!activate) return draft
        return draft.flatMap { JitaiLifecycle.apply(it, LifecycleEvent.SAVE, clock) }.flatMap { active ->
            val verdict = RuleValidator.revalidate(active, environment.context.mediaLibrary)
            if (verdict.isValid) {
                Outcome.success(active)
            } else {
                Outcome.failure(AppError.ValidationError(verdict.codes.map { it.name }))
            }
        }
    }

    private fun saveFailure(error: AppError): UserMessage =
        if (error is AppError.ValidationError) UserMessage.SAVE_BLOCKED else UserMessage.SAVE_FAILED

    @AssistedFactory
    interface Factory {
        fun create(editJitaiId: String?): ManualBuilderViewModel
    }
}

internal sealed interface ManualBuilderUiState {
    data object Loading : ManualBuilderUiState

    data class Error(val error: LoadError) : ManualBuilderUiState

    /**
     * The form and its validation.
     *
     * @property apps launcher-visible apps for app arguments (empty when the app list is unavailable).
     * @property otherRules the user's other rules, for "this reminder / another reminder" arguments.
     * @property limits the limit column of the rule's origin (USER, or AI for an edited AI rule).
     * @property showAllIssues true after a blocked save: the screen expands every section with an issue.
     */
    data class Editing(
        val form: BuilderForm,
        val environment: BuilderEnvironment,
        val apps: ImmutableList<InstalledApp>,
        val media: ImmutableList<MediaAsset>,
        val otherRules: ImmutableList<RuleChoice>,
        val limits: RuleLimits,
        val validation: BuilderValidation,
        val isEdit: Boolean,
        val saving: Boolean = false,
        val showAllIssues: Boolean = false,
    ) : ManualBuilderUiState
}

internal data class RuleChoice(val id: String, val name: String)

internal fun BuilderForm.updateTree(tree: String, transform: (ConditionsForm) -> ConditionsForm): BuilderForm =
    if (tree == Fields.TREE_REQUIREMENTS) copy(requirements = transform(requirements)) else copy(conditions = transform(conditions))
