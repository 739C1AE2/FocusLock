package com.github739c1ae2.focuslock.ui.screen.schedule

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.ScheduleWithProfileName
import com.github739c1ae2.focuslock.database.WEEKDAY_SET
import com.github739c1ae2.focuslock.ui.components.InputDialog
import com.github739c1ae2.focuslock.ui.components.customCardColors
import com.github739c1ae2.focuslock.util.minutesToClockString
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListScreen(
    selectedScheduleId: Long?,
    modifier: Modifier = Modifier,
    viewModel: ScheduleListViewModel = hiltViewModel(),
    onEditSchedule: (scheduleId: Long) -> Unit,
    onOpenCourseTable: () -> Unit = {}
) {
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    val scheduleItems = remember { mutableStateListOf<ScheduleWithProfileName>() }
    var isReordering by remember { mutableStateOf(false) }

    LaunchedEffect(schedules) {
        if (!isReordering) {
            scheduleItems.clear()
            scheduleItems.addAll(schedules)
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        scheduleItems.add(to.index, scheduleItems.removeAt(from.index))
    }

    LaunchedEffect(Unit) {
        viewModel.createResult.collect { newScheduleId ->
            onEditSchedule(newScheduleId)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = {
                Text(
                    text = stringResource(R.string.schedule_list_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.new_item)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    onClick = dropUnlessResumed { onOpenCourseTable() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.course_table_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.course_manage),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null
                        )
                    }
                }
            }
            if (scheduleItems.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.empty_schedules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(
                    items = scheduleItems,
                    key = { it.schedule.id }
                ) { schedule ->
                    ReorderableItem(
                        state = reorderableState,
                        key = schedule.schedule.id
                    ) {
                        val interactionSource = remember {
                            MutableInteractionSource()
                        }
                        ScheduleCard(
                            isSelected = schedule.schedule.id == selectedScheduleId,
                            schedule = schedule,
                            onEdit = dropUnlessResumed { onEditSchedule(schedule.schedule.id) },
                            onToggleActive = { active ->
                                viewModel.setScheduleActive(schedule.schedule, active)
                            },
                            interactionSource = interactionSource,
                            modifier = Modifier.longPressDraggableHandle(
                                interactionSource = interactionSource,
                                onDragStarted = { isReordering = true },
                                onDragStopped = {
                                    isReordering = false
                                    viewModel.saveOrder(scheduleItems.map { it.schedule })
                                }
                            )
                        )
                    }
                }
            }

        }
    }

    var newScheduleName by remember { mutableStateOf("") }
    if (showCreateDialog) {
        InputDialog(
            title = stringResource(R.string.new_schedule),
            label = stringResource(R.string.schedule_name),
            placeholder = stringResource(R.string.schedule_name_hint),
            value = newScheduleName,
            isError = newScheduleName.isBlank(),
            supportingText = if (newScheduleName.isBlank()) {
                stringResource(R.string.schedule_name_empty_error)
            } else null,
            singleLine = true,
            onValueChange = {
                newScheduleName = it
            },
            onConfirm = {
                showCreateDialog = false
                viewModel.createSchedule(newScheduleName)
                newScheduleName = ""
            },
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun ScheduleCard(
    isSelected: Boolean,
    schedule: ScheduleWithProfileName,
    interactionSource: MutableInteractionSource,
    onEdit: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColors = customCardColors(
        isSelected = isSelected,
        isActive = schedule.schedule.isActive
    )
    Card(
        onClick = onEdit,
        modifier = modifier.fillMaxWidth(),
        colors = cardColors,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schedule.schedule.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${schedule.schedule.startMinute.minutesToClockString()} - ${schedule.schedule.endMinute.minutesToClockString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = buildString {
                        val days = schedule.schedule.daysOfWeek
                        if (days.isEmpty()) {
                            append(stringResource(R.string.repeat_once))
                        } else if (days.size == 7) {
                            append(stringResource(R.string.repeat_all_day))
                        } else if (days == WEEKDAY_SET) {
                            append(stringResource(R.string.repeat_weekdays))
                        } else {
                            val dayTexts = schedule.schedule.daysOfWeek.toList()
                                .sortedBy { it.value }
                                .map {
                                    it.getDisplayName(
                                        TextStyle.SHORT,
                                        LocalLocale.current.platformLocale
                                    )
                                }
                            append(dayTexts.joinToString(" "))
                        }
                        append(" · ")
                        append(schedule.profileName)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = schedule.schedule.isActive,
                onCheckedChange = onToggleActive
            )
        }
    }
}