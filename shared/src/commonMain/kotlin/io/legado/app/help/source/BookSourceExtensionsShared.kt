@file:Suppress("unused")

package io.legado.app.help.source

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.ExploreKindsCacheProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.printOnDebug
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.legado.app.utils.concurrent.newConcurrentMap

/*
 * BookSource/BookSourcePart 扩展函数下沉区 (shared commonMain)。
 *
 * exploreKinds() / getExploreKindsKey() (private) / clearExploreKindsCache() 均已下沉,
 * 依赖的 runScriptWithContext / evalJS (BaseSource 成员) / GSON / isJsonArray / printOnDebug /
 * MD5Utils 均已在 commonMain 可用, ACache 读写经 [ExploreKindsCacheProviders] provider 转发
 * (app 端注册转发到 ACache.get("explore"), desktop 端 in-memory Map, 行为等价)。
 *
 * 跨模块同包名同签名扩展自动合并, 消费方 import 零改动。
 * 注意: 同包名同签名扩展函数不允许在两个模块同时定义, 需从 app 端删除已下沉的扩展。
 */

/**
 * 采用md5作为key可以在分类修改后自动重新计算,不需要手动刷新
 */

private val mutexMap by lazy { hashMapOf<String, Mutex>() }

/** exploreKinds 内存缓存: exploreKindsKey → 分类列表 (对应原 app 端 exploreKindsMap) */
internal val exploreKindsMap by lazy { newConcurrentMap<String, List<ExploreKind>>() }

/**
 * 计算发现分类缓存 key (md5(bookSourceUrl + exploreUrl))。
 *
 * 原 app 端 BookSourceExtensions.kt 的 private getExploreKindsKey() 算法, 随 exploreKinds()
 * 一并下沉到 shared; app 端副本已删除, 此处为唯一实现。
 */
private fun BookSource.getExploreKindsKey(): String {
    return MD5Utils.md5Encode(bookSourceUrl + exploreUrl)
}

/**
 * 取当前书源分片对应书源的发现分类列表, 转发到 [BookSource.exploreKinds]。
 *
 * 原 app 端实现走 `getBookSource()!!.exploreKinds()` (getBookSource 依赖 appDb, 留 app 端);
 * shared 侧改直接查 [AppDbProviders] 取 BookSource 再转发, 行为等价 (null 时抛 NPE, 与原 `!!` 一致)。
 */
suspend fun BookSourcePart.exploreKinds(): List<ExploreKind> {
    return AppDbProviders.get().bookSourceDao.getBookSource(bookSourceUrl)!!.exploreKinds()
}

/**
 * 解析 bookSource.exploreUrl 中的分类规则, 缓存到 [exploreKindsMap] (内存) +
 * [ExploreKindsCacheProviders] (磁盘, app 端 ACache / desktop in-memory)。
 *
 * 原 app 端 BookSourceExtensions.kt 的 exploreKinds() 下沉:
 * - ACache.get("explore").getAsString/put → [ExploreKindsCacheProviders.impl]?.getAsString/put
 *   (provider 未注册时退化: 读返回 null 触发重新解析, 写跳过, 行为等价于无磁盘缓存)
 * - runScriptWithContext / evalJS / GSON / isJsonArray / printOnDebug 均已在 commonMain 可用
 * - exploreKindsMap (ConcurrentHashMap) 保留为 commonMain 内存缓存
 * - 纯字符串/JSON 解析逻辑直接迁移, 行为与原 app 端完全一致
 */
suspend fun BookSource.exploreKinds(): List<ExploreKind> {
    val exploreKindsKey = getExploreKindsKey()
    exploreKindsMap[exploreKindsKey]?.let { return it }
    val exploreUrl = exploreUrl
    if (exploreUrl.isNullOrBlank()) {
        return emptyList()
    }
    val mutex = mutexMap[bookSourceUrl] ?: Mutex().apply { mutexMap[bookSourceUrl] = this }
    mutex.withLock {
        exploreKindsMap[exploreKindsKey]?.let { return it }
        val kinds = arrayListOf<ExploreKind>()
        withContext(IoDispatcher) {
            kotlin.runCatching {
                var ruleStr = exploreUrl
                if (exploreUrl.startsWith("<js>", true)
                    || exploreUrl.startsWith("@js:", true)
                ) {
                    ruleStr = ExploreKindsCacheProviders.impl?.getAsString(exploreKindsKey)
                    if (ruleStr.isNullOrBlank()) {
                        val jsStr = if (exploreUrl.startsWith("@")) {
                            exploreUrl.substring(4)
                        } else {
                            exploreUrl.substring(4, exploreUrl.lastIndexOf("<"))
                        }
                        ruleStr = runScriptWithContext {
                            evalJS(jsStr).toString().trim()
                        }
                        ExploreKindsCacheProviders.impl?.put(exploreKindsKey, ruleStr)
                    }
                }
                if (ruleStr.isJsonArray()) {
                    GSON.fromJsonArray<ExploreKind>(ruleStr).getOrThrow().let {
                        kinds.addAll(it)
                    }
                } else {
                    ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
                        val kindCfg = kindStr.split("::")
                        var title = kindCfg.first()
                        var type = RowUi.Type.text
                        var cols: Int? = null
                        when {
                            title.startsWith("BUTTON:") -> {
                                title = title.substring(7)
                                type = RowUi.Type.button
                            }

                            title.startsWith("TITLE:") -> {
                                title = title.substring(6)
                                type = RowUi.Type.title
                                cols = 1
                            }
                        }
                        kinds.add(
                            ExploreKind(
                                title,
                                type,
                                kindCfg.getOrNull(1),
                                cols?.let { FlexChildStyle(cols = it) }
                            )
                        )
                    }
                }
            }.onFailure {
                kinds.add(ExploreKind("ERROR:${it.message}", url = it.stackTraceToString()))
                it.printOnDebug()
            }
        }
        exploreKindsMap[exploreKindsKey] = kinds
        return kinds
    }
}

/**
 * 清除当前书源发现分类缓存 (磁盘 + 内存)。
 *
 * 原 app 端实现: `aCache.remove(key); exploreKindsMap.remove(key)`。
 * 下沉后 shared 侧直接清理 [exploreKindsMap] 内存缓存, 并通过 [ExploreKindsCacheProviders]
 * provider 下发 remove 指令清理磁盘缓存 (app 端 impl 转发到 ACache), 行为与原完全一致。
 */
suspend fun BookSource.clearExploreKindsCache() {
    withContext(IoDispatcher) {
        val exploreKindsKey = getExploreKindsKey()
        exploreKindsMap.remove(exploreKindsKey)
        ExploreKindsCacheProviders.impl?.remove(exploreKindsKey)
    }
}

/**
 * 清除当前书源分片对应书源的发现分类缓存, 转发到 [BookSource.clearExploreKindsCache]。
 *
 * 原 app 端实现走 `getBookSource()!!.clearExploreKindsCache()` (getBookSource 依赖 appDb,
 * 留 app 端); shared 侧改直接查 [AppDbProviders] 取 BookSource 再转发, 行为等价。
 */
suspend fun BookSourcePart.clearExploreKindsCache() {
    val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(bookSourceUrl) ?: return
    bookSource.clearExploreKindsCache()
}
