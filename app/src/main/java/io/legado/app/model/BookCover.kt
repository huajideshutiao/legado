package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import androidx.collection.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.Transformation
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.image.coverDiskCacheKey
import io.legado.app.help.image.sourceOrigin
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.centerCrop
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isWifiConnect
import io.legado.app.utils.putPrefString
import io.legado.app.utils.topCrop
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * 封面比例枚举 -- 下沉至 shared [BookCoverShared.CoverRatio]。
 * 顶层 typealias 保持原 `CoverRatio` 简短引用, 同时让 `BookCover.CoverRatio` 调用方
 * 仅需删除 `BookCover.` 前缀即可 (typealias 与 BookCover 同包, 自动可见)。
 */
typealias CoverRatio = BookCoverShared.CoverRatio

/**
 * 默认封面图集 entry -- 下沉至 shared [BookCoverShared.DefaultCoverEntry]。
 * 纯数据类 (id, ninePatch), 不含路径计算逻辑。
 */
typealias DefaultCoverEntry = BookCoverShared.DefaultCoverEntry

/**
 * 计算默认封面烘焙后的本地路径 (.9.png 或 webp)。
 *
 * 顶层扩展函数, 保持原 `entry.bakedPath(ratio)` 签名不变,
 * 内部委托 shared [BookCoverShared.bakedPath], 注入 app 端专属的 [BookCover.coversDir]。
 * desktop 端如需使用, 可自行包装注入 desktop 的 coversDir。
 */
fun DefaultCoverEntry.bakedPath(ratio: CoverRatio): String =
    BookCoverShared.bakedPath(BookCover.coversDir.absolutePath, this, ratio)

@Keep
@Suppress("ConstPropertyName")
object BookCover {

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
    // internal: 供顶层扩展函数 DefaultCoverEntry.bakedPath 注入此目录, 委托 shared 路径计算
    internal val coversDir: File by lazy {
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
        drawBookName = if (isNightTheme) AppConfig.coverShowNameN else AppConfig.coverShowName
        drawBookAuthor = if (isNightTheme) AppConfig.coverShowAuthorN else AppConfig.coverShowAuthor
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
     * 媒体通知/MediaSession 通用的默认封面 Bitmap。
     */
    val notificationDefaultCover: Bitmap by lazy {
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    }

    /**
     * suspend 取封面 Bitmap: 通知/PhotoDialog/getCover 用。useDefaultCover 或空路径回退默认封面。
     */
    suspend fun loadCoverBitmap(
        context: Context,
        path: String?,
        sourceOrigin: String? = null,
        seed: String? = null,
        ratio: CoverRatio = CoverRatio.NOVEL,
    ): Bitmap {
        if (AppConfig.useDefaultCover || path.isNullOrBlank()) {
            return newDefaultDrawable(ratio, seed).toBitmap()
        }
        val loader = coil3.SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context as PlatformContext)
            .data(path)
            .sourceOrigin(sourceOrigin)
            .bookshelfCoverCache(path)
            .build()
        val result = loader.execute(request)
        return if (result is SuccessResult) {
            result.image?.toBitmap() ?: newDefaultDrawable(ratio, seed).toBitmap()
        } else {
            newDefaultDrawable(ratio, seed).toBitmap()
        }
    }

    /**
     * 通知封面: 保留旧签名(服务调用点不改), 内部走 [loadCoverBitmap]。
     */
    fun loadNotificationCover(
        context: Context,
        url: String?,
        scope: CoroutineScope,
        onLoaded: (Bitmap) -> Unit,
    ): Coroutine<Bitmap>? {
        if (url.isNullOrBlank()) return null
        return Coroutine.async(scope) {
            loadCoverBitmap(context, url, seed = null)
        }.onSuccess { bitmap ->
            if (bitmap.width > 16 && bitmap.height > 16) {
                onLoaded(bitmap)
            }
        }
    }

}

/**
 * 封面加载配置: 调用方 `imageView.load(path) { coverConfig(...) }`。
 * useDefaultCover 由调用方自行判断后 load 默认 Drawable(走默认封面 9-patch 路径)。
 * 封面缓存按 path 默认隔离(Coil3 默认 key 策略), 调用方无需手动设置 cache key。
 * loadOnlyWifi: 非 wifi 时禁网络仅走缓存, 对齐原 Glide OkHttpStreamFetcher"只在wifi加载图片"
 * (Glide 同样只拦 fetch, 磁盘缓存命中仍显示)。
 */
fun ImageRequest.Builder.coverConfig(
    seed: String? = null,
    ratio: CoverRatio = CoverRatio.NOVEL,
    sourceOrigin: String? = null,
    loadOnlyWifi: Boolean = false,
    onLoadFinish: (() -> Unit)? = null,
): ImageRequest.Builder = apply {
    sourceOrigin(sourceOrigin)
    if (loadOnlyWifi && !appCtx.isWifiConnect) {
        networkCachePolicy(CachePolicy.DISABLED)
    }
    if (onLoadFinish != null) {
        listener(
            onSuccess = { _, _ -> onLoadFinish() },
            onError = { _, _ -> onLoadFinish() },
        )
    }
}

/**
 * 书架封面走**持久**磁盘缓存分区 (应用数据目录, 系统/用户清缓存都清不掉)。
 * 对照原版 `ImageLoader.load(.., inBookshelf = true)` 的 `.signature(ObjectKey("covers"))`
 * + `MultiDiskCacheFactory` 分流。
 */
fun ImageRequest.Builder.bookshelfCoverCache(path: String?): ImageRequest.Builder = apply {
    if (!path.isNullOrBlank()) diskCacheKey(coverDiskCacheKey(path))
}

/**
 * 模糊封面配置: 调用方 `imageView.load(path) { blurConfig(...) }`。
 * 含 BlurTransformation + 调用方传入的 extraTransformations(如 BookInfoBgTransformation)。
 */
fun ImageRequest.Builder.blurConfig(
    seed: String? = null,
    ratio: CoverRatio = CoverRatio.NOVEL,
    sourceOrigin: String? = null,
    extraTransformations: List<Transformation> = emptyList(),
): ImageRequest.Builder = apply {
    sourceOrigin(sourceOrigin)
    transformations(listOf(BlurTransformation()) + extraTransformations)
}
