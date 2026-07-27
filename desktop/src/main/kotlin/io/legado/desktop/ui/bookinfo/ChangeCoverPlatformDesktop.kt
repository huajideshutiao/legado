package io.legado.desktop.ui.bookinfo

import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.book.changecover.ChangeCoverPlatform

/**
 * 桌面端 [ChangeCoverPlatform] 实现。
 *
 * 对照桌面端 [io.legado.desktop.ui.book.changesource.DesktopChangeBookSourcePlatform] 模式,
 * 注入桌面端简化实现:
 *
 * # 已实现项 (从 PreferenceProviders 读)
 *
 * - **threadCount**: 读 `PreferenceProviders` (PreferKey.threadCount, 默认 16),
 *   与桌面端 [io.legado.desktop.ui.book.changesource.DesktopChangeBookSourcePlatform.threadCount] 一致;
 * - **cleanAuthor**: `AppPattern.authorRegex` 已下沉 commonMain, 桌面端直接调用
 *   `author.replace(AppPattern.authorRegex, "")`, 与 app 端原实现一致。
 *
 * # 与 app 端的差异
 *
 * - app 端 `threadCount` 读 `AppConfig.threadCount` (依赖 SharedPreferences + appCtx);
 *   桌面端读 `PreferenceProviders` (DesktopPreferenceProvider), 两端读取路径不同但持久化机制等价。
 * - `cleanAuthor` 两端实现完全一致 (AppPattern 已下沉 commonMain)。
 */
class ChangeCoverPlatformDesktop : ChangeCoverPlatform {

    private val prefs get() = PreferenceProviders.get()

    /**
     * 并发线程数 (对照 `AppConfig.threadCount`)。
     *
     * 读 `PreferenceProviders` (PreferKey.threadCount, 默认 16),
     * 与桌面端 [io.legado.desktop.ui.book.changesource.DesktopChangeBookSourcePlatform.threadCount] 一致。
     */
    override val threadCount: Int
        get() = prefs.getInt(PreferKey.threadCount, 16)

    /**
     * 清洗作者字符串 (对照 app 端 `author.replace(AppPattern.authorRegex, "")`)。
     *
     * `AppPattern.authorRegex` 已下沉 commonMain (`io.legado.app.constant.AppPattern`),
     * 桌面端直接调用, 与 app 端原实现完全一致。
     */
    override fun cleanAuthor(author: String): String {
        return author.replace(AppPattern.authorRegex, "")
    }
}
