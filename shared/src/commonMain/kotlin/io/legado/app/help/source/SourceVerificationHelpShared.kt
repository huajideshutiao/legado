package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import kotlin.time.Duration.Companion.minutes

/**
 * 源验证核心流程 (shared commonMain 下沉版)。
 *
 * 原 app 端 `SourceVerificationHelp` 中纯逻辑部分 (缓存 key 生成 / setResult / getResult /
 * clearResult / 内存轮询等待 / 注册并唤醒等待线程) 下沉至此, 供 desktop/iOS/鸿蒙复用。
 *
 * 依赖范围 (均已 commonMain 可用):
 * - [CacheManager] / [IntentData] / [AppLog] / [NoStackTraceException] 均在 shared commonMain。
 *
 * 平台相关 (JVM `LockSupport.parkNanos` / `unpark` / `Thread`) 经 [currentThreadMarker] /
 * [parkThread] / [unparkThread] expect/actual 桥接 (见文件底部 expect 声明,
 * jvmAndAndroidMain actual 委托 `LockSupport` 保持原行为; ios/ohos actual 用 `platformSleep`
 * 占位, 轮询循环自然唤醒)。
 *
 * UI 部分 (VerificationCodeDialog.display / 启动 WebViewActivity) 经 [VerificationUiProvider]
 * 注入, 由 app 端薄壳 `SourceVerificationHelp` 转发, 不在此处直接依赖。
 *
 * 行为与原 app 端完全一致, 仅位置迁移 + 依赖抽象。app 端调用方 import 不变
 * (包名 `io.legado.app.help.source.SourceVerificationHelp` 保持)。
 */
object SourceVerificationHelpShared {

    /** 等待用户输入验证结果的轮询间隔 (1 分钟, 对应原 waitTime)。 */
    private val waitTime = 1.minutes.inWholeNanoseconds

    fun getVerificationResultKey(sourceKey: String): String = "${sourceKey}_verificationResult"

    fun setResult(sourceKey: String, result: String?) {
        CacheManager.putMemory(getVerificationResultKey(sourceKey), result ?: "")
    }

    fun getResult(sourceKey: String): String? {
        return CacheManager.getFromMemory(getVerificationResultKey(sourceKey)) as? String
    }

    fun clearResult(sourceKey: String) {
        CacheManager.delete(getVerificationResultKey(sourceKey))
    }

    /**
     * 注册当前线程为验证结果等待线程 (对应原 `IntentData.put(key, Thread.currentThread())`)。
     *
     * 用 [currentThreadMarker] 替代 JVM `Thread`, 跨平台兼容; 验证结果到达时
     * [notifyResultArrived] 取出 marker 并 [unparkThread] 唤醒。
     */
    fun registerWaitingThread(sourceKey: String) {
        IntentData.put(getVerificationResultKey(sourceKey), currentThreadMarker())
    }

    /**
     * 验证结果到达后唤醒等待线程 (对应原 checkResult 中 `IntentData.get<Thread>` + `unpark`)。
     */
    fun notifyResultArrived(sourceKey: String) {
        val marker = IntentData.get<Any?>(getVerificationResultKey(sourceKey))
        unparkThread(marker)
    }

    /**
     * 内存轮询等待验证结果 (对应原 getVerificationResult 中 while 循环 + `LockSupport.parkNanos`)。
     *
     * 每 [waitTime] 纳秒 park 一次 (JVM 端可被 [notifyResultArrived] 的 unpark 提前唤醒,
     * Native 端靠超时后重新轮询 getResult 自然唤醒, 行为等价)。
     *
     * @return 验证结果字符串 (非空)
     * @throws NoStackTraceException 若结果为空字符串
     */
    fun waitVerificationResult(sourceKey: String): String {
        var waitUserInput = false
        while (getResult(sourceKey) == null) {
            if (!waitUserInput) {
                AppLog.putDebug("等待返回验证结果...")
                waitUserInput = true
            }
            parkThread(waitTime)
        }

        val result = getResult(sourceKey)!!
        clearResult(sourceKey)
        result.ifBlank {
            throw NoStackTraceException("验证结果为空")
        }
        return result
    }
}

/* ---- 线程阻塞/唤醒跨平台桥接 (expect) ---- */

/**
 * 当前线程的跨平台标记 (JVM 为 `Thread`, Native 为占位)。
 * 用于 [SourceVerificationHelpShared.registerWaitingThread] /
 * [SourceVerificationHelpShared.notifyResultArrived]。
 */
internal expect fun currentThreadMarker(): Any

/**
 * 阻塞当前线程指定纳秒, 或被 [unparkThread] 提前唤醒 (JVM 行为)。
 * Native 平台降级为不可中断的 sleep, 靠
 * [SourceVerificationHelpShared.waitVerificationResult] 的轮询循环在超时后重新检查
 * 结果自然唤醒。
 */
internal expect fun parkThread(nanos: Long)

/**
 * 唤醒 [currentThreadMarker] 标记的线程 (JVM 委托 `LockSupport.unpark`)。
 * Native 平台为空操作 (靠轮询超时唤醒)。
 */
internal expect fun unparkThread(marker: Any?)
