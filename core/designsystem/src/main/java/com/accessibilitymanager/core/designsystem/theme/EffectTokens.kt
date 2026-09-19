package com.accessibilitymanager.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 效果 token —— 三处偏离 MD3 的执行规格（规范 §11）。
 *
 * 动效本身（弹簧）**不在这里定义**：一律走 `MaterialTheme.motionScheme`
 * （`MotionScheme.expressive()`），不得自定时长。这里只放
 * 「弹簧之外的数值参数」：叠加层不透明度/模糊半径、反馈的缩放与变暗。
 */
object EffectTokens {

    // ── 偏离 ①：层级改用半透明 + 模糊（ADR-0001）──────────────────────────
    // 不使用 tonal elevation（surfaceTint 叠色）表达高度。

    /** 叠加层底色角色（bottom sheet 用 surfaceContainerLow）。 */
    const val OVERLAY_ROLE: String = "surfaceContainer"

    /** 叠加层不透明度（规范范围 55–70%，取 58%）。 */
    const val OVERLAY_ALPHA: Float = 0.58f

    /** 模糊半径（低端机降到 [OverlayBlurRadiusLowEnd]）。 */
    val OverlayBlurRadius: Dp = 16.dp

    /** 低端机模糊半径。 */
    val OverlayBlurRadiusLowEnd: Dp = 12.dp

    /** 叠加层出现/消失的过渡时长（规范：150ms）。 */
    const val OVERLAY_TRANSITION_MILLIS: Int = 150

    /**
     * 模糊不可用时的**降级目标**：`surfaceContainerHigh` **实色**。
     *
     * 规范明确：Android 上实时模糊开销真实存在，**降级是必要条件，不是可选优化**；
     * 且**叠加层底下必须有真实滚动内容** —— 纯色背景上做模糊只会得到灰雾，
     * **比实色更差**。不允许自创中间不透明度（如 92%）：两头不靠、不合规。
     */
    const val SOLID_FALLBACK_ROLE: String = "surfaceContainerHigh"

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
}
