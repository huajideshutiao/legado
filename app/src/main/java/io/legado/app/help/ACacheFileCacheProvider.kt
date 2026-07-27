package io.legado.app.help

import io.legado.app.utils.ACache

/**
 * app 端 [FileCacheProvider] 实现, 委托 [ACache.get()]。
 *
 * commonMain 的 [CacheManager] 通过 [FileCacheProviders] 注入访问文件/磁盘缓存,
 * app 端注册本对象 (在 App.onCreate 经 [registerAndroidFileCacheProvider]) 后,
 * CacheManager.getFile/putFile/getByteArray/put(ByteArray)/delete 等方法
 * 转发到 ACache, 行为与下沉前 app 端 CacheManager 直接调用 ACache 完全一致。
 *
 * desktop/ios/ohos 端各自实现 [FileCacheProvider] (无 ACache 依赖)。
 */
object ACacheFileCacheProvider : FileCacheProvider {

    override fun put(key: String, value: String, saveTime: Int) {
        ACache.get().put(key, value, saveTime)
    }

    override fun getAsString(key: String): String? {
        return ACache.get().getAsString(key)
    }

    override fun put(key: String, value: ByteArray, saveTime: Int) {
        ACache.get().put(key, value, saveTime)
    }

    override fun getAsBinary(key: String): ByteArray? {
        return ACache.get().getAsBinary(key)
    }

    override fun remove(key: String) {
        ACache.get().remove(key)
    }
}

/**
 * 注册 app 端 [FileCacheProvider] (委托 ACache) 到 commonMain 的 [FileCacheProviders]。
 *
 * 调用时机: App.onCreate 中 (任何 CacheManager 文件操作之前)。
 * 与 [registerAndroidJsEngines] 同阶段注册。
 */
fun registerAndroidFileCacheProvider() {
    FileCacheProviders.impl = ACacheFileCacheProvider
}
