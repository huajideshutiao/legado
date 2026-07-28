package io.legado.desktop.ui.config

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.model.BookCoverShared
import io.legado.app.model.BookCoverShared.CoverRatio
import io.legado.app.model.BookCoverShared.DefaultCoverEntry
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.utils.MD5Utils
import io.legado.desktop.ui.component.FileDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * 桌面端"封面设置" Screen 入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.config.CoverConfigScreen])。
 *
 * # 职责
 *
 * - 在 [io.legado.app.ui.config.CoverConfigScreen] 之上加 [AppTitleBar] (标题"封面设置" + 返回按钮)
 * - 装配 summary:
 *   - coverHeightSummary: 从 prefs 读取 bookshelfCoverHeight, 默认 120
 *   - dayCoverSummary / nightCoverSummary: 调 [BookCoverShared.listDefaultCovers] 计算数量,
 *     0 张显示"选择图片", 否则显示"已选 N 张" (对齐 app 端 CoverConfigHost.coverCountSummary)
 * - 装配 1 个 NumberPicker 弹窗: bookshelfCoverHeight(90..220 dp, 中性"默认"=120)
 * - 装配 onDefaultCover: 弹 [DesktopDefaultCoverDialog] (网格列表 + 添加/删除, 平台特殊行为)
 * - 装配 onRefreshCover: 触发 dataVersion 自增, 重组刷新 summary
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] /
 *   [io.legado.app.ui.config.CoverConfigScreen] 通过 LocalXxx 取依赖
 *
 * # 平台差异 (与 app 端 CoverConfigHost 对齐)
 *
 * - onCoverHeight: 范围 90..220 (app 端原 max=220, min=90), 中性"默认"=写 prefs=120
 *   写 prefs 后不发 EventBus.BOOKSHELF_REFRESH (桌面端书架重组由其他机制触发)
 * - onDefaultCover: 弹 [DesktopDefaultCoverDialog], 桌面端用 BufferedImage/ImageIO 烘焙
 *   (JDK ImageIO 不支持 webp 编码, 文件名 .webp 但内容 PNG, [ImageIO.read] 不依赖后缀)
 * - onRefreshCover: 触发 dataVersion 自增重组 summary (桌面端无全局 BookCover object 状态,
 *   不需调 upDefaultCover 更新内存 dayCovers/nightCovers/drawableCache)
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun CoverConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / io.legado.app.ui.config.CoverConfigScreen 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("cover_config"),
                        onBack = onBack,
                    )
                    CoverConfigContent()
                }
            }
        }
    }
}

/**
 * 装配 summary + 回调, 位置传参调用 [io.legado.app.ui.config.CoverConfigScreen]。
 *
 * 与 app 端 CoverConfigHost.Content 内 io.legado.app.ui.config.CoverConfigScreen(...) 调用对齐。
 */
@Composable
private fun CoverConfigContent() {
    val prefs = remember { PreferenceProviders.get() }
    val coverHeightLabel = rememberString("bookshelf_cover_height")
    val defaultLabel = rememberString("btn_default_s")
    val dpSuffix = "dp"

    // bookshelfCoverHeight 当前值 + 显隐状态 (mutableIntStateOf 让 summary 重组)
    // 默认值 120 (app 端 AppConfig 默认), 范围 90..220
    var coverHeight by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.bookshelfCoverHeight, 120))
    }
    var showCoverHeightDialog by remember { mutableStateOf(false) }
    val coverHeightSummary = coverHeight.toString() + dpSuffix

    // 默认封面摘要: 调 shared BookCoverShared.listDefaultCovers 计算数量 (纯逻辑已下沉 shared)
    // dataVersion 在增删/刷新时自增, 触发本 Composable 重组重取 summary
    var dataVersion by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION")
    dataVersion // 读 dataVersion 建立重组依赖 (jvmGetString 非 @Composable, 须显式读)
    val dayCount = BookCoverShared.listDefaultCovers(prefs, PreferKey.defaultCover).size
    val nightCount = BookCoverShared.listDefaultCovers(prefs, PreferKey.defaultCoverDark).size
    val dayCoverSummary = if (dayCount == 0) {
        jvmGetString("select_image")
    } else {
        jvmGetString("default_cover_count", dayCount)
    }
    val nightCoverSummary = if (nightCount == 0) {
        jvmGetString("select_image")
    } else {
        jvmGetString("default_cover_count", nightCount)
    }

    // 默认封面选择 Dialog 显隐状态
    var showDayCoverDialog by remember { mutableStateOf(false) }
    var showNightCoverDialog by remember { mutableStateOf(false) }

    io.legado.app.ui.config.CoverConfigScreen(
        onDefaultCover = { isNight ->
            if (isNight) showNightCoverDialog = true else showDayCoverDialog = true
        },
        onCoverHeight = { showCoverHeightDialog = true },
        coverHeightSummary = coverHeightSummary,
        dayCoverSummary = dayCoverSummary,
        nightCoverSummary = nightCoverSummary,
        onRefreshCover = {
            // 刷新: 重组刷新 summary (桌面端无全局 BookCover object 状态需更新,
            // 与 app 端 BookCover.upDefaultCover 更新内存 dayCovers/nightCovers/drawableCache 不同)
            dataVersion++
        },
    )

    // 1 个 NumberPickerDialog (shared 共享, 替代 app 端 showNumberPicker)
    // app 端范围: bookshelfCoverHeight 90..220 dp, 中性"默认"=120
    if (showCoverHeightDialog) {
        NumberPickerDialog(
            title = coverHeightLabel,
            value = coverHeight,
            range = 90..220,
            onConfirm = {
                coverHeight = it
                prefs.putInt(PreferKey.bookshelfCoverHeight, it)
                showCoverHeightDialog = false
            },
            onDismiss = { showCoverHeightDialog = false },
            neutralButtonText = defaultLabel,
            onNeutral = {
                coverHeight = 120
                prefs.putInt(PreferKey.bookshelfCoverHeight, 120)
                showCoverHeightDialog = false
            },
        )
    }
    // 默认封面选择 Dialog (日/夜), 替代 app 端 DefaultCoverGalleryDialog
    if (showDayCoverDialog) {
        DesktopDefaultCoverDialog(
            isNight = false,
            onDismiss = { showDayCoverDialog = false },
            onDataChanged = { dataVersion++ },
        )
    }
    if (showNightCoverDialog) {
        DesktopDefaultCoverDialog(
            isNight = true,
            onDismiss = { showNightCoverDialog = false },
            onDataChanged = { dataVersion++ },
        )
    }
}

/**
 * 桌面端默认封面图集管理 Dialog (替代 app 端 DefaultCoverGalleryDialog)。
 *
 * # 职责
 *
 * - 网格列表展示已选封面 (LazyVerticalGrid Fixed(3), 与 app 端一致)
 * - 封面缩略图: [ImageIO.read] 加载 bakedPath → [ImageBitmap] (produceState 异步)
 * - 末尾 + 按钮: [FileDialog] 选图 → 读取 bytes → [bakeAndAddCover] 烘焙落盘 →
 *   [BookCoverShared.addDefaultCoverEntry] 更新 prefs
 * - 点击封面: [AlertDialog] 确认删除 → [BookCoverShared.removeDefaultCoverEntry] 更新 prefs +
 *   删烘焙文件
 *
 * # 平台特殊行为 (与 app 端差异, 不下沉 shared)
 *
 * - 烘焙: app 端 Bitmap + Glide centerCrop/topCrop + WebP 压缩;
 *   桌面端 BufferedImage + Graphics2D 裁剪 + ImageIO.write PNG
 *   (JDK ImageIO 不支持 webp 编码, 文件名仍用 .webp 后缀但内容为 PNG;
 *    [ImageIO.read] 不依赖后缀能正常解码; app 端 BitmapFactory 同样不依赖后缀)
 * - 文件选择: app 端 SAF (HandleFileContract.IMAGE); 桌面端 [FileDialog] (LOAD)
 * - 路径: coversDir = {desktopAppRootDir}/covers/default (对齐 app 端 externalFiles/covers/default)
 *
 * @param isNight true=夜间默认封面 (PreferKey.defaultCoverDark), false=日间 (PreferKey.defaultCover)
 * @param onDismiss 关闭回调
 * @param onDataChanged 增删后通知调用方刷新 summary (dataVersion++)
 */
@Composable
private fun DesktopDefaultCoverDialog(
    isNight: Boolean,
    onDismiss: () -> Unit,
    onDataChanged: () -> Unit,
) {
    val prefs = remember { PreferenceProviders.get() }
    val prefKey = if (isNight) PreferKey.defaultCoverDark else PreferKey.defaultCover
    val scope = rememberCoroutineScope()
    // coversDir: {desktopAppRootDir}/covers/default (对齐 app 端 externalFiles/covers/default)
    val coversDir = remember {
        val dir = Paths.get(desktopAppRootDir(), "covers", "default").toFile()
        if (!dir.exists()) dir.mkdirs()
        dir.absolutePath
    }
    // 数据版本号: 增删后自增触发重组重取列表
    var dataVersion by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION")
    dataVersion
    val entries = BookCoverShared.listDefaultCovers(prefs, prefKey)
    // 删除确认状态
    var pendingDelete by remember { mutableStateOf<DefaultCoverEntry?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                DialogTitleBar(
                    title = rememberString("default_cover"),
                    subtitle = rememberString(if (isNight) "night" else "day"),
                    onBack = onDismiss,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        CoverTile(
                            entry = entry,
                            coversDir = coversDir,
                            onClick = { pendingDelete = entry },
                        )
                    }
                    item(key = "__add__") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .aspectRatio(3f / 4f)
                                .clickable {
                                    // 弹 FileDialog 选图 → 烘焙落盘 → 更新 prefs (IO 线程)
                                    scope.launch {
                                        val picked = withContext(Dispatchers.IO) { FileDialogs.pickImageFile() }
                                        if (picked == null) return@launch
                                        val (bytes, name) = picked
                                        val added = withContext(Dispatchers.IO) {
                                            bakeAndAddCover(
                                                coversDir = coversDir,
                                                prefKey = prefKey,
                                                sourceBytes = bytes,
                                                originalName = name,
                                            )
                                        }
                                        if (added) {
                                            dataVersion++
                                            onDataChanged()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // 用 "+" 文字替代图标 (desktop 端无 ic_add 资源便捷引用)
                            // 28sp/36sp 对应原 M3 headlineMedium
                            Text("+", style = TextStyle(fontSize = 28.sp, lineHeight = 36.sp))
                        }
                    }
                }
            }
        }
    }
    // 删除确认对话框 (对齐 app 端 alert(R.string.delete, R.string.sure_del))
    val target = pendingDelete
    if (target != null) {
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = jvmGetString("delete"),
            message = jvmGetString("sure_del"),
            okButton = AlertButton(rememberString("ok"), dismissOnClick = false) {
                removeCover(coversDir = coversDir, prefKey = prefKey, entry = target)
                pendingDelete = null
                dataVersion++
                onDataChanged()
            },
            cancelButton = AlertButton(rememberString("cancel")),
        )
    }
}

/**
 * 封面缩略图 tile (对齐 app 端 DefaultCoverGalleryDialog.CoverTile)。
 *
 * 用 [ImageIO.read] 加载 bakedPath (NOVEL 比例) → [ImageBitmap], produceState 异步避免阻塞 UI。
 * 加载中/失败: 空白 Box (不显示占位, 与 app 端回落 newDefaultDrawable 不同 —— 桌面端无默认封面图集时不显示)。
 */
@Composable
private fun CoverTile(
    entry: DefaultCoverEntry,
    coversDir: String,
    onClick: () -> Unit,
) {
    val path = remember(entry, coversDir) {
        BookCoverShared.bakedPath(coversDir, entry, CoverRatio.NOVEL)
    }
    // 异步加载封面缩略图 (ImageIO.read 在 IO 线程, 避免阻塞 UI)
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (!file.exists()) return@runCatching null
                ImageIO.read(file)?.toComposeImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .aspectRatio(3f / 4f)
            .clip(DesignTokens.shapeSm)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = entry.id,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 烘焙用户选中的图片并添加到 prefs (平台特殊行为, 不下沉 shared)。
 *
 * - .9.png: 拷贝原文件到 {coversDir}/{md5}.9.png (不烘焙, NinePatchDrawable 会按容器自适应)
 * - 普通图: 按 NOVEL/VIDEO 各裁一份, ImageIO.write PNG
 *   (JDK ImageIO 不支持 webp 编码, 文件名 .webp 但内容 PNG; [ImageIO.read] 不依赖后缀)
 *
 * @return 是否实际新增 (false 表示 md5 已存在被忽略, 或图片解码失败)
 */
private fun bakeAndAddCover(
    coversDir: String,
    prefKey: String,
    sourceBytes: ByteArray,
    originalName: String,
): Boolean {
    val isNinePatch = originalName.endsWith(".9.png", ignoreCase = true)
    val md5 = MD5Utils.md5Encode(ByteArrayInputStream(sourceBytes))
    val entry = DefaultCoverEntry(md5, isNinePatch)
    val prefs = PreferenceProviders.get()
    // 先烘焙落盘
    if (isNinePatch) {
        val out = File(coversDir, "$md5.9.png")
        if (!out.exists()) out.writeBytes(sourceBytes)
    } else {
        val src = ImageIO.read(ByteArrayInputStream(sourceBytes)) ?: return false
        bakeAndWritePng(src, md5, CoverRatio.NOVEL, coversDir)
        bakeAndWritePng(src, md5, CoverRatio.VIDEO, coversDir)
    }
    // 再更新 prefs (BookCoverShared.addDefaultCoverEntry 去重, 相同 id 忽略)
    return BookCoverShared.addDefaultCoverEntry(prefs, prefKey, entry)
}

/**
 * 裁剪并写入烘焙封面 (centerCrop / topCrop + ImageIO.write PNG)。
 * 文件名 {md5}_{ratio.fileTag}.webp, 内容为 PNG (JDK ImageIO 不支持 webp 编码)。
 */
private fun bakeAndWritePng(
    src: BufferedImage,
    md5: String,
    ratio: CoverRatio,
    coversDir: String,
) {
    val out = File(coversDir, "${md5}_${ratio.fileTag}.webp")
    val cropped = if (ratio == CoverRatio.VIDEO) {
        topCropBufferedImage(src, ratio.bakeW, ratio.bakeH)
    } else {
        centerCropBufferedImage(src, ratio.bakeW, ratio.bakeH)
    }
    ImageIO.write(cropped, "png", out)
}

/**
 * centerCrop: 等比放大居中裁剪到 targetW x targetH (对齐 app 端 utils.centerCrop)。
 */
private fun centerCropBufferedImage(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
    val srcW = src.width
    val srcH = src.height
    val scale = maxOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
    val scaledW = (srcW * scale).toInt()
    val scaledH = (srcH * scale).toInt()
    val result = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
    val g = result.createGraphics()
    g.drawImage(src, (targetW - scaledW) / 2, (targetH - scaledH) / 2, scaledW, scaledH, null)
    g.dispose()
    return result
}

/**
 * topCrop: 等比放大顶部裁剪到 targetW x targetH (对齐 app 端 utils.topCrop, 用于 VIDEO 保留顶部封面信息)。
 */
private fun topCropBufferedImage(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
    val srcW = src.width
    val srcH = src.height
    val scale = maxOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
    val scaledW = (srcW * scale).toInt()
    val scaledH = (srcH * scale).toInt()
    val result = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
    val g = result.createGraphics()
    // topCrop: x 居中, y 顶部对齐 (与 app 端 topCrop 语义一致)
    g.drawImage(src, (targetW - scaledW) / 2, 0, scaledW, scaledH, null)
    g.dispose()
    return result
}

/**
 * 删除封面: 更新 prefs + 删烘焙文件 (平台特殊行为)。
 * 文件删除 runCatching 兜底, 失败不阻断 prefs 更新 (与 app 端 removeDefaultCover 一致)。
 */
private fun removeCover(coversDir: String, prefKey: String, entry: DefaultCoverEntry) {
    val prefs = PreferenceProviders.get()
    BookCoverShared.removeDefaultCoverEntry(prefs, prefKey, entry.id)
    runCatching {
        if (entry.ninePatch) {
            File(coversDir, "${entry.id}.9.png").delete()
        } else {
            // 删除所有比例的烘焙文件 (NOVEL + VIDEO)
            CoverRatio.entries.forEach { r ->
                File(coversDir, "${entry.id}_${r.fileTag}.webp").delete()
            }
        }
    }
}

