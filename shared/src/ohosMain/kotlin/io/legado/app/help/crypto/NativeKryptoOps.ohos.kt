package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 摘要 / HMAC / AES-ECB-PKCS7, 基于 @ohos.security.cryptoFramework napi 桥接。
 *
 * # 调用链
 * KMP digest/hmac/aesEncrypt/aesDecrypt → [OhosNativeBridge.invokeCryptoSync] →
 * ArkTS [CryptoBridgeHandler.handleCryptoRequest] →
 * `cryptoFramework.createMd`/`createMac`/`createCipher` + `SymKeyGenerator` + `init` + `doFinal` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析 base64 返回 ByteArray。
 *
 * # 算法映射 (KMP → ArkTS cryptoFramework)
 * - digest: MD5/SHA-1/SHA-256/SHA-512 → createMd(归一化算法名)
 * - hmac: HmacMD5/HmacSHA256 等 → createMac(归一化算法名) + SymKeyGenerator('HMAC') convertKey
 * - aes: AES/ECB/PKCS7 → createCipher('AES<size>|ECB|PKCS7'), key size 由 key 字节长度推断
 *   (16→AES128, 24→AES192, 32→AES256)
 *
 * # 失败处理
 * 桥接未就绪 / ArkTS 运算失败 / 超时 → 抛异常 (与 iOS actual / jvmAndAndroid 失败抛异常一致)。
 *
 * napi 桥接模式参考 [NativeSignOps] / [NativeAsymmetricCryptoOps]。
 */
actual object NativeDigestOps {

    actual fun digest(algorithm: String, data: ByteArray): ByteArray {
        val payload = KS_JSON.encodeToString(
            DigestPayload(algorithm = algorithm, data = Base64.encodeToString(data))
        )
        return invokeAndParse("digest", payload)
    }
}

actual object NativeHmacOps {

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val payload = KS_JSON.encodeToString(
            HmacPayload(
                algorithm = algorithm,
                key = Base64.encodeToString(key),
                data = Base64.encodeToString(data)
            )
        )
        return invokeAndParse("hmac", payload)
    }
}

actual object NativeAesOps {

    actual fun encryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray {
        val payload = KS_JSON.encodeToString(
            AesPayload(key = Base64.encodeToString(key), data = Base64.encodeToString(data))
        )
        return invokeAndParse("aesEncrypt", payload)
    }

    actual fun decryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray {
        val payload = KS_JSON.encodeToString(
            AesPayload(key = Base64.encodeToString(key), data = Base64.encodeToString(data))
        )
        return invokeAndParse("aesDecrypt", payload)
    }
}

// ===== 桥接 payload / 响应 (与 ArkTS CryptoBridgeHandler 对齐) =====

@Serializable
private data class DigestPayload(
    val algorithm: String,
    val data: String
)

@Serializable
private data class HmacPayload(
    val algorithm: String,
    val key: String,
    val data: String
)

@Serializable
private data class AesPayload(
    val key: String,
    val data: String
)

@Serializable
private data class CryptoResponse(
    val ok: Boolean = false,
    val data: String? = null,
    val error: String? = null
)

/**
 * 同步调用 crypto 桥接并解析响应为 ByteArray。
 * 桥接未就绪 / 返回 null / 解析失败 / ok=false → 抛 UnsupportedOperationException。
 */
private fun invokeAndParse(action: String, payloadJson: String): ByteArray {
    if (!OhosNativeBridge.isCryptoBridgeReady()) {
        throw UnsupportedOperationException(
            "NativeKryptoOps.$action is not available on 鸿蒙: crypto napi bridge not ready"
        )
    }
    val result = OhosNativeBridge.invokeCryptoSync(action, payloadJson)
        ?: throw UnsupportedOperationException(
            "NativeKryptoOps.$action on 鸿蒙: crypto bridge returned null (tsfn not registered or timeout)"
        )
    val resp = runCatching { KS_JSON.decodeFromString(CryptoResponse.serializer(), result) }.getOrNull()
        ?: throw UnsupportedOperationException(
            "NativeKryptoOps.$action on 鸿蒙: failed to parse crypto response: $result"
        )
    if (!resp.ok || resp.data == null) {
        throw UnsupportedOperationException(
            "NativeKryptoOps.$action on 鸿蒙 failed: ${resp.error ?: "unknown error"}"
        )
    }
    return Base64.decode(resp.data)
}
