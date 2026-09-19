package com.accessibilitymanager.ui.detail

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 详情弹卡头部的**不可变模型** —— 应用身份（这一屏的主角）。
 *
 * ## 为什么卡片内容也要过模型层
 *
 * 与列表同因（见 `ui/model/ServiceUiModel.kt`）：业务层是 Java/Kotlin 混合，
 * **Java 类型绝不能进入组合树** —— Java POJO 没有 `equals`/不可变语义，
 * Compose 会判定为 unstable 并放弃跳过重组。
 *
 * @param packageClass 服务 id 原文（包名/类名），直接展示给用户
 * @param icon `null` = 图标尚未加载完成 → 显示 [iconInitial] 占位
 */
@Immutable
data class DetailHeader(
    val title: String,
    val packageClass: String,
    val icon: ImageBitmap?,
    val iconInitial: String,
)

/**
 * 详情弹卡的**只读信息块**：基本信息行 + 两组位解码标签。
 *
 * ## 文案为什么由宿主格式化后传入，而不是在这里拼
 *
 * `%1$s` 这类格式串是**与资源 id 绑定的业务文案**，且同一份文案（周期、相对时间）
 * 也被列表卡片复用。若在展示层再拼一遍，就出现两处独立的格式化实现 ——
 * 缺陷模式「同功能双实现必须收口单点」。故本模型只承载**已定稿的字符串**。
 *
 * `capabilityChips` / `eventChips` 为空时，UI 侧渲染 [com.accessibilitymanager.R.string.chip_none]。
 */
@Immutable
data class DetailInfo(
    val basicLines: List<String>,
    val capabilityChips: List<String>,
    val eventChips: List<String>,
)

/**
 * 定期重启区域的**不可变模型**。
 *
 * ## 为什么把"为什么"也放进模型
 *
 * 旧 View 实现里，同一块区域要同时表达：开关态、开关是否可点、周期文案（或过渡态文案）、
 * 上次执行文案。它们**互相不一致就会出现自相矛盾的界面**（例如"重启中…"却还能拨开关）。
 * 收成一个不可变值后，一次提交即一个自洽快照。
 *
 * **状态优先级（沿用既有裁决 R5）**：`failed`（终态警示）> `restarting`（过渡态）> 周期行。
 * 该优先级已由宿主机算进 [periodText]，模型只保留 [failed] 供 UI 上错误色。
 *
 * @param enabled 开关位置（= 该服务存在启用的重启配置）
 * @param toggleEnabled 开关是否可交互（重启补偿进行中为 false）
 * @param periodText 周期行文案；失败/重启中时为对应的终态/过渡态文案
 * @param lastText 上次执行行文案
 * @param lastPeriodMin 最近一次设定的周期（分钟），供"修改周期"对话框预填。
 *        未启用态也**必须**是该服务上一次的设定值，不能回落默认值 —— 否则
 *        「改周期 → 关 → 再开」会把用户自定义周期静默回退成默认周期（既有 R1 裁决）。
 */
@Immutable
data class DetailRestart(
    val enabled: Boolean,
    val toggleEnabled: Boolean,
    val periodText: String,
    val lastText: String,
    val failed: Boolean,
    val lastPeriodMin: Long,
)
