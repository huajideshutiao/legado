package io.legado.app.help.crypto

import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.encodeBase64Standard

/**
 * nativeMain: 对称加解密门面 [SymmetricCrypto] 实现 (iOS / 鸿蒙 两端共用壳)。
 *
 * 真实加解密下沉到 [NativeAesOps] (expect object): iOS 端 krypto actual, 鸿蒙端 napi actual。
 *
 * - 仅支持 AES/ECB/PKCS5Padding（PKCS#5 = PKCS#7 在块大小 16 时的特例, 字节级等价）及其命名变体。
 * - 算法白名单 (uppercase 归一化后):
 *     "AES" / "AES/ECB/PKCS5PADDING" / "AES/ECB/PKCS7PADDING"
 *   其他算法 (AES/CBC, DES, RC4 等) 抛 UnsupportedOperationException 降级。
 * - 密文形态: encryptBase64 输出标准 Base64 (带 padding, 不换行);
 *   decrypt 兼容 hex 与 base64 两种密文形态, 对齐 hutool SecureUtil.decode 自动识别。
 *
 * 与 jvmAndAndroidMain (hutool SymmetricCrypto) 行为对齐: 同算法 + 同 key 字节级互通。
 *
 * JS 桥 createSymmetricCrypto 在 iOS/鸿蒙端 AES/ECB 场景可用, 其他算法降级。
 */
class NativeSymmetricCrypto(
    algorithm: String,
    private val key: ByteArray?,
) : SymmetricCrypto {

    private val aesKey: ByteArray = run {
        if (key == null) {
            throw UnsupportedOperationException(
                "NativeSymmetricCrypto: key must not be null (AES requires explicit key)"
            )
        }
        if (!isAesEcbPkcs5(algorithm)) {
            throw UnsupportedOperationException(
                "NativeSymmetricCrypto: unsupported algorithm '$algorithm', " +
                    "only AES/ECB/PKCS5Padding variants are supported on native"
            )
        }
        key
    }

    override fun encryptBase64(data: ByteArray): String =
        NativeAesOps.encryptEcbPkcs7(aesKey, data).encodeBase64Standard()

    override fun encryptBase64(data: String, charset: String?): String {
        // KMP commonMain 无 charset(name) API (kotlin.text.Charset.forName 是 JVM-only),
        // native 端仅支持 UTF-8 (null 视为 UTF-8, 对齐 jvmAndAndroid 默认行为);
        // 其他 charset 抛 UnsupportedOperationException 降级。
        val bytes = when (charset?.uppercase()) {
            null, "UTF-8", "UTF8" -> data.encodeToByteArray()
            else -> throw UnsupportedOperationException(
                "NativeSymmetricCrypto: unsupported charset '$charset' (only UTF-8 supported on native)"
            )
        }
        return NativeAesOps.encryptEcbPkcs7(aesKey, bytes).encodeBase64Standard()
    }

    override fun encryptBase64(data: String): String =
        NativeAesOps.encryptEcbPkcs7(aesKey, data.encodeToByteArray()).encodeBase64Standard()

    override fun decrypt(data: String): ByteArray {
        val bytes = if (hexRegex.matches(data)) decodeHex(data) else Base64Lenient.decode(data)
        return NativeAesOps.decryptEcbPkcs7(aesKey, bytes)
    }

    /** 算法白名单: AES + ECB + (PKCS5/PKCS7/无指定, 默认 PKCS7) */
    private fun isAesEcbPkcs5(algorithm: String): Boolean {
        val upper = algorithm.uppercase().replace(" ", "")
        // 拆分 "ALGO/MODE/PADDING"
        val parts = upper.split("/")
        val algo = parts.getOrNull(0) ?: return false
        if (algo != "AES") return false
        // 仅允许 ECB 或无模式 (无模式时 hutool 默认 ECB)
        val mode = parts.getOrNull(1)
        if (mode != null && mode != "ECB") return false
        // padding 必须是 PKCS5/PKCS7 或缺省 (默认 PKCS7Padding)
        val padding = parts.getOrNull(2)
        if (padding != null && padding != "PKCS5PADDING" && padding != "PKCS7PADDING") return false
        return true
    }

    private fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "invalid hex length: ${hex.length}" }
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private val hexRegex = Regex("^[a-fA-F0-9]+$")
    }
}
