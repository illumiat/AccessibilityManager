package com.accessibilitymanager.core.designsystem.theme

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

/**
 * 命名主题（规范规则 ④：配色必须是「可切换的主题」，不能只靠壁纸取色）。
 *
 * - 至少 1 个**品牌主题**（品牌色作源色、`Tonal spot` 变体），**默认**；
 * - 至少 1 个**中性主题**（`Neutral` / `Monochrome`）；
 * - 「跟随壁纸」作为**并列**选项，**不设为默认、不强制**。
 *
 * 主题的**选择与切换**由 View 层负责（Android 只能通过 `setTheme` + Activity 重建切换），
 * Compose 侧只消费结果。
 */
enum class ThemeName(val key: String, val label: String) {
    /** 品牌主题，默认。 */
    BRAND("brand", "品牌"),
    /** 中性灰，供不喜欢彩色界面的用户。 */
    NEUTRAL("neutral", "中性"),
    /** 单色（纯灰阶，只有明度差异）。 */
    MONO("mono", "单色"),
    /** 跟随壁纸（动态取色）。 */
    WALLPAPER("wallpaper", "跟随壁纸"),
}

/**
 * 对比度四档（规范规则 ④，**必须暴露给用户**）。
 *
 * **不得实现成两套硬编码配色** —— 它是同一套调的参数，由算法生成
 * （见构建期生成器 `PaletteGeneratorTest`）。
 */
enum class ContrastLevel(val value: Double, val label: String) {
    /** 降低 —— 对高对比敏感、偏头痛等。 */
    REDUCED(-1.0, "降低"),
    /** 默认 —— 常规观感。 */
    DEFAULT(0.0, "默认"),
    /** 较高 —— 轻度视力障碍。 */
    MEDIUM(0.5, "较高"),
    /** 最高 —— 强无障碍需求。 */
    HIGH(1.0, "最高"),
}

/**
 * 应用主题入口。
 *
 * ## 为什么从 Android theme 属性读色，而不是在 Compose 里算色
 *
 * 应用仍有 View 布局通过 `?attr/color*` 引用主题属性，而**主题属性只能解析到静态资源**。
 * 若在 Compose 侧用算法运行时算色，结果是**主题只在 Compose 页面生效、View 页面纹丝不动**，
 * 而且两边色值来自不同来源。
 *
 * 现在改为：**配色由构建期算法生成成静态资源，Compose 与 View 都从同一套主题属性取值。**
 * 于是「切换主题」只需换 Android theme，全应用一起变。
 * 配色依然由算法从源色展开，只是展开发生在构建期，不违反「不得手改单个角色色值」。
 *
 * ## 动效（规范规则 ③）
 *
 * 流畅度用弹簧 [MotionScheme.expressive()]，**禁止用 `tween` 固定时长替代** ——
 * 那会把连续性与可中断性（判据 L1 / L2）直接做废。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()

    // 切主题会 recreate Activity -> Compose 重建 -> 重新解析；此处额外把
    // `context.theme` 纳入 remember key（预防性：解析结果依赖当前主题属性，
    // 若未来出现「同 dark 值但 theme 不同」的路径，可避免读到过期 scheme）。
    val colorScheme = remember(dark, context.theme) { readColorSchemeFromTheme(context, dark) }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        shapes = appShapes,
        typography = appTypography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/**
 * 解析主题属性得到的全部 color role 集合。
 *
 * dark / light 两分支的角色查询**完全相同**，仅 scheme 工厂不同；
 * 集中在此一次性解析，避免两分支逐字重复同一组 `c(...)` 查询。
 */
private data class ColorSchemeRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceTint: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerLowest: Color,
)

/**
 * 从当前 Android theme 解析出完整的 Compose [ColorScheme]。
 *
 * 这样 Compose 侧与 View 布局引用的是**同一份**配色，不存在「两套来源各说各话」的问题。
 * 角色查询集中在 [ColorSchemeRoles] 里一次完成（dark / light 共用，角色映射不变）。
 */
private fun readColorSchemeFromTheme(context: Context, dark: Boolean): ColorScheme {
    fun c(@AttrRes attr: Int): Color = resolveThemeColor(context, attr)

    // colorPrimary / colorError 属 AppCompat 命名空间（material.R 无这两个符号）。
    val primary = c(AppCompatR.attr.colorPrimary)
    val error = c(AppCompatR.attr.colorError)
    val onSurface = c(MaterialR.attr.colorOnSurface)
    // 规范 §12.2：scrim 固定 32% 不透明度，是常量而非可配角色，故不走主题属性。
    val scrim = Color.Black.copy(alpha = EffectTokens.SCRIM_ALPHA)

    // 全部角色查询集中此处一次完成（dark / light 共用），角色映射不变。
    val roles = ColorSchemeRoles(
        primary = primary,
        onPrimary = c(MaterialR.attr.colorOnPrimary),
        primaryContainer = c(MaterialR.attr.colorPrimaryContainer),
        onPrimaryContainer = c(MaterialR.attr.colorOnPrimaryContainer),
        inversePrimary = c(MaterialR.attr.colorPrimaryInverse),
        secondary = c(MaterialR.attr.colorSecondary),
        onSecondary = c(MaterialR.attr.colorOnSecondary),
        secondaryContainer = c(MaterialR.attr.colorSecondaryContainer),
        onSecondaryContainer = c(MaterialR.attr.colorOnSecondaryContainer),
        tertiary = c(MaterialR.attr.colorTertiary),
        onTertiary = c(MaterialR.attr.colorOnTertiary),
        tertiaryContainer = c(MaterialR.attr.colorTertiaryContainer),
        onTertiaryContainer = c(MaterialR.attr.colorOnTertiaryContainer),
        error = error,
        onError = c(MaterialR.attr.colorOnError),
        errorContainer = c(MaterialR.attr.colorErrorContainer),
        onErrorContainer = c(MaterialR.attr.colorOnErrorContainer),
        surface = c(MaterialR.attr.colorSurface),
        onSurface = onSurface,
        // 规范 §12.5：surfaceVariant 已废弃，用 surfaceContainerHighest 替代。
        surfaceVariant = c(MaterialR.attr.colorSurfaceContainerHighest),
        onSurfaceVariant = c(MaterialR.attr.colorOnSurfaceVariant),
        surfaceTint = primary,
        inverseSurface = c(MaterialR.attr.colorSurfaceInverse),
        inverseOnSurface = c(MaterialR.attr.colorOnSurfaceInverse),
        outline = c(MaterialR.attr.colorOutline),
        outlineVariant = c(MaterialR.attr.colorOutlineVariant),
        scrim = scrim,
        surfaceBright = c(MaterialR.attr.colorSurfaceBright),
        surfaceDim = c(MaterialR.attr.colorSurfaceDim),
        // 五档 surface container 全传 —— 漏档会回落到 MD3 基线的紫调中性色。
        surfaceContainer = c(MaterialR.attr.colorSurfaceContainer),
        surfaceContainerHigh = c(MaterialR.attr.colorSurfaceContainerHigh),
        surfaceContainerHighest = c(MaterialR.attr.colorSurfaceContainerHighest),
        surfaceContainerLow = c(MaterialR.attr.colorSurfaceContainerLow),
        surfaceContainerLowest = c(MaterialR.attr.colorSurfaceContainerLowest),
    )

    // scheme 工厂按 dark 选取；角色映射逐字不变，仅工厂不同（darkColorScheme / lightColorScheme）。
    val buildScheme: (ColorSchemeRoles) -> ColorScheme =
        if (dark) {
            { r ->
                darkColorScheme(
                    primary = r.primary,
                    onPrimary = r.onPrimary,
                    primaryContainer = r.primaryContainer,
                    onPrimaryContainer = r.onPrimaryContainer,
                    inversePrimary = r.inversePrimary,
                    secondary = r.secondary,
                    onSecondary = r.onSecondary,
                    secondaryContainer = r.secondaryContainer,
                    onSecondaryContainer = r.onSecondaryContainer,
                    tertiary = r.tertiary,
                    onTertiary = r.onTertiary,
                    tertiaryContainer = r.tertiaryContainer,
                    onTertiaryContainer = r.onTertiaryContainer,
                    error = r.error,
                    onError = r.onError,
                    errorContainer = r.errorContainer,
                    onErrorContainer = r.onErrorContainer,
                    surface = r.surface,
                    onSurface = r.onSurface,
                    surfaceVariant = r.surfaceVariant,
                    onSurfaceVariant = r.onSurfaceVariant,
                    surfaceTint = r.surfaceTint,
                    inverseSurface = r.inverseSurface,
                    inverseOnSurface = r.inverseOnSurface,
                    outline = r.outline,
                    outlineVariant = r.outlineVariant,
                    scrim = r.scrim,
                    surfaceBright = r.surfaceBright,
                    surfaceDim = r.surfaceDim,
                    surfaceContainer = r.surfaceContainer,
                    surfaceContainerHigh = r.surfaceContainerHigh,
                    surfaceContainerHighest = r.surfaceContainerHighest,
                    surfaceContainerLow = r.surfaceContainerLow,
                    surfaceContainerLowest = r.surfaceContainerLowest,
                )
            }
        } else {
            { r ->
                lightColorScheme(
                    primary = r.primary,
                    onPrimary = r.onPrimary,
                    primaryContainer = r.primaryContainer,
                    onPrimaryContainer = r.onPrimaryContainer,
                    inversePrimary = r.inversePrimary,
                    secondary = r.secondary,
                    onSecondary = r.onSecondary,
                    secondaryContainer = r.secondaryContainer,
                    onSecondaryContainer = r.onSecondaryContainer,
                    tertiary = r.tertiary,
                    onTertiary = r.onTertiary,
                    tertiaryContainer = r.tertiaryContainer,
                    onTertiaryContainer = r.onTertiaryContainer,
                    error = r.error,
                    onError = r.onError,
                    errorContainer = r.errorContainer,
                    onErrorContainer = r.onErrorContainer,
                    surface = r.surface,
                    onSurface = r.onSurface,
                    surfaceVariant = r.surfaceVariant,
                    onSurfaceVariant = r.onSurfaceVariant,
                    surfaceTint = r.surfaceTint,
                    inverseSurface = r.inverseSurface,
                    inverseOnSurface = r.inverseOnSurface,
                    outline = r.outline,
                    outlineVariant = r.outlineVariant,
                    scrim = r.scrim,
                    surfaceBright = r.surfaceBright,
                    surfaceDim = r.surfaceDim,
                    surfaceContainer = r.surfaceContainer,
                    surfaceContainerHigh = r.surfaceContainerHigh,
                    surfaceContainerHighest = r.surfaceContainerHighest,
                    surfaceContainerLow = r.surfaceContainerLow,
                    surfaceContainerLowest = r.surfaceContainerLowest,
                )
            }
        }
    return buildScheme(roles)
}

/**
 * 解析主题属性到 [Color]。
 *
 * 属性缺失时返回 `Color.Unspecified`（**不是**某个兜底色）——`Unspecified` 会让上层组件
 * 使用它自己的默认角色色，比硬塞一个可能与当前主题冲突的颜色更安全。
 *
 * ⚠️ 本 KDoc 曾写「回落到主题的 `colorPrimary`」，与实现不符（实现返回 `Color.Unspecified`），已更正。
 */
private fun resolveThemeColor(context: Context, @AttrRes attr: Int): Color {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(attr, value, true)) {
        return Color.Unspecified
    }
    return Color(value.data)
}
