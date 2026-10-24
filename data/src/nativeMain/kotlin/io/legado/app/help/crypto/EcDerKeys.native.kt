package io.legado.app.help.crypto

/**
 * native 端 EC 密钥 DER 归一化 (纯 Kotlin, 底座 [DerTlv], 供 iOS [NativeSignOps] 用)。
 *
 * iOS SecKeyCreateWithData 只吃 X9.63 原始密钥字节 (与 SecKeyCopyExternalRepresentation 输出
 * 同格式), 不接受 DER 封装:
 * - EC 公钥: 0x04 || X || Y (X9.63 未压缩点);
 * - EC 私钥: 0x04 || X || Y || K (点 + 私钥标量)。
 * 本对象把 SPKI/SEC1(RFC 5915)/PKCS#8 输入解包成上述原始字节; SEC1 未内嵌公钥点时点名抛异常
 * (无私钥→公钥点乘实现, 不自研; 重新生成带公钥点的密钥即可, OpenSSL 默认内嵌)。
 */
internal object EcDerKeys {

    /** SPKI DER / 裸 X9.63 点 → 0x04||X||Y (SecKeyCreateWithData EC 公钥格式)。 */
    fun toX963Public(der: ByteArray): ByteArray {
        // 裸点: 0x04 开头 (非 SEQUENCE), 长度 = 1 + 2*坐标
        if (der.isNotEmpty() && der[0] == 0x04.toByte() && der.size % 2 == 1) return der
        val kids = DerTlv.children(der)
        // SPKI: SEQ{SEQ{OID ecPublicKey, OID curve}, BITSTRING{00 || 04||X||Y}}
        if (kids.size == 2 && kids[0].tag == DerTlv.TAG_SEQ && kids[1].tag == DerTlv.TAG_BITSTRING) {
            val bit = kids[1].content
            require(bit.isNotEmpty() && bit[0].toInt() == 0x00) { "EC key: invalid SPKI BIT STRING" }
            val point = bit.copyOfRange(1, bit.size)
            require(point.isNotEmpty() && point[0] == 0x04.toByte()) { "EC key: not an uncompressed point" }
            return point
        }
        throw IllegalArgumentException("EC key: not a SPKI/raw EC public key")
    }

    /** PKCS#8 / SEC1 DER 私钥 → 0x04||X||Y||K (SecKeyCreateWithData EC 私钥格式)。 */
    fun toX963PrivateWithPoint(der: ByteArray): ByteArray {
        var sec1 = der
        // PKCS#8: SEQ{INT 0, SEQ{OID...}, OCTET{SEC1}} → 取内层 SEC1
        val outer = DerTlv.children(der)
        if (outer.size >= 3 && outer[0].tag == DerTlv.TAG_INT && outer[1].tag == DerTlv.TAG_SEQ && outer[2].tag == DerTlv.TAG_OCTET) {
            sec1 = outer[2].content
        }
        // SEC1 (RFC 5915): SEQ{INT 1, OCTET K, [0] params?, [1] BITSTRING 04||X||Y?}
        var scalar: ByteArray? = null
        var point: ByteArray? = null
        for (el in DerTlv.children(sec1)) {
            when (el.tag) {
                DerTlv.TAG_OCTET -> scalar = el.content
                DerTlv.TAG_CTX1_CONSTRUCTED, DerTlv.TAG_CTX1_PRIMITIVE -> {
                    var c = el.content
                    // [1] 内可能再包一层 BITSTRING TLV, 也可能直接是 BITSTRING 内容 (00 || 04||X||Y)
                    if (c.isNotEmpty() && (c[0].toInt() and 0xFF) == DerTlv.TAG_BITSTRING) {
                        DerTlv.readTlv(c, 0)?.let { c = it.content }
                    }
                    if (c.isNotEmpty() && c[0].toInt() == 0x00 && c.size > 1) {
                        val p = c.copyOfRange(1, c.size)
                        if (p.isNotEmpty() && p[0] == 0x04.toByte()) point = p
                    }
                }
            }
        }
        val s = scalar
        val p = point
        if (s == null || p == null) {
            throw IllegalArgumentException(
                "EC private key has no embedded public point; SecKeyCreateWithData needs 0x04||X||Y||K " +
                    "(regenerate the key with the public key embedded, e.g. OpenSSL default output)"
            )
        }
        return p + s
    }
}
