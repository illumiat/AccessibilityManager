# 修复任务书 r9（fixer5 执行）

你是 Android 修复工程师。项目：本仓库。git 基线 4391a75 已锁定：禁止 git commit/push，禁止 gradlew 构建。**白名单：HomeFragment.java、ServiceAdapter.java、RestartPrefs.java、RestartWorker.java、daemonService.java、SettingsFragment.java——白名单外一律禁改。**

## 设计意图（冲突即停手上报）

- tmpSettingValue 为 public static volatile 镜像，三方（daemon/Worker/UI）写 Settings.Secure 前后必须同步
- 置顶移动必须"数据与通知序列一致"：adapter 内部列表先移动，再 notifyItemMoved
- "恢复失败"终态优先于"重启中"过渡态
- 周期预填用 peekLastPeriod（未启用态读最近设定周期），禁用 DEFAULT 兜底覆盖用户自定义周期
- 服务 id 两形态精确匹配（RestartPrefs.isEnabledIn），禁止 contains 子串判断

## 逐项指令

### P1（SEVERE，双轴交叉）置顶路径 adapter 数据未同步

位置：HomeFragment.onItemLongClick。

问题：仅对 fragment 的 display 列表执行 sortDisplay() 后发 notifyItemMoved(from,to)；ServiceAdapter 自上轮 S1 起持有 display 的**副本**，adapter.items 顺序未随移动同步 → stableIds 通知序列与数据错位，置顶后重复行/丢行，可触发 Inconsistency 崩溃。

修法：
1. ServiceAdapter 新增 `public void moveItem(int from, int to)`：内部 items 同步 `Collections.swap` 或 add/remove 移动（与 notifyItemMoved 语义一致），**不加任何 notify**（通知由调用方发）
2. onItemLongClick 流程改为：sortDisplay(display) 重排 → 计算 from/to（移动前后位置）→ adapter.moveItem(from,to) → notifyItemMoved(from,to) → 既有 payload 重绑

### P2（MAJOR）周期对话框静默启用 + 预填错误 + 开关不回正

位置：HomeFragment.showPeriodDialog（约 884-900 行）。

问题：cfg==null（未启用）时确认"修改周期"走 else 分支 RestartPrefs.enable() 静默启用并调度 Worker；对话框预填用 DEFAULT_PERIOD_MIN 而非 peekLastPeriod；确认后 updateRestartViews 不回正开关 → 服务被周期重启而用户不知情。

修法：
1. 对话框预填改 `RestartPrefs.peekLastPeriod(context, serviceId)`
2. 确认路径：cfg==null 时先 enable 再 set period（现状保留），但**确认后必须刷新详情卡开关真实状态**（updateRestartViews 或等价路径让 sw_restart 反映 enabled=true），并在对话框文案或 Toast 明示"已开启定期重启"
3. 单位换算保持 30min~30 天正整数校验不变

### P3（MAJOR）removeCompletely 清错键，失败集永不收缩

位置：RestartPrefs.removeCompletely（约 104 行）`e.remove(serviceId + ".failed")`。

问题：markFailed 实际把失败集存于 "failed" StringSet（M3 持锁实现），该行清的是从未写入的死键 → 卸载服务后失败集残留，banner_failed 与设置页告警常驻误报。

修法：改为从 "failed" StringSet 中移除 serviceId（持锁，与 markFailed 同一把锁同一次 apply 内完成）。

### P4（MAJOR）HomeFragment.onChange 自检基准失真

位置：HomeFragment.onChange（约 113-128 行）。

问题：外部把设置串改回"本 APP 上次写入值"（s == tmpSettingValue）时直接 return，列表开关态不刷新直至 onResume——自检基准应随外部变化校准。

修法：s == tmpSettingValue 分支内**先刷新可见行开关态显示**（只读 UI 刷新，不写 Settings.Secure），再 return；避免死循环的方式是只调 adapter.refreshStates 式的显示刷新而不触发写路径。

### P5（MISSING）详情卡"重启中…"未禁用 sw_restart

位置：HomeFragment 的 updateRestartViews / refreshDetailRestartArea / bindDetail。

问题：方案【盲审修订 P0】要求"卡片与详情显示'重启中…'过渡态，Switch 禁交互"；列表卡已禁用，详情卡 sw_restart 全路径未禁用。

修法：pendingEnable（经 RestartPrefs 查询）非空且包含当前详情 serviceId 时，详情卡 sw_restart.setEnabled(false)（其余状态恢复 true）；refreshDetailRestartArea 与 bindDetail 两路径都覆盖。

### P6 MINOR 逐项

P6-a HomeFragment.onToggle 的 catch (SecurityException) 扩为 catch (Exception)，失败时回滚镜像并提示（与 daemon 侧口径统一）。
P6-b daemonService.doDaemon putString 异常路径：tmpSettingValue 回滚为读取到的实际值（与 tryEnable 的 R3 回滚对称）。
P6-c RestartWorker.tryEnable 与 compensatePending 写设置后同步 daemonService.tmpSettingValue（与 F1 主路径口径统一，消除 daemon 多跑一轮无谓 doDaemon）。
P6-d RestartPrefs 新增 `private static final Pattern COLON = Pattern.compile(":")` 类常量；countDaemonServices/daemonService.doDaemon 等处 `Pattern.compile(":")` 改用常量。
P6-e usageStatsGranted 双实现收口：SettingsFragment 与 RestartWorker 统一调用 RestartPrefs 或新工具类的单一实现。

## 豁免不改（既定裁决）

- Worker enabled 快照 TOCTOU 残余（P1 修复已覆盖主要面）
- Shizuku 监听器常驻、通知文案 miss 回退、TouchDelegateLayout Rect 复用、每行 SP 读
- 设置页分组顺序、描述行末级、优先级队列暂停+补载、补 enable 走常驻通知文案

## 完成标志

向 main 发送：逐项确认清单（P1-P6）+ 修改文件列表 + git diff --stat（唯一允许 git 只读命令）+ 负面清单（含实现偏离说明，如有）。
