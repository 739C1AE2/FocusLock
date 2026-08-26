package com.github739c1ae2.focuslock.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

@Composable
fun customCardColors(
    isSelected: Boolean,
    isActive: Boolean
): CardColors {
    val colorScheme = MaterialTheme.colorScheme

    val (targetContainer, targetContent) = when (isSelected to isActive) {
        // 选中 + 启用
        true to true -> Pair(
            colorScheme.primaryContainer,
            colorScheme.onPrimaryContainer
        )
        // 选中 + 禁用
        true to false -> Pair(
            colorScheme.primaryContainer.copy(alpha = 0.4f),
            colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
        )
        // 未选中 + 启用
        false to true -> Pair(
            colorScheme.surfaceVariant,
            colorScheme.onSurfaceVariant
        )
        // 未选中 + 禁用
        false to false -> Pair(
            colorScheme.surfaceContainerLow,
            colorScheme.onSurface
        )
        else -> error("Unreachable sessionState")
    }

    val animatedContainer by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = 200),
        label = "cardContainerAnimation"
    )
    val animatedContent by animateColorAsState(
        targetValue = targetContent,
        animationSpec = tween(durationMillis = 200),
        label = "cardContentAnimation"
    )

    return CardDefaults.cardColors(
        containerColor = animatedContainer,
        contentColor = animatedContent
    )
}