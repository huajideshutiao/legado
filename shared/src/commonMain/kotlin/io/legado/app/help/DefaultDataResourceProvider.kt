package io.legado.app.help

/**
 * DefaultData 默认数据资源读取抽象 (shared commonMain)。
 *
 * # 背景
 *
 * app 端 [io.legado.app.help.DefaultData] 原通过 `appCtx.assets.open("defaultData/xxx.json")`
 * 读取 app/src/main/assets/defaultData/ 下的 JSON 文件。assets 读取依赖 Android Context,
 * 无法下沉 commonMain, 故抽象为接口, 由各端注册实现:
 *
 * - **Android 端** (app 模块): 用 `appCtx.assets.open("defaultData/$name")` 读取
 *   app/src/main/assets/defaultData/ 下的资源, 行为与原 DefaultData 完全一致。
 * - **桌面 jvm 端** (desktop 模块): 可从 classpath 读取
 *   `getResourceAsStream("/defaultData/$name")` (参考 shared/commonMain/resources 下
 *   LICENSE.md 在 desktop AboutScreen 的读取方式), 前提是资源已迁移到
 *   shared/src/commonMain/resources/defaultData/。
 *
 * # 资源单一数据源
 *
 * 当前资源保留在 app/src/main/assets/defaultData/ (Android 端通过 assets 读取)。
 * 后续如需迁移到 shared/src/commonMain/resources/defaultData/ 以实现跨端单一数据源,
 * 只需调整各端 [DefaultDataResourceProvider] 实现的读取方式 (Android 改用 classpath,
 * desktop 仍用 classpath), [io.legado.app.help.DefaultDataShared] 逻辑不变。
 *
 * 模式参考 [io.legado.app.help.source.SourceCacheProvider] / [io.legado.app.data.AppDatabaseProvider]。
 */
interface DefaultDataResourceProvider {

    /**
     * 读取 defaultData 目录下的资源文件内容 (UTF-8 字符串)。
     *
     * @param name 文件名 (如 "httpTTS.json", "txtTocRule.json"), 不含目录前缀。
     *   实现内部负责拼接 "defaultData/$name" 或对应的 classpath 路径。
     * @return 文件内容字符串。
     * @throws Exception 读取失败时抛出 (与原 `appCtx.assets.open` 行为一致,
     *   由 [io.legado.app.help.DefaultDataShared] 内 runCatching / getOrThrow 处理)。
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
