package io.legado.app.help

/**
 * JsExtensionsCommon file IO 跨平台门面 (expect object)。
 *
 * # 背景
 * 原 app 端 JsExtensions 的 8 个 file IO 方法 (readFile/readTxtFile/deleteFile/
 * getTxtInFolder/downloadFile/cacheFile/importScript) 依赖 [io.legado.app.utils.FileUtilsBase]
 * (jvmAndAndroidMain 专属, 委托 java.io.File)。下沉 commonMain 后, 这些方法需跨端可用,
 * 故抽出本 expect object, 各端 actual 委托本平台 File 实现:
 * - jvmAndAndroidMain: java.io.File (与原 FileUtilsBase 行为一致)
 * - iOS/鸿蒙 nativeMain: kotlin.io.File (POSIX fs, Kotlin/Native 标准库支持)
 *
 * # 设计
 * - 入参统一 String 路径 (避免 commonMain 引入 JDK File 类型, 模式参考 [JsExtensionsPlatform])
 * - 缓存目录走 [io.legado.app.help.file.AppFilesDirs] (commonMain 已下沉)
 * - 仅 JsExtensionsCommon 使用, 标记 internal 避免污染公开 API
 *
 * 模式参考 [JsExtensionsPlatform] / [io.legado.app.utils.FileUtilsBase]。
 */
internal expect object FileUtilsCommon {

    /**
     * 缓存目录绝对路径。
     *
     * - Android: externalCacheDir (与原 `appCtx.externalCache.absolutePath` 一致)
     * - 桌面 JVM / iOS / 鸿蒙: cacheDir (无 externalCache 概念, 走 AppFilesDirs.cacheDir)
     */
    fun getCachePath(): String

    /**
     * 解析缓存相对路径为绝对路径, 含安全检查 (防路径穿越)。
     *
     * - path 以分隔符开头: cachePath + path
     * - 否则: cachePath + separator + path
     * - 校验解析后的 canonicalPath 必须以 cachePath 的父目录为前缀, 否则抛 SecurityException
     *   (与原 app 端 JsExtensions.getFile 安全检查一致)
     */
    fun resolveCachePath(relativePath: String): String

    /** 判断文件/目录是否存在。 */
    fun exist(path: String): Boolean

    /** 读取文件全部字节, 失败返回 null (与原 FileUtilsBase.readBytes 行为一致)。 */
    fun readBytes(path: String): ByteArray?

    /** 写入字节到文件 (覆盖), 自动创建父目录, 成功返回 true。 */
    fun writeBytes(path: String, data: ByteArray): Boolean

    /**
     * 删除文件或目录。
     * - 文件: 直接删除
     * - 目录: 递归删除子项, deleteRootDir 控制是否删除目录本身
     *
     * 成功返回 true (与原 FileUtilsBase.delete 行为一致)。
     */
    fun delete(path: String, deleteRootDir: Boolean = true): Boolean

    /**
     * 列出目录下所有文件 (不含子目录) 的绝对路径。
     * 非目录或不存在返回空列表 (与原 folder.listFiles 行为对齐)。
     */
    fun listFiles(path: String): List<String>

    /** 拼接路径, 用平台分隔符 (与原 FileUtilsBase.getPath(root, vararg) 行为一致)。 */
    fun getPath(rootPath: String, vararg subDirFiles: String): String

    /** 创建文件夹 (含父目录), 已存在则不报错, 成功返回 true。 */
    fun createFolderIfNotExist(path: String): Boolean

    /**
     * 创建文件 (含父目录), 已存在则先删除再创建 (与原 File.createFileReplace 扩展行为一致)。
     * 成功返回 true。
     */
    fun createFileReplace(path: String): Boolean
}
