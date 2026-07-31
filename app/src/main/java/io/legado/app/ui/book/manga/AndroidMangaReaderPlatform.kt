package io.legado.app.ui.book.manga

import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.BatteryManager
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.isNoOp
import io.legado.app.ui.book.manga.config.toColorMatrix
import io.legado.app.ui.book.manga.render.MangaPageImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
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
        // ACTION_BATTERY_CHANGED 是 sticky 广播, registerReceiver(receiver=null) 直接取最近一次
        val intent = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
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
    ) {
        AndroidView(
            factory = { MangaPageImageView(it) },
            modifier = modifier,
            onReset = { it.recycle() },
            onRelease = { it.recycle() },
            update = { view ->
                view.scaleType =
                    if (horizontal) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY
                // 颜色滤镜: 复用 shared toColorMatrix, 全 0 时清除 (对照 app 端 MangaRenderScreen.toColorFilter)
                view.colorFilter = if (colorFilterConfig.isNoOp()) null
                else ColorMatrixColorFilter(ColorMatrix(colorFilterConfig.toColorMatrix()))
                // GIF 由 Coil3 自动识别解码 (coil3-gif MovieDrawable/AnimatedImageDrawable), 无需 isGif 标记
                view.loadPageImage(url, book, source, grayEnabled)
            },
        )
    }

    // 保存图片: destPath 为 CreateDocument 返回的文件 Uri; 优先本地缓存, 本地书走 FileBook
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
        destPath: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            book ?: return@withContext false
            runCatching {
                val uri = android.net.Uri.parse(destPath)
                val image = BookHelp.getImage(book, url)
                if (image.exists()) {
                    appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(image).use { it.copyTo(out) }
                    }
                    true
                } else if (book.isLocal) {
                    io.legado.app.model.fileBook.FileBook.getImage(book, url)?.use { input ->
                        appCtx.contentResolver.openOutputStream(uri)
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
