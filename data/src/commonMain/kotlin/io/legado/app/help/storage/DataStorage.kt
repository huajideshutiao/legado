package io.legado.app.help.storage

import io.legado.app.help.file.AppFilesDirs
import kotlin.concurrent.Volatile

/**
 * 应用存储路径集中 provider: 调用方只问"要哪类目录", 不问"落在哪"。
 *
 * 目录分两层: **应用私有数据** (数据库/缓存/封面/背景/字体) 落在应用数据根目录;
 * **用户可见产物** (导出书籍/规则 json 落 [userExportDir], 备份 zip 落 [defaultBackupDir])
 * 按平台惯例落在系统目录 (桌面=桌面/文档), 用户拿得到。
 *
 * 各端路径映射见实现 (Android `AndroidDataStorage` / 桌面 [JvmDataStorage] /
 * iOS+鸿蒙 `NativeDataStorage`)。一律返回绝对路径字符串, 不保证目录已创建。
 */
interface DataStorage {

    /** 章节正文缓存根目录 (Android `externalFiles/book_cache`, 桌面 `~/.legado/book_cache`)。 */
    val chapterCacheDir: String

    /**
     * 书籍封面的**持久**磁盘缓存目录, 默认 `filesDir/covers` (Android 与原版 Glide
     * `MultiDiskCacheFactory` 同址)。
     *
     * 落数据目录而非缓存目录: 书源失效后封面不可重获, 系统清缓存不该把书架清成一片默认封面。
     */
    val bookCoverCacheDir: String
        get() = AppFilesDirs.get().filesDir.trimEnd('/', '\\') +
            BackupFileOps.separator + "covers"

    /** 阅读背景图目录 (Android `externalFiles/bg`)。 */
    val backgroundsDir: String

    /** 阅读字体目录 (Android `externalFiles/font`)。 */
    val fontsDir: String

    /**
     * 备份**打包暂存**目录 (各端均为 `filesDir/backup`)。
     *
     * 注意: [BackupShared] 收尾会整目录删除, 落地路径请用 [defaultBackupDir]。
     */
    val backupDir: String

    /**
     * 用户可见产物根目录: 导出的书籍 / 书源与替换规则 json 的落地位置。
     *
     * 默认实现回退到 `filesDir/export` (iOS/鸿蒙: filesDir 本身就是用户经"文件"App
     * 可见的沙盒 Documents); 桌面覆盖为系统桌面下的 `legado`, Android 为公共文档目录。
     */
    val userExportDir: String
        get() = AppFilesDirs.get().filesDir.trimEnd('/', '\\') +
            BackupFileOps.separator + "export"

    /** 导出书籍 (TXT/EPUB) 落地目录, 默认 [userExportDir]`/books`。 */
    val bookExportDir: String
        get() = userExportDir.trimEnd('/', '\\') + BackupFileOps.separator + "books"

    /**
     * 备份 zip 默认落地目录 (用户未在设置里指定备份路径时用), 默认 [userExportDir]`/backup`。
     *
     * 返回 null 表示该平台必须由用户先选目录 (Android 需 SAF 授权的 content:// 目录),
     * 调用方应改为拉起目录选择器。
     */
    val defaultBackupDir: String?
        get() = userExportDir.trimEnd('/', '\\') + BackupFileOps.separator + "backup"
}

/**
 * [DataStorage] provider 容器。宿主启动早期注册一次。
 *
 * 模式同 [io.legado.app.help.book.BookStorageProviders] /
 * [io.legado.app.help.config.AppConfigProviders]。
 */
object DataStorageProviders {

    @Volatile
    private var impl: DataStorage? = null

    /** 宿主启动早期注册一次 (任何目录访问之前)。 */
    fun register(impl: DataStorage) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): DataStorage = impl ?: error("DataStorageProviders not registered")

    /** 获取已注册实现, 未注册返回 null (供未接线平台安全回退)。 */
    fun getOrNull(): DataStorage? = impl
}
