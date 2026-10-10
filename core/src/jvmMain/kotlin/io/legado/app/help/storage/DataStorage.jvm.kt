package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.help.file.desktopBackupOutputDir
import io.legado.app.help.file.desktopUserExportDir
import java.io.File
import java.nio.file.Paths

/**
 * [DataStorage] 桌面 JVM 实现。
 *
 * 应用私有数据挂在 [desktopAppRootDir] 下 (与 `~/.legado/legado.db` 同源);
 * 用户可见产物 (导出书籍/规则 json) 落桌面, 备份 zip 落文档目录, 见 [desktopUserExportDir]。
 */
class JvmDataStorage : DataStorage {

    private val rootDir: String = desktopAppRootDir()

    override val chapterCacheDir: String = Paths.get(rootDir, "book_cache").toString()

    // 与导入侧写入位置对齐: ReadBookConfigShared/ThemeConfigProvider 落 {filesDir}/bg 与 /font,
    // 直接挂 rootDir 会差一层 files/, 导致导入后读不到 (AppFilesDirs 惰性取值, 同 backupDir)
    override val backgroundsDir: String
        get() = Paths.get(AppFilesDirs.get().filesDir, "bg").toString()

    override val fontsDir: String
        get() = Paths.get(AppFilesDirs.get().filesDir, "font").toString()

    // AppFilesDirs 可能晚于本类构造注册, 故惰性取值
    override val backupDir: String
        get() = Paths.get(AppFilesDirs.get().filesDir, "backup").toString()

    override val userExportDir: String get() = desktopUserExportDir()

    override val defaultBackupDir: String get() = desktopBackupOutputDir()
}

/** 桌面宿主启动早期注册 [DataStorage] (须早于 [io.legado.app.help.book.JvmBookStorage] 构造)。 */
fun registerJvmDataStorage() {
    DataStorageProviders.register(JvmDataStorage())
    warnLegacyBackupLeftovers()
}

/**
 * 旧版备份 zip 落在应用数据目录 (`filesDir`), 新版落 [desktopBackupOutputDir]。
 * 不自动搬运 (用户可能做过软链/自定义), 只在首次发现残留时记一条日志指路。
 */
private fun warnLegacyBackupLeftovers() {
    runCatching {
        val prefs = runCatching { PreferenceProviders.get() }.getOrNull() ?: return
        if (prefs.getBoolean(LEGACY_BACKUP_NOTICE_KEY, false)) return
        val legacyDir = File(AppFilesDirs.get().filesDir)
        val leftovers = legacyDir.listFiles { f ->
            f.isFile && f.name.startsWith("backup") && f.name.endsWith(".zip")
        }
        if (leftovers.isNullOrEmpty()) return
        prefs.putBoolean(LEGACY_BACKUP_NOTICE_KEY, true)
        AppLog.put(
            "旧备份文件仍在应用数据目录 ${legacyDir.absolutePath} (${leftovers.size} 个), " +
                "新备份改存 ${desktopBackupOutputDir()}, 需要的话请自行搬运"
        )
    }
}

private const val LEGACY_BACKUP_NOTICE_KEY = "legacyBackupDirNoticed"
