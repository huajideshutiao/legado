package io.legado.app.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_reduce
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.plus
import legado.shared.generated.resources.reduce
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 数字选择对话框 (Compose Multiplatform / sharedUiMain)。
 *
 * # 职责
 *
 * 提供跨平台 (Android + Desktop + iOS 等) 通用的数字选择 UI, 替代 app 端
 * `io.legado.app.ui.widget.number.NumberPickerDialog` (基于 Android View NumberPicker,
 * 不可跨平台)。app 端原版保留不动, 本 shared 版本供 desktop 端复用。
 *
 * # API
 *
 * - [title]: 对话框标题
 * - [value]: 初始值
 * - [range]: 取值范围 (min..max, 闭区间, max > min)
 * - [onValueChange]: 拖动/步进时实时回调 (用于即时预览; 可忽略, 默认 no-op)
 * - [onConfirm]: 用户点确认时回调, 携带最终值
 * - [onDismiss]: 用户点取消/外部 dismiss 时回调
 * - [neutralButtonText]: 可选, 中性按钮文案 (如 "默认"); 传入后显示在 cancel 左侧
 * - [onNeutral]: 可选, 中性按钮点击回调
 *
 * # 样式 (Arco Design 规范, 任务约束)
 *
 * - 主色: arcoblue-6 (#165DFF) —— Slider thumb/activeTrack + 步进按钮 tint + 按钮文字
 * - 圆角: arco_radius_lg = 16dp —— AlertDialog shape
 * - 无阴影 (AlertDialog 默认无阴影)
 *
 * # 交互
 *
 * - Slider 拖动: 连续值, 适合大范围 (如 0..3000 ms / 1024..60000 port)
 * - -/+ 按钮: 步进 1, 适合精确调整 (如 8..16 字号)
 * - 当前值居中显示 (大字体 + arcoblue-6), 便于确认
 *
 * # 大范围精度说明
 *
 * Slider valueRange 用 Float 表示。对于 [range] 跨度极大 (如 10..Int.MAX_VALUE,
 * sourceEditMaxLine 场景) 的极端情况, Float 精度约 7 位有效数字, 拖动定位到大致范围后
 * 可用 -/+ 按钮精确调整。所有用例下, 最终 [onConfirm] 返回的值均为 Int 整数 (无精度损失)。
 *
 * @param title 对话框标题
 * @param value 初始值 (会被钳制到 [range] 内)
 * @param range 取值范围 (min..max)
 * @param onValueChange 拖动/步进实时回调 (可选, 默认 no-op)
 * @param onConfirm 确认回调, 携带最终值
 * @param onDismiss 取消/dismiss 回调
 * @param neutralButtonText 中性按钮文案 (可选, 配合 [onNeutral] 使用)
 * @param onNeutral 中性按钮回调 (可选)
 */
@Composable
fun NumberPickerDialog(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit = {},
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    neutralButtonText: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    require(range.first <= range.last) { "Invalid range: $range (first > last)" }
    val initial = value.coerceIn(range.first, range.last)
    var currentValue by remember { mutableIntStateOf(initial) }
    // 点按数字进入编辑态: 键盘直接输入 (对照原版 NumberPicker 内嵌 EditText 行为)
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(initial.toString()) }

    fun commitEdit() {
        // 只在编辑态 (用户点数字进入输入) 时才用 editText 覆盖 currentValue;
        // 否则 Slider 拖动 / +/- 步进调过的值会被陈旧的 editText (仍为初始值)
        // 覆盖回 range 起点, 表现为"调节无效" (如发现页列数、漫画翻页速度等)。
        if (editing) {
            editText.toIntOrNull()?.let { typed ->
                val clamped = typed.coerceIn(range.first, range.last)
                if (clamped != currentValue) {
                    currentValue = clamped
                    onValueChange(clamped)
                }
            }
            editing = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogSize(),
        properties = AppDialogSizes.properties(),
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 当前值: 点按数字进入键盘编辑 (对照原版 NumberPicker 点数字可输入)
                if (editing) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { commitEdit() }),
                        textStyle = TextStyle(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = DesignTokens.arcoBlue6,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = currentValue.toString(),
                        color = DesignTokens.arcoBlue6,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editText = currentValue.toString()
                                editing = true
                            },
                    )
                }
                // Slider 拖动调整 (连续值, 适合大范围)
                // colors 显式指定 arcoblue-6, 不走 MaterialTheme.colors.primary (默认值非 arco)
                Slider(
                    value = currentValue.toFloat(),
                    onValueChange = {
                        editing = false
                        val newValue = it.toInt().coerceIn(range.first, range.last)
                        if (newValue != currentValue) {
                            currentValue = newValue
                            // 同步编辑框文本, 避免后续进入编辑态时显示陈旧值
                            editText = newValue.toString()
                            onValueChange(newValue)
                        }
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = DesignTokens.arcoBlue6,
                        activeTrackColor = DesignTokens.arcoBlue6,
                        inactiveTrackColor = DesignTokens.arcoBlue6.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // -/+ 步进按钮行 (精确调整, 步进 1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            editing = false
                            if (currentValue > range.first) {
                                currentValue--
                                // 同步编辑框文本 (同 Slider 分支)
                                editText = currentValue.toString()
                                onValueChange(currentValue)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_reduce),
                            contentDescription = stringResource(Res.string.reduce),
                            tint = DesignTokens.arcoBlue6,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(
                        onClick = {
                            editing = false
                            if (currentValue < range.last) {
                                currentValue++
                                // 同步编辑框文本 (同 Slider 分支)
                                editText = currentValue.toString()
                                onValueChange(currentValue)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_add),
                            contentDescription = stringResource(Res.string.plus),
                            tint = DesignTokens.arcoBlue6,
                        )
                    }
                }
                // 底部按钮行: 默认/取消/确定 等宽等距 (对齐原版 AlertDialog 三按钮等权按钮条)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (neutralButtonText != null && onNeutral != null) {
                        AppTextButton(
                            text = neutralButtonText,
                            onClick = onNeutral,
                            color = DesignTokens.arcoBlue6,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AppTextButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss,
                        color = DesignTokens.arcoBlue6,
                        modifier = Modifier.weight(1f),
                    )
                    AppTextButton(
                        text = stringResource(Res.string.ok),
                        onClick = {
                            commitEdit()
                            onConfirm(currentValue)
                        },
                        color = DesignTokens.arcoBlue6,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
        shape = DesignTokens.dialogShape,
        // 显式容器色, 避免默认色与项目其他对话框 (8dp 圆角) 视觉割裂
        backgroundColor = AppTheme.colors.fillet,
    )
}
