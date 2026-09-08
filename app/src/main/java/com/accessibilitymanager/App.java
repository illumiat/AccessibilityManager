package com.accessibilitymanager;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 在任何 Activity 创建前应用一次持久化的主题三态，防启动闪烁（方案 Q12）
        int mode = getSharedPreferences("data", MODE_PRIVATE).getInt("theme", -1);
        if (mode >= 0) setThemeMode(mode);
        // API 31+ 动态取色（方案 §二 主题）
        DynamicColors.applyToActivitiesIfAvailable(this);
        // 【惰性调度】仅在存在已启用的定期重启配置时注册周期任务；
        // 全部关闭时零主动唤醒（与 README"默认关闭，开启后按需调度"承诺一致）
        if (RestartPrefs.enabledCount(this) > 0) {
            RestartWorker.schedule(this);
        }
    }

    /** 主题三态：0=跟随系统 1=浅色 2=深色 */
    public static void setThemeMode(int mode) {
        switch (mode) {
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
