package io.legado.app.help.source

object SourceDebugLoggers {
    @Volatile var impl: SourceDebugLogger? = null
}

/**
 * 调试日志 provider。原 app 端 Debug.log 有两个重载:
 * - log(sourceUrl, msg, print, isHtml, showTime, state): 显式带 sourceUrl
 * - log(msg): 隐式使用 Debug.debugSource (startDebug 时设置)
 *
 * shared 侧无 Debug.debugSource 上下文, 两个重载都暴露由 impl 决定走哪条路径。
 * 默认 log(msg) 走 log(null, msg, 1), impl 可覆盖走 Debug.log(msg) 隐式 debugSource。
 *
 * W3-e: 新增 [print] 参数支持。webBook 编排层 (如 BookReview) 在循环列表项时
 * 仅对首项输出详细日志, 通过 `print = (index == 0)` 控制是否回调到 UI 调试窗口,
 * 与原 `Debug.log(key, msg, print)` 行为完全一致。
 *
 * 参数顺序 [print] 在 [state] 之前, 对齐原 Debug.log 签名 (print 第3, state 第6),
 * 让 `log(key, msg, log)` (log 是 Boolean 变量) 直接命中 print 形参无需命名参数。
 * 显式传 state 时使用命名参数 `log(key, msg, state = 50)`。
 */
interface SourceDebugLogger {
    /**
     * 显式 sourceUrl 路径(对应 Debug.log(key, msg, print, state=...))。
     *
     * @param key 书源 URL (对应 Debug.log 的 sourceUrl)
     * @param msg 日志消息
     * @param print 是否输出到 UI 调试回调 (默认 true, 对应 Debug.log 的 print);
     *        false 时 impl 仍执行 Log.d/isChecking 等副作用, 但跳过 callback 输出
     * @param state 日志状态码 (默认 1, 对应 Debug.log 的 state)
     */
    fun log(key: String, msg: String, print: Boolean = true, state: Int = 1)

    /** 隐式 debugSource 路径(对应 Debug.log(msg))。默认走 log(null, msg, 1)。 */
    fun log(msg: String) {
        log("", msg, print = true, state = 1)
    }
}
