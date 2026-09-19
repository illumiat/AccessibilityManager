package com.accessibilitymanager.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距刻度（规范 §12.4，4dp 基线）。
 *
 * 刻度只有这七级：4 / 8 / 12 / 16 / 24 / 32 / 48 dp。**不得自造间距**。
 *
 * 密度纪律（规范 §12.4）：
 * - 组件内部：4 / 8
 * - 同组元素之间：8 / 12
 * - 区块之间：16 / 24
 * - 页面分节：32 / 48
 */
object SpacingTokens {
    /**
     * 零间距。
     *
     * 显式定义一个 0 值条目，让「此处刻意不留白」在代码里可见 ——
     * 写 `0.dp` 会被检查器判为裸尺寸，而直接省略该参数又容易被读成「忘了写」。
     */
    val none = 0.dp

    /** 组件内部。 */
    val xs = 4.dp

    /** 组件内部 / 同组元素之间。 */
    val sm = 8.dp

    /** 同组元素之间。 */
    val md = 12.dp

    /** 区块之间 / 内容边距（compact）。 */
    val lg = 16.dp

    /** 区块之间 / 内容边距（medium）。 */
    val xl = 24.dp

    /** 页面分节 / 内容边距（expanded）。 */
    val xxl = 32.dp

    /** 页面分节。 */
    val xxxl = 48.dp
}

/**
 * 内容边距（规范 §12.4）：compact 16 / medium 24 / expanded 32 dp。
 *
 * 断点（5 级）：<600 / 600–839 / 840–1199 / 1200–1599 / 1600+ dp。
 * 本项目只区分三档边距，故只暴露三个值，避免调用处自行判断断点。
 */
object ContentPadding {
    val Compact: Dp = SpacingTokens.lg
    val Medium: Dp = SpacingTokens.xl
    val Expanded: Dp = SpacingTokens.xxl
}
