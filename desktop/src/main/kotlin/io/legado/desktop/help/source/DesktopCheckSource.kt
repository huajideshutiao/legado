package io.legado.desktop.help.source

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.NotificationId
import io.legado.app.help.notification.DesktopTaskbar
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CheckSourceRunner
import io.legado.app.model.Debug
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面端书源校验调度 (对照 app 端 `CheckSourceService.check`)。
 *
 * 桌面端无 Android Service, 并发编排 (逐 id 取源 → 限流并发 → 进度回调 → 完成回调) 与执行
 * 线程池口径走 commonMain [CheckSourceRunner], 进度既发事件
 * ([EventBus.CHECK_SOURCE] / [EventBus.CHECK_SOURCE_DONE]) 通知 UI, 也留在 [lastProgress]
 * 供托盘呈现取用 (对照 app 端 CheckSourceService 的通知正文); 任务栏进度记在
 * [NotificationId.CheckSourceService] 名下 (与 app 端该校验通知同一 id)。
 *
 * 首帧 (onStart) 只发事件, 不取用任务栏进度位: 任务栏进度由逐项完成帧驱动 (每个完成项都刷一次,
 * 含书源名为空的项)。
 */
object DesktopCheckSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    /**
     * 最近一次进度 (书源名, 已完成, 总数); null = 本轮校验已结束。
     *
     * 供后台任务呈现取与 app 端通知同源的进度文案 (对照 app 端 `CheckSourceService.notificationMsg`
     * 与 iOS 端 `IosCheckSource.lastProgress`)。
     */
    @Volatile
    var lastProgress: Triple<String, Int, Int>? = null
        private set

    /** 按书源 URL 启动校验 (共享对话框确认后走这条)。 */
    fun startUrls(ids: List<String>) {
        if (checkJob?.isActive == true) {
            Toasters.get().toast("已有书源在校验,等完成后再试")
            return
        }
        if (ids.isEmpty()) return
        checkJob = scope.launch {
            // 本轮占用进度位的本代次: 每帧进度都是一次取用, 收尾用本轮最后一次的取值释放 —— 上一轮
            // (取消的收尾晚到) 用的取值对不上, 不会清掉新一轮刚上屏的进度
            var generation = 0L
            CheckSourceRunner.run(
                ids = ids,
                onStart = { total ->
                    lastProgress = Triple("", 0, total)
                    postEvent(EventBus.CHECK_SOURCE, "0/$total")
                },
                onProgress = { name, finishCount, total ->
                    lastProgress = Triple(name, finishCount, total)
                    postEvent(EventBus.CHECK_SOURCE, "$name $finishCount/$total".trim())
                    generation = DesktopTaskbar.show(
                        NotificationId.CheckSourceService, finishCount, total
                    )
                },
                onComplete = {
                    // 对照 CheckSourceService.onDestroy
                    lastProgress = null
                    DesktopTaskbar.clear(NotificationId.CheckSourceService, generation)
                    Debug.finishChecking()
                    postEvent(EventBus.CHECK_SOURCE_DONE, 0)
                }
            )
        }
        checkJob?.invokeOnCompletion { error ->
            if (error != null) AppLog.put("校验书源出错\n${error.message}", error)
        }
    }

    fun stop() {
        checkJob?.cancel()
        checkJob = null
        lastProgress = null
        Debug.finishChecking()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
    }
}
