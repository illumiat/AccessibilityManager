# 交接文档（HANDOVER）

> 用途：让**下一个会话/协作者**在不重读全部历史的前提下接手。
> 本文所有路径均为**绝对路径**，可直接复制。
> 生成时间：2026-09-19 ｜ 对应 git HEAD：`c45c5aa`（v1.1，**全部工作仍未提交**）

---

## 0. 一句话现状

`design-md3-apple-craft` 规范落到存量 **Java + MDC View** 项目上：UI 层已迁 Compose（外壳/主页/设置/**详情弹卡**），业务层已全量 Kotlin，构建全绿；**只剩 P6 收尾**，另有真机回归未做、lint 在当前工具链下不可用（见 §8）。

---

## 1. 关键绝对路径

### 1.1 项目

| 用途 | 绝对路径 |
|---|---|
| **项目根** | `C:\Users\illumiat\CodeBuddy\20260906084704\AccessibilityManager` |
| 应用模块 | `...\AccessibilityManager\app` |
| 设计系统模块 | `...\AccessibilityManager\core\designsystem` |

### 1.2 规范来源（**项目外，勿删**）

| 用途 | 绝对路径 |
|---|---|
| 规范主文件（自成一体） | `C:\Users\illumiat\.workbuddy\skills\design-md3-apple-craft\SKILL.md` |
| 规范附录（通常不需要读） | `C:\Users\illumiat\.workbuddy\skills\design-md3-apple-craft\reference.md` |
| 规范原始 zip | `C:\Users\illumiat\WorkBuddy\2026-09-19-06-45-08\design-md3-apple-craft.zip` |
| 同一规范的参考实现 | `C:\Users\illumiat\WorkBuddy\2026-09-18-06-44-59\connect-screen` |

### 1.3 设计系统（`:core:designsystem`）

token 层 `...\core\designsystem\src\main\java\com\accessibilitymanager\core\designsystem\theme\`：
`ShapeTokens.kt` / `SpacingTokens.kt` / `SizeTokens.kt` / `EffectTokens.kt` / `AppTypography.kt` / `AppTheme.kt` / `ThemeCatalog.kt`

组件层 `...\core\designsystem\...\component\`：
`PressFeedback.kt` / `M3ListItem.kt` / `M3SectionHeader.kt` / `AppNavigationBar.kt` /
**`M3AppIcon.kt`（P4 新增，图标槽唯一实现）** / **`M3TagChip.kt`（P4 新增，只读标签片）**

配色生成器（构建期产出静态资源，**产出物不要手改**）：
`...\core\designsystem\src\test\java\com\accessibilitymanager\core\designsystem\PaletteGeneratorTest.kt`

### 1.4 详情弹卡（**P4 新增，本次核心**）

目录：`...\app\src\main\java\com\accessibilitymanager\ui\detail\`

| 文件 | 职责 |
|---|---|
| `ServiceDetailUiModel.kt` | 不可变模型：`DetailHeader` / `DetailInfo` / `DetailRestart` |
| `ServiceDetailState.kt` | 状态持有者：宿主写入 → Compose 读取 |
| `ServiceDetailScreen.kt` | 界面（**不自带滚动**，见 §5 约束 9） |
| `PeriodDialog.kt` | 周期输入对话框（Compose `AlertDialog` + `SegmentedButton`） |
| `ServiceDetailBinder.kt` | `ComposeView.setContent` 的 Kotlin↔Java 桥接 |
| `ServiceDetailCallback.kt` | 宿主回调接口（C 三件写路径的意图入口） |

### 1.5 应用层 Kotlin（业务层，已全量转换）

`daemonService.kt` / `RestartPrefs.kt` / `RestartWorker.kt` / `IconCache.kt` /
`PermissionHelper.kt` / `App.kt` / `StartReceiver.kt` / `ThemePref.kt`

### 1.6 应用层 Kotlin（UI 层）

| 文件 | 角色 |
|---|---|
| `MainActivity.kt` | 外壳（Compose 底栏 + `fragment_container`） |
| `SettingsFragment.kt` / `ui/settings/SettingsScreen.kt` | 设置页 |
| `ui/home/HomeScreen.kt` / `ServiceCard.kt` / `HomeListState.kt` / `HomeListBinder.kt` / `HomeServiceCallback.kt` | 主页列表 |
| `ui/model/ServiceUiModel.kt` | 列表行的不可变模型 |
| `ui/detail/*` | 详情弹卡（见 §1.4） |

### 1.7 仅剩的 Java：**1 个**

| 文件 | 状态 |
|---|---|
| `...\app\src\main\java\com\accessibilitymanager\HomeFragment.java` | **唯一剩余 Java**。列表与详情卡的**渲染**都已交给 Compose；**写入路径仍在此**（开关写 `Settings.Secure`、锁定写 daemon 串、置顶写 top 串、定期重启写 `RestartPrefs`） |

### 1.8 仅剩的布局 XML：**2 个**

| 文件 | inflate 点 | 性质 |
|---|---|---|
| `...\app\src\main\res\layout\activity_main.xml` | `MainActivity.kt:50` | **壳**：ComposeView 底栏 + `fragment_container` |
| `...\app\src\main\res\layout\fragment_home.xml` | `HomeFragment.java:164` | **壳**：ComposeView 列表 + 空态 + 横幅 + **残留** `search_layout` |

> P4 已删除：`sheet_service_detail.xml`、`view_period_input.xml`。
> 更早删除：`values\themes.xml`、`values-night\themes.xml`、`fragment_settings.xml`、`item_service.xml`、
> `GridSpacingDecoration.java`、`ServiceAdapter.java`、`TouchDelegateLayout.java`、`SettingsFragment.java`、`menu\bottom_nav.xml`。

### 1.9 记录与文档

| 用途 | 绝对路径 |
|---|---|
| **执行版规划（主参照）** | `...\AccessibilityManager\docs\ui-redesign-plan.md` |
| 本交接文档 | `...\AccessibilityManager\docs\HANDOVER.md` |
| **缺陷模式清单（十类，评审扫描用）** | `...\AccessibilityManager\.codebuddy\DEFECT-CHECKLIST.md` |
| **发配/评审规程（仓库最终事实源）** | `...\AccessibilityManager\.codebuddy\rules\delegation-spec.md` |
| 项目约定 | `...\AccessibilityManager\AGENTS.md` |

> ⚠️ **`.codebuddy` 是项目数据（规则/缺陷清单/发布说明），不是缓存，禁止删除。**

---

## 2. 构建与验证命令（均实跑通过）

工作目录统一：`cd C:\Users\illumiat\CodeBuddy\20260906084704\AccessibilityManager`

| 目的 | 命令 |
|---|---|
| 配色生成（产出 24 色板 + 12 样式） | `.\gradlew.bat :core:designsystem:testDebugUnitTest --tests "*PaletteGenerator*" --console=plain` |
| **整体构建（主验收）** | `.\gradlew.bat :app:assembleDebug --console=plain` |
| 产物 | `...\app\build\outputs\apk\debug\app-debug.apk` |
| 取 Java 编译错误详情 | `.\gradlew.bat :app:compileDebugJavaWithJavac --console=plain` |
| ~~lint~~ | **不可用**，见 §8 |

> **用 git 取原文做基线**是校准的固定做法，例：
> `git -C <项目根> show HEAD:app/src/main/java/com/accessibilitymanager/RestartPrefs.java`

---

## 3. 进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 基线 | 版本目录 + Kotlin/Compose 插件 + `:core:designsystem` + compileSdk 35 + MDC 1.14.0 | ✅ |
| 设计系统 | token 层 + `AppTheme` 弹簧动效 | ✅ |
| 设计系统 | 组件层（`PressFeedback` / `M3ListItem` / `M3SectionHeader` / `AppNavigationBar`） | ✅ |
| **P1** | 配色算法生成 + 主题切换接线 | ✅ |
| **P2** | 外壳迁 Compose + Kotlin 不可变模型层 | ✅ |
| **P3** | 主页列表迁 Compose | ✅ |
| **P4** | **详情弹卡（Bottom/Side Sheet）+ 周期对话框** | ✅ **本次完成** |
| **P5** | 设置页迁 Compose（含主题选择器 + 对比度四档） | ✅ |
| **P6** | 收尾：删 View 残留、清垫片、移除 MDC | 🔄 **部分（见 §4）** |
| **K** | 业务层转 Kotlin | ✅ 全量完成 |
| **真机回归** | 详情卡滚动 / 周期对话框全链路 / SideSheet / 暗色 / 设置页开关 | ✅ **本轮完成**（见 §12） |
| 真机回归（剩余） | 灭屏触发 / 伪关闭恢复 / 权限撤销告警 / 多服务并发 / 通知未授权 / 折叠屏分屏 | ❌ **待人工** |

---

## 4. 恢复点：下一步就是 P6

**P6 的清单（按可机械执行程度排序）**：

1. **删布局残留**：`fragment_home.xml` 的 `search_layout`（被 `HomeFragment.java:191` 显式 `setVisibility(GONE)`）+ 5 个无代码引用的 id
   （`app_title` / `banner_container` / `banner_text` / `search_input` / `top_bar`）；
   随后 `values\dimens.xml` 的 `search_box_width` 随之可删。
2. **清裸数值**：`ServiceCard.kt:98`（`0.dp`）、`ServiceCard.kt:199`（`1.dp`）、
   `HomeScreen.kt:60`（`CardMaxWidth`）、`M3ListItem.kt:126/129/132`、
   `IconCache.kt:227`（十六进制色值）。
3. **删未使用字符串**：`home_title` / `service_switch_desc` / `auth_failed_alert`（其中 `list_loading` 已在 1.2.1 删除，见 §17；余 3 条仍属 P6 收尾）。
   > ⚠️ 确认前先查 `<xliff:g>` 与代码内拼接，避免误删。
4. **删兼容垫片**：`IconCache` 的 `@JvmField`×6、`@JvmStatic`×2（调用方迁完后逐行清理）。
5. **移除 MDC**：当前剩余使用点 = `HomeFragment.java`（对话框/Sheet/侧边栏）+ `PermissionHelper.kt` + `App.kt` + `IconCache.kt`（主题属性）+ `AppTheme.kt`（主题属性解析）。
6. **`values\colors.xml`**：**不要删**。审计确认 `bg` / `fg` 仅被自适应图标（`mipmap-anydpi-v26`）引用，属正常资源，
   旧交接文档把它列为"待删残留色值"是误判。
7. **壳的去留**：`activity_main.xml` / `fragment_home.xml` 要消失，需要把两个 Fragment 也换成纯 Compose 承载
   （P6 的最大一块，且**两个 XML 目前仍是 `HomeFragment` 存在的理由之一**）。
8. **`HomeFragment.java` 的终局**：它的写入路径要么下沉到 `HomeListState`/`ServiceDetailState` 的宿主（Kotlin），
   要么保留为 `Fragment` —— 这是**独立决策**，不要顺手做。

---

## 5. 不可回退的约束（改前必读）

| # | 约束 | 原因 |
|---|---|---|
| 1 | **Java 类型绝不能进入组合树** | Java POJO 无可变/相等语义 → Compose 判 unstable → 跳过重组失效 → 掉帧。所有数据先进 `ServiceUiModel` / `DetailHeader`+`DetailInfo`+`DetailRestart`（`@Immutable data class`） |
| 2 | **`ComposeView.setContent` 只能调一次** | 重调会重置列表滚动位置。会变的输入必须是 Compose 状态（见 `HomeListState`、`ServiceDetailState`），不能做 `bind` 入参快照 |
| 3 | **锁身份不得改变** | `RestartPrefs` 的 11 处 `synchronized(RestartPrefs::class.java)` 是**类级锁**；不得改成 `Companion` / 实例锁 / `Mutex`（缺陷清单模式 6） |
| 4 | **静态镜像同步点一处不能少** | `daemonService.tmpSettingValue`：daemon 侧 10 处、Worker 侧 6 处 |
| 5 | **线程模型不得改** | `Thread` + `Handler(Looper.getMainLooper())` + `SystemClock.sleep(1500)` 原样保留；**不要换协程** |
| 6 | **列数阈值 = 500dp（不是 400dp）** | 400dp 会让密度调整后的手机竖屏（≈421dp）误入 2 列 |
| 7 | **写入路径留在宿主** | 开关/锁定/置顶/定期重启的写操作全在 `HomeFragment`；Compose 只发意图（`HomeServiceCallback` / `ServiceDetailCallback`） |
| 8 | **空态暂由 View 层持有** | `fragment_home.xml` 的 `empty_view` 仍负责空态；`updateEmptyState()` 隐藏 Compose 列表避免双渲染。P6 统一后删除 |
| **9** | **详情弹卡的内容根必须是 `NestedScrollView`，不能裸挂 `ComposeView`** | 一手核实：MDC 的 `BottomSheetBehavior.findScrollingChild()` 按 **`view.isNestedScrollingEnabled()`** 判定滚动子视图，并据此决定是否拦截 MOVE 拖动、是否在内容可上滚时放弃捕获。`ComposeView` / `AbstractComposeView` / `AndroidComposeView` **都没调 `setNestedScrollingEnabled(true)`**（javap 核实）→ 判定为空 → 两条协调全失效。`NestedScrollView` 实现 `NestedScrollingChild3` 且默认启用，与迁移前的 `ScrollView` 同构 |
| **10** | **详情 Compose 内容不得再挂 `verticalScroll`** | 滚动由上面的 `NestedScrollView` 承担，双重滚动会互抢手势。`ScrollView` 系按 **UNSPECIFIED 高度**测量子视图，Compose 列可自由展开 |
| **11** | **`ServiceDetailBinder` 的 `ComposeView` 必须用 Activity 上下文** | `AppTheme` 经 `LocalContext` 的**主题属性**解析配色；弹窗会把 Context 包一层 dialog 主题 overlay，可能让 `?attr/colorSurface*` 解析到 overlay 的值。迁移前的内容视图也是 Activity 上下文（`getLayoutInflater().inflate`） |
| **12** | **图标槽的 `contentDescription` 必须挂在容器上，不能只挂在 `Image` 上** | 挂在 `Image` 上只覆盖「已加载」分支；**占位分支（首次渲染的常见路径）在无障碍树里完全没有标签**。故 `M3AppIcon` 在容器上 `clearAndSetSemantics` 一次性给出描述并清空子节点语义（占位首字不应被当成独立文本读） |

---

## 6. 已知欠账与风险

| # | 事项 | 影响 | 处置建议 |
|---|---|---|---|
| 1 | **lint 在当前工具链下不可用** | 失去一整套静态检查能力 | 见 §8，需单独立项 |
| 2 | **P4 未做真机验证** | 详情卡的 `NestedScrollView` 高度/滚动、侧边栏宽度、周期对话框交互只在真机可验 | 按 §3 末行清单跑 |
| 3 | 「跟随壁纸」时**对比度不生效** | 跟随系统动态取色不提供对比度入参 | **已裁决取舍**（AGENTS.md「设计意图」已载明），非待修缺陷；保持现状 |
| 4 | `res/font/` 缺 Roboto 资产 | `AppFontFamily` 暂指系统字体族 | 补 ttf 即切 |
| 5 | 兼容垫片未清 | `IconCache` 的 `@JvmField`×6、`@JvmStatic`×2 等 | P6 逐行清理 |
| 6 | **MDC 尚未移除** | 规范 §10 明确 MDC 非落点 | 全部页面迁完后移除 |
| 7 | 裸 dp / 十六进制残留（6 处） | 违反「无裸数值」判据 | P6，见 §4 第 2 条 |
| 8 | 未使用字符串 4 条 | 资源噪音 | P6，见 §4 第 3 条 |
| 9 | **全部改动未提交** | 无版本保护 | 建议尽快提交一次 |
| 10 | `c:\Users\illumiat\CodeBuddy\20260906084704\_piliplus_ref` | 临时克隆残留 | 人工删除（AI 删除被安全护栏拦截） |
| 11 | **MDC Button/TextButton 仍带 ripple** | 规范偏离②要求按下反馈用「缩放+变暗」；当前只有列表项/详情卡自绘可点区走了 `appClickable`，MD3 按钮未覆盖 | 全站统一项，需一次决策 + 一次机械收敛 |
| 12 | **规范缺口：§2 场景→样式表缺「卡片内的键值数据」一行** | 周期值、上次执行时间这类**卡片内键值对**没有对应行，只能取表内最近邻（卡片正文 = `bodyMedium`/`onSurfaceVariant`）。与「策略说明」同色后，卡片内的数据与元信息无视觉区分 | 属规范待补项，不是实现错误。若规范后续补行，按新行改（仅两处 `Text` 的 `color`） |

---

## 7. Java→Kotlin 转换坑清单（实测，共 10 类）

**A. 编译期会报（安全）**

| # | 陷阱 | 现象 |
|---|---|---|
| 1 | `Entry` 等类在 Java 隐含可继承，Kotlin 默认 `final` | `This type is final...` → 加 `open` |
| 2 | 嵌套类放进 `companion` | Java 侧变 `X.Companion.Config` → 调用方「找不到符号」。**必须放外层类** |
| 3 | `String.valueOf` 在 Kotlin 不存在 | 用 `java.lang.String.valueOf(...)` 保持「null → "null"」语义 |
| 4 | Kotlin 内建 `Result` 遮蔽 `ListenableWorker.Result` | 一律写全限定名 |
| 5 | `val` 不能在 `catch` 里重赋值 | 改 `try` 表达式 |
| 6 | 可变字段（`PackageInfo.applicationInfo`）无法智能转换 | 捕获到局部变量再判空 |
| 7 | Java 多路 catch 无等价物 | 拆成多个 catch 子句，**不得放宽为 `catch (Exception)`** |
| 8 | Kotlin `var x` + 显式 `fun setX` → JVM 签名冲突 | 二选一 |
| 9 | Kotlin `(String) -> Unit` 的 `invoke` 返回 `Unit`（非 void） | Java lambda 不兼容 → 桥接参数改用 `Consumer<T>` / `Runnable` |
| 10 | 字段名与 Context 属性同名（`packageManager`） | 变**自赋值**（lateinit 未初始化即抛）→ 显式 `applicationContext.packageManager` |

> **P4 补充（第 11 类）**：Java 调 Kotlin `data class` 构造函数没问题，但**新增的模型类 import 必须记得加** ——
> Kotlin 侧编译通过、Java 侧才报「找不到符号」，且报错只指向使用点不指向 import。

**B. 编译器抓不到（必须靠评审）**

| # | 陷阱 | 说明 |
|---|---|---|
| 1 | `SharedPreferences.getString` 标 `@Nullable` | Kotlin 视为 `String?`；用 `!!` 保持原「null 即 NPE」语义，**不要静默兜底** |
| 2 | `split(":")` 语义差异 | Java 丢尾部空段、Kotlin 保留；本仓库所有使用点都有 `isEmpty()` 过滤，故结果一致 |
| 3 | `@JvmField` vs `@JvmStatic` | 要**静态字段**（如 `daemonService.tmpSettingValue`）必须 `@JvmField @Volatile`；`@JvmStatic` 只生成访问器 |
| 4 | `internal` 会名字混淆 | `internal fun x` → JVM 名 `x$app_debug`，**打断 Java 调用点**；被 Java 调用的一律 public |
| 5 | 非静态内部类必须写 `inner` | `SettingsValueChangeContentObserver` 需访问外部 `contentResolver`/`doDaemon` |

---

## 8. 验证能力的边界（重要）

**能验到的**：Gradle 构建通过、编译错误、代码级机械扫描（锁/可见性/注解/线程形态计数，以 `git show HEAD:<原文件>` 为基线对照）、
以及通过 `javap` / 构件源码核实**库的二进制事实**（P4 的 `NestedScrollView` 结论就是这么得到的）。

**验不到的（必须人工）**：任何运行时行为 —— 保活是否在灭屏后恢复、伪关闭能否识别、权限撤销告警是否弹出、
并发下镜像是否失真、列表是否真的 ≥55fps、**详情弹卡的滚动与拖拽协调**。

### ⚠️ lint 在本项目**不可用**（属"缺工具链版本对齐"这一类，不是"没跑"）

**证据**：`:app:lintDebug` 与 `:core:designsystem:lintAnalyzeDebug` 均以任务失败告终，崩在**检测器自身的字节码**里：

```
IncompatibleClassChangeError: Found class org.jetbrains.kotlin.analysis.api.resolution.KaSimpleVariableAccessCall,
                             but interface was expected
```

触发文件是**未改动**的 `AppNavigationBar.kt`（compose runtime 的 `RememberInCompositionDetector` /
`FrequentlyChangingValueDetector`、`ComposableFlowOperatorDetector`）与 `PaletteGeneratorTest.kt`；
换一组 `disable` 后又暴露 **`androidx.lifecycle.lint.NonNullableMutableLiveDataDetector`**。

**性质**：多个 AndroidX 构件的 `lint.jar` 编译自比 **AGP 8.7.3 内置的 Kotlin 分析 API 更新的版本**。
逐 issue id 禁用是无界的（已知 compose runtime 一家就有 15 个检测器），故**不采用打补丁式规避**。

**修法（需独立立项）**：升 AGP 到与该 compose/material3 版本配套的版本。
注意这与现有裁决冲突 —— `gradle/libs.versions.toml` 明确把 AGP 8.7.3 保持不动是为了
「不扰动已验证的构建链」，且 material3 1.5.0-alpha18 的可用区间是 `compileSdk 35 + AGP ≥ 8.6.0`，
升级需重新验证该交集。

**因此**：旧文档/规划里 P3、P5 写的「lint 0」在当前工具链下**不可复现**，不要再引用为已完成项。

### 判定口径

**「构建绿 + 机械扫描一致」= 代码级完成；「真机回归通过」= 功能级完成。** 二者不可互相替代。

---

## 9. P4 决策记录（本轮，供后续引用）

| # | 决策 | 依据 |
|---|---|---|
| 1 | **弹卡承载保留原生 Sheet 容器**（`BottomSheetDialog` / `SideSheetDialog`），只把**内容**换成 Compose | 用户裁决。改动最小、拖拽/insets/返回键免费、平板侧边形态保留 |
| 2 | **放弃「共享元素转场」**（规范偏离③） | **由决策 1 直接导致**：对话框是独立 window，不共享 composition，`SharedTransitionLayout` 在该形态下做不到。这是规范三处偏离中**唯一未被兑现**的一处 |
| 3 | 详情内容根用 `NestedScrollView` 包 `ComposeView` | 见 §5 约束 9（一手核实） |
| 4 | 卡片标题/区块标题用 `titleMedium` + `onSurface`（旧 XML 用 `labelLarge` + `colorPrimary`） | 规范 §2 场景→样式表；用 colorPrimary 表达层级属"用颜色代替层级" |
| 5 | 一屏字号层级 = **3 档主层级**（`titleLarge` / `titleMedium` / `bodyMedium`）+ `bodySmall` 仅作字段下辅助说明 | 规范 §1「≤3 档」与 §2「字段下辅助说明 = bodySmall」的交集口径 |
| 6 | 包名/类名由 `bodySmall` 提到 `bodyMedium` | 规范 §2「列表辅助文本 = bodyMedium」；也是为了让字阶回到 3 档 |
| 7 | 标签片**不用 MD3 Chip 组件**，自绘 `M3TagChip`（`Modifier.border` + `labelLarge`） | **一手核实**：material3 alpha18 无任何非交互 Chip（四个变体第一参数全是 `onClick`）。用 Chip 渲染只读标签 = 给无障碍树挂假按钮 |
| 8 | 单位选择用 `SegmentedButton` 替代 `MaterialButtonToggleGroup` | 规范 §5「2–5 个互斥 → SegmentedButton」 |
| 9 | 周期对话框校验文案放 `OutlinedTextField.supportingText` | 规范 §6「字段级错误放该字段下方」 |
| 10 | 图标槽收口到 `M3AppIcon`（主页卡片与详情头部共用） | 缺陷清单模式 10「同功能双实现必须收口单点」 |
| 11 | 新增 token `SizeTokens.HeroIconSize = 48dp` | 与迁移前一致 + 主角需大于列表图标（**规范 §8 未覆盖此场景**，见 §10） |
| 12 | **本轮不做真机验证** | 用户裁决 |
| 13 | 触控目标口径：自绘/自定尺寸元素显式声明 48dp；M3 组件不重复声明 | M3 由 `minimumInteractiveComponentSize` 兜底（已核实该符号存在） |
| 14 | 弹卡内**不用 `bodySmall`** | §2 把该档限定给「字段下辅助说明」；用它会使 sp 值变四档（22/16/14/12），违反 §1。该档只出现在周期对话框的 `supportingText` |
| 15 | **主页卡片固定 88dp 等高；描述限 1 行** | 用户裁决。前版高度随内容 = 64/84/108dp 三种且同行不等高；`16+48+4+20 = 88dp` 正好落规范三档最高档。用 `heightIn(min=)` 以便系统字号最大时增高不截断。见 §13.2 |
| 16 | **保留多列自适应**（不改单列） | 用户裁决。故必须靠卡片等高来保证多列整齐 |
| 17 | **`heightIn` 最小高度挂在 `Row` 上，不挂 `Card`** | 挂在 `Card` 上时内部 `Row` 仍按内容收缩 → 内容顶部对齐、底部留死区、图标偏上 12dp（§13.3a 实测） |
| 18 | **置顶标签放尾部状态区，与锁纵向排列**（标签在上、锁在下） | 用户裁决。不遮挡图标、不挤窄标题、只占 48dp 宽、高度恰好等于内容区不撑高卡片；非置顶时锁自然居中。见 §13.3b |
| 19 | **置顶不改用纯色圆点** | 圆点属颜色通道，违反「不得用颜色作为唯一信息通道」 |

---

## 10. 独立规范审计轮（P4 之后，只读子代理）

对 P4 全部新增/改动文件做了一次独立的只读审计（依据 `design-md3-apple-craft` + 旧实现原文对照）。
**查出 4 个真问题，全部已修**：

| # | 发现 | 性质 | 处置 |
|---|---|---|---|
| 1 | `M3AppIcon` 占位分支**无 `contentDescription`** | **无障碍真 bug** | 描述移到容器 + `clearAndSetSemantics`（见 §5 约束 12） |
| 2 | 定期重启卡的说明行用 `bodySmall` | 违反 §1「一屏 ≤3 档」——§2 把 `bodySmall` 限定给**字段下辅助说明**，弹卡里没有表单字段 | 改 `bodyMedium`，使 sp 值收敛为 22/16/14（**可机械验证**） |
| 3 | 周期行/上次执行行用 `onSurface` | 与 §2「卡片正文 = `bodyMedium`/`onSurfaceVariant`」不一致 | 改 `onSurfaceVariant`（失败态仍 `error`）；并记为规范缺口见 §6 第 12 条 |
| 4 | `SizeTokens.HeroIconSize` 的 KDoc 误引 §8 | 文档声称取「§8 的 48–56dp 大尺寸区间」，但该行**专指空态图形**，与弹卡头部不是同一场景 | 改为"与迁移前一致 + 主角需大于列表图标"两条真实依据，并显式标注「规范未覆盖，按 §18 处理」 |

**附加清理**：`M3TagChip` 去掉 `Color.Transparent`（改用 `Modifier.border`，语义角色只剩 `outline` 与文字色）；`CircleShape` → `ShapeTokens.Full`（等价但走 token）；`PeriodDialog` 去掉重复 dismiss；`ServiceCard` 删无用 import。

**审计提出、已用一手证据关闭的疑问**：

| 疑问 | 结论 |
|---|---|
| 自绘 `M3TagChip` 是否违反 §9②「只允许三处偏离 MD3」 | **不违反**。`javap` 核对 material3 1.5.0-alpha18 的 `ChipKt`：**不存在非交互 Chip**，`AssistChip`/`SuggestionChip`/`FilterChip`/`InputChip` 第一个参数全是 `onClick`。自绘属「照抄 MD3 outlined chip 外观」 |
| M3 组件（Switch / SegmentedButton / TextButton）默认触控目标是否 ≥48dp | **是**。`minimumInteractiveComponentSize` 与 `LocalMinimumInteractiveComponentEnforcement` 均存在于 material3 alpha18，由组件内置兜底。故**不再自加** `heightIn`（自加反而破坏「同一语义全局唯一取值」）；规则已写入 `ServiceDetailScreen` 的「触控目标的口径」节 |
| `ServiceCard.kt` 两处裸 dp 是否本次引入 | **不是**。本次会话开始前读取该文件时即存在（原 98/199 行），属 P6 欠账 |

**仍未验证**（只读审计的能力边界，与 §8 一致）：暗色模式、系统最大字号、400dp/900dp 两端宽度的实机表现。
`M3TagChip` 与两张卡片的实际观感需真机截图确认。

---

## 11. 双轴代码审查轮（固定点 `c45c5aa`，P4 之后）

对**工作树全部改动**做了一次两轴审查（规范轴 + 需求轴，并行只读子代理；固定点 `c45c5aa`，
因改动未提交，故取法是「已跟踪走 `git diff`/`git show`，未跟踪的 `ui/`+`core/` 直接读」）。

### 修掉的 5 个真问题

| # | 轴 | 问题 | 性质 | 处置 |
|---|---|---|---|---|
| 1 | 需求 | **600dp 形态切换丢失**：旧 `HomeFragment.java:217` 的 `addOnLayoutChangeListener`（宽度跨 600dp → dismiss + 按新形态重开）整段未迁，`lastWide` 成为死字段 | **真回归**（旋转/折叠/分屏时弹卡形态错配） | 新增 `bindDetailFormFactorWatcher(View)`（`HomeFragment.java:627`），挂在内容根视图；只保留形态跟随一半职责（span 已归 Compose） |
| 2 | 需求 | **设置页开关视觉回弹**：`Switch` 无状态，位置由 `checked = ui.*` 决定，而回调只写 SP 不更新 `ui` → 拨动后回弹，等 `onResume` 才追上 | **真 bug** | 四个开关回调改为「写 SP + `ui = ui.copy(...)`」（`SettingsFragment.kt:100-115`）。**迁移前的 `MaterialSwitch` 自持状态，故这是 Compose 化带来的状态归属变化** |
| 3 | 规范 | `IconCache.kt` 的 `0xFF9E9E9E` 十六进制兜底 | 违反 UI 规范 §13 硬禁令 | 该值位于 `placeholderFor → createPlaceholder → attrColor` 链条，而 **`placeholderFor` 零调用**（P3/P4 改由 Compose 绘占位后成为死代码）→ **整链删除**。顺带消灭废弃的 `colorSurfaceVariant` attr 与 **IconCache 对 MDC 的全部依赖**（P6 项提前完成） |
| 4 | 规范 | `RestartPrefs.kt:19` KDoc 写「10 处类级锁」，实际 11 处 | 文档错误（与 `AGENTS.md:28`、本文件 §5 约束 3 矛盾） | 改为 11。**注意 `grep -c` 会得 12**——第 12 个匹配是 KDoc 里的示例行；代码中为 11 处，与旧 Java 11 处严格一一对应 |
| 5 | 规范 | `[ServiceAdapter.Callback]` / `[ServiceAdapter.updateDesc]` 悬空 KDoc 链接（`HomeListBinder.kt:16`、`HomeListState.kt:208`），该类已删 | 文档错误 | 改为反引号文本并注明"原为…随适配器退役迁出" |

### 一处刻意保留的行为变更（**曾被错误注释掩盖，已更正**）

**列表警示角标优先级**：迁移前 `ServiceAdapter` 是 `autoRestored > failed`，现在是 `failed > autoRestored`。

- 原注释声称「沿用既有 R5 裁决」—— **这是伪造依据**。R5 裁决的是 `failed` 与 `pendingEnable` 的优先级，**从未裁决 `autoRestored`**。
- 真实理由是**消除自相矛盾**：同一 Adapter 的描述行（`updateDesc`）本就是 `failed` 优先，
  于是两标志同时为真时会出现「描述行红色写『恢复失败』、角标却写『已自动恢复』」。
  两者同时为真确有路径（`compensatePendingEnables` 补 enable 成功写时间戳，5 分钟窗口内再次失败 → `failed`）。
- **结论：保留新行为**（更准确、且可行动），已把理由完整写入 `ServiceUiModel.kt` 的 `WarningKind` KDoc，
  并显式写明「不要写成沿用 R5 裁决」。

> 教训（与 `HeroIconSize` 误引 §8 同一类）：**给一个已做的选择补一条不存在的依据**，比不写依据更坏 ——
> 它会让后续会话把错误结论当既定事实继承。查不到依据时，写「规范未覆盖 + 本项目的真实理由」。

### 经核实后**判定无需改**的两条

| 审查意见 | 核实结论 |
|---|---|
| `App.kt` 把 `DynamicColors` 由无条件改为「仅跟随壁纸时应用」，属范围蔓延 | **必要且正确**。旧版无条件叠加动态取色会**盖掉用户选的命名主题**；规划 §3.2 明确「跟随壁纸作为并列选项、不设为默认、不强制」，故必须条件化。已在代码注释说明。**（注：1.2.1 将动态取色调用从 `App` 移至 `MainActivity.onCreate`，按当次持久化主题对单 Activity 应用，见 §17.1）** |
| `HomeFragment.java:109` `lastWide` 死字段 | 已由修复 1 复活，不再是死字段 |

### 根因扫描：同类问题还有没有

问题 2 的根因是**一类系统性风险**：Compose 的无状态控件 + 回调不更新驱动它的状态。故对全部受控控件做了扫查（`checked=` / `value=` / `selected=`）：

| 控件 | 驱动状态 | 回调是否更新状态 | 结论 |
|---|---|---|---|
| 设置页 4 个开关（`SettingsScreen.kt:112/118/124/133`） | `ui.*` | **否** → 已修 | **本次唯一实例** |
| 详情卡定期重启开关（`ServiceDetailScreen.kt:321`） | `restart.enabled` | 是（`applyRestartToggle` → `refreshDetailRestartArea` → `submitDetailRestart`） | 正常 |
| 列表卡片开关（`ServiceCard.kt:145`） | `model.enabled` | 是（经 **ContentObserver** → `postStatesRefresh` → `refreshList`） | 正常 |
| 搜索框（`HomeScreen.kt:223`） | `state.query` | 是（`HomeListBinder` 内 `state.query = q`） | 正常 |
| 周期对话框输入/单位（`PeriodDialog.kt:122/144`） | 本层 `remember` 状态 | 是 | 正常 |
| 单选对话框（`SettingsScreen.kt:263-276`） | `selectedIndex` | 是（选择**会镜像进 `ui`**，不再依赖是否重建） | 正常 |

**列表开关的一处差异（留作真机观察项，未改）**：`onToggle` 与迁移前**逐字节相同**，成功路径都不刷新、
都依赖 ContentObserver。但迁移前 `MaterialSwitch` 是 `CompoundButton`（**自持** checked），
点击瞬间自身即翻到新态；现在是**无状态** `Switch`，要等 observer 回调经 `refreshList` 才翻。
两者**最终态一致**，差的是一个 observer 往返的**瞬态**。因改成乐观更新属行为变更（且会掩盖写入失败），
**不在本轮改动**，列为真机观察项。

| 项 | 说明 |
|---|---|
| `Entry.placeholder` 字段（`IconCache.kt:53`）| 随占位链删除后成为孤儿字段（无读无写）。按「改动最小化」**未删**，留 P6 一并评估 |
| `strings.xml` 的 `list_loading` | **已在 1.2.1 删除**（零引用死串，随文案外移一并清理）。加载态用骨架屏（无文字），与「动效非唯一信息通道」无关；纯资源噪音 |
| `ServiceCard.kt` 两处裸 dp（`0.dp` / `1.dp`）与 `HomeScreen.kt:60` 的 `500.dp` | 已确认**非本次引入**（会话开始前即存在），属 P6 欠账（见 §6 第 7 条） |

### 真机观察项（由本轮审查新提出）

| 项 | 看什么 | 状态 |
|---|---|---|
| **列表开关的瞬态响应** | 点击后开关翻动是否被察觉为迟滞（依赖 observer 往返）；若明显迟滞，需一次「乐观更新」决策 | 待观察 |
| ~~设置页开关~~ | ~~修复后是否即时跟随~~ | ✅ **已验证**（见 §12） |
| ~~单卡 ≥24 条事件标签~~ | ~~详情卡是否可正常滚动~~ | ✅ **已验证**（见 §12） |
| **旋转 / 折叠 / 分屏** | 跨 600dp 时弹卡是否以新形态重开 | ⚠️ **实测结论：本逻辑不覆盖旋转**（见 §12 与 `HomeFragment.bindDetailFormFactorWatcher` 注释）；折叠/分屏未验 |

---

## 12. 真机回归（2026-09-19，设备 23116PN5BC / Android 16 / SDK 36 / 1440×3200@560dpi）

**判定口径**：§8 说「构建绿 + 机械扫描一致 = 代码级完成；真机回归通过 = 功能级完成」。本节给的是**功能级**证据。

### 12.1 真机查出并修复的 2 个缺陷（构建/lint/两轮只读审计**全部漏掉**）

| # | 缺陷 | 症状 | 根因 | 修法 |
|---|---|---|---|---|
| 1 | **列表标题/描述永久停在兜底值** | 首屏图标已加载（彩色真实图标），但标题仍是**服务类短名**（`.enhance.tb.MiuiEnhanceTService`，看起来像包名）、描述全是「该服务没有描述」 | 标题与描述**同样来自 `IconCache`**，缓存命中前只能出兜底值。迁移前靠 `notifyItemChanged(pos, PAYLOAD_INFO)` → `ServiceAdapter.bindInfo` 一次性重设「图标 + 标题 + 描述」三者；新实现的 `withIconFrom` **只 copy 了 `icon`** | 异步回调改为**重建整行模型**（`HomeListState.requestIcon` 调 `buildModel`），并保留上次 `submit` 的入参快照 |
| 2 | **搜索无结果误报"未发现任何无障碍服务"** | 在装有 7 个服务的设备上搜不存在的关键词 → 显示「未发现任何无障碍服务」，让人以为设备没装服务 | `HomeScreen` 的 `models.isEmpty()` **未区分**「零服务」与「搜索无结果」 | 新增 `SearchEmptyState`：回显查询词、说明是被过滤、主动作改为「清空搜索」（规范 §6 三段式）；新增 3 条字符串 |

> **教训**：只读审查能验「代码是否等价」，验不了「**数据何时到达、到达后 UI 有没有跟上**」。后者必须真机。

### 12.2 验证通过项

| 项 | 证据 |
|---|---|
| **详情卡滚动与拖拽协调**（最高风险项） | 在详情卡内上滑，内容正常滚动到「打开系统设置」；`NestedScrollView` 方案成立（§5 约束 9/10 的一手结论被真机确认） |
| **周期对话框全链路** | 预填（1440min→自动选「天」→填 1）→ 非法输入（0）时**描边/标签/helper 变 error 红 + OK 置灰** → 合法输入（2 小时）后 helper 回中性 → 确认后 **SP 落笔 `.period=120`**（2×60 换算正确）、`.enabled=true`、`.last=当前时间戳` → UI 同步「周期：每 2 小时」「上次执行：刚刚」 |
| **定期重启开关两方向** | ON→OFF 落笔 `.enabled=false` 且**`.period` 保留 120**（验证 R1 裁决：disable 只翻转 enabled，故「关→再开」不丢用户设定） |
| **SideSheet 形态**（743dp） | 以右侧栏打开，约屏宽 60%，列表在宽屏自动 2 列 —— **此前从未验证过的形态** |
| **暗色模式** | 列表与详情卡均正确：`surfaceContainerLow` 分层可见、文字可读、primary 强调清晰 |
| **设置页开关即时性**（双轴审查修复项） | 拨动后 `checked=false` **保持不回弹** + SP 同步 |
| **无障碍语义** | uiautomator 读到 `content-desc="启用定期重启"`（挂在开关容器上，即 P4 审计修复项） |
| **零崩溃** | 整个验证过程 `FATAL EXCEPTION` 计数 = 0 |

### 12.3 未通过 / 结论收缩的一项

**600dp 形态切换（`bindDetailFormFactorWatcher`）** —— 真机实测发现**它的真实生效场景比设计时设想的窄**：

- 加日志实测：宽度变化时 `detailDialog` **恒为 null**（`dialogNull=true showing=false`），
  即**宿主 window 尺寸剧变时系统会先 dismiss Dialog**（对话框是独立 window）；
- 旋转会因 Manifest 未声明 `orientation` 而**重建 Activity**（实测 task id t6822→t6824），Dialog 同样消失。

**结论**：该逻辑对「旋转」与「`wm size` 剧变」**实际不起作用**（两者都表现为卡片关闭后用户重开，**与迁移前行为一致**）。
它真正的场景是 Manifest 已声明 `screenSize` 所覆盖的**窗口 bounds 渐变**（分屏拖动 / 折叠屏展开合拢），
**该场景尚未验证**（缺折叠屏/分屏环境）。

保留理由与失效条件已写入 `HomeFragment.bindDetailFormFactorWatcher` 的 KDoc。
**注意**：不要因为"旋转时卡片关掉了"就认为这是 bug —— 那是系统 dismiss + Activity 重建，新旧一致。

### 12.4 设备状态还原

测试期间为构造场景改过系统设置，**全部已还原并逐项取证**：
`wm size`（无 override）、`display_density_forced`（空）、`user_rotation=0`、`accelerometer_rotation=0`、
`default_input_method`（还原为微信输入法、临时启用的 Gboard 已禁用）、`cmd uimode night no`；
应用 SP 亦还原（`boot=true`、GKD 定期重启 `false`）。

> 期间新建的辅助工具：`C:\Users\illumiat\CodeBuddy\20260906084704\_tools\uitap.py`（按文本/content-desc 精确点击）。
> **为什么需要它**：`screencap` 出来的图常被缩放，用截图估读的坐标会系统性偏移（本次实测比例 ≈2.98），
> 导致点击落在 scrim 上把对话框关掉、浪费多轮。凡需按坐标操作的验证都应先用它取实时坐标。

---

## 13. 用户反馈两项（滑动手感 + 卡片大小不一）| 2026-09-19

用户实测提出：**（a）刚加载出来时滑动略有卡顿；（b）卡片大小不一，"不该用瀑布流"**。两项都实测取证。

### 13.1 卡顿 —— **主因是 debug 构建，不是代码**

**取证方法**：`dumpsys gfxinfo` 在「首屏刚出现即快速滑动 6 次」的同一操作下对比两种构建。

| 指标 | debug | **release** |
|---|---|---|
| **Janky frames** | 35 / 309 = **11.33%** | 3 / 847 = **0.35%** |
| 50th 帧时 | 18ms | **5ms** |
| 90th / 95th | 53ms / 65ms | **9ms / 13ms** |
| 99th | 550ms | 200ms |
| Missed Vsync | 28 | **2** |
| 渲染总帧数 | 309 | **847** |

**结论**：release 为 **0.35% 掉帧**（健康线 <5%）、50th 仅 5ms（预算 16.67ms），**极健康**。
debug 的 2.7 倍帧数差距源于 debug 构建的固有开销（无优化、无内联、Compose 运行时检查）。

> **重要**：此前多轮真机验证装的一直是 **debug APK**，用户据此体验到的卡顿并非代码缺陷。
> **今后凡涉及手感/性能的真机验收，一律用 release**（构建 + debug keystore 签名后覆盖安装即可，无需改项目文件）：
> ```bash
> ./gradlew :app:assembleRelease
> apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
>   --key-pass pass:android --ks-key-alias androiddebugkey \
>   --out app-release-signed.apk app-release-unsigned.apk
> ```

**次因（未处理，列为 P6）**：首屏的**图标异步加载**在滑动时会争抢主线程。代码已有
`IconCache.setPaused(滑动中)` 机制（六条防掉帧条款之一），但**响应滞后** —— 它靠 `snapshotFlow` 在滑动**开始后**才暂停。
`poet` 级别的改进（如滑动前预判）收益有限，且 release 已达标，故不动。

### 13.2 卡片大小不一 —— **确认是真问题，已修**

**实测（`uiautomator` 取 bounds）**：多列下卡片高度为 **224 / 294 / 378 px = 64 / 84 / 108 dp 三种**，
且**同一行内两个卡片也不等高**（294 vs 378）。三个值**没有一个**落在规范「列表项高度只取 56/72/88」的三档上。

**根因**：`ServiceCard` 高度完全由内容撑开，构成关系实测吻合
`高度 = 16(内边距) + 标题行数×24 + 4(间距) + 描述行数×20` —— 标题 1/2 行 × 描述 1/2 行 = 四种组合。

**用户裁决**：保留多列自适应（不改单列）+ **卡片固定 88dp 等高**。

**修法**（`ServiceCard.kt`）：标题最多 2 行 + **描述由 2 行改为 1 行** + `heightIn(min = ListRowHeight.Triple /* 88dp */)`。
于是 `16 + 48 + 4 + 20 = 88dp`，常规字号下**所有卡片恒等 88dp**，正好落在规范三档的最高档。

**用 `heightIn(min=)` 而非 `height(=)`** 是刻意的：系统字号调到最大时允许卡片自然增高
（规范 §8「字号最大时重排而非截断」），代价是那种极端情况下失去等高。

**实测验证**：改后多列下所有卡片 **恒为 308px = 88dp**，同行内完全一致。

**已知代价（需用户知情）**：描述从 2 行减为 1 行，长描述被省略号截断得比较明显
（如「基于高级选择器和订阅规则的…」）。截断方向符合规范 §8「主文本保留完整 → 先截断支持文本」。
若将来觉得信息量不足，**替代方案是改成单列 + 描述恢复 2 行**（高度同样能等高，因为单列下宽度充裕），
但那会放弃大屏的空间利用率。

### 13.3 图标偏置与置顶标签遮挡（用户反馈第二轮，已修）

用户实测反馈两点：**「图标偏置」** 与 **「置顶角标覆盖在图标上，很奇怪」**。两项均实测取证。

#### (a) 图标偏置 —— `heightIn` 挂错了层级

**实测**：卡片 `[56,644][1384,952]`（中心 798），图标 `[98,686][238,826]`（中心 **756**）
→ **图标中心比卡片中心高 42px = 12dp**。

**根因**：88dp 的最小高度挂在 **`Card`** 上，但内部 `Row` 仍按内容收缩 —— 真机实测 `Row` 只有 **48dp**
（标题 1 行 24 + 间距 4 + 描述 1 行 20），于是内容**顶部对齐**、卡片底部留下 **32dp 死区**。

**修法**：把 `heightIn(min = ListRowHeight.Triple)` 从 `Card` 移到内部 `Row`。
Row 至少 88dp → 减上下内边距 16dp 得 72dp 内容区 → `CenterVertically` 正常生效。

**验证**：修复后图标中心 **798 = 卡片中心**，完全重合。

> 教训：**在容器上设最小高度，不等于内容会被居中** —— 内容对齐取决于内容自身容器的尺寸与对齐方式。

#### (b) 置顶标签遮挡图标 —— 语义错配

**实测**：「置顶」标签为 **79×56px（22.6×16dp）**，图标容器 **140×140px（40×40dp）**，
标签横向 `145~224` **完全落在**图标 `98~238` 之内 → **整个盖在图标右上角，遮住约四分之一**。

**根因是语义错配**：MD3 放在图标角上的是**小圆点**（覆盖刻意，但面积极小、不遮内容）；
这里是**带文字的长方形标签**，一覆盖就遮住图标本身。

**修法（经三轮调整，最终形态）**：移到**尾部状态区，与锁定按钮纵向排列**（标签在上、锁在下，整列水平居中）。

- **不放标题行**：试过做前缀（读作「置顶 GKD」），实测**挤窄标题约 29dp**，长标题更早折到第二行；
- **不并排与锁**：并排会让尾部横向变宽、挤占内容列；**纵向排列只占锁的宽度 48dp**；
- **高度恰好卡住**：`标签 20 + 间距 4 + 锁 48 = 72dp = 内容区(88-16)` → **不撑高卡片，等高不受影响**；
- **非置顶时列内只有锁 → 锁自然居中**（用户明确要求的行为）。

**实测验证**：置顶标签中心 x=**1076.5**、锁中心 x=**1076**（水平居中对齐）、标签 y 在上锁 y 在下；
卡片仍为 **308px = 88dp**（等高未破坏）。

> **为什么不改成纯色圆点**：圆点属**颜色通道**，规范禁止「用颜色表达状态、不给文字」。

### 13.4 我在此轮中犯的两个判断错误（记录以免重蹈）

1. **误判「标题前有大片留白」**：从缩放后的截图看像是标题偏右，实测标题起点 x=280
   = 图标右缘 238 + 12dp 间距 42px，**位置完全正确**。**截图缩放会制造虚假的布局印象** —— 结论必须回到 bounds 数值。
2. **误判 2 列下尾部空间不足**：实测 2 列时卡片宽 1230px、尾部需求仅 442px，**空间充裕**。
   若按截图印象去"优化"，会做出错误的改动。

---

## 14. UI 专项审计轮（两轴并行只读子代理 + 设备实测轴）| 2026-09-19

本轮聚焦 UI，分三轴：规范符合度 / 横向一致性 / **设备实测**（代码审计拿不到真实渲染尺寸）。

### 14.1 关于「保真原则」的适用范围（**重要判定口径，用户明确纠正**）

> **保真保的是业务逻辑，不是 UI 呈现。**

早期我以「与旧实现逐字一致」为由，保留了「列表卡片显示 `应用名/服务名`、详情头部只显示 `服务名`」
这个不一致（旧 Java 实现确实如此）。**用户纠正：这不成立。**

- 保真原则的目的是防止迁移中丢掉**文档未覆盖的行为** —— 锁身份、镜像纪律、线程模型、写入路径。
  那些是风险密度最高的地方（r1–r10 修复与缺陷清单密集覆盖），**必须逐行对齐**。
- **UI 层本来就是要按新骨架重写的**（规划 §0：「View 体系的 UI 工作无法迁移，只能按新骨架重写」）。
  故 UI 呈现**没有**「必须与旧版一致」的约束 —— 规范要的是符合 MD3 与设计规范，不是复刻旧界面。
- 结论：**凡「旧实现也这样」的 UI 不一致，都可以也应该改**；只有业务逻辑才受保真约束。

### 14.2 修复项

| # | 问题 | 性质 | 处置 |
|---|---|---|---|
| 1 | **同一服务在列表与详情显示不同名字**：列表 `应用名/服务名`、详情仅 `服务名` | 用户可见不一致 | 新增 `ui/model/ServiceIdentity.kt` 的 `serviceTitle()` 作为**全站唯一实现**，列表与详情共用；顺带把 `placeholderInitial` 从 `ui/home` 移到 `ui/model`（纠正"详情包反向依赖主页包"） |
| 2 | **给 `Button`/`TextButton` 加 `heightIn(min = 48dp)` 是错的** | **违反规范 §9①**（改良 MD3 原值） | 移除。依据：反编译 `ButtonDefaults` 得 `MinHeight = ButtonSmallTokens.containerHeight`，字节码 `ldc2_w double 40.0d` —— **MD3 按钮视觉高度是 40dp**，≥48dp 是 `minimumInteractiveComponentSize` 撑的**触控区**（不改视觉）。实测：移除后 168px→**140px = 40dp** ✓ |
| 3 | Switch 与锁定按钮相邻间距 **4dp** | 违反 §3「相邻目标 ≥8dp」 | 改 `SpacingTokens.sm`。实测间距 `1174-1146 = 28px = 8dp` ✓ |
| 4 | `HomeScreen.kt:302` 用内置 `CircleShape` 绕过 token | 违反「不得自造圆角」（与 `M3AppIcon` 曾犯同一错） | 改 `ShapeTokens.Full` |
| 5 | **`ServiceCard.kt` 仍在写「既有 R5 裁决」** | 伪造依据（`ServiceUiModel.kt` 已更正，此处漏改） | 改为「以 `WarningKind` 的 KDoc 为准」，并注明该说法是错误依据 |
| 6 | `ServiceCard.kt` 注释称「置顶标签已移到**标题行**」 | 注释与代码不符（实际在尾部） | 更正 |
| 7 | `ServiceCard.kt` 槽位表称支持文本「最多 2 行」 | 注释与代码不符（实际 1 行） | 更正 |
| 8 | `SettingsScreen.kt` KDoc 称分组用「卡片 + 留白」 | 注释与代码不符（**该文件零 `Card`**） | 更正为「列表项 + 留白」，并把「是否该改卡片」列为未决偏离 |
| 9 | `AppTheme.kt` 注释称「回落到 `colorPrimary`」 | 注释与代码不符（实际 `Color.Unspecified`） | 更正并说明为何用 `Unspecified` |
| 10 | 死代码：`M3Section`（零调用）、`PillRadius`（**18dp 不在十级刻度内** + 零引用） | 违反「不得自造圆角值」+ 死代码 | 均已删除 |

> **本轮「注释与代码不符」共 5 处**（第 5–9 项）。加上此前两轮已发现的 2 处，**今天累计 7 处**。
> 这是明显的系统性模式：**我在改动代码后未回头同步 KDoc**。凡改动一律要检查同处的 KDoc 是否还成立。

### 14.3 设备实测轴（代码审计拿不到的证据）

| 检查 | 结果 |
|---|---|
| **触控目标尺寸** | 锁定按钮 `168×168px = 48×48dp` ✓；Switch `182×168px = 52×48dp` ✓；搜索框高 56dp ✓；列表项 56dp ✓ —— **全部达标** |
| 相邻目标间距 | 8dp ✓（修复后） |
| Button 视觉高度 | 40dp ✓（修复后，与 MD3 一致） |
| **最大字号（1.3）下卡片高度** | **325 / 378px 不等高** —— 确认「88dp 等高」只保证**常规字号**下的等高；大字号时卡片按内容增高（这是 `heightIn(min=)` 的既定取舍，非缺陷） |
| 卡片高度（常规字号） | 308px = **88dp**，全行一致 ✓ |

**一次自己的误判（已纠正）**：首轮测触控目标时报出「有元素高度 148px = 42.3dp < 48dp」，差点当成缺陷。
滚动后复测发现该元素**被视口边缘裁切**，`uiautomator` 返回的是截断后的 bounds（同一批还出现 98/118/140/226/280 等值）。
**取各元素类型的最大观测值才是真实尺寸**；凡测量视口边缘元素必须先滚到完整露出。

### 14.4 记录为欠账（未修，需裁决或属已知项）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **规范偏离①「半透明 + 模糊」未落地，其 token 组已在 1.2.1 删除** | `EffectTokens` 的 `OVERLAY_ROLE` / `OVERLAY_ALPHA` / `OverlayBlurRadius` / `OverlayBlurRadiusLowEnd` / `OVERLAY_TRANSITION_MILLIS` / `SOLID_FALLBACK_ROLE` 在 1.2.1 中整体删除（原为零引用死 token）。根因不变：详情弹卡由 MDC `BottomSheetDialog` 承载（用户裁决），它用自己的容器色与 scrim。**规范三处偏离中：第二处（半透明+模糊）以「删除 token」收口；第一处（共享元素转场）仍放弃** |
| 2 | **规范 §4.2 弹卡圆角未落地** | `ShapeTokens.BottomSheetTop`（顶角 48dp）零引用，同上原因 |
| 3 | 分级缩放未落地 | `PRESS_SCALE_LARGE` / `PRESS_SCALE_SMALL` 零引用（现统一用 `PRESS_SCALE`） |
| 4 | **设置页分组手段与 §4 口诀不符** | 口诀「能点进去 → 卡片」，设置项可点但用列表项 + 留白。改卡片会导致满屏盒子（§17 反模式）。**待裁决** |
| 5 | 孤儿 token（刻度完整性） | `ShapeTokens` 的 `None`/`LargeIncreased`/`ExtraLargeIncreased`/`ExtraExtraLarge`、`SizeTokens` 的 `IconSmall`/`AppIconSize`/`SwatchDotSize`/`TopBarHeight`、`SpacingTokens` 的 `ContentPadding` 对象 —— **设计系统提供完整刻度是正常形态**，不算缺陷；但 `AppIconSize` 与 `IconContainerSize` 同为 40dp 属重复，建议合并 |
| 6 | `Entry.placeholder` 字段孤儿 | 随占位链删除后无读无写，按改动最小化未删 |
| 7 | 空态双实现 | Compose `EmptyState` 实际不可达（`fragment_home.xml` 的 `empty_view` 仍持有空态）—— 已知 P6 项（§5 约束 8） |
| 8 | 死字符串 3 条 | `home_title` / `service_switch_desc` / `auth_failed_alert`（`list_loading` 已在 1.2.1 删除；余 3 条仍属 P6） |

---

## 15. 双轴复审轮（UI 聚焦）| 2026-09-19 第二轮

固定点仍为 `c45c5aa`（改动未提交）。规范轴 + 需求轴并行只读子代理。
**核心价值是让一双没参与改动的手去验证"今天那 10 项修复本身对不对"**。

### 15.1 修复项

| # | 问题 | 性质 | 处置 |
|---|---|---|---|
| 1 | **`PressFeedback.kt` 有一句"自我辩护式注释"**：称"以 scrim 色叠层等效 brightness 0.86（token 化，非裸色值）"，而代码是 `Color.Black` | **注释为裸色值编造正当理由** | 重写：如实说明 `Color.Black` **不是** scrim 角色、也**不是**"非裸色值"；并解释为何仍用它（变暗在物理上要求无彩色压暗层，任何带色调角色都会引入色偏；与 `Color.Transparent` 同属结构性基元，可配置的只有已 token 化的**强度**） |
| 2 | **零服务空态：View 与 Compose 双实现，且写对的那份不可达** | 缺陷清单模式 10；且 View 那份**违规** | 删除 `fragment_home.xml` 的 `empty_view`（图标 **96dp**，超规范 §8 的 48–56dp 近两倍；且缺 §6 要求的「下一步」那段 —— `empty_desc` 定义了却无人用）；删除 `updateEmptyState()` 与 `emptyView` 字段；ComposeView 恢复「始终可见」。**Compose 的 `EmptyState` 现已可达**（56dp + 三段式） |
| 3 | `AppNavigationBar` KDoc 写「本项目 2 个」却不标偏离 | **注释掩盖规范冲突** | 重写：如实标注**与 §5 冲突**（规范表：<3 个一级目的「不用底部导航」；本项目 2 个却用了），说明未改理由（既有信息架构，改动=重构导航，应单独立项），并提示"勿当作符合规范" |
| 4 | `M3ListItem` 引「规范 §12.4」×2 | 引用出错（行高三档在 §3；§12.4 是间距与断点） | 改为 §3 |
| 5 | `SizeTokens` 引「规范 §12.2：48–56dp」 | 引用出错（图标尺寸在 §8；§12.2 是层级与表面） | 改为 §8 |
| 6 | `ShapeTokens` 引「规范 §4.2」×2 | **技能中无 §4.2**（该内容在 `docs/ui-redesign-plan.md` §4.2） | 标明真实出处，避免"规范说"与"项目规划说"混淆 |
| 7 | `ShapeTokens` 的 `[PillRadius]` 悬空引用 | 被我删 token 时遗漏 | 移除该引用 |
| 8 | `ServiceCard.kt` 未使用的 `import Image` | 死 import | 删除 |

### 15.2 规格文档同步（`ui-redesign-plan.md`）

| 项 | 原文 | 改为 |
|---|---|---|
| 网格公式 | `ceil((可用宽−8)/(400+8))` | **500dp**，并注明"400dp 是未与实现同步的过期值"（旧实现 `c45c5aa` 就是 500dp 且注释过原因） |
| 列表四槽 | 「尾部 = 锁定 IconButton + Switch」 | 反映实况：尾部为**状态区**（警示文字 + 置顶标签 + 锁定，纵向排列）+ 开关 |
| 卡片高度 | （无此条） | 补上 **88dp 等高**的规格与构成算式 |
| §4.3 四槽 | 「不得为视觉调整而改」 | 改为**记录两处用户裁决**（支持文本 1 行、置顶标签入尾部），并说明取舍理由 |
| 详情弹卡头部标题 | 「应用名 + 包名/类名」 | 补上标题规则（与列表共用 `serviceTitle()`）与"保真不约束 UI"的判定口径 |
| 弹卡圆角 28/48 | 陈述为实现要求 | 标注**未落地**（MDC Sheet 用自身形状） |
| 设置页分组 | 陈述用「卡片 + 留白」 | 标注**实况为列表项 + 留白**且**待裁决** |

### 15.3 复审确认**正确**的项（无需改）

规范轴逐项判定了今天 10 项修复，其中 8 项判"正确"并给了证据：
`ServiceIdentity` 三分支规则与两个消费方切换、移除 Button 的 `heightIn`（合 §9①）、
Switch 间距 8dp、`ShapeTokens.Full` 等价性、`PillRadius`/`M3Section` 确为零引用、
三处 KDoc 更正与代码相符、`heightIn` 挂 `Row` 且 `Row` 为 `CenterVertically`、
**触控目标口径全仓统一**（逐个数过：M3 件一律未声明，自绘可点区均已声明）。

§13 硬性禁令扫描：`.kt` 十六进制 **0**、`tween(` **0**、`fontSize/letterSpacing` **0**、
`fontWeight=` **0**、自造圆角 **0**（`CircleShape` 使用点已清零）。

### 15.4 新记录的欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | **底部导航与规范 §5 冲突** | 2 个一级目的按规范"不用底部导航"；本项目用了。**待裁决**（改则需重构导航承载） |
| 2 | **设置页标题用 `headlineMedium`(28sp)** | 规范 §2 的「大标题式顶栏」是 `headlineSmall`(24sp)，无 28sp 这一行。属规范缺口（MD3 的 large top app bar 确用 headlineMedium）还是取值错，**待判定** |
| 3 | **M3 组件仍带 ripple** | 偏离②只落地到自绘可点区；`Button`/`TextButton`/`Switch`/`SegmentedButton`/`RadioButton` 共 14 处仍是 ripple。已核实 `Button` **不接受 `indication` 参数**，唯一干净干预点是 `LocalIndication` 且需同时挂 `appPress`。**待裁决**（改则影响全站按压手感） |
| 4 | 规范 §3「相邻目标 ≥8dp」是否适用于上下紧贴的全宽列表行 | 规范未定义（设置页各行 0dp 间距）——**规范缺口** |
| 5 | 规范 §1「≤3 档字号」按 sp 值还是按 typography 样式计数 | 规范未定义 ——**规范缺口**（本项目按 sp 值执行并已可机械验证） |

---

## 16. 发布记录：1.2（2026-09-19）

### 16.1 已发布内容

| 项 | 值 |
|---|---|
| 提交 | **`ff288f4`** `feat: 1.2 —— UI 全面迁至 Compose + 业务层 Kotlin 化`（116 文件，+9171/−3249） |
| 分支 | `main` 与 `feat/md3-refactor` **均在 `ff288f4`**（`main` 由 `c45c5aa` fast-forward，无独有内容） |
| Tag | `v1.2`（annotated） |
| Release | https://github.com/illumiat/AccessibilityManager/releases/tag/v1.2 |
| 资产 | `app-release-1.2.apk`（**16.29 MB**，sha256 `f797d16e…`） |
| 版本号 | `versionCode 11` / `versionName 1.2` |
| 签名 | debug 证书 SHA-256 `873e18a0…` —— **与 1.0/1.1 相同，可覆盖安装** |

### 16.2 本次发布改变了什么做法（相对 1.0/1.1）

| 项 | 1.0 / 1.1 | **1.2** | 理由 |
|---|---|---|---|
| **APK 类型** | `app-debug.apk` | **release 构建 + debug keystore 签名** | debug 构建性能差：同场景实测掉帧率 **11.33% vs 0.35%**、帧时中位数 18ms vs 5ms。体积也从 24.3MB 降到 16.29MB。签名仍用 debug keystore，故兼容覆盖安装 |
| **仓库卫生** | `.gradle/` 等 16 个构建产物被跟踪 | **已 untrack + .gitignore 生效** | 二进制产物不该进仓 |
| 调试截图 | 无 `.gitignore` 条目 | `.codebuddy/shots/` 已忽略 | 真机验证产生的过程截图不应入库 |

> **今后发布一律用 release 构建**（构建命令 + 签名方式见 §13.1 的代码块）。这已写进 `AGENTS.md`。

### 16.3 发布后验收（已执行并通过）

- **远端资产 sha256 与本地逐字节一致**（`f797d16e…`）；
- **下载包的签名验证通过**，证书与 1.1 相同；
- **包内元数据**：`versionCode='11' versionName='1.2'`，`compileSdkVersion='35'`；
- **干净的从提交构建**：`./gradlew clean :app:assembleRelease :app:assembleDebug` → BUILD SUCCESSFUL
  （即仓库内容可独立编译，不依赖本地未提交状态）；
- 远端 `README.md` 已显示 1.2、截图资产就位。

### 16.4 未完成 / 待办

| # | 事项 | 说明 |
|---|---|---|
| 1 | **从 1.1 升级到 1.2 的路径未验完** | 验证做到一半设备 USB 断开（`adb devices` 空，重启 adb server 无效）。已确认的是**签名一致**（这是覆盖安装的必要条件），但「装 1.1 → 覆盖装 1.2 → 配置保留」未走完 |
| 2 | 发布说明里的「已知遗留」全部仍成立 | 保活链路真机回归未做、折叠屏/分屏未验、弹卡旋转行为与 1.1 一致 |
| 3 | **截图仍是同一台小米设备的** | `screenshot-phone.jpg`（1440×3200 真实竖屏）与 `screenshot-tablet.png`（`wm size 2600x3200` 模拟的 743dp 宽，**同一台设备**）。若需真实平板截图，应另找设备 |

> ⚠️ **本仓库的 git 身份是仓库级配置**（`git config --local`），因为全局未设：
> `user.name=illumiat` / `user.email=128163683+illumiat@users.noreply.github.com`
> （该 noreply 地址经 `gh api user` 核实确属 `illumiat`）。全局配置未被改动。`

---

## 17. 1.2.1 修复与验证（2026-09-20）

> 1.2.1 是 **修复版**。全部改动已落在 `ad0594e` 之前的工作树上（HEAD = `ad0594e`，工作树干净），
> 本仓库状态以 `git log` 为准。本节给下一个接手的人判断「哪些能信、哪些不能信」。

### 17.1 本轮已完成改动（分类要点）

**业务层（保活 / 调度 / 权限）**

- `RestartWorker` 卸载清理分支补 `cancelIfIdle`（`executeDue` 与 `compensatePending` 两处同口径）；补偿分支补「应用是否已安装」校验。
- `RestartWorker` 读回后**无条件**校准 `daemonService.tmpSettingValue`（此前为 `if (after.isNotEmpty())`）。
  技术要点：观察者先把 `null` 归一为 `""` 再与镜像比较（`daemonService.kt:65-66` / `:82-84`），
  故镜像 `""` 与「空」在判定上等价；旧写法会让镜像停在刚写入的 `newValue`，与读回实际值不符 →
  观察者把**自身写入**当外部改动 → 自触发隐患。属修复，非引入风险。
- `RestartPrefs.enabledCount` 的快照提到循环外一次读取。
- `PermissionHelper` 提取 `grantCommand(context, withExit)`，两处命令字符串逐字不变；删除 `minSdk 24` 下不可达的 else 分支。
- `daemonService` 删未用 import 与恒假子条件 `serviceName == null`。

**主题取色**

- 动态取色由 `App` 的进程级 `DynamicColors.applyToActivitiesIfAvailable()` 改为 `MainActivity.onCreate`
  按当次持久化主题对单个 Activity 应用（修复运行期「切到跟随壁纸不生效 / 切回命名主题回不去」）。
- prefs 名与键改走 `ThemePref` 访问器（新增 `nightModeOrNull` / `nightMode`）；夜间模式选择镜像进 `ui`。

**UI 与设计系统**

- `M3ListItem` 行高档位改按 `titleMaxLines` 与 `supportingMaxLines` 两个上限推导（`supportingMaxLines = 3` 生效；`= 2` 与无 supporting 的调用方档位不变）。
- `M3AppIcon` 位图分支补形状裁剪（与占位分支同形状）；占位分支加 `clearAndSetSemantics {}`。
- `M3SectionHeader` 补 `heading()` 语义。
- `PressFeedback` 补 focus / hover 描边（用 `Modifier.border`；compose-ui 不存在 `drawOutline`，已在技能与注释中记明）。
- `ServiceCard` 锁定按钮：标签挂容器且两种状态都提供；未启用时不再声明 `Role.Button`。
- `SettingsScreen` 水平内缩收敛到唯一归属，消除「组件侧 32dp / 非组件侧 16dp」错位。
- `PeriodDialog` 换成溢出前置判定（Long 回绕不再能绕过校验）。
- `AppTheme` 角色查询提取为 `ColorSchemeRoles`；`remember` 的 key 纳入 `context.theme`。
- 删除 `showsWarning`、删除 `EffectTokens` 的 overlay 组；`SizeTokens.IconSmall` 18→20dp。
- 依赖：`work` / `shizuku` 收编进版本目录 `libs.versions.toml`；删除零引用的 `coreKtx` / `window` / `lifecycle`。
- 删除死方法 `updateRestartSummary`、死串 `list_loading`。
- `ThemeName` / `ContrastLevel` 面向用户文案移出设计系统库，改由 app 层 `labelRes()` 映射 + `strings.xml` 资源。

### 17.2 真机验证结果（已做）

设备与 1.2 回归同机（23116PN5BC / Android 16 / SDK 36）。

- **覆盖安装**：release 包用 debug keystore 签名，证书 SHA-256 与 1.2 一致 → 覆盖安装成功且配置保留。
- **安装未掉线**：覆盖安装后 `Settings.Secure.enabled_accessibility_services` 仍是同样 7 条。
- **守护者状态**：`daemonService` 作为前台服务运行；应用托管的 7 个服务与系统表完全一致。
- **保活链路 A/B 对照实验**：向该设置键写入一个非法探针条目后 —— 守护者在跑时，值被补回为托管服务集合；守护者被 `force-stop` 后，值为 `null`（无人补回）。两组唯一差别是守护者，故补回可归因于本应用。
- **设置页水平内缩**：用 `uiautomator` 的 `bounds` 数值验证，标题 / 副标题 / 列表项文本左边界统一在 16dp。

### 17.3 仍未验证 / 已知限制（如实列）

> 2026-09-20 晚些时候已补做其中 4 项（见 §18.3），下表为**补做后仍**未验证的。

- **伪关闭恢复、多服务并发**（保活与补偿链路）—— 未做真机回归。
- **动态取色「跟随壁纸」的视觉效果**未做逐像素比对（只证明了机制无状态，见 §18.3）。
- **详情卡卡片外壳**只做了参数级与结构级核验，**未做像素级比对**。
- lint 在当前工具链（AGP 8.7.3 + compose）下仍不可用，不作为验收依据。

### 17.4 主线程补做的机械等价性验证（2026-09-20，事后补齐）

本轮的「等价类」改动（提取 / 重构）在批次交付时**只写了验收标准、没有被证明过** ——
子代理的自述报告在上一轮中断中丢失，主线程当时仅读了 diff 就接受了。事后补齐如下，
方法均为**脚本机械比对**（旧实现 ↔ 新实现），不是人眼审阅：

| 改动 | 当时写下的验收标准 | 实测结果 |
|---|---|---|
| `AppTheme` 角色查询提取为 `ColorSchemeRoles` | 两分支产出逐字等价 | 34 个角色的**解析表达式逐项一致**；两侧无增无减；两个 scheme 的 34 个字段全部 `r.<同名>` 一一对应；且旧实现的 dark / light 两分支本来就逐字相同 |
| `M3ListItem` 档位改按两个 `maxLines` 推导 | 其余调用方档位不变 | 10 个真实调用点中**仅** `SettingsScreen.kt:169`（`supportingMaxLines = 3`）由 Double→Triple —— 正是本次要修的那行；`SwitchRow`（`:258`）仍 Double |
| `ServiceDetailScreen` 卡片壳提取 | 外观参数逐项一致 | 旧 2 处 `Surface` 参数本就相同 → 新 1 处，`border`（含 `outlineVariant` 第二参数）/ `color` / `modifier` / `shape` 全同 |

**边界**：第三条只证明**参数等价**，**未证明渲染等价**（未做像素比对）—— 故 §17.3 仍保留该项。

**一处需知悉的行为变化（不在用户划定的保真链内）**：`RestartPrefs.enabledCount` 由
`sp(c).getBoolean(key, false)` 改为对快照值做 `java.lang.Boolean.TRUE == value` 比较。
若某 `.enabled` 键存的**不是** Boolean，旧实现抛 `ClassCastException`、新实现静默算作 false。
实务上等价（该键全由 `putBoolean` 写入），且新行为比崩溃安全 —— 此处按事实记录，**不为其补"正当理由"注释**。

### 17.5 过程记录：本批修复由 4 个并行子代理产出

4 个 `[模式:修改]` 子代理按互不重叠的文件白名单并行，共 26 个文件。结果：**2 个批次破坏编译（共 5 处）、
自检命中 0 处**，全部由主线程统一构建抓出；其中一个批次**未执行**给定的裁决口径（设置页水平内缩只做了一半），
另一个批次**无视**「不得改白名单外文件」的明确禁令，破坏了跨文件契约（`ServiceUiModel` 构造参数被删、
生产者 `HomeListState` 悬空）且未上报。
故本节的结论一律以**主线程复核**为准，不以子代理自述为准。

### 17.6 与既有文档的订正（本次同步）

- 见 §6 第 3 条：「跟随壁纸」对比度改为**已裁决取舍**（非待修缺陷）。
- 见 §11 根因扫描表：单选对话框「选中即 recreate」的断言无依据，改为「选择会镜像进 `ui`，不再依赖是否重建」。
- 见 §4 第 3 条、§10、`§14.4 第 8 条`：死串 `list_loading` 已在 1.2.1 删除（余 3 条仍属 P6）。
- 见 §14.4 第 1 条：`EffectTokens` 的 overlay 组已在 1.2.1 删除（原为零引用死 token），规范偏离①以「删除 token」收口。
- 见 §11「经核实后判定无需改」：`DynamicColors` 调用位置在 1.2.1 由 `App` 移至 `MainActivity.onCreate`（见本節 17.1）。

## 18. 发布记录：1.2.2（2026-09-20）

**这是修复版**：只改了一处用户可见的显示错误，并补做了 1.2.1 遗留的真机验证。

| 项 | 值 |
|---|---|
| 提交 | `1f08029`（`feat/md3-refactor` 与 `main` 均已推） |
| Tag | `v1.2.2` |
| Release | https://github.com/illumiat/AccessibilityManager/releases/tag/v1.2.2 （Latest） |
| 资产 | `app-release-1.2.2.apk` 16.29 MB，sha256 `c72a918a…` |
| 版本 | `versionCode 13` / `versionName 1.2.2` |
| 签名 | debug 证书 `873e18a0…`（与 1.0 / 1.1 / 1.2 / 1.2.1 相同 → 可覆盖安装） |

发布后验收：远端资产 sha256 与本地**逐字节一致**、签名证书相同、包内 `versionCode=13 / versionName=1.2.2`。

### 18.1 本次修复：页脚版本号显示错误

**真机确证**：1.2.1 的包（`versionName=1.2.1`）在设置页页脚显示 **「无障碍管理器 v9.0」**。
`v9.0` 全仓库仅出现在 `strings.xml` 一处、无任何依据支撑，与该文本「版本+号」的意图不符。

修法：**从包内版本号派生**，而不是把字面量改成当时的版本号（那样下个版本会再次过期）——
`app/build.gradle` 开 `buildConfig true`；`strings.xml` 改为 `无障碍管理器 v%1$s`；
`SettingsScreen.kt` 用 `stringResource(R.string.version_line, BuildConfig.VERSION_NAME)`。

**修后屏幕确认**：页脚显示「无障碍管理器 v1.2.2」。

### 18.2 为什么值得单独发一版

这是**用户可见的错误信息**，且误导方向最坏（用户报 bug 时会报出错误的版本号）。改动面极小、零行为风险。

### 18.3 补做的真机验证（1.2.1 时列为未验证的四项）

| 项 | 验证方式 | 结果 |
|---|---|---|
| 动态取色运行期切换「方向 B」 | **字节码证据**（非观察） | `applyToActivityIfAvailable(Activity)` 的字节码中 `registerActivityLifecycleCallbacks` 出现 **0** 次，而旧 `applyToActivitiesIfAvailable(Application)` 出现 **1** 次；仓库内进程级注册**代码零处**（唯一命中是 `App.kt` 的解释性注释）。机制无状态 + 主题变更必走 `recreate()` → 该路径**结构上不可能再出现** |
| 三行策略文案是否溢出 | `uiautomator` 的 bounds | 资源原文 43 字符，实测渲染 **2 行**（140px），未触及 3 行上限。该行内容合计 ≈ **88dp**，与 Triple 档一致 |
| 卡片外壳提取 | 真机 bounds + 参数级比对 | `Switch` 182×168px = **52×48dp**（M3 默认尺寸，未额外声明最小尺寸）；开关垂直中心与标题 y **完全重合**（头部 `Row` 垂直居中）；两张卡正文左边界同为 **112px**（内边距一致） |
| 权限撤销 | UI 三态前后对照 | 「已授予」→ **「未授予」** →「已授予」；全程**崩溃 0 行**、无障碍服务表未受影响；撤销后 `grant` 可完整还原（测前后逐项核对过） |
| 灭屏 / 亮屏 | `settings` / `dumpsys` 对照 | 表 7 条不变、`daemonService` 存活、`Crashed services` 无新增 |

### 18.4 仍未验证（延续 §17.3）

- **伪关闭恢复、多服务并发**未做真机回归。
- 动态取色的**视觉效果**未逐像素比对（仅证明机制无状态）。
- 卡片外壳**未做像素级**渲染比对（仅参数级 + 结构级）。

### 18.5 过程记录：本轮的设备侧事故与沉淀

做真机验证时，主线程的自动化**三次把操作落到别的应用上**（首次 dump 抓到无关应用；
一次「对话框未打开」的读数实为别的界面；一次 `KEYCODE_BACK` 退到桌面后后续点击落空），
并**改动了一次用户的主题设置**（已还原并复读验证）。
根因三条，均为可预防的：

1. **交互前没有校验前台是谁**，只在事后加校验、还把其警告忽略掉去解读数据。
2. **`exec-out` 是 adb 自身的命令，不能走 `adb shell`**（写成 `adb shell exec-out cat` 恒返回 0 字节）；
   同一次 dump() **未断言非空** → 又是「静默空结果」。
3. **`KEYCODE_POWER` 灭屏必然带锁屏**，未预判 → 后续 UI 验证被卡住。

**沉淀**：
- 工具 `../_tools/amui.py` —— 真机 UI 定位器，**每次 tap/swipe 前断言前台**、
  dump 必须含 `<hierarchy>` 且节点数非 0、对话框类操作先断言选项已出现，任一不满足即拒绝操作。
- `.codebuddy/rules/delegation-spec.md` 新增「并行修改类发配（实测约束）」五条；
  并修正「子代理类型」条款（`Explore` 无写工具，修改类不能一律用 code-explorer）。
