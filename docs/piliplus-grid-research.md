# PiliPlus 主页网格定位机制调研（适配参考来源）

> 调研对象：https://github.com/bggRGjQaUbCoE/PiliPlus（Flutter 编写的 B 站第三方客户端）
> 调研方式：源码只读分析（2026-09）｜用途：本项目平板网格列数公式的参考来源

## 一、核心结论

PiliPlus 主页推荐流的列数**没有任何横竖屏 / isTablet 分支判断**，由一条连续公式从容器实际宽度推导：

```
列数 = ceil((可用宽度 − 间距) / (单卡最大宽 + 间距))，下限 1
```

用户感知到的"横屏手机三格"是这条公式的自然结果，不是断点配置。

## 二、关键源文件

| 作用 | 路径（仓库内相对路径） |
|---|---|
| 主页推荐流页面（CustomScrollView + SliverGrid） | `lib/pages/rcmd/view.dart` |
| 列数计算核心（自定义 GridDelegate，含两个类） | `lib/utils/grid.dart` |
| 间距/圆角/纵横比常量 | `lib/common/style.dart` |
| 卡片宽度默认值（持久化读取） | `lib/utils/storage_pref.dart`（186–190 行） |
| 设置入口"列表宽度(dp)限制" | `lib/pages/setting/models/style_settings.dart`（127–133、660–686 行） |
| 卡片组件（圆角、封面比例） | `lib/common/widgets/video_card/video_card_v.dart` |
| 动态页瀑布流 delegate（同款公式） | `lib/utils/waterfall.dart` |

## 三、核心公式（grid.dart:98-104）

```dart
int crossAxisCount =
    ((constraints.crossAxisExtent - crossAxisSpacing) /
            (maxCrossAxisExtent + crossAxisSpacing))
        .ceil();
crossAxisCount = max(1, crossAxisCount);
```

即：`列数 = ceil((可用宽 − 8) / (240 + 8))`，下限 1。**没有** isLandscape/isTablet/Orientation 判断参与（`device_utils.dart` 的 `isTablet = shortestSide >= 600` 仅用于播放器全屏默认值，不参与网格）。

### delegate 构造（rcmd/view.dart:54-60）

```dart
late final gridDelegate = SliverGridDelegateWithExtentAndRatio(
  mainAxisSpacing: Style.cardSpace,        // 8
  crossAxisSpacing: Style.cardSpace,       // 8
  maxCrossAxisExtent: Pref.recommendCardWidth, // 默认 240
  childAspectRatio: Style.aspectRatio,     // 16/10
  mainAxisExtent: MediaQuery.textScalerOf(context).scale(90),
);
```

## 四、断点 → 列数映射表（默认 240dp 卡宽；可用宽 = 屏逻辑宽 − 24dp 页边距）

| 屏幕逻辑宽度 (dp) | 可用宽度 | 列数 | 典型设备 |
|---|---|---|---|
| ≤ 280 | ≤ 256 | 1 | 极窄窗口 |
| 281 ~ 528 | 257~504 | **2** | 竖屏手机（360/393/412） |
| 529 ~ 776 | 505~752 | **3** | **横屏手机（640/720）← 用户反馈的"三格"场景** |
| 777 ~ 1024 | 753~1000 | 4 | 横屏手机（800）/平板 |
| 1025 ~ 1272 | 1001~1248 | 5 | 平板横屏（1024） |
| 1273 ~ 1520 | 1249~1496 | 6 | 桌面端（1280） |

规律：**可用宽度每增加 248dp（= 240 卡宽 + 8 间距）加一列**；每列实际宽度始终 ≤ 240dp，通过均分剩余宽度补齐。

## 五、布局参数汇总

| 参数 | 值 | 出处 |
|---|---|---|
| 卡片间距（main/crossAxisSpacing） | 8dp | style.dart:5 |
| 页面左右 margin | 12dp | style.dart:6 |
| 封面纵横比 | 16/10 = 1.6 | style.dart:9 |
| 信息区高度 | 封面外追加 90dp（随字体缩放） | rcmd/view.dart:59 |
| 卡片整卡圆角 / 封面顶部圆角 | 12dp / 顶部 12dp | video_card_v.dart:97,114 |

## 六、可调列数设置（用户侧）

设置项为「列表最大列宽度(默认240dp)」——不直接调列数，而是调"单列最大宽度"（150–500dp，步进 10），宽度越小列数越多。修改后重启生效（style_settings.dart:660-686）。

## 七、移植到 Android RecyclerView 的映射

```
crossSpacing = 8dp
maxCardWidth = 设置项 150~500dp
calcSpan(availableWidthPx) = max(1, ceil((availableWidthPx - crossSpacing) / (maxCardWidth + crossSpacing)))
```

1. 公式入参必须是 **RecyclerView 内容区宽度**（屏宽 − 左右页边距），不是屏宽本身
2. 横竖屏/分屏/折叠展开时在 `onSizeChanged`/`onConfigurationChanged` 重算 `layoutManager.spanCount`
3. 保留"先减 spacing 再除"的原式细节（宽度恰好整除时列数可能差 1）
4. 若做设置项：150–500dp 步进 10 的 SeekBar，值持久化后重建列表生效

