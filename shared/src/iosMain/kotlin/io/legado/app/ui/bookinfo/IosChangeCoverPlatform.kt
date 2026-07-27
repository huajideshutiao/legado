package io.legado.app.ui.bookinfo

import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.book.changecover.ChangeCoverPlatform

/**
 * iOS 端 [ChangeCoverPlatform] 实现。
 *
 * 对照桌面端 [io.legado.desktop.ui.bookinfo.ChangeCoverPlatformDesktop] 模式,
 * 注入 iOS 端实现:
 *
 * - **threadCount**: 读 `PreferenceProviders` (PreferKey.threadCount, 默认 16),
 *   与 [io.legado.app.ui.book.changesource.IosChangeBookSourcePlatform.threadCount] 一致;
 * - **cleanAuthor**: `AppPattern.authorRegex` 已下沉 commonMain, iOS 端直接调用
 *   `author.replace(AppPattern.authorRegex, "")`, 与 app/desktop 端原实现一致。
 *
 * PreferenceProviders 已下沉 commonMain, iOS 端在
 * `io.legado.app.help.config.registerIosProviders` 中已注册 IosPreferenceProvider。
 */
class IosChangeCoverPlatform : ChangeCoverPlatform {

    private val prefs get() = PreferenceProviders.get()

    override val threadCount: Int
        get() = prefs.getInt(PreferKey.threadCount, 16)

    override fun cleanAuthor(author: String): String {
        return author.replace(AppPattern.authorRegex, "")
    }
}
