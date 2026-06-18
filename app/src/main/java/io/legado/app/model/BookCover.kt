package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import androidx.collection.LruCache
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
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
import io.legado.app.model.BookCover.load
import io.legado.app.model.BookCover.upDefaultCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.centerCrop
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.topCrop
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

@Keep
@Suppress("ConstPropertyName")
object BookCover {

    private const val coverRuleConfigKey = "legadoCoverRuleConfig"
    const val configFileName = "coverRule.json"

    /**
     * 封面比例 -- novel 用于普通书籍 (3:4, 居中裁剪);
     * video 用于视频源 (16:9, 顶部裁剪保留封面信息).
     * bakeW/bakeH 是按 480dpi 烘焙落盘的目标像素尺寸。
     */
    enum class CoverRatio(
        val widthRatio: Int,
        val heightRatio: Int,
        val bakeW: Int,
        val bakeH: Int,
        val fileTag: String,
    ) {
        NOVEL(3, 4, 300, 400, "novel"),
        VIDEO(16, 9, 720, 405, "video"),
    }

    var drawBookName = true
        private set
    var drawBookAuthor = true
        private set

    /**
     * 当前生效的图集 (按比例) -- entry 是已烘焙的本地文件路径,或 .9.png 原路径。
     * 由 [upDefaultCover] 在主题/偏好变更时刷新。
     */
    private var dayCovers: List<DefaultCoverEntry> = emptyList()
    private var nightCovers: List<DefaultCoverEntry> = emptyList()

    /**
     * 解码缓存:同一张烘焙图被多个 ImageView 复用时只解一次。
     * 取出的 Drawable 必须 .constantState.newDrawable() 后再用,避免 bounds 串扰。
     */
    private val drawableCache = LruCache<String, Drawable>(16)

    // 列表滑动时 bakedPath 会被频繁调用,提前 mkdirs 一次就够了
    private val coversDir: File by lazy {
        FileUtils.createFolderIfNotExist(appCtx.externalFiles, "covers", "default")
    }

    init {
        upDefaultCover()
    }

    /**
     * 兼容旧用法:不带参数的随机默认封面,等价于 NOVEL 比例 + 随机种子。
     */
    fun newDefaultDrawable(): Drawable = newDefaultDrawable(CoverRatio.NOVEL, null)

    /**
     * 取一张默认封面的独立壳子。
     * @param ratio 目标比例,决定从哪份烘焙集合里选
     * @param seed 稳定挑选种子 (一般是书名),为 null 时随机
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    fun newDefaultDrawable(ratio: CoverRatio, seed: String?): Drawable {
        val list = currentCovers()
        val fallback = appCtx.resources.getDrawable(R.drawable.image_cover_default, null)
        if (list.isEmpty()) {
            return shellOf(fallback)
        }
        val idx = if (seed.isNullOrBlank()) {
            Random.nextInt(list.size)
        } else {
            (seed.hashCode().rem(list.size) + list.size).rem(list.size)
        }
        val entry = list[idx]
        val path = entry.bakedPath(ratio)
        val cacheKey = "${ratio.name}|$path"
        val cached = drawableCache.get(cacheKey)
        if (cached != null) return shellOf(cached)
        val loaded = kotlin.runCatching {
            if (entry.ninePatch) {
                // .9.png 走 createFromPath 才能保留 ninePatchChunk,让外层 FIT_XY 拉伸
                Drawable.createFromPath(path)
            } else {
                BitmapFactory.decodeFile(path)?.toDrawable(appCtx.resources)
            }
        }.getOrNull() ?: return shellOf(fallback)
        drawableCache.put(cacheKey, loaded)
        return shellOf(loaded)
    }

    private fun shellOf(drawable: Drawable): Drawable {
        return drawable.constantState?.newDrawable(appCtx.resources) ?: drawable
    }

    private fun currentCovers(): List<DefaultCoverEntry> {
        return if (AppConfig.isNightTheme) nightCovers else dayCovers
    }

    /**
     * 给 BookController/MediaSession 等只要"任意一张"封面 Bitmap 的场景用。
     * 没有图集时回落到内置资源。
     */
    val defaultDrawable: Drawable
        get() = newDefaultDrawable()

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
        dayCovers = loadCovers(PreferKey.defaultCover)
        nightCovers = loadCovers(PreferKey.defaultCoverDark)
        drawableCache.evictAll()
    }

    private fun loadCovers(prefKey: String): List<DefaultCoverEntry> {
        val raw = appCtx.getPrefString(prefKey).orEmpty()
        if (raw.isBlank()) return emptyList()
        // 旧版本存的是单个路径或路径列表 -- 解析失败的旧值忽略,用户重新选图即可。
        val entries = GSON.fromJsonArray<DefaultCoverEntry>(raw).getOrNull() ?: return emptyList()
        return entries.filter { entry ->
            // novel 烘焙文件(或 .9.png)存在即视为有效。
            File(entry.bakedPath(CoverRatio.NOVEL)).exists()
        }
    }

    /**
     * 把用户选中的图烘焙后落盘,并写入 prefs。原图不保留。
     * - 普通图:解码后按 NOVEL/VIDEO 各裁一份 webp。
     * - .9.png:不烘焙,只拷贝原文件,运行时由 NinePatchDrawable 自适应尺寸。
     */
    fun addDefaultCover(prefKey: String, sourceBytes: ByteArray, originalName: String) {
        val isNinePatch = originalName.endsWith(".9.png", ignoreCase = true)
        val md5 = MD5Utils.md5Encode(sourceBytes.inputStream())
        val entry = DefaultCoverEntry(md5, isNinePatch)
        val existing = currentEntries(prefKey).toMutableList()
        // 相同图片再次添加同一 prefs 直接忽略,避免重复烘焙写盘
        if (existing.any { it.id == entry.id }) return
        if (isNinePatch) {
            val out = File(coversDir, "$md5.9.png")
            if (!out.exists()) FileOutputStream(out).use { it.write(sourceBytes) }
        } else {
            val src = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
                ?: error("decode image failed")
            try {
                bakeAndWrite(src, md5, CoverRatio.NOVEL)
                bakeAndWrite(src, md5, CoverRatio.VIDEO)
            } finally {
                src.recycle()
            }
        }
        existing.add(entry)
        appCtx.putPrefString(prefKey, GSON.toJson(existing))
        upDefaultCover()
    }

    fun removeDefaultCover(prefKey: String, id: String) {
        val existing = currentEntries(prefKey).toMutableList()
        val target = existing.firstOrNull { it.id == id } ?: return
        existing.remove(target)
        appCtx.putPrefString(prefKey, GSON.toJson(existing))
        runCatching {
            if (target.ninePatch) {
                File(coversDir, "${target.id}.9.png").delete()
            } else {
                CoverRatio.entries.forEach { r ->
                    File(coversDir, "${target.id}_${r.fileTag}.webp").delete()
                }
            }
        }
        upDefaultCover()
    }

    fun clearDefaultCovers(prefKey: String) {
        currentEntries(prefKey).toList().forEach { removeDefaultCover(prefKey, it.id) }
        appCtx.putPrefString(prefKey, "")
        upDefaultCover()
    }

    /**
     * 列出某偏好下当前已选的图集。UI 直接用 entry.bakedPath(NOVEL) 显示。
     */
    fun listDefaultCovers(prefKey: String): List<DefaultCoverEntry> = currentEntries(prefKey)

    private fun currentEntries(prefKey: String): List<DefaultCoverEntry> {
        val raw = appCtx.getPrefString(prefKey).orEmpty()
        if (raw.isBlank()) return emptyList()
        return GSON.fromJsonArray<DefaultCoverEntry>(raw).getOrNull().orEmpty()
    }

    private fun bakeAndWrite(src: Bitmap, md5: String, ratio: CoverRatio) {
        val out = File(coversDir, "${md5}_${ratio.fileTag}.webp")
        // 先按目标比例裁剪 (centerCrop 内部会按需缩放到目标 bakeW/bakeH)
        val cropped = if (ratio == CoverRatio.VIDEO) {
            src.topCrop(ratio.bakeW, ratio.bakeH)
        } else {
            src.centerCrop(ratio.bakeW, ratio.bakeH)
        }
        FileOutputStream(out).use { os ->
            @Suppress("DEPRECATION")
            cropped.compress(Bitmap.CompressFormat.WEBP, 85, os)
        }
        if (cropped !== src) cropped.recycle()
    }

    /**
     * 加载封面
     * @param seed 默认封面回退时的稳定挑选种子 (通常是书名),为 null 时随机。
     *             必须与同一书的 loadBlur 用同一 seed,否则失败回退会撞到不同的默认图。
     */
    fun load(
        requestManager: RequestManager,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        inBookshelf: Boolean = false,
        seed: String? = null,
        ratio: CoverRatio = CoverRatio.NOVEL,
        onLoadFinish: (() -> Unit)? = null,
    ): RequestBuilder<Drawable> {
        if (AppConfig.useDefaultCover) {
            return requestManager.load(newDefaultDrawable(ratio, seed)).centerCrop()
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
        return builder.error(newDefaultDrawable(ratio, seed)).centerCrop()
    }

    /**
     * 加载模糊封面
     * @param seed 与 [load] 一致的默认封面种子,保证失败回退时前景/背景挑到同一张图。
     * @param extraTransformations 额外的 Bitmap 变换 (如 BookInfoBgTransformation 的渐变),
     *                             会同时作用于网络结果与默认封面回退,避免外层再叠一层 .error。
     */
    fun loadBlur(
        requestManager: RequestManager,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        inBookshelf: Boolean = false,
        seed: String? = null,
        ratio: CoverRatio = CoverRatio.NOVEL,
        extraTransformations: List<Transformation<Bitmap>> = emptyList(),
    ): RequestBuilder<Drawable> {
        val allTransforms = arrayOf<Transformation<Bitmap>>(BlurTransformation(), CenterCrop()) +
            extraTransformations
        val blurOptions = RequestOptions().transform(*allTransforms)
        if (AppConfig.useDefaultCover) {
            return requestManager.load(newDefaultDrawable(ratio, seed)).apply(blurOptions)
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(requestManager, path, inBookshelf).apply(options).apply(blurOptions)
            .error(requestManager.load(newDefaultDrawable(ratio, seed)).apply(blurOptions))
    }

    /**
     * 媒体通知/MediaSession 通用的默认封面 Bitmap。
     */
    val notificationDefaultCover: Bitmap by lazy {
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    }

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
        var coverRule: String,
    )

    /**
     * 默认封面图集中的一项。
     * - 普通图:bakedPath(ratio) 指向 `covers/default/{id}_{tag}.webp` 的烘焙结果,原图不保留。
     * - .9.png:不烘焙,所有 ratio 都指向 `covers/default/{id}.9.png` (NinePatchDrawable 会按容器自适应)。
     */
    @Keep
    data class DefaultCoverEntry(
        val id: String,
        val ninePatch: Boolean = false,
    ) {
        fun bakedPath(ratio: CoverRatio): String {
            val file = if (ninePatch) {
                File(coversDir, "$id.9.png")
            } else {
                File(coversDir, "${id}_${ratio.fileTag}.webp")
            }
            return file.absolutePath
        }
    }

}
