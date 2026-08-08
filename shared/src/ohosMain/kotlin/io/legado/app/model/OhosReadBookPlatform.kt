package io.legado.app.model

import io.legado.app.help.image.DecodedBitmapCache
import io.legado.app.help.tts.OhosReadAloudHost
import io.legado.app.model.fileBook.TextFile

/**
 * 鸿蒙端 [ReadBookPlatform]: 朗读接 [OhosReadAloudHost] (ReadAloudControllerShared + 系统 TTS),
 * 缓存运行态暂缺, 缓存清理走真实实现。
 *
 * 对照 app 端 `AndroidReadBookPlatform` / desktop 端 `DesktopReadBookPlatform`:
 * 鸿蒙无前台 Service, 朗读由 [OhosReadAloudHost] 驱动 ReadAloudControllerShared
 * (系统 TTS 引擎 = OhosSystemTtsEngine, 经 @ohos.textToSpeech napi 桥)。
 *
 * 未注册时 [ReadBookPlatforms.get] 返回空默认实现, 行为与本实现一致 (仅缺 TextFile 清理)。
 */
private object OhosReadBookPlatform : ReadBookPlatform {

    // 朗读: 桥接到 OhosReadAloudHost (对照 desktop DesktopReadBookPlatform 接 DesktopReadAloudHost)
    override val isReadAloudRun: Boolean get() = OhosReadAloudHost.isRun

    override val isReadAloudPause: Boolean get() = OhosReadAloudHost.isPause

    override fun playReadAloud(play: Boolean, startPos: Int) {
        OhosReadAloudHost.play(play, startPos)
    }

    override fun pauseReadAloud() {
        OhosReadAloudHost.pause()
    }

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
