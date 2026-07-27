package io.legado.app.web.utils

import android.content.Context

/**
 * [WebAssetSource] 的 Android actual 实现。
 *
 * 直接用 [android.content.res.AssetManager] 读 `assets/composeResources/files/web/` 下的资源
 * (Compose Multiplatform 插件自动把 `commonMain/composeResources/` 打包到 Android assets/composeResources/)。
 * 与 iOS/鸿蒙 NativeWebAssetSource / 桌面 ClasspathWebAssetSource 行为一致 (单一数据源)。
 *
 * Context 通过 [registerAndroidWebAssetSource] 注入 (shared androidMain 不依赖 splitties)。
 */
class AndroidWebAssetSource(private val context: Context) : WebAssetSource {

    override suspend fun read(path: String): ByteArray =
        context.assets.open("composeResources/files/$path").use { it.readBytes() }
}

/**
 * 安卓宿主启动早期注册 [WebAssetSource] 的 actual 实现。
 *
 * @param ctx 任意 Context (推荐传 `appCtx`), 内部只用其 applicationContext
 */
fun registerAndroidWebAssetSource(ctx: Context) {
    WebAssetSources.register(AndroidWebAssetSource(ctx.applicationContext))
}
