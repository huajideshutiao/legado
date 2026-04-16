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
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.progress.ProgressManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.launch


open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null
    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3

    companion object {
        private val preloadJobs =
            java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Result<Any?>>>()

        fun preloadImage(imageUrl: String) {
            if (preloadJobs.containsKey(imageUrl)) return
            preloadJobs[imageUrl] = CoroutineScope(IO).async {
                runCatching { ImageLoader.loadManga(imageUrl, coroutineContext) }
            }
        }

        fun cancelPreload(imageUrl: String) {
            preloadJobs.remove(imageUrl)?.cancel()
        }

        fun cancelJobsOutside(validUrls: Set<String>) {
            preloadJobs.keys.forEach {
                if (it !in validUrls) cancelPreload(it)
            }
        }

        fun cancelAllPreload() {
            preloadJobs.values.forEach { it.cancel() }
            preloadJobs.clear()
        }
    }

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
        mImage.tag = imageUrl
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        mRetry?.isGone = true
        mProgress.isVisible = true
        ProgressManager.removeListener(imageUrl)
        ProgressManager.addListener(imageUrl) { _, percentage, _, _ ->
            @SuppressLint("SetTextI18n")
            mProgress.text = "$percentage%"
        }

        val deferred = preloadJobs.getOrPut(imageUrl) {
            CoroutineScope(IO).async {
                runCatching { ImageLoader.loadManga(imageUrl, coroutineContext) }
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = deferred.await()
                if (mImage.tag == imageUrl) {
                    displayImageResult(
                        imageUrl,
                        result.getOrNull(),
                        isHorizontal,
                        isLastImage,
                        transformation
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (mImage.tag == imageUrl) {
                    loadImageWithRetry(imageUrl, isHorizontal, isLastImage, transformation)
                }
            } catch (e: Exception) {
                if (mImage.tag == imageUrl) {
                    displayImageResult(
                        imageUrl,
                        null,
                        isHorizontal,
                        isLastImage,
                        transformation
                    )
                }
            }
        }
    }

    private fun displayImageResult(
        imageUrl: String,
        data: Any?,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?
    ) {
        preloadJobs.remove(imageUrl)
        if (data == null) {
            mFlProgress.isVisible = true
            mLoading.isGone = true
            mRetry?.isVisible = true
            mProgress.isGone = true
            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        } else {
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

            Glide.with(context)
                .asBitmap()
                .load(data)
                .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .apply { transformation?.let { transform(it) } }
                .into(mImage)
        }
    }
}
