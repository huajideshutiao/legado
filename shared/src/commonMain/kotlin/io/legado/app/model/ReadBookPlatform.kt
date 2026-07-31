package io.legado.app.model

import io.legado.app.model.ReadBookPlatforms.get
import kotlin.concurrent.Volatile

/**
 * [ReadBookShared] 的平台能力钩子。
 *
 * app 端 `ReadBook` 里少数几处真平台绑定 (朗读服务 / 缓存服务运行态 / 图片缓存 /
 * 本地 txt 分章缓存) 无法下沉 commonMain, 经本接口注入。模式参考 [CacheBookCallback]。
 *
 * 默认实现全部为空/false: 未注册平台实现时, 阅读编排照常运行, 仅跳过这些副作用。
 */
interface ReadBookPlatform {

    /** 朗读服务是否运行中 (app: `BaseReadAloudService.isRun`) */
    val isReadAloudRun: Boolean get() = false

    /** 朗读是否处于暂停态 (app: `BaseReadAloudService.pause`) */
    val isReadAloudPause: Boolean get() = true

    /** 开始/继续朗读 (app: `ReadAloud.play(appCtx, play, startPos)`) */
    fun playReadAloud(play: Boolean, startPos: Int) {}

    /** 暂停朗读 (app: `ReadAloud.pause(appCtx)`) */
    fun pauseReadAloud() {}

    /** 缓存书籍的前台服务是否运行中 (app: `CacheBookService.isRun`) */
    val isCacheBookServiceRun: Boolean get() = false

    /** 释放图片缓存 (app: `ImageProvider.clear()`) */
    fun clearImageCache() {}

    /** 释放本地 txt 分章缓存 (app: `TextFile.clear()`) */
    fun clearTextFileCache() {}
}

/**
 * [ReadBookPlatform] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), 未注册时 [get] 返回空实现。
 */
object ReadBookPlatforms {

    private object Default : ReadBookPlatform

    @Volatile
    private var impl: ReadBookPlatform? = null

    fun register(impl: ReadBookPlatform) {
        this.impl = impl
    }

    fun get(): ReadBookPlatform = impl ?: Default
}
