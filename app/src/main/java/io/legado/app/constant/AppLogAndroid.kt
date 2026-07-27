package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * AppLog 的安卓副作用实现(原 app 侧 AppLog 的 LogUtils/toast/DEBUG logcat/recordLog 面)。
 * shared 侧 AppLog 走 host 注入(非 expect/actual), 宿主启动早期 App.onCreate 调 registerAndroidAppLogHost。
 */
private val androidAppLogHost = object : AppLogHost {

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override val recordLog: Boolean get() = AppConfig.recordLog

    override fun write(message: String) {
        LogUtils.d("AppLog", message)
    }

    override fun toast(message: String) {
        appCtx.toastOnUi(message)
    }

    override fun debugPrint(message: String, throwable: Throwable?) {
        if (BuildConfig.DEBUG) {
            // 原实现取 stackTrace[3](put 的直接调用者)为 tag; 经 host 间接后按类名扫首个非 AppLog 帧, 语义不变
            val tag = Thread.currentThread().stackTrace
                .firstOrNull {
                    val n = it.className
                    !n.startsWith("io.legado.app.constant.AppLog") &&
                        !n.startsWith("java.lang.Thread") &&
                        !n.startsWith("dalvik.system.VMStack")
                }?.className ?: "AppLog"
            Log.e(tag, message, throwable)
        }
    }
}

/** 宿主启动早期注册一次(App.onCreate)。 */
fun registerAndroidAppLogHost() {
    AppLog.registerHost(androidAppLogHost)
}
