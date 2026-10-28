package io.legado.app.ui.association

import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.addType
import io.legado.app.help.archive.ArchiveProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.http.KmpResponseBody
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.writeBytesAtomically
import io.legado.app.model.webBook.WebBook.getBookInfoByUrlAwait
import io.legado.app.ui.association.SchemeImportOps.determineType
import io.legado.app.ui.association.SchemeImportOps.importReadConfig
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.withContext

/**
 * legado:// deep link 里三个此前"不支持"的类型的落地实现 (KMP commonMain, 各端共享)。
 *
 * 对照原版 `FileAssociationFragment` 的 scheme 分发与 `FileAssociationViewModel`:
 * - `/addToBookshelf` → [AddToBookshelfShared] (原 `AddToBookshelfHelper.add`)
 * - `/readConfig` → [importReadConfig] (原 `getBytes` + `importReadConfig`)
 * - 其余未识别 path → [determineType] (原 `determineType`: 下载内容按 Content-Type 嗅探,
 *   zip/octet-stream 当排版配置, 否则当 JSON 走 [detectJsonType]); 调用方在嗅探前先用
 *   [io.legado.app.model.webBook.WebBook.matchBookSourceByUrl] 判这条地址是不是某书源的书,
 *   是则转成 `/addToBookshelf` (见 DeepLinkImportHost)
 *
 * 均为纯 commonMain 编排 (DB/网络/文件), 不含 Uri/LiveData/Toast, 由
 * [io.legado.app.ui.association.DeepLinkImportHost] 桥接结果到 UI。
 */
object SchemeImportOps {

    /** 压缩包探测目录序号: 每次探测独占一个目录, 不与并发请求共用。 */
    private val probeSequence = atomic(0L)

    /**
     * 下载 [url] 内容, 按 Content-Type 嗅探是"排版配置 zip"还是"JSON 一键导入"
     * (对照 app 端 `determineType`)。
     *
     * 下载载荷与 MIME 原样保留: 排版配置拿整包 zip; 压缩包内若没有排版配置、而是单个
     * JSON, 才按条目语义解出正文交给导入 VM。
     *
     * @return 排版配置分支返回 [DetermineResult.ReadConfig] (已完成导入);
     *   JSON 分支返回 [DetermineResult.Json] (交调用方按 [DeepLinkImportType] 继续走导入 VM);
     *   下载/解析失败抛异常, 调用方按 ImportError 处理。
     */
    suspend fun determineType(url: String): DetermineResult = withDownloadedBody(url) { body ->
        withContext(IoDispatcher) {
            val bytes = body.bytes()
            if (body.isZipPayload()) {
                importZipPayload(bytes)
            } else {
                val json = bytes.decodeToString()
                // 下载到网页而不是可导入内容: 这条链接没有书源认领, 报"未找到匹配书源"
                // (与抓书链同一文案), 不把用户引向"文件格式"
                val jsonType = detectJsonType(json)
                    ?: error(if (json.trimStart().startsWith("<")) "未找到匹配书源" else "格式不对")
                DetermineResult.Json(jsonType.toDeepLinkImportType(), json)
            }
        }
    }

    /** 下载 [url] 后导入排版配置 (对照 app 端 `getBytes` + `importReadConfig`)。 */
    suspend fun importReadConfig(url: String): String = withDownloadedBody(url) { body ->
        withContext(IoDispatcher) { importReadConfigBytes(body.bytes()) }
    }

    /** Content-Type 是 zip / octet-stream (参数如 charset 不参与判定)。 */
    private fun KmpResponseBody.isZipPayload(): Boolean {
        val mime = contentType()?.toString()?.substringBefore(';')?.trim()?.lowercase()
        return mime == "application/zip" || mime == "application/octet-stream"
    }

    /**
     * 压缩包载荷分流: 包内有 readConfig.json 就是排版配置整包; 否则按压缩 JSON 解出正文。
     *
     * 探测在独立临时目录完成, 结束后只清理本目录; 真正的排版导入仍复用 [importReadConfigBytes]。
     */
    private fun importZipPayload(bytes: ByteArray): DetermineResult {
        val sep = BackupFileOps.separator
        val probeDir = AppFilesDirs.get().cacheDir + sep + "schemeImport" + sep +
            "probe-" + probeSequence.incrementAndGet()
        try {
            BackupFileOps.delete(probeDir)
            BackupFileOps.createFolderIfNotExist(probeDir)
            val zipPath = probeDir + sep + "payload.zip"
            check(writeBytesAtomically(zipPath, bytes)) { "写入临时压缩包失败" }
            // 条目名与正文都走现成归档能力: 排版配置看包内是否有 readConfig.json;
            // 压缩 JSON 取归档首个文件条目的正文 (不按后缀挑选, 不改读后续条目)
            val names = ArchiveProviders.get().getFilesName(zipPath) { true }
            if (names.any { it == ReadBookConfigShared.configFileName }) {
                return DetermineResult.ReadConfig(importReadConfigBytes(bytes))
            }
            val first = names.firstOrNull() ?: error("格式不对")
            val content = ArchiveProviders.get().getByteArrayContent(bytes, first)
                ?: error("格式不对")
            val json = content.decodeToString()
            val jsonType = detectJsonType(json) ?: error("格式不对")
            return DetermineResult.Json(jsonType.toDeepLinkImportType(), json)
        } finally {
            BackupFileOps.delete(probeDir)
        }
    }

    /**
     * 下载 [url] 内容, 把响应体交给 [handle], 用完必关。
     *
     * `#requestWithoutUA` 去 UA 的写法只此一份 (类型嗅探与排版配置导入两处共用)。
     * 响应体原样交给调用方, 由调用方按 MIME 决定是否解压。
     */
    private suspend fun <T> withDownloadedBody(
        url: String,
        handle: suspend (KmpResponseBody) -> T,
    ): T {
        val body = OkHttpClientProviders.get().okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }
        return try {
            handle(body)
        } finally {
            body.close()
        }
    }

    /**
     * 导入排版配置字节 (对照 app 端 `importReadConfig` 的核心逻辑):
     * 解压解析后按 name 覆盖已有同名配置, 否则追加, 返回配置名供 UI 提示。
     */
    private fun importReadConfigBytes(bytes: ByteArray): String {
        val config = ReadBookConfigProviders.get().import(bytes)
        val configList = ReadBookConfigProviders.get().configList
        configList.forEachIndexed { index, c ->
            if (c.name == config.name) {
                configList[index] = config
                return config.name
            }
        }
        configList.add(config)
        return config.name
    }

    /** 结果三态, 对照 app 端 `determineType` 的 finally 回调 (title, msg) 语义, 交调用方分流。 */
    sealed interface DetermineResult {
        /** 排版配置已导入完成, [configName] 供 UI toast 提示。 */
        data class ReadConfig(val configName: String) : DetermineResult

        /** 识别为 JSON 一键导入, 交调用方按 [type] 继续走对应导入 VM。 */
        data class Json(val type: DeepLinkImportType, val json: String) : DetermineResult
    }
}

/** [JsonType] → [DeepLinkImportType] 映射 (两套枚举同源, 一一对应)。 */
fun JsonType.toDeepLinkImportType(): DeepLinkImportType = when (this) {
    JsonType.BOOK_SOURCE, JsonType.RSS_SOURCE -> DeepLinkImportType.BOOK_SOURCE
    JsonType.REPLACE_RULE -> DeepLinkImportType.REPLACE_RULE
    JsonType.SOURCE_FILTER_RULE -> DeepLinkImportType.SOURCE_FILTER_RULE
    JsonType.THEME -> DeepLinkImportType.THEME
    JsonType.DICT_RULE -> DeepLinkImportType.DICT_RULE
    JsonType.TXT_RULE -> DeepLinkImportType.TXT_TOC_RULE
    JsonType.HTTP_TTS -> DeepLinkImportType.HTTP_TTS
}

/**
 * "添加到书架" (对照 app 端原 `AddToBookshelfHelper.add`): 按 bookUrl 抓详情, 成功后跳
 * [AppRoute.BookInfo] (未上架, 供用户在详情页决定是否收藏), 失败上抛异常交调用方 toast。
 */
object AddToBookshelfShared {

    /** 抓取书籍详情并构建未上架状态的详情页路由 (供多端导航与路由解析复用)。 */
    suspend fun resolveRoute(bookUrl: String, matchedSource: BookSource? = null): AppRoute {
        if (bookUrl.isBlank()) error("url不能为空")
        val book = getBookInfoByUrlAwait(bookUrl, matchedSource)
        book.addType(BookType.notShelf)
        return AppRoute.BookInfo(book.toRouteRef())
    }

    /**
     * @param beforeNavigate 抓完书、真正 push 之前等的东西。调用方用它把"收起加载框并等它确实
     *   离开界面"插在跳转前面 —— 置隐与跳转落在同一主线程消息时, 系统合成出的第一帧仍是框还在的
     *   画面, 整段页面转场都会带着它 (原版是 "关框 → 打开页面" 的顺序)。
     * @param matchedSource 调用方已认领到的书源, 传下后不再重算认领。
     */
    suspend fun add(
        bookUrl: String,
        beforeNavigate: suspend () -> Unit = {},
        matchedSource: BookSource? = null,
    ) {
        val route = resolveRoute(bookUrl, matchedSource)
        beforeNavigate()
        // getOrNull: Android 透明壳 AssociationActivity 也挂 DeepLinkImportHost, 壳内没有
        // LegadoApp/navigator (壳把书籍类请求拦成 pendingBookNav 转发, 靠的是壳自己的拦截顺序)
        AppNavigatorProviders.getOrNull()?.push(route)
    }
}

/**
 * "书架直读" (legado://import/read?src=... 落地实现, 对照 app 端原 `ReadBookHelper.open`):
 * - src 已在书架 (DB 存在) → 直接进阅读界面 ([AppRoute.Reader] 等阅读类路由);
 * - 不在书架 → 等同 [AddToBookshelfShared]: 抓详情后跳详情界面, 供用户决定是否收藏。
 */
object ReadBookShared {

    /** 根据在架状态解析目标路由: 在架跳阅读页, 未在架走 addToBookshelf 进详情页。 */
    suspend fun resolveRoute(bookUrl: String): AppRoute {
        if (bookUrl.isBlank()) error("url不能为空")
        val book = AppDbProviders.get().bookDao.getBook(bookUrl)
        return book?.toReadRoute() ?: AddToBookshelfShared.resolveRoute(bookUrl)
    }

    /**
     * @param beforeNavigate 同 [AddToBookshelfShared.add]: 跳转前收起加载框并等它离开界面。
     */
    suspend fun read(bookUrl: String, beforeNavigate: suspend () -> Unit = {}) {
        val route = resolveRoute(bookUrl)
        beforeNavigate()
        // getOrNull: 同 AddToBookshelfShared.add, 透明壳里没有 navigator
        AppNavigatorProviders.getOrNull()?.push(route)
    }
}
