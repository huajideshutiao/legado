package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 签名/验签。主实现 mbedTLS [MbedTlsSign] (RSA v1.5 含 MD5withRSA、PSS、NONEwithRSA,
 * 无 UI 线程往返), 任意异常回落既有 @ohos.security.cryptoFramework napi 桥接;
 * ECDSA 在 mbedTLS 裁剪外 (无 ECP), 主实现点名抛异常后由 napi 回落承接。
 *
 * # napi 回落调用链
 * KMP → [OhosNativeBridge.invokeCryptoSync] (tsfn 发请求 + @CName 回调返回结果) →
 * ArkTS [CryptoBridgeHandler.handleCryptoRequest] →
 * `cryptoFramework.createSign`/`createVerify` + `convertKey` + `init` + `sign`/`verify` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析返回 ByteArray/Boolean。
 *
 * # 算法映射 (回落侧, ArkTS [CryptoBridgeHandler.mapSignSpec])
 * - RSA: "MD5withRSA" / "SHA<N>withRSA" → `RSA<size>|PKCS1|<MD5|SHA<N>>`
 * - RSA PSS: "SHA<N>WithRSA/PSS" → `RSA<size>|PSS|SHA<N>|MGF1|SHA<N>`
 * - ECDSA: "SHA<N>withECDSA" → `ECC<size>|SHA<N>`
 *
 * # 密钥格式
 * privateKey: PKCS#8 DER (sign 用); publicKey: X.509 DER (verify 用); mbedTLS 另兼容 PKCS#1/PEM。
 *
 * # 失败处理
 * verify 区分: 运算/密钥错误 → 抛异常; 签名不匹配 → 返回 false (对齐 JCA Signature.verify)。
 */
actual object NativeSignOps {

    actual fun sign(algorithm: String, privateKey: ByteArray?, data: ByteArray): ByteArray = mbedTlsOrFallback(
        { MbedTlsSign.sign(algorithm, privateKey, data) },
        { napiSign(algorithm, privateKey, data) }
    )

    actual fun verify(
        algorithm: String,
        publicKey: ByteArray?,
        data: ByteArray,
        signature: ByteArray
    ): Boolean = mbedTlsOrFallback(
        { MbedTlsSign.verify(algorithm, publicKey, data, signature) },
        { napiVerify(algorithm, publicKey, data, signature) }
    )

    private fun napiSign(algorithm: String, privateKey: ByteArray?, data: ByteArray): ByteArray {
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
                privateKey = Base64.encode(privateKey),
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

    private fun napiVerify(
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
                publicKey = Base64.encode(publicKey),
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

    /** ArkTS → Kotlin 统一响应 (与 ArkTS CryptoBridgeHandler.CryptoResponse 对齐)。 */
    @Serializable
    private data class CryptoResponse(
        val ok: Boolean = false,
        val data: String? = null,
        val result: Boolean? = null,
        val error: String? = null
    )
}
