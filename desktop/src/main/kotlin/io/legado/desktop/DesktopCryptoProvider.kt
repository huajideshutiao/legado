package io.legado.desktop

import cn.hutool.crypto.GlobalBouncyCastleProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * 桌面端 JCA 补齐 provider 注册 (SunJCE 缺国密/PKCS7Padding)。
 *
 * 设计: hutool 的 GlobalBouncyCastleProvider 会在 classpath 出现 BC 类时把 BC **点名**为
 * Cipher/MessageDigest 的 provider (强制覆盖 SunJCE), 而 BC 的 RSA Cipher 报
 * getBlockSize()=127 (SunJCE=0), 会触发 hutool AsymmetricCrypto 分段加密, 网易云
 * weapi 的 128 字节定长输入被切两段产出错误 encSecKey (2026-08-15 教训)。
 * 因此这里必须成对执行:
 * 1. setUseBouncyCastle(false) 关闭 hutool 的点名行为, hutool 全部走默认 JCA 解析;
 * 2. Security.addProvider 把 BC 追加到 provider 链**末尾** (最低优先级):
 *    SunJCE 认识的算法 (AES/RSA/DES/SHA...) 逐字节保持 SunJCE 行为不变,
 *    只有 SunJCE 缺的算法 (SM2/SM3/SM4/PKCS7Padding) 才落到 BC。
 *
 * 必须早于任何一次 hutool 加解密调用执行 (main 入口最先注册)。
 */
object DesktopCryptoProvider {

    fun install() {
        GlobalBouncyCastleProvider.setUseBouncyCastle(false)
        // 先移除再追加: 无论宿主 JVM 是否已注册 BC (java.security 配置可能把它排在链首),
        // 重装后 BC 恒在链末 (最低优先级), 不存在"已注册则跳过"导致 BC 位次先于 SunJCE 的侥幸分支
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
