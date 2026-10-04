package io.legado.app.help.extension.trust

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import io.legado.app.help.extension.ExtensionPrefs

/**
 * 扩展签名信任判定。可信来源两类: 仓库签名指纹命中共可信 (仓库自身被用户添加);
 * 用户逐个确认过的扩展以 "pkgName:versionCode:signatureHash" 记录, 同包名确认
 * 新版本时旧记录被移除。
 */
internal object TrustHelper {

    fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        val repoKeys = ExtensionPrefs.getRepos()
            .mapTo(HashSet()) { it.signingKeyFingerprint.lowercase() }
        if (fingerprints.any { it.lowercase() in repoKeys }) return true

        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return key in ExtensionPrefs.getTrustedSignatures()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        val trusted = ExtensionPrefs.getTrustedSignatures()
            // 移除同包名旧版本记录, 只保留最新确认
            .filterNot { it.startsWith("$pkgName:") }
            .toMutableSet()
        trusted += "$pkgName:$versionCode:$signatureHash"
        ExtensionPrefs.setTrustedSignatures(trusted)
    }

    fun revokeAll() {
        ExtensionPrefs.setTrustedSignatures(emptySet())
    }
}
