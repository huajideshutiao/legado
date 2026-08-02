package io.legado.app.ui.book.read.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.background
import legado.shared.generated.resources.close
import legado.shared.generated.resources.line_size
import legado.shared.generated.resources.page_anim_cover
import legado.shared.generated.resources.page_anim_none
import legado.shared.generated.resources.page_anim_slide
import legado.shared.generated.resources.page_turn
import legado.shared.generated.resources.paragraph_size
import legado.shared.generated.resources.read_config
import legado.shared.generated.resources.text_size
import org.jetbrains.compose.resources.stringResource

/**
 * 桌面端阅读配置面板 (KP2-D P1)。
 *
 * # 对照
 *
 * 对照 app 端 `io.legado.app.ui.book.read.config.ReadStyleDialog`:
 * - app 端用 BottomSheetDialog + LazyRow 样式列表 + 多个 SeekBar,
 *   桌面端 P1 简化为 AlertDialog + 5 个配置项 (字号/行距/段距/背景色/翻页模式)
 * - 配置值通过 [LocalReadConfigProviders] 读写 [ReadBookConfigShared]
 * - 翻页模式切换通过 [onPageAnimChange] 回调通知宿主切换 pageDelegate
 *
 * # 配置项
 *
 * - **字号** (12-36 sp): 直接对应 [ReadBookConfigShared.textSize]
 * - **行距** (1.0-2.0): 映射到 [ReadBookConfigShared.lineSpacingExtra] (整数 10-20)
 *   显示时 /10 得浮点值, 与 app 端 `lineSize` slider `(it-10)/10f` 公式一致 (P1 范围 1.0-2.0)
 * - **段距** (0.5-2.0): 映射到 [ReadBookConfigShared.paragraphSpacing] (整数 1-4)
 *   显示时 /2 得浮点值, 与 app 端 paragraphSpacing 等比缩放
 * - **背景色** (白/黄/绿/黑 4 预设): 写入 [ReadStyleConfig] 的
 *   bgStr/bgStrNight/textColorStr/textColorStrNight/textColor/bgMeanColor
 *   (含夜间字段, 对照原版每套默认主题都有 bgStrNight/textColorStrNight, P1 简化为 4 个固定预设)
 * - **翻页模式** (覆盖/滑动/无动画): 写入 [ReadBookConfigShared.pageAnim] +
 *   通过 [onPageAnimChange] 回调让宿主切换 pageDelegate
 *
 * # 持久化
 *
 * 简单值 (textSize/lineSpacingExtra/paragraphSpacing/pageAnim) 走 configList (内存),
 * 面板关闭时经 DisposableEffect onDispose 统一 [ReadBookConfigShared.save] 落盘
 * (对照原版 ReadStyleDialog.onDismiss -> ReadBookConfig.save);
 * 背景色预设选择后除 onDispose 外还立即 save + postConfig (重启不丢, 阅读页即时刷新)。
 *
 * # 即时生效范围 (P1 简化)
 *
 * - 翻页模式: 即时切换 pageDelegate (通过 [onPageAnimChange] 回调)
 * - 背景色: 选择即 save + postConfig, 阅读页即时重绘
 * - 字号/行距/段距: 仅持久化, 下次启动 ReaderScreen 时由
 *   `io.legado.desktop.ui.reader.ReaderScreen` 读取 readBookConfig 初始化
 *   viewModel/LayoutConfig 后生效 (与任务约束 "启动时读取配置初始化" 一致)
 *
 * @param onDismissRequest 关闭回调
 * @param onPageAnimChange 翻页模式切换回调, 参数为 [PageAnim.Anim] 值;
 *   宿主负责销毁旧 delegate + 创建新 delegate
 *   (覆盖 → CoverPageDelegate, 滑动 → SlidePageDelegate, 无动画 → null)
 */
@Composable
fun ReadConfigPanel(
    onDismissRequest: () -> Unit,
    onPageAnimChange: (Int) -> Unit,
) {
    val providers = LocalReadConfigProviders.current
    val readBookConfig = providers.readBookConfig

    // 本地状态: 启动时从 readBookConfig 读取, 修改时同步写回 (configList 内存,
    // 关闭时 onDispose save 统一落盘)
    var textSize by remember { mutableIntStateOf(readBookConfig.textSize.coerceIn(12, 36)) }
    var lineSpacing by remember { mutableIntStateOf(readBookConfig.lineSpacingExtra.coerceIn(10, 20)) }
    var paragraphSpacing by remember { mutableIntStateOf(readBookConfig.paragraphSpacing.coerceIn(1, 4)) }
    var pageAnim by remember { mutableIntStateOf(readBookConfig.pageAnim) }

    // 当前背景色预设索引 (匹配当前 config.bgStr, 未匹配回退到 0=白色)
    var bgPresetIndex by remember {
        mutableIntStateOf(
            BG_PRESETS.indexOfFirst { it.bgStr == readBookConfig.config.bgStr }.coerceAtLeast(0)
        )
    }

    // 翻页模式 dropdown 是否展开
    var pageAnimMenuExpanded by remember { mutableStateOf(false) }

    AppAlertDialog(
        onDismissRequest = onDismissRequest,
        title = stringResource(Res.string.read_config),
        content = {
            // 字号 (12-36 sp)
            AppDetailSeekBar(
                title = stringResource(Res.string.text_size),
                value = textSize,
                min = 12,
                max = 36,
                valueFormat = { "${it}sp" },
                onChanged = {
                    textSize = it
                    readBookConfig.textSize = it
                },
            )
            // 行距 (内部 10-20, 显示 1.0-2.0)
            AppDetailSeekBar(
                title = stringResource(Res.string.line_size),
                value = lineSpacing,
                min = 10,
                max = 20,
                valueFormat = { (it / 10f).toString() },
                onChanged = {
                    lineSpacing = it
                    readBookConfig.lineSpacingExtra = it
                },
            )
            // 段距 (内部 1-4, 显示 0.5-2.0)
            AppDetailSeekBar(
                title = stringResource(Res.string.paragraph_size),
                value = paragraphSpacing,
                min = 1,
                max = 4,
                valueFormat = { (it / 2f).toString() },
                onChanged = {
                    paragraphSpacing = it
                    readBookConfig.paragraphSpacing = it
                },
            )
            // 背景色 4 预设
            BackgroundColorRow(
                selectedIndex = bgPresetIndex,
                onSelect = { index ->
                    bgPresetIndex = index
                    val preset = BG_PRESETS[index]
                    val cfg = readBookConfig.configList[0]
                    // 预设整包写日/夜两套背景+文字色并同步 Int 缓存 (对照原版每套样式含
                    // bgStrNight/textColorStrNight), 避免夜间主题残留上一套颜色
                    cfg.setPresetColor(
                        bgStr = preset.bgStr,
                        bgStrNight = preset.bgStrNight,
                        textColorStr = preset.textColorStr,
                        textColorStrNight = preset.textColorStrNight,
                        textColor = preset.textColor,
                        textColorNight = preset.textColorNight,
                        bgMeanColor = preset.bgMeanColor,
                    )
                    // 选择即落盘 (save 异步写 readConfig.json, 重启不丢; 对照原版 onDismiss -> save)
                    readBookConfig.save()
                    // 背景/文字色都变了: 对照原版 BgTextConfig 取色 TEXT_COLOR [2,6,9,11] + BG [1]
                    ReadBookEvents.postConfig(
                        ReadConfigChange.BG,
                        ReadConfigChange.STYLE,
                        ReadConfigChange.UP_CONTENT,
                        ReadConfigChange.INVALIDATE_TEXT_PAGE,
                        ReadConfigChange.RENDER_TASK,
                    )
                },
            )
            // 翻页模式
            PageAnimRow(
                currentPageAnim = pageAnim,
                menuExpanded = pageAnimMenuExpanded,
                onMenuExpandedChange = { pageAnimMenuExpanded = it },
                onSelect = { anim ->
                    pageAnim = anim
                    readBookConfig.pageAnim = anim
                    onPageAnimChange(anim)
                },
            )
        },
        okButton = AlertButton(stringResource(Res.string.close)) {
            onDismissRequest()
        },
    )

    // 关闭时统一落盘 (对照原版 ReadStyleDialog.onDismiss -> ReadBookConfig.save,
    // 覆盖字号/行距/段距/翻页模式等所有面板改动)
    DisposableEffect(Unit) {
        onDispose { readBookConfig.save() }
    }
}

/**
 * 背景色预设行: 4 个圆形色块 (白/黄/绿/黑), 点击切换 [ReadStyleConfig] 的
 * bgStr/bgStrNight/textColorStr/textColorStrNight/textColor/bgMeanColor。
 *
 * 对照 app 端 ReadStyleDialog 的 LazyRow 样式列表 (configList), 桌面端 P1 简化为 4 个固定预设,
 * 不支持自定义颜色 / 图片背景 (留待 P2)。
 */
@Composable
private fun BackgroundColorRow(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.background),
            color = colors.primaryText,
            modifier = Modifier.padding(end = 16.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        BG_PRESETS.forEachIndexed { index, preset ->
            val isSelected = index == selectedIndex
            // 选中态: 28dp + accent 边框; 未选中: 24dp + secondaryText 边框
            val sizeDp = if (isSelected) 28.dp else 24.dp
            val borderColor = if (isSelected) colors.accent else colors.secondaryText
            val borderWidth = if (isSelected) 2.dp else 1.dp
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(sizeDp)
                    .background(Color(preset.bgMeanColor), CircleShape)
                    .border(borderWidth, borderColor, CircleShape)
                    .clickable { onSelect(index) },
            )
        }
    }
}

/**
 * 翻页模式行: 标签 + 当前值按钮 + 下拉菜单 (覆盖/滑动/无动画)。
 *
 * 对照 app 端 ReadStyleDialog 的 pageAnim SegmentChip, 桌面端用 DropdownMenu 节省横向空间。
 */
@Composable
private fun PageAnimRow(
    currentPageAnim: Int,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    // 翻页模式标签走 rememberString 缓存 (Composable 顶层一次性求值,
    // PAGE_ANIM_OPTIONS 仅存 key, 渲染时映射为本地标签)
    val coverLabel = stringResource(Res.string.page_anim_cover)
    val slideLabel = stringResource(Res.string.page_anim_slide)
    val noneLabel = stringResource(Res.string.page_anim_none)
    val labels = remember(coverLabel, slideLabel, noneLabel) {
        mapOf(
            PageAnim.coverPageAnim to coverLabel,
            PageAnim.slidePageAnim to slideLabel,
            PageAnim.noAnim to noneLabel,
        )
    }
    val currentLabel = labels[currentPageAnim] ?: noneLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.page_turn),
            color = colors.primaryText,
            modifier = Modifier.padding(end = 16.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Box {
            AppTextButton(
                text = currentLabel,
                onClick = { onMenuExpandedChange(!menuExpanded) },
            )
            AppDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuExpandedChange(false) },
            ) {
                PAGE_ANIM_OPTIONS.forEach { (anim, _) ->
                    DropdownMenuItem(
                        onClick = {
                            onSelect(anim)
                            onMenuExpandedChange(false)
                        },
                    ) {
                        Text(labels[anim] ?: noneLabel)
                    }
                }
            }
        }
    }
}

/** 翻页模式可选项 (与 [PageAnim] 常量对应, value 为 rememberString key) */
private val PAGE_ANIM_OPTIONS: List<Pair<Int, String>> = listOf(
    PageAnim.coverPageAnim to "page_anim_cover",
    PageAnim.slidePageAnim to "page_anim_slide",
    PageAnim.noAnim to "page_anim_none",
)

/**
 * 背景色预设 (4 个, 对照 app 端 ReadBookConfig.configList 默认 5 主题简化):
 * - 白: #FFFFFF / 文字 #3E3D3B
 * - 黄: #F5DEB3 (wheat 护眼黄) / 文字 #5B4636
 * - 绿: #C7EDCC (护眼绿) / 文字 #3E3D3B
 * - 黑: #000000 / 文字 #ADADAD
 * 每个预设含白天/夜间两套背景+文字色 (对照原版默认主题均有 bgStrNight/textColorStrNight;
 * 夜间值取 app 端默认主题口径: 深灰底 #3C3F43 + 亮色文字)。
 *
 * @param bgStr 白天背景颜色 hex 字符串 (写入 [ReadStyleConfig.bgStr])
 * @param bgStrNight 夜间背景颜色 hex 字符串 (写入 [ReadStyleConfig.bgStrNight])
 * @param textColorStr 白天文字颜色 hex 字符串 (写入 [ReadStyleConfig.textColorStr])
 * @param textColorStrNight 夜间文字颜色 hex 字符串 (写入 [ReadStyleConfig.textColorStrNight])
 * @param textColor 白天文字颜色 Int ARGB (写入 [ReadStyleConfig.textColor],
 *   由 PageViewComposable 用 Color(textColor) 渲染)
 * @param textColorNight 夜间文字颜色 Int ARGB (同步 [ReadStyleConfig] 夜间 Int 缓存)
 * @param bgMeanColor 背景颜色 Int ARGB (写入 [ReadStyleConfig.bgMeanColor],
 *   由 PageViewComposable 用 Color(bgMeanColor) 渲染背景)
 */
private data class BgPreset(
    val bgStr: String,
    val bgStrNight: String,
    val textColorStr: String,
    val textColorStrNight: String,
    val textColor: Int,
    val textColorNight: Int,
    val bgMeanColor: Int,
)

private val BG_PRESETS: List<BgPreset> = listOf(
    BgPreset(
        "#FFFFFF",
        "#000000",
        "#3E3D3B",
        "#ADADAD",
        0xFF3E3D3B.toInt(),
        0xFFADADAD.toInt(),
        0xFFFFFFFF.toInt()
    ),
    BgPreset(
        "#F5DEB3",
        "#3C3F43",
        "#5B4636",
        "#DCDFE1",
        0xFF5B4636.toInt(),
        0xFFDCDFE1.toInt(),
        0xFFF5DEB3.toInt()
    ),
    BgPreset(
        "#C7EDCC",
        "#3C3F43",
        "#3E3D3B",
        "#88C16F",
        0xFF3E3D3B.toInt(),
        0xFF88C16F.toInt(),
        0xFFC7EDCC.toInt()
    ),
    BgPreset(
        "#000000",
        "#000000",
        "#ADADAD",
        "#ADADAD",
        0xFFADADAD.toInt(),
        0xFFADADAD.toInt(),
        0xFF000000.toInt()
    ),
)
