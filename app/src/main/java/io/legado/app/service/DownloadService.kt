package io.legado.app.service

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.utils.IntentType
import io.legado.app.utils.openFileUri
import io.legado.app.utils.toastOnUi
import splitties.systemservices.downloadManager

/**
 * 下载文件，通知由系统 DownloadManager 维护，应用关闭后仍可点击打开
 */
class DownloadService : BaseService() {

    private val downloads = hashMapOf<Long, String>()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            queryComplete()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
            request.setMimeType(IntentType.from(fileName))
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
        val ids = downloads.keys.toLongArray()
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
                        openFileUri(uri, IntentType.from(fileName))
                    }
                }
            }
        }
        if (downloads.isEmpty()) stopSelf()
    }

    override fun startForegroundNotification() {
        // 不接入 LiveUpdate: 实际下载进度由系统 DownloadManager 自己的通知维护 (见 startDownload),
        // 那条通知不归本应用所有, 无法挂 ProgressStyle; 这里仅是 FGS 占位通知, 无进度可展示。
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setOngoing(true)
            .build()
        startForeground(NotificationId.DownloadService, notification)
    }

}