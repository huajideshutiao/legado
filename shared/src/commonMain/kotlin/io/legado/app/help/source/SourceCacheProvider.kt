package io.legado.app.help.source

import kotlin.concurrent.Volatile

object SourceCacheProviders {
    @Volatile var impl: SourceCacheProvider? = null
}

interface SourceCacheProvider {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun delete(key: String)
    /** 内存缓存层(对应 CacheManager.getFromMemory, LruCache 而非数据库) */
    fun getFromMemory(key: String): Any?
    /** 内存缓存层(对应 CacheManager.putMemory) */
    fun putMemory(key: String, value: Any)
    /** 内存缓存层(对应 CacheManager.deleteMemory) */
    fun deleteMemory(key: String)

    /**
     * 带过期时间的写入, saveTime 单位秒, 0 表示永久。
     *
     * 默认实现忽略 saveTime 直接转发 [put], app 端匿名实现(在 JsEnginesAndroid)
     * 委托 CacheManager.put 走 cacheDao (deadline 字段持久化), 行为与原一致。
     */
    fun put(key: String, value: String, saveTime: Int) {
        put(key, value)
    }

    /** 读取并解析为 Int, 默认实现基于 [get]。 */
    fun getInt(key: String): Int? = get(key)?.toIntOrNull()

    /** 读取并解析为 Long, 默认实现基于 [get]。 */
    fun getLong(key: String): Long? = get(key)?.toLongOrNull()

    /** 读取并解析为 Double, 默认实现基于 [get]。 */
    fun getDouble(key: String): Double? = get(key)?.toDoubleOrNull()

    /** 读取并解析为 Float, 默认实现基于 [get]。 */
    fun getFloat(key: String): Float? = get(key)?.toFloatOrNull()

    /**
     * 按前缀批量删除内存层缓存。默认空实现, 由持有内存 LruCache 的实现覆盖
     * (AppCacheManager.clearSourceVariables 直接访问 CacheManager.memoryLruCache,
     * 不走此方法; 此方法供其他端按需实现)。
     */
    fun clearMemoryByPrefixes(prefixes: List<String>) {}

    /** 返回用于 JS 绑定的底层对象（保留原 @JsApi 分派表） */
    fun asBinding(): Any = this
}
