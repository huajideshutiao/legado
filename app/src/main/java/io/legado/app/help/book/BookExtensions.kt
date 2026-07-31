@file:Suppress("unused")

package io.legado.app.help.book

import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.utils.FileDoc
import io.legado.app.utils.exists
import io.legado.app.utils.find
import io.legado.app.utils.inputStream
import io.legado.app.utils.isUri
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/*
 * Book 扩展函数 app 端保留区。
 *
 * 原文件中的纯类型位运算扩展 (isVideo/isAudio/isImage/isRss/isWebFile/isUpError/
 * isArchive/isNotShelf/isLocal/isLocalTxt/isEpub/isPdf/isOnLineTxt/archiveName/
 * getRemoteUrl/setType/addType/removeType/removeAllBookType/clearType/isType/upType)
 * 已下沉到 shared BookExtensionsShared.kt (供 WebBook/BookChapterList/BookContent
 * 在 shared 中使用)。
 *
 * 原文件中的显示相关扩展 (getUseReplaceRule/readSimulating/simulatedTotalChapterNum)
 * 已下沉到 shared BookDisplayExtensionsShared.kt (依赖 AppConfigProviders/
 * AppDbProviders/ChineseUtils, 经 provider 间接访问)。
 *
 * 跨模块同包名同签名扩展自动合并, 消费方 import 零改动。
 * 注意: 同包名同签名扩展函数不允许在两个模块同时定义, 已从本文件删除已下沉的扩展。
 *
 * 本文件仅保留依赖 app 平台绑定 (Uri/FileDoc/AppConfig.appCtx/
 * ReadBook/ReadTimeRecorder/BookHelp 等) 的扩展。
 * 对已下沉扩展的调用 (如 sync/migrateTo 调用 getUseReplaceRule,
 * getUnreadChapterNum 调用 simulatedTotalChapterNum) 经跨模块同包名同签名扩展
 * 自动合并解析, 无需 import。
 */

private val localUriCache by lazy {
    ConcurrentHashMap<String, Uri>()
}

fun Book.getLocalUri(): Uri {
    if (!isLocal) {
        throw NoStackTraceException("不是本地书籍")
    }
    var uri = localUriCache[bookUrl]
    if (uri != null) {
        return uri
    }
    uri = if (bookUrl.isUri()) {
        bookUrl.toUri()
    } else {
        Uri.fromFile(File(bookUrl))
    }
    //先检测uri是否有效,这个比较快
    uri.inputStream(appCtx).getOrNull()?.use {
        localUriCache[bookUrl] = uri
    }?.let {
        return uri
    }
    //不同的设备书籍保存路径可能不一样, uri无效时尝试寻找当前保存路径下的文件
    val defaultBookDir = AppConfig.defaultBookTreeUri
    val importBookDir = AppConfig.importBookPath

    // 查找书籍保存目录
    if (!defaultBookDir.isNullOrBlank()) {
        val treeUri = defaultBookDir.toUri()
        val treeFileDoc = FileDoc.fromUri(treeUri, true)
        if (!treeFileDoc.exists()) {
            appCtx.toastOnUi("书籍保存目录失效，请重新设置！")
        } else {
            val fileDoc = treeFileDoc.find(originName, 5, 100)
            if (fileDoc != null) {
                localUriCache[bookUrl] = fileDoc.uri
                //更新bookUrl 重启不用再找一遍
                bookUrl = fileDoc.toString()
                save()
                return fileDoc.uri
            }
        }
    }

    // 查找添加本地选择的目录
    if (!importBookDir.isNullOrBlank() && defaultBookDir != importBookDir) {
        val treeUri = if (importBookDir.isUri()) {
            importBookDir.toUri()
        } else {
            Uri.fromFile(File(importBookDir))
        }
        val treeFileDoc = FileDoc.fromUri(treeUri, true)
        val fileDoc = treeFileDoc.find(originName, 5, 100)
        if (fileDoc != null) {
            localUriCache[bookUrl] = fileDoc.uri
            bookUrl = fileDoc.toString()
            save()
            return fileDoc.uri
        }
    }

    localUriCache[bookUrl] = uri
    return uri
}


fun Book.getArchiveUri(): Uri? {
    val defaultBookDir = AppConfig.defaultBookTreeUri
    return if (isArchive && !defaultBookDir.isNullOrBlank()) {
        FileDoc.fromUri(defaultBookDir.toUri(), true)
            .find(archiveName)?.uri
    } else {
        null
    }
}

fun Book.cacheLocalUri(uri: Uri) {
    localUriCache[bookUrl] = uri
}

fun Book.removeLocalUriCache() {
    localUriCache.remove(bookUrl)
}

fun Book.sync(oldBook: Book) {
    val curBook = runBlocking { appDb.bookDao.getBook(oldBook.bookUrl) }!!
    durChapterTime = curBook.durChapterTime
    durChapterPos = curBook.durChapterPos
    if (durChapterIndex != curBook.durChapterIndex) {
        durChapterIndex = curBook.durChapterIndex
        val replaceRules = ContentProcessor.get(this).getTitleReplaceRules()
        runBlocking { appDb.bookChapterDao.getChapter(bookUrl, durChapterIndex) }?.let {
            durChapterTitle = it.getDisplayTitle(replaceRules, getUseReplaceRule())
        }
    }
    canUpdate = curBook.canUpdate
    readConfig = curBook.readConfig
}

fun Book.update() {
    runBlocking { appDb.bookDao.update(this@update) }
}

// BaseBook.primaryStr() 已下沉到 shared/commonMain BookExtensionsShared.kt
// (供 ChangeBookSourceViewModelShared 等 KMP 模块使用), 此处删除避免重复定义。
// Book.updateTo / hasVariable / isSameNameAuthor / getExportFileName(×2) / tryParesExportFileName
// 亦已下沉到 BookExtensionsShared.kt (纯逻辑, AppConfig → AppConfigProviders), 此处删除避免重复定义。

fun Book.getBookSource(): BookSource? {
    return runBlocking { appDb.bookSourceDao.getBookSource(origin) }
}

// 注: Book.getUnreadChapterNum 已下沉到 shared BookDisplayExtensionsShared.kt
// (同包名 io.legado.app.help.book, 跨模块同名同签名扩展由 Kotlin 自动解析, 无需 import)

// 注: Book.migrateTo 已下沉到 shared BookExtensionsShared.kt
// (BookHelp.getDurChapter → BookHelpChapterLocator, ContentProcessor → ContentProcessorProviders),
// 同包名同签名扩展跨模块自动合并, 此处删除避免重复定义。

fun Book.save() {
    removeType(BookType.notShelf)
    runBlocking {
        if (appDb.bookDao.has(bookUrl)) {
            appDb.bookDao.update(this@save)
        } else {
            appDb.bookDao.insert(this@save)
        }
    }
}

fun Book.saveRead() {
    lastCheckCount = 0
    durChapterTime = System.currentTimeMillis()
    // 仅 PATCH 进度字段; 避免阅读/播放界面退出时整行 update 冲掉
    // 后台 updateToc/refreshBookInfo 写入的最新元数据 (name/intro/cover/totalChapterNum 等)
    runBlocking {
        appDb.bookDao.updateProgress(
            bookUrl,
            durChapterIndex,
            durChapterPos,
            durChapterTime,
            durChapterTitle
        )
    }
    ReadTimeRecorder.flushAll()
}

fun Book.delete() {
    if (ReadBook.book?.bookUrl == bookUrl) {
        ReadBook.book = null
    }
    runBlocking { appDb.bookDao.delete(this@delete) }
    addType(BookType.notShelf)
}
