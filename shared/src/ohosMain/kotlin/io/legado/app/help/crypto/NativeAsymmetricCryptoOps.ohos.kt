package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 非对称加解密, 基于 @ohos.security.cryptoFramework napi 桥接。
 *
 * # 调用链
 * KMP [encrypt]/[decrypt] → [OhosNativeBridge.invokeCryptoSync] (tsfn 发请求 + @CName 回调返回结果) →
 * ArkTS [CryptoBridgeHandler.handleCryptoRequest] →
 * `cryptoFramework.createCipher` + `convertKey` + `init` + `doFinal` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析 base64 返回 ByteArray。
 *
 * # 算法映射
 * JCA transformation → cryptoFramework Cipher spec 由 ArkTS 侧 [CryptoBridgeHandler.mapAsyCodecSpec] 完成:
 * - "RSA" / "RSA/ECB/PKCS1Padding" → `RSA<size>|PKCS1`
 * - "RSA/ECB/OAEPWithSHA-<N>AndMGF1Padding" → `RSA<size>|PKCS1_OAEP|SHA<N>|MGF1_SHA<N>`
 * - "RSA/ECB/NoPadding" → `RSA<size>|NoPadding` (cryptoFramework 可能不支持, 错误透传)
 * RSA keysize 由 ArkTS 侧 convertKey 探测 (1024/2048/3072/4096/8192)。
 *
 * # usePublicKey 语义 (对齐 jvmAndAndroid getKeyType / iOS actual)
 * - encrypt(usePublicKey=true) → 公钥加密; encrypt(usePublicKey=false) → 私钥加密
 * - decrypt(usePublicKey=false) → 私钥解密; decrypt(usePublicKey=true) → 公钥解密
 * cryptoFramework Cipher 支持公钥/私钥双向加解密 (init 时传入对应 key), 故全场景可用,
 * 与 iOS Security.framework 仅单向支持不同。
 *
 * # 密钥格式
 * privateKey: PKCS#8 DER (ArkTS 侧 convertKey type='PKCS#8');
 * publicKey: X.509 DER (ArkTS 侧 convertKey type='X.509')。
 *
 * # 失败处理
 * 桥接未就绪 / ArkTS 运算失败 / 超时 → 抛异常 (与 iOS actual / jvmAndAndroid hutool 失败抛异常一致,
 * 调用方 NativeAsymmetricCrypto.encrypt/decrypt 不捕获, 让 JS 引擎收到错误)。
 *
 * napi 桥接模式参考 [io.legado.app.help.image.OhosImageOps] (tsfn + @CName 回调同步等待)。
 */
actual object NativeAsymmetricCryptoOps {

    actual fun encrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray = invokeAsyCrypto("encrypt", algorithm, usePublicKey, privateKey, publicKey, data)

    actual fun decrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray = invokeAsyCrypto("decrypt", algorithm, usePublicKey, privateKey, publicKey, data)

    /**
     * encrypt/decrypt 共用逻辑: 序列化 payload → invokeCryptoSync → 解析响应 base64 → ByteArray。
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
                privateKey = privateKey?.let { Base64.encodeToString(it) },
                publicKey = publicKey?.let { Base64.encodeToString(it) },
                data = Base64.encodeToString(data)
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
