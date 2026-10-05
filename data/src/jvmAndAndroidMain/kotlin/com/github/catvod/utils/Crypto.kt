package com.github.catvod.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.interfaces.RSAKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * TVBox 壳加解密工具 (API 面对齐 FongMi catvod 模块, jar 内常以 Crypto.md5/equals/aes/des 直调)。
 * Base64 以 java.util.Base64 等价实现 (标准字母表/宽容解码, 对齐 android Base64 DEFAULT/NO_WRAP 语义)。
 */
object Crypto {

    private const val MD5 = "MD5"
    private const val SHA_256 = "SHA-256"
    private const val BUFFER_SIZE = 64 * 1024
    private const val AES_BLOCK_SIZE = 16
    private const val DES_BLOCK_SIZE = 8
    private const val DES_EDE_TWO_KEY_SIZE = 16
    private const val DES_EDE_THREE_KEY_SIZE = 24
    private val HEX = "0123456789abcdef".toCharArray()
    private val OAEP_SHA1_PARAMETERS =
        OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    @JvmStatic
    fun md5(value: String?): String {
        if (value == null || value.isEmpty()) return ""
        return toHex(newDigest(MD5).digest(value.toByteArray(StandardCharsets.UTF_8)))
    }

    @JvmStatic
    fun md5(file: File?): String = try {
        toHex(digest(file, newDigest(MD5)))
    } catch (e: IOException) {
        ""
    }

    @JvmStatic
    fun equals(file: File?, expected: String?): Boolean =
        !expected.isNullOrEmpty() && expected.equals(md5(file), ignoreCase = true)

    @JvmStatic
    @Throws(IOException::class)
    fun sha256(file: File?): ByteArray = digest(file, newDigest(SHA_256))

    @JvmStatic
    fun newDigest(algorithm: String?): MessageDigest = try {
        MessageDigest.getInstance(algorithm)
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalStateException(e)
    }

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun decryptAesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        newAesCipher("AES/CBC/PKCS5Padding", false, key, iv).doFinal(data)

    @JvmStatic
    fun aes(mode: String, encrypt: Boolean, input: String, inputBase64: Boolean, key: String, iv: String, outputBase64: Boolean): String = try {
        val keyBytes = padParameter(key.toByteArray(StandardCharsets.UTF_8), AES_BLOCK_SIZE)
        val ivBytes = getIv(iv, AES_BLOCK_SIZE)
        val cipher = newAesCipher(getAesTransformation(mode), encrypt, keyBytes, ivBytes)
        encode(cipher.doFinal(decode(input, inputBase64)), outputBase64)
    } catch (e: Exception) {
        ""
    }

    @JvmStatic
    fun des(mode: String, encrypt: Boolean, input: String, inputBase64: Boolean, key: String, iv: String, outputBase64: Boolean): String = try {
        val keyBytes = getDesEdeKey(key)
        val ivBytes = getIv(iv, DES_BLOCK_SIZE)
        val cipher = newCipher(getDesTransformation(mode), "DESede", encrypt, keyBytes, ivBytes)
        encode(cipher.doFinal(decode(input, inputBase64)), outputBase64)
    } catch (e: Exception) {
        ""
    }

    @JvmStatic
    fun rsa(mode: String, publicKey: Boolean, encrypt: Boolean, input: String, inputBase64: Boolean, key: String, outputBase64: Boolean): String = try {
        val rsaKey = generateRsaKey(publicKey, key)
        val rsaMode = RsaMode.from(mode)
        val cipher = newRsaCipher(rsaMode, encrypt, rsaKey)
        val output = transformRsa(cipher, rsaMode, encrypt, rsaKey, decode(input, inputBase64))
        encode(output, outputBase64)
    } catch (e: Exception) {
        ""
    }

    @Throws(IOException::class)
    private fun digest(file: File?, digest: MessageDigest): ByteArray {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count)
            return digest.digest()
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun newAesCipher(transformation: String, encrypt: Boolean, key: ByteArray, iv: ByteArray?): Cipher =
        newCipher(transformation, "AES", encrypt, key, iv)

    @Throws(GeneralSecurityException::class)
    private fun newCipher(transformation: String, algorithm: String, encrypt: Boolean, key: ByteArray, iv: ByteArray?): Cipher {
        if (iv == null && transformation.contains("/CBC/")) throw GeneralSecurityException("IV is required for CBC mode")
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key, algorithm)
        val operation = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (iv == null) cipher.init(operation, keySpec) else cipher.init(operation, keySpec, IvParameterSpec(iv))
        return cipher
    }

    private fun getIv(iv: String, blockSize: Int): ByteArray? =
        if (iv.isEmpty()) null else padParameter(iv.toByteArray(StandardCharsets.UTF_8), blockSize)

    private fun getAesTransformation(mode: String): String = when {
        mode.startsWith("AES/CBC") -> "AES/CBC/PKCS5Padding"
        mode.startsWith("AES/ECB") -> "AES/CBC/PKCS5Padding".replace("CBC", "ECB")
        else -> mode + "Padding"
    }

    private fun getDesTransformation(mode: String): String =
        if (mode.startsWith("DESede/CBC")) "DESede/CBC/PKCS5Padding" else mode + "Padding"

    private fun getDesEdeKey(key: String): ByteArray {
        val bytes = padParameter(key.toByteArray(StandardCharsets.UTF_8), DES_EDE_TWO_KEY_SIZE)
        if (bytes.size != DES_EDE_TWO_KEY_SIZE) return bytes
        val expanded = Arrays.copyOf(bytes, DES_EDE_THREE_KEY_SIZE)
        System.arraycopy(bytes, 0, expanded, DES_EDE_TWO_KEY_SIZE, DES_BLOCK_SIZE)
        return expanded
    }

    private fun padParameter(value: ByteArray, minLength: Int): ByteArray =
        if (value.size < minLength) Arrays.copyOf(value, minLength) else value

    @Throws(GeneralSecurityException::class)
    private fun generateRsaKey(publicKey: Boolean, value: String): Key {
        val begin = if (publicKey) "-----BEGIN PUBLIC KEY-----" else "-----BEGIN PRIVATE KEY-----"
        val end = if (publicKey) "-----END PUBLIC KEY-----" else "-----END PRIVATE KEY-----"
        val key = value.replace("\r", "").replace("\n", "").replace(begin, "").replace(end, "")
        val factory = KeyFactory.getInstance("RSA")
        val bytes = java.util.Base64.getMimeDecoder().decode(key)
        return if (publicKey) factory.generatePublic(X509EncodedKeySpec(bytes)) else factory.generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    @Throws(GeneralSecurityException::class)
    private fun newRsaCipher(mode: RsaMode, encrypt: Boolean, key: Key): Cipher {
        val cipher = Cipher.getInstance(mode.transformation)
        val operation = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (mode == RsaMode.OAEP_SHA1) cipher.init(operation, key, OAEP_SHA1_PARAMETERS) else cipher.init(operation, key)
        return cipher
    }

    @Throws(GeneralSecurityException::class)
    private fun transformRsa(cipher: Cipher, mode: RsaMode, encrypt: Boolean, key: Key, input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        val rsaBlockSize = getRsaBlockSize(key)
        val inputBlockSize = if (encrypt) rsaBlockSize - mode.paddingOverhead else rsaBlockSize
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val length = minOf(inputBlockSize, input.size - offset)
            val block = transformRsaBlock(cipher, mode, input, offset, length, rsaBlockSize)
            output.write(block, 0, block.size)
            offset += inputBlockSize
        }
        return output.toByteArray()
    }

    @Throws(GeneralSecurityException::class)
    private fun transformRsaBlock(cipher: Cipher, mode: RsaMode, input: ByteArray, offset: Int, length: Int, blockSize: Int): ByteArray {
        if (mode != RsaMode.NO_PADDING || length == blockSize) return cipher.doFinal(input, offset, length)
        val padded = ByteArray(blockSize)
        System.arraycopy(input, offset, padded, blockSize - length, length)
        return cipher.doFinal(padded)
    }

    @Throws(GeneralSecurityException::class)
    private fun getRsaBlockSize(key: Key): Int {
        val rsaKey = key as? RSAKey ?: throw GeneralSecurityException("Invalid RSA key")
        return (rsaKey.modulus.bitLength() + 7) / 8
    }

    /** base64 入参 (URL_SAFE 变体容错: '-'/'_' 归一回标准字母表; 解码宽容空白/缺补位)。 */
    private fun decode(input: String, base64: Boolean): ByteArray =
        if (base64) {
            java.util.Base64.getMimeDecoder().decode(input.replace('_', '/').replace('-', '+'))
        } else {
            input.toByteArray(StandardCharsets.UTF_8)
        }

    private fun encode(output: ByteArray, base64: Boolean): String =
        if (base64) java.util.Base64.getEncoder().encodeToString(output) else String(output, StandardCharsets.UTF_8)

    private fun toHex(bytes: ByteArray): String {
        val result = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            result[index * 2] = HEX[value ushr 4]
            result[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(result)
    }

    private enum class RsaMode(val transformation: String, val paddingOverhead: Int) {

        PKCS1("RSA/ECB/PKCS1Padding", 11),
        NO_PADDING("RSA/ECB/NoPadding", 0),
        OAEP_SHA1("RSA/ECB/OAEPWithSHA-1AndMGF1Padding", 42);

        companion object {
            @Throws(GeneralSecurityException::class)
            fun from(mode: String): RsaMode = when (mode) {
                "RSA/PKCS1" -> PKCS1
                "RSA/None/NoPadding" -> NO_PADDING
                "RSA/None/OAEPPadding" -> OAEP_SHA1
                else -> throw GeneralSecurityException("Unsupported RSA mode: $mode")
            }
        }
    }
}
