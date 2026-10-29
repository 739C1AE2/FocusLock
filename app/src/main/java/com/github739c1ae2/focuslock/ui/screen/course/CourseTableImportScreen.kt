package com.github739c1ae2.focuslock.ui.screen.course

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.course.COURSE_IMPORT_PARSERS
import com.github739c1ae2.focuslock.course.CourseImportFileParser
import com.github739c1ae2.focuslock.course.CourseImportIssue
import com.github739c1ae2.focuslock.course.CourseImportIssueSeverity
import com.github739c1ae2.focuslock.course.CourseImportParser
import com.github739c1ae2.focuslock.course.CourseTableImport
import com.github739c1ae2.focuslock.course.CourseTableParseResult
import com.github739c1ae2.focuslock.course.SourceType
import com.github739c1ae2.focuslock.database.CourseImportBinding
import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.database.TimeSlot
import com.github739c1ae2.focuslock.ui.components.ProfileDropdown
import com.github739c1ae2.focuslock.util.applyImeInsetsAndConsumeScaffoldPadding
import com.github739c1ae2.focuslock.util.calculateImeAwareScaffoldInsets
import com.github739c1ae2.focuslock.util.epochDayToDateString
import com.github739c1ae2.focuslock.util.minutesToClockString

@Composable
fun CourseTableImportScreen(
    isSinglePane: Boolean,
    onBack: () -> Unit,
    viewModel: CourseImportViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.course_table_import_title),
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
                }
            )
        },
    ) { innerPadding ->
        val insets = calculateImeAwareScaffoldInsets(innerPadding)
        when (val state = uiState) {
            ImportUiState.Idle, is ImportUiState.Parsing -> {
                ParserSelectContent(
                    modifier = Modifier.applyImeInsetsAndConsumeScaffoldPadding(insets),
                    contentPadding = insets.contentPadding,
                    parsingParser = (state as? ImportUiState.Parsing)?.parser,
                    onParseFile = { parser, uri ->
                        viewModel.parseFile(parser, uri)
                    }
                )
            }

            is ImportUiState.Review -> {
                ReviewContent(
                    profiles = profiles,
                    parseResult = state.data,
                    binding = state.binding,
                    baseProfileId = state.baseProfileId,
                    modifier = Modifier.applyImeInsetsAndConsumeScaffoldPadding(insets),
                    contentPadding = insets.contentPadding,
                    onBindingChanged = { viewModel.setImportBinding(it) },
                    onBaseProfileIdChanged = { viewModel.setBaseProfileId(it) },
                    onCancel = { viewModel.cancelImport() },
                    onConfirm = { viewModel.import() }
                )
            }
            is ImportUiState.Success, ImportUiState.Writing, is ImportUiState.ImportError -> {
                ImportResultContent(
                    isWriting = state is ImportUiState.Writing,
                    errorMessage = (state as? ImportUiState.ImportError)?.message,
                    modifier = Modifier.applyImeInsetsAndConsumeScaffoldPadding(insets),
                    contentPadding = insets.contentPadding,
                    onBack = { onBack() }
                )
            }
        }
    }
}

@Composable
private fun ParserSelectContent(
    modifier: Modifier = Modifier,
    parsingParser: CourseImportParser<*>?,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onParseFile: (parser: CourseImportFileParser, uri: Uri) -> Unit
) {
    var pendingParser by remember { mutableStateOf<CourseImportParser<*>?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        (pendingParser as? CourseImportFileParser)?.let { parser ->
            if (uri != null) {
                onParseFile(parser, uri)
            }
            pendingParser = null
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.import_source),
            style = MaterialTheme.typography.titleSmall
        )
        COURSE_IMPORT_PARSERS.forEach { parser ->
            ParserCard(
                parser = parser,
                enabled = parsingParser == null,
                isLoading = parsingParser == parser,
                onClick = dropUnlessResumed {
                    pendingParser = parser
                    when (val type = parser.sourceType) {
                        is SourceType.File -> {
                            importLauncher.launch(type.mimeTypes.toTypedArray())
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ReviewContent(
    profiles: List<ProfileEntity>,
    parseResult: CourseTableParseResult,
    binding: CourseImportBinding,
    baseProfileId: Long,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onBindingChanged: (CourseImportBinding) -> Unit,
    onBaseProfileIdChanged: (Long) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {

    LazyColumn(
        modifier = modifier
            .fillMaxWidth(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.import_binding_title),
                style = MaterialTheme.typography.titleSmall
            )
        }
        items(CourseImportBinding.entries) { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBindingChanged(option) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = binding == option,
                    onClick = null
                )
                Text(
                    text = when (option) {
                        CourseImportBinding.PROFILE -> stringResource(R.string.import_binding_profile)
                        CourseImportBinding.COPY_PER_COURSE -> stringResource(R.string.import_binding_copy)
                    }
                )
            }
        }
        item {
            ProfileDropdown(
                profiles = profiles,
                selectedId = baseProfileId,
                label = stringResource(R.string.import_profile),
                onSelect = { onBaseProfileIdChanged(it) }
            )
        }
        item {
            Row (
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton (
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = { onConfirm() },
                    enabled = parseResult is CourseTableParseResult.Success,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.import_confirm))
                }
            }
        }
        when (parseResult) {
            is CourseTableParseResult.Success -> {
                // 成功情况：展示数据摘要
                item {
                    CourseSummaryCard(data = parseResult.data)
                }

                if (parseResult.issues.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.import_issues_header),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(parseResult.issues) { issue ->
                        IssueItemCard(issue = issue)
                    }
                }
            }

            is CourseTableParseResult.Error -> {
                item {
                    ParseErrorBanner()
                }
                item {
                    Text(
                        text = stringResource(R.string.import_issues_header),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(parseResult.issues) { issue ->
                    IssueItemCard(issue = issue)
                }
            }
        }
    }
}

@Composable
private fun ImportResultContent(
    isWriting: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isWriting) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.import_writing_message),
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.import_error_message),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.import_success_message),
                style = MaterialTheme.typography.titleLarge
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            enabled = !isWriting,
            onClick = dropUnlessResumed {
                if (!isWriting) onBack()
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(text = stringResource(R.string.finish))
        }
    }
}

@Composable
private fun ParserCard(
    parser: CourseImportParser<*>,
    enabled: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(parser.nameRes),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                parser.descRes?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null
                )
            }
        }
    }
}

/**
 * 课表核心数据摘要卡片
 */
@Composable
private fun CourseSummaryCard(data: CourseTableImport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.summary_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            SummaryRow(
                label = stringResource(R.string.summary_total_courses),
                value = data.courses.size.toString()
            )
            SummaryRow(
                label = stringResource(R.string.summary_total_weeks),
                value = data.totalWeeks.toString()
            )
            SummaryRow(
                label = stringResource(R.string.summary_seasonal_tables),
                value = data.seasonalTables.size.toString()
            )
            SummaryRow(
                label = stringResource(R.string.summary_base_slots),
                value = data.basePeriodSchedule.slots.size.toString()
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ParseErrorBanner() {
    ElevatedCard (
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            text = stringResource(R.string.parse_error_banner_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            textAlign = TextAlign.Center
        )
    }
}


@Composable
private fun IssueItemCard(issue: CourseImportIssue) {
    val (containerColor, contentColor, icon) = when (issue.severity) {
        CourseImportIssueSeverity.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Error
        )

        CourseImportIssueSeverity.WARNING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Default.Warning
        )

        CourseImportIssueSeverity.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Info
        )
    }

    val sourceText = when (issue) {
        is CourseImportIssue.Parsing -> stringResource(R.string.issue_source_parsing)
        is CourseImportIssue.Normalization -> stringResource(R.string.issue_source_normalization)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column (
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row (
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )

                Text(
                    text = stringResource(
                        when (issue.severity) {
                            CourseImportIssueSeverity.ERROR -> R.string.issue_severity_error
                            CourseImportIssueSeverity.WARNING -> R.string.issue_severity_warning
                            CourseImportIssueSeverity.INFO -> R.string.issue_severity_info
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor
                )
            }

            Text(
                text = resolveIssueText(issue = issue),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
        }
    }
}

/**
 * 将各类 Issue 转换为对应的字符串资源与参数绑定
 */
@Composable
private fun resolveIssueText(issue: CourseImportIssue): String {
    return when (issue) {
        is CourseImportIssue.ReadFileFailed ->
            stringResource(R.string.issue_read_file_failed, issue.exception.localizedMessage ?: "")

        is CourseImportIssue.InvalidJsonFormat ->
            stringResource(R.string.issue_invalid_json, issue.exception.localizedMessage ?: "")

        is CourseImportIssue.InvalidSemesterStartDateSkipped->
            stringResource(R.string.issue_invalid_semester_start_date_skipped, issue.startDate)

        is CourseImportIssue.InvalidSlotSkipped ->
            stringResource(R.string.issue_invalid_slot_skipped, issue.periodScheduleName, issue.slot)

        is CourseImportIssue.InvalidSeasonStartDateFallback ->
            stringResource(
                R.string.issue_invalid_start_date_fallback,
                issue.periodScheduleName,
                issue.startDate,
                issue.defaultEpochDay.epochDayToDateString()
            )

        is CourseImportIssue.InvalidSeasonEndDateFallback ->
            stringResource(
                R.string.issue_invalid_end_date_fallback,
                issue.periodScheduleName,
                issue.endDate,
                issue.defaultEpochDay.epochDayToDateString()
            )

        is CourseImportIssue.InvalidCourseDayOfWeekSkipped ->
            stringResource(
                R.string.issue_invalid_course_day_of_week_skipped,
                issue.courseName,
                issue.courseSession
            )

        is CourseImportIssue.InvalidCourseTimeSkipped ->
            stringResource(
                R.string.issue_invalid_course_time_skipped,
                issue.courseName,
                issue.courseSession
            )

        is CourseImportIssue.InvalidCourseWeeksSkipped ->
            stringResource(
                R.string.issue_invalid_course_weeks_skipped,
                issue.courseName,
                issue.courseSession
            )

        is CourseImportIssue.InvalidCustomTimeFallbackToSections ->
            stringResource(
                R.string.issue_invalid_custom_time_fallback_to_sections,
                issue.courseName,
                issue.courseSession
            )

        CourseImportIssue.InvalidSleepyFormat ->
            stringResource(R.string.issue_invalid_sleepy_format)

        is CourseImportIssue.UnknownSleepyVersion ->
            stringResource(R.string.issue_unknown_sleepy_version, issue.version)

        is CourseImportIssue.InvalidSleepyLineSkipped ->
            stringResource(R.string.issue_invalid_sleepy_line_skipped, issue.lineNumber, issue.line)

        is CourseImportIssue.ConflictingSleepyLineSkipped ->
            stringResource(R.string.issue_conflicting_sleepy_line_skipped, issue.lineNumber, issue.line)

        is CourseImportIssue.SleepyDeclaredCourseCountMismatch ->
            stringResource(R.string.issue_sleepy_declared_course_count_mismatch, issue.declaredCount, issue.actualCount)

        is CourseImportIssue.SleepyDeclaredTimeSlotsCountMismatch ->
            stringResource(R.string.issue_sleepy_declared_time_slots_count_mismatch, issue.periodScheduleName, issue.declaredCount, issue.actualCount)

        is CourseImportIssue.SleepyChecksumAlgorithmUnsupported ->
            stringResource(R.string.issue_sleepy_checksum_algorithm_unsupported, issue.algorithm)

        CourseImportIssue.SleepyChecksumMismatch ->
            stringResource(R.string.issue_sleepy_checksum_mismatch)

        is CourseImportIssue.InvalidSlotRemoved ->
            stringResource(
                R.string.issue_invalid_slot_removed,
                issue.periodScheduleName,
                issue.slot.toDisplayString()
            )

        is CourseImportIssue.ConflictingSlotsNumbersRemoved ->
            stringResource(
                R.string.issue_conflicting_slots_removed,
                issue.periodScheduleName,
                issue.numbers.joinToString(", ")
            )

        is CourseImportIssue.OverlappingSlotsRemoved ->
            stringResource(
                R.string.issue_overlapping_slots_removed,
                issue.periodScheduleName,
                issue.numbers.joinToString(", ")
            )

        is CourseImportIssue.NonChronologicalSlotsRemoved ->
            stringResource(
                R.string.issue_non_chronological_slots_removed,
                issue.periodScheduleName,
                issue.numbers.joinToString(", ")
            )

        is CourseImportIssue.EmptySeasonalPeriodScheduleRemoved ->
            stringResource(R.string.issue_empty_seasonal_period_schedule_removed, issue.periodScheduleName)

        is CourseImportIssue.PeriodScheduleClearedForSectionGap ->
            stringResource(
                R.string.issue_period_schedule_cleared_section_gap,
                issue.periodScheduleName,
                issue.section
            )

        is CourseImportIssue.EmptySessionCourse ->
            stringResource(R.string.issue_empty_session_course, issue.courseName)

        is CourseImportIssue.InvalidSessionsRemoved ->
            stringResource(
                R.string.issue_invalid_sessions_removed,
                issue.courseName,
                issue.sessionTime.toDisplayString()
            )

        is CourseImportIssue.NoValidPeriodSchedule ->
            stringResource(R.string.issue_no_valid_period_schedule)

        is CourseImportIssue.UnknownSectionReferences ->
            stringResource(
                R.string.issue_unknown_section_references,
                issue.numbers.joinToString(", ")
            )

        is CourseImportIssue.InvalidSessionWeeksRemoved ->
            stringResource(
                R.string.issue_invalid_session_weeks_removed,
                issue.courseName,
                issue.removedWeeks.joinToString(", ")
            )
        is CourseImportIssue.TotalWeeksExpandedForCourseWeeks ->
            stringResource(
                R.string.issue_total_weeks_expanded_for_course_weeks,
                issue.originalTotalWeeks,
                issue.adjustedTotalWeeks
            )
        is CourseImportIssue.TotalWeeksOutOfRangeAdjusted ->
            stringResource(
                R.string.issue_total_weeks_out_of_range_adjusted,
                issue.originalTotalWeeks,
                issue.adjustedTotalWeeks
            )

        is CourseImportIssue.InvalidSeasonDateRangeFallback ->
              stringResource(
                 R.string.issue_invalid_season_date_range_fallback,
                 issue.periodScheduleName,
                 issue.adjustedStartEpochDay.epochDayToDateString(),
                 issue.adjustedEndEpochDay.epochDayToDateString()
                )
        CourseImportIssue.InvalidSemesterStartEpochDayRemoved ->
            stringResource(R.string.issue_invalid_semester_start_epoch_day_removed)
    }
}

@Composable
private fun TimeSlot.toDisplayString(): String {
    return "${number}: ${startMinute.minutesToClockString()} - ${endMinute.minutesToClockString()}"
}

@Composable
private fun CourseSessionTime.toDisplayString(): String {
    return when (this) {
        is CourseSessionTime.Sections -> "$startSection - $endSection"
        is CourseSessionTime.Custom -> "${startMinute.minutesToClockString()} - ${endMinute.minutesToClockString()}"
    }
}
