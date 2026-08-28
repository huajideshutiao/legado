package io.legado.app.service

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelpLogic
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.FileResourceProvider
import io.legado.app.lib.epublib.domain.LazyResource
import io.legado.app.lib.epublib.domain.LazyResourceProvider
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.TOCReference
import io.legado.app.lib.epublib.epub.EpubWriter
import io.legado.app.lib.epublib.epub.EpubWriterProcessor
import io.legado.app.lib.epublib.util.ResourceUtil
import io.legado.app.model.ExportBookUtils
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.cnCompare
import io.legado.app.utils.mapAsync
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.regex.Pattern
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/**
 * EPUB/CBZ 导出业务下沉 (shared jvmAndAndroidMain)。
 *
 * 原 app 端 [io.legado.app.service.ExportBookService] 的 exportEpub / exportCbz /
 * CustomExporter 主体 (setEpubContent / fixPic / setAssets / setCover / extractCbzPages),
 * 依赖 (epublib / ExportBookUtils / FileBook / BookImageStorage / BookStorage /
 * ContentProcessor) 均已下沉, 逐行对照 app 原版搬运。
 *
 * 平台段 (FileDoc / Coil3 封面 / assets 模板 / ContentProcessor 单例) 经 [ExportBookDeps] 注入:
 * - 文件输出: [ExportBookDeps.prepareExportFile] (find+delete+create+openOutputStream)
 * - 封面: [ExportBookDeps.getCoverImageBytes]
 * - 内置模板: [ExportBookDeps.getBuiltinAsset] (app 端 assets/epub/, 其他端自备)
 * - 外部模板: [ExportBookDeps.listTemplateFiles] (app 端 FileDoc 遍历)
 * - 标题替换规则: [ExportBookDeps.getTitleReplaceRules]
 *
 * 无共享可变状态: 分割导出的 scope/size 全程参数传递 (导出逐本串行, 单例安全)。
 *
 * @param shared 复用 [ExportBookShared] 的 setEpubMetadata
 */
class ExportBookEpubShared(
    private val deps: ExportBookDeps,
    private val shared: ExportBookShared
) {

    /**
     * 导出 Epub (对应 app 端 `ExportBookService.exportEpub`)。
     */
    suspend fun exportEpub(path: String, book: Book) {
        deps.removeExportMsg(book.bookUrl)
        deps.postExportEvent(book.bookUrl)
        val filename = deps.getExportFileName(book, "epub")

        val epubBook = EpubBook()
        epubBook.version = "2.0"
        //set metadata
        shared.setEpubMetadata(book, epubBook)
        //set cover
        setCover(book, epubBook)
        //set css
        val contentModel = setAssets(path, book, epubBook)
        //设置正文
        setEpubContent(contentModel, book, epubBook)

        val handle = deps.prepareExportFile(path, filename)
        try {
            handle.outputStream.buffered().use { bookOs ->
                EpubWriter().write(epubBook, bookOs)
            }
            if (deps.exportToWebDav) {
                deps.exportToWebDav(handle.uri, filename)
            }
        } catch (e: Throwable) {
            runCatching { deps.deleteExportUri(handle.uri) }
            throw e
        }
    }

    /**
     * 导出图片书为 cbz (对应 app 端 `ExportBookService.exportCbz`)。
     */
    suspend fun exportCbz(path: String, book: Book, chapters: List<BookChapter>) {
        deps.removeExportMsg(book.bookUrl)
        deps.postExportEvent(book.bookUrl)
        if (chapters.isEmpty()) {
            throw NoStackTraceException("书籍<${book.name}>未找到章节信息")
        }
        val filename = deps.getExportFileName(book, "cbz")
        val handle = deps.prepareExportFile(path, filename)
        try {
            var totalWritten = 0
            handle.outputStream.buffered().use { os ->
                ZipOutputStream(os).use { zos ->
                    // 图片本身已经是压缩格式，再 deflate 几乎无收益且耗 CPU
                    zos.setLevel(Deflater.NO_COMPRESSION)
                    flow {
                        chapters.forEachIndexed { index, chapter -> emit(index to chapter) }
                    }.mapAsync(AppConst.MAX_THREAD) { (index, chapter) ->
                        index to extractCbzPages(book, chapter, index)
                    }.collect { (index, pages) ->
                        currentCoroutineContext().ensureActive()
                        deps.postExportEvent(book.bookUrl)
                        deps.setExportProgress(book.bookUrl, index)
                        pages.forEach { (entryName, openStream) ->
                            zos.putNextEntry(ZipEntry(entryName))
                            openStream()?.use { it.copyTo(zos) } ?: return@forEach
                            zos.closeEntry()
                            totalWritten++
                        }
                    }
                    zos.putNextEntry(ZipEntry("ComicInfo.xml"))
                    zos.write(
                        ExportBookUtils.buildComicInfo(book, totalWritten)
                            .toByteArray(Charsets.UTF_8)
                    )
                    zos.closeEntry()
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

    /**
     * 分割导出 epub (对应 app 端 `CustomExporter.export`)。
     *
     * @param scopeStr 导出范围表达式 (章节索引集合, 经 [ExportBookUtils.parseScope])
     * @param size 单个 epub 文件包含最大章节数
     */
    suspend fun exportCustom(path: String, book: Book, scopeStr: String, size: Int) {
        val scope = ExportBookUtils.parseScope(scopeStr)
            .filter { it < AppDbProviders.get().bookChapterDao.getChapterCount(book.bookUrl) }
            .toHashSet()
        deps.setExportProgress(book.bookUrl, 0)
        deps.removeExportMsg(book.bookUrl)
        deps.postExportEvent(book.bookUrl)
        val currentTimeMillis = System.currentTimeMillis()

        val (contentModel, epubList) = createEpubs(book, path, scope, size)
        var progressBar = 0.0
        epubList.forEachIndexed { index, ep ->
            val (filename, epubBook) = ep
            //设置正文
            setEpubContent(
                contentModel,
                book,
                epubBook,
                scope,
                size,
                index
            ) { _, _ ->
                // 将章节写入内存时更新进度条
                deps.postExportEvent(book.bookUrl)
                progressBar += book.totalChapterNum.toDouble() / scope.size / 2
                deps.setExportProgress(book.bookUrl, progressBar.toInt())
            }
            save2Drive(filename, epubBook, path) { total, _ ->
                //写入硬盘时更新进度条
                progressBar += book.totalChapterNum.toDouble() / epubList.size / total / 2
                deps.postExportEvent(book.bookUrl)
                deps.setExportProgress(book.bookUrl, progressBar.toInt())
            }
        }

        val elapsed = System.currentTimeMillis() - currentTimeMillis
        AppLog.put("分割导出书籍 ${book.name} 一共耗时 $elapsed")
    }

    /**
     * 设置epub正文 (对应 app 端 `ExportBookService.setEpubContent`)。
     */
    private suspend fun setEpubContent(
        contentModel: String,
        book: Book,
        epubBook: EpubBook
    ) = coroutineScope {
        //正文
        val useReplace = deps.exportUseReplace && book.getUseReplaceRule()
        val replaceRules = deps.getTitleReplaceRules(book)
        var parentSection: TOCReference? = null
        flow {
            AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsyncIndexed(AppConst.MAX_THREAD) { index, chapter ->
            val content = BookHelpProviders.get().getContent(book, chapter)
            val (contentFix, resources) = fixPic(
                book,
                content ?: if (chapter.isVolume) "" else "null",
                chapter
            )
            // 不导出vip标识
            chapter.isVip = false
            val content1 = deps.processContent(
                book,
                chapter,
                contentFix,
                includeTitle = false,
                useReplace = useReplace,
                chineseConvert = false,
                reSegment = false
            ).toString()
            val title = chapter.run {
                // 不导出vip标识
                isVip = false
                getDisplayTitle(
                    replaceRules,
                    useReplace = useReplace
                )
            }
            val chapterResource = ResourceUtil.createChapterResource(
                title.replace("🔒", ""),
                content1,
                contentModel,
                "Text/chapter_${index}.html"
            )
            ExportChapter(title, chapterResource, resources, chapter)
        }.collectIndexed { index, exportChapter ->
            deps.postExportEvent(book.bookUrl)
            deps.setExportProgress(book.bookUrl, index)
            val (title, chapterResource, resources, chapter) = exportChapter
            epubBook.resources.addAll(resources)
            if (chapter.isVolume) {
                parentSection = epubBook.addSection(title, chapterResource)
            } else {
                val parent = parentSection
                if (parent == null) {
                    epubBook.addSection(title, chapterResource)
                } else {
                    epubBook.addSection(parent, title, chapterResource)
                }
            }
        }
    }

    /**
     * 设置epub正文 (分割导出版, 对应 app 端 `CustomExporter.setEpubContent`)。
     *
     * @param epubBookIndex 分割后的epub序号
     * @param updateProgress 章节写入内存时的进度回调
     */
    private suspend fun setEpubContent(
        contentModel: String,
        book: Book,
        epubBook: EpubBook,
        scope: HashSet<Int>,
        size: Int,
        epubBookIndex: Int,
        updateProgress: (chapterList: MutableList<BookChapter>, index: Int) -> Unit
    ) {
        //正文
        val useReplace = deps.exportUseReplace && book.getUseReplaceRule()
        val replaceRules = deps.getTitleReplaceRules(book)
        var chapterList: MutableList<BookChapter> = ArrayList()
        AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
            .forEachIndexed { index, chapter ->
                if (scope.contains(index)) {
                    chapterList.add(chapter)
                }
                if (scope.size == chapterList.size) {
                    return@forEachIndexed
                }
            }
        if (chapterList.isEmpty()) {
            throw RuntimeException("书籍<${book.name}>(${epubBookIndex + 1})未找到章节信息")
        }
        chapterList = chapterList.subList(
            epubBookIndex * size,
            min(scope.size, (epubBookIndex + 1) * size)
        )
        chapterList.forEachIndexed { index, chapter ->
            currentCoroutineContext().ensureActive()
            updateProgress(chapterList, index)
            BookHelpProviders.get().getContent(book, chapter).let { content ->
                val (contentFix, resources) = fixPic(
                    book,
                    content ?: if (chapter.isVolume) "" else "null",
                    chapter
                )
                epubBook.resources.addAll(resources)
                val content1 = deps.processContent(
                    book,
                    chapter,
                    contentFix,
                    includeTitle = false,
                    useReplace = useReplace,
                    chineseConvert = false,
                    reSegment = false
                ).toString()
                val title = chapter.run {
                    // 不导出vip标识
                    isVip = false
                    getDisplayTitle(
                        replaceRules,
                        useReplace = useReplace
                    )
                }
                epubBook.addSection(
                    title,
                    ResourceUtil.createChapterResource(
                        title.replace("🔒", ""),
                        content1,
                        contentModel,
                        "Text/chapter_${index}.html"
                    )
                )
            }
        }
    }

    /**
     * 创建多个epub 对象 (对应 app 端 `CustomExporter.createEpubs`)。
     *
     * @return <内容模板字符串, <epub文件名, epub对象>>
     */
    private fun createEpubs(
        book: Book,
        dirPath: String,
        scope: HashSet<Int>,
        size: Int
    ): Pair<String, List<Pair<String, EpubBook>>> {
        val paresNumOfEpub = ExportBookUtils.paresNumOfEpub(scope.size, size)
        val result: MutableList<Pair<String, EpubBook>> = ArrayList(paresNumOfEpub)
        var contentModel = ""
        for (i in 1..paresNumOfEpub) {
            val filename = deps.getExportFileName(book, "epub", i)
            val epubBook = EpubBook()
            epubBook.version = "2.0"
            //set metadata
            shared.setEpubMetadata(book, epubBook)
            //set cover
            setCover(book, epubBook)
            //set css
            contentModel = setAssets(dirPath, book, epubBook)
            // add epubBook
            result.add(Pair(filename, epubBook))
        }
        return Pair(contentModel, result)
    }

    /**
     * 保存文件到 设备 (对应 app 端 `CustomExporter.save2Drive`)。
     */
    private suspend fun save2Drive(
        filename: String,
        epubBook: EpubBook,
        dirPath: String,
        callback: (total: Int, progress: Int) -> Unit
    ) {
        val handle = deps.prepareExportFile(dirPath, filename)
        try {
            handle.outputStream.buffered().use { bookOs ->
                EpubWriter()
                    .setCallback(object : EpubWriterProcessor.Callback {
                        override fun onProgressing(total: Int, progress: Int) {
                            callback(total, progress)
                        }
                    })
                    .write(epubBook, bookOs)
            }
            if (deps.exportToWebDav) {
                deps.exportToWebDav(handle.uri, filename)
            }
        } catch (e: Throwable) {
            runCatching { deps.deleteExportUri(handle.uri) }
            throw e
        }
    }

    private fun setAssets(dirPath: String, book: Book, epubBook: EpubBook): String {
        val customFiles = deps.listTemplateFiles(dirPath)
        return if (customFiles == null) {//使用内置模板
            setAssetsBuiltin(book, epubBook)
        } else {//外部模板
            setAssetsExternal(customFiles, book, epubBook)
        }
    }

    private fun setAssetsExternal(
        files: List<TemplateFileInfo>,
        book: Book,
        epubBook: EpubBook
    ): String {
        var contentModel = ""
        files.forEach { folder ->
            if (folder.isDir && folder.name == "Text") {
                folder.children.sortedWith { o1, o2 ->
                    o1.name.cnCompare(o2.name)
                }.forEach loop@{ file ->
                    when {
                        //正文模板
                        file.name.equals("chapter.html", true)
                            || file.name.equals("chapter.xhtml", true) -> {
                            contentModel = file.readText()
                        }
                        //封面等其他模板
                        file.name.endsWith("html", true) -> {
                            epubBook.addSection(
                                FileUtilsBase.getNameExcludeExtension(file.name),
                                ResourceUtil.createPublicResource(
                                    book.name,
                                    book.getRealAuthor(),
                                    book.getDisplayIntro(),
                                    book.kind,
                                    book.wordCount,
                                    file.readText(),
                                    "Text/${file.name}"
                                )
                            )
                        }
                        //其他格式文件当做资源文件
                        else -> {
                            epubBook.resources.add(
                                Resource(
                                    file.readBytes(),
                                    "Text/${file.name}"
                                )
                            )
                        }
                    }
                }
            } else if (folder.isDir) {
                //资源文件
                folder.children.forEach { child ->
                    epubBook.resources.add(
                        Resource(
                            child.readBytes(),
                            "${folder.name}/${child.name}"
                        )
                    )
                }
            } else {//Asset下面的资源文件
                epubBook.resources.add(
                    Resource(
                        folder.readBytes(),
                        folder.name
                    )
                )
            }
        }
        return contentModel
    }

    private fun setAssetsBuiltin(book: Book, epubBook: EpubBook): String {
        epubBook.resources.add(
            Resource(
                deps.getBuiltinAsset("epub/fonts.css"),
                "Styles/fonts.css"
            )
        )
        epubBook.resources.add(
            Resource(
                deps.getBuiltinAsset("epub/main.css"),
                "Styles/main.css"
            )
        )
        epubBook.resources.add(
            Resource(
                deps.getBuiltinAsset("epub/logo.png"),
                "Images/logo.png"
            )
        )
        epubBook.addSection(
            deps.strImgCover(),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(deps.getBuiltinAsset("epub/cover.html")),
                "Text/cover.html"
            )
        )
        epubBook.addSection(
            deps.strBookIntro(),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(deps.getBuiltinAsset("epub/intro.html")),
                "Text/intro.html"
            )
        )
        return String(deps.getBuiltinAsset("epub/chapter.html"))
    }

    private fun setCover(book: Book, epubBook: EpubBook) {
        kotlin.runCatching {
            // deps.getCoverImageBytes 为 suspend (Coil3 execute), 此处非 suspend 上下文用 runBlocking
            // (与原 app 版 setCover 内 runBlocking 调 loader.execute 行为一致)
            val bytes = runBlocking { deps.getCoverImageBytes(book) } ?: return
            val provider = object : LazyResourceProvider {
                override fun getResourceStream(href: String?) = ByteArrayInputStream(bytes)
            }
            epubBook.coverImage = LazyResource(provider, "Images/cover.jpg")
        }.onFailure {
            AppLog.put("获取书籍封面出错\n${it.localizedMessage}", it)
        }
    }

    /**
     * 修正正文图片引用 (对应 app 端 `ExportBookService.fixPic`)。
     *
     * 图片缓存路径经 [BookImageStorageProviders] 获取 (app 端 BookHelp.getImage 桥),
     * 无缓存则跳过替换 (与原版行为一致)。
     */
    private fun fixPic(
        book: Book,
        content: String,
        chapter: BookChapter
    ): Pair<String, ArrayList<Resource>> {
        val data = StringBuilder("")
        val resources = arrayListOf<Resource>()
        content.split("\n").forEach { text ->
            var text1 = text
            // 本地缓存内容经过 formatKeepImg 处理，img 标签格式统一为 <img src="...">
            // 直接用简单的正则提取 src 即可
            val imgPattern = Pattern.compile("<img src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
            val matcher = imgPattern.matcher(text)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                val originalHref =
                    "${MD5Utils.md5Encode16(src)}.${BookHelpLogic.getImageSuffix(src)}"
                val href =
                    "Images/${MD5Utils.md5Encode16(src)}.${BookHelpLogic.getImageSuffix(src)}"
                val vPath = BookImageStorageProviders.get().getImagePath(book, chapter, src)

                AppLog.putDebug("导出图片检查: ${chapter.title}\n  URL: $src\n  缓存路径: $vPath\n  是否存在: ${vPath != null}")

                if (vPath != null) {
                    val file = File(vPath)
                    val parent = file.parentFile ?: file
                    val fp = FileResourceProvider(parent)
                    val img = LazyResource(fp, href, originalHref)
                    resources.add(img)
                    text1 = text1.replace(src, "../${href}")
                } else {
                    AppLog.put("导出书籍<${book.name}> ${chapter.title} 图片缓存不存在: $src")
                }
            }
            data.append(text1).append("\n")
        }
        return data.toString() to resources
    }

    /**
     * 提取章节内图片页 (对应 app 端 `ExportBookService.extractCbzPages`)。
     */
    private fun extractCbzPages(
        book: Book,
        chapter: BookChapter,
        chapterIndex: Int
    ): List<Pair<String, () -> InputStream?>> {
        val content = BookHelpProviders.get().getContent(book, chapter) ?: return emptyList()
        val chapterDir = "%04d-%s".format(
            chapterIndex + 1,
            chapter.title.normalizeFileName().take(50)
        )
        val pages = ArrayList<Pair<String, () -> InputStream?>>()
        var pageIndex = 0
        // 本地缓存内容经过 formatKeepImg 处理，img 标签格式统一为 <img src="...">
        val imgPattern = Pattern.compile("<img src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        val matcher = imgPattern.matcher(content)
        while (matcher.find()) {
            val src = matcher.group(1) ?: continue
            pageIndex++
            val entryName = "$chapterDir/%04d.%s"
                .format(pageIndex, BookHelpLogic.getImageSuffix(src))
            pages.add(entryName to {
                val vPath = BookImageStorageProviders.get().getImagePath(book, chapter, src)
                if (vPath != null) File(vPath).inputStream() else FileBook.getImage(book, src)
            })
        }
        return pages
    }

    data class ExportChapter(
        val title: String,
        val chapterResource: Resource,
        val resources: ArrayList<Resource>,
        val chapter: BookChapter
    )

    /**
     * 外部模板目录条目 (对应 app 端 FileDoc.list 的条目)。
     *
     * [children] 仅一层 (app 端模板结构: Asset/Text/xx.html 或 Asset/资源目录/文件,
     * 不递归更深层级)。
     */
    data class TemplateFileInfo(
        val name: String,
        val isDir: Boolean,
        val children: List<TemplateFileInfo>,
        val readText: () -> String,
        val readBytes: () -> ByteArray
    )
}
