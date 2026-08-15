package io.legado.app.ui.compose.preference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.ColorUtils
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.ic_check
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
                    .border(DesignTokens.strokeThin, AppTheme.colors.secondaryText, CircleShape),
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
    AppDialog(onDismissRequest = onDismissRequest, properties = AppDialogSizes.properties()) {
        ColorPickerDialogContent(
            initColor = initColor,
            title = title,
            onDismissRequest = onDismissRequest,
            onConfirm = onConfirm,
            showAlphaSlider = showAlphaSlider,
            presets = presets,
            modifier = Modifier.appDialogSize(),
        )
    }
}

/** 取色盘正文（不含窗口），供命令式宿主 base/ComposeDialog 复用 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialogContent(
    initColor: Int,
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
    showAlphaSlider: Boolean = false,
    presets: List<Int> = MaterialPresets,
    modifier: Modifier = Modifier,
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
        // 替代 stringResource(R.string.ok): commonMain 走 stringResource(Res.string.ok)
        okButton = AlertButton(
            text = stringResource(Res.string.ok),
            onClick = { onConfirm(current()) }),
        // 替代 stringResource(R.string.cancel): commonMain 走 stringResource(Res.string.cancel)
        cancelButton = AlertButton(text = stringResource(Res.string.cancel)),
        modifier = modifier,
    ) {
        // 自适应布局（方案 C）：BoxWithConstraints 感知正文可用宽高——
        // 竖屏/窄窗(可用宽 ≤ 可用高): Column 单列(现状), 色板高度封顶防矮窗溢出;
        // 横屏/桌面(可用宽 > 可用高): Row 双列, 左列色板填满正文高, 右列控件区可滚动换行。
        BoxWithConstraints(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            val landscape = maxWidth > maxHeight
            // 色板恒保 aspectRatio 1.3 (宽高互相推导)：
            // 竖屏  高 = min(宽/1.3, 可用高×0.45)（封顶时按高推导宽并居中，防极端矮窗溢出）
            // 横屏  高 = 正文可用高, 宽 = min(高×1.3, 可用宽×0.55)
            val panelHeight = if (landscape) maxHeight
            else minOf(maxWidth / 1.3f, maxHeight * 0.45f)
            val panelWidth = if (landscape) minOf(maxHeight * 1.3f, maxWidth * 0.55f)
            else panelHeight * 1.3f

            // SV 面板（两布局共用, 尺寸由调用处 Modifier 决定）
            val svPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                Box(
                    panelModifier
                        .clip(DesignTokens.shapeSm)
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
            }

            // 控件区（Hue/Alpha/Hex/预设）：竖屏续排在色板下方, 横屏收进右列
            val controls: @Composable ColumnScope.() -> Unit = {
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
                    modifier = Modifier.fillMaxWidth(),
                )
                // 预设色格：选中色属色板时补黑色第 20 格
                // （对照 colorpicker 1.1.0 loadPresets：isMaterialColors && presets.length == 19
                //   → pushIfNotExists(black)，即选中色在 material 色板内才追加黑格）
                val currentStripped = ColorUtils.stripAlpha(current())
                val displayPresets =
                    if (presets.any { ColorUtils.stripAlpha(it) == currentStripped }) {
                        presets + 0xFF000000.toInt()
                    } else presets
                // FlowRow 按行自动换行、高度包裹内容：替换 LazyVerticalGrid 固定 height(120.dp)
                // (窄屏 3 行以上时底部色格被裁剪, 实测主题定制对话框底部固定色消失)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    displayPresets.forEach { preset ->
                        val selected = ColorUtils.stripAlpha(preset) == ColorUtils.stripAlpha(current())
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(preset))
                                .border(if (selected) DesignTokens.strokeMedium else DesignTokens.strokeThin, colors.secondaryText, CircleShape)
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
                                    // 替代 painterResource(R.drawable.ic_check): commonMain 走 painterResource(Res.drawable.ic_check)
                                    painter = painterResource(Res.drawable.ic_check),
                                    contentDescription = null,
                                    tint = if (ColorUtils.isColorLight(preset)) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (landscape) {
                // 横屏/桌面双列：左列色板填满正文高, 右列控件区可滚动(高度有限时保证完整可达)
                Row(Modifier.fillMaxWidth()) {
                    svPanel(Modifier.width(panelWidth).height(panelHeight))
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp),
                    ) { controls() }
                }
            } else {
                // 竖屏单列（现状顺序）：色板封顶收窄时居中
                Column(Modifier.fillMaxWidth()) {
                    svPanel(
                        Modifier
                            .width(panelWidth)
                            .height(panelHeight)
                            .align(Alignment.CenterHorizontally),
                    )
                    controls()
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
                    .border(DesignTokens.strokeThin, Color.Gray, CircleShape),
            )
        }
    }
}

private val HueColors = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/** 对齐 ColorPickerDialog.MATERIAL_COLORS 的预设范围（material 500 系，19 色） */
// colorpicker 1.1.0 源码：RED/PINK/LIGHT PINK(0xFFFF2C93)/PURPLE/DEEP PURPLE/INDIGO/BLUE/
// LIGHT BLUE/CYAN/TEAL/GREEN/LIGHT GREEN/LIME/YELLOW/AMBER/ORANGE/BROWN/BLUE GREY/GREY
// （黑色第 20 格由对话框按"选中色属色板"动态追加，见 ColorPickerDialogContent）
val MaterialPresets = listOf(
    0xFFF44336, 0xFFE91E63, 0xFFFF2C93, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5,
    0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688, 0xFF4CAF50,
    0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800,
    0xFF795548, 0xFF607D8B, 0xFF9E9E9E,
).map { it.toInt() }
