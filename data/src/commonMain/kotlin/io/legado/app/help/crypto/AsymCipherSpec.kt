package io.legado.app.help.crypto

/**
 * 非对称加解密 transformation 归一层 (commonMain, 四端唯一解析点)。
 *
 * JCA transformation 的别名/变体在此统一收敛为规范形态, 各端实现只消费 [AsymCipherSpec]:
 * - Android/desktop (hutool/JCA): [canonicalJcaTransformation] 输出规范 JCA 串;
 * - iOS/鸿蒙 (平台委托): expect [NativeAsymmetricCryptoOps] 直接收 [AsymCipherSpec],
 *   鸿蒙 ArkTS 桥只收规范 padding token (见 CryptoBridgeHandler), 不再自行解析 JCA 串。
 *
 * 归一规则 (四端口径一致):
 * - "RSA" / "RSA/ECB" (缺 padding 段) → PKCS#1 v1.5 (JCA "RSA" 缺省即 v1.5);
 * - "RSA/ECB/PKCS7Padding" → PKCS#1 v1.5 (RSA 下 PKCS7 与 PKCS1 填充字节一致, BC 语义);
 * - mode 段忽略 (JCA "ECB"/"None"/缺省对 RSA 等价);
 * - "OAEPWithSHA-<1|224|256|384|512>AndMGF1Padding" (连字符可省) → OAEP, mgf1 md = md;
 * - "NoPadding" → 裸 RSA (仅鸿蒙 cryptoFramework 支持, 输入须恰为模长字节);
 * - "SM2" (任意后缀) → SM2 (仅鸿蒙)。
 * 白名单外算法/padding 点名抛 UnsupportedOperationException, 各端行为一致。
 */
sealed class AsymCipherSpec {

    /** RSA: padding 归一 + OAEP 摘要位数 (非 OAEP 为 0)。 */
    class Rsa internal constructor(
        val padding: RsaPadding,
        val oaepDigestBits: Int,
    ) : AsymCipherSpec() {
        /** OAEP 摘要字节长 (分块 k-2h-2 用); 仅 OAEP padding 下有意义。 */
        val oaepDigestBytes: Int get() = oaepDigestBits / 8
    }

    /** SM2 (SM2_256|SM3, 密文 C1C3C2, 仅鸿蒙)。 */
    object Sm2 : AsymCipherSpec()

    enum class RsaPadding { PKCS1, OAEP, NO_PADDING }

    companion object {

        fun parse(transformation: String): AsymCipherSpec {
            val parts = transformation.uppercase().replace(" ", "").split("/")
            val algo = parts.getOrNull(0).orEmpty()
            if (algo == "SM2") return Sm2
            if (algo != "RSA") {
                throw UnsupportedOperationException(
                    "AsymmetricCrypto: unsupported algorithm '$transformation' " +
                        "(only RSA / RSA/ECB/PKCS1Padding / RSA/ECB/OAEPWithSHA-1|224|256|384|512AndMGF1Padding / RSA/ECB/NoPadding / SM2)"
                )
            }
            val padding = parts.getOrNull(2)
            // 缺 padding 段 ("RSA" / "RSA/ECB") → JCA 缺省 PKCS#1 v1.5
            if (padding == null || padding == "PKCS1PADDING" || padding == "PKCS7PADDING") {
                return Rsa(RsaPadding.PKCS1, 0)
            }
            if (padding == "NOPADDING") return Rsa(RsaPadding.NO_PADDING, 0)
            val oaep = padding.replace("-", "")
            if (oaep.startsWith("OAEPWITHSHA") && oaep.endsWith("ANDMGF1PADDING")) {
                val hash = oaep.removePrefix("OAEPWITHSHA").removeSuffix("ANDMGF1PADDING")
                val bits = when (hash) {
                    "1" -> 160
                    "224" -> 224
                    "256" -> 256
                    "384" -> 384
                    "512" -> 512
                    else -> throw UnsupportedOperationException(
                        "AsymmetricCrypto: unsupported OAEP hash in '$transformation'"
                    )
                }
                return Rsa(RsaPadding.OAEP, bits)
            }
            throw UnsupportedOperationException(
                "AsymmetricCrypto: unsupported padding in '$transformation' " +
                    "(PKCS1Padding/PKCS7Padding/NoPadding/OAEPWithSHA-1|224|256|384|512AndMGF1Padding)"
            )
        }

        /** Android/desktop (hutool/JCA) 用: 归一后的规范 JCA transformation。 */
        fun canonicalJcaTransformation(transformation: String): String = when (val spec = parse(transformation)) {
            is Sm2 -> "SM2"
            is Rsa -> when (spec.padding) {
                RsaPadding.PKCS1 -> "RSA/ECB/PKCS1Padding"
                RsaPadding.NO_PADDING -> "RSA/ECB/NoPadding"
                RsaPadding.OAEP -> "RSA/ECB/OAEPWithSHA-${spec.oaepDigestBits.let { if (it == 160) 1 else it }}AndMGF1Padding"
            }
        }
    }
}
