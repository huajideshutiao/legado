package io.legado.app.ui.about

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.list
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.isActive

/**
 * 崩溃日志列表 DialogFragment 壳。
 * UI 下沉至 shared/sharedUiMain 的 [CrashLogsDialogContent]；本类仅保留 ViewModel
 * (读崩溃目录/备份路径) 与平台副作用 (分享 Intent、读文件)，经回调注入共享 Composable。
 * 行=文件名，点击查看内容 TextDialog，长按分享；菜单=清空。ViewModel 零改动。
 */
class CrashLogsDialog : BaseComposeDialogFragment() {


    private val viewModel by viewModels<CrashViewModel>()

    @Composable
    override fun Content() {
        var logs by remember { mutableStateOf<List<FileDoc>>(emptyList()) }
        // 无 runtime-livedata 构件，手动 observe（ViewModel 零改动）
        DisposableEffect(Unit) {
            val observer = androidx.lifecycle.Observer<List<FileDoc>> { logs = it }
            viewModel.logLiveData.observeForever(observer)
            onDispose { viewModel.logLiveData.removeObserver(observer) }
        }
        LaunchedEffect(Unit) { viewModel.initData() }
        // FileDoc → CrashLogItem 映射 + name 反查 (回调注入平台读取/分享逻辑)
        val docByName = remember(logs) { logs.associateBy { it.name } }
        CrashLogsDialogContent(
            logs = logs.map { CrashLogItem(it.name) },
            onDismiss = { dismissAllowingStateLoss() },
            onClear = { viewModel.clearCrashLog() },
            onReadFile = { item, cb ->
                docByName[item.name]?.let { showLogFile(it, cb) }
            },
            onShare = { item ->
                docByName[item.name]?.let { shareFile(it) }
            },
        )
    }

    private fun showLogFile(fileDoc: FileDoc, callback: (String) -> Unit) {
        viewModel.readFile(fileDoc) {
            if (lifecycleScope.isActive) {
                callback(it)
            }
        }
    }

    private fun shareFile(fileDoc: FileDoc) {
        fileDoc.asFile()?.let {
            requireContext().share(it, title = getString(R.string.share))
        } ?: requireContext().share(
            fileDoc.uri, title = getString(R.string.share)
        )
    }

    class CrashViewModel(application: Application) : BaseViewModel(application) {

        val logLiveData = MutableLiveData<List<FileDoc>>()

        fun initData() {
            execute {
                val list = arrayListOf<FileDoc>()
                context.externalCacheDir?.getFile("crash")?.listFiles { it.isFile }?.forEach {
                        list.add(FileDoc.fromFile(it))
                    }
                val backupPath = AppConfig.backupPath
                if (!backupPath.isNullOrEmpty()) {
                    val uri = backupPath.toUri()
                    FileDoc.fromUri(uri, true).find("crash")?.list {
                            !it.isDir
                        }?.let {
                            list.addAll(it)
                        }
                }
                return@execute list.sortedByDescending { it.name }.distinctBy { it.name }
            }.onSuccess {
                logLiveData.postValue(it)
            }
        }

        fun readFile(fileDoc: FileDoc, success: (String) -> Unit) {
            execute {
                String(fileDoc.readBytes())
            }.onSuccess {
                success.invoke(it)
            }.onError {
                context.toastOnUi(it.localizedMessage)
            }
        }

        fun clearCrashLog() {
            execute {
                context.externalCacheDir?.getFile("crash")?.let {
                        FileUtils.delete(it, false)
                    }
                val backupPath = AppConfig.backupPath
                if (!backupPath.isNullOrEmpty()) {
                    val uri = backupPath.toUri()
                    FileDoc.fromUri(uri, true).find("crash")?.delete()
                }
            }.onError {
                context.toastOnUi(it.localizedMessage)
            }.onFinally {
                initData()
            }
        }

    }

}
