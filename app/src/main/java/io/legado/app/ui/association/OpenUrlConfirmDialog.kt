package io.legado.app.ui.association

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.SourceType
import io.legado.app.databinding.DialogOpenUrlConfirmBinding
import io.legado.app.help.source.SourceHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.utils.applyTint
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 跳转确认对话框
 * 重构为使用 alert DSL 实现，菜单保持在右上角
 */
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

        val binding = DialogOpenUrlConfirmBinding.inflate(activity.layoutInflater)

        // 配置 Toolbar 以保持右上角菜单
        binding.toolBar.title = "跳转确认"
        binding.toolBar.subtitle = sourceName
        binding.toolBar.inflateMenu(R.menu.open_url_confirm)
        binding.toolBar.menu.applyTint(activity)

        binding.message.text = "${sourceName} 正在请求跳转链接/应用，是否跳转？"

        val dialog = activity.alert {
            customView { binding.root }
        }

        // 按钮点击事件
        binding.btnNegative.setOnClickListener { dialog.dismiss() }
        binding.btnPositive.setOnClickListener {
            openUrl(uri, mimeType)
            dialog.dismiss()
        }

        // 菜单点击事件
        binding.toolBar.setOnMenuItemClickListener { item ->
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
