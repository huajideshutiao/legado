@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.sync

import io.legado.app.constant.AppLog
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.storage.BackupShared
import io.legado.app.help.storage.RestoreShared
import io.legado.app.utils.AlphanumComparator
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLUbiquitousItemDownloadingStatusKey
import platform.Foundation.NSURLUbiquitousItemDownloadingStatusNotDownloaded

/**
 * 完整备份的 iCloud Documents 通道 (对标 [io.legado.app.help.AppWebDavShared] 的
 * backUpWebDav / getBackupNames / restoreWebDav 三个动作)。
 *
 * 备份包就是 [BackupShared] 产出的那个 zip, 文件名沿用 [BackupShared.nowZipFileName]
 * (`backup{yyyy-MM-dd}[-{设备名}].zip`), 与 WebDav 上的包同名同格式, 两边可互导。
 * 恢复走 [RestoreShared.restoreFromZip] 的公共路径, 不另写解析逻辑。
 *
 * # 与 KV 通道的分工
 * 阅读进度这类高频小数据走 [IosICloudProgressSync] 的 `NSUbiquitousKeyValueStore`;
 * 这里只管整包备份 (几百 KB ~ 数 MB), 低频、要历史版本、要能列举。
 *
 * # 云端有、本地没有的文件
 * iCloud Documents 与 WebDav 的最大差异: 容器里的文件可能只是**占位符**, 内容还在云端。
 * 直接读会得到空文件或读失败, 且 [NSFileManager.contentsOfDirectoryAtPath] 列出来的是
 * `.backup2026-07-31.zip.icloud` 这种隐藏占位名。故:
 * - 列举时用 [logicalName] 把占位名还原成真实文件名;
 * - 恢复前先 [ensureDownloaded]: `startDownloadingUbiquitousItemAtURL` 触发下载, 再轮询
 *   `NSURLUbiquitousItemDownloadingStatusKey` 直到不再是 NotDownloaded, 超时放弃并报错。
 *
 * 启用前置条件见 [IosICloud] 的四步清单; 未启用时容器 URL 取不到, 所有入口返回空/false。
 */
object IosICloudBackupSync {

    /** 下载状态轮询间隔。 */
    private const val POLL_INTERVAL_MS = 500L

    /** 单个备份包等待下载完成的上限。 */
    private const val DOWNLOAD_TIMEOUT_MS = 120_000L

    private val fileManager: NSFileManager get() = NSFileManager.defaultManager

    /** iCloud 是否可用 (总开关已开 + 容器已配置 + 用户已登录 iCloud)。 */
    suspend fun isAvailable(): Boolean = containerDocumentsUrl() != null

    /**
     * 走公共备份路径打包, 再把 zip 传到 iCloud 容器。
     *
     * @param uploadToWebDav 是否同时上传 WebDav (两条通道并存, 默认各走各的)
     * @return 上传到 iCloud 的文件名, 失败返回 null
     */
    suspend fun backupToICloud(uploadToWebDav: Boolean = false): String? {
        if (!IosICloud.enabled) return null
        val fileName = BackupShared.nowZipFileName()
        val localZip = runCatching { BackupShared.backupLocked(uploadToWebDav = uploadToWebDav) }
            .onFailure { AppLog.put("iCloud 备份打包失败\n${it.message}", it) }
            .getOrNull() ?: return null
        return if (uploadBackup(localZip, fileName)) fileName else null
    }

    /**
     * 上传已有备份 zip 到 iCloud 容器。
     *
     * 用 `setUbiquitous` 而非直接写容器目录: 该 API 自带文件协调 (等价 NSFileCoordinator),
     * 但语义是**移动**, 故先复制一份到临时目录再移进去, 不动调用方的原文件。
     */
    suspend fun uploadBackup(localZipPath: String, fileName: String): Boolean {
        if (!IosICloud.enabled) return false
        val docs = containerDocumentsUrl() ?: return false
        val target = docs.URLByAppendingPathComponent(fileName) ?: return false
        val stagePath = NSTemporaryDirectory().trimEnd('/') + "/" + fileName
        return withContext(Dispatchers.Default) {
            runCatching {
                BackupFileOps.delete(stagePath)
                BackupFileOps.copyFile(localZipPath, stagePath)
                removeIfExists(target)
                val ok = memScoped {
                    val err = alloc<ObjCObjectVar<NSError?>>()
                    val done = fileManager.setUbiquitous(
                        flag = true,
                        itemAtURL = NSURL.fileURLWithPath(stagePath),
                        destinationURL = target,
                        error = err.ptr
                    )
                    if (!done) {
                        AppLog.put("iCloud 上传备份失败: ${err.value?.localizedDescription}")
                    }
                    done
                }
                BackupFileOps.delete(stagePath)
                ok
            }.getOrElse {
                AppLog.put("iCloud 上传备份失败\n${it.message}", it)
                false
            }
        }
    }

    /**
     * 列出容器内的历史备份名 (按名称倒序, 与
     * [io.legado.app.help.AppWebDavShared.getBackupNames] 同语义)。
     *
     * 未下载的占位文件也会列出, 名字已还原为真实文件名。
     */
    suspend fun getBackupNames(): List<String> {
        if (!IosICloud.enabled) return emptyList()
        val docs = containerDocumentsUrl() ?: return emptyList()
        val dirPath = docs.path ?: return emptyList()
        return withContext(Dispatchers.Default) {
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val items = fileManager.contentsOfDirectoryAtPath(dirPath, err.ptr)
                if (items == null) {
                    AppLog.put("iCloud 列举备份失败: ${err.value?.localizedDescription}")
                    return@memScoped emptyList<String>()
                }
                items.filterIsInstance<String>()
                    .map { logicalName(it) }
                    .filter { it.startsWith("backup") }
                    .distinct()
                    .sortedWith(AlphanumComparator)
                    .reversed()
            }
        }
    }

    /** 最近一次备份名 (按名称倒序取首个, 名字自带日期)。 */
    suspend fun lastBackupName(): String? = getBackupNames().firstOrNull()

    /** 指定备份是否已存在容器内。 */
    suspend fun hasBackup(fileName: String): Boolean = fileName in getBackupNames()

    /**
     * 从 iCloud 恢复指定备份: 确保内容已落到本地 → 复制到临时目录 → 走公共恢复路径。
     */
    suspend fun restoreFromICloud(fileName: String): Boolean {
        if (!IosICloud.enabled) return false
        val docs = containerDocumentsUrl() ?: return false
        val target = docs.URLByAppendingPathComponent(fileName) ?: return false
        if (!ensureDownloaded(target)) {
            AppLog.put("iCloud 备份未下载完成: $fileName", toast = true)
            return false
        }
        val remotePath = target.path ?: return false
        val localPath = NSTemporaryDirectory().trimEnd('/') + "/restore_" + fileName
        return runCatching {
            BackupFileOps.delete(localPath)
            BackupFileOps.copyFile(remotePath, localPath)
            RestoreShared.restoreFromZip(localPath)
            BackupFileOps.delete(localPath)
            true
        }.getOrElse {
            AppLog.put("iCloud 恢复备份失败\n${it.message}", it, toast = true)
            false
        }
    }

    /** 容器内的 Documents 子目录 (对用户在"文件"App 可见), 不存在时创建。 */
    private suspend fun containerDocumentsUrl(): NSURL? {
        if (!IosICloud.enabled) return null
        // URLForUbiquityContainerIdentifier 首次调用会阻塞 (要和 daemon 通信), 不能放主线程
        return withContext(Dispatchers.Default) {
            val container = fileManager
                .URLForUbiquityContainerIdentifier(IosICloud.CONTAINER_ID)
                ?: return@withContext null
            val docs = container.URLByAppendingPathComponent("Documents")
                ?: return@withContext null
            val path = docs.path
            if (path != null && !fileManager.fileExistsAtPath(path)) {
                memScoped {
                    val err = alloc<ObjCObjectVar<NSError?>>()
                    fileManager.createDirectoryAtURL(
                        docs,
                        withIntermediateDirectories = true,
                        attributes = null,
                        error = err.ptr
                    )
                }
            }
            docs
        }
    }

    /**
     * 触发下载并等待内容落到本地。
     *
     * 已是最新直接返回 true; 取不到下载状态说明不是 ubiquitous 项, 退化为文件存在性判断。
     */
    private suspend fun ensureDownloaded(url: NSURL): Boolean {
        if (isDownloaded(url)) return true
        val started = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val ok = fileManager.startDownloadingUbiquitousItemAtURL(url, err.ptr)
            if (!ok) AppLog.put("iCloud 触发下载失败: ${err.value?.localizedDescription}")
            ok
        }
        if (!started) return false
        var waited = 0L
        while (waited < DOWNLOAD_TIMEOUT_MS) {
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
            if (isDownloaded(url)) return true
        }
        return false
    }

    private fun isDownloaded(url: NSURL): Boolean = memScoped {
        val value = alloc<ObjCObjectVar<Any?>>()
        val err = alloc<ObjCObjectVar<NSError?>>()
        val ok = url.getResourceValue(
            value.ptr,
            forKey = NSURLUbiquitousItemDownloadingStatusKey,
            error = err.ptr
        )
        val status = if (ok) value.value as? String else null
        when {
            status == null -> url.path?.let { fileManager.fileExistsAtPath(it) } == true
            else -> status != NSURLUbiquitousItemDownloadingStatusNotDownloaded
        }
    }

    private fun removeIfExists(url: NSURL) {
        val path = url.path ?: return
        if (!fileManager.fileExistsAtPath(path)) return
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            fileManager.removeItemAtPath(path, err.ptr)
        }
    }

    /** 未下载的文件在目录里是 `.名字.icloud` 占位符, 还原成真实文件名。 */
    private fun logicalName(rawName: String): String {
        if (!rawName.endsWith(".icloud")) return rawName
        return rawName.removeSuffix(".icloud").removePrefix(".")
    }
}
