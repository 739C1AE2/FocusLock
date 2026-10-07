package com.github739c1ae2.focuslock.ui.screen.course

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseImportBinding
import com.github739c1ae2.focuslock.database.CourseListItem
import com.github739c1ae2.focuslock.database.CourseTableEntity
import com.github739c1ae2.focuslock.database.GLOBAL_PROFILE_ID
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.ui.components.InputDialog
import com.github739c1ae2.focuslock.ui.components.ProfileDropdown
import com.github739c1ae2.focuslock.ui.components.customCardColors
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseListScreen(
    isSinglePane: Boolean,
    onBack: () -> Unit,
    onEditCourse: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CourseListViewModel = hiltViewModel()
) {
    val courseTable by viewModel.courseTable.collectAsStateWithLifecycle(initialValue = null)
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var importEvent by remember { mutableStateOf<CourseImportEvent?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    LaunchedEffect(Unit) {
        viewModel.createResult.collect { newCourseId ->
            onEditCourse(newCourseId)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.importResult.collect { importEvent = it }
    }

    val importMessage: String? = when (val event = importEvent) {
        null -> null
        is CourseImportEvent.Success -> stringResource(
            R.string.import_success_fmt,
            event.tableName,
            event.courseCount,
            event.sessionCount
        )

        is CourseImportEvent.EmptyFile -> stringResource(R.string.import_empty_file)
        is CourseImportEvent.Failure -> stringResource(
            R.string.import_failed_fmt,
            event.message.orEmpty()
        )
    }

    LaunchedEffect(importMessage) {
        if (importMessage != null) {
            snackbarHostState.showSnackbar(importMessage)
            importEvent = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.course_table_title),
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
                    IconButton(onClick = dropUnlessResumed { onOpenSettings() }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.course_table_settings_title)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.new_course)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                courseTable?.let { CourseTableSummaryCard(table = it) }
            }
            item {
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "text/json", "text/plain", "*/*")
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.import_course_table))
                }
            }
            if (courses.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.course_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(
                    items = courses,
                    key = { it.course.id }
                ) { course ->
                    CourseCard(
                        item = course,
                        onEdit = dropUnlessResumed { onEditCourse(course.course.id) },
                        onToggleActive = { active ->
                            viewModel.setCourseActive(course.course.id, active)
                        }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        InputDialog(
            title = stringResource(R.string.new_course),
            label = stringResource(R.string.course_name),
            value = newCourseName,
            isError = newCourseName.isBlank(),
            supportingText = if (newCourseName.isBlank()) {
                stringResource(R.string.course_name_empty_error)
            } else null,
            singleLine = true,
            onValueChange = { newCourseName = it },
            onConfirm = {
                showCreateDialog = false
                viewModel.createCourse(newCourseName)
                newCourseName = ""
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    pendingImportUri?.let { uri ->
        CourseImportDialog(
            profiles = profiles,
            defaultTableName = courseTable?.name.orEmpty(),
            onConfirm = { binding, baseProfileId, tableName ->
                pendingImportUri = null
                viewModel.import(uri, binding, baseProfileId, tableName)
            },
            onDismiss = { pendingImportUri = null }
        )
    }
}

@Composable
private fun CourseTableSummaryCard(
    table: CourseTableEntity,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = table.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            val startText = table.semesterStartEpochDay
                ?.let { LocalDate.ofEpochDay(it).toString() }
                ?: stringResource(R.string.not_set)
            Text(
                text = stringResource(
                    R.string.course_table_summary_fmt,
                    startText,
                    table.semesterTotalWeeks
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (table.semesterStartEpochDay == null) {
                Text(
                    text = stringResource(R.string.course_table_not_imported_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.timetable_unset_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CourseCard(
    item: CourseListItem,
    onEdit: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val cardColors = customCardColors(
        isSelected = false,
        isActive = item.course.isActive
    )
    Card(
        onClick = onEdit,
        modifier = modifier.fillMaxWidth(),
        colors = cardColors
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.course.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        val teacher = item.course.teacher
                        val position = item.course.position
                        if (teacher.isNotBlank()) append(teacher)
                        if (position.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(position)
                        }
                        if (isNotEmpty()) append(" · ")
                        append(item.profileName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = item.course.isActive,
                onCheckedChange = onToggleActive
            )
        }
    }
}

@Composable
private fun CourseImportDialog(
    profiles: List<ProfileEntity>,
    defaultTableName: String,
    onConfirm: (CourseImportBinding, Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    var binding by remember { mutableStateOf(CourseImportBinding.GLOBAL) }
    var baseProfileId by remember { mutableStateOf(GLOBAL_PROFILE_ID) }
    var tableName by remember { mutableStateOf(defaultTableName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_course_table)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.import_binding_title),
                    style = MaterialTheme.typography.titleSmall
                )
                CourseImportBinding.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { binding = option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = binding == option,
                            onClick = { binding = option }
                        )
                        Text(
                            text = when (option) {
                                CourseImportBinding.GLOBAL -> stringResource(R.string.import_binding_global)
                                CourseImportBinding.EXISTING_PROFILE -> stringResource(R.string.import_binding_existing)
                                CourseImportBinding.COPY_PER_COURSE -> stringResource(R.string.import_binding_copy)
                            }
                        )
                    }
                }
                if (binding != CourseImportBinding.GLOBAL) {
                    ProfileDropdown(
                        profiles = profiles,
                        selectedId = baseProfileId,
                        label = stringResource(R.string.import_base_profile),
                        onSelect = { baseProfileId = it }
                    )
                }
                OutlinedTextField(
                    value = tableName,
                    onValueChange = { tableName = it },
                    label = { Text(stringResource(R.string.import_table_name)) },
                    placeholder = { Text(stringResource(R.string.import_table_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(binding, baseProfileId, tableName) }) {
                Text(stringResource(R.string.import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
