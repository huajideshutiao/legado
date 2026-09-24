package io.legado.app.web.utils

import legado.ui.generated.resources.Res

/**
 * [WebAssetSource] 的四端共用实现。
 *
 * 经 composeResources 生成的 `Res.readBytes` 取数: 打包前缀由资源生成器写进 `Res`
 * (形如 `composeResources/legado.ui.generated.resources/`), 源码侧不持有该前缀,
 * 模块切分/改名后无需改动任何读取点。
 *
 * # 单一数据源
 * web 资源唯一数据源在 `ui/src/commonMain/composeResources/files/web/`, 无任何平台端副本。
 * 返回 [ByteArray] (资源都很小, 几 KB 到几百 KB), 避开 commonMain 无 java.io.InputStream 的问题。
 */
class ComposeResourceWebAssetSource : WebAssetSource {

    override suspend fun read(path: String): ByteArray = Res.readBytes("files/$path")
}

/**
 * 四端宿主启动早期注册 [WebAssetSource] (任何 [AssetsWeb] 调用之前)。
 *
 * 调用点: App.onCreate (安卓) / DesktopCore.registerSecondaryCoreProviders (桌面与无头) /
 * IosProviderRegistry / OhosProviderRegistry。
 *
 * 模式参考 [io.legado.app.help.service.ServiceLaunchers]。
 */
fun registerComposeWebAssetSource() {
    WebAssetSources.register(ComposeResourceWebAssetSource())
}
