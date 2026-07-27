package io.legado.app.model

/**
 * 桌面 JVM 端 [ReadBookProvider] 实现。
 *
 * 与 `ui.compose.platform.DesktopThemeStoreProvider` / `DesktopAppConfigProvider` 同范式:
 * 桌面端无 app.ReadBook 单例底座, 直接 new [ReadBookShared] 实例, 由 Compose 入口
 * 用 `CompositionLocalProvider(LocalReadBookProvider provides DesktopReadBookProvider())` 注入。
 *
 * 桌面端的 chapterSize/BookSource/ContentProcessor 等 actual 逻辑后续按需扩展
 * (通过 BookHelpAccessor / AppDbProviders / AppConfigProviders 等 shared/commonMain
 * 已下沉的 accessor 实现, 不依赖 app 模块)。
 */
class DesktopReadBookProvider : ReadBookProvider {
    override val readBook: ReadBookShared = ReadBookShared()
}
