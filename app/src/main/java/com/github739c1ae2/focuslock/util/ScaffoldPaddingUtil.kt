package com.github739c1ae2.focuslock.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.minus
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier


// FIXME: 暂时找不到官方推荐的方法同时实现以下需求，当前方案仅为 workaround：
//  1. 保持虚拟按键半透明或手势指示条透明（无边框效果）。
//  2. 弹出输入法时能够将输入框完整推入可视区域。
//  3. 边距计算完全正确。
//  当前的方案已经是效果最好的了，但还有一些问题：
//  1. 如果输入框已有焦点且位于屏幕边缘，弹出输入法时不会推动输入框，导致输入框被遮挡。
//  对比其他方案：
//  fitInside(WindowInsetsRulers.Ime.current)：会使动画显示严重异常。
//  不使用 contentPadding，全部放到 Modifier 中：无法让手势指示条透明，因为算到 contentPadding 里的话 UI 还能延伸过去，但 padding 掉之后手势条区域就只剩背景色能显示了。
//  直接使用 innerPadding 作为 contentPadding：边距计算完全正确，但输入框如果在屏幕边界处时，输入法弹出，虽然可以被推高但没有完全推入可视区域，导致被遮挡。
//  Scaffold(contentWindowInsets = WindowInsets.safeDrawing)：同上，给 contentPadding 会使输入法弹出时无法推动输入框，给 Modifier 会导致手势指示条不透明。
//  https://github.com/android/skills/blob/main/system/edge-to-edge/SKILL.md
//  https://developer.android.com/codelabs/edge-to-edge
//  https://developer.android.com/design/ui/mobile/guides/layout-and-content/edge-to-edge

/**
 * Scaffold + IME 场景下给可滚动内容使用的 Insets。
 *
 * LazyColumn 用法：
 * ```kotlin
 * Scaffold { innerPadding ->
 *     val insets = calculateImeAwareScaffoldInsets(innerPadding)
 *
 *     LazyColumn(
 *         modifier = Modifier
 *             .fillMaxSize()
 *             .applyImeInsetsAndConsumeScaffoldPadding(insets),
 *         contentPadding = insets.contentPadding,
 *     ) {
 *         // ...
 *     }
 * }
 * ```
 *
 * Column + verticalScroll 用法：
 * ```kotlin
 * Scaffold { innerPadding ->
 *     val insets = calculateImeAwareScaffoldInsets(innerPadding)
 *
 *     Column(
 *         modifier = Modifier
 *             .fillMaxSize()
 *             .applyImeInsetsAndConsumeScaffoldPadding(insets)
 *             .verticalScroll(rememberScrollState())
 *             .padding(insets.contentPadding),
 *     ) {
 *         // ...
 *     }
 * }
 * ```
 *
 * 注意：
 * - LazyColumn：把 contentPadding 传给 contentPadding 参数
 * - Column：把 contentPadding 放到 verticalScroll() 后面
 */
data class ImeAwareScaffoldInsets(
    val contentPadding: PaddingValues,
    val imeInsets: WindowInsets
)

/**
 * 根据 Scaffold 的 innerPadding 计算 IME-aware 的布局参数。
 */
@Composable
fun calculateImeAwareScaffoldInsets(
    innerPadding: PaddingValues
): ImeAwareScaffoldInsets {
    val imeInsets = WindowInsets.ime
    /*
     * 将 Scaffold 的 bottom 空间拆成两部分：
     * 1. imeInsets.bottom
     *    -> 交给 Modifier
     * 2. innerPadding.bottom - imeInsets.bottom（PaddingValues.minus 会确保最小为 0）
     *    -> 保留在 contentPadding 中
     *
     * 因此最终 bottom 空间始终等于：
     *     max(innerPadding.bottom, imeInsets.bottom)
     *
     * IME 未显示时：
     *     contentPadding.bottom = innerPadding.bottom
     *     Modifier 不额外占用 bottom
     * IME 小于 innerPadding.bottom 时（可能性较低）：
     *     Modifier 占用 IME 高度
     *     contentPadding 保留剩余部分
     * IME 大于 innerPadding.bottom 时：
     *     Modifier 占用整个 IME 高度
     *     contentPadding.bottom = 0
     */
    return ImeAwareScaffoldInsets(
        contentPadding = innerPadding - imeInsets.asPaddingValues(),
        imeInsets = imeInsets,
    )
}

/**
 * 把 calculateImeAwareScaffoldInsets() 计算出的 Insets 中的输入法部分应用到当前 Modifier，并消费所有相关 insets。
 *
 * 注意：
 * - 虽然没有设置 contentPadding，但会把 contentPadding 消费掉，必须配合 `.padding(insets.contentPadding)`
 *  或 `contentPadding = insets.contentPadding` 使用
 */
fun Modifier.applyImeInsetsAndConsumeScaffoldPadding(
    insets: ImeAwareScaffoldInsets
): Modifier =
    this
        .windowInsetsPadding(insets.imeInsets)
        .consumeWindowInsets(insets.contentPadding)