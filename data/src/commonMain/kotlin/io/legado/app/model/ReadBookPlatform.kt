package io.legado.app.model

import kotlin.concurrent.Volatile

/**
 * [ReadBookShared] 的平台能力钩子。
 *
 * app 端 `ReadBook` 里少数几处真平台绑定 (朗读服务 / 缓存服务运行态 / 图片缓存 /
 * 本地 txt 分章缓存) 无法下沉 commonMain, 经本接口注入。模式参考 [CacheBookCallback]。
 *
 * 成员一律不给默认实现: 新端漏接一项要在编译期报错, 不能靠默认值静默跑错行为。
 */
interface ReadBookPlatform {

    /** 朗读服务是否运行中 (app: `BaseReadAloudService.isRun`) */
    val isReadAloudRun: Boolean

    /** 朗读是否处于暂停态 (app: `BaseReadAloudService.pause`) */
    val isReadAloudPause: Boolean

    /** 开始/继续朗读 (app: `ReadAloud.play(appCtx, play, startPos)`) */
    fun playReadAloud(play: Boolean, startPos: Int)

    /** 暂停朗读 (app: `ReadAloud.pause(appCtx)`) */
    fun pauseReadAloud()

    /** 缓存书籍的前台服务是否运行中 (app: `CacheBookService.isRun`) */
    val isCacheBookServiceRun: Boolean

    /** 释放图片缓存 (app: `ImageProvider.clear()`) */
    fun clearImageCache()

    /** 释放本地 txt 分章缓存 (app: `TextFile.clear()`) */
    fun clearTextFileCache()
}

/**
 * [ReadBookPlatform] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main 阶段1 / registerIosProviders /
 * registerOhosProviders), 四端都严格早于任何阅读页入口。
 */
object ReadBookPlatforms {

    @Volatile
    private var impl: ReadBookPlatform? = null

    fun register(impl: ReadBookPlatform) {
        this.impl = impl
    }

    /** 取已注册实现, 未注册抛 IllegalStateException (与其余 Providers 一致)。 */
    fun get(): ReadBookPlatform = impl ?: error("ReadBookPlatforms not registered")
}
