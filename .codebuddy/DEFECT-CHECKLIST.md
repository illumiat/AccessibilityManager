# 缺陷模式清单（评审员扫描用）

> 历次审核发现的问题模式沉淀。每轮评审按此单逐文件扫描，发现即报；清单外新问题正常上报。

## 模式 1：子串误匹配
- 服务 id 互为前缀（"pkg/.Svc" ⊂ "pkg/.SvcX"）时，`String.contains(id)` / `indexOf(id)` 全部误判。
- 扫描点：所有对 daemon 串、top 串、settingValue 的 contains/indexOf/replace/substring 调用。
- 正确做法：按 ":" 切段精确相等（兼容展开形态 "pkg/pkg.Cls"），或段级 List 保序定位。

## 模式 2：镜像同步缺失
- tmpSettingValue（static volatile）：任何新增的 Settings.Secure 写路径，写前必须设镜像为预期值、写后以读回实际值校准（失败回滚为实际值）。
- daemonSet/topSet 内存镜像：任何 daemon/top 串修改点，镜像必须同步。
- daemonService 与 RestartWorker 双写方：一方加同步，另一方同款路径必须同款处理（历史漏点：compensatePending、tryEnable）。

## 模式 3：闭包捕获旧值
- 监听器/回调闭包捕获"绑定时刻"的配置对象或位置 int；重绑/重排后使用旧值。
- 扫描点：所有 OnCheckedChangeListener、TextWatcher、onClick 内使用的非参数局部 cfg/pos。

## 模式 4：集合/通知序列错位
- adapter 持副本（S1）后：任何数据变动必须先同步 adapter.items 再 notify，顺序不得颠倒。
- notifyItemMoved 后对旧 from 发 notifyItemChanged 会重绑错误行。
- stableIds 开启下，notify 序列与 getItemId 不一致会 Inconsistency 崩溃。

## 模式 5：单槽/死键清理
- per-service 状态存取必须集合化（单槽会丢并发项）。
- 清理函数必须覆盖全部相关键（enabled/period/last/failed/pending/pending.ts/daemon/top/auto_restored）——逐键核对，防"清 A 漏 B"。

## 模式 6：锁覆盖不完整
- 同一 SP 键的读改写若多方（daemon/Worker/UI 线程）可达，全部读改写点必须持同一把锁。
- 扫描点：getSharedPreferences("restart"/"data") 的 StringSet/String read-modify-write。

## 模式 7：主线程阻塞与异常
- p.waitFor()/Binder 重 IPC 不得在主线程（历史：Shizuku grant）。
- Settings.Secure 读写必须 try-catch（权限可能被撤销）；失败路径必须可感知（markFailed/告警），禁止静默 return。
- catch 后镜像必须回滚为实际值。

## 模式 8：错包/错类型引用
- AccessibilityServiceInfo 在 android.accessibilityservice 包（勿写 view.accessibility）。
- MaterialAlertDialogBuilder.create() 返回 androidx AlertDialog（勿赋 android.app.AlertDialog）。
- colorError attr 在 androidx.appcompat.R.attr（material.R 无此符号）。
- 原生 EditText/Spinner 呈直角风格：用户可见控件用 Material 等价物。

## 模式 9：insets 与挖孔
- edge-to-edge 开启后，每个独立窗口/根容器需消费对应 insets（AppBar→statusBars+cutout、BottomNav→navigationBars、Sheet 内容→navigationBars）。
- 只给部分 Fragment 避让会造成"有的页面正常有的上钻"的不一致。

## 模式 10：空防御与口径
- NotificationManager 等系统服务判 null（历史三处不一致）。
- Handler 用 Looper.getMainLooper()。
- 同功能双实现必须收口单点（历史：removeService 双实现分叉、usageStatsGranted 双实现）。
