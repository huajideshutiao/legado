package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.storage.BackupShared
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.replaceReservedChar
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.toJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * AppWebDav 业务流程的 KMP 共享版 (BackupShared/RestoreShared 配套)。
 *
 * # 背景
 * app 端 `io.legado.app.help.AppWebDav` 单例依赖 Android-specific 类
 * (splitties.init.appCtx / appString / RemoteBookWebDav / Uri / isNetworkAvailable /
 * toastOnUi), 不能直接下沉 shared/commonMain。本 KMP 版抽掉 Android 专属依赖,
 * 仅保留 WebDav 业务编排 (checkUrl / upload / download / delete / exists / listFiles /
 * 书籍进度同步 / 备份恢复编排), 桌面端 / iOS 端可直接复用。
 *
 * # 与 app 端 [io.legado.app.help.AppWebDav] 的差异
 * - **不依赖 appCtx / SharedPreferences**: 配置读走 [AppConfigProviders] /
 *   [PreferenceProviders] (与 AppConfigAccessor 同语义, webDavDir/webDavDeviceName 走
 *   PreferenceProviders 直接读 PreferKey)。
 * - **不依赖 appString / toastOnUi**: 错误信息改为通过 [AppLog.put] 输出, 由宿主
 *   AppLogHost 决定是否 toast/落盘。
 * - **不下沉 books export / upBgs 业务**: 依赖 `RemoteBookWebDav` / `Uri` /
 *   `Array<File>` 的方法 (exportWebDav(Uri) / upBgs / RemoteBookWebDav) 保留在
 *   app 端 [io.legado.app.help.AppWebDav], 桌面端如需可后续补 desktop actual。
 * - **不依赖 isNetworkAvailable**: 直接 try-catch, 让 [WebDavException] 自身报错。
 *
 * # 命名后缀 Shared
 * 与 app 端 [io.legado.app.help.AppWebDav] 同包同名会与 app 模块类冲突
 * (shared androidMain target 被 app 模块依赖), 故加 `Shared` 后缀避免歧义,
 * 同时表明这是 KMP 共享版本。
 *
 * # 模式参考
 * - app 端 [io.legado.app.help.AppWebDav] (业务对照原型)
 * - shared/commonMain `io.legado.app.model.webBook.WebBook` (KMP 业务编排层下沉先例)
 */
object AppWebDavShared {

    /** 默认 WebDav 服务地址 (坚果云), 与 app 端 [io.legado.app.help.AppWebDav] 一致。 */
    private const val DEFAULT_WEB_DAV_URL = "https://dav.jianguoyun.com/dav/"

    /** 书籍进度同步子目录名, 与 app 端一致。 */
    private const val PROGRESS_SUB_DIR = "bookProgress/"

    /** 导出书籍子目录名, 与 app 端一致 (桌面端暂不使用, 保留以备扩展)。 */
    private const val EXPORTS_SUB_DIR = "books/"

    /** 背景图片子目录名, 与 app 端一致 (桌面端暂不使用, 保留以备扩展)。 */
    private const val BG_SUB_DIR = "background/"

    /** 当前认证信息, null 表示未初始化 / 认证失败。 */
    var authorization: Authorization? = null
        private set

    /** 配置是否就绪 (账号 + 密码均非空且 [upConfig] 成功)。 */
    val isOk: Boolean get() = authorization != null

    /** 是否使用坚果云 (用于特殊兼容, 例如坚果云的 PROPFIND 限制)。 */
    val isJianGuoYun: Boolean get() = rootWebDavUrl.startsWith(DEFAULT_WEB_DAV_URL, true)

    /** 书籍进度同步 URL (rootWebDavUrl + PROGRESS_SUB_DIR)。 */
    private val bookProgressUrl: String get() = "${rootWebDavUrl}${PROGRESS_SUB_DIR}"

    /** 书籍导出 URL (rootWebDavUrl + EXPORTS_SUB_DIR), 桌面端保留备用。 */
    @Suppress("unused")
    private val exportsWebDavUrl: String get() = "${rootWebDavUrl}${EXPORTS_SUB_DIR}"

    /** 背景图片 URL (rootWebDavUrl + BG_SUB_DIR), 桌面端保留备用。 */
    @Suppress("unused")
    private val bgWebDavUrl: String get() = "${rootWebDavUrl}${BG_SUB_DIR}"

    /**
     * WebDav 根 URL (含子目录, 末尾带 /)。
     *
     * 与 app 端 [io.legado.app.help.AppWebDav.rootWebDavUrl] 同语义:
     * - 配置 URL 为空时默认 [DEFAULT_WEB_DAV_URL]
     * - URL 不以 "/" 结尾自动补
     * - webDavDir 非空时追加为子目录
     */
    private val rootWebDavUrl: String
        get() {
            val configUrl = AppConfigProviders.get().webDavUrl
            var url = if (configUrl.isEmpty()) DEFAULT_WEB_DAV_URL else configUrl
            if (!url.endsWith("/")) url = "$url/"
            PreferenceProviders.get().getString(PreferKey.webDavDir, "legado")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { url = "${url}${it}/" }
            return url
        }

    /**
     * 刷新配置 + 校验认证 + 创建根目录。
     *
     * 与 app 端 [io.legado.app.help.AppWebDav.upConfig] 行为一致:
     * - account / password 任一为空直接跳过 (authorization = null)
     * - 校验失败抛 [WebDavException] (由调用方 toast, 与原版 checkAuthorization 内 toast 等价)
     * - 校验成功创建 root / bookProgress / exports / bg 4 个子目录
     */
    @Throws(WebDavException::class)
    suspend fun upConfig() {
        authorization = null
        val account = AppConfigProviders.get().webDavAccount
        val password = AppConfigProviders.get().webDavPassword
        if (account.isEmpty() || password.isEmpty()) return
        val mAuthorization = Authorization(account, password)
        checkAuthorization(mAuthorization)
        try {
            WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
            WebDav(bookProgressUrl, mAuthorization).makeAsDir()
            WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
            WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
            authorization = mAuthorization
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav upConfig 失败\n${e.message}", e)
            throw e
        }
    }

    /**
     * 校验用户名密码是否有效。
     *
     * 校验失败会清空 webDavPassword (避免持续使用错误密码), 并抛 [WebDavException],
     * 文案走 [appString] 与 app 端 R.string.webdav_application_authorization_error 一致。
     */
    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            PreferenceProviders.get().remove(PreferKey.webDavPassword)
            throw WebDavException(appString(AppStringKey.webdav_application_authorization_error))
        }
    }

    /**
     * 列出 WebDav 上的所有 backup* 备份文件名 (按名称倒序)。
     *
     * 用于"恢复"页面下拉选择。
     */
    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let { auth ->
            val files = WebDav(rootWebDavUrl, auth).listFiles()
                .sortedWith { o1, o2 -> AlphanumComparator.compare(o1.displayName, o2.displayName) }
                .reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    /**
     * 从 WebDav 下载指定备份 zip 并解压恢复 (走 [RestoreShared.restoreLocked])。
     *
     * @param name 备份文件名 (相对 [rootWebDavUrl])
     */
    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        val auth = authorization ?: return
        val webDav = WebDav(rootWebDavUrl + name, auth)
        webDav.downloadTo(BackupShared.zipFilePath, true)
        BackupFileOps.delete(BackupShared.backupPath)
        BackupFileOps.unZipToPath(BackupShared.zipFilePath, BackupShared.backupPath)
        io.legado.app.help.storage.RestoreShared.restoreLocked(BackupShared.backupPath)
    }

    /** 指定备份文件是否已存在云端 (用于增量备份判断)。 */
    suspend fun hasBackUp(backUpName: String): Boolean {
        val auth = authorization ?: return false
        val url = "$rootWebDavUrl$backUpName"
        return WebDav(url, auth).exists()
    }

    /**
     * 取最近一次 backup* 备份的 [WebDavFile] 元数据 (按 lastModify 倒序)。
     *
     * 用于"自动检查新备份"。
     */
    suspend fun lastBackUp(): Result<WebDavFile?> = kotlin.runCatching {
        val auth = authorization ?: return@runCatching null
        var lastBackupFile: WebDavFile? = null
        WebDav(rootWebDavUrl, auth).listFiles().reversed().forEach { webDavFile ->
            if (webDavFile.displayName.startsWith("backup")) {
                if (lastBackupFile == null || webDavFile.lastModify > lastBackupFile!!.lastModify) {
                    lastBackupFile = webDavFile
                }
            }
        }
        lastBackupFile
    }

    /**
     * 上传统一备份 zip 到 WebDav。
     *
     * @param fileName 备份文件名 (相对 [rootWebDavUrl])
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        val auth = authorization ?: throw NoStackTraceException("webDav没有配置")
        val putUrl = "$rootWebDavUrl$fileName"
        WebDav(putUrl, auth).upload(BackupShared.zipFilePath)
    }

    /**
     * 上传整本书籍文件到 WebDav (对照 app 端 RemoteBookWebDav.upload + BookInfoViewModel.uploadBook)。
     *
     * 用 [LocalBookLocators] 取本地文件路径 (替代 app 端 `book.bookUrl.toUri()` 的
     * Android Uri 解析), 上传到 `books/` 子目录, 写回 book.origin 为 webDavTag + URL。
     *
     * @param book 书籍实体 (须为本地书, 否则 getLocalPath 返回 null 抛异常)
     * @param serverID 远程服务器 ID (默认 null, 与 app 端 defaultBookWebDav 一致)
     */
    @Throws(Exception::class)
    suspend fun uploadBook(book: Book, serverID: Long? = null) {
        val auth = authorization ?: throw NoStackTraceException("webDav没有配置")
        val localPath = LocalBookLocators.get().getLocalPath(book)
            ?: throw NoStackTraceException("本地文件路径不存在")
        val putUrl = "$exportsWebDavUrl${book.originName}"
        WebDav(putUrl, auth).upload(localPath)
        book.origin = BookType.webDavTag + CustomUrl(putUrl)
            .putAttribute("serverID", serverID)
            .toString()
        book.lastCheckTime = systemCurrentTimeMillis()
        AppDatabaseProviders.get().appDb.bookDao.update(book)
    }

    /**
     * 上传单本书籍进度到 WebDav (JSON 格式)。
     *
     * @param book 书籍实体
     * @param toast 是否在失败时 toast (false 时只 AppLog.put)
     * @param onSuccess 上传成功回调 (供 UI 刷新等)
     */
    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val auth = authorization ?: return
        if (!AppConfigProviders.get().syncBookProgress) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            WebDav(url, auth).upload(json.encodeToByteArray(), "application/json")
            book.syncTime = systemCurrentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.message}", e, toast)
        }
    }

    /**
     * 上传预制 [BookProgress] 实体到 WebDav。
     */
    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        try {
            val auth = authorization ?: return
            if (!AppConfigProviders.get().syncBookProgress) return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            WebDav(url, auth).upload(json.encodeToByteArray(), "application/json")
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.message}", e)
        }
    }

    /** 拼接书籍进度的 WebDav URL。 */
    private fun getProgressUrl(name: String, author: String): String {
        return "$bookProgressUrl${getProgressFileName(name, author)}"
    }

    /** 书籍进度文件名: `${name}_${author}.json`, 保留字符转义 (与 app 端原版一致)。 */
    private fun getProgressFileName(name: String, author: String): String {
        return replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 拉取单本书籍的云端进度。
     *
     * - JSON 解析失败 / 网络异常均返回 null (走 AppLog 记录, 不抛)
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        val url = getProgressUrl(book.name, book.author)
        kotlin.runCatching {
            val auth = authorization ?: return null
            WebDav(url, auth).download().let { byteArray ->
                val json = byteArray.decodeToString()
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.message}", it)
        }
        return null
    }

    /**
     * 拉取所有书籍进度并写回本地数据库 (仅当云端进度比本地新时更新)。
     *
     * 与 app 端 [io.legado.app.help.AppWebDav.downloadAllBookProgress] 同语义。
     */
    suspend fun downloadAllBookProgress() {
        val auth = authorization ?: return
        val bookProgressFiles = WebDav(bookProgressUrl, auth).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach { map[it.displayName] = it }
        AppDatabaseProviders.get().appDb.bookDao.all().forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = map[progressFileName] ?: return@forEach
            if (webDavFile.lastModify <= book.syncTime) {
                // 本地同步时间大于上传时间不用同步
                return@forEach
            }
            getBookProgress(book)?.let { bookProgress ->
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                        && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = systemCurrentTimeMillis()
                    AppDatabaseProviders.get().appDb.bookDao.update(book)
                }
            }
        }
    }
}
