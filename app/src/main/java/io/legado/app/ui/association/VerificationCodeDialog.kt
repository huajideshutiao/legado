package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.databinding.DialogVerificationCodeViewBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.onDismiss
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.model.ImageProvider
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 图片验证码对话框
 * 重构为使用 alert DSL 实现，菜单保持在右上角
 */
object VerificationCodeDialog {

    fun display(
        imageUrl: String,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int
    ) {
        val activity = io.legado.app.help.LifecycleHelp.currentActivity as? AppCompatActivity
        if (activity == null) {
            appCtx.toastOnUi("无法在后台显示验证码对话框")
            return
        }

        val binding = DialogVerificationCodeViewBinding.inflate(activity.layoutInflater)

        // 配置 Toolbar 以保持右上角菜单
        binding.toolBar.setTitle(R.string.verification_code)
        binding.toolBar.subtitle = sourceName
        binding.toolBar.inflateMenu(R.menu.verification_code)
        binding.toolBar.menu.applyTint(activity)

        val dialog = activity.alert {
            customView { binding.root }

            onDismiss {
                SourceVerificationHelp.checkResult(sourceOrigin!!)
            }
        }

        // 菜单点击事件
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_ok -> {
                    val verificationCode = binding.verificationCode.text.toString()
                    SourceVerificationHelp.setResult(sourceOrigin!!, verificationCode)
                    dialog.dismiss()
                }

                R.id.menu_disable_source -> {
                    sourceOrigin?.let { SourceHelp.enableSource(it, sourceType, false) }
                    dialog.dismiss()
                }

                R.id.menu_delete_source -> {
                    activity.alert(R.string.draw) {
                        setMessage(activity.getString(R.string.sure_del) + "\n" + sourceName)
                        noButton()
                        yesButton {
                            sourceOrigin?.let { SourceHelp.deleteSource(it, sourceType) }
                            dialog.dismiss()
                        }
                    }
                }
            }
            true
        }

        loadImage(activity, binding, imageUrl, sourceOrigin)

        binding.verificationCodeImageView.setOnClickListener {
            activity.showDialogFragment(PhotoDialog(imageUrl, sourceOrigin))
        }
    }

    @SuppressLint("CheckResult")
    private fun loadImage(
        activity: AppCompatActivity,
        binding: DialogVerificationCodeViewBinding,
        url: String,
        sourceUrl: String?
    ) {
        ImageProvider.remove(url)
        ImageLoader.loadBitmap(activity, url).apply {
            sourceUrl?.let {
                apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it))
            }
        }.error(R.drawable.image_loading_error)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap?>,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    val bitmap = resource.copy(resource.config!!, true)
                    ImageProvider.put(url, bitmap)
                    return false
                }
            })
            .into(binding.verificationCodeImageView)
    }

}
