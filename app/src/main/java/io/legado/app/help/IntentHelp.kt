package io.legado.app.help

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.App
import io.legado.app.help.i18n.androidAppString
import io.legado.app.utils.toastOnUi

@Suppress("unused")
object IntentHelp {

    fun getBrowserIntent(url: String): Intent {
        return getBrowserIntent(url.toUri())
    }

    fun getBrowserIntent(uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = uri
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(App.instance.packageManager) == null) {
            return Intent.createChooser(intent, "请选择浏览器")
        }
        return intent
    }

    fun openTTSSetting() {
        //跳转到文字转语音设置界面
        kotlin.runCatching {
            val intent = Intent()
            intent.action = "com.android.settings.TTS_SETTINGS"
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            App.instance.startActivity(intent)
        }.onFailure {
            App.instance.toastOnUi(androidAppString("tip_cannot_jump_setting_page"))
        }
    }

    /**
     * 跳转系统“安装未知来源应用”设置页。
     *
     * 原版既有本方法, 且原版自身无消费方 (仅本处定义); 保留以对齐原版公开 API。
     */
    fun toInstallUnknown(context: Context) {
        kotlin.runCatching {
            val intent = Intent()
            intent.action = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }.onFailure {
            context.toastOnUi("无法打开设置")
        }
    }

}