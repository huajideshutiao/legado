package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 摘要 / HMAC / AES。主实现 mbedTLS ([MbedTlsDigest]/[MbedTlsCipher]/[MbedTlsRng],
 * 全模式/全 padding 且不走 UI 线程往返), 任意异常回落既有 @ohos.security.cryptoFramework napi 桥接
 * (桥仅支持 AES ECB+PKCS7)。
 *
 * mbedTLS 目标码由 ohosApp CMake 编入 liblegado_napi.so, 与 liblegado_shared.so 同 dlopen 组解析符号;
 * 若 napi so 缺 mbedtls 目标码, 是加载/链接期失败而非运行期, runtime 回落救不了缺符号。
 *
 * # napi 回落调用链
 * KMP → [OhosNativeBridge.invokeCryptoSync] → ArkTS CryptoBridgeHandler →
 * `cryptoFramework.createMd`/`createMac`/`createCipher` + `SymKeyGenerator` + `init` + `doFinal` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析 base64 返回 ByteArray。
 */
actual object NativeDigestOps {

    actual fun digest(algorithm: String, data: ByteArray): ByteArray = mbedTlsOrFallback(
        { MbedTlsDigest.digest(algorithm, data) },
        {
            val payload = KS_JSON.encodeToString(
                DigestPayload(algorithm = algorithm, data = Base64.encode(data))
            )
            invokeAndParse("digest", payload)
        }
    )
}

actual object NativeHmacOps {

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray = mbedTlsOrFallback(
        { MbedTlsDigest.hmac(algorithm, key, data) },
        {
            val payload = KS_JSON.encodeToString(
                HmacPayload(
                    algorithm = algorithm,
                    key = Base64.encode(key),
                    data = Base64.encode(data)
                )
            )
            invokeAndParse("hmac", payload)
        }
    )
}

actual object NativeAesOps {

    actual fun encryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray =
        encrypt(key, data, "ECB", "PKCS7PADDING", null)

    actual fun decryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray =
        decrypt(key, data, "ECB", "PKCS7PADDING", null)

    actual fun encrypt(
        key: ByteArray,
        data: ByteArray,
        mode: String,
        padding: String,
        iv: ByteArray?
    ): ByteArray = mbedTlsOrFallback(
        { MbedTlsCipher.encrypt("AES", mode, padding, key, iv, data) },
        {
            requireEcbPkcs7(mode, padding)
            napiAes("aesEncrypt", key, data)
        }
    )

    actual fun decrypt(
        key: ByteArray,
        data: ByteArray,
        mode: String,
        padding: String,
        iv: ByteArray?
    ): ByteArray = mbedTlsOrFallback(
        { MbedTlsCipher.decrypt("AES", mode, padding, key, iv, data) },
        {
            requireEcbPkcs7(mode, padding)
            napiAes("aesDecrypt", key, data)
        }
    )

    /** 主走 mbedTLS CTR_DRBG; 回落 K/N Random (鸿蒙走平台熵源, 同 UuidUtils.native.kt 先例)。 */
    actual fun randomKey(size: Int): ByteArray = mbedTlsOrFallback(
        { MbedTlsRng.random(size) },
        { kotlin.random.Random.Default.nextBytes(ByteArray(size)) }
    )

    private fun napiAes(action: String, key: ByteArray, data: ByteArray): ByteArray {
        val payload = KS_JSON.encodeToString(
            AesPayload(key = Base64.encode(key), data = Base64.encode(data))
        )
        return invokeAndParse(action, payload)
    }

    /** ArkTS CryptoBridgeHandler 的 cipherSpec 硬编码 ECB|PKCS7, 回落面外显式点名。 */
    private fun requireEcbPkcs7(mode: String, padding: String) {
        if (mode != "ECB" || padding != "PKCS7PADDING") {
            throw UnsupportedOperationException(
                "NativeAesOps: AES/$mode/$padding napi fallback unavailable on 鸿蒙 (bridge only ECB/PKCS7Padding)"
            )
        }
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
