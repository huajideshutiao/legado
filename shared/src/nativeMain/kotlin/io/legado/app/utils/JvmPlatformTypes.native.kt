package io.legado.app.utils

/**
 * JVM 专属类型的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/utils/JvmPlatformTypes.kt expect 注释。
 * 纯 Kotlin 包装: 不依赖 platform.* 框架, iOS/鸿蒙代码完全一致。
 *
 * - [URL] 内部用 String 字段保存 URL 文本
 * - [InputStream] 纯 Kotlin ByteArrayInputStream 风格 (内存数据源)
 * - [Closeable] 纯 Kotlin 接口, close() 默认空实现
 *
 * [toInputStream]: ByteArray 内存 ByteArrayInputStream 风格 (与 JVM ByteArrayInputStream 行为对齐)。
 *
 * KP4 OkHttp 跨平台修复: [byteStreamAsInput] 接收者改为 [Any] (commonMain expect 不引用 okhttp3.ResponseBody);
 * iOS/鸿蒙无 OkHttp 实际 ResponseBody (OkHttp 5.3.2 不发布 iosArm64/linuxArm64 变体),
 * 此函数在 iOS/鸿蒙 target 永不执行 (调用方 AnalyzeUrlCore.getInputStreamAwait 引用 okhttp3.Response,
 * 在 iOS/鸿蒙 target 编译失败), 抛 UnsupportedOperationException 兜底。
 */
actual class URL actual constructor(val url: String) {
    override fun toString(): String = url
}

actual abstract class InputStream {
    abstract fun read(): Int
    open fun read(b: ByteArray, off: Int, len: Int): Int {
        var i = 0
        while (i < len) {
            val v = read()
            if (v == -1) break
            b[off + i] = v.toByte()
            i++
        }
        return if (i == 0) -1 else i
    }

    open fun close() {}
}

// java.io.File stub: iOS/鸿蒙端 BitmapProvider 暂未实现, 此 actual 仅满足 expect/actual 编译契约。
// 仅持有 path 字段, 不提供任何文件系统操作; 后续若 native 端实现 BitmapProvider 再补全。
actual class File actual constructor(val path: String)

actual interface Closeable {
    fun close()
}

/**
 * ByteArray → InputStream 转换: 内存 ByteArrayInputStream 风格。
 * actual: 纯 Kotlin 包装, 内部 pos 计数, 与 JVM ByteArrayInputStream 行为对齐。
 */
internal actual fun ByteArray.toInputStream(): InputStream = ByteArrayInputStream(this)

/**
 * ResponseBody → InputStream 转换 (iOS/鸿蒙 stub)。
 *
 * KP4: 接收者改为 [Any] (commonMain expect 不引用 okhttp3.ResponseBody)。
 * iOS/鸿蒙无 OkHttp 实际 ResponseBody, 此函数永不执行, 抛异常兜底。
 * 原实现 `ResponseBody.bytes()` 包装为 ByteArrayInputStream 已移除 (引用 okhttp3.* 会编译失败)。
 */
internal actual fun Any.byteStreamAsInput(): InputStream {
    throw UnsupportedOperationException("byteStreamAsInput not supported on iOS/ohos: OkHttp unavailable")
}

/** 纯 Kotlin ByteArrayInputStream 风格, 用于 toInputStream。 */
private class ByteArrayInputStream(
    private val buffer: ByteArray,
    private var pos: Int = 0,
    private val count: Int = buffer.size,
) : InputStream() {
    override fun read(): Int {
        return if (pos >= count) -1 else (buffer[pos++].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= count) return -1
        val n = minOf(len, count - pos)
        buffer.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }
}
