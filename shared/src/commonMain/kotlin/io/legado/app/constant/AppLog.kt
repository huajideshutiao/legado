package io.legado.app.constant

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 全域日志入口(下沉自 app,包名/对象名不变,消费方 import 零改动)。
 *
 * 纯面(内存环形列表 + put/putDebug/putNotSave/clear/logs)落 commonMain;
 * 平台/宿主副作用(文件落盘 LogUtils、UI toast、DEBUG logcat、recordLog 门、墙钟)因依赖
 * 都在 app 模块(shared 不可反向引用),走 [AppLogHost] provider 注入——同 [io.legado.app.help.i18n.AppStrings]
 * 先例: 平台差异只在「谁供 sink」,是依赖注入而非编译期 expect/actual 分叉。宿主启动早期注册一次。
 */
object AppLog {

    private val lock = SynchronizedObject()
    private val mLogs = ArrayList<Triple<Long, String, Throwable?>>()

    val logs get() = synchronized(lock) { mLogs.toList() }

    @Volatile
    private var host: AppLogHost? = null

    /** 宿主启动早期注册一次(任何 put 之前)。未注册时纯环形列表仍可用,副作用静默 no-op。 */
    fun registerHost(appLogHost: AppLogHost) {
        host = appLogHost
    }

    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        val h = host
        if (toast) h?.toast(message)
        val logMessage = if (throwable == null) {
            message
        } else {
            "$message\n${throwable.stackTraceToString()}"
        }
        h?.write(logMessage)
        synchronized(lock) {
            if (mLogs.size > 100) {
                mLogs.removeLastOrNull()
            }
            mLogs.add(0, Triple(h?.currentTimeMillis() ?: 0L, message, throwable))
        }
        h?.debugPrint(message, throwable)
    }

    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        val h = host
        if (toast) h?.toast(message)
        synchronized(lock) {
            if (mLogs.size > 100) {
                mLogs.removeLastOrNull()
            }
            mLogs.add(0, Triple(h?.currentTimeMillis() ?: 0L, message, throwable))
        }
        h?.debugPrint(message, throwable)
    }

    fun clear() {
        synchronized(lock) {
            mLogs.clear()
        }
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (host?.recordLog == true) {
            put(message, throwable)
        }
    }

    /**
     * 是否启用日志记录 (原 AppConfig.recordLog 门), 供下沉到 shared 的 DispatchersMonitor
     * 等场景查询, 避免直接引用 app 端 AppConfig。未注册 host 时返回 false。
     */
    val isRecordLogEnabled: Boolean get() = host?.recordLog == true

}

/**
 * AppLog 的平台/宿主副作用出口, 由宿主(安卓 App.onCreate)注册。
 * 各方法对应原 app 实现: [write]=LogUtils.d 落盘, [toast]=toastOnUi, [debugPrint]=DEBUG logcat,
 * [recordLog]=AppConfig.recordLog 门, [currentTimeMillis]=墙钟时间戳。
 */
interface AppLogHost {
    fun currentTimeMillis(): Long
    val recordLog: Boolean
    fun write(message: String)
    fun toast(message: String)
    fun debugPrint(message: String, throwable: Throwable?)
}
