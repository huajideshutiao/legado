package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.BookCoverShared
import io.legado.app.model.BookCoverShared.CoverRatio
import io.legado.app.model.BookCoverShared.DefaultCoverEntry
import io.legado.app.model.bakeCoverVariantsToCache
import io.legado.app.model.coverBakedCacheDir
import io.legado.app.model.coverOriginalDir
import io.legado.app.model.defaultCoverDisplayPath
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.DefaultCoverNineImage
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.NinePatchImageOrImage
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.normalizeImageSuffix
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.day
import legado.shared.generated.resources.default_cover
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.night
import legado.shared.generated.resources.no
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * 默认封面图集管理对话框 (KMP 共享, 对照 app 端 DefaultCoverGalleryDialog)。
 *
 * 原版"默认封面库"是用户自选图集: 网格展示已选封面 (烘焙 3:4 图), 末尾 + 按钮选图加入,
 * 点击封面二次确认删除; 选中的图集用于书籍无封面/useDefaultCover 时的封面回退
 * (shared 链: [io.legado.app.ui.bookshelf.defaultCoverFilePath] → 用户图集 → 内置图)。
 *
 * 本对话框把该管理 UI 下沉 shared 供四端复用 (四端均经
 * [io.legado.app.ui.root.PlatformCapabilities.showDefaultCoverGallery] Overlay 弹出;
 * 原 app 端 DefaultCoverGalleryDialog Fragment 已随封面统一删除):
 *
 * - 列表: [BookCoverShared.listDefaultCovers] 读 prefs (PreferKey.defaultCover / defaultCoverDark)
 * - 瓦片: 按 [io.legado.app.model.defaultCoverDisplayPath] 经 [BookImageLoaders] 加载烘焙图
 *   (未注册 loader 的端如鸿蒙显示内置占位, 与书架封面链一致)
 * - 添加: 平台文件选择器 ([PlatformServiceProviders].files.pickFile, 阻塞式须切 IO) →
 *   读字节 → 字节数作 id → **原图落图集** `customImg/covers/<id>.<ext>` (.9.png 按原名单路径
 *   直落, 保留九宫格标记框) + [bakeCoverVariantsToCache] 烘焙两 ratio 产物写**缓存**
 *   (失败 toast 中止, 不留 prefs 残留 entry) → [BookCoverShared.addDefaultCoverEntry] 写
 *   prefs → 广播 [EventBus.DEFAULT_COVER_CHANGED]
 * - 删除: 二次确认 → 移除 prefs entry + 删图集原图与缓存产物 (另一昼夜图集仍引用同 id 时保留)
 * - 增删后广播 DEFAULT_COVER_CHANGED (封面配置页 summary 刷新) + BOOKSHELF_REFRESH
 *   (书架/详情页默认封面链重组重读 prefs)
 *
 * @param isNight 编辑夜间图集 (PreferKey.defaultCoverDark) 还是日间 (PreferKey.defaultCover)
 * @param onDismiss 关闭回调
 */
@Composable
fun DefaultCoverGalleryDialogHost(
    isNight: Boolean,
    onDismiss: () -> Unit,
) {
    val prefKey = if (isNight) PreferKey.defaultCoverDark else PreferKey.defaultCover
    val prefs = PreferenceProviders.get()
    val scope = rememberCoroutineScope()

    // 数据版本号: 增删后自增触发重组重取列表 (对照 app 端 dataVersion)
    var dataVersion by remember { mutableIntStateOf(0) }
    val entries = remember(dataVersion) { BookCoverShared.listDefaultCovers(prefs, prefKey) }
    // 广播驱动刷新: 本对话框自身的增删也会 emit, 统一走此路径 (含外部修改)
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.DEFAULT_COVER_CHANGED).collect { dataVersion++ }
    }

    var pendingDelete by remember { mutableStateOf<DefaultCoverEntry?>(null) }
    var adding by remember { mutableStateOf(false) }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                modifier = Modifier.appDialogSize(fullHeight = true),
            ) {
                Column(Modifier.fillMaxSize()) {
                    DialogTitleBar(
                        title = stringResource(Res.string.default_cover),
                        subtitle = stringResource(if (isNight) Res.string.night else Res.string.day),
                        onBack = onDismiss,
                    )
                    LazyVerticalGrid(
                        columns = rememberResponsiveColumns(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            DefaultCoverTile(entry = entry) { pendingDelete = entry }
                        }
                        item(key = "__add__") {
                            // + 按钮: 平台图片选择器 → 烘焙落盘 → 写 prefs (对照原版 onAddClick)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .aspectRatio(3f / 4f)
                                    .clip(DesignTokens.shapeDefault)
                                    .background(AppTheme.colors.fillet)
                                    .clickable(enabled = !adding) {
                                        adding = true
                                        scope.launch {
                                            addDefaultCoverFromPicker(prefKey)
                                            adding = false
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = rememberPainter("ic_add"),
                                    contentDescription = stringResource(Res.string.add),
                                    tint = AppTheme.colors.primaryText,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除二次确认 (对照 app 端 alert(delete, sure_del) + yesButton/noButton)
    pendingDelete?.let { entry ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(stringResource(Res.string.yes)) {
                scope.launch {
                    removeDefaultCover(prefKey, entry)
                    pendingDelete = null
                }
            },
            cancelButton = AlertButton(stringResource(Res.string.no)) {},
        )
    }
}

/**
 * Overlay 渲染入口 (LegadoApp DialogOverlayContent 按 key="default_cover_gallery" 分流;
 * payload "1"=夜间, 其余=日间)。
 */
@Composable
internal fun DefaultCoverGalleryOverlayDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
) {
    DefaultCoverGalleryDialogHost(
        isNight = overlay.payload == "1",
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

/** 单个封面瓦片: 展示路径按图集原图/缓存产物计算 (见 [defaultCoverDisplayPath]), 点击删除。 */
@Composable
private fun DefaultCoverTile(entry: DefaultCoverEntry, onClick: () -> Unit) {
    val loader = remember { BookImageLoaders.getOrNull() }
    var bitmap by remember(entry.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.id, loader) {
        if (loader == null) return@LaunchedEffect
        bitmap = loader.loadImageOrNull(
            url = defaultCoverDisplayPath(entry, CoverRatio.NOVEL),
            sourceOrigin = null,
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .aspectRatio(3f / 4f)
            .clip(DesignTokens.shapeDefault)
            .background(AppTheme.colors.fillet)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            // .9 图按九宫格拉伸预览, 普通图走 Crop 裁剪
            NinePatchImageOrImage(
                bitmap = bmp,
                isNinePatch = entry.ninePatch,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 加载中/未注册 loader/读盘失败: 内置默认封面图占位 (对照原版 loadThumb 失败回落)
            DefaultCoverNineImage(modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 平台图片选择器 → 加入默认封面图集 (对照原 app 端 DefaultCoverGalleryDialog.onAddClick →
 * HandleFileContract.IMAGE + BookCover.addDefaultCover)。
 *
 * 顺序: 判重 (烘焙前, 重复添加零开销) → [bakeDefaultCoverBytes] 烘焙 + 写盘 → 写 prefs →
 * 广播。烘焙失败 (输入字节解码不了, 对各端渲染同样不可解码) 直接 toast 中止,
 * 不回落原图直落、不留 prefs 残留 entry。.9.png 按原版语义识别并单路径直落保标记框。
 *
 * 成功后广播 DEFAULT_COVER_CHANGED (本对话框经 LaunchedEffect 刷新列表) +
 * BOOKSHELF_REFRESH (书架默认封面链重组)。
 */
private suspend fun addDefaultCoverFromPicker(prefKey: String) {
    // 选择器是阻塞式的 (各端 runBlocking 等系统回调), 必须切到 IO 再调
    val path = withContext(IoDispatcher) {
        PlatformServiceProviders.getOrNull()?.files?.pickFile(FileFilter.Images)
    } ?: return
    val bytes = FileUtilsCommon.readBytes(path)
    if (bytes == null || bytes.isEmpty()) {
        Toasters.get().toast("读取图片失败")
        return
    }
    val prefs = PreferenceProviders.get()
    val ninePatch = path.endsWith(".9.png", ignoreCase = true)
    // 内容字节数特征值作 id (与背景/启动图同规则, 同内容复用)
    val entry = DefaultCoverEntry(id = bytes.size.toString(), ninePatch = ninePatch)
    // 相同图片已存在直接忽略, 避免重复烘焙写盘
    if (BookCoverShared.listDefaultCovers(prefs, prefKey).any { it.id == entry.id }) return
    withContext(IoDispatcher) {
        runCatching {
            // 先烘焙 (失败中止, 不留孤儿原图/entry), 成功后再落图集原图
            if (entry.ninePatch) {
                val nineDest = FileUtilsCommon.getPath(coverOriginalDir(), "${entry.id}.9.png")
                if (!FileUtilsCommon.exist(nineDest)) FileUtilsCommon.writeBytes(nineDest, bytes)
            } else {
                if (!bakeCoverVariantsToCache(bytes, entry.id)) {
                    error("bake cover failed")
                }
                val dest = FileUtilsCommon.getPath(
                    coverOriginalDir(),
                    "${entry.id}.${normalizeImageSuffix(path.substringAfterLast('.'))}"
                )
                if (!FileUtilsCommon.exist(dest)) FileUtilsCommon.writeBytes(dest, bytes)
            }
        }
    }.onFailure {
        Toasters.get().toast("添加封面失败\n${it.message}")
        return
    }
    BookCoverShared.addDefaultCoverEntry(prefs, prefKey, entry)
    FlowBus.with(EventBus.DEFAULT_COVER_CHANGED).tryEmit(prefKey)
    FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
}

/** 移除 prefs entry + 删除图集原图与缓存产物 (另一昼夜图集仍引用同 id 时保留), 成功后广播刷新。 */
private suspend fun removeDefaultCover(prefKey: String, entry: DefaultCoverEntry) {
    val prefs = PreferenceProviders.get()
    val removed = BookCoverShared.removeDefaultCoverEntry(prefs, prefKey, entry.id)
    if (removed == null) return
    // 另一昼夜图集可能复用同一文件 (同字节数=同内容), 仍引用则保留
    val otherKey =
        if (prefKey == PreferKey.defaultCover) PreferKey.defaultCoverDark else PreferKey.defaultCover
    val stillUsed = BookCoverShared.listDefaultCovers(prefs, otherKey).any { it.id == removed.id }
    if (!stillUsed) {
        withContext(IoDispatcher) {
            runCatching {
                if (removed.ninePatch) {
                    FileUtilsCommon.delete(
                        FileUtilsCommon.getPath(coverOriginalDir(), "${removed.id}.9.png")
                    )
                } else {
                    CoverRatio.entries.forEach { ratio ->
                        FileUtilsCommon.delete(
                            FileUtilsCommon.getPath(
                                coverBakedCacheDir(),
                                "${removed.id}_${ratio.fileTag}.webp"
                            )
                        )
                    }
                    // 图集原图 (扩展名未入 entry, 按 id 前缀扫删)
                    FileUtilsCommon.listFiles(coverOriginalDir())?.forEach { path ->
                        if (path.substringAfterLast('/').substringAfterLast('\\')
                                .substringBeforeLast('.') == removed.id
                        ) {
                            FileUtilsCommon.delete(path)
                        }
                    }
                }
            }
        }
    }
    FlowBus.with(EventBus.DEFAULT_COVER_CHANGED).tryEmit(prefKey)
    FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
}
