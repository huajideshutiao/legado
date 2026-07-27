package io.legado.app.web.utils

import android.content.Context

/**
 * [WebAssetSource] 的 Android actual 实现。
 *
 * 用 Android [Context.assets] 读 `app/src/main/assets/web/` 下的静态资源,
 * 对齐原 app 端 [AssetsWeb] 用 `appCtx.assets.open(path)` 的行为。
 * 返回 ByteArray (原 InputStream, 下沉 commonMain 后接口统一)。
 *
 * # 单一数据源说明
 * 资源本体存 `shared/src/jvmMain/resources/web/` (jvmMain resources 经 shared jar 暴露);
 * app 端 `src/main/assets/web/` 为 Android AssetManager 兼容副本 (KMP commonMain resources
 * 不自动路由到 Android assets, 详见 AssetsWeb.kt 注释)。Android 端读 assets 副本,
 * 桌面端读 classpath, iOS/鸿蒙端读 composeResources, 资源内容保持一致。
 *
 * @param context 任意 Context (推荐 appCtx), 用于 assets
 *
 * 模式参考 `registerAndroidServiceLauncher`。
 */
class AndroidWebAssetSource(
    private val context: Context,
) : WebAssetSource {

    override suspend fun read(path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }
}

/**
 * 安卓宿主启动早期注册 [WebAssetSource] 的 actual 实现。
 *
 * @param context 任意 Context (推荐 appCtx), 用于 assets
 *
 * 模式参考 `registerAndroidServiceLauncher`。
 */
fun registerAndroidWebAssetSource(context: Context) {
    WebAssetSources.register(AndroidWebAssetSource(context.applicationContext))
}
