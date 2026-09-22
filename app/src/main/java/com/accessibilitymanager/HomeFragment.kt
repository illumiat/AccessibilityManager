package com.accessibilitymanager

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.accessibilitymanager.ui.detail.DetailHost
import com.accessibilitymanager.ui.detail.DetailInfo
import com.accessibilitymanager.ui.detail.DetailRestart
import com.accessibilitymanager.ui.detail.ServiceDetailCallback
import com.accessibilitymanager.ui.home.HomeListBinder
import com.accessibilitymanager.ui.home.HomeListState
import com.accessibilitymanager.ui.home.HomeServiceCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashSet

/**
 * 主页（方案 §二）：服务网格 + 开关写 Settings.Secure（SettingValueWriter.mirror 防循环）+
 * ContentObserver 局部刷新 + 置顶精确移动 + 授权检查与激活对话框 + StartForeGroundDaemon +
 * 空态/未授权态/onResume 重查列表 + 详情弹卡（<600dp Bottom Sheet / ≥600dp Side Sheet）
 * + per-服务定期重启配置入口。
 */
class HomeFragment : Fragment(), HomeServiceCallback {

    private lateinit var sp: SharedPreferences
    private lateinit var iconCache: IconCache
    /** 【P3】列表状态持有者（Java 数据 → 不可变模型）+ Compose 列表宿主（替代 adapter/recyclerView） */
    private lateinit var listState: HomeListState
    private var listView: View? = null

    // 【P6】原 `emptyView` 字段已删（连同 `updateEmptyState()`）：
    // 零服务空态改由 Compose 的 `HomeScreen.EmptyState` 独任，不再有 View 侧开关。
    private val installed: MutableList<AccessibilityServiceInfo> = ArrayList()
    private val display: MutableList<AccessibilityServiceInfo> = ArrayList()
    private var searchQuery = ""

    private var settingValue = ""
    private var daemon = ""
    private var top = ""
    /** top/daemon 串按 ":" 精确切分的 id 集合，防互为前缀的服务 id 子串误判【MAJOR 3】 */
    private val topSet: MutableSet<String> = HashSet()
    private val daemonSet: MutableSet<String> = HashSet()

    /** 当前弹出详情的服务（`onSaveInstanceState` 持久化、重建后回填 —— 旋转保留卡片状态）【MISSING 12】 */
    private var detailServiceId: String? = null
    private var detailInfo: AccessibilityServiceInfo? = null
    /** 详情卡打开期间可重入的定期重启区域刷新逻辑（openDetail 注册 / dismiss 清理）【P3】 */
    private var detailRestartRefresher: Runnable? = null
    /**
     * 【P4】详情卡片的**宿主状态**：打开哪个服务、不可变模型、意图回调。
     *
     * 与 [listState] 同一条约束：**Java 类型绝不能进入组合树**（Java POJO 无可变/相等语义
     * → Compose 判定 unstable → 跳过重组失效）。宿主只往里提交已定稿的模型。
     *
     * ⚠️ 承载方式已由 MDC 的 `BottomSheetDialog` / `SideSheetDialog` 改为**同组合内 overlay**
     * （见 `ui/detail/DetailOverlay.kt`）：**共享元素跨不过 window**，
     * 而规范 §11.3 要求页面转场用共享元素，故弹卡必须与列表同处一个 `SharedTransitionLayout`。
     */
    private val detailHost = DetailHost()

    private lateinit var contentObserver: SettingsValueChangeContentObserver
    private var shizukuListener: Shizuku.OnRequestPermissionResultListener? = null
    /** 【R4】onCreate 捕获的 applicationContext：onDestroy 注销 observer 不依赖 getContext()（极端时序为 null） */
    private lateinit var appContext: Context

    inner class SettingsValueChangeContentObserver : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val cr: ContentResolver? = if (context != null) context!!.contentResolver else null
            if (cr == null) return
            var s = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (s == null) s = ""
            settingValue = s
            // 【P4】s==SettingValueWriter.mirror（本 APP 自己写，或外部把设置串改回"上次写入值"）不再静默
            // return：先做只读 UI 刷新再收尾，自检基准随外部变化校准，不再滞留旧态至 onResume。
            // 刷新仅读设置值刷新可见行开关态与详情卡（refreshStates/updateRestartViews 均不写
            // Settings.Secure，也不触碰 SettingValueWriter.mirror 写方镜像）→ 无写路径、无回环
            postStatesRefresh()
        }
    }

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 【MISSING 12】旋转后回填「当前打开的服务 id」（存取见 onSaveInstanceState）
        detailServiceId = savedInstanceState?.getString(KEY_DETAIL_SERVICE_ID)
        appContext = requireContext().getApplicationContext() // 【R4】提前捕获，onDestroy 注销用
        sp = requireContext().getSharedPreferences("data", 0)
        daemon = sp.getString("daemon", "") ?: ""
        top = sp.getString("top", "") ?: ""
        fillIds(top, topSet)
        fillIds(daemon, daemonSet)
        iconCache = IconCache(requireContext())

        contentObserver = SettingsValueChangeContentObserver()
        requireContext().contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            true,
            contentObserver,
        )

        val shizukuListenerLocal = Shizuku.OnRequestPermissionResultListener { _, _ ->
            // Shizuku 授权结果回来后重试 pm grant
            if (context != null) PermissionHelper.grantViaShizuku(context!!)
        }
        shizukuListener = shizukuListenerLocal
        // 未授权时注册监听（与旧行为一致）
        if (!PermissionHelper.hasWritePermission(requireContext())) {
            Shizuku.addRequestPermissionResultListener(shizukuListenerLocal)
        }
    }

    override fun onCreateView(
        @NonNull inflater: LayoutInflater,
        @Nullable container: ViewGroup?,
        @Nullable savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 【MISSING 16】Android 13+ 首页首次进入即请求一次通知权限（守护启动路径既有请求保留）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            if (nm != null && !nm.areNotificationsEnabled()) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS,
                )
            }
        }
        // 【P3】搜索栏已迁入 Compose（HomeScreen 的 SearchField）：原 EditText 隐藏，
        // searchQuery 字段保留为唯一数据源，由 Compose 的 onQueryChange 回写。【P6】从布局删除此项。
        val searchLayout = view.findViewById<View>(R.id.search_layout)
        if (searchLayout != null) searchLayout.visibility = View.GONE

        // 【P3】列表改由 Compose 渲染，以下三段 View 侧机制整段退役：
        //   1) GridLayoutManager 的 span 判定 → Compose 用 BoxWithConstraints 实时算（等价）
        //   2) GridSpacingDecoration → LazyGrid 的 spacedBy(8dp)
        //   3) OnScrollListener 的滚动暂停 → snapshotFlow(isScrollInProgress) → IconCache.setPaused
        listState = HomeListState(requireContext().getApplicationContext(), iconCache)
        listView = view.findViewById(R.id.compose_list)
        bindComposeList()

        loadInstalled()

        // 【MISSING 12】旋转后按回填的 id 重开详情卡（图标/数据重新取，不阻塞首屏）
        detailServiceId?.let { id ->
            installed.firstOrNull { it.getId() == id }?.let { openDetail(it) }
        }

        // 隐藏后台：随设置页开关即时生效，这里应用当前偏好
        applyHideFromRecents()

        // 首次使用隐私政策
        if (sp.getBoolean("first", true)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.privacy_title)
                .setMessage(R.string.privacy_message)
                .setPositiveButton(R.string.action_ok, null)
                .show()
            sp.edit().putBoolean("first", false).apply()
        }

        // 设备从未打开过无障碍设置界面时，设置项不存在，需要引导激活
        if (Settings.Secure.getString(
                requireContext().contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
            ) == null
        ) {
            val cmd = "pm grant " + requireContext().getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.activate_dialog_message)
                .setNegativeButton(R.string.action_root_activate) { _, _ ->
                    PermissionHelper.grantViaRoot(requireContext(), cmd)
                }
                .setPositiveButton(R.string.action_copy_command) { _, _ ->
                    PermissionHelper.copyToClipboard(requireContext(), "adb shell $cmd")
                    Toast.makeText(requireContext(), R.string.toast_command_copied, Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(R.string.action_shizuku_activate) { _, _ ->
                    PermissionHelper.grantViaShizuku(requireContext())
                }
                .show()
            try {
                Settings.Secure.putString(
                    requireContext().contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    "1",
                )
            } catch (ignored: Exception) {
            }
        }

        // 受保活锁定的服务 → 启动前台守护
        startDaemonIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // 【二轮修订 P1】从系统设置返回时重查列表与权限，不依赖 ContentObserver 事件
        loadInstalled()
        val granted = PermissionHelper.hasWritePermission(requireContext())
        if (::listState.isInitialized) listState.setPermission(granted)
        refreshStates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 【MISSING 12】旋转保留卡片状态：Activity 重建（Manifest 未声明 `orientation`）会丢 Compose 状态，
        // 故把「当前打开的服务 id」写进 savedInstanceState，重建后回填并重开（模型随之重提交）。
        detailServiceId?.let { outState.putString(KEY_DETAIL_SERVICE_ID, it) }
    }

    override fun onDestroy() {
        // 【MAJOR 4】退出前关闭详情弹卡，防 WindowLeaked/Activity 泄漏
        dismissDetailSheet()
        val shizukuListenerLocal = shizukuListener
        if (shizukuListenerLocal != null) Shizuku.removeRequestPermissionResultListener(shizukuListenerLocal)
        if (::contentObserver.isInitialized && ::appContext.isInitialized) {
            // 【R4】经 applicationContext 的 ContentResolver 注销（与注册同一 ContentService），
            // getContext() 为 null 的极端时序同样注销，防 ContentObserver 持 Fragment/Activity 引用泄漏
            appContext.contentResolver.unregisterContentObserver(contentObserver)
        }
        if (::iconCache.isInitialized) iconCache.shutdown()
        super.onDestroy()
    }

    // ---------- 列表加载与排序 ----------

    private fun loadInstalled() {
        installed.clear()
        // 【SEVERE 1】AccessibilityManager 位于 android.view.accessibility 包（原错包引用编译必失败）
        val am = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        installed.addAll(am.getInstalledAccessibilityServiceList())

        // 卸载清理：重启配置（含置顶标记）一并移除【二轮修订 P2】
        cleanupUninstalledConfigs()

        sortDisplay()
        // 【P3】原 `applyFilter()`（→ adapter.setItems）此处省略：末尾 refreshStates() 已用**新的**
        // settingValue 做一次 refreshList()，先跑一次会拿旧 settingValue 造成一帧开关态错位。
        settingValue = SettingValueWriter.read(requireContext())
        // 【P6】原 updateEmptyState() 已删：空态由 Compose 自然渲染，不再需要 View 侧开关。
        refreshStates()
    }

    /**
     * 搜索过滤：基于已排序的 display 保序过滤（置顶顺序保留）。
     * 匹配范围：服务 id、缓存的应用标签/服务标签。空查询显示全部。
     */
    private fun applyFilter() {
        // 【P3】过滤已上移到 HomeListState.submit（按 id / 应用名 / 服务名匹配，同一口径）；
        // searchQuery 是唯一数据源，此处只需重算列表。display 仍由 sortDisplay() 维护（供详情/置顶定位用）。
        refreshList()
    }

    /** 列表刷新时同步清理不存在服务的重启配置【二轮修订 P2】 */
    private fun cleanupUninstalledConfigs() {
        for (id in ArrayList(RestartPrefs.getPendingEnables(requireContext()).keys)) {
            if (!isInstalledId(id)) RestartPrefs.removeCompletely(requireContext(), id)
        }
        for (id in ArrayList(RestartPrefs.getFailed(requireContext()))) {
            if (!isInstalledId(id)) RestartPrefs.removeCompletely(requireContext(), id)
        }
        for (id in RestartPrefs.enabledIds(requireContext())) {
            if (!isInstalledId(id)) RestartPrefs.removeCompletely(requireContext(), id)
        }
        // 【惰性调度】卸载清理后若已无启用配置，取消空转的周期任务
        RestartWorker.cancelIfIdle(requireContext())
    }

    private fun isInstalledId(serviceId: String): Boolean {
        for (info in installed) {
            if (info.getId() == serviceId) return true
        }
        return false
    }

    private fun sortDisplay() {
        display.clear()
        display.addAll(installed)
        // 【F3】top 串按 ":" 段级切分（与 fillIds 同源），比较器用 List.indexOf 精确定位。
        // 原 top.indexOf 子串定位在互为前缀的服务 id 上可双向 compare 同返回 1，
        // 违反比较器契约（TimSort 可能抛 "Comparison method violates its general contract"）。
        // 同一 id 在 topOrder 中至多出现一次（toggleTop 前插/移除保证），索引比较严格满足反对称性。
        val topOrder: List<String> = RestartPrefs.splitIds(top)
        Collections.sort(display, Comparator<AccessibilityServiceInfo> { info1, info2 ->
            val i1 = topOrder.indexOf(info1.getId())
            val i2 = topOrder.indexOf(info2.getId())
            if (i1 == -1 && i2 == -1) return@Comparator 0
            if (i1 == -1) return@Comparator 1 // -1 视为未置顶排后
            if (i2 == -1) return@Comparator -1
            Integer.compare(i1, i2)
        })
    }

    // 【P6】updateEmptyState() 已删除。
    //
    // 它原本在 `installed.isEmpty()` 时把 View 侧的空态置 VISIBLE、把 ComposeView 置 GONE。
    // 这是**双实现**：Compose 的 EmptyState 已经完整实现三段式（56dp 低对比图标 +
    // titleMedium + bodyMedium 下一步 + 主动作），却被这段逻辑挡住而**永远不可达**；
    // 而真正渲染的 View 空态反而不合规（图标 96dp，超规范 §8 的 48–56dp 近两倍，
    // 且缺 §6 要求的「下一步」一段）。
    //
    // 现由 Compose 独任：列表为空时 `HomeScreen` 自然渲染 EmptyState，
    // 故 **ComposeView 必须始终可见**，不再有"谁显示"的开关。

    // ---------- 【P3】列表渲染收口 ----------

    /**
     * 列表刷新**唯一入口**：把当前全部数据交给 Compose 状态持有者。
     * 替代原先散落的 adapter.setItems / refreshStates / moveItem / notifyItemChanged 调用。
     */
    private fun refreshList() {
        if (!::listState.isInitialized) return
        listState.submit(
            ArrayList(installed),
            settingValue,
            pendingSet(),
            failedSet(),
            topOrder(),
            daemonSet,
            ::restartSummary,
        )
    }

    /** 绑定 Compose 列表（**只调一次**：状态类输入走 listState 的 Compose 状态，不重调 setContent）。 */
    private fun bindComposeList() {
        val lv = listView
        if (lv !is ComposeView) return
        HomeListBinder.bind(
            lv,
            listState,
            detailHost,
            this,
            { q ->
                searchQuery = q
                refreshList()
            },
            { PermissionHelper.showPermissionDialog(requireContext()) },
            { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            ::refreshList,
        )
    }

    /** 置顶串顺序（既有 sortDisplay 的 indexOf 排序依赖串顺序，故必须传 List 而非 Set）。 */
    private fun topOrder(): List<String> = RestartPrefs.splitIds(sp.getString("top", "").orEmpty())

    /** observer 路径的只读显示刷新：可见行开关态 + 详情卡定期重启区域（不写 Settings.Secure）【P4】 */
    private fun postStatesRefresh() {
        val act = activity
        if (act != null) {
            act.runOnUiThread {
                refreshList()
                refreshDetailRestartArea() // 【P3】Sheet 打开中同步"重启中…"/恢复态，防停留旧态
            }
        }
    }

    private fun refreshStates() {
        refreshList()
        refreshDetailRestartArea() // 【P3】局部刷新路径同步详情 Sheet 定期重启区域
    }

    /** 【P3】详情 Sheet 打开中时刷新定期重启卡区域（周期行/重启中态/上次执行时间） */
    private fun refreshDetailRestartArea() {
        if (detailHost.isOpen && detailServiceId != null && detailRestartRefresher != null) {
            detailRestartRefresher!!.run()
        }
    }

    private fun startDaemonIfNeeded() {
        if (PermissionHelper.hasWritePermission(requireContext())) {
            for (info in installed) {
                if (daemonSet.contains(info.getId())) {
                    startForegroundDaemon()
                    break
                }
            }
        }
    }

    private fun applyHideFromRecents() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                (requireContext().getSystemService(Service.ACTIVITY_SERVICE) as ActivityManager)
                    .getAppTasks().get(0).setExcludeFromRecents(sp.getBoolean("hide", true))
            }
        } catch (ignored: Exception) {
        }
    }

    /** 启动前台守护服务（迁移自旧 StartForeGroundDaemon） */
    fun startForegroundDaemon() {
        // 【S2】调用方（startDaemonIfNeeded/onLockClick）均确认有权限才进入；此处只拦截"无权限"，
        // 原逻辑遇有权限反而 return → daemon 永不启动（启动链路死锁）
        if (!PermissionHelper.hasWritePermission(requireContext())) {
            PermissionHelper.showPermissionDialog(requireContext())
            return
        }
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        // 【M-b】系统服务可能取不到，判 null 防 NPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && nm != null && !nm.areNotificationsEnabled()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
            Toast.makeText(requireContext(), R.string.toast_please_grant_notification, Toast.LENGTH_SHORT).show()
            return
        }
        val power = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + requireContext().packageName),
                    ),
                )
            } catch (ignored: Exception) {
            }
        }
        val intent = Intent(requireContext(), daemonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(requireContext(), intent)
        } else {
            requireContext().startService(intent)
        }
    }

    // ---------- HomeServiceCallback（列表意图 → Java 侧落笔） ----------

    override fun onToggle(info: AccessibilityServiceInfo, checked: Boolean) {
        if (!PermissionHelper.hasWritePermission(requireContext())) {
            PermissionHelper.showPermissionDialog(requireContext())
            refreshStates() // 回滚 UI 状态
            return
        }
        val serviceName = info.getId()
        // 【MAJOR 10】开关串的**单服务前置 / 移除**统一走 RestartPrefs 单一实现（防双实现分叉）。
        // 【写入协议】镜像同步 / 写 / 读回校准 / 异常回滚 收口 SettingValueWriter.commit（唯一实现）。
        try {
            SettingValueWriter.commit(requireContext()) {
                if (checked) RestartPrefs.prependService(it, serviceName)
                else RestartPrefs.removeService(it, serviceName)
            }
        } catch (e: Exception) {
            // 【P6-a】catch 保持 Exception（与 daemon 侧口径统一）：失败 UX 在此，
            // 镜像已在 commit 内回滚为读回实际值（依赖自愈存在窗口期）
            Toast.makeText(requireContext(), R.string.toast_write_failed, Toast.LENGTH_SHORT).show()
            refreshStates()
        }
        // 【惰性调度】开关变化后回收空转调度：全部定期重启配置关闭时取消周期任务（零主动唤醒）
        RestartWorker.cancelIfIdle(requireContext())
    }

    override fun onLockClick(info: AccessibilityServiceInfo) {
        if (!PermissionHelper.hasWritePermission(requireContext())) {
            PermissionHelper.showPermissionDialog(requireContext())
            return
        }
        val serviceName = info.getId()
        // 【M4】daemon 串读改写收口 RestartPrefs.toggleDaemon（与 Worker/daemon 主线程互斥，单次 apply）
        val nowLocked = RestartPrefs.toggleDaemon(requireContext(), serviceName)
        if (nowLocked) {
            daemonSet.add(serviceName)
        } else {
            daemonSet.remove(serviceName)
        }
        daemon = sp.getString("daemon", "") ?: "" // 内存镜像自 SP 回读，与持锁写入结果保持一致
        startForegroundDaemon()
        // 【P3】锁图标即时跟随：Compose 侧按**不可变模型整体比对**（locked 在模型里），
        // 只要重启列表即自动反映；原 adapter.notifyItemChanged(pos, PAYLOAD_STATE) 精确重绑不再需要。
        refreshStates()
    }

    override fun onItemClick(info: AccessibilityServiceInfo) {
        openDetail(info)
    }

    override fun onItemLongClick(info: AccessibilityServiceInfo) {
        // 【二轮修订 P0】置顶路径禁用 notifyDataSetChanged：sort 后 notifyItemMoved 精确移动
        val serviceName = info.getId()
        val label = IconCache.shortClassName(serviceName)
        // 【M4】top 串读改写收口 RestartPrefs.toggleTop（与 Worker/daemon 主线程互斥，单次 apply）
        val nowTop = RestartPrefs.toggleTop(requireContext(), serviceName)
        if (nowTop) {
            topSet.add(serviceName)
            Toast.makeText(requireContext(), getString(R.string.toast_topped, label), Toast.LENGTH_SHORT).show()
        } else {
            topSet.remove(serviceName)
            Toast.makeText(requireContext(), getString(R.string.toast_untopped, label), Toast.LENGTH_SHORT).show()
        }
        top = sp.getString("top", "") ?: "" // 内存镜像自 SP 回读（sortDisplay 的 indexOf 排序依赖串顺序）

        sortDisplay()
        // 【P3】置顶移动改由 Compose/LazyGrid 承担：`items(key = serviceId)` 保证身份稳定，
        // `animateItem()` 以弹簧完成重排 —— 原 adapter.moveItem + notifyItemMoved 那条
        // 「数据与通知序列必须一致」的纪律由 key 机制天然满足（不再有稳定 id 错位面）。
        refreshStates()
    }

    override fun isTop(serviceId: String): Boolean {
        return topSet.contains(serviceId)
    }

    override fun isLocked(serviceId: String): Boolean {
        return daemonSet.contains(serviceId)
    }

    override fun restartSummary(serviceId: String): String? {
        val cfg = RestartPrefs.get(requireContext(), serviceId)
        return if (cfg != null) formatPeriod(cfg.periodMin) else null
    }

    // ---------- 批 2：详情弹卡 ----------

    fun dismissDetailSheet() {
        // 与旧实现一致：关闭即清记录（用户点遮罩/下滑关闭同样生效），防残留触发误重开【MISSING 12】
        detailHost.close()
        detailServiceId = null
        detailInfo = null
        detailRestartRefresher = null // 【P3】防引用已失效的刷新闭包
    }

    /** 点击卡片弹出详情：<600dp Bottom Sheet / ≥600dp Side Sheet（WindowMetrics 实时判断，Q2） */
    private fun openDetail(info: AccessibilityServiceInfo) {
        dismissDetailSheet()
        val serviceId = info.getId()
        detailServiceId = serviceId // 【MISSING 12】记录当前详情服务，尺寸变化时以新形态重开
        detailInfo = info
        val slash = serviceId.indexOf('/')
        val pkg = if (slash > 0) serviceId.substring(0, slash) else serviceId

        // 【P4】详情内容改由 Compose 渲染。回调按**弹卡实例**构造，捕获本卡恒定的
        // serviceId / info / pkg（与迁移前的闭包捕获同一口径，不经 id 反查）。
        // 写入路径仍全部由宿主落笔 —— F1/R3 镜像纪律的唯一落点，Compose 只发意图。
        val callback = object : ServiceDetailCallback {
            override fun onRestartToggle(checked: Boolean) {
                applyRestartToggle(serviceId, checked)
            }

            override fun onPeriodConfirmed(periodMin: Long) {
                applyPeriod(serviceId, periodMin)
            }

            override fun onOpenSystemSettings() {
                openServiceSettings(info, pkg)
            }
        }

        // 【承载方式变更】不再建 Dialog —— 详情卡由同组合内的 DetailOverlay 渲染。
        // 共享元素跨不过 window，弹卡必须与列表同处一个 SharedTransitionLayout（规范 §11.3）。
        // 形态（<600dp 底部抽屉 / ≥600dp 居中悬浮）由 overlay 按 BoxWithConstraints 实时判定。
        detailHost.callback = callback
        detailHost.serviceId = serviceId

        // 首刷：头部 / 信息块 / 定期重启区
        val e = iconCache.peek(serviceId)
        detailHost.state.submitHeaderFromCache(serviceId, e)
        if (e == null || e.icon == null) {
            // 【M-c】缓存未命中时传回调：卡片仍打开且仍是同一服务 → 补刷图标与标题，
            // 防加载完成后停留占位图。守卫与迁移前逐条一致。
            iconCache.load(info, IconCache.LoadCallback { reloadDetailHeader(info, serviceId) })
        }
        submitDetailInfo(info)
        detailRestartRefresher = Runnable { submitDetailRestart() } // 【P3】注册供 onChange/refreshStates 重入
        submitDetailRestart()
    }

    /** 详情头部异步补刷（图标/标签就绪后重提交）。 */
    private fun reloadDetailHeader(info: AccessibilityServiceInfo, serviceId: String) {
        if (!detailHost.isOpen || detailServiceId == null || serviceId != detailServiceId) {
            return
        }
        detailHost.state.submitHeaderFromCache(serviceId, iconCache.peek(serviceId))
    }

    /**
     * 只读信息块：状态 / 生效范围 / 反馈方式 + 两组位解码标签。
     *
     * 文案由宿主拼：格式串与资源 id 绑定，且与列表卡片共用同一套
     * （展示层再拼一遍就是「同功能双实现」）。
     */
    private fun submitDetailInfo(info: AccessibilityServiceInfo) {
        val st = detailHost.state
        val serviceId = info.getId()
        // 【P6】收口 RestartPrefs.isEnabledIn（原经 ServiceAdapter.isEnabledIn 中转，后者随 Adapter 删除）
        val enabled = RestartPrefs.isEnabledIn(settingValue, serviceId)

        val statusLine = getString(
            R.string.detail_status,
            getString(if (enabled) R.string.detail_status_enabled else R.string.detail_status_disabled),
        )
        val rangeLine = getString(R.string.detail_range) + "：" +
            (if (info.packageNames == null) getString(R.string.range_global) else joinArray(info.packageNames))
        val feedbackLine = getString(R.string.detail_feedback) + "：" + feedbackText(info.feedbackType)

        st.info = DetailInfo(
            java.util.Arrays.asList(statusLine, rangeLine, feedbackLine),
            capabilityChips(info),
            eventChips(info),
        )
    }

    /**
     * 定期重启区重入刷新（原 `updateRestartViews` Runnable）。
     *
     * 状态优先级与迁移前逐条一致：**恢复失败（终态警示）> 重启中…（过渡态）> 周期行**；
     * 开关位置取 `RestartPrefs.get(...) != null`；重启补偿进行中开关禁交互。
     *
     * 【P2/P5 的差异】**开关"回正"这件事在 Compose 侧不再需要**：
     * 旧实现要在回正前「先摘 listener → `setChecked` → 再设回」，否则会误触发一次切换。
     * Compose 的 `Switch(checked = ...)` 位置完全由模型决定、自身不持有状态 ——
     * 那条纪律不是被绕过，而是**在结构上不存在了**。
     */
    private fun submitDetailRestart() {
        val st = detailHost.state
        val serviceId = detailServiceId
        if (serviceId == null) return
        val cur = RestartPrefs.get(requireContext(), serviceId)
        val pending = RestartPrefs.getPendingEnables(requireContext()).containsKey(serviceId)
        val failed = RestartPrefs.getFailed(requireContext()).contains(serviceId)

        val periodText = if (failed) {
            getString(R.string.service_restart_failed)
        } else if (pending) {
            getString(R.string.service_restarting)
        } else {
            getString(
                R.string.detail_restart_period,
                if (cur != null) formatPeriod(cur.periodMin) else "—",
            )
        }
        val lastText = if (cur != null && cur.lastRestart > 0)
            getString(R.string.detail_restart_last, formatRelative(cur.lastRestart))
        else
            getString(R.string.detail_restart_never)

        st.restart = DetailRestart(
            cur != null,
            !pending,
            periodText,
            lastText,
            failed,
            // 【R1】预填值必须是"最近一次设定的周期"（含未启用态），不能回落默认值
            RestartPrefs.peekLastPeriod(requireContext(), serviceId),
        )
    }

    // ---------- 详情卡的写入路径（全部落笔在这里，Compose 只发意图） ----------

    /**
     * 定期重启开关（迁移前 `restartToggle` 闭包体，逐行保留）。
     *
     * 【R1】启用时**实时**取当前 `periodMin`（含"修改周期"刚写入的值），禁用闭包捕获的旧 cfg：
     * 已启用态用 `cfg.periodMin`，未启用态（关→再开）读最近设定周期，防新周期被静默回退成默认值。
     */
    private fun applyRestartToggle(serviceId: String, isChecked: Boolean) {
        if (isChecked) {
            val live = RestartPrefs.get(requireContext(), serviceId)
            val period = if (live != null) live.periodMin
            else RestartPrefs.peekLastPeriod(requireContext(), serviceId)
            RestartPrefs.enable(requireContext(), serviceId, period)
            RestartWorker.schedule(requireContext()) // 幂等
        } else {
            RestartPrefs.disable(requireContext(), serviceId)
        }
        refreshDetailRestartArea() // 重入刷新：周期行 / 上次执行 / 开关位置
        refreshStates() // 卡片描述行同步摘要
    }

    /**
     * 周期确认后的落笔（迁移前 `showPeriodDialog` 确认分支，逐行保留）。
     *
     * 已启用 → 只改周期；未启用 → 启用 + 调度，并 Toast 明示
     * （防「改周期」静默启用后服务被周期重启而用户不知情）。
     *
     * 范围校验在此**再验一次**：对话框已保证合法，但写入路径不接受来自展示层的隐式保证。
     */
    private fun applyPeriod(serviceId: String, minutes: Long) {
        if (minutes < RestartPrefs.MIN_PERIOD_MIN || minutes > RestartPrefs.MAX_PERIOD_MIN) return
        if (RestartPrefs.get(requireContext(), serviceId) != null) {
            RestartPrefs.setPeriod(requireContext(), serviceId, minutes)
        } else {
            RestartPrefs.enable(requireContext(), serviceId, minutes)
            RestartWorker.schedule(requireContext())
            Toast.makeText(requireContext(), R.string.detail_restart_enable, Toast.LENGTH_SHORT).show()
        }
        refreshStates()
    }

    /** "打开系统设置"：优先跳该服务自己的设置页，失败回落系统无障碍设置。 */
    private fun openServiceSettings(info: AccessibilityServiceInfo, pkg: String) {
        try {
            val settingsActivity = info.getSettingsActivityName()
            if (settingsActivity != null && settingsActivity.length > 0) {
                startActivity(Intent().setComponent(ComponentName(pkg, settingsActivity)))
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } catch (ignored: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (ignored2: Exception) {
            }
        }
    }

    private fun capabilityChips(info: AccessibilityServiceInfo): List<String> {
        val out: MutableList<String> = ArrayList()
        val cap = info.getCapabilities()
        if ((cap and 32) != 0) out.add(getString(R.string.cap_gestures))
        if ((cap and 16) != 0) out.add(getString(R.string.cap_magnification))
        if ((cap and 8) != 0) out.add(getString(R.string.cap_filter_key_events))
        if ((cap and 4) != 0) out.add(getString(R.string.cap_web))
        if ((cap and 2) != 0) out.add(getString(R.string.cap_touch_exploration))
        if ((cap and 1) != 0) out.add(getString(R.string.cap_retrieve_content))
        val fg = info.flags
        if ((fg and 64) != 0) out.add(getString(R.string.flag_interactive_windows))
        if ((fg and 32) != 0) out.add(getString(R.string.flag_filter_key_events))
        if ((fg and 16) != 0) out.add(getString(R.string.flag_report_view_ids))
        if ((fg and 8) != 0) out.add(getString(R.string.flag_enhanced_web))
        if ((fg and 4) != 0) out.add(getString(R.string.flag_touch_exploration))
        if ((fg and 2) != 0) out.add(getString(R.string.flag_include_not_important))
        if ((fg and 1) != 0) out.add(getString(R.string.flag_default))
        return out
    }

    private fun eventChips(info: AccessibilityServiceInfo): List<String> {
        val out: MutableList<String> = ArrayList()
        val eve = info.eventTypes
        if ((eve and 33554432) != 0) out.add(getString(R.string.ev_assistant))
        if ((eve and 16777216) != 0) out.add(getString(R.string.ev_context_clicked))
        if ((eve and 8388608) != 0) out.add(getString(R.string.ev_windows_changed))
        if ((eve and 4194304) != 0) out.add(getString(R.string.ev_touch_end))
        if ((eve and 2097152) != 0) out.add(getString(R.string.ev_touch_start))
        if ((eve and 1048576) != 0) out.add(getString(R.string.ev_gesture_end))
        if ((eve and 524288) != 0) out.add(getString(R.string.ev_gesture_start))
        if ((eve and 262144) != 0) out.add(getString(R.string.ev_text_traversed))
        if ((eve and 131072) != 0) out.add(getString(R.string.ev_clear_focus))
        if ((eve and 65536) != 0) out.add(getString(R.string.ev_gain_focus))
        if ((eve and 32768) != 0) out.add(getString(R.string.ev_announcement))
        if ((eve and 16384) != 0) out.add(getString(R.string.ev_selection_changed))
        if ((eve and 8192) != 0) out.add(getString(R.string.ev_view_scrolled))
        if ((eve and 4096) != 0) out.add(getString(R.string.ev_content_changed))
        if ((eve and 2048) != 0) out.add(getString(R.string.ev_touch_explore_end))
        if ((eve and 1024) != 0) out.add(getString(R.string.ev_touch_explore_start))
        if ((eve and 512) != 0) out.add(getString(R.string.ev_text_input_end))
        if ((eve and 256) != 0) out.add(getString(R.string.ev_text_input_start))
        if ((eve and 128) != 0) out.add(getString(R.string.ev_notification_changed))
        if ((eve and 64) != 0) out.add(getString(R.string.ev_window_state_changed))
        if ((eve and 32) != 0) out.add(getString(R.string.ev_text_changed))
        if ((eve and 16) != 0) out.add(getString(R.string.ev_view_focused))
        if ((eve and 8) != 0) out.add(getString(R.string.ev_view_selected))
        if ((eve and 4) != 0) out.add(getString(R.string.ev_view_long_clicked))
        if ((eve and 2) != 0) out.add(getString(R.string.ev_view_clicked))
        return out
    }

    private fun feedbackText(fb: Int): String {
        val out: MutableList<String> = ArrayList()
        if ((fb and 32) != 0) out.add(getString(R.string.feedback_braille))
        if ((fb and 16) != 0) out.add(getString(R.string.feedback_generic))
        if ((fb and 8) != 0) out.add(getString(R.string.feedback_visual))
        if ((fb and 4) != 0) out.add(getString(R.string.feedback_audible))
        if ((fb and 2) != 0) out.add(getString(R.string.feedback_haptic))
        if ((fb and 1) != 0) out.add(getString(R.string.feedback_spoken))
        if (out.isEmpty()) return getString(R.string.chip_none)
        val sb = StringBuilder()
        for (i in out.indices) {
            if (i > 0) sb.append('、')
            sb.append(out[i])
        }
        return sb.toString()
    }

    private fun joinArray(arr: Array<String>): String {
        val sb = StringBuilder()
        for (i in arr.indices) {
            if (i > 0) sb.append('、')
            sb.append(arr[i])
        }
        return sb.toString()
    }

    // ---------- 展示辅助 ----------

    /** 周期展示：整小时/整天取大单位 */
    private fun formatPeriod(periodMin: Long): String {
        if (periodMin % 1440 == 0L) {
            return getString(R.string.period_display_days, (periodMin / 1440).toString())
        }
        if (periodMin % 60 == 0L) {
            return getString(R.string.period_display_hours, (periodMin / 60).toString())
        }
        return getString(R.string.period_display_minutes, periodMin)
    }

    /** 相对时间："3 天前"（【二轮修订 P2】） */
    private fun formatRelative(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        val min = diff / 60000L
        if (min < 1) return getString(R.string.relative_just_now)
        if (min < 60) return getString(R.string.relative_minutes_ago, min)
        val hours = min / 60
        if (hours < 24) return getString(R.string.relative_hours_ago, hours)
        return getString(R.string.relative_days_ago, hours / 24)
    }

    // ---------- 批 2 数据源 ----------

    fun pendingSet(): Set<String> {
        return RestartPrefs.getPendingEnables(requireContext()).keys
    }

    fun failedSet(): Set<String> {
        return RestartPrefs.getFailed(requireContext())
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1

        /** 详情卡在 savedInstanceState 里的键（旋转保留卡片状态）【MISSING 12】 */
        private const val KEY_DETAIL_SERVICE_ID = "detail_service_id"

        /** ":" 连接的 id 串 → 精确 id 集合【MAJOR 3】 */
        private fun fillIds(colonJoined: String, out: MutableSet<String>) {
            out.clear()
            out.addAll(RestartPrefs.splitIds(colonJoined))
        }
    }
}
