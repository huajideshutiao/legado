package io.legado.app.help

import io.legado.app.help.file.AppFilesDirs
import kotlin.io.File

/**
 * nativeMain: [FileCacheProvider] 的 iOS / 鸿蒙 两端共用真实实现
 * (基于 kotlin.io.File + AppFilesDirs.cacheDir)。
 *
 * 与 [NativeSourceCacheProvider] 同源设计: 两端实现逻辑一致, 仅文件 API 不同
 * (iOS 原可 NSFileManager + NSData, 鸿蒙用 kotlin.io.File), 统一用 [kotlin.io.File]
 * (Kotlin/Native iOS/ohos target 标准库均支持, 基于 POSIX fs, 行为与 JVM java.io.File 等价),
 * 下沉到 nativeMain 共用, 避免两端复制代码。
 *
 * # 背景
 * commonMain 的 [CacheManager] 文件/二进制层 (getFile/putFile/getByteArray/put(ByteArray)/
 * delete 文件部分) 委托 [FileCacheProviders]。app 端注册 [ACacheFileCacheProvider] (委托 ACache),
 * desktop 端注册 [io.legado.desktop.help.DesktopFileCacheProvider] (基于 java.io.File)。
 * iOS/鸿蒙端未注册时 [FileCacheProviders].impl 为 null, CacheManager 文件层调用静默 no-op
 * (background 所述 P0: getFile 永远返回 null, put(ByteArray) 丢弃, delete 文件部分空操作)。
 *
 * # 存储
 * - 根目录: `{AppFilesDirs.cacheDir}/file_cache/` (与 desktop DesktopFileCacheProvider 同名子目录,
 *   隔离 file_cache 避免与其他 cache 使用方冲突, 与 app 端 ACache 独立目录语义对齐)
 * - 文件名: `key.hashCode()` (与 app 端 ACache.ACacheManager.newFile / desktop 一致, key 可含任意字符)
 *
 * # TTL
 * 复用 app 端 ACache.Utils 的内嵌日期头格式 (`<13位毫秒>-<存活秒数> ` + 数据),
 * 文件格式与 app 端 ACache / desktop DesktopFileCacheProvider 完全一致, 行为可预期:
 * - saveTime = 0: 直接存原始数据, 永不过期
 * - saveTime > 0: 存 `createDateInfo(saveTime) + 数据`, 读取时校验过期则删除文件并返回 null
 *
 * 注册入口 (iOS/鸿蒙共用): [registerNativeFileCacheProvider]
 *
 * 前置依赖: 各端 [registerIosAppFilesDir] / [registerOhosAppFilesDir] 需先注册
 * (本文件持久化目录从 [AppFilesDirs.get].cacheDir 派生)。
 *
 * 模式参考 [NativeSourceCacheProvider] / desktop [io.legado.desktop.help.DesktopFileCacheProvider]。
 */
class NativeFileCacheProvider : FileCacheProvider {

    /**
     * 缓存根目录: `{AppFilesDirs.cacheDir}/file_cache/`。
     *
     * 启动时确保目录存在 (mkdirs 失败静默, 后续真实 I/O 时再报错更易定位;
     * 与 [NativeSourceCacheProvider] 的 cacheRoot 创建行为对齐)。
     */
    private val cacheDir: String = run {
        val base = AppFilesDirs.get().cacheDir
        val root = if (base.endsWith("/")) "${base}file_cache" else "$base/file_cache"
        runCatching { File(root).mkdirs() }
        root
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

    // ---- ACache 兼容的日期头格式 (与 app 端 ACache.Utils / desktop DesktopFileCacheProvider 一致,
    //      保证两端文件格式相同, 行为可预期) ----

    private val separator = ' '

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
 * 注册 [NativeFileCacheProvider] 到 [FileCacheProviders] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [AppFilesDirs] 已注册 (持久化目录从 AppFilesDirs.get().cacheDir 派生)。
 */
fun registerNativeFileCacheProvider() {
    FileCacheProviders.impl = NativeFileCacheProvider()
}
