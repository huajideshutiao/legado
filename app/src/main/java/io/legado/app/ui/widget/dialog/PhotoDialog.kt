package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File

/**
 * 显示图片
 */
class PhotoDialog() : BaseDialogFragment(R.layout.dialog_photo_view) {

    constructor(src: String, sourceOrigin: String? = null) : this() {
        arguments = Bundle().apply {
            putString("src", src)
            putString("sourceOrigin", sourceOrigin)
        }
    }

    private val binding by viewBinding(DialogPhotoViewBinding::bind)
    private lateinit var src: String
    private var localImageFile: File? = null

    private val requestOptions: RequestOptions by lazy {
        RequestOptions().apply {
            arguments?.getString("sourceOrigin")?.let {
                set(OkHttpModelLoader.sourceOriginOption, it)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private val saveImageLauncher = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            doSaveImage(uri)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        src = arguments?.getString("src") ?: return

        // 预先查找本地图片文件
        localImageFile = ReadBook.book?.let { book ->
            BookHelp.getImage(book, src).takeIf { it.exists() }
        }

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
        ImageProvider.get(src)?.let {
            binding.photoView.setImageBitmap(it)
            return
        }

        localImageFile?.let { file ->
            Glide.with(this).load(file)
                .error(R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(binding.photoView)
            return
        }

        ImageLoader.load(requireContext(), src)
            .apply(requestOptions)
            .error(BookCover.defaultDrawable)
            .dontTransform()
            .downsample(DownsampleStrategy.NONE)
            .addListener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    // 默认缓存找不到，尝试用 "covers" 签名从 coversCache 再找一次
                    ImageLoader.load(requireContext(), src)
                        .apply(requestOptions)
                        .signature(ObjectKey("covers"))
                        .error(BookCover.defaultDrawable)
                        .dontTransform()
                        .downsample(DownsampleStrategy.NONE)
                        .into(binding.photoView)
                    return true
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean = false
            })
            .into(binding.photoView)
    }

    @SuppressLint("CheckResult")
    private fun doSaveImage(uri: Uri) {
        execute {
            val result = localImageFile?.let { file ->
                FileUtils.saveImage(file, uri)
            } ?: run {
                val glideFile = runCatching {
                    Glide.with(requireContext())
                        .downloadOnly()
                        .apply(requestOptions)
                        .onlyRetrieveFromCache(true)
                        .load(src)
                        .submit()
                        .get()
                }.getOrNull()

                glideFile?.let { FileUtils.saveImage(it, uri) } ?: FileUtils.saveImage(src, uri)
            }
            if (!result) error("保存图片失败")
        }.onError {
            ACache.get().remove(AppConst.imagePathKey)
            context?.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context?.toastOnUi("保存成功")
        }
    }

}
