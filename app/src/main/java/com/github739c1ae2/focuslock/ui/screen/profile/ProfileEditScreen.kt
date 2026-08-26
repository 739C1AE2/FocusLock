package com.github739c1ae2.focuslock.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.adapter.AdapterFactoryRegistry
import com.github739c1ae2.focuslock.database.AppRuleMode
import com.github739c1ae2.focuslock.database.GLOBAL_PROFILE_ID
import com.github739c1ae2.focuslock.database.ScheduleEntity
import com.github739c1ae2.focuslock.ui.screen.adapter.AdapterConfigInfo
import com.github739c1ae2.focuslock.util.AppIconRequest
import com.github739c1ae2.focuslock.util.minutesToClockString
import kotlinx.serialization.Serializable

private enum class ProfileTab {
    USER_APPS,
    SYSTEM_APPS
}

@Serializable
data class AdapterConfigResult(
    val packageName: String,
    val config: AdapterConfigInfo?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    profileId: Long,
    isSinglePane: Boolean,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onConfigureAdapter: (packageName: String, original: AdapterConfigInfo?) -> Unit,
    viewModel: ProfileEditViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val schedules by viewModel.schedules.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.isDirty) {
        onDirtyChange(state.isDirty)
    }

    LaunchedEffect(viewModel.eventFlow) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is UiEvent.ProfileSaved -> onSaved()
                is UiEvent.ProfileDeleted -> onDeleted()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.edit
                        )
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
                    if (profileId != GLOBAL_PROFILE_ID) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    val valid = (state as? UiState.Loaded)?.profile?.isValid ?: false
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
            is UiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is UiState.Failed -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.failed_to_load_profile))
                }
            }

            is UiState.Loaded -> {
                ProfileForm(
                    profile = state.profile,
                    schedules = schedules,
                    onNameChange = viewModel::updateName,
                    onSetUserAppMode = viewModel::setUserAppMode,
                    onSetSystemAppMode = viewModel::setSystemAppMode,
                    onAddRules = viewModel::addRules,
                    onConfigureAdapter = onConfigureAdapter,
                    onRemoveRule = viewModel::removeRule,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }


    if (showDeleteConfirm) {
        val name = (state as? UiState.Loaded)?.profile?.name ?: profileId.toString()
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.confirm_delete_profile, name)) },
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
private fun ProfileForm(
    profile: ProfileDraft,
    schedules: List<ScheduleEntity>,
    onNameChange: (String) -> Unit,
    onSetUserAppMode: (AppRuleMode) -> Unit,
    onSetSystemAppMode: (AppRuleMode) -> Unit,
    onAddRules: (packageNames: Set<String>) -> Unit,
    onRemoveRule: (packageName: String) -> Unit,
    onConfigureAdapter: (packageName: String, original: AdapterConfigInfo?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(ProfileTab.USER_APPS) }
    var showAppPicker by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            OutlinedTextField(
                value = profile.name.value,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.profile_name)) },
                isError = !profile.name.isValid,
                supportingText = {
                    profile.name.errorMessage?.asString()?.let {
                        Text(it)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            Text(
                text = stringResource(R.string.associated_schedules),
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            if (schedules.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_associated_schedules),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    schedules.take(5).forEach { schedule ->
                        Text(
                            text = "${schedule.name} ${schedule.startMinute.minutesToClockString()} - ${schedule.endMinute.minutesToClockString()}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (schedules.size > 5) {
                        Text(
                            text = stringResource(R.string.more_schedules_fmt, schedules.size - 5),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        stickyHeader {
            SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == ProfileTab.USER_APPS,
                    onClick = {
                        selectedTab = ProfileTab.USER_APPS
                    },
                    text = { Text(stringResource(R.string.section_user_apps)) }
                )
                Tab(
                    selected = selectedTab == ProfileTab.SYSTEM_APPS,
                    onClick = {
                        selectedTab = ProfileTab.SYSTEM_APPS
                    },
                    text = { Text(stringResource(R.string.section_system_apps)) }
                )
            }
        }
        when (selectedTab) {
            ProfileTab.USER_APPS -> {
                appRulesTab(
                    mode = profile.userAppMode,
                    rules = profile.appRules.filter { !it.isSystemApp },
                    onSelectMode = onSetUserAppMode,
                    onAddApp = {
                        showAppPicker = true
                    },
                    onRemove = onRemoveRule,
                    onConfigureAdapter = onConfigureAdapter
                )
            }

            ProfileTab.SYSTEM_APPS -> {
                appRulesTab(
                    mode = profile.systemAppMode,
                    rules = profile.appRules.filter { it.isSystemApp },
                    onSelectMode = onSetSystemAppMode,
                    onAddApp = {
                        showAppPicker = true
                    },
                    onRemove = onRemoveRule,
                    onConfigureAdapter = onConfigureAdapter
                )
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            isSystemApp = selectedTab == ProfileTab.SYSTEM_APPS,
            alreadyAddedPackages = profile.appRules.map { it.packageName }.toSet(),
            onDismiss = { showAppPicker = false },
            onConfirm = { packages ->
                onAddRules(packages)
                showAppPicker = false
            }
        )
    }
}

private fun LazyListScope.appRulesTab(
    mode: AppRuleMode,
    rules: List<RuleUiItem>,
    onSelectMode: (AppRuleMode) -> Unit,
    onAddApp: () -> Unit,
    onRemove: (String) -> Unit,
    onConfigureAdapter: (packageName: String, original: AdapterConfigInfo?) -> Unit
) {
    item {
        ModeSelector(
            title = stringResource(R.string.mode),
            selected = mode,
            onSelect = onSelectMode
        )
    }

    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_rules),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAddApp) {
                Text(stringResource(R.string.add_app))
            }
        }
    }

    if (rules.isEmpty()) {
        item {
            EmptyText(stringResource(R.string.no_app_rules))
        }
    } else {
        items(rules, key = { it.packageName }) { rule ->
            RuleCard(
                rule = rule,
                onRemove = { onRemove(rule.packageName) },
                onConfigure = {
                    onConfigureAdapter(
                        rule.packageName,
                        rule.appliedAdapterId?.let {
                            AdapterConfigInfo(
                                adapterId = it,
                                config = rule.adapterConfig
                            )
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun ModeSelector(
    title: String,
    selected: AppRuleMode,
    onSelect: (AppRuleMode) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == AppRuleMode.WHITELIST,
                onClick = { onSelect(AppRuleMode.WHITELIST) },
                label = { Text(stringResource(R.string.mode_whitelist)) }
            )
            FilterChip(
                selected = selected == AppRuleMode.BLACKLIST,
                onClick = { onSelect(AppRuleMode.BLACKLIST) },
                label = { Text(stringResource(R.string.mode_blacklist)) }
            )
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun RuleCard(
    rule: RuleUiItem,
    onRemove: () -> Unit,
    onConfigure: () -> Unit,
) {
    val adapterName = rule.appliedAdapterId?.let {
        AdapterFactoryRegistry.getFactoryById(it).adapterName
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(AppIconRequest(rule.packageName))
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    placeholder = rememberVectorPainter(Icons.Default.Android)
                )
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                ) {
                    Text(rule.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        rule.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onRemove) {
                    Text(
                        stringResource(R.string.remove_app),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (adapterName != null) {
                        stringResource(adapterName)
                    } else {
                        stringResource(R.string.rule_follow_mode)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = dropUnlessResumed { onConfigure() }) {
                    Text(stringResource(R.string.configure))
                }
            }
        }
    }
}
