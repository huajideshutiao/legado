package io.legado.app.help

import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SymmetricCrypto
import kotlin.concurrent.Volatile

/**
 * JS 加解密面的平台实现入口。
 *
 * commonMain 无平台加密实现 (hutool / krypto + mbedTLS 均为平台能力), JsExtensionsCommon
 * 的加解密默认方法经 [JsCryptoProviders] 转发到各端注册的实现:
 * - jvmAndAndroid: hutool DigestUtil/HMac + SymmetricCryptoAndroid/AsymmetricCryptoAndroid/SignAndroid
 * - native (iOS/鸿蒙): krypto MD5/SHA/HMAC + NativeSymmetricCrypto/NativeAsymmetricCrypto/NativeSign
 *
 * app 端注册时机: JsEnginesAndroid / registerDesktopSourceProviders / registerNativeJsEngines。
 */
interface JsCryptoProvider : JsEncodeUtils {

    /**
     * 创建对称加解密器。key 为 null 时使用随机密钥, iv 非空时设置初始向量。
     * 不支持的算法/模式由平台实现构造时抛错。
     */
    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCrypto

    /**
     * 创建非对称加解密器
     */
    fun createAsymmetricCrypto(
        transformation: String
    ): AsymmetricCrypto

    /**
     * 创建签名器
     */
    fun createSign(
        algorithm: String
    ): Sign
}

object JsCryptoProviders {
    @Volatile
    private var impl: JsCryptoProvider? = null

    fun register(impl: JsCryptoProvider) {
        this.impl = impl
    }

    fun get(): JsCryptoProvider = impl ?: error("JsCryptoProvider 未注册")
}
