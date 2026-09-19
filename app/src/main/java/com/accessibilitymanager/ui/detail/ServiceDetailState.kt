package com.accessibilitymanager.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import com.accessibilitymanager.IconCache
import com.accessibilitymanager.ui.model.placeholderInitial
import com.accessibilitymanager.ui.model.serviceTitle

/**
 * 详情弹卡的**状态持有者**：宿主写入 → Compose 读取。
 *
 * ## 与列表状态持有者的分工一致
 *
 * 宿主（`HomeFragment`）负责读业务数据、拼文案、落笔写；本类只**承载快照**，
 * 不访问 `Settings.Secure`、不碰 `tmpSettingValue`。
 *
 * ## 为什么是三个独立状态而不是一个大对象
 *
 * 三块的刷新时机不同：
 *
 * | 状态 | 何时提交 |
 * |---|---|
 * | [header] | 开卡时一次；图标/标签异步就绪后**再补一次** |
 * | [info] | 开卡时一次（位解码结果与 `settingValue` 同批） |
 * | [restart] | 开卡时 + **详情打开期间每次 `refreshStates()` 重入** |
 *
 * 合成一个对象会让"只刷新重启区"变成"重建整块并让另两块也重组"。
 *
 * ## 线程
 *
 * 全部写入来自宿主的主线程路径（开卡、`runOnUiThread` 刷新、`IconCache` 的主线程回调），
 * 故可直接写 Compose 状态。**不得从后台线程调用**。
 */
class ServiceDetailState {

    /** 头部（应用身份）。 */
    var header by mutableStateOf(DetailHeader("", "", null, ""))

    /** 只读信息块。 */
    var info by mutableStateOf(DetailInfo(emptyList(), emptyList(), emptyList()))

    /** 定期重启区。 */
    var restart by mutableStateOf(DetailRestart(false, false, "", "", false, 0L))

    /**
     * 用图标缓存项刷新区头部。
     *
     * **同一方法承担两条路径**（开卡时的同步取值、图标异步就绪后的补刷），
     * 因为两者的映射规则完全相同：标题走 [serviceTitle]（与列表卡片**同一规则**），
     * 图标取缓存命中项（未命中则为 `null` → UI 出占位）。
     * 分成两处写迟早分叉 —— 缺陷模式「同功能双实现」。
     */
    fun submitHeaderFromCache(serviceId: String, entry: IconCache.Entry?) {
        header = DetailHeader(
            title = serviceTitle(entry?.appLabel, entry?.serviceLabel, serviceId),
            packageClass = serviceId,
            icon = entry?.icon?.asImageBitmap(),
            iconInitial = placeholderInitial(serviceId),
        )
    }
}
