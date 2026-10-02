package com.github739c1ae2.focuslock.ui.screen.adapter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.adapter.ConfigValue
import com.github739c1ae2.focuslock.ui.navigation.scene.AdaptiveDialogScaffold
import com.github739c1ae2.focuslock.util.calculateImeAwareScaffoldInsets
import com.github739c1ae2.focuslock.util.applyImeInsetsAndConsumeScaffoldPadding


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdapterConfigScreen(
    packageName: String,
    original: AdapterConfigInfo?,
    onConfirm: (AdapterConfigInfo?) -> Unit,
    onCancel: (force: Boolean) -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    viewModel: AdapterConfigViewModel = hiltViewModel<AdapterConfigViewModel, AdapterConfigViewModel.Factory> { factory ->
        factory.create(packageName, original)
    }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isDirty) {
        onDirtyChange(state.isDirty)
    }

    AdaptiveDialogScaffold(
        title = stringResource(R.string.screen_title_adapter_config),
        onClose = { onCancel(false) },
        onCancel = { onCancel(true) },
        onConfirm = {
            onConfirm(
                state.adapters.find {
                    it.adapterId == state.selectedId
                }?.toConfigInfo()
            )
        }

    ) { innerPadding ->
        val insets = calculateImeAwareScaffoldInsets(innerPadding)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .applyImeInsetsAndConsumeScaffoldPadding(insets),
            contentPadding = insets.contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                val isSelected = state.selectedId == null
                NoneAdapterCard(
                    isSelected = isSelected,
                    onSelectToggle = {
                        viewModel.toggleAdapterSelection(null)
                    }
                )
            }

            items(items = state.adapters, key = { it.adapterId }) { adapter ->
                val isSelected = adapter.adapterId == state.selectedId

                AdapterConfigCard(
                    adapter = adapter,
                    isSelected = isSelected,
                    onSelectToggle = {
                        viewModel.toggleAdapterSelection(adapter.adapterId)
                    },
                    onConfigChange = { newConfig ->
                        viewModel.updateConfig(
                            AdapterConfigInfo(
                                adapterId = adapter.adapterId,
                                config = newConfig
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun NoneAdapterCard(
    isSelected: Boolean,
    onSelectToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectToggle() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelectToggle
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.adapter_none),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
fun AdapterConfigCard(
    adapter: AdapterItem,
    isSelected: Boolean,
    onSelectToggle: () -> Unit,
    onConfigChange: (Map<String, ConfigValue>) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectToggle() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onSelectToggle
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(adapter.adapterName),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (adapter.description != null) {
                        Text(
                            text = stringResource(adapter.description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isSelected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))
                    AdapterConfigForm(
                        specs = adapter.configSchema,
                        values = adapter.config,
                        onValueChange = { newConfig ->
                            onConfigChange(newConfig)
                        }
                    )
                }
            }
        }
    }
}