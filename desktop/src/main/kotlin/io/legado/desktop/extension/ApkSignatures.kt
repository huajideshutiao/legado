// 扩展 APK 签名提取: 经官方 apksig 库校验并取全部签名证书, 输出与 Android 端
// ExtensionLoader.getSignatures 同口径的 SHA-256 小写 hex (desktop 测试级: 只提取不判信任,
// 信任决策沿用宿主 TrustHelper 名单语义, 由调用方自行比对)。
package io.legado.desktop.extension

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

object ApkSignatures {

    /**
     * @return 全部签名证书的 SHA-256 小写 hex; APK 无任何签名抛 [ApkVerifier.Exception] 之外
     * 的校验失败时抛 IOException/IllegalState, 由加载器归为对应 NotLoaded 原因。
     */
    fun sha256HexList(apkFile: File): List<String> {
        val result = ApkVerifier.Builder(apkFile).build().verify()
        if (!result.isVerified) {
            val issue = result.errors.firstOrNull()?.toString() ?: "unverified"
            throw IllegalStateException("APK 签名校验失败: $issue")
        }
        return result.signerCertificates
            .map { certificate -> sha256Hex(certificate.encoded) }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
