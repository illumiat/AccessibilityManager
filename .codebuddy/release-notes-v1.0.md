# 无障碍管理器 MD3 重构版 1.0

- 全新 Material Design 3 界面（浅 / 深主题 + 动态取色）
- 手机 / 平板自适应网格（1–3 列，宽度驱动）
- 服务搜索
- 每服务定期自动重启（WorkManager，仅灭屏执行，不耗电）
- 详情卡片：服务能力 / 事件解析 + 周期设置
- 最低系统：Android 7.0（原为 5.0）

## 安装

下载下方 APK 直接安装（debug 签名版）。安装后需通过 ADB / root / Shizuku 授予 WRITE_SECURE_SETTINGS 权限：

```
adb shell pm grant com.accessibilitymanager android.permission.WRITE_SECURE_SETTINGS
```
