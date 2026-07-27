package io.legado.app.help.crypto

import com.soywiz.krypto.AES
import com.soywiz.krypto.Hmac
import com.soywiz.krypto.MD5
import com.soywiz.krypto.SHA1
import com.soywiz.krypto.SHA256
import com.soywiz.krypto.SHA512

/**
 * iOS actual: 摘要 / HMAC / AES-ECB-PKCS7, 基于 krypto 库纯 Kotlin 实现。
 *
 * 从 nativeMain 原始实现搬迁 (CryptoHelper / BackupAES / NativeSymmetricCrypto / JsEncodeUtils /
 * JsExtensionsPlatform 中的 krypto 调用), 逻辑零变化。
 *
 * 行为字节级对齐 jvmAndAndroidMain (javax.crypto / hutool) 与鸿蒙 napi actual。
 */
actual object NativeDigestOps {

    actual fun digest(algorithm: String, data: ByteArray): ByteArray {
        // krypto 顶层对象: MD5 / SHA1 / SHA256 / SHA512, digest(ByteArray) 返回原始摘要字节
        return when (algorithm.uppercase()) {
            "MD5" -> MD5.digest(data)
            "SHA-1", "SHA1" -> SHA1.digest(data)
            "SHA-256", "SHA256" -> SHA256.digest(data)
            "SHA-512", "SHA512" -> SHA512.digest(data)
            else -> throw IllegalArgumentException("Unsupported digest algorithm: $algorithm")
        }
    }
}

actual object NativeHmacOps {

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        // 去掉 HMAC/Hmac 前缀 (含连字符变体), 归一为标准算法名后再匹配 krypto HMAC.Algorithm
        val upper = algorithm.uppercase()
        val normalized = when {
            upper.startsWith("HMAC-") -> upper.substring(5)
            upper.startsWith("HMAC") -> upper.substring(4)
            else -> upper
        }
        // krypto HMAC 构造: HMAC(algorithm, key).digest(data) 返回原始 HMAC 字节
        val alg = when (normalized) {
            "MD5" -> Hmac.Algorithm.MD5
            "SHA-1", "SHA1" -> Hmac.Algorithm.SHA1
            "SHA-256", "SHA256" -> Hmac.Algorithm.SHA256
            "SHA-512", "SHA512" -> Hmac.Algorithm.SHA512
            else -> throw IllegalArgumentException("Unsupported HMac algorithm: $algorithm")
        }
        return Hmac(alg, key).digest(data)
    }
}

actual object NativeAesOps {

    actual fun encryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray {
        // krypto AES: ECB + PKCS7Padding (在 AES 块大小 16 下与 PKCS5 等价)
        val aes = AES(key, mode = AES.Mode.ECB, padding = AES.Padding.PKCS7Padding)
        return aes.encrypt(data)
    }

    actual fun decryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray {
        val aes = AES(key, mode = AES.Mode.ECB, padding = AES.Padding.PKCS7Padding)
        return aes.decrypt(data)
    }
}
