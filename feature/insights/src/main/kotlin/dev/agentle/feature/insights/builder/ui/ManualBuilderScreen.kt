package dev.agentle.feature.insights.builder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.analytics.features.FeatureArgKind
import dev.agentle.analytics.features.FeatureAvailability
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.insights.R
import dev.agentle.feature.insights.builder.BuilderForm
import dev.agentle.feature.insights.builder.BuilderValidation
import dev.agentle.feature.insights.builder.ConditionRow
import dev.agentle.feature.insights.builder.ConditionsForm
import dev.agentle.feature.insights.builder.ContentType
import dev.agentle.feature.insights.builder.ExpiryMode
import dev.agentle.feature.insights.builder.FieldIssue
import dev.agentle.feature.insights.builder.Fields
import dev.agentle.feature.insights.builder.GroupMode
import dev.agentle.feature.insights.builder.LocalIssue
import dev.agentle.feature.insights.builder.ManualBuilderUiState
import dev.agentle.feature.insights.builder.ManualBuilderViewModel
import dev.agentle.feature.insights.builder.RowKind
import dev.agentle.feature.insights.builder.RowPart
import dev.agentle.feature.insights.builder.TextPairForm
import dev.agentle.feature.insights.builder.TriggerType
import dev.agentle.feature.insights.ui.CollectEffects
import dev.agentle.feature.insights.ui.ErrorState
import dev.agentle.feature.insights.ui.Gutter
import dev.agentle.feature.insights.ui.InsightsScaffold
import dev.agentle.feature.insights.ui.LoadingState
import dev.agentle.feature.insights.ui.Picker
import dev.agentle.feature.insights.ui.SectionTitle
import dev.agentle.feature.insights.ui.StatusLine
import dev.agentle.feature.insights.ui.featureLabel
import dev.agentle.feature.insights.ui.issueText
import dev.agentle.feature.insights.ui.label
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.Tone
import dev.agentle.jitai.dsl.model.WeekDay
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.TypedLiterals
import dev.agentle.jitai.dsl.validation.IssueSeverity

/** Everything the manual builder can do. */
internal data class BuilderActions(
    val update: (BuilderForm) -> Unit = {},
    val addRow: (String, RowKind) -> Unit = { _, _ -> },
    val removeRow: (String, Int) -> Unit = { _, _ -> },
    val updateRow: (String, ConditionRow) -> Unit = { _, _ -> },
    val replacePreserved: (String) -> Unit = {},
    val saveDraft: () -> Unit = {},
    val activate: () -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
internal fun ManualBuilderRoute(viewModel: ManualBuilderViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, navigator, snackbar)
    ManualBuilderScreen(
        state = state,
        snackbar = snackbar,
        actions = BuilderActions(
            update = viewModel::update,
            addRow = viewModel::addRow,
            removeRow = viewModel::removeRow,
            updateRow = viewModel::updateRow,
            replacePreserved = viewModel::replacePreserved,
            saveDraft = viewModel::saveDraft,
            activate = viewModel::activate,
            retry = viewModel::retry,
        ),
        onBack = navigator::back,
    )
}

@Composable
internal fun ManualBuilderScreen(
    state: ManualBuilderUiState,
    actions: BuilderActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
) {
    val title = stringResource(
        if ((state as? ManualBuilderUiState.Editing)?.isEdit == true) R.string.builder_edit_title else R.string.builder_title,
    )
    InsightsScaffold(title, snackbar, onBack) { padding ->
        when (state) {
            ManualBuilderUiState.Loading -> LoadingState(Modifier.padding(padding))

            is ManualBuilderUiState.Error -> ErrorState(state.error, actions.retry, Modifier.padding(padding))

            is ManualBuilderUiState.Editing -> Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditingForm(state, actions)
            }
        }
    }
}

@Composable
private fun EditingForm(state: ManualBuilderUiState.Editing, actions: BuilderActions) {
    val form = state.form
    val v = state.validation
    val update = actions.update
    Preview(v)
    SectionTitle(stringResource(R.string.builder_basics))
    TextInput(stringResource(R.string.builder_name), form.name, v, Fields.NAME) { update(form.copy(name = it)) }
    TextInput(stringResource(R.string.builder_description), form.description, v, Fields.DESCRIPTION) { update(form.copy(description = it)) }
    Picker(stringResource(R.string.builder_kind), form.kind, JitaiKind.entries, { label(it) }, { update(form.copy(kind = it)) })
    Picker(
        stringResource(R.string.builder_category),
        form.category,
        JitaiCategory.entries,
        { label(it) },
        { update(form.copy(category = it)) },
    )
    Issues(v.issuesFor(Fields.CATEGORY))
    if (form.isIntervention) TriggerSection(form, v, update)
    WindowSection(form, v, update)
    ConditionsSection(
        Fields.TREE_CONDITIONS,
        stringResource(R.string.builder_conditions),
        form.conditions,
        Fields.CONDITIONS,
        state,
        actions,
    )
    if (form.isIntervention) {
        ConditionsSection(
            Fields.TREE_REQUIREMENTS,
            stringResource(R.string.builder_requirements),
            form.requirements,
            Fields.REQUIREMENTS,
            state,
            actions,
        )
        FrequencySection(form, v, update)
        DeliverySection(form, v, update)
        ContentSection(form, v, state, update)
        SnoozeSection(form, v, update)
    } else {
        SuppressionSection(form, v, update)
    }
    ExpirySection(form, v, update)
    CheckRow(
        stringResource(R.string.builder_confirm_unknown),
        form.confirmUnknownOverrides,
    ) { update(form.copy(confirmUnknownOverrides = it)) }
    Issues(v.general)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.needsApproval) StatusLine(R.drawable.ic_insights_info, stringResource(R.string.builder_needs_approval))
        OutlinedButton(onClick = actions.saveDraft, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.builder_save_draft))
        }
        if (!state.needsApproval) {
            Button(onClick = actions.activate, enabled = !state.saving && v.canActivate, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.builder_activate))
            }
        }
    }
}

@Composable
private fun Preview(v: BuilderValidation) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.builder_preview), style = MaterialTheme.typography.titleSmall)
            Text(v.preview)
            if (v.errorCount > 0) StatusLine(R.drawable.ic_insights_error, stringResource(R.string.builder_error_count, v.errorCount))
            if (v.confirmCount > 0) {
                StatusLine(
                    R.drawable.ic_insights_warning,
                    stringResource(R.string.builder_confirm_count, v.confirmCount),
                )
            }
            if (v.canActivate) StatusLine(R.drawable.ic_insights_check, stringResource(R.string.builder_ready))
        }
    }
}

@Composable
private fun TriggerSection(form: BuilderForm, v: BuilderValidation, update: (BuilderForm) -> Unit) {
    val trigger = form.trigger
    SectionTitle(stringResource(R.string.builder_when))
    Picker(
        stringResource(R.string.builder_trigger),
        trigger.type,
        TriggerType.entries,
        { label(it) },
        { update(form.copy(trigger = trigger.copy(type = it))) },
    )
    Issues(v.issuesFor(Fields.TRIGGER))
    when (trigger.type) {
        TriggerType.DAILY_AT -> {
            TextInput(stringResource(R.string.builder_times), trigger.dailyTimes.joinToString(", "), v, Fields.TIMES) { text ->
                update(form.copy(trigger = trigger.copy(dailyTimes = text.split(',').map { it.trim() }.filter { it.isNotEmpty() })))
            }
            trigger.dailyTimes.indices.forEach { Issues(v.issuesFor(Fields.time(it))) }
            NumberInput(
                stringResource(R.string.builder_lateness),
                trigger.lateness,
                v,
                Fields.LATENESS,
            ) { update(form.copy(trigger = trigger.copy(lateness = it))) }
        }

        TriggerType.INTERVAL ->
            NumberInput(stringResource(R.string.builder_interval), trigger.intervalMinutes, v, Fields.INTERVAL) {
                update(form.copy(trigger = trigger.copy(intervalMinutes = it)))
            }

        TriggerType.EVENT -> {
            Chips(JitaiEventType.entries.filter { it.isAvailable }, trigger.events.toSet(), { label(it) }) { event, on ->
                val events = if (on) trigger.events + event else trigger.events - event
                update(form.copy(trigger = trigger.copy(events = events)))
            }
            Issues(v.issuesFor(Fields.EVENTS))
            NumberInput(stringResource(R.string.builder_debounce), trigger.debounceSeconds, v, Fields.DEBOUNCE) {
                update(form.copy(trigger = trigger.copy(debounceSeconds = it)))
            }
        }
    }
}

@Composable
private fun WindowSection(form: BuilderForm, v: BuilderValidation, update: (BuilderForm) -> Unit) {
    val window = form.window
    SectionTitle(stringResource(R.string.builder_window))
    CheckRow(stringResource(R.string.builder_window_on), window.enabled) { update(form.copy(window = window.copy(enabled = it))) }
    Issues(v.issuesFor(Fields.WINDOW))
    if (window.enabled) {
        TextInput(
            stringResource(R.string.builder_start),
            window.start,
            v,
            Fields.WINDOW_START,
        ) { update(form.copy(window = window.copy(start = it))) }
        TextInput(
            stringResource(R.string.builder_end),
            window.end,
            v,
            Fields.WINDOW_END,
        ) { update(form.copy(window = window.copy(end = it))) }
        Chips(WeekDay.entries, window.days, { label(it) }) { day, on ->
            update(form.copy(window = window.copy(days = if (on) window.days + day else window.days - day)))
        }
        Issues(v.issuesFor(Fields.WINDOW_DAYS))
    }
}

@Composable
private fun ConditionsSection(
    tree: String,
    title: String,
    conditions: ConditionsForm,
    field: String,
    state: ManualBuilderUiState.Editing,
    actions: BuilderActions,
) {
    val v = state.validation
    SectionTitle(title)
    Issues(v.issuesFor(field))
    val preserved = conditions.preserved
    if (preserved != null) {
        Text(stringResource(R.string.builder_preserved))
        TextButton(onClick = { actions.replacePreserved(tree) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.builder_replace_preserved))
        }
        return
    }
    if (conditions.rows.size > 1) {
        Picker(stringResource(R.string.builder_combine), conditions.mode, GroupMode.entries, { label(it) }, { mode ->
            actions.update(
                state.form.let { f ->
                    if (tree == Fields.TREE_REQUIREMENTS) {
                        f.copy(
                            requirements = f.requirements.copy(mode = mode),
                        )
                    } else {
                        f.copy(conditions = f.conditions.copy(mode = mode))
                    }
                },
            )
        })
    }
    conditions.rows.forEach { row -> RowEditor(tree, row, state, actions) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { actions.addRow(tree, RowKind.FEATURE) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.builder_add_condition))
        }
        TextButton(onClick = { actions.addRow(tree, RowKind.TIME_WINDOW) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.builder_add_time))
        }
    }
}

@Composable
private fun RowEditor(tree: String, row: ConditionRow, state: ManualBuilderUiState.Editing, actions: BuilderActions) {
    val v = state.validation
    val set: (ConditionRow) -> Unit = { actions.updateRow(tree, it) }
    fun field(part: RowPart) = Fields.row(tree, row.key, part)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Issues(v.issuesFor(field(RowPart.ROW)))
            if (row.kind == RowKind.TIME_WINDOW) {
                TextInput(stringResource(R.string.builder_start), row.start, v, field(RowPart.START)) { set(row.copy(start = it)) }
                TextInput(stringResource(R.string.builder_end), row.end, v, field(RowPart.END)) { set(row.copy(end = it)) }
            } else {
                FeatureRow(row, v, state, ::field, set)
            }
            CheckRow(stringResource(R.string.builder_negate), row.negate) { set(row.copy(negate = it)) }
            TextButton(onClick = { actions.removeRow(tree, row.key) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.builder_remove))
            }
        }
    }
}

@Composable
private fun FeatureRow(
    row: ConditionRow,
    v: BuilderValidation,
    state: ManualBuilderUiState.Editing,
    field: (RowPart) -> String,
    set: (ConditionRow) -> Unit,
) {
    val features = RealtimeFeatureCatalog.all.filter { it.availability == FeatureAvailability.Available }.map { it.id }
    Picker(
        stringResource(R.string.builder_feature),
        row.featureId,
        features,
        { featureLabel(it) },
        { set(ConditionRow.forFeature(row.key, it)) },
    )
    Issues(v.issuesFor(field(RowPart.FEATURE)))
    val definition = RealtimeFeatureCatalog[row.featureId] ?: return
    val operators = TypedLiterals.allowedOperators(definition.type).toList()
    Picker(stringResource(R.string.builder_operator), row.operator, operators, { label(it) }, { set(row.copy(operator = it)) })
    Issues(v.issuesFor(field(RowPart.OPERATOR)))
    when {
        row.operator == dev.agentle.jitai.dsl.rule.Operator.IN ->
            TextInput(stringResource(R.string.builder_values), row.values.joinToString(", "), v, field(RowPart.VALUES)) { text ->
                set(row.copy(values = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            }

        row.operator == dev.agentle.jitai.dsl.rule.Operator.BETWEEN -> {
            TextInput(stringResource(R.string.builder_min), row.value, v, field(RowPart.MIN)) { set(row.copy(value = it)) }
            TextInput(stringResource(R.string.builder_max), row.secondValue, v, field(RowPart.MAX)) { set(row.copy(secondValue = it)) }
        }

        definition.type == FeatureType.ENUM ->
            Picker(
                stringResource(R.string.builder_value),
                row.value,
                definition.enumValues,
                { it.lowercase().replace('_', ' ') },
                { set(row.copy(value = it)) },
            )

        definition.type == FeatureType.BOOL ->
            Picker(stringResource(R.string.builder_value), row.value, listOf("true", "false"), {
                stringResource(if (it == "true") R.string.builder_yes else R.string.builder_no)
            }, { set(row.copy(value = it)) })

        else -> TextInput(
            stringResource(R.string.builder_value) + definition.unit?.let { " ($it)" }.orEmpty(),
            row.value,
            v,
            field(RowPart.VALUE),
        ) { set(row.copy(value = it)) }
    }
    Issues(v.issuesFor(field(RowPart.VALUE)))
    definition.args.forEach { arg ->
        val value = row.args[arg.name].orEmpty()
        val onArg: (String) -> Unit = { set(row.copy(args = row.args + (arg.name to it))) }
        when (arg.kind) {
            FeatureArgKind.PACKAGE -> if (state.apps.isNotEmpty()) {
                Picker(stringResource(R.string.builder_app), value, state.apps.map { it.packageName }, { pkg ->
                    state.apps.firstOrNull { it.packageName == pkg }?.label ?: stringResource(R.string.builder_choose)
                }, onArg)
            } else {
                TextInput(stringResource(R.string.builder_app), value, v, field(RowPart.PACKAGE), onArg)
            }

            FeatureArgKind.APP_CATEGORY ->
                Picker(
                    stringResource(R.string.builder_app_category),
                    value,
                    RealtimeFeatureCatalog.APP_CATEGORIES,
                    { it.lowercase().replace('_', ' ') },
                    onArg,
                )

            FeatureArgKind.SINCE -> TextInput(stringResource(R.string.builder_since), value, v, field(RowPart.SINCE), onArg)

            FeatureArgKind.JITAI_REF -> Picker(
                stringResource(R.string.builder_rule_ref),
                value,
                listOf("self") + state.otherRules.map { it.id },
                { id ->
                    if (id == "self") {
                        stringResource(
                            R.string.builder_this_rule,
                        )
                    } else {
                        state.otherRules.firstOrNull { it.id == id }?.name ?: id
                    }
                },
                onArg,
            )
        }
    }
    listOf(RowPart.PACKAGE, RowPart.CATEGORY, RowPart.SINCE, RowPart.JITAI).forEach { Issues(v.issuesFor(field(it))) }
    Picker(
        stringResource(R.string.builder_unknown),
        row.onUnknown,
        listOf(null, OnUnknown.ASSUME_FALSE, OnUnknown.ASSUME_TRUE),
        { it?.let { value -> label(value) } ?: stringResource(R.string.builder_unknown_skip) },
        { set(row.copy(onUnknown = it)) },
    )
    Issues(v.issuesFor(field(RowPart.ON_UNKNOWN)))
}

@Composable
private fun FrequencySection(form: BuilderForm, v: BuilderValidation, update: (BuilderForm) -> Unit) {
    val f = form.frequency
    SectionTitle(stringResource(R.string.builder_frequency))
    Text(stringResource(R.string.builder_limits_hint), style = MaterialTheme.typography.bodySmall)
    NumberInput(
        stringResource(R.string.builder_cooldown),
        f.cooldownMinutes,
        v,
        Fields.COOLDOWN,
    ) { update(form.copy(frequency = f.copy(cooldownMinutes = it))) }
    NumberInput(
        stringResource(R.string.builder_max_day),
        f.maxPerDay,
        v,
        Fields.MAX_PER_DAY,
    ) { update(form.copy(frequency = f.copy(maxPerDay = it))) }
    NumberInput(
        stringResource(R.string.builder_max_week),
        f.maxPerWeek,
        v,
        Fields.MAX_PER_WEEK,
    ) { update(form.copy(frequency = f.copy(maxPerWeek = it))) }
    NumberInput(
        stringResource(R.string.builder_priority),
        f.priority,
        v,
        Fields.PRIORITY,
    ) { update(form.copy(frequency = f.copy(priority = it))) }
}

@Composable
private fun DeliverySection(form: BuilderForm, v: BuilderValidation, update: (BuilderForm) -> Unit) {
    val d = form.delivery
    SectionTitle(stringResource(R.string.builder_delivery))
    Picker(stringResource(R.string.builder_channel), d.channel, DeliveryChannel.entries - DeliveryChannel.NONE, { label(it) }, {
        update(form.copy(delivery = d.copy(channel = it)))
    })
    Issues(v.issuesFor(Fields.CHANNEL))
    CheckRow(
        stringResource(R.string.builder_quiet_hours),
        d.allowDuringQuietHours,
    ) { update(form.copy(delivery = d.copy(allowDuringQuietHours = it))) }
    Issues(v.issuesFor(Fields.QUIET_HOURS))
    NumberInput(stringResource(R.string.builder_timeout), d.notificationTimeoutMinutes, v, Fields.TIMEOUT) {
        update(form.copy(delivery = d.copy(notificationTimeoutMinutes = it)))
    }
}

@Composable
private fun ContentSection(form: BuilderForm, v: BuilderValidation, state: ManualBuilderUiState.Editing, update: (BuilderForm) -> Unit) {
    val c = form.content
    SectionTitle(stringResource(R.string.builder_content))
    Picker(
        stringResource(R.string.builder_content_type),
        c.type,
        ContentType.entries,
        { label(it) },
        { update(form.copy(content = c.copy(type = it))) },
    )
    Issues(v.issuesFor(Fields.CONTENT))
    when (c.type) {
        ContentType.VARIANTS -> {
            c.variants.forEachIndexed { index, pair ->
                val replace: (TextPairForm) -> Unit = { p ->
                    update(
                        form.copy(content = c.copy(variants = c.variants.mapIndexed { i, old -> if (i == index) p else old })),
                    )
                }
                TextInput(
                    stringResource(R.string.builder_variant_title, index + 1),
                    pair.title,
                    v,
                    Fields.variant(index, "title"),
                ) { replace(pair.copy(title = it)) }
                TextInput(
                    stringResource(R.string.builder_variant_body, index + 1),
                    pair.body,
                    v,
                    Fields.variant(index, "body"),
                ) { replace(pair.copy(body = it)) }
            }
            TextButton(
                onClick = { update(form.copy(content = c.copy(variants = c.variants + TextPairForm()))) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.builder_add_variant))
            }
            Issues(v.issuesFor(Fields.CONTENT_VARIANTS))
        }

        else -> {
            if (c.type == ContentType.AI_TEXT) {
                TextInput(
                    stringResource(R.string.builder_goal),
                    c.goal,
                    v,
                    Fields.CONTENT_GOAL,
                ) { update(form.copy(content = c.copy(goal = it))) }
                Picker(
                    stringResource(R.string.builder_tone),
                    c.tone,
                    Tone.entries,
                    { label(it) },
                    { update(form.copy(content = c.copy(tone = it))) },
                )
            }
            if (c.type == ContentType.MEDIA) {
                Picker(stringResource(R.string.builder_media), c.assetId, state.media.map { it.assetId }, { id ->
                    state.media.firstOrNull { it.assetId == id }?.label ?: stringResource(R.string.builder_choose)
                }, { update(form.copy(content = c.copy(assetId = it))) })
                Issues(v.issuesFor(Fields.CONTENT_ASSET))
            }
            val fallback = c.type == ContentType.AI_TEXT || c.type == ContentType.MEDIA
            TextInput(
                stringResource(if (fallback) R.string.builder_fallback_title else R.string.builder_title_field),
                c.title,
                v,
                Fields.CONTENT_TITLE,
            ) {
                update(form.copy(content = c.copy(title = it)))
            }
            TextInput(
                stringResource(if (fallback) R.string.builder_fallback_body else R.string.builder_body),
                c.body,
                v,
                Fields.CONTENT_BODY,
            ) {
                update(form.copy(content = c.copy(body = it)))
            }
        }
    }
}

@Composable
private fun SnoozeSection(form: BuilderForm, v: BuilderValidation, update: (BuilderForm) -> Unit) {
    val s = form.snooze
    SectionTitle(stringResource(R.string.builder_snooze))
    Picker(
        stringResource(R.string.builder_snooze_mode),
        s.mode,
        SnoozeMode.entries,
        { label(it) },
        { update(form.copy(snooze = s.copy(mode = it))) },
    )
    Chips(SnoozeOption.entries, s.options, { label(it) }) { option, on ->
        update(form.copy(snooze = s.copy(options = if (on) s.options + option else s.options - option)))
    }
    Issues(v.issuesFor(Fields.SNOOZE))
}

@Composable
private fun SuppressionSection(form: BuilderForm, v: BuilderValidation, update: (BuilderForm) -> Unit) {
    SectionTitle(stringResource(R.string.builder_suppression))
    Chips(JitaiCategory.entries, form.suppressionCategories, { label(it) }) { category, on ->
        update(form.copy(suppressionCategories = if (on) form.suppressionCategories + category else form.suppressionCategories - category))
    }
    Issues(v.issuesFor(Fields.SUPPRESSION))
}

@Composable
private fun ExpirySection(form: BuilderForm, v: BuilderValidation, update: (BuilderForm) -> Unit) {
    val e = form.expiry
    SectionTitle(stringResource(R.string.builder_expiry))
    val modes = if (e.keep != null) ExpiryMode.entries else ExpiryMode.entries - ExpiryMode.KEEP
    Picker(stringResource(R.string.builder_expiry_mode), e.mode, modes, { label(it) }, { update(form.copy(expiry = e.copy(mode = it))) })
    if (e.mode == ExpiryMode.AFTER_DAYS) {
        NumberInput(stringResource(R.string.builder_days), e.days, v, Fields.EXPIRY) { update(form.copy(expiry = e.copy(days = it))) }
    } else {
        Issues(v.issuesFor(Fields.EXPIRY))
    }
}

@Composable
private fun TextInput(label: String, value: String, v: BuilderValidation, field: String, onChange: (String) -> Unit) {
    val issues = v.issuesFor(field)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = issues.any { it.severity == IssueSeverity.ERROR },
        modifier = Modifier.fillMaxWidth(),
    )
    Issues(issues)
}

@Composable
private fun NumberInput(label: String, value: String, v: BuilderValidation, field: String, onChange: (String) -> Unit) {
    val issues = v.issuesFor(field)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = issues.any { it.severity == IssueSeverity.ERROR },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Issues(issues)
}

/** Each issue as an icon plus its localized message, on the field it concerns. */
@Composable
internal fun Issues(issues: List<FieldIssue>) {
    issues.forEach { issue ->
        val text = when {
            issue.local == LocalIssue.NOT_A_NUMBER -> stringResource(R.string.builder_local_not_a_number)
            issue.local == LocalIssue.DAYS_OUT_OF_RANGE -> stringResource(R.string.builder_local_days_range)
            issue.code != null -> issueText(issue.code, issue.params)
            else -> ""
        }
        val icon = when (issue.severity) {
            IssueSeverity.ERROR -> R.drawable.ic_insights_error
            IssueSeverity.CONFIRM -> R.drawable.ic_insights_warning
            IssueSeverity.WARNING -> R.drawable.ic_insights_info
        }
        StatusLine(icon, text)
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: Set<T>, text: @Composable (T) -> String, onToggle: (T, Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val on = option in selected
            FilterChip(selected = on, onClick = { onToggle(option, !on) }, label = { Text(text(option)) })
        }
    }
}
