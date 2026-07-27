package io.legado.app.service

import io.legado.app.data.entities.Book
import io.legado.app.lib.epublib.domain.Author
import io.legado.app.lib.epublib.domain.Date
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.Metadata
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * 导出书籍业务逻辑下沉 (shared jvmAndAndroidMain)。
 *
 * # 背景
 *
 * app 端 [ExportBookService] 是 Android Service, 包含 EPUB/CBZ/TXT 导出逻辑。
 * Service 框架 (onStartCommand / 通知 / lifecycleScope) 不可下沉, 但导出业务
 * 逻辑可下沉到 shared 供 desktop / iOS / 鸿蒙 复用。
 *
 * 本类提取 [ExportBookService] 中**不依赖 FileDoc / Glide / appCtx.assets /
 * BookHelp.getImage / FileBook / ReadBook** 的纯业务方法:
 * - [setEpubMetadata]: 设置 EpubBook 元数据 (纯 epublib domain 操作)
 * - [exportTxt]: TXT 导出主流程 (OutputStream 写入 + WebDav 上传)
 *
 * 正文拼接纯逻辑 (getAllContents / getExportData) 已进一步下沉到 commonMain
 * [ExportBookContentShared], 本类通过 [contentShared] 委托调用, 供 iOS / 鸿蒙复用。
 *
 * # 平台依赖注入
 *
 * 以下 Android 专属 / 未下沉依赖通过 [ExportBookDeps] 注入:
 * - 字符串资源 (R.string.author_show / intro_show): app 端 getString
 * - AppConfig (exportCharset / exportUseReplace / exportNoChapterName /
 *   exportToWebDav): app 端 AppConfig 单例
 * - ContentProcessor (getContent / getTitleReplaceRules): app 端
 *   ContentProcessor.get(book) (WeakReference 缓存)
 * - getExportFileName: app 端 Book.getExportFileName 扩展 (未下沉)
 * - 文件输出 (FileDoc): app 端 FileDoc + 扩展 (未下沉, 通过 [ExportBookDeps.prepareExportFile])
 * - 事件 / 进度 (EventBus / exportProgress / exportMsg): app 端单例
 * - WebDav 上传 (AppWebDav): app 端 (android.net.Uri)
 *
 * # 未下沉的方法 (保留 app 端 ExportBookService)
 *
 * 以下方法强依赖未下沉的 Android API, 保留在 [ExportBookService]:
 * - exportEpub: setCover (Glide) + setAssets (appCtx.assets) + setEpubContent (fixPic)
 * - exportCbz: extractCbzPages (BookHelp.getImage / FileBook.getImage)
 * - setEpubContent / fixPic: BookHelp.getImage (File) + FileResourceProvider
 * - setAssets / setAssetsExternal / setCover: FileDoc + assets + Glide
 * - ensureChapterList: FileBook.getChapterList + ReadBook.onChapterListUpdated
 * - CustomExporter: 依赖上述 setEpubContent / setCover / setAssets / fixPic
 *
 * 后续 FileDoc / BookHelp.getImage / ImageLoader 等下沉后, 可进一步把
 * exportEpub / exportCbz / CustomExporter 下沉到本类。
 *
 * # 已复用的 shared 下沉件
 *
 * - [ExportBookContentShared]: shared commonMain (正文拼接纯逻辑, 由 [contentShared] 委托)
 * - [ExportBookDeps] 继承 [ExportBookContentDeps], 复用同一份平台 deps 实现
 *
 * commonMain 侧 (ExportBookContentShared) 复用: AppDbProviders / BookHelpProviders /
 * AppConst.MAX_THREAD / HtmlFormatter / mapAsync / collectIndexed。
 *
 * @see ExportBookDeps 平台依赖注入接口
 */
class ExportBookShared(private val deps: ExportBookDeps) {

    /**
     * 设置 EpubBook 元数据 (标题/作者/语言/日期/出版者/简介)。
     *
     * 对应 app 端 `ExportBookService.setEpubMetadata`, 逻辑完全一致。
     * 纯 epublib domain 操作, 无平台依赖。
     */
    fun setEpubMetadata(book: Book, epubBook: EpubBook) {
        val metadata = Metadata()
        metadata.titles?.add(book.name)//书籍的名称
        metadata.authors.add(Author(book.getRealAuthor()))//书籍的作者
        metadata.language = "zh"//数据的语言
        metadata.dates.add(Date())//数据的创建日期
        metadata.publishers.add("Legado")//数据的创建者
        metadata.descriptions.add(book.getDisplayIntro())//书籍的简介
        //metadata.subjects.add("")//书籍的主题，在静读天下里面有使用这个分类书籍
        epubBook.metadata = metadata
    }

    // 正文拼接纯逻辑委托 (commonMain ExportBookContentShared), 供全平台复用
    private val contentShared = ExportBookContentShared(deps)

    /**
     * 获取全书正文文本 (供 TXT 导出), 委托 [contentShared] (commonMain)。
     *
     * 章节列表来自 AppDbProviders, 已缓存正文来自 BookHelpProviders,
     * 事件 / 进度 / 字符串资源 / ContentProcessor 通过 [deps] 注入。
     */
    suspend fun getAllContents(
        book: Book,
        append: (text: String) -> Unit
    ) = contentShared.getAllContents(book, append)

    /**
     * 导出 TXT。
     *
     * 对应 app 端 `ExportBookService.exportTxt(path, book)`, 逻辑完全一致:
     * 1. 在 path 目录下准备导出文件 (find+delete 已存在 + createFileIfNotExist +
     *    openOutputStream), 由 [deps.prepareExportFile] 完成
     * 2. 按 [deps.exportCharset] 编码 BufferedWriter 写入 [getAllContents] 拼接的全书正文
     * 3. 若 [deps.exportToWebDav] 开启, 上传 WebDav
     * 4. 异常时删除半成品文件 (回滚)
     *
     * @param path 导出目录 (FileDoc.fromDir 可识别的路径)
     * @param book 书籍
     */
    suspend fun exportTxt(path: String, book: Book) {
        deps.removeExportMsg(book.bookUrl)
        deps.postExportEvent(book.bookUrl)
        val filename = deps.getExportFileName(book, "txt")
        val handle = deps.prepareExportFile(path, filename)
        try {
            val charset = Charset.forName(deps.exportCharset)
            handle.outputStream.bufferedWriter(charset).use { bw ->
                getAllContents(book) { text ->
                    bw.write(text)
                }
            }
            if (deps.exportToWebDav) {
                deps.exportToWebDav(handle.uri, filename)
            }
        } catch (e: Throwable) {
            runCatching { deps.deleteExportUri(handle.uri) }
            throw e
        }
    }
}

/**
 * 导出书籍平台依赖注入接口。
 *
 * 由各平台实现并注入 [ExportBookShared]:
 * - Android: [io.legado.app.service.ExportBookService] 内部 `ExportBookDepsImpl`
 *   实现, 桥接 AppConfig / ContentProcessor / FileDoc / AppWebDav / R.string /
 *   EventBus 等
 * - Desktop / iOS / 鸿蒙: 后续按需实现 (需先下沉 FileDoc / ContentProcessor /
 *   AppConfig 等, 或提供桌面端等价实现)
 *
 * 详见 [ExportBookShared] 类注释 "平台依赖注入" 段。
 */
interface ExportBookDeps : ExportBookContentDeps {

    /** 导出编码 (对应 AppConfig.exportCharset, 如 "UTF-8")。 */
    val exportCharset: String

    /** 是否导出到 WebDav (对应 AppConfig.exportToWebDav)。 */
    val exportToWebDav: Boolean

    /**
     * 生成导出文件名 (对应 Book.getExportFileName(suffix))。
     * 原 app 端扩展未下沉, 由平台实现桥接。
     */
    fun getExportFileName(book: Book, suffix: String): String

    /**
     * 在 [dirPath] 目录下准备导出文件: 查找同名文件并删除 (find+delete),
     * 创建新文件 (createFileIfNotExist), 打开输出流 (openOutputStream)。
     *
     * 对应 app 端:
     * ```
     * val fileDoc = FileDoc.fromDir(path)
     * fileDoc.find(filename)?.delete()
     * val bookDoc = fileDoc.createFileIfNotExist(filename)
     * val os = bookDoc.openOutputStream().getOrThrow()
     * ```
     *
     * @return 输出流 + 文件 uri (用于 WebDav 上传 / 异常回滚删除)
     */
    fun prepareExportFile(dirPath: String, filename: String): ExportFileHandle

    /** 删除导出文件 (异常回滚用, 对应 bookDoc.delete())。 */
    fun deleteExportUri(uri: String)

    /** 移除导出进度 (对应 exportProgress.remove(bookUrl))。 */
    fun removeExportProgress(bookUrl: String)

    /** 设置导出消息 (对应 exportMsg[bookUrl] = msg)。 */
    fun setExportMsg(bookUrl: String, msg: String)

    /** 移除导出消息 (对应 exportMsg.remove(bookUrl))。 */
    fun removeExportMsg(bookUrl: String)

    /**
     * 上传导出文件到 WebDav (对应 AppWebDav.exportWebDav(uri, filename))。
     *
     * @param uri 文件 uri (由 [prepareExportFile] 返回的 [ExportFileHandle.uri])
     * @param filename 文件名
     */
    suspend fun exportToWebDav(uri: String, filename: String)
}

/**
 * 导出文件句柄 (输出流 + uri)。
 *
 * 由 [ExportBookDeps.prepareExportFile] 返回, [ExportBookShared] 用
 * [outputStream] 写入内容, 用 [uri] 上传 WebDav / 异常回滚删除。
 */
data class ExportFileHandle(val outputStream: OutputStream, val uri: String)
