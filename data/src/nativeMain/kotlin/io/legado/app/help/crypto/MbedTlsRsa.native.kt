@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import io.legado.app.nativecrypto.mbedtls.MBEDTLS_RSA_PKCS_V21
import io.legado.app.nativecrypto.mbedtls.lg_pk_decrypt
import io.legado.app.nativecrypto.mbedtls.lg_pk_encrypt
import io.legado.app.nativecrypto.mbedtls.lg_pk_rsa
import io.legado.app.nativecrypto.mbedtls.lg_rsa_private
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_get_size
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_get_type
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_info_t
import io.legado.app.nativecrypto.mbedtls.mbedtls_pk_context
import io.legado.app.nativecrypto.mbedtls.mbedtls_pk_free
import io.legado.app.nativecrypto.mbedtls.mbedtls_pk_get_bitlen
import io.legado.app.nativecrypto.mbedtls.mbedtls_pk_init
import io.legado.app.nativecrypto.mbedtls.mbedtls_pk_parse_key
import io.legado.app.nativecrypto.mbedtls.mbedtls_pk_parse_public_key
import io.legado.app.nativecrypto.mbedtls.mbedtls_rsa_context
import io.legado.app.nativecrypto.mbedtls.mbedtls_rsa_public
import io.legado.app.nativecrypto.mbedtls.mbedtls_rsa_set_padding
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * pk_parse 封装: DER 与 PEM 都吃 (PEM 按 mbedTLS 要求补 NUL 终止), PKCS#1/PKCS#8/SPKI 自动识别;
 * EC 密钥因裁剪不含 ECP 在解析期即报错。
 */
internal object MbedTlsPk {

    fun <T> withKey(
        keyBytes: ByteArray?,
        isPrivate: Boolean,
        block: (CPointer<mbedtls_pk_context>) -> T
    ): T {
        val role = if (isPrivate) "privateKey" else "publicKey"
        require(keyBytes != null && keyBytes.isNotEmpty()) { "$role not set" }
        return memScoped {
            val pk = alloc<mbedtls_pk_context>()
            mbedtls_pk_init(pk.ptr)
            try {
                // PEM 文本必须以 NUL 结尾且 keylen 计入 (pkparse.c: key[keylen-1] == '\0' 才走 PEM 分支)
                val buf = if (looksLikePem(keyBytes)) keyBytes + 0 else keyBytes
                buf.usePinned { pin ->
                    val ret = if (isPrivate) {
                        mbedtls_pk_parse_key(
                            pk.ptr, pin.addressOf(0).reinterpret(), buf.size.convert(),
                            null, 0u.convert(), null, null
                        )
                    } else {
                        mbedtls_pk_parse_public_key(pk.ptr, pin.addressOf(0).reinterpret(), buf.size.convert())
                    }
                    mbedCheck(if (isPrivate) "pk_parse_key" else "pk_parse_public_key", ret)
                }
                block(pk.ptr)
            } finally {
                mbedtls_pk_free(pk.ptr)
            }
        }
    }

    /** 取内部 RSA context; 非 RSA 密钥点名抛异常。 */
    fun rsa(pk: CPointer<mbedtls_pk_context>): CPointer<mbedtls_rsa_context> =
        lg_pk_rsa(pk) ?: throw UnsupportedOperationException(
            "mbedTLS: not an RSA key (trimmed build has no ECP/EC support)"
        )

    /** 模长字节数 k。 */
    fun keyLen(pk: CPointer<mbedtls_pk_context>): Int = (mbedtls_pk_get_bitlen(pk).toInt() + 7) / 8

    private val PEM_MARK = "-----BEGIN".encodeToByteArray()

    private fun looksLikePem(bytes: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - PEM_MARK.size) {
            for (j in PEM_MARK.indices) {
                if (bytes[i + j] != PEM_MARK[j]) continue@outer
            }
            return true
        }
        return false
    }
}

/**
 * mbedTLS RSA 全向加解密 (iOS/鸿蒙主实现, 失败由 actual 回落 Security/napi)。
 *
 * - 公钥加密/私钥解密: pk_encrypt/pk_decrypt (默认 PKCS#1 v1.5; OAEP 经 rsa_set_padding V21,
 *   MGF1 与摘要同 hash, 对齐 iOS SecKey / 鸿蒙 cryptoFramework 既有行为);
 * - 私钥加密/公钥解密 (hutool KeyType 反向): rsa_private/rsa_public 原语 + 手工 PKCS#1 v1.5
 *   type-01 padding (00 01 FF..FF 00 || M), 解包对称校验; 仅 v1.5, OAEP 反向无定义;
 * - 分块语义对照 hutool: 加密块 k-11 (OAEP 为 k-2h-2), 解密块 k, 多块输出顺序拼接。
 */
internal object MbedTlsRsa {

    fun encrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray {
        val oaepMd = parseTransformation(algorithm)
        return if (usePublicKey) {
            MbedTlsPk.withKey(publicKey, isPrivate = false) { pk -> publicEncrypt(pk, oaepMd, data) }
        } else {
            if (oaepMd != null) {
                throw UnsupportedOperationException("MbedTlsRsa: private-key encrypt supports PKCS1 v1.5 only (no OAEP)")
            }
            MbedTlsPk.withKey(privateKey, isPrivate = true) { pk -> privateEncryptV15(pk, data) }
        }
    }

    fun decrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray {
        val oaepMd = parseTransformation(algorithm)
        return if (usePublicKey) {
            if (oaepMd != null) {
                throw UnsupportedOperationException("MbedTlsRsa: public-key decrypt supports PKCS1 v1.5 only (no OAEP)")
            }
            MbedTlsPk.withKey(publicKey, isPrivate = false) { pk -> publicDecryptV15(pk, data) }
        } else {
            MbedTlsPk.withKey(privateKey, isPrivate = true) { pk -> privateDecrypt(pk, oaepMd, data) }
        }
    }

    /** transformation → OAEP 摘要 (null=PKCS#1 v1.5); NoPadding/非 RSA 点名抛异常。 */
    private fun parseTransformation(algorithm: String): CPointer<mbedtls_md_info_t>? {
        val parts = algorithm.uppercase().replace(" ", "").split("/")
        if (parts.getOrNull(0) != "RSA") {
            throw UnsupportedOperationException("MbedTlsRsa: unsupported algorithm '$algorithm' (only RSA)")
        }
        val padding = parts.getOrNull(2)
        // 无 padding 段 → JDK 默认 PKCS1Padding (PKCS7 写法按 iOS actual 先例同样归入 v1.5)
        if (padding == null || padding == "PKCS1PADDING" || padding == "PKCS7PADDING") return null
        if (padding == "NOPADDING") {
            throw UnsupportedOperationException("MbedTlsRsa: RSA/ECB/NoPadding not supported")
        }
        if (padding.startsWith("OAEPWITH") && padding.endsWith("ANDMGF1PADDING")) {
            val hash = padding.removePrefix("OAEPWITH").removeSuffix("ANDMGF1PADDING")
            return MbedTlsDigest.mdInfo(hash)
        }
        if (padding == "OAEPPADDING") return MbedTlsDigest.mdInfo("SHA1")
        throw UnsupportedOperationException("MbedTlsRsa: unsupported padding in '$algorithm'")
    }

    private fun setOaep(rsa: CPointer<mbedtls_rsa_context>, oaepMd: CPointer<mbedtls_md_info_t>?) {
        if (oaepMd != null) {
            mbedCheck(
                "rsa_set_padding(OAEP)",
                mbedtls_rsa_set_padding(rsa, MBEDTLS_RSA_PKCS_V21, mbedtls_md_get_type(oaepMd))
            )
        }
    }

    private fun publicEncrypt(
        pk: CPointer<mbedtls_pk_context>,
        oaepMd: CPointer<mbedtls_md_info_t>?,
        data: ByteArray
    ): ByteArray = memScoped {
        setOaep(MbedTlsPk.rsa(pk), oaepMd)
        val k = MbedTlsPk.keyLen(pk)
        val chunk = if (oaepMd != null) k - 2 * mbedtls_md_get_size(oaepMd).toInt() - 2 else k - 11
        require(chunk > 0) { "MbedTlsRsa: key too small (k=$k) for padding overhead" }
        val blocks = if (data.isEmpty()) 1 else (data.size + chunk - 1) / chunk
        val out = ByteArray(blocks * k)
        val olen = alloc<ULongVar>()
        var outOff = 0
        MbedTlsRng.withDrbg { drbg ->
            val src = if (data.isEmpty()) ByteArray(1) else data
            src.usePinned { dp ->
                out.usePinned { op ->
                    var off = 0
                    while (true) {
                        val len = minOf(chunk, data.size - off)
                        mbedCheck(
                            "pk_encrypt",
                            lg_pk_encrypt(
                                pk, dp.addressOf(off).reinterpret(), len.convert(),
                                op.addressOf(outOff).reinterpret(), olen.ptr,
                                (out.size - outOff).convert(), drbg
                            )
                        )
                        outOff += olen.value.toInt()
                        off += len
                        if (off >= data.size) break
                    }
                }
            }
        }
        out.copyOf(outOff)
    }

    /**
     * 私钥加密 (hutool KeyType.PrivateKey 向): 手工 PKCS#1 v1.5 type-01 padding + rsa_private 原语。
     * EM 逐字节: EM[0]=0x00, EM[1]=0x01(块类型01), EM[2..k-2-len]=0xFF 填充(≥8 字节, 由块上限 k-11 保证),
     * EM[k-1-len]=0x00 分隔符, EM[k-len..k-1]=M。
     */
    private fun privateEncryptV15(pk: CPointer<mbedtls_pk_context>, data: ByteArray): ByteArray {
        val rsa = MbedTlsPk.rsa(pk)
        val k = MbedTlsPk.keyLen(pk)
        val chunk = k - 11
        require(chunk > 0) { "MbedTlsRsa: key too small (k=$k)" }
        val blocks = if (data.isEmpty()) 1 else (data.size + chunk - 1) / chunk
        val out = ByteArray(blocks * k)
        val em = ByteArray(k)
        MbedTlsRng.withDrbg { drbg ->
            em.usePinned { ep ->
                out.usePinned { op ->
                    var off = 0
                    var outOff = 0
                    while (true) {
                        val len = minOf(chunk, data.size - off)
                        em.fill(0xFF.toByte())
                        em[0] = 0x00
                        em[1] = 0x01
                        em[k - 1 - len] = 0x00
                        data.copyInto(em, k - len, off, off + len)
                        mbedCheck(
                            "rsa_private",
                            lg_rsa_private(rsa, drbg, ep.addressOf(0).reinterpret(), op.addressOf(outOff).reinterpret())
                        )
                        outOff += k
                        off += len
                        if (off >= data.size) break
                    }
                }
            }
        }
        return out
    }

    private fun privateDecrypt(
        pk: CPointer<mbedtls_pk_context>,
        oaepMd: CPointer<mbedtls_md_info_t>?,
        data: ByteArray
    ): ByteArray = memScoped {
        setOaep(MbedTlsPk.rsa(pk), oaepMd)
        val k = MbedTlsPk.keyLen(pk)
        require(data.isNotEmpty() && data.size % k == 0) {
            "MbedTlsRsa: ciphertext length ${data.size} not a positive multiple of key size $k"
        }
        val olen = alloc<ULongVar>()
        val buf = ByteArray(k)
        val parts = ArrayList<ByteArray>(data.size / k)
        MbedTlsRng.withDrbg { drbg ->
            data.usePinned { dp ->
                buf.usePinned { bp ->
                    var off = 0
                    while (off < data.size) {
                        mbedCheck(
                            "pk_decrypt",
                            lg_pk_decrypt(
                                pk, dp.addressOf(off).reinterpret(), k.convert(),
                                bp.addressOf(0).reinterpret(), olen.ptr, k.convert(), drbg
                            )
                        )
                        parts.add(buf.copyOf(olen.value.toInt()))
                        off += k
                    }
                }
            }
        }
        concat(parts)
    }

    /** 公钥解密 (hutool KeyType.PublicKey 向): rsa_public 原语 + type-01 对称校验解包。 */
    private fun publicDecryptV15(pk: CPointer<mbedtls_pk_context>, data: ByteArray): ByteArray {
        val rsa = MbedTlsPk.rsa(pk)
        val k = MbedTlsPk.keyLen(pk)
        require(data.isNotEmpty() && data.size % k == 0) {
            "MbedTlsRsa: ciphertext length ${data.size} not a positive multiple of key size $k"
        }
        val em = ByteArray(k)
        val parts = ArrayList<ByteArray>(data.size / k)
        data.usePinned { dp ->
            em.usePinned { ep ->
                var off = 0
                while (off < data.size) {
                    mbedCheck(
                        "rsa_public",
                        mbedtls_rsa_public(rsa, dp.addressOf(off).reinterpret(), ep.addressOf(0).reinterpret())
                    )
                    parts.add(stripType1Padding(em, k))
                    off += k
                }
            }
        }
        return concat(parts)
    }

    /** EM = 00 01 FF..FF(≥8) 00 || M; 头两字节/填充长度/分隔符逐项校验, 与加密向逐字节对称。 */
    private fun stripType1Padding(em: ByteArray, k: Int): ByteArray {
        if (em[0].toInt() != 0x00 || em[1].toInt() != 0x01) {
            throw IllegalStateException("MbedTlsRsa: bad PKCS#1 type-01 header")
        }
        var i = 2
        while (i < k && em[i] == 0xFF.toByte()) i++
        if (i - 2 < 8 || i >= k || em[i].toInt() != 0x00) {
            throw IllegalStateException("MbedTlsRsa: bad PKCS#1 type-01 padding structure")
        }
        return em.copyOfRange(i + 1, k)
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        if (parts.size == 1) return parts[0]
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }
}
