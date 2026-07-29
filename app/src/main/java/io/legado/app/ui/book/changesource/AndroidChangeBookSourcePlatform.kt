package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * Android 端 [ChangeBookSourcePlatform] 实现 (顶级类)。
 *
 * 从 [ChangeBookSourceViewModel] 的 inner class 提取, 供 shared Route + app ViewModel 共用。
 * 委托 [AppConfig] / [ContentProcessor] / [BookHelp] / [SourceConfig] / [appCtx.toastOnUi]。
 *
 * 注: 原 inner class 通过 `context` (BaseViewModel 提供) 访问 Android Context 调
 * `context.toastOnUi`; 顶级类无外类 context, 改用 [appCtx] (Application Context),
 * 与原行为等价 (appCtx 也是 Context, toastOnUi 是 Context 扩展)。
 */
class AndroidChangeBookSourcePlatform : ChangeBookSourcePlatform {

    // ---- AppConfig 相关 ----

    override val threadCount: Int
        get() = AppConfig.threadCount

    /**
     * searchGroup 用 var 实现: getter 读 [AppConfig.searchGroup],
     * setter 写 [AppConfig.searchGroup] (持久化到 SharedPreferences)。
     *
     * 注: [setSearchGroup] 默认实现已调 `searchGroup = value` 触发 setter, 无需额外覆盖。
     */
    override var searchGroup: String
        get() = AppConfig.searchGroup
        set(value) {
            AppConfig.searchGroup = value
        }

    /**
     * changeSourceCheckAuthor: getter 读 [AppConfig.changeSourceCheckAuthor],
     * setter 写 [AppConfig.changeSourceCheckAuthor] (持久化到 SharedPreferences)。
     *
     * app 端 Dialog 直接 `AppConfig.changeSourceCheckAuthor = value` 写回, 走此 setter。
     */
    override var changeSourceCheckAuthor: Boolean
        get() = AppConfig.changeSourceCheckAuthor
        set(value) {
            AppConfig.changeSourceCheckAuthor = value
        }

    override var changeSourceLoadInfo: Boolean
        get() = AppConfig.changeSourceLoadInfo
        set(value) {
            AppConfig.changeSourceLoadInfo = value
        }

    override var changeSourceLoadToc: Boolean
        get() = AppConfig.changeSourceLoadToc
        set(value) {
            AppConfig.changeSourceLoadToc = value
        }

    override var changeSourceLoadWordCount: Boolean
        get() = AppConfig.changeSourceLoadWordCount
        set(value) {
            AppConfig.changeSourceLoadWordCount = value
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
        appCtx.toastOnUi(msg)
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
