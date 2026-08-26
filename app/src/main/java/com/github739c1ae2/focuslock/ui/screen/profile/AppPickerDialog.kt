package com.github739c1ae2.focuslock.ui.screen.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.util.AppIconRequest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
@Composable
fun AppPickerDialog(
    isSystemApp: Boolean,
    alreadyAddedPackages: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    dialogViewModel: AppPickerViewModel = hiltViewModel()
) {
    val dialogState by dialogViewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val debouncedQuery by remember {
        snapshotFlow { query }
            .debounce(300.milliseconds)
    }.collectAsStateWithLifecycle(initialValue = query)

    val filteredApp by remember {
        derivedStateOf {
            dialogState.appList
                .filter { it.isSystemApp == isSystemApp }
                .filter { app ->
                    debouncedQuery.isBlank() ||
                            app.appName.contains(debouncedQuery, ignoreCase = true) ||
                            app.packageName.contains(debouncedQuery, ignoreCase = true)
                }
        }
    }

    LaunchedEffect(Unit) {
        dialogViewModel.resetSelection()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.pick_apps)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_apps)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .clickable {
                            dialogViewModel.toggleShowAllApps()
                        }
                        .padding(bottom = 4.dp)
                ) {
                    Checkbox(
                        checked = dialogState.showAllApps,
                        onCheckedChange = null
                    )
                    Text(stringResource(R.string.show_all_apps))
                }

                if (dialogState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                    ) {
                        items(filteredApp, key = { it.packageName }) { app ->
                            val isAlreadyAdded = alreadyAddedPackages.contains(app.packageName)
                            val isChecked = dialogState.checkedPackages.contains(app.packageName)
                            val alpha = if (isAlreadyAdded) 0.5f else 1f

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(alpha)
                                    .clickable(enabled = !isAlreadyAdded) {
                                        dialogViewModel.toggleAppCheck(app.packageName)
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isAlreadyAdded || isChecked,
                                    onCheckedChange = null,
                                    enabled = !isAlreadyAdded
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(AppIconRequest(app.packageName))
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    placeholder = rememberVectorPainter(Icons.Default.Android)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                if (isAlreadyAdded) {
                                    Text(
                                        text = stringResource(R.string.app_has_been_added),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(dialogState.checkedPackages)
                },
                enabled = !dialogState.isLoading && dialogState.checkedPackages.isNotEmpty()
            ) {
                Text(
                    stringResource(
                        R.string.confirm_add_app_fmt,
                        dialogState.checkedPackages.size
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}