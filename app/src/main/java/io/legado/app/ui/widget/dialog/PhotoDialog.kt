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
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isEpub
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
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

    private val requestOptions: RequestOptions by lazy {
        arguments?.getString("sourceOrigin")?.let {
            RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it)
        } ?: RequestOptions()
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
            bitmap ?: loadBitmapByGlide()
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
    private fun loadBitmapByGlide(): Bitmap? {
        val ctx = context ?: return null
        val base = ImageLoader.with(ctx).asBitmap()
            .apply(requestOptions)
            .dontTransform()
            .downsample(DownsampleStrategy.NONE)
        return runCatching {
            base.clone().signature(ObjectKey("covers")).onlyRetrieveFromCache(true)
                .load(src).submit().get()
        }.getOrNull() ?: runCatching {
            base.clone().load(src).submit().get()
        }.getOrNull() ?: BookCover.newDefaultDrawable().toBitmap()
    }

    @SuppressLint("CheckResult")
    private fun doSaveImage(uri: Uri) {
        execute {
            val localFile = ReadBook.book?.let { book ->
                BookHelp.getImage(book, src).takeIf { it.exists() }
            }
            val file = localFile ?: run {
                val glide = ImageLoader.with(requireContext())
                runCatching {
                    glide.downloadOnly()
                        .apply(requestOptions)
                        .signature(ObjectKey("covers"))
                        .onlyRetrieveFromCache(true)
                        .load(src)
                        .submit()
                        .get()
                }.getOrNull() ?: runCatching {
                    glide.downloadOnly()
                        .apply(requestOptions)
                        .onlyRetrieveFromCache(true)
                        .load(src)
                        .submit()
                        .get()
                }.getOrNull()
            }

            val result = file?.let { FileUtils.saveImage(it, uri) } ?: FileUtils.saveImage(src, uri)

            if (!result) error("找不到数据")
        }.onError {
            ACache.get().remove(AppConst.imagePathKey)
            context?.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context?.toastOnUi("保存成功")
        }
    }

}
