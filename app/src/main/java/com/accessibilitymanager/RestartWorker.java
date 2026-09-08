package com.accessibilitymanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
 */
public class RestartWorker extends Worker {

    private static final String UNIQUE_WORK = "restart_periodic";
    private static final int NOTIFY_ID = 2;
    private static final String CHANNEL_RESTART = "restart_log";

    public RestartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** 幂等调度：全局唯一周期任务（App.onCreate 调用） */
    public static void schedule(Context context) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(RestartWorker.class,
                30, TimeUnit.MINUTES, 15, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        compensatePending(ctx);
        executeDue(ctx);
        return Result.success();
    }

    /** 开头检查 pendingEnable 残留，立即补 enable【盲审修订 P0】 */
    private void compensatePending(Context ctx) {
        for (Map.Entry<String, Long> e : RestartPrefs.getPendingEnables(ctx).entrySet()) {
            String id = e.getKey();
            long age = System.currentTimeMillis() - e.getValue();
            if (e.getValue() > 0 && age > RestartPrefs.pendingStaleMs()) {
                RestartPrefs.removePendingEnable(ctx, id);
                RestartPrefs.markFailed(ctx, id);
                continue;
            }
            String cur = readSettingValue(ctx);
            if (RestartPrefs.containsService(cur, id)) {
                RestartPrefs.removePendingEnable(ctx, id);
                continue;
            }
            if (tryEnable(ctx, id)) {
                RestartPrefs.removePendingEnable(ctx, id);
                // 【P2】与 daemon doDaemon 一致：补 enable 成功也写"已自动恢复"时间戳（ServiceAdapter 5 分钟角标复用）
                ctx.getSharedPreferences("restart", 0).edit()
                        .putLong(RestartPrefs.AUTO_RESTORED_PREFIX + id, System.currentTimeMillis())
                        .apply();
            } else {
                RestartPrefs.markFailed(ctx, id);
            }
        }
    }

    private void executeDue(Context ctx) {
        SharedPreferences data = ctx.getSharedPreferences("data", 0);
        List<String> ids = new ArrayList<>();
        // 【M-d】循环外取一次 SP 快照并遍历 entry 集合，避免循环内重复 getSharedPreferences
        for (Map.Entry<String, ?> entry : ctx.getSharedPreferences("restart", 0).getAll().entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(".enabled") && Boolean.TRUE.equals(entry.getValue())) {
                ids.add(key.substring(0, key.length() - ".enabled".length()));
            }
        }
        if (ids.isEmpty()) return;

        PackageManager pm = ctx.getPackageManager();
        List<String> restartedLabels = new ArrayList<>();
        for (String id : ids) {
            // 卸载清理（含置顶标记）【二轮修订 P2】
            int slash = id.indexOf('/');
            String pkg = slash > 0 ? id.substring(0, slash) : id;
            if (!isPackageInstalled(pm, pkg)) {
                RestartPrefs.removeCompletely(ctx, id);
                continue;
            }

            RestartPrefs.Config cfg = RestartPrefs.get(ctx, id);
            if (cfg == null) continue;
            long now = System.currentTimeMillis();
            // 到期检查：now − lastRestart ≥ period；过期即每周期检查执行条件（2× 兜底条款）
            if (now - cfg.lastRestart < cfg.periodMin * 60_000L) continue;
            if (shouldSkipForFocus(ctx, pkg)) continue;

            // disable 前先落盘 pendingEnable（中断补偿）
            RestartPrefs.addPendingEnable(ctx, id);
            String cur = readSettingValue(ctx);
            String disabled = RestartPrefs.removeService(cur, id);
            // 【F1】重启窗口协调：disable 写入前先把静态镜像置为 disabled 值，
            // daemon 观察者（同进程）将本次变化视为自己写的而跳过回写，1.5s 窗口内干净重启真实发生
            daemonService.tmpSettingValue = disabled;
            writeSettingValue(ctx, disabled);
            // 【F4】写 disable 后重读确认生效；未生效（如 SecurityException 静默失败）→ markFailed
            // 可感知并跳过本轮（不 markRestarted、不发通知），防 enable 幂等命中误报"已重启"并推进 lastRestart
            if (RestartPrefs.containsService(readSettingValue(ctx), id)) {
                RestartPrefs.markFailed(ctx, id);
                // 镜像回滚为实际值，防 daemon 观察者对后续外部变化误跳过
                daemonService.tmpSettingValue = readSettingValue(ctx);
                continue;
            }
            SystemClock.sleep(1500);

            boolean ok = tryEnable(ctx, id);
            if (ok) {
                // 【F1】enable 读回确认成功后同步静态镜像为启用后实际值
                daemonService.tmpSettingValue = readSettingValue(ctx);
                RestartPrefs.removePendingEnable(ctx, id);
                RestartPrefs.markRestarted(ctx, id);
                RestartPrefs.clearFailed(ctx, id);
                restartedLabels.add(labelOf(pm, pkg, id));
            } else {
                // enable 失败：pendingEnable 保留供 daemon 补偿 + 落盘告警【二轮修订 P1】
                RestartPrefs.markFailed(ctx, id);
            }
            // 【F2】原成功路径无条件 pruneDaemon 会误清保活锁（锁定服务首次周期重启后 daemon
            // 不再恢复、UI 镜像仍显示锁定），已删除；daemon 串清理由 RestartPrefs.removeCompletely
            // （卸载分支）统一承担
        }

        if (!restartedLabels.isEmpty() && data.getBoolean("restart_notify", true)) {
            notifyMerged(ctx, restartedLabels);
        }
    }

    /**
     * v3.1 执行条件：!isInteractive() → 直接执行（不经焦点判定）；
     * 亮屏 → 焦点判定（appops 每次 Worker 校验，未授权降级 isInteractive）。
     */
    private boolean shouldSkipForFocus(Context ctx, String targetPkg) {
        PowerManager power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean interactive = power.isInteractive();
        if (!interactive) return false; // 灭屏直接执行【三轮 P0-2 修正】

        // 亮屏：查 24h 回溯窗内最近一条 MOVE_TO_FOREGROUND
        String latest = latestForegroundPackage(ctx);
        if (latest == null) {
            // 无事件/查询异常 → 无法判定 → 回退 isInteractive 判据【三轮 P1 修正】
            return interactive;
        }
        if (latest.equals(targetPkg)) return true;
        // 执行前二次查询确认（UsageStats 落库有 Doze 合批延迟）【三轮 P1 修正】
        String latest2 = latestForegroundPackage(ctx);
        if (latest2 == null) return interactive;
        return latest2.equals(targetPkg);
    }

    /** @return 24h 内最近 MOVE_TO_FOREGROUND 包名；无事件/异常返回 null（调用方回退） */
    private String latestForegroundPackage(Context ctx) {
        if (!RestartPrefs.usageStatsGranted(ctx)) return null; // 降级授权【三轮 P1 修正】【P6-e 收口 RestartPrefs 单一实现】
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - TimeUnit.HOURS.toMillis(24), now);
            UsageEvents.Event ev = new UsageEvents.Event();
            String latest = null;
            long latestTime = -1;
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                // MOVE_TO_FOREGROUND 与 ACTIVITY_RESUMED 同值，minSdk 24 全覆盖【三轮 P0-1 修正】
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                        && ev.getTimeStamp() > latestTime) {
                    latestTime = ev.getTimeStamp();
                    latest = ev.getPackageName();
                }
            }
            return latest;
        } catch (Exception e) {
            return null;
        }
    }

    /** 写回 enable（复用 tmpSettingValue 同构逻辑：serviceId 前插）；@return 是否确认生效 */
    private boolean tryEnable(Context ctx, String id) {
        try {
            String cur = readSettingValue(ctx);
            if (RestartPrefs.containsService(cur, id)) return true;
            String newValue = id + ":" + cur;
            // 【P6-c】写设置后同步静态镜像（与 F1 主路径、daemon tryEnable R3 口径统一）：
            // 补 enable 的变化对 daemon 观察者视为自己写的而跳过回写，
            // 消除补 enable 成功后 daemon 多跑一轮无谓 doDaemon；读回后以实际值校准
            daemonService.tmpSettingValue = newValue;
            writeSettingValue(ctx, newValue);
            String after = readSettingValue(ctx);
            if (!after.isEmpty()) daemonService.tmpSettingValue = after;
            return RestartPrefs.containsService(after, id);
        } catch (Exception e) {
            // 【P6-c】异常路径镜像回滚为实际值（与 daemon tryEnable 的 R3 回滚对称），防失真
            try {
                daemonService.tmpSettingValue = readSettingValue(ctx);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private String readSettingValue(Context ctx) {
        String s = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s == null ? "" : s;
    }

    private void writeSettingValue(Context ctx, String value) {
        try {
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
        } catch (Exception ignored) {
            // 【M-b】扩为 Exception：其他运行时异常同样不中断本轮；
            // 失败可感知（markFailed）由调用方读回确认/异常路径处理
        }
    }

    private boolean isPackageInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String labelOf(PackageManager pm, String pkg, String serviceId) {
        try {
            return String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (PackageManager.NameNotFoundException e) {
            return serviceId;
        }
    }

    /** 合并静默日志通知：每次 Worker 通知合并为一条（IMPORTANCE_LOW，可在设置关） */
    private void notifyMerged(Context ctx, List<String> labels) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_RESTART,
                    ctx.getString(R.string.channel_restart_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_RESTART)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.tile)
                .setContentTitle(ctx.getString(R.string.notification_restart_title))
                .setContentText(joinLabels(labels))
                .setStyle(new Notification.BigTextStyle().bigText(
                        ctx.getString(R.string.notification_restart_title) + "\n" + joinLabels(labels)))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 0,
                        new Intent(ctx, MainActivity.class),
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                                ? PendingIntent.FLAG_IMMUTABLE : 0));
        nm.notify(NOTIFY_ID, b.build());
    }

    private String joinLabels(List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(labels.get(i));
        }
        return sb.toString();
    }
}
