package com.accessibilitymanager.ui.home

import androidx.compose.ui.platform.ComposeView
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.accessibilitymanager.core.designsystem.theme.AppTheme
import com.accessibilitymanager.ui.detail.DetailHost
import com.accessibilitymanager.ui.detail.DetailOverlay
import java.util.function.Consumer

/**
 * 主页列表的 **Kotlin↔Java 桥接**。
 *
 * ## 为什么需要这一层
 *
 * `ComposeView.setContent` 接收的是 `@Composable` lambda —— **Java 无法提供**。
 * 宿主 `HomeFragment` 仍是 Java，故由本文件承担 `setContent`，并把 Java 侧的
 * 回调桥接进 Compose。
 *
 * ## 为什么带 [callback] 参数
 *
 * 该接口（[HomeServiceCallback]）由 `HomeFragment` 实现，且方法集
 * （`onToggle` / `onLockClick` / `onItemClick` / `onItemLongClick` /
 * `restartSummary`）与列表所需动作**完全对应** —— 接入即是「换渲染层」，
 * 不触碰任何写入路径。（该接口原为 `ServiceAdapter.Callback`，随适配器退役迁出并改名。）
 *
 * ## 职责边界
 *
 * 只做「渲染 + 事件转发」；**不写** `Settings.Secure`、不碰 `tmpSettingValue`。
 * 开关/锁定/置顶的写入仍由宿主既有实现承担（F1/R3 镜像纪律的唯一落点）。
 */
object HomeListBinder {

    /**
     * 把 [state] 渲染到 [view]，事件转发给 [callback]。
     *
     * 注：定期重启摘要**不在此参数化** —— 它已由 [HomeListState.submit] 折进
     * 模型的 `descriptionText`，无需二次传入。
     *
     * @param onQueryChange 搜索词变化（宿主据此重算列表）
     * @param onAuthorize 未授权横幅的"去授权"
     * @param onOpenSystemSettings 空态主动作"去系统设置开启无障碍服务"
     * @param onRetry 错误态"重试"
     */
      @JvmStatic
      @Suppress("LongParameterList")
      @OptIn(ExperimentalSharedTransitionApi::class)
      fun bind(
          view: ComposeView,
          state: HomeListState,
          detailHost: DetailHost,
          callback: HomeServiceCallback,
          onQueryChange: Consumer<String>,
          onAuthorize: Runnable,
          onOpenSystemSettings: Runnable,
          onRetry: Runnable,
      ) {
        // ⚠️ 本方法**只调一次**。授权态/搜索词/加载/错误都从 state 读取（Compose 状态），
        // 若改为入参快照，宿主每次变化都要重调 setContent —— 那会重置列表滚动位置。
          view.setContent {
              AppTheme {
                  SharedTransitionLayout {
                      Box(Modifier.fillMaxSize()) {
                          HomeScreen(
                              models = state.models,
                              sharedScope = this@SharedTransitionLayout,
                              permissionGranted = state.permissionGranted,
                              searchQuery = state.query,
                              loading = state.loading,
                              error = state.error,
                              onScrollingChanged = state::onScrollingChanged,
                              onSearchChange = { q ->
                                  state.query = q // 驱动输入框回显
                                  onQueryChange.accept(q) // 宿主据此重算列表
                              },
                              onAuthorize = { onAuthorize.run() },
                              onOpenSystemSettings = { onOpenSystemSettings.run() },
                              onRetry = { onRetry.run() },
                              onToggle = { model, checked ->
                                  state.infoFor(model.serviceId)?.let { callback.onToggle(it, checked) }
                              },
                              onLockClick = { model ->
                                  state.infoFor(model.serviceId)?.let { callback.onLockClick(it) }
                              },
                              onOpen = { model ->
                                  state.infoFor(model.serviceId)?.let { callback.onItemClick(it) }
                              },
                              // 长按置顶：功能必须有可发现的替代入口（判据 L4）
                              onLongPress = { model ->
                                  state.infoFor(model.serviceId)?.let { callback.onItemLongClick(it) }
                              },
                          )
                          // 详情卡片：与列表同处一个 SharedTransitionLayout，故图标可做共享元素。
                          // 承载方式是**同组合内 overlay**，不是 Dialog —— 共享元素跨不过 window。
                          DetailOverlay(
                              host = detailHost,
                              sharedScope = this@SharedTransitionLayout,
                          )
                      }
                  }
              }
          }
    }
}
