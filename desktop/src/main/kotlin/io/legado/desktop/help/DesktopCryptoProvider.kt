package io.legado.desktop.help

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * 桌面端 JCE 补丁 provider 注册 (对应书源加解密脚本在 JVM 端的兼容层)。
 *
 * 背景: 书源登录/解密脚本常写 `java.createSymmetricCrypto('AES/CBC/PKCS7Padding', ...)`
 * 或直接 `cn.hutool.crypto.SecureUtil.aes('AES/CBC/PKCS7Padding', ...)`, 内部走
 * `Cipher.getInstance("AES/CBC/PKCS7Padding")`。Android 内置 Conscrypt/BC 支持该变换,
 * 但桌面端 JVM 的 SunJCE 只认 PKCS5Padding, 抛
 * `NoSuchAlgorithmException: Cannot find any provider supporting AES/CBC/PKCS7Padding`
 * (PKCS5/PKCS7 在 AES 块大小 16 字节下字节级等价, 属纯算法名缺失)。
 *
 * 为什么是 BouncyCastle 而不是自研别名 provider: JVM 的 JCE 框架对 `javax.crypto.Cipher`
 * 有 provider 认证要求 (JceSecurity.ProviderVerifier), 未签名 provider 一律抛
 * "JCE cannot authenticate the provider" —— 只有 JDK 内置模块或由 Oracle JCE Code
 * Signing CA 签名的 jar 才能提供 Cipher 服务。bcprov 官方 jar 正是该 CA 签发的
 * (BouncyCastle 长期与 Oracle 的 JCE 兼容安排), 是零自研、跨 JDK 稳定的唯一路径。
 *
 * 幂等: 同名 provider 已存在时 Security.addProvider 返回 -1, 重复调用无副作用。
 */
private val bcProviderRegistered: Boolean by lazy {
    runCatching { Security.addProvider(BouncyCastleProvider()) >= 0 }
        .getOrDefault(false)
}

/**
 * 确保 BouncyCastle 已注册 (幂等), 供 Main.main() 启动早期调用, 先于任何书源 JS 执行。
 * 补齐: AES 各模式 PKCS7Padding、SM4、RC4 等 SunJCE 缺失的算法。
 */
fun ensureJvmCryptoProviders() {
    bcProviderRegistered
}
