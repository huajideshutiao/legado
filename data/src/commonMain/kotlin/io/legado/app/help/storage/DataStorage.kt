package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import kotlin.concurrent.Volatile

/**
 * 应用存储路径集中 provider: 调用方只问"要哪类目录", 不问"落在哪"。
 *
 * 目录分两层: **应用私有数据** (数据库/缓存/封面/背景/字体) 落在应用数据根目录;
 * **用户产物** (导出书籍/规则 json 落 [userExportDir], 备份 zip 落 [defaultBackupDir])
 * 优先按平台惯例落在用户找得到的位置 (桌面=桌面/文档, iOS=Documents), 鸿蒙无用户可见的
 * 应用目录, 导出书籍由用户经目录选择器自选公共目录 ([defaultBookExportDir] 为 null)。
 *
 * 各端路径映射见实现 (Android `AndroidDataStorage` / 桌面 [JvmDataStorage] /
 * iOS [NativeDataStorage] / 鸿蒙 `OhosDataStorage`)。一律返回绝对路径字符串, 不保证目录已创建。
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
     * 导出产物根目录: 书源与替换规则 json 的落地位置。
     *
     * 默认实现回退到 `filesDir/export` (iOS: filesDir 本身就是用户经"文件"App
     * 可见的沙盒 Documents; 鸿蒙: 沙盒不可见, 仅供应用内部消费);
     * 桌面覆盖为系统桌面下的 `legado`, Android 为公共文档目录。
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

    /**
     * 导出书籍默认落地目录 (用户未在"书架管理 → 选择导出文件夹"里指定时的落点),
     * 默认 [bookExportDir]。
     *
     * 返回 null 表示该平台必须由用户先选目录 (Android 需 SAF 授权的 content:// 目录;
     * 鸿蒙的应用沙盒对文件管理器不可见, 导出产物必须落用户选定的可见目录)。
     * 语义与 [defaultBackupDir] 完全一致:
     * 用户已选则以用户选择为准, 未选则用本默认值 (不写 pref、不打扰用户),
     * 本默认值为 null 才拉起目录选择器 —— 取值顺序见 [effectiveBookExportDir]。
     */
    val defaultBookExportDir: String?
        get() = bookExportDir

    /**
     * 书籍文件默认落地目录 (用户未在"其它设置 → 书籍保存位置"里指定时的落点),
     * 默认 `filesDir/books` (iOS/鸿蒙 = 应用沙盒内的固定 books 目录)。
     *
     * 返回 null 表示该平台必须由用户先选目录 (Android 需 SAF 授权的 content:// 目录),
     * 调用方应改为拉起目录选择器。语义与 [defaultBackupDir] 完全一致:
     * 用户已选则以用户选择为准, 未选则用本默认值 (不写 pref、不打扰用户),
     * 本默认值为 null 才拉起目录选择器。
     */
    val defaultBookDir: String?
        get() = AppFilesDirs.get().filesDir.trimEnd('/', '\\') +
            BackupFileOps.separator + "books"
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

/**
 * 有效导出目录 (与备份路径 [DataStorage.defaultBackupDir] 同一套"用户偏好键 + 可为 null 的
 * 平台惯例目录"语义, 取值顺序逐字对照 `BackupConfigRoute` 的 onBackup 三分支; 书籍保存位置
 * 的 `effectiveBookSaveDir` 是同一形制):
 *
 * 1. 用户选过 (书架管理 → 选择导出文件夹, [PreferKey.bookExportPath]) → 用选定目录;
 * 2. 未选过但平台有惯例导出目录 ([DataStorage.defaultBookExportDir]) → 用惯例目录,
 *    不写偏好、不打扰用户 (iOS 的 Documents/export 对用户可见, 属于这一支);
 * 3. 都没有 (Android: 写公共目录要 SAF 授权; 鸿蒙: 应用沙盒对文件管理器不可见,
 *    两端惯例目录均为 null) → 返回 null,
 *    调用方应改为拉起目录选择器, 选中后写 [PreferKey.bookExportPath] 再继续。
 *
 * 只负责取出"目录标识", 不做路径解析: Android 端选过的值是 SAF `content://` 树引用,
 * 由 ExportBookService / FileDoc 自行解析; 桌面/iOS/鸿蒙 是普通绝对路径。
 */
fun effectiveBookExportDir(): String? =
    PreferenceProviders.get().getString(PreferKey.bookExportPath)?.takeIf { it.isNotBlank() }
        ?: DataStorageProviders.getOrNull()?.defaultBookExportDir
