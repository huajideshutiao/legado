package io.legado.app.model

import io.legado.app.App
import io.legado.app.model.fileBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService

/**
 * 注册 [ReadBookShared] 的 Android 平台出口 (朗读/缓存服务运行态 + 图片/本地 txt 缓存清理)。
 *
 * 宿主启动早期调用一次 (App.onCreate), 注册须早于任何 Activity。
 */
fun registerAndroidReadBookPlatform() {
    ReadBookPlatforms.register(AndroidReadBookPlatform)
}

/**
 * Android 平台出口实现：朗读服务 / 缓存服务 / 图片与本地 txt 缓存。
 */
private object AndroidReadBookPlatform : ReadBookPlatform {

    override val isReadAloudRun: Boolean get() = BaseReadAloudService.isRun

    override val isReadAloudPause: Boolean get() = BaseReadAloudService.pause

    override fun playReadAloud(play: Boolean, startPos: Int) {
        ReadAloud.play(App.instance, play, startPos = startPos)
    }

    override fun pauseReadAloud() {
        ReadAloud.pause(App.instance)
    }

    override val isCacheBookServiceRun: Boolean get() = CacheBookService.isRun

    override fun clearImageCache() {
        ImageProvider.clear()
    }

    override fun clearTextFileCache() {
        TextFile.clear()
    }
}
