package io.legado.app.ui.book.changecover

import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfigProviders
import kotlin.concurrent.Volatile

/**
 * [ChangeCoverPlatform] 注入容器 (shared commonMain)。
 *
 * 模式参考 [io.legado.app.ui.book.changesource.ChangeBookSourcePlatformProviders]。
 *
 * # 默认实现
 *
 * 与 ChangeBookSourcePlatform 不同, ChangeCoverPlatform 的两个依赖均可直接在 commonMain 获取:
 * - `threadCount`: [AppConfigProviders.get().threadCount] (AppConfigAccessor 已下沉 commonMain)
 * - `cleanAuthor`: `author.replace(AppPattern.authorRegex, "")` (AppPattern 已下沉 commonMain)
 *
 * 故提供 [DefaultChangeCoverPlatform] 作为默认实现, 宿主未注册时自动兜底,
 * 避免 Overlay 渲染时因未注册而抛 IllegalStateException。
 *
 * 宿主仍可通过 [register] 注入平台专属实现覆盖默认行为 (如 iOS 端用 NSRegularExpression)。
 */
object ChangeCoverPlatformProviders {

    @Volatile
    private var impl: ChangeCoverPlatform? = null

    /** 宿主启动早期注册 (可选, 未注册时使用 [DefaultChangeCoverPlatform] 兜底)。 */
    fun register(impl: ChangeCoverPlatform) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 [DefaultChangeCoverPlatform] 兜底。 */
    fun get(): ChangeCoverPlatform = impl ?: DefaultChangeCoverPlatform

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}

/**
 * 默认 ChangeCoverPlatform 实现 (commonMain, 各端可直接复用)。
 *
 * - threadCount: 读 [AppConfigProviders.get().threadCount]
 * - cleanAuthor: `author.replace(AppPattern.authorRegex, "")`
 */
private object DefaultChangeCoverPlatform : ChangeCoverPlatform {
    override val threadCount: Int get() = AppConfigProviders.get().threadCount
    override fun cleanAuthor(author: String): String =
        author.replace(AppPattern.authorRegex, "")
}
