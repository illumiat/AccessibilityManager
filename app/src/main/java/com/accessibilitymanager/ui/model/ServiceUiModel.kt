package com.accessibilitymanager.ui.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 列表行的**描述行语义类别**。
 *
 * 描述行的内容优先级（沿用既有实现）：`FAILED`（终态警示）> `RESTARTING`（过渡态）
 * > `RESTART_SCHEDULE`（定期重启摘要）> `NORMAL`（服务自身描述）。
 *
 * 它是**独立的语义字段**而非仅靠颜色区分 —— 规范禁止「用颜色表达状态、不给文字」
 * （色盲用户拿不到信息，且违反「动效/颜色非唯一信息通道」）。
 */
enum class ServiceDescKind {
    /** 终态警示：自动补启用失败，需用户可感知。 */
    FAILED,

    /** 过渡态：重启补偿进行中。 */
    RESTARTING,

    /** 定期重启摘要（如「每 24 小时」）。 */
    RESTART_SCHEDULE,

    /** 常规描述（无障碍服务的描述文本）。 */
    NORMAL,
}

/**
 * 列表行的 **Kotlin 不可变模型** —— Java 业务层与 Compose 之间的唯一边界。
 *
 * ## 为什么必须有这一层（关键约束）
 *
 * 业务层保持 Java（`AccessibilityServiceInfo`、`IconCache.Entry` 等）。
 * 但 **Java 类型绝不能进入组合树**：
 * - Java POJO 没有 `equals`/不可变语义 → Compose 判定为 **unstable** →
 *   跳过重组（skipping）失效 → 每次状态变化全量重组 → **掉帧**；
 * - Java 方法返回值在 Kotlin 侧是**平台类型**（`String!`），可空性未知。
 *
 * 故这里用 `data class` 收敛成不可变值对象，并标注 `@Immutable`：
 * 向编译器承诺「实例不可变、比较按值」，使 `LazyColumn`/`LazyVerticalGrid`
 * 能真正跳过未变化行的重组。
 *
 * ## 字段来源
 *
 * | 字段 | 来源 |
 * |---|---|
 * | [serviceId] / [title] / [descriptionText] / [icon] | `AccessibilityServiceInfo` + `IconCache.Entry` |
 * | [enabled] | `RestartPrefs.isEnabledIn(settingValue, id)`（精确匹配，禁子串判断） |
 * | [locked] / [pinned] | daemon/top 内存镜像（`isLocked` / `isTop`） |
 * | [restarting] / [failed] | `pendingEnable` / `failed` 集合 |
 * | [autoRestored] | `restart` SP 的 `AUTO_RESTORED_PREFIX + id`（窗口内视为已自动恢复） |
 *
 * @param icon `null` = 图标尚未加载完成 → UI 显示占位（灰底首字），**不得阻塞绑定**
 * @param iconInitial 占位符首字（图标未就绪时显示）
 * @param toggleEnabled 开关是否可交互（未授权时为 false；[restarting] 期间为 false）
 */
@Immutable
data class ServiceUiModel(
    val serviceId: String,
    val title: String,
    val descriptionText: String,
    val descriptionKind: ServiceDescKind,
    val icon: ImageBitmap?,
    val iconInitial: String,
    val enabled: Boolean,
    val toggleEnabled: Boolean,
    val permissionGranted: Boolean,
    val pinned: Boolean,
    val locked: Boolean,
    val restarting: Boolean,
    val failed: Boolean,
    val autoRestored: Boolean,
) {
    /** 是否显示警示角标（终态失败或窗口内已自动恢复）。 */
    val showsWarning: Boolean get() = failed || autoRestored

    /** 警示角标文案 —— 由 UI 层映射为字符串资源（模型不持有文案）。 */
    val warning: WarningKind?
        get() = when {
            failed -> WarningKind.FAILED
            autoRestored -> WarningKind.AUTO_RESTORED
            else -> null
        }
}

/**
 * 警示角标类别。
 *
 * ## `FAILED` 优先于 `AUTO_RESTORED` —— 这是**对迁移前行为的刻意更正**，不是保真转换
 *
 * 迁移前 `ServiceAdapter` 的角标判定是（原文）：
 *
 * ```java
 * if (restoredAt 在 5 分钟窗口内) { setText(已自动恢复); setVisible(VISIBLE); }
 * else { setText(恢复失败); setVisible(failed.contains(id)); }
 * ```
 *
 * 即**「已自动恢复」压倒「恢复失败」**。但同一个 Adapter 的描述行（`updateDesc`）却是
 * `failed` 优先（`恢复失败`）—— 于是当两个标志同时为真时，会出现
 * **描述行红色写「恢复失败」、角标却写「已自动恢复」的自相矛盾**。
 *
 * 两者同时为真确有路径：`daemonService.compensatePendingEnables` 补 enable 成功后写
 * 自动恢复时间戳，之后再次失败则写入 `failed`；而时间戳窗口是 **5 分钟** ——
 * 在这 5 分钟内该服务会同时命中两个标志。
 *
 * 故本次改为 `FAILED` 优先，使角标与描述行口径一致，且显示的是**可行动**的那条信息
 * （「已自动恢复」此刻已经是过时消息）。
 *
 * ⚠️ **不要**把这里写成「沿用既有 R5 裁决」—— R5 裁决的是
 * `failed` 与 `pendingEnable`（重启中）的优先级，**从未裁决过 `autoRestored` 的位置**。
 * （此处曾有一版错误注释如此声称，已由独立审计指出。）
 */
enum class WarningKind {
    FAILED,
    AUTO_RESTORED,
}
