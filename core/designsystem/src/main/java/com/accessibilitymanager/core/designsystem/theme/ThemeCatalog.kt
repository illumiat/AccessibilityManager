package com.accessibilitymanager.core.designsystem.theme

import androidx.annotation.StyleRes
import com.accessibilitymanager.core.designsystem.R

/**
 * 主题目录：`主题 × 对比度 → 主题样式资源 id`。
 *
 * ## 为什么手写映射而不是反射查表
 *
 * `getResources().getIdentifier(...)` 慢、被 lint 警告，且**拼错名字只在运行时才发现**。
 * 这里显式引用 `R.style.*`，**编译期就能发现拼错**，且与生成器（`PaletteGeneratorTest`）
 * 产出的样式名一一对应 —— 生成器改了名字，这里立刻编译不过。
 *
 * 主题的**切换**由 View 层负责（Android 只能通过 `setTheme` + Activity 重建切换），
 * 本目录只提供 id 查询。
 */
object ThemeCatalog {

    /**
     * 取主题样式 id。
     *
     * [ThemeName.WALLPAPER] 返回基样式：壁纸取色是**运行时**行为
     * （API 31+ 由 `DynamicColors` 在 Activity 创建时叠加），静态资源无法预先算出。
     */
    @JvmStatic
    @StyleRes
    fun styleRes(theme: ThemeName, contrast: ContrastLevel): Int = when (theme) {
        ThemeName.BRAND -> when (contrast) {
            ContrastLevel.REDUCED -> R.style.AppTheme_brand_reduced
            ContrastLevel.DEFAULT -> R.style.AppTheme
            ContrastLevel.MEDIUM -> R.style.AppTheme_brand_medium
            ContrastLevel.HIGH -> R.style.AppTheme_brand_high
        }

        ThemeName.NEUTRAL -> when (contrast) {
            ContrastLevel.REDUCED -> R.style.AppTheme_neutral_reduced
            ContrastLevel.DEFAULT -> R.style.AppTheme_neutral_default
            ContrastLevel.MEDIUM -> R.style.AppTheme_neutral_medium
            ContrastLevel.HIGH -> R.style.AppTheme_neutral_high
        }

        ThemeName.MONO -> when (contrast) {
            ContrastLevel.REDUCED -> R.style.AppTheme_mono_reduced
            ContrastLevel.DEFAULT -> R.style.AppTheme_mono_default
            ContrastLevel.MEDIUM -> R.style.AppTheme_mono_medium
            ContrastLevel.HIGH -> R.style.AppTheme_mono_high
        }

        // 壁纸取色：静态兜底用品牌默认样式，运行时由 DynamicColors 叠加。
        ThemeName.WALLPAPER -> R.style.AppTheme
    }

    /** 是否需要运行时动态取色（规范规则 ④：「跟随壁纸」是并列选项，**不设为默认、不强制**）。 */
    @JvmStatic
    fun needsDynamicColor(theme: ThemeName): Boolean = theme == ThemeName.WALLPAPER
}
