# 无障碍管理器（MD3 重构版）

本 APP 可以彻底取代系统设置里的无障碍设置页面。仅需授权本 APP 写入安全设置即可使用。保活为事件驱动守护（无轮询、无定时唤醒），且保活速度极快；可选的定期重启功能默认关闭，开启后按需调度。

## 本分支：MD3 重构版 1.0

- 全新 Material Design 3 界面（浅 / 深主题 + 动态取色）
- 手机 / 平板自适应网格（1–3 列，宽度驱动）
- 服务搜索
- 每服务定期自动重启（WorkManager，仅灭屏执行，不耗电）
- 详情卡片：服务能力 / 事件解析 + 周期设置
- 最低系统：**Android 7.0**（原为 5.0）

## 使用

1. 安装 APK（见 Releases）
2. 授权写入安全设置（三选一）：

   ```bash
   adb shell pm grant com.accessibilitymanager android.permission.WRITE_SECURE_SETTINGS
   ```

   或 root 执行同命令，或通过 Shizuku 授权

3. 正常使用
