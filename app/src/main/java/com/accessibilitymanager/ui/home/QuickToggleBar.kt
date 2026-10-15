package com.accessibilitymanager.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.accessibilitymanager.R
import com.accessibilitymanager.core.designsystem.component.appClickable
import com.accessibilitymanager.core.designsystem.theme.ShapeTokens
import com.accessibilitymanager.core.designsystem.theme.SizeTokens
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens

/**
 * 主页底部**悬浮栏**里的一个功能开关。
 *
 * @param icon 图标（仅图形，无障碍标签必须由本组件挂 —— 见下）
 * @param labelRes 开关名（既作可见文字，也作无障碍标签，避免两处各写一份）
 * @param on 当前是否开启
 * @param onToggle 点击切换
 */
data class QuickToggle(
    val icon: ImageVector,
    val labelRes: Int,
    val on: Boolean,
    val onToggle: () -> Unit,
)

/**
 * 主页底部**悬浮栏** —— 沉在内容之上的悬浮胶囊，**不占布局空间**（内容从它下方滑过）。
 *
 * ## 为什么是悬浮而不是贴底条
 *
 * 「悬浮」的定义就是不参与布局：贴底全宽条会挤掉列表底部空间，还要与导航栏做 insets 让位
 * （既有 `AppNavigationBar` 已经处理过一次这类问题）。胶囊浮起来则彼此独立、互不干扰。
 *
 * ## 为什么用 `Surface` + `ShapeTokens.Full` 而不是 FAB
 *
 * MD3 的 FAB 是**单个主动作**的语义；这里是**一组并列开关**，属「浮起来的工具条」，
 * 故用 `Surface` 自绘：胶囊形状取既有刻度、底色用 `surfaceContainerHigh`
 * （与详情卡同一套浮层口径），**不自创形状与色值**。
 *
 * ## 无障碍
 *
 * 每个可点项的标签挂在**容器**上（`clearAndSetSemantics` 由 [appClickable] 一侧保证），
 * 并额外给出「已开启 / 已关闭」的选中态 —— 纯图形按钮若无状态语义，读屏读不出开关当前是开还是关。
 *
 * @param toggles 栏内开关（顺序即展示顺序）
 */
@Composable
fun QuickToggleBar(
    toggles: List<QuickToggle>,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = ShapeTokens.Full,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = SpacingTokens.sm,
                vertical = SpacingTokens.xs,
            ),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toggles.forEach { t ->
                val label = stringResource(t.labelRes)
                Icon(
                    imageVector = t.icon,
                    // 标签挂在图标上但**补全状态与角色**：读屏需一次听到「名称 + 开/关 + 按钮」
                    contentDescription = stringResource(
                        if (t.on) R.string.quick_toggle_on else R.string.quick_toggle_off,
                        label,
                    ),
                    tint = if (t.on) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        // 自绘可点元素必须显式声明 ≥48dp 触控区（M3 自带组件才不用）
                        .size(SizeTokens.MinTouchTarget)
                        .appClickable(onClick = t.onToggle)
                        .padding(SpacingTokens.sm),
                )
            }
        }
    }
}

/**
 * 悬浮栏的**宿主状态**：宿主写、组合读。
 *
 * 与其他状态持有者同一条分工：宿主（`HomeFragment`）负责 SP 读写与业务副作用，
 * 本类只承载「现在开没开、界面上显示什么」。四个开关的真值来源仍是 `data` SP
 * （`boot` / `toast` / `hide` / `restart_notify`），**与设置页同源，不另存一份**。
 *
 * [enabled] 是**悬浮栏自身的开关**（`data` SP 的 `quick_bar`）：关掉它整条栏不显示。
 */
@Stable
class QuickBarHost {

    /** 悬浮栏自身的开关（设置页可改）。 */
    var enabled by mutableStateOf(true)

    var boot by mutableStateOf(true)
    var toast by mutableStateOf(true)
    var hide by mutableStateOf(true)
    var restartNotify by mutableStateOf(true)

    /** 四个开关的写入回调（由宿主注入，落 `data` SP）。 */
    var onBoot: (Boolean) -> Unit = {}
    var onToast: (Boolean) -> Unit = {}
    var onHide: (Boolean) -> Unit = {}
    var onRestartNotify: (Boolean) -> Unit = {}

    /** 当前该显示哪几个开关。顺序即展示顺序。 */
    fun toggles(): List<QuickToggle> = listOf(
        QuickToggle(Icons.Outlined.PowerSettingsNew, R.string.setting_boot, boot) { onBoot(!boot) },
        QuickToggle(Icons.Outlined.VisibilityOff, R.string.setting_hide, hide) { onHide(!hide) },
        QuickToggle(Icons.Outlined.Notifications, R.string.setting_toast, toast) { onToast(!toast) },
        QuickToggle(Icons.Outlined.RestartAlt, R.string.setting_restart_notify, restartNotify) {
            onRestartNotify(!restartNotify)
        },
    )
}
