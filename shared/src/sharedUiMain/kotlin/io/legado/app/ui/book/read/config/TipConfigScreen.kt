package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.hexString
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.body_title
import legado.shared.generated.resources.footer
import legado.shared.generated.resources.header
import legado.shared.generated.resources.header_footer
import legado.shared.generated.resources.hide
import legado.shared.generated.resources.hide_when_status_bar_show
import legado.shared.generated.resources.left
import legado.shared.generated.resources.middle
import legado.shared.generated.resources.right
import legado.shared.generated.resources.show
import legado.shared.generated.resources.show_hide
import legado.shared.generated.resources.text_color
import legado.shared.generated.resources.tip_divider_color
import legado.shared.generated.resources.title_center
import legado.shared.generated.resources.title_font_size
import legado.shared.generated.resources.title_hide
import legado.shared.generated.resources.title_left
import legado.shared.generated.resources.title_margin_bottom
import legado.shared.generated.resources.title_margin_top
import legado.shared.generated.resources.read_tip
import legado.shared.generated.resources.tip_color
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * Tip 配置控制器：把 app 端 `ReadBookConfig` / `ReadTipConfig` 的字段读写抽象为接口，
 * 让 shared Composable 不直接依赖 app 端 `object`，由 app 端 thin wrapper 实现并桥接。
 *
 * 字段命名与 app 端 `ReadBookConfig` / `ReadTipConfig` 完全一致，方便 wrapper 直接转发。
 */
interface TipConfigController {
    var titleMode: Int
    var titleSize: Int
    var titleTop: Int
    var titleBottom: Int
    var headerMode: Int
    var footerMode: Int
    var tipHeaderLeft: Int
    var tipHeaderMiddle: Int
    var tipHeaderRight: Int
    var tipFooterLeft: Int
    var tipFooterMiddle: Int
    var tipFooterRight: Int
    var tipColor: Int
    var tipDividerColor: Int
}

/**
 * Tip 配置对话框正文 Composable（页眉页脚/标题信息位 + 提示色 + 字号/间距）。
 *
 * 下沉自 app 端 `TipConfigDialog`，原 DialogFragment 宿主由 app 端 thin wrapper 保留：
 * - `BaseComposeDialogFragment` 提供窗口/圆角/Provider 注入
 * - `dismissAllowingStateLoss` 通过 [onBack] 回调由 wrapper 桥接
 * - `ReadBookEvents.postConfig(...)` 通过 [onPostConfig] 回调由 wrapper 桥接
 * - `ReadBookConfig` / `ReadTipConfig` 读写通过 [controller] 桥接
 *
 * 资源访问替换：
 * - `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)`
 * - `ReadTipConfig.tipNames` (`R.array.read_tip`) → `stringArrayResource(Res.array.read_tip)`
 * - `ReadTipConfig.tipColorNames` (`R.array.tip_color`) → `stringArrayResource(Res.array.tip_color)`
 * - `ReadTipConfig.tipDividerColorNames` (`R.array.tip_divider_color`) → `stringArrayResource(Res.array.tip_divider_color)`
 * - `ReadTipConfig.getHeaderModes(context)` → 内部用 `rememberString` 构造 LinkedHashMap
 *
 * 行为对齐原 TipConfigDialog：选新值前把重复占用同值的其它信息位重置为"无"，
 * 即时写配置并 postConfig 刷新渲染。
 *
 * @param controller 配置读写桥接
 * @param onBack 关闭回调（对应 `dismissAllowingStateLoss`）
 * @param onPostConfig 配置变更通知（对应 `ReadBookEvents.postConfig(changes)`）
 */
@Composable
fun TipConfigScreen(
    controller: TipConfigController,
    onBack: () -> Unit,
    onPostConfig: (List<ReadConfigChange>) -> Unit,
) {
    val colors = AppTheme.colors

    // tip 名称/值表：常量在 ReadTipConfigShared.companion 中（与 app 端 ReadTipConfig 一致）
    val tipNames = stringArrayResource(Res.array.read_tip)
    val tipColorNames = stringArrayResource(Res.array.tip_color)
    val tipDividerColorNames = stringArrayResource(Res.array.tip_divider_color)

    // 页眉/页脚显示模式映射（替代原 ReadTipConfig.getHeaderModes/getFooterModes(context)）
    // rememberString 是 @Composable, 需先在外部取值再装入 Map, 不能在 remember{} 内调用
    val hideWhenStatusBarShow = stringResource(Res.string.hide_when_status_bar_show)
    val showLabel = stringResource(Res.string.show)
    val hideLabel = stringResource(Res.string.hide)
    val headerModes = remember(hideWhenStatusBarShow, showLabel, hideLabel) {
        linkedMapOf(
            0 to hideWhenStatusBarShow,
            1 to showLabel,
            2 to hideLabel,
        )
    }
    val footerModes = remember(showLabel, hideLabel) {
        linkedMapOf(
            0 to showLabel,
            1 to hideLabel,
        )
    }

    // 值文本：越界回退到"无"名，颜色 0 走名称首项否则 hex，分隔线 -1/0 走名称否则 hex
    fun tipText(value: Int): String {
        return tipNames.getOrElse(ReadTipConfigShared.tipValues.indexOf(value)) {
            tipNames[ReadTipConfigShared.none]
        }
    }

    fun tipColorText(): String {
        val c = controller.tipColor
        return if (c == 0) tipColorNames.first() else "#${c.hexString}"
    }

    fun tipDividerText(): String {
        return when (val v = controller.tipDividerColor) {
            -1, 0 -> tipDividerColorNames[v + 1]
            else -> "#${v.hexString}"
        }
    }

    // 标题模式：原实现保证 titleMode 在 0..2 范围
    var titleMode by remember {
        if (controller.titleMode !in 0..2) controller.titleMode = 0
        mutableIntStateOf(controller.titleMode)
    }
    var titleSize by remember { mutableIntStateOf(controller.titleSize) }
    var titleTop by remember { mutableIntStateOf(controller.titleTop) }
    var titleBottom by remember { mutableIntStateOf(controller.titleBottom) }

    var headerShowText by remember {
        mutableStateOf(headerModes[controller.headerMode] ?: "")
    }
    var footerShowText by remember {
        mutableStateOf(footerModes[controller.footerMode] ?: "")
    }
    var headerLeftText by remember { mutableStateOf(tipText(controller.tipHeaderLeft)) }
    var headerMiddleText by remember { mutableStateOf(tipText(controller.tipHeaderMiddle)) }
    var headerRightText by remember { mutableStateOf(tipText(controller.tipHeaderRight)) }
    var footerLeftText by remember { mutableStateOf(tipText(controller.tipFooterLeft)) }
    var footerMiddleText by remember { mutableStateOf(tipText(controller.tipFooterMiddle)) }
    var footerRightText by remember { mutableStateOf(tipText(controller.tipFooterRight)) }
    var tipColorLabel by remember { mutableStateOf(tipColorText()) }
    var tipDividerLabel by remember { mutableStateOf(tipDividerText()) }

    var showTipColorPicker by remember { mutableStateOf(false) }
    var showDividerColorPicker by remember { mutableStateOf(false) }

    // selector 状态：替代原 context.selector 命令式调用，用 AppSelectorDialog 渲染
    var selectorItems by remember { mutableStateOf<List<String>?>(null) }
    var selectorCallback by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    val showSelector: (List<String>, (Int) -> Unit) -> Unit = { items, cb ->
        selectorItems = items
        selectorCallback = cb
    }

    // 选新值前把重复占用同值的其它信息位重置为"无"，并刷新其文本
    fun clearRepeat(repeat: Int) {
        val none = ReadTipConfigShared.none
        if (repeat == none) return
        val noneName = tipNames[none]
        if (controller.tipHeaderLeft == repeat) {
            controller.tipHeaderLeft = none; headerLeftText = noneName
        }
        if (controller.tipHeaderMiddle == repeat) {
            controller.tipHeaderMiddle = none; headerMiddleText = noneName
        }
        if (controller.tipHeaderRight == repeat) {
            controller.tipHeaderRight = none; headerRightText = noneName
        }
        if (controller.tipFooterLeft == repeat) {
            controller.tipFooterLeft = none; footerLeftText = noneName
        }
        if (controller.tipFooterMiddle == repeat) {
            controller.tipFooterMiddle = none; footerMiddleText = noneName
        }
        if (controller.tipFooterRight == repeat) {
            controller.tipFooterRight = none; footerRightText = noneName
        }
    }

    fun selectTitleMode(index: Int) {
        titleMode = index
        controller.titleMode = index
        onPostConfig(listOf(ReadConfigChange.LOAD_CONTENT))
    }

    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(
            title = stringResource(Res.string.body_title),
            onBack = onBack,
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // 标题模式：左/居中/隐藏
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    stringResource(Res.string.title_left),
                    stringResource(Res.string.title_center),
                    stringResource(Res.string.title_hide),
                ).forEachIndexed { index, label ->
                    Row(
                        Modifier.clickable { selectTitleMode(index) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppRadioButton(
                            selected = titleMode == index,
                            onClick = { selectTitleMode(index) },
                        )
                        Text(label, color = colors.primaryText)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }

            val styleAndLoad =
                listOf(ReadConfigChange.CHAPTER_STYLE, ReadConfigChange.LOAD_CONTENT)
            AppDetailSeekBar(
                title = stringResource(Res.string.title_font_size),
                value = titleSize,
                max = 10,
                onChanged = {
                    titleSize = it
                    controller.titleSize = it
                    onPostConfig(styleAndLoad)
                },
            )
            AppDetailSeekBar(
                title = stringResource(Res.string.title_margin_top),
                value = titleTop,
                max = 100,
                onChanged = {
                    titleTop = it
                    controller.titleTop = it
                    onPostConfig(styleAndLoad)
                },
            )
            AppDetailSeekBar(
                title = stringResource(Res.string.title_margin_bottom),
                value = titleBottom,
                max = 100,
                onChanged = {
                    titleBottom = it
                    controller.titleBottom = it
                    onPostConfig(styleAndLoad)
                },
            )

            SectionHeader(stringResource(Res.string.header))
            TipRow(stringResource(Res.string.show_hide), headerShowText) {
                showSelector(headerModes.values.toList()) { i ->
                    controller.headerMode = headerModes.keys.toList()[i]
                    headerShowText = headerModes[controller.headerMode] ?: ""
                    onPostConfig(listOf(ReadConfigChange.STYLE))
                }
            }
            TipRow(stringResource(Res.string.left), headerLeftText) {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    controller.tipHeaderLeft = tipValue
                    headerLeftText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            }
            TipRow(stringResource(Res.string.middle), headerMiddleText) {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    controller.tipHeaderMiddle = tipValue
                    headerMiddleText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            }
            TipRow(stringResource(Res.string.right), headerRightText) {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    controller.tipHeaderRight = tipValue
                    headerRightText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            }

            SectionHeader(stringResource(Res.string.footer))
            TipRow(stringResource(Res.string.show_hide), footerShowText) {
                showSelector(footerModes.values.toList()) { i ->
                    controller.footerMode = footerModes.keys.toList()[i]
                    footerShowText = footerModes[controller.footerMode] ?: ""
                    onPostConfig(listOf(ReadConfigChange.STYLE))
                }
            }
            TipRow(stringResource(Res.string.left), footerLeftText) {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    controller.tipFooterLeft = tipValue
                    footerLeftText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            }
            TipRow(stringResource(Res.string.middle), footerMiddleText) {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    controller.tipFooterMiddle = tipValue
                    footerMiddleText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            }
            TipRow(stringResource(Res.string.right), footerRightText) {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    controller.tipFooterRight = tipValue
                    footerRightText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            }

            SectionHeader(stringResource(Res.string.header_footer))
            TipRow(stringResource(Res.string.text_color), tipColorLabel) {
                showSelector(tipColorNames) { i ->
                    when (i) {
                        0 -> {
                            controller.tipColor = 0
                            tipColorLabel = tipColorText()
                            onPostConfig(listOf(ReadConfigChange.STYLE))
                        }

                        1 -> showTipColorPicker = true
                    }
                }
            }
            TipRow(stringResource(Res.string.tip_divider_color), tipDividerLabel) {
                showSelector(tipDividerColorNames) { i ->
                    when (i) {
                        0, 1 -> {
                            controller.tipDividerColor = i - 1
                            tipDividerLabel = tipDividerText()
                            onPostConfig(listOf(ReadConfigChange.STYLE))
                        }

                        2 -> showDividerColorPicker = true
                    }
                }
            }
        }
    }

    // 取色盘 init 沿用 jaredrummler 自定义模式默认黑，确认后内联生效（原在 ReadBookActivity TIP_COLOR 分支）
    if (showTipColorPicker) {
        ColorPickerDialog(
            initColor = 0xFF000000.toInt(),
            title = stringResource(Res.string.text_color),
            showAlphaSlider = false,
            onDismissRequest = { showTipColorPicker = false },
            onConfirm = { color ->
                controller.tipColor = color
                tipColorLabel = tipColorText()
                onPostConfig(listOf(ReadConfigChange.STYLE))
            },
        )
    }
    if (showDividerColorPicker) {
        ColorPickerDialog(
            initColor = 0xFF000000.toInt(),
            title = stringResource(Res.string.tip_divider_color),
            showAlphaSlider = false,
            onDismissRequest = { showDividerColorPicker = false },
            onConfirm = { color ->
                controller.tipDividerColor = color
                tipDividerLabel = tipDividerText()
                onPostConfig(listOf(ReadConfigChange.STYLE))
            },
        )
    }

    // selector 命令式桥接：showSelector 触发后渲染 AppSelectorDialog，选项后回调并关闭
    selectorItems?.let { items ->
        val cb = selectorCallback
        AppSelectorDialog(
            onDismissRequest = {
                selectorItems = null
                selectorCallback = null
            },
            items = items,
            onItemSelected = { i ->
                cb?.invoke(i)
                selectorItems = null
                selectorCallback = null
            },
        )
    }
}

/** 分区标题：accent 色 18sp（复刻 AccentTextView） */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = AppTheme.colors.accent,
        fontSize = 18.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** 信息位行：标签占满左侧 + 当前值文本，整行点击弹 selector */
@Composable
private fun TipRow(label: String, value: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.primaryText,
            modifier = Modifier.weight(1f).padding(8.dp),
        )
        Text(
            text = value,
            color = colors.primaryText,
            modifier = Modifier.padding(8.dp),
        )
    }
}

// ===== @Preview 合并自 androidMain 的 book/read/config/ReadConfigPrefsPreviews.kt (TipConfigScreen) =====

// ===== TipConfigScreen =====


/** Preview 期 onPostConfig noop。 */
private val noopPostConfig: (List<ReadConfigChange>) -> Unit = {}

/** Preview 期 [TipConfigController] stub, 字段返回常见默认值。 */
private class PreviewTipConfigController : TipConfigController {
    override var titleMode: Int = 1
    override var titleSize: Int = 4
    override var titleTop: Int = 0
    override var titleBottom: Int = 0
    override var headerMode: Int = 1
    override var footerMode: Int = 0
    override var tipHeaderLeft: Int = 0
    override var tipHeaderMiddle: Int = 1
    override var tipHeaderRight: Int = 2
    override var tipFooterLeft: Int = 3
    override var tipFooterMiddle: Int = 4
    override var tipFooterRight: Int = 5
    override var tipColor: Int = 0
    override var tipDividerColor: Int = -1
}

@Preview
@Composable
fun TipConfigScreenPreview() = LegadoThemePreview {
    TipConfigScreen(
        controller = PreviewTipConfigController(),
        onBack = {},
        onPostConfig = noopPostConfig,
    )
}

@Preview
@Composable
fun TipConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    TipConfigScreen(
        controller = PreviewTipConfigController(),
        onBack = {},
        onPostConfig = noopPostConfig,
    )
}

