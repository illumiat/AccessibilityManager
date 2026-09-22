package com.accessibilitymanager

import android.content.Context
import android.provider.Settings

/**
 * 「向 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 落一笔」的**唯一实现**。
 *
 * ## 接缝与 depth
 *
 * 调用者只需知道一件事：**`commit(c) { 现值 -> 新值 }`**。至于写前同步镜像、写、读回校准、
 * 异常回滚这四步**全部藏在 implementation 里**。3 个写入方（UI 开关 / daemon 恢复 / Worker 重启补偿）
 * = **两个以上 adapter = 真实接缝**，而接缝上此前**没有 module**：四步协议散在 5 处、
 * 镜像还跨两个类两种归属（`daemonService.tmpSettingValue` 静态 + `HomeFragment.tmpSettingValue` 实例）。
 *
 * ## 镜像（写方身份识别）为什么必须在写前同步
 *
 * ContentObserver 无法直接判断「本次变化是不是自己写的」。故写前把「即将写入的值」存进 [mirror]，
 * 观察者读到 `s == mirror` 即认定为自写、跳过回写 —— 否则会把自己刚写入的值当外部改动，
 * 触发一轮无谓的 `doDaemon`（在 Worker 的 1.5s 重启窗口里，还会把 disabled 中的服务提前 enable）。
 *
 * **读回校准不可省**：写入可能被系统改写（或 `putString` 静默失败），镜像必须以**读回的实际值**为准，
 * 否则镜像失真会让观察者对后续的**外部**变化误判为自写而漏掉恢复。
 *
 * ## 与「设置串纯函数」的分工
 *
 * 本 module 只管「**落一笔**」这件事（读 / 写 / 镜像）；串的**词法**（`:` 段切分、短/展开形态等价、
 * 前置 / 移除）全在 [RestartPrefs] 的纯函数面 —— 两者一个管「怎么改」、一个管「怎么落笔」。
 *
 * ## 可测试性
 *
 * 调用方与测试穿过同一条 seam（`commit`）：镜像同步、读回校准、异常回滚**首次可被测试触达**。
 * 在此之前它们是 `daemonService` / `RestartWorker` 的 private 步骤，**穿不过任何 interface**。
 */
object SettingValueWriter {

    /**
     * 写方镜像：**即将写入 / 最近读回**的设置串。
     *
     * 观察者以 `s == mirror` 判定「自己写的」。写前同步、写后以读回值校准、异常回滚为实际值 ——
     * 三处同步**一处都不能少**（原分散在 5 处手写时漏一处就会失真）。
     */
    @JvmField
    @Volatile
    var mirror: String = ""

    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    /** 读当前设置串（`null` 归一为 `""`，与各调用点原口径一致）。 */
    fun read(c: Context): String {
        val s = Settings.Secure.getString(c.contentResolver, KEY)
        return s ?: ""
    }

    /**
     * 提交一次变换：**镜像同步 → 写 → 读回校准**；写入异常时**镜像回滚为实际值后原样抛出**，
     * 交回调用方做各自的失败 UX（Toast / markFailed / 跳过本轮）。
     *
     * @param transform 现值 → 新值（`[RestartPrefs]` 的纯函数在这里组合，调用方不碰串语法）
     * @return **读回的实际值**（不是想写入的值 —— 系统可能改写）
     * @throws Exception 写入失败；抛出前镜像已回滚，调用方不必再回滚
     */
    /**
     * 提交互斥锁：read → transform → putString → 读回校准 是**一条不可分割的序列**。
     * 三个写入方分属不同线程（UI / Service 主线程 / Worker 线程），不加锁会后写覆盖前写、
     * 且 mirror 被交错赋值（B 的写前同步可能被 A 的读回校准覆盖成旧值）→ 观察者误判自写而漏恢复。
     */
    private val commitLock = Any()

    fun commit(c: Context, transform: (String) -> String): String = synchronized(commitLock) {
        val cur = read(c)
        val newValue = transform(cur)
        // 【F1/R3】写前同步镜像：让观察者把本次变化识别为「自己写的」
        mirror = newValue
        try {
            // 【A 级修复】必须接住返回值：无 WRITE_SECURE_SETTINGS 时 putString 可能**不抛异常而静默返回 false**，
            // 此时读回只是旧值，若当成功返回会让调用方误判、且 mirror 被校准成旧值 → 后续外部变化被误判为自写。
            val ok = Settings.Secure.putString(c.contentResolver, KEY, newValue)
            if (!ok) throw IllegalStateException("putString returned false for $KEY")
        } catch (e: Exception) {
            // 镜像回滚为实际值，防镜像失真致观察者对后续外部变化误跳过（与 daemon 侧 R3 同源）
            mirror = try {
                read(c)
            } catch (ignored: Exception) {
                ""
            }
            throw e
        }
        // 以读回实际值校准镜像（写入可能被改写 / 静默失败；空串亦为真实读回值，须照实校准）
        val after = read(c)
        mirror = after
        return after
    }
}
