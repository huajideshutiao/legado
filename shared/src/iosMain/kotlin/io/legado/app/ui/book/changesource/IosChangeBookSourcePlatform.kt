package io.legado.app.ui.book.changesource

import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.toast.Toasters

/**
 * iOS 端 [ChangeBookSourcePlatform] 实现。
 *
 * 对照桌面端 [io.legado.desktop.ui.book.changesource.DesktopChangeBookSourcePlatform],
 * 行为对齐, 仅 toast 通道替换为 iOS 端 [Toasters] (对照桌面端 println 占位)。
 *
 * # 简化项 (与桌面端一致, 依赖未下沉的 app 端组件)
 *
 * - **getDurChapter**: 取末章索引 (BookHelp.getDurChapter 依赖 Android 专属字符串处理, 未下沉);
 * - **processContent**: 直接返回 content (ContentProcessor 依赖未下沉, 但默认配置不触发此方法);
 * - **toastOnUi**: 用 [Toasters.get().toast] (iOS 端已注册 IosToaster)。
 *
 * PreferenceProviders / SourceConfig 已下沉 commonMain, iOS 端在
 * [io.legado.app.help.config.registerIosProviders] 中已注册 IosPreferenceProvider,
 * 直接读写持久化配置。
 */
class IosChangeBookSourcePlatform : ChangeBookSourcePlatform {

    private val prefs get() = PreferenceProviders.get()

    // ---- AppConfig 相关 ----

    override val threadCount: Int
        get() = prefs.getInt(PreferKey.threadCount, 16)

    override var searchGroup: String
        get() = prefs.getString(PreferKey.searchGroup, "")
        set(value) {
            prefs.putString(PreferKey.searchGroup, value)
        }

    override var changeSourceCheckAuthor: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceCheckAuthor, true)
        set(value) {
            prefs.putBoolean(PreferKey.changeSourceCheckAuthor, value)
        }

    override var changeSourceLoadInfo: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadInfo, false)
        set(value) {
            prefs.putBoolean(PreferKey.changeSourceLoadInfo, value)
        }

    override var changeSourceLoadToc: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadToc, false)
        set(value) {
            prefs.putBoolean(PreferKey.changeSourceLoadToc, value)
        }

    override var changeSourceLoadWordCount: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadWordCount, false)
        set(value) {
            prefs.putBoolean(PreferKey.changeSourceLoadWordCount, value)
        }

    // ---- BookHelp 相关 ----

    // iOS 端简化: 取末章索引 (与桌面端一致, BookHelp.getDurChapter 未下沉)
    override fun getDurChapter(oldBook: Book, chapters: List<BookChapter>): Int {
        return chapters.lastIndex
    }

    // ---- ContentProcessor 相关 ----

    // iOS 端简化: 直接返回 content (与桌面端一致, ContentProcessor 未下沉)
    override fun processContent(
        oldBook: Book, chapter: BookChapter, content: String, includeTitle: Boolean
    ): CharSequence {
        return content
    }

    // ---- SourceConfig 评分相关 ----

    override fun setBookScore(origin: String, name: String, author: String, score: Int) {
        SourceConfig.setBookScore(origin, name, author, score)
    }

    override fun getBookScore(origin: String, name: String, author: String): Int {
        return SourceConfig.getBookScore(origin, name, author)
    }

    override fun getSourceScore(origin: String): Int {
        return SourceConfig.getSourceScore(origin)
    }

    // ---- Toast 相关 ----

    // iOS 端用 Toasters (IosProviderRegistry 已注册 IosToaster, 对照桌面端 println)
    override fun toastOnUi(msg: String) {
        Toasters.get().toast(msg)
    }
}
