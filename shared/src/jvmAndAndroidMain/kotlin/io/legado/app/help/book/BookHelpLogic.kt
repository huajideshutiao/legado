@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.UrlUtil

/**
 * BookHelp 纯逻辑方法下沉 (shared jvmAndAndroidMain)。
 *
 * 原 app 端 `BookHelp.kt` object 中的纯 Kotlin/JVM 逻辑, 下沉到 shared 供全平台复用。
 * 实现逻辑、函数签名均与原 app 端保持一致。
 *
 * 依赖说明:
 * - formatBookAuthor 已移至 [BookHelpShared] (commonMain), 此处委托;
 * - getDurChapter 及其章节名/章节号辅助方法已移至 [BookHelpChapterLocator] (commonMain), 此处委托;
 * - [UrlUtil.getSuffix] 仍在 jvmAndAndroidMain (依赖 java.net.URL 等), 故本文件保留在 jvmAndAndroidMain。
 *
 * app 端 `BookHelp` 同名方法改为薄壳委托, 70+ 处消费方零改动。
 */
object BookHelpLogic {

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String = BookHelpShared.formatBookAuthor(author)

    /**
     * 取图片后缀
     */
    fun getImageSuffix(src: String): String {
        return UrlUtil.getSuffix(src, "jpg")
    }

    /**
     * 根据目录名获取当前章节
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int = BookHelpChapterLocator.getDurChapter(
        oldDurChapterIndex,
        oldDurChapterName,
        newChapterList,
        oldChapterListSize
    )

    fun getDurChapter(
        oldBook: Book,
        newChapterList: List<BookChapter>
    ): Int = BookHelpChapterLocator.getDurChapter(oldBook, newChapterList)

}
