package io.legado.app.ui.compose.preference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.drawscope.Stroke
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ColorUtils
import androidx.compose.foundation.lazy.LazyListScope

/**
 * 取色项：行尾颜色格子（复刻 cpv 预览方块），点击进自绘取色盘。复刻 ColorPreference。
 * 读写 Int（key 不变）；showAlphaSlider=false 时落盘前补满 alpha（对齐 withAlpha）。
 */
fun LazyListScope.colorPreference(
    prefKey: String,
    title: String,
    summary: String? = null,
    defaultValue: Int = 0xFF000000.toInt(),
    icon: Painter? = null,
    enabled: Boolean = true,
    isBottomBackground: Boolean = false,
    showAlphaSlider: Boolean = false,
    presets: List<Int> = MaterialPresets,
    onColorChange: ((Int) -> Boolean)? = null,
) = item {
    // 替代 LocalContext.current + context.getPrefInt: commonMain 通过 PreferenceStoreProvider 注入
    val pref = LocalPreferenceStoreProvider.current
    var color by remember { mutableStateOf(pref.getInt(prefKey, defaultValue)) }
    var showDialog by remember { mutableStateOf(false) }
    PreferenceRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        isBottomBackground = isBottomBackground,
        onClick = { showDialog = true },
        widget = {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, AppTheme.colors.secondaryText, CircleShape),
            )
        },
    )
    if (showDialog) {
        ColorPickerDialog(
            initColor = color,
            title = title,
            showAlphaSlider = showAlphaSlider,
            presets = presets,
            onDismissRequest = { showDialog = false },
            onConfirm = { picked ->
                val v = if (showAlphaSlider) picked else ColorUtils.withAlpha(picked, 1f)
                // onColorChange 返回 true 表示外部已处理（对齐 onSaveColor）
                if (onColorChange?.invoke(v) != true) {
                    color = v
                    // 替代 context.putPrefInt: 走 PreferenceStoreProvider
                    pref.putInt(prefKey, v)
                }
            },
        )
    }
}

/**
 * 自绘取色盘（对照 jaredrummler ColorPicker 功能范围）：
 * SV 面板 + Hue 滑条 + 可选 Alpha 滑条 + hex 输入 + 预设色格。不加不减。
 */
@Composable
fun ColorPickerDialog(
    initColor: Int,
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
    showAlphaSlider: Boolean = false,
    presets: List<Int> = MaterialPresets,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        ColorPickerDialogContent(
            initColor = initColor,
            title = title,
            onDismissRequest = onDismissRequest,
            onConfirm = onConfirm,
            showAlphaSlider = showAlphaSlider,
            presets = presets,
        )
    }
}

/** 取色盘正文（不含窗口），供命令式宿主 base/ComposeDialog 复用 */
@Composable
fun ColorPickerDialogContent(
    initColor: Int,
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
    showAlphaSlider: Boolean = false,
    presets: List<Int> = MaterialPresets,
) {
    // 替代 android.graphics.Color.colorToHSV: commonMain 用纯 Kotlin ColorUtils.colorToHSV
    val hsv = remember { FloatArray(3).also { ColorUtils.colorToHSV(initColor, it) } }
    var hue by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    // 替代 android.graphics.Color.alpha: commonMain 用纯 Kotlin ColorUtils.alpha
    var alpha by remember { mutableStateOf(ColorUtils.alpha(initColor) / 255f) }

    fun current(): Int {
        // 替代 android.graphics.Color.HSVToColor: commonMain 用纯 Kotlin ColorUtils.HSVToColor
        val rgb = ColorUtils.HSVToColor(floatArrayOf(hue, sat, value))
        // 替代 androidx.core.graphics.ColorUtils.setAlphaComponent:
        // ColorUtils.withAlpha(rgb, alpha) 内部把 alpha*255 clamp 后合并 RGB, 与原结果完全一致
        return ColorUtils.withAlpha(rgb, alpha)
    }

    var hex by remember { mutableStateOf(ColorUtils.intToString(current())) }
    val colors = AppTheme.colors

    fun applyHsv() { hex = ColorUtils.intToString(current()) }

    AppAlertDialogContent(
        onDismissRequest = onDismissRequest,
        title = title,
        // 替代 stringResource(R.string.ok): commonMain 走 rememberString("ok")
        okButton = AlertButton(text = rememberString("ok"), onClick = { onConfirm(current()) }),
        // 替代 stringResource(R.string.cancel): commonMain 走 rememberString("cancel")
        cancelButton = AlertButton(text = rememberString("cancel")),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            // SV 面板
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f)
                    .clip(RoundedCornerShape(4.dp))
                    .pointerInput(hue) {
                        detectTapGestures { pos ->
                            sat = (pos.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                            applyHsv()
                        }
                    }
                    .pointerInput(hue) {
                        detectDragGestures { change, _ ->
                            sat = (change.position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            applyHsv()
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val hueColor = Color(ColorUtils.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    // 选点指示
                    drawCircle(
                        color = Color.White,
                        radius = 8f,
                        center = Offset(sat * size.width, (1f - value) * size.height),
                        style = Stroke(width = 3f),
                    )
                }
            }
            // Hue 滑条
            GradientSlider(
                fraction = hue / 360f,
                brush = Brush.horizontalGradient(HueColors),
                onChange = { hue = (it * 360f).coerceIn(0f, 360f); applyHsv() },
                modifier = Modifier.padding(top = 12.dp),
            )
            // Alpha 滑条（可选）
            if (showAlphaSlider) {
                val opaque = Color(ColorUtils.HSVToColor(floatArrayOf(hue, sat, value)))
                GradientSlider(
                    fraction = alpha,
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, opaque)),
                    onChange = { alpha = it; applyHsv() },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // hex 输入
            AppOutlinedTextField(
                value = hex,
                onValueChange = { input ->
                    hex = input
                    // 替代 android.graphics.Color.parseColor: commonMain 用纯 Kotlin ColorUtils.parseColor
                    runCatching { ColorUtils.parseColor(input) }.getOrNull()?.let { c ->
                        val out = FloatArray(3)
                        ColorUtils.colorToHSV(c, out)
                        hue = out[0]; sat = out[1]; value = out[2]
                        if (showAlphaSlider) alpha = ColorUtils.alpha(c) / 255f
                    }
                },
                singleLine = true,
                label = "Hex",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            // 预设色格
            LazyVerticalGrid(
                columns = GridCells.Adaptive(44.dp),
                modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presets) { preset ->
                    val selected = ColorUtils.stripAlpha(preset) == ColorUtils.stripAlpha(current())
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(preset))
                            .border(if (selected) 2.dp else 1.dp, colors.secondaryText, CircleShape)
                            .clickable {
                                val out = FloatArray(3)
                                ColorUtils.colorToHSV(preset, out)
                                hue = out[0]; sat = out[1]; value = out[2]
                                if (showAlphaSlider) alpha = ColorUtils.alpha(preset) / 255f
                                applyHsv()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                // 替代 painterResource(R.drawable.ic_check): commonMain 走 rememberPainter("ic_check")
                                painter = rememberPainter("ic_check"),
                                contentDescription = null,
                                tint = if (ColorUtils.isColorLight(preset)) Color.Black else Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 通用渐变滑条：track 走 brush，thumb 随 fraction 移动 */
@Composable
private fun GradientSlider(
    fraction: Float,
    brush: Brush,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(brush)
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, Color.Gray, CircleShape),
            )
        }
    }
}

private val HueColors = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/** 对齐 ColorPickerDialog.MATERIAL_COLORS 的预设范围（material 500 系） */
val MaterialPresets = listOf(
    0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5,
    0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688, 0xFF4CAF50,
    0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800,
    0xFFFF5722, 0xFF795548, 0xFF9E9E9E, 0xFF607D8B,
).map { it.toInt() }
