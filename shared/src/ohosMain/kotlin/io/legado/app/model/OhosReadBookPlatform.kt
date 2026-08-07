package io.legado.app.model

import io.legado.app.help.image.DecodedBitmapCache
import io.legado.app.model.fileBook.TextFile

/**
 * 鸿蒙端 [ReadBookPlatform]: 朗读与缓存服务运行态暂缺, 缓存清理走真实实现。
 *
 * 对照 app 端 `AndroidReadBookPlatform` / iOS 端 `IosReadBookPlatform`; 鸿蒙无前台
 * Service, 朗读钩子留待 [io.legado.app.service.ReadAloudControllerShared] 接入阅读页后补齐
 * (当前 OhosReaderPlatformProvider.clickReadAloud 同为 TODO 空实现)。
 *
 * 未注册时 [ReadBookPlatforms.get] 返回空默认实现, 行为与本实现一致 (仅缺 TextFile 清理),
 * 注册本实现保证与 iOS 端形态对齐, 并为后续朗读/缓存运行态接线提供挂点。
 */
private object OhosReadBookPlatform : ReadBookPlatform {

    // TODO: 接入 ReadAloudControllerShared 后返回真实朗读状态 (当前恒非运行态, 同 iOS)
    override val isReadAloudRun: Boolean get() = false

    override val isReadAloudPause: Boolean get() = true

    // TODO: 接入 ReadAloudControllerShared.start/resume
    override fun playReadAloud(play: Boolean, startPos: Int) = Unit

    // TODO: 接入 ReadAloudControllerShared.pause
    override fun pauseReadAloud() = Unit

    // 鸿蒙无前台 CacheBookService; 缓存 job 由 NativeServiceLauncher 进程内管理, 无运行态标志
    // (对照 iOS IosBackgroundTasks.isCacheBookRunning; 待 ServiceLauncher 补 isRun 后接真值)
    override val isCacheBookServiceRun: Boolean get() = false

    // 鸿蒙图片加载无 Coil 内存缓存, 但 ImageBitmapLoader 解码结果进进程级
    // DecodedBitmapCache (大图查看/阅读背景等), 退出阅读时一并清空 (I1)
    override fun clearImageCache() {
        DecodedBitmapCache.clear()
    }

    override fun clearTextFileCache() {
        runCatching { TextFile.clear() }
    }
}

/** 宿主启动早期注册一次 (任何阅读页进入之前)。 */
fun registerOhosReadBookPlatform() {
    ReadBookPlatforms.register(OhosReadBookPlatform)
}
