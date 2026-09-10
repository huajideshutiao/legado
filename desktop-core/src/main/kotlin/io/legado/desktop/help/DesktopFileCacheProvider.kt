package io.legado.desktop.help

import io.legado.app.help.FileCacheProvider
import io.legado.app.help.FileCacheProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.ACacheBase
import java.io.File

/**
 * desktop 端 [FileCacheProvider] 实现, 基于 JVM 文件系统 (java.io.File)。
 *
 * commonMain 的 [io.legado.app.help.CacheManager] 通过 [FileCacheProviders] 注入访问文件/磁盘缓存,
 * desktop 端注册本对象 (在 Main.registerSecondaryProviders 经 [registerDesktopFileCacheProvider]) 后,
 * CacheManager.getFile/putFile/getByteArray/put(ByteArray)/delete 等方法转发到本实现,
 * 行为对齐 app 端 ACacheFileCacheProvider (委托 ACache)。未注册时 [FileCacheProviders.get]
 * 抛 IllegalStateException (不再静默丢弃, 注册遗漏立即暴露)。
 *
 * # 存储
 * - 根目录: `{AppFilesDirs.cacheDir}/file_cache/` (cacheDir 桌面端为系统临时目录
 *   {java.io.tmpdir}/legado/cache, 隔离 file_cache 子目录避免与其他 cache 使用方冲突)
 * - 文件名: `key.hashCode()` (与 app 端 ACache.ACacheManager.newFile 一致, key 可含任意字符)
 *
 * # 实现
 * 复用 shared [ACacheBase] (ACache 的纯 JDK 部分), 与 [io.legado.desktop.help.source]
 * 的 DesktopExploreKindsCacheProvider 同模式: 目录注入改为薄子类 [DesktopFileACache]。
 * 日期头编解码 / isDue 过期判断 / clearDateInfo 由 ACacheBase 内部 Utils 提供,
 * 文件格式与 app 端 ACache 完全一致, 行为可预期:
 * - saveTime = 0: 直接存原始数据, 永不过期
 * - saveTime > 0: 存 `createDateInfo(saveTime) + 数据`, 读取时校验过期则删除文件并返回 null
 *
 * 模式参考 app 端 ACacheFileCacheProvider / shared NativeSourceCacheProvider。
 */
object DesktopFileCacheProvider : FileCacheProvider {

    /** 缓存根目录, lazy 首次访问时创建 (此时 AppFilesDirs 已注册)。 */
    private val cacheDir: File by lazy {
        File(AppFilesDirs.get().cacheDir, "file_cache").apply { mkdirs() }
    }

    /** 持久根目录 (对应 app 端 `ACache.get(cacheDir = false)` 的 filesDir), 清缓存不受影响。 */
    private val filesDir: File by lazy {
        File(AppFilesDirs.get().filesDir, "file_cache").apply { mkdirs() }
    }

    /** 易失缓存 (cacheDir), 清缓存会被清除。 */
    private val cache by lazy { DesktopFileACache(cacheDir) }

    /** 持久缓存 (filesDir), 清缓存不受影响。 */
    private val filesCache by lazy { DesktopFileACache(filesDir) }

    private fun aCache(persistent: Boolean) = if (persistent) filesCache else cache

    override fun put(key: String, value: String, saveTime: Int, persistent: Boolean) {
        aCache(persistent).put(key, value, saveTime)
    }

    override fun getAsString(key: String, persistent: Boolean): String? =
        aCache(persistent).getAsString(key)

    override fun put(key: String, value: ByteArray, saveTime: Int, persistent: Boolean) {
        aCache(persistent).put(key, value, saveTime)
    }

    override fun getAsBinary(key: String, persistent: Boolean): ByteArray? =
        aCache(persistent).getAsBinary(key)

    override fun remove(key: String, persistent: Boolean) {
        aCache(persistent).remove(key)
    }
}

/** [ACacheBase] 构造器是 protected, 桌面端开一个薄子类接管目录 (对照 app 端 ACache)。 */
private class DesktopFileACache(cacheDir: File) : ACacheBase(
    cacheDir, ACacheBase.MAX_SIZE.toLong(), ACacheBase.MAX_COUNT
)

/**
 * 注册 desktop 端 [FileCacheProvider] 到 commonMain 的 [FileCacheProviders]。
 *
 * 调用时机: Main.registerSecondaryProviders 中 (任何 CacheManager 文件操作之前)。
 * 依赖 [io.legado.app.help.file.registerDesktopAppFilesDir] 已在 main 阶段1 同步注册。
 */
fun registerDesktopFileCacheProvider() {
    FileCacheProviders.impl = DesktopFileCacheProvider
}
