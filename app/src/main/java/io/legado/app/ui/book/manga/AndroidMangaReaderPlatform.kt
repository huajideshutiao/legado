package io.legado.app.ui.book.manga

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import io.legado.app.App
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.render.MangaCoilImage
import io.legado.app.ui.book.manga.render.preloadMangaImageAndroid
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.imageExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

object AndroidMangaReaderPlatform : MangaReaderScreenModel.Platform {
    override fun getBatteryLevel(): Int {
        // ACTION_BATTERY_CHANGED 是 sticky 广播, registerReceiver(receiver=null) 直接取最近一次;
        // 失败/无电池统一回落 100 (用户拍板 2026-08: 电量恒显示, 与 desktop 一致)
        val intent =
            App.instance.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    // 预载到内存缓存: 实现体在 shared (preloadMangaImageAndroid), 与 MangaCoilImage 显示请求
    // 同 key 同参, 翻到预载区间即秒显
    override suspend fun preloadImage(url: String, book: Book, source: BookSource?) {
        runCatching { preloadMangaImageAndroid(App.instance, url, book, source) }
    }

    override fun flowImages(
        bookChapter: BookChapter,
        content: String
    ): kotlinx.coroutines.flow.Flow<String> =
        BookHelp.flowImages(bookChapter, content)

    // 渲染全在 shared/androidMain 的 MangaCoilImage (纯 Compose, GIF Drawable 控制 +
    // 预载 peek + 绘制期灰度/调色), 本端只负责保存图片 (对齐 iOS/ohos Platform.Image 形态)
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
        MangaCoilImage(
            url = url,
            modifier = modifier,
            horizontal = horizontal,
            book = book,
            source = source,
            colorFilterConfig = colorFilterConfig,
            grayEnabled = grayEnabled,
            onLoadState = onLoadState,
            retryTick = retryTick,
            onProgress = onProgress,
        )
    }

    // 保存图片: 先取得原始字节, 以实际格式生成 SAF 文件名 (对齐原版 FileUtils.saveImage(File, Uri) 的魔数命名)
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
    ): Boolean? = withContext(Dispatchers.IO) {
        book ?: return@withContext false
        val files = PlatformServiceProviders.get().files
        runCatching {
            val bytes = MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
                ?: return@runCatching false
            val name = "manga-${System.currentTimeMillis()}${imageExtension(bytes, url)}"
            val destPath = files.saveFile(name)
                ?: return@runCatching null
            val uri = destPath.toUri()
            App.instance.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return@runCatching false
            true
        }.getOrElse {
            io.legado.app.constant.AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            false
        }
    }
}
