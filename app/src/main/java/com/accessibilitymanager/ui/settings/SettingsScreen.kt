package com.accessibilitymanager.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.accessibilitymanager.R
import com.accessibilitymanager.core.designsystem.component.M3ListItem
import com.accessibilitymanager.core.designsystem.component.M3SectionHeader
import com.accessibilitymanager.core.designsystem.theme.ContrastLevel
import com.accessibilitymanager.core.designsystem.theme.ThemeName
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens

/**
 * 设置页的不可变 UI 状态。
 *
 * 用 `data class`（而非散装 Boolean 参数）的理由：Compose 按**值**判断是否跳过重组，
 * 一个等值的 data class 能让整屏在无关变化时被跳过。
 */
@Immutable
data class SettingsUiState(
    val boot: Boolean,
    val toast: Boolean,
    val hide: Boolean,
    val restartNotify: Boolean,
    /** 已启用定期重启的服务数（文案由 UI 层用资源格式化：模型不持有文案）。 */
    val restartEnabledCount: Int,
    val authGranted: Boolean,
    /** 前台判定模式：true = 焦点判定（已授权使用情况访问），false = 灭屏降级。 */
    val judgementFocus: Boolean,
    val notificationOk: Boolean,
    /** 是否存在"恢复失败"服务（顶部告警）。 */
    val failedAlertVisible: Boolean,
    val themeName: ThemeName,
    val contrast: ContrastLevel,
    /** 暗色三态：0 跟随系统 / 1 浅色 / 2 深色（文案由 UI 层解析）。 */
    val nightMode: Int,
)

/**
 * 设置页（规范 §5「表单：≥4 字段 → 独立页面」）。
 *
 * ## 分组手段（规范 §4）—— **实际用的是「列表项 + 留白」，不是卡片**
 *
 * 本文件**不含 `Card`**（无 `Card` import），每个设置项由 [M3ListItem] 渲染。
 * 组与组之间靠留白分隔（[M3SectionHeader] 自带 32dp 上距）。
 *
 * ⚠️ **这是规范 §4 的一处未决偏离**：口诀「能点进去 → 卡片」按字面适用于本页
 * （设置项可点、可切换，是可独立操作的对象），但实现走的是列表项 + 留白。
 *
 * 该偏离**尚未裁决**，两种走向各有代价：
 * - 改卡片：符合口诀，但设置页会有 10+ 个卡片、满屏盒子（规范 §17 反模式「满屏卡片」）；
 * - 保持现状：页面更轻，但与口诀字面不符。
 *
 * **不要把本页描述成"卡片 + 留白"**（本文件早期 KDoc 如此声称，与实际不符，已更正）。
 *
 * ## 分组标题样式
 *
 * 用 `titleMedium` + `onSurface`（规范 §2「区块标题」）。
 * **原实现用的是 `labelLarge` + `colorPrimary`**，两处都不对：字号偏小、颜色让标题与开关抢注意力。
 *
 * @param onOpenNotificationSettings 通知权限行点击（跳系统通知设置）
 */
@Composable
fun SettingsScreen(
    ui: SettingsUiState,
    onBootChange: (Boolean) -> Unit,
    onToastChange: (Boolean) -> Unit,
    onHideChange: (Boolean) -> Unit,
    onRestartNotifyChange: (Boolean) -> Unit,
    onRegrantClick: () -> Unit,
    onHelpClick: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onThemeRowClick: () -> Unit,
    onContrastRowClick: () -> Unit,
    onNightRowClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.lg),
    ) {
        // 页面标题：一屏一个主角 → 这里是"设置"本身（大标题），故不再叠顶栏标题
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = SpacingTokens.lg,
                bottom = SpacingTokens.xs,
            ),
        )
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── 保活组 ──
        M3SectionHeader(stringResource(R.string.group_keep_alive))
        SwitchRow(
            title = stringResource(R.string.setting_boot),
            supporting = stringResource(R.string.setting_boot_desc),
            checked = ui.boot,
            onCheckedChange = onBootChange,
        )
        SwitchRow(
            title = stringResource(R.string.setting_toast),
            supporting = stringResource(R.string.setting_toast_desc),
            checked = ui.toast,
            onCheckedChange = onToastChange,
        )
        SwitchRow(
            title = stringResource(R.string.setting_hide),
            supporting = stringResource(R.string.setting_hide_desc),
            checked = ui.hide,
            onCheckedChange = onHideChange,
        )

        // ── 定期重启组 ──
        M3SectionHeader(stringResource(R.string.group_restart))
        SwitchRow(
            title = stringResource(R.string.setting_restart_notify),
            supporting = stringResource(R.string.setting_restart_notify_desc),
            checked = ui.restartNotify,
            onCheckedChange = onRestartNotifyChange,
        )
        M3ListItem(
            title = stringResource(R.string.setting_restart_summary, ui.restartEnabledCount),
            supporting = stringResource(R.string.restart_policy_desc),
            supportingMaxLines = 3,
        )

        // ── 授权与帮助组 ──
        M3SectionHeader(stringResource(R.string.group_auth_help))
        if (ui.failedAlertVisible) {
            M3ListItem(
                title = stringResource(R.string.banner_restart_failed),
                titleMaxLines = 3,
            )
        }
        M3ListItem(
            title = stringResource(
                if (ui.authGranted) R.string.auth_status_ok else R.string.auth_status_missing,
            ),
            supporting = stringResource(
                if (ui.judgementFocus) {
                    R.string.auth_judgement_focus
                } else {
                    R.string.auth_judgement_degraded
                },
            ),
            supportingMaxLines = 2,
        )
        M3ListItem(
            title = stringResource(
                if (ui.notificationOk) {
                    R.string.auth_notification_ok
                } else {
                    R.string.auth_notification_missing
                },
            ),
            onClick = onOpenNotificationSettings,
        )
        M3ListItem(
            title = stringResource(R.string.auth_regrant_guide),
            onClick = onRegrantClick,
        )
        M3ListItem(
            title = stringResource(R.string.usage_help),
            onClick = onHelpClick,
        )

        // ── 外观组（规范规则 ④ 的界面：命名主题 + 对比度 + 明暗，三者并列）──
        M3SectionHeader(stringResource(R.string.group_appearance))
        M3ListItem(
            title = stringResource(R.string.setting_theme),
            supporting = ui.themeName.label,
            onClick = onThemeRowClick,
        )
        M3ListItem(
            title = stringResource(R.string.setting_contrast),
            supporting = ui.contrast.label,
            onClick = onContrastRowClick,
        )
        M3ListItem(
            title = stringResource(R.string.setting_night_mode),
            supporting = stringResource(
                when (ui.nightMode) {
                    1 -> R.string.theme_light
                    2 -> R.string.theme_dark
                    else -> R.string.theme_follow_system
                },
            ),
            onClick = onNightRowClick,
        )

        Column(modifier = Modifier.padding(bottom = SpacingTokens.xxl)) {
            Text(
                text = stringResource(R.string.version_line),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 开关行：四槽列表项 + 尾部 Switch（触控目标由 M3 Switch 自身保证 ≥48dp）。 */
@Composable
private fun SwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    M3ListItem(
        title = title,
        supporting = supporting,
        supportingMaxLines = 2,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

/**
 * 单选对话框（主题 / 对比度 / 明暗三态共用）。
 *
 * 用 Compose 的 `AlertDialog` 而非 View 侧 `MaterialAlertDialogBuilder`：
 * 对话框也在组合树内，才能继承 `AppTheme` 的弹簧动效与 token。
 */
@Composable
fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(index) },
                            )
                            .padding(vertical = SpacingTokens.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = SpacingTokens.lg),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_ok),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}
