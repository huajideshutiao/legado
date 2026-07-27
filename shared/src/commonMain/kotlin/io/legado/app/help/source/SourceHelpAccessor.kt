package io.legado.app.help.source

import io.legado.app.data.entities.BookSource

/**
 * SourceHelp 跨平台访问 app 端单例的 provider 接口。
 *
 * 原 app 端 SourceHelp 中 `getSource(key)` 走 ReadBook.bookSource / AudioPlay.bookSource
 * 作为缓存快捷路径; `deleteBookSource*` 走 SourceConfig.removeSource(s) + AppCacheManager
 * .clearSourceVariables() 做 app 端副作用清理。这些单例本身依赖 app 平台绑定
 * (LiveData / SharedPreferences / appCtx), 留 app 端; 本接口把它们抽象为两个钩子,
 * 由 app 端 SourceHelpAccessorImpl 在 App.onCreate 经 [SourceHelpAccessors.register] 注册。
 *
 * 模式参考 AppDbProviders / AppConfigProviders。
 */
interface SourceHelpAccessor {

    /**
     * 当前阅读书籍对应书源 (对应 ReadBook.bookSource)。
     * @param key 书源 URL, 与缓存的书源 URL 匹配则返回缓存实例, 否则 null。
     */
    fun getCachedReadingBookSource(key: String?): BookSource?

    /**
     * 当前播放音频对应书源 (对应 AudioPlay.bookSource)。
     * @param key 书源 URL, 与缓存的书源 URL 匹配则返回缓存实例, 否则 null。
     */
    fun getCachedAudioBookSource(key: String?): BookSource?

    /**
     * 单源删除后的 app 端副作用 (对应 SourceConfig.removeSource(key)
     * + AppCacheManager.clearSourceVariables())。
     */
    fun onBookSourceDeleted(key: String)

    /**
     * 多源删除后的 app 端副作用 (对应 SourceConfig.removeSources(keys)
     * + AppCacheManager.clearSourceVariables())。
     */
    fun onBookSourcesDeleted(keys: List<String>)
}

/**
 * SourceHelp provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `SourceHelpAccessors.get().onBookSourceDeleted(...)` 替代
 * 原 `SourceConfig.removeSource(...)` + `AppCacheManager.clearSourceVariables()` 组合,
 * 行为完全一致, 仅多一层 provider 间接。
 */
object SourceHelpAccessors {
    @Volatile
    private var impl: SourceHelpAccessor? = null

    /** 宿主启动早期注册一次(任何 SourceHelp 调用之前)。 */
    fun register(impl: SourceHelpAccessor) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): SourceHelpAccessor = impl ?: error("SourceHelpAccessors not registered")
}
