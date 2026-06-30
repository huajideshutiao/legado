package com.script.quickjs

/**
 * JS 执行异常。
 *
 * 兼容 com.script.ScriptException 的基本接口,业务层 catch 时可替换 import。
 */
class ScriptException : Exception {

    val fileName: String?
    val lineNumber: Int
    val columnNumber: Int

    constructor(message: String?) : this(message, null, -1, -1)

    constructor(message: String?, fileName: String?, lineNumber: Int, columnNumber: Int) : super(
        message
    ) {
        this.fileName = fileName
        this.lineNumber = lineNumber
        this.columnNumber = columnNumber
    }

    constructor(message: String?, cause: Throwable?) : this(message, cause, null, -1, -1)

    constructor(
        message: String?,
        cause: Throwable?,
        fileName: String?,
        lineNumber: Int,
        columnNumber: Int
    ) : super(message, cause) {
        this.fileName = fileName
        this.lineNumber = lineNumber
        this.columnNumber = columnNumber
    }

    /**
     * 不写 "ScriptException: " 前缀:JS message 自带 "Error: ..." / "TypeError: ..."
     * 已经够清楚,再加一层 Java 类名只是噪声。
     *
     * 注:Java/Kotlin/JNI 调用栈仍照常收集 (默认 fillInStackTrace),便于排查引擎侧问题。
     */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(message ?: "")
        if (fileName != null && lineNumber != -1) {
            sb.append(" at ").append(fileName).append(':').append(lineNumber)
            if (columnNumber != -1) {
                sb.append(':').append(columnNumber)
            }
        }
        return sb.toString()
    }
}
