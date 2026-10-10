@file:Suppress("unused")

package io.legado.app.help.book

import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import kotlinx.coroutines.runBlocking

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
 * 本文件仅保留依赖 app 平台绑定 (Uri/FileDoc/AppConfig 等) 的扩展。
 * 对已下沉扩展的调用 (如 sync/migrateTo 调用 getUseReplaceRule,
 * getUnreadChapterNum 调用 simulatedTotalChapterNum) 经跨模块同包名同签名扩展
 * 自动合并解析, 无需 import。
 */

fun Book.getArchiveUri(): Uri? {
    val defaultBookDir = AppConfig.defaultBookTreeUri
    return if (isArchive && !defaultBookDir.isNullOrBlank()) {
        FileDoc.fromUri(defaultBookDir.toUri(), true)
            .find(archiveName)?.uri
    } else {
        null
    }
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

fun Book.getBookSource(): BookSource? {
    return runBlocking { appDb.bookSourceDao.getBookSource(origin) }
}
