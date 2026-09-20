package com.accessibilitymanager

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 安全设置写入权限的判定与三条授权路径（ADB 复制 / root / Shizuku），主页与设置页共用。
 *
 * ## 转换说明（行为不变）
 *
 * - 静态方法全部进 companion 并标 `@JvmStatic`：调用方（`HomeFragment` / `SettingsFragment`）
 *   仍是 Java，需保持 `PermissionHelper.xxx(...)` 静态调用形态。UI 迁完后可去掉。
 * - **多路 catch 无 Kotlin 等价物**：`catch (IOException | InterruptedException)` 必须拆成
 *   两个 catch 子句，**不得放宽为 `catch (Exception)`** —— 那会吞掉原实现不吞的异常。
 * - `new Thread(...)` + `Handler(Looper.getMainLooper()).post(...)` 原样保留：
 *   线程模型属行为，不得在语言转换中改动。
 */
class PermissionHelper private constructor() {

    companion object {

        /** 构造 "pm grant" 授权命令字符串；Shizuku 路径传 withExit=true 追加 "\nexit\n"（与 root 路径 writeBytes("$cmd\nexit\n") 同形）【重构订正】 */
        private fun grantCommand(context: Context, withExit: Boolean = false): String {
            val base = "pm grant " + context.packageName + " android.permission.WRITE_SECURE_SETTINGS"
            return if (withExit) base + "\nexit\n" else base
        }

        /** @return true = 已授予 */
        @JvmStatic
        fun hasWritePermission(context: Context): Boolean {
            // minSdk 24 ≥ M(23)，SDK_INT >= M 恒真，原 else 分支（PackageInfo / FLAG_SYSTEM 系统应用判定）不可达已删除
            return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }

        /** 未授权时的三选一引导对话框（迁移自旧 createPermissionDialog） */
        @JvmStatic
        fun showPermissionDialog(context: Context) {
            val cmd = grantCommand(context)
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.perm_dialog_title)
                .setMessage(context.getString(R.string.perm_dialog_message, cmd))
                .setPositiveButton(R.string.action_copy_command) { _, _ ->
                    copyToClipboard(context, "adb shell $cmd")
                    toast(context, R.string.toast_command_copied)
                }
                .setNegativeButton(R.string.action_root_activate) { _, _ -> grantViaRoot(context, cmd) }
                .setNeutralButton(R.string.action_shizuku_activate) { _, _ -> grantViaShizuku(context) }
                .show()
        }

        /** root 路径：后台线程执行 su + pm grant */
        @JvmStatic
        fun grantViaRoot(context: Context, cmd: String) {
            Thread {
                var ok = false
                try {
                    val p = Runtime.getRuntime().exec("su")
                    val o = DataOutputStream(p.outputStream)
                    o.writeBytes("$cmd\nexit\n")
                    o.flush()
                    o.close()
                    p.waitFor()
                    ok = p.exitValue() == 0
                } catch (ignored: IOException) {
                } catch (ignored: InterruptedException) {
                }
                val success = ok
                Handler(Looper.getMainLooper()).post {
                    toast(context, if (success) R.string.toast_activate_ok else R.string.toast_activate_fail)
                }
            }.start()
        }

        /** Shizuku 路径（迁移自旧 check()） */
        @JvmStatic
        fun grantViaShizuku(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            if (hasWritePermission(context)) return
            var proceed = true
            var granted = false
            try {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(0)
                } else {
                    granted = true
                }
            } catch (e: Exception) {
                if (context.checkSelfPermission("moe.shizuku.manager.permission.API_V23") ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    granted = true
                }
                if (e is IllegalStateException) {
                    proceed = false
                    toast(context, R.string.toast_shizuku_not_running)
                }
            }
            if (proceed && granted) {
                // 【MAJOR 5】newProcess + waitFor 移后台线程（比照 grantViaRoot），结果回主线程 Toast 防 ANR
                Thread {
                    var ok = false
                    try {
                        val p = Shizuku.newProcess(arrayOf("sh"), null, null)
                        val out: OutputStream = p.outputStream
                        out.write(grantCommand(context, withExit = true).toByteArray())
                        out.flush()
                        out.close()
                        p.waitFor()
                        ok = p.exitValue() == 0
                    } catch (ignored: IOException) {
                    } catch (ignored: InterruptedException) {
                    }
                    val success = ok
                    Handler(Looper.getMainLooper()).post {
                        toast(context, if (success) R.string.toast_activate_ok else R.string.toast_activate_fail)
                    }
                }.start()
            }
        }

        @JvmStatic
        fun copyToClipboard(context: Context, text: String) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("c", text))
        }

        @JvmStatic
        fun toast(context: Context, resId: Int) {
            Toast.makeText(context.applicationContext, resId, Toast.LENGTH_SHORT).show()
        }
    }
}
