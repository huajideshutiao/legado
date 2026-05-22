package io.legado.app.ui.config

import android.app.Application
import android.content.Context
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.utils.FileUtils
import io.legado.app.utils.restart
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx

class ConfigViewModel(application: Application) : BaseViewModel(application) {

    val backupRestoreState = MutableLiveData<String?>()
    private var backupRestoreJob: io.legado.app.help.coroutine.Coroutine<*>? = null

    fun cancelBackupRestore() {
        backupRestoreJob?.cancel()
        backupRestoreState.value = null
    }

    fun upWebDavConfig() {
        execute {
            AppWebDav.upConfig()
        }
    }

    fun clearCache() {
        execute {
            BookHelp.clearCache()
            FileUtils.delete(context.cacheDir.absolutePath)
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

    fun clearWebViewData() {
        execute {
            FileUtils.delete(context.getDir("webview", Context.MODE_PRIVATE))
            FileUtils.delete(context.getDir("hws_webview", Context.MODE_PRIVATE), true)
            context.toastOnUi(R.string.clear_webview_data_success)
            delay(3000)
            appCtx.restart()
        }
    }

    fun shrinkDatabase() {
        execute {
            appDb.bookChapterDao.deleteNotShelfBookChapters()
            appDb.bookDao.deleteNotShelfBook()
            appDb.openHelper.writableDatabase.execSQL("VACUUM")
        }.onSuccess {
            context.toastOnUi(R.string.success)
        }
    }

    fun backup(backupPath: String) {
        backupRestoreState.value = "备份中…"
        backupRestoreJob = execute {
            Backup.backupLocked(appCtx, backupPath)
        }.onSuccess {
            context.toastOnUi(R.string.backup_success)
        }.onError {
            AppLog.put("备份出错\n${it.localizedMessage}", it)
            context.toastOnUi(context.getString(R.string.backup_fail, it.localizedMessage))
        }.onFinally {
            backupRestoreState.value = null
        }
    }

    fun restore(uri: android.net.Uri) {
        backupRestoreState.value = "恢复中…"
        backupRestoreJob = execute {
            Restore.restore(appCtx, uri)
        }.onFinally {
            backupRestoreState.value = null
        }
    }

    fun restoreWebDav(name: String) {
        backupRestoreState.value = "恢复中…"
        backupRestoreJob = execute {
            AppWebDav.restoreWebDav(name)
        }.onError {
            AppLog.put("WebDav恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi("WebDav恢复出错\n${it.localizedMessage}")
        }.onFinally {
            backupRestoreState.value = null
        }
    }

    fun loadBackupNames(onSuccess: (List<String>) -> Unit) {
        backupRestoreState.value = context.getString(R.string.loading)
        backupRestoreJob = execute {
            AppWebDav.getBackupNames()
        }.onSuccess { names ->
            backupRestoreState.value = null
            if (AppWebDav.isJianGuoYun && names.size > 700) {
                context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
            }
            onSuccess(names)
        }.onError {
            backupRestoreState.value = null
            AppLog.put("获取WebDav备份列表出错\n${it.localizedMessage}", it)
            context.toastOnUi("WebDavError\n${it.localizedMessage}")
            onSuccess(emptyList())
        }
    }

}
