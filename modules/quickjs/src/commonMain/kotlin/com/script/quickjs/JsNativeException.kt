package com.script.quickjs

/**
 * native JS 执行异常。
 *
 * 由 nativeEval 等方法在 JS 抛出异常时抛出。
 * message 为 JS 异常的字符串表示 (含 stack trace, 如 "TypeError: ...\n    at eval (...):line:col")。
 *
 * @param fileName  JS 错误发生的源文件名 (从 stack trace 首帧提取, 如 "<eval>")
 * @param lineNumber  JS 错误发生的行号 (从 stack trace 首帧提取, -1 表示未知)
 * @param columnNumber  JS 错误发生的列号 (从 stack trace 首帧提取, -1 表示未知)
 */
class JsNativeException(
    message: String,
    val fileName: String? = null,
    val lineNumber: Int = -1,
    val columnNumber: Int = -1
) : RuntimeException(message)
