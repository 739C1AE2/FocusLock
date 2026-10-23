package com.github739c1ae2.focuslock.ui.screen.course

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.ui.components.SimpleDatePickerDialog
import com.github739c1ae2.focuslock.ui.components.TimeOfDayPickerDialog
import com.github739c1ae2.focuslock.util.ValidatedField
import com.github739c1ae2.focuslock.util.applyImeInsetsAndConsumeScaffoldPadding
import com.github739c1ae2.focuslock.util.calculateImeAwareScaffoldInsets
import com.github739c1ae2.focuslock.util.minutesToClockString
import java.time.LocalDate

private enum class DateTarget { START, END }

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
                    onDateChange = { target, epochDay ->
                        when (target) {
                            DateTarget.START -> viewModel.updateStartEpochDay(epochDay)
                            DateTarget.END -> viewModel.updateEndEpochDay(epochDay)
                        }
                    },
                    onAddSlot = viewModel::addSlot,
                    onRemoveSlot = viewModel::removeSlot,
                    onUpdateSlot = { key, transform ->
                        viewModel.updateSlot(key, transform)
                    }
                )
            }
        }
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
    onDateChange: (DateTarget, Long) -> Unit,
    onAddSlot: () -> Unit,
    onRemoveSlot: (Long) -> Unit,
    onUpdateSlot: (Long, (TimeSlotDraft) -> TimeSlotDraft) -> Unit,
    modifier: Modifier = Modifier
) {

    var datePickerTarget by remember { mutableStateOf<DateTarget?>(null) }
    val insets = calculateImeAwareScaffoldInsets(innerPadding)
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .applyImeInsetsAndConsumeScaffoldPadding(insets),
        contentPadding = insets.contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            OutlinedTextField(
                value = draft.name.value,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.time_table_name)) },
                isError = !draft.name.isValid,
                supportingText = {
                    draft.name.errorMessage?.asString()?.let { Text(it) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (!draft.isBase) {
            item {
                DateField(
                    label = stringResource(R.string.time_table_start_date),
                    epochDay = draft.startEpochDay.value,
                    errorMessage = draft.startEpochDay.errorMessage?.asString(),
                    onClick = { datePickerTarget = DateTarget.START }
                )
            }
            item {
                DateField(
                    label = stringResource(R.string.time_table_end_date),
                    epochDay = draft.endEpochDay.value,
                    errorMessage = draft.endEpochDay.errorMessage?.asString(),
                    onClick = { datePickerTarget = DateTarget.END }
                )
            }
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
                    text = stringResource(R.string.base_time_slots),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onAddSlot) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.add_time_slot))
                }
            }
        }
        itemsIndexed(items = draft.slots, key = { _, item -> item.value.key }) { index, slot ->
            Card(modifier =
                Modifier.fillMaxWidth()
                    .animateItem(
                        placementSpec = spring(
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
            ) {
                TimeSlotEditorRow(
                    index = index,
                    slot = slot,
                    onUpdate = { transform -> onUpdateSlot(slot.value.key, transform) },
                    onRemove = { onRemoveSlot(slot.value.key) }
                )
            }
        }
    }


    datePickerTarget?.let { target ->
        SimpleDatePickerDialog(
            initialEpochDay = when (target) {
                DateTarget.START -> draft.startEpochDay.value
                DateTarget.END -> draft.endEpochDay.value
            },
            onConfirm = { epochDay ->
                onDateChange(target, epochDay)
                datePickerTarget = null
            },
            onDismiss = { datePickerTarget = null }
        )
    }

}

@Composable
private fun DateField(
    modifier: Modifier = Modifier,
    label: String,
    epochDay: Long?,
    errorMessage: String?,
    onClick: () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = epochDay?.let { LocalDate.ofEpochDay(it).toString() }
                ?: stringResource(R.string.not_set),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            isError = errorMessage != null,
            supportingText = { errorMessage?.let { Text(it) } },
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
fun PlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
) {
    val colors = TextFieldDefaults.colors()
    val interactionSource = remember {
        MutableInteractionSource()
    }
    val textColor = LocalTextStyle.current.color.takeOrElse {
            colors.textColor(
                enabled = true,
                isError = false,
                focused = interactionSource.collectIsFocusedAsState().value,
            )
        }

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            singleLine = true,
            textStyle = LocalTextStyle.current.merge(TextStyle(color = textColor)),
            cursorBrush = SolidColor(colors.cursorColor(false)),
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                TextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    placeholder = placeholder,
                    container = {},
                    contentPadding = PaddingValues(0.dp),
                    colors = colors
                )
            }
        )
    }
}

@Composable
private fun TimeSlotEditorRow(
    index: Int,
    slot: ValidatedField<TimeSlotDraft>,
    onUpdate: ((TimeSlotDraft) -> TimeSlotDraft) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PlainTextField(
                value = slot.value.alias,
                onValueChange = { value -> onUpdate { it.copy(alias = value) } },
                placeholder = {
                    Text(
                        text = stringResource(R.string.slot_number_fmt, index + 1)
                    )
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimePickerField(
                label = stringResource(R.string.start_time),
                minute = slot.value.startMinute,
                errorMessage = slot.errorMessage?.asString(),
                onClick = { showStartPicker = true },
                modifier = Modifier.weight(1f)
            )
            TimePickerField(
                label = stringResource(R.string.end_time),
                minute = slot.value.endMinute,
                errorMessage = slot.errorMessage?.asString(),
                onClick = { showEndPicker = true },
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showStartPicker) {
        TimeOfDayPickerDialog(
            initialMinute = slot.value.startMinute,
            onConfirm = { minute ->
                onUpdate { it.copy(startMinute = minute) }
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimeOfDayPickerDialog(
            initialMinute = slot.value.endMinute,
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
    errorMessage: String?,
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
            isError = errorMessage != null,
            supportingText = { errorMessage?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { onClick() }
        )
    }
}
