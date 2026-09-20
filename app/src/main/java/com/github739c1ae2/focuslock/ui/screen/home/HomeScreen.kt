package com.github739c1ae2.focuslock.ui.screen.home

import androidx.annotation.FloatRange
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.ui.components.InputDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val isRunning = serviceState != EngineServiceState.Stopped
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = {
                Text(
                    text = stringResource(R.string.home_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AccessibilityBanner(
                isRunning = isRunning,
                onOpenSettings = {
                    viewModel.openSettings(context)
                }
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val isWideScreen = maxWidth >= 600.dp
                if (isWideScreen) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StartButton(
                            serviceState = serviceState,
                            onStartLock = viewModel::startQuickLock,
                            modifier = Modifier.weight(1f)
                        )
                        QuickLockConfigPanel(
                            selectedProfileId = uiState.selectedProfileId,
                            selectedMinutes = uiState.selectedMinutes,
                            profiles = profiles,
                            onSelectProfile = viewModel::updateSelectedProfile,
                            onSelectMinutes = viewModel::updateSelectedMinutes,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        StartButton(
                            serviceState = serviceState,
                            onStartLock = viewModel::startQuickLock
                        )

                        QuickLockConfigPanel(
                            selectedProfileId = uiState.selectedProfileId,
                            selectedMinutes = uiState.selectedMinutes,
                            profiles = profiles,
                            onSelectProfile = viewModel::updateSelectedProfile,
                            onSelectMinutes = viewModel::updateSelectedMinutes,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AccessibilityBanner(isRunning: Boolean, onOpenSettings: () -> Unit) {
    AnimatedVisibility(visible = !isRunning) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSettings() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.fix_service_unavailable),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

fun Modifier.fillMaxSquare(
    @FloatRange(from = 0.0, to = 1.0, fromInclusive = false) scaleRatio: Float = 1.0f,
    minSize: Dp = Dp.Unspecified,
    maxSize: Dp = Dp.Unspecified,
): Modifier = this.layout { measurable, constraints ->
    require(scaleRatio > 0f && scaleRatio <= 1f) { "scaleRatio 必须在 (0, 1] 范围内" }

    val availW = if (constraints.hasBoundedWidth) constraints.maxWidth else null
    val availH = if (constraints.hasBoundedHeight) constraints.maxHeight else null

    val baseSquareSize = when {
        availW != null && availH != null -> minOf(availW, availH)
        availW != null -> availW
        availH != null -> availH
        else -> constraints.minWidth
    }

    val minPx = if (minSize != Dp.Unspecified) minSize.roundToPx() else 0
    val maxPx = if (maxSize != Dp.Unspecified) maxSize.roundToPx() else Int.MAX_VALUE

    val rawInnerSize = (baseSquareSize * scaleRatio).toInt()
    val innerSquareSize = rawInnerSize.coerceIn(minPx, maxPx)

    val outerSize = (innerSquareSize / scaleRatio).toInt().coerceAtLeast(innerSquareSize)

    val placeable = measurable.measure(Constraints.fixed(innerSquareSize, innerSquareSize))

    layout(outerSize, outerSize) {
        val offset = (outerSize - innerSquareSize) / 2
        placeable.placeRelative(offset, offset)
    }
}

@Composable
fun StartButton(
    serviceState: EngineServiceState,
    onStartLock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled = serviceState == EngineServiceState.Idle
    Surface(
        onClick = onStartLock,
        enabled = isEnabled,
        shape = CircleShape,
        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = if (isEnabled) 12.dp else 0.dp,
        modifier = modifier
            .fillMaxSquare(0.8f, minSize = 48.dp, maxSize = 240.dp)
            .clip(CircleShape)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "START",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = when (serviceState) {
                        EngineServiceState.Idle -> {
                            stringResource(R.string.start_quick_lock)
                        }

                        EngineServiceState.Stopped -> {
                            stringResource(R.string.service_not_available)
                        }

                        EngineServiceState.InSession -> {
                            stringResource(R.string.lock_in_progress)
                        }
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickLockConfigPanel(
    selectedProfileId: Long,
    selectedMinutes: Int,
    profiles: List<ProfileEntity>,
    onSelectProfile: (Long) -> Unit,
    onSelectMinutes: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val presetTimes = listOf(15, 30, 45, 60, 90, 120)
    var expanded by remember { mutableStateOf(false) }
    val selectedName = profiles.firstOrNull { it.id == selectedProfileId }?.name
        ?: selectedProfileId.toString()
    var showCustomDialog by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.profile_binding)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            onSelectProfile(profile.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.minute_fmt, selectedMinutes),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presetTimes) { time ->
                FilterChip(
                    selected = selectedMinutes == time,
                    onClick = { onSelectMinutes(time) },
                    label = { Text("${time}m") },
                    shape = CircleShape
                )
            }
            item {
                FilterChip(
                    selected = !presetTimes.contains(selectedMinutes),
                    onClick = { showCustomDialog = true },
                    label = { Text("自定义...") },
                    shape = CircleShape
                )
            }
        }
    }
    if (showCustomDialog) {
        var inputText by remember { mutableStateOf("") }
        val isError = inputText.toIntOrNull()?.let { it !in 1..1440 } ?: true
        InputDialog(
            title = stringResource(R.string.custom_quick_lock_minutes),
            label = stringResource(R.string.custom_quick_lock_label),
            value = inputText,
            isError = isError,
            supportingText = if (isError) {
                stringResource(R.string.quick_lock_minutes_invalid_fmt, 1, 1440)
            } else null,
            onValueChange = { newValue ->
                inputText = newValue
            },
            onConfirm = {
                onSelectMinutes(inputText.toInt())
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
            keyboardType = KeyboardType.Number
        )
    }
}