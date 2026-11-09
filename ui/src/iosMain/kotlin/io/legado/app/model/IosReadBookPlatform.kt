package io.legado.app.model

import io.legado.app.help.image.DecodedBitmapCache
import io.legado.app.help.image.coverReuseStoreForWrite
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

    override suspend fun awaitReadAloudStopped() = IosReadAloudHost.awaitStopped()

    override val isReadAloudPause: Boolean get() = IosReadAloudHost.isPause

    override fun playReadAloud(play: Boolean, startPos: Int) {
        IosReadAloudHost.play(play, startPos)
    }

    override fun pauseReadAloud() {
        IosReadAloudHost.pause()
    }

    // iOS 无 CacheBookService, 运行态取 IosBackgroundTasks 的调度/后台任务标记
    override val isCacheBookServiceRun: Boolean get() = IosBackgroundTasks.isCacheBookRunning

    // 对照 app 端 ImageProvider.clear(): 释放内存里的普通图片 (磁盘缓存保留) +
    // 解码位图进程级 LRU 主表 (PhotoDialog/阅读背景等, I1)。
    // 已登记的封面用途条目跨页面存活, 清了会让书架/详情首帧真封面失效。
    override fun clearImageCache() {
        // 普通图片清理要覆盖"封面登记尚未建立"的情形: 这里按现库正常取得路径执行,
        // 与原先直接访问该内存缓存同一依赖; 已登记的封面用途条目保留。
        coverReuseStoreForWrite().clearNonCoverEntries()
        DecodedBitmapCache.clearDecoded()
    }

    override fun clearTextFileCache() {
        TextFile.clear()
    }
}

/** 宿主启动早期注册一次 (任何阅读页进入之前)。 */
fun registerIosReadBookPlatform() {
    ReadBookPlatforms.register(IosReadBookPlatform)
}
