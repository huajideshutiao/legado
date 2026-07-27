package io.legado.app.help.crypto

import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.encodeBase64Standard
import io.legado.app.utils.toHexLower

/**
 * nativeMain: 对称加解密门面 [SymmetricCrypto] 实现 (iOS / 鸿蒙 两端共用壳)。
 *
 * 路由: AES 的 ECB/CBC/PCBC/CFB/OFB/CTR 走 [NativeAesOps] (actual 内 mbedTLS 主实现, 异常回落
 * krypto/napi 既有路径); AES/GCM 与 DES/DESede 无既有回落实现, 直走 [MbedTlsCipher]。
 *
 * - transformation 解析对齐 JCA "ALGO/MODE/PADDING": 缺省 mode=ECB, 缺省 padding=PKCS5
 *   (块大小 16/8 下与 PKCS7 字节级等价, 归一为 PKCS7)。
 * - 白名单: AES=ECB/CBC/PCBC/CFB/OFB/CTR/GCM (GCM 仅 NoPadding, 密文=cipher||tag16 对齐 JCA);
 *   DES/DESede=ECB/CBC (mbedTLS cipher_wrap 仅注册这两种); RC4/SM4 抛异常点名。
 * - key==null 时生成随机密钥 (AES 16B / DES 8B / DESede 24B, 对齐 hutool KeyUtil.generateKey 默认强度)。
 * - setIv 真实存 IV; ECB 设 IV 在加解密时报错 (对齐 JCA "ECB mode cannot use IV"),
 *   非 ECB 模式 (含 GCM) 缺 IV 报错 (JCA encrypt 会生成不可取回的随机 IV, 结果不可用, 此处显式报错)。
 * - 密文形态: decrypt(String) 兼容 hex 与 base64, 对齐 hutool SecureUtil.decode 自动识别。
 *
 * 与 jvmAndAndroidMain (hutool SymmetricCrypto) 行为对齐: 同算法 + 同 key/iv 字节级互通。
 */
class NativeSymmetricCrypto(
    transformation: String,
    key: ByteArray?,
) : SymmetricCrypto {

    private val algorithm: String
    private val mode: String
    private val padding: String
    private val keyBytes: ByteArray
    private var iv: ByteArray? = null

    init {
        val parts = transformation.uppercase().replace(" ", "").split("/")
        algorithm = when (val algo = parts.getOrNull(0).orEmpty()) {
            "AES", "DES", "DESEDE" -> algo
            "TRIPLEDES", "3DES" -> "DESEDE"
            else -> throw UnsupportedOperationException(
                "NativeSymmetricCrypto: algorithm '$algo' is not supported on iOS/鸿蒙 " +
                    "(only AES/DES/DESede; RC4/SM4 unavailable)"
            )
        }
        mode = parts.getOrNull(1) ?: "ECB"
        val supportedModes = if (algorithm == "AES") AES_MODES else DES_MODES
        if (mode !in supportedModes) {
            throw UnsupportedOperationException(
                "NativeSymmetricCrypto: $algorithm mode '$mode' is not supported " +
                    "(AES: ${AES_MODES.joinToString("/")}; DES/DESede: ${DES_MODES.joinToString("/")})"
            )
        }
        padding = normalizePadding(parts.getOrNull(2) ?: "PKCS5PADDING")
        if (mode == "GCM" && padding != "NOPADDING") {
            throw UnsupportedOperationException("NativeSymmetricCrypto: AES/GCM only supports NoPadding")
        }
        keyBytes = key ?: NativeAesOps.randomKey(defaultKeySize(algorithm))
        val valid = when (algorithm) {
            "AES" -> keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32
            "DES" -> keyBytes.size == 8
            else -> keyBytes.size == 16 || keyBytes.size == 24
        }
        require(valid) {
            "invalid $algorithm key length: ${keyBytes.size} (AES=16/24/32, DES=8, DESede=16/24 bytes)"
        }
    }

    /** 存 IV 并返回 this (对齐 hutool setIv 链式调用, 校验推迟到加解密时)。 */
    fun setIv(iv: ByteArray): NativeSymmetricCrypto {
        this.iv = iv
        return this
    }

    override fun encrypt(data: ByteArray): ByteArray = doCrypt(encrypt = true, bytes = data)

    override fun encrypt(data: String): ByteArray = encrypt(data.encodeToByteArray())

    override fun encryptHex(data: ByteArray): String = encrypt(data).toHexLower()

    override fun encryptHex(data: String): String = encrypt(data).toHexLower()

    override fun encryptBase64(data: ByteArray): String = encrypt(data).encodeBase64Standard()

    override fun encryptBase64(data: String, charset: String?): String {
        // KMP commonMain 无 charset(name) API (kotlin.text.Charset.forName 是 JVM-only),
        // native 端仅支持 UTF-8 (null 视为 UTF-8, 对齐 jvmAndAndroid 默认行为)。
        val bytes = when (charset?.uppercase()) {
            null, "UTF-8", "UTF8" -> data.encodeToByteArray()
            else -> throw UnsupportedOperationException(
                "NativeSymmetricCrypto: unsupported charset '$charset' (only UTF-8 supported on native)"
            )
        }
        return encrypt(bytes).encodeBase64Standard()
    }

    override fun encryptBase64(data: String): String = encrypt(data).encodeBase64Standard()

    override fun decrypt(data: String): ByteArray {
        val bytes = if (hexRegex.matches(data)) decodeHex(data) else Base64Lenient.decode(data)
        return doCrypt(encrypt = false, bytes = bytes)
    }

    /** AES 非 GCM 走 expect ops (mbedTLS 主 + 既有回落); 其余组合 mbedTLS 直连。 */
    private fun doCrypt(encrypt: Boolean, bytes: ByteArray): ByteArray {
        val realIv = resolveIv()
        return if (algorithm == "AES" && mode != "GCM") {
            if (encrypt) NativeAesOps.encrypt(keyBytes, bytes, mode, padding, realIv)
            else NativeAesOps.decrypt(keyBytes, bytes, mode, padding, realIv)
        } else {
            if (encrypt) MbedTlsCipher.encrypt(algorithm, mode, padding, keyBytes, realIv, bytes)
            else MbedTlsCipher.decrypt(algorithm, mode, padding, keyBytes, realIv, bytes)
        }
    }

    /** ECB 不接受 IV, 非 ECB (含 GCM) 必须有 IV, 违反即报错 (见类注释)。 */
    private fun resolveIv(): ByteArray? {
        val v = iv
        if (mode == "ECB") {
            require(v == null) { "ECB mode cannot use IV" }
            return null
        }
        requireNotNull(v) { "IV is required for $algorithm/$mode" }
        return v
    }

    private fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "invalid hex length: ${hex.length}" }
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private val hexRegex = Regex("^[a-fA-F0-9]+$")

        /** 白名单 (原 SUPPORTED_MODES 扩枚举: AES +GCM, DES/DESede 单列)。 */
        private val AES_MODES = setOf("ECB", "CBC", "PCBC", "CFB", "OFB", "CTR", "GCM")
        private val DES_MODES = setOf("ECB", "CBC")

        private fun defaultKeySize(algorithm: String): Int = when (algorithm) {
            "AES" -> 16
            "DES" -> 8
            else -> 24
        }

        /** padding 归一 (PKCS5→PKCS7), 白名单外抛异常点名。 */
        private fun normalizePadding(p: String): String = when (p) {
            "PKCS5PADDING", "PKCS7PADDING" -> "PKCS7PADDING"
            "NOPADDING" -> "NOPADDING"
            "ANSIX923PADDING" -> "ANSIX923PADDING"
            "ISO10126PADDING" -> "ISO10126PADDING"
            "ZEROPADDING" -> "ZEROPADDING"
            else -> throw UnsupportedOperationException(
                "NativeSymmetricCrypto: unsupported padding '$p'"
            )
        }
    }
}
