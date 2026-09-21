package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfigProviders
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

/**
 * 鸿蒙端书源校验调度 (对照 app 端 `CheckSourceService.check` / desktop `DesktopCheckSource`)。
 *
 * 鸿蒙无 Android Service, 与桌面端同样用协程 + [onEachParallel] 限流并发跑
 * [CheckSourceShared.checkSource] (业务全流程已下沉), 进度经 EventBus 通知 UI。
 */
object OhosCheckSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var checkJob: Job? = null

    fun start(selection: List<BookSourcePart>) {
        if (checkJob?.isActive == true) {
            Toasters.get().toast("已有书源在校验,等完成后再试")
            return
        }
        val ids = selection.map { it.bookSourceUrl }
        if (ids.isEmpty()) return
        val appDb = AppDbProviders.get()
        var finishCount = 0
        checkJob = scope.launch {
            flow<BookSource> {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let { emit(it) }
                }
            }.onEachParallel(AppConfigProviders.get().threadCount) {
                CheckSourceShared.checkSource(it)
            }.onEach { source ->
                finishCount++
                postEvent(EventBus.CHECK_SOURCE, "${source.bookSourceName} $finishCount/${ids.size}")
                appDb.bookSourceDao.update(source)
            }.onCompletion {
                // 对照 CheckSourceService.onDestroy
                Debug.finishChecking()
                postEvent(EventBus.CHECK_SOURCE_DONE, 0)
            }.collect { }
        }
        checkJob?.invokeOnCompletion { error ->
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
