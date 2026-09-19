# AGENTS.md — AccessibilityManager（MD3 重构）

无障碍服务管理工具。**业务层 Kotlin**、**UI 层 Jetpack Compose**；Material Components 1.14.0（MDC）作为**过渡期依赖**保留（规范 §10 明确 MDC 非落点，收尾时移除）。
minSdk 24 / targetSdk 33 / compileSdk 35；AGP 8.7.3 / Gradle 8.9（JDK 21 强制）。

> 现状：UI 层已迁 Compose（外壳/主页/设置/详情弹卡），业务层已全量 Kotlin。
> 仅剩 1 个 Java 文件（`HomeFragment.java`：写入路径的落点）与 2 个壳布局。
> **接手前先读 `docs/HANDOVER.md`**（含进度、不可回退约束、欠账、验证能力边界）。

## 必读

1. `.codebuddy/rules/delegation-spec.md` — 子代理发配、git 隔离验收、报告用词规范（**最终事实源**）
2. `docs/HANDOVER.md` — 当前状态、恢复点、不可回退约束
3. `docs/ui-redesign-plan.md` — 执行版规划
4. 仓库外 `../md3-redesign-consensus.md` — 方案 v3.1（锁定）；【盲审修订】标记为硬约束
5. 仓库外 `../ui-prototype/index.html` — UI 参考原型
6. **UI 规范技能**：`C:\Users\illumiat\.workbuddy\skills\design-md3-apple-craft\SKILL.md`（自成一体；任何涉及安卓界面视觉与手感的决定都应先加载）

## 设计意图（改代码前必读，冲突即停手上报）

### 保活与镜像（业务层，改动风险最高）

- `tmpSettingValue`：写 `Settings.Secure` 前后区分"自己写/外部改"，防 ContentObserver 自触发循环；**新增写路径必须维护**
- daemon 抢跑（1.5s 窗口回写 enable）是预期边界，不是 bug
- 焦点判定语义：目标应用前台绝不闪断（UsageStats / `MOVE_TO_FOREGROUND`）
- `pendingEnable` 是崩溃补偿；enable 失败必须 `markFailed` 可感知
- appops 用字符串 op（int 常量为隐藏 API）
- 锁身份不得改变：`RestartPrefs` 的 11 处 `synchronized(RestartPrefs::class.java)` 是**类级锁**
- 线程模型不得改：`Thread` + `Handler(Looper.getMainLooper())` + `SystemClock.sleep(1500)`；**不要换协程**

### 架构与边界

- `MainActivity` 仅是容器（双 Fragment `show()`/`hide()` 保状态，**不用 replace**）
- **Java 类型绝不能进入组合树**：先经 `@Immutable data class` 模型（`ServiceUiModel` / `DetailHeader`+`DetailInfo`+`DetailRestart`），否则 Compose 判 unstable → 跳过重组失效
- **写入路径留在宿主**：开关写 `Settings.Secure`、锁定写 daemon 串、置顶写 top 串、定期重启写 `RestartPrefs` —— 全在 `HomeFragment`；Compose 只发意图（`HomeServiceCallback` / `ServiceDetailCallback`）
- `ComposeView.setContent` **只能调一次**；会变的输入必须是 Compose 状态，不能做 bind 入参快照
- **详情弹卡的内容根必须是 `NestedScrollView`**（不能裸挂 `ComposeView`）；且 Compose 内容**不得再挂 `verticalScroll`** —— 理由见 HANDOVER §5 约束 9/10（一手核实：MDC 按 `isNestedScrollingEnabled()` 找滚动子视图，而 `ComposeView` 不启用嵌套滚动）
- 详情卡的 `ComposeView` 必须用 **Activity 上下文**（弹窗的 dialog 主题 overlay 会干扰 `AppTheme` 的主题属性解析）
- 保留资源：`drawable-v21/v23/v24` 是图标矢量 fallback；`values/colors.xml` 的 `bg`/`fg` 被自适应图标引用 —— **都不要当残留删**

### 已裁决的取舍

- 列数阈值 = **500dp**（不是 400dp）
- **共享元素转场（规范偏离③）已由用户裁决放弃** —— 弹卡保留原生 Sheet 容器（独立 window），做不到
- 「跟随壁纸」的对比度暂不生效
- targetSdk 33、默认周期 24h、工具链 AGP 8.7.3/Gradle 8.9（JDK 21 强制）均为用户知情决策

## 构建与验收

- **用户已授权 AI 执行 gradlew 构建验证**（2026-09-06 起，见 delegation-spec）：`gradlew :app:assembleDebug`
- 验收手段：`git diff` 审查 + 构建验证 + 逐项对照修复清单
- **⚠️ lint 在当前工具链下不可用**（多个 AndroidX 构件的 lint.jar 与 AGP 8.7.3 内置 Kotlin 分析 API 二进制不兼容，任务直接崩溃）。
  **不要再把 lint 当作验收依据**；`docs/HANDOVER.md` §8 有完整证据与修法。

## 发布约定（1.2 起）

- **一律用 release 构建 + debug keystore 签名**（不要再用 debug 包：debug 构建实测掉帧率 11.33% vs release 0.35%）：
  ```bash
  ./gradlew :app:assembleRelease
  apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out app-release-<版本>.apk app-release-unsigned.apk
  ```
  用 debug keystore 签名 → 与已发布版本同签名 → 用户可**直接覆盖安装**，无需卸载。
- **发布目标是 fork**（`illumiat/AccessibilityManager`），不是上游。上游 `origin` = `WuDi-ZhanShen/AccessibilityManager`，不推送。
- **流程**：改 `versionCode`/`versionName` → 写 `.codebuddy/release-notes-v<版本>.md` → 提交 → push `main` 与工作分支 → tag `v<版本>` → `gh release create`（notes 用发布说明文件，`--target main`，附 APK）。
- **发布前必做**：`./gradlew clean :app:assembleRelease` 通过（证明仓库内容可独立编译）。
- **构建产物与调试截图不得入库**（`.gitignore` 已覆盖 `.gradle/`、`build/`、`.codebuddy/shots/`）。
