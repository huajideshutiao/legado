package io.legado.app.utils

/**
 * RFC3986 百分号编码，行为对齐 hutool PercentCodec：安全字符原样输出，
 * 其余按 charset 转字节 %XX 大写编码；空格不转 '+'，代理对合并为一个码点编码。
 * 算法主体在 common 的 PercentCodecCore。
 *
 * **下沉说明**: commonMain 不暴露 [java.nio.charset.Charset] (Kotlin 2.3 stdlib common
 * 无 kotlin.text.Charset expect class), encode 公开签名用 [toBytes] lambda 注入字节化;
 * jvmAndAndroidMain 端 PercentCodecExt.kt 提供 `encode(str, Charset)` 扩展函数,
 * 供 app 端测试 (HutoolReplacementCompatTest) 等需要 Charset 重载的调用方使用。
 */
class PercentCodec private constructor(private val core: PercentCodecCore) {

    fun orNew(codec: PercentCodec): PercentCodec = PercentCodec(core.orNew(codec.core))

    /**
     * commonMain: 用 [toBytes] lambda 注入字节化, 不暴露 Charset 类型。
     * jvmAndAndroidMain 端有 `fun PercentCodec.encode(str, charset: Charset)` 扩展,
     * 内部调用本方法并传入 `{ it.toByteArray(charset) }`。
     * commonMain 端 [String.encodeURI] 直接调用本方法并传入 `{ it.toByteArray() }`
     * (默认 UTF_8, kotlin.text 标准 API, commonMain 可用)。
     */
    fun encode(str: CharSequence, toBytes: (String) -> ByteArray): String =
        core.encode(str, toBytes)

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
