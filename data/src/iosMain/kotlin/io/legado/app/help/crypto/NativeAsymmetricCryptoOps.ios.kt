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
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA1
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA224
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA256
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA384
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA512
import platform.Security.kSecKeyAlgorithmRSAEncryptionPKCS1

/**
 * iOS actual: 非对称加解密。平台委托: Security.framework SecKey* (2026-10 起 native 不再带 mbedTLS)。
 *
 * transformation 已由 commonMain [AsymCipherSpec.parse] 归一 (别名在归一层处理), 本层只消费
 * 规范形态:
 * - RSA 公钥加密/私钥解密: PKCS1 v1.5 + OAEP(SHA1/224/256/384/512), 对齐 JCA Cipher transformation;
 * - RSA/ECB/NoPadding: SecKey 无裸 RSA 算法, 点名抛异常 (与原 mbedTLS 主实现一致);
 * - SM2: iOS 无国密, 点名抛异常;
 * - RSA 私钥加密/公钥解密 (hutool KeyType 反向): Security.framework 无此操作, 点名抛异常;
 * - 分块语义对齐 hutool/mbedTLS 主实现: 加密块 k-11 (OAEP 为 k-2h-2), 解密块 k, 多块输出顺序拼接;
 * - 密钥格式: 经 [RsaDerKeys] 归一为 PKCS#1 DER (PEM/PKCS#8/SPKI 输入自动转换)。
 */
@OptIn(ExperimentalForeignApi::class)
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
                "iOS AsymmetricCrypto.encrypt with privateKey is not supported " +
                    "(Security.framework SecKeyCreateEncryptedData requires public key)"
            )
        }
        if (spec !is AsymCipherSpec.Rsa) {
            throw UnsupportedOperationException("iOS crypto: SM2 is not supported (no 国密 on iOS)")
        }
        val der = RsaDerKeys.stripPemToDer(
            publicKey ?: throw IllegalArgumentException("AsymmetricCrypto.encrypt: publicKey not set")
        )
        val pkcs1 = RsaDerKeys.toPkcs1Public(der)
        val (alg, oaepMdLen) = secKeyAlgorithm(spec)
        val k = RsaDerKeys.modulusSize(pkcs1, isPrivate = false)
        val chunk = if (oaepMdLen != null) k - 2 * oaepMdLen - 2 else k - 11
        require(chunk > 0) { "iOS crypto: key too small (k=$k) for padding overhead" }
        val secKey = IosCryptoNative.secKeyFromDer(pkcs1, isPrivate = false, keyType = kSecAttrKeyTypeRSA)
            ?: throw UnsupportedOperationException("iOS crypto: SecKeyCreateWithData returned null")
        return try {
            // 空输入仍产出一个完整块 (对齐 JCA RSA Cipher: doFinal(空) 输出 k 字节填充块)
            val blocks = if (data.isEmpty()) 1 else (data.size + chunk - 1) / chunk
            val src = if (data.isEmpty()) byteArrayOf(0) else data
            val out = ArrayList<ByteArray>(blocks)
            var off = 0
            while (off < src.size) {
                val len = minOf(chunk, src.size - off)
                out.add(encryptOne(secKey, alg, src, off, len))
                off += len
            }
            concatBlocks(out)
        } finally {
            CFRelease(secKey)
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
                "iOS AsymmetricCrypto.decrypt with publicKey is not supported " +
                    "(Security.framework SecKeyCreateDecryptedData requires private key)"
            )
        }
        if (spec !is AsymCipherSpec.Rsa) {
            throw UnsupportedOperationException("iOS crypto: SM2 is not supported (no 国密 on iOS)")
        }
        val der = RsaDerKeys.stripPemToDer(
            privateKey ?: throw IllegalArgumentException("AsymmetricCrypto.decrypt: privateKey not set")
        )
        val pkcs1 = RsaDerKeys.toPkcs1Private(der)
        val (alg, _) = secKeyAlgorithm(spec)
        val k = RsaDerKeys.modulusSize(pkcs1, isPrivate = true)
        require(data.isNotEmpty() && data.size % k == 0) {
            "ciphertext length ${data.size} not a positive multiple of key size $k"
        }
        val secKey = IosCryptoNative.secKeyFromDer(pkcs1, isPrivate = true, keyType = kSecAttrKeyTypeRSA)
            ?: throw UnsupportedOperationException("iOS crypto: SecKeyCreateWithData returned null")
        return try {
            val parts = ArrayList<ByteArray>(data.size / k)
            var off = 0
            while (off < data.size) {
                parts.add(decryptOne(secKey, alg, data, off, k))
                off += k
            }
            concatBlocks(parts)
        } finally {
            CFRelease(secKey)
        }
    }

    /** 单块公钥加密 (SecKeyCreateEncryptedData, 输出恰为 k 字节)。 */
    private fun encryptOne(secKey: SecKeyRef, alg: CFStringRef?, data: ByteArray, off: Int, len: Int): ByteArray {
        val plainData = IosCryptoNative.cfData(data, off, len)
        return try {
            memScoped {
                val err = allocCFError()
                val cipher = SecKeyCreateEncryptedData(secKey, alg, plainData, err)
                if (cipher == null) {
                    IosCryptoNative.throwIosCryptoError("SecKeyCreateEncryptedData", err.pointed.value)
                }
                val bytes = IosCryptoNative.cfDataToBytes(cipher)
                CFRelease(cipher)
                bytes
            }
        } finally {
            CFRelease(plainData)
        }
    }

    /** 单块私钥解密 (SecKeyCreateDecryptedData, 输入恰为 k 字节)。 */
    private fun decryptOne(secKey: SecKeyRef, alg: CFStringRef?, data: ByteArray, off: Int, len: Int): ByteArray {
        val cipherData = IosCryptoNative.cfData(data, off, len)
        return try {
            memScoped {
                val err = allocCFError()
                val plain = SecKeyCreateDecryptedData(secKey, alg, cipherData, err)
                if (plain == null) {
                    IosCryptoNative.throwIosCryptoError("SecKeyCreateDecryptedData", err.pointed.value)
                }
                val bytes = IosCryptoNative.cfDataToBytes(plain)
                CFRelease(plain)
                bytes
            }
        } finally {
            CFRelease(cipherData)
        }
    }

    /** 归一后的 spec → (SecKeyAlgorithm, OAEP 摘要字节长度 or null)。 */
    private fun secKeyAlgorithm(spec: AsymCipherSpec.Rsa): Pair<CFStringRef?, Int?> = when (spec.padding) {
        AsymCipherSpec.RsaPadding.PKCS1 -> kSecKeyAlgorithmRSAEncryptionPKCS1 to null
        AsymCipherSpec.RsaPadding.NO_PADDING ->
            throw UnsupportedOperationException("iOS crypto RSA/ECB/NoPadding not supported (Security.framework)")
        AsymCipherSpec.RsaPadding.OAEP -> when (spec.oaepDigestBits) {
            160 -> kSecKeyAlgorithmRSAEncryptionOAEPSHA1 to 20
            224 -> kSecKeyAlgorithmRSAEncryptionOAEPSHA224 to 28
            256 -> kSecKeyAlgorithmRSAEncryptionOAEPSHA256 to 32
            384 -> kSecKeyAlgorithmRSAEncryptionOAEPSHA384 to 48
            512 -> kSecKeyAlgorithmRSAEncryptionOAEPSHA512 to 64
            else -> throw UnsupportedOperationException("iOS crypto unsupported OAEP hash bits: ${spec.oaepDigestBits}")
        }
    }
}
