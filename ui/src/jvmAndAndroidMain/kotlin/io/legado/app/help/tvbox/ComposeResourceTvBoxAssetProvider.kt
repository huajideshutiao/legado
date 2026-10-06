package io.legado.app.help.tvbox

import kotlinx.coroutines.runBlocking
import legado.ui.generated.resources.Res

/**
 * [TvBoxHostAssetProvider] 的 Android/桌面共用实现。
 *
 * 经 composeResources 生成的 `Res.readBytes` 取数: 打包前缀由资源生成器写进 `Res`
 * (形如 `composeResources/legado.ui.generated.resources/`), 源码侧不持有该前缀,
 * 模块切分/改名后无需改动任何读取点。
 *
 * 引导脚本唯一数据源在 `ui/src/commonMain/composeResources/files/tvbox/`。
 * [read] 为同步接口, [runBlocking] 包装 (调用方均在 IO 派发路径, 见 TvBoxManager.spiderFor)。
 */
class ComposeResourceTvBoxAssetProvider : TvBoxHostAssetProvider {

    override fun read(path: String): String =
        runBlocking { Res.readBytes("files/$path") }.decodeToString()
}

/**
 * 两端宿主启动早期注册一次 (任何 [readHostAsset] 调用之前)。
 *
 * 调用点: App.onCreate (安卓) / registerDesktopTvBoxProviders (桌面)。
 *
 * 模式参考 [io.legado.app.help.registerComposeDefaultDataResourceProvider]。
 */
fun registerComposeTvBoxAssetProvider() {
    TvBoxHostAssetProviders.register(ComposeResourceTvBoxAssetProvider())
}
