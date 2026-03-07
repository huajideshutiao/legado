package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
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
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.script.rhino.runScriptWithContext
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookHelp.isImageExist
import io.legado.app.help.book.BookHelp.writeImage
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.ReadManga
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null

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
        CoroutineScope(IO).launch {
            var glide: RequestBuilder<Drawable>? = null
            try {
                if (isImageExist(ReadManga.book!!, imageUrl)) {
                    glide = Glide.with(context).load(BookHelp.getImage(ReadManga.book!!, imageUrl))
                } else {
                    val analyzeUrl = AnalyzeUrl(
                        imageUrl, source = ReadManga.bookSource, coroutineContext = coroutineContext
                    )
                    val bytes = analyzeUrl.getByteArrayAwait()
                    //某些图片被加密，需要进一步解密
                    runScriptWithContext {
                        ImageUtils.decode(
                            imageUrl, bytes, isCover = false, ReadManga.bookSource, ReadManga.book
                        )
                    }?.let {
                        writeImage(ReadManga.book!!, imageUrl, it)
                        glide = Glide.with(context).load(it)
                    }
                }
            } catch (e: Exception) {
                e.printOnDebug()
            } finally {
                withContext(Dispatchers.Main) {
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
                        if (!isHorizontal) {
                            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                gravity = Gravity.NO_GRAVITY
                            }
                            if (isLastImage) {
                                mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                                }
                                //itemView.minimumHeight = minHeight
                            } else {
                                mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                    height = ViewGroup.LayoutParams.MATCH_PARENT
                                }
                            }
                            itemView.minimumHeight = 0
                            mImage.scaleType = ImageView.ScaleType.FIT_XY
                        } else {
                            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                                height = ViewGroup.LayoutParams.MATCH_PARENT
                            }
                            itemView.minimumHeight = 0
                            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                height = ViewGroup.LayoutParams.MATCH_PARENT
                                gravity = Gravity.CENTER
                            }
                            mImage.scaleType = ImageView.ScaleType.FIT_CENTER
                        }

                        glide!!.override(
                            context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL
                        ).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).let {
                            if (transformation != null) {
                                it.transform(transformation)
                            } else {
                                it
                            }
                        }.into(mImage)
                    }
                }
            }
        }
    }
}