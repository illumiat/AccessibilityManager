package com.accessibilitymanager

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 前台守护服务（方案 §五）：
 * - 保留事件驱动、无轮询模型
 * - 常驻通知降 IMPORTANCE_LOW + 正经文案（"无障碍服务保活中 · N 个服务受保护"）
 * - onCreate/onStartCommand 开头检查 RestartPrefs 的 pendingEnable 残留并补 enable【盲审修订 P0】
 * - doDaemon() NPE 防御：NameNotFoundException 不再吞后裸调 loadLabel
 *
 * ## 转换说明（行为不变，三点必须守住）
 *
 * 1. [tmpSettingValue] 必须是 **Java 静态字段**：用 `@JvmField @Volatile`（而非 `@JvmStatic`）。
 *    `@JvmStatic` 只生成静态 **getter/setter**，不是字段 —— 原 Java 是 `public static volatile String`，
 *    字段语义一旦变成访问器就不再等价。
 * 2. [SettingsValueChangeContentObserver] 在 Java 中是**非静态内部类**（隐式持有外部服务引用，
 *    因此能直接调用 `getContentResolver()` / [doDaemon]）；Kotlin 必须写 `inner class`，
 *    写成嵌套类会编译失败（拿不到外部成员）。
 * 3. [tmpSettingValue] 的全部写入点（doDaemon 写前、写失败回滚、写后读回校准、tryEnable 写前/读回/异常回滚、
 *    compensatePendingEnables 收尾同步）**一处都不能少** —— 这是 ContentObserver 自触发防循环的唯一依据。
 */
class daemonService : Service() {

    private var mContentOb: SettingsValueChangeContentObserver? = null

    /** onDestroy 卸载防崩标志位：空 daemon 提前 stopSelf 路径未注册 receiver/observer【SEVERE 2】 */
    private var receiverRegistered = false
    private var observerRegistered = false

    private lateinit var sp: SharedPreferences
    private lateinit var notification: Notification.Builder
    private var systemService: NotificationManager? = null
    private lateinit var l: MutableList<String>
    private lateinit var packageManager: PackageManager

    private val myReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            var set = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (set == null) set = ""
            if (tmpSettingValue == set) return
            doDaemon(set)
        }
    }

    /** 自定义一个内容监视器（**inner**：需访问外部服务的 `contentResolver` 与 [doDaemon]） */
    inner class SettingsValueChangeContentObserver : ContentObserver(
        // 【M-a】显式主线程 Looper：空参 new Handler() 依赖隐式当前线程 Looper，语义不明确
        Handler(Looper.getMainLooper()),
    ) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            var s = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (s == null) s = ""
            // 如果这俩相等，说明本次变动是 APP 自己改的。于是就不需要做处理。
            if (tmpSettingValue == s) return
            doDaemon(s)
        }
    }

    private fun doDaemon(s: String) {
        val list = sp.getString("daemon", "")!!
        val serviceNames = RestartPrefs.COLON.split(list) // 【P6-d】共用 Pattern 类常量
        val add = StringBuilder()
        val add1 = StringBuilder()
        val restored = ArrayList<String>() // 【MISSING 11】本次自动恢复的服务 id
        for (serviceName in serviceNames) {
            // 【M1】启用判定收口 RestartPrefs.isEnabledIn（":" 切段精确匹配，消除 contains 子串误判）
            if (serviceName == null || serviceName == "null" || serviceName.isEmpty() ||
                RestartPrefs.isEnabledIn(s, serviceName) || !l.contains(serviceName)
            ) {
                continue
            }

            // 【R2】防御不含"/"的异常段（历史/异常持久化数据）：indexOf=-1 会越界崩溃前台服务，
            // 直接跳过并按段清理守护串（pruneDaemon 内部含 contains 检查，非守护项为无副作用空操作）
            val slash = serviceName.indexOf('/')
            if (slash <= 0) {
                RestartPrefs.pruneDaemon(this@daemonService, serviceName)
                continue
            }
            // 【MISSING 15】标签查询并入 IconCache 静态缓存层（同进程直调，miss 后台 load）
            val packageLabel = IconCache.packageLabel(packageManager, serviceName.substring(0, slash))
            add.append(serviceName).append(":")
            add1.append(packageLabel).append("\n")
            restored.add(serviceName)
            if (sp.getBoolean("toast", true)) {
                Toast.makeText(
                    this@daemonService,
                    getString(R.string.toast_keep_alive, packageLabel),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        if (add.length > 0) {
            tmpSettingValue = add.toString() + s
            // 【S3】WRITE_SECURE_SETTINGS 缺失时 putString 抛 SecurityException，不得崩溃前台服务：
            // 失败即 return 停止本次恢复循环。
            // 【F5】恢复失败必须可感知：对本轮 restored 列表逐个 markFailed（方案 §四 三重可感知硬约束），
            // 修复 daemon-only 服务恢复失败永久静默的问题（原仅 return 不 markFailed）
            try {
                Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    tmpSettingValue,
                )
            } catch (e: Exception) {
                for (id in restored) {
                    RestartPrefs.markFailed(this, id)
                }
                // 【P6-b】写失败回滚镜像为读取到的实际值（与 tryEnable 的 R3 回滚对称），
                // 防镜像失真致观察者对后续外部变化误跳过
                try {
                    val actual = Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    )
                    tmpSettingValue = actual ?: ""
                } catch (ignored: Exception) {
                }
                return
            }
            // 【MISSING 11】自动恢复成功 → 写时间戳标记（ServiceAdapter 5 分钟内显示"已自动恢复"角标）
            val now = System.currentTimeMillis()
            val mark = getSharedPreferences("restart", 0).edit()
            for (id in restored) {
                mark.putLong(RestartPrefs.AUTO_RESTORED_PREFIX + id, now)
            }
            mark.apply()
            // 【M1-r10】写成功后读回实际值校准镜像（写 → 读回校准三段式，与 tryEnable/Worker 口径一致），
            // 防止观察者对后续外部变化误跳过
            val after = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (after != null) tmpSettingValue = after
            notification.setContentText(
                add1.toString() + getString(
                    R.string.notification_keep_time,
                    SimpleDateFormat("H:mm ss秒", Locale.getDefault())
                        .format(Calendar.getInstance().time),
                ),
            )
                .setContentTitle(getString(R.string.notification_keep_title))
            // 【M-d】系统服务可能取不到，判 null 防 NPE
            systemService?.notify(NOTIFY_ID, notification.build())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sp = getSharedPreferences("data", 0)
        // `SharedPreferences.getString` 在 SDK 中标注 @Nullable（Kotlin 视为 String?）；
        // 原 Java 直接取 .length()，null 即 NPE —— 用 `!!` 保持同一语义，不做静默兜底。
        if (sp.getString("daemon", "")!!.length == 0) {
            // 【SEVERE 2②】API 26+ 经 startForegroundService 拉起时必须先 startForeground 再 stopSelf，
            // 否则 5 秒内未进入前台触发 ForegroundServiceDidNotStartInTimeException
            startForegroundPlaceholder()
            stopSelf()
            return
        }
        // ⚠️ 本类字段名 `packageManager` 与 Context 的属性同名，写 `packageManager = packageManager`
        // 会变成自赋值（且 lateinit 未初始化读取直接抛异常）。显式走 applicationContext 取同一个
        // PackageManager —— 与原 `getPackageManager()` 行为等价（服务 PM 由其 base context 提供）。
        this.packageManager = applicationContext.packageManager
        Toast.makeText(this@daemonService, R.string.toast_daemon_start, Toast.LENGTH_SHORT).show()
        val list = (applicationContext
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .installedAccessibilityServiceList
        l = ArrayList()
        for (i in list.indices) {
            l.add(list[i].id)
        }
        // 注册监视器，读取当前设置项并存到 tmpSettingValue
        mContentOb = SettingsValueChangeContentObserver()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            true,
            mContentOb!!,
        )
        observerRegistered = true
        var initial = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (initial == null) initial = ""
        tmpSettingValue = initial

        registerReceiver(myReceiver, IntentFilter("android.intent.action.SCREEN_ON"))
        receiverRegistered = true

        // 发送前台通知：IMPORTANCE_LOW + 正经文案
        val count = countDaemonServices(sp.getString("daemon", "")!!)
        notification = Notification.Builder(this)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentTitle(getString(R.string.notification_daemon_title, count))
        systemService = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notification
                .setSmallIcon(Icon.createWithResource(this, R.drawable.tile))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                CHANNEL_DAEMON,
                getString(R.string.channel_daemon_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationChannel.enableLights(false)
            notificationChannel.setShowBadge(false)
            notificationChannel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            // 【M-d】系统服务可能取不到，判 null 防 NPE
            systemService?.createNotificationChannel(notificationChannel)
            notification.setChannelId(CHANNEL_DAEMON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        startForeground(NOTIFY_ID, notification.build())

        // 先检查 pendingEnable 残留并补 enable【盲审修订 P0】，再做一次保活
        compensatePendingEnables()
        doDaemon(tmpSettingValue)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被拉起/重启时同样检查残留【盲审修订 P0】
        compensatePendingEnables()
        return super.onStartCommand(intent, flags, startId)
    }

    /** 检查 RestartPrefs 的 pendingEnable 残留并补 enable【盲审修订 P0】 */
    private fun compensatePendingEnables() {
        var any = false
        for ((id, ts) in RestartPrefs.getPendingEnables(this)) {
            any = true
            val age = System.currentTimeMillis() - ts
            if (ts > 0 && age > RestartPrefs.pendingStaleMs()) {
                RestartPrefs.removePendingEnable(this, id)
                RestartPrefs.markFailed(this, id)
                continue
            }
            var cur = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (cur == null) cur = ""
            if (RestartPrefs.containsService(cur, id)) {
                RestartPrefs.removePendingEnable(this, id)
                continue
            }
            val ok = tryEnable(id)
            if (ok) {
                RestartPrefs.removePendingEnable(this, id)
                // 【M-c】与 Worker compensatePending 一致：补 enable 成功写"已自动恢复"时间戳
                getSharedPreferences("restart", 0).edit()
                    .putLong(RestartPrefs.AUTO_RESTORED_PREFIX + id, System.currentTimeMillis())
                    .apply()
            } else {
                RestartPrefs.markFailed(this, id)
            }
        }
        if (any) {
            // 补 enable 改变了设置串，同步 tmpSettingValue 防自我触发
            var s = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (s == null) s = ""
            tmpSettingValue = s
        }
    }

    private fun tryEnable(serviceId: String): Boolean {
        return try {
            var cur = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (cur == null) cur = ""
            if (RestartPrefs.containsService(cur, serviceId)) return true
            // 【R3】写入前同步静态镜像为写入后值（与 doDaemon/Worker F1 模式一致）：
            // 补偿恰逢 Worker 重启窗口（disable 后 enable 前）时，观察者把本次变化视为
            // 自己写的而跳过回写，不再把 disabled 中的服务提前 enable
            val newValue = "$serviceId:$cur"
            tmpSettingValue = newValue
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newValue,
            )
            val after = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (after != null) tmpSettingValue = after // 以读回实际值校准镜像
            after != null && RestartPrefs.containsService(after, serviceId)
        } catch (e: Exception) {
            // 【R3】写入异常回滚镜像为实际值，防镜像失真致观察者对后续外部变化误跳过（与 M-e 同源）
            try {
                val actual = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
                tmpSettingValue = actual ?: ""
            } catch (ignored: Exception) {
            }
            false
        }
    }

    private fun countDaemonServices(daemon: String): Int {
        var n = 0
        for (s in RestartPrefs.COLON.split(daemon)) { // 【P6-d】共用 Pattern 类常量
            if (s.isNotEmpty() && s != "null") n++
        }
        return Math.max(1, n)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 【SEVERE 2①】空 daemon 提前 stopSelf 路径未注册，按标志位与 null 判断防崩
        if (receiverRegistered) {
            unregisterReceiver(myReceiver)
            receiverRegistered = false
        }
        if (observerRegistered && mContentOb != null) {
            contentResolver.unregisterContentObserver(mContentOb!!)
            observerRegistered = false
        }
        Toast.makeText(this@daemonService, R.string.toast_daemon_stop, Toast.LENGTH_SHORT).show()
    }

    /** 空 daemon 提前退出路径的前台占位通知（防 startForegroundService 5 秒超时崩溃）【SEVERE 2②】 */
    private fun startForegroundPlaceholder() {
        val b = Notification.Builder(this).setSmallIcon(R.drawable.tile)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_DAEMON,
                getString(R.string.channel_daemon_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            ch.setShowBadge(false)
            nm?.createNotificationChannel(ch)
            b.setChannelId(CHANNEL_DAEMON)
        }
        startForeground(NOTIFY_ID, b.build())
    }

    companion object {
        private const val CHANNEL_DAEMON = "daemon"
        private const val NOTIFY_ID = 1

        /**
         * 【F1】Settings.Secure 写入方共享镜像（daemon 为单实例服务，静态 volatile 保证
         * Worker（同进程后台线程）与 daemon 主线程间的可见性）：写方在写入前后更新此镜像，
         * 观察者据以区分"自己写/外部改"，防 ContentObserver 自触发循环。
         * RestartWorker 重启窗口内直接写此静态字段（disabled/enable 后值），
         * daemon 把窗口内变化视为自己写的而跳过回写，1.5s 干净重启真实发生。
         * 初始化 "" 防静态期 NPE（onCreate 注册观察者前立即以实际值覆盖）。
         *
         * ⚠️ `@JvmField` 而非 `@JvmStatic`：要的是**静态字段**，不是静态访问器。
         * `@Volatile` 不可省（跨线程可见性）。
         */
        @JvmField
        @Volatile
        var tmpSettingValue: String = ""
    }
}
