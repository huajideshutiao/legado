package io.legado.app.help

import kotlin.concurrent.Volatile

/**
 * 打包资源字节读取抽象 (commonMain) —— 注入原语。
 *
 * # 资源单一数据源
 *
 * 随包资源唯一数据源在 `ui/src/commonMain/composeResources/` (TVBox 引导脚本在该目录的
 * `files/tvbox/` 下), 由 compose 资源插件分发到各端产物。读取实现必须由资源模块 (:ui) 提供:
 * 打包前缀由资源生成器写进 `Res`, 只有资源模块持有它 —— 换模块名/切分产物都不需要改读取点,
 * 数据层与宿主核心也就不必持有该前缀。
 *
 * 消费方: [io.legado.app.help.tvbox.readHostAsset] (TVBox JS 引导脚本)。
 *
 * 模式参考 [DefaultDataResourceProvider] / [io.legado.app.help.source.SourceCacheProvider]。
 */
interface PackagedResourceBytesProvider {

    /**
     * 读取打包资源 [path] 的全部字节。
     *
     * @param path 相对 composeResources `files` 目录的路径 (如 "tvbox/TvBoxJsApi.js")。
     * @return 资源字节。
     * @throws Exception 读取失败时抛出, 由调用方转成明确的装载错误。
     */
    fun readBytes(path: String): ByteArray
}

/**
 * [PackagedResourceBytesProvider] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (Android=App.onCreate / 桌面与无头=DesktopCore 阶段1), 早于任何 TVBox
 * 引导脚本读取。未注册时调用 [get] 抛 [IllegalStateException] (不静默返回空, 注册遗漏立即暴露)。
 *
 * 模式参考 [DefaultDataResourceProviders] / [io.legado.app.help.tvbox.TvBoxPlatforms]。
 */
object PackagedResourceBytesProviders {

    @Volatile
    private var impl: PackagedResourceBytesProvider? = null

    /** 宿主启动早期注册一次 (任何 [get] 调用之前)。 */
    fun register(impl: PackagedResourceBytesProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 [IllegalStateException]。 */
    fun get(): PackagedResourceBytesProvider =
        impl ?: error("PackagedResourceBytesProviders not registered; 当前平台未接入打包资源读取")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
