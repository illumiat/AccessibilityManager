package com.accessibilitymanager;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * per-服务定期重启配置（方案 §四）：enabled + periodMin + lastRestart，
 * SharedPreferences 持久化；pendingEnable 为 per-service 集合（每项附时间戳防陈旧残留）
 * 【盲审修订 P0 / 二轮修订】；enable 失败落盘可感知【二轮修订 P1】。
 */
public final class RestartPrefs {

    /** 周期边界：30 分钟 ~ 30 天（Q15） */
    public static final long MIN_PERIOD_MIN = 30L;
    public static final long MAX_PERIOD_MIN = 30L * 24 * 60;
    /** 默认周期：24 小时（方案未定默认值，取最简实现，见报告标注） */
    public static final long DEFAULT_PERIOD_MIN = 24L * 60;
    /** pendingEnable 残留超过 24h 视为陈旧，清除并标记失败 */
    private static final long PENDING_STALE_MS = 24L * 60 * 60 * 1000;
    /** "已自动恢复"角标：SP key 前缀 + 显示窗口（daemonService 写 / ServiceAdapter 读）【MISSING 11】 */
    public static final String AUTO_RESTORED_PREFIX = "last_auto_restored.";
    public static final long AUTO_RESTORED_WINDOW_MS = 5L * 60 * 1000;
    /** 【P6-d】":" 段切分共用 Pattern 类常量（daemonService.doDaemon/countDaemonServices 复用，免每次 split 重复编译） */
    public static final Pattern COLON = Pattern.compile(":");

    public static class Config {
        public boolean enabled;
        public long periodMin;
        public long lastRestart;
    }

    private RestartPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("restart", 0);
    }

    @Nullable
    public static Config get(Context c, String serviceId) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean(serviceId + ".enabled", false)) return null;
        Config cfg = new Config();
        cfg.enabled = true;
        cfg.periodMin = p.getLong(serviceId + ".period", DEFAULT_PERIOD_MIN);
        cfg.lastRestart = p.getLong(serviceId + ".last", 0);
        return cfg;
    }

    /** 开启配置：lastRestart 初值 = 配置启用时刻【二轮修订】 */
    public static void enable(Context c, String serviceId, long periodMin) {
        sp(c).edit()
                .putBoolean(serviceId + ".enabled", true)
                .putLong(serviceId + ".period", clampPeriod(periodMin))
                .putLong(serviceId + ".last", System.currentTimeMillis())
                .apply();
    }

    public static void disable(Context c, String serviceId) {
        sp(c).edit().putBoolean(serviceId + ".enabled", false).apply();
        clearFailed(c, serviceId);
    }

    public static void setPeriod(Context c, String serviceId, long periodMin) {
        sp(c).edit().putLong(serviceId + ".period", clampPeriod(periodMin)).apply();
    }

    /**
     * 【R1】读取最近一次设定的周期（含未启用态）：disable 只翻转 .enabled、保留 .period，
     * get() 在未启用态返回 null；详情卡"修改周期→关→再开"需以最近设定周期恢复，防新周期被默认值静默回退。
     */
    public static long peekLastPeriod(Context c, String serviceId) {
        return sp(c).getLong(serviceId + ".period", DEFAULT_PERIOD_MIN);
    }

    public static void markRestarted(Context c, String serviceId) {
        sp(c).edit().putLong(serviceId + ".last", System.currentTimeMillis()).apply();
    }

    public static long enabledCount(Context c) {
        long n = 0;
        for (String key : sp(c).getAll().keySet()) {
            if (key.endsWith(".enabled") && sp(c).getBoolean(key, false)) n++;
        }
        return n;
    }

    /** 卸载清理：移除该服务全部配置（含置顶标记）【二轮修订 P2】 */
    public static void removeCompletely(Context c, String serviceId) {
        // 【MAJOR 6】pending 读改写全程持锁，与 daemon/Worker 线程互斥
        synchronized (RestartPrefs.class) {
            SharedPreferences p = sp(c);
            SharedPreferences.Editor e = p.edit()
                    .remove(serviceId + ".enabled")
                    .remove(serviceId + ".period")
                    .remove(serviceId + ".last")
                    .remove(AUTO_RESTORED_PREFIX + serviceId); // 【M-a】补清"已自动恢复"时间戳键，防重装后误显角标
            // 【P3】失败集存于 "failed" StringSet（markFailed M3 持锁实现）；原清除的
            // serviceId+".failed" 是从未写入的死键 → 卸载服务后失败集残留，banner_failed 与
            // 设置页告警常驻误报。改为从 StringSet 按 id 移除（同锁同一次 apply 内完成）。
            Set<String> failed = new HashSet<>(p.getStringSet("failed", new HashSet<>()));
            failed.remove(serviceId);
            e.putStringSet("failed", failed);
            Set<String> pending = new HashSet<>(getPendingEnables(c).keySet());
            pending.remove(serviceId);
            e.putStringSet("pending", serializePending(pending));
            e.remove("pending.ts." + serviceId); // 【MINOR 17】同步删除 pending 时间戳键
            e.apply();
        }
        // 置顶/保活标记在 "data" SP【M4：pruneTop/pruneDaemon 持锁读改写，防与 UI/Worker 并发互踩】
        // 【F2】卸载分支承担 daemon 串清理语义（Worker 成功路径的 pruneDaemon 已删，防误清保活锁）
        pruneDaemon(c, serviceId);
        pruneTop(c, serviceId);
    }

    // ---------- pendingEnable（中断补偿）【盲审修订 P0 / 二轮修订：集合化 + 时间戳】 ----------

    public static void addPendingEnable(Context c, String serviceId) {
        // 【MAJOR 6】pending 读改写全程持锁（集合与时间戳同一次 apply 落盘）
        synchronized (RestartPrefs.class) {
            Map<String, Long> m = getPendingEnables(c);
            m.put(serviceId, System.currentTimeMillis());
            sp(c).edit()
                    .putStringSet("pending", serializePending(m.keySet()))
                    .putLong("pending.ts." + serviceId, System.currentTimeMillis())
                    .apply();
        }
    }

    public static void removePendingEnable(Context c, String serviceId) {
        // 【MAJOR 6】pending 读改写全程持锁
        synchronized (RestartPrefs.class) {
            Map<String, Long> m = getPendingEnables(c);
            m.remove(serviceId);
            sp(c).edit().putStringSet("pending", serializePending(m.keySet()))
                    .remove("pending.ts." + serviceId)
                    .apply();
        }
    }

    /** @return serviceId → 标志写入时间戳 */
    @NonNull
    public static Map<String, Long> getPendingEnables(Context c) {
        // 【MAJOR 6】读侧同样持锁，保证与写侧互斥
        synchronized (RestartPrefs.class) {
            SharedPreferences p = sp(c);
            Map<String, Long> result = new HashMap<>();
            Set<String> raw = p.getStringSet("pending", null);
            if (raw != null) {
                for (String id : raw) {
                    result.put(id, p.getLong("pending.ts." + id, 0));
                }
            }
            return result;
        }
    }

    public static long pendingStaleMs() {
        return PENDING_STALE_MS;
    }

    private static Set<String> serializePending(Set<String> ids) {
        return new HashSet<>(ids);
    }

    // ---------- enable 失败告警【二轮修订 P1】 ----------

    public static void markFailed(Context c, String serviceId) {
        // 【M3】与 pending 一致全程持锁，读改写合并单次 apply（daemon 主线程/Worker/UI 三方并发防丢更新）
        synchronized (RestartPrefs.class) {
            SharedPreferences p = sp(c);
            Set<String> failed = new HashSet<>(p.getStringSet("failed", new HashSet<>()));
            failed.add(serviceId);
            p.edit().putStringSet("failed", failed).apply();
        }
    }

    public static void clearFailed(Context c, String serviceId) {
        // 【M3】与 pending 一致全程持锁，读改写合并单次 apply
        synchronized (RestartPrefs.class) {
            SharedPreferences p = sp(c);
            Set<String> failed = new HashSet<>(p.getStringSet("failed", new HashSet<>()));
            failed.remove(serviceId);
            p.edit().putStringSet("failed", failed).apply();
        }
    }

    @NonNull
    public static Set<String> getFailed(Context c) {
        // 【M3】读侧同样持锁，保证与写侧互斥（与 getPendingEnables 同模式）
        synchronized (RestartPrefs.class) {
            Set<String> failed = sp(c).getStringSet("failed", null);
            return failed != null ? new HashSet<>(failed) : new HashSet<>();
        }
    }

    // ---------- PACKAGE_USAGE_STATS 授权检查【P6-e】 ----------

    /**
     * 【P6-e】appops 授权检查单一实现（原 SettingsFragment / RestartWorker 双实现分叉，
     * 两处统一调用本方法）；经 applicationContext 获取，进程内任意线程/组件可用。
     */
    public static boolean usageStatsGranted(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getApplicationContext()
                    .getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow("android:package_usage_stats",
                    android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 设置串纯函数（Worker 与 daemonService 共用，与 UI 开关逻辑一致） ----------

    /** 向已开启服务串前插目标（与 Worker tryEnable 同构；UI 开关开启路径统一入口）【MAJOR 10】 */
    public static String prependService(String settingValue, String serviceId) {
        return serviceId + ":" + settingValue;
    }

    /** 服务 id 的展开形态（flattenToShortString："pkg/.Cls" → "pkg/pkg.Cls"）；非 "pkg/cls" 形态原样返回 */
    private static String expandedForm(String serviceId) {
        int slash = serviceId.indexOf('/');
        return slash > 0
                ? serviceId.substring(0, slash) + "/" + serviceId.substring(0, slash) + serviceId.substring(slash + 1)
                : serviceId;
    }

    /**
     * 【M1】精确判定 serviceId 是否在设置串中：settingValue 按 ":" 切段，
     * 每段与 serviceId 精确相等，或该段等于展开形态 "pkg/pkg.cls" 时精确相等。
     * 替代原 contains 子串误判（"pkg/.Svc" 是 "pkg/.SvcExtra" 的子串）。
     */
    public static boolean isEnabledIn(String settingValue, String serviceId) {
        if (settingValue == null || settingValue.isEmpty() || serviceId == null || serviceId.isEmpty()) {
            return false;
        }
        String expanded = expandedForm(serviceId);
        for (String seg : settingValue.split(":")) {
            if (seg.isEmpty()) continue;
            if (seg.equals(serviceId) || seg.equals(expanded)) return true;
        }
        return false;
    }

    /** 从已开启服务串中移除目标【M2：按 ":" 段边界过滤重建，替代 replace 子串链（"pkg/.SvcX:pkg/.Svc" 不再残留 "X:" 垃圾段）】 */
    public static String removeService(String settingValue, String serviceId) {
        String expanded = expandedForm(serviceId);
        List<String> kept = new ArrayList<>();
        for (String seg : settingValue.split(":")) {
            if (seg.isEmpty()) continue;
            if (seg.equals(serviceId) || seg.equals(expanded)) continue;
            kept.add(seg);
        }
        return String.join(":", kept);
    }

    public static boolean containsService(String settingValue, String serviceId) {
        // 【M1】收口到本类 isEnabledIn（原经 ServiceAdapter.isEnabledIn 中转，收口后反向会循环调用）
        return isEnabledIn(settingValue, serviceId);
    }

    public static long clampPeriod(long periodMin) {
        return Math.max(MIN_PERIOD_MIN, Math.min(MAX_PERIOD_MIN, periodMin));
    }

    // ---------- "data" SP daemon/top 串收口【M4：daemon 主线程 / Worker / UI 三方并发，读改写全程持锁单次 apply】 ----------

    private static SharedPreferences dataSp(Context c) {
        return c.getApplicationContext().getSharedPreferences("data", 0);
    }

    private static List<String> splitIds(String colonJoined) {
        List<String> out = new ArrayList<>();
        for (String id : colonJoined.split(":")) {
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** 切换保活锁定（daemon 串）；@return 切换后是否处于锁定态 */
    public static boolean toggleDaemon(Context c, String serviceId) {
        synchronized (RestartPrefs.class) {
            SharedPreferences data = dataSp(c);
            List<String> ids = splitIds(data.getString("daemon", ""));
            boolean nowOn;
            if (ids.contains(serviceId)) {
                ids.remove(serviceId);
                nowOn = false;
            } else {
                ids.add(0, serviceId); // 前插，与旧 UI 语义一致（新锁定项排最前）
                nowOn = true;
            }
            data.edit().putString("daemon", String.join(":", ids)).apply();
            return nowOn;
        }
    }

    /** 切换置顶（top 串）；@return 切换后是否处于置顶态 */
    public static boolean toggleTop(Context c, String serviceId) {
        synchronized (RestartPrefs.class) {
            SharedPreferences data = dataSp(c);
            List<String> ids = splitIds(data.getString("top", ""));
            boolean nowOn;
            if (ids.contains(serviceId)) {
                ids.remove(serviceId);
                nowOn = false;
            } else {
                ids.add(0, serviceId); // 前插：sortDisplay 的 indexOf 排序依赖 top 串顺序
                nowOn = true;
            }
            data.edit().putString("top", String.join(":", ids)).apply();
            return nowOn;
        }
    }

    /** 清理 daemon 串中已失效项（按 ":" 段精确移除，无子串误伤） */
    public static void pruneDaemon(Context c, String serviceId) {
        synchronized (RestartPrefs.class) {
            SharedPreferences data = dataSp(c);
            List<String> ids = splitIds(data.getString("daemon", ""));
            if (!ids.contains(serviceId)) return;
            ids.remove(serviceId);
            data.edit().putString("daemon", String.join(":", ids)).apply();
        }
    }

    /** 清理 top 串中已失效项（按 ":" 段精确移除，无子串误伤） */
    public static void pruneTop(Context c, String serviceId) {
        synchronized (RestartPrefs.class) {
            SharedPreferences data = dataSp(c);
            List<String> ids = splitIds(data.getString("top", ""));
            if (!ids.contains(serviceId)) return;
            ids.remove(serviceId);
            data.edit().putString("top", String.join(":", ids)).apply();
        }
    }
}
