package io.legado.app.help

import kotlinx.coroutines.runBlocking
import legado.ui.generated.resources.Res

/**
 * [DefaultDataResourceProvider] 的四端共用实现。
 *
 * 经 composeResources 生成的 `Res.readBytes` 取数: 打包前缀由资源生成器写进 `Res`
 * (形如 `composeResources/legado.ui.generated.resources/`), 源码侧不持有该前缀,
 * 模块切分/改名后无需改动任何读取点。
 *
 * 默认数据 JSON 唯一数据源在 `ui/src/commonMain/composeResources/files/defaultData/`。
 * [readResource] 为同步接口, [runBlocking] 包装 (调用方均在后台惰性初始化路径)。
 */
class ComposeResourceDefaultDataProvider : DefaultDataResourceProvider {

    override fun readResource(name: String): String =
        runBlocking { Res.readBytes("files/defaultData/$name") }.decodeToString()
}

/**
 * 四端宿主启动早期注册一次 (任何 [DefaultDataShared] 属性访问之前)。
 *
 * 调用点: App.onCreate 经 registerAndroidJsEngines (安卓) /
 * DesktopCore.registerSecondaryCoreProviders (桌面与无头) / IosProviderRegistry /
 * OhosProviderRegistry。
 *
 * 模式参考 [io.legado.app.help.service.ServiceLaunchers]。
 */
fun registerComposeDefaultDataResourceProvider() {
    DefaultDataResourceProviders.register(ComposeResourceDefaultDataProvider())
}
