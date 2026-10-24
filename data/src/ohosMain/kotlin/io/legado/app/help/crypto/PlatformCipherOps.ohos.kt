package io.legado.app.help.crypto

import io.legado.app.utils.KS_JSON
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 对称 NoPadding 裸原语 (cryptoFramework napi 桥 symEncrypt/symDecrypt) + 熵源 (/dev/urandom)。
 *
 * padding 由 nativeMain [SymPadding] 处理, 桥侧恒 NoPadding; GCM 不经本层 (纯 Kotlin [GcmNative],
 * 经 [aesEcbEncryptBlock] 取单块原语 — 注意单块走桥, GCM 大数据量时为多次桥接往返, 书源场景可接受)。
 */
internal actual object PlatformCipherOps {

    actual fun encrypt(
        algorithm: String,
        mode: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray {
        val payload = KS_JSON.encodeToString(
            SymPayload(
                algorithm = algorithm,
                mode = mode,
                key = Base64.encode(key),
                iv = iv?.let { Base64.encode(it) },
                data = Base64.encode(data)
            )
        )
        return invokeAndParse("symEncrypt", payload)
    }

    actual fun decrypt(
        algorithm: String,
        mode: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray {
        val payload = KS_JSON.encodeToString(
            SymPayload(
                algorithm = algorithm,
                mode = mode,
                key = Base64.encode(key),
                iv = iv?.let { Base64.encode(it) },
                data = Base64.encode(data)
            )
        )
        return invokeAndParse("symDecrypt", payload)
    }

    /** AES-ECB 单块加密 (16 字节), 供纯 Kotlin [GcmNative] 的 CTR/GHASH 使用。 */
    actual fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray =
        encrypt("AES", "ECB", key, null, block)

    actual fun randomBytes(size: Int): ByteArray = urandomBytes(size)
}
