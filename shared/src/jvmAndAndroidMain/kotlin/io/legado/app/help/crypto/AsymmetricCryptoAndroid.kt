package io.legado.app.help.crypto

import cn.hutool.crypto.CipherWrapper
import cn.hutool.crypto.KeyUtil
import cn.hutool.crypto.asymmetric.KeyType
import io.legado.app.utils.EncoderUtils
import java.io.InputStream
import javax.crypto.Cipher


// KMP 化: 原 `class AsymmetricCrypto` 改名为 `AsymmetricCryptoAndroid` (仿 SymmetricCryptoAndroid
// 命名约定), 避免与 commonMain 同包同名 interface AsymmetricCrypto 在合并编译时冲突
// (Kotlin 规则: 同包 class-like declarations 不能同名)。
// 方法体零变化: 仅追加 `: AsymmetricCrypto` (commonMain interface) 实现标记 + 方法 `override`。
// 例外: 原 @JvmOverloads + `usePublicKey: Boolean? = true` 默认值生成的 5 个单参 JVM 重载
// (decrypt/decryptStr/encrypt/encryptHex/encryptBase64) 在 override 化后丢失——Kotlin 禁止
// override 声明参数默认值, @JvmOverloads 无从生成 1 参方法, JS 桥反射按 1 参调用即报
// "Cannot find method ... with args [String]"。已显式补回 (见文件底部 JVM-only 单参重载)。
//
// @Keep 移除：shared 无 androidx.annotation 依赖，JS 桥反射保活改由 consumer-rules.pro -keep 登记（照 JsURL/StrResponse 先例）。
@Suppress("unused")
class AsymmetricCryptoAndroid(algorithm: String) : cn.hutool.crypto.asymmetric.AsymmetricCrypto(algorithm), AsymmetricCrypto {

    /**
     * 2026-08-15 教训: 非对称 Cipher 强制走 JCE 默认 provider (SunJCE), 防止 hutool 在
     * classpath 出现 BC 类时 (GlobalBouncyCastleProvider 自动激活) 用 BC 的 RSA Cipher:
     * BC 的 RSA getBlockSize()=127 (SunJCE=0), 而 AsymmetricCrypto.encrypt 以 getBlockSize
     * 作分段大小, 128 字节输入 (>127) 触发 doFinalWithBlock 分段加密 (127+1 两段分别 RSA
     * 再拼接), 产出错误的 encSecKey → 网易云 weapi 全部 200 空体 (07c2a5e5 引入 bcprov 后
     * 网易云发现/目录无法加载, 根因见 desktop/build.gradle.kts 注释)。
     *
     * 当前 bcprov 已移除, 本 override 与 hutool 默认行为一致 (JCE 解析); 保留作为防御:
     * 将来若重新引入 BC 类, 非对称加密仍走 SunJCE (getBlockSize=0 不分段, 结果正确),
     * BC 仅用于对称/摘要等路径 (AES 输出与 SunJCE 逐字节一致, PKCS7Padding 由
     * SymmetricCryptoAndroid 归一化解决, 无需 BC)。
     *
     * JCE 不支持的 BC 专属算法 (如 SM2) 回退 hutool 默认 (BC)。
     */
    override fun initCipher() {
        try {
            cipherWrapper = CipherWrapper(Cipher.getInstance(algorithm))
        } catch (e: Exception) {
            // JCE 不支持的算法 (BC 专属) 回退 hutool 默认
            super.initCipher()
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    override fun setPrivateKey(key: ByteArray): AsymmetricCryptoAndroid {
        setPrivateKey(
            KeyUtil.generatePrivateKey(this.algorithm, key)
        )
        return this
    }

    override fun setPrivateKey(key: String): AsymmetricCryptoAndroid = setPrivateKey(key.encodeToByteArray())

    @Suppress("MemberVisibilityCanBePrivate")
    override fun setPublicKey(key: ByteArray): AsymmetricCryptoAndroid {
        setPublicKey(
            KeyUtil.generatePublicKey(this.algorithm, key)
        )
        return this
    }

    override fun setPublicKey(key: String): AsymmetricCryptoAndroid = setPublicKey(key.encodeToByteArray())

    private fun getKeyType(usePublicKey: Boolean? = true): KeyType {
        return when (usePublicKey) {
            true -> KeyType.PublicKey
            else -> KeyType.PrivateKey
        }
    }

    override fun decrypt(data: Any, usePublicKey: Boolean?): ByteArray {
        return when (data) {
            is ByteArray -> decrypt(data, getKeyType(usePublicKey))
            is String -> decrypt(data, getKeyType(usePublicKey))
            is InputStream -> decrypt(data, getKeyType(usePublicKey))
            else -> throw IllegalArgumentException("Unexpected input type")
        }
    }

    override fun decryptStr(data: Any, usePublicKey: Boolean?): String {
        return when (data) {
            is ByteArray -> String(decrypt(data, getKeyType(usePublicKey)))
            is String -> decryptStr(data, getKeyType(usePublicKey))
            is InputStream -> String(decrypt(data, getKeyType(usePublicKey)))
            else -> throw IllegalArgumentException("Unexpected input type")
        }
    }

    override fun encrypt(data: Any, usePublicKey: Boolean?): ByteArray {
        return when (data) {
            is ByteArray -> encrypt(data, getKeyType(usePublicKey))
            is String -> encrypt(data, getKeyType(usePublicKey))
            is InputStream -> encrypt(data, getKeyType(usePublicKey))
            else -> throw IllegalArgumentException("Unexpected input type")
        }
    }

    override fun encryptHex(data: Any, usePublicKey: Boolean?): String {
        return when (data) {
            is ByteArray -> encryptHex(data, getKeyType(usePublicKey))
            is String -> encryptHex(data, getKeyType(usePublicKey))
            is InputStream -> encryptHex(data, getKeyType(usePublicKey))
            else -> throw IllegalArgumentException("Unexpected input type")
        }
    }

    override fun encryptBase64(data: Any, usePublicKey: Boolean?): String {
        return EncoderUtils.base64Encode(encrypt(data, usePublicKey))
    }

    // ============ JVM-only 单参重载 ============
    // 原版 @JvmOverloads 语义还原: `usePublicKey: Boolean? = true` 缺省即公钥加密/解密。
    // 书源 JS 按 `java.createAsymmetricCrypto(...).encryptHex(str)` 单参调用
    // (hutool 父类仅 KeyType 双参变体, 无 1 参方法), 缺了这些重载 JavaObjectBridge
    // 反射 findMethod 返回 null → IllegalStateException。
    // 不进 commonMain interface (native 端 NativeJsExtensionsBridge 自有单参分派,
    // 仿 SymmetricCryptoAndroid JVM-only 附加成员先例)。
    fun decrypt(data: Any): ByteArray = decrypt(data, true)

    fun decryptStr(data: Any): String = decryptStr(data, true)

    fun encrypt(data: Any): ByteArray = encrypt(data, true)

    fun encryptHex(data: Any): String = encryptHex(data, true)

    fun encryptBase64(data: Any): String = encryptBase64(data, true)

}
