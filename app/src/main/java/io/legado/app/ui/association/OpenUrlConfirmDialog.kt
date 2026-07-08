package io.legado.app.ui.association

import android.content.Intent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.SourceType
import io.legado.app.help.source.SourceHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.negativeButton
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.positiveButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.utils.applyTint
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object OpenUrlConfirmDialog {

    fun display(
        uri: String,
        mimeType: String?,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int = SourceType.book
    ) {
        val activity = io.legado.app.help.LifecycleHelp.currentActivity as? AppCompatActivity
        if (activity == null) {
            appCtx.toastOnUi("无法在后台显示跳转确认对话框")
            return
        }

        val padding = activity.resources.getDimensionPixelSize(R.dimen.arco_spacing_lg)

        // 应用 Style.DialogToolbar(elevation=0, titleTextAppearance, popupTheme), 与其他对话框 Toolbar 一致
        val toolbar =
            Toolbar(android.view.ContextThemeWrapper(activity, R.style.Style_DialogToolbar)).apply {
            setTitle("跳转确认")
            subtitle = sourceName
            inflateMenu(R.menu.open_url_confirm)
            menu.applyTint(activity)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // Message
        val messageText = TextView(activity).apply {
            text = "${sourceName} 正在请求跳转链接/应用，是否跳转？"
            setTextColor(activity.getColor(R.color.primaryText))
            textSize = 16f
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(padding, padding, padding, padding)
        }

        // customView 仅承载 Toolbar(带菜单) + 提示文案, 操作按钮交给 alert DSL 标准底栏(水平排布)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(messageText)
        }

        val dialog = activity.alert {
            customView { root }
            negativeButton(R.string.cancel)
            // positiveButton 点击后 AlertDialog 默认 dismiss, openUrl 执行完即关闭
            positiveButton(R.string.ok) {
                openUrl(uri, mimeType)
            }
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_disable_source -> {
                    sourceOrigin?.let { SourceHelp.enableSource(it, sourceType, false) }
                    dialog.dismiss()
                }

                R.id.menu_delete_source -> {
                    activity.alert(R.string.draw) {
                        setMessage(activity.getString(R.string.sure_del) + "\n" + sourceName)
                        noButton()
                        yesButton {
                            sourceOrigin?.let { SourceHelp.deleteSource(it, sourceType) }
                            dialog.dismiss()
                        }
                    }
                }
            }
            true
        }
    }

    private fun openUrl(uriString: String, mimeType: String?) {
        try {
            val uri = uriString.toUri()
            val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                if (!mimeType.isNullOrBlank()) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (targetIntent.resolveActivity(appCtx.packageManager) != null) {
                appCtx.startActivity(targetIntent)
            } else {
                appCtx.toastOnUi(R.string.can_not_open)
            }
        } catch (e: Exception) {
            AppLog.put("打开链接失败", e, true)
        }
    }
}
