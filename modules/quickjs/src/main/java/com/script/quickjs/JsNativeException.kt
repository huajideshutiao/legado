package com.script.quickjs

/**
 * native JS 执行异常。
 *
 * 由 nativeEval 等方法在 JS 抛出异常时抛出。
 * message 为 JS 异常的字符串表示 (如 "TypeError: cannot read property of null")。
 */
class JsNativeException(message: String) : RuntimeException(message)
