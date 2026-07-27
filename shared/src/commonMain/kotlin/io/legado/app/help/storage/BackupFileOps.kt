package io.legado.app.help.storage

/**
 * 跨平台文件 + zip 操作 expect 声明（BackupShared/RestoreShared 用）。
 *
 * # 背景
 * app 端 [io.legado.app.help.storage.Backup] 直接依赖 `java.io.File` +
 * `app.utils.FileUtils` + `app.utils.compress.ZipUtils`, 这些 API 在 commonMain
 * 不可见 (jvmAndAndroidMain 独占)。本 expect 把 Backup/Restore 用到的 7 个
 * 原子操作 (delete / createFolderIfNotExist / createFileIfNotExist / writeText /
 * readText / exists / zipFiles / unZipToPath) 抽象为平台无关函数签名, 各 actual
 * 自行调用平台 SDK。
 *
 * - jvmAndAndroidMain actual: 委托 `FileUtilsBase` + `ZipUtils` (JDK 实现, 行为零变化)
 * - nativeMain actual (iOS/鸿蒙共用): `kotlin.io.File` + `NativeZipCodec` 真实实现
 *
 * # 路径约定
 * - 所有路径用 String (与 [io.legado.app.help.file.AppFilesDir] 一致), 不暴露 File 类型
 * - 路径分隔符由 actual 自行处理 (jvm 端用 `File.separator`)
 *
 * 模式参考 `io.legado.app.utils.MimeBase64Decoder` (expect object, JVM-only 包装)。
 */
expect object BackupFileOps {

    /** 路径分隔符 (jvm = `File.separator`, iOS/鸿蒙 = "/"). */
    val separator: String

    /** 删除文件或目录 (递归删除子项)。目录不存在视为成功。 */
    fun delete(path: String): Boolean

    /** 判断文件或目录是否存在。 */
    fun exists(path: String): Boolean

    /** 创建文件夹 (含父目录), 已存在直接返回 File。 */
    fun createFolderIfNotExist(path: String)

    /**
     * 创建文件 (含父目录), 已存在直接返回。
     *
     * @return 文件绝对路径 (actual 内部转换)
     */
    fun createFileIfNotExist(path: String): String

    /** 写文本到文件 (覆盖写, 默认 UTF-8)。文件不存在则创建。 */
    fun writeText(path: String, text: String)

    /** 读取文件文本 (默认 UTF-8)。文件不存在抛异常。 */
    fun readText(path: String): String

    /**
     * 列出目录下所有文件 (不含子目录) 的绝对路径。
     *
     * 目录不存在或为空目录时返回 null (与 `java.io.File.listFiles()` 语义一致)。
     * 用于缓存清理场景 (如 [io.legado.app.help.config.ReadBookConfigShared.clearBgAndCache]
     * / [io.legado.app.help.config.ThemeConfigProvider.clearBg])。
     *
     * @return 文件绝对路径列表, 目录不存在/空返回 null
     */
    fun listFiles(path: String): List<String>?

    /**
     * 复制文件 (覆盖写, 父目录不存在则创建)。
     *
     * 与 app 端 [io.legado.app.help.storage.Backup] 的 `copyBackup(File)` 纯文件复制
     * 同语义 (FileInputStream → FileOutputStream → copyTo)。供各端路径备份复用,
     * 不依赖 Android FileDoc / Uri (content scheme 由调用方先解析为本地路径)。
     *
     * @param srcPath 源文件路径
     * @param destPath 目标文件路径 (父目录不存在则创建, 已存在则覆盖)
     */
    fun copyFile(srcPath: String, destPath: String)

    /**
     * 把多个文件压成 zip。
     *
     * @param srcPaths 源文件路径列表 (按顺序写入 zip)
     * @param zipPath 目标 zip 文件路径
     * @return 是否成功
     */
    fun zipFiles(srcPaths: List<String>, zipPath: String): Boolean

    /**
     * 把 zip 解压到目标目录。
     *
     * @param zipPath zip 文件路径
     * @param destDir 目标目录 (不存在则创建)
     */
    fun unZipToPath(zipPath: String, destDir: String)
}
