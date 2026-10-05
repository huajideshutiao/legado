package io.legado.app.model.manga

import android.content.Context

/**
 * [io.legado.app.ui.book.manga.extension.MangaExtensionService] 安卓注册壳:
 * 状态组装与操作面已下沉 ui 层 [SharedMangaExtensionPlatform] (JVM+Android 共用),
 * 插件自带配置桥亦为共享实现 [SharedMangaSourceConfig], 本类只是 MainActivity 的注册入口。
 */
class AndroidMangaExtensionPlatform(
    context: Context,
) : SharedMangaExtensionPlatform(SharedMangaSourceConfig(context.applicationContext))
