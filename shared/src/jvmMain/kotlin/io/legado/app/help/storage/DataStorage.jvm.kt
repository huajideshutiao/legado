package io.legado.app.help.storage

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.desktopAppRootDir
import java.nio.file.Paths

/**
 * [DataStorage] 桌面 JVM 实现: 除备份外全部挂在 [desktopAppRootDir] 下, 与
 * `~/.legado/legado.db` 同源; 备份目录与 [io.legado.app.help.storage.BackupShared.backupPath]
 * 保持一致 (filesDir/backup)。
 */
class JvmDataStorage : DataStorage {

    private val rootDir: String = desktopAppRootDir()

    override val chapterCacheDir: String = Paths.get(rootDir, "book_cache").toString()

    override val coversDir: String = Paths.get(rootDir, "covers", "default").toString()

    override val backgroundsDir: String = Paths.get(rootDir, "bg").toString()

    override val fontsDir: String = Paths.get(rootDir, "font").toString()

    // AppFilesDirs 可能晚于本类构造注册, 故惰性取值
    override val backupDir: String
        get() = Paths.get(AppFilesDirs.get().filesDir, "backup").toString()
}

/** 桌面宿主启动早期注册 [DataStorage] (须早于 [io.legado.app.help.book.JvmBookStorage] 构造)。 */
fun registerJvmDataStorage() {
    DataStorageProviders.register(JvmDataStorage())
}
