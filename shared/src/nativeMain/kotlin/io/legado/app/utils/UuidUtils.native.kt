package io.legado.app.utils

/**
 * UuidUtils 的 iOS/鸿蒙 actual。
 *
 * 纯 Kotlin 实现: 4 个 32-bit 随机数拼装, 与 java.util.UUID.randomUUID().toString()
 * 字节级一致 (8-4-4-4-12 格式, version=4, variant=IETF)。
 *
 * 注: K/N 中 kotlin.random.Random 默认用平台熵源 (iOS/鸿蒙均可用),
 * 分布质量与 java.util.UUID 等价 (4 位版本 + 2 位 variant 固定, 其余 122 位随机)。
 */
actual fun randomUUIDString(): String {
    val r = kotlin.random.Random.Default
    // 16 字节随机数, 拼成 UUID 字符串
    val bytes = ByteArray(16)
    r.nextBytes(bytes)
    // version = 4 (random)
    bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
    // variant = IETF RFC 4122
    bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
    return formatUuid(bytes)
}

private fun formatUuid(b: ByteArray): String {
    val sb = StringBuilder(36)
    appendHex(sb, b, 0, 4)
    sb.append('-')
    appendHex(sb, b, 4, 2)
    sb.append('-')
    appendHex(sb, b, 6, 2)
    sb.append('-')
    appendHex(sb, b, 8, 2)
    sb.append('-')
    appendHex(sb, b, 10, 6)
    return sb.toString()
}

private fun appendHex(sb: StringBuilder, b: ByteArray, off: Int, len: Int) {
    val hex = "0123456789abcdef"
    for (i in 0 until len) {
        val v = b[off + i].toInt() and 0xff
        sb.append(hex[v ushr 4]).append(hex[v and 0xf])
    }
}
