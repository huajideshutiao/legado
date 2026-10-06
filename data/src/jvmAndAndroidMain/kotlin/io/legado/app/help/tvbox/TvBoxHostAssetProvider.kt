package io.legado.app.help.tvbox

import kotlin.concurrent.Volatile

/**
 * TVBox JS 宿主引导脚本读取抽象 (jvmAndAndroidMain; TVBox 子系统仅 Android/桌面)。
 *
 * # 资源单一数据源
 *
 * 引导脚本 (TvBoxJsModuleLoader.js / TvBoxJsApi.js / TvBoxDrpyParser.js) 唯一数据源在
 * `ui/src/commonMain/composeResources/files/tvbox/`, 由 compose 资源插件分发到各端产物,
 * 两端注册同一实现读取:
 *
 * - :ui 的 ComposeResourceTvBoxAssetProvider (经 composeResources 生成的 `Res.readBytes`
 *   取数, 打包前缀由资源生成器写进 `Res`, 源码侧不持有该前缀)。
 *
 * 模式参考 [io.legado.app.help.DefaultDataResourceProviders]。
 */
interface TvBoxHostAssetProvider {

    /**
     * 读取引导脚本内容 (UTF-8 字符串)。
     *
     * @param path 相对 composeResources files 根的路径 (如 "tvbox/TvBoxJsApi.js";
     *   宿主未随包的生态依赖库如 "js/lib/cheerio.min.js" 同样走此路径, 由调用方
     *   捕获异常后按需下载, 见 TvBoxJsSpider.fetchAsset)。
     * @return 文件内容字符串。
     * @throws Exception 读取失败时抛出, 由 [readHostAsset] 转成明确的装载错误。
     */
    fun read(path: String): String
}

/**
 * [TvBoxHostAssetProvider] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (Android=App.onCreate / 桌面=registerDesktopTvBoxProviders),
 * 早于任何 TVBoxManager/装载器使用。未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 模式参考 [io.legado.app.help.DefaultDataResourceProviders]。
 */
object TvBoxHostAssetProviders {

    @Volatile
    private var impl: TvBoxHostAssetProvider? = null

    /** 宿主启动早期注册一次 (任何 [readHostAsset] 调用之前)。 */
    fun register(impl: TvBoxHostAssetProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): TvBoxHostAssetProvider =
        impl ?: error("TvBoxHostAssetProviders not registered; 当前平台未接入 TVBox 引导脚本读取")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
