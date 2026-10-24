package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 签名/验签。平台委托: @ohos.security.cryptoFramework napi 桥
 * (2026-10 起 native 不再带 mbedTLS)。
 *
 * # 调用链
 * KMP → [OhosNativeBridge.invokeCryptoSync] (tsfn 发请求 + @CName 回调返回结果) →
 * ArkTS CryptoBridgeHandler → `cryptoFramework.createSign`/`createVerify` + `convertKey` + `sign`/`verify` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析返回 ByteArray/Boolean。
 *
 * # 算法映射 (ArkTS CryptoBridgeHandler.mapSignSpec)
 * - RSA: "MD5withRSA" / "SHA<N>withRSA" → `RSA<size>|PKCS1|<MD5|SHA<N>>`
 * - RSA PSS: "SHA<N>WithRSA/PSS" → `RSA<size>|PSS|SHA<N>|MGF1_SHA<N>`
 * - ECDSA: "SHA<N>withECDSA" → `ECC<size>|SHA<N>`
 * - SM2: "SM2WithSM3" → `SM2_256|SM3`
 * RSA/ECC keysize 由 ArkTS 侧 convertKey 探测。
 *
 * # 密钥格式
 * 经 [RsaDerKeys] 归一: sign 私钥 PKCS#8 DER, verify 公钥 X.509 SPKI DER (RSA 系; ECC/SM2 原样透传)。
 *
 * # 失败处理
 * verify 区分: 运算/密钥错误 → 抛异常; 签名不匹配 → 返回 false (对齐 JCA Signature.verify)。
 */
actual object NativeSignOps {

    actual fun sign(algorithm: String, privateKey: ByteArray?, data: ByteArray): ByteArray {
        if (privateKey == null) {
            throw IllegalArgumentException("Sign.sign: privateKey not set")
        }
        if (!OhosNativeBridge.isCryptoBridgeReady()) {
            throw UnsupportedOperationException(
                "Sign.sign is not available on 鸿蒙: crypto napi bridge not ready"
            )
        }
        val payload = KS_JSON.encodeToString(
            SignPayload(
                algorithm = algorithm,
                privateKey = Base64.encode(normalizeSignKey(algorithm, privateKey, isPrivate = true)),
                data = Base64.encode(data)
            )
        )
        val result = OhosNativeBridge.invokeCryptoSync("sign", payload)
            ?: throw UnsupportedOperationException(
                "Sign.sign on 鸿蒙: crypto bridge returned null (tsfn not registered or timeout)"
            )
        val resp = runCatching { KS_JSON.decodeFromString(CryptoResponse.serializer(), result) }.getOrNull()
            ?: throw UnsupportedOperationException(
                "Sign.sign on 鸿蒙: failed to parse crypto response: $result"
            )
        if (!resp.ok || resp.data == null) {
            throw UnsupportedOperationException(
                "Sign.sign on 鸿蒙 failed: ${resp.error ?: "unknown error"}"
            )
        }
        return Base64.decode(resp.data)
    }

    actual fun verify(
        algorithm: String,
        publicKey: ByteArray?,
        data: ByteArray,
        signature: ByteArray
    ): Boolean {
        if (publicKey == null) {
            throw IllegalArgumentException("Sign.verify: publicKey not set")
        }
        if (!OhosNativeBridge.isCryptoBridgeReady()) {
            throw UnsupportedOperationException(
                "Sign.verify is not available on 鸿蒙: crypto napi bridge not ready"
            )
        }
        val payload = KS_JSON.encodeToString(
            VerifyPayload(
                algorithm = algorithm,
                publicKey = Base64.encode(normalizeSignKey(algorithm, publicKey, isPrivate = false)),
                data = Base64.encode(data),
                signature = Base64.encode(signature)
            )
        )
        val result = OhosNativeBridge.invokeCryptoSync("verify", payload)
            ?: throw UnsupportedOperationException(
                "Sign.verify on 鸿蒙: crypto bridge returned null (tsfn not registered or timeout)"
            )
        val resp = runCatching { KS_JSON.decodeFromString(CryptoResponse.serializer(), result) }.getOrNull()
            ?: throw UnsupportedOperationException(
                "Sign.verify on 鸿蒙: failed to parse crypto response: $result"
            )
        if (!resp.ok || resp.result == null) {
            // ArkTS 运算异常 (key 格式错误 / 算法不支持等): 抛异常 (非签名不匹配)
            throw UnsupportedOperationException(
                "Sign.verify on 鸿蒙 failed: ${resp.error ?: "unknown error"}"
            )
        }
        // resp.result: true→签名匹配, false→签名不匹配 (对齐 JCA Signature.verify, 不抛异常)
        return resp.result
    }

    /**
     * RSA 系签名密钥归一 (PKCS#8/SPKI); ECC/SM2 密钥非 RSA DER 结构, 原样透传
     * (cryptoFramework convertKey 自行识别, 不经 [RsaDerKeys] 的 RSA OID 校验)。
     */
    private fun normalizeSignKey(algorithm: String, key: ByteArray, isPrivate: Boolean): ByteArray {
        if (algorithm.uppercase().contains("ECDSA")) return key
        val der = RsaDerKeys.stripPemToDer(key)
        return runCatching {
            if (isPrivate) RsaDerKeys.toPkcs8Private(der) else RsaDerKeys.toSpkiPublic(der)
        }.getOrDefault(der)
    }

    /** sign 请求 payload (与 ArkTS CryptoBridgeHandler.SignPayload 对齐)。 */
    @Serializable
    private data class SignPayload(
        val algorithm: String,
        val privateKey: String,
        val data: String
    )

    /** verify 请求 payload (与 ArkTS CryptoBridgeHandler.VerifyPayload 对齐)。 */
    @Serializable
    private data class VerifyPayload(
        val algorithm: String,
        val publicKey: String,
        val data: String,
        val signature: String
    )
}
