package io.legado.app.constant

import android.util.Log
import io.legado.app.App
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi

/**
 * AppLog 的安卓副作用实现(原 app 侧 AppLog 的 LogUtils/toast/DEBUG logcat/recordLog 面)。
 * shared 侧 AppLog 走 host 注入(非 expect/actual), 宿主启动早期 App.onCreate 调 registerAndroidAppLogHost。
 */
private val androidAppLogHost = object : AppLogHost {

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun timeZoneOffsetMillis(): Long =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

    override val recordLog: Boolean get() = AppConfig.recordLog

    override fun write(tag: String, message: String) {
        LogUtils.d(tag, message)
    }

    override fun toast(message: String) {
        App.instance.toastOnUi(message)
    }

    override fun debugPrint(tag: String, message: String, throwable: Throwable?) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, message, throwable)
        }
    }
}

/** 宿主启动早期注册一次(App.onCreate)。 */
fun registerAndroidAppLogHost() {
    AppLog.registerHost(androidAppLogHost)
}
