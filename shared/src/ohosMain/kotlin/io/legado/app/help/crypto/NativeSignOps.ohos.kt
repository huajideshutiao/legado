package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 签名/验签, 基于 @ohos.security.cryptoFramework napi 桥接。
 *
 * # 调用链
 * KMP [sign]/[verify] → [OhosNativeBridge.invokeCryptoSync] (tsfn 发请求 + @CName 回调返回结果) →
 * ArkTS [CryptoBridgeHandler.handleCryptoRequest] →
 * `cryptoFramework.createSign`/`createVerify` + `convertKey` + `init` + `sign`/`verify` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析返回 ByteArray/Boolean。
 *
 * # 算法映射
 * JCA Signature algorithm → cryptoFramework Sign/Verify spec 由 ArkTS 侧
 * [CryptoBridgeHandler.mapSignSpec] 完成:
 * - RSA: "MD5withRSA" / "SHA<N>withRSA" → `RSA<size>|PKCS1|<MD5|SHA<N>>`
 * - RSA PSS: "SHA<N>WithRSA/PSS" → `RSA<size>|PSS|SHA<N>|MGF1|SHA<N>`
 * - ECDSA: "SHA<N>withECDSA" → `ECC<size>|SHA<N>`
 * RSA/ECC keysize 由 ArkTS 侧 convertKey 探测 (RSA: 1024/2048/3072/4096/8192; ECC: 256/384/521)。
 *
 * # 密钥格式
 * privateKey: PKCS#8 DER (sign 用); publicKey: X.509 DER (verify 用)。
 *
 * # 失败处理
 * 桥接未就绪 / ArkTS 运算失败 / 超时 → 抛异常 (与 iOS actual / jvmAndAndroid hutool 失败抛异常一致)。
 * verify 区分: ArkTS 运算异常 (如 key 格式错误) → 抛异常; 签名不匹配 → 返回 false (不抛异常,
 * 对齐 JCA Signature.verify 语义, 由 ArkTS 侧 verifyData 返回 boolean 透传)。
 *
 * napi 桥接模式参考 [NativeAsymmetricCryptoOps] / [io.legado.app.help.image.OhosImageOps]。
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
                privateKey = Base64.encodeToString(privateKey),
                data = Base64.encodeToString(data)
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
                publicKey = Base64.encodeToString(publicKey),
                data = Base64.encodeToString(data),
                signature = Base64.encodeToString(signature)
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
