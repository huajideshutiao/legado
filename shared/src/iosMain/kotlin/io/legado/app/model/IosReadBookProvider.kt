package io.legado.app.model

/**
 * iOS 端 [ReadBookProvider]: 无 app.ReadBook 单例底座, 直接持有 [ReadBookShared] 实例。
 *
 * 范式同 jvmMain [DesktopReadBookProvider], 由 [io.legado.app.MainViewController]
 * 用 `CompositionLocalProvider(LocalReadBookProvider provides IosReadBookProvider())` 注入。
 */
class IosReadBookProvider : ReadBookProvider {
    override val readBook: ReadBookShared = ReadBookShared()
}
