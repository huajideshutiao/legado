package io.legado.desktop.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppCacheManager
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.source.SourceHelpAccessor

/**
 * 桌面端 [SourceHelpAccessor] 实现, 供 shared commonMain 中下沉的
 * SourceHelp.saveSource / deleteBookSource / deleteBookSourcesByKeys 通过
 * [io.legado.app.help.source.SourceHelpAccessors] 间接调用, 避免未注册时
 * `SourceHelpAccessors.get()` 抛 IllegalStateException 导致书源删除流程被
 * runCatching 静默吞掉、副作用不执行。
 *
 * # 注册时机
 * desktop `main()` 中在 `AppDbProviders.register(DesktopAppDbAccessor())` 之后注册
 * (本文件依赖 SourceConfig + AppCacheManager, 两者已下沉 commonMain;
 * 顺序紧跟 AppDbAccessor 便于维护), 见 [io.legado.desktop.Main]。
 *
 * # 与 app 端 [io.legado.app.help.source.SourceHelpAccessorImpl] 区别
 * - app 端 `getCachedReadingBookSource` 走 `ReadBook.bookSource` 单例缓存 (内存中当前阅读书源)
 * - app 端 `getCachedAudioBookSource` 走 `AudioPlay.bookSource` 单例缓存 (内存中当前播放书源)
 * - `onBookSourceDeleted` / `onBookSourcesDeleted` 走 `SourceConfig.removeSource(s)`
 *   + `AppCacheManager.clearSourceVariables()` 清缓存副作用
 *   (SourceConfig / AppCacheManager 已下沉 commonMain, 桌面端可直接调用, 行为与 app 端等价;
 *   仅 ReadBook / AudioPlay 内存缓存快捷路径桌面端无对应单例, 留 null)
 *
 * # 后续扩展
 * 待 ReadBook / AudioPlay 桌面端单例建立后, 在此补内存缓存读取。
 *
 * 模式参考 app 端 SourceHelpAccessorImpl 的实现段。
 */
class DesktopSourceHelpAccessor : SourceHelpAccessor {

    /**
     * 桌面端无 ReadBook 单例缓存 (Android 专属), 始终返回 null。
     *
     * 调用方 [io.legado.app.help.source.SourceHelp.getSource] 收到 null 后会回退到
     * `bookSourceDao.getBookSource(key)` 按 key 查询数据库, 行为正确,
     * 仅失去内存缓存快捷路径 (桌面端可接受)。
     */
    override fun getCachedReadingBookSource(key: String?): BookSource? = null

    /**
     * 桌面端无 AudioPlay 单例缓存 (Android 专属), 始终返回 null。
     *
     * 同 [getCachedReadingBookSource], 调用方回退到 bookSourceDao 查询。
     */
    override fun getCachedAudioBookSource(key: String?): BookSource? = null

    /**
     * 对应原 app 端 `SourceConfig.removeSource(key)` + `AppCacheManager.clearSourceVariables()`。
     *
     * SourceConfig 已下沉 commonMain (走 PreferenceProviders, 桌面端 java.util.prefs),
     * AppCacheManager 已下沉 commonMain (内存 LruCache), 直接调用, 行为与 app 端等价。
     */
    override fun onBookSourceDeleted(key: String) {
        SourceConfig.removeSource(key)
        AppCacheManager.clearSourceVariables()
    }

    /**
     * 对应原 app 端 `SourceConfig.removeSources(keys)` + `AppCacheManager.clearSourceVariables()`。
     *
     * 同 [onBookSourceDeleted], 批量版本。
     */
    override fun onBookSourcesDeleted(keys: List<String>) {
        SourceConfig.removeSources(keys)
        AppCacheManager.clearSourceVariables()
    }
}
