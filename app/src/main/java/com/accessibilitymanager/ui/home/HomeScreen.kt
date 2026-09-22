package com.accessibilitymanager.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.accessibilitymanager.R
import com.accessibilitymanager.core.designsystem.theme.ShapeTokens
import com.accessibilitymanager.core.designsystem.theme.SizeTokens
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens
import com.accessibilitymanager.ui.model.ServiceUiModel
import kotlin.math.ceil
import kotlin.math.max

/**
 * 卡片舒适宽度上限（dp）。
 *
 * 列数公式移植自参考实现（PiliPlus 主页）：
 * `列数 = ceil((可用宽 − 间距) / (单卡最大宽 + 间距))`，下限 1，**无横竖屏/设备类型硬编码**。
 *
 * ⚠️ **取 500dp 而非 400dp**（沿用既有实现已修正的值）：400dp 阈值会让**密度调整后的
 * 手机竖屏（≈421dp）误入 2 列**。故最小宽度定为 500dp：
 * 手机竖屏（≈420dp）恒 1 列 → 平板竖屏（≈800dp）2 列 → 平板横屏（≥1224dp）3 列。
 */
private val CardMaxWidth: Dp = 500.dp

/**
 * 主页（服务列表）。
 *
 * ## 一屏一个主角（规范 §1）
 *
 * 动词 = **浏览**；主角 = **服务列表**。顶栏标题与搜索框均为从属元素。
 *
 * ## 分组手段（规范 §4）
 *
 * 只有「卡片」一种：每行是可独立操作的对象（可点进详情 + 独立动作）。
 *
 * ## 三态（规范 §6）
 *
 * 首屏用**骨架屏**（不用转圈，保留结构感）；空态说清「这里本该有什么」并给下一步；
 * 错误态**就地显示**（不弹窗打断），动词是「重试」。
 *
 * ## 流畅度
 *
 * - 列表项以 `serviceId` 为 key + `animateItem()`（置顶移动走弹簧，不是整表重绘）；
 * - **滑动中暂停后台图标预加载**（`onScrollingChanged`），避免与滑动抢 Binder/CPU。
 *
 * @param models 已映射完成的不可变行模型（由状态持有者产出，**Java 类型不得进入本层**）
 * @param loading 首次加载中（且尚无数据）→ 显示骨架屏
 * @param error 读取失败 → 显示错误态
 */
@Composable
fun HomeScreen(
    models: List<ServiceUiModel>,
    sharedScope: SharedTransitionScope,
    /** 当前打开详情的服务 id；null = 无卡。用于决定图标共享元素的起点可见性。 */
    detailServiceId: String?,
    permissionGranted: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggle: (ServiceUiModel, Boolean) -> Unit,
    onLockClick: (ServiceUiModel) -> Unit,
    onOpen: (ServiceUiModel) -> Unit,
    onLongPress: (ServiceUiModel) -> Unit,
    onAuthorize: () -> Unit,
    onRetry: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: Boolean = false,
    onScrollingChanged: (Boolean) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (!permissionGranted) {
            PermissionBanner(onAuthorize = onAuthorize)
        }

        SearchField(
            query = searchQuery,
            onQueryChange = onSearchChange,
        )

        val gridState = rememberLazyGridState()

        // 滑动中暂停预加载：滑动状态变化推给调用方（它再转发给 IconCache.setPaused）。
        LaunchedEffect(gridState, onScrollingChanged) {
            snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
                onScrollingChanged(scrolling)
            }
        }

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val spacing = SpacingTokens.sm
            val spanCount = max(
                1,
                ceil(((maxWidth - spacing) / (CardMaxWidth + spacing)).toFloat()).toInt(),
            )

            when {
                error && models.isEmpty() -> ErrorState(onRetry = onRetry)

                loading && models.isEmpty() -> LoadingSkeleton(spanCount = spanCount)

                // ⚠️ 「搜索无结果」与「设备上确实没有无障碍服务」是**两件事**，必须分开表达。
                //
                // 真机实测（2026-09-19）：在装有 7 个无障碍服务的设备上搜一个不存在的关键词，
                // 界面显示"未发现任何无障碍服务"——这会**误导用户以为设备上没装服务**，
                // 而实际上只是被搜索条件过滤掉了。两个状态此前共用一个分支。
                models.isEmpty() && searchQuery.isNotBlank() -> SearchEmptyState(
                    query = searchQuery,
                    onClearQuery = { onSearchChange("") },
                )

                models.isEmpty() -> EmptyState(onOpenSystemSettings = onOpenSystemSettings)

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(spanCount),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = SpacingTokens.lg,
                        end = SpacingTokens.lg,
                        top = SpacingTokens.sm,
                        bottom = SpacingTokens.xxl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    items(
                        items = models,
                        key = { it.serviceId },
                    ) { model ->
                          ServiceCard(
                              model = model,
                              sharedScope = sharedScope,
                              sharedIconKey = model.serviceId,
                              iconSharedVisible = model.serviceId != detailServiceId,
                              modifier = Modifier.animateItem(),
                            onToggle = { onToggle(model, it) },
                            onLockClick = { onLockClick(model) },
                            onClick = { onOpen(model) },
                            onLongClick = { onLongPress(model) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 未授权横幅。
 *
 * 「未授权」是**状态**而非错误：用 `surfaceContainerHigh` 中性承载 + `primary` 动作色，
 * 不用 error 色（错误色留给真正的写入失败）。
 */
@Composable
private fun PermissionBanner(onAuthorize: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ShapeTokens.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SpacingTokens.lg,
                end = SpacingTokens.lg,
                top = SpacingTokens.sm,
            ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = SpacingTokens.lg,
                end = SpacingTokens.sm,
                top = SpacingTokens.sm,
                bottom = SpacingTokens.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.banner_no_permission),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAuthorize) {
                Text(
                    text = stringResource(R.string.banner_action_auth),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** 搜索框（`Full` 圆角；输入内容 `bodyLarge`，符合规范 §2）。 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = ShapeTokens.Full,
        placeholder = {
            Text(
                text = stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SpacingTokens.lg,
                end = SpacingTokens.lg,
                top = SpacingTokens.sm,
                bottom = SpacingTokens.sm,
            ),
    )
}

/**
 * 首屏骨架屏（**不用转圈**）。
 *
 * 骨架形状**接近真实内容**：圆形图标位 + 两条文本条，而不是清一色灰条。
 */
@Composable
private fun LoadingSkeleton(spanCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = SpacingTokens.lg,
                end = SpacingTokens.lg,
                top = SpacingTokens.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        repeat(if (spanCount > 1) spanCount * 2 else 4) {
            SkeletonCard()
        }
    }
}

@Composable
private fun SkeletonCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ShapeTokens.Medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                start = SpacingTokens.md,
                end = SpacingTokens.md,
                top = SpacingTokens.md,
                bottom = SpacingTokens.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(SizeTokens.IconContainerSize)
                    // 用 token 而非内置 `CircleShape`：两者观感等价，但绕过 token 会让
                    // 「圆角只能从十级刻度取」这条纪律无法机械检查（`M3AppIcon` 曾犯同一错误）。
                    .clip(ShapeTokens.Full)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = SpacingTokens.md),
            ) {
                SkeletonBar(widthFraction = 0.5f)
                Spacer(Modifier.height(SpacingTokens.sm))
                SkeletonBar(widthFraction = 0.75f)
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(SizeTokens.SkeletonLineHeight)
            .clip(ShapeTokens.ExtraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/**
 * 空态（规范 §6 三段式）：图标低对比 + 标题说清「本该有什么」+ 说明下一步 + 一个主动作。
 *
 * **不写「暂无数据」** —— 那既没说本该有什么，也没说下一步。
 */
@Composable
private fun EmptyState(onOpenSystemSettings: () -> Unit) {
    CenteredMessage(
        iconRes = R.drawable.ic_accessibility_placeholder,
        title = stringResource(R.string.empty_title),
        desc = stringResource(R.string.empty_desc),
    ) {
        Button(onClick = onOpenSystemSettings) {
            Text(
                text = stringResource(R.string.empty_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** 错误态：结构与空态一致，动词是「重试」；**就地显示，不弹窗打断**。 */
@Composable
private fun ErrorState(onRetry: () -> Unit) {
    CenteredMessage(
        iconRes = R.drawable.ic_accessibility_placeholder,
        title = stringResource(R.string.list_error_title),
        desc = stringResource(R.string.list_error_desc),
    ) {
        Button(onClick = onRetry) {
            Text(
                text = stringResource(R.string.list_error_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 「搜索无结果」态。
 *
 * 与 [EmptyState] 的区别是**信息不同**：这里必须让用户明白「服务是有的，只是被本次搜索过滤掉了」，
 * 否则会把「搜不到」误读成「设备上没有服务」。
 *
 * 按规范 §6 三段式，且**把具体查询词回显出来**（用户才能看出是不是打错了），
 * 主动作是「清空搜索」——那是此刻唯一有意义的下一步（不是"去系统设置开启服务"）。
 */
@Composable
private fun SearchEmptyState(
    query: String,
    onClearQuery: () -> Unit,
) {
    CenteredMessage(
        iconRes = R.drawable.ic_accessibility_placeholder,
        title = stringResource(R.string.empty_search_title, query),
        desc = stringResource(R.string.empty_search_desc),
    ) {
        Button(onClick = onClearQuery) {
            Text(
                text = stringResource(R.string.empty_search_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun CenteredMessage(
    iconRes: Int,
    title: String,
    desc: String,
    action: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SpacingTokens.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SizeTokens.EmptyStateIcon),
        )
        Spacer(Modifier.height(SpacingTokens.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.xl))
        action()
    }
}
