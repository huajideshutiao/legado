package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppCacheManager
import io.legado.app.help.config.SourceConfig

/**
 * nativeMain: [SourceHelpAccessor] 的 iOS / 鸿蒙 两端共用实现。
 *
 * 供 shared commonMain 中下沉的 SourceHelp.saveSource / deleteBookSource /
 * deleteBookSourcesByKeys 通过 [SourceHelpAccessors] 间接调用, 避免未注册时
 * `SourceHelpAccessors.get()` 抛 IllegalStateException 导致书源删除流程被
 * runCatching 静默吞掉、副作用不执行。
 *
 * 两端实现逻辑完全一致 (仅类名 Ios/Ohos 前缀与注释平台描述不同), 下沉到 nativeMain 共用。
 *
 * # 与桌面端 [io.legado.desktop.help.source.DesktopSourceHelpAccessor] 区别
 * - app 端 `getCachedReadingBookSource` 走 `ReadBook.bookSource` 单例缓存 (内存中当前阅读书源)
 * - app 端 `getCachedAudioBookSource` 走 `AudioPlay.bookSource` 单例缓存 (内存中当前播放书源)
 * - `onBookSourceDeleted` / `onBookSourcesDeleted` 走 `SourceConfig.removeSource(s)`
 *   + `AppCacheManager.clearSourceVariables()` 清缓存副作用
 *   (SourceConfig / AppCacheManager 已下沉 commonMain, iOS/鸿蒙端可直接调用, 行为与 app 端等价;
 *   仅 ReadBook / AudioPlay 内存缓存快捷路径 iOS/鸿蒙端无对应单例, 留 null)
 *
 * # 后续扩展
 * 待 ReadBook / AudioPlay 在 iOS/鸿蒙端单例建立后, 在此补内存缓存读取。
 *
 * 注册入口 (iOS/鸿蒙共用): [registerNativeSourceHelpAccessor]
 *
 * 模式参考 desktop `DesktopSourceHelpAccessor` 实现。
 */
class NativeSourceHelpAccessor : SourceHelpAccessor {

    /**
     * iOS/鸿蒙端无 ReadBook 单例缓存 (Android 专属), 始终返回 null。
     *
     * 调用方 [io.legado.app.help.source.SourceHelp.getSource] 收到 null 后会回退到
     * `bookSourceDao.getBookSource(key)` 按 key 查询数据库, 行为正确,
     * 仅失去内存缓存快捷路径 (iOS/鸿蒙端可接受)。
     */
    override fun getCachedReadingBookSource(key: String?): BookSource? = null

    /**
     * iOS/鸿蒙端无 AudioPlay 单例缓存 (Android 专属), 始终返回 null。
     *
     * 同 [getCachedReadingBookSource], 调用方回退到 bookSourceDao 查询。
     */
    override fun getCachedAudioBookSource(key: String?): BookSource? = null

    /**
     * 对应原 app 端 `SourceConfig.removeSource(key)` + `AppCacheManager.clearSourceVariables()`。
     *
     * SourceConfig 已下沉 commonMain (走 PreferenceProviders, iOS 端 NSUserDefaults / 鸿蒙端文件持久化),
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

/**
 * 注册 [NativeSourceHelpAccessor] 到 [SourceHelpAccessors] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [PreferenceProviders] 已注册 (SourceConfig 走 PreferenceProviders.get())。
 */
fun registerNativeSourceHelpAccessor() {
    SourceHelpAccessors.register(NativeSourceHelpAccessor())
}
