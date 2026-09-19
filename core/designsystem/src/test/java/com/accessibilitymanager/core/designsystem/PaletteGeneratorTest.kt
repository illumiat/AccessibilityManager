package com.accessibilitymanager.core.designsystem

import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeNeutral
import com.google.android.material.color.utilities.SchemeTonalSpot
import org.junit.Test
import java.io.File

/**
 * 配色生成器（**构建期工具，不参与运行时**）。
 *
 * ## 生成的是完整矩阵：主题 × 对比度 × 明暗
 *
 * 规范规则 ④ 明确配色的**四维构成是「变体 × 源色 × 明暗 × 对比度」**，
 * 且「4 档对比度**必须暴露给用户**」。故这里生成全矩阵。
 *
 * ## 为什么必须在构建期生成成静态资源（而不是运行时算）
 *
 * 应用里仍有 View 布局通过 `?attr/color*` 引用主题属性，
 * 而**主题属性只能解析到静态资源** —— View 侧消费不了运行时算出的颜色。
 * 若把配色做成运行时计算，结果是「只有 Compose 页面跟着变、其他页面纹丝不动」。
 *
 * 同时这也符合规范本意：**静态源色与动态取色都在 MD3 支持范围内，
 * 本项目选择以静态主题为主**。配色依然全部由算法从源色展开，
 * 只是展开发生在构建期，不违反「不得手改单个角色色值」。
 *
 * ## 运行方式
 *
 * ```
 * gradlew :core:designsystem:testDebugUnitTest --tests "*PaletteGenerator*"
 * ```
 *
 * 产出**直接写入** `core/designsystem/src/main/res/`（`values/` 与 `values-night/`）。
 * **改了主题清单就重跑，绝不手改生成物里的色值。**
 */
class PaletteGeneratorTest {

    /**
     * 一个命名主题（规范规则 ④：至少 1 个品牌主题 + 至少 1 个中性主题）。
     *
     * @param variant 变体决定「源色如何展开成整套调色板」，是主题之间观感差异的主要来源。
     * @param primary 主要主题：其「默认对比度」沿用既有的 `AppTheme` / `am_` 前缀，
     *                避免动到 manifest 与既有布局的属性引用。
     */
    private data class ThemeSpec(
        val id: String,
        val displayName: String,
        val seed: Long,
        val variant: Variant,
        val primary: Boolean = false,
    )

    private enum class Variant(val label: String) {
        TONAL_SPOT("Tonal spot"),
        NEUTRAL("Neutral"),
        MONOCHROME("Monochrome"),
    }

    /**
     * 对比度档位（规范：「-1.0 降低 · 0.0 默认 · 0.5 较高 · 1.0 最高」）。
     *
     * 它是**同一套调的参数**，不是四套硬编码配色，由算法生成。
     *
     * 注：取值不是从 material 库的 `Contrast` 类取 —— 该类只含对比率常量，**不含这四档**。
     */
    private enum class ContrastLevel(val label: String, val value: Double, val tag: String) {
        REDUCED("降低", -1.0, "reduced"),
        DEFAULT("默认", 0.0, "default"),
        MEDIUM("较高", 0.5, "medium"),
        HIGH("最高", 1.0, "high"),
    }

    /** 主题清单。**改配色只改这里。** */
    private val themes = listOf(
        // 品牌主题：既有品牌色作源色，Tonal spot 变体，**默认**。
        ThemeSpec("brand", "品牌", 0xFF4759B8, Variant.TONAL_SPOT, primary = true),
        // 中性主题：低饱和选项，供不喜欢彩色界面的用户。
        ThemeSpec("neutral", "中性", 0xFF0066CC, Variant.NEUTRAL),
        // 单色主题：纯灰阶，只有明度差异。
        ThemeSpec("mono", "单色", 0xFF0066CC, Variant.MONOCHROME),
    )

    /** 资源前缀：默认主题沿用 `am_`，其余 `am_<主题>_<对比度>`。 */
    private fun prefixOf(theme: ThemeSpec, contrast: ContrastLevel): String =
        if (theme.primary && contrast == ContrastLevel.DEFAULT) "am"
        else "am_${theme.id}_${contrast.tag}"

    private fun styleNameOf(theme: ThemeSpec, contrast: ContrastLevel): String =
        if (theme.primary && contrast == ContrastLevel.DEFAULT) "AppTheme"
        else "AppTheme_${theme.id}_${contrast.tag}"

    private fun schemeFor(
        variant: Variant,
        hct: Hct,
        dark: Boolean,
        contrast: Double,
    ): DynamicScheme = when (variant) {
        Variant.TONAL_SPOT -> SchemeTonalSpot(hct, dark, contrast)
        Variant.NEUTRAL -> SchemeNeutral(hct, dark, contrast)
        Variant.MONOCHROME -> SchemeMonochrome(hct, dark, contrast)
    }

    @Test
    fun generatePalettes() {
        val resRoot = File("src/main/res")
        var fileCount = 0

        themes.forEach { theme ->
            ContrastLevel.entries.forEach { contrast ->
                listOf(false, true).forEach { dark ->
                    val dirName = if (dark) "values-night" else "values"
                    val prefix = prefixOf(theme, contrast)
                    val file = File(resRoot, "$dirName/colors_$prefix.xml")
                    file.parentFile.mkdirs()
                    file.writeText(renderColors(theme, contrast, dark, prefix))
                    fileCount++
                }
            }
        }

        val themeXml = File(resRoot, "values/themes_generated.xml")
        themeXml.parentFile?.mkdirs()
        themeXml.writeText(renderThemes())
        fileCount++

        println("[PaletteGenerator] 生成 $fileCount 个文件")
        println(
            "[PaletteGenerator] 主题 ${themes.size} × 对比度 ${ContrastLevel.entries.size} × 明暗 2 = " +
                "${themes.size * ContrastLevel.entries.size * 2} 套调色板",
        )
        themes.forEach { t ->
            println("   ${t.displayName}(${t.id})  变体=${t.variant.label}  源色=#${"%06X".format(t.seed and 0xFFFFFF)}")
        }
    }

    private fun renderColors(
        theme: ThemeSpec,
        contrast: ContrastLevel,
        dark: Boolean,
        prefix: String,
    ): String {
        val scheme = schemeFor(theme.variant, Hct.fromInt(theme.seed.toInt()), dark, contrast.value)

        val roles = linkedMapOf(
            "primary" to scheme.primary,
            "on_primary" to scheme.onPrimary,
            "primary_container" to scheme.primaryContainer,
            "on_primary_container" to scheme.onPrimaryContainer,
            "secondary" to scheme.secondary,
            "on_secondary" to scheme.onSecondary,
            "secondary_container" to scheme.secondaryContainer,
            "on_secondary_container" to scheme.onSecondaryContainer,
            "tertiary" to scheme.tertiary,
            "on_tertiary" to scheme.onTertiary,
            "tertiary_container" to scheme.tertiaryContainer,
            "on_tertiary_container" to scheme.onTertiaryContainer,
            "error" to scheme.error,
            "on_error" to scheme.onError,
            "error_container" to scheme.errorContainer,
            "on_error_container" to scheme.onErrorContainer,
            "surface" to scheme.surface,
            "on_surface" to scheme.onSurface,
            "on_surface_variant" to scheme.onSurfaceVariant,
            // 五档 surface container 必须全给 —— 漏档会回落到 MD3 基线的紫调中性色。
            "surface_container_lowest" to scheme.surfaceContainerLowest,
            "surface_container_low" to scheme.surfaceContainerLow,
            "surface_container" to scheme.surfaceContainer,
            "surface_container_high" to scheme.surfaceContainerHigh,
            "surface_container_highest" to scheme.surfaceContainerHighest,
            "outline" to scheme.outline,
            "outline_variant" to scheme.outlineVariant,
            "inverse_surface" to scheme.inverseSurface,
            "inverse_on_surface" to scheme.inverseOnSurface,
            "inverse_primary" to scheme.inversePrimary,
            // surfaceBright / surfaceDim 供高/低亮度表面使用（AppTheme 解析要用）。
            "surface_bright" to scheme.surfaceBright,
            "surface_dim" to scheme.surfaceDim,
        )

        return buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("<!--")
            appendLine("  由 PaletteGeneratorTest 生成，请勿手改。")
            appendLine("  主题：${theme.displayName}(${theme.id})　变体：${theme.variant.label}　源色：#${"%06X".format(theme.seed and 0xFFFFFF)}")
            appendLine("  对比度：${contrast.label}(${contrast.value})　明暗：${if (dark) "dark" else "light"}")
            appendLine("  改配色请改生成器的主题清单并重跑，不要直接改这里的色值。")
            appendLine("-->")
            appendLine("<resources>")
            roles.forEach { (role, argb) ->
                appendLine("""    <color name="${prefix}_$role">${hex(argb)}</color>""")
            }
            appendLine("</resources>")
        }
    }

    /**
     * 生成主题样式。
     *
     * 每个主题只覆盖**颜色角色**，其余（父主题、系统栏）由 [styleNameOf] 的基样式承担 ——
     * 避免把 30+ 条非颜色配置重复 24 遍、将来改一处要改 24 处。
     */
    private fun renderThemes(): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<!--")
        appendLine("  由 PaletteGeneratorTest 生成，请勿手改。")
        appendLine("  基样式 AppTheme 承载父主题与非颜色项；其余样式 parent=\"AppTheme\" 只覆盖颜色角色。")
        appendLine("-->")
        appendLine("<resources>")

        // 基样式：默认品牌主题 + 默认对比度
        appendLine()
        appendLine("""    <!-- 品牌 · 默认对比度 · Tonal spot（基样式，manifest 引用它） -->""")
        appendLine("""    <style name="AppTheme" parent="Theme.Material3.DayNight.NoActionBar">""")
        appendLine("""        <item name="android:statusBarColor">@android:color/transparent</item>""")
        colorRoles.forEach { attr ->
            appendLine("""        <item name="$attr">@color/am_${attrToRole(attr)}</item>""")
        }
        appendLine("    </style>")

        themes.forEach { theme ->
            ContrastLevel.entries.forEach { contrast ->
                if (theme.primary && contrast == ContrastLevel.DEFAULT) return@forEach
                val prefix = prefixOf(theme, contrast)
                appendLine()
                appendLine("""    <!-- ${theme.displayName} · ${contrast.label}对比度 · ${theme.variant.label} -->""")
                appendLine("""    <style name="${styleNameOf(theme, contrast)}" parent="AppTheme">""")
                colorRoles.forEach { attr ->
                    appendLine("""        <item name="$attr">@color/${prefix}_${attrToRole(attr)}</item>""")
                }
                appendLine("    </style>")
            }
        }

        appendLine("</resources>")
    }

    /** 与生成的基样式逐条对应；顺序一致便于人工比对。 */
    private val colorRoles = listOf(
        "colorPrimary", "colorOnPrimary", "colorPrimaryContainer", "colorOnPrimaryContainer",
        "colorSecondary", "colorOnSecondary", "colorSecondaryContainer", "colorOnSecondaryContainer",
        "colorTertiary", "colorOnTertiary", "colorTertiaryContainer", "colorOnTertiaryContainer",
        "colorError", "colorOnError", "colorErrorContainer", "colorOnErrorContainer",
        "colorSurface", "colorOnSurface", "colorOnSurfaceVariant",
        "colorSurfaceContainerLowest", "colorSurfaceContainerLow", "colorSurfaceContainer",
        "colorSurfaceContainerHigh", "colorSurfaceContainerHighest",
        "colorOutline", "colorOutlineVariant",
        "colorSurfaceInverse", "colorOnSurfaceInverse", "colorPrimaryInverse",
        "colorSurfaceBright", "colorSurfaceDim",
    )

    private fun attrToRole(attr: String): String = when (attr) {
        "colorPrimary" -> "primary"
        "colorOnPrimary" -> "on_primary"
        "colorPrimaryContainer" -> "primary_container"
        "colorOnPrimaryContainer" -> "on_primary_container"
        "colorSecondary" -> "secondary"
        "colorOnSecondary" -> "on_secondary"
        "colorSecondaryContainer" -> "secondary_container"
        "colorOnSecondaryContainer" -> "on_secondary_container"
        "colorTertiary" -> "tertiary"
        "colorOnTertiary" -> "on_tertiary"
        "colorTertiaryContainer" -> "tertiary_container"
        "colorOnTertiaryContainer" -> "on_tertiary_container"
        "colorError" -> "error"
        "colorOnError" -> "on_error"
        "colorErrorContainer" -> "error_container"
        "colorOnErrorContainer" -> "on_error_container"
        "colorSurface" -> "surface"
        "colorOnSurface" -> "on_surface"
        "colorOnSurfaceVariant" -> "on_surface_variant"
        "colorSurfaceContainerLowest" -> "surface_container_lowest"
        "colorSurfaceContainerLow" -> "surface_container_low"
        "colorSurfaceContainer" -> "surface_container"
        "colorSurfaceContainerHigh" -> "surface_container_high"
        "colorSurfaceContainerHighest" -> "surface_container_highest"
        "colorOutline" -> "outline"
        "colorOutlineVariant" -> "outline_variant"
        "colorSurfaceInverse" -> "inverse_surface"
        "colorOnSurfaceInverse" -> "inverse_on_surface"
        "colorPrimaryInverse" -> "inverse_primary"
        "colorSurfaceBright" -> "surface_bright"
        "colorSurfaceDim" -> "surface_dim"
        else -> error("未映射的颜色属性: $attr")
    }

    private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
}
