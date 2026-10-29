package com.github739c1ae2.focuslock.ui.screen.course.list

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
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.github739c1ae2.focuslock.database.CourseListItem
import com.github739c1ae2.focuslock.database.CourseTableEntity
import com.github739c1ae2.focuslock.ui.components.InputDialog
import com.github739c1ae2.focuslock.ui.components.customCardColors
import com.github739c1ae2.focuslock.util.epochDayToDateString

@Composable
fun CourseListScreen(
    isSinglePane: Boolean,
    onBack: () -> Unit,
    onEditCourse: (Long) -> Unit,
    onImportCourse: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CourseListViewModel = hiltViewModel()
) {
    val courseTable by viewModel.courseTable.collectAsStateWithLifecycle(initialValue = null)
    val courses by viewModel.courses.collectAsStateWithLifecycle()


    var showCreateDialog by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }


    LaunchedEffect(Unit) {
        viewModel.createResult.collect { newCourseId ->
            onEditCourse(newCourseId)
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
        floatingActionButton = {
            var expanded by rememberSaveable {
                mutableStateOf(false)
            }
            FloatingActionButtonMenu(
                expanded = expanded,
                button = {
                    ToggleFloatingActionButton(
                        checked = expanded,
                        onCheckedChange = {
                            expanded = it
                        }
                    ) {
                        Icon(
                            imageVector = if (checkedProgress > 0.5f) {
                                Icons.Default.Close
                            } else {
                                Icons.Default.Add
                            },
                            contentDescription = null,
                            modifier = Modifier.animateIcon(
                                checkedProgress = { checkedProgress }
                            )
                        )
                    }
                }
            ) {
                FloatingActionButtonMenuItem(
                    onClick = dropUnlessResumed {
                        expanded = false
                        onImportCourse()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(stringResource(R.string.import_course_table))
                    }
                )
                FloatingActionButtonMenuItem(
                    onClick = dropUnlessResumed {
                        expanded = false
                        showCreateDialog = true
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.AddBox,
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(stringResource(R.string.new_course))
                    }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = innerPadding + PaddingValues(16.dp) + PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                courseTable?.let { CourseTableSummaryCard(table = it) }
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
}

@Composable
private fun CourseTableSummaryCard(
    table: CourseTableEntity,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = table.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            val startText = table.semesterStartEpochDay
                ?.epochDayToDateString()
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
                    text = stringResource(R.string.course_table_unset_hint),
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
                    text = item.profileName,
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


