package com.accessibilitymanager.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 效果 token —— 偏离 MD3 的执行规格（规范 §11）。
 *
 * 动效本身（弹簧）**不在这里定义**：一律走 `MaterialTheme.motionScheme`
 * （`MotionScheme.expressive()`），不得自定时长。这里只放
 * 「弹簧之外的数值参数」：反馈的缩放与变暗。
 */
object EffectTokens {

    // ── 偏离 ②：交互反馈改用缩放 + 变暗（ADR-0002）────────────────────────
    // 不用 ripple。按下**即时**响应，不等抬手。

    /** 一般块（列表行、卡片）的按下缩放。 */
    const val PRESS_SCALE: Float = 0.975f

    /** 大块的按下缩放。 */
    const val PRESS_SCALE_LARGE: Float = 0.98f

    /** 小块的按下缩放。 */
    const val PRESS_SCALE_SMALL: Float = 0.96f

    /** 按下变暗系数（规范范围 0.85–0.90，取 0.86）。 */
    const val PRESS_BRIGHTNESS: Float = 0.86f

    /**
     * 反馈过渡时长（规范：120ms）。
     *
     * 这是**唯一的时长例外**：反馈是「元素自身变化」，且要求按下即响应；
     * 但即便如此也不得扩散到别处使用 —— 其余一律用弹簧。
     */
    const val PRESS_TRANSITION_MILLIS: Int = 120

    /** 模态遮罩不透明度（规范固定 32%，它承担「阻断交互」而非「材质感」）。 */
    const val SCRIM_ALPHA: Float = 0.32f

    /**
     * 聚焦 / 悬停描边宽度。
     *
     * **规范未覆盖该数值**（规范只覆盖按下反馈，未定义键盘 / D-pad 聚焦的指示量级），
     * 参照 MD3 焦点指示的默认量级取 2dp。
     *
     * 它属于「效果参数」而非尺寸刻度，故放这里而不是 [SizeTokens]；
     * 唯一消费方是 `PressFeedback` 的 `appPress`。
     */
    val FOCUS_RING_WIDTH: Dp = 2.dp
}
