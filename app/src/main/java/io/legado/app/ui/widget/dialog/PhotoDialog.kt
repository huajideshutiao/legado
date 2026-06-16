package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isEpub
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ACache
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 显示图片
 */
class PhotoDialog() : BaseDialogFragment(R.layout.dialog_photo_view) {

    override val isFullHeight: Boolean = true

    constructor(src: String, sourceOrigin: String? = null) : this() {
        arguments = Bundle().apply {
            putString("src", src)
            putString("sourceOrigin", sourceOrigin)
        }
    }

    private val binding by viewBinding(DialogPhotoViewBinding::bind)
    private lateinit var src: String

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

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        src = arguments?.getString("src") ?: return
        initEvent()
        loadPhoto()
    }

    private fun initEvent() {
        binding.photoView.setOnLongClickListener {
            binding.photoView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val path = ACache.get().getAsString(AppConst.imagePathKey)
            if (path.isNullOrEmpty()) {
                saveImageLauncher.launch { }
            } else {
                doSaveImage(path.toUri())
            }
            true
        }
    }

    private fun loadPhoto() {
        val book = ReadBook.book
        // 异步走本地路径：data URI / 章节缓存文件 / 本地 EPUB ZIP
        // 解码尺寸取屏幕的 2 倍，给 PhotoView 放大留余量
        val dm = resources.displayMetrics
        val w = dm.widthPixels * 2
        val h = dm.heightPixels * 2
        val targetDensity = dm.densityDpi

        execute {
            when {
                src.startsWith("data:") -> {
                    val pos = src.indexOf(";base64,")
                    if (pos == -1) null
                    else decodeBytes(Base64.decode(src.substring(pos + 8), Base64.DEFAULT), w, h)
                }

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
        }.onSuccess { bitmap ->
            if (bitmap != null) binding.photoView.setImageBitmap(bitmap)
            else loadByGlide()
        }.onError {
            loadByGlide()
        }
    }

    /** 先用 BitmapFactory 解栅格图，失败则按 SVG 渲染 */
    private fun decodeBytes(bytes: ByteArray, w: Int, h: Int): Bitmap? =
        BitmapUtils.decodeBitmap(bytes, w, h) ?: SvgUtils.renderInto(bytes, w, h)

    private fun decodeFile(path: String, w: Int, h: Int): Bitmap? =
        BitmapUtils.decodeBitmap(path, w, h) ?: SvgUtils.renderInto(path, w, h)

    /**
     * 本地路径都不命中时的兜底
     * 先查 covers 磁盘缓存（封面场景命中），否则走正常请求
     */
    private fun loadByGlide() {
        val ctx = context ?: return
        val normalRequest = ImageLoader.load(ctx, src)
            .apply(requestOptions)
            .error(BookCover.newDefaultDrawable())
            .dontTransform()
            .downsample(DownsampleStrategy.NONE)

        ImageLoader.load(ctx, src)
            .apply(requestOptions)
            .signature(ObjectKey("covers"))
            .onlyRetrieveFromCache(true)
            .error(normalRequest)
            .dontTransform()
            .downsample(DownsampleStrategy.NONE)
            .into(binding.photoView)
    }

    @SuppressLint("CheckResult")
    private fun doSaveImage(uri: Uri) {
        execute {
            val localFile = ReadBook.book?.let { book ->
                BookHelp.getImage(book, src).takeIf { it.exists() }
            }
            val file = localFile ?: run {
                val glide = Glide.with(requireContext())
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
