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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.LegadoThemePreview
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
 * - [neutralButtonText]: 可选, 中性按钮文案 (如 "默认"); 传入后显示在左侧
 * - [onNeutral]: 可选, 中性按钮点击回调
 *
 * # 样式 (对照原版 AppCompat AlertDialog 布局 + AppTheme 动态色)
 *
 * - 主色: AppTheme.colors.accent (跟随主题, 非硬编码 arcoblue-6) —— Slider thumb/activeTrack
 *   + 步进按钮 tint + 按钮文字 + 数值文字/光标
 * - 圆角: DesignTokens.dialogShape = arco_radius_lg 16dp
 * - 底部按钮条: 对照 AppCompat AlertDialog 三按钮布局 —— 中性按钮("默认")靠左,
 *   取消+确定靠右 (非均分), 取消/确定之间 8dp
 * - 顶部数值: 点按直接进入键盘编辑 (进入编辑态即请求焦点, 单击出光标, 无需二次点击);
 *   编辑态为无下划线/无 label/hint 的纯文本输入 (特殊文本编辑形态, 不套 AppTextField 装饰盒)
 *
 * # 交互
 *
 * - Slider 拖动: 连续值, 适合大范围 (如 0..3000 ms / 1024..60000 port)
 * - -/+ 按钮: 步进 1, 适合精确调整 (如 8..16 字号)
 * - 当前值居中显示 (大字体 + accent), 便于确认
 *
 * # 大范围精度说明
 *
 * Slider valueRange 用 Float 表示。对于 [range] 跨度极大的极端情况 (如 1024..60000
 * 端口场景), Float 精度约 7 位有效数字, 拖动定位到大致范围后
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
    val colors = AppTheme.colors
    val initial = value.coerceIn(range.first, range.last)
    var currentValue by remember { mutableIntStateOf(initial) }
    // 点按数字进入编辑态: 键盘直接输入 (对照原版 NumberPicker 内嵌 EditText 行为)
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(initial.toString()) }
    // 进入编辑态立即请求焦点: 单击数字即出光标/键盘, 避免"先点进编辑再点聚焦"两次点击
    val editFocus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (editing) editFocus.requestFocus()
    }

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
                // 当前值: 点按数字直接进入键盘编辑 (对照原版 NumberPicker 点数字可输入;
                // 进入编辑态即请求焦点, 单击直接出光标, 无需二次点击聚焦)
                if (editing) {
                    BasicTextField(
                        value = editText,
                        onValueChange = { editText = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { commitEdit() }),
                        textStyle = TextStyle(
                            // 与显示态 Text 字号一致 (32sp), 点击编辑不跳变
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = colors.accent,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        // 特殊文本编辑形态: 无下划线/无 label/hint (不套 AppTextField 装饰盒)
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(editFocus),
                    )
                } else {
                    Text(
                        text = currentValue.toString(),
                        color = colors.accent,
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
                // colors 走动态主题色 accent, 不走 MaterialTheme.colors.primary (默认值非 arco)
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
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.accent.copy(alpha = 0.2f),
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
                            tint = colors.accent,
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
                            tint = colors.accent,
                        )
                    }
                }
            }
        },
        // 底部按钮条走 buttons 槽 (非 confirm/dismiss 槽): 按钮行钉在对话框底部,
        // 不再悬在正文下方 ~32dp (原实现把按钮行放进 text 槽, 叠加 M2 正文底部 padding
        // 28dp + 空按钮条 4dp)。布局对照 AppCompat AlertDialog: 中性按钮靠左, 取消+确定靠右。
        buttons = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (neutralButtonText != null && onNeutral != null) {
                    AppTextButton(
                        text = neutralButtonText,
                        onClick = onNeutral,
                        color = colors.accent,
                    )
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = onDismiss,
                    color = colors.accent,
                )
                Spacer(Modifier.width(8.dp))
                AppTextButton(
                    text = stringResource(Res.string.ok),
                    onClick = {
                        commitEdit()
                        onConfirm(currentValue)
                    },
                    color = colors.accent,
                )
            }
        },
        shape = DesignTokens.dialogShape,
        // 显式容器色, 避免默认色与项目其他对话框 (8dp 圆角) 视觉割裂
        backgroundColor = AppTheme.colors.fillet,
    )
}

// ===== @Preview 合并自 androidMain 的 dialog/NumberPickerDialogPreviews.kt =====

/**
 * [NumberPickerDialog.kt] 中 [NumberPickerDialog] 的 @Preview。
 */

@Preview
@Composable
fun NumberPickerDialogPreview() = LegadoThemePreview {
    NumberPickerDialog(
        title = "字号",
        value = 18,
        range = 12..36,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun NumberPickerDialogLargeRangePreview() = LegadoThemePreview {
    NumberPickerDialog(
        title = "换源间隔(ms)",
        value = 500,
        range = 0..3000,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun NumberPickerDialogWithNeutralPreview() = LegadoThemePreview {
    NumberPickerDialog(
        title = "端口",
        value = 8080,
        range = 1024..65535,
        onConfirm = {},
        onDismiss = {},
        neutralButtonText = "默认",
        onNeutral = {},
    )
}

@Preview
@Composable
fun NumberPickerDialogDarkPreview() = LegadoThemePreview(dark = true) {
    NumberPickerDialog(
        title = "字号",
        value = 16,
        range = 12..36,
        onConfirm = {},
        onDismiss = {},
    )
}
