package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isEpub
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.sourceOrigin
import io.legado.app.model.BookCover
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.compose.component.zoomable
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ACache
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.toastOnUi
import java.io.ByteArrayInputStream

/**
 * 显示图片
 */
class PhotoDialog() : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    constructor(src: String, sourceOrigin: String? = null) : this() {
        arguments = android.os.Bundle().apply {
            putString("src", src)
            putString("sourceOrigin", sourceOrigin)
        }
    }

    private lateinit var src: String

    @Composable
    override fun Content() {
        src = arguments?.getString("src") ?: return
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(Unit) { loadPhoto { image = it } }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            image?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomable(
                            contentAspectRatio = bitmap.width.toFloat() / bitmap.height,
                            onLongPress = { onLongPressSave() },
                        ),
                )
            }
        }
    }

    private val saveImageLauncher by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                ACache.get().put(AppConst.imagePathKey, uri.toString())
                doSaveImage(uri)
            }
        }
    }

    private fun onLongPressSave() {
        val path = ACache.get().getAsString(AppConst.imagePathKey)
        if (path.isNullOrEmpty()) {
            saveImageLauncher.launch { }
        } else {
            doSaveImage(path.toUri())
        }
    }

    private fun loadPhoto(onLoaded: (ImageBitmap?) -> Unit) {
        val book = ReadBook.book
        // 异步走本地路径：data URI / 章节缓存文件 / 本地 EPUB ZIP，解码尺寸取屏幕 2 倍留放大余量
        val dm = resources.displayMetrics
        val w = dm.widthPixels * 2
        val h = dm.heightPixels * 2
        val targetDensity = dm.densityDpi

        execute {
            val bitmap = when {
                src.startsWith("data:image/svg+xml;base64,") ->
                    decodeBytes(Base64.decode(src.substring(26), Base64.DEFAULT), w, h)

                book != null -> {
                    val file = BookHelp.getImage(book, src)
                    when {
                        file.exists() -> decodeFile(file.absolutePath, w, h)
                        book.isEpub -> FileBook.getImage(book, src)?.use { input ->
                            decodeBytes(input.readBytes(), w, h)
                        }

                        else -> null
                    }
                }

                else -> null
            }?.apply { density = targetDensity }
            bitmap ?: loadBitmapByCoil()
        }.onSuccess { bitmap ->
            onLoaded(bitmap?.asImageBitmap())
        }.onError {
            onLoaded(null)
        }
    }

    /** 先用 BitmapFactory 解栅格图，失败则按 SVG 渲染 */
    private fun decodeBytes(bytes: ByteArray, w: Int, h: Int): Bitmap? =
        BitmapUtils.decodeBitmap(bytes, w, h) ?: SvgUtils.renderInto(bytes, w, h)

    private fun decodeFile(path: String, w: Int, h: Int): Bitmap? =
        BitmapUtils.decodeBitmap(path, w, h) ?: SvgUtils.renderInto(path, w, h)

    /**
     * 本地路径都不命中时的兜底：先查 covers 磁盘缓存（封面场景命中），否则正常请求，仍失败取默认封面。
     */
    private suspend fun loadBitmapByCoil(): Bitmap? {
        val ctx = context ?: return null
        val sourceOrigin = arguments?.getString("sourceOrigin")
        val loader = coil3.SingletonImageLoader.get(ctx)
        val cached = runCatching {
            val req = ImageRequest.Builder(ctx as PlatformContext)
                .data(src)
                .sourceOrigin(sourceOrigin)
                .diskCachePolicy(CachePolicy.READ_ONLY)
                .networkCachePolicy(CachePolicy.DISABLED)
                .size(Size.ORIGINAL)
                .build()
            loader.execute(req)
        }.getOrNull()
        if (cached is SuccessResult) return cached.image?.toBitmap()
        val fresh = runCatching {
            val req = ImageRequest.Builder(ctx as PlatformContext)
                .data(src)
                .sourceOrigin(sourceOrigin)
                .size(Size.ORIGINAL)
                .build()
            loader.execute(req)
        }.getOrNull()
        if (fresh is SuccessResult) return fresh.image?.toBitmap()
        return BookCover.newDefaultDrawable().toBitmap()
    }

    @SuppressLint("CheckResult")
    private fun doSaveImage(uri: Uri) {
        execute {
            val book = ReadBook.book
            val localFile = book?.let { BookHelp.getImage(it, src).takeIf { it.exists() } }
            // 章节缓存命中直接保存; 未命中走加载器字节链路 (磁盘/进程缓存优先,
            // 未命中才带书源防盗链 header/cookie 下载并回写缓存 —— 对齐原版
            // Glide downloadOnly + onlyRetrieveFromCache 的缓存优先语义);
            // 最后兜底按 URL/base64 裸下载 (data: URI 等加载器不支持的场景)。
            val result = localFile?.let { FileUtils.saveImage(it, uri) }
                ?: loadImageBytes(book)?.let { bytes ->
                    FileUtils.saveImage(
                        ByteArrayInputStream(bytes),
                        uri,
                        ".${BookHelp.getImageSuffix(src)}"
                    )
                }
                ?: FileUtils.saveImage(src, uri)

            if (!result) error("找不到数据")
        }.onError {
            ACache.get().remove(AppConst.imagePathKey)
            context?.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context?.toastOnUi("保存成功")
        }
    }

    /**
     * 取待保存图片的原始字节 (对齐原版 PhotoDialog 的 Glide downloadOnly 双签名:
     * 先按封面规则 signature("covers") 再按正常规则, 均 onlyRetrieveFromCache 优先)。
     * [ImageBitmapLoader.loadBytes] 内部 [io.legado.app.help.image.ImageBytesCache]
     * 磁盘/进程缓存优先, 未命中才带书源 header/cookie/charset/JS 下载并按 [isCover]
     * 执行响应字节解密后回写缓存 —— 防盗链书源的网络图无需缓存也能保存成功。
     */
    private suspend fun loadImageBytes(book: Book?): ByteArray? {
        val bookSource = book?.getBookSource()
        val loader = ImageBitmapLoader()
        return runCatching { loader.loadBytes(src, book, bookSource, isCover = true) }
            .getOrNull()
            ?: runCatching { loader.loadBytes(src, book, bookSource, isCover = false) }
                .getOrNull()
    }

}
