package io.legado.app.ui.book.read.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.component.StrokeTextChip
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 阅读样式配置控制器：把 app 端 `ReadBookConfig` 的样式字段读写抽象为接口，
 * 由 app/desktop 端 thin wrapper 实现并桥接到各自配置存储。
 *
 * 字段命名与 app 端 `ReadBookConfig` 完全一致，方便 wrapper 直接转发。
 */
interface ReadStyleController {
    /** 字重 0:正常 1:粗体 2:细体（对应 `ReadBookConfig.textBold`） */
    var textBold: Int

    /** 简繁转换类型 0:不转 1:简体 2:繁体（对应 `AppConfig.chineseConverterType`） */
    var chineseType: Int

    /** 翻页动画 [PageAnim.Anim]（对应 `ReadBook.pageAnim()`） */
    var pageAnim: Int

    /** 是否共享排版（对应 `ReadBookConfig.shareLayout`） */
    var shareLayout: Boolean

    /** 文字大小（对应 `ReadBookConfig.textSize`，5-50） */
    var textSize: Int

    /** 字间距（对应 `ReadBookConfig.letterSpacing`） */
    var letterSpacing: Float

    /** 行距（对应 `ReadBookConfig.lineSpacingExtra`，10-20） */
    var lineSpacingExtra: Int

    /** 段距（对应 `ReadBookConfig.paragraphSpacing`，0-20） */
    var paragraphSpacing: Int

    /** 当前样式索引（对应 `ReadBookConfig.styleSelect`） */
    var styleSelect: Int

    /** 段落缩进（对应 `ReadBookConfig.paragraphIndent`） */
    var paragraphIndent: String

    /** 样式主题列表（对应 `ReadBookConfig.configList`） */
    val configList: List<ReadStyleConfig>

    /** 当前文字颜色 Int（对应 `durConfig.curTextColor()`） */
    fun curTextColor(): Int

    /** 新增样式主题（对应 `ReadBookConfig.configList.add(ReadBookConfig.Config())`） */
    fun addStyle()

    /** 持久化配置（对应 `ReadBookConfig.save()`） */
    fun save()
}

/**
 * 阅读样式配置动作回调：把 app 端 Android 专属操作（字体选择 / 简繁转换 /
 * 边距配置 / 提示信息配置 / 背景文字配置 / 翻页动画切换）抽象为接口。
 *
 * app 端原实现：
 * - `FontSelectDialog` 字体选择
 * - `ChineseUtils.showConverterSelector` 简繁转换（依赖 ChineseUtils 动态加载）
 * - `PaddingConfigDialog` 边距配置（已下沉 shared `PaddingConfigScreen`）
 * - `TipConfigDialog` 提示信息配置（已下沉 shared `TipConfigScreen`）
 * - `BgTextConfigDialog` 背景文字配置（已下沉 shared `BgTextConfigScreen`）
 * - `callBack.upPageAnim()` + `ReadBook.loadContent(false)` 翻页动画切换
 *
 * shared/commonMain 无 FontSelectDialog / ChineseUtils，由 wrapper 实现。
 */
interface ReadStyleActions {
    /** 弹字体选择对话框（对应 `showDialogFragment<FontSelectDialog>()`） */
    fun showFontSelect()

    /** 弹简繁转换选择器（对应 `ChineseUtils.showConverterSelector`） */
    fun showChineseConverter()

    /** 弹边距配置（对应 `callBack.showPaddingConfig()`，已下沉 shared `PaddingConfigScreen`） */
    fun showPaddingConfig()

    /** 弹提示信息配置（对应 `TipConfigDialog().show`，已下沉 shared `TipConfigScreen`） */
    fun showTipConfig()

    /**
     * 弹背景文字配置（对应 `callBack.showBgTextConfig()`）。
     * @param index 触发的样式索引（app 端原 `showBgTextConfig(index)` 先切换样式再弹窗）
     */
    fun showBgTextConfig(index: Int)

    /** 翻页动画切换后刷新（对应 `callBack.upPageAnim()` + `ReadBook.loadContent(false)`） */
    fun onUpPageAnim()

    /** 配置变更通知（对应 `ReadBookEvents.postConfig(...)`） */
    fun onPostConfig(changes: List<ReadConfigChange>)
}

/**
 * 阅读样式配置 Screen 正文 Composable（完整版，对照 app 端 `ReadStyleDialog`）。
 *
 * 下沉自 app 端 `ReadStyleDialog`，原 DialogFragment 宿主由各平台 thin wrapper 保留：
 * - `BaseReadBottomComposeDialog` 的 BottomSheet 形式（app 端）
 * - `AppAlertDialog` 的 content 槽 / 全屏 Dialog（desktop 端）
 *
 * 资源访问替换：
 * - `stringResource(R.string.xxx)` → `rememberString("xxx")`
 * - `stringArrayResource(R.array.xxx)` → `rememberStringArray("xxx")`
 * - `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")`
 * - `ReadBookConfig.xxx` → [controller.xxx]
 * - `callBack.xxx` / `showDialogFragment<Xxx>` → [actions.xxx]
 *
 * 行为对齐原 ReadStyleDialog：
 * - 顶部按钮行：字重 SegmentChip + 字体 StrokeTextChip + 缩进 StrokeTextChip +
 *   简繁 SegmentChip + 边距 StrokeTextChip + 信息 StrokeTextChip
 * - 4 SeekBar：字号(0-45, +5 显示) / 字间距(-50~50, /100 显示) /
 *   行距(0-20, -10/10 显示) / 段距(0-20, /10 显示)
 * - 翻页动画 5 RadioChip：覆盖/滑动/仿真/滚动/无动画
 * - shareLayout 开关
 * - 样式列表 LazyRow + 新增样式项
 *
 * # 字重 / 缩进 selector
 * 直接用 shared 的 [AppSelectorDialog]，通过 `rememberStringArray("text_font_weight")` /
 * `rememberStringArray("indent")` 取 entries，无需 callback 桥接。
 *
 * # 简繁 / 字体 / 边距 / 信息 / showBgTextConfig
 * 通过 [actions] 桥接到各平台实现（ChineseUtils / FontSelectDialog 等 Android 专属）。
 *
 * # 样式列表预览
 * 原实现用 `bg.curBgDrawable(100, 150).toBitmap().asImageBitmap()` 渲染背景预览，
 * shared/commonMain 无对应 API，通过 [bgPreviewSlot] 桥接：wrapper 接收 config 渲染预览。
 *
 * @param controller 配置读写桥接
 * @param actions 动作回调桥接
 * @param bgPreviewSlot 单个样式预览渲染（config, size, selected, onClick, onLongClick）
 */
@Composable
fun ReadStyleScreen(
    controller: ReadStyleController,
    actions: ReadStyleActions,
    bgPreviewSlot: @Composable (
        config: ReadStyleConfig,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) -> Unit,
) {
    val colors = AppTheme.colors
    // 资源 key 在 Composable 顶层一次性求值
    val fontWeightTextStr = rememberString("font_weight_text")
    val textFontStr = rememberString("text_font")
    val textIndentStr = rememberString("text_indent")
    val paddingStr = rememberString("padding")
    val informationStr = rememberString("information")
    val textSizeStr = rememberString("text_size")
    val textLetterSpacingStr = rememberString("text_letter_spacing")
    val lineSizeStr = rememberString("line_size")
    val paragraphSizeStr = rememberString("paragraph_size")
    val pageAnimStr = rememberString("page_anim")
    val textBgStyleStr = rememberString("text_bg_style")
    val shareLayoutStr = rememberString("share_layout")
    val addStr = rememberString("add")
    val textStr = rememberString("text")
    // 翻页动画标签
    val pageAnimCoverStr = rememberString("page_anim_cover")
    val pageAnimSlideStr = rememberString("page_anim_slide")
    val pageAnimSimulationStr = rememberString("page_anim_simulation")
    val pageAnimScrollStr = rememberString("page_anim_scroll")
    val pageAnimNoneStr = rememberString("page_anim_none")
    // 字重 / 缩进 selector entries
    val fontWeightEntries = rememberStringArray("text_font_weight")
    val indentEntries = rememberStringArray("indent")
    // 文字色取自当前样式 (用于样式列表预览文字色, 对齐 app 端 textColor = Color(item.curTextColor()))
    val textColor = Color(controller.curTextColor())

    // 样式切换/共用布局切换后需整体重读配置（对齐原 upView）
    var refresh by remember { mutableIntStateOf(0) }
    var textBold by remember(refresh) { mutableIntStateOf(controller.textBold) }
    var chineseType by remember { mutableIntStateOf(controller.chineseType) }
    var pageAnim by remember(refresh) { mutableIntStateOf(controller.pageAnim) }
    var shareLayout by remember { mutableStateOf(controller.shareLayout) }
    var textSize by remember(refresh) { mutableIntStateOf(controller.textSize - 5) }
    var letterSpacing by remember(refresh) {
        mutableIntStateOf((controller.letterSpacing * 100).toInt() + 50)
    }
    var lineSize by remember(refresh) { mutableIntStateOf(controller.lineSpacingExtra) }
    var paragraphSpacing by remember(refresh) {
        mutableIntStateOf(controller.paragraphSpacing)
    }
    var styleSelect by remember(refresh) { mutableIntStateOf(controller.styleSelect) }
    var showTextBoldSelector by remember { mutableStateOf(false) }
    var showTextIndentSelector by remember { mutableStateOf(false) }

    fun changeBgTextConfig(index: Int) {
        if (index != controller.styleSelect) {
            controller.styleSelect = index
            styleSelect = index
            refresh++
            actions.onPostConfig(
                listOf(
                    ReadConfigChange.BG,
                    ReadConfigChange.STYLE,
                    ReadConfigChange.LOAD_CONTENT,
                )
            )
        }
    }

    fun showBgTextConfig(index: Int) {
        changeBgTextConfig(index)
        actions.showBgTextConfig(index)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // 顶部功能小按钮行
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentChip(
                segments = fontWeightTextStr,
                activeRange = mapOf(0 to (0 to 1), 1 to (2 to 3), 2 to (4 to 5))[textBold],
                textColor = colors.secondaryText,
            ) {
                showTextBoldSelector = true
            }
            Spacer(Modifier.weight(1f))
            StrokeTextChip(textFontStr, textColor = colors.secondaryText) {
                actions.showFontSelect()
            }
            Spacer(Modifier.weight(1f))
            StrokeTextChip(textIndentStr, textColor = colors.secondaryText) {
                showTextIndentSelector = true
            }
            Spacer(Modifier.weight(1f))
            SegmentChip(
                segments = "简/繁",
                activeRange = mapOf(1 to (0 to 1), 2 to (2 to 3))[chineseType],
                textColor = colors.secondaryText,
            ) {
                actions.showChineseConverter()
            }
            Spacer(Modifier.weight(1f))
            StrokeTextChip(paddingStr, textColor = colors.secondaryText) {
                actions.showPaddingConfig()
            }
            Spacer(Modifier.weight(1f))
            StrokeTextChip(informationStr, textColor = colors.secondaryText) {
                actions.showTipConfig()
            }
        }
        // 字号 SeekBar (内部 0-45, 显示 +5)
        AppDetailSeekBar(
            title = textSizeStr,
            value = textSize, max = 45, textColor = colors.primaryText,
            valueFormat = { (it + 5).toString() },
            onChanged = {
                textSize = it
                controller.textSize = it + 5
                actions.onPostConfig(
                    listOf(ReadConfigChange.CHAPTER_STYLE, ReadConfigChange.LOAD_CONTENT)
                )
            },
            modifier = Modifier.padding(top = 4.dp),
        )
        // 字间距 SeekBar (内部 0-100, 显示 (it-50)/100)
        AppDetailSeekBar(
            title = textLetterSpacingStr,
            value = letterSpacing, max = 100, textColor = colors.primaryText,
            valueFormat = { ((it - 50) / 100f).toString() },
            onChanged = {
                letterSpacing = it
                controller.letterSpacing = (it - 50) / 100f
                actions.onPostConfig(
                    listOf(ReadConfigChange.CHAPTER_STYLE, ReadConfigChange.LOAD_CONTENT)
                )
            },
        )
        // 行距 SeekBar (内部 0-20, 显示 (it-10)/10)
        AppDetailSeekBar(
            title = lineSizeStr,
            value = lineSize, max = 20, textColor = colors.primaryText,
            valueFormat = { ((it - 10) / 10f).toString() },
            onChanged = {
                lineSize = it
                controller.lineSpacingExtra = it
                actions.onPostConfig(
                    listOf(ReadConfigChange.CHAPTER_STYLE, ReadConfigChange.LOAD_CONTENT)
                )
            },
        )
        // 段距 SeekBar (内部 0-20, 显示 /10)
        AppDetailSeekBar(
            title = paragraphSizeStr,
            value = paragraphSpacing, max = 20, textColor = colors.primaryText,
            valueFormat = { (it / 10f).toString() },
            onChanged = {
                paragraphSpacing = it
                controller.paragraphSpacing = it
                actions.onPostConfig(
                    listOf(ReadConfigChange.CHAPTER_STYLE, ReadConfigChange.LOAD_CONTENT)
                )
            },
        )
        Divider()
        // 翻页动画标签
        Text(
            pageAnimStr,
            color = colors.primaryText.copy(alpha = 0.75f), fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            listOf(
                pageAnimCoverStr to PageAnim.coverPageAnim,
                pageAnimSlideStr to PageAnim.slidePageAnim,
                pageAnimSimulationStr to PageAnim.simulationPageAnim,
                pageAnimScrollStr to PageAnim.scrollPageAnim,
                pageAnimNoneStr to PageAnim.noAnim,
            ).forEach { (label, anim) ->
                RadioChip(
                    text = label,
                    checked = pageAnim == anim,
                    textColor = colors.primaryText,
                    modifier = Modifier.weight(1f).padding(4.dp),
                ) {
                    controller.pageAnim = anim
                    pageAnim = anim
                    actions.onUpPageAnim()
                }
            }
        }
        Divider()
        // shareLayout 行
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                textBgStyleStr,
                color = colors.primaryText.copy(alpha = 0.75f), fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(shareLayoutStr, color = colors.primaryText)
            AppCheckbox(
                checked = shareLayout,
                onCheckedChange = {
                    controller.shareLayout = it
                    shareLayout = it
                    refresh++
                    actions.onPostConfig(
                        listOf(
                            ReadConfigChange.BG,
                            ReadConfigChange.STYLE,
                            ReadConfigChange.LOAD_CONTENT,
                        )
                    )
                },
                modifier = Modifier.padding(start = 8.dp, end = 16.dp),
            )
        }
        // 样式列表 LazyRow
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(controller.configList.size) { index ->
                val item = controller.configList[index]
                bgPreviewSlot(
                    item,
                    styleSelect == index,
                    { changeBgTextConfig(index) },
                    { showBgTextConfig(index) },
                )
            }
            item {
                AddStyleItem(colors.primaryText) {
                    controller.addStyle()
                    showBgTextConfig(controller.configList.lastIndex)
                }
            }
        }
    }

    // 字重选择器 (对应 app 端 context.alert + items(R.array.text_font_weight))
    if (showTextBoldSelector) {
        AppSelectorDialog(
            onDismissRequest = { showTextBoldSelector = false },
            title = rememberString("text_font_weight_converter"),
            items = fontWeightEntries,
            onItemSelected = { index ->
                controller.textBold = index
                textBold = index
                actions.onPostConfig(
                    listOf(
                        ReadConfigChange.CHAPTER_STYLE,
                        ReadConfigChange.INVALIDATE_TEXT_PAGE,
                        ReadConfigChange.UP_CONTENT,
                    )
                )
            },
        )
    }

    // 缩进选择器 (对应 app 端 context.selector + items(R.array.indent))
    if (showTextIndentSelector) {
        AppSelectorDialog(
            onDismissRequest = { showTextIndentSelector = false },
            title = textIndentStr,
            items = indentEntries,
            onItemSelected = { index ->
                // 对齐 app 端: ReadBookConfig.paragraphIndent = "　".repeat(index)
                controller.paragraphIndent = "　".repeat(index)
                actions.onPostConfig(
                    listOf(ReadConfigChange.CHAPTER_STYLE, ReadConfigChange.LOAD_CONTENT)
                )
            },
        )
    }
}

/** 分隔线（复刻 app 端 Divider private fun） */
@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(0.8.dp)
            .background(AppTheme.colors.secondaryText.copy(alpha = 0.3f)),
    )
}

/**
 * 分段选择小按钮（复刻 app 端 SegmentChip）：当前段染 accent 色。
 *
 * 对照 app 端 `ReadStyleDialog.SegmentChip`：1dp 描边 4dp 圆角 + 14sp 文字，
 * activeRange 范围内文字染 accent 色，其余文字用 textColor。
 */
@Composable
private fun SegmentChip(
    segments: String,
    activeRange: Pair<Int, Int>?,
    textColor: Color,
    onClick: () -> Unit,
) {
    val accent = AppTheme.colors.accent
    val text = buildAnnotatedString {
        append(segments)
        activeRange?.let { (start, end) ->
            addStyle(SpanStyle(color = accent), start, end)
        }
    }
    val shape = RoundedCornerShape(4.dp)
    Text(
        text = text,
        color = textColor,
        fontSize = 14.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, textColor, shape)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * 新增样式项：加号圆（复刻 app 端 AddStyleItem），48dp 圆 + 1dp 描边 + 加号图标。
 *
 * 严格对齐 app 端尺寸：size=48dp, padding=6dp, border=1dp, icon=center。
 */
@Composable
private fun AddStyleItem(textColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .size(48.dp)
            .padding(6.dp)
            .clip(CircleShape)
            .border(1.dp, textColor, CircleShape)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter("ic_add"),
            contentDescription = rememberString("add"),
            tint = textColor,
        )
    }
}
