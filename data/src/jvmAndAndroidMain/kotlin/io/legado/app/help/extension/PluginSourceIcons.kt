package io.legado.app.help.extension

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.VirtualPluginSourcePrefix
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.model.manga.MangaSourceMapper
import io.legado.app.model.plugin.pluginInstalledOf
import java.io.File
import java.io.RandomAccessFile

/**
 * 插件源虚拟行的行首图标 (从插件包读启动图标并落盘缓存)。
 *
 * 插件源 (tachi/aniyomi 系) 的虚拟书源行没有任何行内图标字段, 图标只能来自插件包本身;
 * 共享扩展与私有扩展同包名, 读包入口在扩展宿主的 [MangaExtensionHost.readExtensionIcon]。
 * TVBox 站点源没有包内图标来源, 本对象对其恒返回 null (调用方按书源名首字占位)。
 *
 * 缓存键是「包名 + 插件版本」: 插件更新后键随之改变, 旧版本文件成为无人引用的残留, 由
 * [prune] 清理。缓存落 [AppFilesDirs.cacheDir] 下的私有缓存区, 随「清除缓存」整体清掉,
 * 下次显示重新提取, 不会留下指向缺失文件的引用。
 *
 * 缓存文件一律是完整 PNG: 提取侧统一编码成 PNG, 写入侧先写临时文件再改名, 取用侧按
 * [isIntactIcon] 判完整性 —— 写入被打断只会留下临时文件, 不会留下指向半截 png 的缓存。
 *
 * 首次调用会读插件包并落盘, 调用方应在后台线程调用。
 */
object PluginSourceIcons {

    /** 缓存目录名 (位于应用私有缓存目录下)。 */
    private const val CACHE_DIR_NAME = "pluginIcons"

    /** 原子写的临时文件后缀 (与扩展安装落盘同一做法: 先写临时文件再改名)。 */
    private const val TEMP_SUFFIX = ".part"

    /** PNG 文件签名 (PNG 规范 5.2 PNG file signature)。 */
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** IEND 块开头 8 字节 (数据长度 0 + 块类型)。 */
    private val PNG_IEND_HEAD = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44)

    /** 末尾该留出的字节数: IEND 块的数据长度 4 + 类型 4 + CRC 4。 */
    private const val PNG_IEND_SIZE = 12

    /** 合法 PNG 的最短长度: 签名 8 + IHDR 块 25 + IEND 块 12, 再短的必然残缺。 */
    private const val MIN_PNG_SIZE = 8 + 25 + PNG_IEND_SIZE

    /**
     * 插件源 URL → 图标缓存文件绝对路径。
     *
     * 非 tachiyomi 插件源、插件包已卸载、包内取不到图标、缓存写入失败都返回 null
     * (调用方回落书源名首字占位)。
     *
     * 命中缓存前先按 [isIntactIcon] 判一次完整性: 判为损坏即当缓存不存在重新提取, 不必等插件
     * 更新或清缓存。
     *
     * 边界: 本方法逐行被书源列表流调用, 任何异常都收成 null —— 漏到上游会把整条列表流终结
     * (此后不再随插件与书源库更新)。
     */
    fun iconPathOf(bookSourceUrl: String): String? =
        runCatching { resolveIconPath(bookSourceUrl) }
            .getOrElse { e ->
                // 落日志自身也要写文件, 再套一层: 日志失败同样不能让异常漏出本方法
                runCatching { AppLog.put("插件源图标读取出错: $bookSourceUrl", e) }
                null
            }

    /** [iconPathOf] 的实现体 (允许抛出, 由外层收容)。 */
    private fun resolveIconPath(bookSourceUrl: String): String? {
        val pkgName = pkgNameOf(bookSourceUrl) ?: return null
        val versionCode = pluginInstalledOf(pkgName)?.versionCode ?: return null
        val path = FileUtilsCommon.getPath(iconCacheDir(), iconFileName(pkgName, versionCode))
        if (FileUtilsCommon.exist(path) && isIntactIcon(File(path))) return path
        val bytes = MangaExtensionHostProviders.getOrNull()?.readExtensionIcon(pkgName)
            // 落盘前按同一判据过一遍: 写不出完整 PNG 就回占位, 不让残缺字节进缓存
            ?.takeIf { isIntactPng(it) }
            ?: return null
        return path.takeIf { writeIconAtomically(it, bytes) }
    }

    /**
     * 清理缓存目录里已无插件引用的图标文件 (插件卸载或更新后的旧版本残留)。
     *
     * 由扩展装载流程在虚拟行同步后调用: 此刻的已装载集合就是唯一的活引用集合, 目录因此有界
     * (每个已装载插件至多一个当前版本的文件)。
     */
    fun prune() {
        val live = liveIconFileNames()
        FileUtilsCommon.listFiles(iconCacheDir())
            .filter { FileUtilsCommon.getName(it) !in live }
            .forEach { FileUtilsCommon.delete(it) }
    }

    /**
     * 图标缓存是否完整可用。
     *
     * 判据 (3 次小读, 不整文件解码): 长度不小于 [MIN_PNG_SIZE]、头 8 字节等于 PNG 签名、
     * 末尾 12 字节以 IEND 块开头 —— PNG 规定 IEND 是最后一个块, 写入中途被杀留下的截断文件
     * 必然缺它。代价与图标体积无关 (常量级), 不覆盖的情形只有「结构完整但像素数据损坏」,
     * 那要整文件解码才能发现, 与命中缓存的开销不相称。
     */
    private fun isIntactIcon(file: File): Boolean {
        val length = file.length()
        if (length < MIN_PNG_SIZE) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(PNG_SIGNATURE.size)
                raf.readFully(head)
                raf.seek(length - PNG_IEND_SIZE)
                val tail = ByteArray(PNG_IEND_SIZE)
                raf.readFully(tail)
                matchesAt(head, 0, PNG_SIGNATURE) && matchesAt(tail, 0, PNG_IEND_HEAD)
            }
        }.getOrDefault(false)
    }

    /** 内存字节的完整性判据 (与 [isIntactIcon] 同一条)。 */
    private fun isIntactPng(bytes: ByteArray): Boolean =
        bytes.size >= MIN_PNG_SIZE &&
            matchesAt(bytes, 0, PNG_SIGNATURE) &&
            matchesAt(bytes, bytes.size - PNG_IEND_SIZE, PNG_IEND_HEAD)

    /** [bytes] 自 [offset] 起是否与 [expected] 逐字节相同。 */
    private fun matchesAt(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean =
        expected.indices.all { bytes[offset + it] == expected[it] }

    /**
     * 图标缓存落盘: 先写同目录的 [TEMP_SUFFIX] 临时文件再改名 (与扩展安装落盘同一做法)。
     *
     * 改名覆盖不了已存在的目标 (Windows 上 rename 会失败, Java 平台差异), 故先删目标: 走到这里
     * 的目标只可能是被判为损坏的旧缓存或不存在, 删掉不丢有效数据, 且两端行为一致。
     */
    private fun writeIconAtomically(path: String, bytes: ByteArray): Boolean {
        val file = File(path)
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        return try {
            file.parentFile?.mkdirs()
            temp.writeBytes(bytes)
            if (file.exists()) file.delete()
            val renamed = temp.renameTo(file)
            if (!renamed) temp.delete()
            renamed
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private fun iconCacheDir(): String =
        FileUtilsCommon.getPath(AppFilesDirs.get().cacheDir, CACHE_DIR_NAME)

    /** 缓存文件名: 包名 + 插件版本 (版本变即换文件)。 */
    private fun iconFileName(pkgName: String, versionCode: Long): String =
        "${pkgName}_$versionCode.png"

    /** 已装载插件当前版本的图标文件名集合 (即缓存里应当保留的全部文件)。 */
    private fun liveIconFileNames(): Set<String> = buildSet {
        MangaExtensionManager.loadedExtensions.value.values.forEach { extension ->
            add(iconFileName(extension.pkgName, extension.versionCode))
        }
    }

    /** 插件源 URL → 归属插件包名; 非 tachiyomi 前缀、注册表无该源、包名非法时 null。 */
    private fun pkgNameOf(bookSourceUrl: String): String? {
        if (!bookSourceUrl.startsWith(VirtualPluginSourcePrefix.TACHIYOMI)) return null
        val sourceId = MangaSourceMapper.sourceIdOf(bookSourceUrl) ?: return null
        val pkgName = MangaExtensionManager.sources.value.firstOrNull { it.source.id == sourceId }?.pkgName
            ?: MangaExtensionManager.animeSources.value.firstOrNull { it.source.id == sourceId }?.pkgName
        // 包名参与缓存文件路径拼接: 只接受合法扩展包名 (与扩展文件落盘同一约束)
        return pkgName?.takeIf { isValidExtensionPackageName(it) }
    }
}
