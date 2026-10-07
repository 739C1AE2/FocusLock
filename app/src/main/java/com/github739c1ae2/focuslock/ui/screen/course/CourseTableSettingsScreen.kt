package com.github739c1ae2.focuslock.ui.screen.course

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.github739c1ae2.focuslock.database.CourseTimeTableEntity
import com.github739c1ae2.focuslock.ui.components.DatePickerDialog
import com.github739c1ae2.focuslock.util.ValidatedField
import com.github739c1ae2.focuslock.util.applyImeInsetsAndConsumeScaffoldPadding
import com.github739c1ae2.focuslock.util.calculateImeAwareScaffoldInsets
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseTableSettingsScreen(
    isSinglePane: Boolean,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onOpenTimeTable: (Long) -> Unit,
    onSaved: () -> Unit,
    viewModel: CourseTableSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val timeTables by viewModel.timeTables.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var timeTableToDelete by remember { mutableStateOf<CourseTimeTableEntity?>(null) }

    LaunchedEffect(state.isDirty) {
        onDirtyChange(state.isDirty)
    }

    LaunchedEffect(viewModel.eventFlow) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is TableSettingsEvent.Saved -> onSaved()
                is TableSettingsEvent.OpenTimeTable -> onOpenTimeTable(event.timeTableId)
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
                        enabled = state.isDirty && (loaded?.draft?.isValid == true)
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
                TableSettingsForm(
                    draft = state.draft,
                    timeTables = timeTables,
                    innerPadding = innerPadding,
                    onUpdate = viewModel::updateDraft,
                    onPickSemesterStart = { showDatePicker = true },
                    onAddSeasonal = viewModel::addSeasonal,
                    onOpenTimeTable = onOpenTimeTable,
                    onDeleteTimeTable = { timeTableToDelete = it }
                )
            }
        }
    }

    if (showDatePicker) {
        val draft = (state as? TableSettingsState.Loaded)?.draft
        DatePickerDialog(
            initialEpochDay = draft?.semesterStartEpochDay,
            onConfirm = { epochDay -> viewModel.updateDraft { it.copy(semesterStartEpochDay = epochDay) } },
            onDismiss = { showDatePicker = false }
        )
    }

    timeTableToDelete?.let { timeTable ->
        AlertDialog(
            onDismissRequest = { timeTableToDelete = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_seasonal_confirm_fmt, timeTable.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTimeTable(timeTable.id)
                    timeTableToDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { timeTableToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TableSettingsForm(
    draft: CourseTableDraft,
    timeTables: List<CourseTimeTableEntity>,
    innerPadding: PaddingValues,
    onUpdate: ((CourseTableDraft) -> CourseTableDraft) -> Unit,
    onPickSemesterStart: () -> Unit,
    onAddSeasonal: () -> Unit,
    onOpenTimeTable: (Long) -> Unit,
    onDeleteTimeTable: (CourseTimeTableEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val insets = calculateImeAwareScaffoldInsets(innerPadding)
    Column(
        modifier = modifier
            .fillMaxSize()
            .applyImeInsetsAndConsumeScaffoldPadding(insets)
            .verticalScroll(rememberScrollState())
            .padding(insets.contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = draft.name.value,
            onValueChange = { onUpdate { d -> d.copy(name = ValidatedField(it)) } },
            label = { Text(stringResource(R.string.import_table_name)) },
            isError = !draft.name.isValid,
            supportingText = {
                draft.name.errorMessage?.asString()?.let { Text(it) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SemesterStartField(
            epochDay = draft.semesterStartEpochDay,
            onClick = onPickSemesterStart
        )

        OutlinedTextField(
            value = if (draft.totalWeeks.value == 0) "" else draft.totalWeeks.value.toString(),
            onValueChange = { value ->
                val weeks = value.filter { it.isDigit() }.toIntOrNull() ?: 0
                onUpdate { d -> d.copy(totalWeeks = ValidatedField(weeks)) }
            },
            label = { Text(stringResource(R.string.semester_total_weeks)) },
            isError = !draft.totalWeeks.isValid,
            supportingText = {
                draft.totalWeeks.errorMessage?.asString()?.let { Text(it) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        FirstDayDropdown(
            selected = draft.firstDayOfWeek,
            onSelect = { day -> onUpdate { d -> d.copy(firstDayOfWeek = day) } }
        )

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.time_tables),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onAddSeasonal) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_seasonal))
            }
        }

        timeTables.forEach { timeTable ->
            TimeTableCard(
                timeTable = timeTable,
                onClick = { onOpenTimeTable(timeTable.id) },
                onDelete = { onDeleteTimeTable(timeTable) }
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
private fun TimeTableCard(
    timeTable: CourseTimeTableEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
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
                    text = timeTable.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (timeTable.isBase) {
                        stringResource(R.string.permanent)
                    } else {
                        val start = timeTable.startEpochDay?.let { LocalDate.ofEpochDay(it) }
                        val end = timeTable.endEpochDay?.let { LocalDate.ofEpochDay(it) }
                        if (start != null && end != null) "$start ~ $end" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (timeTable.isBase) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
