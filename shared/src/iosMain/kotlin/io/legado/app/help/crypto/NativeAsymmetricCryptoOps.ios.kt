@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA1
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA224
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA256
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA384
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA512
import platform.Security.kSecKeyAlgorithmRSAEncryptionPKCS1

/**
 * iOS actual: 非对称加解密。主实现 mbedTLS [MbedTlsOps] (v1.5/OAEP + 私钥加密/公钥解密反向 +
 * hutool 同款分块), 任意异常回落既有 Security.framework 路径 (仅公钥加密/私钥解密单向)。
 * 原 "iOS 私钥加密向 Security 无解" 的缺口由 mbedTLS 主实现补上。
 *
 * 回落路径算法映射 (对齐 jvmAndAndroid JCA Cipher transformation):
 * - RSA / RSA/ECB/PKCS1Padding(默认) → kSecKeyAlgorithmRSAEncryptionPKCS1;
 * - RSA/ECB/OAEPWithSHA-1/224/256/384/512AndMGF1Padding → 对应 OAEPSHA*;
 * - 其他 (RSA/ECB/NoPadding, ECIES, 非 RSA) 抛 UnsupportedOperationException。
 *
 * 密钥格式: 私钥 PKCS#8/PKCS#1 DER 或 PEM (mbedTLS pk_parse 自动识别; Security 回落仅 DER)。
 */
@OptIn(ExperimentalForeignApi::class)
actual object NativeAsymmetricCryptoOps {

    actual fun encrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray = mbedTlsOrFallback(
        { MbedTlsOps.rsaEncrypt(algorithm, usePublicKey, privateKey, publicKey, data) },
        { legacyEncrypt(algorithm, usePublicKey, publicKey, data) }
    )

    actual fun decrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray = mbedTlsOrFallback(
        { MbedTlsOps.rsaDecrypt(algorithm, usePublicKey, privateKey, publicKey, data) },
        { legacyDecrypt(algorithm, usePublicKey, privateKey, data) }
    )

    /** Security.framework 回落: 仅公钥加密 (SecKeyCreateEncryptedData 需公钥)。 */
    private fun legacyEncrypt(
        algorithm: String,
        usePublicKey: Boolean,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray {
        if (!usePublicKey) {
            throw UnsupportedOperationException(
                "iOS AsymmetricCrypto.encrypt with privateKey is not supported " +
                    "(Security.framework SecKeyCreateEncryptedData requires public key)"
            )
        }
        val secKey = IosCryptoNative.secKeyFromDer(
            publicKey ?: throw IllegalArgumentException("AsymmetricCrypto.encrypt: publicKey not set"),
            isPrivate = false,
            keyType = kSecAttrKeyTypeRSA
        )
        return try {
            val alg = rsaEncryptionAlgorithm(algorithm)
            val plainData = IosCryptoNative.cfData(data)
                ?: throw UnsupportedOperationException("iOS crypto: CFDataCreate returned null")
            try {
                memScoped {
                    val err = allocCFError()
                    val cipher = SecKeyCreateEncryptedData(secKey, alg, plainData, err)
                    if (cipher == null) {
                        IosCryptoNative.throwIosCryptoError("SecKeyCreateEncryptedData($algorithm)", err.pointed.value)
                    }
                    val bytes = IosCryptoNative.cfDataToBytes(cipher)
                    CFRelease(cipher)
                    bytes
                }
            } finally {
                CFRelease(plainData)
            }
        } finally {
            CFRelease(secKey)
        }
    }

    /** Security.framework 回落: 仅私钥解密 (SecKeyCreateDecryptedData 需私钥)。 */
    private fun legacyDecrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        data: ByteArray
    ): ByteArray {
        if (usePublicKey) {
            throw UnsupportedOperationException(
                "iOS AsymmetricCrypto.decrypt with publicKey is not supported " +
                    "(Security.framework SecKeyCreateDecryptedData requires private key)"
            )
        }
        val secKey = IosCryptoNative.secKeyFromDer(
            privateKey ?: throw IllegalArgumentException("AsymmetricCrypto.decrypt: privateKey not set"),
            isPrivate = true,
            keyType = kSecAttrKeyTypeRSA
        )
        return try {
            val alg = rsaEncryptionAlgorithm(algorithm)
            val cipherData = IosCryptoNative.cfData(data)
                ?: throw UnsupportedOperationException("iOS crypto: CFDataCreate returned null")
            try {
                memScoped {
                    val err = allocCFError()
                    val plain = SecKeyCreateDecryptedData(secKey, alg, cipherData, err)
                    if (plain == null) {
                        IosCryptoNative.throwIosCryptoError("SecKeyCreateDecryptedData($algorithm)", err.pointed.value)
                    }
                    val bytes = IosCryptoNative.cfDataToBytes(plain)
                    CFRelease(plain)
                    bytes
                }
            } finally {
                CFRelease(cipherData)
            }
        } finally {
            CFRelease(secKey)
        }
    }

    /** RSA 加密算法字符串 → SecKeyAlgorithm (CFStringRef)。 */
    private fun rsaEncryptionAlgorithm(algorithm: String): CFStringRef? {
        // 归一化: 大写 + 去空格; 拆 ALGO/MODE/PADDING
        val parts = algorithm.uppercase().replace(" ", "").split("/")
        val algo = parts.getOrNull(0) ?: throw algoError(algorithm)
        if (algo != "RSA") throw algoError(algorithm)
        val padding = parts.getOrNull(2)
        // 无 padding 指定 (RSA 或 RSA/ECB) → JDK 默认 PKCS1Padding
        if (padding == null || padding == "PKCS1PADDING" || padding == "PKCS7PADDING") {
            return kSecKeyAlgorithmRSAEncryptionPKCS1
        }
        if (padding == "NOPADDING") {
            throw UnsupportedOperationException("iOS crypto RSA/ECB/NoPadding not supported (Security.framework)")
        }
        if (padding.startsWith("OAEPWITH") && padding.contains("MGF1PADDING")) {
            // OAEPWithSHA-1AndMGF1Padding / OAEPWithSHA-256AndMGF1Padding ...
            return when {
                padding.contains("SHA-1") || padding.contains("SHA1") -> kSecKeyAlgorithmRSAEncryptionOAEPSHA1
                padding.contains("SHA-224") || padding.contains("SHA224") -> kSecKeyAlgorithmRSAEncryptionOAEPSHA224
                padding.contains("SHA-256") || padding.contains("SHA256") -> kSecKeyAlgorithmRSAEncryptionOAEPSHA256
                padding.contains("SHA-384") || padding.contains("SHA384") -> kSecKeyAlgorithmRSAEncryptionOAEPSHA384
                padding.contains("SHA-512") || padding.contains("SHA512") -> kSecKeyAlgorithmRSAEncryptionOAEPSHA512
                else -> throw UnsupportedOperationException("iOS crypto unsupported OAEP hash: $algorithm")
            }
        }
        throw algoError(algorithm)
    }

    private fun algoError(algorithm: String): Nothing =
        throw UnsupportedOperationException(
            "iOS AsymmetricCrypto unsupported algorithm '$algorithm' (only RSA/ECB/PKCS1 and RSA/ECB/OAEPWithSHA-1/224/256/384/512AndMGF1Padding)"
        )
}
