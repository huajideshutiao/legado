package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.help.FileSaveHelper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import androidx.lifecycle.lifecycleScope
import io.legado.app.utils.setLayout
import io.legado.app.utils.toast
import io.legado.app.utils.viewbindingdelegate.viewBinding

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
    private var imageSrc: String? = null
    private val saveImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            saveImage(imageSrc, uri)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @SuppressLint("CheckResult")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val arguments = arguments ?: return
        val src = arguments.getString("src") ?: return
        imageSrc = src
        ImageProvider.get(src)?.let {
            binding.photoView.setImageBitmap(it)
            setLongClick()
            return
        }
        val file = ReadBook.book?.let { book ->
            BookHelp.getImage(book, src)
        }
        if (file?.exists() == true) {
            Glide.with(requireContext()).load(file)
                .error(R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(binding.photoView)
        } else {
            ImageLoader.load(requireContext(), src).apply {
                arguments.getString("sourceOrigin")?.let { sourceOrigin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin))
                }
            }.error(BookCover.defaultDrawable)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .into(binding.photoView)
        }
        setLongClick()
    }

    private fun setLongClick() {
        binding.photoView.setOnLongClickListener {
            saveImage(imageSrc)
            true
        }
    }

    private fun saveImage(url: String?) {
        url?.let {
            this.imageSrc = it
            val path = ACache.get().getAsString(imagePathKey)
            if (path.isNullOrEmpty()) {
                selectSaveFolder(it)
            } else {
                saveImage(it, path.toUri())
            }
        }
    }

    private fun selectSaveFolder(url: String) {
        this.imageSrc = url
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    private fun saveImage(url: String?, uri: android.net.Uri) {
        url?.let {
            lifecycleScope.launch {
                kotlin.runCatching {
                    val bitmap = ImageProvider.get(it) ?: return@launch
                    val fileName = "image_${System.currentTimeMillis()}.png"
                    FileSaveHelper.saveFile(requireContext(), uri, fileName, bitmap)
                    toast(R.string.save_success)
                }.onFailure {
                    toast(R.string.save_fail)
                }
            }
        }
    }

}
