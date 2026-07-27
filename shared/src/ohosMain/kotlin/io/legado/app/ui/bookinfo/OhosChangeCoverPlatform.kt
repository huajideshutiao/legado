package io.legado.app.ui.bookinfo

import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.book.changecover.ChangeCoverPlatform

/**
 * 鸿蒙端 [ChangeCoverPlatform] 实现。
 *
 * 对照 iOS 端 [IosChangeCoverPlatform] / 桌面端 [io.legado.desktop.ui.bookinfo.ChangeCoverPlatformDesktop],
 * 注入鸿蒙端实现:
 *
 * - **threadCount**: 读 `PreferenceProviders` (PreferKey.threadCount, 默认 16),
 *   与 [io.legado.app.ui.book.changesource.OhosChangeBookSourcePlatform.threadCount] 一致;
 * - **cleanAuthor**: `AppPattern.authorRegex` 已下沉 commonMain, 鸿蒙端直接调用
 *   `author.replace(AppPattern.authorRegex, "")`, 与 app/desktop/iOS 端原实现一致。
 *
 * PreferenceProviders 已下沉 commonMain, 鸿蒙端在
 * [io.legado.app.help.config.OhosProviderRegistry.registerOhosProviders] 中已注册
 * OhosPreferenceProvider, 直接读写持久化配置。
 */
class OhosChangeCoverPlatform : ChangeCoverPlatform {

    private val prefs get() = PreferenceProviders.get()

    override val threadCount: Int
        get() = prefs.getInt(PreferKey.threadCount, 16)

    override fun cleanAuthor(author: String): String {
        return author.replace(AppPattern.authorRegex, "")
    }
}
