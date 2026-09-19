package com.accessibilitymanager

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 图标缓存（方案 §三 六条硬条款）：
 * 内存 LruCache（maxMemory/8，key=服务id|lastUpdateTime）+ 后台线程池 + in-flight 合并
 * + 48dp 降采样 + 绑定期零回源（onBind 只读缓存）+ 可见优先/滑动暂停预加载。
 *
 * ## 【K1】Java → Kotlin 转换说明（行为不变的硬约束）
 *
 * 本文件是 K1 转换产物，**只做语言转换，不含任何行为/结构改动**。以下四点是
 * 「编译器抓不到」的语义等价点，后续改动必须逐条守住：
 *
 * 1. [Entry] 的字段被 Java 侧以**字段方式**访问（`e.loaded` / `e.icon` / …），
 *    故全部标注 `@JvmField` —— 若误用普通属性，Java 侧需要 `getLoaded()`，
 *    编译即失败（这类会暴露）。但 `loaded` 是**跨线程**字段（worker 写、主线程读），
 *    `@Volatile` **不得省略** —— 省略后单机大概率测不出，只在真机时序下偶发。
 * 2. [lock] 的身份必须保持「每个 IconCache 实例一把」，不得改为类级锁或 `@Synchronized`。
 * 3. [sPkgLabelCache] / [sPkgLabelLock] / [sPkgLabelPool] 原为 `private static`，
 *    对应 companion 内成员；静态初始化时机与 Java 一致（类加载时）。
 * 4. `cache.put(e.key, e)` 在 Java 原文中**位于 synchronized 块之外** —— 属现状，
 *    本次转换**原样保留**（不夹带"顺手修正"）。
 */
class IconCache(context: Context) {

    /**
     * 一条缓存项。
     *
     * 字段全部 `@JvmField`：Java 侧（`ServiceAdapter` / `HomeFragment`）直接读字段。
     */
    open class Entry {
        /** 跨线程可见：worker 线程置 true，主线程读。**`@Volatile` 不得省略。** */
        @JvmField
        @Volatile
        var loaded: Boolean = false

        @JvmField
        var icon: Bitmap? = null

        @JvmField
        var placeholder: Bitmap? = null

        @JvmField
        var appLabel: String? = null

        @JvmField
        var serviceLabel: String? = null

        @JvmField
        var description: String? = null
    }

    fun interface LoadCallback {
        fun onLoaded()
    }

    private val appContext: Context = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val iconSizePx: Int =
        Math.round(48 * appContext.resources.displayMetrics.density)
    private val currentKeyByService = HashMap<String, String>()
    private val waiting = HashMap<String, MutableList<LoadCallback>>()
    private val pool: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile
    private var paused: Boolean = false

    private val cache: LruCache<String, Entry> =
        object : LruCache<String, Entry>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Entry): Int =
                if (value.icon != null) value.icon!!.byteCount else 1
        }

    /** 绑定期只读缓存，绝不回源 */
    fun peek(serviceId: String): Entry? {
        synchronized(lock) {
            val key = currentKeyByService[serviceId]
            return if (key != null) cache.get(key) else null
        }
    }

    /**
     * 异步加载图标/标签/描述。回调在主线程触发，调用方（Adapter）须按包名校验后再刷新单行。
     */
    fun load(info: AccessibilityServiceInfo, callback: LoadCallback?) {
        val serviceId = info.id
        synchronized(lock) {
            val key = currentKeyByService[serviceId]
            if (key != null && cache.get(key) != null) {
                callback?.onLoaded()
                return
            }
            val list = waiting[serviceId]
            if (list != null) {
                if (callback != null) list.add(callback)
                return
            }
            val newList = mutableListOf<LoadCallback>()
            if (callback != null) newList.add(callback)
            waiting[serviceId] = newList
        }
        pool.execute {
            val e = loadSync(info)
            val toNotify: List<LoadCallback>?
            synchronized(lock) {
                currentKeyByService[serviceId] = e.key
                toNotify = waiting.remove(serviceId)
            }
            // 现状：put 在锁外（Java 原文如此），本次转换原样保留。
            cache.put(e.key, e)
            if (toNotify != null) {
                for (c in toNotify) main.post { c.onLoaded() }
            }
        }
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    fun isPaused(): Boolean = paused

    fun shutdown() {
        pool.shutdownNow()
    }

    private class Loaded(val key: String) : Entry()

    private fun loadSync(info: AccessibilityServiceInfo): Loaded {
        val serviceId = info.id
        val slash = serviceId.indexOf('/')
        val pkg = if (slash > 0) serviceId.substring(0, slash) else serviceId
        val cls = if (slash > 0) serviceId.substring(slash + 1) else ""
        var lastUpdate = 0L
        try {
            val pi = pm.getPackageInfo(pkg, 0)
            lastUpdate = pi.lastUpdateTime
        } catch (ignored: Exception) {
        }
        val e = Loaded("$serviceId|$lastUpdate")

        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            e.icon = downsample(ai.loadIcon(pm))
            e.appLabel = java.lang.String.valueOf(ai.loadLabel(pm))
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        try {
            val si = pm.getServiceInfo(
                ComponentName(pkg, pkg + cls),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            e.serviceLabel = java.lang.String.valueOf(si.loadLabel(pm))
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        try {
            e.description = info.loadDescription(pm)
        } catch (ignored: Exception) {
        }
        e.loaded = true
        return e
    }

    private fun downsample(d: Drawable): Bitmap {
        val b = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        d.setBounds(0, 0, iconSizePx, iconSizePx)
        d.draw(canvas)
        return b
    }

    companion object {
        @JvmStatic
        fun shortClassName(serviceId: String): String? {
            val slash = serviceId.lastIndexOf('/')
            return if (slash >= 0 && slash + 1 < serviceId.length) serviceId.substring(slash + 1) else serviceId
        }

        // ---------- 静态包名标签缓存（daemonService.doDaemon 守护路径同进程直调）【MISSING 15】 ----------
        private val sPkgLabelCache = LruCache<String, String>(64)
        private val sPkgLabelLock = Any()
        private val sPkgLabelPool: ExecutorService = Executors.newSingleThreadExecutor()

        /**
         * 守护路径取包名展示名：缓存命中零 IPC；miss 后台加载入缓存（不阻塞守护主线程），
         * 本次回退包名；查询失败同样以包名入缓存，避免反复 IPC。
         */
        @JvmStatic
        fun packageLabel(pm: PackageManager, pkg: String): String {
            synchronized(sPkgLabelLock) {
                val cached = sPkgLabelCache.get(pkg)
                if (cached != null) return cached
            }
            sPkgLabelPool.execute {
                // 现状：查询失败以包名入缓存（避免反复 IPC），本次转换原样保留。
                val label: String = try {
                    java.lang.String.valueOf(
                        pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).loadLabel(pm),
                    )
                } catch (e: Exception) {
                    pkg
                }
                synchronized(sPkgLabelLock) {
                    sPkgLabelCache.put(pkg, label)
                }
            }
            return pkg
        }
    }
}
