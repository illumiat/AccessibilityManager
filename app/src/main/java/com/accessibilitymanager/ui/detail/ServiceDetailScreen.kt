package com.accessibilitymanager.ui.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.accessibilitymanager.R
import com.accessibilitymanager.core.designsystem.component.M3AppIcon
import com.accessibilitymanager.core.designsystem.component.M3TagChip
import com.accessibilitymanager.core.designsystem.theme.ShapeTokens
import com.accessibilitymanager.core.designsystem.theme.SizeTokens
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens

/**
 * 服务详情（弹卡内容）。
 *
 * ## 一屏一个主角（规范 §1）
 *
 * 动词 = **确认**；主角 = **应用身份**（图标 + 应用名 + 包名/类名）。
 * 最大图标（48dp）、最大字号（`titleLarge`）都落在头部，其余全部降级。
 *
 * ## 字号层级 = 3 档（规范 §1 硬约束，**按 sp 值可机械验证**）
 *
 * | 档 | sp | 用在哪 |
 * |---|---|---|
 * | `titleLarge` | 22 | 应用名（主角，全屏唯一） |
 * | `titleMedium` | 16 | 卡片标题、区块标题 |
 * | `bodyMedium` | 14 | 包名、基本信息行、策略说明、周期行、上次执行行 |
 *
 * `labelLarge`（按钮文字、标签片）也是 **14sp**，与 `bodyMedium` 同值 —— 它改变的是字重与
 * 用途，**不新增字号层级**。
 *
 * **本层刻意不用 `bodySmall`**：§2 把 `bodySmall` 明文限定给「**字段下的辅助/错误说明**」，
 * 而弹卡里没有表单字段。早期版本用它渲染定期重启卡内的策略说明，使 sp 值变成四档
 * （22/16/14/12），违反 §1；已改回 `bodyMedium`。该档只出现在周期对话框的
 * `supportingText`（那才是真正的字段下说明），见 `PeriodDialog.kt`。
 *
 * ## 分组手段 = 2 种（规范 §4：同屏最多混用两种）
 *
 * **卡片**（基本信息、定期重启 —— 都是可独立认知的对象）+ **留白**（两组标签片区块）。
 * 标签片不套卡片：它们只是同一屏的补充说明，套上就是满屏盒子。
 *
 * ## 反馈
 *
 * 标签片是**只读**的（[M3TagChip] 故意不进交互树）；
 * 可点元素只有 `Switch`、"修改周期"、"打开系统设置"三个。
 *
 * ## 触控目标的口径（**已用字节码核实，勿凭印象改**）
 *
 * 规则只有一条：**凡 M3 提供的组件一律不声明最小尺寸；只有「自绘可点区」才显式声明 48dp。**
 *
 * | 类别 | 处理 | 依据 |
 * |---|---|---|
 * | M3 组件（`Button` / `TextButton` / `Switch` / `SegmentedButton` / `OutlinedTextField`） | **不声明** | M3 由 `minimumInteractiveComponentSize` 保证 ≥48dp **触控区**，且**不改变视觉高度** |
 * | 自绘可点区（`appClickable` / `appCombinedClickable` 的 `Box`） | **必须显式 ≥48dp** | 无兜底机制，尺寸即触控区 |
 *
 * ⚠️ **早期实现给 Button/TextButton 加了 `heightIn(min = 48dp)`，那是错的**：
 *
 * 已反编译 `ButtonDefaults` 确认 `MinHeight = ButtonSmallTokens.containerHeight`，
 * 其字节码为 `ldc2_w double 40.0d` —— 即 **MD3 按钮的视觉高度是 40dp**，
 * 而 ≥48dp 是**触控区**（由 `minimumInteractiveComponentSize` 在外层撑开，视觉不变）。
 * 显式加 48dp 会把视觉高度从 40dp 抬到 48dp（真机实测 168px = 48dp），
 * 属规范 §9① 明文禁止的「改良 MD3 原值」。
 *
 * 副作用：那处多加的声明还造成了同页「按钮声明、Switch 不声明」的口径自相矛盾。已移除。
 *
 * ## ⚠️ 本层**不自带滚动**
 *
 * 滚动由宿主提供的 `NestedScrollView` 承担，原因是一手核实的事实：
 *
 * MDC 的 `BottomSheetBehavior.findScrollingChild()` 判定"滚动子视图"的依据是
 * **`view.isNestedScrollingEnabled()`**（不是 `instanceof NestedScrollingChild`），
 * 并据此协调「内容滚动 vs 拖动关闭」：`hasScrollingChild()` 决定是否为了拖动而拦截 MOVE，
 * `tryCaptureView` 在内容还能上滚时**放弃**捕获。
 * 而 `ComposeView`（→ `AbstractComposeView` → `ViewGroup`）与 `AndroidComposeView`
 * **都没有启用嵌套滚动**（javap 核实）→ 判定为空 → 两条协调全失效，
 * 拖拽抢走滚动，**内容一超屏就滚不动**（事件标签最多 24 条，必然超屏）。
 *
 * 故宿主用 `NestedScrollView` 包住本内容，滚动发生在 View 层（与迁移前同构）；
 * 本层**不得**再挂 `verticalScroll`，否则双重滚动。
 *
 * @param header 头部模型（Java 类型不得进入本层）
 * @param restart 定期重启区模型；其 `toggleEnabled` 已包含"重启补偿中禁改"的裁决
 */
@Composable
fun ServiceDetailScreen(
    header: DetailHeader,
    info: DetailInfo,
    restart: DetailRestart,
    callback: ServiceDetailCallback,
    sharedScope: SharedTransitionScope,
    sharedIconKey: Any,
    modifier: Modifier = Modifier,
) {
    // 周期对话框的可见性归本层自持：它是纯展示态，不涉及写入，
    // 没必要让宿主（Java）多持一个会跟弹卡生命周期耦合的字段。
    var showPeriodDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = SpacingTokens.lg,
                end = SpacingTokens.lg,
                top = SpacingTokens.xl,
                bottom = SpacingTokens.xxxl,
            ),
    ) {
        HeaderRow(header, sharedScope, sharedIconKey)

        Spacer(Modifier.height(SpacingTokens.xl))
        BasicInfoCard(info)

        Spacer(Modifier.height(SpacingTokens.xl))
        ChipSection(
            title = stringResource(R.string.detail_capabilities),
            chips = info.capabilityChips,
        )

        Spacer(Modifier.height(SpacingTokens.xl))
        ChipSection(
            title = stringResource(R.string.detail_events),
            chips = info.eventChips,
        )

        Spacer(Modifier.height(SpacingTokens.xl))
        RestartCard(
            restart = restart,
            onToggle = { callback.onRestartToggle(it) },
            onModifyPeriod = { showPeriodDialog = true },
        )

        Spacer(Modifier.height(SpacingTokens.xl))
        // 不声明 heightIn：M3 的 Button 已由 `minimumInteractiveComponentSize` 保证 ≥48dp 触控，
        // 且 `ButtonDefaults.MinHeight` = `ButtonSmallTokens.containerHeight` = **40dp**（已解字节码确认）——
        // 显式加 48dp 会把**视觉高度**从 40dp 抬到 48dp，属规范 §9① 禁止的「改良 MD3 原值」。
        // 详见本文件「触控目标的口径」节。
        Button(
            onClick = { callback.onOpenSystemSettings() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.detail_open_settings),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    if (showPeriodDialog) {
        PeriodDialog(
            currentPeriodMin = restart.lastPeriodMin,
            onDismiss = { showPeriodDialog = false },
            onConfirm = { minutes ->
                callback.onPeriodConfirmed(minutes)
                showPeriodDialog = false
            },
        )
    }
}

  /** 头部：图标 + 应用名（主角）+ 包名/类名。 */
  @OptIn(ExperimentalSharedTransitionApi::class)
  @Composable
  private fun HeaderRow(
      header: DetailHeader,
      sharedScope: SharedTransitionScope,
      sharedIconKey: Any,
  ) {
      // 共享元素的**终点**：列表缩略图 → 详情头图（规范 §11.3 该共享项第一条）。
      // 起止矩形来自真实布局（SharedTransitionScope extends LookaheadScope），未手写坐标。
      val iconModifier = with(sharedScope) {
          Modifier.sharedElementWithCallerManagedVisibility(
              sharedContentState = rememberSharedContentState(key = sharedIconKey),
              visible = true,
          )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
          M3AppIcon(
              icon = header.icon,
              initial = header.iconInitial,
              contentDescription = stringResource(R.string.service_icon_desc),
              size = SizeTokens.HeroIconSize,
              modifier = iconModifier,
          )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = SpacingTokens.lg),
        ) {
            Text(
                text = header.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = header.packageClass,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 基本信息卡片。 */
@Composable
private fun BasicInfoCard(info: DetailInfo) {
    DetailCard(title = stringResource(R.string.detail_basic_info)) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
            info.basicLines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 卡片外壳（基本信息卡与定期重启卡**共用**，避免两份相同 Surface 配置）。
 *
 * 底色取 `surfaceContainerLow` —— 规范 §11.1：叠加层内的卡片用低一档容器色，
 * **靠角色分层，不靠 `surfaceTint` 叠色、也不靠阴影**（静止态 elevation = 0）。
 *
 * `headerContent` 为标题行右侧的自定义槽（如定期重启卡的 `Switch`）；不传则为纯标题。
 * 外观参数（颜色 / 形状 / 描边 / 内边距）与旧实现逐项一致。
 */
@Composable
private fun DetailCard(
    title: String,
    headerContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ShapeTokens.Medium,
        border = BorderStroke(SizeTokens.Hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                headerContent?.invoke(this)
            }
            Spacer(Modifier.height(SpacingTokens.sm))
            content()
        }
    }
}

/** 标签片区块：区块标题 + 自动换行的标签片流。 */
@Composable
private fun ChipSection(title: String, chips: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        ChipFlow(chips)
    }
}

/**
 * 标签片流。
 *
 * 空集时渲染「无」而不是留白 —— 「没有特殊能力」是一条**信息**，
 * 留白会让用户以为没加载出来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(chips: List<String>) {
    val labels = chips.ifEmpty { listOf(stringResource(R.string.chip_none)) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        labels.forEach { label -> M3TagChip(text = label) }
    }
}

/**
 * 定期重启卡片。
 *
 * ## 开关为什么是"受控"的
 *
 * 旧 View 实现需要在回正开关前**先摘掉 listener 再 `setChecked` 再设回**，
 * 否则 `setChecked` 会误触发一次切换。Compose 的 `Switch(checked = ...)` 位置完全由模型决定、
 * 自身不持有状态，那条纪律**在结构上不再存在** —— 不是被绕过，是不需要了。
 *
 * ## 状态优先级
 *
 * 「恢复失败」（终态）> 「重启中…」（过渡态）> 周期行。该优先级已由宿主机算进
 * [DetailRestart.periodText]，本层只负责给失败态上 `error` 色
 * （**颜色之外还有文字**，符合「不得用颜色作为唯一信息通道」）。
 */
@Composable
private fun RestartCard(
    restart: DetailRestart,
    onToggle: (Boolean) -> Unit,
    onModifyPeriod: () -> Unit,
) {
    val switchDescription = stringResource(R.string.detail_restart_enable)
    // 卡片外壳复用 [DetailCard]（标题行 + 右侧 Switch），正文整体移入 content 槽。
    DetailCard(
        title = stringResource(R.string.detail_restart_group),
        headerContent = {
            Switch(
                checked = restart.enabled,
                onCheckedChange = onToggle,
                enabled = restart.toggleEnabled,
                modifier = Modifier.semantics { contentDescription = switchDescription },
            )
        },
    ) {
        Text(
            text = stringResource(R.string.detail_restart_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            text = restart.periodText,
            style = MaterialTheme.typography.bodyMedium,
            // 失败态用 error 色 —— 这是**状态**而非层级差异；且颜色之外还有文字
            // （periodText 本身即「恢复失败」），满足「不得用颜色作为唯一信息通道」。
            color = if (restart.failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            text = restart.lastText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(SpacingTokens.sm))
        // 同样不声明 heightIn（M3 组件自带 48dp 触控兜底），理由见「打开系统设置」处的注释。
        TextButton(onClick = onModifyPeriod) {
            Text(
                text = stringResource(R.string.detail_restart_modify),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
