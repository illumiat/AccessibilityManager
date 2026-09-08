package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.sidesheet.SideSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * 主页（方案 §二）：服务网格 + 开关写 Settings.Secure（tmpSettingValue 防循环）+
 * ContentObserver 局部刷新 + 置顶精确移动 + 授权检查与激活对话框 + StartForeGroundDaemon +
 * 空态/未授权态/onResume 重查列表 + 详情弹卡（<600dp Bottom Sheet / ≥600dp Side Sheet）
 * + per-服务定期重启配置入口。
 */
public class HomeFragment extends Fragment implements ServiceAdapter.Callback {

    private static final int REQUEST_POST_NOTIFICATIONS = 1;

    private SharedPreferences sp;
    private IconCache iconCache;
    private ServiceAdapter adapter;
    private GridLayoutManager layoutManager;

    private RecyclerView recyclerView;
    private View emptyView;
    private View banner;
    private View bannerFailed;

    private final List<AccessibilityServiceInfo> installed = new ArrayList<>();
    private final List<AccessibilityServiceInfo> display = new ArrayList<>();
    private String searchQuery = "";

    private String settingValue = "";
    private String tmpSettingValue = "";
    private String daemon = "";
    private String top = "";
    /** top/daemon 串按 ":" 精确切分的 id 集合，防互为前缀的服务 id 子串误判【MAJOR 3】 */
    private final Set<String> topSet = new HashSet<>();
    private final Set<String> daemonSet = new HashSet<>();

    private Dialog detailDialog;
    /** 当前弹出详情的服务（尺寸变化时以新形态重开）【MISSING 12】 */
    private String detailServiceId;
    private AccessibilityServiceInfo detailInfo;
    /** 详情 Sheet 打开期间可重入的定期重启区域刷新逻辑（bindDetail 注册 / dismiss 清理）【P3】 */
    private Runnable detailRestartRefresher;
    /** 上次已知宽度是否 ≥600dp（Sheet 形态阈值态）；null = 尚未测量【SP1】 */
    private Boolean lastWide;

    private SettingsValueChangeContentObserver contentObserver;
    private Shizuku.OnRequestPermissionResultListener shizukuListener;
    /** 【R4】onCreate 捕获的 applicationContext：onDestroy 注销 observer 不依赖 getContext()（极端时序为 null） */
    private Context appContext;

    class SettingsValueChangeContentObserver extends ContentObserver {
        SettingsValueChangeContentObserver() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            ContentResolver cr = getContext() != null ? getContext().getContentResolver() : null;
            if (cr == null) return;
            String s = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) s = "";
            settingValue = s;
            // 【P4】s==tmpSettingValue（本 APP 自己写，或外部把设置串改回"上次写入值"）不再静默
            // return：先做只读 UI 刷新再收尾，自检基准随外部变化校准，不再滞留旧态至 onResume。
            // 刷新仅读设置值刷新可见行开关态与详情卡（refreshStates/updateRestartViews 均不写
            // Settings.Secure，也不触碰 tmpSettingValue 写方镜像）→ 无写路径、无回环
            postStatesRefresh();
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appContext = requireContext().getApplicationContext(); // 【R4】提前捕获，onDestroy 注销用
        sp = requireContext().getSharedPreferences("data", 0);
        daemon = sp.getString("daemon", "");
        top = sp.getString("top", "");
        fillIds(top, topSet);
        fillIds(daemon, daemonSet);
        iconCache = new IconCache(requireContext());

        contentObserver = new SettingsValueChangeContentObserver();
        requireContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, contentObserver);

        shizukuListener = (requestCode, grantResult) -> {
            // Shizuku 授权结果回来后重试 pm grant
            if (getContext() != null) PermissionHelper.grantViaShizuku(getContext());
        };
        // 未授权时注册监听（与旧行为一致）
        if (!PermissionHelper.hasWritePermission(requireContext())) {
            Shizuku.addRequestPermissionResultListener(shizukuListener);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 【MISSING 16】Android 13+ 首页首次进入即请求一次通知权限（守护启动路径既有请求保留）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            NotificationManager nm = (NotificationManager) requireContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && !nm.areNotificationsEnabled()) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
            }
        }
        recyclerView = view.findViewById(R.id.recycler);
        emptyView = view.findViewById(R.id.empty_view);
        banner = view.findViewById(R.id.banner);
        bannerFailed = view.findViewById(R.id.banner_failed);

        view.findViewById(R.id.btn_open_accessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        view.findViewById(R.id.banner_action).setOnClickListener(v ->
                PermissionHelper.showPermissionDialog(requireContext()));

        // 搜索栏：输入即过滤（保序，置顶顺序保留），清空恢复全量
        android.widget.EditText searchInput = view.findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                searchQuery = s.toString();
                applyFilter();
            }
        });

        layoutManager = new GridLayoutManager(requireContext(), 1);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.addItemDecoration(new GridSpacingDecoration(
                Math.round(8 * getResources().getDisplayMetrics().density)));

        adapter = new ServiceAdapter(requireContext(), display, iconCache, this);
        recyclerView.setAdapter(adapter);

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                boolean scrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING
                        || newState == RecyclerView.SCROLL_STATE_SETTLING;
                adapter.setScrollingPaused(scrolling);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) adapter.reloadVisibleIcons(rv);
            }
        });

        // spanCount 动态计算 = ceil((contentWidth−8dp)/(500dp+8dp))，下限 1
        // 单卡最大宽 500dp：手机竖屏(≈420dp)恒 1 列；平板竖屏(≈800dp) 2 列；平板横屏(≥1224dp) 3 列
        // （400dp 阈值会让密度调整后的手机竖屏 421dp 误入 2 列，已上调）
        final float density = getResources().getDisplayMetrics().density;
        // 【首帧修正】onCreateView 即按窗口宽度预算初始 span，避免首帧单列闪跳
        int initialSpan = (int) Math.ceil((getResources().getConfiguration().screenWidthDp - 8f) / 508f);
        layoutManager.setSpanCount(Math.max(1, initialSpan));
        recyclerView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            int width = r - l;
            int oldWidth = or2 - ol;
            if (width == oldWidth) return;
            float gap = 8 * density;
            int span = (int) Math.ceil((width - gap) / (500 * density + gap));
            span = Math.max(1, span);
            // 【SP1】600dp 阈值态独立跟踪：590→620dp 时 span 恒 2 不变，但 Sheet 形态已跨 Bottom/Side 界限，必须跟随
            boolean newWide = width >= 600 * density;
            boolean wideChanged = lastWide != null && lastWide != newWide;
            lastWide = newWide;
            boolean spanChanged = layoutManager.getSpanCount() != span;
            if (!spanChanged && !wideChanged) return;
            int first = layoutManager.findFirstVisibleItemPosition();
            layoutManager.setSpanCount(span);
            if (first != RecyclerView.NO_POSITION) layoutManager.scrollToPosition(first);
            // 尺寸变化（跨 span 阈值或 600dp 形态阈值）时详情卡先 dismiss，再按新宽度以新形态重开【MISSING 12 / SP1】
            AccessibilityServiceInfo reopen =
                    detailDialog != null && detailDialog.isShowing() && detailServiceId != null
                            ? detailInfo : null;
            dismissDetailSheet();
            if (reopen != null) openDetail(reopen);
        });

        loadInstalled();

        // 隐藏后台：随设置页开关即时生效，这里应用当前偏好
        applyHideFromRecents();

        // 首次使用隐私政策
        if (sp.getBoolean("first", true)) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.privacy_title)
                    .setMessage(R.string.privacy_message)
                    .setPositiveButton(R.string.action_ok, null)
                    .show();
            sp.edit().putBoolean("first", false).apply();
        }

        // 设备从未打开过无障碍设置界面时，设置项不存在，需要引导激活
        if (Settings.Secure.getString(requireContext().getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED) == null) {
            String cmd = "pm grant " + requireContext().getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.activate_dialog_message)
                    .setNegativeButton(R.string.action_root_activate, (d, w) ->
                            PermissionHelper.grantViaRoot(requireContext(), cmd))
                    .setPositiveButton(R.string.action_copy_command, (d, w) -> {
                        PermissionHelper.copyToClipboard(requireContext(), "adb shell " + cmd);
                        Toast.makeText(requireContext(), R.string.toast_command_copied, Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton(R.string.action_shizuku_activate, (d, w) ->
                            PermissionHelper.grantViaShizuku(requireContext()))
                    .show();
            try {
                Settings.Secure.putString(requireContext().getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            } catch (Exception ignored) {
            }
        }

        // 受保活锁定的服务 → 启动前台守护
        startDaemonIfNeeded();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 【二轮修订 P1】从系统设置返回时重查列表与权限，不依赖 ContentObserver 事件
        loadInstalled();
        boolean granted = PermissionHelper.hasWritePermission(requireContext());
        if (adapter != null) adapter.setPermissionState(granted);
        refreshStates();
        updateBanners(granted);
    }

    @Override
    public void onDestroy() {
        // 【MAJOR 4】退出前关闭详情弹卡，防 WindowLeaked/Activity 泄漏
        dismissDetailSheet();
        if (shizukuListener != null) Shizuku.removeRequestPermissionResultListener(shizukuListener);
        if (contentObserver != null && appContext != null) {
            // 【R4】经 applicationContext 的 ContentResolver 注销（与注册同一 ContentService），
            // getContext() 为 null 的极端时序同样注销，防 ContentObserver 持 Fragment/Activity 引用泄漏
            appContext.getContentResolver().unregisterContentObserver(contentObserver);
        }
        if (iconCache != null) iconCache.shutdown();
        super.onDestroy();
    }

    // ---------- 列表加载与排序 ----------

    private void loadInstalled() {
        installed.clear();
        // 【SEVERE 1】AccessibilityManager 位于 android.view.accessibility 包（原错包引用编译必失败）
        AccessibilityManager am =
                (AccessibilityManager) requireContext()
                        .getSystemService(Context.ACCESSIBILITY_SERVICE);
        installed.addAll(am.getInstalledAccessibilityServiceList());

        // 卸载清理：重启配置（含置顶标记）一并移除【二轮修订 P2】
        cleanupUninstalledConfigs();

        sortDisplay();
        if (adapter != null) applyFilter();
        settingValue = readSettingValue();
        tmpSettingValue = settingValue;
        updateEmptyState();
        refreshStates();
    }

    /**
     * 搜索过滤：基于已排序的 display 保序过滤（置顶顺序保留）。
     * 匹配范围：服务 id、缓存的应用标签/服务标签。空查询显示全部。
     */
    private void applyFilter() {
        List<AccessibilityServiceInfo> filtered = new ArrayList<>();
        String q = searchQuery == null ? "" : searchQuery.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            filtered.addAll(display);
        } else {
            for (AccessibilityServiceInfo info : display) {
                String id = info.getId();
                IconCache.Entry e = iconCache.peek(id);
                String hay = (id + " "
                        + (e != null && e.appLabel != null ? e.appLabel : "") + " "
                        + (e != null && e.serviceLabel != null ? e.serviceLabel : ""))
                        .toLowerCase(java.util.Locale.ROOT);
                if (hay.contains(q)) filtered.add(info);
            }
        }
        if (adapter != null) adapter.setItems(filtered);
    }

    /** 列表刷新时同步清理不存在服务的重启配置【二轮修订 P2】 */
    private void cleanupUninstalledConfigs() {
        for (String id : new ArrayList<>(RestartPrefs.getPendingEnables(requireContext()).keySet())) {
            if (!isInstalledId(id)) RestartPrefs.removeCompletely(requireContext(), id);
        }
        for (String id : new ArrayList<>(RestartPrefs.getFailed(requireContext()))) {
            if (!isInstalledId(id)) RestartPrefs.removeCompletely(requireContext(), id);
        }
        for (String id : enabledRestartIds()) {
            if (!isInstalledId(id)) RestartPrefs.removeCompletely(requireContext(), id);
        }
        // 【惰性调度】卸载清理后若已无启用配置，取消空转的周期任务
        RestartWorker.cancelIfIdle(requireContext());
    }

    private boolean isInstalledId(String serviceId) {
        for (AccessibilityServiceInfo info : installed) {
            if (info.getId().equals(serviceId)) return true;
        }
        return false;
    }

    private List<String> enabledRestartIds() {
        List<String> ids = new ArrayList<>();
        for (String key : requireContext().getSharedPreferences("restart", 0).getAll().keySet()) {
            if (key.endsWith(".enabled") && requireContext().getSharedPreferences("restart", 0)
                    .getBoolean(key, false)) {
                ids.add(key.substring(0, key.length() - ".enabled".length()));
            }
        }
        return ids;
    }

    private void sortDisplay() {
        display.clear();
        display.addAll(installed);
        // 【F3】top 串按 ":" 段级切分（与 fillIds 同源），比较器用 List.indexOf 精确定位。
        // 原 top.indexOf 子串定位在互为前缀的服务 id 上可双向 compare 同返回 1，
        // 违反比较器契约（TimSort 可能抛 "Comparison method violates its general contract"）。
        // 同一 id 在 topOrder 中至多出现一次（toggleTop 前插/移除保证），索引比较严格满足反对称性。
        final List<String> topOrder = new ArrayList<>();
        for (String t : top.split(":")) {
            if (!t.isEmpty()) topOrder.add(t);
        }
        Collections.sort(display, new Comparator<AccessibilityServiceInfo>() {
            @Override
            public int compare(AccessibilityServiceInfo info1, AccessibilityServiceInfo info2) {
                int i1 = topOrder.indexOf(info1.getId());
                int i2 = topOrder.indexOf(info2.getId());
                if (i1 == -1 && i2 == -1) return 0;
                if (i1 == -1) return 1; // -1 视为未置顶排后
                if (i2 == -1) return -1;
                return Integer.compare(i1, i2);
            }
        });
    }

    private void updateEmptyState() {
        boolean empty = installed.isEmpty();
        if (emptyView != null) emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** observer 路径的只读显示刷新：可见行开关态 + 详情卡定期重启区域（不写 Settings.Secure）【P4】 */
    private void postStatesRefresh() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (adapter != null) adapter.refreshStates(settingValue, pendingSet(), failedSet());
                refreshDetailRestartArea(); // 【P3】Sheet 打开中同步"重启中…"/恢复态，防停留旧态
            });
        }
    }

    private void refreshStates() {
        if (adapter != null) adapter.refreshStates(settingValue, pendingSet(), failedSet());
        refreshDetailRestartArea(); // 【P3】局部刷新路径同步详情 Sheet 定期重启区域
    }

    /** 【P3】详情 Sheet 打开中时刷新定期重启卡区域（周期行/重启中态/上次执行时间） */
    private void refreshDetailRestartArea() {
        if (detailDialog != null && detailDialog.isShowing()
                && detailServiceId != null && detailRestartRefresher != null) {
            detailRestartRefresher.run();
        }
    }

    private void updateBanners(boolean granted) {
        if (banner != null) banner.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (bannerFailed != null) bannerFailed.setVisibility(failedSet().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String readSettingValue() {
        String s = Settings.Secure.getString(requireContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s == null ? "" : s;
    }

    private void startDaemonIfNeeded() {
        if (PermissionHelper.hasWritePermission(requireContext())) {
            for (AccessibilityServiceInfo info : installed) {
                if (daemonSet.contains(info.getId())) {
                    startForegroundDaemon();
                    break;
                }
            }
        }
    }

    private void applyHideFromRecents() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ((ActivityManager) requireContext().getSystemService(Service.ACTIVITY_SERVICE))
                        .getAppTasks().get(0).setExcludeFromRecents(sp.getBoolean("hide", true));
            }
        } catch (Exception ignored) {
        }
    }

    /** 启动前台守护服务（迁移自旧 StartForeGroundDaemon） */
    void startForegroundDaemon() {
        // 【S2】调用方（startDaemonIfNeeded/onLockClick）均确认有权限才进入；此处只拦截"无权限"，
        // 原逻辑遇有权限反而 return → daemon 永不启动（启动链路死锁）
        if (!PermissionHelper.hasWritePermission(requireContext())) {
            PermissionHelper.showPermissionDialog(requireContext());
            return;
        }
        NotificationManager nm = (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        // 【M-b】系统服务可能取不到，判 null 防 NPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && nm != null && !nm.areNotificationsEnabled()) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            Toast.makeText(requireContext(), R.string.toast_please_grant_notification, Toast.LENGTH_SHORT).show();
            return;
        }
        PowerManager power = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
        if (!power.isIgnoringBatteryOptimizations(requireContext().getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + requireContext().getPackageName())));
            } catch (Exception ignored) {
            }
        }
        Intent intent = new Intent(requireContext(), daemonService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(requireContext(), intent);
        } else {
            requireContext().startService(intent);
        }
    }

    // ---------- ServiceAdapter.Callback ----------

    @Override
    public void onToggle(AccessibilityServiceInfo info, boolean checked) {
        if (!PermissionHelper.hasWritePermission(requireContext())) {
            PermissionHelper.showPermissionDialog(requireContext());
            refreshStates(); // 回滚 UI 状态
            return;
        }
        String serviceName = info.getId();
        String s = readSettingValue();
        // 【MAJOR 10】开关串逻辑统一走 RestartPrefs 单一实现（与 daemon/Worker 共用，防双实现分叉）
        tmpSettingValue = checked
                ? RestartPrefs.prependService(s, serviceName)
                : RestartPrefs.removeService(s, serviceName);
        try {
            Settings.Secure.putString(requireContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
        } catch (Exception e) {
            // 【P6-a】catch 由 SecurityException 扩为 Exception（与 daemon 侧口径统一）：任何写失败
            // 均回滚镜像为读取到的实际旧值并提示刷新，防 tmpSettingValue 失真（依赖自愈存在窗口期）
            tmpSettingValue = s;
            Toast.makeText(requireContext(), R.string.toast_write_failed, Toast.LENGTH_SHORT).show();
            refreshStates();
        }
        // 【惰性调度】开关变化后回收空转调度：全部定期重启配置关闭时取消周期任务（零主动唤醒）
        RestartWorker.cancelIfIdle(requireContext());
    }

    @Override
    public void onLockClick(AccessibilityServiceInfo info) {
        if (!PermissionHelper.hasWritePermission(requireContext())) {
            PermissionHelper.showPermissionDialog(requireContext());
            return;
        }
        String serviceName = info.getId();
        // 【M4】daemon 串读改写收口 RestartPrefs.toggleDaemon（与 Worker/daemon 主线程互斥，单次 apply）
        boolean nowLocked = RestartPrefs.toggleDaemon(requireContext(), serviceName);
        if (nowLocked) {
            daemonSet.add(serviceName);
        } else {
            daemonSet.remove(serviceName);
        }
        daemon = sp.getString("daemon", ""); // 内存镜像自 SP 回读，与持锁写入结果保持一致
        startForegroundDaemon();
        refreshStates();
        // 【锁状态即时跟随】refreshStates 的变更检测不含锁定项（daemonSet 不在其比对集），
        // 点击后对该行精确发 payload 重绑，锁图标立即切换
        int pos = display.indexOf(info);
        if (pos != RecyclerView.NO_POSITION && adapter != null) {
            adapter.notifyItemChanged(pos, ServiceAdapter.PAYLOAD_STATE);
        }
    }

    @Override
    public void onItemClick(AccessibilityServiceInfo info) {
        openDetail(info);
    }

    @Override
    public void onItemLongClick(AccessibilityServiceInfo info) {
        // 【二轮修订 P0】置顶路径禁用 notifyDataSetChanged：sort 后 notifyItemMoved 精确移动
        String serviceName = info.getId();
        String label = IconCache.shortClassName(serviceName);
        // 【M4】top 串读改写收口 RestartPrefs.toggleTop（与 Worker/daemon 主线程互斥，单次 apply）
        boolean nowTop = RestartPrefs.toggleTop(requireContext(), serviceName);
        if (nowTop) {
            topSet.add(serviceName);
            Toast.makeText(requireContext(), getString(R.string.toast_topped, label), Toast.LENGTH_SHORT).show();
        } else {
            topSet.remove(serviceName);
            Toast.makeText(requireContext(), getString(R.string.toast_untopped, label), Toast.LENGTH_SHORT).show();
        }
        top = sp.getString("top", ""); // 内存镜像自 SP 回读（sortDisplay 的 indexOf 排序依赖串顺序）

        int from = display.indexOf(info);
        sortDisplay();
        int to = display.indexOf(info);
        if (from != to && adapter != null) {
            // 【P1】ServiceAdapter 自 S1 起持有 display 的副本：置顶必须先同步 adapter 内部
            // items 再发通知，否则 stableIds 通知序列与数据错位 → 置顶后重复行/丢行，
            // 可触发 Inconsistency 崩溃（notifyItemMoved 由调用方发，moveItem 内部不加 notify）
            adapter.moveItem(from, to);
            adapter.notifyItemMoved(from, to);
            adapter.notifyItemChanged(from, ServiceAdapter.PAYLOAD_STATE);
            adapter.notifyItemChanged(to, ServiceAdapter.PAYLOAD_STATE);
        }
    }

    @Override
    public boolean isTop(String serviceId) {
        return topSet.contains(serviceId);
    }

    @Override
    public boolean isLocked(String serviceId) {
        return daemonSet.contains(serviceId);
    }

    @Override
    public String restartSummary(String serviceId) {
        RestartPrefs.Config cfg = RestartPrefs.get(requireContext(), serviceId);
        return cfg != null ? formatPeriod(cfg.periodMin) : null;
    }

    // ---------- 批 2：详情弹卡 ----------

    void dismissDetailSheet() {
        if (detailDialog != null && detailDialog.isShowing()) {
            detailDialog.dismiss();
        }
        detailDialog = null;
    }

    private int contentWidthDp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = requireActivity().getWindowManager().getCurrentWindowMetrics();
            return Math.round(metrics.getBounds().width() / getResources().getDisplayMetrics().density);
        }
        return Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
    }

    /** 点击卡片弹出详情：<600dp Bottom Sheet / ≥600dp Side Sheet（WindowMetrics 实时判断，Q2） */
    private void openDetail(AccessibilityServiceInfo info) {
        dismissDetailSheet();
        final String serviceId = info.getId();
        detailServiceId = serviceId; // 【MISSING 12】记录当前详情服务，尺寸变化时以新形态重开
        detailInfo = info;
        int slash = serviceId.indexOf('/');
        final String pkg = slash > 0 ? serviceId.substring(0, slash) : serviceId;

        Dialog dialog;
        View content;
        if (contentWidthDp() < 600) {
            BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
            content = getLayoutInflater().inflate(R.layout.sheet_service_detail, null, false);
            applySheetInsets(content); // 【MISSING 13】
            sheet.setContentView(content);
            dialog = sheet;
        } else {
            SideSheetDialog side = new SideSheetDialog(requireContext());
            content = getLayoutInflater().inflate(R.layout.sheet_service_detail, null, false);
            applySheetInsets(content); // 【MISSING 13】
            side.setContentView(content);
            // 平板加宽：默认 sheet 偏窄，改为 500dp（不超过屏宽 60%）
            side.setOnShowListener(d -> {
                View sheet = side.findViewById(com.google.android.material.R.id.m3_side_sheet);
                if (sheet != null) {
                    float density = getResources().getDisplayMetrics().density;
                    int want = Math.round(500 * density);
                    int max = Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f);
                    android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                    if (lp != null) {
                        lp.width = Math.min(want, max);
                        sheet.setLayoutParams(lp);
                    }
                }
            });
            dialog = side;
        }
        detailDialog = dialog;
        // 关闭时清理记录（用户手动下滑关闭同样生效），防止残留触发误重开【MISSING 12】
        dialog.setOnDismissListener(d -> {
            if (detailDialog == d) {
                detailDialog = null;
                detailServiceId = null;
                detailInfo = null;
                detailRestartRefresher = null; // 【P3】防引用已销毁视图的刷新闭包
            }
        });

        bindDetail(content, info, pkg, dialog);
        dialog.show();
    }

    /**
     * 【MISSING 13】Sheet 内容根布局挂 OnApplyWindowInsetsListener：
     * 手势导航 navigationBar insets 动态叠加 padding（保留布局原 padding 基线）
     */
    private void applySheetInsets(View content) {
        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = WindowInsetsCompat.toWindowInsetsCompat(insets)
                    .getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(baseLeft + bars.left, baseTop + bars.top,
                    baseRight + bars.right, baseBottom + bars.bottom);
            return insets;
        });
    }

    private void bindDetail(View content, final AccessibilityServiceInfo info, String pkg, Dialog dialog) {
        final String serviceId = info.getId();

        ImageView ivIcon = content.findViewById(R.id.iv_icon);
        IconCache.Entry e = iconCache.peek(serviceId);
        if (e != null && e.icon != null) {
            ivIcon.setImageBitmap(e.icon);
        } else {
            ivIcon.setImageBitmap(iconCache.placeholderFor(serviceId));
            // 【M-c】传回调：详情卡在显示时补刷图标与标题，防加载完成后停留占位图
            iconCache.load(info, () -> {
                if (detailDialog == null || !detailDialog.isShowing()
                        || detailServiceId == null || !serviceId.equals(detailServiceId)) return;
                IconCache.Entry loaded = iconCache.peek(serviceId);
                if (loaded != null && loaded.icon != null) {
                    ivIcon.setImageBitmap(loaded.icon);
                }
                String app2 = loaded != null ? loaded.appLabel : null;
                String svc2 = loaded != null ? loaded.serviceLabel : null;
                String title2 = svc2 != null ? svc2 : (app2 != null ? app2 : IconCache.shortClassName(serviceId));
                ((TextView) content.findViewById(R.id.tv_app_name)).setText(title2);
            });
        }

        String app = e != null ? e.appLabel : null;
        String svc = e != null ? e.serviceLabel : null;
        String title = svc != null ? svc : (app != null ? app : IconCache.shortClassName(serviceId));
        ((TextView) content.findViewById(R.id.tv_app_name)).setText(title);
        ((TextView) content.findViewById(R.id.tv_pkg_cls)).setText(serviceId);

        // 基本信息：状态 / 生效范围 / 反馈方式
        boolean enabled = ServiceAdapter.isEnabledIn(settingValue, serviceId);
        StringBuilder basic = new StringBuilder();
        basic.append(getString(R.string.detail_status, getString(enabled
                ? R.string.detail_status_enabled : R.string.detail_status_disabled))).append('\n');
        basic.append(getString(R.string.detail_range)).append("：");
        basic.append(info.packageNames == null ? getString(R.string.range_global)
                : joinArray(info.packageNames)).append('\n');
        basic.append(getString(R.string.detail_feedback)).append("：").append(feedbackText(info.feedbackType));
        ((TextView) content.findViewById(R.id.tv_basic)).setText(basic.toString());

        // 能力/事件/标志 chips（迁移原 flags 位解码代码）
        bindChips(content.findViewById(R.id.chip_group_caps), capabilityChips(info));
        bindChips(content.findViewById(R.id.chip_group_events), eventChips(info));

        // 定期重启卡片
        MaterialSwitch swRestart = content.findViewById(R.id.sw_restart);
        TextView tvPeriod = content.findViewById(R.id.tv_period);
        TextView tvLast = content.findViewById(R.id.tv_last_restart);
        View btnModify = content.findViewById(R.id.btn_modify_period);
        View btnOpen = content.findViewById(R.id.btn_open_settings);

        // 【P2】开关切换监听器先声明：updateRestartViews 回正开关时需设回同一实例；
        // 重入刷新经 refreshDetailRestartArea（detailRestartRefresher 已注册，详情卡交互期间恒可重入）
        final android.widget.CompoundButton.OnCheckedChangeListener restartToggle = (b, isChecked) -> {
            if (isChecked) {
                // 【R1】实时取当前值（含"修改周期"刚写入的 periodMin），禁用闭包捕获的旧 cfg 引用；
                // 已启用态用 cfg.periodMin，未启用态（关→再开）读最近设定周期，防新周期被静默回退
                RestartPrefs.Config live = RestartPrefs.get(requireContext(), serviceId);
                long period = live != null ? live.periodMin
                        : RestartPrefs.peekLastPeriod(requireContext(), serviceId);
                RestartPrefs.enable(requireContext(), serviceId, period);
                RestartWorker.schedule(requireContext()); // 幂等
            } else {
                RestartPrefs.disable(requireContext(), serviceId);
            }
            refreshDetailRestartArea(); // 重入 updateRestartViews：周期行/上次执行/开关回正【P2/P5】
            refreshStates(); // 卡片描述行同步摘要
        };
        Runnable updateRestartViews = () -> {
            // 【MISSING 14】重启补偿进行中 → 定期重启区域显示"重启中…"过渡态
            RestartPrefs.Config cur = RestartPrefs.get(requireContext(), serviceId);
            boolean pending = RestartPrefs.getPendingEnables(requireContext()).containsKey(serviceId);
            // 【R5】"恢复失败"为终态警示，优先级高于"重启中…"过渡态
            if (RestartPrefs.getFailed(requireContext()).contains(serviceId)) {
                tvPeriod.setText(R.string.service_restart_failed);
            } else if (pending) {
                tvPeriod.setText(R.string.service_restarting);
            } else {
                tvPeriod.setText(cur != null
                        ? getString(R.string.detail_restart_period, formatPeriod(cur.periodMin))
                        : getString(R.string.detail_restart_period, "—"));
            }
            tvLast.setText(cur != null && cur.lastRestart > 0
                    ? getString(R.string.detail_restart_last, formatRelative(cur.lastRestart))
                    : getString(R.string.detail_restart_never));
            // 【P5】重启补偿进行中 → sw_restart 禁交互（与列表卡口径一致），其余状态恢复可交互；
            // bindDetail 与 refreshDetailRestartArea 重入刷新共用本 Runnable，两路径全覆盖
            swRestart.setEnabled(!pending);
            // 【P2】开关回正：以 SP 真实 enabled 态校准 sw_restart（先摘 listener 再 setChecked 再设回，
            // 防 setChecked 误触发切换），修复"修改周期"静默 enable 后开关滞留关位、
            // 服务被周期重启而用户不知情的问题
            swRestart.setOnCheckedChangeListener(null);
            swRestart.setChecked(cur != null);
            swRestart.setOnCheckedChangeListener(restartToggle);
        };
        detailRestartRefresher = updateRestartViews; // 【P3】注册供 onChange/refreshStates 重入刷新
        swRestart.setOnCheckedChangeListener(restartToggle);
        updateRestartViews.run();

        btnModify.setOnClickListener(v -> showPeriodDialog(serviceId, updateRestartViews));

        btnOpen.setOnClickListener(v -> {
            try {
                if (info.getSettingsActivityName() != null && info.getSettingsActivityName().length() > 0) {
                    startActivity(new Intent().setComponent(new ComponentName(pkg, info.getSettingsActivityName())));
                } else {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }
            } catch (Exception ignored) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored2) {
                }
            }
        });
    }

    private void bindChips(com.google.android.material.chip.ChipGroup group, List<String> texts) {
        group.removeAllViews();
        if (texts.isEmpty()) {
            addChip(group, getString(R.string.chip_none));
            return;
        }
        for (String t : texts) addChip(group, t);
    }

    private void addChip(com.google.android.material.chip.ChipGroup group, String text) {
        Chip chip = new Chip(requireContext());
        chip.setText(text);
        chip.setCheckable(false);
        chip.setChipBackgroundColorResource(android.R.color.transparent);
        chip.setChipStrokeWidth(getResources().getDisplayMetrics().density);
        group.addView(chip);
    }

    private List<String> capabilityChips(AccessibilityServiceInfo info) {
        List<String> out = new ArrayList<>();
        int cap = info.getCapabilities();
        if ((cap & 32) != 0) out.add(getString(R.string.cap_gestures));
        if ((cap & 16) != 0) out.add(getString(R.string.cap_magnification));
        if ((cap & 8) != 0) out.add(getString(R.string.cap_filter_key_events));
        if ((cap & 4) != 0) out.add(getString(R.string.cap_web));
        if ((cap & 2) != 0) out.add(getString(R.string.cap_touch_exploration));
        if ((cap & 1) != 0) out.add(getString(R.string.cap_retrieve_content));
        int fg = info.flags;
        if ((fg & 64) != 0) out.add(getString(R.string.flag_interactive_windows));
        if ((fg & 32) != 0) out.add(getString(R.string.flag_filter_key_events));
        if ((fg & 16) != 0) out.add(getString(R.string.flag_report_view_ids));
        if ((fg & 8) != 0) out.add(getString(R.string.flag_enhanced_web));
        if ((fg & 4) != 0) out.add(getString(R.string.flag_touch_exploration));
        if ((fg & 2) != 0) out.add(getString(R.string.flag_include_not_important));
        if ((fg & 1) != 0) out.add(getString(R.string.flag_default));
        return out;
    }

    private List<String> eventChips(AccessibilityServiceInfo info) {
        List<String> out = new ArrayList<>();
        int eve = info.eventTypes;
        if ((eve & 33554432) != 0) out.add(getString(R.string.ev_assistant));
        if ((eve & 16777216) != 0) out.add(getString(R.string.ev_context_clicked));
        if ((eve & 8388608) != 0) out.add(getString(R.string.ev_windows_changed));
        if ((eve & 4194304) != 0) out.add(getString(R.string.ev_touch_end));
        if ((eve & 2097152) != 0) out.add(getString(R.string.ev_touch_start));
        if ((eve & 1048576) != 0) out.add(getString(R.string.ev_gesture_end));
        if ((eve & 524288) != 0) out.add(getString(R.string.ev_gesture_start));
        if ((eve & 262144) != 0) out.add(getString(R.string.ev_text_traversed));
        if ((eve & 131072) != 0) out.add(getString(R.string.ev_clear_focus));
        if ((eve & 65536) != 0) out.add(getString(R.string.ev_gain_focus));
        if ((eve & 32768) != 0) out.add(getString(R.string.ev_announcement));
        if ((eve & 16384) != 0) out.add(getString(R.string.ev_selection_changed));
        if ((eve & 8192) != 0) out.add(getString(R.string.ev_view_scrolled));
        if ((eve & 4096) != 0) out.add(getString(R.string.ev_content_changed));
        if ((eve & 2048) != 0) out.add(getString(R.string.ev_touch_explore_end));
        if ((eve & 1024) != 0) out.add(getString(R.string.ev_touch_explore_start));
        if ((eve & 512) != 0) out.add(getString(R.string.ev_text_input_end));
        if ((eve & 256) != 0) out.add(getString(R.string.ev_text_input_start));
        if ((eve & 128) != 0) out.add(getString(R.string.ev_notification_changed));
        if ((eve & 64) != 0) out.add(getString(R.string.ev_window_state_changed));
        if ((eve & 32) != 0) out.add(getString(R.string.ev_text_changed));
        if ((eve & 16) != 0) out.add(getString(R.string.ev_view_focused));
        if ((eve & 8) != 0) out.add(getString(R.string.ev_view_selected));
        if ((eve & 4) != 0) out.add(getString(R.string.ev_view_long_clicked));
        if ((eve & 2) != 0) out.add(getString(R.string.ev_view_clicked));
        return out;
    }

    private String feedbackText(int fb) {
        List<String> out = new ArrayList<>();
        if ((fb & 32) != 0) out.add(getString(R.string.feedback_braille));
        if ((fb & 16) != 0) out.add(getString(R.string.feedback_generic));
        if ((fb & 8) != 0) out.add(getString(R.string.feedback_visual));
        if ((fb & 4) != 0) out.add(getString(R.string.feedback_audible));
        if ((fb & 2) != 0) out.add(getString(R.string.feedback_haptic));
        if ((fb & 1) != 0) out.add(getString(R.string.feedback_spoken));
        if (out.isEmpty()) return getString(R.string.chip_none);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    private String joinArray(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append('、');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    // ---------- 周期对话框（Q15：数字+单位，30 分钟 ~ 30 天，非法输入禁用确认键） ----------

    private int unitIndexOf(int checkedButtonId) {
        if (checkedButtonId == R.id.btn_unit_min) return 0;
        if (checkedButtonId == R.id.btn_unit_hour) return 1;
        return 2;
    }

    private void showPeriodDialog(final String serviceId, final Runnable onUpdated) {
        // 【P2】周期预填改 peekLastPeriod：未启用态读最近设定周期（与 R1 同源），禁用 DEFAULT 兜底覆盖用户自定义周期
        final long currentPeriod = RestartPrefs.peekLastPeriod(requireContext(), serviceId);

        View view = getLayoutInflater().inflate(R.layout.view_period_input, null, false);
        final EditText etValue = view.findViewById(R.id.et_value);
        final com.google.android.material.button.MaterialButtonToggleGroup tgUnit =
                view.findViewById(R.id.tg_unit);
        final TextView tvHelper = view.findViewById(R.id.tv_helper);

        // 默认回填当前周期，自动选择合适单位
        long[] factors = {1L, 60L, 1440L};
        int unitIdx = 0;
        if (currentPeriod % 1440 == 0) unitIdx = 2;
        else if (currentPeriod % 60 == 0) unitIdx = 1;
        tgUnit.check(unitIdx == 0 ? R.id.btn_unit_min
                : unitIdx == 1 ? R.id.btn_unit_hour : R.id.btn_unit_day);
        etValue.setText(String.valueOf(currentPeriod / factors[unitIdx]));

        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.period_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.action_ok, null)
                .setNegativeButton(android.R.string.cancel, null);

        final androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
        final android.widget.Button positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);

        final long[] f = factors;
        Runnable validate = () -> {
            String text = etValue.getText().toString().trim();
            boolean valid = false;
            if (!text.isEmpty()) {
                try {
                    long value = Long.parseLong(text);
                    if (value > 0) {
                        long minutes = value * f[unitIndexOf(tgUnit.getCheckedButtonId())];
                        valid = minutes >= RestartPrefs.MIN_PERIOD_MIN && minutes <= RestartPrefs.MAX_PERIOD_MIN;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            positive.setEnabled(valid);
            boolean empty = text.isEmpty();
            tvHelper.setText(empty || valid ? R.string.period_dialog_helper : R.string.period_invalid);
            tvHelper.setTextColor(MaterialColors.getColor(tvHelper, valid || empty
                    ? com.google.android.material.R.attr.colorOnSurfaceVariant
                    : androidx.appcompat.R.attr.colorError));
        };
        validate.run();
        etValue.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { validate.run(); }
        });
        tgUnit.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) validate.run();
        });

        positive.setOnClickListener(v -> {
            try {
                long value = Long.parseLong(etValue.getText().toString().trim());
                long minutes = value * f[unitIndexOf(tgUnit.getCheckedButtonId())];
                if (minutes < RestartPrefs.MIN_PERIOD_MIN || minutes > RestartPrefs.MAX_PERIOD_MIN) return;
                if (RestartPrefs.get(requireContext(), serviceId) != null) {
                    RestartPrefs.setPeriod(requireContext(), serviceId, minutes);
                } else {
                    RestartPrefs.enable(requireContext(), serviceId, minutes);
                    RestartWorker.schedule(requireContext());
                    // 【P2】未启用态"修改周期"确认会静默启用并调度 Worker，必须可感知：
                    // Toast 明示开启，防服务被周期重启而用户不知情
                    Toast.makeText(requireContext(), R.string.detail_restart_enable, Toast.LENGTH_SHORT).show();
                }
                refreshStates();
                // 【P2】onUpdated（updateRestartViews）含开关回正：sw_restart 以 SP 真实 enabled 态
                // 校准为 true，并同步周期行显示
                onUpdated.run();
                dialog.dismiss();
            } catch (NumberFormatException ignored) {
            }
        });
    }

    // ---------- 展示辅助 ----------

    /** ":" 连接的 id 串 → 精确 id 集合【MAJOR 3】 */
    private static void fillIds(String colonJoined, Set<String> out) {
        out.clear();
        for (String id : colonJoined.split(":")) {
            if (!id.isEmpty()) out.add(id);
        }
    }

    /** 周期展示：整小时/整天取大单位 */
    private String formatPeriod(long periodMin) {
        if (periodMin % 1440 == 0) {
            return getString(R.string.period_display_days, String.valueOf(periodMin / 1440));
        }
        if (periodMin % 60 == 0) {
            return getString(R.string.period_display_hours, String.valueOf(periodMin / 60));
        }
        return getString(R.string.period_display_minutes, periodMin);
    }

    /** 相对时间："3 天前"（【二轮修订 P2】） */
    private String formatRelative(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long min = diff / 60000L;
        if (min < 1) return getString(R.string.relative_just_now);
        if (min < 60) return getString(R.string.relative_minutes_ago, min);
        long hours = min / 60;
        if (hours < 24) return getString(R.string.relative_hours_ago, hours);
        return getString(R.string.relative_days_ago, hours / 24);
    }

    // ---------- 批 2 数据源 ----------

    Set<String> pendingSet() {
        return RestartPrefs.getPendingEnables(requireContext()).keySet();
    }

    Set<String> failedSet() {
        return RestartPrefs.getFailed(requireContext());
    }
}
