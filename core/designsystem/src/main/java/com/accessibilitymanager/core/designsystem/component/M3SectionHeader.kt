package com.accessibilitymanager.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens

/**
 * 分节标题（规范 §2「场景 → 样式」表：区块标题 = `titleMedium` + `onSurface`）。
 *
 * ## 为什么由本组件自带间距，而不是交给调用方
 *
 * 上 32dp（规范：页面分节 32/48dp）、下 8dp（版块紧随其后）。
 * 分节的纵向韵律是**全局一致的**，逐处各写一遍必然分叉。
 *
 * ## 两个都曾做错的点
 *
 * - 用 `titleSmall`(14sp) → 行高只有 20dp（规范要求 24dp），标题显得比应有的**短**；
 * - 用 `onSurfaceVariant`（次级灰）→ 标题在周围留白里更弱，与下方卡片比例关系失衡。
 */
@Composable
fun M3SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = SpacingTokens.lg,
                end = SpacingTokens.lg,
                top = SpacingTokens.xxl,
                bottom = SpacingTokens.sm,
            ),
    )
}

// 【已删除】M3Section(title, content) —— 零调用。
// 它只是 `M3SectionHeader` + `content()` 的包装（一个 Middle Man），
// 且其 KDoc 主张的「首节不叠加标题底部内边距」在当前布局下并不需要
// （实际做法是顶部不再单独放页面标题，故不存在叠加问题）。
// 若将来确需该包装，应连同"首节间距特例"的真实需求一起加回来。
