package io.legado.app.help.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.utils.isFilePath
import io.legado.app.utils.lifecycle
import java.io.File

//https://bumptech.github.io/glide/doc/generatedapi.html
//Instead of GlideApp, use com.bumptech.Glide
@Suppress("unused")
object ImageLoader {

    /**
     * 自动判断path类型
     */
    fun load(
        requestManager: RequestManager,
        path: String?,
        inBookshelf: Boolean = false
    ): RequestBuilder<Drawable> {
        return requestManager.load(if (path.isFilePath()) File(path) else path)
            .let { if (inBookshelf) it.signature(ObjectKey("covers")) else it }
    }

    fun load(
        context: Context,
        path: String?,
        inBookshelf: Boolean = false
    ): RequestBuilder<Drawable> {
        return load(Glide.with(context), path, inBookshelf)
    }

    fun load(
        fragment: Fragment,
        lifecycle: Lifecycle,
        path: String?,
        inBookshelf: Boolean = false
    ): RequestBuilder<Drawable> {
        return load(Glide.with(fragment).lifecycle(lifecycle), path, inBookshelf)
    }

    fun loadBitmap(context: Context, path: String?): RequestBuilder<Bitmap> {
        val requestManager = Glide.with(context).`as`(Bitmap::class.java)
        return when {
            path.isFilePath() -> requestManager.load(File(path))
            else -> requestManager.load(path)
        }.signature(ObjectKey("default"))
    }

    /**
     * 加载漫画图片
     */
    fun loadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        transformation: Transformation<Bitmap>? = null,
    ): RequestBuilder<Drawable> {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(OkHttpModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return load(context, path)
            .apply(options)
            .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(true).let {
                if (transformation != null) {
                    it.transform(transformation)
                } else {
                    it
                }
            }
    }

    fun preloadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<File?> {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(OkHttpModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return Glide.with(context)
            .downloadOnly()
            .apply(options)
            .load(path)
    }

}
