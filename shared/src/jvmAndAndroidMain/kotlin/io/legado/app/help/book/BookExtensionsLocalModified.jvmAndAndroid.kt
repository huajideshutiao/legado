@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.model.fileBook.FileBookProviders

/**
 * Book.isLocalModified 下沉 (shared jvmAndAndroidMain)。
 *
 * 原 app 端 `BookExtensions.kt` 中的扩展, 因被下沉的 [io.legado.app.model.fileBook.TextFile] 使用而一并下沉。
 *
 * 语义: 本地书且文件最后修改时间 > 书籍最新章节时间, 表示文件已变更需重新解析。
 *
 * 依赖 [FileBookProviders.get].getLastModified (BaseFileBook 接口默认方法已下沉 commonMain,
 * 委托 FileBookProviders 在各平台实现: app 端委托原 FileBook, desktop 端用 LocalBookLocators)。
 *
 * 跨模块同包名同签名扩展自动合并, app 端 ExportBookService 等消费方 import 零改动。
 */
fun Book.isLocalModified(): Boolean {
    return isLocal && FileBookProviders.get().getLastModified(this).getOrDefault(0L) > latestChapterTime
}
