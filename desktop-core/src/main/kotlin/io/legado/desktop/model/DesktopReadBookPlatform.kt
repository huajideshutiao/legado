package io.legado.desktop.model

import io.legado.app.help.image.DecodedBitmapCache
import io.legado.app.model.ReadBookPlatform
import io.legado.app.model.ReadBookPlatforms
import io.legado.app.model.fileBook.TextFile
import io.legado.desktop.help.tts.DesktopReadAloudHost

/**
 * 桌面端 [ReadBookPlatform]: 供 shared [io.legado.app.model.ReadBookShared] 回调平台副作用。
 *
 * 对照 app 端 `AndroidReadBookPlatform`, 桌面端接朗读宿主 / 缓存运行态 / 本地 txt 分章缓存,
 * 图片缓存只清解码位图 LRU 不清 Coil 封面缓存 (原因见 override 注释)。
 */
object DesktopReadBookPlatform : ReadBookPlatform {

    /**
     * 桌面端章节预下载走协程 (无 Android Service), 用 [DesktopCacheBook.isRun] 表达运行态;
     * 退出阅读时若仍有下载在跑, ReadBookShared 就不会 close 掉 CacheBookShared。
     */
    override val isCacheBookServiceRun: Boolean get() = DesktopCacheBook.isRun

    /** 本地 txt 分章缓存 (jvmAndAndroid 共用 TextFile 单例), 换书时释放。 */
    override fun clearTextFileCache() {
        TextFile.clear()
    }

    // 朗读: 桥接到 DesktopReadAloudHost (ReadAloudControllerShared + 桌面 TTS 引擎),
    // 对照 app 端 AndroidReadBookPlatform 的 BaseReadAloudService / ReadAloud 门面。
    override val isReadAloudRun: Boolean get() = DesktopReadAloudHost.isRun

    override val isReadAloudPause: Boolean get() = DesktopReadAloudHost.isPause

    override fun playReadAloud(play: Boolean, startPos: Int) {
        DesktopReadAloudHost.play(play, startPos)
    }

    override fun pauseReadAloud() {
        DesktopReadAloudHost.pause()
    }

    // clearImageCache: Coil 单例内存缓存与书架封面共用, 退出阅读时清会误伤封面, 保持不清;
    // 解码位图进程级 LRU 主表 (DecodedBitmapCache: 大图查看/阅读背景/样式预览), 退出阅读时清空
    // (I1, 与 iOS/鸿蒙端对齐)。封面小表属封面链, [DecodedBitmapCache.clearDecoded] 不碰它。
    override fun clearImageCache() {
        DecodedBitmapCache.clearDecoded()
    }
}

/** 桌面宿主启动早期注册一次 (任何阅读页打开之前)。 */
fun registerDesktopReadBookPlatform() {
    ReadBookPlatforms.register(DesktopReadBookPlatform)
}
