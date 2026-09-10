package io.legado.desktop.help.changesource

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelpChapterLocator
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.changesource.ChangeBookSourcePlatform
import io.legado.app.ui.book.changesource.ChangeBookSourcePlatformProviders

/**
 * desktop 端 [ChangeBookSourcePlatform] 实现。
 *
 * 对照 app 端 [io.legado.app.ui.book.changesource.AndroidChangeBookSourcePlatform]:
 * - threadCount / searchGroup: 走 shared [AppConfigProviders] (desktop 端 DesktopAppConfigAccessor 已注册)
 * - 换源偏好统一走 shared [AppConfigProviders]，与其他配置保持单一来源。
 * - getDurChapter: 复用 shared commonMain 的 [BookHelpChapterLocator.getDurChapter] (与 app 端 BookHelp.getDurChapter 同一份算法)
 * - processContent: 复用 shared [ContentProcessorProviders] (desktop 端 DesktopContentProcessorAccessor 已注册,
 *   走完整 ContentProcessorShared 替换规则 / 简繁 / 重排段 / 去重标题)
 * - setBookScore / getBookScore / getSourceScore: 复用 shared commonMain 的 [SourceConfig]
 *   (经 PreferenceProviders 持久化, desktop 端与 app 端同源)
 * - toastOnUi: 走 shared [Toasters] (desktop 端 DesktopToaster 已注册, SystemTray 通知)
 *
 * 注册时机: desktop Main.kt, 在 registerDesktopWebBookProviders() / registerDesktopJsEngines() 之后
 * (ChangeBookSource 依赖 AppDbProviders / WebBookProviders / ContentProcessorProviders 已注册)。
 */
class DesktopChangeBookSourcePlatform : ChangeBookSourcePlatform {

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

    /** 委托 shared BookHelpChapterLocator, 与 app 端 BookHelp.getDurChapter 同一份算法。 */
    override fun getDurChapter(oldBook: Book, chapters: List<BookChapter>): Int =
        BookHelpChapterLocator.getDurChapter(oldBook, chapters)

    // ---- ContentProcessor 相关 ----

    /** 委托 shared ContentProcessorProviders, 走完整正文处理 (替换规则/简繁/重排段/去重标题)。 */
    override fun processContent(
        oldBook: Book, chapter: BookChapter, content: String, includeTitle: Boolean
    ): CharSequence = ContentProcessorProviders.get().getContent(
        oldBook, chapter, content, includeTitle, useReplace = true
    )

    // ---- SourceConfig 评分相关 ----

    override fun setBookScore(origin: String, name: String, author: String, score: Int) =
        SourceConfig.setBookScore(origin, name, author, score)

    override fun getBookScore(origin: String, name: String, author: String): Int =
        SourceConfig.getBookScore(origin, name, author)

    override fun getSourceScore(origin: String): Int =
        SourceConfig.getSourceScore(origin)

    // ---- Toast 相关 ----

    override fun toastOnUi(msg: String) {
        Toasters.get().toast(msg)
    }
}

/**
 * 桌面端注册 ChangeBookSource 平台 provider。
 *
 * 调用时机: desktop Main.kt, 在 registerDesktopWebBookProviders() 之后
 * (换源依赖 AppDbProviders / WebBookProviders / ContentProcessorProviders 已注册)。
 * 模式参考 [io.legado.app.ui.book.changesource.registerAndroidChangeBookSourcePlatform]。
 */
fun registerDesktopChangeBookSourcePlatform() {
    ChangeBookSourcePlatformProviders.register(DesktopChangeBookSourcePlatform())
}
