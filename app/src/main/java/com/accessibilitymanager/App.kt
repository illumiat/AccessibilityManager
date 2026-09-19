package com.accessibilitymanager

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.accessibilitymanager.core.designsystem.theme.ThemeCatalog
import com.google.android.material.color.DynamicColors

/**
 * Application。
 *
 * 三件事，顺序即原实现顺序（**不得调整**）：主题三态 → 动态取色（仅壁纸主题）→ 惰性调度。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        val sp = getSharedPreferences("data", MODE_PRIVATE)
        // 在任何 Activity 创建前应用一次持久化的主题三态，防启动闪烁
        if (sp.contains("theme")) {
            setThemeMode(sp.getInt("theme", ThemePref.NIGHT_FOLLOW_SYSTEM))
        }
        // 「跟随壁纸」是并列选项（不设为默认、不强制）：只有选中它时才叠加运行时动态取色，
        // 否则壁纸配色会盖掉用户选的命名主题。
        if (ThemeCatalog.needsDynamicColor(ThemePref.theme(this))) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
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
