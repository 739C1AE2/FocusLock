package com.github739c1ae2.focuslock.ui.screen.course.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CoursePeriodScheduleEntity
import com.github739c1ae2.focuslock.ui.components.SimpleDatePickerDialog
import com.github739c1ae2.focuslock.util.ValidationResult
import com.github739c1ae2.focuslock.util.applyImeInsetsAndConsumeScaffoldPadding
import com.github739c1ae2.focuslock.util.calculateImeAwareScaffoldInsets
import com.github739c1ae2.focuslock.util.epochDayToDateString
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

@Composable
fun CourseTableSettingsScreen(
    isSinglePane: Boolean,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onOpenPeriodSchedule: (Long) -> Unit,
    onSaved: () -> Unit,
    viewModel: CourseTableSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val periodSchedules by viewModel.periodSchedules.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.isDirty) {
        onDirtyChange(state.isDirty)
    }

    LaunchedEffect(viewModel.eventFlow) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is TableSettingsEvent.Saved -> onSaved()
                is TableSettingsEvent.OpenPeriodSchedule -> onOpenPeriodSchedule(event.periodScheduleId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.course_table_settings_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (isSinglePane) {
                        IconButton(onClick = dropUnlessResumed { onBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(android.R.string.cancel)
                            )
                        }
                    }
                },
                actions = {
                    val loaded = state as? TableSettingsState.Loaded
                    TextButton(
                        onClick = viewModel::load,
                        enabled = state.isDirty
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                    TextButton(
                        onClick = viewModel::save,
                        enabled = state.isDirty && (loaded?.validation?.hasErrors == false)
                    ) {
                        Text(
                            if (state.isDirty) stringResource(R.string.save) else stringResource(R.string.saved)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = state) {
            is TableSettingsState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is TableSettingsState.Loaded -> {
                val defPeriodScheduleName = stringResource(R.string.default_period_schedule_name)
                TableSettingsForm(
                    draft = state.draft,
                    validation = state.validation,
                    periodSchedules = periodSchedules,
                    innerPadding = innerPadding,
                    onUpdate = viewModel::updateDraft,
                    onPickSemesterStart = { showDatePicker = true },
                    onAddPeriodSchedule = { viewModel.addPeriodSchedule(defPeriodScheduleName) },
                    onOpenPeriodSchedule = onOpenPeriodSchedule
                )
            }
        }
    }

    if (showDatePicker) {
        val draft = (state as? TableSettingsState.Loaded)?.draft
        SimpleDatePickerDialog(
            initialEpochDay = draft?.semesterStartEpochDay,
            onConfirm = { epochDay -> viewModel.updateDraft { it.copy(semesterStartEpochDay = epochDay) } },
            onDismiss = { showDatePicker = false }
        )
    }
}

@Composable
private fun TableSettingsForm(
    draft: CourseTableDraft,
    validation: ValidationResult<CourseTableField>,
    periodSchedules: List<CoursePeriodScheduleEntity>,
    innerPadding: PaddingValues,
    onUpdate: ((CourseTableDraft) -> CourseTableDraft) -> Unit,
    onPickSemesterStart: () -> Unit,
    onAddPeriodSchedule: () -> Unit,
    onOpenPeriodSchedule: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val insets = calculateImeAwareScaffoldInsets(innerPadding)
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .applyImeInsetsAndConsumeScaffoldPadding(insets),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = insets.contentPadding + PaddingValues(16.dp)
    ) {
        item {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onUpdate { d -> d.copy(name = it) } },
                label = { Text(stringResource(R.string.import_table_name)) },
                isError = validation.hasError(CourseTableField.Name),
                supportingText = {
                    validation.firstError(CourseTableField.Name)?.asString()?.let { Text(it) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            SemesterStartField(
                epochDay = draft.semesterStartEpochDay,
                onClick = onPickSemesterStart
            )
        }

        item {
            OutlinedTextField(
                value = if (draft.totalWeeks == 0) "" else draft.totalWeeks.toString(),
                onValueChange = { value ->
                    val weeks = value.filter { it.isDigit() }.toIntOrNull() ?: 0
                    onUpdate { d -> d.copy(totalWeeks = weeks) }
                },
                label = { Text(stringResource(R.string.semester_total_weeks)) },
                isError = validation.hasError(CourseTableField.TotalWeeks),
                supportingText = {
                    validation.firstError(CourseTableField.TotalWeeks)?.asString()?.let { Text(it) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            FirstDayDropdown(
                selected = draft.firstDayOfWeek,
                onSelect = { day -> onUpdate { d -> d.copy(firstDayOfWeek = day) } }
            )
        }

        item {
            HorizontalDivider()
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.period_schedules),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onAddPeriodSchedule) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.add_period_schedule))
                }
            }

        }

        items(periodSchedules) { periodSchedule ->
            PeriodScheduleCard(
                periodSchedule = periodSchedule,
                onClick = { onOpenPeriodSchedule(periodSchedule.id) }
            )
        }
    }

}


@Composable
private fun SemesterStartField(
    epochDay: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = epochDay?.let { LocalDate.ofEpochDay(it).toString() }
                ?: stringResource(R.string.not_set),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.semester_start_date)) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { onClick() }
        )
    }
}

@Composable
private fun PeriodScheduleCard(
    periodSchedule: CoursePeriodScheduleEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = periodSchedule.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (periodSchedule.isBase) {
                        stringResource(R.string.permanent)
                    } else {
                        val start = periodSchedule.startEpochDay.epochDayToDateString()
                        val end = periodSchedule.endEpochDay.epochDayToDateString()
                        "$start ~ $end"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun FirstDayDropdown(
    selected: DayOfWeek,
    onSelect: (DayOfWeek) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    val selectedText = selected.getDisplayName(TextStyle.FULL, locale)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.first_day_of_week)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DayOfWeek.entries.forEach { day ->
                DropdownMenuItem(
                    text = { Text(day.getDisplayName(TextStyle.FULL, locale)) },
                    onClick = {
                        onSelect(day)
                        expanded = false
                    }
                )
            }
        }
    }
}
