@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read

/**
 * 鸿蒙 actual: 摘要 / HMAC / AES。平台委托 (2026-10 起 native 不再带 mbedTLS):
 * - SM3/SHA3-* 及其 HMAC: 纯 Kotlin 侧车 ([KotlinDigestNative]/[Sha3Native]), 双端同源;
 * - 其余摘要/HMAC: @ohos.security.cryptoFramework napi 桥 (createMd/createMac);
 * - AES 全模式: 桥 createCipher (NoPadding 裸原语, padding 由 [SymPadding] 在 Kotlin 层补/剥),
 *   AES/GCM 由 NativeSymmetricCrypto 路由到纯 Kotlin [GcmNative];
 * - 随机密钥: /dev/urandom (对齐原 mbedTLS entropy_poll 在鸿蒙沙箱的实现)。
 *
 * # 桥接调用链
 * KMP → [OhosNativeBridge.invokeCryptoSync] (tsfn 发请求 + @CName 回调返回结果) →
 * ArkTS CryptoBridgeHandler → `cryptoFramework.createMd`/`createMac`/`createCipher` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析 base64 返回 ByteArray。
 */
actual object NativeDigestOps {

    actual fun digest(algorithm: String, data: ByteArray): ByteArray {
        KotlinDigestNative.digest(algorithm, data)?.let { return it }
        val payload = KS_JSON.encodeToString(
            DigestPayload(algorithm = algorithm, data = Base64.encode(data))
        )
        return invokeAndParse("digest", payload)
    }
}

actual object NativeHmacOps {

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        KotlinDigestNative.hmac(algorithm, key, data)?.let { return it }
        val payload = KS_JSON.encodeToString(
            HmacPayload(
                algorithm = algorithm,
                key = Base64.encode(key),
                data = Base64.encode(data)
            )
        )
        return invokeAndParse("hmac", payload)
    }
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
    ): ByteArray = PlatformCipherOps.encrypt("AES", mode, key, iv, SymPadding.pad(padding, data, 16, mode))

    actual fun decrypt(
        key: ByteArray,
        data: ByteArray,
        mode: String,
        padding: String,
        iv: ByteArray?
    ): ByteArray = SymPadding.unpad(
        padding,
        PlatformCipherOps.decrypt("AES", mode, key, iv, data),
        16
    )

    /** /dev/urandom 平台熵源 (对齐 hutool KeyUtil.generateKey 的 SecureRandom 语义)。 */
    actual fun randomKey(size: Int): ByteArray = PlatformCipherOps.randomBytes(size)
}

/** 鸿蒙熵源: /dev/urandom (沙箱内可读, musl 无 getrandom 直连分支, 对齐原 entropy_poll 先例)。 */
@OptIn(ExperimentalForeignApi::class)
internal fun urandomBytes(size: Int): ByteArray {
    if (size <= 0) return ByteArray(0)
    val fd = open("/dev/urandom", O_RDONLY)
    require(fd >= 0) { "open /dev/urandom failed" }
    try {
        val out = ByteArray(size)
        out.usePinned { p ->
            var got = 0
            while (got < size) {
                val n = read(fd, p.addressOf(got), (size - got).convert())
                require(n > 0) { "read /dev/urandom failed" }
                got += n.toInt()
            }
        }
        return out
    } finally {
        close(fd)
    }
}

/**
 * 同步调用 crypto 桥接并解析响应为 ByteArray。
 * 桥接未就绪 / 返回 null / 解析失败 / ok=false → 抛 UnsupportedOperationException。
 */
internal fun invokeAndParse(action: String, payloadJson: String): ByteArray {
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

// ===== 桥接 payload / 响应 (与 ArkTS CryptoBridgeHandler 对齐) =====

@Serializable
internal data class DigestPayload(
    val algorithm: String,
    val data: String
)

@Serializable
internal data class HmacPayload(
    val algorithm: String,
    val key: String,
    val data: String
)

/** symEncrypt/symDecrypt 请求 payload (NoPadding 裸原语, padding 已在 Kotlin 层处理)。 */
@Serializable
internal data class SymPayload(
    val algorithm: String,
    val mode: String,
    val key: String,
    val iv: String? = null,
    val data: String
)

@Serializable
internal data class CryptoResponse(
    val ok: Boolean = false,
    val data: String? = null,
    val result: Boolean? = null,
    val error: String? = null
)
