package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.CacheManager
import io.legado.app.help.DefaultData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx

@Keep
@Suppress("ConstPropertyName")
object BookCover {

    private const val coverRuleConfigKey = "legadoCoverRuleConfig"
    const val configFileName = "coverRule.json"

    var drawBookName = true
        private set
    var drawBookAuthor = true
        private set
    lateinit var defaultDrawable: Drawable
        private set


    init {
        upDefaultCover()
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun upDefaultCover() {
        val isNightTheme = AppConfig.isNightTheme
        drawBookName = if (isNightTheme) {
            appCtx.getPrefBoolean(PreferKey.coverShowNameN, true)
        } else {
            appCtx.getPrefBoolean(PreferKey.coverShowName, true)
        }
        drawBookAuthor = if (isNightTheme) {
            appCtx.getPrefBoolean(PreferKey.coverShowAuthorN, true)
        } else {
            appCtx.getPrefBoolean(PreferKey.coverShowAuthor, true)
        }
        val key = if (isNightTheme) PreferKey.defaultCoverDark else PreferKey.defaultCover
        val path = appCtx.getPrefString(key)
        if (path.isNullOrBlank()) {
            defaultDrawable = appCtx.resources.getDrawable(R.drawable.image_cover_default, null)
            return
        }
        defaultDrawable = kotlin.runCatching {
            BitmapUtils.decodeBitmap(path, 600, 900)!!.toDrawable(appCtx.resources)
        }.getOrDefault(appCtx.resources.getDrawable(R.drawable.image_cover_default, null))
    }

    /**
     * 加载封面
     */
    fun load(
        requestManager: RequestManager,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        inBookshelf: Boolean = false,
        onLoadFinish: (() -> Unit)? = null,
    ): RequestBuilder<Drawable> {
        if (AppConfig.useDefaultCover) {
            return requestManager.load(defaultDrawable).centerCrop()
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        var builder = ImageLoader.load(requestManager, path, inBookshelf).apply(options)
        if (onLoadFinish != null) {
            builder = builder.addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable?>,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }
            })
        }
        return builder.error(defaultDrawable).centerCrop()
    }

    /**
     * 加载模糊封面
     */
    fun loadBlur(
        requestManager: RequestManager,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        inBookshelf: Boolean = false
    ): RequestBuilder<Drawable> {
        val blurOptions = RequestOptions().transform(BlurTransformation(), CenterCrop())
        if (AppConfig.useDefaultCover) {
            return requestManager.load(defaultDrawable).apply(blurOptions)
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(requestManager, path, inBookshelf).apply(options).apply(blurOptions)
            .error(requestManager.load(defaultDrawable).apply(blurOptions))
    }

    /**
     * 媒体通知/MediaSession 通用的默认封面 Bitmap。
     * 朗读和音频两个 Service 在没有真实封面时回退到这张。
     */
    val notificationDefaultCover: Bitmap by lazy {
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    }

    /**
     * 异步加载封面 Bitmap 用于通知或 MediaSession metadata。
     *
     * - 仅在尺寸大于 16x16 时才回调,过小/损坏的图被视为加载失败
     * - 加载失败/url 为空 时不调用 [onLoaded],调用方继续使用之前的 Bitmap 即可
     */
    fun loadNotificationCover(
        context: Context,
        url: String?,
        scope: CoroutineScope,
        onLoaded: (Bitmap) -> Unit,
    ): Coroutine<Bitmap>? {
        if (url.isNullOrBlank()) return null
        return Coroutine.async(scope) {
            ImageLoader.loadBitmap(context, url).submit().get()
        }.onSuccess { bitmap ->
            if (bitmap.width > 16 && bitmap.height > 16) {
                onLoaded(bitmap)
            }
        }
    }

    fun getCoverRule(): CoverRule {
        return getConfig() ?: DefaultData.coverRule
    }

    fun getConfig(): CoverRule? {
        return GSON.fromJsonObject<CoverRule>(CacheManager.get(coverRuleConfigKey)).getOrNull()
    }

    @Keep
    data class CoverRule(
        var enable: Boolean = true,
        var searchUrl: String,
        var coverRule: String
    )

}