package com.accessibilitymanager.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.accessibilitymanager.core.designsystem.theme.ShapeTokens
import com.accessibilitymanager.core.designsystem.theme.SizeTokens
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens

/**
 * 只读标签片（Chip 的**非交互形态**）。
 *
 * ## 为什么自绘而不套 MD3 组件 —— 一手核实，不是取舍
 *
 * 已用 `javap` 核对 material3 1.5.0-alpha18 的 `ChipKt`：**该版本不存在非交互 Chip**。
 * `AssistChip` / `SuggestionChip` / `FilterChip` / `InputChip` 的**第一个参数无一例外是
 * `Function0<Unit> onClick`**。用它渲染"执行手势""读取屏幕内容"这类**纯展示标签**，
 * 等于给无障碍树挂上一批点了没反应的假按钮 —— 违反「控件不得是唯一信息通道」，
 * 也是 §17 反模式「有交互外观却无对应动作」的同类问题。
 *
 * 故这里**照抄 MD3 outlined chip 的外观**（胶囊 `Full` + 1dp `outline` 描边 +
 * `labelLarge` 文字），只是去掉交互。这属于规范 §18「规范里没写 → 按 MD3 默认值做」
 * 的最近邻照抄，**不是** §9② 禁止的"偏离 MD3 默认值"。
 *
 * ## 为什么用 `Modifier.border` 而不是 `Surface`
 *
 * `Surface` 需要一个容器色，就得到处写 `Color.Transparent` —— 而"透明"不是一个
 * 语义角色。改用 `border` 直接描边，连容器色都不需要（语义角色只剩 `outline`
 * 与文字色两个，都来自主题）。
 */
@Composable
fun M3TagChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .border(
                border = BorderStroke(SizeTokens.Hairline, MaterialTheme.colorScheme.outline),
                shape = ShapeTokens.Full,
            )
            .padding(
                horizontal = SpacingTokens.md,
                vertical = SpacingTokens.sm,
            ),
    )
}
