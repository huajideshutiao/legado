package io.legado.app.help.crypto

/**
 * native 端对称加解密平台原语下沉点 (expect object, iOS/鸿蒙 各自 actual)。
 *
 * 只暴露 NoPadding 裸原语: padding 由 [SymPadding] 在 Kotlin 层统一补/剥,
 * 分块 (ECB) 与多块拼接均由平台一次完成。算法/模式白名单在门面
 * [NativeSymmetricCrypto] 构造期校验; iOS actual 在此层点名校验算法/模式,
 * 鸿蒙 actual 原样转发, 由 ArkTS 桥 createCipher/createMd 报错透传。
 *
 * - iOS: CommonCrypto CCCryptorCreateWithMode (kCCModeECB/CBC/CFB/CTR/OFB,
 *   kCCAlgorithmAES128/DES/3DES), 随机数 SecRandomCopyBytes (Security.framework);
 * - 鸿蒙: ArkTS cryptoFramework napi 桥 (AES/SM4/3DES; DES 系统侧 API 20+, 低版本设备点名失败),
 *   随机数读 /dev/urandom (对齐原 mbedTLS entropy_poll 在鸿蒙沙箱的实现)。
 *
 * PCBC 无任一平台实现 (SunJCE 也无), actual 点名抛异常。
 */
internal expect object PlatformCipherOps {

    /** NoPadding 裸加解密; mode ∈ {ECB, CBC, CFB, OFB, CTR}, ECB 忽略 iv。 */
    fun encrypt(algorithm: String, mode: String, key: ByteArray, iv: ByteArray?, data: ByteArray): ByteArray

    fun decrypt(algorithm: String, mode: String, key: ByteArray, iv: ByteArray?, data: ByteArray): ByteArray

    /** AES-ECB 单块加密 (16 字节), 供纯 Kotlin [GcmNative] 的 CTR/GHASH 使用。 */
    fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray

    /** 平台安全熵源随机字节 (iOS SecRandomCopyBytes / 鸿蒙 /dev/urandom)。 */
    fun randomBytes(size: Int): ByteArray
}
