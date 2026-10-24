@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyTypeEC
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA1
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA384
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA512
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA1
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA384
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA512
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePSSSHA1
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePSSSHA256
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePSSSHA384
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePSSSHA512

/**
 * iOS actual: 签名/验签。平台委托: Security.framework SecKey* (2026-10 起 native 不再带 mbedTLS)。
 *
 * 算法映射 (对齐 jvmAndAndroid JCA Signature algorithm):
 * - RSA PKCS1 v1.5: SHA1/256/384/512withRSA → kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA*;
 * - RSA PSS: SHA1/256/384/512WithRSA/PSS → kSecKeyAlgorithmRSASignatureMessagePSSSHA*;
 * - ECDSA: SHA1/256/384/512withECDSA → kSecKeyAlgorithmECDSASignatureMessageX962SHA*;
 * - MD5withRSA / RIPEMD160withRSA / SHA224 系 / NONEwithRSA: Security 无对应算法, 点名抛异常
 *   (原 mbedTLS 主实现覆盖, 平台委托后 iOS 缺口, 鸿蒙侧经 cryptoFramework 亦无)。
 *
 * 密钥格式: RSA 经 [RsaDerKeys] 归一为 PKCS#1 DER; EC 经 [EcDerKeys] 解包为 X9.63 原始字节
 * (SecKeyCreateWithData 只吃 X9.63: 公钥 0x04||X||Y, 私钥 0x04||X||Y||K, DER 封装会被拒),
 * PEM 输入先经 stripPemToDer 剥壳; sign 用私钥, verify 用公钥。
 */
@OptIn(ExperimentalForeignApi::class)
actual object NativeSignOps {

    actual fun sign(algorithm: String, privateKey: ByteArray?, data: ByteArray): ByteArray {
        val (keyType, alg) = resolveSignAlgorithm(algorithm)
        val der = RsaDerKeys.stripPemToDer(
            privateKey ?: throw IllegalArgumentException("Sign.sign: privateKey not set")
        )
        val secKey = if (keyType == kSecAttrKeyTypeEC) {
            IosCryptoNative.secKeyFromDer(EcDerKeys.toX963PrivateWithPoint(der), isPrivate = true, keyType = keyType)
        } else {
            IosCryptoNative.secKeyFromDer(RsaDerKeys.toPkcs1Private(der), isPrivate = true, keyType = keyType)
        }
        return try {
            val msgData = IosCryptoNative.cfData(data)
                ?: throw UnsupportedOperationException("iOS crypto: CFDataCreate returned null")
            try {
                memScoped {
                    val err = allocCFError()
                    val sig = SecKeyCreateSignature(secKey, alg, msgData, err)
                    if (sig == null) {
                        IosCryptoNative.throwIosCryptoError("SecKeyCreateSignature($algorithm)", err.pointed.value)
                    }
                    val bytes = IosCryptoNative.cfDataToBytes(sig)
                    CFRelease(sig)
                    bytes
                }
            } finally {
                CFRelease(msgData)
            }
        } finally {
            CFRelease(secKey)
        }
    }

    actual fun verify(
        algorithm: String,
        publicKey: ByteArray?,
        data: ByteArray,
        signature: ByteArray
    ): Boolean {
        val (keyType, alg) = resolveSignAlgorithm(algorithm)
        val der = RsaDerKeys.stripPemToDer(
            publicKey ?: throw IllegalArgumentException("Sign.verify: publicKey not set")
        )
        val secKey = if (keyType == kSecAttrKeyTypeEC) {
            IosCryptoNative.secKeyFromDer(EcDerKeys.toX963Public(der), isPrivate = false, keyType = keyType)
        } else {
            IosCryptoNative.secKeyFromDer(RsaDerKeys.toPkcs1Public(der), isPrivate = false, keyType = keyType)
        }
        return try {
            val msgData = IosCryptoNative.cfData(data)
            val sigData = IosCryptoNative.cfData(signature)
            if (msgData == null || sigData == null) {
                msgData?.let { CFRelease(it) }
                sigData?.let { CFRelease(it) }
                return false
            }
            try {
                memScoped {
                    val err = allocCFError()
                    val ok = SecKeyVerifySignature(secKey, alg, msgData, sigData, err)
                    // 验签失败 err 会被填充 (errSecAuthFailed 等), 释放后返回 false (不抛异常, 对齐 JCA verify 返回 false)
                    err.pointed.value?.let { CFRelease(it) }
                    ok
                }
            } finally {
                CFRelease(msgData)
                CFRelease(sigData)
            }
        } finally {
            CFRelease(secKey)
        }
    }

    /** 算法字符串 → (keyType, SecKeyAlgorithm)。 */
    private fun resolveSignAlgorithm(algorithm: String): Pair<CFStringRef?, CFStringRef?> {
        // 归一化: 大写; 容忍 SHA-256 / SHA256, RSA/PSS, with 大小写
        val upper = algorithm.uppercase().replace(" ", "").replace("-", "")
        return when {
            upper.endsWith("WITHRSA") || upper.endsWith("WITHECDSA") ||
                upper.contains("WITHRSA/") -> resolveRsaOrEcdsa(upper, algorithm)
            else -> throw signAlgoError(algorithm)
        }
    }

    private fun resolveRsaOrEcdsa(upper: String, original: String): Pair<CFStringRef?, CFStringRef?> {
        val isPss = upper.contains("WITHRSA/PSS")
        val isEcdsa = upper.contains("WITHECDSA")
        val hash = when {
            upper.startsWith("SHA1") -> "SHA1"
            upper.startsWith("SHA224") -> "SHA224"
            upper.startsWith("SHA256") -> "SHA256"
            upper.startsWith("SHA384") -> "SHA384"
            upper.startsWith("SHA512") -> "SHA512"
            upper.startsWith("MD5") -> throw UnsupportedOperationException(
                "iOS Sign MD5withRSA not supported (Security.framework has no MD5 signature algorithm)"
            )
            upper.startsWith("NONEWITH") -> throw UnsupportedOperationException(
                "iOS Sign NONEwithRSA/ECDSA not supported (Security.framework requires hash)"
            )
            else -> throw signAlgoError(original)
        }
        return when {
            isEcdsa -> {
                val alg = when (hash) {
                    "SHA1" -> kSecKeyAlgorithmECDSASignatureMessageX962SHA1
                    "SHA224" -> throw UnsupportedOperationException("iOS Sign SHA224withECDSA not supported")
                    "SHA256" -> kSecKeyAlgorithmECDSASignatureMessageX962SHA256
                    "SHA384" -> kSecKeyAlgorithmECDSASignatureMessageX962SHA384
                    "SHA512" -> kSecKeyAlgorithmECDSASignatureMessageX962SHA512
                    else -> throw signAlgoError(original)
                }
                kSecAttrKeyTypeEC to alg
            }
            isPss -> {
                val alg = when (hash) {
                    "SHA1" -> kSecKeyAlgorithmRSASignatureMessagePSSSHA1
                    "SHA224" -> throw UnsupportedOperationException("iOS Sign SHA224WithRSA/PSS not supported")
                    "SHA256" -> kSecKeyAlgorithmRSASignatureMessagePSSSHA256
                    "SHA384" -> kSecKeyAlgorithmRSASignatureMessagePSSSHA384
                    "SHA512" -> kSecKeyAlgorithmRSASignatureMessagePSSSHA512
                    else -> throw signAlgoError(original)
                }
                kSecAttrKeyTypeRSA to alg
            }
            else -> { // RSA PKCS1 v1.5
                val alg = when (hash) {
                    "SHA1" -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA1
                    "SHA224" -> throw UnsupportedOperationException("iOS Sign SHA224withRSA not supported")
                    "SHA256" -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256
                    "SHA384" -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA384
                    "SHA512" -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA512
                    else -> throw signAlgoError(original)
                }
                kSecAttrKeyTypeRSA to alg
            }
        }
    }

    private fun signAlgoError(algorithm: String): Nothing =
        throw UnsupportedOperationException(
            "iOS Sign unsupported algorithm '$algorithm' " +
                "(supported: SHA1/256/384/512withRSA, SHAxxxWithRSA/PSS, SHA1/256/384/512withECDSA)"
        )
}
