package com.accessibilitymanager.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.accessibilitymanager.R
import com.accessibilitymanager.RestartPrefs
import com.accessibilitymanager.core.designsystem.theme.SpacingTokens

/**
 * 周期换算因子（与单位按钮同序）。
 *
 * 与旧实现同一组值，**不得改动**：预填用的整除判定、确认时的乘法都依赖它。
 */
// 周期单位因子收口 RestartPrefs.UNIT_FACTORS（唯一实现；索引即单位：0=分钟 1=小时 2=天）
private val UnitFactors = RestartPrefs.UNIT_FACTORS

/**
 * 周期单位标签（顺序即 [UnitFactors] 的索引）。
 */
private val UnitLabelRes = intArrayOf(
    R.string.unit_minutes,
    R.string.unit_hours,
    R.string.unit_days,
)

/**
 * 修改重启周期对话框。
 *
 * ## 为什么用 Compose 的 `AlertDialog` 而不是 `MaterialAlertDialogBuilder`
 *
 * 与设置页的单选对话框同因：**对话框也在组合树内**，才能继承 `AppTheme` 的
 * 弹簧动效与 token（`MaterialAlertDialogBuilder` 走 View 体系，拿不到 `motionScheme`）。
 *
 * ## 单位选择为什么是分段按钮
 *
 * 规范 §5「选择控件」：**2–5 个互斥 → `SegmentedButton`**。
 * 旧实现是 `MaterialButtonToggleGroup`（三个互斥按钮），语义等价；
 * 换成 M3 的分段按钮是为了拿到同一套形状与选中态。
 *
 * ## 校验口径（与旧实现逐条一致）
 *
 * | 输入 | 确认键 | 字段下方文案 |
 * |---|---|---|
 * | 空 | 禁用 | 策略说明（中性色，**不是错误色**） |
 * | 非法（非数字 / 非正 / 越界） | 禁用 | 「周期需在 30 分钟 ~ 30 天之间」（错误色） |
 * | 合法 | 可点 | 策略说明 |
 *
 * 溢出语义也保持一致：`value * 因子` 若溢出则越出范围 → 判为非法（不额外报错）。
 *
 * ## 校验为什么不在本层做写入
 *
 * 本对话框**只回传一个已通过范围校验的分钟数**；`setPeriod` / `enable` / 调度
 * 全部由宿主落笔（写入路径与镜像纪律的唯一落点）。
 *
 * @param currentPeriodMin 预填值 —— 必须是**该服务上一次的设定周期**（含未启用态），
 *        不能回落默认周期，否则「改周期 → 关 → 再开」会把用户设定静默回退（既有 R1 裁决）
 * @param onConfirm 传入换算后的分钟数（范围已保证合法）；**其实现负责关闭本对话框**
 *
 * ## 触控目标
 *
 * 三个可点元素（分段按钮、两个对话框按钮）都**不**显式声明 48dp：
 * M3 组件由 `minimumInteractiveComponentSize` 内置兜底（已核实该符号存在于 material3 alpha18）。
 * 显式规则见 `ServiceDetailScreen` 的「触控目标的口径」节。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PeriodDialog(
    currentPeriodMin: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    // 预填：按整除性自动选最大可用单位（整天 > 整小时 > 分钟）——
    // 收口 RestartPrefs.periodUnitIndex（唯一实现，原为第二份同构判定）
    val initialUnitIndex = RestartPrefs.periodUnitIndex(currentPeriodMin)
    var text by remember {
        mutableStateOf((currentPeriodMin / UnitFactors[initialUnitIndex]).toString())
    }
    var unitIndex by remember { mutableStateOf(initialUnitIndex) }

    val trimmed = text.trim()
    val empty = trimmed.isEmpty()
    val factor = UnitFactors[unitIndex]
    val value = trimmed.toLongOrNull()
    // 溢出前置判定：先卡 `value > MAX_PERIOD_MIN / factor`，超界即拒绝，
    // 使 `value * factor` 的 Long 回绕值不可能进入合法区间（如 307445734561825861 小时会
    // 回绕成 44 分钟被旧逻辑放行）。卡住后 `value * factor` 必在 [factor, MAX] 内、无溢出。
    val minutes = value?.let { v ->
        if (v > 0L && v <= RestartPrefs.MAX_PERIOD_MIN / factor) v * factor else null
    }
    val valid = minutes != null && minutes >= RestartPrefs.MIN_PERIOD_MIN
    val showError = !valid && !empty

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.period_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.period_dialog_hint)) },
                    isError = showError,
                    // 字段级说明放**该字段下方**（规范 §6），不另起一行独立提示
                    supportingText = {
                        Text(
                            text = stringResource(
                                if (showError) R.string.period_invalid else R.string.period_dialog_helper
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(SpacingTokens.lg))

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    UnitLabelRes.forEachIndexed { index, labelRes ->
                        SegmentedButton(
                            selected = index == unitIndex,
                            onClick = { unitIndex = index },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = UnitLabelRes.size,
                            ),
                            label = {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // valid 才可点，故 value/minutes 必非空。
                    // **不在此处调 onDismiss()** —— 显示与否由调用方的状态决定
                    // （本组件是无状态的 `if (visible)` 子节点，关不掉自己）；
                    // 调用方的 onConfirm 负责关闭。两处各关一次只是冗余置位。
                    minutes?.let { onConfirm(it) }
                },
                enabled = valid,
            ) {
                Text(
                    text = stringResource(R.string.action_ok),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(android.R.string.cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}
