package io.legado.app.ui.association

import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.help.book.addType
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.model.webBook.WebBook.getBookInfoByUrlAwait
import io.legado.app.ui.association.SchemeImportOps.determineType
import io.legado.app.ui.association.SchemeImportOps.importReadConfig
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef

/**
 * legado:// deep link 里三个此前"不支持"的类型的落地实现 (KMP commonMain, 各端共享)。
 *
 * 对照 app 端 `FileAssociationFragment.handleOnLineImport` 与 `FileAssociationViewModel`:
 * - `/addToBookshelf` → [AddToBookshelfShared] (原 `AddToBookshelfHelper.add`)
 * - `/readConfig` → [importReadConfig] (原 `getBytes` + `importReadConfig`)
 * - 其余未识别 path → [determineType] (原 `determineType`: 下载内容按 Content-Type 嗅探,
 *   zip/octet-stream 当排版配置, 否则当 JSON 走 [detectJsonType])
 *
 * 均为纯 commonMain 编排 (DB/网络/文件), 不含 Uri/LiveData/Toast, 由
 * [io.legado.app.ui.association.DeepLinkImportHost] 桥接结果到 UI。
 */
object SchemeImportOps {

    /**
     * 下载 [url] 内容, 按 Content-Type 嗅探是"排版配置 zip"还是"JSON 一键导入"
     * (对照 app 端 `determineType`)。
     *
     * @return 排版配置分支返回 [DetermineResult.ReadConfig] (已完成导入);
     *   JSON 分支返回 [DetermineResult.Json] (交调用方按 [DeepLinkImportType] 继续走导入 VM);
     *   下载/解析失败抛异常, 调用方按 ImportError 处理。
     */
    suspend fun determineType(url: String): DetermineResult {
        val body = OkHttpClientProviders.get().okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed()
        try {
            val contentType = body.contentType()?.toString()
            return if (contentType == "application/zip" || contentType == "application/octet-stream") {
                DetermineResult.ReadConfig(importReadConfigBytes(body.bytes()))
            } else {
                val json = body.bytes().decodeToString()
                val type = detectJsonType(json) ?: error("格式不对")
                DetermineResult.Json(type.toDeepLinkImportType(), json)
            }
        } finally {
            body.close()
        }
    }

    /** 下载 [url] 后导入排版配置 (对照 app 端 `getBytes` + `importReadConfig`)。 */
    suspend fun importReadConfig(url: String): String {
        val body = OkHttpClientProviders.get().okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed()
        return try {
            importReadConfigBytes(body.bytes())
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

    suspend fun add(bookUrl: String) {
        if (bookUrl.isBlank()) error("url不能为空")
        val book = getBookInfoByUrlAwait(bookUrl)
        book.addType(BookType.notShelf)
        AppNavigatorProviders.getOrNull()?.push(AppRoute.BookInfo(book.toRouteRef()))
    }
}

/**
 * "书架直读" (legado://import/read?src=... 落地实现, 对照 app 端原 `ReadBookHelper.open`):
 * - src 已在书架 (DB 存在) → 直接进阅读界面 ([AppRoute.Reader] 等阅读类路由);
 * - 不在书架 → 等同 [AddToBookshelfShared.add]: 抓详情后跳详情界面, 供用户决定是否收藏。
 */
object ReadBookShared {

    suspend fun read(bookUrl: String) {
        if (bookUrl.isBlank()) error("url不能为空")
        val book = AppDbProviders.get().bookDao.getBook(bookUrl)
        if (book != null) {
            AppNavigatorProviders.getOrNull()?.push(book.toReadRoute())
        } else {
            AddToBookshelfShared.add(bookUrl)
        }
    }
}
