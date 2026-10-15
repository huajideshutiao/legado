package io.legado.app.help.storage

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.help.file.desktopUserExportDir
import java.nio.file.Paths

/**
 * [DataStorage] 桌面 JVM 实现。
 *
 * 应用私有数据挂在 [desktopAppRootDir] 下 (与 `~/.legado/legado.db` 同源);
 * 用户可见产物 (导出书籍/规则 json) 落桌面, 见 [desktopUserExportDir]。
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
}

/** 桌面宿主启动早期注册 [DataStorage] (须早于 [io.legado.app.help.book.JvmBookStorage] 构造)。 */
fun registerJvmDataStorage() {
    DataStorageProviders.register(JvmDataStorage())
}
