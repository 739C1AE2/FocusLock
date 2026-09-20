package com.github739c1ae2.focuslock.ui.screen.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.ui.components.InputDialog
import com.github739c1ae2.focuslock.ui.components.TimeOfDayPickerDialog
import com.github739c1ae2.focuslock.util.ValidatedField
import com.github739c1ae2.focuslock.util.minutesToClockString
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditScreen(
    scheduleId: Long,
    isSinglePane: Boolean,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onProfileCreated: (Long) -> Unit,
    viewModel: ScheduleEditViewModel = hiltViewModel<ScheduleEditViewModel, ScheduleEditViewModel.Factory> { factory ->
        factory.create(scheduleId)
    }
) {
    val state by viewModel.scheduleState.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }


    LaunchedEffect(state.isDirty) {
        onDirtyChange(state.isDirty)
    }

    LaunchedEffect(viewModel.eventFlow) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is UiEvent.ScheduleSaved -> onSaved()
                is UiEvent.ScheduleDeleted -> onDeleted()
                is UiEvent.ProfileCreated -> {
                    onProfileCreated(event.profileId)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.edit),
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
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(
                        onClick = viewModel::load,
                        enabled = state.isDirty
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                    val valid = (state as? ScheduleState.Loaded)?.schedule?.isValid ?: false
                    TextButton(
                        onClick = viewModel::save,
                        enabled = state.isDirty && valid
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
            is ScheduleState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is ScheduleState.Failed -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.failed_to_load_schedule))
                }
            }

            is ScheduleState.Loaded -> {
                ScheduleForm(
                    schedule = state.schedule,
                    profiles = viewModel.profiles.collectAsState().value,
                    onScheduleChange = viewModel::updateSchedule,
                    onCreateProfile = viewModel::createProfile,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }


    if (showDeleteConfirm) {
        val name = (state as? ScheduleState.Loaded)?.schedule?.name ?: scheduleId.toString()
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Text(stringResource(R.string.confirm_delete_schedule, name)) },
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
private fun ScheduleForm(
    schedule: ScheduleDraft,
    profiles: List<ProfileEntity>,
    onScheduleChange: (ScheduleDraft) -> Unit,
    onCreateProfile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showNewProfileDialog by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = schedule.name.value,
            onValueChange = { onScheduleChange(schedule.copy(name = ValidatedField(it))) },
            label = { Text(stringResource(R.string.schedule_name)) },
            placeholder = { Text(stringResource(R.string.schedule_name_hint)) },
            isError = !schedule.name.isValid,
            supportingText = {
                schedule.name.errorMessage?.asString()?.let { Text(it) }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = schedule.startMinute.value.minutesToClockString(),
                onValueChange = {},
                label = { Text(stringResource(R.string.start_time)) },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                isError = !schedule.startMinute.isValid,
                supportingText = {
                    schedule.startMinute.errorMessage?.asString()?.let { Text(it) }
                }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { showStartPicker = true }
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = schedule.endMinute.value.minutesToClockString(),
                onValueChange = {},
                label = { Text(stringResource(R.string.end_time)) },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                isError = !schedule.endMinute.isValid,
                supportingText = {
                    schedule.endMinute.errorMessage?.asString()?.let { Text(it) }
                }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { showEndPicker = true }
            )
        }

        Text(
            text = stringResource(R.string.repeat_days),
            style = MaterialTheme.typography.titleSmall
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DayOfWeek.entries) { day ->
                val dayText = day.getDisplayName(
                    TextStyle.SHORT,
                    LocalLocale.current.platformLocale
                )
                FilterChip(
                    selected = day in schedule.daysOfWeek,
                    onClick = {
                        onScheduleChange(
                            schedule.copy(
                                daysOfWeek =
                                    if (day in schedule.daysOfWeek)
                                        schedule.daysOfWeek - day
                                    else
                                        schedule.daysOfWeek + day
                            )
                        )
                    },
                    label = { Text(dayText) }
                )
            }
        }

        ProfileDropdown(
            profiles = profiles,
            selectedId = schedule.profileId,
            onSelect = { profileId -> onScheduleChange(schedule.copy(profileId = profileId)) },
            onNewProfile = { showNewProfileDialog = true }
        )
    }

    if (showStartPicker) {
        TimeOfDayPickerDialog(
            initialMinute = schedule.startMinute.value,
            onConfirm = {
                onScheduleChange(schedule.copy(startMinute = ValidatedField(it)))
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimeOfDayPickerDialog(
            initialMinute = schedule.endMinute.value,
            onConfirm = {
                onScheduleChange(schedule.copy(endMinute = ValidatedField(it)))
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
    if (showNewProfileDialog) {
        var newProfileName by remember { mutableStateOf("") }
        InputDialog(
            title = stringResource(R.string.new_profile),
            label = stringResource(R.string.profile_name),
            placeholder = stringResource(R.string.profile_name_hint),
            value = newProfileName,
            isError = newProfileName.isBlank(),
            supportingText = if (newProfileName.isBlank()) {
                stringResource(R.string.profile_name_empty_error)
            } else null,
            singleLine = true,
            onValueChange = {
                newProfileName = it
            },
            onConfirm = {
                showNewProfileDialog = false
                onCreateProfile(newProfileName)
                newProfileName = ""
            },
            onDismiss = { showNewProfileDialog = false }
        )
    }
}

@Preview
@Composable
private fun FormPreview() {
    val sampleSchedule = ScheduleDraft(
        id = 1L,
        name = ValidatedField("Sample Schedule"),
        daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        startMinute = ValidatedField(480), // 8:00 AM
        endMinute = ValidatedField(1020), // 5:00 PM
        profileId = 1L
    )
    val sampleProfiles = listOf(
        ProfileEntity(id = 1L, name = "Work"),
        ProfileEntity(id = 2L, name = "Personal")
    )
    ScheduleForm(
        schedule = sampleSchedule,
        profiles = sampleProfiles,
        onScheduleChange = {},
        onCreateProfile = {}
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDropdown(
    profiles: List<ProfileEntity>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onNewProfile: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = profiles.firstOrNull { it.id == selectedId }?.name
        ?: selectedId.toString()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.profile_binding)) },
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
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        onSelect(profile.id)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.create_profile)) },
                onClick = {
                    expanded = false
                    onNewProfile()
                }
            )
        }
    }
}
