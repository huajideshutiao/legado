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
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.Transformation
import io.legado.app.App
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.image.sourceOrigin
import io.legado.app.model.BookCover.currentCovers
import io.legado.app.model.BookCover.loadCoverBitmap
import io.legado.app.model.BookCover.newDefaultDrawable
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import legado.shared.generated.resources.Res
import java.io.File
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
object BookCover {

    /**
     * 解码缓存:同一张烘焙图被多个消费点复用时只解一次。
     * 取出的 Drawable 必须 .constantState.newDrawable() 后再用,避免 bounds 串扰。
     * cacheKey 含路径 (md5 id), 增删封面只会产生新 key, 无昼夜/内容串扰。
     *
     * 图集列表缓存已下沉 [BookCoverShared.listDefaultCovers] (按 prefs 原始串记忆化),
     * 昼夜切换读 [currentCovers] 惰性取、增删封面写入新串自动失效, 本对象不再持有列表状态。
     */
    private val drawableCache = LruCache<String, Drawable>(16)

    // 列表滑动时 bakedPath 会被频繁调用,提前 mkdirs 一次就够了
    // internal: 供顶层扩展函数 DefaultCoverEntry.bakedPath 注入此目录, 委托 shared 路径计算
    internal val coversDir: File by lazy {
        FileUtils.createFolderIfNotExist(App.instance.externalFiles, "covers", "default")
    }

    /**
     * 内置兜底封面: 已随 KMP 化迁到 shared composeResources (app res 不再保留该图)。
     * lazy 惰性读一次; Res.readBytes 为 suspend, 同步接口用 runBlocking 包装
     * (与 NativeDefaultDataResourceProvider 同款做法)。
     */
    private val builtinCoverDrawable: Drawable by lazy {
        val bytes = runBlocking { Res.readBytes("drawable/image_cover_default.jpg") }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.toDrawable(App.instance.resources)
            ?: error("decode builtin default cover failed")
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
        val fallback = builtinCoverDrawable
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
                BitmapFactory.decodeFile(path)?.toDrawable(App.instance.resources)
            }
        }.getOrNull() ?: return shellOf(fallback)
        drawableCache.put(cacheKey, loaded)
        return shellOf(loaded)
    }

    private fun shellOf(drawable: Drawable): Drawable {
        return drawable.constantState?.newDrawable(App.instance.resources) ?: drawable
    }

    /**
     * 当前生效的图集: 直接读 shared 记忆化解析 (与 Compose 封面链同源同选图,
     * 不做文件存在性预过滤 -- 缺文件经 [newDefaultDrawable] 的 runCatching 回落内置图)。
     */
    private fun currentCovers(): List<DefaultCoverEntry> {
        return BookCoverShared.currentDefaultCovers(
            PreferenceProviders.get(), AppConfig.isNightTheme,
        )
    }

    /** 清默认封面 Drawable 解码缓存 (图集增删后调用; 列表缓存自动失效, 无需手动刷)。 */
    fun evictDrawableCache() {
        drawableCache.evictAll()
    }

    /**
     * 列出某偏好下当前已选的图集 (委托 shared 记忆化解析)。UI 直接用 entry.bakedPath(NOVEL) 显示。
     */
    fun listDefaultCovers(prefKey: String): List<DefaultCoverEntry> =
        BookCoverShared.listDefaultCovers(PreferenceProviders.get(), prefKey)

    /**
     * 媒体通知/MediaSession 通用的默认封面 Bitmap。
     */
    val notificationDefaultCover: Bitmap by lazy {
        BitmapFactory.decodeResource(App.instance.resources, R.drawable.icon_read_book)
    }

    /**
     * suspend 取封面 Bitmap: 通知/PhotoDialog/getCover 用。useDefaultCover 或空路径回退默认封面。
     * 不改写 diskCacheKey: 对照原版 `ImageLoader.loadBitmap` 无 `signature("covers")`, 写临时区;
     * 读时 MultiDiskCache 会回查持久区, 书架书仍能命中。
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
        val request = ImageRequest.Builder(context)
            .data(path)
            .sourceOrigin(sourceOrigin)
            .build()
        val result = loader.execute(request)
        return if (result is SuccessResult) {
            result.image.toBitmap()
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
