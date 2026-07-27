package io.legado.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.help.image.pickImage
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.BgImageItem
import io.legado.app.ui.book.read.config.BgTextConfigActions
import io.legado.app.ui.book.read.config.BgTextConfigController
import io.legado.app.ui.book.read.config.BgTextConfigScreen as SharedBgTextConfigScreen
import io.legado.app.ui.book.read.config.ChineseConverterSelectorDialog
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.book.read.config.FontSelectDialog
import io.legado.app.ui.book.read.config.PaddingConfigController
import io.legado.app.ui.book.read.config.PaddingConfigScreen as SharedPaddingConfigScreen
import io.legado.app.ui.book.read.config.ReadStyleActions
import io.legado.app.ui.book.read.config.ReadStyleController
import io.legado.app.ui.book.read.config.ReadStyleScreen as SharedReadStyleScreen
import io.legado.app.ui.book.read.config.TipConfigController
import io.legado.app.ui.book.read.config.TipConfigScreen as SharedTipConfigScreen
import io.legado.app.ui.book.read.config.listIosFontFiles
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.TransType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * iOS 端"阅读样式配置"对话框入口 (包装 shared/sharedUiMain 的 [SharedReadStyleScreen])。
 *
 * # 职责
 *
 * - 用 [AppAlertDialog] 包裹 [SharedReadStyleScreen], 提供标题"阅读样式" + 关闭按钮
 * - 装配 iOS 版 [IosReadStyleController] (桥接到 [ReadBookConfigShared] 各字段)
 * - 装配 iOS 版 [IosReadStyleActions] (桥接到 padding/tip/bgText/font/chineseConverter 子 Dialog + 翻页动画切换回调)
 * - 装配 [bgPreviewSlot]: 用 Box+Text 显示主题名作为占位 (iOS 端无 curBgDrawable 解码)
 * - 内联托管 5 个子 Dialog: BgTextConfig / PaddingConfig / TipConfig / FontSelect / ChineseConverter (由 actions 触发显隐)
 *
 * # 简化项
 *
 * - onSelectBgImage: iOS 端用 PHPickerViewController 选图 (见 [io.legado.app.help.image.pickImage])
 *
 * 对照桌面端 `desktop/src/main/kotlin/io/legado/desktop/ui/book/read/config/ReadStyleDialog.kt`,
 * iOS 端把子 Dialog 内联托管 (桌面端把 BgTextConfig hoist 到 ReaderScreen, padding/tip 走路由切换)。
 *
 * @param readBookConfig 阅读配置 (由 IosReaderScreen 注入, 来自 LocalReadConfigProviders)
 * @param isImageBook 是否图片书籍 (控制 BgTextConfig 是否显示下划线开关)
 * @param onUpPageAnim 翻页动画切换后刷新回调 (由 IosReaderScreen 注入, 销毁旧 delegate + 创建新 delegate)
 * @param onDismiss 关闭回调
 */
@Composable
fun IosReadStyleDialog(
    readBookConfig: ReadBookConfigShared,
    isImageBook: Boolean,
    onUpPageAnim: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 子 Dialog 显隐状态 (由 ReadStyleActions 触发)
    var showBgTextConfig by remember { mutableStateOf(false) }
    var showPaddingConfig by remember { mutableStateOf(false) }
    var showTipConfig by remember { mutableStateOf(false) }
    var showFontSelectDialog by remember { mutableStateOf(false) }
    var showChineseConverterDialog by remember { mutableStateOf(false) }

    // 字体列表 (showFontSelectDialog 打开时异步扫描 Documents/font)
    var fontItems by remember { mutableStateOf<List<FontItem>>(emptyList()) }
    LaunchedEffect(showFontSelectDialog) {
        if (showFontSelectDialog) {
            fontItems = listIosFontFiles()
        }
    }

    val controller = remember(readBookConfig) { IosReadStyleController(readBookConfig) }
    val actions = remember(onUpPageAnim) {
        IosReadStyleActions(
            onUpPageAnim = onUpPageAnim,
            onShowBgTextConfig = { showBgTextConfig = true },
            onShowPaddingConfig = { showPaddingConfig = true },
            onShowTipConfig = { showTipConfig = true },
            onShowFontSelect = { showFontSelectDialog = true },
            onShowChineseConverter = { showChineseConverterDialog = true },
        )
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("read_style"),
        content = {
            SharedReadStyleScreen(
                controller = controller,
                actions = actions,
                bgPreviewSlot = { config, selected, onClick, onLongClick ->
                    IosStylePreview(config, selected, onClick, onLongClick)
                },
            )
        },
        okButton = AlertButton(rememberString("close")) { onDismiss() },
    )

    // 子 Dialog: 背景文字配置 (ReadStyleScreen 内长按样式项触发)
    if (showBgTextConfig) {
        IosBgTextConfigDialog(
            readBookConfig = readBookConfig,
            isImageBook = isImageBook,
            onDismiss = { showBgTextConfig = false },
        )
    }

    // 子 Dialog: 边距配置 (ReadStyleScreen 顶部"边距"按钮触发)
    if (showPaddingConfig) {
        AppAlertDialog(
            onDismissRequest = { showPaddingConfig = false },
            title = rememberString("padding_config"),
            content = {
                SharedPaddingConfigScreen(
                    controller = remember(readBookConfig) { IosPaddingConfigController(readBookConfig) },
                    onPostConfig = { changes ->
                        ReadBookEvents.postConfig(changes)
                    },
                )
            },
            okButton = AlertButton(rememberString("close")) { showPaddingConfig = false },
        )
    }

    // 子 Dialog: 提示信息配置 (ReadStyleScreen 顶部"信息"按钮触发)
    // TipConfigScreen 自带 DialogTitleBar, 用 Dialog+Surface 包裹避免双重标题
    if (showTipConfig) {
        Dialog(
            onDismissRequest = { showTipConfig = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxWidth()) {
                SharedTipConfigScreen(
                    controller = remember(readBookConfig) { IosTipConfigController(readBookConfig) },
                    onBack = { showTipConfig = false },
                    onPostConfig = { changes ->
                        ReadBookEvents.postConfig(changes)
                    },
                )
            }
        }
    }

    // 子 Dialog: 字体选择 (ReadStyleScreen 顶部"字体"按钮触发)
    if (showFontSelectDialog) {
        val curFontPath = readBookConfig.textFont
        FontSelectDialog(
            fontItems = fontItems,
            curFontPath = curFontPath,
            curFontName = curFontPath.substringAfterLast('/'),
            onSelectFont = { path ->
                // 对齐 app 端 FontSelectDialog.CallBack.selectFont
                if (path != curFontPath || path.isEmpty()) {
                    readBookConfig.textFont = path
                    ReadBookEvents.postConfig(ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT)
                }
            },
            onSelectDefault = {
                // 对齐 app 端 selectFont("") — 清空字体路径
                if (curFontPath.isNotEmpty()) {
                    readBookConfig.textFont = ""
                    ReadBookEvents.postConfig(ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT)
                }
            },
            onDismiss = { showFontSelectDialog = false },
        )
    }

    // 子 Dialog: 简繁转换选择 (ReadStyleScreen 简繁 SegmentChip 触发)
    if (showChineseConverterDialog) {
        ChineseConverterSelectorDialog(
            currentType = AppConfigProviders.get().chineseConverterType,
            onChanged = {
                // 对齐 app 端 showConverterSelector 回调: 卸载字典 + 刷新排版
                ChineseUtils.unLoad(*TransType.entries.toTypedArray())
                ReadBookEvents.postConfig(ReadConfigChange.LOAD_CONTENT)
            },
            onDismiss = { showChineseConverterDialog = false },
        )
    }
}

// ====================================================================================================
// ReadStyleController / ReadStyleActions 实现
// ====================================================================================================

/**
 * iOS 版 [ReadStyleController]: 桥接到 [ReadBookConfigShared]。
 *
 * 字段映射与桌面端 `DesktopReadStyleController` 一致, 见桌面端 KDoc。
 */
private class IosReadStyleController(
    private val readBookConfig: ReadBookConfigShared,
) : ReadStyleController {

    override var textBold: Int
        get() = readBookConfig.textBold
        set(value) { readBookConfig.textBold = value }

    override var chineseType: Int
        get() = AppConfigProviders.get().chineseConverterType
        set(value) { AppConfigProviders.get().chineseConverterType = value }

    override var pageAnim: Int
        get() = readBookConfig.pageAnim
        set(value) { readBookConfig.pageAnim = value }

    override var shareLayout: Boolean
        get() = readBookConfig.shareLayout
        set(value) { readBookConfig.shareLayout = value }

    override var textSize: Int
        get() = readBookConfig.textSize
        set(value) { readBookConfig.textSize = value }

    override var letterSpacing: Float
        get() = readBookConfig.letterSpacing
        set(value) { readBookConfig.letterSpacing = value }

    override var lineSpacingExtra: Int
        get() = readBookConfig.lineSpacingExtra
        set(value) { readBookConfig.lineSpacingExtra = value }

    override var paragraphSpacing: Int
        get() = readBookConfig.paragraphSpacing
        set(value) { readBookConfig.paragraphSpacing = value }

    override var styleSelect: Int
        get() = readBookConfig.readStyleSelect
        set(value) { readBookConfig.readStyleSelect = value }

    override var paragraphIndent: String
        get() = readBookConfig.paragraphIndent
        set(value) { readBookConfig.paragraphIndent = value }

    override val configList: List<ReadStyleConfig>
        get() = readBookConfig.configList

    override fun curTextColor(): Int {
        val cfg = readBookConfig.durConfig
        return if (cfg.textColor != 0) cfg.textColor
        else runCatching { ColorUtils.parseColor(cfg.textColorStr) }.getOrDefault(0xFF3E3D3B.toInt())
    }

    override fun addStyle() {
        readBookConfig.configList.add(ReadStyleConfig())
    }

    override fun save() {
        // prefs 自动持久化, 无需显式 save
    }
}

/**
 * iOS 版 [ReadStyleActions]: 桥接到子 Dialog 显隐状态 + 翻页动画切换回调。
 *
 * - showFontSelect: 触发 [FontSelectDialog] 显隐状态 (字体列表由 Composable 的 LaunchedEffect 扫描)
 * - showChineseConverter: 触发 [ChineseConverterSelectorDialog] 显隐状态
 * - onPostConfig: 通过 [ReadBookEvents.postConfig] 发布配置变更事件 (下沉到 commonMain)
 */
private class IosReadStyleActions(
    private val onUpPageAnim: () -> Unit,
    private val onShowBgTextConfig: () -> Unit,
    private val onShowPaddingConfig: () -> Unit,
    private val onShowTipConfig: () -> Unit,
    private val onShowFontSelect: () -> Unit,
    private val onShowChineseConverter: () -> Unit,
) : ReadStyleActions {

    override fun showFontSelect() = onShowFontSelect()

    override fun showChineseConverter() = onShowChineseConverter()

    override fun showPaddingConfig() = onShowPaddingConfig()
    override fun showTipConfig() = onShowTipConfig()
    override fun showBgTextConfig(index: Int) = onShowBgTextConfig()
    override fun onUpPageAnim() = onUpPageAnim()

    override fun onPostConfig(changes: List<ReadConfigChange>) {
        ReadBookEvents.postConfig(changes)
    }
}

/**
 * iOS 版样式预览: Box + 文字 (主题名) 作为占位。
 *
 * iOS 端无 curBgDrawable 解码 Bitmap, 用主题名文字 + 边框渲染预览。
 * 尺寸严格对齐桌面端 `DesktopStylePreview`: 宽 60dp、高 90dp、padding 6dp。
 */
@Composable
private fun IosStylePreview(
    config: ReadStyleConfig,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val borderColor = if (selected) colors.accent else colors.secondaryText
    val borderWidth = if (selected) 2.dp else 1.dp
    // 背景色 (从 bgStr 解析, 失败回退到 colors.background)
    val bgColor = remember(config.bgStr) {
        runCatching { Color(ColorUtils.parseColor(config.bgStr)) }
            .getOrDefault(colors.background)
    }
    // 文字色 (从 textColorStr 解析, 失败回退到 primaryText)
    val textColor = remember(config.textColorStr) {
        runCatching { Color(ColorUtils.parseColor(config.textColorStr)) }
            .getOrDefault(colors.primaryText)
    }
    // 主题名 (空时显示"文字"占位, 对齐 app 端 if (name.isEmpty()) "文字" else name)
    val displayName = config.name.ifBlank { rememberString("text") }

    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(width = 60.dp, height = 90.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayName,
            color = textColor,
            fontSize = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// ====================================================================================================
// BgTextConfig 子 Dialog (背景文字配置, ReadStyleScreen 内长按样式项触发)
// ====================================================================================================

/**
 * iOS 端"背景文字配置"子 Dialog (包装 shared/sharedUiMain 的 [SharedBgTextConfigScreen])。
 *
 * 用 [AppAlertDialog] 包裹, 提供标题"背景与文字" + 关闭按钮。
 * 装配 [IosBgTextConfigController] / [IosBgTextConfigActions] / [IosBgImagePreview]。
 */
@Composable
private fun IosBgTextConfigDialog(
    readBookConfig: ReadBookConfigShared,
    isImageBook: Boolean,
    onDismiss: () -> Unit,
) {
    val controller = remember(readBookConfig) { IosBgTextConfigController(readBookConfig) }
    val scope = rememberCoroutineScope()
    val actions = remember(readBookConfig, scope) { IosBgTextConfigActions(readBookConfig, scope) }
    // iOS 端无 assets 预设背景图, bgImageList 为空 (与桌面端一致)
    val bgImageList: List<BgImageItem> = remember { emptyList() }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("text_bg_style"),
        content = {
            SharedBgTextConfigScreen(
                controller = controller,
                actions = actions,
                isImageBook = isImageBook,
                bgImageList = bgImageList,
                bgImagePreviewSlot = { item, onClick ->
                    IosBgImagePreview(item, onClick)
                },
            )
        },
        okButton = AlertButton(rememberString("close")) { onDismiss() },
    )
}

/**
 * iOS 版 [BgTextConfigController]: 桥接到 [ReadBookConfigShared.durConfig] 各字段。
 *
 * 字段映射与桌面端 `DesktopBgTextConfigController` 一致。
 */
private class IosBgTextConfigController(
    private val readBookConfig: ReadBookConfigShared,
) : BgTextConfigController {

    override var name: String
        get() = readBookConfig.durConfig.name
        set(value) { readBookConfig.durConfig.name = value }

    override fun darkStatusIcon(): Boolean = readBookConfig.durConfig.darkStatusIcon

    override fun setCurStatusIconDark(value: Boolean) {
        readBookConfig.durConfig.darkStatusIcon = value
    }

    override fun underline(): Boolean = readBookConfig.underline

    override fun setUnderline(value: Boolean) {
        readBookConfig.underline = value
    }

    override fun bgAlpha(): Int = readBookConfig.bgAlpha

    override fun setBgAlpha(value: Int) {
        readBookConfig.bgAlpha = value
    }

    override fun curTextColor(): Int {
        val cfg = readBookConfig.durConfig
        // textColor 默认 0 时从 textColorStr 解析 (对齐 app 端 curTextColor 逻辑)
        return if (cfg.textColor != 0) cfg.textColor
        else runCatching { ColorUtils.parseColor(cfg.textColorStr) }.getOrDefault(0xFF3E3D3B.toInt())
    }

    override fun curBgType(): Int = readBookConfig.durConfig.bgType

    override fun curBgStr(): String = readBookConfig.durConfig.bgStr

    override fun setCurTextColor(color: Int) {
        val cfg = readBookConfig.durConfig
        cfg.textColor = color
        cfg.textColorStr = ColorUtils.intToString(color)
    }

    override fun setCurBg(type: Int, value: String) {
        val cfg = readBookConfig.durConfig
        cfg.bgType = type
        cfg.bgStr = value
    }

    override fun deleteDur(): Boolean = readBookConfig.deleteDur()

    override fun save() {
        // prefs 自动持久化, 无需显式 save
    }

    override fun restorePresetNames(): List<String> = listOf("Default")

    override fun restorePreset(index: Int) {
        // iOS 端无 DefaultData 下沉, 仅重置当前 durConfig 为默认值 (与桌面端一致)
        val cfg = readBookConfig.durConfig
        cfg.name = ""
        cfg.bgStr = "#EEEEEE"
        cfg.bgStrNight = "#000000"
        cfg.bgStrEInk = "#FFFFFF"
        cfg.bgType = 0
        cfg.bgTypeNight = 0
        cfg.bgTypeEInk = 0
        cfg.darkStatusIcon = true
        cfg.darkStatusIconNight = false
        cfg.darkStatusIconEInk = true
        cfg.textColorStr = "#3E3D3B"
        cfg.textColorStrNight = "#ADADAD"
        cfg.textColorStrEInk = "#000000"
        cfg.textColor = 0
        cfg.bgMeanColor = 0
    }
}

/**
 * iOS 版 [BgTextConfigActions]。
 *
 * # 简化项
 *
 * - onImportConfig / onExportConfig: zip 解压/打包逻辑未下沉, 暂 no-op (TODO)
 * - onImportNetConfig: RemoteAssetsUtils 未下沉, 暂 no-op (TODO)
 * - onSelectBgImage: 用 PHPickerViewController 选图 (见 [pickImage]), 拷贝到 Documents/bg/
 *   后写入 bgType=2 + bgStr=路径 (对齐桌面端 FileDialog 选图写法)
 * - onSelectBgPreset: bgImageList 为空, 此回调不会被触发 (no-op)
 */
private class IosBgTextConfigActions(
    private val readBookConfig: ReadBookConfigShared,
    private val scope: CoroutineScope,
) : BgTextConfigActions {

    override fun onImportConfig() {
        // TODO: iOS 端 zip 解压逻辑未下沉, 暂 no-op
    }

    override fun onExportConfig() {
        // TODO: iOS 端 zip 打包逻辑未下沉, 暂 no-op
    }

    override fun onImportNetConfig() {
        // TODO: iOS 端 RemoteAssetsUtils 未下沉, 暂 no-op
    }

    override fun onSelectBgImage() {
        // PHPicker 选图, 返回持久化 NSURL 后写 bgType=2 (其它图片) + bgStr=路径
        scope.launch {
            val url = pickImage() ?: return@launch
            val path = url.path ?: return@launch
            readBookConfig.durConfig.bgType = 2
            readBookConfig.durConfig.bgStr = path
        }
    }

    override fun onSelectBgPreset(fileName: String) {
        // bgImageList 为空, 此回调不会被触发 (no-op)
    }

    override fun onPostConfig(changes: List<ReadConfigChange>) {
        ReadBookEvents.postConfig(changes)
    }
}

/**
 * iOS 版背景图预览项: Box + 占位图标 + 标签。
 *
 * iOS 端无 RemoteAssetsUtils 解码 assets 图片为 Bitmap, 用 Box + Icon 占位渲染。
 * 尺寸严格对齐桌面端 `DesktopBgImagePreview`: 66x88dp。
 */
@Composable
private fun IosBgImagePreview(item: BgImageItem, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .size(66.dp, 88.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, colors.secondaryText, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(58.dp, 58.dp),
            contentAlignment = Alignment.Center,
        ) {
            // fileName 为空表示"选择图片"按钮 (对齐 app 端 BgItem(icon = ic_image))
            Icon(
                painter = rememberPainter("ic_image"),
                contentDescription = item.label,
                tint = colors.secondaryText,
            )
        }
        Text(
            text = item.label,
            color = colors.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

// ====================================================================================================
// PaddingConfig / TipConfig Controller 实现
// ====================================================================================================

/**
 * iOS 版 [PaddingConfigController]: 桥接到 [ReadBookConfigShared] 各边距字段。
 *
 * 字段映射 (app 端 → iOS 端):
 * - `ReadBookConfig.showHeaderLine` → [ReadBookConfigShared.showHeaderLine]
 * - `ReadBookConfig.showFooterLine` → [ReadBookConfigShared.showFooterLine]
 * - `ReadBookConfig.headerPaddingTop` → [ReadBookConfigShared.headerPaddingTop]
 * - 其余 headerPaddingXxx / paddingXxx / footerPaddingXxx 同名直转
 *
 * 与桌面端 `DesktopPaddingConfigController` 差异: iOS 端桥接到真实持久化 (prefs),
 * 桌面端用内部 state 持有不持久化 (TODO 标记)。
 */
private class IosPaddingConfigController(
    private val readBookConfig: ReadBookConfigShared,
) : PaddingConfigController {
    override var showHeaderLine: Boolean
        get() = readBookConfig.showHeaderLine
        set(value) { readBookConfig.showHeaderLine = value }
    override var showFooterLine: Boolean
        get() = readBookConfig.showFooterLine
        set(value) { readBookConfig.showFooterLine = value }
    override var headerPaddingTop: Int
        get() = readBookConfig.headerPaddingTop
        set(value) { readBookConfig.headerPaddingTop = value }
    override var headerPaddingBottom: Int
        get() = readBookConfig.headerPaddingBottom
        set(value) { readBookConfig.headerPaddingBottom = value }
    override var headerPaddingLeft: Int
        get() = readBookConfig.headerPaddingLeft
        set(value) { readBookConfig.headerPaddingLeft = value }
    override var headerPaddingRight: Int
        get() = readBookConfig.headerPaddingRight
        set(value) { readBookConfig.headerPaddingRight = value }
    override var paddingTop: Int
        get() = readBookConfig.paddingTop
        set(value) { readBookConfig.paddingTop = value }
    override var paddingBottom: Int
        get() = readBookConfig.paddingBottom
        set(value) { readBookConfig.paddingBottom = value }
    override var paddingLeft: Int
        get() = readBookConfig.paddingLeft
        set(value) { readBookConfig.paddingLeft = value }
    override var paddingRight: Int
        get() = readBookConfig.paddingRight
        set(value) { readBookConfig.paddingRight = value }
    override var footerPaddingTop: Int
        get() = readBookConfig.footerPaddingTop
        set(value) { readBookConfig.footerPaddingTop = value }
    override var footerPaddingBottom: Int
        get() = readBookConfig.footerPaddingBottom
        set(value) { readBookConfig.footerPaddingBottom = value }
    override var footerPaddingLeft: Int
        get() = readBookConfig.footerPaddingLeft
        set(value) { readBookConfig.footerPaddingLeft = value }
    override var footerPaddingRight: Int
        get() = readBookConfig.footerPaddingRight
        set(value) { readBookConfig.footerPaddingRight = value }
}

/**
 * iOS 版 [TipConfigController]: 桥接到 [ReadBookConfigShared] 各 tip 字段。
 *
 * 字段映射:
 * - `titleMode` / `titleSize` → 同名直转
 * - `titleTop` → [ReadBookConfigShared.titleTopSpacing] (app 端字段名差异)
 * - `titleBottom` → [ReadBookConfigShared.titleBottomSpacing] (app 端字段名差异)
 * - `headerMode` / `footerMode` → 同名直转
 * - `tipHeaderLeft` / `tipHeaderMiddle` / `tipHeaderRight` → 同名直转
 * - `tipFooterLeft` / `tipFooterMiddle` / `tipFooterRight` → 同名直转
 * - `tipColor` / `tipDividerColor` → 同名直转
 *
 * 与桌面端 `DesktopTipConfigController` 差异: iOS 端桥接到真实持久化 (prefs),
 * 桌面端用内部 state 持有不持久化 (TODO 标记)。
 */
private class IosTipConfigController(
    private val readBookConfig: ReadBookConfigShared,
) : TipConfigController {
    override var titleMode: Int
        get() = readBookConfig.titleMode
        set(value) { readBookConfig.titleMode = value }
    override var titleSize: Int
        get() = readBookConfig.titleSize
        set(value) { readBookConfig.titleSize = value }
    override var titleTop: Int
        get() = readBookConfig.titleTopSpacing
        set(value) { readBookConfig.titleTopSpacing = value }
    override var titleBottom: Int
        get() = readBookConfig.titleBottomSpacing
        set(value) { readBookConfig.titleBottomSpacing = value }
    override var headerMode: Int
        get() = readBookConfig.headerMode
        set(value) { readBookConfig.headerMode = value }
    override var footerMode: Int
        get() = readBookConfig.footerMode
        set(value) { readBookConfig.footerMode = value }
    override var tipHeaderLeft: Int
        get() = readBookConfig.tipHeaderLeft
        set(value) { readBookConfig.tipHeaderLeft = value }
    override var tipHeaderMiddle: Int
        get() = readBookConfig.tipHeaderMiddle
        set(value) { readBookConfig.tipHeaderMiddle = value }
    override var tipHeaderRight: Int
        get() = readBookConfig.tipHeaderRight
        set(value) { readBookConfig.tipHeaderRight = value }
    override var tipFooterLeft: Int
        get() = readBookConfig.tipFooterLeft
        set(value) { readBookConfig.tipFooterLeft = value }
    override var tipFooterMiddle: Int
        get() = readBookConfig.tipFooterMiddle
        set(value) { readBookConfig.tipFooterMiddle = value }
    override var tipFooterRight: Int
        get() = readBookConfig.tipFooterRight
        set(value) { readBookConfig.tipFooterRight = value }
    override var tipColor: Int
        get() = readBookConfig.tipColor
        set(value) { readBookConfig.tipColor = value }
    override var tipDividerColor: Int
        get() = readBookConfig.tipDividerColor
        set(value) { readBookConfig.tipDividerColor = value }
}
