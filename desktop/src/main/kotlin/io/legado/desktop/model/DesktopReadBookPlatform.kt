package io.legado.desktop.model

import io.legado.app.model.ReadBookPlatform
import io.legado.app.model.ReadBookPlatforms
import io.legado.app.model.fileBook.TextFile

/**
 * 桌面端 [ReadBookPlatform]: 供 shared [io.legado.app.model.ReadBookShared] 回调平台副作用。
 *
 * 对照 app 端 `AndroidReadBookPlatform`, 桌面端只接缓存运行态与本地 txt 分章缓存,
 * 朗读/图片缓存两组保持接口默认空实现 (原因见各 override 注释)。
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

    // 朗读 (isReadAloudRun/isReadAloudPause/playReadAloud/pauseReadAloud) 用接口默认值:
    // 桌面端没有朗读服务宿主 (ReadAloudControllerShared 尚无接入点), 默认值等价于"未朗读",
    // 阅读编排照常跑, 只是翻页不联动朗读。
    // clearImageCache 同样保持空实现: 桌面端阅读内联图无独立位图缓存,
    // Coil 单例内存缓存与书架封面共用, 退出阅读时清会误伤封面。
}

/** 桌面宿主启动早期注册一次 (任何阅读页打开之前)。 */
fun registerDesktopReadBookPlatform() {
    ReadBookPlatforms.register(DesktopReadBookPlatform)
}
