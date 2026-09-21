package io.legado.app.ui.route

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadConfigDefaults
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.model.resolveBakedReadingBgSource
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
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.other_folder
import org.jetbrains.compose.resources.stringResource

/**
 * 阅读样式设置底部弹窗形态 (对照原版 ReadStyleDialog: BaseBottomDialogFragment, 无标题栏)。
 * 由阅读菜单"界面"按钮弹起; 内嵌子配置在此打开, 不再 push 整屏路由: 版面 (边距+提示信息
 * 合并)/背景文字弹窗打开期间本弹窗隐藏 (对照原版 tvPadding / showBgTextConfig 先
 * dismissAllowingStateLoss; 提示弹窗原版 tvTip 为子对话框叠层, 与边距合并后统一隐藏)。
 */
@Composable
fun ReadStyleDialogHost(
    onDismiss: () -> Unit,
) {
    var subConfig by remember { mutableStateOf(ReadStyleSubConfig.NONE) }
    // 版面/背景文字弹窗打开期间不显示本弹窗 (对照原版 tvPadding / showBgTextConfig 先
    // dismissAllowingStateLoss 再弹子对话框), 关掉后 subConfig 复位 NONE 本弹窗即恢复,
    // 恢复时整棵重组自然读到新配置 —— 显隐由 subConfig 单一状态派生, 不另设无人复位
    // 的独立开关 (重蹈"关掉后呼不出来"的覆辙)
    if (subConfig == ReadStyleSubConfig.NONE) {
        AppBottomSheetDialog(
            onDismissRequest = onDismiss,
            properties = AppDialogSizes.properties(),
        ) {
            AppTheme {
                // 原版 BaseBottomDialogFragment: 窗口 MATCH_PARENT 全宽贴底 + Gravity.BOTTOM + WRAP_CONTENT,
                // 背景 filletBackground (bottomBackground 色 + radius.default 8dp 圆角);
                // 内容横向 8dp 间距由 ReadStyleScreen 内部 padding(horizontal=spacingDefault) 提供
                // (arco_spacing_default; 原 XML root 为 lg)。
                Surface(
                    shape = DesignTokens.shapeDefault,
                    color = AppTheme.colors.bottomBackground,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ReadStyleContent(
                        onShowLayoutConfig = { subConfig = ReadStyleSubConfig.LAYOUT },
                        onShowBgTextConfig = { subConfig = ReadStyleSubConfig.BG_TEXT },
                    )
                }
            }
        }
    }
    when (subConfig) {
        // 版面弹窗 (边距+提示信息合并) 独占屏幕 (本弹窗已隐藏, 见上方 if), 关掉即恢复
        ReadStyleSubConfig.LAYOUT ->
            ReadLayoutConfigDialogHost(onDismiss = { subConfig = ReadStyleSubConfig.NONE })

        // 背景文字弹窗独占屏幕 (本弹窗已隐藏, 见上方 if), 关掉即恢复
        ReadStyleSubConfig.BG_TEXT ->
            BgTextConfigDialogHost(onDismiss = { subConfig = ReadStyleSubConfig.NONE })

        ReadStyleSubConfig.NONE -> Unit
    }
}

/** 界面设置弹窗内打开的子配置 (对照原版 边距/提示/背景文字 三个对话框, 前两者合并为版面)。 */
private enum class ReadStyleSubConfig { NONE, LAYOUT, BG_TEXT }

/** 阅读样式正文 (Screen + 内嵌对话框), 路由/弹窗两形态共用 */
@Composable
private fun ReadStyleContent(
    onShowLayoutConfig: () -> Unit,
    onShowBgTextConfig: (Int) -> Unit,
) {
    val readBookConfig = ReadBookConfigProviders.get()
    var showFontSelect by remember { mutableStateOf(false) }
    var showChineseConverter by remember { mutableStateOf(false) }
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    // 组合期捕获，供事件回调内写 fontFolder（CompositionLocal 只能在组合期读取）
    val prefs = PreferenceProviders.get()

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
                readBookConfig.configList.add(ReadConfigDefaults.newStyleFrom(readBookConfig.config))
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

        override fun showLayoutConfig() {
            onShowLayoutConfig()
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
                Text(
                    text = stringResource(Res.string.other_folder),
                    color = colors.primaryText,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                val path = withContext(IoDispatcher) {
                                    PlatformServiceProviders.get().files.pickDirectory()
                                } ?: return@launch
                                prefs.putString(PreferKey.fontFolder, path)
                                rescanFontItems()
                            }
                        }
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                )
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
 * 到 100x150）；shared UI 没有 Drawable，这里渲染真实缩略图：用户图（本地路径）优先
 * 烘焙产物加载（[resolveBakedReadingBgSource]，缺失现场重烘焙），按槽位像素采样解码。
 * 加载中/失败时回落背景代表色（[ReadStyleConfig.bgMeanColor]），避免列表退化成空槽位。
 *
 * 背景源变化（换背景）时 LaunchedEffect key 重建重新加载。
 */
@Composable
private fun ReadStylePreviewSlot(
    config: ReadStyleConfig,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val bgSource = config.curBgImageSource()
    // I4: 预览槽位实际显示尺寸 (48dp 正圆), 解码按槽位像素采样 + 进程级位图 LRU
    // (ImageBitmapLoader 内部, 同尺寸二次打开零重复解码); 1080p+ 壁纸不再全尺寸解码
    val slotPx = with(LocalDensity.current) { 48.dp.roundToPx() }.coerceAtLeast(1)
    // 图片背景缩略图异步加载（仅图片背景；纯色背景 null 不加载）
    var bgBitmap by remember(config, bgSource) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bgSource) {
        if (bgSource == null) {
            bgBitmap = null
            return@LaunchedEffect
        }
        // 优先烘焙产物 (缺失现场重烘焙, 重烘焙是整张图 CPU 活, 推到 IO 线程)
        bgBitmap = withContext(IoDispatcher) {
            ImageBitmapLoader().loadBitmap(
                resolveBakedReadingBgSource(bgSource), null, null,
                widthPx = slotPx, heightPx = slotPx,
            )
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
    // 原版 createStyleItemBinding: 48dp 方块 + 左右 margin 8dp (space.default),
    // ivStyle 为 ShapeableImageView cornerSize=size/2 (正圆) + strokeWidth 固定 1dp (仅颜色切换),
    // tvStyle 叠加在图上居中 (FrameLayout 内后添加覆盖在上层, gravity=CENTER)。
    val shape = CircleShape

    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(48.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(
                width = DesignTokens.strokeThin,
                color = borderColor,
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
        }
        // 样式名叠加居中 (原版 tvStyle 始终覆盖在图上, 选中加粗 + 文字色取当前样式)
        Text(
            text = config.name.ifBlank { "文字" },
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
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
