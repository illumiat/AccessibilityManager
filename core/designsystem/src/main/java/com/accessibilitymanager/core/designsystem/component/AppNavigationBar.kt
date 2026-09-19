package com.accessibilitymanager.core.designsystem.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航的一个目的。
 *
 * ## 为什么用两态图标（selected / unselected）
 *
 * MD3 的 `NavigationBar` 以「图标形态变化 + 药丸指示器 + 标签」共同表达选中态。
 * 只给一个实心图标时，选中态只剩药丸一种通道 —— 而规范要求
 * **动效/形态不得是唯一信息通道**（判据 L4）。故这里强制要求提供两态图标。
 *
 * @param key 目的标识（由调用方定义，如 `"home"`）
 * @param label 标签文案（**由调用方传入**：设计系统不持有业务文案）
 */
data class NavItem(
    val key: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

/**
 * 底部导航。
 *
 * ## ⚠️ 与本规范 §5 冲突（未裁决，勿当作"符合规范"）
 *
 * 规范 §5「导航」表：
 *
 * | 一级目的数 | 选择 |
 * |---|---|
 * | **< 3** | **不用底部导航，顶栏返回即可** |
 * | 3–5 | `NavigationBar` |
 *
 * **本项目只有 2 个一级目的（主页 / 设置）**，按该表**不应该有底部导航**；
 * 而本组件用了 `NavigationBar`。这是**偏离**，不是符合。
 *
 * 未改动的理由：底部导航是**既有信息架构**（旧实现即 `BottomNavigationView`），
 * 改动它等于重构导航与两个页面的承载方式，超出 UI 规范化范围；且 2 个目的用底部导航
 * 在实践中常见、用户也熟悉。**如需对齐规范，应单独立项裁决**（选项：改为设置入口放顶栏 / 保留但记录偏离）。
 *
 * 本 KDoc 早期版本写「本项目 2 个」却未标偏离，读起来像符合 —— 已更正。
 *
 * ## 取值依据（规范 §2「场景 → 样式」）
 *
 * | 场景 | 样式 |
 * |---|---|
 * | 底部导航标签 | `labelMedium`（`NavigationBarItem` 默认即为该样式） |
 *
 * ## 尺寸
 *
 * `NavigationBar` 自身高度即为规范要求的 80dp（`SizeTokens.NavigationBarHeight`），
 * **不额外覆写** —— 覆写会破坏 MD3 的触控目标与标签基线。
 *
 * @param windowInsets 默认取 MD3 的窗口 insets。**活动窗口未开启 edge-to-edge 时，
 *        必须由调用方传 `WindowInsets(0)`** —— 否则系统已给内容让出的底部区域
 *        会被二次让位，底栏被顶高（缺陷清单模式 9 的 insets 一致性问题）。
 */
@Composable
fun AppNavigationBar(
    items: List<NavItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationBarDefaults.windowInsets,
) {
    NavigationBar(
        modifier = modifier,
        windowInsets = windowInsets,
    ) {
        items.forEach { item ->
            val selected = item.key == selectedKey
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(item.key) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        // 标签已承载语义，图标为装饰性 —— 置 null 避免读屏重复播报。
                        contentDescription = null,
                    )
                },
                label = { Text(text = item.label) },
            )
        }
    }
}
