# 修复任务书

你是 Android 修复工程师。项目：本仓库。git 基线 874c395 已锁定：禁止 git commit/push，禁止 gradlew 构建。**白名单：HomeFragment.java、daemonService.java、ServiceAdapter.java、RestartPrefs.java、RestartWorker.java、SettingsFragment.java、res/values/strings.xml、res/layout/sheet_service_detail.xml、AndroidManifest.xml——白名单外一律禁改。**

## 设计意图

- tmpSettingValue 为 public static volatile 镜像 + Worker 重启窗口协调；daemon 补偿路径写前必须同步镜像
- 焦点判定：目标应用前台绝不闪断；pendingEnable 是崩溃补偿；enable 失败必须 markFailed 可感知
- "恢复失败"是终态警示，优先级高于"重启中"过渡态
- 服务 id 两形态："pkg/.Cls" 与 "pkg/pkg.Cls"，精确匹配需兼容
- appops 字符串 op、TouchDelegate 视觉 40dp、drawable-v21/23/24 保留、targetSdk 33、24h 默认周期：均为既定设计

## 逐项指令

### R1 详情卡开关闭包捕获旧 cfg

位置：HomeFragment.bindDetail 中 swRestart 的 OnCheckedChangeListener，闭包捕获了 bindDetail 时读取的 cfg 旧值。

问题：用户在详情卡"修改周期→关闭重启→再开启"后，enable 以旧 cfg.periodMin 落盘，新周期被静默回退，必现。

修法：开关监听器内**实时**调用 `RestartPrefs.get(context, serviceId)` 取当前 cfg（含刚修改的 periodMin），据其 enabled 状态写回；禁止使用闭包捕获的 cfg 旧引用。

### R2 doDaemon substring 无防御

位置：daemonService.doDaemon 中 `serviceName.substring(0, serviceName.indexOf("/"))`。

问题：daemon 串存在不含 "/" 的段（历史或异常持久化数据）时 indexOf 返回 -1，触发 StringIndexOutOfBoundsException 崩溃前台服务。

修法：indexOf("/")==-1 或段为空的条目直接跳过（continue），并防御性清理该段（可复用 pruneDaemon）。

### R3 daemon 补偿与 Worker 重启窗口竞态

位置：daemonService.compensatePendingEnables 的 tryEnable 调用。

问题：daemon 补偿 tryEnable 前未同步静态镜像即按 "id:cur" 前插写回；若补偿恰在 Worker 重启窗口（disable 后 enable 前）运行，会把 disabled 中的服务提前 enable，导致 Worker 复检误 markFailed，重启延后一周期。

修法：daemon compensatePendingEnables 的 tryEnable 写入**前**同步 `daemonService.tmpSettingValue = 写入后值`（与 doDaemon、Worker 的既有模式一致）。

### R4 onDestroy observer 泄漏

位置：HomeFragment.onDestroy 中注销 ContentObserver 依赖 `getContext() != null`。

问题：极端时序 getContext() 为 null 时 observer 不注销，ContentObserver 持 Fragment/Activity 引用泄漏。

修法：注销改用非空安全的 registration 引用，或用 applicationContext 注册的 observer 自身引用直接 unregister——ContentObserver 对象持有即可注销，不依赖 context，确保任何路径都注销。

### R5 失败终态优先于过渡态

位置：ServiceAdapter.updateDesc 与 HomeFragment 详情刷新。

问题：pendingEnable 与 failed 同时存在时，"重启中…"过渡态覆盖"恢复失败"终态警示。

修法：优先级改为 failed > pendingEnable > 定期重启摘要 > 服务描述；详情卡周期行同步：failed 时显示"恢复失败"而非"重启中…"。

### R6 周期对话框 hint 对齐

位置：res/values/strings.xml 的 period_dialog_helper 及相关 hint 资源、sheet 周期对话框布局。

修法：hint 注明"实际间隔不早于设定值（仅在该服务未被使用时执行）"，与设置页策略行语义完全一致；helper 行补充括注"服务使用中自动顺延"。

### MINOR 逐项

M-a RestartPrefs.removeCompletely 补清 last_auto_restored.<id> 时间戳键，防止重装同名服务后 5 分钟内误显"已自动恢复"角标。
M-b RestartWorker.writeSettingValue 的 catch (SecurityException) 扩为 catch (Exception)；其他运行时异常不中断本轮，由调用方落 markFailed。
M-c daemon compensatePendingEnables 补 enable 成功后写 AUTO_RESTORED 时间戳，与 Worker compensatePending 行为一致。
M-d daemonService.onCreate 与 SettingsFragment.refreshAuthStatus 的 NotificationManager 判 null。
M-e HomeFragment.onToggle 的 SecurityException 分支：tmpSettingValue 回滚为读取到的实际旧值，防镜像失真依赖自愈。

## 以下项勿动

- Shizuku 监听器常驻至 onDestroy，无功能损害
- doDaemon 常驻通知文案在缓存 miss 时首轮回退包名，下次事件自愈
- TouchDelegateLayout 的 Rect 复用与每行 SP 读，留待后续版本
- Worker enabled 快照的 TOCTOU 残余风险已记录在案

## 完成标志

向 main 发送：逐项确认清单（R1-R6 与 MINOR 各项）+ 修改文件列表 + git diff --stat 输出（唯一允许的 git 只读命令）+ 负面清单。
