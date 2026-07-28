package io.legado.app.constant

import io.legado.app.constant.AppLog.lock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

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
    private val _logsFlow = MutableStateFlow<List<Triple<Long, String, Throwable?>>>(emptyList())

    val logs get() = synchronized(lock) { mLogs.toList() }

    /** 日志快照流；每次新增或清空日志时立即发布，供 UI 响应式订阅。 */
    val logsFlow: StateFlow<List<Triple<Long, String, Throwable?>>> = _logsFlow.asStateFlow()

    @Volatile
    private var host: AppLogHost? = null

    /** 宿主启动早期注册一次(任何 put 之前)。未注册时纯环形列表仍可用,副作用静默 no-op。 */
    fun registerHost(appLogHost: AppLogHost) {
        host = appLogHost
    }

    /** 调用方必须持有 [lock]。 */
    private fun publishLogsLocked() {
        _logsFlow.value = mLogs.toList()
    }

    /**
     * 记录日志并落盘。
     *
     * 对齐 origin AppLog: 所有副作用 (toast/write/ring/debugPrint) 在同一把锁内,
     * 保证落盘与环形列表顺序一致 (原 @Synchronized 语义)。
     *
     * @param tag 组件标签, 默认 "AppLog", 供迁移前 LogUtils.d(TAG,...) 调用方保留原 TAG。
     */
    fun put(
        message: String?,
        throwable: Throwable? = null,
        toast: Boolean = false,
        tag: String = "AppLog"
    ) {
        message ?: return
        val h = host
        synchronized(lock) {
            val now = h?.currentTimeMillis() ?: 0L
            if (toast) h?.toast(message)
            val logMessage = if (throwable == null) {
                message
            } else {
                "$message\n${throwable.stackTraceToString()}"
            }
            h?.write(tag, logMessage)
            if (mLogs.size > 100) {
                mLogs.removeLastOrNull()
            }
            mLogs.add(0, Triple(now, message, throwable))
            h?.debugPrint(tag, message, throwable)
            publishLogsLocked()
        }
    }

    /**
     * 记录日志但不落盘 (仅内存环形列表 + DEBUG logcat)。
     *
     * @param tag 组件标签, 默认 "AppLog"。
     */
    fun putNotSave(
        message: String?,
        throwable: Throwable? = null,
        toast: Boolean = false,
        tag: String = "AppLog"
    ) {
        message ?: return
        val h = host
        synchronized(lock) {
            val now = h?.currentTimeMillis() ?: 0L
            if (toast) h?.toast(message)
            if (mLogs.size > 100) {
                mLogs.removeLastOrNull()
            }
            mLogs.add(0, Triple(now, message, throwable))
            h?.debugPrint(tag, message, throwable)
            publishLogsLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            mLogs.clear()
            publishLogsLocked()
        }
    }

    /**
     * 受 recordLog 门控的调试日志。
     *
     * @param tag 组件标签, 默认 "AppLog"。
     */
    fun putDebug(
        message: String?,
        throwable: Throwable? = null,
        tag: String = "AppLog"
    ) {
        if (host?.recordLog == true) {
            put(message, throwable, tag = tag)
        }
    }

    /**
     * 是否启用日志记录 (原 AppConfig.recordLog 门), 供下沉到 shared 的 DispatchersMonitor
     * 等场景查询, 避免直接引用 app 端 AppConfig。未注册 host 时返回 false。
     */
    val isRecordLogEnabled: Boolean get() = host?.recordLog == true

    /**
     * 当前时区相对 UTC 的偏移量 (毫秒), 供 UI 层 [AppLogDialog] 将 UTC 时间戳
     * 转为本地时间显示。未注册 host 时返回 0 (显示 UTC)。
     *
     * 示例: 中国标准时间 (UTC+8) 返回 +28800000。
     */
    fun timeZoneOffsetMillis(): Long = host?.timeZoneOffsetMillis() ?: 0L

}

/**
 * AppLog 的平台/宿主副作用出口, 由宿主(安卓 App.onCreate)注册。
 * 各方法对应原 app 实现: [write]=LogUtils.d 落盘, [toast]=toastOnUi, [debugPrint]=DEBUG logcat,
 * [recordLog]=AppConfig.recordLog 门, [currentTimeMillis]=墙钟时间戳。
 *
 * [tag] 参数来自 [AppLog.put] 的 tag, 允许组件保留原始 TAG 用于日志过滤,
 * 对齐迁移前直接 [LogUtils.d](TAG,...) 调用方的行为。
 */
interface AppLogHost {
    /** 当前 UTC epoch 毫秒 (墙钟)。 */
    fun currentTimeMillis(): Long

    /** 当前时区相对 UTC 的偏移量 (毫秒)。 */
    fun timeZoneOffsetMillis(): Long
    val recordLog: Boolean
    fun write(tag: String, message: String)
    fun toast(message: String)
    fun debugPrint(tag: String, message: String, throwable: Throwable?)
}
