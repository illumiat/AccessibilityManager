# AccessibilityManager · UI 重设计规划

> 依据技能：**`design-md3-apple-craft`**（MD3 骨架 × 苹果标尺）
> 本文是**执行版规格**：只写「做什么、取什么值」，不赘述「为什么这么选」。
> 凡本文未列出的视觉决定，一律取 MD3 默认值。

---

## 0. 结论先行：这份规范与现有技术栈正面冲突

**必须先说清楚这件事，否则后面全是空谈。**

| 规范要求 | 项目现状 | 差距 |
|---|---|---|
| `androidx.compose.material3:material3:1.5.0-alpha18`（Expressive） | MDC `com.google.android.material:material:1.13.0`（**View 体系**） | 换体系 |
| Kotlin（Compose 编译必需） | **0 个 Kotlin 文件**，14 个 Java 文件 | 加语言 |
| Kotlin 插件 | **完全未应用** | 加插件 |
| **禁止** `material-components-android` | 正在用，且已深度使用（主题属性、全部布局） | 移除 |

规范原文裁决：

> 不要用 `material-components-android`（MDC / View 体系）：**1.14.0 是终版，已进入维护模式**。

**三条不可回避的事实：**

1. **本项目在 View 体系上做的全部 UI 工作**（M3 主题、网格公式、四槽卡片、详情弹卡）**无法迁移到 Compose**。
   可迁移的是**信息架构、文案、槽位设计、业务逻辑**；不可迁移的是布局 XML 与样式定义。
2. **Compose 与 Java 可以互操作**，因此**不必重写全部 14 个 Java 文件** ——
   `daemonService` / `RestartWorker` / `RestartPrefs` / `IconCache` 等**业务层保持 Java**。
   这是本规划的关键杠杆（见 §5）。
3. 规范的三处偏离（半透明模糊 / 缩放变暗 / 共享元素）**在 View 体系里都做不出合规版本**；
   尤其共享元素转场依赖 `SharedTransitionLayout`（Compose 专有）。

**因此：本规划按「UI 层迁 Compose、业务层保留 Java」推进，不做全量重写。**

---

## 1. 现状盘点（改造起点）

### 模块

| 模块 | 内容 |
|---|---|
| `:app` | 全部实现：14 Java + 6 layout + values |

### 业务层（**保留 Java，不动**）

| 文件 | 职责 | 迁移影响 |
|---|---|---|
| `daemonService.java` | 前台保活服务、事件驱动、`tmpSettingValue` 镜像 | 无 |
| `RestartWorker.java` | 定期重启调度、焦点判定、pendingEnable 补偿 | 无 |
| `RestartPrefs.java` | 重启配置持久化（集合化 + 持锁） | 无 |
| `IconCache.java` | 图标 LruCache + 异步队列 | 无（UI 层换调用方式） |
| `PermissionHelper.java` | Shizuku / WRITE_SECURE_SETTINGS 授权链 | 无 |
| `StartReceiver.java` / `App.java` | 开机自启 / Application | 无 |

### UI 层（**迁移对象**）

| 现状 | 处理 |
|---|---|
| `MainActivity.java`（容器 + 底栏） | → Compose 外壳 `MainShell` |
| `HomeFragment.java`（列表/搜索/网格/详情弹卡/周期对话框） | → Compose `HomeScreen` + `DetailSheet` + 对话框 |
| `SettingsFragment.java`（保活/定期重启/外观/授权与帮助） | → Compose `SettingsScreen` |
| `ServiceAdapter.java` | → `LazyColumn`/`LazyVerticalGrid` + `M3ListItem` 四槽 |
| `TouchDelegateLayout.java` / `GridSpacingDecoration.java` | 迁移后作废（Compose 用 `Modifier` 与 `LazyGrid` 间距） |
| `layout/*.xml`（6 个）、`menu/bottom_nav.xml` | 迁移后作废 |
| `values/themes.xml`（手写 M3 角色色值） | → **构建期算法生成**（规范禁令：不得手改单个角色色值） |

### 页面清单（1 外壳 + 2 一级页 + 1 弹卡 + 3 对话框）

| 类别 | 页面 | 承载 |
|---|---|---|
| 外壳 | `MainActivity` | `NavigationBar` 双 tab + Compose 容器 |
| 一级 | `HomeFragment` | 服务列表（搜索、网格、置顶、锁定、开关） |
| 一级 | `SettingsFragment` | 保活 / 定期重启 / 授权与帮助 / 外观 / 版本 |
| 弹卡 | 服务详情 | 基本信息、能力与事件、定期重启、打开系统设置 |
| 对话框 | 周期输入 / 主题选择 / 授权引导 | — |

---

## 2. 落地基线（工具链，已一手核实 + 本仓库实测通过）

### ⚠️ 规范里「material3 1.4.0 即为公开 API」的说法不成立

`1.4.0` 中 `MaterialExpressiveTheme` 与 `MotionScheme.expressive()` 是 Kotlin **`internal`**
（须查 `sources.jar`，不看注解 —— 真正的门是 `internal` 可见性），应用代码调不到。
且**所有 compose BOM 都把 material3 钉在 1.4.0**，套上就拿不到 Expressive API。

### 实测可用的版本组合（已在 `gradle/libs.versions.toml` 落地并构建通过）

| 项 | 取值 | 说明 |
|---|---|---|
| AGP | **8.7.3** | 规范基线要求 ≥ 8.6.0，本仓库既有工具链满足，保持不动 |
| Gradle | 8.9 | 现有 wrapper（JDK 21 强制） |
| Kotlin | **2.1.20** | compose 1.11.0-beta02 与 material3 1.5.0-alpha18 的 POM 均要求 |
| material3 | **1.5.0-alpha18** | Expressive API 公开 + compileSdk 35 + AGP 8.6.0 的最高交集 |
| compose | **1.11.0-beta02** | 与 material3 alpha18 混用无兼容问题 |
| material-icons-extended | 1.7.8 | 底栏需 filled/outlined 两态图标 |
| **compileSdk** | **35** | material3 alpha18 的 AAR 元数据要求（原 34） |
| minSdk | 24 | **不变** |
| targetSdk | 33 | **不变**（保活链路不触碰平台行为变更） |
| MDC（过渡期） | **1.14.0** | 原 1.13.0 → 1.14.0：1.13.0 缺 `colorSurfaceContainerLowest` 等档位；1.14.0 是终版、要求 AGP 8.7.3+ 与 minSdk 23，本仓库恰好满足 |

> **不使用 compose BOM**：所有 BOM 都把 material3 钉在 1.4.0，且约束无法覆盖。

### 模块形态（规范 §14 落地形态）

```
:core:designsystem        设计系统骨架（业务模块只消费、不定义）
├─ theme/                 token：色彩角色、形状、字阶、间距、尺寸、效果、动效
├─ component/             组件外观：只做规范件库里的具名件
└─ src/test/              构建期配色生成器（生成静态主题资源）
:app                      业务模块（UI 层迁 Compose、业务层保留 Java）
```

**验证记录**：`:core:designsystem:compileDebugKotlin` 与 `:app:assembleDebug` 均已通过。

---

## 3. 设计系统（`:core:designsystem`）

### 3.1 已落地

| 文件 | 内容 |
|---|---|
| `theme/ShapeTokens.kt` | 形状十级刻度 + `appShapes`（不得自造圆角） |
| `theme/SpacingTokens.kt` | 间距七级 + 内容边距三档（compact/medium/expanded） |
| `theme/SizeTokens.kt` | 具名尺寸，含 **`MinTouchTarget = 48dp`** 硬性下限 |
| `theme/EffectTokens.kt` | 三处偏离的执行参数（58%/16dp 模糊、0.975/0.86 反馈、32% scrim） |
| `theme/AppTypography.kt` | 30 个字阶（15 常规 + 15 强调）全量注入字体族 |
| `theme/AppTheme.kt` | `MaterialExpressiveTheme` + `MotionScheme.expressive()`；配色从主题属性解析 |
| `component/PressFeedback.kt` | `Modifier.appPress` / `appClickable`（缩放 + 变暗，`indication = null` 去 ripple） |
| `component/M3ListItem.kt` | 四槽列表项 + 行高三档（56/72/88） |
| `component/M3SectionHeader.kt` | 分节标题（`titleMedium` + `onSurface`，自带 32/8dp 间距） |

### 3.2 配色 —— 规则 ④：可切换的命名主题

**必须内置命名主题供用户切换**，动态取色只是其中一个选项：

| 主题 | 源色 | 变体 | 默认 |
|---|---|---|---|
| 品牌 | `#4759B8`（既有品牌色） | `Tonal spot` | **是** |
| 中性 | `#0066CC` | `Neutral` | 否 |
| 单色 | `#0066CC` | `Monochrome` | 否 |
| 跟随壁纸 | 系统壁纸 | 动态取色 | 否（**不设为默认、不强制**） |

**对比度四档必须暴露给用户**：`-1.0 降低 / 0.0 默认 / 0.5 较高 / 1.0 最高`。
**不得实现成两套硬编码配色** —— 它是同一套调的参数，由算法生成。

**关键机制（沿用已验证方案）**：配色由**构建期**用 `material-color-utilities`
（`SchemeTonalSpot` / `SchemeNeutral` / `SchemeMonochrome`）生成成**静态资源**，
Compose 与 View **从同一套主题属性取值**。
于是「切换主题」只需换 Android theme（`setTheme` + 重建），全应用一起变；
配色仍由算法从源色展开，不违反「不得手改单个角色色值」。

**✅ 已落地**：
- `core/designsystem/src/test/.../PaletteGeneratorTest.kt` —— 构建期生成器
  （运行：`gradlew :core:designsystem:testDebugUnitTest --tests "*PaletteGenerator*"`）。
  产出**直接写入** `core/designsystem/src/main/res/`，共 **24 套调色板 + 12 个主题样式**。
- `theme/ThemeCatalog.kt` —— `主题 × 对比度 → 样式 id` 映射，显式引用 `R.style.*`，
  **拼错编译期即报错**（不用 `getIdentifier` 反射）。
- `app/ThemePref.kt` —— 偏好持久化 + 套用（`setTheme` 必须在 `super.onCreate()` 之前）。
- **已删除** app 里手写色值的 `values/themes.xml` 与 `values-night/themes.xml`（规范禁止项）。

> **遗留**：「跟随壁纸」的**对比度**暂不生效 —— 壁纸取色由 MDC `DynamicColors` 在
> 运行时叠加，目前不支持叠加对比度参数（`DynamicColorsOptions` 无该入参）。
> 待 Compose 侧接管后改用 `dynamicLightColorScheme`/`dynamicDarkColorScheme`，届时再评估对比度通道。

### 3.3 动效（规则 ③）：弹簧，不用时长

- 一律走 `MaterialTheme.motionScheme`（`MotionScheme.expressive()`）。
- **禁止 `tween` 固定时长替代弹簧** —— 会把连续性（L1）与可中断性（L2）直接做废。
- 短距用 `fast*Spec`、长距用 `slow*Spec`（判据 L3：节奏与位移成比例）。

### 3.4 层级与反馈（偏离 MD3 ① ②）

| 项 | 规格 |
|---|---|
| 叠加层底色 | `surfaceContainer`（bottom sheet 用 `surfaceContainerLow`） |
| 不透明度 / 模糊 | **58% / 16dp**（低端机 12dp） |
| **降级** | `surfaceContainerHigh` **实色**（必要条件，不是可选优化） |
| scrim | **32% 固定**，承担「阻断交互」 |
| 按下反馈 | 缩放 **0.975**（大块 0.98 / 小块 0.96）+ 变暗 **0.86** |
| 反馈触发 | **按下即响应**，不等抬手；**长列表滚动中不得触发** |

> **⚠️ 实现前提（一手核实）**：Compose 的 `Modifier.blur` KDoc 原文是 **"Draw content blurred"** ——
> 它模糊的是**该 composable 自己绘制的内容**，**不是背后被遮挡的内容**，且仅 Android 12+ 生效。
> 在只有一个背景色的 Box 上挂 `blur()` 等于什么都没模糊。
> 真正背景模糊需借：宿主 View `RenderEffect` / 第三方库 `haze` / 手工抓取下层。
> **不具备该能力时走降级实色，不要自创中间不透明度（如 92%）。**
> 且**叠加层底下必须有真实滚动内容**才成立（纯色背景上做模糊 = 灰雾，比实色更差）。

---

## 4. 页面级重设计

### 4.1 一屏一个主角（规范 §1）

| 页面 | 动词 | 主角 | 降级项 |
|---|---|---|---|
| 主页 | **浏览** | 服务列表 | 搜索框、顶栏标题 |
| 设置 | **配置** | 分组卡片 | 版本行 |
| 详情弹卡 | **确认** | 应用身份（图标 + 名称 + 包名） | 各信息卡 |

**硬约束：一屏 ≤3 个字号层级。**

### 4.2 逐页映射

**主页**
- 顶栏：`titleLarge` 标题（**不与大标题同屏**，二选一）；搜索框 `SearchBar`（`Full` 圆角）。
- 列表：**四槽列表项**（缩略图 = 应用图标 40dp / 主文本 = 应用名或服务名 / 支持文本 = 状态语义行 / 尾部状态区 = 置顶标签 + 锁定 + `Switch`）。
- 网格：`LazyVerticalGrid` + 动态列数（公式 `ceil((可用宽−8)/(500+8))`，下限 1；
  手机竖屏 1 列 → 平板竖屏 2 列 → 平板横屏 3 列+；间距 8dp、卡片圆角 `Medium` 12dp）。

  > ⚠️ **单卡最大宽取 500dp，不是 400dp**。本文早期版本写 400dp，那是**过期值**（未与实现同步）。
  > 既有实现在 `c45c5aa` 就已上调为 500dp 并注释了原因：**400dp 会让密度调整后的手机竖屏
  > （≈421dp）误入 2 列** —— `ceil((421−8)/408) = 2`，而 `ceil((421−8)/508) = 1`。
  > 现实现见 `HomeScreen.kt` 的 `CardMaxWidth`。

- **卡片高度固定 88dp**（用户裁决，2026-09-19）：标题最多 2 行 + 支持文本限 1 行 + `heightIn(min=88dp)`，
  构成 `16(内边距) + 48(标题 2 行) + 4(间距) + 20(支持文本 1 行) = 88dp`，
  常规字号下所有卡片等高（规范「列表行高只取 56/72/88」三档的最高档）。
  最小高度须挂在**列表行的 `Row`** 上而非 `Card`（挂 `Card` 会导致内容顶部对齐、底部留死区）。
- **空间策略**：锁定按钮与开关是卡片必备控件，任何列数下都必须渲染；
  宽度不足时收缩的是描述文本（降 1 行省略），**绝不动操作控件**。
- 触摸目标：锁定/开关视觉可 40dp，**命中区一律 ≥48dp**（`TouchDelegate` 在 View 侧的做法不再需要，
  Compose 用 `Modifier.sizeIn(minWidth/minHeight = 48.dp)` + 多矩形分发的替代方案）。
- 三态：**零服务空态**（48–56dp 图标 + `titleMedium` 说明「这里本该有什么」+ `bodyMedium` 下一步）、
  **首屏骨架屏**（不用转圈；骨架形状接近真实内容）、**错误态就地显示**。

**详情弹卡**（<600dp `BottomSheet` / ≥600dp `SideSheet`，圆角 `ExtraLarge` 28dp / 顶角 `48dp`）
- 头部：图标 + 应用名或服务名（规则见下）+ 包名/类名
- 基本信息卡片 / 能力与事件卡片（chips）/ 定期重启卡片（`Switch` + 周期 + `[修改周期]` + 上次执行**相对时间**）
- `[打开系统设置]` Filled Button

> **头部标题规则**：与列表卡片**共用同一实现**（`ui/model/ServiceIdentity.kt` 的 `serviceTitle()`）：
> `应用名 != 服务名` 时显示 `应用名/服务名`，否则显示可得的那一个，都无则用服务类短名。
> 早期实现详情头部只显示服务名、列表显示 `应用名/服务名`，**同一个服务在两页名字不同**；
> 已于 2026-09-19 统一（**保真原则只约束业务逻辑，不约束 UI 呈现**，见 `HANDOVER.md` §14.1）。

> ⚠️ **圆角 28dp / 顶角 48dp 未落地**：弹卡由 MDC `BottomSheetDialog`/`SideSheetDialog` 承载
> （用户裁决保留原生 Sheet），它使用自己的默认形状。`ShapeTokens.BottomSheetTop` 零引用。

**设置**
- 分组：保活 / 定期重启 / **授权与帮助** / 外观 / 版本
- 外观组新增：**主题选择器**（4 个命名主题）+ **对比度四档**（规则 ④ 的界面）
- 分组手段：**卡片**（内容是可独立操作的对象）与**留白**（不同语义区块）——同屏最多混用两种

  > ⚠️ **实际实现与本节不符，且为未决项**：设置页用**列表项 + 留白**，**零 `Card`**。
  > 口诀「能点进去 → 卡片」按字面适用，但设置页有 10+ 个可点项，全改卡片会满屏盒子
  > （违反 §17 反模式「满屏卡片」）。**待裁决**，见 `HANDOVER.md` §14.4。

### 4.3 列表条目：四槽

见 `component/M3ListItem.kt`。截断优先级：**主文本保留完整 → 先截断支持文本**。

**槽位定义**（2026-09-19 更新，反映用户裁决）：

| 槽 | 主页服务卡片的内容 |
|---|---|
| 缩略图 | 应用图标 40dp（未加载 → 灰底首字占位） |
| 主文本 | 应用名/服务名（最多 **2 行**，可独立控行数） |
| 支持文本 | 状态语义行（失败 / 重启中 / 重启摘要 / 服务描述）（**限 1 行**） |
| 尾部 | **状态区**（警示文字 + 置顶标签 + 锁定按钮，纵向排列）+ 开关 |

> **两处 UI 裁决，均以「用户裁决」而非「规范缺口」记录**：
> 1. **支持文本 2 行 → 1 行**：卡片固定 88dp 等高的必要条件
>    （`16 + 48 + 4 + 20 = 88dp`）。截断方向符合「主文本保留完整 → 先截断支持文本」。
> 2. **置顶标签进入尾部槽**：原叠在图标角上，实测该标签 79×56px 而图标容器仅 140×140px，
>    且标签横向完全落在图标范围内 —— 遮住约四分之一。移到尾部后与锁定按钮**纵向**排列
>    （不并排，避免横向挤占内容列；高度 20+4+48=72dp 恰等于内容区，不撑高卡片）。
>
> 这两项是**对规矩的刻意放宽**，不是遗漏。规范技能 §3 说「列表项高度只取三档」、
> 四槽「不得为视觉调整而改」；本项目的取舍是**高度纪律优先于行数自由**，理由是
> 多列布局下高度不一致的观感损失更大（`HANDOVER.md` §13.2 有实测数据）。

---

## 5. 迁移策略：UI 层迁 Compose，业务层保留 Java

### 关键杠杆

0. **业务层保持 Java（用户裁决 2026-09-19）**：那 1309 行是风险密度最高的区域
   （`synchronized` / `volatile` 镜像 / `ContentObserver` / `Worker` / 焦点判定），
   是 r1–r10 修复与缺陷清单密集覆盖的对象。现在转 Kotlin = **高风险零功能增益**，
   与「稳定性绝对优先」原则冲突。UI 层本来就要重写，转 Kotlin 的边际成本≈0；业务层反之。
   **约束：Java 类型绝不能进入组合树** —— 必须新增 Kotlin 不可变模型层（`data class`）作边界，
   否则 Java POJO 没有 `equals`/不可变语义 → Compose 判定为 unstable → 跳过重组失效 → 掉帧。
   **语言统一已列入规划**（见 §5.1 业务层重构规程）：**规格先行的一次性重写**（S → R），
   排在 Java UI 删除之后——那时调用方全为 Kotlin，可一遍写到位，不必先写一层兼容垫片再返工。
   已完成的 K1（`IconCache` 转 Kotlin）定位修正为「产出实测清单」，其代码将在 R 阶段按规格重写。
1. **Compose 与 Java 互操作** → 业务层 0 改动，风险隔离在 UI 层。
2. **主题属性是两条线的接缝** → 配色仍是静态资源，Compose 与 View 共用，切主题全应用一致。
3. **图标缓存（`IconCache`）是 Java** → Compose 侧经 `Drawable` → `ImageBitmap` 桥接复用，
   六条防掉帧条款（绑定期零回源 / 增量刷新 / 降采样 / 按包名 LruCache / 预载分级 / 启动流水线）**原样保留**。

### 分阶段（每阶段可独立验收，可随时停）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0** | 构建基线 + `:core:designsystem`（token + 组件外观） | ✅ 已通过 `compileDebugKotlin` / `assembleDebug` |
| **P1** ✅ | 配色生成器（命名主题 × 亮暗 × 4 对比度 → 静态资源）+ 主题切换（`setTheme` + 重建） | 已落地：24 套调色板 + 12 主题样式 + `ThemeCatalog`/`ThemePref`；构建通过 |
| **P2** ✅ | 外壳：`NavigationBar` 双 tab 迁 Compose + **Kotlin 不可变模型层** | 已落地：`MainActivity.kt` + `AppNavigationBar` + `ServiceUiModel`；构建通过 |
| **P3** 🔄 | 主页：四槽列表项 + 动态网格 + 搜索 + 图标异步 + 三态 | **UI 层已落**（`ServiceCard` + `HomeScreen`，构建通过、lint 0）；**状态持有者与 `HomeFragment` 接线待做** |
| **P4** | 详情弹卡：BottomSheet / SideSheet + chips + 周期对话框 | 共享元素转场（图标 → 详情头部） |
| **P5** | 设置页：分组 + 主题选择器 + 对比度 + 授权与帮助 | 五组齐全、48dp 触控、暗色完整 |
| **P6** | 收尾：删 View UI 残留（布局/适配器/`TouchDelegateLayout`/`GridSpacingDecoration`/`values` 手写色值）、移除 MDC | 代码里搜不到十六进制色值 / 裸 dp / `tween(` |
| **K1** ✅ | **`IconCache` 转 Kotlin**（229 行 → 行为不变转换） | 已落地：构建通过 + 机械扫描（`synchronized` 6 处锁身份逐一对应 / `@Volatile` 2 / `@JvmField` 6 / `@JvmStatic` 2 / `internal` 0）；**真机图标回归仍待做** |
| **K2+** ✅ | **业务层一次性转 Kotlin（用户裁决：不拆步）** —— `daemonService`/`RestartPrefs`/`RestartWorker`/`App`/`StartReceiver`/`PermissionHelper`/`IconCache` **全部完成** | 每文件构建通过 + 机械扫描核对（`RestartPrefs` 类级锁 11→11、`RestartWorker` 镜像点 6→6、`daemonService` 镜像点 10→10；协程/线程模型零改动）；**真机回归仍待做** |

**为什么 K1 要提前**：`IconCache` 是列表图标的**异步引擎**，P3 必须把它接进 Compose。
若先用「Java 回调 → Compose state」桥接、转换后再重做一次，等于**同一段接缝做两遍**。
其余 4 个文件排在 UI 定型之后 —— 那时它们的调用面才清楚。

### 5.1 业务层重构规程（**保留结构与经验，不保留代码文本**）

**方向裁决（用户裁决 2026-09-19）**：业务层**直接一次性推进到 Kotlin，不拆阶段**。
理由（用户）：总体体量不大（业务层 ~1080 行），拆分收益不抵步骤成本；
要保留的是**结构与经验**（不变量、教训），它们已文档化，不依赖 Java 文本存活。

**执行口径**：

- 兼容垫片（`@JvmField` / `@JvmStatic`）**暂留** —— 调用方仍有 Java UI 文件；
  UI 删完后可逐行清理，不值得为它再拆阶段。
- 线程模型、锁身份、异常范围**一律原样保留**（`Thread` + `Handler(Looper.getMainLooper())` 不换协程），
  属行为而非语言形态；要改另立任务。
- 处置方式仍是**逐行保真转换**（非规格先行重写）：这是在不写规格前提下唯一能保证
  「文档未覆盖的行为不丢」的做法。
- 转换中发现的怪癖**就地以注释显式标注**（当作轻量怪癖记录），供后续决定是否立项修改。

**重写（R）时必须守住的三条**（违反即回滚）：

1. **行为按规格实现，不夹带未裁决的行为变更** —— 任何"顺手改进"都必须先在规格里立项。
2. **三类静默语义必须显式表达**（编译器抓不到，只能用评审兜住）：

   | 构造 | 实测处数 | 坑 |
   |---|---|---|
   | `synchronized (X.class)` | **17** | 类级锁 vs 实例锁 vs `Mutex` 的覆盖范围不同；改身份 = 缺陷清单模式 6 |
   | `volatile` | **4** | 必须 `@Volatile`；漏了是可见性 bug，单机**大概率测不出**。全在 `tmpSettingValue` 跨 daemon/Worker/UI 镜像纪律上 |
   | `Worker.doWork()` | 1 | 跑在**后台线程**且内部是阻塞 Binder IPC；改 `CoroutineWorker` 会同时换掉线程模型与 1.5s 窗口 |

   另有 `getStringSet` 读改写 **5** 处、`try/catch` **20** 处（含权限撤销后的镜像回滚与 `markFailed` 可感知路径）——
   重写时可换成更清晰的形态，但**语义必须逐条对齐**。
3. **规格逐条对照 + 缺陷清单十类扫描 + 一次真机回归**；旧实现通过前不删。

**真机回归清单（R 阶段整体跑一次）**：灭屏触发 / 伪关闭恢复 / 权限撤销告警 / 多服务并发 / 通知未授权场景。

> **K1 的定位修正**：`IconCache.kt` 属业务层，会在 R 阶段按规格重写（Handler + 线程池 + 回调 → 协程/Flow）。
> K1 的产出不再是终态，**其价值是那份实测清单**（17/4/5/20 处与 `@JvmField`、`@JvmStatic`、`internal` 名字混淆三个坑）
> —— 正好作为 S 阶段规格的输入。**代码可能重做，经验留下。**

---

## 6. 验收标准（三条互相独立，任一条不过都能定位到哪层没做到）

| 检验 | 怎么验 | 通过标准 |
|---|---|---|
| **观感** | 截图 | **看不出苹果的痕迹。「像苹果」即失败** |
| **流畅度** | 上手操作 | 连续性 / 可中断性 / 节奏与位移成比例 / 动效非唯一信息通道 |
| **规范性** | 读代码 | 无裸数值 · 同一语义全局唯一取值 · 可机械检查 |

### 流畅度四条判据（可检验）

| # | 判据 | 检验方法 |
|---|---|---|
| L1 | **连续性** —— 从元素**当前值**出发，不从逻辑起点重放 | 动画进行到一半时触发反向，观察是否跳回起点 |
| L2 | **可中断性** —— 运动中可被抓取改向，速度不重置 | 手势中途反向，观察是否突变 |
| L3 | **节奏与位移成比例** | 对比同组件不同位移量的时长（短距 `fast*Spec` / 长距 `slow*Spec`） |
| L4 | **动效非唯一信息通道** | 关掉动画后功能仍可用 |

### 交付前自检清单（规范 §16）

- [ ] 手指能碰到的元素全部 ≥ 48dp，相邻 ≥ 8dp
- [ ] 一屏只有一个视觉主角
- [ ] 一屏字号层级 ≤ 3 档
- [ ] 每处颜色都来自语义角色（代码里搜不到十六进制）
- [ ] 列表项高度只有 56 / 72 / 88 三档
- [ ] 需要强调的地方用的是**强调变体**，不是手改字重
- [ ] **暗色模式**下完整看过一遍
- [ ] **系统字号放到最大**后不截断、不重叠
- [ ] **400dp 与 900dp 两个宽度**都看过
- [ ] 叠加层底下**确实有滚动内容**；无内容时走了降级路径
- [ ] 长列表滚动时**没有反馈动画**
- [ ] 空／加载／错误**三态都设计过**，不是占位

---

## 7. 风险与结构性不可得

### 必须承认的代价

- 现有 View 体系的 UI 工作（M3 主题、网格公式实现、四槽卡片 XML）**无法迁移**，只能按新骨架重写。
- 迁移期**两套体系共存**（Compose + 未迁移的 View 页面），故 MDC 依赖短期不能删。
- **中文排版**：`letterSpacing` 按规范用 `0`（M3 的 0.5sp 是为拉丁文调的）；
  行高沿用 MD3 原值（`bodyLarge` 16/24 = 1.5，满足中文 ≥1.5 的需要）。
- **字体**：规范指定静态 Roboto，SF Pro 授权禁止嵌入。当前 `res/font/` 无 Roboto 资产，
  `AppFontFamily` 暂指系统默认字体族 —— 补资产即可切换，不影响其余代码。

### 结构性不可得（**不要尝试**）

| 项目 | 挡它的东西 |
|---|---|
| **SF Pro** | Apple Font License 明文禁止嵌入非 Apple 应用 |
| **Liquid Glass** | iOS 系统级材质，Android 无等价物 |
| **Dynamic Type 自动缩放** | Apple OS 特性 |
| **系统色自动 vibrancy** | Apple OS 特性 |
| **边缘返回手势** | 双方皆由 OS 持有，**不属设计语言范畴** |

### 未核实项（勿当事实引用）

- 苹果逐样式的精确字距（HIG 只发布 size / leading / weight）
- 苹果弹簧的默认 stiffness / damping（`0.25s` 是 UIKit 约定，非 HIG token）

---

## 8. 下一步（按序）

1. **P2 外壳**：`MainShell` + `NavigationBar` + Compose 容器与两个 Fragment 的接驳；
   同时落 **Kotlin 不可变模型层**（`data class`）作为唯一进入组合树的类型（见 §5）。
2. **P3 主页**：四槽列表项 + 动态网格 + 图标异步桥接（复用 `IconCache`）+ 三态。
3. **K1 `IconCache` 转 Kotlin**（§5.1 规程）—— 必须在 P3 之前，避免同一条接缝做两遍。
4. **P4 详情弹卡** → **P5 设置页（含主题选择器 + 对比度四档）** → **P6 收尾**。
5. **S 业务层行为规格**（三来源 + 逐怪癖裁决）→ **R 按规格一次性重写**（§5.1）。
   前置：P6 删完 Java UI，确保重写只面向最终调用方。
