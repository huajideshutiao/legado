package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

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

    /** autoBack 自身的互斥, 避免多入口并发触发自动备份。 */
    private val mutex = Mutex()

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    /**
     * 自动备份 (距上次备份超过一天时触发, 云端已有当日备份则只更新时间戳)。
     */
    @Suppress("UNUSED_PARAMETER")
    fun autoBack(context: Context) {
        if (shouldBackup()) {
            Coroutine.async {
                mutex.withLock {
                    if (shouldBackup()) {
                        val backupZipFileName = BackupShared.nowZipFileName()
                        if (!AppWebDav.hasBackUp(backupZipFileName)) {
                            BackupShared.backupLocked(AppConfig.backupPath)
                        } else {
                            LocalConfig.lastBackup = System.currentTimeMillis()
                        }
                    }
                }
            }.onError {
                AppLog.put("自动备份失败\n${it.localizedMessage}")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun backupLocked(context: Context, path: String?, uploadToWebDav: Boolean = true) {
        withContext(IO) {
            BackupShared.backupLocked(path, uploadToWebDav)
        }
    }

    fun clearCache() {
        BackupShared.clearCache()
    }

    /**
     * 复制备份 zip 到 SAF 目录 (content scheme 专用, 与原实现一致)。
     */
    @Throws(Exception::class)
    fun copyBackup(srcZipPath: String, uri: Uri, fileName: String) {
        val treeDoc = FileDoc.fromDir(uri)
        treeDoc.find(fileName)?.delete()
        val fileDoc = kotlin.runCatching {
            treeDoc.createFileIfNotExist(fileName)
        }.getOrElse {
            throw NoStackTraceException("创建文件失败")
        }
        val outputS = fileDoc.openOutputStream().getOrElse {
            if (it is NullPointerException) throw NoStackTraceException("打开OutputStream失败")
            throw it
        }
        outputS.use {
            FileInputStream(srcZipPath).use { inputS ->
                inputS.copyTo(outputS)
            }
        }
    }
}

/**
 * 备份/恢复流程中 Android 专属环节的实现, 由 App 启动早期注册到 [BackupRestoreHooks]。
 */
object AndroidBackupRestoreHook : BackupRestoreHook {

    override fun onBackupStart() {
        LocalConfig.lastBackup = System.currentTimeMillis()
    }

    /** content:// 目录走 SAF 复制; 普通路径交回 shared 的文件复制。 */
    override fun copyBackupTo(zipFilePath: String, destination: String, fileName: String): Boolean {
        if (!destination.isContentScheme()) return false
        Backup.copyBackup(zipFilePath, destination.toUri(), fileName)
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
        Restore.onRestoreFinished()
    }
}

/** 宿主启动早期注册一次 (任何备份/恢复之前)。 */
fun registerAndroidBackupRestoreHook() {
    BackupRestoreHooks.register(AndroidBackupRestoreHook)
}
