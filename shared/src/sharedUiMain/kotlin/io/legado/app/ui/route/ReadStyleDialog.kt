package io.legado.app.ui.route

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.ChineseConverterSelectorDialog
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.book.read.config.FontSelectDialog
import io.legado.app.ui.book.read.config.ReadStyleActions
import io.legado.app.ui.book.read.config.ReadStyleController
import io.legado.app.ui.book.read.config.ReadStyleScreen
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.other_folder
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

/**
 * 阅读样式设置底部弹窗形态 (对照原版 ReadStyleDialog: BaseBottomDialogFragment, 无标题栏)。
 * 由阅读菜单"界面"按钮弹起; 内嵌子配置 (边距/提示/背景文字) 在此以对话框叠层打开,
 * 不再 push 整屏路由 (对照原版 showDialogFragment<TipConfigDialog> 等)。
 */
@Composable
fun ReadStyleDialogHost(
    onDismiss: () -> Unit,
) {
    var subConfig by remember { mutableStateOf(ReadStyleSubConfig.NONE) }
    // 子弹窗（背景文字）叠层形态下配置变更的版本号：改名/换背景/换色后自增，
    // 让本弹窗的样式列表（名称/缩略图）实时刷新（对照原版弹窗形态 dismiss 后重进自然读新值）
    var styleRefresh by remember { mutableIntStateOf(0) }
    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.bottomBackground,
                modifier = Modifier.appDialogSize().padding(16.dp),
            ) {
                ReadStyleContent(
                    onShowPaddingConfig = { subConfig = ReadStyleSubConfig.PADDING },
                    onShowTipConfig = { subConfig = ReadStyleSubConfig.TIP },
                    onShowBgTextConfig = { subConfig = ReadStyleSubConfig.BG_TEXT },
                    bgTextConfigTick = styleRefresh,
                )
            }
        }
    }
    when (subConfig) {
        ReadStyleSubConfig.PADDING ->
            PaddingConfigDialogHost(onDismiss = { subConfig = ReadStyleSubConfig.NONE })

        ReadStyleSubConfig.TIP ->
            TipConfigDialogHost(onDismiss = { subConfig = ReadStyleSubConfig.NONE })

        ReadStyleSubConfig.BG_TEXT ->
            BgTextConfigDialogHost(
                onDismiss = { subConfig = ReadStyleSubConfig.NONE },
                onConfigChanged = { styleRefresh++ },
            )

        ReadStyleSubConfig.NONE -> Unit
    }
}

/** 界面设置弹窗内可叠层的子配置 (对照原版 边距/提示/背景文字 三个对话框)。 */
private enum class ReadStyleSubConfig { NONE, PADDING, TIP, BG_TEXT }

/** 阅读样式正文 (Screen + 内嵌对话框), 路由/弹窗两形态共用 */
@Composable
private fun ReadStyleContent(
    onShowPaddingConfig: () -> Unit,
    onShowTipConfig: () -> Unit,
    onShowBgTextConfig: (Int) -> Unit,
    /** 背景文字弹窗叠层形态的配置变更版本号，透传给 ReadStyleScreen 刷新样式列表 */
    bgTextConfigTick: Int = 0,
) {
    val readBookConfig = ReadBookConfigProviders.get()
    var showFontSelect by remember { mutableStateOf(false) }
    var showChineseConverter by remember { mutableStateOf(false) }
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    // 组合期捕获，供事件回调内写 fontFolder（CompositionLocal 只能在组合期读取）
    val prefs = LocalPreferenceStoreProvider.current

    // 字体列表: 平台扫描注入 (对照 app 端 FontSelectDialog.loadFontFiles; 未实现端空列表)
    var fontItems by remember { mutableStateOf(emptyList<FontItem>()) }
    fun rescanFontItems() {
        scope.launch {
            fontItems = withContext(IoDispatcher) {
                PlatformCapabilityProviders.get().scanFontItems()
            }
        }
    }
    LaunchedEffect(Unit) {
        rescanFontItems()
    }

    // 退出时持久化 (对齐 app 端 ReadStyleDialog.onDismiss -> ReadBookConfig.save())
    DisposableEffect(Unit) {
        onDispose { readBookConfig.save() }
    }

    val controller = remember {
        object : ReadStyleController {
            override var textBold: Int
                get() = readBookConfig.textBold
                set(value) {
                    readBookConfig.textBold = value
                }
            override var chineseType: Int
                get() = AppConfigProviders.get().chineseConverterType
                set(value) {
                    AppConfigProviders.get().chineseConverterType = value
                }
            override var pageAnim: Int
                get() = readBookConfig.pageAnim
                set(value) {
                    readBookConfig.pageAnim = value
                }
            override var shareLayout: Boolean
                get() = readBookConfig.shareLayout
                set(value) {
                    readBookConfig.shareLayout = value
                }
            override var textSize: Int
                get() = readBookConfig.textSize
                set(value) {
                    readBookConfig.textSize = value
                }
            override var letterSpacing: Float
                get() = readBookConfig.letterSpacing
                set(value) {
                    readBookConfig.letterSpacing = value
                }
            override var lineSpacingExtra: Int
                get() = readBookConfig.lineSpacingExtra
                set(value) {
                    readBookConfig.lineSpacingExtra = value
                }
            override var paragraphSpacing: Int
                get() = readBookConfig.paragraphSpacing
                set(value) {
                    readBookConfig.paragraphSpacing = value
                }
            override var styleSelect: Int
                get() = readBookConfig.styleSelect
                set(value) {
                    readBookConfig.styleSelect = value
                }
            override var paragraphIndent: String
                get() = readBookConfig.paragraphIndent
                set(value) {
                    readBookConfig.paragraphIndent = value
                }
            override val configList: List<ReadStyleConfig> = readBookConfig.configList
            override fun curTextColor(): Int = readBookConfig.config.curTextColor()
            override fun addStyle(): Int {
                readBookConfig.configList.add(ReadStyleConfig())
                return readBookConfig.configList.lastIndex
            }

            override fun save() = readBookConfig.save()
        }
    }

    val actions = object : ReadStyleActions {
        override fun showFontSelect() {
            showFontSelect = true
        }

        override fun showChineseConverter() {
            showChineseConverter = true
        }

        override fun showPaddingConfig() {
            onShowPaddingConfig()
        }

        override fun showTipConfig() {
            onShowTipConfig()
        }

        override fun showBgTextConfig(index: Int) {
            onShowBgTextConfig(index)
        }

        override fun onUpPageAnim() {
            // 对照 app 端 callBack.upPageAnim() + ReadBook.loadContent(false)
            ReadBookEvents.postConfig(
                ReadConfigChange.PAGE_ANIM, ReadConfigChange.LOAD_CONTENT
            )
        }

        override fun onPostConfig(changes: List<ReadConfigChange>) {
            ReadBookEvents.postConfig(changes)
        }

        override fun onPostActionBarChange() {
            // 对照原版 postEvent(UPDATE_READ_ACTION_BAR, true) → readMenu.reset()
            ReadBookEvents.postActionBarChange()
        }
    }

    ReadStyleScreen(
        controller = controller,
        actions = actions,
        bgPreviewSlot = { config, selected, onClick, onLongClick ->
            ReadStylePreviewSlot(
                config = config,
                selected = selected,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        },
        externalRefresh = bgTextConfigTick,
    )

    // 字体选择对话框 (fontItems 由平台 [PlatformCapabilityProviders] 扫描注入)
    if (showFontSelect) {
        FontSelectDialog(
            fontItems = fontItems,
            curFontPath = readBookConfig.textFont,
            // 对照 app 端 FontSelectDialog: URLDecoder.decode(curFontPath) 后再取文件名 (P3)
            curFontName = urlDecodePath(readBookConfig.textFont).substringAfterLast('/'),
            onSelectFont = { path ->
                readBookConfig.textFont = path
                actions.onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT))
            },
            onSelectDefault = {
                readBookConfig.textFont = ""
                actions.onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT))
            },
            onDismiss = { showFontSelect = false },
            // "其它目录"入口: 走平台目录选择能力 (Android SAF OpenDocumentTree / iOS·鸿蒙文档选择器),
            // 选完写 fontFolder pref + 重扫列表 (对照 app 端 FontSelectDialog.openFolder → AppConfig.fontFolder)
            topBarTrailing = {
                val services = PlatformServiceProviders.getOrNull()
                if (services != null) {
                    Text(
                        text = stringResource(Res.string.other_folder),
                        color = colors.primaryText,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    val path = withContext(IoDispatcher) {
                                        services.files.pickDirectory()
                                    } ?: return@launch
                                    prefs.putString(PreferKey.fontFolder, path)
                                    rescanFontItems()
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                    )
                }
            },
        )
    }

    // 简繁转换选择器 (自包含: 内部写 AppConfigProviders)
    if (showChineseConverter) {
        ChineseConverterSelectorDialog(
            currentType = AppConfigProviders.get().chineseConverterType,
            onChanged = {
                actions.onPostConfig(listOf(ReadConfigChange.LOAD_CONTENT))
            },
            onDismiss = { showChineseConverter = false },
        )
    }
}

/**
 * 阅读样式组合预览。
 *
 * 原版由 [ReadStyleConfig.curBgDrawable] 生成背景缩略图（颜色或真实背景图，centerCrop
 * 到 100x150）；shared UI 没有 Drawable，这里渲染真实缩略图，二级缓冲对标原版
 * curBgDrawable（缓存有原图 → 原图；否则 preview 立即显示 + 后台下载原图后切换）：
 * - 内置图（bg:// 前缀）：一级直接读 shared composeResources 的 bg_preview 缩略图
 *   （四端同一份, 本地零网络, 见 commonMain/composeResources/files/bg_preview），
 *   二级经 bg:// 加载原图（缓存命中直读，未命中下载后切换）
 * - 用户图（本地路径）：直接加载
 * 加载中/失败时回落背景代表色（[ReadStyleConfig.bgMeanColor]），避免列表退化成空槽位。
 *
 * 背景源变化（换背景）时 LaunchedEffect key 重建重新加载；上层弹窗配置变更经
 * [ReadStyleScreen.externalRefresh] 触发重组后自动取到新配置。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ReadStylePreviewSlot(
    config: ReadStyleConfig,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val bgSource = config.curBgImageSource()
    // 图片背景缩略图异步加载（仅图片背景；纯色背景 null 不加载）
    var bgBitmap by remember(config, bgSource) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bgSource) {
        if (bgSource == null) {
            bgBitmap = null
            return@LaunchedEffect
        }
        if (bgSource.startsWith("bg://")) {
            val fileName = bgSource.removePrefix("bg://")
            // 一级: shared composeResources 内置 bg_preview 缩略图立即显示 (四端本地零网络)
            bgBitmap = runCatching { Res.readBytes("files/bg_preview/$fileName") }
                .getOrNull()
                ?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
            // 二级: 原图 (bg:// 加载器内部缓存命中直读, 未命中下载), 下好切换
            ImageBitmapLoader().loadBitmap(bgSource, null, null)?.let { bgBitmap = it }
        } else {
            bgBitmap = ImageBitmapLoader().loadBitmap(bgSource, null, null)
        }
    }
    val backgroundColor = when {
        bgBitmap != null -> colors.bottomBackground // 图片铺满槽位，底色仅作加载占位
        config.curBgType() == 0 -> runCatching {
            Color(ColorUtils.parseColor(config.curBgStr()))
        }.getOrDefault(colors.bottomBackground)

        else -> config.bgMeanColor.takeIf { it != 0 }?.let(::Color)
            ?: colors.bottomBackground
    }
    val textColor = runCatching { Color(config.curTextColor()) }
        .getOrDefault(colors.primaryText)
    val borderColor = if (selected) colors.accent else textColor
    val shape = DesignTokens.shapeSm

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(backgroundColor)
                .border(
                    width = if (selected) DesignTokens.strokeMedium else DesignTokens.strokeThin,
                    color = borderColor,
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bgBitmap
            if (bmp != null) {
                // 中心裁剪铺满缩略图（对照原版 curBgDrawable 的 centerCrop 语义）
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "阅",
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Text(
            text = config.name.ifBlank { "文字" },
            color = if (selected) colors.accent else colors.primaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun urlDecodePath(input: String): String {
    if (input.isEmpty()) return input
    val bytes = ArrayList<Byte>(input.length)
    var i = 0
    while (i < input.length) {
        val c = input[i]
        if (c == '%' && i + 2 < input.length) {
            val h = hexValue(input[i + 1])
            val l = hexValue(input[i + 2])
            if (h >= 0 && l >= 0) {
                bytes.add(((h shl 4) or l).toByte())
                i += 3
                continue
            }
        }
        // 非 %XX 字符按 UTF-8 编码进字节流
        val cp = c.code
        when {
            cp < 0x80 -> bytes.add(cp.toByte())
            cp < 0x800 -> {
                bytes.add((0xC0 or (cp shr 6)).toByte())
                bytes.add((0x80 or (cp and 0x3F)).toByte())
            }

            else -> {
                bytes.add((0xE0 or (cp shr 12)).toByte())
                bytes.add((0x80 or ((cp shr 6) and 0x3F)).toByte())
                bytes.add((0x80 or (cp and 0x3F)).toByte())
            }
        }
        i++
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
