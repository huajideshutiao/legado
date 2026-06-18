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

    /**
     * 拿默认封面的一个独立壳子 -- 共享底层 Bitmap/ninePatchChunk,
     * 但各自有独立的 bounds/alpha,避免多个 ImageView 共享同一实例时 bounds 互相串扰。
     * 没有 ConstantState 的极少数 Drawable (理论上不会有) 回退共享原实例。
     */
    fun newDefaultDrawable(): Drawable {
        return defaultDrawable.constantState?.newDrawable(appCtx.resources) ?: defaultDrawable
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
        val fallback = appCtx.resources.getDrawable(R.drawable.image_cover_default, null)
        if (path.isNullOrBlank()) {
            defaultDrawable = fallback
            return
        }
        defaultDrawable = kotlin.runCatching {
            // .9.png 走 createFromPath 才能保留 ninePatchChunk;
            // 普通图继续走下采样路径避免大图 OOM
            if (path.endsWith(".9.png", ignoreCase = true)) {
                Drawable.createFromPath(path)
            } else {
                BitmapUtils.decodeBitmap(path, 300, 400)?.toDrawable(appCtx.resources)
            } ?: fallback
        }.getOrDefault(fallback)
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
            return requestManager.load(newDefaultDrawable()).centerCrop()
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
        return builder.error(newDefaultDrawable()).centerCrop()
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
            return requestManager.load(newDefaultDrawable()).apply(blurOptions)
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(requestManager, path, inBookshelf).apply(options).apply(blurOptions)
            .error(requestManager.load(newDefaultDrawable()).apply(blurOptions))
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
        return getConfig() ?: CoverRule(enable = false, searchUrl = "", coverRule = "")
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