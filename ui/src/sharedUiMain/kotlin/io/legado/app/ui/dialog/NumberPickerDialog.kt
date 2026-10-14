package io.legado.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
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
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.ic_add
import legado.ui.generated.resources.ic_reduce
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.plus
import legado.ui.generated.resources.reduce
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
 * - [onConfirm]: 用户点确认时回调, 携带最终值; 回调后对话框自动关闭
 * - [onDismiss]: 用户点取消/外部 dismiss 时回调
 * - [neutralButtonText]: 可选, 中性按钮文案 (如 "默认"); 传入后显示在左侧
 * - [onNeutral]: 可选, 中性按钮点击回调
 *
 * # 布局 ([AppAlertDialog]: 标题纯文字, 正文槽放控件, 按钮走 alert 三槽)
 *
 * 对齐原版 `showNumberPicker` (androidx `AlertDialog.Builder` + `customView(NumberPicker)`
 * + `okButton`/`cancelButton`/`neutralButton`):
 *
 * 1. 标题: alert 自绘纯文字标题 —— **无返回箭头、无工具条顶栏** (原版无 `dialog_title_bar`,
 *    关闭靠取消/点外部/返回键)
 * 2. `[-] [数字输入框] [+]` 同一行: 步进按钮移到数字左右两侧, 数字恒为输入框
 *    (纯文本形态, 无下划线/无 label/hint, 与旧版编辑态一致, 不加底色)
 * 3. Slider: 与按钮行仅隔 8dp (不再隔 28dp, 消除"操控悬在上部"的观感)
 * 4. 按钮: 中性按钮("默认")在 alert 中性槽 (靠左), 取消/确定走 negative/positive 槽 (靠右)
 *
 * 不用 M2 AlertDialog 的 text 槽: 它自带 28dp 底部 padding (TextPadding), 无法收紧
 * "滑条-按钮"间距; [AppAlertDialog] 同时带来全项目统一的进入/退出动画、
 * 返回键拦截与 E-Ink 分支。
 *
 * # 交互
 *
 * - 数字恒为输入框: 点击直接聚焦键盘输入 (无需二次点击); 输入经 inputTransformation
 *   过滤非数字; 键盘 Done / 点确定时解析并钳制到 [range] 提交
 * - Slider 拖动 / -+ 步进: **直接改写输入框文本** (调整控件即修改文本值), 并实时
 *   回调 [onValueChange]; -+ 步进基于输入框当前文本 (输入 99 后点 + 得 100)
 * - 确定按钮回调执行完毕后自动关闭对话框 (对齐原版 AlertDialog
 *   确定按钮点击后默认 dismiss 的行为)
 *
 * # 大范围精度说明
 *
 * 滑条按手指位置换算整数刻度 (四舍五入)。[range] 跨度极大时 (如 1024..60000 端口场景)
 * 一个像素宽度对应几百个刻度, 拖动只能定位到大致范围, 精确值用 -/+ 按钮或输入框调。
 *
 * @param title 对话框标题
 * @param value 初始值 (会被钳制到 [range] 内)
 * @param range 取值范围 (min..max)
 * @param onValueChange 拖动/步进/提交实时回调 (可选, 默认 no-op)
 * @param onConfirm 确认回调, 携带最终值; 回调后对话框自动关闭
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
    // 滑条位置由 AppSlider 自己按手指位置持有并取整, 这里只留业务值与输入框文本
    // 数字恒为输入框 (不再"点按数字才进入编辑态"): 输入框文本是唯一数据源,
    // Slider 拖动 / -+ 步进都直接改写文本 (调整控件即修改文本值)。
    val editState = remember { TextFieldState(initial.toString()) }

    /** 解析输入框文本 → 钳制到 range → 提交为 currentValue (键盘 Done / 点确定时调用) */
    fun commitEdit() {
        val typed = editState.text.toString().toIntOrNull() ?: currentValue
        val clamped = typed.coerceIn(range.first, range.last)
        // 无条件把钳制结果写回输入框, 越界/非法输入不再滞留界面
        editState.edit { replace(0, length, clamped.toString()) }
        if (clamped != currentValue) {
            currentValue = clamped
            onValueChange(clamped)
        }
    }

    /** 步进 delta: 基于输入框当前文本 (而非已提交值), 步进结果直接写回文本 */
    fun stepBy(delta: Int) {
        val base = editState.text.toString().toIntOrNull() ?: currentValue
        val stepped = (base + delta).coerceIn(range.first, range.last)
        // 判断对 base 而非 currentValue: 输 51 (越界未提交) 后点减号必须能写回 50
        if (stepped != base) {
            currentValue = stepped
            editState.edit { replace(0, length, stepped.toString()) }
            onValueChange(stepped)
        }
    }

    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        // 原版 okButton: setPositiveButton 点击后默认 dismiss; 回调里先提交编辑值再回传
        okButton = AlertButton(text = okText) {
            commitEdit()
            onConfirm(currentValue)
        },
        cancelButton = AlertButton(text = cancelText),
        // 原版 neutralButton: setNeutralButton 在左侧槽位, 点击后同样 dismiss
        neutralButton = if (neutralButtonText != null && onNeutral != null) {
            AlertButton(text = neutralButtonText, onClick = onNeutral)
        } else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignTokens.spacingDefault),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacingDefault),
        ) {
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
            AppSlider(
                value = currentValue,
                min = range.first,
                max = range.last,
                onValueChange = { newValue ->
                    if (newValue != currentValue) {
                        currentValue = newValue
                        // 同步输入框文本: 调整滑条即修改文本值
                        editState.edit { replace(0, length, newValue.toString()) }
                        onValueChange(newValue)
                    }
                },
                color = colors.accent,
                trackColor = colors.accent.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
