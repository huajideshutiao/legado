package io.legado.app.utils

/**
 * 按名字符集编解码门面 (commonMain 不暴露 Charset 类型, 与 PlatformEncoding.kt 约定一致)。
 *
 * 供 [io.legado.app.model.fileBook.TextFileCore] 等需要非 UTF-8 解码的下沉逻辑使用:
 * - jvmAndAndroidMain actual: 包装 java.nio.charset.Charset (Charset.forName 直通, 行为不变);
 * - nativeMain actual: 委托 com.fleeksoft.charset (OpenJDK sun.nio.cs 移植), iOS/鸿蒙共用,
 *   编解码均 REPLACE 与 JVM 字节级一致 (平台原生转换器与 JVM 映射不保证一致, 不下沉平台)。
 */
interface TextCharsetCodec {

    /** 规范化字符集名。 */
    val name: String

    /** 解码 [bytes] 的 [offset]..[offset]+[length] 区间为字符串 (对齐 JVM 宽松解码, 坏字节替换为 U+FFFD)。 */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String

    /** 编码字符串为字节 (对齐 JVM REPLACE 模式)。 */
    fun encode(str: String): ByteArray

    /**
     * 新建有状态编码器 (一次编码操作私有, 不共享不缓存): [TextCharsetEncoder.encode] 每次
     * 接收完整文本片段, 内部状态跨片段保留 (如 UTF-16 的 BOM 按首输入写入); 片段末尾孤立
     * high surrogate 立即按 replacement 输出, 不与后续片段拼接成代理对。
     */
    fun newEncoder(): TextCharsetEncoder
}

/**
 * 有状态字符编码器: 同一次编码操作的连续片段逐个编码, 各片段字节可直接顺序拼接,
 * BOM 与替换语义由底层真实编码器决定, 与整串一次性编码一致。
 */
fun interface TextCharsetEncoder {

    /** 编码一个完整文本片段, 返回该片段字节 (跨片段状态保留在编码器实例内)。 */
    fun encode(input: String): ByteArray
}

/** 按名获取编解码器; 平台不支持该字符集时抛异常 (与 JVM Charset.forName 未知名抛异常语义对齐)。 */
expect fun textCharsetCodec(name: String): TextCharsetCodec
