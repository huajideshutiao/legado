package io.legado.app.model

/**
 * Android 端 [ReadBookProvider] 实现 (P0-2/3)。
 *
 * 包装 app 端 [ReadBook] 单例持有的 [ReadBookShared] 实例 ([ReadBook.shared]),
 * 供 Compose 入口通过 [io.legado.app.model.LocalReadBookProvider] 注入到共享 UI。
 *
 * # 设计
 * - 编排逻辑已下沉 [ReadBookShared], `ReadBook.shared` 即状态源真, app 端 `ReadBook` 只是转发壳
 * - 本类仅作 Compose 注入适配, 不持有额外状态, `readBook` 直接取 `ReadBook.shared`
 * - 桌面端对应 `DesktopReadBookProvider` 直接 `new ReadBookShared()`, 与本类对称
 *
 * 调用方在 Compose 入口注入:
 * ```kotlin
 * CompositionLocalProvider(LocalReadBookProvider provides AndroidReadBookProvider()) {
 *     Content()
 * }
 * ```
 *
 * 模式参考 app 端 `AndroidThemeStoreProvider`。
 */
class AndroidReadBookProvider : ReadBookProvider {
    override val readBook: ReadBookShared get() = ReadBook.shared
}
