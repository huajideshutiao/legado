package io.legado.app.help

import kotlin.concurrent.Volatile

/**
 * DefaultData 默认数据资源读取抽象 (shared commonMain)。
 *
 * # 资源单一数据源
 *
 * 默认数据 JSON 唯一数据源在 `shared/src/commonMain/composeResources/files/defaultData/`,
 * 由 compose 资源插件分发到各端产物, 各端注册实现读取:
 *
 * - **Android 端** (app 模块): assets 内 `composeResources/legado.shared.generated.resources/files/defaultData/`
 *   (见 app 端 registerAndroidJsEngines 内注册)。
 * - **桌面 jvm 端** (desktop 模块): classpath 内同前缀路径 (DesktopDefaultDataResourceProvider)。
 * - **iOS/鸿蒙 native 端**: 生成类 `Res.readBytes` (NativeDefaultDataResourceProvider)。
 *
 * 模式参考 [io.legado.app.help.source.SourceCacheProvider] / [io.legado.app.data.AppDatabaseProvider]。
 */
interface DefaultDataResourceProvider {

    /**
     * 读取 defaultData 目录下的资源文件内容 (UTF-8 字符串)。
     *
     * @param name 文件名 (如 "httpTTS.json", "txtTocRule.json"), 不含目录前缀。
     *   实现内部负责拼接各端资源路径前缀。
     * @return 文件内容字符串。
     * @throws Exception 读取失败时抛出, 由 [io.legado.app.help.DefaultDataShared] 内
     *   runCatching / getOrThrow 处理。
     */
    fun readResource(name: String): String
}

/**
 * [DefaultDataResourceProvider] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 模式参考 [io.legado.app.data.AppDatabaseProviders] / [io.legado.app.help.config.AppConfigProviders]。
 */
object DefaultDataResourceProviders {

    @Volatile
    private var impl: DefaultDataResourceProvider? = null

    /** 宿主启动早期注册一次 (任何 DefaultDataShared 属性访问之前)。 */
    fun register(impl: DefaultDataResourceProvider) {
        this.impl = impl
    }

    /**
     * 获取已注册实现, 未注册抛出 [IllegalStateException]。
     *
     * 调用方 (如 [io.legado.app.help.DefaultDataShared]) 应在 lazy 属性内调用,
     * 确保在宿主注册之后才访问。
     */
    fun get(): DefaultDataResourceProvider =
        impl ?: error("DefaultDataResourceProviders not registered")
}
