package com.accessibilitymanager.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.accessibilitymanager.R
import com.accessibilitymanager.core.designsystem.component.ListRowHeight
import com.accessibilitymanager.core.designsystem.component.M3AppIcon
import com.accessibilitymanager.core.designsystem.component.appCombinedClickable
import com.accessibilitymanager.core.designsystem.theme.ShapeTokens
import com.accessibilitymanager.core.designsystem.theme.SizeTokens
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens
import com.accessibilitymanager.ui.model.ServiceDescKind
import com.accessibilitymanager.ui.model.ServiceUiModel
import com.accessibilitymanager.ui.model.WarningKind

/**
 * 服务卡片（主页列表项）。
 *
 * ## 卡片 vs 分隔线
 *
 * 规范 §4 口诀：**能点进去 → 卡片**。本行可点开详情、且尾部带独立动作，属「可独立操作的对象」。
 *
 * ## 槽位（规范 §12.6 四槽）
 *
 * | 槽 | 内容 |
 * |---|---|
 * | 缩略图 | 应用图标 40dp（未加载 → 灰底首字占位） |
 * | 主力文本 | 应用名/服务名（最多 2 行，**可独立控行数**） |
 * | 支持文本 | 状态语义行（失败 / 重启中 / 重启摘要 / 服务描述）（**限 1 行**，见上方「高度」节） |
 * | 尾部动作 | 状态区（警示文字 + 置顶标签 + 锁定按钮）+ 开关 |
 *
 * **偏离说明（既有裁决，非本次新增）**：规范建议尾部动作「多个时按优先级取前若干」，
 * 但本项目经评审明确：**锁定按钮与开关是卡片必备控件，任何列数下都必须渲染**；
 * 宽度不足时收缩的是描述文本，绝不动操作控件。
 *
 * ## 高度：**固定 88dp 等高**（经真机取证 + 用户裁决）
 *
 * 迁移后早期版本让卡片高度随内容自然撑开，真机实测出现 **64 / 84 / 108dp 三种高度、
 * 且同一行内两个卡片也不等高**（多列时尤其刺眼）。构成关系实测吻合：
 * `高度 = 16(内边距) + 标题行数×24 + 4(间距) + 描述行数×20`。
 *
 * 故改为：**标题最多 2 行 + 描述最多 1 行 + 最小高度 88dp**。于是
 * `16 + 48 + 4 + 20 = 88dp` —— 常规字号下**所有卡片恒等 88dp**，
 * 正好落在规范「列表项高度只取 56 / 72 / 88 三档」的最高档上。
 *
 * **用 `heightIn(min =)` 而不是 `height(=)`** 是关键：系统字号调到最大时允许卡片自然增高
 * （规范 §8 要求「字号最大时布局重排而不是截断」），代价是那种极端情况下会失去等高 ——
 * 这是刻意的取舍，不是遗漏。
 *
 * **描述由 2 行减为 1 行**是等高所必需的（否则「标题 1 行 + 描述 2 行」会撑到 84dp）。
 * 截断方向符合规范 §8 的优先级「主文本保留完整 → 先截断支持文本」。
 *
 * ## 反馈
 *
 * 整卡用 `appCombinedClickable`（**缩放 + 变暗，不用 ripple**）；
 * 锁定按钮为自绘 48dp 触控盒（不用带 ripple 的 `IconButton`）；
 * `Switch` 沿用 M3 自身形态变化 —— 形态是**非动效通道**，满足判据 L4。
 */
@Composable
fun ServiceCard(
    model: ServiceUiModel,
    onToggle: (Boolean) -> Unit,
    onLockClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .appCombinedClickable(
                interactionSource = interactionSource,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        shape = ShapeTokens.Medium,
        colors = CardDefaults.cardColors(
            // 置顶加深一档：静止态只落 elevation 0–3，高度靠 surfaceContainer 角色区分（规范 §12.2）。
            containerColor = if (model.pinned) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(SizeTokens.Hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ 最小高度必须挂在 **Row** 上，不能挂在 Card 上。
                //
                // 挂在 Card 上时（早期实现）Card 确有 88dp，但内部 Row 仍按内容收缩 ——
                // 真机实测：Row 只有 48dp（标题 1 行 24 + 间距 4 + 描述 1 行 20），
                // 于是内容**顶部对齐**、卡片底部留下 32dp 死区，图标中心比卡片中心高 12dp。
                // 用户反馈的「图标偏置」即此。
                //
                // 挂在 Row 上后：Row 至少 88dp，减去上下内边距 16dp 得 72dp 内容区，
                // `CenterVertically` 把图标与文本整体垂直居中。
                .heightIn(min = ListRowHeight.Triple)
                .padding(
                    start = SpacingTokens.md,
                    end = SpacingTokens.sm,
                    top = SpacingTokens.sm,
                    bottom = SpacingTokens.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServiceIcon(model)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = SpacingTokens.md),
            ) {
                // 标题行：标题独占（不掺入状态标记，避免挤窄标题）。
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(SpacingTokens.xs))
                Text(
                    text = model.descriptionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (model.descriptionKind == ServiceDescKind.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // 支持文本限 1 行：这是卡片等高（88dp）的必要条件，
                    // 且截断方向符合规范 §8「主文本保留完整 → 先截断支持文本」。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 尾部状态区。
            //
            // **纵向排列，不并排**：置顶标签在上、锁定按钮在下，整列水平居中。
            // - 非置顶时列里只有锁定按钮 → 它落在列中央，即"锁居中"；
            // - 置顶时标签在上、锁在下。
            //
            // 高度正好卡住：`标签 20dp + 间距 4dp + 锁 48dp = 72dp`，
            // 等于卡片内容区（`88 - 8 - 8 = 72dp`）→ **不会把卡片撑高，等高不受影响**。
            // （系统字号放大时标签变高会超出，那时卡片自然增高不截断，见上方 KDoc。）
            WarningBadge(model)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (model.pinned) {
                    PinnedLabel()
                    Spacer(Modifier.height(SpacingTokens.xs))
                }
                LockAction(model = model, onLockClick = onLockClick)
            }

            Switch(
                checked = model.enabled,
                onCheckedChange = onToggle,
                enabled = model.toggleEnabled,
                // 与左侧锁定按钮（48dp 自绘触控盒）之间的间距：
                // 规范 §3 要求「相邻触控目标间 ≥8dp」，故必须用 sm(8dp) 而非 xs(4dp)。
                // 早期实现用 xs → 两个相邻目标只隔 4dp，属实测可见的规范违规。
                modifier = Modifier.padding(start = SpacingTokens.sm),
            )
        }
    }
}

/**
 * 缩略图槽：已加载显示位图，未加载显示**灰底首字占位**。
 *
 * 占位在 Compose 侧直接绘制（位图由 `IconCache` 提供，但**占位不走向量图路径**）：
 * 少一次位图分配与一次缓存查找，且随主题自动换色。
 *
 * **图标/占位的渲染收口在 `M3AppIcon`** —— 详情头部是同一规则的另一个消费方，
 * 各写一份必然分叉（占位底色、字阶、直径）。
 *
 * **本槽不再承载任何角标**：置顶标签在**尾部状态区**（见 [PinnedLabel]）。
 */
@Composable
private fun ServiceIcon(model: ServiceUiModel) {
    M3AppIcon(
        icon = model.icon,
        initial = model.iconInitial,
        contentDescription = stringResource(R.string.service_icon_desc),
        size = SizeTokens.IconContainerSize,
    )
}

/**
 * 「置顶」标签 —— 位于**尾部状态区**，与锁定按钮相邻。
 *
 * ## 为什么不再叠在图标角上
 *
 * 早期实现把它叠在图标容器的 `Alignment.TopEnd`。真机实测：该标签为 **79×56px（22.6×16dp）**，
 * 而图标容器只有 **140×140px（40×40dp）**，且标签横向范围 `145~224` **完全落在**图标 `98~238` 之内
 * —— 也就是它整个盖在应用图标右上角上，遮住约四分之一。
 *
 * 根因是**语义错配**：MD3 放在图标角上的是**小圆点**（覆盖是刻意的，但面积极小、不遮挡内容）；
 * 而这里是**带文字的长方形标签**，一覆盖就遮住了图标本身。
 *
 * ## 为什么落在尾部、且与锁定按钮**纵向**排列
 *
 * 曾试过放在标题行做前缀（读作「置顶 GKD」），但实测会**挤窄标题约 29dp**，
 * 使长标题更早折到第二行。放进尾部状态区后：
 *
 * 1. 不遮挡图标；
 * 2. **标题恢复完整宽度**；
 * 3. 与锁定按钮同处一区 —— 两者都是「该服务的状态标记」，语义上成组；
 * 4. 尾部已有预留宽度（未启用服务时锁定按钮仍占着 48dp 空盒），标签进来不会挤压内容列。
 *
 * **与锁纵向排列（标签在上、锁在下）而非并排**：
 * - 并排时尾部会横向变宽，挤占内容列；纵向排列只占锁的宽度（48dp）；
 * - 高度恰好等于内容区：`标签 20 + 间距 4 + 锁 48 = 72dp = 88 - 16`，不撑高卡片；
 * - **非置顶时列内只有锁 → 锁自然居中**，与置顶态视觉重心一致。
 *
 * 未改用纯色圆点：那属**颜色通道**，规范禁止「用颜色表达状态、不给文字」。
 */
@Composable
private fun PinnedLabel() {
    Text(
        text = stringResource(R.string.service_top_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(ShapeTokens.ExtraSmall)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(
                horizontal = SpacingTokens.xs,
                vertical = SizeTokens.TagVerticalPadding,
            ),
    )
}

/**
 * 警示角标槽。
 *
 * **文字而非纯色**：规范禁止「用颜色表达状态、不给文字」。
 *
 * 优先级与理由**以 [ServiceUiModel.warning] / [WarningKind] 的 KDoc 为准** ——
 * 那里完整记录了「`FAILED` 优先于 `AUTO_RESTORED` 是对迁移前行为的刻意更正」及其真实理由。
 *
 * ⚠️ 此处曾写「既有 R5 裁决」，那是**错误依据**（R5 裁决的是 `failed` 与 `pendingEnable`，
 * 从未裁决过 `autoRestored` 的位置），已在 `ServiceUiModel.kt` 更正，本处同步移除该说法。
 */
@Composable
private fun WarningBadge(model: ServiceUiModel) {
    val kind = model.warning ?: return
    val text = when (kind) {
        WarningKind.FAILED -> stringResource(R.string.service_restart_failed)
        WarningKind.AUTO_RESTORED -> stringResource(R.string.service_auto_restored)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (kind == WarningKind.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        modifier = Modifier.padding(start = SpacingTokens.sm),
    )
}

/**
 * 尾部动作：保活锁定按钮。
 *
 * **触控目标 ≥48dp**：视觉图标 24dp，触控盒 48dp（规范硬性要求，不按元件类型开例外）。
 * 未启用服务时不渲染图标（保留 48dp 占位盒，避免整行宽度抖动）。
 */
@Composable
private fun LockAction(
    model: ServiceUiModel,
    onLockClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(SizeTokens.MinTouchTarget)
            .appCombinedClickable(
                interactionSource = interactionSource,
                enabled = model.enabled,
                role = Role.Button,
                onClick = onLockClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (model.enabled) {
            Icon(
                imageVector = if (model.locked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                contentDescription = stringResource(R.string.service_lock_desc),
                tint = if (model.locked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(SizeTokens.IconDefault),
            )
        }
    }
}
