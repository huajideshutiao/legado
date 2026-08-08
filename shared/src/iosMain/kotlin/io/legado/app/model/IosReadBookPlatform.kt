package io.legado.app.model

import io.legado.app.help.image.DecodedBitmapCache
import io.legado.app.help.image.iosCoilImageLoader
import io.legado.app.help.service.IosBackgroundTasks
import io.legado.app.help.tts.IosReadAloudHost
import io.legado.app.model.fileBook.TextFile

/**
 * iOS 端 [ReadBookPlatform]: 图片/分章缓存走真实实现, 朗读桥接
 * [IosReadAloudHost] (ReadAloudControllerShared + AVSpeechSynthesizer/AVPlayer)。
 *
 * 对照 app 端 `AndroidReadBookPlatform`; iOS 无前台 Service, 朗读在进程内编排,
 * 语义对齐 app 端 `BaseReadAloudService.isRun/pause` + `ReadAloud.play/pause`。
 */
private object IosReadBookPlatform : ReadBookPlatform {

    override val isReadAloudRun: Boolean get() = IosReadAloudHost.isRun

    override val isReadAloudPause: Boolean get() = IosReadAloudHost.isPause

    override fun playReadAloud(play: Boolean, startPos: Int) {
        IosReadAloudHost.play(play, startPos)
    }

    override fun pauseReadAloud() {
        IosReadAloudHost.pause()
    }

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
