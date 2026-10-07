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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.ui.components.InputDialog
import com.github739c1ae2.focuslock.ui.components.ProfileDropdown
import com.github739c1ae2.focuslock.ui.components.TimeOfDayPickerDialog
import com.github739c1ae2.focuslock.ui.components.WeeksDialog
import com.github739c1ae2.focuslock.util.ValidatedField
import com.github739c1ae2.focuslock.util.WeeksSummary
import com.github739c1ae2.focuslock.util.applyImeInsetsAndConsumeScaffoldPadding
import com.github739c1ae2.focuslock.util.calculateImeAwareScaffoldInsets
import com.github739c1ae2.focuslock.util.minutesToClockString
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditorScreen(
    courseId: Long,
    isSinglePane: Boolean,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onProfileCreated: (Long) -> Unit,
    viewModel: CourseEditorViewModel = hiltViewModel<CourseEditorViewModel, CourseEditorViewModel.Factory> { factory ->
        factory.create(courseId)
    }
) {
    val state by viewModel.state.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val timeSlots by viewModel.timeSlots.collectAsState()
    val totalWeeks by viewModel.totalWeeks.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNewProfileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.isDirty) {
        onDirtyChange(state.isDirty)
    }

    LaunchedEffect(viewModel.eventFlow) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is CourseEditEvent.Saved -> onSaved()
                is CourseEditEvent.Deleted -> onDeleted()
                is CourseEditEvent.ProfileCreated -> onProfileCreated(event.profileId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.course_editor_title),
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
                    TextButton(onClick = viewModel::load, enabled = state.isDirty) {
                        Text(stringResource(R.string.reset))
                    }
                    val valid = (state as? CourseEditState.Loaded)?.draft?.isValid ?: false
                    TextButton(onClick = viewModel::save, enabled = state.isDirty && valid) {
                        Text(
                            if (state.isDirty) stringResource(R.string.save) else stringResource(R.string.saved)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = state) {
            is CourseEditState.Loading -> {
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

            is CourseEditState.Failed -> {
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

            is CourseEditState.Loaded -> {
                CourseForm(
                    draft = state.draft,
                    profiles = profiles,
                    timeSlots = timeSlots,
                    totalWeeks = totalWeeks,
                    innerPadding = innerPadding,
                    onUpdate = viewModel::updateDraft,
                    onAddSession = viewModel::addSession,
                    onRemoveSession = viewModel::removeSession,
                    onUpdateSession = viewModel::updateSession,
                    onCreateProfile = { showNewProfileDialog = true }
                )
            }
        }
    }

    if (showDeleteConfirm) {
        val name = (state as? CourseEditState.Loaded)?.draft?.name?.value ?: courseId.toString()
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.confirm_delete_course_fmt, name)) },
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
            onValueChange = { newProfileName = it },
            onConfirm = {
                showNewProfileDialog = false
                viewModel.createProfile(newProfileName)
                newProfileName = ""
            },
            onDismiss = { showNewProfileDialog = false }
        )
    }
}

@Composable
private fun CourseForm(
    draft: CourseDraft,
    profiles: List<ProfileEntity>,
    timeSlots: List<CourseTimeSlotEntity>,
    totalWeeks: Int,
    innerPadding: PaddingValues,
    onUpdate: ((CourseDraft) -> CourseDraft) -> Unit,
    onAddSession: () -> Unit,
    onRemoveSession: (Long) -> Unit,
    onUpdateSession: (Long, (SessionDraft) -> SessionDraft) -> Unit,
    onCreateProfile: () -> Unit,
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
            label = { Text(stringResource(R.string.course_name)) },
            isError = !draft.name.isValid,
            supportingText = {
                draft.name.errorMessage?.asString()?.let { Text(it) }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = draft.teacher,
            onValueChange = { onUpdate { d -> d.copy(teacher = it) } },
            label = { Text(stringResource(R.string.course_teacher)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = draft.position,
            onValueChange = { onUpdate { d -> d.copy(position = it) } },
            label = { Text(stringResource(R.string.course_position)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = draft.remark,
            onValueChange = { onUpdate { d -> d.copy(remark = it) } },
            label = { Text(stringResource(R.string.course_remark)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        ProfileDropdown(
            profiles = profiles,
            selectedId = draft.profileId,
            onSelect = { id -> onUpdate { d -> d.copy(profileId = id) } },
            onNewProfile = onCreateProfile
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.course_active),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = draft.isActive,
                onCheckedChange = { active -> onUpdate { d -> d.copy(isActive = active) } }
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.course_sessions),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onAddSession) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_session))
            }
        }

        draft.sessions.forEachIndexed { index, session ->
            key(session.key) {
                SessionCard(
                    index = index,
                    session = session,
                    timeSlots = timeSlots,
                    totalWeeks = totalWeeks,
                    onUpdate = { transform -> onUpdateSession(session.key, transform) },
                    onRemove = { onRemoveSession(session.key) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCard(
    index: Int,
    session: SessionDraft,
    timeSlots: List<CourseTimeSlotEntity>,
    totalWeeks: Int,
    onUpdate: ((SessionDraft) -> SessionDraft) -> Unit,
    onRemove: () -> Unit
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showWeeksDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete)
                    )
                }
            }

            Text(
                text = stringResource(R.string.session_day),
                style = MaterialTheme.typography.bodySmall
            )
            DayOfWeekChips(
                selected = session.dayOfWeek,
                onSelect = { day -> onUpdate { it.copy(dayOfWeek = day) } }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !session.isCustomTime,
                    onClick = { onUpdate { it.copy(isCustomTime = false) } },
                    label = { Text(stringResource(R.string.session_follow_timetable)) }
                )
                FilterChip(
                    selected = session.isCustomTime,
                    onClick = { onUpdate { it.copy(isCustomTime = true) } },
                    label = { Text(stringResource(R.string.session_custom_time)) }
                )
            }

            if (session.isCustomTime) {
                TimeField(
                    label = stringResource(R.string.session_start_time),
                    minute = session.startMinute,
                    onClick = { showStartPicker = true }
                )
                TimeField(
                    label = stringResource(R.string.session_end_time),
                    minute = session.endMinute,
                    onClick = { showEndPicker = true }
                )
            } else {
                if (timeSlots.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_time_slots),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    SectionDropdown(
                        label = stringResource(R.string.session_start_section),
                        slots = timeSlots,
                        selectedNumber = session.startSection,
                        onSelect = { number ->
                            onUpdate { current ->
                                current.copy(
                                    startSection = number,
                                    endSection = current.endSection ?: number
                                )
                            }
                        }
                    )
                    SectionDropdown(
                        label = stringResource(R.string.session_end_section),
                        slots = timeSlots,
                        selectedNumber = session.endSection,
                        onSelect = { number -> onUpdate { it.copy(endSection = number) } }
                    )
                }
            }

            WeeksField(
                weeks = session.weeks,
                totalWeeks = totalWeeks,
                onClick = { showWeeksDialog = true }
            )
        }
    }

    if (showStartPicker) {
        TimeOfDayPickerDialog(
            initialMinute = session.startMinute,
            onConfirm = { minute ->
                onUpdate { it.copy(startMinute = minute) }
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimeOfDayPickerDialog(
            initialMinute = session.endMinute,
            onConfirm = { minute ->
                onUpdate { it.copy(endMinute = minute) }
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
    if (showWeeksDialog) {
        WeeksDialog(
            totalWeeks = totalWeeks,
            initial = session.weeks,
            onConfirm = {
                onUpdate { current -> current.copy(weeks = it) }
                showWeeksDialog = false
            },
            onDismiss = { showWeeksDialog = false }
        )
    }
}

@Composable
private fun DayOfWeekChips(
    selected: DayOfWeek,
    onSelect: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = LocalLocale.current.platformLocale
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day == selected,
                onClick = { onSelect(day) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionDropdown(
    label: String,
    slots: List<CourseTimeSlotEntity>,
    selectedNumber: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = slots.firstOrNull { it.number == selectedNumber }
    val display = selected?.let { slot ->
        val time = "${slot.startMinute.minutesToClockString()} - ${slot.endMinute.minutesToClockString()}"
        "${stringResource(R.string.slot_number_fmt, slot.number)}（$time）"
    } ?: stringResource(R.string.not_set)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
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
            slots.forEach { slot ->
                val time = "${slot.startMinute.minutesToClockString()} - ${slot.endMinute.minutesToClockString()}"
                DropdownMenuItem(
                    text = { Text("${stringResource(R.string.slot_number_fmt, slot.number)}（$time）") },
                    onClick = {
                        onSelect(slot.number)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeField(
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

@Composable
private fun WeeksField(
    weeks: Set<Int>,
    totalWeeks: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = when (val weeksSummary = WeeksSummary.from(weeks, totalWeeks)) {
        is WeeksSummary.All -> stringResource(R.string.weeks_summary_all)
        is WeeksSummary.Single -> stringResource(R.string.weeks_summary_single_fmt, weeksSummary.week)
        is WeeksSummary.Range -> stringResource(
            R.string.weeks_summary_range_fmt,
            weeksSummary.start,
            weeksSummary.end
        )

        is WeeksSummary.Odd -> stringResource(
            R.string.weeks_summary_odd_fmt,
            weeksSummary.start,
            weeksSummary.end
        )

        is WeeksSummary.Even -> stringResource(
            R.string.weeks_summary_even_fmt,
            weeksSummary.start,
            weeksSummary.end
        )

        is WeeksSummary.Weeks -> stringResource(
            R.string.weeks_summary_fmt,
            weeksSummary.weeks.joinToString(",")
        )
    }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = summary,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.weeks)) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { onClick() }
        )
    }
}
