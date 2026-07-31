package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelpChapterLocator
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.toast.Toasters

// iOS 换源平台实现: 复用 commonMain 的 PreferenceProviders/SourceConfig/Toasters/BookHelpChapterLocator
class IosChangeBookSourcePlatform : ChangeBookSourcePlatform {

    private val appConfig get() = AppConfigProviders.get()

    // ---- AppConfig 相关 ----

    override val threadCount: Int
        get() = appConfig.threadCount

    override var searchGroup: String
        get() = appConfig.searchGroup
        set(value) {
            appConfig.searchGroup = value
        }

    override var changeSourceCheckAuthor: Boolean
        get() = appConfig.changeSourceCheckAuthor
        set(value) {
            appConfig.changeSourceCheckAuthor = value
        }

    override var changeSourceLoadInfo: Boolean
        get() = appConfig.changeSourceLoadInfo
        set(value) {
            appConfig.changeSourceLoadInfo = value
        }

    override var changeSourceLoadToc: Boolean
        get() = appConfig.changeSourceLoadToc
        set(value) {
            appConfig.changeSourceLoadToc = value
        }

    override var changeSourceLoadWordCount: Boolean
        get() = appConfig.changeSourceLoadWordCount
        set(value) {
            appConfig.changeSourceLoadWordCount = value
        }

    // ---- BookHelp 相关 ----

    // 章节定位: 复用 commonMain 的相似度匹配算法 (与 app 端 BookHelp.getDurChapter 同源)
    override fun getDurChapter(oldBook: Book, chapters: List<BookChapter>): Int =
        BookHelpChapterLocator.getDurChapter(oldBook, chapters)

    // ---- ContentProcessor 相关 ----

    // ContentProcessor 未下沉, 直接返回原文 (与 desktop 简化一致)
    override fun processContent(
        oldBook: Book, chapter: BookChapter, content: String, includeTitle: Boolean
    ): CharSequence = content

    // ---- SourceConfig 评分相关 (复用 commonMain, 走 PreferenceProviders 持久化) ----

    override fun setBookScore(origin: String, name: String, author: String, score: Int) {
        SourceConfig.setBookScore(origin, name, author, score)
    }

    override fun getBookScore(origin: String, name: String, author: String): Int =
        SourceConfig.getBookScore(origin, name, author)

    override fun getSourceScore(origin: String): Int =
        SourceConfig.getSourceScore(origin)

    // ---- Toast 相关 (复用 commonMain Toasters, IosToaster 已注册) ----

    override fun toastOnUi(msg: String) {
        Toasters.get().toast(msg)
    }
}

// iOS 宿主注册入口 (对照 app 端 registerAndroidChangeBookSourcePlatform)
fun registerIosChangeBookSourcePlatform() {
    ChangeBookSourcePlatformProviders.register(IosChangeBookSourcePlatform())
}
