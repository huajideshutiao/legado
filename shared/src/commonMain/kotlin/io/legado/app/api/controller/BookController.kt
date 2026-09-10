package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController.getImg
import io.legado.app.api.controller.ReadBookStateProviders.getOrNull
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensionsPlatform
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isSecurityException
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import legado.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.collections.getOrNull
import kotlin.concurrent.Volatile
import kotlin.text.getOrNull

/**
 * 书籍 Web 接口 (shared commonMain 下沉版)。
 *
 * 原 app 端实现 13 个方法, 其中 11 个纯业务方法下沉本文件, 2 个图片方法
 * (getCover/getImg) 依赖 android.graphics.Bitmap + Glide/ImageProvider (Android-only),
 * 通过 [ImageControllerProvider] 接口注入, 由 app 端注册 actual 实现, shared 端仅做委托。
 *
 * 下沉模式参考 [BookSourceController]:
 * - `appDb.bookDao` / `appDb.bookGroupDao` / `appDb.bookChapterDao` / `appDb.bookSourceDao`
 *   → `AppDbProviders.get().bookDao` 等 (DAO 已下沉 commonMain)
 * - `AppConfig.bookshelfSort` → `AppConfigProviders.get().bookshelfSort`
 * - `BookHelp.getContent` → `BookHelpProviders.get().getContent`
 * - `ContentProcessor.get(name, origin).getContent(..., includeTitle = false)`
 *   → `ContentProcessorProviders.get().getContent(book, chapter, content, includeTitle, useReplace)`
 *   (ContentProcessorAccessor 新增 includeTitle 重载, 由各平台 actual 透传)
 * - `AppWebDav.uploadBookProgress` → `AppWebDavShared.uploadBookProgress`
 * - `FileBook.getChapterList` / `FileBook.importLocalFile` → 直接调用 (object FileBook 已下沉)
 * - `FileBook.saveBookFile(File(fileData).inputStream(), fileName)`
 *   → `FileBookProviders.get().saveBookFileFromPath(fileData, fileName)`
 *   (FileBookAccessor 新增 saveBookFileFromPath 重载, commonMain 无法直接 File.inputStream())
 * - `WebBook.getBookInfoAwait` / `getChapterListAwait` / `getContentAwait` → 直接调用 (已下沉)
 * - `CacheManager.put/delete/get` → 直接调用 (已下沉)
 * - `String.cnCompare` → 直接调用 (已下沉 commonMain, expect/actual 分派 ICU)
 * - `book.save()` / `book.delete()` 扩展 (app 端 BookExtensions.kt, 依赖 appDb/ReadBook)
 *   → 内联展开为 AppDbProviders.get().bookDao 操作 + ReadBookStateProvider (ReadBook 单例未下沉)
 * - `ReadBook.book` / `ReadBook.webBookProgress` → [ReadBookStateProvider] 注入
 *   (ReadBook 单例未下沉 commonMain, 通过 provider 桥接; desktop/iOS/鸿蒙未注册时跳过同步)
 * - `System.currentTimeMillis()` → `systemCurrentTimeMillis()` (expect/actual)
 *
 * 行为与原 app 端逐字等价, 仅多一层 provider 间接。消费方 import 不变。
 */
object BookController {

    /** 目录/书源切换串行化，防止同一 bookUrl 的并发请求交叉覆盖 Book 与 chapters。 */
    private val catalogMutationMutex = Mutex()

    /*
    * 分组号及名称
     */
    suspend fun groups(): ReturnData {
        val returnData = ReturnData()
        return returnData.setData(AppDbProviders.get().bookGroupDao.all())
    }

    /**
     * 通过group id获取书籍
     *
     * 空分组返回空数组而非 setErrorMsg: 「这个分组一本书都没有」不是错误, 报错会让 web 端
     * 弹红色 toast 并停在上一个分组的列表上 (原 app 端返回 "未找到", 前端只好去 includes 匹配文案)。
     */
    suspend fun getBooks(parameters: Map<String, List<String>>): ReturnData {
        val groupId = parameters["groupId"]?.firstOrNull()?.toLong()
        val books = if (groupId == null) {
            AppDbProviders.get().bookDao.all().filterNot { (it.type and BookType.notShelf) > 0 }
        } else {
            AppDbProviders.get().bookDao.flowByGroup(groupId).first()
        }
        val data = when (AppConfigProviders.get().bookshelfSort) {
            1 -> books.sortedByDescending { it.latestChapterTime }
            2 -> books.sortedWith { o1, o2 ->
                o1.name.cnCompare(o2.name)
            }

            3 -> books.sortedBy { it.order }
            else -> books.sortedByDescending { it.durChapterTime }
        }
        return ReturnData().setData(data)
    }

    /**
     * 获取封面
     *
     * 依赖 android.graphics.Bitmap + Glide (ImageLoader.loadBitmap), 通过
     * [ImageControllerProvider] 注入由 app 端 actual 实现, 本方法仅做参数解析与委托。
     *
     * 原版失败时回退默认封面并 setData 成功 (web 端封面加载失败也能显示默认图);
     * 默认封面位图下沉 shared composeResources, 经 [Res.readBytes] 取内置兜底封面字节。
     */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun getCover(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val coverPath = parameters["path"]?.firstOrNull()
        val bytes = ImageControllerProviders.get().getCover(coverPath)
        if (bytes != null) {
            returnData.setData(bytes)
        } else {
            // 原版 BookController.getCover 失败时回退默认封面 (对照 archive:76-95, 失败分支
            // 试 defaultCoverBitmap/内置图后 setData 成功); 这里取内置兜底封面字节 (同 app 端 BookCover)
            val defaultBytes = try {
                Res.readBytes("drawable/image_cover_default.jpg")
            } catch (e: Exception) {
                null
            }
            if (defaultBytes != null) {
                returnData.setData(defaultBytes)
            } else {
                returnData.setErrorMsg("getCover error")
            }
        }
        return returnData
    }

    /**
     * 获取正文图片
     *
     * 依赖 android.graphics.Bitmap + ImageProvider, 通过 [ImageControllerProvider]
     * 注入由 app 端 actual 实现 (内部缓存 book/bookSource 状态, 与原 app 端 getImg 一致)。
     */
    suspend fun getImg(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val bookUrl = parameters["url"]?.firstOrNull()
            ?: return returnData.setErrorMsg("bookUrl为空")
        val src = parameters["path"]?.firstOrNull()
            ?: return returnData.setErrorMsg("图片链接为空")
        val width = parameters["width"]?.firstOrNull()?.toInt() ?: 640
        // provider 接口无法区分"查无此书"与"取图失败", 这里按原版口径先校验一次;
        // 与原版一样只在 bookUrl 变化时查库, 稳态无额外查询。
        if (lastImgBookUrl != bookUrl) {
            AppDbProviders.get().bookDao.getBook(bookUrl)
                ?: return returnData.setErrorMsg("bookUrl不对")
            lastImgBookUrl = bookUrl
        }
        val bytes = ImageControllerProviders.get().getImg(bookUrl, src, width)
        return if (bytes != null) {
            returnData.setData(bytes)
        } else {
            returnData.setErrorMsg("getImg error")
        }
    }

    /** [getImg] 上次校验通过的 bookUrl (对齐原版 BookController 的 bookUrl 缓存, 避免每张图查库)。 */
    private var lastImgBookUrl: String = ""

    /**
     * 更新目录
     */
    suspend fun refreshToc(parameters: Map<String, List<String>>): ReturnData =
        catalogMutationMutex.withLock {
            val returnData = ReturnData()
            try {
                val bookUrl = parameters["url"]?.firstOrNull()
                    ?: return@withLock returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
                val bookDao = AppDbProviders.get().bookDao
                val oldBook = bookDao.getBook(bookUrl)
                val requestedOrigin =
                    parameters["origin"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                val source = when {
                    oldBook?.isLocal == true -> null
                    requestedOrigin != null -> AppDbProviders.get().bookSourceDao.getBookSource(
                        requestedOrigin
                    )

                    oldBook != null -> AppDbProviders.get().bookSourceDao.getBookSource(oldBook.origin)
                    else -> resolveSourceByUniqueLongestPrefix(bookUrl).getOrElse {
                        return@withLock returnData.setErrorMsg(it.message ?: "无法唯一确定书源")
                    }
                }
                if (oldBook?.isLocal != true && source == null) {
                    return@withLock returnData.setErrorMsg("未找到对应书源,请换源")
                }

                val sourceChanged =
                    oldBook != null && source != null && oldBook.origin != source.bookSourceUrl
                val book = if (oldBook == null) {
                    Book(
                        bookUrl = bookUrl,
                        origin = source!!.bookSourceUrl,
                        originName = parameters["originName"]?.firstOrNull()
                            ?: source.bookSourceName,
                        name = parameters["name"]?.firstOrNull().orEmpty(),
                        author = parameters["author"]?.firstOrNull().orEmpty(),
                        tocUrl = parameters["tocUrl"]?.firstOrNull().orEmpty(),
                        type = parameters["type"]?.firstOrNull()?.toIntOrNull() ?: BookType.text,
                        coverUrl = parameters["coverUrl"]?.firstOrNull(),
                        intro = parameters["intro"]?.firstOrNull(),
                        kind = parameters["kind"]?.firstOrNull(),
                        wordCount = parameters["wordCount"]?.firstOrNull(),
                        variable = parameters["variable"]?.firstOrNull(),
                    ).apply { addType(BookType.notShelf) }
                } else if (sourceChanged) {
                    val previous = oldBook
                    previous.copy(
                        origin = source.bookSourceUrl,
                        originName = parameters["originName"]?.firstOrNull()
                            ?: source.bookSourceName,
                        tocUrl = parameters["tocUrl"]?.firstOrNull().orEmpty(),
                        coverUrl = parameters["coverUrl"]?.firstOrNull() ?: previous.coverUrl,
                        intro = parameters["intro"]?.firstOrNull() ?: previous.intro,
                        kind = parameters["kind"]?.firstOrNull() ?: previous.kind,
                        wordCount = parameters["wordCount"]?.firstOrNull() ?: previous.wordCount,
                        variable = parameters["variable"]?.firstOrNull() ?: previous.variable,
                    )
                } else {
                    oldBook
                }

                if (source != null && book.tocUrl.isBlank()) {
                    WebBook.getBookInfoAwait(source, book)
                    // Web API 以请求 url 为稳定主键；书源详情规则不得在本次请求中悄然换主键。
                    book.bookUrl = bookUrl
                }
                val keepNotShelf = oldBook == null || (oldBook.type and BookType.notShelf) > 0
                // 禁止复用的 Loader 在网络返回后先行写入半套 Book/chapters；由本临界区统一发布。
                book.addType(BookType.notShelf)
                val toc = BookChapterLoader.fetchFromSource(book, source)
                if (!keepNotShelf) book.removeType(BookType.notShelf)
                if (sourceChanged) BookStorageProviders.get().delContent(oldBook)

                // 发布顺序为「删旧目录 → 更新 Book/origin → 插入新目录」；任何单步失败最多留下空目录，
                // 不会留下 B Book + A chapters。Web 正文/目录读取由同一锁隔离发布窗口。
                val chapterDao = AppDbProviders.get().bookChapterDao
                chapterDao.delByBook(book.bookUrl)
                if (bookDao.has(book.bookUrl)) bookDao.update(book) else bookDao.insert(book)
                if (toc.isNotEmpty()) chapterDao.insert(*toc.toTypedArray())
                return@withLock returnData.setData(toc)
            } catch (e: Exception) {
                return@withLock returnData.setErrorMsg(e.message ?: "refresh toc error")
            }
        }

    private suspend fun resolveSourceByUniqueLongestPrefix(bookUrl: String): Result<BookSource> {
        val matches = AppDbProviders.get().bookSourceDao.all()
            .filter { it.bookSourceUrl.isNotEmpty() && bookUrl.startsWith(it.bookSourceUrl) }
        if (matches.isEmpty()) return Result.failure(IllegalStateException("无法根据书籍地址确定书源，请传 origin"))
        val longest = matches.maxOf { it.bookSourceUrl.length }
        val best = matches.filter { it.bookSourceUrl.length == longest }
        return if (best.size == 1) Result.success(best.single())
        else Result.failure(IllegalStateException("书籍地址匹配到多个同长度书源，请明确传 origin"))
    }

    /**
     * 获取目录
     */
    suspend fun getChapterList(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        val requestedOrigin = parameters["origin"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        val cached = catalogMutationMutex.withLock {
            val book = AppDbProviders.get().bookDao.getBook(bookUrl)
            if (book == null || (requestedOrigin != null && book.origin != requestedOrigin)) {
                null
            } else {
                AppDbProviders.get().bookChapterDao.getChapterList(bookUrl)
                    .takeIf { it.isNotEmpty() }
            }
        }
        return if (cached != null) returnData.setData(cached) else refreshToc(parameters)
    }

    /**
     * 获取正文
     */
    suspend fun getBookContent(parameters: Map<String, List<String>>): ReturnData =
        catalogMutationMutex.withLock { getBookContentLocked(parameters) }

    private suspend fun getBookContentLocked(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        val index = parameters["index"]?.firstOrNull()?.toInt()
        val returnData = ReturnData()
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        if (index == null) {
            return returnData.setErrorMsg("参数index不能为空, 请指定目录序号")
        }
        val book = AppDbProviders.get().bookDao.getBook(bookUrl)
        val requestedOrigin = parameters["origin"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        if (book != null && requestedOrigin != null && book.origin != requestedOrigin) {
            return returnData.setErrorMsg("书籍、目录与请求书源不一致，请重新加载目录")
        }
        val bookChapterDao = AppDbProviders.get().bookChapterDao
        val chapter = bookChapterDao.getChapter(bookUrl, index) ?: withTimeoutOrNull(30_000) {
            bookChapterDao.flowChapter(bookUrl, index).filterNotNull().first()
        }
        if (book == null || chapter == null) {
            return returnData.setErrorMsg("未找到")
        }
        val refresh = parameters["refresh"]?.firstOrNull()?.toBoolean() == true ||
            parameters["reParse"]?.firstOrNull()?.toBoolean() == true
        if (refresh) {
            chapter.resourceUrl = null
            bookChapterDao.upResourceUrl(book.bookUrl, chapter.url, null)
            BookStorageProviders.get().delContent(book, chapter)
        }
        var content: String? = if (refresh) null else BookHelpProviders.get().getContent(book, chapter)
        if (content == null && book.isLocal) {
            // 本地书 (txt/epub/cbz) 正文不经书源: 经 FileBook 处理器从原文件按章节
            // start/end 切读 (缓存层 BookStorage 只存网书缓存, 本地书无缓存条目)
            content = runCatching {
                FileBookProviders.get().getHandler(book).getContent(book, chapter)
            }.getOrElse { null }
        }
        if (content != null) {
            content = ContentProcessorProviders.get().getContent(
                book, chapter, content, includeTitle = false, useReplace = true
            ).toString()
            val bookSource = if (!book.isLocal) {
                AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            } else null
            return returnData.setData(transformProxyImagesIfNeeded(content, book, bookSource))
        }
        val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            ?: return returnData.setErrorMsg("未找到书源")
        try {
            content = WebBook.getContentAwait(bookSource, book, chapter).let {
                ContentProcessorProviders.get().getContent(
                    book, chapter, it, includeTitle = false, useReplace = true
                ).toString()
            }
            returnData.setData(transformProxyImagesIfNeeded(content, book, bookSource))
        } catch (e: Exception) {
            returnData.setErrorMsg(e.stackTraceStr)
        }
        return returnData
    }

    private val imgTagRegex = Regex("""<img[^>]*\ssrc=['"]([^'"]*(?:['"][^>]+\})?)['"][^>]*>""", RegexOption.IGNORE_CASE)
    private val mdImgRegex = Regex("""(!\[[^\]]*\]\()([^\)\s]+)(\))""")
    private val legadoUrlParamRegex = Regex(""",\s*\{""")

    /**
     * 在输出给 Web 前端前，动态转换需要解密或中转的图片为相对代理路径 `/image?path=...&url=...`。
     * 底层数据库和文件缓存仍保持纯净原始文本，不污染持久化缓存。
     */
    private fun transformProxyImagesIfNeeded(
        content: String,
        book: Book,
        bookSource: BookSource?,
    ): String {
        if (content.isBlank()) return content

        val hasImageDecode = !bookSource?.contentRule?.imageDecode.isNullOrBlank()
        val hasLegadoUrl = content.contains(",{") || content.contains(", {")

        if (!hasImageDecode && !hasLegadoUrl) {
            return content
        }

        fun toProxyUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.startsWith("/image?") ||
                trimmed.startsWith("data:") ||
                trimmed.startsWith("blob:")
            ) {
                return trimmed
            }
            val isLegado = legadoUrlParamRegex.containsMatchIn(trimmed)
            if (!hasImageDecode && !isLegado) {
                return trimmed
            }
            val encodedPath = JsExtensionsPlatform.urlEncode(trimmed, "UTF-8")
            val encodedUrl = JsExtensionsPlatform.urlEncode(book.bookUrl, "UTF-8")
            return "/image?path=$encodedPath&url=$encodedUrl"
        }

        // 1. 处理 HTML <img ... src="..."> 标签
        var result = imgTagRegex.replace(content) { matchResult ->
            val fullTag = matchResult.value
            val src = matchResult.groupValues[1]
            val proxySrc = toProxyUrl(src)
            if (proxySrc != src) {
                fullTag.replace(src, proxySrc)
            } else {
                fullTag
            }
        }

        // 2. 处理 Markdown ![alt](url) 语法
        result = mdImgRegex.replace(result) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val src = matchResult.groupValues[2]
            val suffix = matchResult.groupValues[3]
            val proxySrc = toProxyUrl(src)
            "$prefix$proxySrc$suffix"
        }

        // 3. 处理漫画纯图片 URL 行
        result = result.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (isImageOrHttpLine(trimmed)) {
                toProxyUrl(trimmed)
            } else {
                line
            }
        }

        return result
    }

    private fun isImageOrHttpLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.startsWith("/image?")) return false
        if (line.startsWith("http://", ignoreCase = true) ||
            line.startsWith("https://", ignoreCase = true) ||
            line.startsWith("//")
        ) {
            return true
        }
        val clean = line.substringBefore(",{").trim()
        return clean.endsWith(".jpg", ignoreCase = true) ||
               clean.endsWith(".jpeg", ignoreCase = true) ||
               clean.endsWith(".png", ignoreCase = true) ||
               clean.endsWith(".gif", ignoreCase = true) ||
               clean.endsWith(".webp", ignoreCase = true) ||
               clean.endsWith(".bmp", ignoreCase = true) ||
               clean.endsWith(".avif", ignoreCase = true)
    }

    /**
     * 保存书籍
     */
    suspend fun saveBook(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<Book>(postData).getOrNull()?.let { book ->
            val bookDao = AppDbProviders.get().bookDao
            val existing = bookDao.getBook(book.bookUrl)
            val existingNotShelf = existing?.takeIf { (it.type and BookType.notShelf) > 0 }
            val saved = if (existingNotShelf != null) {
                // Web 搜索对象可能是稀疏 JSON；以已抓取的 notShelf 记录为底，仅合并有效元数据。
                existingNotShelf.copy(
                    name = book.name.ifBlank { existingNotShelf.name },
                    author = book.author.ifBlank { existingNotShelf.author },
                    origin = book.origin.ifBlank { existingNotShelf.origin },
                    originName = book.originName.ifBlank { existingNotShelf.originName },
                    tocUrl = book.tocUrl.ifBlank { existingNotShelf.tocUrl },
                    kind = book.kind ?: existingNotShelf.kind,
                    coverUrl = book.coverUrl ?: existingNotShelf.coverUrl,
                    intro = book.intro ?: existingNotShelf.intro,
                    wordCount = book.wordCount ?: existingNotShelf.wordCount,
                    variable = book.variable ?: existingNotShelf.variable,
                    latestChapterTitle = book.latestChapterTitle
                        ?: existingNotShelf.latestChapterTitle,
                    originOrder = if (book.originOrder != 0) book.originOrder else existingNotShelf.originOrder,
                    type = existingNotShelf.type,
                ).apply { removeType(BookType.notShelf) }
            } else {
                book.apply { removeType(BookType.notShelf) }
            }
            AppWebDavShared.uploadBookProgress(saved)
            if (existing != null) bookDao.update(saved) else bookDao.insert(saved)
            return returnData.setData("")
        }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 删除书籍
     */
    suspend fun deleteBook(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<Book>(postData).getOrNull()?.let { book ->
            // 删除当前阅读书时清空 ReadBook.book (单例经 provider 解耦), 再 delete + addType(notShelf)
            val readBookProvider = ReadBookStateProviders.getOrNull()
            if (readBookProvider != null && readBookProvider.currentBookUrl == book.bookUrl) {
                readBookProvider.clearCurrentBook()
            }
            AppDbProviders.get().bookDao.delete(book)
            book.addType(BookType.notShelf)
            return returnData.setData("")
        }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 保存进度
     */
    suspend fun saveBookProgress(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<BookProgress>(postData)
            .onFailure { it.printStackTraceOnDebug() }
            .getOrNull()?.let { bookProgress ->
                AppDbProviders.get().bookDao.getBook(bookProgress.name, bookProgress.author)?.let { book ->
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    AppWebDavShared.uploadBookProgress(bookProgress) {
                        book.syncTime = systemCurrentTimeMillis()
                    }
                    AppDbProviders.get().bookDao.update(book)
                    // ReadBook 同步 (app 端 ReadBook 单例未下沉, 通过 provider 注入):
                    // 当前阅读书与进度书同名同作者时, 更新 ReadBook.webBookProgress
                    val readBookProvider = ReadBookStateProviders.getOrNull()
                    if (readBookProvider != null &&
                        readBookProvider.currentBookName == bookProgress.name &&
                        readBookProvider.currentBookAuthor == bookProgress.author
                    ) {
                        readBookProvider.setWebBookProgress(bookProgress)
                    }
                    return returnData.setData("")
                }
            }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 添加本地书籍
     */
    fun addLocalBook(
        parameters: Map<String, List<String>>,
        files: Map<String, String>
    ): ReturnData {
        val returnData = ReturnData()
        val fileName = parameters["fileName"]?.firstOrNull()
            ?: return returnData.setErrorMsg("fileName 不能为空")
        val fileData = files["fileData"]
            ?: return returnData.setErrorMsg("fileData 不能为空")
        kotlin.runCatching {
            // FileBook.saveBookFile(File(fileData).inputStream(), fileName) →
            // FileBookProviders.get().saveBookFileFromPath (commonMain 无法直接 File.inputStream())
            val uri = FileBookProviders.get().saveBookFileFromPath(fileData, fileName)
            FileBook.importLocalFile(uri)
        }.onFailure {
            return when {
                it.isSecurityException() -> returnData.setErrorMsg("需重新设置书籍保存位置!")
                else -> returnData.setErrorMsg("保存书籍错误\n${it.message}")
            }
        }
        return returnData.setData(true)
    }

    /**
     * 保存web阅读界面配置
     */
    fun saveWebReadConfig(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData?.let {
            CacheManager.put("webReadConfig", postData)
        } ?: CacheManager.delete("webReadConfig")
        return returnData.setData("")
    }

    /**
     * 获取web阅读界面配置
     */
    fun getWebReadConfig(): ReturnData {
        val returnData = ReturnData()
        val data = CacheManager.get("webReadConfig")
            ?: return returnData.setErrorMsg("没有配置")
        return returnData.setData(data)
    }

}

/**
 * 图片 Web 接口跨平台 provider 契约 (getCover/getImg)。
 *
 * 原 app 端 BookController.getCover/getImg 依赖 android.graphics.Bitmap + Glide
 * (ImageLoader.loadBitmap) + ImageProvider, 均为 Android-only, 无法下沉 commonMain。
 * 本接口将这些 Android 专属实现抽象为 commonMain 可用的方法签名, 由 app 端
 * [io.legado.app.api.controller.BookControllerImageProviderImpl] 包装原 app 端逻辑,
 * 在 App.onCreate 经 [ImageControllerProviders.register] 注册。
 *
 * 模式参考 [io.legado.app.data.AppDbProviders] / [io.legado.app.help.book.BookHelpProviders]。
 *
 * app 端实现内部持有原 BookController 的实例状态 (book/bookSource/bookUrl/defaultCoverBitmap),
 * 行为与原 app 端 getCover/getImg 完全一致。
 */
interface ImageControllerProvider {

    /**
     * 获取封面图片字节流 (PNG), 对应原 app 端 BookController.getCover。
     *
     * @param coverPath 封面路径/URL (parameters["path"])
     * @return 图片字节流, null 表示获取失败 (调用方 setErrorMsg)
     */
    fun getCover(coverPath: String?): ByteArray?

    /**
     * 获取正文图片字节流 (PNG), 对应原 app 端 BookController.getImg。
     *
     * 实现内部按 bookUrl 缓存 book/bookSource (与原 app 端 getImg 的 this.book/this.bookSource 一致),
     * 避免重复查库。
     *
     * @param bookUrl 书籍 url (parameters["url"])
     * @param src 图片链接 (parameters["path"])
     * @param width 宽度 (parameters["width"], 默认 640)
     * @return 图片字节流, null 表示获取失败 (调用方 setErrorMsg)
     */
    fun getImg(bookUrl: String, src: String, width: Int): ByteArray?
}

/**
 * [ImageControllerProvider] provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `ImageControllerProviders.get().getCover(...)` 替代
 * 原 app 端 `BookController.getCover(...)` 内的 Glide 逻辑, 行为完全一致,
 * 仅多一层 provider 间接。
 */
object ImageControllerProviders {
    @Volatile
    private var impl: ImageControllerProvider? = null

    /** 宿主启动早期注册一次 (任何 BookController.getCover/getImg 调用之前)。 */
    fun register(impl: ImageControllerProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ImageControllerProvider = impl ?: error("ImageControllerProviders not registered")
}

/**
 * ReadBook 单例状态跨平台 provider 契约。
 *
 * 原 app 端 BookController.deleteBook/saveBookProgress 通过 `ReadBook.book` /
 * `ReadBook.webBookProgress` 同步当前阅读状态。ReadBook 单例 (object) 依赖 Compose
 * CompositionLocal 注入 (ReadBookShared 是 class, 无全局访问点), 未下沉 commonMain。
 * 本接口把 BookController 用到的 4 个 ReadBook 操作抽象为 commonMain 可用契约,
 * 由 app 端实现桥接 ReadBook 单例, 在 App.onCreate 经 [ReadBookStateProviders.register] 注册。
 *
 * desktop/iOS/鸿蒙端无 ReadBook 单例, 不注册时 [getOrNull] 返回 null,
 * BookController.deleteBook/saveBookProgress 跳过 ReadBook 同步 (web API 返回值不受影响,
 * 仅当前阅读界面状态不同步, 这些平台本无阅读界面)。
 *
 * 模式参考 [io.legado.app.data.AppDbProviders]。
 */
interface ReadBookStateProvider {
    /** 当前阅读书籍的 bookUrl (对应 `ReadBook.book?.bookUrl`), null 表示无正在阅读的书。 */
    val currentBookUrl: String?

    /** 当前阅读书籍的 name (对应 `ReadBook.book?.name`)。 */
    val currentBookName: String?

    /** 当前阅读书籍的 author (对应 `ReadBook.book?.author`)。 */
    val currentBookAuthor: String?

    /** 清空当前阅读书 (对应 `ReadBook.book = null`), deleteBook 删除当前阅读书时调用。 */
    fun clearCurrentBook()

    /** 设置 web 阅读进度 (对应 `ReadBook.webBookProgress = progress`), saveBookProgress 同步时调用。 */
    fun setWebBookProgress(progress: BookProgress)
}

/**
 * [ReadBookStateProvider] provider 容器。宿主启动早期注册一次 (可选)。
 *
 * 与 [AppDbProviders] 不同, 本容器允许不注册 (desktop/iOS/鸿蒙无 ReadBook 单例):
 * BookController 通过 [getOrNull] 取实现, null 时跳过 ReadBook 同步, 行为降级但不报错。
 */
object ReadBookStateProviders {
    @Volatile
    private var impl: ReadBookStateProvider? = null

    /** 宿主启动早期注册一次 (可选, desktop/iOS/鸿蒙可不注册)。 */
    fun register(impl: ReadBookStateProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null (调用方自行降级处理)。 */
    fun getOrNull(): ReadBookStateProvider? = impl
}
