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
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.SourceType
import io.legado.app.help.source.SourceHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.noButton
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
        val spacingLg = activity.resources.getDimensionPixelSize(R.dimen.arco_spacing_lg)

        // Toolbar
        val toolbar = Toolbar(activity).apply {
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

        // Buttons
        val btnNegative = TextView(activity).apply {
            text = activity.getString(R.string.cancel)
            layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            setPadding(spacingLg, spacingLg, spacingLg, spacingLg)
        }

        val btnPositive = TextView(activity).apply {
            text = activity.getString(R.string.ok)
            layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            setPadding(spacingLg, spacingLg, spacingLg, spacingLg)
        }

        val buttonBar = FlexboxLayout(activity).apply {
            flexDirection = FlexDirection.ROW
            justifyContent = JustifyContent.FLEX_END
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(btnNegative)
            addView(btnPositive)
        }

        // Root
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(messageText)
            addView(buttonBar)
        }

        val dialog = activity.alert {
            customView { root }
        }

        btnNegative.setOnClickListener { dialog.dismiss() }
        btnPositive.setOnClickListener {
            openUrl(uri, mimeType)
            dialog.dismiss()
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
