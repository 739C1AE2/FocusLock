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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.ui.components.DatePickerDialog
import com.github739c1ae2.focuslock.ui.components.TimeOfDayPickerDialog
import com.github739c1ae2.focuslock.util.ValidatedField
import com.github739c1ae2.focuslock.util.applyImeInsetsAndConsumeScaffoldPadding
import com.github739c1ae2.focuslock.util.calculateImeAwareScaffoldInsets
import com.github739c1ae2.focuslock.util.minutesToClockString
import java.time.LocalDate

private enum class DateTarget { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeTableEditorScreen(
    timeTableId: Long,
    isSinglePane: Boolean,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: TimeTableEditorViewModel = hiltViewModel<TimeTableEditorViewModel, TimeTableEditorViewModel.Factory> { factory ->
        factory.create(timeTableId)
    }
) {
    val state by viewModel.state.collectAsState()
    var datePickerTarget by remember { mutableStateOf<DateTarget?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.isDirty) {
        onDirtyChange(state.isDirty)
    }

    LaunchedEffect(viewModel.eventFlow) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is TimeTableEditorEvent.Saved -> onSaved()
                is TimeTableEditorEvent.Deleted -> onDeleted()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.time_tables),
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
                    val loaded = state as? TimeTableEditorState.Loaded
                    if (loaded?.draft?.isBase == false) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
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
            is TimeTableEditorState.Loading -> {
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

            is TimeTableEditorState.Failed -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.failed_to_load_schedule))
                }
            }

            is TimeTableEditorState.Loaded -> {
                TimeTableForm(
                    draft = state.draft,
                    innerPadding = innerPadding,
                    onNameChange = { name ->
                        viewModel.updateDraft { it.copy(name = ValidatedField(name)) }
                    },
                    onPickDate = { datePickerTarget = it },
                    onAddSlot = viewModel::addSlot,
                    onRemoveSlot = viewModel::removeSlot,
                    onUpdateSlot = { key, transform ->
                        viewModel.updateDraft { draft ->
                            draft.copy(
                                slots = draft.slots.map { if (it.key == key) transform(it) else it }
                            )
                        }
                    }
                )
            }
        }
    }

    datePickerTarget?.let { target ->
        val draft = (state as? TimeTableEditorState.Loaded)?.draft
        DatePickerDialog(
            initialEpochDay = when (target) {
                DateTarget.START -> draft?.startEpochDay
                DateTarget.END -> draft?.endEpochDay
            },
            onConfirm = { epochDay ->
                viewModel.updateDraft {
                    when (target) {
                        DateTarget.START -> it.copy(startEpochDay = epochDay)
                        DateTarget.END -> it.copy(endEpochDay = epochDay)
                    }
                }
            },
            onDismiss = { datePickerTarget = null }
        )
    }

    if (showDeleteConfirm) {
        val name = (state as? TimeTableEditorState.Loaded)?.draft?.name?.value ?: ""
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_seasonal_confirm_fmt, name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TimeTableForm(
    draft: TimeTableDraft,
    innerPadding: PaddingValues,
    onNameChange: (String) -> Unit,
    onPickDate: (DateTarget) -> Unit,
    onAddSlot: () -> Unit,
    onRemoveSlot: (Long) -> Unit,
    onUpdateSlot: (Long, (TimeSlotDraft) -> TimeSlotDraft) -> Unit,
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
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.seasonal_name)) },
            isError = !draft.name.isValid,
            supportingText = {
                draft.name.errorMessage?.asString()?.let { Text(it) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!draft.isBase) {
            DateField(
                label = stringResource(R.string.seasonal_start_date),
                epochDay = draft.startEpochDay,
                onClick = { onPickDate(DateTarget.START) }
            )
            DateField(
                label = stringResource(R.string.seasonal_end_date),
                epochDay = draft.endEpochDay,
                onClick = { onPickDate(DateTarget.END) },
                isError = draft.dateErrorMessage != null
            )
            draft.dateErrorMessage?.asString()?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.base_time_slots),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onAddSlot) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_time_slot))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                draft.slots.forEachIndexed { index, slot ->
                    TimeSlotEditorRow(
                        index = index,
                        slot = slot,
                        onUpdate = { transform -> onUpdateSlot(slot.key, transform) },
                        onRemove = { onRemoveSlot(slot.key) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DateField(
    label: String,
    epochDay: Long?,
    onClick: () -> Unit,
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = epochDay?.let { LocalDate.ofEpochDay(it).toString() }
                ?: stringResource(R.string.not_set),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            isError = isError,
            label = { Text(label) },
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
private fun TimeSlotEditorRow(
    index: Int,
    slot: TimeSlotDraft,
    onUpdate: ((TimeSlotDraft) -> TimeSlotDraft) -> Unit,
    onRemove: () -> Unit
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.slot_number_fmt, index + 1),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimePickerField(
                label = stringResource(R.string.start_time),
                minute = slot.startMinute,
                onClick = { showStartPicker = true },
                modifier = Modifier.weight(1f)
            )
            TimePickerField(
                label = stringResource(R.string.end_time),
                minute = slot.endMinute,
                onClick = { showEndPicker = true },
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = slot.alias,
            onValueChange = { value -> onUpdate { it.copy(alias = value) } },
            label = { Text(stringResource(R.string.slot_alias)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showStartPicker) {
        TimeOfDayPickerDialog(
            initialMinute = slot.startMinute,
            onConfirm = { minute ->
                onUpdate { it.copy(startMinute = minute) }
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimeOfDayPickerDialog(
            initialMinute = slot.endMinute,
            onConfirm = { minute ->
                onUpdate { it.copy(endMinute = minute) }
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
}

@Composable
private fun TimePickerField(
    label: String,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = minute.minutesToClockString(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { onClick() }
        )
    }
}
