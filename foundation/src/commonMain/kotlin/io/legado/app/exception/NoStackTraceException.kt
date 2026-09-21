package io.legado.app.exception

/**
 * 不记录错误堆栈的报错
 * 栈抑制是 JVM 专属能力（fillInStackTrace），故 expect/actual
 */
expect open class NoStackTraceException(msg: String) : Exception
