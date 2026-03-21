package io.legado.app.ui.book.read

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import androidx.core.net.toUri

class TextActionMenu(private val context: Context, private val callBack: CallBack) {

    private var actionMode: ActionMode? = null
    private val contentRect = Rect()
    private var dismissByApp = false

    private val actionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.content_select_action, menu)
            onInitializeMenu(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (!callBack.onMenuItemSelected(item.itemId)) {
                onMenuItemClick(item)
            }
            callBack.onMenuActionFinally()
            dismissByApp = true
            mode.finish()
            dismissByApp = false
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            if (!dismissByApp) {
                callBack.onMenuActionFinally()
            }
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            outRect.set(contentRect)
        }
    }

    fun show(
        view: View,
        startX: Int,
        startTopY: Int,
        endX: Int,
        endBottomY: Int
    ) {
        contentRect.set(
            minOf(startX, endX),
            startTopY,
            maxOf(startX, endX),
            endBottomY
        )

        if (actionMode == null) {
            actionMode =
                view.startActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidateContentRect()
        }
    }

    fun dismiss() {
        dismissByApp = true
        actionMode?.finish()
        actionMode = null
        dismissByApp = false
    }

    private fun onMenuItemClick(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_copy -> context.sendToClip(callBack.selectedText)
            R.id.menu_share_str -> context.share(callBack.selectedText)
            R.id.menu_browser -> {
                kotlin.runCatching {
                    val intent = if (callBack.selectedText.isAbsUrl()) {
                        Intent(Intent.ACTION_VIEW).apply {
                            data = callBack.selectedText.toUri()
                        }
                    } else {
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, callBack.selectedText)
                        }
                    }
                    context.startActivity(intent)
                }.onFailure {
                    it.printOnDebug()
                    context.toastOnUi(it.localizedMessage ?: "ERROR")
                }
            }
            else -> item.intent?.let {
                kotlin.runCatching {
                    it.putExtra(Intent.EXTRA_PROCESS_TEXT, callBack.selectedText)
                    it.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                    context.startActivity(it)
                }.onFailure { e ->
                    AppLog.put("执行文本菜单操作出错\n$e", e, true)
                }
            }
        }
    }

    private fun createProcessTextIntent(): Intent {
        return Intent()
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
    }

    private fun getSupportedActivities(): List<ResolveInfo> {
        return context.packageManager
            .queryIntentActivities(createProcessTextIntent(), 0)
    }

    private fun createProcessTextIntentForResolveInfo(info: ResolveInfo): Intent {
        return createProcessTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

    private fun onInitializeMenu(menu: Menu) {
        kotlin.runCatching {
            var menuItemOrder = 100
            for (resolveInfo in getSupportedActivities()) {
                val item = menu.add(
                    Menu.NONE, Menu.NONE,
                    menuItemOrder++, resolveInfo.loadLabel(context.packageManager)
                )
                item.intent = createProcessTextIntentForResolveInfo(resolveInfo)
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                kotlin.runCatching {
                    item.icon = resolveInfo.loadIcon(context.packageManager)
                }
            }
        }.onFailure {
            context.toastOnUi("获取文字操作菜单出错:${it.localizedMessage}")
        }
    }

    interface CallBack {
        val selectedText: String

        fun onMenuItemSelected(itemId: Int): Boolean

        fun onMenuActionFinally()
    }
}