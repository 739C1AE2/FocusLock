package com.github739c1ae2.focuslock.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.ui.navigation.AppRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedRoute: AppRoute.SettingsDetail?,
    onNavigateTo: (AppRoute) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) }
            )
        }
    ) { padding ->
        SettingsList(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            selectedRoute = selectedRoute,
            onNavigateTo = onNavigateTo
        )
    }
}

@Composable
private fun SettingsList(
    modifier: Modifier = Modifier,
    selectedRoute: AppRoute.SettingsDetail?,
    onNavigateTo: (AppRoute) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
    ListItem(
        modifier = Modifier
            .clickable(onClick = dropUnlessResumed { onClick() }),
        colors = settingsItemColors(selected),
        headlineContent = {
            Text(title)
        },
        supportingContent = summary?.let {
            { Text(it) }
        },
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null
                )
            }
        },
        trailingContent = trailingContent
    )
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
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
private fun settingsItemColors(
    selected: Boolean
): ListItemColors {
    val scheme = MaterialTheme.colorScheme
    val containerColor = if (selected) {
        scheme.secondaryContainer
    } else {
        scheme.surfaceContainerHigh
    }
    return ListItemDefaults.colors(
        containerColor = containerColor,
        headlineColor = contentColorFor(containerColor),
        supportingColor = contentColorFor(containerColor),
        leadingIconColor = scheme.primary,
        trailingIconColor = contentColorFor(containerColor)
    )
}
