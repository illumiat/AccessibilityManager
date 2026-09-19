package com.accessibilitymanager.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.accessibilitymanager.core.designsystem.theme.ShapeTokens
import com.accessibilitymanager.core.designsystem.theme.SizeTokens

/**
 * 应用图标槽 —— **全站唯一实现**。
 *
 * ## 为什么收口在这里
 *
 * 「已加载 → 画位图；未加载 → 灰底首字占位」是同一个规则。主页列表卡片与详情弹卡头部
 * 是它的两个消费方；各写一份，占位底色/字阶/直径迟早分叉（缺陷清单模式 10：
 * 同功能双实现必须收口单点）。两者只在**直径**上不同，故尺寸是参数。
 *
 * ## 无障碍（关键，曾漏）
 *
 * 图标是**纯图形**，必须始终有 `contentDescription`。早期实现把描述挂在
 * `Image` 上 —— 那只覆盖「已加载」分支；**占位分支（占位符首次渲染的常见路径）在
 * 无障碍树里完全没有标签**，读屏软件读不出这一屏的主角是什么。
 *
 * 故改为：在**容器**上 `clearAndSetSemantics` 一次性给出描述，并清空子节点语义
 * （对纯图标正是想要的 —— 占位首字不应被当成独立文本来读）。
 *
 * @param icon `null` = 尚未加载 → 显示 [initial] 占位（**不得阻塞调用方绑定**）
 * @param initial 占位首字（图标未就绪时显示）
 * @param contentDescription 无障碍标签；传 `null` 表示装饰性图标
 */
@Composable
fun M3AppIcon(
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    initial: String,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .then(semanticsModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null, // 由容器统一承载，避免重复播报
                modifier = Modifier.size(size),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(ShapeTokens.Full)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    // 占位首字的字号随直径档位走（小图标用 titleMedium 会撑破）
                    style = if (size >= SizeTokens.HeroIconSize) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
