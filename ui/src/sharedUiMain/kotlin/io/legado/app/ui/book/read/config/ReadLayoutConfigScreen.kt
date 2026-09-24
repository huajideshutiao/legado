package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
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
import io.legado.app.ui.book.read.page.TITLE_SIZE_EXTRA_SP
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.hexString
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.body_title
import legado.ui.generated.resources.divider_line
import legado.ui.generated.resources.footer
import legado.ui.generated.resources.header
import legado.ui.generated.resources.header_footer
import legado.ui.generated.resources.hide
import legado.ui.generated.resources.hide_when_status_bar_show
import legado.ui.generated.resources.left
import legado.ui.generated.resources.main_body
import legado.ui.generated.resources.middle
import legado.ui.generated.resources.padding_bottom
import legado.ui.generated.resources.padding_left
import legado.ui.generated.resources.padding_right
import legado.ui.generated.resources.padding_top
import legado.ui.generated.resources.read_tip
import legado.ui.generated.resources.right
import legado.ui.generated.resources.show
import legado.ui.generated.resources.text_color
import legado.ui.generated.resources.tip_color
import legado.ui.generated.resources.tip_divider_color
import legado.ui.generated.resources.title_center
import legado.ui.generated.resources.title_font_size
import legado.ui.generated.resources.title_hide
import legado.ui.generated.resources.title_left
import legado.ui.generated.resources.title_margin_bottom
import legado.ui.generated.resources.title_margin_top
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * Tip 配置控制器：把 app 端 `ReadBookConfig` / `ReadTipConfig` 的字段读写抽象为接口，
 * 让 shared Composable 不直接依赖 app 端 `object`，由 app 端 thin wrapper 实现并桥接。
 *
 * 字段命名与 app 端 `ReadBookConfig` / `ReadTipConfig` 完全一致，方便 wrapper 直接转发。
 */
interface TipConfigController {
    /** 正文字号（sp）：标题字号滑条换算真实 sp 显示用（标题 = textSize + titleSize + TITLE_SIZE_EXTRA_SP） */
    val textSize: Int
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
 * 边距配置控制器：把 app 端 `ReadBookConfig` 的边距/分隔线字段读写抽象为接口，
 * 由 app 端 thin wrapper 实现并桥接到 `ReadBookConfig`。
 *
 * 字段命名与 app 端 `ReadBookConfig` 完全一致，方便 wrapper 直接转发。
 */
interface PaddingConfigController {
    var showHeaderLine: Boolean
    var showFooterLine: Boolean
    var headerPaddingTop: Int
    var headerPaddingBottom: Int
    var headerPaddingLeft: Int
    var headerPaddingRight: Int
    var paddingTop: Int
    var paddingBottom: Int
    var paddingLeft: Int
    var paddingRight: Int
    var footerPaddingTop: Int
    var footerPaddingBottom: Int
    var footerPaddingLeft: Int
    var footerPaddingRight: Int
}

/**
 * 版面配置 Screen 正文（原 TipConfigScreen + PaddingConfigScreen 两对话框合并）：
 * 正文标题（模式/字号/上下边距）+ 页眉（信息位左中右一行 + 分隔线开关 + 四边距）+
 * 页脚（同页眉，显示状态为开关）+ 正文边距 + 提示色（文字/分隔线颜色）。
 *
 * 页眉/正文/页脚边距即时写 [paddingController] 并 postConfig 刷新渲染，
 * 边距配置走 STYLE（仅样式刷新），正文边距走 CHAPTER_LAYOUT + LOAD_CONTENT（重排）；
 * 标题/信息位/提示色即时写 [tipController] 并 postConfig。
 *
 * @param tipController 标题/信息位/提示色读写桥接
 * @param paddingController 边距/分隔线读写桥接
 * @param onPostConfig 配置变更通知（对应 `ReadBookEvents.postConfig(changes)`）
 */
@Composable
fun ReadLayoutConfigScreen(
    tipController: TipConfigController,
    paddingController: PaddingConfigController,
    onPostConfig: (List<ReadConfigChange>) -> Unit,
) {
    val colors = AppTheme.colors
    val styleOnly = listOf(ReadConfigChange.STYLE)
    val layoutAndLoad = listOf(ReadConfigChange.CHAPTER_LAYOUT, ReadConfigChange.LOAD_CONTENT)

    // 标题模式：原实现保证 titleMode 在 0..2 范围
    var titleMode by remember {
        if (tipController.titleMode !in 0..2) tipController.titleMode = 0
        mutableIntStateOf(tipController.titleMode)
    }
    var titleSize by remember { mutableIntStateOf(tipController.titleSize) }
    var titleTop by remember { mutableIntStateOf(tipController.titleTop) }
    var titleBottom by remember { mutableIntStateOf(tipController.titleBottom) }

    // 页眉显示模式映射（替代原 ReadTipConfig.getHeaderModes(context)）
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
    val titleLeftLabel = stringResource(Res.string.title_left)
    val titleCenterLabel = stringResource(Res.string.title_center)
    val titleHideLabel = stringResource(Res.string.title_hide)
    val titleModes = remember(titleLeftLabel, titleCenterLabel, titleHideLabel) {
        linkedMapOf(
            0 to titleLeftLabel,
            1 to titleCenterLabel,
            2 to titleHideLabel,
        )
    }
    val tipNames = stringArrayResource(Res.array.read_tip)
    val tipColorNames = stringArrayResource(Res.array.tip_color)
    val tipDividerColorNames = stringArrayResource(Res.array.tip_divider_color)

    // 值文本：越界回退到"无"名，颜色 0 走名称首项否则 hex，分隔线 -1/0 走名称否则 hex
    fun tipText(value: Int): String {
        return tipNames.getOrElse(ReadTipConfigShared.tipValues.indexOf(value)) {
            tipNames[ReadTipConfigShared.none]
        }
    }

    fun tipColorText(): String {
        val c = tipController.tipColor
        return if (c == 0) tipColorNames.first() else "#${c.hexString}"
    }

    fun tipDividerText(): String {
        return when (val v = tipController.tipDividerColor) {
            -1, 0 -> tipDividerColorNames[v + 1]
            else -> "#${v.hexString}"
        }
    }

    var headerMode by remember { mutableIntStateOf(tipController.headerMode) }
    var footerMode by remember { mutableIntStateOf(tipController.footerMode) }
    var headerLeftText by remember { mutableStateOf(tipText(tipController.tipHeaderLeft)) }
    var headerMiddleText by remember { mutableStateOf(tipText(tipController.tipHeaderMiddle)) }
    var headerRightText by remember { mutableStateOf(tipText(tipController.tipHeaderRight)) }
    var footerLeftText by remember { mutableStateOf(tipText(tipController.tipFooterLeft)) }
    var footerMiddleText by remember { mutableStateOf(tipText(tipController.tipFooterMiddle)) }
    var footerRightText by remember { mutableStateOf(tipText(tipController.tipFooterRight)) }
    var tipColorLabel by remember { mutableStateOf(tipColorText()) }
    var tipDividerLabel by remember { mutableStateOf(tipDividerText()) }
    // 分隔线开关必须经 remember 状态回写: checked 直读 paddingController (普通对象,
    // 非 Compose 可观察) 时点击只写配置不触发重组, 复选框视觉不动
    var showHeaderLine by remember { mutableStateOf(paddingController.showHeaderLine) }
    var showFooterLine by remember { mutableStateOf(paddingController.showFooterLine) }

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
        if (tipController.tipHeaderLeft == repeat) {
            tipController.tipHeaderLeft = none; headerLeftText = noneName
        }
        if (tipController.tipHeaderMiddle == repeat) {
            tipController.tipHeaderMiddle = none; headerMiddleText = noneName
        }
        if (tipController.tipHeaderRight == repeat) {
            tipController.tipHeaderRight = none; headerRightText = noneName
        }
        if (tipController.tipFooterLeft == repeat) {
            tipController.tipFooterLeft = none; footerLeftText = noneName
        }
        if (tipController.tipFooterMiddle == repeat) {
            tipController.tipFooterMiddle = none; footerMiddleText = noneName
        }
        if (tipController.tipFooterRight == repeat) {
            tipController.tipFooterRight = none; footerRightText = noneName
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.spacingDefault)
            .padding(top = DesignTokens.spacingLg)
            .padding(bottom = DesignTokens.spacingDefault)
    ) {
        // 正文标题 ──────────── [模式▾]
        SectionHeader(stringResource(Res.string.body_title)) {
            StatusDropdownButton(
                label = titleModes[titleMode] ?: "",
                options = titleModes.entries.map { it.value to it.key },
                onSelect = { index ->
                    titleMode = index
                    tipController.titleMode = index
                    onPostConfig(listOf(ReadConfigChange.LOAD_CONTENT))
                },
            )
        }
        AppDetailSeekBar(
            title = stringResource(Res.string.title_font_size),
            value = titleSize,
            max = 10,
            // 存储值是增量 (0-10), 显示换算为真实标题字号 sp (口径同 ReaderDrawStyle.titleStyle)
            valueFormat = { (tipController.textSize + it + TITLE_SIZE_EXTRA_SP).toString() },
            // 外层钳高压缩滑条行 (组件默认固定 viewHeightLarge)
            modifier = Modifier.height(DesignTokens.viewHeightSmall),
            onChanged = {
                titleSize = it
                tipController.titleSize = it
                onPostConfig(styleOnly)
            },
        )
        AppDetailSeekBar(
            title = stringResource(Res.string.title_margin_top),
            value = titleTop,
            max = 50,
            modifier = Modifier.height(DesignTokens.viewHeightSmall),
            onChanged = {
                titleTop = it
                tipController.titleTop = it
                onPostConfig(styleOnly)
            },
        )
        AppDetailSeekBar(
            title = stringResource(Res.string.title_margin_bottom),
            value = titleBottom,
            max = 50,
            modifier = Modifier.height(DesignTokens.viewHeightSmall),
            onChanged = {
                titleBottom = it
                tipController.titleBottom = it
                onPostConfig(styleOnly)
            },
        )

        // 页眉 ──────────── [显示▾] ☑分隔线
        SectionHeader(stringResource(Res.string.header)) {
            StatusDropdownButton(
                label = headerModes[headerMode] ?: "",
                options = headerModes.entries.map { it.value to it.key },
                onSelect = { key ->
                    headerMode = key
                    tipController.headerMode = key
                    onPostConfig(styleOnly)
                },
            )
            LineCheckbox(stringResource(Res.string.divider_line), showHeaderLine) {
                showHeaderLine = it
                paddingController.showHeaderLine = it
                onPostConfig(styleOnly)
            }
        }
        TipPositionsRow(
            left = headerLeftText,
            middle = headerMiddleText,
            right = headerRightText,
            onLeftClick = {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    tipController.tipHeaderLeft = tipValue
                    headerLeftText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            },
            onMiddleClick = {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    tipController.tipHeaderMiddle = tipValue
                    headerMiddleText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            },
            onRightClick = {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    tipController.tipHeaderRight = tipValue
                    headerRightText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            },
        )
        PaddingSeekBar(stringResource(Res.string.padding_top), 50, { paddingController.headerPaddingTop }, {
            paddingController.headerPaddingTop = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_bottom), 50, { paddingController.headerPaddingBottom }, {
            paddingController.headerPaddingBottom = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_left), 50, { paddingController.headerPaddingLeft }, {
            paddingController.headerPaddingLeft = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_right), 50, { paddingController.headerPaddingRight }, {
            paddingController.headerPaddingRight = it
        }, styleOnly, onPostConfig)

        // 页脚 ──────────── ☑显示 ☑分隔线 (两态开关, 无对话框)
        SectionHeader(stringResource(Res.string.footer)) {
            LineCheckbox(
                showLabel,
                footerMode == 0,
            ) {
                footerMode = if (it) 0 else 1
                tipController.footerMode = footerMode
                onPostConfig(styleOnly)
            }
            LineCheckbox(stringResource(Res.string.divider_line), showFooterLine) {
                showFooterLine = it
                paddingController.showFooterLine = it
                onPostConfig(styleOnly)
            }
        }
        TipPositionsRow(
            left = footerLeftText,
            middle = footerMiddleText,
            right = footerRightText,
            onLeftClick = {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    tipController.tipFooterLeft = tipValue
                    footerLeftText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            },
            onMiddleClick = {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    tipController.tipFooterMiddle = tipValue
                    footerMiddleText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            },
            onRightClick = {
                showSelector(tipNames) { i ->
                    val tipValue = ReadTipConfigShared.tipValues[i]
                    clearRepeat(tipValue)
                    tipController.tipFooterRight = tipValue
                    footerRightText = tipNames[i]
                    onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.UP_CONTENT))
                }
            },
        )
        PaddingSeekBar(stringResource(Res.string.padding_top), 50, { paddingController.footerPaddingTop }, {
            paddingController.footerPaddingTop = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_bottom), 50, { paddingController.footerPaddingBottom }, {
            paddingController.footerPaddingBottom = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_left), 50, { paddingController.footerPaddingLeft }, {
            paddingController.footerPaddingLeft = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_right), 50, { paddingController.footerPaddingRight }, {
            paddingController.footerPaddingRight = it
        }, styleOnly, onPostConfig)

        // 正文 ──────────── (边距改动触发重排)
        SectionHeader(stringResource(Res.string.main_body))
        PaddingSeekBar(stringResource(Res.string.padding_top), 50, { paddingController.paddingTop }, {
            paddingController.paddingTop = it
        }, layoutAndLoad, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_bottom), 50, { paddingController.paddingBottom }, {
            paddingController.paddingBottom = it
        }, layoutAndLoad, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_left), 50, { paddingController.paddingLeft }, {
            paddingController.paddingLeft = it
        }, layoutAndLoad, onPostConfig)
        PaddingSeekBar(stringResource(Res.string.padding_right), 50, { paddingController.paddingRight }, {
            paddingController.paddingRight = it
        }, layoutAndLoad, onPostConfig)

        // 页眉页脚 ──────────── 提示色
        SectionHeader(stringResource(Res.string.header_footer))
        TipRow(stringResource(Res.string.text_color), tipColorLabel) {
            showSelector(tipColorNames) { i ->
                when (i) {
                    0 -> {
                        tipController.tipColor = 0
                        tipColorLabel = tipColorText()
                        onPostConfig(styleOnly)
                    }

                    1 -> showTipColorPicker = true
                }
            }
        }
        TipRow(stringResource(Res.string.tip_divider_color), tipDividerLabel) {
            showSelector(tipDividerColorNames) { i ->
                when (i) {
                    0, 1 -> {
                        tipController.tipDividerColor = i - 1
                        tipDividerLabel = tipDividerText()
                        onPostConfig(styleOnly)
                    }

                    2 -> showDividerColorPicker = true
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
                tipController.tipColor = color
                tipColorLabel = tipColorText()
                onPostConfig(styleOnly)
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
                tipController.tipDividerColor = color
                tipDividerLabel = tipDividerText()
                onPostConfig(styleOnly)
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

/** 分区标题行：accent 色 18sp 标题（复刻 AccentTextView）+ 尾部控件（分隔线开关/显示状态下拉） */
@Composable
private fun SectionHeader(text: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = DesignTokens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = AppTheme.colors.accent, fontSize = 18.sp, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** 分区标题行尾的显示状态下拉按钮：显示当前值，点击弹 AppDropdownMenu 单选（右对齐） */
@Composable
private fun StatusDropdownButton(
    label: String,
    options: List<Pair<String, Int>>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            label,
            color = AppTheme.colors.primaryText,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(start = DesignTokens.spacingDefault, top = DesignTokens.spacingXs, bottom = DesignTokens.spacingXs),
        )
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                ) {
                    Text(text)
                }
            }
        }
    }
}

/** 分区标题行尾的带文案开关（对照原 边距屏 LineCheckbox：文案 + AppCheckbox；size 前置钳住
 *  material Checkbox 的 48dp 最小触摸目标，避免撑高标题行；start 间距分隔同行的多个开关） */
@Composable
private fun LineCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Text(
        label,
        color = AppTheme.colors.primaryText,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = DesignTokens.spacingDefault),
    )
    AppCheckbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.padding(start = DesignTokens.spacingXs).size(22.dp),
    )
}

/** 信息位左/中/右一行：三格均分宽度，格内文案上下两行（方位名一行、当前值一行），各自点击弹 selector */
@Composable
private fun TipPositionsRow(
    left: String,
    middle: String,
    right: String,
    onLeftClick: () -> Unit,
    onMiddleClick: () -> Unit,
    onRightClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = DesignTokens.spacingXs)) {
        TipPositionCell(stringResource(Res.string.left), left, Modifier.weight(1f), onLeftClick)
        TipPositionCell(stringResource(Res.string.middle), middle, Modifier.weight(1f), onMiddleClick)
        TipPositionCell(stringResource(Res.string.right), right, Modifier.weight(1f), onRightClick)
    }
}

@Composable
private fun TipPositionCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = DesignTokens.spacingXs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = AppTheme.colors.secondaryText, fontSize = 12.sp)
        Text(value, color = AppTheme.colors.primaryText, maxLines = 1)
    }
}

/** 信息位行：标签占满左侧 + 当前值文本，整行点击弹 selector（提示色两行沿用） */
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
            modifier = Modifier.weight(1f).padding(DesignTokens.spacingDefault),
        )
        Text(
            text = value,
            color = colors.primaryText,
            modifier = Modifier.padding(DesignTokens.spacingDefault),
        )
    }
}

@Composable
private fun PaddingSeekBar(
    title: String,
    max: Int,
    getter: () -> Int,
    setter: (Int) -> Unit,
    changes: List<ReadConfigChange>,
    onPostConfig: (List<ReadConfigChange>) -> Unit,
) {
    var value by remember { mutableIntStateOf(getter()) }
    AppDetailSeekBar(
        title = title,
        value = value,
        max = max,
        // 外层钳高压缩滑条行 (组件默认固定 viewHeightLarge)
        modifier = Modifier.height(DesignTokens.viewHeightSmall),
        onChanged = {
            value = it
            setter(it)
            onPostConfig(changes)
        },
    )
}
