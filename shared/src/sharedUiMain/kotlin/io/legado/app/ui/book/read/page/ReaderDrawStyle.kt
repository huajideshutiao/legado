package io.legado.app.ui.book.read.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.root.PlatformCapabilityProviders

/**
 * 阅读画布的样式快照，对应 app 端 `TextStyleProvider.upStyle` 产出的
 * titlePaint / contentPaint / reviewPaint 三套 Paint 加 `ReadBookConfig` 的颜色项。
 *
 * @param letterSpacingEm 字距（em，与 Android `Paint.letterSpacing` 同口径），
 *        画布按 `fontSizePx * letterSpacingEm` 折算像素补偿量。
 */
@Immutable
data class ReaderDrawStyle(
    val contentStyle: TextStyle,
    val titleStyle: TextStyle,
    val letterSpacingEm: Float,
    val textColor: Color,
    val accentColor: Color,
    val selectedColor: Color,
    val searchColor: Color,
    val reviewColor: Color,
    val reviewTextSize: TextUnit,
    /** 不透明底层色；图片背景在其上按 [backgroundImageAlpha] 叠加。 */
    val bgColor: Color,
    /** 当前生效图片背景地址，纯色背景为 null。 */
    val backgroundImageSource: String?,
    /** 图片背景透明度（0..1），对应 ReadBookConfig.bgAlpha。 */
    val backgroundImageAlpha: Float,
    val tipColor: Color,
    val underline: Boolean,
    val isEInk: Boolean,
)

/**
 * 读取 [ReadBookConfigShared] 构建 [ReaderDrawStyle]，并订阅 [ReadBookEvents.configChange]
 * 在样式类事件到达时重建（对应 app 端 ReadBookActivity 收到 UP_CONFIG 后调 upStyle + invalidate）。
 */
@Composable
fun rememberReaderDrawStyle(): ReaderDrawStyle {
    val providers = LocalReadConfigProviders.current
    val readBookConfig = providers.readBookConfig
    val readTipConfig = providers.readTipConfig
    val accentColor = AppTheme.colors.accent
    val isEInk = LocalEInk.current

    var styleVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ReadBookEvents.configChange.collect { changes ->
            if (changes.any { it in redrawChanges }) styleVersion++
        }
    }

    // 字体文件读盘只在路径变化时做一次，样式版本变动不重复加载
    val fontPath = remember(styleVersion, readBookConfig) { readBookConfig.textFont }
    val fontFamily = remember(fontPath) {
        if (fontPath.isEmpty()) null else loadReaderFontFamily(fontPath)
    }

    return remember(styleVersion, fontFamily, accentColor, isEInk, readBookConfig, readTipConfig) {
        buildReaderDrawStyle(readBookConfig, readTipConfig, fontFamily, accentColor, isEInk)
    }
}

/** 触发画布重建的事件集合，与 app 端 ReadBookActivity 里走 upStyle / invalidate 的分支对齐。 */
private val redrawChanges = setOf(
    ReadConfigChange.BG,
    ReadConfigChange.BG_ALPHA,
    ReadConfigChange.STYLE,
    ReadConfigChange.CHAPTER_STYLE,
    ReadConfigChange.CHAPTER_LAYOUT,
    ReadConfigChange.LOAD_CONTENT,
    ReadConfigChange.UP_CONTENT,
    ReadConfigChange.INVALIDATE_TEXT_PAGE,
)

private fun buildReaderDrawStyle(
    readBookConfig: ReadBookConfigShared,
    readTipConfig: ReadTipConfigShared,
    fontFamily: FontFamily?,
    accentColor: Color,
    isEInk: Boolean,
): ReaderDrawStyle {
    val textColor = Color(readBookConfig.textColor)
    // 与 app 端 getPaints 一致：0 标题粗/正文常规，1 标题 900/正文粗，2 标题常规/正文 300
    val (titleWeight, contentWeight) = when (readBookConfig.textBold) {
        1 -> FontWeight.W900 to FontWeight.Bold
        2 -> FontWeight.Normal to FontWeight.W300
        else -> FontWeight.Bold to FontWeight.Normal
    }
    val letterSpacing = readBookConfig.letterSpacing
    val contentSize = readBookConfig.textSize.sp
    val titleSize = (readBookConfig.textSize + readBookConfig.titleSize).sp
    val contentStyle = TextStyle(
        color = textColor,
        fontSize = contentSize,
        fontWeight = contentWeight,
        fontFamily = fontFamily,
        // Android Paint.letterSpacing 是字号倍数，Compose 对应单位是 em
        letterSpacing = letterSpacing.em,
    )
    return ReaderDrawStyle(
        contentStyle = contentStyle,
        titleStyle = contentStyle.copy(fontSize = titleSize, fontWeight = titleWeight),
        letterSpacingEm = letterSpacing,
        textColor = textColor,
        accentColor = accentColor,
        // 长按选中高亮: 动态主题色 (accent) + 透明度处理 (用户需求 2026-08-04,
        // 原版 btn_bg_press_2 固定黑色 0x20 透明度, 现跟随日夜主题)
        selectedColor = accentColor.copy(alpha = 0.25f),
        // 原版搜索结果只换文字色（accentColor），高亮底色沿用 accent 弱化值
        searchColor = accentColor.copy(alpha = 0.25f),
        // 与 app 端 reviewPaint 一致：正文色 60% 透明度 + 0.45 倍字号
        reviewColor = textColor.copy(alpha = 0.6f),
        reviewTextSize = contentSize * 0.45f,
        // 纯色背景按 bgStr + bgAlpha 折算；图片背景使用不透明代表色作为底层，
        // 实际图片在 PageViewComposable 中按 bgAlpha 叠加（对应 Android upBg 的 LayerDrawable）。
        bgColor = Color(readBookConfig.config.curBgColor()).copy(alpha = 1f),
        backgroundImageSource = readBookConfig.config.curBgImageSource(),
        backgroundImageAlpha = (readBookConfig.bgAlpha / 100f).coerceIn(0f, 1f),
        tipColor = Color(if (readTipConfig.tipColor == 0) readBookConfig.textColor else readTipConfig.tipColor),
        underline = readBookConfig.underline,
        isEInk = isEInk,
    )
}


/**
 * 页眉是否可见（对照原版 PageView.upTipStyle 的 `llHeader.isGone` 判定）：
 * - headerMode=1: 恒显
 * - headerMode=2: 恒隐
 * - headerMode=0（默认）: 有系统栏平台（Android/iOS/ohos）仅当"隐藏状态栏"开启时显示
 *   （状态栏显示时隐藏，跟随沉浸联动，对照原版 `else -> !ReadBookConfig.hideStatusBar`，
 *   与菜单显隐无关）；无系统栏平台（桌面）状态栏恒"不显示"，按原版沉浸语义
 *   （状态栏隐藏 → 页眉顶替显示时间/电量/章节）退化为恒显。
 *
 * 渲染侧（PageViewComposable 页眉子节点显隐）唯一判定；页眉高度不再由公式预留，
 * 显隐只控制布局子节点是否组合，高度由布局系统实测后反哺排版（单一来源）。
 */
fun headerTipVisible(headerMode: Int, hideStatusBar: Boolean): Boolean = when (headerMode) {
    1 -> true
    2 -> false
    else -> {
        // 无系统栏平台（桌面）：hideStatusBar 无入口可改且恒 false，状态栏恒不显示 →
        // headerMode=0 退化为恒显（原版语义：状态栏隐藏时页眉顶替显示）。
        // 未注册 capabilities（如 @Preview）按有系统栏处理，与 MoreConfigScreen 约定一致，
        // 移动端三态行为不变。
        val hasSystemBars = PlatformCapabilityProviders.getOrNull()?.hasSystemBars() ?: true
        !hasSystemBars || hideStatusBar
    }
}

/** 页眉/页脚 tip 文本字号（sp），与 PageViewComposable 的 TipSlot 渲染字号一致。 */
const val READ_TIP_TEXT_SIZE_SP = 12
