package io.legado.app.model

/**
 * Debug 模块 UI 回调 provider 接口。
 *
 * 原 app 端 `Debug.callback: Callback?` 直接持有 app 端实现 (BookSourceDebugModel /
 * DebugWsHandler), shared 内无法直接持有这些 app 端类型。改为 provider 模式:
 * app 端 DebugCallbackImpl 在 App.onCreate 经 [DebugCallbacks.register] 注册,
 * shared 内 Debug 通过 [DebugCallbacks.impl] 间接调用, 行为与原 `callback?.printLog(...)` 一致。
 *
 * 取消调试 (cancel) 也经本接口下发, app 端 impl 决定是否清理 callback 引用。
 *
 * 模式参考 SourceDebugLoggers / IntentDataProviders。
 */
interface DebugCallback {

    /**
     * 输出调试日志到 UI。
     * @param state 原 Debug.Callback.printLog 的 state (1=普通, -1=错误, 1000=完成, 10/50=HTML 等)
     * @param msg 已格式化 (含时间戳/HTML 格式化) 的日志消息
     */
    fun log(state: Int, msg: String)

    /**
     * 取消调试。
     * @param destroy true 时同时清理 callback 引用 (对应原 Debug.cancelDebug(destroy=true))
     */
    fun cancel(destroy: Boolean)
}

/**
 * Debug callback provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `DebugCallbacks.impl?.log(...)` 替代原 `Debug.callback?.printLog(...)`,
 * 行为完全一致, 仅多一层 provider 间接。
 */
object DebugCallbacks {
    @Volatile
    var impl: DebugCallback? = null
}
