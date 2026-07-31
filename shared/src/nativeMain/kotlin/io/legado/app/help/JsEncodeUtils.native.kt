package io.legado.app.help

import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.NativeAsymmetricCrypto
import io.legado.app.help.crypto.NativeDigestOps
import io.legado.app.help.crypto.NativeHmacOps
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
 * 默认实现移至 [JsEncodeUtilsDefaults] interface, 由调用方多继承注入。
 *
 * - md5Encode / md5Encode16: 复用 commonMain [MD5Utils] (expect object, 纯 Kotlin 实现)。
 * - digestHex / digestBase64Str: [NativeDigestOps] 提供 MD5/SHA-1/SHA-224/SHA-256/SHA-384/SHA-512/RIPEMD160,
 *   与 jvmAndAndroidMain 的 hutool DigestUtil 字节级一致 (标准 FIPS 180-4 / RFC 1321 算法)。
 * - HMacHex / HMacBase64: [NativeHmacOps] HMAC, 与 jvmAndAndroidMain 的 hutool HMac 字节级一致 (RFC 2104)。
 * - Base64 编码: [encodeBase64Standard] (标准字母表 + padding + 不换行, 对齐 java.util.Base64.getEncoder())。
 * - 摘要/HMAC 输出小写 hex (hutool DigestUtil.digestHex 默认小写, [toHexLower] 对齐)。
 *
 * 算法名归一化: 接受 hutool/JDK 常见别名 (MD5/SHA-1/SHA1/SHA-256/SHA256/SHA-512/SHA512,
 * HMAC 前缀含连字符或无连字符均识别)。
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
 * nativeMain: [JsEncodeUtils] 默认实现 (iOS / 鸿蒙 两端共用)。
 *
 * KMP 限制: actual interface 成员 modality 必须与 expect 一致 (abstract), 不能带方法体;
 * 故将默认实现下沉到独立的 Defaults interface, 由调用方多继承注入。
 *
 * digest/HMAC 委托 [NativeDigestOps]/[NativeHmacOps] (expect object): mbedTLS 主实现, 异常回落 krypto/napi。
 */
@Suppress("unused")
interface JsEncodeUtilsDefaults : JsEncodeUtils {

    override fun md5Encode(str: String): String = MD5Utils.md5Encode(str)

    override fun md5Encode16(str: String): String = MD5Utils.md5Encode16(str)

    override fun digestHex(data: String, algorithm: String): String {
        val bytes = data.encodeToByteArray()
        return NativeDigestOps.digest(algorithm, bytes).toHexLower()
    }

    override fun digestBase64Str(data: String, algorithm: String): String {
        val bytes = data.encodeToByteArray()
        return NativeDigestOps.digest(algorithm, bytes).encodeBase64Standard()
    }

    @Suppress("FunctionName")
    override fun HMacHex(data: String, algorithm: String, key: String): String {
        val mac = NativeHmacOps.hmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray())
        return mac.toHexLower()
    }

    @Suppress("FunctionName")
    override fun HMacBase64(data: String, algorithm: String, key: String): String {
        val mac = NativeHmacOps.hmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray())
        return mac.encodeBase64Standard()
    }

    //******************对称加密解密工厂************************//

    /**
     * native 端工厂: 创建 [NativeSymmetricCrypto] (iOS krypto AES 全模式 / 鸿蒙 napi AES-ECB)。
     *
     * 与 [io.legado.app.help.JsEncodeUtilsDefaults.createSymmetricCrypto] 签名与行为对齐:
     * key==null 用随机密钥, iv 非空时 setIv; 不支持的算法/模式由 [NativeSymmetricCrypto] 构造抛异常降级。
     *
     * 返回类型为 commonMain [SymmetricCrypto] interface (跨端引用透明兼容),
     * 非 app 端 hutool `cn.hutool.crypto.symmetric.SymmetricCrypto` (native 无 hutool)。
     *
     * JS 中调用方式:
     * java.createSymmetricCrypto(transformation, key, iv).decrypt(data)
     * java.createSymmetricCrypto(transformation, key, iv).decryptStr(data)
     * java.createSymmetricCrypto(transformation, key, iv).encrypt(data)
     * java.createSymmetricCrypto(transformation, key, iv).encryptBase64(data)
     * java.createSymmetricCrypto(transformation, key, iv).encryptHex(data)
     */
    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCrypto {
        val crypto = NativeSymmetricCrypto(transformation, key)
        return if (iv != null && iv.isNotEmpty()) crypto.setIv(iv) else crypto
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
     * native 端工厂: 创建 [NativeAsymmetricCrypto] (iOS Security.framework 真实 / 鸿蒙 stub 抛异常, 见类注释)。
     * 与 [io.legado.app.help.JsEncodeUtilsDefaults.createAsymmetricCrypto] 签名对齐。
     */
    fun createAsymmetricCrypto(
        transformation: String
    ): AsymmetricCrypto {
        return NativeAsymmetricCrypto(transformation)
    }

    //******************签名工厂************************//

    /**
     * native 端工厂: 创建 [NativeSign] (iOS Security.framework 真实 / 鸿蒙 stub 抛异常, 见类注释)。
     * 与 [io.legado.app.help.JsEncodeUtilsDefaults.createSign] 签名对齐。
     */
    fun createSign(
        algorithm: String
    ): Sign {
        return NativeSign(algorithm)
    }
}
