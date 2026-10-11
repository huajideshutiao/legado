package io.legado.app.help.config

import android.content.Context
import io.legado.app.help.crash.CrashMarkerProvider
import io.legado.app.help.crash.CrashMarkerProviders
import io.legado.app.help.crash.CrashMarkerFile
import io.legado.app.utils.getFile

/**
 * Android 端 [CrashMarkerProvider] 实现。
 *
 * # 为什么不能只用偏好存储
 * Java 异常路径 (app 端 [io.legado.app.help.CrashHandler]) 可以写 SharedPreferences,
 * 但**原生信号路径不能** (信号处理器内不允许分配/加锁), 那条路只能落标记文件
 * (见 `native_crash_handler.cpp` 的 `pending-crash`)。故读取侧必须同时看两处。
 *
 * # 与偏好存储的分工
 * 置位: 两条路径都写 (Java 走偏好, 原生走文件)。
 * 读取并清除: 两处都查、都清 —— 同一次崩溃只走其中一条, 但两次崩溃可能一条写文件一条写偏好。
 */
private class AndroidCrashMarkerProvider(private val context: Context) : CrashMarkerProvider {

    override fun mark() {
        LocalConfigProviders.get().putBoolean(LocalConfigKeys.appCrash, true)
    }

    override fun consumePending(): Boolean {
        val fromFile = CrashMarkerFile.consume(crashDir())
        val store = LocalConfigProviders.get()
        val fromPrefs = store.getBoolean(LocalConfigKeys.appCrash, false)
        if (fromPrefs) store.putBoolean(LocalConfigKeys.appCrash, false)
        return fromFile || fromPrefs
    }

    /** 与 [io.legado.app.help.CrashHandler.saveCrashInfo2File] 的外部缓存那份同处。 */
    private fun crashDir(): String? =
        context.externalCacheDir?.getFile("crash")?.absolutePath
}

/** 安卓宿主启动早期注册 [CrashMarkerProvider] (须在启动期崩溃提示读取之前)。 */
fun registerAndroidCrashMarkerProvider(context: Context) {
    CrashMarkerProviders.register(AndroidCrashMarkerProvider(context))
}
