// android.* JVM stub (桌面端 TVBox catvod 壳类用): 仅保证类解析与 android 语义等价。
// 范围依据: :data jvmAndAndroidMain 壳类 (com.github.catvod.*) 的引用面; 缺口按运行期报错按需补。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.util

import java.util.logging.Logger

/**
 * android.util.Base64 的 JVM 语义等价实现 (JDK 无此类的 flag 语义, 按 Android 文档逐位映射):
 * - DEFAULT(0): 标准字母表 + 补位 + 76 字符换行 (末尾带 '\n')
 * - NO_PADDING(1): 不补 '='; NO_WRAP(2): 不换行; CRLF(4): 换行符为 "\r\n"; URL_SAFE(8): URL 字母表
 * - decode 一律宽容 (容忍空白/缺补位), 对齐 Android 解码器行为
 */
object Base64 {

    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    private const val LINE_LENGTH = 76

    fun encode(input: ByteArray, flags: Int = DEFAULT): ByteArray {
        var encoder = if (flags and URL_SAFE != 0) {
            java.util.Base64.getUrlEncoder()
        } else {
            java.util.Base64.getEncoder()
        }
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val encoded = encoder.encodeToString(input)
        val text = if (flags and NO_WRAP == 0) {
            val lineSep = if (flags and CRLF != 0) "\r\n" else "\n"
            val sb = StringBuilder(encoded.length + encoded.length / LINE_LENGTH * lineSep.length + 2)
            var index = 0
            while (index < encoded.length) {
                val end = minOf(index + LINE_LENGTH, encoded.length)
                sb.append(encoded, index, end).append(lineSep)
                index = end
            }
            sb.toString()
        } else {
            encoded
        }
        return text.toByteArray(Charsets.US_ASCII)
    }

    fun encodeToString(input: ByteArray, flags: Int = DEFAULT): String =
        String(encode(input, flags), Charsets.US_ASCII)

    fun decode(str: String, flags: Int = DEFAULT): ByteArray {
        // URL_SAFE 输入把字母表归一回标准字母表; MIME 解码器宽容处理空白/缺补位
        val normalized = if (flags and URL_SAFE != 0) str.replace('-', '+').replace('_', '/') else str
        return java.util.Base64.getMimeDecoder().decode(normalized)
    }
}

/** android.util.Log 的 JVM 等价: 走 JUL (壳类 SpiderDebug/Shell/Path 的调试输出通道)。 */
object Log {

    private val logger = Logger.getLogger("AndroidLog")

    fun d(tag: String, msg: String): Int {
        logger.fine("[$tag] $msg")
        return 0
    }

    fun d(tag: String, msg: String, tr: Throwable?): Int {
        logger.fine("[$tag] $msg\n${tr?.toString().orEmpty()}")
        return 0
    }

    fun e(tag: String, msg: String): Int {
        logger.severe("[$tag] $msg")
        return 0
    }

    fun e(tag: String, msg: String, tr: Throwable?): Int {
        logger.severe("[$tag] $msg\n${tr?.toString().orEmpty()}")
        return 0
    }

    fun i(tag: String, msg: String): Int {
        logger.info("[$tag] $msg")
        return 0
    }

    fun w(tag: String, msg: String): Int {
        logger.warning("[$tag] $msg")
        return 0
    }

    fun v(tag: String, msg: String): Int {
        logger.finest("[$tag] $msg")
        return 0
    }
}
