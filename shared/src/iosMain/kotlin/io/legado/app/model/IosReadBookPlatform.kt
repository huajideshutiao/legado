package io.legado.app.model

import io.legado.app.help.image.DecodedBitmapCache
import io.legado.app.help.image.iosCoilImageLoader
import io.legado.app.help.service.IosBackgroundTasks
import io.legado.app.model.fileBook.TextFile

/**
 * iOS 端 [ReadBookPlatform]: 图片/分章缓存走真实实现, 朗读与缓存服务运行态暂缺。
 *
 * 对照 app 端 `AndroidReadBookPlatform`; iOS 无前台 Service, 朗读钩子留待
 * [io.legado.app.service.ReadAloudControllerShared] 接入阅读页后补齐。
 */
private object IosReadBookPlatform : ReadBookPlatform {

    // TODO: 接入 ReadAloudControllerShared 后返回真实朗读状态 (当前恒非运行态)
    override val isReadAloudRun: Boolean get() = false

    override val isReadAloudPause: Boolean get() = true

    // TODO: 接入 ReadAloudControllerShared.start/resume
    override fun playReadAloud(play: Boolean, startPos: Int) = Unit

    // TODO: 接入 ReadAloudControllerShared.pause
    override fun pauseReadAloud() = Unit

    // iOS 无 CacheBookService, 运行态取 IosBackgroundTasks 的调度/后台任务标记
    override val isCacheBookServiceRun: Boolean get() = IosBackgroundTasks.isCacheBookRunning

    // 对照 app 端 ImageProvider.clear(): 释放 Coil3 内存缓存 (磁盘缓存保留) +
    // 解码位图进程级 LRU (PhotoDialog/阅读背景等, I1)
    override fun clearImageCache() {
        runCatching { iosCoilImageLoader.memoryCache?.clear() }
        DecodedBitmapCache.clear()
    }

    override fun clearTextFileCache() {
        runCatching { TextFile.clear() }
    }
}

/** 宿主启动早期注册一次 (任何阅读页进入之前)。 */
fun registerIosReadBookPlatform() {
    ReadBookPlatforms.register(IosReadBookPlatform)
}
