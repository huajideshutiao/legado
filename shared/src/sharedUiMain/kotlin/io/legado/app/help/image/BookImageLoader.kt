package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ensureActive

/**
 * Compose 图片加载跨平台抽象 (Coil3 迁移批 1 共享面)。
 *
 * 替代 app 端 Glide ImageLoader.load(context, url, sourceOrigin) 系列入口,
 * 供 sharedUiMain / app / desktop 的 Composable 直接调用。本批仅建共享面 + 接线,
 * 不替换既有 Glide 消费点 (Glide 与 Coil3 共存)。
 *
 * [sourceOrigin] 用于书源防盗链 header 注入: 传入书源 bookUrl 作为 key,
 * 实现内部按 [io.legado.app.help.source.SourceHelp.getSource] 解析书源 header
 * (对齐 app 端 Glide OkHttpModelLoader.sourceOriginOption 行为)。
 *
 * 实现注册:
 * - Android: app 端 App.onCreate 早期调用 [BookImageLoaders.register] 注入
 *   AndroidBookImageLoader (基于 Coil3 ImageLoader + AsyncImage)。
 * - 桌面 JVM: desktop Main.kt 注入 JvmBookImageLoader。
 * - iOS: registerIosProviders 注入 IosBookImageLoader (Coil3 + Ktor3 网络后端)。
 * - 鸿蒙: 未注册 (coil3 无 ohosArm64 变体), 消费点经 [BookImageLoaders.getOrNull] 拿到 null,
 *   恒走内置占位图。
 *
 * 模式参考 [io.legado.app.help.book.BookImageStorageProviders]。
 */
interface BookImageLoader {

    /**
     * 异步加载图片为 [ImageBitmap]。
     *
     * 实现方自己的 CoroutineScope, 调用方取消不了; 列表条目请改用 [loadImageOrNull]。
     * 同 URL 并发调用经 [BookImageLoadDedup] 单飞去重 (与 [loadImageOrNull] 共享同一去重表)。
     *
     * @param url 图片 URL
     * @param sourceOrigin 书源 bookUrl (可为 null), 用于防盗链 header 注入
     * @param onSuccess 成功回调
     * @param onError 失败回调
     */
    fun loadImage(
        url: String,
        sourceOrigin: String?,
        onSuccess: (ImageBitmap) -> Unit,
        onError: (Throwable) -> Unit
    )

    /**
     * 挂起版加载: 在调用方协程里执行, 随之取消 (列表条目滚出视口即中止下载/解码);
     * 同 URL 并发请求经 [BookImageLoadDedup] 单飞去重 (书架网格多条目共用一次下载/解码)。
     *
     * [widthPx]/[heightPx] 均 > 0 时按目标尺寸降采样解码 (Scale.FILL + Precision.INEXACT,
     * 对齐消费端的 ContentScale.Crop); 否则解原图 —— 同屏几十张封面时决定性的开销差别。
     *
     * [loadOnlyWifi] 为 true 且非 WiFi 时 fetcher 层拦截网络获取 (缓存命中仍显示),
     * 对齐原版 Glide `loadOnlyWifiOption` (仅 Android/iOS/鸿蒙消费; 桌面恒不拦截)。
     *
     * @return 失败返回 null (不抛)
     */
    suspend fun loadImageOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int = 0,
        heightPx: Int = 0,
        loadOnlyWifi: Boolean = false,
    ): ImageBitmap?

    /**
     * 书籍封面加载: 同 [loadImageOrNull], 但磁盘缓存落**持久区** (应用数据目录),
     * 系统清缓存/用户清缓存都清不掉 —— 书源失效后封面不可重获, 对齐原版 Glide
     * `MultiDiskCacheFactory` 的 `filesDir/covers` 分区。
     *
     * 默认实现等同 [loadImageOrNull] (未分区的平台按原行为走)。
     */
    suspend fun loadCoverOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int = 0,
        heightPx: Int = 0,
        loadOnlyWifi: Boolean = false,
    ): ImageBitmap? = loadImageOrNull(url, sourceOrigin, widthPx, heightPx, loadOnlyWifi)
}

/**
 * [BookImageLoader] provider 容器。宿主启动早期注册一次。
 */
object BookImageLoaders {

    @Volatile
    private var impl: BookImageLoader? = null

    /** 宿主启动早期注册一次 (任何 Composable 图片加载之前)。 */
    fun register(impl: BookImageLoader) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): BookImageLoader = impl ?: error("BookImageLoader not registered")

    /** 获取已注册实现, 未注册返回 null (供 ohos 等未注册平台安全回退占位)。 */
    fun getOrNull(): BookImageLoader? = impl
}

/**
 * 书架/详情封面 url 级单飞去重 (I6, 图片加载深度优化)。
 *
 * 背景: Coil3 无 Glide ActiveResources 式 in-flight 去重 —— 书架网格同 URL 多条目并发
 * execute() 时各自重复下载/解码 (内存缓存只对已完成请求生效)。
 * 模式对齐 [io.legado.app.help.image.ReaderImageCache] 的 inFlight 去重: 同一
 * (url+sourceOrigin+目标尺寸+分区) 并发请求共享一个 Deferred, 先到者执行, 后到者 await 其结果;
 * 执行者完成即从表移除, 后续请求由 Coil3 内存缓存命中。仅覆盖并发窗口, 竞态退化为重复加载
 * (第二次命中内存缓存, 代价可忽略)。
 */
object BookImageLoadDedup {

    private val lock = SynchronizedObject()
    private val inFlight = HashMap<String, Deferred<Any?>>()

    /** 单飞: [key] 相同且未完成时共享结果; 完成/失败后移除表项。
     * 执行者被取消 (如列表条目滚出视口) 时, 仍活跃的等待者自己顶上重试,
     * 不把执行者的 CancellationException 传染给其他调用方。 */
    suspend fun <T> singleFlight(key: String, block: suspend () -> T): T {
        while (true) {
            val existing = synchronized(lock) { inFlight[key] }
            if (existing != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    return (existing as Deferred<T>).await()
                } catch (e: CancellationException) {
                    // 执行者被取消: 本协程仍活跃则回环顶上重试, 自身被取消则正常传播
                    coroutineContext.ensureActive()
                }
            }
            val deferred = CompletableDeferred<Any?>()
            val winner = synchronized(lock) { inFlight[key] ?: deferred.also { inFlight[key] = it } }
            if (winner === deferred) {
                try {
                    val result = block()
                    deferred.complete(result)
                    @Suppress("UNCHECKED_CAST")
                    return result
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                    throw t
                } finally {
                    synchronized(lock) { inFlight.remove(key) }
                }
            }
            // 竞态落败: 回到循环首部 await 胜者的 Deferred
        }
    }
}
