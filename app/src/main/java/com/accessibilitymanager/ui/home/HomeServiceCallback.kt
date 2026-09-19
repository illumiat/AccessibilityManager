package com.accessibilitymanager.ui.home

import android.accessibilityservice.AccessibilityServiceInfo

/**
 * 主页列表的宿主回调（原 `ServiceAdapter.Callback`，随 Adapter 退役迁来）。
 *
 * ## 为什么独立成一个接口
 *
 * 列表渲染已迁到 Compose（`HomeScreen` + `HomeListState`），但**写入路径仍在 Java 宿主**
 * （`HomeFragment`）—— 开关写 `Settings.Secure`、锁定写 daemon 串、置顶写 top 串，
 * 三处都要守 F1/R3 镜像纪律，不能在展示层重写一遍。
 *
 * 该接口即这条边界的契约：**Compose 只管发意图，Java 宿主负责落笔**。
 * 抽出来之后 `ServiceAdapter`（View 适配器）与其布局即可删除。
 */
interface HomeServiceCallback {

    /** 开关切换（List item 的 Switch）。 */
    fun onToggle(info: AccessibilityServiceInfo, checked: Boolean)

    /** 保活锁定按钮。 */
    fun onLockClick(info: AccessibilityServiceInfo)

    /** 点卡片 → 详情。 */
    fun onItemClick(info: AccessibilityServiceInfo)

    /** 长按卡片 → 置顶切换。 */
    fun onItemLongClick(info: AccessibilityServiceInfo)

    fun isTop(serviceId: String): Boolean

    fun isLocked(serviceId: String): Boolean

    /** 定期重启摘要（如"每 24 小时"）；`null` = 未启用。 */
    fun restartSummary(serviceId: String): String?
}
