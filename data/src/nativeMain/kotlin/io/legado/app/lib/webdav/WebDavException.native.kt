package io.legado.app.lib.webdav

// native 无 fillInStackTrace 可覆写, 普通类即可
//
// 平台限制核实 (Kotlin/Native): Throwable 的栈捕获在构造时即完成
// (private val stackTrace = getCurrentStackTrace(), 见 stdlib nativeMain Throwable.kt),
// API 中不存在 fillInStackTrace 覆写点, 无法像 jvmAndAndroidMain 那样抑制栈捕获。
// 影响: 每次抛 WebDavException 仍会捕获一次调用栈 (WebDav 轮询/重试场景有少量额外开销),
// 行为正确性不受影响; 待 K/N 提供栈捕获开关 (KT-57164 相关) 后可再对齐。
actual open class WebDavException actual constructor(msg: String) : Exception(msg)
