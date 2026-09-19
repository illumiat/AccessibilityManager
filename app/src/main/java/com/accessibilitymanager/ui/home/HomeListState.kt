package com.accessibilitymanager.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.accessibilitymanager.IconCache
import com.accessibilitymanager.R
import com.accessibilitymanager.RestartPrefs
import com.accessibilitymanager.ui.model.ServiceDescKind
import com.accessibilitymanager.ui.model.ServiceUiModel
import com.accessibilitymanager.ui.model.placeholderInitial
import com.accessibilitymanager.ui.model.serviceTitle
import java.util.Locale

/**
 * 主页列表的**状态持有者**：Java 业务数据 → [ServiceUiModel]（唯一进入组合树的类型）。
 *
 * ## 职责边界
 *
 * 只做「读数据 + 映射 + 图标异步」；**不写** `Settings.Secure`、不碰 `tmpSettingValue`。
 * 开关/锁定/置顶的写入路径仍由宿主（`HomeFragment` 或后续的 Compose 壳）承担，
 * 因为那是 F1/R3 镜像纪律的落点，不能在展示层重写一遍。
 *
 * ## 与既有实现逐条对齐（迁自 `ServiceAdapter` + `HomeFragment`）
 *
 * | 项 | 规则 |
 * |---|---|
 * | 标题 | 应用名/服务名同时存在且不同 → `app/svc`；否则取可得的那个；都没有 → 服务类短名 |
 * | 描述优先级 | `failed`（终态）> `restarting`（过渡）> 定期重启摘要 > 服务描述 > 兜底文案 |
 * | 启用判定 | `RestartPrefs.isEnabledIn`（**按 ":" 切段精确匹配**，不用 contains 子串） |
 * | 已自动恢复 | `restart` SP 的 `AUTO_RESTORED_PREFIX + id`，且在 `AUTO_RESTORED_WINDOW_MS` 内 |
 * | 开关可交互 | 已授权 **且** 不在 `pendingEnable`（重启补偿中禁改，防中途变更） |
 *
 * ## 图标异步（六条防掉帧条款在 Compose 侧的落地）
 *
 * - **绑定期零回源**：[submit] 只读 `IconCache.peek`，miss 就出占位符并发起异步请求；
 * - **增量刷新**：图标就绪后只替换**该行**（`SnapshotStateList.set`），不重建整表；
 * - **按 id 校验**：回调时用 `indexOfFirst { serviceId == id }` 反查，**不用位置** ——
 *   期间发生的置顶/过滤/插入都会让位置失效（盲审 P0 条款）；
 * - **回调在主线程**：`IconCache` 内部经 `Handler(mainLooper).post` 回调，故可直接改 Compose 状态；
 * - **滑动中暂停**：[onScrollingChanged] 转发给 `IconCache.setPaused`，避免与滑动抢 Binder/CPU。
 */
class HomeListState(
    private val appContext: Context,
    private val iconCache: IconCache,
) {

    /** 列表数据源：Compose 状态列表，元素为**不可变**模型 → 未变化行可被跳过重组。 */
    val models: SnapshotStateList<ServiceUiModel> = mutableStateListOf()

    // ── 以下四项是 **Compose 状态**（不是构造参数）──
    // 原因：`ComposeView.setContent` 只能调一次，重调会重置列表滚动位置。
    // 故凡是会变的输入都必须是状态、在 composable 内读取，而不是 bind 时的快照值。

    /** 安全设置写入授权态（横幅显示依据）。 */
    var permissionGranted by mutableStateOf(true)
        private set

    /** 搜索词（输入框回显的唯一数据源）。 */
    var query by mutableStateOf("")

    /** 首屏加载中（显示骨架屏）。 */
    var loading by mutableStateOf(false)

    /** 读取失败（显示错误态）。 */
    var error by mutableStateOf(false)

    // ⚠️ 这三个属性**不要**再写显式的 `fun setQuery/setLoading/setError` ——
    // Kotlin 属性本身已生成 setter，重复声明会报「Platform declaration clash」（JVM 签名相同）。
    // Java 侧直接调 `listState.setQuery(...)` 即可。
    //
    // permissionGranted 则保留 `private set` + 具名 setter：名字不同（setPermission），无冲突，
    // 且语义上明确「这是宿主告知的授权态」而非任意赋值。

    fun setPermission(granted: Boolean) {
        permissionGranted = granted
    }

    private var installed: List<AccessibilityServiceInfo> = emptyList()

    // ── 上次 [submit] 的入参快照 ──
    // 用途只有一个：**图标/标签异步就绪后重建那一行**。
    //
    // ⚠️ 不能只 `copy(icon = ...)`：标题（应用名/服务名）与描述（服务描述）**同样来自
    // IconCache**，在缓存命中前只能出兜底值。迁移前靠 `notifyItemChanged(pos, PAYLOAD_INFO)`
    // → `ServiceAdapter.bindInfo` 一次性重设「图标 + 标题 + 描述」三者。
    // 早期版本只补了图标，导致**标题永远停在服务类短名（看起来像包名）、描述永远是"该服务没有描述"**——
    // 真机首屏即暴露（构建期完全看不见）。
    private var lastSettingValue: String = ""
    private var lastPending: Set<String> = emptySet()
    private var lastFailed: Set<String> = emptySet()
    private var lastTopSet: Set<String> = emptySet()
    private var lastDaemonSet: Set<String> = emptySet()
    private var lastRestartSummary: (String) -> String? = { null }

    /**
     * 提交一次全量数据（数据源/设置串/状态集/授权态/搜索词任一变化时调用）。
     *
     * @param topOrder 置顶串**顺序**（不是集合）：既有 `sortDisplay()` 按 `top` 串顺序排置顶项，
     *                 用 Set 会丢掉顺序 —— 这里必须收 List 才能保持同序。
     */
    fun submit(
        installed: List<AccessibilityServiceInfo>,
        settingValue: String,
        pending: Set<String>,
        failed: Set<String>,
        topOrder: List<String>,
        daemonSet: Set<String>,
        restartSummary: (String) -> String?,
    ) {
        this.installed = installed
        val topSet = topOrder.toSet()
        lastSettingValue = settingValue
        lastPending = pending
        lastFailed = failed
        lastTopSet = topSet
        lastDaemonSet = daemonSet
        lastRestartSummary = restartSummary
        val q = query.trim().lowercase(Locale.ROOT)
        val filtered = if (q.isEmpty()) installed else installed.filter { matches(it, q) }
        val ordered = sortByTop(filtered, topOrder)

        val next = ordered.map { info ->
            buildModel(
                info = info,
                settingValue = settingValue,
                pending = pending,
                failed = failed,
                topSet = topSet,
                daemonSet = daemonSet,
                permissionGranted = permissionGranted,
                restartSummary = restartSummary,
            )
        }
        models.clear()
        models.addAll(next)

        // 启动流水线：图标/标签/描述异步补齐（可见项在上一帧已以占位符呈现）
        for (info in ordered) {
            val e = iconCache.peek(info.id)
            if (e == null || !e.loaded) requestIcon(info)
        }
    }

    /** 滑动状态变化 → 暂停/恢复后台图标预加载。 */
    fun onScrollingChanged(scrolling: Boolean) {
        iconCache.setPaused(scrolling)
    }

    /**
     * 单行「图标/标签/描述」异步就绪后的补刷。
     *
     * 回调按 **id** 反查位置（不是位置快照），并在替换前确认该行仍在列表中
     * —— 期间发生的置顶/过滤/插入都会让位置失效。
     *
     * **重建整行而不是只换图标**：理由见上方「上次 submit 的入参快照」注释。
     */
    private fun requestIcon(info: AccessibilityServiceInfo) {
        val id = info.id
        iconCache.load(info) {
            val e = iconCache.peek(id) ?: return@load
            if (!e.loaded) return@load
            val index = models.indexOfFirst { it.serviceId == id }
            if (index < 0) return@load
            models[index] = buildModel(
                info = info,
                settingValue = lastSettingValue,
                pending = lastPending,
                failed = lastFailed,
                topSet = lastTopSet,
                daemonSet = lastDaemonSet,
                permissionGranted = permissionGranted,
                restartSummary = lastRestartSummary,
            )
        }
    }

    private fun buildModel(
        info: AccessibilityServiceInfo,
        settingValue: String,
        pending: Set<String>,
        failed: Set<String>,
        topSet: Set<String>,
        daemonSet: Set<String>,
        permissionGranted: Boolean,
        restartSummary: (String) -> String?,
    ): ServiceUiModel {
        val id = info.id
        val e = iconCache.peek(id)
        val loaded = e != null && e.loaded

        // 标题：轻量数据（服务类短名）先同步渲染，图标/标签就绪后由 requestIcon 重建整行补齐。
        // 规则收口在 [serviceTitle]（详情头部共用同一规则，避免同一服务两页不同名）。
        val title = if (loaded) {
            serviceTitle(e.appLabel, e.serviceLabel, id)
        } else {
            IconCache.shortClassName(id).orEmpty()
        }

        // 描述优先级与图标是否就绪**无关**（失败/重启中/重启摘要都不依赖 IconCache），故只算一次
        val (descriptionText, descriptionKind) = describe(
            id = id,
            serviceDesc = if (loaded) e.description else null,
            pending = pending,
            failed = failed,
            restartSummary = restartSummary,
        )

        val autoRestoredAt = appContext
            .getSharedPreferences("restart", 0)
            .getLong(RestartPrefs.AUTO_RESTORED_PREFIX + id, 0)
        val autoRestored = autoRestoredAt > 0 &&
            System.currentTimeMillis() - autoRestoredAt <= RestartPrefs.AUTO_RESTORED_WINDOW_MS

        val restarting = pending.contains(id)

        return ServiceUiModel(
            serviceId = id,
            title = title,
            descriptionText = descriptionText,
            descriptionKind = descriptionKind,
            icon = if (loaded) e.icon?.asImageBitmap() else null,
            iconInitial = placeholderInitial(id),
            enabled = RestartPrefs.isEnabledIn(settingValue, id),
            toggleEnabled = permissionGranted && !restarting,
            permissionGranted = permissionGranted,
            pinned = topSet.contains(id),
            locked = daemonSet.contains(id),
            restarting = restarting,
            failed = failed.contains(id),
            autoRestored = autoRestored,
        )
    }

    /**
     * 描述行语义（与迁移前的 `ServiceAdapter.updateDesc` 逐条一致）：
     * **终态失败 > 过渡态重启中 > 定期重启摘要 > 服务描述 > 兜底文案**。
     */
    private fun describe(
        id: String,
        serviceDesc: String?,
        pending: Set<String>,
        failed: Set<String>,
        restartSummary: (String) -> String?,
    ): Pair<String, ServiceDescKind> {
        if (failed.contains(id)) {
            return appContext.getString(R.string.service_restart_failed) to ServiceDescKind.FAILED
        }
        if (pending.contains(id)) {
            return appContext.getString(R.string.service_restarting) to ServiceDescKind.RESTARTING
        }
        val summary = restartSummary(id)
        if (summary != null) return summary to ServiceDescKind.RESTART_SCHEDULE
        val d = serviceDesc?.takeIf { it.isNotEmpty() }
            ?: appContext.getString(R.string.service_no_description)
        return d to ServiceDescKind.NORMAL
    }

    /** 匹配：id / 应用名 / 服务名 任一命中（与既有过滤口径一致，均转小写）。 */
    private fun matches(info: AccessibilityServiceInfo, lowerQuery: String): Boolean {
        val id = info.id
        val e = iconCache.peek(id)
        val hay = (
            id + " " +
                (e?.appLabel ?: "") + " " +
                (e?.serviceLabel ?: "")
            ).lowercase(Locale.ROOT)
        return hay.contains(lowerQuery)
    }

    /**
     * 置顶优先，其余保持安装顺序；**置顶项内部按 `top` 串顺序**（既有 `sortDisplay` 口径）。
     *
     * 用 `topOrder.indexOf` 定序：串里靠前的置顶项排在前面；不在串里的保持原相对顺序。
     */
    private fun sortByTop(
        list: List<AccessibilityServiceInfo>,
        topOrder: List<String>,
    ): List<AccessibilityServiceInfo> {
        if (topOrder.isEmpty()) return list
        return list.sortedWith(compareBy { topOrder.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } })
    }

    /** 按 serviceId 反查安装项（供回调把模型映射回 Java 数据对象）。 */
    fun infoFor(serviceId: String): AccessibilityServiceInfo? =
        installed.firstOrNull { it.id == serviceId }
}
