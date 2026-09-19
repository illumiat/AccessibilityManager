package com.accessibilitymanager.ui.detail

import androidx.compose.ui.platform.ComposeView
import com.accessibilitymanager.core.designsystem.theme.AppTheme

/**
 * 详情弹卡的 **Kotlin↔Java 桥接**。
 *
 * ## 为什么需要这一层
 *
 * `ComposeView.setContent` 接收的是 `@Composable` lambda —— **Java 无法提供**。
 * 宿主 `HomeFragment` 仍是 Java，故由本文件承担 `setContent`。
 *
 * ## 上下文为什么取 Activity 而不是弹窗自己的 Context
 *
 * [AppTheme] 通过 `LocalContext` 的**主题属性**解析配色（这是 Compose 与尚未迁移的
 * View 布局共用同一份配色的接缝）。弹窗会把 Context 包一层 dialog 主题 overlay，
 * 若用那层 Context，`?attr/colorSurface*` 可能解析到 overlay 的值而非本应用的生成主题。
 * 取 Activity 上下文则与迁移前的行为一致 —— 旧实现的内容视图是
 * `getLayoutInflater().inflate(...)` 出来的，其 Context 就是 Activity。
 *
 * ## 调用时机
 *
 * **每次开卡只调一次**。所有会变的输入都是 [ServiceDetailState] 里的 Compose 状态，
 * 在 composable 内读取；若改成入参快照，宿主每次刷新都要重调 `setContent`。
 */
object ServiceDetailBinder {

    /**
     * 把 [state] 渲染到 [view]，事件转发给 [callback]。
     *
     * @param view 必须是**用 Activity 上下文构造**的 `ComposeView`（见类注释）
     */
    @JvmStatic
    fun bind(
        view: ComposeView,
        state: ServiceDetailState,
        callback: ServiceDetailCallback,
    ) {
        view.setContent {
            AppTheme {
                ServiceDetailScreen(
                    header = state.header,
                    info = state.info,
                    restart = state.restart,
                    callback = callback,
                )
            }
        }
    }
}
