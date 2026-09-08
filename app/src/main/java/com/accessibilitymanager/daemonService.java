package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 前台守护服务（方案 §五）：
 * - 保留事件驱动、无轮询模型
 * - 常驻通知降 IMPORTANCE_LOW + 正经文案（"无障碍服务保活中 · N 个服务受保护"）
 * - onCreate/onStartCommand 开头检查 RestartPrefs 的 pendingEnable 残留并补 enable【盲审修订 P0】
 * - doDaemon() NPE 防御：NameNotFoundException 不再吞后裸调 loadLabel
 */
public class daemonService extends Service {

    private static final String CHANNEL_DAEMON = "daemon";
    private static final int NOTIFY_ID = 1;

    private SettingsValueChangeContentObserver mContentOb;
    /** onDestroy 卸载防崩标志位：空 daemon 提前 stopSelf 路径未注册 receiver/observer【SEVERE 2】 */
    private boolean receiverRegistered;
    private boolean observerRegistered;
    SharedPreferences sp;
    Notification.Builder notification;
    NotificationManager systemService;
    /**
     * 【F1】Settings.Secure 写入方共享镜像（daemon 为单实例服务，静态 volatile 保证
     * Worker（同进程后台线程）与 daemon 主线程间的可见性）：写方在写入前后更新此镜像，
     * 观察者据以区分"自己写/外部改"，防 ContentObserver 自触发循环。
     * RestartWorker 重启窗口内直接写此静态字段（disabled/enable 后值），
     * daemon 把窗口内变化视为自己写的而跳过回写，1.5s 干净重启真实发生。
     * 初始化 "" 防静态期 NPE（onCreate 注册观察者前立即以实际值覆盖）。
     */
    public static volatile String tmpSettingValue = "";
    List<String> l;
    PackageManager packageManager;

    final private BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String set = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (set == null) set = "";
            if (tmpSettingValue.equals(set)) return;
            doDaemon(set);
        }
    };

    //自定义一个内容监视器
    class SettingsValueChangeContentObserver extends ContentObserver {
        public SettingsValueChangeContentObserver() {
            // 【M-a】显式主线程 Looper：空参 new Handler() 依赖隐式当前线程 Looper，语义不明确
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) s = "";
            //如果这俩相等，说明本次变动是APP自己改的。于是就不需要做处理。
            if (tmpSettingValue.equals(s)) return;
            doDaemon(s);
        }
    }

    private void doDaemon(String s) {
        String list = sp.getString("daemon", "");
        String[] serviceNames = RestartPrefs.COLON.split(list); // 【P6-d】共用 Pattern 类常量
        StringBuilder add = new StringBuilder();
        StringBuilder add1 = new StringBuilder();
        List<String> restored = new ArrayList<>(); // 【MISSING 11】本次自动恢复的服务 id
        for (String serviceName : serviceNames) {
            // 【M1】启用判定收口 RestartPrefs.isEnabledIn（":" 切段精确匹配，消除 contains 子串误判）
            if (serviceName == null || serviceName.equals("null") || serviceName.length() == 0
                    || RestartPrefs.isEnabledIn(s, serviceName) || !l.contains(serviceName))
                continue;

            // 【R2】防御不含"/"的异常段（历史/异常持久化数据）：indexOf=-1 会越界崩溃前台服务，
            // 直接跳过并按段清理守护串（pruneDaemon 内部含 contains 检查，非守护项为无副作用空操作）
            int slash = serviceName.indexOf('/');
            if (slash <= 0) {
                RestartPrefs.pruneDaemon(daemonService.this, serviceName);
                continue;
            }
            // 【MISSING 15】标签查询并入 IconCache 静态缓存层（同进程直调，miss 后台 load）
            String packageLabel = IconCache.packageLabel(packageManager,
                    serviceName.substring(0, slash));
            add.append(serviceName).append(":");
            add1.append(packageLabel).append("\n");
            restored.add(serviceName);
            if (sp.getBoolean("toast", true))
                Toast.makeText(daemonService.this, getString(R.string.toast_keep_alive, packageLabel), Toast.LENGTH_SHORT).show();
        }
        if (add.length() > 0) {
            tmpSettingValue = add + s;
            // 【S3】WRITE_SECURE_SETTINGS 缺失时 putString 抛 SecurityException，不得崩溃前台服务：
            // 失败即 return 停止本次恢复循环。
            // （指令原文 SecurityException|Exception 多捕获在 Java 中非法——Exception 已含前者，等价写为单捕获 Exception）
            // 【F5】恢复失败必须可感知：对本轮 restored 列表逐个 markFailed（方案 §四 三重可感知硬约束），
            // 修复 daemon-only 服务恢复失败永久静默的问题（原仅 return 不 markFailed）
            try {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
            } catch (Exception e) {
                for (String id : restored) {
                    RestartPrefs.markFailed(this, id);
                }
                // 【P6-b】写失败回滚镜像为读取到的实际值（与 tryEnable 的 R3 回滚对称），
                // 防镜像失真致观察者对后续外部变化误跳过
                try {
                    String actual = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    tmpSettingValue = actual != null ? actual : "";
                } catch (Exception ignored) {
                }
                return;
            }
            // 【MISSING 11】自动恢复成功 → 写时间戳标记（ServiceAdapter 5 分钟内显示"已自动恢复"角标）
            long now = System.currentTimeMillis();
            SharedPreferences.Editor mark = getSharedPreferences("restart", 0).edit();
            for (String id : restored) {
                mark.putLong(RestartPrefs.AUTO_RESTORED_PREFIX + id, now);
            }
            mark.apply();
            // 【M1-r10】写成功后读回实际值校准镜像（写 → 读回校准三段式，与 tryEnable/Worker 口径一致），
            // 防止观察者对后续外部变化误跳过
            String after = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (after != null) tmpSettingValue = after;
            notification.setContentText(add1 + getString(R.string.notification_keep_time,
                    new SimpleDateFormat("H:mm ss秒", Locale.getDefault()).format(Calendar.getInstance().getTime())))
                    .setContentTitle(getString(R.string.notification_keep_title));
            // 【M-d】系统服务可能取不到，判 null 防 NPE
            if (systemService != null) systemService.notify(NOTIFY_ID, notification.build());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("data", 0);
        if (sp.getString("daemon", "").length() == 0) {
            // 【SEVERE 2②】API 26+ 经 startForegroundService 拉起时必须先 startForeground 再 stopSelf，
            // 否则 5 秒内未进入前台触发 ForegroundServiceDidNotStartInTimeException
            startForegroundPlaceholder();
            stopSelf();
            return;
        }
        packageManager = getPackageManager();
        Toast.makeText(daemonService.this, R.string.toast_daemon_start, Toast.LENGTH_SHORT).show();
        List<AccessibilityServiceInfo> list = ((AccessibilityManager) getApplicationContext()
                .getSystemService(Context.ACCESSIBILITY_SERVICE)).getInstalledAccessibilityServiceList();
        l = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            l.add(list.get(i).getId());
        }
        //注册监视器，读取当前设置项并存到tmpSettingValue
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);
        observerRegistered = true;
        tmpSettingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (tmpSettingValue == null) tmpSettingValue = "";

        registerReceiver(myReceiver, new IntentFilter("android.intent.action.SCREEN_ON"));
        receiverRegistered = true;

        //发送前台通知：IMPORTANCE_LOW + 正经文案
        int count = countDaemonServices(sp.getString("daemon", ""));
        notification = new Notification.Builder(this)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentTitle(getString(R.string.notification_daemon_title, count));
        systemService = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notification
                    .setSmallIcon(Icon.createWithResource(this, R.drawable.tile))
                    .setContentIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class),
                            PendingIntent.FLAG_IMMUTABLE));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_DAEMON,
                    getString(R.string.channel_daemon_name), NotificationManager.IMPORTANCE_LOW);
            notificationChannel.enableLights(false);
            notificationChannel.setShowBadge(false);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            // 【M-d】系统服务可能取不到，判 null 防 NPE
            if (systemService != null) systemService.createNotificationChannel(notificationChannel);
            notification.setChannelId(CHANNEL_DAEMON);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        startForeground(NOTIFY_ID, notification.build());

        //先检查 pendingEnable 残留并补 enable【盲审修订 P0】，再做一次保活
        compensatePendingEnables();
        doDaemon(tmpSettingValue);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 服务被拉起/重启时同样检查残留【盲审修订 P0】
        compensatePendingEnables();
        return super.onStartCommand(intent, flags, startId);
    }

    /** 检查 RestartPrefs 的 pendingEnable 残留并补 enable【盲审修订 P0】 */
    private void compensatePendingEnables() {
        boolean any = false;
        for (Map.Entry<String, Long> e : RestartPrefs.getPendingEnables(this).entrySet()) {
            any = true;
            String id = e.getKey();
            long age = System.currentTimeMillis() - e.getValue();
            if (e.getValue() > 0 && age > RestartPrefs.pendingStaleMs()) {
                RestartPrefs.removePendingEnable(this, id);
                RestartPrefs.markFailed(this, id);
                continue;
            }
            String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            if (RestartPrefs.containsService(cur, id)) {
                RestartPrefs.removePendingEnable(this, id);
                continue;
            }
            boolean ok = tryEnable(id);
            if (ok) {
                RestartPrefs.removePendingEnable(this, id);
                // 【M-c】与 Worker compensatePending 一致：补 enable 成功写"已自动恢复"时间戳
                getSharedPreferences("restart", 0).edit()
                        .putLong(RestartPrefs.AUTO_RESTORED_PREFIX + id, System.currentTimeMillis())
                        .apply();
            } else {
                RestartPrefs.markFailed(this, id);
            }
        }
        if (any) {
            // 补 enable 改变了设置串，同步 tmpSettingValue 防自我触发
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) s = "";
            tmpSettingValue = s;
        }
    }

    private boolean tryEnable(String serviceId) {
        try {
            String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            if (RestartPrefs.containsService(cur, serviceId)) return true;
            // 【R3】写入前同步静态镜像为写入后值（与 doDaemon/Worker F1 模式一致）：
            // 补偿恰逢 Worker 重启窗口（disable 后 enable 前）时，观察者把本次变化视为
            // 自己写的而跳过回写，不再把 disabled 中的服务提前 enable
            String newValue = serviceId + ":" + cur;
            tmpSettingValue = newValue;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue);
            String after = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (after != null) tmpSettingValue = after; // 以读回实际值校准镜像
            return after != null && RestartPrefs.containsService(after, serviceId);
        } catch (Exception e) {
            // 【R3】写入异常回滚镜像为实际值，防镜像失真致观察者对后续外部变化误跳过（与 M-e 同源）
            try {
                String actual = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                tmpSettingValue = actual != null ? actual : "";
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private int countDaemonServices(String daemon) {
        int n = 0;
        for (String s : RestartPrefs.COLON.split(daemon)) { // 【P6-d】共用 Pattern 类常量
            if (s.length() > 0 && !s.equals("null")) n++;
        }
        return Math.max(1, n);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 【SEVERE 2①】空 daemon 提前 stopSelf 路径未注册，按标志位与 null 判断防崩
        if (receiverRegistered) {
            unregisterReceiver(myReceiver);
            receiverRegistered = false;
        }
        if (observerRegistered && mContentOb != null) {
            getContentResolver().unregisterContentObserver(mContentOb);
            observerRegistered = false;
        }
        Toast.makeText(daemonService.this, R.string.toast_daemon_stop, Toast.LENGTH_SHORT).show();
    }

    /** 空 daemon 提前退出路径的前台占位通知（防 startForegroundService 5 秒超时崩溃）【SEVERE 2②】 */
    private void startForegroundPlaceholder() {
        Notification.Builder b = new Notification.Builder(this).setSmallIcon(R.drawable.tile);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_DAEMON,
                    getString(R.string.channel_daemon_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
            b.setChannelId(CHANNEL_DAEMON);
        }
        startForeground(NOTIFY_ID, b.build());
    }
}
