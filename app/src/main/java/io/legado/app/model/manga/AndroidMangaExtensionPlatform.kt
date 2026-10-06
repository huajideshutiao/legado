package io.legado.app.model.manga

import android.content.Context

/**
 * [io.legado.app.ui.book.manga.extension.MangaExtensionService] 安卓注册壳:
 * 状态组装与操作面已下沉 ui 层 [SharedMangaExtensionPlatform] (JVM+Android 共用),
 * 插件自带配置桥亦为共享实现 [SharedMangaSourceConfig]。
 *
 * 进程级单例: 平台实例自持状态流 scope 与 combine 收集器, Activity 重建时新建会让旧实例的
 * 收集器永不取消; 本类只持 applicationContext, 单例不产生泄漏。
 */
class AndroidMangaExtensionPlatform private constructor(
    context: Context,
) : SharedMangaExtensionPlatform(SharedMangaSourceConfig(context.applicationContext)) {

    companion object {
        @Volatile
        private var instance: AndroidMangaExtensionPlatform? = null

        fun get(context: Context): AndroidMangaExtensionPlatform =
            instance ?: synchronized(this) {
                instance ?: AndroidMangaExtensionPlatform(context.applicationContext)
                    .also { instance = it }
            }
    }
}
