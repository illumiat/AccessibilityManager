package com.accessibilitymanager.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 形状十级刻度（规范 §12.1）。
 *
 * **不得自造圆角值** —— 只能从这十级取。角族取 `rounded`（MD3 默认）。
 * 需要某个圆角时到此取用，不要在调用处写 `RoundedCornerShape(13.dp)` 这类值。
 */
object ShapeTokens {
    /** 全宽表面。 */
    val None = RoundedCornerShape(0.dp)

    /** 小标签。 */
    val ExtraSmall = RoundedCornerShape(4.dp)

    /** 列表项内元素。 */
    val Small = RoundedCornerShape(8.dp)

    /** **Card 默认**。 */
    val Medium = RoundedCornerShape(12.dp)

    /** **FAB 默认**。 */
    val Large = RoundedCornerShape(16.dp)

    val LargeIncreased = RoundedCornerShape(20.dp)

    /** 大容器、对话框。 */
    val ExtraLarge = RoundedCornerShape(28.dp)

    val ExtraLargeIncreased = RoundedCornerShape(32.dp)

    /** 底部抽屉顶角。 */
    val ExtraExtraLarge = RoundedCornerShape(48.dp)

    /** **Search bar、Chip、头像**。 */
    val Full = RoundedCornerShape(percent = 50)

    /**
     * 可插值的圆角半径值（供动画使用）。
     *
     * [RoundedCornerShape] 实例之间无法直接动画（会跳变，违反连续性判据 L1），
     * 而动画需要裸数值。把刻度值在这里显式暴露出来，既支持插值，
     * 又保证数值仍**只从刻度出**（不会在调用处出现裸 `12.dp`）。
     *
     * ⚠️ **当前零引用**：本应用尚未有对圆角做插值的动画。保留是因为它对应
     * 规范刻度里的 `Medium`，且删掉会让"要做圆角动画时又得在调用处写裸值"。
     */
    val MediumRadius: Dp = 12.dp

    /**
     * 底部抽屉：只圆顶角（`docs/ui-redesign-plan.md` §4.2 的「弹卡顶角 48dp」）。
     *
     * ⚠️ **注意引用出处**：`48dp 顶角` 出自**项目的执行版规划**（`docs/ui-redesign-plan.md`
     * §4.2「详情弹卡」），**不是** UI 规范技能里的条款 —— 技能中无 §4.2。
     *
     * ⚠️ **当前零引用，且这代表一处未落地的规格项**：
     * 详情弹卡由 MDC 的 `BottomSheetDialog` 承载（用户裁决保留原生 Sheet），
     * 它使用**自己的默认形状**，本 token 用不上。
     * 故规范 §4.2 写的「圆角 `ExtraLarge` 28dp / 顶角 `48dp`」**没有实现**。
     *
     * 保留理由：一旦弹卡改为 Compose 自绘（如 `ModalBottomSheet` 或 overlay），
     * 这个 token 就是落地入口。**不要误以为它已被使用。**
     */
    val BottomSheetTop = RoundedCornerShape(
        topStart = 48.dp,
        topEnd = 48.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )

    // 【已删除】PillRadius = 18.dp
    // 删除理由（两重，任一即足够）：
    //   1) **值不在十级刻度内** —— 刻度为 4/8/12/16/20/28/32/48/Full，18dp 是自造值，
    //      违反「不得自造圆角值」；
    //   2) **零引用** —— 全仓无任何使用点。
}

/**
 * MD3 的五个 `Shapes` 槽位，映射到本项目的刻度。
 * 组件内部走 `MaterialTheme.shapes.*` 时即取到这里的值。
 */
val appShapes = Shapes(
    extraSmall = ShapeTokens.ExtraSmall,
    small = ShapeTokens.Small,
    medium = ShapeTokens.Medium,
    large = ShapeTokens.Large,
    extraLarge = ShapeTokens.ExtraLarge,
)
