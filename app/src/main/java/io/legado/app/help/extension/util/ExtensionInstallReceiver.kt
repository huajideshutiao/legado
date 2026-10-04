package io.legado.app.help.extension.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.legado.app.BuildConfig

/**
 * 扩展安装事件接收器。系统广播 (任何应用的装/更/卸) 与私有扩展广播
 * (BuildConfig.APPLICATION_ID 前缀 action, package 限定本应用) 都会到达,
 * 广播到达即整表重扫, 不做防抖; 重扫的串行化由 MangaExtensionManager 保证。
 *
 * 系统包广播是受保护广播, 仅系统可发; RECEIVER_EXPORTED 对齐仓库既有先例。
 */
internal class ExtensionInstallReceiver(
    private val appContext: Context,
    private val onExtensionsChanged: () -> Unit,
) : BroadcastReceiver() {

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
        when (intent?.action) {
            Intent.ACTION_PACKAGE_ADDED, ACTION_EXTENSION_ADDED -> {
                if (isReplacing(intent)) return
                if (!isRelevantPackage(context, intent)) return
                onExtensionsChanged()
            }

            Intent.ACTION_PACKAGE_REPLACED, ACTION_EXTENSION_REPLACED -> {
                if (!isRelevantPackage(context, intent)) return
                onExtensionsChanged()
            }

            Intent.ACTION_PACKAGE_REMOVED, ACTION_EXTENSION_REMOVED -> {
                if (isReplacing(intent)) return
                // 包已移除时无法回查 feature 标记, 统一交给整表重扫
                onExtensionsChanged()
            }
        }
    }

    private fun isRelevantPackage(context: Context, intent: Intent): Boolean {
        when (intent.action) {
            ACTION_EXTENSION_ADDED, ACTION_EXTENSION_REPLACED, ACTION_EXTENSION_REMOVED -> return true
        }
        val pkgName = getPackageNameFromIntent(intent) ?: return false
        return ExtensionLoader.isExtensionPackage(context, pkgName)
    }

    private fun isReplacing(intent: Intent): Boolean {
        return intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
    }

    private fun getPackageNameFromIntent(intent: Intent): String? {
        return intent.data?.encodedSchemeSpecificPart
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
