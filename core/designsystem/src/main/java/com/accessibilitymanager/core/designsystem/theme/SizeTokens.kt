package com.accessibilitymanager.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 具名尺寸 token。
 *
 * 规范判据 N1 要求「每个视觉决定可追溯到 token、代码中无裸数值」。
 * 这些值不属于间距刻度（[SpacingTokens] 是 4dp 基线的**布局间距**），
 * 也不属于形状刻度（[ShapeTokens] 是**圆角**），故单列一组，
 * 避免在组件里写 `24.dp` / `1.dp` 这类裸字面量。
 */
object SizeTokens {
    /**
     * **触控目标下限 48dp（硬性）**。
     *
     * 规范 §16：「手指能碰到的元素全部 ≥ 48dp」，**不按元件类型开例外**。
     * 视觉上想更紧凑应靠**内边距**收，而不是压缩触控目标。
     * 相邻目标之间还要保 ≥ 8dp（[SpacingTokens.sm]）。
     */
    val MinTouchTarget: Dp = 48.dp

    /** MD3 标准图标固有尺寸。 */
    val IconDefault: Dp = 24.dp

    /** 内联图标（与文字并排的箭头等）——用满尺寸会压过文字。 */
    val IconSmall: Dp = 18.dp

    /** 空态图形尺寸下限（规范 §8「图标与长文本」的图标尺寸表：48–56dp，低对比 `onSurfaceVariant`）。 */
    val EmptyStateIcon: Dp = 56.dp

    /**
     * 列表行里的应用图标。
     *
     * 比 [IconDefault] 大：应用图标是**彩色具象图形**，24dp 看不清细节，
     * 且与单色线条的 Material 图标视觉重量不同。
     */
    val AppIconSize: Dp = 40.dp

    /** 应用图标底色容器（圆形）。 */
    val IconContainerSize: Dp = 40.dp

    /**
     * 主元素图标：详情弹卡头部等**主角槽位**的应用图标。
     *
     * ## 取值的依据（两重，都不是"规范 §8 的某一行"）
     *
     * **① 与迁移前一致**：旧布局 `sheet_service_detail.xml` 的 `iv_icon` 就是 48dp，
     * 迁移不改变尺寸。
     *
     * **② 视觉分量要求**：它所在的位置是「一屏一个主角」（§1）的那个主角，
     * 必须显著大于列表卡片的 [IconContainerSize]（40dp）。
     *
     * **⚠️ 不要**把它说成"取自规范 §8 的 48–56dp 大尺寸区间" —— 已核对 §8 的图标尺寸表
     * 只有四行（内联 20 / 按钮·列表项 24 / 大按钮容器 40 / 空态图形 48–56），
     * 其中 48–56 那行**专指空态图形**，与弹卡头部不是同一场景。
     * 该尺寸属规范未覆盖项，按 §18 以"照抄既有实现 + MD3 默认值"处理。
     */
    val HeroIconSize: Dp = 48.dp

    /** 主题色板色点直径（纯装饰，不承担触控职责）。 */
    val SwatchDotSize: Dp = 24.dp

    /** 发丝线（分割线、描边）厚度。 */
    val Hairline: Dp = 1.dp

    /**
     * 小标签（如「置顶」）的纵向内边距。
     *
     * 比 [SpacingTokens.xs] 更紧：标签高度 = `labelSmall` 行高 16 + 2×2 = 20dp，
     * **不超过标题行高 24dp**，避免标签比标题还高。横向内边距仍用 [SpacingTokens.xs]。
     */
    val TagVerticalPadding: Dp = 2.dp

    /** 底部导航高度（一级页面）。 */
    val NavigationBarHeight: Dp = 80.dp

    /** 顶栏高度（小号）。 */
    val TopBarHeight: Dp = 64.dp

    /** 加载骨架条高度（首屏骨架屏用，形状要接近真实内容）。 */
    val SkeletonLineHeight: Dp = 16.dp
}
