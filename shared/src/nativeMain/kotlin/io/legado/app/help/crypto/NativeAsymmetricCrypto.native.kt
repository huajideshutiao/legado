package io.legado.app.help.crypto

/**
 * nativeMain: 非对称加解密门面 [AsymmetricCrypto] 降级实现 (iOS / 鸿蒙 两端共用 stub)。
 *
 * 两端 stub 逻辑完全一致, 下沉到 nativeMain 共用, 待真实化时各端可独立替换为平台原生实现:
 * - iOS 端 RSA 非对称加解密需要 Security.framework (SecKeyCreateEncryptedData/SecKeyCreateDecryptedData)
 *   cinterop + ASN.1 密钥解析, 当前 iosMain 仅配置 quickjs cinterop, 未配置 Security.framework cinterop;
 * - 鸿蒙端 RSA 非对称加解密需要 ohos.security.cryptoAsymCipher napi, 当前 ohosMain 走 linuxArm64 target
 *   (OpenHarmony arm64 与 linuxArm64 ABI 兼容), 无 ohos napi;
 * - krypto 库不提供 RSA。两端共用本降级 stub (不抛 UnsupportedOperationException):
 *   - setPrivateKey/setPublicKey: 缓存 key 字节, 返回 this, 让 JS 链式调用不崩;
 *   - encrypt/decrypt/encryptHex/encryptBase64/decryptStr: 返回空值 (空 ByteArray / 空 String),
 *     不 throw, 让 JS 调用链继续执行 (JS 业务代码可检查空返回值降级处理)。
 *
 * 调用方降级: JS 桥 createAsymmetricCrypto 在 iOS/鸿蒙端 P0 阶段不桥接复杂对象 (java 变量注入时跳过,
 * JS 里为 undefined), 实际不会触发本 stub; 备份加解密走 [io.legado.app.help.storage.BackupAES] (AES/ECB),
 * 不经此门面。预留 actual 实现确保 commonMain 引用编译通过 + 未来 JS 桥扩展时不 throw。
 *
 * 注: krypto 4.0.10 已发布 linuxArm64 变体, 鸿蒙端走 linuxArm64 target, 与 iOS 端实现结构一致,
 * 故两端共用 stub 下沉到 nativeMain。
 *
 * TODO: 后续按需引入平台原生 API (iOS platform.Security.* cinterop + SecKeyCreateEncryptedData/
 * SecKeyCreateDecryptedData; 鸿蒙 ohos.security.cryptoAsymCipher napi),
 * 或纯 Kotlin RSA 实现 (BigInteger 模幂), 届时各端在 iosMain/ohosMain 提供 actual 实现替换本 stub。
 */
class NativeAsymmetricCrypto(
    @Suppress("UNUSED_PARAMETER") algorithm: String,
) : AsymmetricCrypto {

    // 缓存 key 字节, 不 throw; 为未来接入平台原生 API 或纯 Kotlin RSA 实现预留接口
    private var privateKeyBytes: ByteArray? = null
    private var publicKeyBytes: ByteArray? = null

    override fun setPrivateKey(key: ByteArray): AsymmetricCrypto {
        privateKeyBytes = key
        return this
    }

    override fun setPrivateKey(key: String): AsymmetricCrypto = setPrivateKey(key.encodeToByteArray())

    override fun setPublicKey(key: ByteArray): AsymmetricCrypto {
        publicKeyBytes = key
        return this
    }

    override fun setPublicKey(key: String): AsymmetricCrypto = setPublicKey(key.encodeToByteArray())

    // 降级: 返回空 ByteArray, 不 throw, 让 JS 调用链继续 (JS 业务代码可检查空返回值降级)
    override fun decrypt(data: Any, usePublicKey: Boolean?): ByteArray = ByteArray(0)

    override fun decryptStr(data: Any, usePublicKey: Boolean?): String = ""

    override fun encrypt(data: Any, usePublicKey: Boolean?): ByteArray = ByteArray(0)

    override fun encryptHex(data: Any, usePublicKey: Boolean?): String = ""

    override fun encryptBase64(data: Any, usePublicKey: Boolean?): String = ""
}
