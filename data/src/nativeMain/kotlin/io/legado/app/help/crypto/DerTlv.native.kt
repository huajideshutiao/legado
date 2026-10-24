package io.legado.app.help.crypto

/**
 * DER TLV 解析/编码原语 (nativeMain, iOS/鸿蒙 共用)。
 *
 * [RsaDerKeys] (RSA PKCS#1/PKCS#8/SPKI) 与 [EcDerKeys] (EC SEC1/PKCS#8/SPKI) 的共同底座,
 * 单一实现, 不各自复制解析器。
 */
internal object DerTlv {

    const val TAG_SEQ = 0x30
    const val TAG_INT = 0x02
    const val TAG_OID = 0x06
    const val TAG_BITSTRING = 0x03
    const val TAG_OCTET = 0x04
    const val TAG_CTX0 = 0xA0
    const val TAG_CTX1_CONSTRUCTED = 0xA1
    const val TAG_CTX1_PRIMITIVE = 0x81

    class Tlv(val tag: Int, val content: ByteArray, val totalLength: Int)

    /** 解析 SEQUENCE 的直接子元素 (外层非 SEQUENCE 或结构损坏抛 IllegalArgumentException)。 */
    fun children(der: ByteArray): List<Tlv> {
        val outer = readTlv(der, 0)
            ?: throw IllegalArgumentException("key: not a DER SEQUENCE")
        if (outer.tag != TAG_SEQ) throw IllegalArgumentException("key: outer element is not SEQUENCE")
        val out = ArrayList<Tlv>(3)
        var off = 0
        val c = outer.content
        while (off < c.size) {
            val t = readTlv(c, off) ?: break
            out.add(t)
            off += t.totalLength
        }
        if (out.isEmpty()) throw IllegalArgumentException("key: empty SEQUENCE")
        return out
    }

    /** 读一个 TLV; 越界/长度损坏返回 null (children 靠它跳过尾部残留)。 */
    fun readTlv(bytes: ByteArray, off: Int): Tlv? {
        if (off >= bytes.size) return null
        val tag = bytes[off].toInt() and 0xFF
        var p = off + 1
        if (p >= bytes.size) return null
        var len = bytes[p].toInt() and 0xFF
        p++
        if (len and 0x80 != 0) {
            val n = len and 0x7F
            if (n == 0 || n > 4 || p + n > bytes.size) return null
            len = 0
            for (i in 0 until n) {
                len = (len shl 8) or (bytes[p].toInt() and 0xFF)
                p++
            }
        }
        if (p + len > bytes.size) return null
        return Tlv(tag, bytes.copyOfRange(p, p + len), p + len - off)
    }

    fun lengthBytes(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), (n and 0xFF).toByte())
    }

    fun wrap(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + lengthBytes(content.size) + content

    fun intTlv(value: Int): ByteArray = wrap(TAG_INT, byteArrayOf(value.toByte()))

    fun octetTlv(content: ByteArray): ByteArray = wrap(TAG_OCTET, content)

    fun bitstringTlv(content: ByteArray): ByteArray = wrap(TAG_BITSTRING, content)
}
