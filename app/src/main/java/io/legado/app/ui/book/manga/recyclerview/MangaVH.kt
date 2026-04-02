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
import io.legado.app.model.ReadManga
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null
    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3

    data class PreloadJob(
        val job: Job,
        var result: Any? = null,
        var error: Exception? = null,
        var isCompleted: Boolean = false
    )

    companion object {
        private val preloadJobs = java.util.concurrent.ConcurrentHashMap<String, PreloadJob>()

        fun preloadImage(imageUrl: String) {
            if (preloadJobs.containsKey(imageUrl)) return

            val preloadJob = PreloadJob(Job())
            preloadJobs[imageUrl] = preloadJob

            CoroutineScope(IO).launch(preloadJob.job) {
                try {
                    preloadJob.result = ImageLoader.loadManga(imageUrl, coroutineContext)
                } catch (e: Exception) {
                    preloadJob.error = e
                    e.printOnDebug()
                } finally {
                    preloadJob.isCompleted = true
                }
            }
        }

        fun cancelPreload(imageUrl: String) {
            preloadJobs.remove(imageUrl)?.job?.cancel()
        }

        fun cancelJobsOutside(validUrls: Set<String>) {
            val keysToCancel = preloadJobs.keys().toList().filter { it !in validUrls }
            for (key in keysToCancel) {
                preloadJobs.remove(key)?.job?.cancel()
            }
        }

        fun cancelAllPreload() {
            preloadJobs.values.toList().forEach { it.job.cancel() }
            preloadJobs.clear()
        }

        fun getPreloadJob(imageUrl: String): PreloadJob? = preloadJobs[imageUrl]

        fun removePreloadJob(imageUrl: String) {
            preloadJobs.remove(imageUrl)
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
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        mRetry?.isGone = true
        mProgress.isVisible = true
        ProgressManager.removeListener(imageUrl)
        ProgressManager.addListener(imageUrl) { _, percentage, _, _ ->
            @SuppressLint("SetTextI18n")
            mProgress.text = "$percentage%"
        }
        mImage.tag = imageUrl

        val existingPreloadJob = getPreloadJob(imageUrl)
        if (existingPreloadJob != null) {
            CoroutineScope(IO).launch {
                if (existingPreloadJob.job.isActive) {
                    existingPreloadJob.job.join()
                }
                withContext(Dispatchers.Main) {
                    if (mImage.tag != imageUrl) return@withContext
                    val result = existingPreloadJob.result
                    val glide = if (result is ByteArray) {
                        Glide.with(context).load(result)
                    } else if (result is java.io.File) {
                        Glide.with(context).load(result)
                    } else {
                        null
                    }
                    displayImageResult(
                        imageUrl,
                        glide,
                        existingPreloadJob.error,
                        isHorizontal,
                        isLastImage,
                        transformation
                    )
                }
            }
            return
        }

        val preloadJob = PreloadJob(Job())
        preloadJobs[imageUrl] = preloadJob
        CoroutineScope(IO).launch(preloadJob.job) {
            var glide: Any? = null
            var error: Exception? = null
            try {
                val result = ImageLoader.loadManga(imageUrl, coroutineContext)
                preloadJob.result = result
                if (result is java.io.File) {
                    glide = Glide.with(context).load(result)
                } else if (result is ByteArray) {
                    glide = Glide.with(context).load(result)
                }
            } catch (e: Exception) {
                e.printOnDebug()
                error = e
                preloadJob.error = e
            } finally {
                preloadJob.isCompleted = true
            }

            withContext(Dispatchers.Main) {
                if (mImage.tag == imageUrl) {
                    displayImageResult(
                        imageUrl,
                        glide,
                        error,
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
        glide: Any?,
        error: Exception?,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?
    ) {
        if (glide == null) {
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

            @Suppress("UNCHECKED_CAST")
            (glide as com.bumptech.glide.RequestBuilder<Bitmap>)
                .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .apply { transformation?.let { transform(it) } }
                .into(mImage)
        }
        removePreloadJob(imageUrl)
    }
}
