@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.model.fileBook.FileBookProviders

/**
 * Book.isLocalModified (原 jvmAndAndroidMain, 因 TextFileCore 下沉 commonMain 而上移;
 * 依赖的 isLocal / FileBookProviders 均在 commonMain, jvm 端行为不变)。
 *
 * 语义: 本地书且文件最后修改时间 > 书籍最新章节时间, 表示文件已变更需重新解析。
 */
fun Book.isLocalModified(): Boolean {
    return isLocal && FileBookProviders.get().getLastModified(this).getOrDefault(0L) > latestChapterTime
}
