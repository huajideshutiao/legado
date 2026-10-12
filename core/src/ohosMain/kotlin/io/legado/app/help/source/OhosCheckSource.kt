package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CheckSourceRunner
import io.legado.app.model.Debug
import io.legado.app.napi.NotificationButton
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.utils.postEvent
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 鸿蒙端书源校验调度 (对照 app 端 `CheckSourceService.check` / desktop `DesktopCheckSource`)。
 *
 * 鸿蒙无 Android Service, 并发编排 (逐 id 取源 → 限流并发 → 进度回调 → 完成回调) 与执行线程池
 * 口径走 commonMain [CheckSourceRunner], 进度既发事件 (通知 UI) 也进通知栏
 * (对照 app 端 CheckSourceService: 通知 id / 标题 / 取消动作与 app 端同一套, 两处文案的取舍见
 * [upNotification])。
 */
object OhosCheckSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var checkJob: Job? = null

    /** 最近一次进度参数 (对照 app 端 CheckSourceService 的 notificationMsg/originSize: 重进页时重发)。 */
    @Volatile
    private var lastProgress: Triple<String, Int, Int>? = null

    /** 按书源 URL 启动校验 (共享对话框确认后走这条)。 */
    fun startUrls(ids: List<String>) {
        if (checkJob?.isActive == true) {
            Toasters.get().toast("已有书源在校验,等完成后再试")
            return
        }
        if (ids.isEmpty()) return
        checkJob = scope.launch {
            CheckSourceRunner.run(
                ids = ids,
                onStart = { total -> upNotification("", 0, total) },
                onProgress = { name, finishCount, total ->
                    upNotification(name, finishCount, total)
                },
                onComplete = {
                    // 对照 CheckSourceService.onDestroy
                    lastProgress = null
                    Debug.finishChecking()
                    postEvent(EventBus.CHECK_SOURCE_DONE, 0)
                    OhosNativeBridge.cancelNotification(NotificationId.CheckSourceService)
                }
            )
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
     *
     * 不申请长时任务保活: 校验书源是一批 HTTP 请求的**校验** (搜索/详情/目录/正文探测),
     * 既非上传也非下载 (无持续数据流), 后台模式表里没有一个类型对得上 —— `dataTransfer`
     * (非托管上传/下载) 要求应用持续更新进度 (首次发布后 10 分钟无更新即被删通知停任务,
     * 之后每次更新把时限重置为 15 分钟), 与请求-响应型流量天然不符,
     * 其余类型 (蓝牙/定位/录音/投屏/通话/星闪) 更不相干, `wifiInteraction` 仅对
     * 系统应用开放, `taskKeeping` 有设备/ACL 门槛。
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
