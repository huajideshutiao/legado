package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.help.glide.MangaModel
import io.legado.app.help.glide.progress.ProgressManager

open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null
    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3

    fun initComponent(
        loading: ProgressBar,
        image: AppCompatImageView,
        progress: TextView,
        button: Button? = null,
        flProgress: FrameLayout,
    ) {
        mLoading = loading
        mImage = image
        mRetry = button
        mProgress = progress
        mFlProgress = flProgress
    }

    @SuppressLint("CheckResult")
    fun loadImageWithRetry(
        imageUrl: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?
    ) {
        //if (mImage.tag == imageUrl && mRetry?.isVisible != true) return
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        mRetry?.isGone = true
        mProgress.isVisible = true
        ProgressManager.removeListener(imageUrl)
        ProgressManager.addListener(imageUrl) { _, percentage, bytesRead, totalBytes ->
            if (mImage.tag == imageUrl) {
                @SuppressLint("SetTextI18n")
                mProgress.text = if (totalBytes > 0) {
                    "$percentage%"
                } else {
                    "${bytesRead / 1024}kb"
                }
            }
        }
        mImage.tag = imageUrl
        Glide.with(context)
            .asBitmap()
            .load(MangaModel(imageUrl))
            .override(context.resources.displayMetrics.widthPixels, Target.SIZE_ORIGINAL)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .apply { transformation?.let { transform(it) } }
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (mImage.tag == imageUrl) {
                        mFlProgress.isVisible = true
                        mLoading.isGone = true
                        mRetry?.isVisible = true
                        mProgress.isGone = true
                        itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                            height = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    if (mImage.tag == imageUrl) {
                        mFlProgress.isGone = true
                        if (isHorizontal) {
                            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                                height = ViewGroup.LayoutParams.MATCH_PARENT
                            }
                            itemView.minimumHeight = 0
                            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                height = ViewGroup.LayoutParams.MATCH_PARENT
                                gravity = Gravity.CENTER
                            }
                            mImage.scaleType = ImageView.ScaleType.FIT_CENTER
                        } else {
                            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            itemView.minimumHeight = if (isLastImage) minHeight else 0
                            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                gravity = Gravity.NO_GRAVITY
                                height =
                                    if (isLastImage) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
                            }
                            mImage.scaleType = ImageView.ScaleType.FIT_XY
                        }
                    }
                    return false
                }
            })
            .into(mImage)
    }
}
