package com.accessibilitymanager.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * 应用字体族。
 *
 * 规范指定「静态 Roboto」，并明确 **SF Pro 授权禁止嵌入非 Apple 应用**。
 *
 * ## ⚠️ 当前缺口（必须如实说明）
 *
 * Roboto 的 ttf 资产尚未放入 `res/font/`，故 `AppFontFamily` 暂指系统默认字体族。
 * 补齐方式（二选一，均不改变本文件其余部分）：
 * 1. 把 `roboto_regular.ttf` / `roboto_medium.ttf`（SIL OFL，可商用）放入
 *    `core/designsystem/src/main/res/font/`，然后改用：
 *    ```kotlin
 *    internal val AppFontFamily = FontFamily(
 *        Font(R.font.roboto_regular, FontWeight.Normal),
 *        Font(R.font.roboto_medium, FontWeight.Medium),
 *    )
 *    ```
 * 2. 沿用系统字体族（观感不达标，但不阻塞迁移）。
 *
 * ## 中文回退（关键约束）
 *
 * **Roboto 不含汉字**。只声明 Roboto 不会让中文消失 —— Android 排版引擎在字体缺字形时
 * 会**回退到系统字体链**（CJK 由系统 Noto Sans CJK / 厂商字体提供）。
 * 故实际效果是「拉丁字母与数字用 Roboto、汉字仍由系统 CJK 字体渲染」。
 * 若将来要连中文也统一，正确做法是**内置 CJK 字体的子集**，而不是指望 Roboto 覆盖。
 */
internal val AppFontFamily: FontFamily = FontFamily.Default

/**
 * 把字体族应用到**全部 30 个字阶**。
 *
 * ## 为什么必须显式写全 30 个，不能只写 15 个
 *
 * `Typography` 的强调变体（`titleLargeEmphasized` 等）是**独立字段、各自带默认值**，
 * **不会**从对应的常规样式派生字体族。只传 15 个常规样式的后果是：
 * **常规样式是目标字体、强调变体却回落到系统字体** ——
 * 表现为「同一个标题，加粗后字体变了」，这是很难自查出来的不一致。
 *
 * 除字体外的所有排版参数（字号 / 行高 / 字距 / 字重）**一律保持 MD3 原值** ——
 * 规范要求「取 MD3 原值，不改行高比」（bodyLarge 16/24 = 1.5，恰好满足中文 ≥1.5 的需要）。
 */
internal fun Typography.withFont(family: FontFamily): Typography = Typography(
    // ── 15 个常规样式 ──
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
    // ── 15 个强调变体（不写会回落系统字体，见上方注释）──
    displayLargeEmphasized = displayLargeEmphasized.copy(fontFamily = family),
    displayMediumEmphasized = displayMediumEmphasized.copy(fontFamily = family),
    displaySmallEmphasized = displaySmallEmphasized.copy(fontFamily = family),
    headlineLargeEmphasized = headlineLargeEmphasized.copy(fontFamily = family),
    headlineMediumEmphasized = headlineMediumEmphasized.copy(fontFamily = family),
    headlineSmallEmphasized = headlineSmallEmphasized.copy(fontFamily = family),
    titleLargeEmphasized = titleLargeEmphasized.copy(fontFamily = family),
    titleMediumEmphasized = titleMediumEmphasized.copy(fontFamily = family),
    titleSmallEmphasized = titleSmallEmphasized.copy(fontFamily = family),
    bodyLargeEmphasized = bodyLargeEmphasized.copy(fontFamily = family),
    bodyMediumEmphasized = bodyMediumEmphasized.copy(fontFamily = family),
    bodySmallEmphasized = bodySmallEmphasized.copy(fontFamily = family),
    labelLargeEmphasized = labelLargeEmphasized.copy(fontFamily = family),
    labelMediumEmphasized = labelMediumEmphasized.copy(fontFamily = family),
    labelSmallEmphasized = labelSmallEmphasized.copy(fontFamily = family),
)

/**
 * 应用字阶：MD3 原值，只注入字体族。
 *
 * **不得自造字号/字距**（规范硬性禁令）；需要强调时用**强调变体**，
 * 不要手动加 `fontWeight`、也不要换更大的样式 —— 换样式 = 改层级，
 * 会破坏「一屏 ≤3 个字号层级」的约束；强调变体 = 同级加强。
 */
val appTypography: Typography = Typography().withFont(AppFontFamily)
