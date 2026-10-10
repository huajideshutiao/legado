package io.legado.app.help.extension

import io.legado.app.data.entities.VirtualPluginSourcePrefix
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.model.manga.MangaSourceMapper
import io.legado.app.model.plugin.pluginInstalledOf

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
 * 首次调用会读插件包并落盘, 调用方应在后台线程调用。
 */
object PluginSourceIcons {

    /** 缓存目录名 (位于应用私有缓存目录下)。 */
    private const val CACHE_DIR_NAME = "pluginIcons"

    /**
     * 插件源 URL → 图标缓存文件绝对路径。
     *
     * 非 tachiyomi 插件源、插件包已卸载、包内取不到图标、缓存写入失败都返回 null
     * (调用方回落书源名首字占位)。
     */
    fun iconPathOf(bookSourceUrl: String): String? {
        val pkgName = pkgNameOf(bookSourceUrl) ?: return null
        val versionCode = pluginInstalledOf(pkgName)?.versionCode ?: return null
        val path = FileUtilsCommon.getPath(iconCacheDir(), iconFileName(pkgName, versionCode))
        if (FileUtilsCommon.exist(path)) return path
        val bytes = MangaExtensionHostProviders.getOrNull()?.readExtensionIcon(pkgName)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return path.takeIf { FileUtilsCommon.writeBytes(it, bytes) }
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
