package com.accessibilitymanager

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.accessibilitymanager.core.designsystem.theme.AppTheme
import com.accessibilitymanager.core.designsystem.theme.ContrastLevel
import com.accessibilitymanager.core.designsystem.theme.ThemeName
import com.accessibilitymanager.ui.settings.SettingsScreen
import com.accessibilitymanager.ui.settings.SettingsUiState
import com.accessibilitymanager.ui.settings.SingleChoiceDialog

/**
 * 设置页（**P5：迁 Compose**）。
 *
 * ## 结构
 *
 * 本类只做三件事：持状态、写 SP、弹选择器；**界面全部在 [SettingsScreen]**。
 * 与主页同理 —— **写入路径留在宿主**（`hide` 改动即时生效走 ActivityManager，
 * 主题/对比度改动需重建 Activity，因为主题属性是静态资源、无法热替换）。
 *
 * ## 与旧 View 实现的差异
 *
 * - 4 个 `MaterialSwitch` 的手工 `setChecked` + listener 时序纪律**消失**：
 *   Compose 的 `Switch` 由状态单向驱动，不存在「程序化赋值触发监听器」的循环风险。
 * - 授权状态/判定模式/通知权限三行由 `refresh()` 一次性重算（原来各写一遍 setText）。
 * - 主题选择器从 View 对话框换成 Compose 对话框，并**补齐规范规则 ④ 要求的两个维度**：
 *   命名主题（品牌/中性/单色/跟随壁纸）+ 对比度四档。
 */
class SettingsFragment : Fragment() {

    private enum class Choice { THEME, CONTRAST, NIGHT }

    private lateinit var sp: SharedPreferences

    private var ui by mutableStateOf(
        SettingsUiState(
            boot = true,
            toast = true,
            hide = true,
            restartNotify = true,
            restartEnabledCount = 0,
            authGranted = false,
            judgementFocus = false,
            notificationOk = false,
            failedAlertVisible = false,
            themeName = ThemeName.BRAND,
            contrast = ContrastLevel.DEFAULT,
            nightMode = ThemePref.NIGHT_FOLLOW_SYSTEM,
        ),
    )

    private var choice by mutableStateOf<Choice?>(null)
    private var showHelp by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = requireContext().getSharedPreferences("data", 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            AppTheme {
                SettingsScreen(
                    ui = ui,
                    // ⚠️ 四个开关回调都必须**同时更新 `ui`**。
                    //
                    // 原因：`Switch` 是**无状态**组件，位置完全由 `checked = ui.*` 决定，
                    // 自身不持有状态。若回调只写 SP 而不更新 `ui`，`checked` 仍是旧值 ——
                    // 拨动后开关**视觉回弹**（或干脆不动），要等 `onResume` → `refresh()`
                    // 重算 `ui` 才追上，期间用户看到的是「我拨了但没反应」。
                    //
                    // 迁移前的 View 实现由 `MaterialSwitch` 自持状态，故无此问题；
                    // 这是 Compose 化带来的**状态归属变化**，必须由回调补上这一拍。
                    // 真值来源仍是 SP（`refresh()` 从 SP 重算），此处只是立即镜像。
                    onBootChange = {
                        sp.edit().putBoolean("boot", it).apply()
                        ui = ui.copy(boot = it)
                    },
                    onToastChange = {
                        sp.edit().putBoolean("toast", it).apply()
                        ui = ui.copy(toast = it)
                    },
                    onHideChange = {
                        // 【盲审修订】hide 改动即时生效
                        sp.edit().putBoolean("hide", it).apply()
                        ui = ui.copy(hide = it)
                        applyHide(it)
                    },
                    onRestartNotifyChange = {
                        sp.edit().putBoolean("restart_notify", it).apply()
                        ui = ui.copy(restartNotify = it)
                    },
                    onRegrantClick = { PermissionHelper.showPermissionDialog(requireContext()) },
                    onHelpClick = { showHelp = true },
                    onOpenNotificationSettings = { openNotificationSettings() },
                    onThemeRowClick = { choice = Choice.THEME },
                    onContrastRowClick = { choice = Choice.CONTRAST },
                    onNightRowClick = { choice = Choice.NIGHT },
                )

                // ── 选择器（主题 / 对比度 / 明暗）──
                when (choice) {
                    Choice.THEME -> SingleChoiceDialog(
                        title = getString(R.string.setting_theme),
                        options = ThemeName.entries.map { it.label },
                        selectedIndex = ThemeName.entries.indexOf(ui.themeName),
                        onSelect = { index ->
                            ThemePref.setTheme(requireContext(), ThemeName.entries[index])
                            choice = null
                            // 主题属性是静态资源：必须重建 Activity 才能生效（无法热替换）
                            requireActivity().recreate()
                        },
                        onDismiss = { choice = null },
                    )

                    Choice.CONTRAST -> SingleChoiceDialog(
                        title = getString(R.string.setting_contrast),
                        options = ContrastLevel.entries.map { it.label },
                        selectedIndex = ContrastLevel.entries.indexOf(ui.contrast),
                        onSelect = { index ->
                            ThemePref.setContrast(requireContext(), ContrastLevel.entries[index])
                            choice = null
                            requireActivity().recreate()
                        },
                        onDismiss = { choice = null },
                    )

                    Choice.NIGHT -> SingleChoiceDialog(
                        title = getString(R.string.setting_night_mode),
                        options = listOf(
                            getString(R.string.theme_follow_system),
                            getString(R.string.theme_light),
                            getString(R.string.theme_dark),
                        ),
                        selectedIndex = ui.nightMode,
                        onSelect = { index ->
                            // setDefaultNightMode 自身会触发 Activity 重建，无需显式 recreate
                            // 对话框顺序 [跟随系统, 浅色, 深色] 与三态常量一一对应
                            val mode = when (index) {
                                ThemePref.NIGHT_FOLLOW_SYSTEM -> ThemePref.NIGHT_FOLLOW_SYSTEM
                                ThemePref.NIGHT_LIGHT -> ThemePref.NIGHT_LIGHT
                                ThemePref.NIGHT_DARK -> ThemePref.NIGHT_DARK
                                else -> ThemePref.NIGHT_FOLLOW_SYSTEM
                            }
                            ThemePref.setNightMode(requireContext(), mode)
                            App.setThemeMode(mode)
                            // 与上方四个开关一致：立即镜像选择，避免 setDefaultNightMode 未触发重建时选中项滞后
                            ui = ui.copy(nightMode = mode)
                            choice = null
                        },
                        onDismiss = { choice = null },
                    )

                    null -> Unit
                }

                if (showHelp) {
                    AlertDialog(
                        onDismissRequest = { showHelp = false },
                        title = {
                            Text(
                                text = getString(R.string.usage_help),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        },
                        text = {
                            Text(
                                text = getString(R.string.usage_help_detail),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showHelp = false }) {
                                Text(getString(R.string.action_ok))
                            }
                        },
                    )
                }
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 一次性重算全部展示态（授权/判定模式/通知/概览/外观）。 */
    private fun refresh() {
        val ctx = context ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        ui = SettingsUiState(
            boot = sp.getBoolean("boot", true),
            toast = sp.getBoolean("toast", true),
            hide = sp.getBoolean("hide", true),
            restartNotify = sp.getBoolean("restart_notify", true),
            restartEnabledCount = RestartPrefs.enabledCount(ctx).toInt(),
            authGranted = PermissionHelper.hasWritePermission(ctx),
            // 判定模式：appops 每页校验（PACKAGE_USAGE_STATS 可被 ROM 重置）【三轮 P1 修正】
            judgementFocus = RestartPrefs.usageStatsGranted(ctx),
            notificationOk = nm != null && nm.areNotificationsEnabled(), // 【M-d】判 null 防 NPE
            failedAlertVisible = RestartPrefs.getFailed(ctx).isNotEmpty(),
            themeName = ThemePref.theme(ctx),
            contrast = ThemePref.contrast(ctx),
            nightMode = ThemePref.nightMode(ctx),
        )
    }

    private fun applyHide(hide: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .appTasks.get(0).setExcludeFromRecents(hide)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName),
                )
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.toast_activate_fail, Toast.LENGTH_SHORT).show()
        }
    }
}
