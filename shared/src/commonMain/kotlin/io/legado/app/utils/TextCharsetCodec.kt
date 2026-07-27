package io.legado.app.utils

/**
 * 按名字符集编解码门面 (commonMain 不暴露 Charset 类型, 与 PlatformEncoding.kt 约定一致)。
 *
 * 供 [io.legado.app.model.fileBook.TextFileCore] 等需要非 UTF-8 解码的下沉逻辑使用:
 * - jvmAndAndroidMain actual: 包装 java.nio.charset.Charset (Charset.forName 直通, 行为不变);
 * - nativeMain actual: 内置 UTF-8/UTF-16 系/UTF-32 系/ISO-8859-1/US-ASCII;
 *   GBK 系/Big5 经 platformDecodeCjk/platformEncodeCjk 分端下沉
 *   (iOS: NSString GB18030/Big5; 鸿蒙: @ohos.util TextDecoder/TextEncoder napi 桥,
 *   桥未就绪保持"暂不支持请转码"报错)。
 */
interface TextCharsetCodec {

    /** 规范化字符集名。 */
    val name: String

    /** 解码 [bytes] 的 [offset]..[offset]+[length] 区间为字符串 (对齐 JVM 宽松解码, 坏字节替换为 U+FFFD)。 */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String

    /** 编码字符串为字节 (对齐 JVM REPLACE 模式)。 */
    fun encode(str: String): ByteArray
}

/** 按名获取编解码器; 平台不支持该字符集时抛异常 (与 JVM Charset.forName 未知名抛异常语义对齐)。 */
internal expect fun textCharsetCodec(name: String): TextCharsetCodec
