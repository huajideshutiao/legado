package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.App
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.lib.theme.MonetColorExtract
import io.legado.app.utils.FileDoc
import io.legado.app.utils.copyToDocumentAtomically
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * 备份 (app 端薄壳)。
 *
 * 条目收集 / 序列化 / zip / 上传全部在 [BackupShared], 本 object 只留 Android 专属段:
 * autoBack 调度 + SAF(content://) 复制 + LocalConfig.lastBackup + 背景图上传,
 * 经 [AndroidBackupRestoreHook] 注入 shared, 保证与桌面/iOS 走同一条核心路径。
 */
object Backup {

    /** 备份工作目录 (filesDir/backup), 与 [BackupShared.backupPath] 同一路径。 */
    val backupPath: String get() = BackupShared.backupPath

    /** 备份 zip 临时文件, 与 [BackupShared.zipFilePath] 同一路径。 */
    val zipFilePath: String get() = BackupShared.zipFilePath

    /**
     * 自动备份 (距上次备份超过一天时触发, 云端已有当日备份则只更新时间戳)。
     */
    @Suppress("UNUSED_PARAMETER")
    fun autoBack(context: Context) {
        BackupShared.autoBack()
    }

    fun clearCache() {
        BackupShared.clearCache()
    }

    /**
     * 复制备份 zip 到 SAF 目录 (content scheme 专用) 下的 [subDir] 子目录 (不存在则建)。
     *
     * 目标名上已有同名旧文档时由 [copyToDocumentAtomically] 先把它挪成契约过渡件, 就位成功后删
     * 过渡件, 失败则改回目标名 —— 不会旧件已清而新件没写成。备份包含图集与封面, zip 体积
     * 可能很大, 按流写, 不整份读进内存; 写不成时抛异常 (调用方按失败上报), 提供方不做同目录就位
     * 时由该入口按既有回落直写 (目标名上可能出现半截)。
     */
    @Throws(Exception::class)
    fun copyBackup(srcZipPath: String, uri: Uri, fileName: String, subDir: String) {
        val treeDoc = FileDoc.fromDir(uri)
        // SAF 树引用不能拼路径: 子目录在树内建出 (提供方拒绝建目录时如实报失败)
        val targetDir = runCatching { treeDoc.createFolderIfNotExist(subDir) }
            .getOrElse { throw NoStackTraceException("创建备份目录失败: $subDir") }
        FileInputStream(srcZipPath).use { inputS ->
            if (!copyToDocumentAtomically(App.instance, targetDir.uri, fileName, inputS)) {
                throw NoStackTraceException("写入书籍保存位置失败: $fileName")
            }
        }
    }
}

/**
 * 备份/恢复流程中 Android 专属环节的实现, 由 App 启动早期注册到 [BackupRestoreHooks]。
 */
object AndroidBackupRestoreHook : BackupRestoreHook {

    override fun getLastBackup(): Long = LocalConfig.lastBackup

    override fun setLastBackup(time: Long) {
        LocalConfig.lastBackup = time
    }

    override fun onBackupStart() {
        LocalConfig.lastBackup = System.currentTimeMillis()
    }

    /** content:// 目录走 SAF 复制 (在树内建出备份子目录); 普通路径交回 shared 的文件复制。 */
    override fun copyBackupTo(
        zipFilePath: String,
        destination: String,
        fileName: String,
        subDir: String,
    ): Boolean {
        if (!destination.isContentScheme()) return false
        Backup.copyBackup(zipFilePath, destination.toUri(), fileName, subDir)
        return true
    }

    /** 上传阅读背景图到 WebDav (依赖 Array<File> + 网络判断, 未下沉)。 */
    override suspend fun onBackupFinished(uploadToWebDav: Boolean) {
        if (!uploadToWebDav) return
        ReadBookConfig.getAllPicBgStr().map {
            if (it.contains(File.separator)) {
                File(it)
            } else {
                App.instance.externalFiles.getFile("bg", it)
            }
        }.let {
            AppWebDav.upBgs(it.toTypedArray())
        }
    }

    override fun unZipBackup(zipPath: String, destDir: String): Boolean =
        Restore.unZipBackup(zipPath, destDir)

    override fun onRestoreFromZipFinished() {
        LocalConfig.lastBackup = System.currentTimeMillis()
    }

    override fun readLegacyConfig(dirPath: String): Map<String, Any?>? =
        Restore.readLegacyConfigXml(dirPath)

    override fun onThemeConfigRestored() {
        Restore.upThemeConfig()
    }

    override suspend fun onRestoreFinished() {
        // 偏好已被恢复覆盖, 漫画扩展的仓库/信任/启用表需同步重载内存态
        MangaExtensionManager.onRestoreFinished()
        // 莫奈跟随开关也已被覆盖: 同轮把进程内壁纸监听注册态对齐到新 pref,
        // 否则残留监听会在壁纸变化时把恢复后的主题色拽回壁纸色
        withContext(Dispatchers.Main) {
            MonetColorExtract.syncFollowAfterRestore(App.instance)
        }
        Restore.onRestoreFinished()
    }
}

/** 宿主启动早期注册一次 (任何备份/恢复之前)。 */
fun registerAndroidBackupRestoreHook() {
    BackupRestoreHooks.register(AndroidBackupRestoreHook)
}
