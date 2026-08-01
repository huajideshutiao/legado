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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk

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
    val bgColor: Color,
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
        selectedColor = selectedHighlightColor,
        // 原版搜索结果只换文字色（accentColor），高亮底色沿用 accent 弱化值
        searchColor = accentColor.copy(alpha = 0.25f),
        // 与 app 端 reviewPaint 一致：正文色 60% 透明度 + 0.45 倍字号
        reviewColor = textColor.copy(alpha = 0.6f),
        reviewTextSize = contentSize * 0.45f,
        // 纯色背景按 bgStr + bgAlpha 折算，图片背景用 bgMeanColor（对应 app 端 upBg 产物）
        bgColor = Color(readBookConfig.config.curBgColor()),
        tipColor = Color(if (readTipConfig.tipColor == 0) readBookConfig.textColor else readTipConfig.tipColor),
        underline = readBookConfig.underline,
        isEInk = isEInk,
    )
}

/** 选中态底色，对齐 app 端 `ContentTextView.selectedPaint` 取的 `@color/btn_bg_press_2`。 */
private val selectedHighlightColor = Color(0x20000000)
