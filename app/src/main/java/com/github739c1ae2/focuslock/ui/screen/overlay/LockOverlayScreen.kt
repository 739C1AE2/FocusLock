package com.github739c1ae2.focuslock.ui.screen.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.engine.EngineState
import kotlin.time.Duration.Companion.seconds
import coil3.compose.AsyncImage
import com.github739c1ae2.focuslock.engine.OVERLAY_WINDOW_TYPE
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

@Composable
fun LockOverlayScreen(
    viewModel: OverlayViewModel
) {
    val engineState by viewModel.engineState.collectAsState()
    when (val state = engineState) {
        EngineState.Allowed, EngineState.Idle, EngineState.Paused -> {}
        is EngineState.Locked -> {
            LockedScreen(state, viewModel)
        }

        is EngineState.Warning -> {
            WarningScreen(state)
        }
    }
}

fun Int.toTimeString(): String {
    val hours = this / 60
    val minutes = this % 60

    val formatter = DateTimeFormatter.ofPattern("HH:mm")

    return LocalTime.of(hours % 24, minutes).format(formatter)
}

@Composable
fun LockedScreen(state: EngineState.Locked, viewModel: OverlayViewModel) {
    val allowedApps by viewModel.allowedApps.collectAsState()
    var showPauseDialog by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    val startTime = state.schedule.startMinute.toTimeString()
    val endTime = state.schedule.endMinute.toTimeString()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp, 32.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "专注时间",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "系统已限制该应用的使用",
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )

            Text(
                text = "$startTime - $endTime",
                modifier = Modifier
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                "允许使用的应用",
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            when (val state = allowedApps) {
                is AllowedAppListState.Loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is AllowedAppListState.Loaded -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(state.apps) { app ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable { viewModel.onLaunchApp(app.packageName) }
                                    .padding(8.dp)
                            ) {
                                AsyncImage(
                                    model = app.icon,
                                    contentDescription = app.appName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Text(
                                    app.appName,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 两个保命按钮
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { showPauseDialog = true },
                    enabled = true, // TODO: 额度校验闭环：没有额度直接变灰！
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("暂停")
                }

                OutlinedButton(
                    onClick = { showUnlockDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("紧急解除")
                }
            }
        }
    }
    if (showPauseDialog) {
        DurationInputDialog(
            "暂时解除锁定",
            minSeconds = 5,
            maxSeconds = 180,
            onDismiss = { showPauseDialog = false },
            onConfirm = {
                viewModel.onRequestPause(it)
                showPauseDialog = false
            }
        )
    }

    if (showUnlockDialog) {
        CountDownConfirmDialog(
            "强制退出",
            "确定要紧急解除锁定吗？",
            secondsToWait = 15,
            onDismiss = { showUnlockDialog = false },
            onConfirm = {
                viewModel.onRequestUnlock()
                showUnlockDialog = false
            }
        )
    }
}

@Composable
fun WarningScreen(state: EngineState.Warning) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp, 32.dp)
    ) {
        Text(
            text = stringResource(R.string.lock_overlay_warning, state.remaining.inWholeSeconds),
            modifier = Modifier
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun DurationInputDialog(
    title: String,
    minSeconds: Int,
    maxSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Duration) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    val inputVal = text.toIntOrNull()
    val isError = text.isNotEmpty() && (inputVal == null || inputVal !in minSeconds..maxSeconds)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() }) {
                            text = newValue
                        }
                    },
                    label = { Text(stringResource(R.string.text_field_label_input_seconds)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.text_field_supporting_text_duration_range_seconds,
                                minSeconds,
                                maxSeconds
                            )
                        )
                    },
                    isError = isError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    inputVal?.let { onConfirm(it.seconds) }
                },
                enabled = text.isNotEmpty() && !isError
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        properties = DialogProperties(
            windowType = OVERLAY_WINDOW_TYPE
        )
    )
}

@Composable
fun CountDownConfirmDialog(
    title: String,
    message: String,
    secondsToWait: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // 记录剩余秒数
    var timeLeft by remember { mutableIntStateOf(secondsToWait) }

    // 使用 LaunchedEffect 处理倒计时
    LaunchedEffect(Unit) {
        while (timeLeft > 0) {
            delay(1.seconds)
            timeLeft--
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = timeLeft == 0
            ) {
                val text = if (timeLeft > 0) {
                    stringResource(R.string.ok_with_seconds, timeLeft)
                } else {
                    stringResource(android.R.string.ok)
                }
                Text(text)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        properties = DialogProperties(
            windowType = OVERLAY_WINDOW_TYPE
        )
    )
}

@Preview
@Composable
fun WarningScreenPreview() {
    WarningScreen(
        state = EngineState.Warning(
            remaining = 10.seconds
        )
    )
}