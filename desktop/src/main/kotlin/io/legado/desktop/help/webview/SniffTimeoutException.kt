package io.legado.desktop.help.webview

import io.legado.app.exception.NoStackTraceException

/**
 * 引擎资源嗅探窗到期 (AppConst.timeLimit 内未命中)。
 *
 * 独立类型而非文案约定: 调用方按类型归一超时文案, 引擎改超时提示不会把超时静默降级成
 * 普通失败重抛。
 */
class SniffTimeoutException(message: String) : NoStackTraceException(message)
