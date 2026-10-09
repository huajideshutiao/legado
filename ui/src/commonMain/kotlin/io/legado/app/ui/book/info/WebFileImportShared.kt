package io.legado.app.ui.book.info

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.archive.ArchiveProviders
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.http.OkHttpProxyClientProviders
import io.legado.app.help.http.header
import io.legado.app.help.http.newCallResponse
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBookProviders
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * webFile 下载导入的平台无关部分 (四端共用)。
 *
 * 对照 app 端 `BookInfoViewModel.importWebFile` / `downloadWebFile` / `getArchiveFilesName` /
 * `importBookFromArchive` 与 `BaseReadViewModel.loadWebFile` 里与平台无关的那几段。
 *
 * # 边界
 * 留在各端的部分 (不在此处): 等待框、条目选择框、"去设置目录"动作、不支持格式时交系统程序打开、
 * 以及选择框之后的分支串联 (四端 UI 形态不同, 那些属于平台能力面)。
 * 此处只提供"拿到文件 / 算出文件名 / 从压缩包导入 / 导入后合并"这几段纯编排。
 *
 * # 落库
 * 目录落库不在此处: 调用方在拿到 [WebFileImportShared.importWebFile] /
 * [WebFileImportShared.importBookFromArchive] 返回的书籍后, 统一经
 * [io.legado.app.help.book.BookChapterLoader.fetchFromSource] 拉目录并落库
 * (与阅读页同一条链), 不另写一份"取目录 + 落库"。
 *
 * # 与 app 端的两处等价替换
 * - `AnalyzeUrl` (app 端 JsApi 子类) → [AnalyzeUrlCore] (commonMain 同实现基类), 解析行为一致
 * - `UrlUtil.getFileName` (在 jvmAndAndroidMain, 含 HEAD 探测) → 本文件 [getUrlFileName] 复刻,
 *   照 [io.legado.app.help.JsExtensionsCommon] 复刻 `UrlUtil.getSuffix` 的同一先例
 */
object WebFileImportShared {

    /**
     * 从书籍的下载地址构建 webFile 列表 (对照 app 端 `BaseReadViewModel.loadWebFile`)。
     *
     * @param book 书籍实体 (读 `downloadUrls`)
     * @param source 书源 (由调用方查好; 本地书传 null)
     * @return 每个下载地址对应一个条目; 无下载地址时为空列表
     */
    suspend fun buildWebFiles(book: Book, source: BookSource?): List<FileBook.WebFile> {
        val urls = book.downloadUrls ?: return emptyList()
        val fileNameNoExtension = if (book.author.isBlank()) book.name
        else "${book.name} 作者：${book.author}"
        return urls.map { url ->
            val analyzeUrl = AnalyzeUrlCore(
                url, source = source,
                coroutineContext = currentCoroutineContext()
            )
            val fileName = getUrlFileName(analyzeUrl.url, analyzeUrl.headerMap)
                ?: "$fileNameNoExtension.${analyzeUrl.type}"
            FileBook.WebFile(url, fileName)
        }
    }

    /**
     * 支持的书籍格式: 下载并导入为本地书, 返回合并后的书籍 (对照 app 端 `importWebFile`)。
     *
     * 不含目录落库与等待框, 两者由调用方处理 (见类注释)。
     * 保存位置未设置时 [io.legado.app.exception.InvalidBooksDirException] 原样抛出,
     * 由调用方转成"去设置目录"动作。
     */
    @Throws(Exception::class)
    suspend fun importWebFile(book: Book, webFile: FileBook.WebFile): Book {
        val source = bookSourceOf(book)
        val fileName = book.getExportFileName(webFile.suffix)
        val savedRef = FileBookProviders.get().saveBookFile(webFile.url, fileName, source)
        val localBook = FileBook.importLocalFile(savedRef)
        return FileBook.mergeBook(localBook, book)
    }

    /**
     * 仅下载文件, 返回保存引用 (对照 app 端 `downloadWebFile`)。
     *
     * 不含等待框, 由调用方处理。保存位置未设置时同样原样抛出。
     */
    @Throws(Exception::class)
    suspend fun downloadWebFile(book: Book, webFile: FileBook.WebFile): String {
        val source = bookSourceOf(book)
        val fileName = book.getExportFileName(webFile.suffix)
        return FileBookProviders.get().saveBookFile(webFile.url, fileName, source)
    }

    /**
     * 列出压缩包内的书籍条目名 (对照 app 端 `getArchiveFilesName`)。
     *
     * 与 app 端调用点同口径: 先按后缀校验确为压缩包 (非压缩包报错, 不静默返回空),
     * 再只取书籍文件 (底层要求 filter 非 null, 传 null 会恒返回空列表)。
     * 读失败已由 provider 记日志并返回空列表, 调用方据此弹“不支持的压缩包条目”。
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun getArchiveFilesName(archivePath: String): List<String> {
        // 对照 app 端 ArchiveUtils.checkArchive: 后缀不是压缩包就报错
        val fileName = archivePath.substringAfterLast('/').substringAfterLast('\\')
        require(AppPattern.archiveFileRegex.matches(fileName)) { "Unexpected file suffix" }
        return ArchiveProviders.get()
            .getFilesName(archivePath) { AppPattern.bookFileRegex.matches(it) }
    }

    /**
     * 从压缩包内指定条目导入书籍, 返回合并后的书籍 (对照 app 端 `importBookFromArchive`)。
     *
     * 不含目录落库与等待框, 由调用方处理 (见类注释)。
     */
    @Throws(Exception::class)
    suspend fun importBookFromArchive(
        archivePath: String,
        archiveEntryName: String,
        book: Book,
    ): Book {
        val suffix = archiveEntryName.substringAfterLast(".")
        val saveFileName = book.getExportFileName(suffix)
        val books = FileBook.importFromArchive(archivePath, saveFileName) {
            it.contains(archiveEntryName)
        }
        val imported = books.first()
        return FileBook.mergeBook(imported, book)
    }

    /** 书源查询: 本地书无书源 (对照 app 端 `appDb.bookSourceDao.getBookSource(book.origin)` 的取用场景)。 */
    suspend fun bookSourceOf(book: Book): BookSource? =
        AppDbProviders.get().bookSourceDao.getBookSource(book.origin)

    // ---- 文件名推导 (复刻 jvmAndAndroidMain `UrlUtil.getFileName`) ----

    /** 路径后缀不可信的黑名单 (与 `UrlUtil.unExpectFileSuffixs` 一致)。 */
    private val unExpectFileSuffixs = arrayOf("php", "html")

    private val fileSuffixRegex = Regex("^[a-z\\d]+$", RegexOption.IGNORE_CASE)

    /**
     * 根据网络 url 获取文件名 (对应 `UrlUtil.getFileName`)。
     *
     * 先按 URL 路径取, 取不到再 HEAD 探测响应头; 都没有返回 null,
     * 由调用方套用 `书名.后缀` 兜底 (与原版 `?:` 分支一致)。
     */
    suspend fun getUrlFileName(
        fileUrl: String,
        headerMap: Map<String, String>? = null,
    ): String? = kotlin.runCatching {
        getFileNameFromPath(fileUrl) ?: getFileNameFromResponseHeader(fileUrl, headerMap)
    }.getOrNull()

    /** URL 路径末段是否为合法文件名 (对应 `UrlUtil.getFileNameFromPath`)。 */
    private fun getFileNameFromPath(fileUrl: String): String? {
        val path = CustomUrl(fileUrl).getUrl()
        val name = path.substringAfterLast("/").substringBefore("?").substringBefore("#")
        val suffix = legalSuffix(path)
        return if (suffix != null && suffix !in unExpectFileSuffixs && name.isNotEmpty()) {
            name
        } else {
            AppLog.put("getFileNameFromPath: Unexpected file suffix: ${suffix ?: ""}")
            null
        }
    }

    /**
     * HEAD 探测响应头取文件名 (对应 `UrlUtil.getFileNameFromResponseHeader`)。
     *
     * # 已知差异 (如实记录, 不静默)
     * 原版禁止跟随重定向, 以便从 `Location` 头再解析一次路径。跨平台 HTTP 抽象没有该开关
     * (客户端自行跟随重定向), 故改取"响应所属请求的最终 URL"再解析一次路径。Android 端
     * 该最终 URL 是重定向后的真实地址, 与原版 `Location` 分支等效; iOS/鸿蒙端的请求 URL
     * 恒为原地址, 这一补救分支不生效, 仅剩 `Content-Disposition` 一条路, 两者都没有时
     * 返回 null 走 `书名.后缀` 兜底。
     */
    private suspend fun getFileNameFromResponseHeader(
        fileUrl: String,
        headerMap: Map<String, String>?,
    ): String? {
        val response = OkHttpProxyClientProviders.get().getProxyClient(null).newCallResponse {
            url(fileUrl)
            method("HEAD", null)
            headerMap?.forEach { (key, value) -> header(key, value) }
        }
        try {
            response.header("Content-Disposition")?.let { raw ->
                parseContentDisposition(raw)?.let { return it }
            }
            val finalUrl = response.request.url.toString()
            if (finalUrl.isNotEmpty() && finalUrl != fileUrl) {
                getFileNameFromPath(finalUrl)?.let { return it }
            }
            AppLog.put("Cannot obtain URL file name, enable recordLog for response header")
            return null
        } finally {
            response.close()
        }
    }

    /**
     * 解析 Content-Disposition 里的文件名 (对应 `UrlUtil.getFileNameFromResponseHeader` 的解析段)。
     *
     * 取第一条带 `filename` 的字段: `filename*=charset''name` 做百分号解码,
     * `filename="name"` 去掉成对引号。
     */
    private fun parseContentDisposition(raw: String): String? {
        val candidate = raw.split(';').firstOrNull { it.contains("filename") } ?: return null
        val value = candidate.substringAfter("=").trim().trim('"')
        if (!candidate.contains("filename*")) return value
        return percentDecode(value.substringAfter("''", value))
    }

    /** 百分号解码 (commonMain 无 URLDecoder; 按 UTF-8 还原, 非百分号部分逐字符透传)。 */
    private fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hi = if (c == '%' && i + 2 < text.length) text[i + 1].digitToIntOrNull(16) else null
            val lo = if (hi != null) text[i + 2].digitToIntOrNull(16) else null
            if (hi != null && lo != null) {
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes.add(it) }
                i++
            }
        }
        val out = ByteArray(bytes.size)
        bytes.forEachIndexed { index, b -> out[index] = b }
        return out.decodeToString()
    }

    /** 合法文件后缀; 非法或缺失返回 null (对应 `UrlUtil.getSuffix` 走 default 分支的判据)。 */
    private fun legalSuffix(str: String): String? {
        val suffix = str.substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringAfterLast(".", "")
        return if (suffix.length > 5 || !suffix.matches(fileSuffixRegex)) null else suffix
    }
}
