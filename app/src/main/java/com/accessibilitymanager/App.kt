package com.accessibilitymanager

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application。
 *
 * 两件事，顺序即原实现顺序（**不得调整**）：主题三态 → 惰性调度。
 *
 * 动态取色不再在进程级注册：原 `DynamicColors.applyToActivitiesIfAvailable` 会注册**不可注销**的
 * `ActivityLifecycleCallbacks`，无法按当前持久化主题在运行期双向切换；改为在 `MainActivity.onCreate`
 * 按当次主题对本 Activity 应用一次（见 `MainActivity`）。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // 在任何 Activity 创建前应用一次持久化的主题三态，防启动闪烁；
        // 「未设置（SP 无该键）则不覆盖默认主题」语义由 nightModeOrNull 保证。
        ThemePref.nightModeOrNull(this)?.let { setThemeMode(it) }
        // 【惰性调度】仅在存在已启用的定期重启配置时注册周期任务；
        // 全部关闭时零主动唤醒（与 README「默认关闭，开启后按需调度」承诺一致）
        if (RestartPrefs.enabledCount(this) > 0) {
            RestartWorker.schedule(this)
        }
    }

    companion object {
        /** 主题三态：0=跟随系统 1=浅色 2=深色 */
        @JvmStatic
        fun setThemeMode(mode: Int) {
            when (mode) {
                1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }
}
