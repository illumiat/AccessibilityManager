package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图标缓存（方案 §三 六条硬条款）：
 * 内存 LruCache（maxMemory/8，key=服务id|lastUpdateTime）+ 后台线程池 + in-flight 合并
 * + 48dp 降采样 + 绑定期零回源（onBind 只读缓存）+ 可见优先/滑动暂停预加载。
 */
public class IconCache {

    public static class Entry {
        public volatile boolean loaded;
        @Nullable
        public Bitmap icon;
        @Nullable
        public Bitmap placeholder;
        @Nullable
        public String appLabel;
        @Nullable
        public String serviceLabel;
        @Nullable
        public String description;
    }

    public interface LoadCallback {
        void onLoaded();
    }

    private final PackageManager pm;
    private final Context appContext;
    private final LruCache<String, Entry> cache;
    private final Map<String, String> currentKeyByService = new HashMap<>();
    private final Map<String, List<LoadCallback>> waiting = new HashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int iconSizePx;
    private final Map<Character, Bitmap> placeholderCache = new HashMap<>();
    private volatile boolean paused = false;

    public IconCache(Context context) {
        appContext = context.getApplicationContext();
        pm = appContext.getPackageManager();
        iconSizePx = Math.round(48 * appContext.getResources().getDisplayMetrics().density);
        cache = new LruCache<String, Entry>((int) (Runtime.getRuntime().maxMemory() / 8)) {
            @Override
            protected int sizeOf(String key, Entry value) {
                return value.icon != null ? value.icon.getByteCount() : 1;
            }
        };
    }

    /** 绑定期只读缓存，绝不回源 */
    @Nullable
    public Entry peek(String serviceId) {
        synchronized (lock) {
            String key = currentKeyByService.get(serviceId);
            return key != null ? cache.get(key) : null;
        }
    }

    /**
     * 异步加载图标/标签/描述。回调在主线程触发，调用方（Adapter）须按包名校验后再刷新单行。
     */
    public void load(final AccessibilityServiceInfo info, @Nullable final LoadCallback callback) {
        final String serviceId = info.getId();
        synchronized (lock) {
            String key = currentKeyByService.get(serviceId);
            if (key != null && cache.get(key) != null) {
                if (callback != null) callback.onLoaded();
                return;
            }
            List<LoadCallback> list = waiting.get(serviceId);
            if (list != null) {
                if (callback != null) list.add(callback);
                return;
            }
            List<LoadCallback> newList = new ArrayList<>();
            if (callback != null) newList.add(callback);
            waiting.put(serviceId, newList);
        }
        pool.execute(() -> {
            Loaded e = loadSync(info);
            List<LoadCallback> toNotify;
            synchronized (lock) {
                currentKeyByService.put(serviceId, e.key);
                toNotify = waiting.remove(serviceId);
            }
            cache.put(e.key, e);
            if (toNotify != null) {
                for (final LoadCallback c : toNotify) main.post(c::onLoaded);
            }
        });
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    /** 灰底首字占位图（主题 role 取色，禁硬编码色值） */
    public Bitmap placeholderFor(String serviceId) {
        String cls = shortClassName(serviceId);
        char c = cls == null || cls.isEmpty() ? '?' : Character.toUpperCase(cls.charAt(0));
        synchronized (placeholderCache) {
            Bitmap b = placeholderCache.get(c);
            if (b == null) {
                b = createPlaceholder(c);
                placeholderCache.put(c, b);
            }
            return b;
        }
    }

    private static class Loaded extends Entry {
        final String key;

        Loaded(String key) {
            this.key = key;
        }
    }

    private Loaded loadSync(AccessibilityServiceInfo info) {
        String serviceId = info.getId();
        int slash = serviceId.indexOf('/');
        String pkg = slash > 0 ? serviceId.substring(0, slash) : serviceId;
        String cls = slash > 0 ? serviceId.substring(slash + 1) : "";
        long lastUpdate = 0;
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            lastUpdate = pi.lastUpdateTime;
        } catch (Exception ignored) {
        }
        Loaded e = new Loaded(serviceId + "|" + lastUpdate);

        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            e.icon = downsample(ai.loadIcon(pm));
            e.appLabel = String.valueOf(ai.loadLabel(pm));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        try {
            android.content.pm.ServiceInfo si = pm.getServiceInfo(new android.content.ComponentName(pkg, pkg + cls),
                    PackageManager.MATCH_DEFAULT_ONLY);
            e.serviceLabel = String.valueOf(si.loadLabel(pm));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        try {
            e.description = info.loadDescription(pm);
        } catch (Exception ignored) {
        }
        e.loaded = true;
        return e;
    }

    private Bitmap downsample(Drawable d) {
        Bitmap b = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        d.setBounds(0, 0, iconSizePx, iconSizePx);
        d.draw(canvas);
        return b;
    }

    private Bitmap createPlaceholder(char c) {
        Bitmap b = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        int bg = attrColor(com.google.android.material.R.attr.colorSurfaceVariant);
        int fg = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(bg);
        canvas.drawCircle(iconSizePx / 2f, iconSizePx / 2f, iconSizePx / 2f, paint);
        paint.setColor(fg);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(iconSizePx * 0.5f);
        paint.setFakeBoldText(true);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = iconSizePx / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(String.valueOf(c), iconSizePx / 2f, baseline, paint);
        return b;
    }

    private int attrColor(int attr) {
        TypedValue tv = new TypedValue();
        if (appContext.getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        // 兜底仅在主题属性缺失时触发
        return 0xFF9E9E9E;
    }

    @Nullable
    static String shortClassName(String serviceId) {
        int slash = serviceId.lastIndexOf('/');
        return slash >= 0 && slash + 1 < serviceId.length() ? serviceId.substring(slash + 1) : serviceId;
    }

    // ---------- 静态包名标签缓存（daemonService.doDaemon 守护路径同进程直调）【MISSING 15】 ----------
    private static final LruCache<String, String> sPkgLabelCache = new LruCache<>(64);
    private static final Object sPkgLabelLock = new Object();
    private static final ExecutorService sPkgLabelPool = Executors.newSingleThreadExecutor();

    /**
     * 守护路径取包名展示名：缓存命中零 IPC；miss 后台加载入缓存（不阻塞守护主线程），
     * 本次回退包名；查询失败同样以包名入缓存，避免反复 IPC。
     */
    public static String packageLabel(final PackageManager pm, final String pkg) {
        synchronized (sPkgLabelLock) {
            String cached = sPkgLabelCache.get(pkg);
            if (cached != null) return cached;
        }
        sPkgLabelPool.execute(() -> {
            String label;
            try {
                label = String.valueOf(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).loadLabel(pm));
            } catch (Exception e) {
                label = pkg;
            }
            synchronized (sPkgLabelLock) {
                sPkgLabelCache.put(pkg, label);
            }
        });
        return pkg;
    }
}
