package com.github739c1ae2.focuslock.ui.screen.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.ui.navigation.AppRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedRoute: AppRoute.SettingsDetail?,
    onNavigateTo: (AppRoute) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val requestOverlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            viewModel.toggleUseApplicationOverlay(true)
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is SettingsUiEvent.RequestSystemAlertWindowPermission -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri()
                    )
                    requestOverlayLauncher.launch(intent)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    ) { padding ->
        SettingsList(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            selectedRoute = selectedRoute,
            state = state,
            onNavigateTo = onNavigateTo,
            onToggleUseApplicationOverlay = viewModel::toggleUseApplicationOverlay,
            onToggleHideFromRecents = viewModel::toggleHideFromRecents
        )
    }
}

@Composable
private fun SettingsList(
    modifier: Modifier = Modifier,
    selectedRoute: AppRoute.SettingsDetail?,
    state: SettingsUiState,
    onNavigateTo: (AppRoute) -> Unit,
    onToggleUseApplicationOverlay: (Boolean) -> Unit,
    onToggleHideFromRecents: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SettingsSection(title = stringResource(R.string.settings_section_general)) {
                SettingSwitchItem(
                    icon = Icons.Outlined.Window,
                    title = stringResource(R.string.settings_use_application_overlay_title),
                    summary = stringResource(R.string.settings_use_application_overlay_summary),
                    checked = state.useApplicationOverlay,
                    onCheckedChange = onToggleUseApplicationOverlay
                )

            }
        }
        item {
            SettingsSection(title = stringResource(R.string.settings_section_app_lifecycle)) {
                SettingSwitchItem(
                    icon = Icons.Outlined.VisibilityOff,
                    title = stringResource(R.string.settings_hide_from_recents_title),
                    summary = stringResource(R.string.settings_hide_from_recents_summary),
                    checked = state.hideFromRecents,
                    onCheckedChange = onToggleHideFromRecents
                )
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingClickableItem(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = stringResource(R.string.settings_licenses_title),
                    summary = stringResource(R.string.settings_licenses_summary),
                    selected = selectedRoute is AppRoute.Licenses,
                    onClick = { onNavigateTo(AppRoute.Licenses) }
                )
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector?,
    title: String,
    summary: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val containerColor = if (selected) {
        scheme.secondaryContainer
    } else {
        scheme.surfaceContainerHigh
    }
    val defaultContentColor = contentColorFor(containerColor)

    Surface(
        modifier = Modifier.clickable(onClick = dropUnlessResumed { onClick() }),
        color = containerColor,
        contentColor = defaultContentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (summary != null) 72.dp else 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading Icon
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }

            // Texts
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = defaultContentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = defaultContentColor.copy(alpha = 0.8f),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Trailing Content
            if (trailingContent != null) {
                CompositionLocalProvider(LocalContentColor provides defaultContentColor) {
                    Box(modifier = Modifier.padding(start = 16.dp)) {
                        trailingContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = 16.dp,
                bottom = 8.dp
            )
        )
        Card(
            modifier = modifier.fillMaxWidth()
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun SettingClickableItem(
    icon: ImageVector?,
    title: String,
    summary: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    SettingsItem(
        icon = icon,
        title = title,
        summary = summary,
        selected = selected,
        onClick = onClick
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null
        )
    }
}

@Composable
private fun SettingSwitchItem(
    icon: ImageVector?,
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsItem(
        icon = icon,
        title = title,
        summary = summary,
        onClick = { onCheckedChange(!checked) }
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}