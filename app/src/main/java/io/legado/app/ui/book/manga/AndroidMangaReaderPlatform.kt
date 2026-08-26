package io.legado.app.ui.book.manga

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.BatteryManager
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.Ref
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import io.legado.app.App
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.manga.MangaModel
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.isNoOp
import io.legado.app.ui.book.manga.config.toColorMatrix
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.render.MangaPageImageView
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

object AndroidMangaReaderPlatform : MangaReaderScreenModel.Platform {
    override val config: MangaReaderConfig
        get() = MangaReaderConfig(
            hideMangaTitle = AppConfig.hideMangaTitle,
            preDownloadNum = AppConfig.mangaPreDownloadNum,
            syncBookProgressPlus = AppConfig.syncBookProgressPlus,
            horizontal = AppConfig.enableMangaHorizontalScroll,
            autoPageSpeed = AppConfig.mangaAutoPageSpeed,
            grayEnabled = AppConfig.enableMangaGray,
            colorFilterConfig = runCatching {
                io.legado.app.utils.GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter)
                    .getOrNull()
            }.getOrNull() ?: MangaColorFilterConfig(),
            gifAutoNext = AppConfig.enableMangaGifAutoNext,
            disablePageAnim = AppConfig.disableMangaPageAnim,
            footerConfig = runCatching {
                io.legado.app.utils.GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig)
                    .getOrNull()
            }.getOrNull() ?: MangaFooterConfig(),
        )

    override fun getBatteryLevel(): Int {
        // ACTION_BATTERY_CHANGED 是 sticky 广播, registerReceiver(receiver=null) 直接取最近一次;
        // 失败/无电池统一回落 100 (用户拍板 2026-08: 电量恒显示, 与 desktop 一致)
        val intent =
            App.instance.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    override fun toggleHorizontal(): Boolean {
        val enable = !AppConfig.enableMangaHorizontalScroll
        AppConfig.enableMangaHorizontalScroll = enable
        return enable
    }

    // 持久化颜色滤镜 (对照 app 端 MangaColorFilterDialog.onDismiss 写 AppConfig.mangaColorFilter)
    override fun updateColorFilter(config: MangaColorFilterConfig) {
        AppConfig.mangaColorFilter = config.toJson()
    }

    // 持久化灰度开关 (对照 app 端 MangaColorFilterDialog.upGray 写 AppConfig.enableMangaGray)
    override fun updateGray(enable: Boolean) {
        AppConfig.enableMangaGray = enable
    }

    // 持久化页脚配置 (对照 app 端 MangaFooterSettingDialog.onDismiss 写 AppConfig.mangaFooterConfig)
    override fun updateFooterConfig(config: MangaFooterConfig) {
        AppConfig.mangaFooterConfig = io.legado.app.utils.GSON.toJson(config)
    }

    // 切换隐藏漫画标题 (对照 app 端 MangaMenuAction.HIDE_TITLE)
    override fun toggleHideTitle(): Boolean {
        AppConfig.hideMangaTitle = !AppConfig.hideMangaTitle
        return AppConfig.hideMangaTitle
    }

    // 切换禁用翻页动画 (对照 app 端 MangaMenuAction.DISABLE_PAGE_ANIM)
    override fun toggleDisablePageAnim(): Boolean {
        AppConfig.disableMangaPageAnim = !AppConfig.disableMangaPageAnim
        return AppConfig.disableMangaPageAnim
    }

    // 切换 GIF 播完翻页 (对照 app 端 MangaMenuAction.GIF_AUTO_NEXT)
    override fun toggleGifAutoNext(): Boolean {
        AppConfig.enableMangaGifAutoNext = !AppConfig.enableMangaGifAutoNext
        return AppConfig.enableMangaGifAutoNext
    }

    // 持久化预下载章节数 / 自动翻页速度 (对照 MangaMenuAction.PRE_DOWNLOAD_NUM / AUTO_PAGE_SPEED)
    override fun setPreDownloadNum(num: Int) {
        AppConfig.mangaPreDownloadNum = num
    }

    override fun setAutoPageSpeed(speed: Int) {
        AppConfig.mangaAutoPageSpeed = speed
    }

    // 预载到内存缓存: WRITE_ONLY 只写不返回图 (对照原版 RecyclerViewPreloader 预载语义;
    // 显示请求 memoryCachePolicy(ENABLED) 命中同 loader 同 Keyer 的 url 键, 翻到预载区间即秒显)。
    // Size.ORIGINAL 全尺寸解码 (isSampled=false), 对任意显示请求尺寸均有效
    // (Coil3 MemoryCacheService.isCacheValueValidForSize); 磁盘缓存禁用由 fetcher 层的
    // BookHelp 缓存承担, 与显示请求参数一致 (desktop 同参同链路)。
    override suspend fun preloadImage(url: String, book: Book, source: BookSource?) {
        runCatching {
            val request = ImageRequest.Builder(App.instance)
                .data(MangaModel(url, book, source))
                .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(Size.ORIGINAL)
                .build()
            SingletonImageLoader.get(App.instance).execute(request)
        }
    }

    override fun flowImages(
        bookChapter: BookChapter,
        content: String
    ): kotlinx.coroutines.flow.Flow<String> =
        BookHelp.flowImages(bookChapter, content)

    @Composable
    override fun Image(
        url: String,
        modifier: Modifier,
        horizontal: Boolean,
        book: Book?,
        source: BookSource?,
        colorFilterConfig: MangaColorFilterConfig,
        grayEnabled: Boolean,
        onLoadState: (MangaCellState) -> Unit,
        retryTick: Int,
        onProgress: (String) -> Unit,
    ) {
        val viewRef = remember { Ref<MangaPageImageView>() }
        // 重试: shared 单元格"重新加载"点击 → retryTick 自增 → 直接调 MangaPageImageView.retry() (对照 app 端 MangaRenderScreen)
        LaunchedEffect(retryTick) {
            if (retryTick > 0) viewRef.value?.retry()
        }
        if (book == null) {
            // 缺少书籍上下文: loadPageImage 会 book ?: return 而永不回调, 这里不再静默, 直接上报错误态
            LaunchedEffect(Unit) { onLoadState(MangaCellState.ERROR) }
            return
        }
        AndroidView(
            factory = { MangaPageImageView(it) },
            modifier = modifier,
            onReset = { it.recycle() },
            onRelease = { it.recycle() },
            update = { view ->
                viewRef.value = view
                view.scaleType =
                    if (horizontal) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY
                // 颜色滤镜: 复用 shared toColorMatrix, 全 0 时清除 (对照 app 端 MangaRenderScreen.toColorFilter)
                view.colorFilter = if (colorFilterConfig.isNoOp()) null
                else ColorMatrixColorFilter(ColorMatrix(colorFilterConfig.toColorMatrix()))
                // 上报加载状态给 shared 单元格 (对齐 app 端 MangaRenderScreen: v.onStateChange = { load = it })
                view.onStateChange = { state ->
                    onLoadState(
                        when (state) {
                            io.legado.app.ui.book.manga.render.MangaCellState.LOADING ->
                                MangaCellState.LOADING

                            io.legado.app.ui.book.manga.render.MangaCellState.SUCCESS ->
                                MangaCellState.SUCCESS

                            io.legado.app.ui.book.manga.render.MangaCellState.ERROR ->
                                MangaCellState.ERROR
                        }
                    )
                }
                // 上报下载进度给 shared 单元格转圈环心 (对照 app 端 MangaRenderScreen: v.onProgress = { progress = it })
                view.onProgress = onProgress
                // GIF 由 Coil3 自动识别解码 (coil3-gif MovieDrawable/AnimatedImageDrawable), 无需 isGif 标记
                view.loadPageImage(url, book, source, grayEnabled)
            },
        )
    }

    // 保存图片: destPath 为 CreateDocument 返回的文件 Uri; 优先本地缓存, 本地书走 FileBook
    @SuppressLint("Recycle") // openOutputStream 均以 .use 关闭, lint 追踪不到嵌套 use 内的流
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
        destPath: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            book ?: return@withContext false
            runCatching {
                val uri = destPath.toUri()
                val image = BookHelp.getImage(book, url)
                if (image.exists()) {
                    App.instance.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(image).use { it.copyTo(out) }
                    }
                    true
                } else if (book.isLocal) {
                    io.legado.app.model.fileBook.FileBook.getImage(book, url)?.use { input ->
                        App.instance.contentResolver.openOutputStream(uri)
                            ?.use { out -> input.copyTo(out) }
                        true
                    } ?: false
                } else false
            }.getOrElse {
                io.legado.app.constant.AppLog.put("保存图片出错\n${it.localizedMessage}", it)
                false
            }
        }
}
