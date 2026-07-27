@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import io.legado.app.nativecrypto.mbedtls.MBEDTLS_RSA_PKCS_V21
import io.legado.app.nativecrypto.mbedtls.lg_md_none
import io.legado.app.nativecrypto.mbedtls.lg_pk_sign
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_get_type
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_info_t
import io.legado.app.nativecrypto.mbedtls.mbedtls_pk_verify
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

/**
 * mbedTLS pk 层签名/验签 (iOS/鸿蒙主实现, 失败由 actual 回落 Security/napi)。
 *
 * - v1.5: MD5withRSA (用户必须项) / SHA1/224/256/384/512withRSA / RIPEMD160withRSA;
 * - PSS: SHA*withRSA/PSS (rsa_set_padding V21, 盐长=摘要长, MGF1 同 hash, 对齐 JCA/BC 默认);
 * - NONEwithRSA: md=NONE 原文直签 (v1.5 无 DigestInfo, 对齐 JCA);
 * - ECDSA: 裁剪不含 ECP, 直接点名抛异常回落 (iOS Security 支持 ECDSA)。
 *
 * pk_sign/pk_verify 收摘要而非原文, 消息 hash 由 [MbedTlsDigest] 先行计算 (对齐 JCA Signature 内部 hash)。
 */
internal object MbedTlsSign {

    fun sign(algorithm: String, privateKey: ByteArray?, data: ByteArray): ByteArray {
        val spec = resolve(algorithm)
        val hash = spec.mdInfo?.let { MbedTlsDigest.digest(spec.mdName, data) } ?: data
        return MbedTlsPk.withKey(privateKey, isPrivate = true) { pk ->
            val rsa = MbedTlsPk.rsa(pk)
            if (spec.pss) {
                mbedCheck(
                    "rsa_set_padding(PSS)",
                    mbedtls_rsa_set_padding(rsa, MBEDTLS_RSA_PKCS_V21, mdType(spec))
                )
            }
            val sigSize = maxOf(MbedTlsPk.keyLen(pk), 64)
            val sig = ByteArray(sigSize)
            memScoped {
                val slen = alloc<ULongVar>()
                MbedTlsRng.withDrbg { drbg ->
                    hash.usePinnedInput { hp, hlen ->
                        sig.usePinned { sp ->
                            mbedCheck(
                                "pk_sign($algorithm)",
                                lg_pk_sign(
                                    pk, mdType(spec), hp, hlen,
                                    sp.addressOf(0).reinterpret(), sigSize.convert(), slen.ptr, drbg
                                )
                            )
                        }
                    }
                }
                sig.copyOf(slen.value.toInt())
            }
        }
    }

    fun verify(algorithm: String, publicKey: ByteArray?, data: ByteArray, signature: ByteArray): Boolean {
        val spec = resolve(algorithm)
        val hash = spec.mdInfo?.let { MbedTlsDigest.digest(spec.mdName, data) } ?: data
        return MbedTlsPk.withKey(publicKey, isPrivate = false) { pk ->
            val rsa = MbedTlsPk.rsa(pk)
            if (spec.pss) {
                mbedCheck(
                    "rsa_set_padding(PSS)",
                    mbedtls_rsa_set_padding(rsa, MBEDTLS_RSA_PKCS_V21, mdType(spec))
                )
            }
            val ret = hash.usePinnedInput { hp, hlen ->
                signature.usePinnedInput { sp, sl ->
                    mbedtls_pk_verify(pk, mdType(spec), hp, hlen, sp, sl)
                }
            }
            // 非 0 一律按验签不通过返回 false (对齐 JCA verify 与 iOS actual 行为, 密钥问题已在解析期抛出)
            ret == 0
        }
    }

    private class SignSpec(val mdName: String, val mdInfo: CPointer<mbedtls_md_info_t>?, val pss: Boolean)

    private fun mdType(spec: SignSpec) =
        spec.mdInfo?.let { mbedtls_md_get_type(it) } ?: lg_md_none()

    /** JCA Signature 算法名 → (摘要, PSS); ECDSA/未知组合点名抛异常。 */
    private fun resolve(algorithm: String): SignSpec {
        val upper = algorithm.uppercase().replace(" ", "").replace("-", "")
        if (upper.contains("ECDSA")) {
            throw UnsupportedOperationException(
                "MbedTlsSign: ECDSA unavailable (mbedTLS trimmed build has no ECP), falling back to platform impl"
            )
        }
        val i = upper.indexOf("WITH")
        if (i < 0) throw UnsupportedOperationException("MbedTlsSign: unsupported algorithm '$algorithm'")
        val hashPart = upper.substring(0, i)
        val keyPart = upper.substring(i + 4)
        if (keyPart != "RSA" && keyPart != "RSA/PSS") {
            throw UnsupportedOperationException("MbedTlsSign: unsupported algorithm '$algorithm'")
        }
        val pss = keyPart == "RSA/PSS"
        if (hashPart == "NONE") {
            if (pss) throw UnsupportedOperationException("MbedTlsSign: NONEwithRSA/PSS not supported")
            return SignSpec("NONE", null, false)
        }
        return SignSpec(hashPart, MbedTlsDigest.mdInfo(hashPart), pss)
    }
}
