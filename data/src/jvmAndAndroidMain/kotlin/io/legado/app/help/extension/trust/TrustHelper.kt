package io.legado.app.help.extension.trust

import io.legado.app.help.extension.ExtensionPrefs

/**
 * 扩展签名信任判定。可信来源两类: 仓库签名指纹命中共可信 (仓库自身被用户添加);
 * 用户逐个确认过的扩展以 "pkgName:versionCode:signatureHash" 记录, 同包名确认
 * 新版本时旧记录被移除。
 *
 * 判定入参为中性字段 (pkgName/versionCode/签名指纹), Android 端由 app 模块
 * ExtensionLoader 从 PackageInfo 取值后传入, 桌面端由扩展装载器从 APK 解析传入。
 */
object TrustHelper {

    fun isTrusted(pkgName: String, versionCode: Long, fingerprints: List<String>): Boolean {
        val repoKeys = ExtensionPrefs.getRepos()
            .mapTo(HashSet()) { it.signingKeyFingerprint.lowercase() }
        if (fingerprints.any { it.lowercase() in repoKeys }) return true

        val key = "$pkgName:$versionCode:${fingerprints.last()}"
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
