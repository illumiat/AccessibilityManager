package com.accessibilitymanager

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Process

/**
 * per-服务定期重启配置（方案 §四）：enabled + periodMin + lastRestart，
 * SharedPreferences 持久化；pendingEnable 为 per-service 集合（每项附时间戳防陈旧残留）
 * 【盲审修订 P0 / 二轮修订】；enable 失败落盘可感知【二轮修订 P1】。
 *
 * ## 转换说明（行为不变，三点必须守住）
 *
 * 1. **锁身份**：原实现是 `synchronized (RestartPrefs.class)` —— 锁对象是**类的 Class 对象**。
 *    故 Kotlin 侧一律写 `synchronized(RestartPrefs::class.java)`。
 *    ⚠️ **不得改成 `Companion`**：那是另一个对象，锁覆盖范围会变（缺陷清单模式 6）。
 *    此处的 11 处类级锁是有意的全局串行（daemon 主线程 / Worker / UI 三方并发读改写），
 *    不是顺手写法 —— 「怪癖记录：刻意设计」。
 * 2. **`Config` 字段被 Java 直接访问**（`cfg.enabled` / `cfg.periodMin` / `cfg.lastRestart`），
 *    故三个字段必须 `@JvmField`。（原同段提到的 `COLON` 已随串切分收口删除；
 *    且「被 Java 静态访问」的理由已随 `HomeFragment` Kotlin 化而消失。）
 * 3. **`split(":")` 语义已核对**：Java 会丢弃尾部空段、Kotlin 保留。现已把切分**收口进 [splitIds]**
 *    （跳过空段），[isEnabledIn] / [removeService] 亦经它取段 —— 全文件只剩一处切分实现，
 *    该 Java/Kotlin 差异随之在单点被吸收。
 */
class RestartPrefs private constructor() {

    /**
     * 定期重启配置。
     *
     * ⚠️ 必须声明在**外层类**而不是 `companion` 内：Java 调用方以 `RestartPrefs.Config` 引用它，
     * 放进 companion 就变成 `RestartPrefs.Companion.Config`，Java 侧直接「找不到符号」。
     * 字段被 Java 直接读写（`cfg.enabled` 等），故全部 `@JvmField`。
     */
    class Config {
        @JvmField
        var enabled: Boolean = false

        @JvmField
        var periodMin: Long = 0

        @JvmField
        var lastRestart: Long = 0
    }

    companion object {

        /** 周期边界：30 分钟 ~ 30 天（Q15） */
        const val MIN_PERIOD_MIN = 30L
        const val MAX_PERIOD_MIN = 30L * 24 * 60

        /** 默认周期：24 小时（方案未定默认值，取最简实现，见报告标注） */
        const val DEFAULT_PERIOD_MIN = 24L * 60

        /** pendingEnable 残留超过 24h 视为陈旧，清除并标记失败 */
        private const val PENDING_STALE_MS = 24L * 60 * 60 * 1000

        /** "已自动恢复"角标：SP key 前缀 + 显示窗口（daemonService 写 / ServiceAdapter 读）【MISSING 11】 */
        const val AUTO_RESTORED_PREFIX = "last_auto_restored."
        const val AUTO_RESTORED_WINDOW_MS = 5L * 60 * 1000

          private fun sp(c: Context): SharedPreferences =
            c.applicationContext.getSharedPreferences("restart", 0)

        @JvmStatic
        fun get(c: Context, serviceId: String): Config? {
            val p = sp(c)
            if (!p.getBoolean("$serviceId.enabled", false)) return null
            val cfg = Config()
            cfg.enabled = true
            cfg.periodMin = p.getLong("$serviceId.period", DEFAULT_PERIOD_MIN)
            cfg.lastRestart = p.getLong("$serviceId.last", 0)
            return cfg
        }

        /** 开启配置：lastRestart 初值 = 配置启用时刻【二轮修订】 */
        @JvmStatic
        fun enable(c: Context, serviceId: String, periodMin: Long) {
            sp(c).edit()
                .putBoolean("$serviceId.enabled", true)
                .putLong("$serviceId.period", clampPeriod(periodMin))
                .putLong("$serviceId.last", System.currentTimeMillis())
                .apply()
        }

        @JvmStatic
        fun disable(c: Context, serviceId: String) {
            sp(c).edit().putBoolean("$serviceId.enabled", false).apply()
            clearFailed(c, serviceId)
        }

        @JvmStatic
        fun setPeriod(c: Context, serviceId: String, periodMin: Long) {
            sp(c).edit().putLong("$serviceId.period", clampPeriod(periodMin)).apply()
        }

        /**
         * 【R1】读取最近一次设定的周期（含未启用态）：disable 只翻转 .enabled、保留 .period，
         * get() 在未启用态返回 null；详情卡"修改周期→关→再开"需以最近设定周期恢复，防新周期被默认值静默回退。
         */
        @JvmStatic
        fun peekLastPeriod(c: Context, serviceId: String): Long =
            sp(c).getLong("$serviceId.period", DEFAULT_PERIOD_MIN)

        @JvmStatic
        fun markRestarted(c: Context, serviceId: String) {
            sp(c).edit().putLong("$serviceId.last", System.currentTimeMillis()).apply()
        }

        /**
         * 全部已启用的定期重启服务 id —— 「`.enabled` 键扫描」的唯一实现。
         *
         * 曾有三份重复实现（本函数前身 [enabledCount]、`HomeFragment.enabledRestartIds`、
         * `RestartWorker.executeDue` 内联），语义同为「`.enabled` 后缀且值为真 -> 前缀即 id」。
         *
         * 判真口径取 `java.lang.Boolean.TRUE == value`（与旧 enabledCount / Worker 内联一致）：
         * 若某键存的不是 Boolean，静默算作 false，而非 `getBoolean` 抛 `ClassCastException`。
         */
        @JvmStatic
        fun enabledIds(c: Context): List<String> {
            val out = ArrayList<String>()
            for ((key, value) in sp(c).all) {
                if (key.endsWith(".enabled") && java.lang.Boolean.TRUE == value) {
                    out.add(key.substring(0, key.length - ".enabled".length))
                }
            }
            return out
        }

        /** 已启用配置数（惰性调度判据：为 0 时取消周期任务）。 */
        @JvmStatic
        fun enabledCount(c: Context): Long = enabledIds(c).size.toLong()

        /**
         * 周期单位换算因子（索引即单位：0=分钟、1=小时、2=天）——
         * 「整除取最大单位」这条规则的**唯一实现**见 [periodUnitIndex]。
         */
        @JvmField
        val UNIT_FACTORS: LongArray = longArrayOf(1L, 60L, 1440L)

        /**
         * 「周期取大单位」的**唯一实现**：整天 > 整小时 > 分钟（返回 [UNIT_FACTORS] 的索引）。
         *
         * 曾有两份同构实现（`HomeFragment.formatPeriod` 的 `% 1440`/`% 60` 判定、
         * `PeriodDialog.initialUnitIndex` 的 when 分支）。文案资源仍由调用方注入。
         */
        @JvmStatic
        fun periodUnitIndex(periodMin: Long): Int = when {
            periodMin % UNIT_FACTORS[2] == 0L -> 2
            periodMin % UNIT_FACTORS[1] == 0L -> 1
            else -> 0
        }

        /** 卸载清理：移除该服务全部配置（含置顶标记）【二轮修订 P2】 */
        @JvmStatic
        fun removeCompletely(c: Context, serviceId: String) {
            // 【MAJOR 6】pending 读改写全程持锁，与 daemon/Worker 线程互斥
            synchronized(RestartPrefs::class.java) {
                val p = sp(c)
                val e = p.edit()
                    .remove("$serviceId.enabled")
                    .remove("$serviceId.period")
                    .remove("$serviceId.last")
                    .remove(AUTO_RESTORED_PREFIX + serviceId) // 【M-a】补清"已自动恢复"时间戳键，防重装后误显角标
                // 【P3】失败集存于 "failed" StringSet（markFailed M3 持锁实现）；原清除的
                // serviceId+".failed" 是从未写入的死键 → 卸载服务后失败集残留，banner_failed 与
                // 设置页告警常驻误报。改为从 StringSet 按 id 移除（同锁同一次 apply 内完成）。
                val failed = HashSet(p.getStringSet("failed", HashSet())!!)
                failed.remove(serviceId)
                e.putStringSet("failed", failed)
                val pending = HashSet(getPendingEnables(c).keys)
                pending.remove(serviceId)
                e.putStringSet("pending", serializePending(pending))
                e.remove("pending.ts.$serviceId") // 【MINOR 17】同步删除 pending 时间戳键
                e.apply()
            }
            // 置顶/保活标记在 "data" SP【M4：pruneTop/pruneDaemon 持锁读改写，防与 UI/Worker 并发互踩】
            // 【F2】卸载分支承担 daemon 串清理语义（Worker 成功路径的 pruneDaemon 已删，防误清保活锁）
            pruneDaemon(c, serviceId)
            pruneTop(c, serviceId)
        }

        // ---------- pendingEnable（中断补偿）【盲审修订 P0 / 二轮修订：集合化 + 时间戳】 ----------

        @JvmStatic
        fun addPendingEnable(c: Context, serviceId: String) {
            // 【MAJOR 6】pending 读改写全程持锁（集合与时间戳同一次 apply 落盘）
            synchronized(RestartPrefs::class.java) {
                val m = getPendingEnables(c)
                m[serviceId] = System.currentTimeMillis()
                sp(c).edit()
                    .putStringSet("pending", serializePending(m.keys))
                    .putLong("pending.ts.$serviceId", System.currentTimeMillis())
                    .apply()
            }
        }

        @JvmStatic
        fun removePendingEnable(c: Context, serviceId: String) {
            // 【MAJOR 6】pending 读改写全程持锁
            synchronized(RestartPrefs::class.java) {
                val m = getPendingEnables(c)
                m.remove(serviceId)
                sp(c).edit().putStringSet("pending", serializePending(m.keys))
                    .remove("pending.ts.$serviceId")
                    .apply()
            }
        }

        /** @return serviceId → 标志写入时间戳 */
        @JvmStatic
        fun getPendingEnables(c: Context): MutableMap<String, Long> {
            // 【MAJOR 6】读侧同样持锁，保证与写侧互斥
            synchronized(RestartPrefs::class.java) {
                val p = sp(c)
                val result = HashMap<String, Long>()
                val raw = p.getStringSet("pending", null)
                if (raw != null) {
                    for (id in raw) {
                        result[id] = p.getLong("pending.ts.$id", 0)
                    }
                }
                return result
            }
        }

        @JvmStatic
        fun pendingStaleMs(): Long = PENDING_STALE_MS

        private fun serializePending(ids: Set<String>): HashSet<String> = HashSet(ids)

        // ---------- enable 失败告警【二轮修订 P1】 ----------

        @JvmStatic
        fun markFailed(c: Context, serviceId: String) {
            // 【M3】与 pending 一致全程持锁，读改写合并单次 apply（daemon 主线程/Worker/UI 三方并发防丢更新）
            synchronized(RestartPrefs::class.java) {
                val p = sp(c)
                val failed = HashSet(p.getStringSet("failed", HashSet())!!)
                failed.add(serviceId)
                p.edit().putStringSet("failed", failed).apply()
            }
        }

        @JvmStatic
        fun clearFailed(c: Context, serviceId: String) {
            // 【M3】与 pending 一致全程持锁，读改写合并单次 apply
            synchronized(RestartPrefs::class.java) {
                val p = sp(c)
                val failed = HashSet(p.getStringSet("failed", HashSet())!!)
                failed.remove(serviceId)
                p.edit().putStringSet("failed", failed).apply()
            }
        }

        @JvmStatic
        fun getFailed(c: Context): HashSet<String> {
            // 【M3】读侧同样持锁，保证与写侧互斥（与 getPendingEnables 同模式）
            synchronized(RestartPrefs::class.java) {
                val failed = sp(c).getStringSet("failed", null)
                return if (failed != null) HashSet(failed) else HashSet()
            }
        }

        // ---------- PACKAGE_USAGE_STATS 授权检查【P6-e】 ----------

        /**
         * 【P6-e】appops 授权检查单一实现（原 SettingsFragment / RestartWorker 双实现分叉，
         * 两处统一调用本方法）；经 applicationContext 获取，进程内任意线程/组件可用。
         */
        @JvmStatic
        fun usageStatsGranted(c: Context): Boolean {
            return try {
                val ops = c.applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager?
                if (ops == null) {
                    false
                } else {
                    val mode = ops.checkOpNoThrow(
                        "android:package_usage_stats",
                        Process.myUid(),
                        c.packageName,
                    )
                    mode == AppOpsManager.MODE_ALLOWED
                }
            } catch (e: Exception) {
                false
            }
        }

        // ---------- 设置串纯函数（Worker 与 daemonService 共用，与 UI 开关逻辑一致） ----------

        /** 向已开启服务串前插目标（与 Worker tryEnable 同构；UI 开关开启路径统一入口）【MAJOR 10】 */
        @JvmStatic
        fun prependService(settingValue: String, serviceId: String): String = "$serviceId:$settingValue"

        /** 服务 id 的展开形态（flattenToShortString："pkg/.Cls" → "pkg/pkg.Cls"）；非 "pkg/cls" 形态原样返回 */
        private fun expandedForm(serviceId: String): String {
            val slash = serviceId.indexOf('/')
            return if (slash > 0) {
                serviceId.substring(0, slash) + "/" + serviceId.substring(0, slash) + serviceId.substring(slash + 1)
            } else {
                serviceId
            }
        }

        /**
         * 【M1】精确判定 serviceId 是否在设置串中：settingValue 按 ":" 切段，
         * 每段与 serviceId 精确相等，或该段等于展开形态 "pkg/pkg.cls" 时精确相等。
         * 替代原 contains 子串误判（"pkg/.Svc" 是 "pkg/.SvcExtra" 的子串）。
         */
        @JvmStatic
        fun isEnabledIn(settingValue: String?, serviceId: String?): Boolean {
            if (settingValue.isNullOrEmpty() || serviceId.isNullOrEmpty()) return false
              val expanded = expandedForm(serviceId)
              for (seg in splitIds(settingValue)) {
                  if (seg == serviceId || seg == expanded) return true
              }
            return false
        }

        /** 从已开启服务串中移除目标【M2：按 ":" 段边界过滤重建，替代 replace 子串链（"pkg/.SvcX:pkg/.Svc" 不再残留 "X:" 垃圾段）】 */
        @JvmStatic
        fun removeService(settingValue: String, serviceId: String): String {
              val expanded = expandedForm(serviceId)
              val kept = ArrayList<String>()
              for (seg in splitIds(settingValue)) {
                  if (seg == serviceId || seg == expanded) continue
                  kept.add(seg)
              }
            return kept.joinToString(":")
        }

        @JvmStatic
        fun containsService(settingValue: String?, serviceId: String?): Boolean {
            // 【M1】收口到本类 isEnabledIn（原经 ServiceAdapter.isEnabledIn 中转，收口后反向会循环调用）
            return isEnabledIn(settingValue, serviceId)
        }

        @JvmStatic
        fun clampPeriod(periodMin: Long): Long =
            Math.max(MIN_PERIOD_MIN, Math.min(MAX_PERIOD_MIN, periodMin))

        // ---------- "data" SP daemon/top 串收口【M4：daemon 主线程 / Worker / UI 三方并发，读改写全程持锁单次 apply】 ----------

        private fun dataSp(c: Context): SharedPreferences =
            c.applicationContext.getSharedPreferences("data", 0)

        /** ":" 连接的 id 串 -> 段列表（跳过空段）—— 串切分的唯一实现。 */
        fun splitIds(colonJoined: String): MutableList<String> {
            val out = ArrayList<String>()
            for (id in colonJoined.split(":")) {
                if (id.isNotEmpty()) out.add(id)
            }
            return out
        }

        /** 切换保活锁定（daemon 串）；@return 切换后是否处于锁定态 */
        @JvmStatic
        fun toggleDaemon(c: Context, serviceId: String): Boolean {
            synchronized(RestartPrefs::class.java) {
                val data = dataSp(c)
                val ids = splitIds(data.getString("daemon", "")!!)
                val nowOn: Boolean
                if (ids.contains(serviceId)) {
                    ids.remove(serviceId)
                    nowOn = false
                } else {
                    ids.add(0, serviceId) // 前插，与旧 UI 语义一致（新锁定项排最前）
                    nowOn = true
                }
                data.edit().putString("daemon", ids.joinToString(":")).apply()
                return nowOn
            }
        }

        /** 切换置顶（top 串）；@return 切换后是否处于置顶态 */
        @JvmStatic
        fun toggleTop(c: Context, serviceId: String): Boolean {
            synchronized(RestartPrefs::class.java) {
                val data = dataSp(c)
                val ids = splitIds(data.getString("top", "")!!)
                val nowOn: Boolean
                if (ids.contains(serviceId)) {
                    ids.remove(serviceId)
                    nowOn = false
                } else {
                    ids.add(0, serviceId) // 前插：sortDisplay 的 indexOf 排序依赖 top 串顺序
                    nowOn = true
                }
                data.edit().putString("top", ids.joinToString(":")).apply()
                return nowOn
            }
        }

        /** 清理 daemon 串中已失效项（按 ":" 段精确移除，无子串误伤） */
        @JvmStatic
        fun pruneDaemon(c: Context, serviceId: String) {
            synchronized(RestartPrefs::class.java) {
                val data = dataSp(c)
                val ids = splitIds(data.getString("daemon", "")!!)
                if (!ids.contains(serviceId)) return
                ids.remove(serviceId)
                data.edit().putString("daemon", ids.joinToString(":")).apply()
            }
        }

        /** 清理 top 串中已失效项（按 ":" 段精确移除，无子串误伤） */
        @JvmStatic
        fun pruneTop(c: Context, serviceId: String) {
            synchronized(RestartPrefs::class.java) {
                val data = dataSp(c)
                val ids = splitIds(data.getString("top", "")!!)
                if (!ids.contains(serviceId)) return
                ids.remove(serviceId)
                data.edit().putString("top", ids.joinToString(":")).apply()
            }
        }
    }
}
