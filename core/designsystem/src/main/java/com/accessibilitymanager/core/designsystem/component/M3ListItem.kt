package com.accessibilitymanager.core.designsystem.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.accessibilitymanager.core.designsystem.theme.SizeTokens
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens

/**
 * 标准列表项 —— 四槽（规范 §12.6，**槽位不得为视觉调整而改动**）。
 *
 * | 槽 | 内容 | 约束 |
 * |---|---|---|
 * | 缩略图 | 图或图标 | 可空 |
 * | 主力文本 | 标题 | **可独立控行数** |
 * | 支持文本 | 副标题／说明 | 可独立控行数 |
 * | 尾部动作 | 箭头／开关／菜单 | 可空 |
 *
 * ## 为什么是四槽而不是三槽
 *
 * 三槽方案**无法单独控制标题行数**，标题一长就把尾部动作挤掉。
 * 四槽让「主文本保留完整 → 先截断支持文本」这条截断优先级（规范 §8）真正可实现。
 *
 * ## 行高只取三档
 *
 * 单行 56 / 双行 72 / 三行 88 dp（规范 §3「页面骨架」），不出现第四种。
 * 档位按 `titleMaxLines` 与 `supportingMaxLines` 两个上限推导：
 * 任一达 3 → 三行档；其次「有 supporting」→ 双行档；否则单行档。
 * 该值为 `heightIn(min = …)` 的**最小**高度，内容超出时实际更高（非固定值）。
 */
@Composable
fun M3ListItem(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    titleMaxLines: Int = 2,
    supportingMaxLines: Int = 2,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val minHeight = when {
        titleMaxLines >= 3 || (supporting != null && supportingMaxLines >= 3) -> ListRowHeight.Triple
        supporting != null -> ListRowHeight.Double
        else -> ListRowHeight.Single
    }

    val target = if (onClick != null) {
        modifier.appClickable(
            interactionSource = interactionSource,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        modifier
    }

    Row(
        modifier = target
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(
                horizontal = SpacingTokens.lg,
                vertical = SpacingTokens.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.size(SizeTokens.IconContainerSize),
                contentAlignment = Alignment.Center,
            ) { leading() }
            Spacer(Modifier.width(SpacingTokens.lg))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = supportingMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            Spacer(Modifier.width(SpacingTokens.md))
            trailing()
        }
    }
}

/**
 * 列表行高三档（规范 §3「页面骨架」，**不出现第四种**）。
 *
 * 单独放这里而不是 [SizeTokens]：它只服务于列表项这一个语义。
 */
object ListRowHeight {
    /** 单行。 */
    val Single: Dp = 56.dp

    /** 双行。 */
    val Double: Dp = 72.dp

    /** 三行。 */
    val Triple: Dp = 88.dp
}
