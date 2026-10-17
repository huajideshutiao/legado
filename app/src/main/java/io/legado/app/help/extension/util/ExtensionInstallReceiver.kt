package io.legado.app.help.extension.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.MangaExtensionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 扩展安装事件接收器。系统广播 (任何应用的装/更/卸) 与私有扩展广播
 * (BuildConfig.APPLICATION_ID 前缀 action, package 限定本应用) 都会到达。
 *
 * 架构设计:
 * 1. onReceive 仅在主线程提取意图与包名并入队 Channel, 零 IPC、零阻塞;
 * 2. 由后台协程 (IoDispatcher) 异步串行消费, 避免多事件交错与主线程卡顿;
 * 3. 消费端包裹异常隔离屏障, 外部三方应用元数据异常不危及宿主进程;
 * 4. 卸载广播根据内存表前置过滤, 非扩展应用卸载零 I/O 丢弃。
 *
 * 系统包广播是受保护广播, 仅系统可发; RECEIVER_EXPORTED 对齐仓库既有先例。
 */
internal class ExtensionInstallReceiver(
    private val appContext: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + IoDispatcher),
    private val onExtensionsChanged: () -> Unit,
) : BroadcastReceiver() {

    private sealed interface Event {
        data class Added(val pkgName: String, val isPrivateAction: Boolean) : Event
        data class Replaced(val pkgName: String, val isPrivateAction: Boolean) : Event
        data class Removed(val pkgName: String, val isPrivateAction: Boolean) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    handleEvent(event)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    AppLog.put("扩展事件处理失败: $event", e)
                }
            }
        }
    }

    private val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(ACTION_EXTENSION_ADDED)
        addAction(ACTION_EXTENSION_REPLACED)
        addAction(ACTION_EXTENSION_REMOVED)
        addDataScheme("package")
    }

    fun register() {
        ContextCompat.registerReceiver(appContext, this, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pkgName = getPackageNameFromIntent(intent) ?: return

        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (isReplacing(intent)) return
                events.trySend(Event.Added(pkgName, isPrivateAction = false))
            }

            ACTION_EXTENSION_ADDED -> {
                events.trySend(Event.Added(pkgName, isPrivateAction = true))
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                events.trySend(Event.Replaced(pkgName, isPrivateAction = false))
            }

            ACTION_EXTENSION_REPLACED -> {
                events.trySend(Event.Replaced(pkgName, isPrivateAction = true))
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                if (isReplacing(intent)) return
                events.trySend(Event.Removed(pkgName, isPrivateAction = false))
            }

            ACTION_EXTENSION_REMOVED -> {
                events.trySend(Event.Removed(pkgName, isPrivateAction = true))
            }
        }
    }

    private fun handleEvent(event: Event) {
        when (event) {
            is Event.Added -> {
                if (event.isPrivateAction || ExtensionLoader.isExtensionPackage(appContext, event.pkgName)) {
                    onExtensionsChanged()
                }
            }

            is Event.Replaced -> {
                if (event.isPrivateAction || ExtensionLoader.isExtensionPackage(appContext, event.pkgName)) {
                    onExtensionsChanged()
                }
            }

            is Event.Removed -> {
                if (event.isPrivateAction || MangaExtensionManager.hasExtension(event.pkgName)) {
                    onExtensionsChanged()
                }
            }
        }
    }

    private fun isReplacing(intent: Intent): Boolean {
        return intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
    }

    private fun getPackageNameFromIntent(intent: Intent): String? {
        return intent.data?.encodedSchemeSpecificPart?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val ACTION_EXTENSION_ADDED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_ADDED"
        private const val ACTION_EXTENSION_REPLACED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_REPLACED"
        private const val ACTION_EXTENSION_REMOVED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_REMOVED"

        fun notifyAdded(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_ADDED)
        }

        fun notifyReplaced(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REPLACED)
        }

        fun notifyRemoved(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REMOVED)
        }

        private fun notify(context: Context, pkgName: String, action: String) {
            val intent = Intent(action).apply {
                data = "package:$pkgName".toUri()
                `package` = context.packageName
            }
            context.sendBroadcast(intent)
        }
    }
}
