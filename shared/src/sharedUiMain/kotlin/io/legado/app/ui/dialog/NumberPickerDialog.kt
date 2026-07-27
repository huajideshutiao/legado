package io.legado.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString

/** Arco Design arcoblue-6 主色 (#165DFF)。 */
private val ArcoBlue6 = Color(0xFF165DFF)

/** Arco Design arco_radius_lg = 16dp。 */
private val ArcoRadiusLg = 16.dp

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 当前值居中显示 (大字体 + arcoblue-6 主色, 便于确认)
                Text(
                    text = currentValue.toString(),
                    color = ArcoBlue6,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Slider 拖动调整 (连续值, 适合大范围)
                // colors 显式指定 arcoblue-6, 不走 MaterialTheme.colorScheme.primary (默认值非 arco)
                Slider(
                    value = currentValue.toFloat(),
                    onValueChange = {
                        val newValue = it.toInt().coerceIn(range.first, range.last)
                        if (newValue != currentValue) {
                            currentValue = newValue
                            onValueChange(newValue)
                        }
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = ArcoBlue6,
                        activeTrackColor = ArcoBlue6,
                        inactiveTrackColor = ArcoBlue6.copy(alpha = 0.2f),
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
                            if (currentValue > range.first) {
                                currentValue--
                                onValueChange(currentValue)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = rememberPainter("ic_reduce"),
                            contentDescription = rememberString("reduce"),
                            tint = ArcoBlue6,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(
                        onClick = {
                            if (currentValue < range.last) {
                                currentValue++
                                onValueChange(currentValue)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = rememberPainter("ic_add"),
                            contentDescription = rememberString("plus"),
                            tint = ArcoBlue6,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentValue) }) {
                Text(
                    text = rememberString("ok"),
                    color = ArcoBlue6,
                )
            }
        },
        dismissButton = {
            // 布局对齐 AlertDialog: neutral 靠左, cancel 靠右
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (neutralButtonText != null && onNeutral != null) {
                    TextButton(onClick = onNeutral) {
                        Text(
                            text = neutralButtonText,
                            color = ArcoBlue6,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = rememberString("cancel"),
                        color = ArcoBlue6,
                    )
                }
            }
        },
        shape = RoundedCornerShape(ArcoRadiusLg),
        // 显式容器色, 避免 Material3 AlertDialog 默认色与项目其他对话框 (8dp 圆角) 视觉割裂
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
