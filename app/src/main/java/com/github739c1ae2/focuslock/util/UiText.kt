package com.github739c1ae2.focuslock.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

sealed class UiText {
    data class DynamicString(val value: String) : UiText()

    class StringResource(@StringRes val resId: Int, vararg args: Any) : UiText() {
        // Own the arguments so callers cannot change equality after publication to StateFlow.
        val args: List<Any> = args.toList()

        override fun equals(other: Any?): Boolean =
            other is StringResource && resId == other.resId && args == other.args

        override fun hashCode(): Int = 31 * resId + args.hashCode()
    }

    @Composable
    fun asString(): String = when (this) {
        is DynamicString -> value
        is StringResource -> stringResource(resId, *args.toTypedArray())
    }
}
