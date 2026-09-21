package io.legado.app.ui.reader

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.legado.app.constant.AppLog

/**
 * [rememberReaderTextPlatformActions] 的 Android actual: 列出系统注册的 ACTION_PROCESS_TEXT
 * 应用 (翻译/流转/搜索等), 对照原版 TextActionMenu.onInitializeMenu。
 *
 * PackageManager 查询按 context 缓存一次。选中文本由调用方按点击时的选区传入,
 * 不读剪贴板。
 */
@Composable
internal actual fun rememberReaderTextPlatformActions(): List<ReaderTextPlatformAction> {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val pm = context.packageManager
            val base = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            pm.queryIntentActivities(base, 0).map { info ->
                val target = Intent(base)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                ReaderTextPlatformAction(info.loadLabel(pm).toString()) { text ->
                    runCatching {
                        context.startActivity(target.putExtra(Intent.EXTRA_PROCESS_TEXT, text))
                    }.onFailure { AppLog.put("执行文本菜单操作出错\n$it", it, true) }
                }
            }
        }.onFailure { AppLog.put("获取文字操作菜单出错\n$it", it) }.getOrDefault(emptyList())
    }
}
