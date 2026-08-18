@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.script.JsEngines
import io.legado.app.model.script.buildScriptBindings
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.normalizeFileName
import kotlin.math.min

/*
 * Book 扩展函数下沉区 (shared jvmAndAndroidMain)。
 *
 * 仅放 shared 侧实体类 (Book/BaseBook 等) 自身依赖、且无 app 平台绑定的扩展。
 * 安卓侧绑定 (Uri/FileDoc/AppConfig 等) 留 app 端 BookExtensions.kt 同包合并。
 *
 * 跨模块同包名同签名扩展自动合并, 消费方 import 零改动。
 * 注意: 同包名同签名扩展函数不允许在两个模块同时定义, 需从 app 端删除已下沉的扩展。
 */

fun Book.getFolderNameNoCache(): String {
    return name.replace(AppPattern.fileNameRegex, "").let {
        it.substring(0, min(9, it.length)) + MD5Utils.md5Encode16(bookUrl)
    }
}

/**
 * 判断书籍是否包含指定类型位 (BookType 位掩码)。
 *
 * 原 app 端 BookExtensions.kt 中的扩展, 下沉到 shared 以支持
 * isVideo/isAudio/isRss 等扩展在 shared 中使用。
 */
fun BaseBook.isType(bookType: Int): Boolean = type and bookType > 0

val BaseBook.isVideo: Boolean
    get() = isType(BookType.video)

val BaseBook.isAudio: Boolean
    get() = isType(BookType.audio)

val BaseBook.isImage: Boolean
    get() = isType(BookType.image)

val BaseBook.isRss: Boolean
    get() = isType(BookType.rss)

val BaseBook.isWebFile: Boolean
    get() = isType(BookType.webFile)

val BaseBook.isUpError: Boolean
    get() = isType(BookType.updateError)

val BaseBook.isArchive: Boolean
    get() = isType(BookType.archive)

val BaseBook.isNotShelf: Boolean
    get() = isType(BookType.notShelf)

val BaseBook.isLocal: Boolean
    get() {
        if (type == 0) {
            return origin == BookType.localTag || origin.startsWith(BookType.webDavTag)
        }
        return isType(BookType.local)
    }

val BaseBook.isLocalTxt: Boolean
    get() = isLocal && originName.endsWith(".txt", true)

val BaseBook.isEpub: Boolean
    get() = isLocal && originName.endsWith(".epub", true)

val BaseBook.isPdf: Boolean
    get() = isLocal && originName.endsWith(".pdf", true)

val BaseBook.isOnLineTxt: Boolean
    get() = !isLocal && isType(BookType.text)

val BaseBook.archiveName: String
    get() {
        if (!isArchive) throw io.legado.app.exception.NoStackTraceException("Book is not deCompressed from archive")
        // local_book::archive.rar
        // webDav::https://...../archive.rar
        return origin.substringAfter("::").substringAfterLast("/")
    }

fun BaseBook.getRemoteUrl(): String? {
    if (origin.startsWith(BookType.webDavTag)) {
        return origin.substring(BookType.webDavTag.length)
    }
    return null
}

fun BaseBook.setType(vararg types: Int) {
    type = 0
    addType(*types)
}

fun BaseBook.addType(vararg types: Int) {
    types.forEach {
        type = type or it
    }
}

fun BaseBook.removeType(vararg types: Int) {
    types.forEach {
        type = type and it.inv()
    }
}

fun BaseBook.removeAllBookType() {
    removeType(BookType.allBookType)
}

fun BaseBook.clearType() {
    type = 0
}

fun BaseBook.upType() {
    if (type < 8) {
        type = when (type) {
            BookSourceType.video -> BookType.video
            BookSourceType.image -> BookType.image
            BookSourceType.audio -> BookType.audio
            BookSourceType.file -> BookType.webFile
            else -> BookType.text
        }
        if (origin == BookType.localTag || origin.startsWith(BookType.webDavTag)) {
            type = type or BookType.local
        }
    }
}

/**
 * 释放详情页/目录页 HTML 缓存字段。
 *
 * 搜索结果合并后, 短暂保留的原始 HTML 已无用, 置 null 让 GC 回收。
 * 原实现位于 app 端 BookExtensions.kt, 现下沉到 shared 供 SearchModel (shared) 调用。
 */
fun BaseBook.releaseHtmlData() {
    infoHtml = null
    tocHtml = null
}

/**
 * 书籍主键字符串 (origin + bookUrl), 用作 tocMap / bookMap 等 cache key。
 *
 * 原实现位于 app 端 BookExtensions.kt: `fun BaseBook.primaryStr(): String = origin + bookUrl`。
 * 下沉到 shared 供 [io.legado.app.ui.book.changesource.ChangeBookSourceViewModelShared] 使用,
 * 跨模块同包名同签名扩展自动合并, app 端调用方 import 零改动
 * (app 端 BookExtensions.kt 中同名扩展需删除以避免重复定义)。
 *
 * 注意与 [io.legado.app.data.entities.BookChapter.primaryStr] 区分:
 * - BaseBook.primaryStr() = `origin + bookUrl` (Book / SearchBook 用)
 * - BookChapter.primaryStr() = `bookUrl + url` (章节用, 成员方法)
 */
fun BaseBook.primaryStr(): String {
    return origin + bookUrl
}

/**
 * 上架/下架统一核心 (四端共用, 避免各端维护多份拷贝)。
 *
 * 对照原 app 端 [io.legado.app.base.BaseReadViewModel.addToBookshelf] → `Book.save()`
 * 与 `delBook` 的 DB 部分, 从桌面/iOS/鸿蒙的 toggleBookshelf 实现提炼下沉:
 * - 下架: 删章节 + 删书 (desktop/iOS/ohos 原有行为)
 * - 上架: 补 order + 合并同名书阅读进度 + 去 notShelf 标记后按 bookUrl 存在性 update/insert
 *   (对照原 addToBookshelf; 去 notShelf 不可省略, 否则书架查询与发现页绿点会过滤掉该书)
 *
 * 平台专属行为 (删除确认弹窗 / 本地文件删除 / 缓存清理) 由各端在调用前后自行处理。
 *
 * @return null = 下架成功 (书已从书架删除); true = 上架成功; false = 操作失败
 */
suspend fun Book.toggleBookshelfCore(inBookshelf: Boolean): Boolean? {
    val appDb = AppDbProviders.get()
    return if (inBookshelf) {
        appDb.bookChapterDao.delByBook(bookUrl)
        appDb.bookDao.delete(this)
        null
    } else {
        if (order == 0) order = appDb.bookDao.minOrder() - 1
        appDb.bookDao.getBook(name, author)?.let {
            durChapterIndex = it.durChapterIndex
            durChapterPos = it.durChapterPos
            durChapterTitle = it.durChapterTitle
        }
        removeType(BookType.notShelf)
        if (appDb.bookDao.has(bookUrl)) appDb.bookDao.update(this)
        else appDb.bookDao.insert(this)
        true
    }
}

/**
 * 静默删除书籍核心 (四端共用): 删章节 + 删书 + 标记 notShelf, 本地书按需删源文件。
 *
 * 对照 app 端 [io.legado.app.base.BaseReadViewModel.delBook] 与 `Book.delete()`: 不弹确认框,
 * 供未入架临时书清理 (目录页返回未选章节) 与确认后的下架共用。
 * 平台缓存清理 (正文/封面) 由各端在此之后自行处理。
 */
suspend fun Book.delBookCore(deleteOriginal: Boolean = false) {
    toggleBookshelfCore(inBookshelf = true)
    // 对照 Book.delete(): 删库后内存对象要标记回临时书
    addType(BookType.notShelf)
    if (isLocal) FileBook.deleteBook(this, deleteOriginal)
}

/**
 * 同名同作者判断 (原 app 端 BookExtensions.kt, 纯逻辑下沉)。
 */
fun BaseBook.isSameNameAuthor(other: Any?): Boolean {
    if (other is BaseBook) {
        return name == other.name && author == other.author
    }
    return false
}

/**
 * 是否包含指定变量 (variableMap + RuleBigDataProviders 大变量存储)。
 * 原 app 端 BookExtensions.kt, 依赖均已下沉 commonMain, 整体下沉。
 */
fun Book.hasVariable(key: String): Boolean {
    return variableMap.contains(key)
        || RuleBigDataProviders.impl?.hasBookVariable(bookUrl, key) == true
}

/**
 * 将当前书籍的阅读进度/分组/自定义信息迁移到 newBook (纯字段操作)。
 * 原 app 端 BookExtensions.kt, 依赖 encodeStringMap/hasVariable 均在 commonMain, 整体下沉。
 */
fun Book.updateTo(newBook: Book): Book {
    newBook.durChapterIndex = durChapterIndex
    newBook.durChapterTitle = durChapterTitle
    newBook.durChapterPos = durChapterPos
    newBook.durChapterTime = durChapterTime
    newBook.group = group
    newBook.order = order
    newBook.customCoverUrl = customCoverUrl
    newBook.customIntro = customIntro
    newBook.customTag = customTag
    newBook.canUpdate = canUpdate
    newBook.readConfig = readConfig
    val variableMap = variableMap.toMutableMap()
    variableMap.keys.removeAll {
        newBook.hasVariable(it)
    }
    newBook.variableMap.putAll(variableMap)
    newBook.variable = encodeStringMap(newBook.variableMap)
    return newBook
}

/**
 * 导出文件名 (整本, js 表达式来自 AppConfigProviders.bookExportFileName)。
 * 原 app 端 BookExtensions.kt, AppConfig → AppConfigProviders, 其余依赖均在 commonMain。
 */
fun Book.getExportFileName(suffix: String): String {
    val default = "$name 作者：${getRealAuthor()}.$suffix"
    val jsStr = AppConfigProviders.get().bookExportFileName
    if (jsStr.isBlank()) {
        return default.normalizeFileName()
    }
    val bindings = buildScriptBindings { bindings ->
        bindings["epubIndex"] = ""// 兼容老版本,修复可能存在的错误
        bindings["name"] = name
        bindings["author"] = getRealAuthor()
    }
    return kotlin.runCatching {
        JsEngines.get().eval(jsStr, bindings).toString() + "." + suffix
    }.onFailure {
        AppLog.put("导出书名规则错误,使用默认规则\n${it.message}", it)
    }.getOrDefault(default).normalizeFileName()
}

/**
 * 导出文件名 (分卷, js 表达式来自 AppConfigProviders.episodeExportFileName)。
 * 原 app 端 BookExtensions.kt, AppConfig → AppConfigProviders, 其余依赖均在 commonMain。
 */
fun Book.getExportFileName(
    suffix: String,
    epubIndex: Int,
    jsStr: String? = AppConfigProviders.get().episodeExportFileName
): String {
    // 默认规则
    val default = "$name 作者：${getRealAuthor()} [${epubIndex}].$suffix"
    if (jsStr.isNullOrBlank()) {
        return default
    }
    val bindings = buildScriptBindings { bindings ->
        bindings["name"] = name
        bindings["author"] = getRealAuthor()
        bindings["epubIndex"] = epubIndex
    }
    return kotlin.runCatching {
        JsEngines.get().eval(jsStr, bindings).toString() + "." + suffix
    }.onFailure {
        AppLog.put("导出书名规则错误,使用默认规则\n${it.message}", it)
    }.getOrDefault(default).normalizeFileName()
}

/**
 * 校验导出文件名 js 表达式是否可执行。
 * 原 app 端 BookExtensions.kt, 依赖 JsEngines/buildScriptBindings 均在 commonMain, 整体下沉。
 */
fun tryParesExportFileName(jsStr: String): Boolean {
    val bindings = buildScriptBindings { bindings ->
        bindings["name"] = "name"
        bindings["author"] = "author"
        bindings["epubIndex"] = "epubIndex"
    }
    return runCatching {
        JsEngines.get().eval(jsStr, bindings)
        true
    }.getOrDefault(false)
}
