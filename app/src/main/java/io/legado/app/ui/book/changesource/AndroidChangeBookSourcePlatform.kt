package io.legado.app.ui.book.changesource

import io.legado.app.App
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.SourceConfig
import io.legado.app.utils.toastOnUi

/**
 * Android 端 [ChangeBookSourcePlatform] 实现 (顶级类)。
 *
 * 从 [ChangeBookSourceViewModel] 的 inner class 提取, 供 shared Route + app ViewModel 共用。
 * 委托 [AppConfigProviders] / [ContentProcessor] / [BookHelp] / [SourceConfig] / [appCtx.toastOnUi]。
 *
 * 注: 原 inner class 通过 `context` (BaseViewModel 提供) 访问 Android Context 调
 * `context.toastOnUi`; 顶级类无外类 context, 改用 [appCtx] (Application Context),
 * 与原行为等价 (appCtx 也是 Context, toastOnUi 是 Context 扩展)。
 */
class AndroidChangeBookSourcePlatform : ChangeBookSourcePlatform {

    // ---- AppConfig 相关 ----

    private val appConfig get() = AppConfigProviders.get()

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

    /** 委托 [BookHelp.getDurChapter], 章节名相似度匹配定位当前章节。 */
    override fun getDurChapter(oldBook: Book, chapters: List<BookChapter>): Int {
        return BookHelp.getDurChapter(oldBook, chapters)
    }

    // ---- ContentProcessor 相关 ----

    /**
     * 委托 [ContentProcessor.get].getContent, 走完整正文处理
     * (替换规则 / 简繁 / 重排段 / 去重复标题)。
     *
     * includeTitle 传 false, 与原 `contentProcessor.getContent(oldBook, chapter, content, false)` 一致。
     *
     * 注: [ContentProcessor.getContent] 返回 [BookContent] (非 CharSequence), 接口要求
     * [CharSequence]; [BookContent.toString] 实现为 `textList.joinToString("\n")`,
     * 与 shared 调用方 [ChangeBookSourceViewModelShared.loadWordCount] 中
     * `platform.processContent(...).toString()` 语义一致 (取拼接后的正文文本)。
     */
    override fun processContent(
        oldBook: Book, chapter: BookChapter, content: String, includeTitle: Boolean
    ): CharSequence {
        return ContentProcessor.get(oldBook).getContent(
            oldBook, chapter, content, includeTitle
        ).toString()
    }

    // ---- SourceConfig 评分相关 ----

    /** 委托 [SourceConfig.setBookScore], 持久化到 SharedPreferences。 */
    override fun setBookScore(origin: String, name: String, author: String, score: Int) {
        SourceConfig.setBookScore(origin, name, author, score)
    }

    /** 委托 [SourceConfig.getBookScore], 从 SharedPreferences 读。 */
    override fun getBookScore(origin: String, name: String, author: String): Int {
        return SourceConfig.getBookScore(origin, name, author)
    }

    /** 委托 [SourceConfig.getSourceScore], 从 SharedPreferences 读。 */
    override fun getSourceScore(origin: String): Int {
        return SourceConfig.getSourceScore(origin)
    }

    // ---- Toast 相关 ----

    /** 委托 [appCtx.toastOnUi], 走 Android Toast。 */
    override fun toastOnUi(msg: String) {
        App.instance.toastOnUi(msg)
    }
}

/**
 * 安卓宿主启动早期注册 ChangeBookSource 平台 provider。
 *
 * 调用时机: App.onCreate, 在 `registerAndroidAudioPlayProviders()` 之后
 * (ChangeBookSource 依赖 AppDbProviders / WebBookProviders 已注册)。
 *
 * 模式参考 `registerAndroidAudioPlayProviders` / `registerAndroidWebBookProviders`。
 */
fun registerAndroidChangeBookSourcePlatform() {
    ChangeBookSourcePlatformProviders.register(AndroidChangeBookSourcePlatform())
}
