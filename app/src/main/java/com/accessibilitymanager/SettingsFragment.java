package com.accessibilitymanager;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 设置页（方案 §二）：保活组（boot/toast/hide，hide 改即生效）/ 定期重启组（日志通知开关+
 * 概览行+策略说明）/ 授权与帮助组（授权状态+判定模式+通知权限+重新授权引导+使用说明）/
 * 外观组（主题三态）/ 版本行 v9.0。
 */
public class SettingsFragment extends Fragment {

    private SharedPreferences sp;
    private MaterialSwitch swBoot, swToast, swHide, swRestartNotify;
    private TextView tvRestartSummary, tvAuthStatus, tvJudgement, tvNotification;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = requireContext().getSharedPreferences("data", 0);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        swBoot = view.findViewById(R.id.sw_boot);
        swToast = view.findViewById(R.id.sw_toast);
        swHide = view.findViewById(R.id.sw_hide);
        swRestartNotify = view.findViewById(R.id.sw_restart_notify);
        tvRestartSummary = view.findViewById(R.id.tv_restart_summary);
        tvAuthStatus = view.findViewById(R.id.tv_auth_status);
        tvJudgement = view.findViewById(R.id.tv_judgement);
        tvNotification = view.findViewById(R.id.tv_notification);

        swBoot.setChecked(sp.getBoolean("boot", true));
        swToast.setChecked(sp.getBoolean("toast", true));
        swHide.setChecked(sp.getBoolean("hide", true));
        swRestartNotify.setChecked(sp.getBoolean("restart_notify", true));

        swBoot.setOnCheckedChangeListener((b, checked) -> sp.edit().putBoolean("boot", checked).apply());
        swToast.setOnCheckedChangeListener((b, checked) -> sp.edit().putBoolean("toast", checked).apply());
        // 【盲审修订】hide 改动即时生效
        swHide.setOnCheckedChangeListener((b, checked) -> {
            sp.edit().putBoolean("hide", checked).apply();
            applyHide(checked);
        });
        swRestartNotify.setOnCheckedChangeListener((b, checked) -> sp.edit().putBoolean("restart_notify", checked).apply());

        view.findViewById(R.id.row_theme).setOnClickListener(v -> showThemeDialog());

        view.findViewById(R.id.row_regrant).setOnClickListener(v ->
                PermissionHelper.showPermissionDialog(requireContext()));

        view.findViewById(R.id.row_help).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.usage_help)
                        .setMessage(R.string.usage_help_detail)
                        .setPositiveButton(R.string.action_ok, null)
                        .show());

        tvNotification.setOnClickListener(v -> openNotificationSettings());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAuthStatus();
        updateRestartSummary();
    }

    private void refreshAuthStatus() {
        boolean granted = PermissionHelper.hasWritePermission(requireContext());
        tvAuthStatus.setText(granted ? R.string.auth_status_ok : R.string.auth_status_missing);
        // 判定模式：appops 每次/每页校验（PACKAGE_USAGE_STATS 可被 ROM 重置）【三轮 P1 修正】
        // 【P6-e】收口 RestartPrefs 单一实现（原与本类私有方法双实现分叉）
        tvJudgement.setText(RestartPrefs.usageStatsGranted(requireContext())
                ? R.string.auth_judgement_focus : R.string.auth_judgement_degraded);
        NotificationManager nm = (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        // 【M-d】系统服务可能取不到，判 null 防 NPE
        boolean notifOk = nm != null && nm.areNotificationsEnabled();
        tvNotification.setText(notifOk ? R.string.auth_notification_ok : R.string.auth_notification_missing);
        // 恢复失败告警【二轮修订 P1】
        TextView failedAlert = requireView().findViewById(R.id.tv_auth_failed_alert);
        failedAlert.setVisibility(RestartPrefs.getFailed(requireContext()).isEmpty() ? View.GONE : View.VISIBLE);
    }

    void updateRestartSummary() {
        // 已启用定期重启服务数概览（缓解入口较深）【二轮修订 P0】
        tvRestartSummary.setText(getString(R.string.setting_restart_summary, RestartPrefs.enabledCount(requireContext())));
    }

    private void applyHide(boolean hide) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ((ActivityManager) requireContext().getSystemService(Context.ACTIVITY_SERVICE))
                        .getAppTasks().get(0).setExcludeFromRecents(hide);
            }
        } catch (Exception ignored) {
        }
    }

    private void openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName()));
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.toast_activate_fail, Toast.LENGTH_SHORT).show();
        }
    }

    /** 外观组：主题三态（跟随系统/浅色/深色）→ AppCompatDelegate.setDefaultNightMode */
    private void showThemeDialog() {
        int current = sp.getInt("theme", 0);
        String[] items = new String[]{
                getString(R.string.theme_follow_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_theme)
                .setSingleChoiceItems(items, current, (dialog, which) -> {
                    sp.edit().putInt("theme", which).apply();
                    App.setThemeMode(which);
                    dialog.dismiss();
                })
                .setPositiveButton(R.string.action_ok, null) // 【MINOR 20】OK 按钮原先误挂 negativeButton
                .show();
    }
}
