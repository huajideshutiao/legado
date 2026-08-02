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
        val msg = message?.trim().orEmpty()
        sb.append(msg)
        if (fileName != null && lineNumber != -1) {
            // message 已含 JS stack trace (如 "TypeError: ...\n    at <eval> (<input>:40:365)"),
            // 追加的首帧位置信息需独占一行,否则末帧与该行粘连
            if (msg.isNotEmpty()) {
                sb.append('\n')
            }
            sb.append("at ").append(fileName).append(':').append(lineNumber)
            if (columnNumber != -1) {
                sb.append(':').append(columnNumber)
            }
        } else if (msg.isBlank() || msg == "JS Exception" || msg == "Eval bytecode failed") {
            // 引擎侧拿不到 JS 错误详情: 抛出值非 Error 对象 (无 stack 无位置) / toString 失败 / 内存不足。
            // 单独标注,便于区分 JS 脚本问题与引擎/桥接问题 (引擎问题可看 cause 栈)。
            if (msg.isNotEmpty()) {
                sb.append('\n')
            }
            sb.append("[错误详情缺失: JS 抛出非 Error 值或引擎无法序列化错误, 无位置信息; ")
                .append("若与具体书源脚本无关而反复出现, 可能是引擎/桥接问题, 请附 cause 栈排查]")
        }
        return sb.toString()
    }
}