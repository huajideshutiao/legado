package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 非对称加解密。主实现 mbedTLS [MbedTlsRsa] (v1.5/OAEP + 私钥加密/公钥解密反向 +
 * hutool 同款分块, 无 UI 线程往返), 任意异常回落既有 @ohos.security.cryptoFramework napi 桥接。
 *
 * # napi 回落调用链
 * KMP → [OhosNativeBridge.invokeCryptoSync] (tsfn 发请求 + @CName 回调返回结果) →
 * ArkTS [CryptoBridgeHandler.handleCryptoRequest] →
 * `cryptoFramework.createCipher` + `convertKey` + `init` + `doFinal` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析 base64 返回 ByteArray。
 *
 * # 算法映射 (回落侧)
 * JCA transformation → cryptoFramework Cipher spec 由 ArkTS [CryptoBridgeHandler.mapAsyCodecSpec] 完成:
 * - "RSA" / "RSA/ECB/PKCS1Padding" → `RSA<size>|PKCS1`
 * - "RSA/ECB/OAEPWithSHA-<N>AndMGF1Padding" → `RSA<size>|PKCS1_OAEP|SHA<N>|MGF1_SHA<N>`
 * RSA keysize 由 ArkTS 侧 convertKey 探测 (1024/2048/3072/4096/8192)。
 *
 * # usePublicKey 语义 (对齐 jvmAndAndroid getKeyType)
 * encrypt(true)=公钥加密 / encrypt(false)=私钥加密 / decrypt(false)=私钥解密 / decrypt(true)=公钥解密,
 * mbedTLS 主实现与 cryptoFramework 回落均支持全向。
 *
 * # 密钥格式
 * privateKey: PKCS#8 DER (mbedTLS 另兼容 PKCS#1/PEM); publicKey: X.509 DER (mbedTLS 另兼容 PKCS#1/PEM)。
 */
actual object NativeAsymmetricCryptoOps {

    actual fun encrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray = mbedTlsOrFallback(
        { MbedTlsRsa.encrypt(algorithm, usePublicKey, privateKey, publicKey, data) },
        { invokeAsyCrypto("encrypt", algorithm, usePublicKey, privateKey, publicKey, data) }
    )

    actual fun decrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray = mbedTlsOrFallback(
        { MbedTlsRsa.decrypt(algorithm, usePublicKey, privateKey, publicKey, data) },
        { invokeAsyCrypto("decrypt", algorithm, usePublicKey, privateKey, publicKey, data) }
    )

    /**
     * napi 回落共用逻辑: 序列化 payload → invokeCryptoSync → 解析响应 base64 → ByteArray。
     *
     * @param action "encrypt" 或 "decrypt"
     */
    private fun invokeAsyCrypto(
        action: String,
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray {
        if (!OhosNativeBridge.isCryptoBridgeReady()) {
            throw UnsupportedOperationException(
                "AsymmetricCrypto.$action is not available on 鸿蒙: crypto napi bridge not ready"
            )
        }
        val payload = KS_JSON.encodeToString(
            AsyCryptoPayload(
                algorithm = algorithm,
                usePublicKey = usePublicKey,
                privateKey = privateKey?.let { Base64.encode(it) },
                publicKey = publicKey?.let { Base64.encode(it) },
                data = Base64.encode(data)
            )
        )
        val result = OhosNativeBridge.invokeCryptoSync(action, payload)
            ?: throw UnsupportedOperationException(
                "AsymmetricCrypto.$action on 鸿蒙: crypto bridge returned null (tsfn not registered or timeout)"
            )
        val resp = runCatching { KS_JSON.decodeFromString(CryptoResponse.serializer(), result) }.getOrNull()
            ?: throw UnsupportedOperationException(
                "AsymmetricCrypto.$action on 鸿蒙: failed to parse crypto response: $result"
            )
        if (!resp.ok || resp.data == null) {
            throw UnsupportedOperationException(
                "AsymmetricCrypto.$action on 鸿蒙 failed: ${resp.error ?: "unknown error"}"
            )
        }
        return Base64.decode(resp.data)
    }

    /** encrypt/decrypt 请求 payload (与 ArkTS CryptoBridgeHandler.AsyCryptoPayload 对齐)。 */
    @Serializable
    private data class AsyCryptoPayload(
        val algorithm: String,
        val usePublicKey: Boolean,
        val privateKey: String? = null,
        val publicKey: String? = null,
        val data: String
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
