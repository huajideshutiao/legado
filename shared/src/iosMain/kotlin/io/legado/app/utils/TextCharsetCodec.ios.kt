@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.utils

import io.legado.app.exception.NoStackTraceException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingBig5
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

/**
 * [platformDecodeCjk]/[platformEncodeCjk] iOS actual: NSString GB18030/Big5 编解码。
 *
 * NSStringEncoding 无 GB18030/Big5 直接常量, 经 CoreFoundation
 * [CFStringConvertEncodingToNSStringEncoding] 从 CFStringEncodings 换算
 * (kCFStringEncodingGB_18030_2000=1586 / kCFStringEncodingBig5=2563, 平台库符号已核对)。
 *
 * 解码用 `NSString.create(bytes:length:encoding:)` (免中间 NSData 拷贝);
 * NSString 解码是严格模式, 无 JVM 的坏字节→U+FFFD 宽松替换, 因此对失败做尾字节递减重试:
 * TextFileCore.getChapterList 的探测块按固定字节数截断, 可能切在多字节字符中间
 * (GB18030 单字符最长 4 字节), 裁尾 1..3 字节后补 U+FFFD 对齐 JVM 行为;
 * 仍失败视为文件损坏, 抛明确异常 (而非返回 null 误导为"平台不支持")。
 */
internal actual fun platformDecodeCjk(
    bytes: ByteArray, offset: Int, length: Int, charset: PlatformCjkCharset
): String? {
    if (length <= 0) return ""
    val encoding = charset.nsEncoding()
    bytes.usePinned { pinned ->
        // 裁尾重试: trim=0 整段, 1..3 覆盖缓冲边界切断的 GB18030/Big5 多字节字符尾部
        for (trim in 0..3) {
            val len = length - trim
            if (len <= 0) break
            val decoded = NSString.create(
                bytes = pinned.addressOf(offset),
                length = len.toULong(),
                encoding = encoding,
            ) as String?
            if (decoded != null) {
                return if (trim == 0) decoded else decoded + '�'
            }
        }
    }
    throw NoStackTraceException(
        "iOS 端 ${charset.transportName} 解码失败, TXT 文件可能已损坏或编码识别有误"
    )
}

/**
 * iOS actual: `NSString.dataUsingEncoding(allowLossyConversion=true)` 编码。
 * lossy=true 对齐 JVM REPLACE 模式 (不可映射字符降级输出而非失败);
 * 可映射字符字节输出与解码方向同表, 保证 TextFileCore 解码→编码往返字节数一致。
 */
internal actual fun platformEncodeCjk(str: String, charset: PlatformCjkCharset): ByteArray? {
    if (str.isEmpty()) return ByteArray(0)
    @Suppress("CAST_NEVER_SUCCEEDS")
    val nsString = str as NSString
    val data = nsString.dataUsingEncoding(charset.nsEncoding(), allowLossyConversion = true)
        ?: throw NoStackTraceException(
            "iOS 端 ${charset.transportName} 编码失败, 内容含无法转换的字符"
        )
    val size = data.length.toInt()
    if (size == 0) return ByteArray(0)
    return data.bytes?.readBytes(size) ?: ByteArray(0)
}

/** CFStringEncodings 常量 → NSStringEncoding (CFStringEncoding 为 UInt, NSStringEncoding 为 ULong)。 */
private fun PlatformCjkCharset.nsEncoding(): ULong = when (this) {
    PlatformCjkCharset.GB18030 ->
        CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.toUInt())
    PlatformCjkCharset.BIG5 ->
        CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingBig5.toUInt())
}
