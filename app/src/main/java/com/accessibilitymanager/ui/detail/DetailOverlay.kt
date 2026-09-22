package com.accessibilitymanager.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.accessibilitymanager.core.designsystem.theme.ShapeTokens

/**
 * 详情卡片的**宿主状态**：宿主写、组合读。
 *
 * 与 [HomeListState][com.accessibilitymanager.ui.home.HomeListState] 同一条分工：
 * 宿主（`HomeFragment`）负责写入路径与业务数据，本类只承载「现在打开的是哪个服务、
 * 用哪套不可变模型渲染、意图回调交给谁」。**不写** `Settings.Secure`、不碰镜像。
 *
 * ## 为什么不再用 Dialog
 *
 * 转换前详情卡由 MDC 的 `BottomSheetDialog` / `SideSheetDialog` 承载，**它们是独立 window**。
 * 而规范 §11.3 要求页面转场用共享元素、§9 规则② 把它列为三处偏离之一 ——
 * **共享元素只在同一个组合（同一个 window）内可行**（`SharedTransitionLayout` 跨不过 window，
 * material3 的 `ModalBottomSheet` 内部也是 Dialog）。故承载改为同组合内的 overlay，
 * 形态观感不变（手机仍是底部抽屉、平板仍是居中悬浮），只是承载方式换了。
 *
 * ## 旋转保留
 *
 * [serviceId] 是普通 Compose 状态，Activity 重建（Manifest 未声明 `orientation`）会丢。
 * 宿主在 `onSaveInstanceState` 持久化该 id、重建后回填本字段并重提交模型 ——
 * 「卡片旋转时不关闭」这条由宿主的存取保证，不是本类的职责。
 */
class DetailHost {

    /** 当前打开的服务 id；`null` = 无卡。**唯一数据源**：宿主写、组合读。 */
    var serviceId: String? by mutableStateOf(null)

    /** 按弹卡实例构造的意图回调（捕获本卡恒定的 serviceId / info / pkg）。 */
    var callback: ServiceDetailCallback? = null

    /** 内容快照：头部 / 只读信息 / 定期重启区，三块刷新时机不同（见 [ServiceDetailState]）。 */
    val state: ServiceDetailState = ServiceDetailState()

    /** 是否有卡。 */
    val isOpen: Boolean get() = serviceId != null

    /** 关卡（用户下滑 / 点遮罩 / 宿主关闭都会走这里）。 */
    fun close() {
        serviceId = null
        callback = null
    }
}

/**
 * 详情卡片 overlay —— 同一个组合内承载两种形态。
 *
 * ## 两种形态（用户裁决 2026-09-22，外观各不相同、动画一致）
 *
 * | 形态 | 位置 | 圆角 | 底色 |
 * |---|---|---|---|
 * | 内容宽 < 600dp | 贴底（底部抽屉观感） | [ShapeTokens.BottomSheetTop]（只圆顶角 48dp） | `surfaceContainerHigh` |
 * | 内容宽 ≥ 600dp | 居中（悬浮卡片） | [ShapeTokens.ExtraLarge]（28dp） | `surfaceContainerHigh` |
 *
 * 两种形态**都做应用图标的共享元素转场**（规范 §11.3「列表缩略图 → 详情头图」）。
 *
 * ## 为什么底色都是 `surfaceContainerHigh` 实色
 *
 * 规范 §11.1 把这类浮层定为「叠加层」，正解是 58% 不透明度 + 16dp 模糊。
 * 但**Compose 的 `Modifier.blur` 做不到模糊下层内容**（它只模糊自己画的东西，规范已一手核实）。
 * 故走规范明说的**降级实色**路径：`surfaceContainerHigh` 实色 ——
 * 「降级是必要条件，不是可选优化」。**不要自创中间不透明度**（如 92%），那两头不靠。
 *
 * 遮罩用 `colorScheme.scrim`（`AppTheme` 里已是 `Color.Black.copy(alpha = SCRIM_ALPHA)` = 32% 实色），
 * 承担「阻断交互」，**不做模糊**（规范 §11.1 明说模态遮罩不模糊）。
 *
 * ## 出现／消失动效
 *
 * 规范 §7：元素出现／消失用 `fastEffectsSpec`（淡入淡出 + 轻微位移），**不另设时长**。
 *
 * @param host 宿主状态（打开哪个服务、模型、意图回调）
 * @param sharedScope 共享元素的 scope（由列表树根的 `SharedTransitionLayout` 提供）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DetailOverlay(
    host: DetailHost,
    sharedScope: SharedTransitionScope,
    modifier: Modifier = Modifier,
) {
    val serviceId = host.serviceId ?: return
    val callback = host.callback ?: return
    val motion = MaterialTheme.motionScheme

    AnimatedVisibility(
        visible = host.isOpen,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(animationSpec = motion.fastEffectsSpec()),
        exit = fadeOut(animationSpec = motion.fastEffectsSpec()),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 形态阈值（600dp）**实时**取值：每次重组按当前宽度选形态，
            // 旋转 / 分屏拖动 / 折叠屏开合都自动跟随 —— 故旧的「形态跟随 watcher」整段退役。
            val wide = maxWidth >= 600.dp
            // 遮罩：scrim 角色本身就是 32% 实色（AppTheme 内合成），不叠额外 alpha、不模糊
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { host.close() },
            )

            Surface(
                shape = if (wide) ShapeTokens.ExtraLarge else ShapeTokens.BottomSheetTop,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(if (wide) Alignment.Center else Alignment.BottomCenter)
                    .then(
                        if (wide) {
                            // 平板：居中悬浮，宽 500dp 且不超过屏宽 60%（沿用侧边栏时代的既有口径）
                            Modifier
                                .fillMaxWidth(fraction = 0.6f)
                                .widthIn(max = 500.dp)
                        } else {
                            // 手机：贴底全宽，顶部 48dp 圆角（ShapeTokens.BottomSheetTop）
                            Modifier.fillMaxWidth()
                        },
                    )
                    // 高度按内容，上限留出上下呼吸空间（规范 §3：首块距顶、底部留白）
                    .heightIn(max = maxHeight - 96.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ServiceDetailScreen(
                        header = host.state.header,
                        info = host.state.info,
                        restart = host.state.restart,
                        callback = callback,
                        sharedScope = sharedScope,
                        sharedIconKey = serviceId,
                    )
                }
            }
        }
    }
}
