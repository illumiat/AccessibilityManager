package com.accessibilitymanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 定期重启 Worker（方案 §四 v3.1 全部条款）：
 * - 全局单 PeriodicWorkRequest(30min) + flexInterval 收窄顺延抖动（Q16）
 * - 到期检查（now−lastRestart ≥ period；超 2× 周期后每周期检查，永不干扰使用中的服务）
 * - 执行条件【v3.1 定稿】：!isInteractive() || latestResumedEvent != target
 *   · 统一 MOVE_TO_FOREGROUND（与 ACTIVITY_RESUMED 同值，minSdk 24 全覆盖）
 *   · 灭屏直接执行，不经焦点判定；查询失败/无事件 → 回退 isInteractive 判据
 *   · 执行前二次查询确认（UsageStats 落库有 Doze 合批延迟）；appops 每次 Worker 校验
 * - 中断补偿：disable 前落盘 pendingEnable → sleep 1.5s → enable → 清标志；
 *   开头检查残留立即补 enable
 * - enable 失败落盘告警【二轮修订 P1】；卸载清理（含置顶标记）【二轮修订 P2】
 * - 合并静默日志通知（IMPORTANCE_LOW，可在设置关）【二轮修订】
 * - 【F1】重启窗口与 daemonService.tmpSettingValue 静态镜像协调：disable 前/enable 读回确认后
 *   更新镜像，daemon 观察者把窗口内变化视为自己写的而跳过回写（防秒级回写吞掉真实重启）
 * - 【F2】成功路径不再 pruneDaemon（防误清保活锁，清理由 removeCompletely 卸载分支承担）
 * - 【F4】写 disable 后重读确认生效；未生效 → markFailed 可感知并跳过本轮（不 markRestarted、不发通知）
 *
 * ## 转换说明（行为不变）
 *
 * 1. **`Result` 必须写全限定 `ListenableWorker.Result`**：Java 里 `Result` 经继承进入作用域，
 *    Kotlin 不保证，且 Kotlin 内建 `kotlin.Result` 会遮蔽 —— 写裸 `Result` 会解析到错误的类型。
 * 2. `SystemClock.sleep(1500)` **原样保留**（阻塞 worker 线程）；**不得**改成 `delay`/协程，
 *    那会改变线程模型与 1.5s 窗口的语义。
 * 3. `Boolean.TRUE.equals(value)` 原样保留（值来自 `SharedPreferences.getAll()` 的 `Object`，
 *    直接 `== true` 会引入装箱比较的歧义）。
 * 4. 静态镜像 `daemonService.tmpSettingValue` 的四处同步点**一处都不能少**：
 *    disable 前、disable 读回失败回滚、enable 读回确认后、tryEnable 异常回滚。
 */
class RestartWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): ListenableWorker.Result {
        val ctx = applicationContext
        compensatePending(ctx)
        executeDue(ctx)
        return ListenableWorker.Result.success()
    }

    /** 开头检查 pendingEnable 残留，立即补 enable【盲审修订 P0】 */
    private fun compensatePending(ctx: Context) {
        for ((id, ts) in RestartPrefs.getPendingEnables(ctx)) {
            val age = System.currentTimeMillis() - ts
            if (ts > 0 && age > RestartPrefs.pendingStaleMs()) {
                RestartPrefs.removePendingEnable(ctx, id)
                RestartPrefs.markFailed(ctx, id)
                continue
            }
            val cur = readSettingValue(ctx)
            if (RestartPrefs.containsService(cur, id)) {
                RestartPrefs.removePendingEnable(ctx, id)
                continue
            }
            if (tryEnable(ctx, id)) {
                RestartPrefs.removePendingEnable(ctx, id)
                // 【P2】与 daemon doDaemon 一致：补 enable 成功也写"已自动恢复"时间戳（ServiceAdapter 5 分钟角标复用）
                ctx.getSharedPreferences("restart", 0).edit()
                    .putLong(RestartPrefs.AUTO_RESTORED_PREFIX + id, System.currentTimeMillis())
                    .apply()
            } else {
                RestartPrefs.markFailed(ctx, id)
            }
        }
    }

    private fun executeDue(ctx: Context) {
        val data: SharedPreferences = ctx.getSharedPreferences("data", 0)
        val ids = ArrayList<String>()
        // 【M-d】循环外取一次 SP 快照并遍历 entry 集合，避免循环内重复 getSharedPreferences
        for ((key, value) in ctx.getSharedPreferences("restart", 0).all) {
            if (key.endsWith(".enabled") && java.lang.Boolean.TRUE == value) {
                ids.add(key.substring(0, key.length - ".enabled".length))
            }
        }
        if (ids.isEmpty()) return

        val pm = ctx.packageManager
        val restartedLabels = ArrayList<String>()
        for (id in ids) {
            // 卸载清理（含置顶标记）【二轮修订 P2】
            val slash = id.indexOf('/')
            val pkg = if (slash > 0) id.substring(0, slash) else id
            if (!isPackageInstalled(pm, pkg)) {
                RestartPrefs.removeCompletely(ctx, id)
                continue
            }

            val cfg = RestartPrefs.get(ctx, id) ?: continue
            val now = System.currentTimeMillis()
            // 到期检查：now − lastRestart ≥ period；过期即每周期检查执行条件（2× 兜底条款）
            if (now - cfg.lastRestart < cfg.periodMin * 60_000L) continue
            if (shouldSkipForFocus(ctx, pkg)) continue

            // disable 前先落盘 pendingEnable（中断补偿）
            RestartPrefs.addPendingEnable(ctx, id)
            val cur = readSettingValue(ctx)
            val disabled = RestartPrefs.removeService(cur, id)
            // 【F1】重启窗口协调：disable 写入前先把静态镜像置为 disabled 值，
            // daemon 观察者（同进程）将本次变化视为自己写的而跳过回写，1.5s 窗口内干净重启真实发生
            daemonService.tmpSettingValue = disabled
            writeSettingValue(ctx, disabled)
            // 【F4】写 disable 后重读确认生效；未生效（如 SecurityException 静默失败）→ markFailed
            // 可感知并跳过本轮（不 markRestarted、不发通知），防 enable 幂等命中误报"已重启"并推进 lastRestart
            if (RestartPrefs.containsService(readSettingValue(ctx), id)) {
                RestartPrefs.markFailed(ctx, id)
                // 镜像回滚为实际值，防 daemon 观察者对后续外部变化误跳过
                daemonService.tmpSettingValue = readSettingValue(ctx)
                continue
            }
            SystemClock.sleep(1500)

            val ok = tryEnable(ctx, id)
            if (ok) {
                // 【F1】enable 读回确认成功后同步静态镜像为启用后实际值
                daemonService.tmpSettingValue = readSettingValue(ctx)
                RestartPrefs.removePendingEnable(ctx, id)
                RestartPrefs.markRestarted(ctx, id)
                RestartPrefs.clearFailed(ctx, id)
                restartedLabels.add(labelOf(pm, pkg, id))
            } else {
                // enable 失败：pendingEnable 保留供 daemon 补偿 + 落盘告警【二轮修订 P1】
                RestartPrefs.markFailed(ctx, id)
            }
            // 【F2】原成功路径无条件 pruneDaemon 会误清保活锁（锁定服务首次周期重启后 daemon
            // 不再恢复、UI 镜像仍显示锁定），已删除；daemon 串清理由 RestartPrefs.removeCompletely
            // （卸载分支）统一承担
        }

        if (restartedLabels.isNotEmpty() && data.getBoolean("restart_notify", true)) {
            notifyMerged(ctx, restartedLabels)
        }
    }

    /**
     * v3.1 执行条件：!isInteractive() → 直接执行（不经焦点判定）；
     * 亮屏 → 焦点判定（appops 每次 Worker 校验，未授权降级 isInteractive）。
     */
    private fun shouldSkipForFocus(ctx: Context, targetPkg: String): Boolean {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val interactive = power.isInteractive
        if (!interactive) return false // 灭屏直接执行【三轮 P0-2 修正】

        // 亮屏：查 24h 回溯窗内最近一条 MOVE_TO_FOREGROUND
        val latest = latestForegroundPackage(ctx)
            // 无事件/查询异常 → 无法判定 → 回退 isInteractive 判据【三轮 P1 修正】
            ?: return interactive
        if (latest == targetPkg) return true
        // 执行前二次查询确认（UsageStats 落库有 Doze 合批延迟）【三轮 P1 修正】
        val latest2 = latestForegroundPackage(ctx) ?: return interactive
        return latest2 == targetPkg
    }

    /** @return 24h 内最近 MOVE_TO_FOREGROUND 包名；无事件/异常返回 null（调用方回退） */
    private fun latestForegroundPackage(ctx: Context): String? {
        // 降级授权【三轮 P1 修正】【P6-e 收口 RestartPrefs 单一实现】
        if (!RestartPrefs.usageStatsGranted(ctx)) return null
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager?
                ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - TimeUnit.HOURS.toMillis(24), now)
            val ev = UsageEvents.Event()
            var latest: String? = null
            var latestTime = -1L
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                // MOVE_TO_FOREGROUND 与 ACTIVITY_RESUMED 同值，minSdk 24 全覆盖【三轮 P0-1 修正】
                if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && ev.timeStamp > latestTime) {
                    latestTime = ev.timeStamp
                    latest = ev.packageName
                }
            }
            latest
        } catch (e: Exception) {
            null
        }
    }

    /** 写回 enable（复用 tmpSettingValue 同构逻辑：serviceId 前插）；@return 是否确认生效 */
    private fun tryEnable(ctx: Context, id: String): Boolean {
        return try {
            val cur = readSettingValue(ctx)
            if (RestartPrefs.containsService(cur, id)) return true
            val newValue = "$id:$cur"
            // 【P6-c】写设置后同步静态镜像（与 F1 主路径、daemon tryEnable R3 口径统一）：
            // 补 enable 的变化对 daemon 观察者视为自己写的而跳过回写，
            // 消除补 enable 成功后 daemon 多跑一轮无谓 doDaemon；读回后以实际值校准
            daemonService.tmpSettingValue = newValue
            writeSettingValue(ctx, newValue)
            val after = readSettingValue(ctx)
            if (after.isNotEmpty()) daemonService.tmpSettingValue = after
            RestartPrefs.containsService(after, id)
        } catch (e: Exception) {
            // 【P6-c】异常路径镜像回滚为实际值（与 daemon tryEnable 的 R3 回滚对称），防失真
            try {
                daemonService.tmpSettingValue = readSettingValue(ctx)
            } catch (ignored: Exception) {
            }
            false
        }
    }

    private fun readSettingValue(ctx: Context): String {
        val s = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return s ?: ""
    }

    private fun writeSettingValue(ctx: Context, value: String) {
        try {
            Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                value,
            )
        } catch (ignored: Exception) {
            // 【M-b】扩为 Exception：其他运行时异常同样不中断本轮；
            // 失败可感知（markFailed）由调用方读回确认/异常路径处理
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun labelOf(pm: PackageManager, pkg: String, serviceId: String): String {
        return try {
            // 保持 `String.valueOf` 原义：null 得 "null" 而非 NPE（`toString()` 会 NPE，属行为变更）
            java.lang.String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm))
        } catch (e: PackageManager.NameNotFoundException) {
            serviceId
        }
    }

    /** 合并静默日志通知：每次 Worker 通知合并为一条（IMPORTANCE_LOW，可在设置关） */
    private fun notifyMerged(ctx: Context, labels: List<String>) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (nm == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_RESTART,
                ctx.getString(R.string.channel_restart_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val b: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL_RESTART)
        } else {
            Notification.Builder(ctx)
        }
        b.setSmallIcon(R.drawable.tile)
            .setContentTitle(ctx.getString(R.string.notification_restart_title))
            .setContentText(joinLabels(labels))
            .setStyle(
                Notification.BigTextStyle().bigText(
                    ctx.getString(R.string.notification_restart_title) + "\n" + joinLabels(labels),
                ),
            )
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, 0,
                    Intent(ctx, MainActivity::class.java),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
                ),
            )
        nm.notify(NOTIFY_ID, b.build())
    }

    private fun joinLabels(labels: List<String>): String {
        val sb = StringBuilder()
        for (i in labels.indices) {
            if (i > 0) sb.append('\n')
            sb.append(labels[i])
        }
        return sb.toString()
    }

    companion object {
        private const val UNIQUE_WORK = "restart_periodic"
        private const val NOTIFY_ID = 2
        private const val CHANNEL_RESTART = "restart_log"

        /** 幂等调度：全局唯一周期任务（App.onCreate 调用） */
        @JvmStatic
        fun schedule(context: Context) {
            val req = PeriodicWorkRequest.Builder(
                RestartWorker::class.java,
                30, TimeUnit.MINUTES, 15, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }

        /** 【惰性调度】全部定期重启配置关闭/清理后取消周期任务，恢复零主动唤醒 */
        @JvmStatic
        fun cancelIfIdle(context: Context) {
            if (RestartPrefs.enabledCount(context) == 0L) {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
            }
        }
    }
}
