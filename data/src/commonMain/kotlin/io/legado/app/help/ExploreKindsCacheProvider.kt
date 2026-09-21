package io.legado.app.help

import kotlin.concurrent.Volatile

/**
 * F2: BookSource 下沉解除对 app 端 ACache 的直接依赖。
 *
 * 原 BookSource.exploreKindsJson() / BookSourceExtensions.exploreKinds() 通过
 * `ACache.get("explore").getAsString(key)` / `.put(key, value)` 读写发现规则缓存
 * (ACache 为 Android 文件缓存, 无法下沉到 shared)。
 * app 端启动时通过 [ExploreKindsCacheProviders.impl] = ... 注入实现,
 * shared 中 BookSource / exploreKinds 改走 [ExploreKindsCacheProviders.impl]?.getAsString/put(...),
 * 行为与原 ACache 完全一致。
 *
 * 读/写侧均下沉到 shared commonMain (BookSourceExtensionsShared.kt 的 exploreKinds()),
 * 通过本 provider 转发到平台 ACache (app) 或 in-memory Map (desktop)。
 */
object ExploreKindsCacheProviders {
    @Volatile
    var impl: ExploreKindsCacheProvider? = null
}

interface ExploreKindsCacheProvider {
    /** 对应 ACache.getAsString(key): 不存在返回 null */
    fun getAsString(key: String): String?

    /**
     * 对应 ACache.put(key, value): 写入磁盘缓存条目。
     * 由下沉的 `BookSource.exploreKinds()` (shared) 在执行 JS 解析出 ruleStr 后调用,
     * app 端实现转发到 `ACache.get("explore").put(...)`, 行为与原 app 端写入侧完全一致。
     */
    fun put(key: String, value: String)

    /**
     * 对应 ACache.remove(key): 删除磁盘缓存条目。
     * 由下沉的 `BookSource.clearExploreKindsCache()` / `BookSourcePart.clearExploreKindsCache()`
     * (shared) 调用; shared 侧同时清理 exploreKindsMap 内存缓存,
     * app 端实现仅需清理 ACache (exploreKindsMap 已由 shared 侧 clearExploreKindsCache 清理)。
     */
    fun remove(key: String)
}
