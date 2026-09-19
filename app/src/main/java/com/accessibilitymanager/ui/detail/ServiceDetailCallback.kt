package com.accessibilitymanager.ui.detail

/**
 * 详情弹卡的宿主回调（由 `HomeFragment` 实现）。
 *
 * ## 边界在哪
 *
 * 与 `HomeServiceCallback` 同一条纪律：**Compose 只管发意图，Java 宿主负责落笔**。
 * 本弹卡涉及三处写路径，全部留在宿主：
 *
 * | 意图 | 宿主的落笔 |
 * |---|---|
 * | [onRestartToggle] | `RestartPrefs.enable/disable` + `RestartWorker.schedule` |
 * | [onPeriodConfirmed] | `RestartPrefs.setPeriod` 或 `enable` + 调度 |
 * | [onOpenSystemSettings] | 外部 Intent |
 *
 * ## 为什么没有参数
 *
 * 回调由宿主**按弹卡实例**构造，捕获该实例的 `serviceId` / `serviceInfo`。
 * 若改成传参，则宿主需要按 id 反查服务对象 —— 多一条可能查不到的路径，
 * 而查不到时的行为没有既定语义。捕获的这两个值在单个弹卡生命周期内**恒定**，
 * 不构成"闭包捕获旧值"（该模式说的是捕获会变的配置对象/位置）。
 */
interface ServiceDetailCallback {

    /** 定期重启开关切换。 */
    fun onRestartToggle(checked: Boolean)

    /** "修改周期"确认，参数为换算后的**分钟数**（已通过范围校验）。 */
    fun onPeriodConfirmed(periodMin: Long)

    /** "打开系统设置"：优先跳该服务自己的设置页，失败回落系统无障碍设置。 */
    fun onOpenSystemSettings()
}
