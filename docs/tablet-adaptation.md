# 平板适配方案（本项目现行做法）

> 适用仓库：AccessibilityManager（MD3 重构后）
> 来源：方案 v3.1（Q2 决策）+ r7-r10 轮实现与审核 ｜ 参考上游：见 `docs/piliplus-grid-research.md`

## 一、核心机制：宽度驱动的动态列数（无设备类型判断）

```
spanCount = ceil((RecyclerView内容宽px − 8dppx) / (500dp×density + 8dppx))，下限 1
```

- 与 PiliPlus 同构，但**单卡最大宽为 500dp**（PiliPlus 为 240dp 竖版视频卡；本项目是横版设置行卡，500dp 才舒适）
- 400dp 阈值的历史教训：用户设备（1440px/密度 547）竖屏等效 421dp，以 1dp 之差误入 2 列 → 上调至 500dp 后全密度有安全余量

### 列数对照表（本参数下）

| 设备姿态 | 内容宽 | 列数 |
|---|---|---|
| 手机竖屏（≈360-430dp） | — | **1 列** |
| 手机横屏（≈700-850dp） | — | **2 列** |
| 16:10 平板竖屏（≈800dp） | — | **2 列** |
| 平板横屏（≈1200dp） | — | **3 列** |
| 更宽 | — | 4+ 自然增长 |

## 二、实现位置（基线 71d6baa 之后）

| 机制 | 位置 |
|---|---|
| 公式与重算 | HomeFragment onCreateView（首帧预算）+ addOnLayoutChangeListener |
| 首帧修正 | onCreateView 内用 configuration.screenWidthDp 预算 initialSpan，消除首帧单列闪跳 |
| 重算安全 | layoutManager.setSpanCount(span) + findFirstVisibleItemPosition 恢复滚动 |
| 600dp 形态阈值 | lastWide 独立跟踪：跨阈值时详情弹卡 dismiss 后按新宽度以新形态重开 |
| 网格间距 | GridSpacingDecoration 8dp；卡片圆角 12dp |

## 三、详情弹卡形态

- 小于 600dp：BottomSheetDialog（底部滑入）
- 不小于 600dp：SideSheetDialog（右缘滑入），宽度 500dp（上限屏宽 60%，onShow 时改 sheet LayoutParams）
- 跨阈值旋转：dismiss 后按新宽度以新形态重开（不跨态迁移）

## 四、insets（挖孔/手势导航）

- 主窗口未开启 edge-to-edge（decorFits 默认，targetSdk 33 无强制）→ 主体内容自然避让状态栏
- Sheet（Material 自窗口 edge-to-edge）：内容根挂 OnApplyWindowInsetsListener 消费 navigationBars 叠加 padding（applySheetInsets）
- 历史教训：曾对主页开 edge-to-edge 导致设置页与挖孔避让不一致，已回退（见 git log）

## 五、顶栏（左横幅右搜索）

- 左：应用名横幅字（常显）+ 告警横幅列（未授权/恢复失败，有则叠加）
- 右：搜索栏（TextInputLayout OutlinedBox），手机 180dp / 平板（values-sw600dp）360dp 限宽
- 搜索：输入即过滤 display（保序，置顶顺序保留），匹配 id+缓存标签

## 六、验证基准（平板）

- 竖屏 2 列 / 横屏 3 列切换，滚动位置保持
- Sheet 跨 600dp 形态跟随（dismiss 重开）
- 旋转后无首帧单列闪跳
- Side Sheet 宽度舒适（500dp）
