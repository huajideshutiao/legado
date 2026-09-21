package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.constant.AppLog
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.storage.BackupShared
import io.legado.app.help.storage.RestoreShared
import io.legado.app.utils.InputStream
import io.legado.app.web.api.WebApiResponse

/**
 * Web 服务备份/恢复控制器 (2026-09-06 用户裁决: 必须用全端统一的 zip 备份格式,
 * 与 App 备份/WebDav 恢复同一管线 [BackupShared]/[RestoreShared], 不另造 JSON 结构)。
 *
 * - 备份: [BackupShared.backupForDownload] 生成标准备份 zip (书架/书签/分组/书源/替换规则/
 *   阅读配置/主题/config 等, 与 App/WebDav 完全一致), 返回磁盘文件流供浏览器下载
 * - 恢复: 上传的 zip 落临时文件后走 [RestoreShared.restoreFromZip] (解压→逐 DAO 恢复)
 */
object BackupController {

    /** 生成独占临时备份 zip，并以关闭即删除的磁盘流返回。 */
    suspend fun getBackupZip(): WebApiResponse {
        var zipPath: String? = null
        return try {
            val createdZipPath = BackupShared.backupForDownload()
            zipPath = createdZipPath
            // 先固定长度再打开流；该路径仅属于本次响应，后续备份不会覆盖它。
            val contentLength = BackupFileOps.fileSize(createdZipPath)
            WebApiResponse.Stream(
                inputStream = DeleteOnCloseInputStream(
                    delegate = BackupFileOps.openInputStream(createdZipPath),
                    path = createdZipPath,
                ),
                contentType = "application/zip",
                contentLength = contentLength,
                headers = mapOf(
                    "Content-Disposition" to "attachment; filename=backup.zip",
                    // Native Ktor 壳从 headers 写响应头；JVM 壳用 contentLength 创建定长响应。
                    "Content-Length" to contentLength.toString(),
                ),
            )
        } catch (e: Exception) {
            zipPath?.let { BackupFileOps.delete(it) }
            AppLog.put("生成备份失败", e)
            WebApiResponse.Json(
                ReturnData().setErrorMsg("生成备份失败: ${e.message ?: "未知错误"}")
            )
        }
    }

    /** 删除动作绑定到响应流生命周期；先关文件句柄，再删文件，兼容 Windows。 */
    private class DeleteOnCloseInputStream(
        private val delegate: InputStream,
        private val path: String,
    ) : InputStream() {
        private var closed = false

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray): Int = delegate.read(b)

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun skip(n: Long): Long = delegate.skip(n)

        override fun available(): Int = delegate.available()

        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate.close()
            } finally {
                BackupFileOps.delete(path)
            }
        }
    }

    /** 从上传的备份 zip 恢复 (multipart 文件字段 fileData, 服务端已落临时文件)。 */
    suspend fun restoreBackup(files: Map<String, String>): ReturnData {
        val zipPath = files["fileData"]
            ?: return ReturnData().setErrorMsg("备份文件不能为空")
        return runCatching {
            val success = RestoreShared.restoreFromZip(zipPath)
            if (success) {
                ReturnData().setData(true)
            } else {
                ReturnData().setErrorMsg("恢复备份失败，请检查备份文件是否完整有效")
            }
        }.getOrElse {
            AppLog.put("恢复备份异常", it)
            ReturnData().setErrorMsg("恢复备份异常: ${it.message ?: "未知错误"}")
        }
    }
}
