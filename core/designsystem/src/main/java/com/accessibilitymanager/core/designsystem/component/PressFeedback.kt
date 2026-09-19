package com.accessibilitymanager.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.accessibilitymanager.core.designsystem.theme.EffectTokens

/**
 * 按下反馈（规范 §11.2，**偏离 MD3 ②**）。
 *
 * 不用 ripple —— 按下改**缩放 + 变暗**，且**按下即响应**，不等抬手。
 *
 * ## 纪律
 *
 * - 缩放幅度**在 token 层定全局值**（[EffectTokens.PRESS_SCALE]），
 *   逐组件各调直接违反规范性判据 N2。
 * - **长列表滚动中不得触发**：由调用方保证（滚动中不派发按下态）。
 * - **动效不是唯一反馈**：可点/状态必须另有非动效表达（判据 L4）。
 * - 过渡**取 `motionScheme` 的 effects spec**，不自定时长、不用 `tween`。
 *   （规范 §11.2 的 120ms 是原型实测参考值，不是实现的时长来源。）
 *
 * @param interactionSource 与点击同源，保证「按下」与「点击」是同一份手势状态。
 * @param scale 缩放幅度，默认取 token；仅大块/小块可传对应 token 值。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Modifier.appPress(
    interactionSource: MutableInteractionSource,
    scale: Float = EffectTokens.PRESS_SCALE,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val appliedScale by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = spec,
        label = "appPressScale",
    )

    return this
        .graphicsLayer {
            scaleX = appliedScale
            scaleY = appliedScale
        }
        .drawWithContent {
            drawContent()
            // 变暗：**中性黑叠层**，alpha = 1 - 0.86 = 0.14。
            //
            // ⚠️ 本注释曾写「以 scrim 色叠层等效 brightness 0.86（token 化，非裸色值）」，
            // **那是错的自我辩护**：
            //   - `Color.Black` **不是** scrim 角色（scrim 是 `colorScheme.scrim`，用于模态遮罩的
            //     32% 阻断层，语义完全不同）；
            //   - 它也就**不是**"非裸色值"。
            //
            // 为什么仍用 `Color.Black` 而不是某个语义角色：**变暗在物理上要求无彩色的压暗层** ——
            // 任何带色调的角色（primary/error/scrim）都会引入色偏，把"按暗一点"变成"按下去变色"。
            // 这与 `Color.Transparent` 属同类：**结构性基元**，不是主题可配置的角色色。
            // 唯一可配置的量是**强度**，它已 token 化（[EffectTokens.PRESS_BRIGHTNESS]）。
            if (pressed) {
                drawRect(
                    color = Color.Black,
                    alpha = 1f - EffectTokens.PRESS_BRIGHTNESS,
                )
            }
        }
}

/**
 * 可点击 + 按下反馈的组合修饰符。
 *
 * `indication = null` 是**刻意**的：MD3 组件默认用 ripple，本规范改用缩放 + 变暗，
 * 故必须显式去掉 ripple（去掉后每个可交互组件都要自己处理反馈，不能再白拿）。
 */
@Composable
fun Modifier.appClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    scale: Float = EffectTokens.PRESS_SCALE,
    onClick: () -> Unit,
): Modifier = this
    .appPress(interactionSource = interactionSource, scale = scale)
    .clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )

/** 便捷重载：内部自建 `interactionSource`，供一次性使用（非复用列表）场景。 */
@Composable
fun Modifier.appClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    scale: Float = EffectTokens.PRESS_SCALE,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.appClickable(
        interactionSource = source,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        scale = scale,
        onClick = onClick,
    )
}

/**
 * 可点击 + 可长按 + 按下反馈（示例：卡片长按置顶）。
 *
 * 与 [appClickable] 同样是 `indication = null`（不用 ripple）。
 * **长按同样不是唯一通道** —— 长按的功能必须有可发现的替代入口，
 * 否则违反判据 L4 与「图标按钮既无文字也无 tooltip」的反模式。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.appCombinedClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    scale: Float = EffectTokens.PRESS_SCALE,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this
    .appPress(interactionSource = interactionSource, scale = scale)
    .combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onClick = onClick,
    )
