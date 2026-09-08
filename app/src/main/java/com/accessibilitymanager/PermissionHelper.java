package com.accessibilitymanager;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import rikka.shizuku.Shizuku;

/** 安全设置写入权限的判定与三条授权路径（ADB 复制 / root / Shizuku），主页与设置页共用 */
public final class PermissionHelper {

    private PermissionHelper() {
    }

    /** @return true = 已授予 */
    public static boolean hasWritePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        }
        PackageInfo info = new PackageInfo();
        try {
            info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_CONFIGURATIONS);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return info.applicationInfo != null
                && (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /** 未授权时的三选一引导对话框（迁移自旧 createPermissionDialog） */
    public static void showPermissionDialog(Context context) {
        String cmd = "pm grant " + context.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.perm_dialog_title)
                .setMessage(context.getString(R.string.perm_dialog_message, cmd))
                .setPositiveButton(R.string.action_copy_command, (d, w) -> {
                    copyToClipboard(context, "adb shell " + cmd);
                    toast(context, R.string.toast_command_copied);
                })
                .setNegativeButton(R.string.action_root_activate, (d, w) -> grantViaRoot(context, cmd))
                .setNeutralButton(R.string.action_shizuku_activate, (d, w) -> grantViaShizuku(context))
                .show();
    }

    /** root 路径：后台线程执行 su + pm grant */
    public static void grantViaRoot(Context context, String cmd) {
        new Thread(() -> {
            boolean ok = false;
            try {
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream o = new DataOutputStream(p.getOutputStream());
                o.writeBytes(cmd + "\nexit\n");
                o.flush();
                o.close();
                p.waitFor();
                ok = p.exitValue() == 0;
            } catch (IOException | InterruptedException ignored) {
            }
            final boolean success = ok;
            new Handler(Looper.getMainLooper()).post(
                    () -> toast(context, success ? R.string.toast_activate_ok : R.string.toast_activate_fail));
        }).start();
    }

    /** Shizuku 路径（迁移自旧 check()） */
    public static void grantViaShizuku(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (hasWritePermission(context)) return;
        boolean proceed = true, granted = false;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
            } else {
                granted = true;
            }
        } catch (Exception e) {
            if (context.checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }
            if (e instanceof IllegalStateException) {
                proceed = false;
                toast(context, R.string.toast_shizuku_not_running);
            }
        }
        if (proceed && granted) {
            // 【MAJOR 5】newProcess + waitFor 移后台线程（比照 grantViaRoot），结果回主线程 Toast 防 ANR
            new Thread(() -> {
                boolean ok = false;
                try {
                    Process p = Shizuku.newProcess(new String[]{"sh"}, null, null);
                    OutputStream out = p.getOutputStream();
                    out.write(("pm grant " + context.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\nexit\n").getBytes());
                    out.flush();
                    out.close();
                    p.waitFor();
                    ok = p.exitValue() == 0;
                } catch (IOException | InterruptedException ignored) {
                }
                final boolean success = ok;
                new Handler(Looper.getMainLooper()).post(
                        () -> toast(context, success ? R.string.toast_activate_ok : R.string.toast_activate_fail));
            }).start();
        }
    }

    static void copyToClipboard(Context context, String text) {
        ((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("c", text));
    }

    static void toast(Context context, int resId) {
        Toast.makeText(context.getApplicationContext(), resId, Toast.LENGTH_SHORT).show();
    }
}
