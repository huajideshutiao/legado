package io.legado.desktop.help

import io.legado.app.help.FileCacheProvider
import io.legado.app.help.FileCacheProviders
import io.legado.app.help.file.AppFilesDirs
import java.io.File

/**
 * desktop 端 [FileCacheProvider] 实现, 基于 JVM 文件系统 (java.io.File)。
 *
 * commonMain 的 [io.legado.app.help.CacheManager] 通过 [FileCacheProviders] 注入访问文件/磁盘缓存,
 * desktop 端注册本对象 (在 Main.registerSecondaryProviders 经 [registerDesktopFileCacheProvider]) 后,
 * CacheManager.getFile/putFile/getByteArray/put(ByteArray)/delete 等方法转发到本实现,
 * 行为对齐 app 端 ACacheFileCacheProvider (委托 ACache)。未注册时 [FileCacheProviders].impl 为 null,
 * CacheManager 文件层调用静默 no-op (背景所述 P0 问题)。
 *
 * # 存储
 * - 根目录: `{AppFilesDirs.cacheDir}/file_cache/` (cacheDir 桌面端为系统临时目录
 *   {java.io.tmpdir}/legado/cache, 隔离 file_cache 子目录避免与其他 cache 使用方冲突)
 * - 文件名: `key.hashCode()` (与 app 端 ACache.ACacheManager.newFile 一致, key 可含任意字符)
 *
 * # TTL
 * 复用 app 端 ACache.Utils 的内嵌日期头格式 (`<13位毫秒>-<存活秒数> ` + 数据),
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

    override fun put(key: String, value: String, saveTime: Int) {
        val data = if (saveTime == 0) value else createDateInfo(saveTime) + value
        putBytes(key, data.toByteArray(Charsets.UTF_8))
    }

    override fun getAsString(key: String): String? {
        val bytes = getBytes(key) ?: return null
        return clearDateInfo(bytes).toString(Charsets.UTF_8)
    }

    override fun put(key: String, value: ByteArray, saveTime: Int) {
        val data = if (saveTime == 0) value else {
            val header = createDateInfo(saveTime).toByteArray(Charsets.UTF_8)
            ByteArray(header.size + value.size).also { ret ->
                System.arraycopy(header, 0, ret, 0, header.size)
                System.arraycopy(value, 0, ret, header.size, value.size)
            }
        }
        putBytes(key, data)
    }

    override fun getAsBinary(key: String): ByteArray? {
        val bytes = getBytes(key) ?: return null
        return clearDateInfo(bytes)
    }

    override fun remove(key: String) {
        runCatching { file(key).delete() }
    }

    private fun putBytes(key: String, data: ByteArray) {
        runCatching { file(key).writeBytes(data) }
    }

    /** 读取原始字节 (含日期头), 不存在/已过期/读取失败返回 null。 */
    private fun getBytes(key: String): ByteArray? {
        val file = file(key)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (isDue(bytes)) {
                runCatching { file.delete() }
                null
            } else {
                bytes
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun file(key: String): File = File(cacheDir, key.hashCode().toString())

    // ---- ACache 兼容的日期头格式 (与 app 端 ACache.Utils 一致, 保证两端文件格式相同) ----

    private const val separator = ' '

    private fun createDateInfo(second: Int): String {
        // 13 位零填充毫秒时间戳 + "-" + 存活秒数 + 分隔符
        val currentTime = StringBuilder(System.currentTimeMillis().toString())
        while (currentTime.length < 13) {
            currentTime.insert(0, "0")
        }
        return "$currentTime-$second$separator"
    }

    private fun isDue(data: ByteArray): Boolean {
        val info = getDateInfo(data) ?: return false
        if (info.size != 2) return false
        return try {
            val saveTime = info[0].toLong()
            val deleteAfter = info[1].toLong()
            System.currentTimeMillis() > saveTime + deleteAfter * 1000
        } catch (e: Exception) {
            false
        }
    }

    private fun clearDateInfo(data: ByteArray): ByteArray {
        if (!hasDateInfo(data)) return data
        val sepIdx = indexOf(data, separator)
        return if (sepIdx < 0) data else data.copyOfRange(sepIdx + 1, data.size)
    }

    private fun hasDateInfo(data: ByteArray): Boolean {
        return data.size > 15 && data[13] == '-'.code.toByte() && indexOf(data, separator) > 14
    }

    private fun getDateInfo(data: ByteArray): Array<String>? {
        if (!hasDateInfo(data)) return null
        val saveDate = String(data.copyOfRange(0, 13))
        val deleteAfter = String(data.copyOfRange(14, indexOf(data, separator)))
        return arrayOf(saveDate, deleteAfter)
    }

    private fun indexOf(data: ByteArray, c: Char): Int {
        for (i in data.indices) {
            if (data[i] == c.code.toByte()) return i
        }
        return -1
    }
}

/**
 * 注册 desktop 端 [FileCacheProvider] 到 commonMain 的 [FileCacheProviders]。
 *
 * 调用时机: Main.registerSecondaryProviders 中 (任何 CacheManager 文件操作之前)。
 * 依赖 [io.legado.app.help.file.registerDesktopAppFilesDir] 已在 main 阶段1 同步注册。
 */
fun registerDesktopFileCacheProvider() {
    FileCacheProviders.impl = DesktopFileCacheProvider
}
