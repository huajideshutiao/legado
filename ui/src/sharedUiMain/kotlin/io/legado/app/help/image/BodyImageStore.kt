package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.constant.PreferKey
import io.legado.app.help.CommonLruCache
import io.legado.app.help.config.PreferenceProviders

/**
 * 正文图片位图的唯一存放处与字节预算所有者。
 *
 * 同一进程只有一份: Android 复用 [io.legado.app.model.ImageProvider] 的位图 LRU (与 web 出图
 * `getImg` 共用同一预算和条目), 其余三端是 [CommonLruCache] 的字节 LRU。阅读页与其它正文图
 * 消费者都经本接口读写, 不各自另立预算。单张即超本表上限的图不写入 (调用方仍持有该图交
 * 显示位), 写入库表会把既有条目全数挤掉。
 */
interface BodyImageStore {

    /** 命中返回位图并刷新访问序。 */
    fun peek(key: String): ImageBitmap?

    /** 写入; 单张超过本表上限时不写入并返回 false。 */
    fun put(key: String, bitmap: ImageBitmap): Boolean

    /** 清掉某本书的全部正文图 (key 以 [bookKey] 开头)。 */
    fun clearBook(bookKey: String)

    fun clear()

    /** 按当前设置立即裁剪到新上限。 */
    fun applyBudget()
}

/** 位图占用字节的 Int 口径 (CommonLruCache 的 sizeOf 是 Int)。 */
internal fun ImageBitmap.byteSizeInt(): Int =
    byteSize().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** 正文图片表的字节上限 (设置值全额), 供 LRU 的 Int 上限使用。 */
internal fun bodyImageCacheLimit(): Int =
    bodyImageCacheMaxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * 非 Android 端的正文图片表: [CommonLruCache] 的字节 LRU, 上限取设置项 `bitmapCacheSize` 全额。
 *
 * 预算订阅归本表自己所有: 对象初始化时 (对象本身就是进程级单例, 初始化天然只一次)
 * 按当前设置对齐并订阅后续变化, 不靠某个 UI 入口先跑过。
 */
internal object InMemoryBodyImageStore : BodyImageStore {

    private val cache = CommonLruCache<String, ImageBitmap>(
        maxSize = bodyImageCacheLimit(),
        sizeOf = { _, bitmap -> bitmap.byteSizeInt() },
    )

    init {
        // 预算归本表 (真实缓存所有者): 先注册监听再同步读一次当前设置,
        // 否则另一线程在“读→注册”之间改设置会漏事件
        val prefs = PreferenceProviders.get()
        prefs.addPreferenceChangeListener { key ->
            if (key.isEmpty() || key == PreferKey.bitmapCacheSize) {
                cache.resize(bodyImageCacheLimit())
            }
        }
        cache.resize(bodyImageCacheLimit())
    }

    override fun peek(key: String): ImageBitmap? = cache[key]

    override fun put(key: String, bitmap: ImageBitmap): Boolean {
        if (bitmap.byteSizeInt() > cache.maxSize()) {
            cache.remove(key)
            return false
        }
        cache.put(key, bitmap)
        return true
    }

    override fun clearBook(bookKey: String) {
        cache.snapshot().keys.forEach { if (it.startsWith(bookKey)) cache.remove(it) }
    }

    override fun clear() {
        cache.snapshot().keys.forEach { cache.remove(it) }
    }

    override fun applyBudget() {
        cache.resize(bodyImageCacheLimit())
    }
}

/**
 * 当前端的正文图片表。
 *
 * Android: [io.legado.app.model.ImageProvider] 的位图 LRU (与 web 出图共用);
 * 其余三端: [InMemoryBodyImageStore]。
 */
internal expect fun bodyImageStore(): BodyImageStore
