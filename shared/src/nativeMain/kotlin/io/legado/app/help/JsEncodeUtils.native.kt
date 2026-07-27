package io.legado.app.help

import com.soywiz.krypto.Hmac
import com.soywiz.krypto.MD5
import com.soywiz.krypto.SHA1
import com.soywiz.krypto.SHA256
import com.soywiz.krypto.SHA512
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.NativeAsymmetricCrypto
import io.legado.app.help.crypto.NativeSign
import io.legado.app.help.crypto.NativeSymmetricCrypto
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SymmetricCrypto
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.encodeBase64Standard
import io.legado.app.utils.toHexLower

/**
 * nativeMain actual: [JsEncodeUtils] actual interface, 纯 abstract
 * (与 commonMain expect modality 对齐, iOS / 鸿蒙 两端共用)。
 *
 * 两端 actual interface 实现完全一致, 下沉到 nativeMain 共用 (nativeMain 是 iosMain / ohosMain
 * 的父源集, 此处提供 actual 自动满足两端)。
 *
 * 默认实现 (krypto) 移至 [JsEncodeUtilsDefaults] interface, 由调用方多继承注入。
 *
 * - md5Encode / md5Encode16: 复用 commonMain [MD5Utils] (expect object, iOS/鸿蒙 actual 已用 krypto 实现)。
 * - digestHex / digestBase64Str: krypto 提供 MD5/SHA-1/SHA-256/SHA-512, 与 jvmAndAndroidMain
 *   的 hutool DigestUtil 字节级一致 (标准 FIPS 180-4 / RFC 1321 算法)。
 * - HMacHex / HMacBase64: krypto HMAC, 与 jvmAndAndroidMain 的 hutool HMac 字节级一致 (RFC 2104)。
 * - Base64 编码: [encodeBase64Standard] (标准字母表 + padding + 不换行, 对齐 java.util.Base64.getEncoder())。
 * - 摘要/HMAC 输出小写 hex (hutool DigestUtil.digestHex 默认小写, [toHexLower] 对齐)。
 *
 * 算法名归一化: 接受 hutool/JDK 常见别名 (MD5/SHA-1/SHA1/SHA-256/SHA256/SHA-512/SHA512,
 * HMAC 前缀含连字符或无连字符均识别)。
 *
 * 注: krypto 4.0.10 已发布 linuxArm64 变体, 鸿蒙端走 linuxArm64 target, 可直接复用 iOS 端实现,
 * 故两端下沉到 nativeMain 共用。
 */
@Suppress("unused")
actual interface JsEncodeUtils {

    actual fun md5Encode(str: String): String

    actual fun md5Encode16(str: String): String

    //******************消息摘要/散列消息鉴别码************************//

    actual fun digestHex(data: String, algorithm: String): String

    actual fun digestBase64Str(data: String, algorithm: String): String

    @Suppress("FunctionName")
    actual fun HMacHex(data: String, algorithm: String, key: String): String

    @Suppress("FunctionName")
    actual fun HMacBase64(data: String, algorithm: String, key: String): String
}

/**
 * nativeMain: [JsEncodeUtils] 默认实现 (krypto 库, iOS / 鸿蒙 两端共用)。
 *
 * KMP 限制: actual interface 成员 modality 必须与 expect 一致 (abstract), 不能带方法体;
 * 故将默认实现下沉到独立的 Defaults interface, 由调用方多继承注入。
 *
 * 行为零变化: 6 个方法体原样搬迁自原两端 actual interface, 仅 host 类型变化。
 */
@Suppress("unused")
interface JsEncodeUtilsDefaults : JsEncodeUtils {

    override fun md5Encode(str: String): String = MD5Utils.md5Encode(str)

    override fun md5Encode16(str: String): String = MD5Utils.md5Encode16(str)

    override fun digestHex(data: String, algorithm: String): String {
        val bytes = data.encodeToByteArray()
        return digestByAlgorithm(algorithm, bytes).toHexLower()
    }

    override fun digestBase64Str(data: String, algorithm: String): String {
        val bytes = data.encodeToByteArray()
        return digestByAlgorithm(algorithm, bytes).encodeBase64Standard()
    }

    @Suppress("FunctionName")
    override fun HMacHex(data: String, algorithm: String, key: String): String {
        val mac = hmacByAlgorithm(algorithm, key.encodeToByteArray(), data.encodeToByteArray())
        return mac.toHexLower()
    }

    @Suppress("FunctionName")
    override fun HMacBase64(data: String, algorithm: String, key: String): String {
        val mac = hmacByAlgorithm(algorithm, key.encodeToByteArray(), data.encodeToByteArray())
        return mac.encodeBase64Standard()
    }

    //******************对称加密解密工厂************************//

    /**
     * native 端工厂: 创建 [NativeSymmetricCrypto] (krypto AES/ECB/PKCS5)。
     *
     * 与 app 端 [io.legado.app.help.JsEncodeUtilsAndroid.createSymmetricCrypto] 签名对齐, 行为差异:
     * - iv 参数忽略 (krypto 仅支持 ECB, 不支持 CBC/IV);
     * - algorithm 非 AES/ECB/PKCS5 变体时, [NativeSymmetricCrypto] 构造抛 UnsupportedOperationException 降级。
     *
     * 返回类型为 commonMain [SymmetricCrypto] interface (跨端引用透明兼容),
     * 非 app 端 hutool `cn.hutool.crypto.symmetric.SymmetricCrypto` (native 无 hutool)。
     *
     * JS 中调用方式:
     * java.createSymmetricCrypto(transformation, key, iv).decrypt(data)
     * java.createSymmetricCrypto(transformation, key, iv).decryptStr(data)
     * java.createSymmetricCrypto(transformation, key, iv).encryptBase64(data)
     */
    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCrypto {
        // iv 在 native 端不支持 (krypto AES ECB only), 忽略; algorithm 降级由 NativeSymmetricCrypto 内部判定
        return NativeSymmetricCrypto(transformation, key)
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray
    ): SymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: String
    ): SymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: String,
        iv: String?
    ): SymmetricCrypto {
        return createSymmetricCrypto(
            transformation, key.encodeToByteArray(), iv?.encodeToByteArray()
        )
    }

    //******************非对称加密解密工厂************************//

    /**
     * native 端工厂: 创建 [NativeAsymmetricCrypto] (P0 阶段降级 stub, 不抛异常, 返回空值)。
     * 与 app 端 [io.legado.app.help.JsEncodeUtilsAndroid.createAsymmetricCrypto] 签名对齐。
     */
    fun createAsymmetricCrypto(
        transformation: String
    ): AsymmetricCrypto {
        return NativeAsymmetricCrypto(transformation)
    }

    //******************签名工厂************************//

    /**
     * native 端工厂: 创建 [NativeSign] (P0 阶段降级 stub, 仅缓存 key 不抛异常)。
     * 与 app 端 [io.legado.app.help.JsEncodeUtilsAndroid.createSign] 签名对齐。
     */
    fun createSign(
        algorithm: String
    ): Sign {
        return NativeSign(algorithm)
    }
}

/** 摘要算法分发: 接受 hutool/JDK 常见命名 (大小写/连字符不敏感)。 */
private fun digestByAlgorithm(algorithm: String, data: ByteArray): ByteArray {
    // krypto 顶层对象: MD5 / SHA1 / SHA256 / SHA512, digest(ByteArray) 返回原始摘要字节
    return when (algorithm.uppercase()) {
        "MD5" -> MD5.digest(data)
        "SHA-1", "SHA1" -> SHA1.digest(data)
        "SHA-256", "SHA256" -> SHA256.digest(data)
        "SHA-512", "SHA512" -> SHA512.digest(data)
        else -> throw IllegalArgumentException("Unsupported digest algorithm: $algorithm")
    }
}

/** HMAC 算法分发: 算法名归一化 (去掉 Hmac/HMAC 前缀, 含连字符变体)。 */
private fun hmacByAlgorithm(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
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
