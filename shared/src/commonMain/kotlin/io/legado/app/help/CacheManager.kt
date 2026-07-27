package io.legado.app.help

import com.script.jsdispatch.JsApi
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Cache
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 最多只缓存 50M 的数据, 防止 OOM。
 *
 * 与原 app 端一致: sizeOf 按估算字节数累加, 超过 maxSize 时按 LRU 顺序裁剪。
 */
private val memoryLruCache = CommonLruCache<String, Any>(1024 * 1024 * 50) { _, value ->
    estimateMemorySize(value)
}

/** QueryTTF 缓存, 最多 4 个 entry (按数量裁剪, sizeOf 默认返回 1)。 */
private val queryTTFMap = CommonLruCache<String, QueryTTF>(4)

/**
 * sizeOf 估算: 类型分派, 避免 LinkedHashMap 子类走 AbstractMap.toString() 递归展开成大字符串。
 * Map/Collection 只按 size 浅算, 不精确但省 O(total chars) 开销。
 *
 * 从 app 端 CacheManager.kt 原样迁移, 行为完全一致。
 */
private fun estimateMemorySize(value: Any?): Int {
    return when (value) {
        null -> 0
        is CharSequence -> 40 + 2 * value.length
        is Number, is Boolean, is Char -> 24
        is ByteArray -> 16 + value.size
        is Map<*, *> -> 40 + value.size * 40
        is Collection<*> -> 40 + value.size * 40
        else -> 40 + 2 * value.toString().length
    }
}

object AppCacheManager {

    fun put(key: String, queryTTF: QueryTTF) {
        queryTTFMap.put(key, queryTTF)
    }

    fun getQueryTTF(key: String): QueryTTF? {
        return queryTTFMap[key]
    }

    fun clearSourceVariables() {
        // 遍历 snapshot 副本, 删除前缀匹配的内存缓存 (与原 app 端行为一致)。
        memoryLruCache.snapshot().keys.forEach {
            if (it.startsWith("v_")
                || it.startsWith("userInfo_")
                || it.startsWith("loginHeader_")
                || it.startsWith("sourceVariable_")
            ) {
                memoryLruCache.remove(it)
            }
        }
    }

}


/**
 * 缓存管理器主体 (从 app 端下沉到 commonMain)。
 *
 * 设计 (避免与 SourceCacheProviders 循环依赖):
 * - 持久层 (String KV) 直接走 [AppDbProviders.get].cacheDao (4 端 Room KMP 都有 cacheDao),
 *   不再委托 SourceCacheProviders。
 * - 文件/二进制层 (ByteArray/String 文件缓存) 委托 [FileCacheProviders] (app 端 ACacheFileCacheProvider)。
 * - 内存层 自持 [memoryLruCache] (CommonLruCache, 替代 androidx.collection.LruCache)。
 *
 * SourceCacheProviders 保留给 BaseSource/CheckSourceShared 等已有调用方使用,
 * app 端 JsEnginesAndroid 中匿名 object 仍委托本 object (asBinding 返回 CacheManager)。
 *
 * 包名保持 `io.legado.app.help`, app 端调用方 import 不变 (跨模块同包名同签名 object 合并)。
 *
 * 注: 原 app 端 `@Keep` (androidx.annotation.Keep) 是 Android 专属, commonMain 不用;
 * `@JsApi` 已在 commonMain (com.script.jsdispatch), 保留以驱动 KSP 静态分派表生成。
 *
 * # runBlockingInScope 保留说明 (逃生舱)
 *
 * `get/put/delete` 经 `@JsApi` 暴露给 JS 引擎 (QuickJS), JS 调用是同步的, 不能 suspend。
 * 故这三个方法无法改为 suspend, 内部经 `runBlockingInScope(EmptyCoroutineContext)` 保留阻塞语义
 * (委托 jvmAndAndroidMain actual 调用原 runBlocking, commonMain 不直接依赖 JVM-only API)。
 * - JVM 端 (Android/desktop): 阻塞安全, JS 引擎在 IO 线程同步调用。
 * - Native 端 (iOS/鸿蒙): JS 引擎暂未启用, get/put/delete 仅经 SourceVerificationHelpShared.clearResult
 *   / BookController 调用; 这两条路径在 Native 未启用, 不会触发阻塞。
 *   后续 Native 启用相关功能时, 需为这两条路径提供 suspend 替代方案。
 */
@JsApi
@Suppress("unused")
object CacheManager {

    /**
     * saveTime 单位为秒
     */
    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        val deadline =
            if (saveTime == 0) 0L else systemCurrentTimeMillis() + saveTime * 1000
        when (value) {
            is ByteArray -> FileCacheProviders.impl?.put(key, value, saveTime)
            else -> {
                val cache = Cache(key, value.toString(), deadline)
                // @JsApi 暴露给 JS 引擎, 不能 suspend; 详见 object 级 runBlockingInScope 保留说明
                runBlockingInScope(EmptyCoroutineContext) { AppDbProviders.get().cacheDao.insert(cache) }
            }
        }
    }

    fun putMemory(key: String, value: Any) {
        memoryLruCache.put(key, value)
    }

    //从内存中获取数据 使用lruCache
    fun getFromMemory(key: String): Any? {
        return memoryLruCache[key]
    }

    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
    }

    fun get(key: String): String? {
        // @JsApi 暴露给 JS 引擎, 不能 suspend; 详见 object 级 runBlockingInScope 保留说明
        val cache = runBlockingInScope(EmptyCoroutineContext) { AppDbProviders.get().cacheDao.get(key) }
        if (cache != null && (cache.deadline == 0L || cache.deadline > systemCurrentTimeMillis())) {
            return cache.value
        }
        return null
    }

    fun getInt(key: String): Int? {
        return get(key)?.toIntOrNull()
    }

    fun getLong(key: String): Long? {
        return get(key)?.toLongOrNull()
    }

    fun getDouble(key: String): Double? {
        return get(key)?.toDoubleOrNull()
    }

    fun getFloat(key: String): Float? {
        return get(key)?.toFloatOrNull()
    }

    fun getByteArray(key: String): ByteArray? {
        return FileCacheProviders.impl?.getAsBinary(key)
    }

    @JvmOverloads
    fun putFile(key: String, value: String, saveTime: Int = 0) {
        FileCacheProviders.impl?.put(key, value, saveTime)
    }

    fun getFile(key: String): String? {
        return FileCacheProviders.impl?.getAsString(key)
    }

    fun delete(key: String) {
        // @JsApi 暴露给 JS 引擎, 不能 suspend; 详见 object 级 runBlockingInScope 保留说明
        runBlockingInScope(EmptyCoroutineContext) { AppDbProviders.get().cacheDao.delete(key) }
        deleteMemory(key)
        FileCacheProviders.impl?.remove(key)
    }
}
