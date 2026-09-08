# 修复任务书 r7（fixer3 执行）

你是 Android 修复工程师。项目：本仓库（c:\Users\illumiat\CodeBuddy\20260906084704\AccessibilityManager）。git 基线 1a77a0e 已锁定：禁止 git commit/push，禁止 gradlew 构建。**白名单：RestartWorker.java、RestartPrefs.java、daemonService.java、HomeFragment.java、ServiceAdapter.java、res/values/strings.xml——白名单外一律禁改。**

## 设计意图（冲突即停手上报，勿自行变通）

- tmpSettingValue：daemon/UI 写 Settings.Secure 前后区分自己写/外部改，防 ContentObserver 自触发循环
- 焦点判定：目标应用前台绝不闪断
- pendingEnable 崩溃补偿；enable 失败必须 markFailed 可感知
- daemon 抢跑对"未锁定+未开定期重启"服务是预期边界；对"锁定+双配置"服务由本任务 F1 协调
- 服务 id 两形态：注册串 "pkg/.Cls" 与展开串 "pkg/pkg.Cls"，精确匹配需同时兼容

## 逐项指令

### F1（核心）Worker 重启与 daemon tmpSettingValue 协调

问题：daemonService.tmpSettingValue 是实例字段。RestartWorker.executeDue 写 disable/enable 不更新它 → 锁定+双配置服务 disable 后，daemon 观察者 1.5s 内按"外部关闭"秒级回写，tryEnable 幂等命中 → 真实重启从未发生，却通知"已重启"并推进 lastRestart。

修法：
1. daemonService.tmpSettingValue 改为 `public static volatile String`（daemon 为单实例服务；类内所有引用点同步改）
2. RestartWorker.executeDue 重启序列协调：写 disable 前设 `daemonService.tmpSettingValue = disabled 值`；enable 成功（读回确认）后设 `daemonService.tmpSettingValue = 启用后值`。Worker 与 daemon 同进程，直接静态访问
3. 效果：重启窗口内 daemon 把变化视为自己写的而跳过回写，1.5s 干净重启真实发生

### F2（SEVERE）pruneDaemon 误清保活锁（采纳修法 b）

问题：RestartWorker.executeDue 成功路径无条件 pruneDaemon → 锁定服务首次周期重启后保活锁被清，daemon 不再恢复，UI 镜像仍显示锁定。

修法：
1. executeDue 成功路径删除 pruneDaemon 调用
2. RestartPrefs.removeCompletely 内持锁补 pruneDaemon（卸载分支承担清理语义）

### F3（MAJOR）sortDisplay 比较器契约违反

问题：HomeFragment.sortDisplay 用 top.indexOf(id) 子串定位，互为前缀 id 双向 compare 同返回 1，TimSort 可抛契约异常。

修法：改用段级顺序：top 按 ":" split 得 List（与 fillIds 同源），比较器用该 List 的 indexOf 精确定位（-1 视为未置顶排后），保证 compare(a,b)/compare(b,a) 一致。

### F4（MAJOR）disable 未生效静默推进

问题：RestartWorker 的 writeSettingValue 仅捕 SecurityException 不校验生效；未生效时 enable 幂等命中 → 误报"已重启"并推进 lastRestart。

修法：写 disable 后重读确认 id 已移除；未生效 → RestartPrefs.markFailed(ctx,id) 并 continue（不 markRestarted、不发通知）。

### F5（MAJOR）doDaemon 恢复失败静默

位置：daemonService.doDaemon putString catch 块。

问题：恢复失败仅 return，不 markFailed——daemon-only 服务恢复失败永久不可感知，违反方案 §四 三重可感知硬约束。

修法：catch 块内对本轮 restored 列表逐个 RestartPrefs.markFailed 后再 return。

### F6（DIVERGENT，1 行）文案统一

位置：res/values/strings.xml 的 period_dialog_helper。

修法：文案统一为"仅在该服务未被使用（应用不在前台）时执行；实际间隔不早于设定值"。

## 豁免不改（已裁决记录）

- 描述行末级用服务描述而非包名（信息量更优）
- 设置页分组顺序：保活/定期重启/授权与帮助/外观/版本
- 优先级队列仅实现暂停+补载（无预加载请求，无实际缺口）
- 补 enable 可感知走常驻通知文案（已达成）

## 完成标志

向 main 发送：逐项确认清单（F1-F6）+ 修改文件列表 + `git diff --stat` 摘要（唯一允许的 git 只读命令）+ 负面清单（未修项/风险）。
