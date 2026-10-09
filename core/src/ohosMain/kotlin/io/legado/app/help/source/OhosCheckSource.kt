package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CheckSourceShared
import io.legado.app.model.Debug
import io.legado.app.napi.NotificationButton
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlin.concurrent.Volatile
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
 * [CheckSourceShared.checkSource] (业务全流程已下沉), 进度既发事件 (通知 UI) 也进通知栏
 * (对照 app 端 CheckSourceService: 通知 id / 标题 / 取消动作与 app 端同一套, 两处文案的取舍见
 * [upNotification])。
 */
object OhosCheckSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var checkJob: Job? = null

    /** 最近一次进度参数 (对照 app 端 CheckSourceService 的 notificationMsg/originSize: 重进页时重发)。 */
    @Volatile
    private var lastProgress: Triple<String, Int, Int>? = null

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
            // 初始帧 (对照 app 端 CheckSourceService.check 的 onStart 分支: 尚无书源名)
            upNotification("", 0, ids.size)
            flow<BookSource> {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let { emit(it) }
                }
            }.onEachParallel(AppConfigProviders.get().threadCount) {
                CheckSourceShared.checkSource(it)
            }.onEach { source ->
                finishCount++
                upNotification(source.bookSourceName, finishCount, ids.size)
                appDb.bookSourceDao.update(source)
            }.onCompletion {
                // 对照 CheckSourceService.onDestroy
                lastProgress = null
                Debug.finishChecking()
                postEvent(EventBus.CHECK_SOURCE_DONE, 0)
                OhosNativeBridge.cancelNotification(NotificationId.CheckSourceService)
            }.collect { }
        }
        checkJob?.invokeOnCompletion { error ->
            if (error != null) AppLog.put("校验书源出错\n${error.message}", error)
        }
    }

    /** 校验中重新进入书源管理页时重发进度 (对照 app 端 `CheckSourceService` 的 IntentAction.resume)。 */
    fun resume() {
        if (checkJob?.isActive != true) return
        val (name, count, total) = lastProgress ?: return
        upNotification(name, count, total)
    }

    fun stop() {
        checkJob?.cancel()
        checkJob = null
        lastProgress = null
        Debug.finishChecking()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
        // 对照 app 端 CheckSourceService.onDestroy 的 notificationManager.cancel
        OhosNativeBridge.cancelNotification(NotificationId.CheckSourceService)
    }

    /**
     * 进度事件 + 进度通知 (对照 app 端 CheckSourceService.upNotification: 同一次进度两处使用)。
     *
     * 事件正文沿用各端既有格式 (与 desktop/鸿蒙 同一串, 共享路由的进度标签按它显示);
     * 通知正文取 app 端同 key 文案 `progress_show` (与 app 端通知正文逐字同源)。
     */
    private fun upNotification(name: String, count: Int, total: Int) {
        lastProgress = Triple(name, count, total)
        postEvent(EventBus.CHECK_SOURCE, progressText(name, count, total))
        OhosNativeBridge.showNotification(
            id = NotificationId.CheckSourceService,
            title = syncGetString("check_book_source"),
            content = syncGetString("progress_show", name, count, total).trim(),
            progress = count,
            max = total,
            // 常驻 + 只提醒一次 + 取消按钮, 对照 app 端通知的三个 setter;
            // 点击回书源管理页由 ArkTS 侧 id→route 表 (107) 映射
            ongoing = true,
            alertOnce = true,
            buttons = listOf(
                NotificationButton(text = syncGetString("cancel"), action = IntentAction.stop)
            ),
        )
    }

    /**
     * 进度事件正文 (首帧无书源名, 去掉前导空格, 对照 app 端 `progress_show` 首帧 trim)。
     */
    private fun progressText(name: String, count: Int, total: Int): String =
        "$name $count/$total".trim()
}
