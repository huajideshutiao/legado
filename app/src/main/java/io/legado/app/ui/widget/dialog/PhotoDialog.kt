package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
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

    @SuppressLint("CheckResult")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments ?: return
        val imageSrc = args.getString("src") ?: return
        src = imageSrc
        // 预先查找本地图片文件
        localImageFile = ReadBook.book?.let { book ->
            BookHelp.getImage(book, src).takeIf { it.exists() }
        }
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
        ImageProvider.get(src)?.let {
            binding.photoView.setImageBitmap(it)
            return
        }
        val file = localImageFile
        if (file != null) {
            Glide.with(requireContext()).load(file)
                .error(R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(binding.photoView)
        } else {
            ImageLoader.load(requireContext(), src).apply {
                args.getString("sourceOrigin")?.let { sourceOrigin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin))
                }
            }.error(BookCover.defaultDrawable)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .into(binding.photoView)
        }
    }

    @SuppressLint("CheckResult")
    private fun doSaveImage(uri: Uri) {
        execute {
            val result = localImageFile?.let { file ->
                FileUtils.saveImage(file, uri)
            } ?: run {
                try {
                    val options = RequestOptions()
                    arguments?.getString("sourceOrigin")?.let { sourceOrigin ->
                        options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
                    }
                    val glideFile = Glide.with(requireContext())
                        .downloadOnly()
                        .apply(options)
                        .onlyRetrieveFromCache(true)
                        .load(src)
                        .submit()
                        .get()
                    FileUtils.saveImage(glideFile, uri)
                } catch (_: Exception) {
                    FileUtils.saveImage(src, uri)
                }
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
