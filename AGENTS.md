# AGENTS.md — AccessibilityManager（MD3 重构）

无障碍服务管理工具：纯 Java + View 体系 + Material Components 1.13.0（M3）+ WorkManager。minSdk 24 / targetSdk 33 / compileSdk 34。

## 必读

1. `.codebuddy/rules/delegation-spec.md` — 子代理发配、git 隔离验收、报告用词规范（最终事实源）
2. 仓库外 `../md3-redesign-consensus.md` — 方案 v3.1（锁定），实施唯一依据；【盲审修订】标记为硬约束
3. `../ui-prototype/index.html` — UI 参考原型

## 设计意图（改代码前必读，冲突即停手上报）

- 架构：`MainActivity` 仅是容器（双 Fragment show/hide 保状态），业务在 `HomeFragment`/`SettingsFragment`
- `tmpSettingValue`：写 `Settings.Secure` 前后区分"自己写/外部改"，防 ContentObserver 自触发循环；新增写路径必须维护
- daemon 抢跑（1.5s 窗口回写 enable）是预期边界，不是 bug
- 焦点判定语义：目标应用前台绝不闪断（UsageStats / `MOVE_TO_FOREGROUND`）
- `pendingEnable` 是崩溃补偿；enable 失败必须 `markFailed` 可感知
- appops 用字符串 op（int 常量为隐藏 API）；TouchDelegate 视觉 40dp + 命中 48dp 是刻意设计
- `drawable-v21/v23/v24` 是图标矢量 fallback，保留；targetSdk 33、默认周期 24h、工具链 AGP 8.7.3/Gradle 8.9（JDK 21 强制）均为用户知情决策

## 构建

- gradlew 构建/打包由用户执行，AI 侧验收以 diff 审查 + lint 为准（见 delegation-spec）
