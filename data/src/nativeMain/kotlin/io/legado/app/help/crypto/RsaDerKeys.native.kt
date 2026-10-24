package io.legado.app.help.crypto

/**
 * native 端 RSA 密钥 DER/PEM 归一化 (纯 Kotlin, iOS/鸿蒙 共用)。
 *
 * 原 mbedTLS pk_parse 自动识别 PEM/PKCS#1/PKCS#8/SPKI; 平台委托后各平台 API 只吃固定格式:
 * - iOS SecKeyCreateWithData: RSA 私钥/公钥均 PKCS#1 DER;
 * - 鸿蒙 cryptoFramework convertKey: 私钥 PKCS#8 DER, 公钥 X.509 SPKI DER。
 * 本对象负责 PEM 剥壳与四种形态互转, 调用方按目标格式取用。
 *
 * 仅 RSA OID (1.2.840.113549.1.1.1); 输入形态不符/非 RSA 点名抛异常。
 * DER 基元在共享 [DerTlv] (EC 密钥解析 [EcDerKeys] 同底座)。
 */
internal object RsaDerKeys {

    private val RSA_OID = byteArrayOf(
        0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(),
        0xF7.toByte(), 0x0D.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte()
    )

    /** PEM 剥壳 (-----BEGIN...-----END 之间 base64); 非 PEM 原样返回。 */
    fun stripPemToDer(bytes: ByteArray): ByteArray {
        val text = bytes.decodeToString()
        if (!text.contains("-----BEGIN")) return bytes
        val body = text
            .substringAfter("-----BEGIN", "")
            .substringAfter("\n", "")
            .substringBefore("-----END")
        val cleaned = body.filter { !it.isWhitespace() && it != '\r' && it != '\n' }
        require(cleaned.isNotEmpty()) { "RSA key: empty PEM body" }
        return runCatching { io.legado.app.utils.Base64Lenient.decode(cleaned) }
            .getOrElse { throw IllegalArgumentException("RSA key: invalid PEM base64 body") }
    }

    /** 归一为 PKCS#1 私钥 DER (iOS SecKeyCreateWithData 私钥格式)。 */
    fun toPkcs1Private(der: ByteArray): ByteArray {
        val kids = DerTlv.children(der)
        // PKCS#8: SEQ{INT 0, SEQ{OID, ...}, OCTET{PKCS#1}}
        if (kids.size >= 3 && kids[0].tag == DerTlv.TAG_INT && kids[1].tag == DerTlv.TAG_SEQ && kids[2].tag == DerTlv.TAG_OCTET) {
            return kids[2].content
        }
        // PKCS#1: SEQ{INT 0, INT n, INT e, ...}
        if (kids.size >= 3 && kids[0].tag == DerTlv.TAG_INT && kids[1].tag == DerTlv.TAG_INT && kids[2].tag == DerTlv.TAG_INT) {
            return der
        }
        throw IllegalArgumentException("RSA key: not a PKCS#1/PKCS#8 private key")
    }

    /** 归一为 PKCS#1 公钥 DER (iOS SecKeyCreateWithData 公钥格式)。 */
    fun toPkcs1Public(der: ByteArray): ByteArray {
        val kids = DerTlv.children(der)
        // SPKI: SEQ{SEQ{OID, ...}, BITSTRING{00 || PKCS#1}}
        if (kids.size == 2 && kids[0].tag == DerTlv.TAG_SEQ && kids[1].tag == DerTlv.TAG_BITSTRING) {
            val bit = kids[1].content
            require(bit.isNotEmpty() && bit[0].toInt() == 0x00) { "RSA key: invalid SPKI BIT STRING" }
            return bit.copyOfRange(1, bit.size)
        }
        // PKCS#1: SEQ{INT n, INT e}
        if (kids.size == 2 && kids[0].tag == DerTlv.TAG_INT && kids[1].tag == DerTlv.TAG_INT) {
            return der
        }
        throw IllegalArgumentException("RSA key: not a PKCS#1/SPKI public key")
    }

    /** 归一为 PKCS#8 私钥 DER (鸿蒙 convertKey 私钥槽)。 */
    fun toPkcs8Private(der: ByteArray): ByteArray {
        val kids = DerTlv.children(der)
        if (kids.size >= 3 && kids[0].tag == DerTlv.TAG_INT && kids[1].tag == DerTlv.TAG_SEQ && kids[2].tag == DerTlv.TAG_OCTET) {
            return der
        }
        if (kids.size >= 3 && kids[0].tag == DerTlv.TAG_INT && kids[1].tag == DerTlv.TAG_INT && kids[2].tag == DerTlv.TAG_INT) {
            val algId = DerTlv.wrap(DerTlv.TAG_SEQ, RSA_OID + byteArrayOf(0x05, 0x00))
            return DerTlv.wrap(DerTlv.TAG_SEQ, DerTlv.intTlv(0) + algId + DerTlv.octetTlv(der))
        }
        throw IllegalArgumentException("RSA key: not a PKCS#1/PKCS#8 private key")
    }

    /** 归一为 X.509 SPKI 公钥 DER (鸿蒙 convertKey 公钥槽)。 */
    fun toSpkiPublic(der: ByteArray): ByteArray {
        val kids = DerTlv.children(der)
        if (kids.size == 2 && kids[0].tag == DerTlv.TAG_SEQ && kids[1].tag == DerTlv.TAG_BITSTRING) {
            return der
        }
        if (kids.size == 2 && kids[0].tag == DerTlv.TAG_INT && kids[1].tag == DerTlv.TAG_INT) {
            val algId = DerTlv.wrap(DerTlv.TAG_SEQ, RSA_OID + byteArrayOf(0x05, 0x00))
            return DerTlv.wrap(DerTlv.TAG_SEQ, algId + DerTlv.bitstringTlv(byteArrayOf(0x00) + der))
        }
        throw IllegalArgumentException("RSA key: not a PKCS#1/SPKI public key")
    }

    /** PKCS#1 DER → 模长字节数 k (取模数 INTEGER 内容长度, 去符号补零; RSA 分块语义用)。 */
    fun modulusSize(pkcs1Der: ByteArray, isPrivate: Boolean): Int {
        val kids = DerTlv.children(pkcs1Der)
        val modulus = if (isPrivate) kids[1] else kids[0]
        require(modulus.tag == DerTlv.TAG_INT) { "RSA key: modulus INTEGER missing" }
        var c = modulus.content
        while (c.isNotEmpty() && c[0].toInt() == 0x00) c = c.copyOfRange(1, c.size)
        require(c.isNotEmpty()) { "RSA key: empty modulus" }
        return c.size
    }
}
