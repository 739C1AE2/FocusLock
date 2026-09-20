package com.github739c1ae2.focuslock.ui.navigation.scene

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.window.core.layout.WindowSizeClass
import com.github739c1ae2.focuslock.R


data class AdaptiveDialogProviderScene(
    private val scene: OverlayScene<NavKey> /* androidx.navigation3.scene.DialogScene<NavKey> */
) : OverlayScene<NavKey> {

    override val key = scene.key
    override val entries = scene.entries
    override val previousEntries = scene.previousEntries
    override val overlaidEntries: List<NavEntry<NavKey>> = scene.overlaidEntries

    override val content = @Composable {
        CompositionLocalProvider(LocalAdaptiveDialogIsBasicDialog provides true) {
            scene.content()
        }
    }
}

class AdaptiveDialogSceneStrategy(
    private val windowSizeClass: WindowSizeClass
) : SceneStrategy<NavKey> {

    private val delegate = DialogSceneStrategy<NavKey>()

    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>
    ): Scene<NavKey>? {
        if (!windowSizeClass.isAtLeastBreakpoint(
                widthDpBreakpoint = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                heightDpBreakpoint = WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND
            )
        ) {
            return null
        }
        val scene = with(delegate) {
            calculateScene(entries)
        } as? OverlayScene<NavKey> ?: return null

        return AdaptiveDialogProviderScene(scene)
    }

    companion object {
        fun dialog(
            dialogProperties: DialogProperties = DialogProperties()
        ): Map<String, Any> = DialogSceneStrategy.dialog(dialogProperties)
    }
}

val LocalAdaptiveDialogIsBasicDialog = compositionLocalOf { false }

@Composable
fun rememberAdaptiveDialogSceneStrategy(
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
): SceneStrategy<NavKey> = remember(windowSizeClass) {
    AdaptiveDialogSceneStrategy(windowSizeClass)
}

/**
 * 自适应对话框的框架，需配合 [AdaptiveDialogSceneStrategy] 使用。
 * @param title 对话框标题
 * @param onClose 关闭对话框的回调，用户按返回键时不会拦截并回调！
 * @param onCancel 用户意图明确的取消操作回调（用户点击 basic dialog 的取消按钮），默认为 [onClose]
 * @param onConfirm 确认操作的回调
 * @param confirmText 确认按钮文本
 * @param dismissText 取消按钮文本
 * @param content 对话框内容
 * */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveDialogScaffold(
    title: String,
    onClose: () -> Unit,
    onCancel: (() -> Unit) = onClose,
    onConfirm: (() -> Unit),
    confirmText: String = stringResource(id = R.string.save),
    dismissText: String = stringResource(id = android.R.string.cancel),
    content: @Composable () -> Unit
) {
    val isDialogMode = LocalAdaptiveDialogIsBasicDialog.current

    if (isDialogMode) {
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                CompositionLocalProvider(
                    LocalContentColor provides AlertDialogDefaults.textContentColor
                ) {
                    Box(
                        modifier = Modifier
                            .weight(weight = 1f, fill = false)
                            .padding(bottom = 24.dp)
                    ) {
                        content()
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = dropUnlessResumed { onCancel() }) {
                        Text(dismissText)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = dropUnlessResumed { onConfirm() }) {
                        Text(confirmText)
                    }
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = dropUnlessResumed { onClose() }) {
                            Icon(Icons.Default.Close, contentDescription = dismissText)
                        }
                    },
                    actions = {
                        TextButton(onClick = dropUnlessResumed { onConfirm() }) {
                            Text(confirmText)
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                content()
            }
        }
    }
}