package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.ReadStyleActions
import io.legado.app.ui.book.read.config.ReadStyleController
import io.legado.app.ui.book.read.config.ReadStyleScreen as SharedReadStyleScreen
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ColorUtils

/**
 * 桌面端"阅读样式配置"对话框入口（包装 shared/sharedUiMain 的 [SharedReadStyleScreen]）。
 *
 * # 职责
 *
 * - 用 [AppAlertDialog] 包裹 [SharedReadStyleScreen]，提供标题"阅读样式" + 关闭按钮
 * - 装配桌面版 [ReadStyleController]（桥接到 [ReadBookConfigShared] 各字段）
 * - 装配桌面版 [ReadStyleActions]（桥接到 padding/tip/bgText 配置入口 + 翻页动画切换回调）
 * - 装配 [bgPreviewSlot]：用 Box+Text 显示主题名作为占位（桌面端无 curBgDrawable 解码）
 *
 * # 简化项（依赖未下沉的功能用 TODO 注释 + no-op）
 *
 * - showFontSelect: 弹 [FontSelectDialog]（叠加在 ReadStyleDialog 之上，与 BgTextConfigDialog 模式一致）
 * - showChineseConverter: 桌面端无 ChineseUtils，暂 no-op（TODO）
 * - showPaddingConfig: 切到 PADDING_CONFIG 路由（由 ReaderScreen 注入回调，与现有路由切换模式一致）
 * - showTipConfig: 切到 TIP_CONFIG 路由
 * - showBgTextConfig: ReaderScreen 内部弹 [BgTextConfigDialog]（保持阅读上下文）
 * - onUpPageAnim: 销毁旧 delegate + 创建新 delegate（由 ReaderScreen 注入回调）
 * - onPostConfig: 桌面端 ReadBookEvents 未下沉，暂 no-op（TODO）
 *
 * # chineseType 字段
 *
 * [ReadStyleController.chineseType] 是 var，但 shared/commonMain 的
 * [AppConfigProviders.get().chineseConverterType] 是 val（只读），desktop 端 set 暂 no-op + TODO。
 * 实际上 [SharedReadStyleScreen] 内部不会直接写 controller.chineseType，点击简繁 SegmentChip
 * 触发 [ReadStyleActions.showChineseConverter]（已 no-op），所以 chineseType set 不会被调用。
 *
 * @param readBookConfig 阅读配置（由 ReaderScreen 注入）
 * @param callbacks 动作回调（由 ReaderScreen 注入，桥接到 padding/tip 路由 + bgText Dialog + 翻页 delegate）
 * @param onDismiss 关闭回调
 */
@Composable
fun ReadStyleDialog(
    readBookConfig: ReadBookConfigShared,
    callbacks: ReadStyleDialogCallbacks,
    onDismiss: () -> Unit,
) {
    val controller = remember(readBookConfig) { DesktopReadStyleController(readBookConfig) }
    // 字体选择对话框显隐状态（由 DesktopReadStyleActions.showFontSelect 触发）
    var showFontSelectDialog by remember { mutableStateOf(false) }
    val actions = remember(callbacks) {
        DesktopReadStyleActions(callbacks) { showFontSelectDialog = true }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("read_style"),
        widthFraction = 0.8f,
        content = {
            SharedReadStyleScreen(
                controller = controller,
                actions = actions,
                bgPreviewSlot = { config, selected, onClick, onLongClick ->
                    DesktopStylePreview(config, selected, onClick, onLongClick)
                },
            )
        },
        okButton = AlertButton(text = rememberString("close")) { onDismiss() },
    )

    // 字体选择对话框（叠加在 ReadStyleDialog 之上，与 BgTextConfigDialog 叠加模式一致）
    if (showFontSelectDialog) {
        FontSelectDialog(
            readBookConfig = readBookConfig,
            onDismiss = { showFontSelectDialog = false },
            onPostConfig = {
                // 桥接到 actions.onPostConfig（desktop 端 ReadBookEvents.postConfig 未下沉，实际 no-op）
                // 用 CHAPTER_STYLE + LOAD_CONTENT（对齐 ReadStyleScreen 中字号/行距等排版变更语义）
                actions.onPostConfig(
                    listOf(
                        ReadConfigChange.CHAPTER_STYLE,
                        ReadConfigChange.LOAD_CONTENT,
                    )
                )
            },
        )
    }
}

/**
 * 阅读样式对话框动作回调集，由 ReaderScreen 实现并注入。
 */
interface ReadStyleDialogCallbacks {
    /** 切到边距配置路由（对应 app 端 callBack.showPaddingConfig） */
    fun showPaddingConfig()
    /** 切到提示信息配置路由（对应 app 端 TipConfigDialog().show） */
    fun showTipConfig()
    /** 弹背景文字配置 Dialog（对应 app 端 callBack.showBgTextConfig(index)） */
    fun showBgTextConfig(index: Int)
    /** 翻页动画切换后刷新（对应 app 端 callBack.upPageAnim() + ReadBook.loadContent(false)） */
    fun onUpPageAnim()
}

/**
 * 桌面版样式预览：Box + 文字（主题名）作为占位。
 *
 * 桌面端无 curBgDrawable 解码 Bitmap，用主题名文字 + 边框渲染预览。
 * 尺寸严格对齐 app 端 LazyRow 项：宽 60dp、高 90dp、padding 6dp。
 *
 * @param config 样式配置
 * @param selected 是否选中（选中时 accent 边框）
 * @param onClick 点击回调（切换样式）
 * @param onLongClick 长按回调（弹 BgTextConfigDialog）
 */
@Composable
private fun DesktopStylePreview(
    config: ReadStyleConfig,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val borderColor = if (selected) colors.accent else colors.secondaryText
    val borderWidth = if (selected) 2.dp else 1.dp
    // 背景色（从 bgStr 解析，失败回退到 colors.background）
    val bgColor = remember(config.bgStr) {
        runCatching { Color(ColorUtils.parseColor(config.bgStr)) }
            .getOrDefault(colors.background)
    }
    // 文字色（从 textColorStr 解析，失败回退到 primaryText）
    val textColor = remember(config.textColorStr) {
        runCatching { Color(ColorUtils.parseColor(config.textColorStr)) }
            .getOrDefault(colors.primaryText)
    }
    // 主题名（空时显示"文字"占位，对齐 app 端 if (name.isEmpty()) "文字" else name）
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

/**
 * 桌面版 [ReadStyleController]：桥接到 [ReadBookConfigShared]。
 *
 * 字段映射（app 端 → 桌面端）：
 * - `ReadBookConfig.textBold` → [ReadBookConfigShared.textBold]
 * - `AppConfig.chineseConverterType` → [AppConfigProviders.get().chineseConverterType]（只读，set no-op）
 * - `ReadBook.pageAnim()` → [ReadBookConfigShared.pageAnim]
 * - `ReadBookConfig.shareLayout` → [ReadBookConfigShared.shareLayout]
 * - `ReadBookConfig.textSize` → [ReadBookConfigShared.textSize]
 * - `ReadBookConfig.letterSpacing` → [ReadBookConfigShared.letterSpacing]
 * - `ReadBookConfig.lineSpacingExtra` → [ReadBookConfigShared.lineSpacingExtra]
 * - `ReadBookConfig.paragraphSpacing` → [ReadBookConfigShared.paragraphSpacing]
 * - `ReadBookConfig.styleSelect` → [ReadBookConfigShared.readStyleSelect]
 * - `ReadBookConfig.paragraphIndent` → [ReadBookConfigShared.paragraphIndent]
 * - `ReadBookConfig.configList` → [ReadBookConfigShared.configList]
 * - `durConfig.curTextColor()` → 解析 durConfig.textColor / textColorStr
 * - `ReadBookConfig.configList.add(Config())` → [ReadBookConfigShared.configList].add([ReadStyleConfig])
 * - `ReadBookConfig.save()` → no-op（prefs 自动持久化）
 */
private class DesktopReadStyleController(
    private val readBookConfig: ReadBookConfigShared,
) : ReadStyleController {

    override var textBold: Int
        get() = readBookConfig.textBold
        set(value) { readBookConfig.textBold = value }

    override var chineseType: Int
        get() = AppConfigProviders.get().chineseConverterType
        set(value) {
            // TODO: shared/commonMain AppConfigAccessor.chineseConverterType 为 val（只读），
            //   desktop 端暂不支持修改简繁转换类型（点击 SegmentChip 会触发 showChineseConverter，
            //   该回调已 no-op，所以此处 set 不会被调用）
        }

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
        // prefs 自动持久化，无需显式 save
    }
}

/**
 * 桌面版 [ReadStyleActions]：桥接到 [ReadStyleDialogCallbacks]。
 *
 * # 简化项
 *
 * - showFontSelect: 触发 [onShowFontSelect] 弹出 [FontSelectDialog]（由 [ReadStyleDialog] 持有状态）
 * - showChineseConverter: 桌面端无 ChineseUtils，暂 no-op（TODO）
 * - onPostConfig: 桌面端 ReadBookEvents 未下沉，暂 no-op（TODO）
 */
private class DesktopReadStyleActions(
    private val callbacks: ReadStyleDialogCallbacks,
    private val onShowFontSelect: () -> Unit,
) : ReadStyleActions {

    override fun showFontSelect() {
        // 弹字体选择对话框（状态由 ReadStyleDialog 持有，叠加在 ReadStyleDialog 之上）
        onShowFontSelect()
    }

    override fun showChineseConverter() {
        // TODO: 桌面端无 ChineseUtils，暂 no-op
    }

    override fun showPaddingConfig() = callbacks.showPaddingConfig()
    override fun showTipConfig() = callbacks.showTipConfig()
    override fun showBgTextConfig(index: Int) = callbacks.showBgTextConfig(index)
    override fun onUpPageAnim() = callbacks.onUpPageAnim()

    override fun onPostConfig(changes: List<ReadConfigChange>) {
        // TODO: 桌面端 ReadBookEvents.postConfig 未下沉，暂不刷新阅读页渲染
    }
}
