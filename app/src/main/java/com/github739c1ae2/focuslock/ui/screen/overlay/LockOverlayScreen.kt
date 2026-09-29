package com.github739c1ae2.focuslock.ui.screen.overlay

import android.view.WindowManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.ui.components.InputDialog
import com.github739c1ae2.focuslock.util.AppIconRequest
import com.github739c1ae2.focuslock.util.timestampMillisToClockString
import kotlinx.coroutines.delay
import java.time.format.FormatStyle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


val LocalOverlayWindowType =
    compositionLocalOf { WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }

@Composable
fun LockOverlayScreen(
    viewModel: OverlayViewModel
) {
    val state by viewModel.overlayState.collectAsStateWithLifecycle()
    val overlayWindowType by viewModel.overlayWindowType.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalOverlayWindowType provides overlayWindowType
    ) {
        when (val state = state) {
            is OverlayState.Locked -> {
                LockedScreen(state, viewModel)
            }

            is OverlayState.Warning -> {
                WarningScreen(state)
            }

            is OverlayState.Hidden -> {
                // Do nothing
            }
        }
    }
}

@Composable
fun LockedScreen(state: OverlayState.Locked, viewModel: OverlayViewModel) {
    val allowedApps by viewModel.allowedApps.collectAsStateWithLifecycle()
    val remainingPauseSeconds by viewModel.remainingPauseSeconds.collectAsStateWithLifecycle()
    val remainingForceUnlocks by viewModel.remainingForceUnlocks.collectAsStateWithLifecycle()
    var showPauseDialog by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    val startTime = state.session.startTimeMillis.timestampMillisToClockString(FormatStyle.MEDIUM)
    val endTime = state.session.endTimeMillis.timestampMillisToClockString(FormatStyle.MEDIUM)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.background
            )
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.lock_screen_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "$startTime - $endTime",
                modifier = Modifier
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                stringResource(R.string.allowed_apps),
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
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(AppIconRequest(app.packageName))
                                        .build(),
                                    contentDescription = app.appName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    placeholder = rememberVectorPainter(Icons.Default.Android)
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

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { showPauseDialog = true },
                    enabled = remainingPauseSeconds >= 5,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.pause))
                }

                OutlinedButton(
                    onClick = { showUnlockDialog = true },
                    enabled = remainingForceUnlocks > 0,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.force_unlock))
                }
            }
        }
    }
    if (showPauseDialog) {
        DurationInputDialog(
            stringResource(R.string.dialog_title_pause),
            minSeconds = 5,
            maxSeconds = remainingPauseSeconds,
            onDismiss = { showPauseDialog = false },
            onConfirm = {
                viewModel.onRequestPause(it)
                showPauseDialog = false
            }
        )
    }

    if (showUnlockDialog) {
        CountDownConfirmDialog(
            stringResource(R.string.force_unlock),
            stringResource(R.string.confirm_force_unlock_fmt, remainingForceUnlocks),
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
fun WarningScreen(state: OverlayState.Warning) {
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

    InputDialog(
        title = title,
        value = text,
        onValueChange = { newValue ->
            if (newValue.all { it.isDigit() }) {
                text = newValue
            }
        },
        onConfirm = {
            inputVal?.let { onConfirm(it.seconds) }
        },
        onDismiss = onDismiss,
        label = stringResource(R.string.text_field_label_input_seconds),
        supportingText = stringResource(
            R.string.text_field_supporting_text_duration_range_seconds,
            minSeconds,
            maxSeconds
        ),
        isError = isError,
        keyboardType = KeyboardType.Number,
        properties = DialogProperties(
            windowType = LocalOverlayWindowType.current
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
            windowType = LocalOverlayWindowType.current
        )
    )
}

@Preview
@Composable
fun WarningScreenPreview() {
    WarningScreen(
        state = OverlayState.Warning(
            remaining = 10.seconds
        )
    )
}