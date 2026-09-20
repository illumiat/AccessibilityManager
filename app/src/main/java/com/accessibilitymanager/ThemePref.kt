package com.accessibilitymanager

import android.app.Activity
import android.content.Context
import com.accessibilitymanager.core.designsystem.theme.ContrastLevel
import com.accessibilitymanager.core.designsystem.theme.ThemeCatalog
import com.accessibilitymanager.core.designsystem.theme.ThemeName

/**
 * 主题偏好的持久化与套用。
 *
 * ## 为什么在 View 层（而不是 Compose 侧）
 *
 * Android 只能通过 `setTheme` 切换主题、且必须发生在 `super.onCreate()` 之前，
 * 所以**选择的持有与套用属于 Activity 生命周期**，Compose 侧只消费结果
 * （`AppTheme` 从主题属性解析配色，见 `core/designsystem`）。
 *
 * ## 键位约定
 *
 * 沿用既有 `data` SP（与 `App.java` 的暗色三态键 `theme` 同库，避免两处存储各说各话）。
 */
object ThemePref {

    internal const val SP = "data"
    private const val KEY_NAME = "ui_theme_name"
    private const val KEY_CONTRAST = "ui_theme_contrast"
    private const val KEY_NIGHT = "theme"

    /** 暗色三态：0=跟随系统 1=浅色 2=深色（**既有键，保持兼容**）。 */
    const val NIGHT_FOLLOW_SYSTEM = 0
    const val NIGHT_LIGHT = 1
    const val NIGHT_DARK = 2

    private fun sp(context: Context) = context.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 当前命名主题。默认**品牌**（规范：品牌主题是产品色彩识别的载体，不让位给壁纸）。 */
    @JvmStatic
    fun theme(context: Context): ThemeName {
        val raw = sp(context).getString(KEY_NAME, null) ?: return ThemeName.BRAND
        return ThemeName.entries.firstOrNull { it.key == raw } ?: ThemeName.BRAND
    }

    /** 当前对比度档位。默认 `0.0`。 */
    @JvmStatic
    fun contrast(context: Context): ContrastLevel {
        val raw = sp(context).getString(KEY_CONTRAST, null) ?: return ContrastLevel.DEFAULT
        return ContrastLevel.entries.firstOrNull { it.name == raw } ?: ContrastLevel.DEFAULT
    }

    @JvmStatic
    fun setTheme(context: Context, theme: ThemeName) {
        sp(context).edit().putString(KEY_NAME, theme.key).apply()
    }

    @JvmStatic
    fun setContrast(context: Context, contrast: ContrastLevel) {
        sp(context).edit().putString(KEY_CONTRAST, contrast.name).apply()
    }

    @JvmStatic
    fun setNightMode(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_NIGHT, mode).apply()
    }

    /** 已存的夜间三态值；SP 中无该键（未设置）时返回 `null`。App 据此保留「未设置则不覆盖默认主题」。 */
    @JvmStatic
    fun nightModeOrNull(context: Context): Int? {
        val s = sp(context)
        return if (s.contains(KEY_NIGHT)) s.getInt(KEY_NIGHT, NIGHT_FOLLOW_SYSTEM) else null
    }

    /** 当前夜间三态值；未设置时返回 [default]（默认 [NIGHT_FOLLOW_SYSTEM]）。 */
    @JvmStatic
    fun nightMode(context: Context, default: Int = NIGHT_FOLLOW_SYSTEM): Int =
        sp(context).getInt(KEY_NIGHT, default)

    /**
     * 套用主题。**必须在 `Activity.onCreate` 的 `super.onCreate()` 之前调用。**
     *
     * 切换后需 `recreate()` 才能生效 —— 主题属性是静态资源，无法热替换。
     */
    @JvmStatic
    fun applyTo(activity: Activity) {
        val theme = theme(activity)
        activity.setTheme(ThemeCatalog.styleRes(theme, contrast(activity)))
    }
}
