package io.legado.app.web.utils

import android.content.Context

/** composeResources 打进 assets 的目录前缀 (含模块限定名, 由插件按模块生成)。 */
private const val ASSET_PREFIX = "composeResources/legado.shared.generated.resources/files/"

/**
 * [WebAssetSource] 的 Android actual 实现。
 *
 * 直接用 [android.content.res.AssetManager] 读 composeResources 打进 assets 的 `files/web/` 资源。
 * 插件产物带模块限定目录名 (legado.shared.generated.resources), 前缀不能省。
 * 与 iOS/鸿蒙 NativeWebAssetSource / 桌面 ClasspathWebAssetSource 行为一致 (单一数据源)。
 *
 * Context 通过 [registerAndroidWebAssetSource] 注入 (shared androidMain 不依赖 splitties)。
 */
class AndroidWebAssetSource(private val context: Context) : WebAssetSource {

    override suspend fun read(path: String): ByteArray =
        context.assets.open("$ASSET_PREFIX$path").use { it.readBytes() }
}

/**
 * 安卓宿主启动早期注册 [WebAssetSource] 的 actual 实现。
 *
 * @param ctx 任意 Context (推荐传 `appCtx`), 内部只用其 applicationContext
 */
fun registerAndroidWebAssetSource(ctx: Context) {
    WebAssetSources.register(AndroidWebAssetSource(ctx.applicationContext))
}
