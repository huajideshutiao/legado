package io.legado.app.utils

/**
 * 标准 Base64 编码（RFC 4648）：标准字母表 + padding('=') + 不换行。
 *
 * 行为对齐 java.util.Base64.getEncoder().encodeToString(bytes)
 * （即原 android.util.Base64.NO_WRAP）。
 *
 * 仅供 iOS/鸿蒙 JsEncodeUtils 的 digestBase64Str / HMacBase64 以及 iOS 加密门面
 * （CryptoHelper / BackupAES / SymmetricCryptoIos）的密文输出使用，
 * 避免依赖 Base64Lenient（仅 decode）。
 */
internal fun ByteArray.encodeBase64Standard(): String {
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < size) {
        val v = ((this[i].toInt() and 0xff) shl 16) or
            ((this[i + 1].toInt() and 0xff) shl 8) or
            (this[i + 2].toInt() and 0xff)
        sb.append(table[v ushr 18 and 0x3f])
        sb.append(table[v ushr 12 and 0x3f])
        sb.append(table[v ushr 6 and 0x3f])
        sb.append(table[v and 0x3f])
        i += 3
    }
    val rem = size - i
    if (rem == 1) {
        val v = (this[i].toInt() and 0xff) shl 16
        sb.append(table[v ushr 18 and 0x3f])
        sb.append(table[v ushr 12 and 0x3f])
        sb.append("==")
    } else if (rem == 2) {
        val v = ((this[i].toInt() and 0xff) shl 16) or
            ((this[i + 1].toInt() and 0xff) shl 8)
        sb.append(table[v ushr 18 and 0x3f])
        sb.append(table[v ushr 12 and 0x3f])
        sb.append(table[v ushr 6 and 0x3f])
        sb.append('=')
    }
    return sb.toString()
}
