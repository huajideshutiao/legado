package io.legado.app.ui.book.read.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn

/**
 * KMP 版阅读页面视图：用 Compose 替代 app 端 `PageView` (FrameLayout + ViewBookPageBinding)。
 *
 * 内部结构：`Box(background) { PageContentCanvas + HeaderTip + FooterTip + BatteryIndicator }`
 * 与 app 端 PageView 布局一一对应：
 * - 背景：从 [LocalReadConfigProviders] 取 `readBookConfig.config.bgMeanColor`，用 [Modifier.background] 渲染纯色
 * - 主内容：[PageContentCanvas] fillMaxSize
 * - 顶部 tip：[HeaderTip] 显示章节标题/时间/电池（按 [ReadTipConfigShared] 6 槽位配置）
 * - 底部 tip：[FooterTip] 显示页码/进度（按 [ReadTipConfigShared] 6 槽位配置）
 * - 电池图标：[BatteryIndicator] 显示电池百分比
 *
 * 与 app 端差异：状态栏/导航栏 padding 由调用方（桌面窗口 Composable）决定，
 * 本 Composable 不强制 statusBarsPadding（桌面端无 Android WindowInsets）。
 *
 * @param textPage 当前页内容，null 时显示加载占位
 * @param batteryLevel 电池电量 0-100，传 -1 表示不显示
 */
@Composable
fun PageViewComposable(
    textPage: TextPage?,
    modifier: Modifier = Modifier,
    batteryLevel: Int = -1,
    onClick: (TextColumn?) -> Unit = {},
    onLongClick: (TextColumn?) -> Unit = {},
) {
    val providers = LocalReadConfigProviders.current
    val readBookConfig = providers.readBookConfig
    val readTipConfig = providers.readTipConfig
    val bgColor = readBookConfig.config.bgMeanColor
    val textColor = readBookConfig.config.textColor
    val tipColor = if (readTipConfig.tipColor == 0) textColor else readTipConfig.tipColor

    Box(
        modifier = modifier
            // commonMain 无 Brush.solidColor，Modifier.background 有 Color 重载，直接传纯色
            .background(Color(bgColor))
            .fillMaxSize()
    ) {
        // 主内容
        textPage?.let {
            PageContentCanvas(
                textPage = it,
                modifier = Modifier.fillMaxSize(),
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }

        // 顶部 tip
        HeaderTip(
            textPage = textPage,
            readTipConfig = readTipConfig,
            textColor = Color(tipColor),
            batteryLevel = batteryLevel,
        )

        // 底部 tip
        FooterTip(
            textPage = textPage,
            readTipConfig = readTipConfig,
            textColor = Color(tipColor),
            batteryLevel = batteryLevel,
        )
    }
}

/**
 * 顶部 tip：对应 app 端 PageView llHeader (tvHeaderLeft/Middle/Right)。
 *
 * 按 [ReadTipConfigShared.headerMode] 决定是否显示：
 * - 0: 显示（与状态栏显示同步，桌面端无状态栏概念，按"显示"处理）
 * - 1: 显示
 * - 2: 隐藏
 */
@Composable
private fun HeaderTip(
    textPage: TextPage?,
    readTipConfig: ReadTipConfigShared,
    textColor: Color,
    batteryLevel: Int,
) {
    if (readTipConfig.headerMode == 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TipSlot(
            tipType = readTipConfig.tipHeaderLeft,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            modifier = Modifier.weight(1f),
        )
        TipSlot(
            tipType = readTipConfig.tipHeaderMiddle,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            modifier = Modifier.weight(1f),
            align = Alignment.CenterHorizontally,
        )
        TipSlot(
            tipType = readTipConfig.tipHeaderRight,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            modifier = Modifier.weight(1f),
            align = Alignment.End,
        )
    }
}

/**
 * 底部 tip：对应 app 端 PageView llFooter (tvFooterLeft/Middle/Right)。
 *
 * 按 [ReadTipConfigShared.footerMode] 决定是否显示：
 * - 0: 显示
 * - 1: 隐藏
 */
@Composable
private fun FooterTip(
    textPage: TextPage?,
    readTipConfig: ReadTipConfigShared,
    textColor: Color,
    batteryLevel: Int,
) {
    if (readTipConfig.footerMode == 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TipSlot(
            tipType = readTipConfig.tipFooterLeft,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            modifier = Modifier.weight(1f),
        )
        TipSlot(
            tipType = readTipConfig.tipFooterMiddle,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            modifier = Modifier.weight(1f),
            align = Alignment.CenterHorizontally,
        )
        TipSlot(
            tipType = readTipConfig.tipFooterRight,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            modifier = Modifier.weight(1f),
            align = Alignment.End,
        )
    }
}

/**
 * tip 槽位：按 [tipType] 常量渲染对应内容。
 *
 * 与 app 端 PageView.getTipView 对应，但用 when + Composable 替代 BatteryView 多态：
 * - [ReadTipConfigShared.none]: 空 Spacer 占位
 * - [ReadTipConfigShared.chapterTitle]: textPage.title
 * - [ReadTipConfigShared.time]: HH:mm（简化用 "HH:mm" 字符串，actual 平台可注入实际时间）
 * - [ReadTipConfigShared.battery] / [ReadTipConfigShared.batteryPercentage]: 电池图标 / 百分比文字
 * - [ReadTipConfigShared.page] / [ReadTipConfigShared.pageAndTotal]: 页码
 * - [ReadTipConfigShared.totalProgress] / [ReadTipConfigShared.totalProgress1]: 进度百分比
 * - [ReadTipConfigShared.bookName]: 书名（暂用 title 占位，actual 接入 ReadBookShared 后替换）
 * - [ReadTipConfigShared.timeBattery] / [ReadTipConfigShared.timeBatteryPercentage]: 时间 + 电池组合
 */
@Composable
private fun TipSlot(
    tipType: Int,
    textPage: TextPage?,
    textColor: Color,
    batteryLevel: Int,
    modifier: Modifier = Modifier,
    align: Alignment.Horizontal = Alignment.Start,
) {
    val textAlign = when (align) {
        Alignment.Start -> TextAlign.Start
        Alignment.Center -> TextAlign.Center
        Alignment.End -> TextAlign.End
        else -> TextAlign.Start
    }
    val text: String = when (tipType) {
        ReadTipConfigShared.none -> ""
        ReadTipConfigShared.chapterTitle -> textPage?.title ?: ""
        ReadTipConfigShared.time -> "--:--"
        ReadTipConfigShared.battery -> if (batteryLevel >= 0) "$batteryLevel%" else ""
        ReadTipConfigShared.batteryPercentage -> if (batteryLevel >= 0) "$batteryLevel%" else ""
        ReadTipConfigShared.page -> textPage?.let { "${it.index + 1}/${it.pageSize.takeIf { p -> p > 0 } ?: "-"}" } ?: ""
        ReadTipConfigShared.totalProgress -> textPage?.readProgress ?: ""
        ReadTipConfigShared.totalProgress1 -> textPage?.let { "${it.chapterIndex + 1}/${it.chapterSize.takeIf { s -> s > 0 } ?: "-"}" } ?: ""
        ReadTipConfigShared.pageAndTotal -> textPage?.let {
            val ps = it.pageSize.takeIf { p -> p > 0 } ?: "-"
            "${it.index + 1}/$ps  ${it.readProgress}"
        } ?: ""
        ReadTipConfigShared.bookName -> textPage?.title ?: ""
        ReadTipConfigShared.timeBattery -> if (batteryLevel >= 0) "--:-- $batteryLevel%" else "--:--"
        ReadTipConfigShared.timeBatteryPercentage -> if (batteryLevel >= 0) "--:-- $batteryLevel%" else "--:--"
        else -> ""
    }

    if (tipType == ReadTipConfigShared.battery && batteryLevel >= 0) {
        // 电池槽位用 BatteryIndicator 图标
        BatteryIndicator(
            batteryLevel = batteryLevel,
            color = textColor,
            modifier = modifier,
        )
    } else if (text.isNotEmpty()) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            textAlign = textAlign,
            modifier = modifier,
        )
    } else {
        Spacer(modifier = modifier)
    }
}

/**
 * 电池图标：简化版（与 app 端 BatteryView 视觉对齐）。
 *
 * app 端用自定义 View + drawable 绘制电池外形 + 液柱；
 * KMP 版用 Canvas 矩形 + Text 百分比近似，保持跨平台一致。
 */
@Composable
private fun BatteryIndicator(
    batteryLevel: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val safeLevel = batteryLevel.coerceIn(0, 100)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$safeLevel%",
            color = color,
            fontSize = 11.sp,
        )
    }
}
