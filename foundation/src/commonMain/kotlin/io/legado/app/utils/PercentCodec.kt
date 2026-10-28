package io.legado.app.utils

/**
 * RFC3986 百分号编码，行为对齐 hutool PercentCodec：安全字符原样输出，
 * 其余按 charset 转字节 %XX 大写编码；空格不转 '+'，代理对合并为一个码点编码。
 * 算法主体在 common 的 PercentCodecCore。
 *
 * **下沉说明**: commonMain 不暴露 [java.nio.charset.Charset] (Kotlin 2.3 stdlib common
 * 无 kotlin.text.Charset expect class), 字节化经 [toBytes] lambda 或 [TextCharsetCodec]
 * 注入; jvmAndAndroidMain 端 PercentCodecExt.kt 提供 `encode(str, Charset)` 扩展函数,
 * 供 app 端测试 (HutoolReplacementCompatTest) 等需要 Charset 重载的调用方使用。
 */
class PercentCodec private constructor(private val core: PercentCodecCore) {

    fun orNew(codec: PercentCodec): PercentCodec = PercentCodec(core.orNew(codec.core))

    /**
     * commonMain: 用 [toBytes] lambda 注入字节化, 不暴露 Charset 类型。
     * jvmAndAndroidMain 端有 `fun PercentCodec.encode(str, charset: Charset)` 扩展,
     * 经 [textCharsetCodec] (Charset 工厂) 走下方 TextCharsetCodec 重载 (编码器状态跨调用持续);
     * commonMain 端 [String.encodeURI] 走该 codec 重载。
     */
    fun encode(str: CharSequence, toBytes: (String) -> ByteArray): String =
        core.encode(str, toBytes)

    /**
     * 用 [TextCharsetCodec] 的编码器编码: 整个 encode 调用共用一个 [TextCharsetEncoder],
     * 每连续不安全段一段一次 encode, 编码器状态跨整个调用持续 —— 带 BOM 的字符集
     * (UTF-16) BOM 全调用仅随首个不安全段输出一次, 对齐 archive hutool 5.8.22
     * PercentCodec.encode(String, Charset)「循环外一个 OutputStreamWriter」的一手语义;
     * 段尾孤立 high 代理由 [TextCharsetEncoder] 按 replacement 即时替换, 不跨段拼接。
     */
    fun encode(str: CharSequence, codec: TextCharsetCodec): String {
        val encoder = codec.newEncoder()
        return encodeMergingRuns(str) { encoder.encode(it) }
    }

    /** 字符是否在本编码器的安全表内 (原样输出, 不参与百分号编码)。 */
    fun isSafe(c: Char): Boolean = core.isSafe(c)

    /**
     * 逐码点编码, 保留成套的 `%XX` 不二次转义, 不成套的 `%` 写为 `%25` (RFC 3986 §2.1)。
     * 与 [encode] 的区别只在 `%` 的处理: 本方法用于「输入已是部分转义的 URI」的合法化。
     */
    fun encodeKeepEscapes(str: CharSequence, toBytes: (String) -> ByteArray): String =
        core.encodeKeepEscapes(str, toBytes)

    /**
     * [encodeKeepEscapes] 的 [TextCharsetCodec] 版, 与 [encode] 的 codec 版同构: 整个调用
     * 共用一个 [TextCharsetEncoder] (一次 newEncoder, 编码器状态跨整个调用持续),
     * `%` 转义保留语义与 lambda 版一致 (RFC 3986 §2.1)。
     */
    fun encodeKeepEscapes(str: CharSequence, codec: TextCharsetCodec): String {
        val encoder = codec.newEncoder()
        return encodeKeepEscapes(str) { encoder.encode(it) }
    }

    /**
     * 连续非安全字符合并为一段、一段只调用一次 [toBytes] 的编码 (无状态字符集字节与 [encode] 一致)。
     * native 端远程字符集转换依赖此合并控制跨线程往返次数 (每次转换覆盖一段而非一个码点)。
     */
    fun encodeMergingRuns(str: CharSequence, toBytes: (String) -> ByteArray): String =
        core.encodeMergingRuns(str, toBytes)

    companion object {

        fun of(chars: CharSequence): PercentCodec = PercentCodec(PercentCodecCore.of(chars))

        /** RFC3986 unreserved：ALPHA / DIGIT / "-" / "." / "_" / "~" */
        val UNRESERVED: PercentCodec = of(buildString {
            for (c in 'A'..'Z') append(c)
            for (c in 'a'..'z') append(c)
            for (c in '0'..'9') append(c)
            append("_.-~")
        })

        /** RFC3986 query：pchar / "/" / "?"，即 hutool RFC3986.QUERY */
        val QUERY: PercentCodec = UNRESERVED.orNew(of("!\$&'()*+,;=:@/?"))
    }
}
