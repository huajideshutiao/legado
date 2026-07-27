package io.legado.app.web.utils

import kotlin.concurrent.Volatile

/**
 * Web 模块本地化文案抽象 (shared commonMain)。
 *
 * # 背景
 * 原 app 端 [io.legado.app.web.WebSocketServer] 用 `appCtx.getString(R.string.cannot_empty)`
 * 取「不能为空」文案注入 [io.legado.app.web.api.DebugWsHandler] / SearchWsHandler。
 * shared commonMain 不能引用 app 模块 R 资源, 抽象至此接口, 由各端 actual 注入。
 *
 * 桌面端暂用硬编码中文 (与 app 端 strings.xml `cannot_empty` 文案一致), 后续接入 i18n 资源后替换。
 */
interface WebStrings {

    /** 「不能为空」文案 (原 R.string.cannot_empty), 供 WebSocketServer 注入 handlers。 */
    val cannotEmpty: String
}

/**
 * [WebStrings] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 模式参考 [io.legado.app.help.service.ServiceLaunchers]。
 */
object WebStringsProviders {

    @Volatile
    private var impl: WebStrings? = null

    /** 宿主启动早期注册一次 (任何 WebSocketServer 调用之前)。 */
    fun register(impl: WebStrings) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): WebStrings =
        impl ?: error("WebStringsProviders not registered; call registerAndroidWebStrings() or registerDesktopWebStrings() first")

    /** 仅测试场景: 清空注册。 */
    fun reset() {
        impl = null
    }
}
