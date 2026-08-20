package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController.getImg
import io.legado.app.api.controller.ReadBookStateProviders.getOrNull
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.CacheManager
import io.legado.app.help.book.BookHelpProviders
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

    /*
    * 分组号及名称
     */
    suspend fun groups(): ReturnData {
        val returnData = ReturnData()
        return returnData.setData(AppDbProviders.get().bookGroupDao.all())
    }

    /**
     * 通过group id获取书籍
     */
    suspend fun getBooks(parameters: Map<String, List<String>>): ReturnData {
        val groupId = parameters["groupId"]?.firstOrNull()?.toLong()
        val books = if (groupId == null) AppDbProviders.get().bookDao.all() else AppDbProviders.get().bookDao.flowByGroup(groupId).first()
        return if (books.isEmpty()) {
            ReturnData().setErrorMsg("未找到")
        } else {
            val data = when (AppConfigProviders.get().bookshelfSort) {
                1 -> books.sortedByDescending { it.latestChapterTime }
                2 -> books.sortedWith { o1, o2 ->
                    o1.name.cnCompare(o2.name)
                }

                3 -> books.sortedBy { it.order }
                else -> books.sortedByDescending { it.durChapterTime }
            }
            ReturnData().setData(data)
        }
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
    suspend fun refreshToc(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        try {
            val bookUrl = parameters["url"]?.firstOrNull()
            if (bookUrl.isNullOrEmpty()) {
                return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
            }
            val book = AppDbProviders.get().bookDao.getBook(bookUrl)
                ?: return returnData.setErrorMsg("未在数据库找到对应书籍，请先添加")
            if (book.isLocal) {
                val toc = FileBook.getChapterList(book)
                val appDb = AppDbProviders.get()
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
                appDb.bookDao.update(book)
                return returnData.setData(toc)
            } else {
                val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                    ?: return returnData.setErrorMsg("未找到对应书源,请换源")
                if (book.tocUrl.isBlank()) {
                    WebBook.getBookInfoAwait(bookSource, book)
                }
                val toc = WebBook.getChapterListAwait(bookSource, book).getOrThrow()
                val appDb = AppDbProviders.get()
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
                appDb.bookDao.update(book)
                return returnData.setData(toc)
            }
        } catch (e: Exception) {
            return returnData.setErrorMsg(e.message ?: "refresh toc error")
        }
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
        val chapterList = AppDbProviders.get().bookChapterDao.getChapterList(bookUrl)
        if (chapterList.isEmpty()) {
            return refreshToc(parameters)
        }
        return returnData.setData(chapterList)
    }

    /**
     * 获取正文
     */
    suspend fun getBookContent(parameters: Map<String, List<String>>): ReturnData {
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
        val bookChapterDao = AppDbProviders.get().bookChapterDao
        val chapter = bookChapterDao.getChapter(bookUrl, index) ?: withTimeoutOrNull(30_000) {
            bookChapterDao.flowChapter(bookUrl, index).filterNotNull().first()
        }
        if (book == null || chapter == null) {
            return returnData.setErrorMsg("未找到")
        }
        var content: String? = BookHelpProviders.get().getContent(book, chapter)
        if (content != null) {
            content = ContentProcessorProviders.get().getContent(
                book, chapter, content, includeTitle = false, useReplace = true
            ).toString()
            return returnData.setData(content)
        }
        val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            ?: return returnData.setErrorMsg("未找到书源")
        try {
            content = WebBook.getContentAwait(bookSource, book, chapter).let {
                ContentProcessorProviders.get().getContent(
                    book, chapter, it, includeTitle = false, useReplace = true
                ).toString()
            }
            returnData.setData(content)
        } catch (e: Exception) {
            returnData.setErrorMsg(e.stackTraceStr)
        }
        return returnData
    }

    /**
     * 保存书籍
     */
    suspend fun saveBook(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<Book>(postData).getOrNull()?.let { book ->
            AppWebDavShared.uploadBookProgress(book)
            // 内联 book.save() 扩展 (app 端 BookExtensions.kt, 未下沉 commonMain):
            // removeType(notShelf) + 按 bookUrl 判断 insert/update
            book.removeType(BookType.notShelf)
            val bookDao = AppDbProviders.get().bookDao
            if (bookDao.has(book.bookUrl)) {
                bookDao.update(book)
            } else {
                bookDao.insert(book)
            }
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
