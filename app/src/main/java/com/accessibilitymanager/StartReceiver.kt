package com.accessibilitymanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启接收器。
 *
 * 行为与原 Java 实现逐字一致：读 `data` SP 的 `boot` 开关（默认 true），
 * 满足则拉起前台保活服务（O 及以上用 `startForegroundService`）。
 */
class StartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sharedPreferences = context.getSharedPreferences("data", 0)
        if (sharedPreferences.getBoolean("boot", true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, daemonService::class.java))
            } else {
                context.startService(Intent(context, daemonService::class.java))
            }
        }
    }
}
