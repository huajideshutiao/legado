package io.legado.app.help.source

/*
 * BookSource/BookSourcePart 扩展函数下沉说明 (app 端残留文件)。
 *
 * 原本文件的 exploreKinds() / BookSourcePart.exploreKinds() / getExploreKindsKey() (private) /
 * exploreKindsMap (internal) / mutexMap / aCache 已全部下沉到 shared commonMain
 * (BookSourceExtensionsShared.kt), ACache 读写经 ExploreKindsCacheProviders provider 转发。
 *
 * - getBookType() / exploreKindsJson(): 已提升为 BookSource 成员方法,
 *   调用方直接 bookSource.getBookType() / bookSource.exploreKindsJson()。
 * - clearExploreKindsCache() / exploreKinds(): 已下沉到 shared BookSourceExtensionsShared.kt,
 *   跨模块同包名同签名扩展自动合并, 消费方 import `io.legado.app.help.source.exploreKinds`
 *   / `clearExploreKindsCache` 零改动。
 * - app 端 JsEnginesAndroid.kt 的 ExploreKindsCacheProvider 实现 (getAsString/put/remove)
 *   转发到 ACache.get("explore"); exploreKindsMap 内存缓存由 shared 侧 clearExploreKindsCache 清理。
 *
 * 后续若有 app 平台专属的 BookSource 扩展 (依赖 ACache/runScriptWithContext 之外 app 单例),
 * 可在此文件追加; 无 app 专属依赖的扩展请优先放 shared BookSourceExtensionsShared.kt。
 */
