package com.github739c1ae2.focuslock.ui.screen.profile

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.ui.components.InputDialog
import com.github739c1ae2.focuslock.ui.components.customCardColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    selectedProfileId: Long?,
    modifier: Modifier = Modifier,
    viewModel: ProfileListViewModel = hiltViewModel(),
    onEditProfile: (profileId: Long) -> Unit
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.createResult.collect { newProfileId ->
            onEditProfile(newProfileId)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = {
                Text(
                    text = stringResource(R.string.profile_list_title),
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
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (profiles.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.empty_profiles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(
                    items = profiles,
                    key = { it.id }
                ) { profile ->
                    ProfileCard(
                        isSelected = profile.id == selectedProfileId,
                        profile = profile,
                        onEdit = dropUnlessResumed { onEditProfile(profile.id) }
                    )
                }
            }

        }
    }

    var newProfileName by remember { mutableStateOf("") }
    if (showCreateDialog) {
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
                showCreateDialog = false
                viewModel.createProfile(newProfileName)
                newProfileName = ""
            },
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun ProfileCard(
    isSelected: Boolean,
    profile: ProfileEntity,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardColors = customCardColors(
        isSelected = isSelected,
        isActive = true
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(stringResource(profile.userAppMode.toStringRes()))
                        append(" / ")
                        append(stringResource(profile.systemAppMode.toStringRes()))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.edit),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}