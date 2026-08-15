package io.legado.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppDialog
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
 * - [onValueChange]: 拖动/步进/提交时实时回调 (用于即时预览; 可忽略, 默认 no-op)
 * - [onConfirm]: 用户点确认时回调, 携带最终值
 * - [onDismiss]: 用户点取消/外部 dismiss 时回调
 * - [neutralButtonText]: 可选, 中性按钮文案 (如 "默认"); 传入后显示在左侧
 * - [onNeutral]: 可选, 中性按钮点击回调
 *
 * # 布局 (自上而下, 自绘 [AppDialog] + [Surface], 对齐项目 AppAlertDialog 模式)
 *
 * 1. 标题 (16sp primaryText, 左对齐)
 * 2. `[-] [数字输入框] [+]` 同一行: 步进按钮移到数字左右两侧, 数字恒为输入框
 *    (纯文本形态, 无下划线/无 label/hint, 与旧版编辑态一致, 不加底色)
 * 3. Slider: 与底部按钮条仅隔 8dp (不再隔 28dp, 消除"操控悬在上部"的观感)
 * 4. 底部按钮条: 中性按钮("默认")靠左, 取消+确定靠右 (取消/确定之间 8dp)
 *
 * 改用自绘布局的原因: M2 AlertDialog 的 text 槽自带 28dp 底部 padding (TextPadding),
 * 无法收紧"滑条-按钮"间距; [AppDialog] 同时带来全项目统一的进入/退出动画、
 * 返回键拦截与 E-Ink 分支。
 *
 * # 交互
 *
 * - 数字恒为输入框: 点击直接聚焦键盘输入 (无需二次点击); 输入经 inputTransformation
 *   过滤非数字; 键盘 Done / 点确定时解析并钳制到 [range] 提交
 * - Slider 拖动 / -+ 步进: **直接改写输入框文本** (调整控件即修改文本值), 并实时
 *   回调 [onValueChange]; -+ 步进基于输入框当前文本 (输入 99 后点 + 得 100)
 * - 最终 [onConfirm] 返回的值均为 Int 整数 (无精度损失)
 *
 * # 大范围精度说明
 *
 * Slider valueRange 用 Float 表示。对于 [range] 跨度极大的极端情况 (如 1024..60000
 * 端口场景), Float 精度约 7 位有效数字, 拖动定位到大致范围后
 * 可用 -/+ 按钮精确调整。
 *
 * @param title 对话框标题
 * @param value 初始值 (会被钳制到 [range] 内)
 * @param range 取值范围 (min..max)
 * @param onValueChange 拖动/步进/提交实时回调 (可选, 默认 no-op)
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
    // 数字恒为输入框 (不再"点按数字才进入编辑态"): 输入框文本是唯一数据源,
    // Slider 拖动 / -+ 步进都直接改写文本 (调整控件即修改文本值)。
    val editState = remember { TextFieldState(initial.toString()) }

    /** 解析输入框文本 → 钳制到 range → 提交为 currentValue (键盘 Done / 点确定时调用) */
    fun commitEdit() {
        editState.text.toString().toIntOrNull()?.let { typed ->
            val clamped = typed.coerceIn(range.first, range.last)
            if (clamped != currentValue) {
                currentValue = clamped
                onValueChange(clamped)
            }
        }
    }

    /** 步进 delta: 基于输入框当前文本 (而非已提交值), 步进结果直接写回文本 */
    fun stepBy(delta: Int) {
        val base = editState.text.toString().toIntOrNull() ?: currentValue
        val stepped = (base + delta).coerceIn(range.first, range.last)
        if (stepped != currentValue) {
            currentValue = stepped
            editState.edit { replace(0, length, stepped.toString()) }
            onValueChange(stepped)
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 标题 (对齐项目其他对话框: 左对齐 16sp primaryText)
                Text(
                    text = title,
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
                // [- ] [数字输入框] [ +]: 步进按钮移到数字左右, 输入框居中
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { stepBy(-1) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_reduce),
                            contentDescription = stringResource(Res.string.reduce),
                            tint = colors.accent,
                        )
                    }
                    // 常驻数字输入框: 点击即聚焦编辑 (无需二次点击);
                    // 纯文本形态 (无下划线/无 label/hint), 与显示态外观一致
                    BasicTextField(
                        state = editState,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        inputTransformation = InputTransformation {
                            val filtered = asCharSequence().toString().filter { it.isDigit() }
                            if (filtered.length != length) {
                                replace(0, length, filtered)
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        onKeyboardAction = { commitEdit() },
                        textStyle = TextStyle(
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = colors.accent,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { stepBy(1) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_add),
                            contentDescription = stringResource(Res.string.plus),
                            tint = colors.accent,
                        )
                    }
                }
                // Slider 拖动调整 (连续值, 适合大范围); 拖动直接改写输入框文本
                Slider(
                    value = currentValue.toFloat(),
                    onValueChange = {
                        val newValue = it.toInt().coerceIn(range.first, range.last)
                        if (newValue != currentValue) {
                            currentValue = newValue
                            // 同步输入框文本: 调整滑条即修改文本值
                            editState.edit { replace(0, length, newValue.toString()) }
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
                // 底部按钮条: 紧贴滑条下方 (spacedBy 8dp), 不再隔 28dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
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
            }
        }
    }
}
