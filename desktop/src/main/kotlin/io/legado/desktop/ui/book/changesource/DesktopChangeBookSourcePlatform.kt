package io.legado.desktop.ui.book.changesource

import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.SourceConfig
import io.legado.app.ui.book.changesource.ChangeBookSourcePlatform

/**
 * 桌面端 [ChangeBookSourcePlatform] 实现。
 *
 * 桌面端换源功能简化版, 与 app 端 [io.legado.app.ui.book.changesource.ChangeBookSourceViewModel]
 * 内部的 AndroidChangeBookSourcePlatform 区别:
 *
 * # 简化项 (依赖未下沉的 app 端组件)
 *
 * - **getDurChapter**: 取 `chapters.lastIndex` (与 fromReadBookActivity=false 路径一致),
 *   桌面端换源不从阅读页进入, 无需定位当前章节
 *   (BookHelp.getDurChapter 依赖 EscapeUtils.jaccardSimilarity / StringUtils.fullToHalf
 *   / Pattern 等 Android 专属字符串处理, 未下沉);
 * - **processContent**: 直接返回 content, 不走替换规则/简繁/重排段
 *   (ContentProcessor 依赖 appDb.replaceRuleDao / BookHelp / Pattern, 未下沉);
 * - **toastOnUi**: 用 println 替代 (桌面端无 Android Toast, 后续可接桌面通知系统)。
 *
 * # 已实现项 (从 PreferenceProviders 读)
 *
 * - 4 个 changeSource* 开关 + threadCount + searchGroup 读写:
 *   桌面端 PreferenceProviders (DesktopPreferenceProvider 实现) 已注册, 直接读;
 *   searchGroup setter 写回 PreferenceProviders 持久化。
 * - **评分 3 方法** (setBookScore / getBookScore / getSourceScore):
 *   SourceConfig 已下沉 commonMain (走 PreferenceProviders), 直接调用;
 *   桌面端评分通过 java.util.prefs.Preferences 持久化, 行为与 app 端等价。
 *
 * 模式参考 [io.legado.desktop.config.DesktopAppConfigAccessor]。
 */
class DesktopChangeBookSourcePlatform : ChangeBookSourcePlatform {

    private val prefs get() = PreferenceProviders.get()

    // ---- AppConfig 相关 ----

    override val threadCount: Int
        get() = prefs.getInt(PreferKey.threadCount, 16)

    /**
     * searchGroup: getter 读 PreferenceProviders, setter 写回持久化。
     *
     * 注: [setSearchGroup] 默认实现已调 `searchGroup = value` 触发 setter, 无需额外覆盖。
     */
    override var searchGroup: String
        get() = prefs.getString(PreferKey.searchGroup, "")
        set(value) {
            prefs.putString(PreferKey.searchGroup, value)
        }

    // 桌面端默认值与 app 端 AppConfig 对齐 (AppConfig 中 4 个 changeSource* 默认值)
    // var: UI 切换开关时需写回 PreferenceProviders 持久化
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

    /**
     * 桌面端简化: 取末章索引 (与 fromReadBookActivity=false 路径一致)。
     *
     * 桌面端换源不从阅读页进入, 无需定位当前章节。
     * BookHelp.getDurChapter 依赖 Android 专属字符串处理, 未下沉。
     */
    override fun getDurChapter(oldBook: Book, chapters: List<BookChapter>): Int {
        return chapters.lastIndex
    }

    // ---- ContentProcessor 相关 ----

    /**
     * 桌面端简化: 直接返回 content, 不走替换规则/简繁/重排段。
     *
     * ContentProcessor 依赖 appDb.replaceRuleDao / BookHelp / Pattern, 未下沉。
     * 桌面端换源默认 changeSourceLoadWordCount=false, 不会触发此方法
     * (仅 loadBookWordCount 调用), 但保留 no-op 实现以防用户开启字数加载。
     */
    override fun processContent(
        oldBook: Book, chapter: BookChapter, content: String, includeTitle: Boolean
    ): CharSequence {
        return content
    }

    // ---- SourceConfig 评分相关 ----

    /** 委托 [SourceConfig.setBookScore], 持久化到 PreferenceProviders 后端 (桌面端 java.util.prefs)。 */
    override fun setBookScore(origin: String, name: String, author: String, score: Int) {
        SourceConfig.setBookScore(origin, name, author, score)
    }

    /** 委托 [SourceConfig.getBookScore], 从 PreferenceProviders 后端读。 */
    override fun getBookScore(origin: String, name: String, author: String): Int {
        return SourceConfig.getBookScore(origin, name, author)
    }

    /** 委托 [SourceConfig.getSourceScore], 从 PreferenceProviders 后端读。 */
    override fun getSourceScore(origin: String): Int {
        return SourceConfig.getSourceScore(origin)
    }

    // ---- Toast 相关 ----

    /**
     * 桌面端用 println 替代 Android Toast。
     *
     * 后续可接桌面通知系统 (java.awt.SystemTray / Notifications)。
     */
    override fun toastOnUi(msg: String) {
        println("[ChangeSource] $msg")
    }
}
