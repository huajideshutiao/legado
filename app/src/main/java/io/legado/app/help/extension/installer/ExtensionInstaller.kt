package io.legado.app.help.extension.installer

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.legado.app.help.extension.ExtensionApkInfo
import io.legado.app.help.extension.ExtensionInstallScaffold
import io.legado.app.help.extension.util.ExtensionLoader
import java.io.File

/**
 * 扩展安装器 (Android 平台面): 元信息经 PackageManager 读取, 落盘到私有扩展目录
 * filesDir/exts (只读)。下载/进度/取消与覆盖校验在 [ExtensionInstallScaffold]
 * (与桌面端同一份实现)。不调用系统包安装器; 共享扩展的卸载走系统卸载界面。
 */
internal class ExtensionInstaller(private val context: Context) : ExtensionInstallScaffold() {

    override fun tempFile(pkgName: String): File = File(context.cacheDir, "$pkgName.apk")

    override fun readApkInfo(file: File): ExtensionApkInfo? =
        ExtensionLoader.archiveApkInfo(context, file)

    override fun readInstalledInfo(pkgName: String): ExtensionApkInfo? =
        ExtensionLoader.installedApkInfo(context, pkgName)

    override fun placeApk(file: File, info: ExtensionApkInfo, replaced: Boolean) {
        ExtensionLoader.installPrivateExtensionFile(context, file, info.pkgName, replaced)
    }

    fun uninstallSharedApk(pkgName: String) {
        val intent = Intent(Intent.ACTION_DELETE, "package:$pkgName".toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
