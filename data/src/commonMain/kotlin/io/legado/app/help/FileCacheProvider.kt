package io.legado.app.help

import kotlin.concurrent.Volatile

/**
 * 文件缓存抽象层。
 *
 * 抽象 app 端 `ACache.get()` 的文件/磁盘缓存能力，供 commonMain 的 [CacheManager]
 * 通过 [FileCacheProviders] 注入访问。app 端注册 [ACacheFileCacheProvider]（委托 ACache），
 * desktop/ios/ohos 端各自实现。
 *
 * 与 [io.legado.app.help.source.SourceCacheProvider] 的区别：
 * - [io.legado.app.help.source.SourceCacheProvider] 走 cacheDao (Room DB, 字符串 KV)
 * - [FileCacheProvider] 走文件缓存 (字符串/二进制 KV，对应原 app 端 ACache)
 *
 * persistent = true 走不会被"清除缓存"抹掉的目录 (对应 app 端 `ACache.get(cacheDir = false)`)，
 * 用于在线导入历史这类需长期保留的记录。
 *
 * 模式参考 [io.legado.app.help.source.SourceCacheProviders] / AppDbProviders。
 */
object FileCacheProviders {
    @Volatile
    var impl: FileCacheProvider? = null

    fun get(): FileCacheProvider = impl ?: error("FileCacheProvider not registered")
}

interface FileCacheProvider {
    /** 写入字符串缓存, saveTime 单位秒, 0 表示永久。 */
    fun put(key: String, value: String, saveTime: Int = 0, persistent: Boolean = false)

    /** 读取字符串缓存, 不存在或已过期返回 null。 */
    fun getAsString(key: String, persistent: Boolean = false): String?

    /** 写入二进制缓存, saveTime 单位秒, 0 表示永久。 */
    fun put(key: String, value: ByteArray, saveTime: Int = 0, persistent: Boolean = false)

    /** 读取二进制缓存, 不存在或已过期返回 null。 */
    fun getAsBinary(key: String, persistent: Boolean = false): ByteArray?

    /** 删除指定 key 的缓存。 */
    fun remove(key: String, persistent: Boolean = false)
}
