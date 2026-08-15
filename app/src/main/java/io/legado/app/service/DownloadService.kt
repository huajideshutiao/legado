package io.legado.app.service

import android.app.DownloadManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.downloadManager
import io.legado.app.utils.FileUtils
import io.legado.app.utils.openFileUri
import io.legado.app.utils.toastOnUi

/**
 * 下载文件，监听下载完成后自动打开
 * 不显示前台通知，依赖系统 DownloadManager 显示下载进度
 */
class DownloadService : Service() {

    private val downloads = hashMapOf<Long, String>()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            queryComplete()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        kotlin.runCatching { unregisterReceiver(downloadReceiver) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            IntentAction.start -> startDownload(
                intent.getStringExtra("url"),
                intent.getStringExtra("fileName")
            )
        }
        return result
    }

    /**
     * 提交下载任务到系统 DownloadManager，通知由系统管理
     */
    @Synchronized
    private fun startDownload(url: String?, fileName: String?) {
        if (url == null || fileName == null) {
            if (downloads.isEmpty()) stopSelf()
            return
        }
        kotlin.runCatching {
            val request = DownloadManager.Request(url.toUri())
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setTitle(fileName)
            request.setMimeType(FileUtils.getMimeType(fileName))
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, fileName
            )
            val downloadId = downloadManager.enqueue(request)
            downloads[downloadId] = fileName
        }.onFailure {
            it.printStackTrace()
            val msg = when (it) {
                is SecurityException -> "下载出错,没有存储权限"
                else -> "下载出错,${it.localizedMessage}"
            }
            toastOnUi(msg)
            AppLog.put(msg, it)
        }
    }

    /**
     * 查询已完成的下载，自动打开文件
     */
    @Synchronized
    private fun queryComplete() {
        if (downloads.isEmpty()) return
        val ids = LongArray(downloads.size) { index -> downloads.keys.elementAt(index) }
        val query = DownloadManager.Query().setFilterById(*ids)
        downloadManager.query(query).use { cursor ->
            val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (idIndex < 0 || statusIndex < 0) return
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val status = cursor.getInt(statusIndex)
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val fileName = downloads.remove(id)
                    downloadManager.getUriForDownloadedFile(id)?.let { uri ->
                        openFileUri(uri, FileUtils.getMimeType(fileName ?: ""))
                    }
                }
            }
        }
        if (downloads.isEmpty()) stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? = null

}