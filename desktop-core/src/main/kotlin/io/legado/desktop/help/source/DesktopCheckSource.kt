package io.legado.desktop.help.source

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.closeIfCloseable
import io.legado.app.help.coroutine.newFixedThreadPoolDispatcher
import io.legado.app.help.notification.DesktopTaskbar
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CheckSourceShared
import io.legado.app.model.Debug
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 桌面端书源校验调度 (对照 app 端 `CheckSourceService.check`)。
 *
 * 桌面端无 Android Service, 用协程 + [onEachParallel] 限流并发跑
 * [CheckSourceShared.checkSource] (业务全流程已下沉), 进度经
 * [EventBus.CHECK_SOURCE] / [EventBus.CHECK_SOURCE_DONE] 通知 UI。
 *
 * 线程模型对照原版 CheckSourceService: 固定线程池 `min(threadCount, MAX_THREAD)`
 * (原版 searchCoroutine), 校验是网络阻塞调用, 不能占 Dispatchers.Default 的 CPU 池。
 */
object DesktopCheckSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    fun start(selection: List<BookSourcePart>) {
        if (checkJob?.isActive == true) {
            Toasters.get().toast("已有书源在校验,等完成后再试")
            return
        }
        val ids = selection.map { it.bookSourceUrl }
        if (ids.isEmpty()) return
        val appDb = AppDbProviders.get()
        val threadCount = AppConfigProviders.get().threadCount
        // 对照原版 searchCoroutine: 池大小钳到 MAX_THREAD, 跑完随 job 关闭释放线程
        val checkPool = newFixedThreadPoolDispatcher(min(threadCount, AppConst.MAX_THREAD))
        var finishCount = 0
        checkJob = scope.launch(checkPool) {
            flow<BookSource> {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let { emit(it) }
                }
            }.onEachParallel(threadCount) {
                CheckSourceShared.checkSource(it)
            }.onEach { source ->
                finishCount++
                postEvent(EventBus.CHECK_SOURCE, "${source.bookSourceName} $finishCount/${ids.size}")
                DesktopTaskbar.show(finishCount, ids.size)
                appDb.bookSourceDao.update(source)
            }.onCompletion {
                // 对照 CheckSourceService.onDestroy
                DesktopTaskbar.clear()
                Debug.finishChecking()
                postEvent(EventBus.CHECK_SOURCE_DONE, 0)
            }.collect { }
        }
        checkJob?.invokeOnCompletion { error ->
            checkPool.closeIfCloseable()
            if (error != null) AppLog.put("校验书源出错\n${error.message}", error)
        }
    }

    fun stop() {
        checkJob?.cancel()
        checkJob = null
        Debug.finishChecking()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
    }
}
