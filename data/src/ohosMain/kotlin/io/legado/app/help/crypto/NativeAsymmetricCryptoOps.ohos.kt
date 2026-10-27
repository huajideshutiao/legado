package io.legado.app.help.crypto

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlin.io.encoding.Base64

/**
 * 鸿蒙 actual: 非对称加解密。平台委托: @ohos.security.cryptoFramework napi 桥
 * (2026-10 起 native 不再带 mbedTLS)。
 *
 * # 调用链
 * KMP → [OhosNativeBridge.invokeCryptoSync] (tsfn 发请求 + @CName 回调返回结果) →
 * ArkTS CryptoBridgeHandler → `cryptoFramework.createCipher` + `convertKey` + `init` + `doFinal` →
 * `legado.cryptoCallback(requestId, resultJson)` 回送结果 → KMP 解析 base64 返回 ByteArray。
 *
 * transformation 已由 commonMain [AsymCipherSpec.parse] 归一, 本层与 ArkTS 桥只消费规范形态
 * (padding token + keyBits + chunk), 桥不解析 JCA transformation 字符串、不探测 keysize:
 * - 密钥转换与分块循环都在桥侧按整次请求执行一次 (convertKey 一次, doFinal 按块循环);
 * - RSA keysize 由 Kotlin 侧 [RsaDerKeys.modulusSize] 从 DER 模长算出, 单一来源。
 *
 * # usePublicKey 语义 (对齐 jvmAndAndroid getKeyType)
 * encrypt(true)=公钥加密 / decrypt(false)=私钥解密 (PKCS1 v1.5 / OAEP / NoPadding 均支持,
 * OAEP 私钥解密为 cryptoFramework 官方支持的 DECRYPT_MODE + PriKey 组合);
 * 私钥加密/公钥解密反向 cryptoFramework 不支持 (须先经 sign 原语), 点名抛异常。
 *
 * # 分块语义 (对齐 hutool/mbedTLS 主实现)
 * 加密块 k-11 (OAEP 为 k-2h-2), 解密块 k, 多块输出顺序拼接; NoPadding 输入须恰为模长字节。
 *
 * # 密钥格式
 * 经 [RsaDerKeys] 归一: 私钥 PKCS#8 DER, 公钥 X.509 SPKI DER (PEM/PKCS#1 输入自动转换);
 * SM2 密钥按 PKCS#8/SPKI 原样透传 (SM2 OID 非 RSA, 不经 RsaDerKeys)。
 */
actual object NativeAsymmetricCryptoOps {

    actual fun encrypt(
        spec: AsymCipherSpec,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray {
        if (!usePublicKey) {
            throw UnsupportedOperationException(
                "AsymmetricCrypto.encrypt with privateKey is not supported on 鸿蒙 " +
                    "(cryptoFramework RSA encrypt requires public key)"
            )
        }
        val der = RsaDerKeys.stripPemToDer(
            publicKey ?: throw IllegalArgumentException("AsymmetricCrypto.encrypt: publicKey not set")
        )
        return when (spec) {
            is AsymCipherSpec.Sm2 -> invokeAsyCrypto(
                "encrypt", spec, usePublicKey, null, Base64.encode(der), data, chunk = 0, keyBytes = 0
            )

            is AsymCipherSpec.Rsa -> {
                val pkcs1 = RsaDerKeys.toPkcs1Public(der)
                val k = RsaDerKeys.modulusSize(pkcs1, isPrivate = false)
                val chunk = chunkSize(spec, k)
                require(chunk > 0) { "RSA key too small (k=$k) for padding overhead" }
                if (spec.padding == AsymCipherSpec.RsaPadding.NO_PADDING) {
                    require(data.size == k) {
                        "RSA/ECB/NoPadding requires input of exactly key size $k bytes (got ${data.size})"
                    }
                }
                // 空输入仍产出一个完整块 (对齐 JCA RSA Cipher: doFinal(空) 输出 k 字节填充块)
                val src = if (data.isEmpty()) byteArrayOf(0) else data
                invokeAsyCrypto(
                    "encrypt", spec, usePublicKey, null,
                    Base64.encode(RsaDerKeys.toSpkiPublic(der)), src, chunk, k
                )
            }
        }
    }

    actual fun decrypt(
        spec: AsymCipherSpec,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray {
        if (usePublicKey) {
            throw UnsupportedOperationException(
                "AsymmetricCrypto.decrypt with publicKey is not supported on 鸿蒙 " +
                    "(cryptoFramework RSA decrypt requires private key)"
            )
        }
        val der = RsaDerKeys.stripPemToDer(
            privateKey ?: throw IllegalArgumentException("AsymmetricCrypto.decrypt: privateKey not set")
        )
        return when (spec) {
            is AsymCipherSpec.Sm2 -> invokeAsyCrypto(
                "decrypt", spec, usePublicKey, Base64.encode(der), null, data, chunk = 0, keyBytes = 0
            )

            is AsymCipherSpec.Rsa -> {
                val pkcs1 = RsaDerKeys.toPkcs1Private(der)
                val k = RsaDerKeys.modulusSize(pkcs1, isPrivate = true)
                require(data.isNotEmpty() && data.size % k == 0) {
                    "ciphertext length ${data.size} not a positive multiple of key size $k"
                }
                invokeAsyCrypto(
                    "decrypt", spec, usePublicKey,
                    Base64.encode(RsaDerKeys.toPkcs8Private(der)), null, data, k, k
                )
            }
        }
    }

    /** hutool 分块语义: 加密 k-11 (OAEP k-2h-2), 解密 k (调用方按方向传入)。 */
    private fun chunkSize(spec: AsymCipherSpec.Rsa, k: Int): Int = when (spec.padding) {
        AsymCipherSpec.RsaPadding.NO_PADDING -> k
        AsymCipherSpec.RsaPadding.OAEP -> k - 2 * spec.oaepDigestBytes - 2
        AsymCipherSpec.RsaPadding.PKCS1 -> k - 11
    }

    /** 整次请求走桥一次: payload 携带归一 padding token + keyBits + 分块大小, 桥侧一次 convertKey 后按块循环。 */
    private fun invokeAsyCrypto(
        action: String,
        spec: AsymCipherSpec,
        usePublicKey: Boolean,
        privateB64: String?,
        publicB64: String?,
        data: ByteArray,
        chunk: Int,
        keyBytes: Int,
    ): ByteArray {
        if (!OhosNativeBridge.isCryptoBridgeReady()) {
            throw UnsupportedOperationException(
                "AsymmetricCrypto is not available on 鸿蒙: crypto napi bridge not ready"
            )
        }
        val payload = KS_JSON.encodeToString(
            AsyCryptoPayload(
                padding = paddingToken(spec),
                keyBits = keyBytes * 8,
                usePublicKey = usePublicKey,
                privateKey = privateB64,
                publicKey = publicB64,
                chunk = chunk,
                data = Base64.encode(data)
            )
        )
        val result = OhosNativeBridge.invokeCryptoSync(action, payload)
            ?: throw UnsupportedOperationException(
                "AsymmetricCrypto on 鸿蒙: crypto bridge returned null (tsfn not registered or timeout)"
            )
        val resp = runCatching { KS_JSON.decodeFromString(CryptoResponse.serializer(), result) }.getOrNull()
            ?: throw UnsupportedOperationException(
                "AsymmetricCrypto on 鸿蒙: failed to parse crypto response: $result"
            )
        if (!resp.ok || resp.data == null) {
            throw UnsupportedOperationException(
                "AsymmetricCrypto on 鸿蒙 failed: ${resp.error ?: "unknown error"}"
            )
        }
        return Base64.decode(resp.data)
    }

    /** 归一 padding token (与 ArkTS CryptoBridgeHandler 的 switch 对齐)。 */
    private fun paddingToken(spec: AsymCipherSpec): String = when (spec) {
        is AsymCipherSpec.Sm2 -> "SM2"
        is AsymCipherSpec.Rsa -> when (spec.padding) {
            AsymCipherSpec.RsaPadding.PKCS1 -> "PKCS1"
            AsymCipherSpec.RsaPadding.NO_PADDING -> "NOPADDING"
            AsymCipherSpec.RsaPadding.OAEP -> "OAEP_SHA" + when (spec.oaepDigestBits) {
                160 -> 1
                else -> spec.oaepDigestBits
            }
        }
    }

    /** encrypt/decrypt 请求 payload (与 ArkTS CryptoBridgeHandler.AsyCryptoPayload 对齐)。 */
    @kotlinx.serialization.Serializable
    private data class AsyCryptoPayload(
        val padding: String,
        val keyBits: Int,
        val usePublicKey: Boolean,
        val privateKey: String? = null,
        val publicKey: String? = null,
        val chunk: Int = 0,
        val data: String
    )
}
