package io.legado.app.model.fileBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.MediaType
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.TOCReference
import io.legado.app.lib.epublib.epub.EpubReader
import io.legado.app.lib.epublib.epub.ResourcesLoader
import io.legado.app.lib.epublib.util.zip.AndroidZipFile
import io.legado.app.lib.epublib.util.zip.ZipFileWrapper
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.encodeURI
import io.legado.app.utils.isXml
import io.legado.app.utils.printOnDebug
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset

class EpubFile(var book: Book) {

    companion object : BaseFileBook {
        private var eFile: EpubFile? = null

        @Synchronized
        private fun getEFile(book: Book): EpubFile {
            if (eFile == null || eFile?.book?.bookUrl != book.bookUrl) {
                eFile = EpubFile(book)
                //对于Epub文件默认不启用替换
                //io.legado.app.data.entities.Book getUseReplaceRule
                return eFile!!
            }
            eFile?.book = book
            return eFile!!
        }

        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getEFile(book).getChapterList()
        }

        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getEFile(book).getContent(chapter)
        }

        override fun getImage(
            book: Book,
            href: String
        ): InputStream? {
            return getEFile(book).getImage(href)
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            return getEFile(book).upBookInfo()
        }

        override fun clear() {
            eFile = null
        }
    }

    private var mCharset: Charset = Charset.defaultCharset()

    @Volatile
    private var fileDescriptor: ParcelFileDescriptor? = null

    @Volatile
    private var remoteZipWrapper: RemoteZipWrapper? = null

    @Volatile
    private var epubBook: EpubBook? = null
        get() = field ?: synchronized(this) {
            field ?: readEpub().also { field = it }
        }

    @Volatile
    private var epubBookContents: List<Resource>? = null
        get() = field ?: synchronized(this) {
            field ?: epubBook?.contents?.filterNotNull()?.also { field = it }
        }

    init {
        upBookCover(true)
    }

    /**
     * 重写epub文件解析代码，直接读出压缩包文件生成Resources给epublib
     * 对于远程EPUB文件，使用RemoteZipWrapper实现动态加载（不全量下载）
     */
    private fun readEpub(): EpubBook? {
        return runCatching {
            if (book.bookUrl.startsWith(BookType.webDavTag) || book.bookUrl.startsWith("http")) {
                readEpubRemote()
            } else {
                BookHelp.getBookPFD(book)?.let {
                    fileDescriptor = it
                    val zipFile = AndroidZipFile(it, book.originName)
                    EpubReader().readEpubLazy(zipFile, "utf-8")
                }
            }
        }.onFailure {
            AppLog.put("读取Epub文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
        }.getOrNull()
    }

    /**
     * 使用RemoteZipWrapper动态加载远程EPUB
     * 类似CBZ的处理方式：仅下载中央目录元数据，文件内容按需获取
     */
    private fun readEpubRemote(): EpubBook? {
        val url = book.getRemoteUrl() ?: book.bookUrl
        val webDav = runCatching {
            WebDav.fromPath(url)
        }.getOrElse {
            AppWebDav.authorization?.let { auth ->
                WebDav(url, auth)
            } ?: return null
        }

        var size = 0L
        var eocd = 0L
        var central = 0L
        var entryCount = 0
        // 从variable中恢复缓存的元数据（如果存在）
        book.variable?.takeIf { it.startsWith("epub:") }?.substring(5)?.split(",")
            ?.let { p ->
                eocd = p.getOrNull(0)?.toLongOrNull() ?: 0L
                central = p.getOrNull(1)?.toLongOrNull() ?: 0L
                size = p.getOrNull(2)?.toLongOrNull() ?: 0L
                entryCount = p.getOrNull(3)?.toIntOrNull() ?: 0
            }

        val wrapper = RemoteZipWrapper(webDav, book.originName, size, eocd, central, entryCount)
        remoteZipWrapper = wrapper
        val zipFileWrapper = ZipFileWrapper(wrapper)
        val lazyLoadedTypes: MutableList<MediaType?> =
            io.legado.app.lib.epublib.domain.MediaTypes.mediaTypes.toMutableList()
        val resources = ResourcesLoader.loadResources(zipFileWrapper, "utf-8", lazyLoadedTypes)
        return EpubReader().readEpub(resources)
    }

    private fun getContent(chapter: BookChapter): String? {
        /*获取当前章节文本*/
        val contents = epubBookContents ?: return null
        val nextChapterFirstResourceHref = chapter.getVariable("nextUrl").substringBeforeLast("#")
        val currentChapterFirstResourceHref = chapter.url.substringBeforeLast("#")
        val isLastChapter = nextChapterFirstResourceHref.isBlank()
        val startFragmentId = chapter.startFragmentId
        val endFragmentId = chapter.endFragmentId
        val elements = Elements()
        var findChapterFirstSource = false
        val includeNextChapterResource = !endFragmentId.isNullOrBlank()
        /*一些书籍依靠href索引的resource会包含多个章节，需要依靠fragmentId来截取到当前章节的内容*/
        /*注:这里较大增加了内容加载的时间，所以首次获取内容后可存储到本地cache，减少重复加载*/
        for (res in contents) {
            if (!findChapterFirstSource) {
                if (currentChapterFirstResourceHref != res.getHref()) continue
                findChapterFirstSource = true
                // 第一个xhtml文件
                elements.add(
                    getBody(res, startFragmentId, endFragmentId)
                )
                // 不是最后章节 且 已经遍历到下一章节的内容时停止
                if (!isLastChapter && res.getHref() == nextChapterFirstResourceHref) break
                continue
            }
            if (nextChapterFirstResourceHref != res.getHref()) {
                // 其余部分
                elements.add(getBody(res, null, null))
            } else {
                // 下一章节的第一个xhtml
                if (includeNextChapterResource) {
                    //有Fragment 则添加到上一章节
                    elements.add(getBody(res, null, endFragmentId))
                }
                break
            }
        }
        //title标签中的内容不需要显示在正文中，去除
        elements.select("title").remove()
        elements.select("[style*=display:none]").remove()
        elements.select("img").forEach {
            if (it.attributesSize() <= 1) {
                return@forEach
            }
            val src = it.attr("src")
            it.clearAttributes()
            it.attr("src", src)
        }
        val tag = Book.rubyTag
        if (book.config.delTag and tag == tag) {
            elements.select("rp, rt").remove()
        }
        val html = elements.outerHtml()
        return HtmlFormatter.formatKeepImg(html)
    }

    private fun getBody(res: Resource, startFragmentId: String?, endFragmentId: String?): Element {
        // Jsoup可能会修复不规范的xhtml文件 解析处理后再获取
        var bodyElement = Jsoup.parse(String(res.data ?: ByteArray(0), mCharset)).body()
        bodyElement.children().run {
            select("script").remove()
            select("style").remove()
        }
        // 获取body对应的文本
        var bodyString = bodyElement.outerHtml()
        val originBodyString = bodyString
        /**
         * 某些xhtml文件 章节标题和内容不在一个节点或者不是兄弟节点
         * <div>
         *    <a class="mulu1>目录1</a>
         * </div>
         * <p>....</p>
         * <div>
         *    <a class="mulu2>目录2</a>
         * </div>
         * <p>....</p>
         * 先找到FragmentId对应的Element 然后直接截取之间的html
         */
        if (!startFragmentId.isNullOrBlank()) {
            bodyElement.getElementById(startFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = tagStart + bodyString.substringAfter(tagStart)
            }
        }
        if (!endFragmentId.isNullOrBlank() && endFragmentId != startFragmentId) {
            bodyElement.getElementById(endFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = bodyString.substringBefore(tagStart)
            }
        }
        //截取过再重新解析
        if (bodyString != originBodyString) {
            bodyElement = Jsoup.parse(bodyString).body()
        }
        /*选择去除正文中的H标签，部分书籍标题与阅读标题重复待优化*/
        val tag = Book.hTag
        if (book.config.delTag and tag == tag) {
            bodyElement.run {
                select("h1, h2, h3, h4, h5, h6").remove()
                //getElementsMatchingOwnText(chapter.title)?.remove()
            }
        }
        bodyElement.select("image").forEach {
            it.tagName("img", Parser.NamespaceHtml)
            it.attr("src", it.attr("xlink:href"))
        }
        bodyElement.select("img").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("data:", ignoreCase = true)) {
                it.attr("src", src)
            } else {
                val encodedSrc = src.encodeURI()
                val href = res.getHref().encodeURI()
                val resolvedHref =
                    URLDecoder.decode(URI(href).resolve(encodedSrc).toString(), "UTF-8")
                it.attr("src", resolvedHref)
            }
        }
        return bodyElement
    }

    private fun getImage(href: String): InputStream? {
        val decodedHref = URLDecoder.decode(href, "UTF-8")

        // 精确匹配原始href
        epubBook?.resources?.getByHref(href)?.inputStream?.let {
            return it
        }

        // 尝试解码后的href
        if (decodedHref != href) {
            epubBook?.resources?.getByHref(decodedHref)?.inputStream?.let {
                return it
            }
        }

        // 兜底：按文件名模糊匹配，处理路径前缀不匹配的情况
        val fileName = decodedHref.substringAfterLast("/")
        if (fileName.isNotEmpty()) {
            epubBook?.resources?.all?.find { resource ->
                val resourceHref = resource.getHref()
                resourceHref.equals(fileName, ignoreCase = true)
                    || resourceHref.endsWith("/$fileName", ignoreCase = true)
            }?.inputStream?.let {
                return it
            }
        }

        return null
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            epubBook?.let {
                if (book.coverUrl.isNullOrEmpty()) {
                    book.coverUrl = FileBook.getCoverPath(book.bookUrl)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return
                }
                /*部分书籍DRM处理后，封面获取异常，待优化*/
                it.coverImage?.inputStream?.use { input ->
                    val cover = BitmapFactory.decodeStream(input)
                    val out = FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!))
                    cover.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()
                } ?: AppLog.putDebug("Epub: 封面获取为空. path: ${book.bookUrl}")
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        if (epubBook == null) {
            eFile = null
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            // 保存远程EPUB的元数据，供后续动态加载使用（类似CBZ的variable存储）
            remoteZipWrapper?.apply {
                book.variable = "epub:$eocdOffset,$centralOffset,$fileSize,$entryCount"
            }
            val metadata = epubBook!!.metadata
            book.name = metadata.firstTitle ?: ""
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".epub", "")
            }

            if (metadata.authors.isNotEmpty()) {
                val author =
                    metadata.authors[0].toString().replace("^, |, $".toRegex(), "")
                book.author = author
            }
            if (metadata.descriptions.isNotEmpty()) {
                val desc = metadata.descriptions[0]
                book.intro = if (desc.isXml()) {
                    Jsoup.parse(metadata.descriptions[0] ?: "").text()
                } else {
                    desc
                }
            }
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        epubBook?.let { eBook ->
            val refs = eBook.tableOfContents.tocReferences
            if (refs.isNullOrEmpty()) {
                AppLog.putDebug("Epub: NCX file parse error, check the file: ${book.bookUrl}")
                val spineReferences = eBook.spine.spineReferences
                var i = 0
                val size = spineReferences?.size ?: 0
                while (i < size) {
                    val resource = spineReferences?.get(i)?.resource
                    var title = resource?.title
                    if (TextUtils.isEmpty(title)) {
                        try {
                            val doc =
                                Jsoup.parse(String(resource?.data ?: ByteArray(0), mCharset))
                            val elements = doc.getElementsByTag("title")
                            if (elements.isNotEmpty()) {
                                title = elements[0].text()
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    val chapter = BookChapter()
                    chapter.index = i
                    chapter.bookUrl = book.bookUrl
                    chapter.url = resource?.getHref() ?: ""
                    if (i == 0 && title.isNullOrEmpty()) {
                        chapter.title = "封面"
                    } else {
                        chapter.title = title ?: ""
                    }
                    chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
                    chapterList.add(chapter)
                    i++
                }
            } else {
                parseFirstPage(chapterList, refs)
                parseMenu(chapterList, refs, 0)
                for (i in chapterList.indices) {
                    chapterList[i].index = i
                }
            }
        }
        return chapterList
    }

    /*获取书籍起始页内容。部分书籍第一章之前存在封面，引言，扉页等内容*/
    /*tile获取不同书籍风格杂乱，格式化处理待优化*/
    private var durIndex = 0
    private fun parseFirstPage(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?
    ) {
        val contents = epubBook?.contents
        if (epubBook == null || contents == null || refs == null) return
        val firstRef = refs.firstOrNull { it.resource != null } ?: return
        var i = 0
        durIndex = 0
        while (i < contents.size) {
            val content = contents[i] ?: run { i++; continue }
            if (!content.mediaType.toString().contains("htm")) {
                i++
                continue
            }
            /**
             * 检索到第一章href停止
             * completeHref可能有fragment(#id) 必须去除
             * fix https://github.com/gedoor/legado/issues/1932
             */
            if (firstRef.completeHref?.substringBeforeLast("#") == content.getHref()) break
            val chapter = BookChapter()
            var title = content.title
            if (TextUtils.isEmpty(title)) {
                val elements = Jsoup.parse(
                    String(
                        epubBook!!.resources.getByHref(content.getHref())?.data ?: ByteArray(0),
                        mCharset
                    )
                ).getElementsByTag("title")
                title =
                    if (elements.isNotEmpty() && elements[0].text().isNotBlank())
                        elements[0].text()
                    else
                        "--卷首--"
            }
            chapter.bookUrl = book.bookUrl
            chapter.title = title ?: ""
            chapter.url = content.getHref()
            chapter.startFragmentId =
                if (content.getHref().substringAfter("#") == content.getHref()) null
                else content.getHref().substringAfter("#")

            chapterList.lastOrNull()?.endFragmentId = chapter.startFragmentId
            chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
            chapterList.add(chapter)
            durIndex++
            i++
        }
    }

    private fun parseMenu(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?,
        level: Int
    ) {
        refs?.forEach { ref ->
            if (ref.resource != null) {
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title ?: ""
                chapter.url = ref.completeHref ?: ""
                chapter.startFragmentId = ref.fragmentId
                chapterList.lastOrNull()?.endFragmentId = chapter.startFragmentId
                chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
                chapterList.add(chapter)
                durIndex++
            }
            if (ref.children.isNotEmpty()) {
                chapterList.lastOrNull()?.isVolume = true
                parseMenu(chapterList, ref.children, level + 1)
            }
        }
    }


    protected fun finalize() {
        fileDescriptor?.close()
        remoteZipWrapper?.close()
    }

}
