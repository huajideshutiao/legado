package io.legado.app.help

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Looper
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import io.legado.app.App
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.appInfo
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.crash.CrashLogWriter
import io.legado.app.help.crash.CrashMarkerProviders
import io.legado.app.model.ReadAloud
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.externalCache
import io.legado.app.utils.getFile
import io.legado.app.utils.longToastOnUiLegacy
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.writeText
import java.io.File

/**
 * 异常管理类
 */
class CrashHandler(val context: Context) : Thread.UncaughtExceptionHandler {

    /**
     * 系统默认UncaughtExceptionHandler
     */
    private var mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        //设置该CrashHandler为系统默认的
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /**
     * uncaughtException 回调函数
     */
    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (shouldAbsorb(ex)) {
            AppLog.put("发生未捕获的异常\n${ex.localizedMessage}", ex)
            Looper.loop()
        } else {
            ReadAloud.stop(context)
            handleException(ex)
            mDefaultHandler?.uncaughtException(thread, ex)
        }
    }

    private fun shouldAbsorb(e: Throwable): Boolean {
        return when {
            e::class.simpleName == "CannotDeliverBroadcastException" -> true
            e is SecurityException && e.message?.contains(
                "nor current process has android.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS",
                true
            ) == true -> true

            else -> false
        }
    }

    /**
     * 处理该异常
     */
    private fun handleException(ex: Throwable?) {
        if (ex == null) return
        // 异常退出标记: 下次启动提示用户查看崩溃日志 (读取方 = shared notifyAppCrash)
        CrashMarkerProviders.get().mark()
        //保存日志文件
        saveCrashInfo2File(ex)
        if ((ex is OutOfMemoryError || ex.cause is OutOfMemoryError) && AppConfig.recordHeapDump) {
            doHeapDump()
        }
        context.longToastOnUiLegacy(ex.stackTraceStr)
        Thread.sleep(3000)
    }

    companion object {
        /**
         * 存储异常和参数信息
         */
        private val paramsMap by lazy {
            val map = LinkedHashMap<String, String>()
            kotlin.runCatching {
                //获取系统信息
                map["MANUFACTURER"] = Build.MANUFACTURER
                map["BRAND"] = Build.BRAND
                map["MODEL"] = Build.MODEL
                map["SDK_INT"] = Build.VERSION.SDK_INT.toString()
                map["RELEASE"] = Build.VERSION.RELEASE
                map["WebViewPackage"] = try {
                    WebViewCompat.getCurrentWebViewPackage(App.instance)?.let {
                        "${it.packageName} ${it.versionName}"
                    } ?: "null"
                } catch (e: Throwable) {
                    e.toString()
                }
                map["packageName"] = App.instance.packageName
                map["heapSize"] = Runtime.getRuntime().maxMemory().toString()
                //获取app版本信息
                AppConst.appInfo.let {
                    map["versionName"] = it.versionName
                    map["versionCode"] = it.versionCode.toString()
                }
            }
            map
        }

        /**
         * 保存错误信息到文件中: 取一次快照, 配置的备份路径 (SAF) 与外部缓存各落一份。
         *
         * 参数头组装/cause 链拼接/文件名/7 天清理与桌面端同链路, 都在 [CrashLogWriter];
         * 两处落盘传同一份快照, 文件名一致 (列表侧按名去重与回读)。
         */
        fun saveCrashInfo2File(ex: Throwable) {
            val snapshot = CrashLogWriter.snapshot(ex, paramsMap)
            try {
                val backupPath = AppConfig.backupPath
                    ?: throw NoStackTraceException("备份路径未配置")
                val uri = backupPath.toUri()
                val fileDoc = FileDoc.fromUri(uri, true)
                fileDoc.createFileIfNotExist(snapshot.fileName, "crash")
                    .writeText(snapshot.text)
            } catch (_: Exception) {
            }
            kotlin.runCatching {
                App.instance.externalCacheDir?.let { rootFile ->
                    CrashLogWriter.save(snapshot, listOf(File(rootFile, "crash")))
                }
            }
        }

        /**
         * 进行堆转储
         */
        fun doHeapDump(manually: Boolean = false) {
            val heapDir = App.instance
                .externalCache
                .getFile("heapDump")
            heapDir.createFolderReplace()
            val fileName = if (manually) {
                "heap-dump-manually-${System.currentTimeMillis()}.hprof"
            } else {
                "heap-dump-${System.currentTimeMillis()}.hprof"
            }
            val heapFile = heapDir.getFile(fileName)
            val heapDumpName = heapFile.absolutePath
            Debug.dumpHprofData(heapDumpName)
        }

    }

}
