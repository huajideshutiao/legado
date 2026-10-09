package io.legado.app.help.service

import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.napi.NotificationButton
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.utils.FlowBus
import io.legado.app.web.WebServerManager
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 鸿蒙端 Web 服务常驻通知 (对照 app 端 `WebService.startForegroundNotification`)。
 *
 * app 端通知随 Service 生死: `onCreate` 起按 [WebServerManager] 的地址列表发常驻通知,
 * 地址变化时重发同一条; `onDestroy` 撤掉; 点击 = 复制服务地址 (`"copyHostAddress"`),
 * 取消动作 = `IntentAction.stop` → `stopSelf()`。
 *
 * 鸿蒙无 Service, 通知态由既有信号驱动, 不新造调度:
 * - **服务态/地址**: 共享层既有事件 [EventBus.WEB_SERVICE] (由 [WebServerManager] 的
 *   start/stop/updateAddresses 发出), 正文取 [WebServerManager.hostAddresses] /
 *   [WebServerManager.hostAddress] (与 app 端 `notificationList` 同一批 URL)
 * - **点击复制地址**: 通知载荷的 `clickAction` = [ACTION_COPY_HOST_ADDRESS], 点击后经统一平台
 *   事件通道回传到 `OhosPlatformCapabilities.onNotificationAction` (与按钮同一路径)
 * - **取消**: 同一条通道的 [IntentAction.stop] → 停服务 (对照原版 `stopSelf()`)
 *
 * 通知 id 用 [NotificationId.WebService] (与 app 端同 id); 该 id 不在 ArkTS 侧 id→route 表内,
 * 因为原版这条通知的 contentIntent 指向的是 Service action 而非某个页面。
 */
internal object OhosWebServiceNotifications {

    /** 通知点击动作: 复制服务地址 (逐字对照 app 端 `WebService.onStartCommand` 的 "copyHostAddress" 分支)。 */
    const val ACTION_COPY_HOST_ADDRESS = "copyHostAddress"

    /**
     * Web 服务的长时任务类型 (module.json5 的 backgroundModes 项, 必须与之逐字一致)。
     *
     * 选"多设备互联"的理由 (长时任务文档"使用场景"表逐项对照, 该表无"局域网 HTTP 服务"一行):
     * - `dataTransfer` (数据传输: 非托管形式的上传/下载): 文档额外要求"应用需要更新进度, 若首次
     *   超过 10 分钟未更新则长时任务被取消, 且更新通知类型必须为实况窗" —— 空闲等连接的服务
     *   满足不了, 10 分钟后必被取消
     * - `taskKeeping` (计算任务): API 20 及之前仅 PC/2in1 设备开放, API 21 起非 PC 设备还需
     *   受限 ACL 权限 `ohos.permission.KEEP_BACKGROUND_RUNNING_SYSTEM`
     * - `wifiInteraction` (WLAN 相关业务): 仅对系统应用开放
     * - `multiDeviceConnection` (多设备互联, 场景举例: 分布式业务连接、投播): 本服务就是"另一台
     *   设备的浏览器连到本机"的跨设备会话, 是表内唯一不以特定传输方式/特定数据流为前提的连接类类型
     *
     * ⚠ 系统会对长时任务的业务真实性做一致性校验 (接入规范: "保证应用后台执行的业务与申请的任务
     * 类型一致"), 本机无鸿蒙设备, 该模式是否通过校验**未经真机验证**; 被管控时只影响保活
     * (服务仍可前台使用, 失败会记日志)。
     */
    const val CONTINUOUS_TASK_MODE = "multiDeviceConnection"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = SynchronizedObject()

    @Volatile
    private var observing = false

    /** 订阅服务状态事件 (宿主启动早期一次, 见 [registerOhosWebServiceNotifications])。 */
    fun observe() {
        if (observing) return
        observing = true
        FlowBus.with(EventBus.WEB_SERVICE).onEach { refresh() }.launchIn(scope)
    }

    /**
     * 服务态/地址变化 → 常驻通知 (运行中) 或撤掉通知 (已停止)。
     *
     * 对照 app 端: 运行中 = `startForegroundNotification()` (setOngoing(true) + 取消动作 +
     * 点击复制地址); 停止 = `onDestroy` 后前台通知随服务消失, 故这里直接撤掉。
     * 原版该通知未调 setOnlyAlertOnce, 故不下发 alertOnce (地址刷新时照常提醒一次)。
     *
     * 运行中按 [CONTINUOUS_TASK_MODE] 申请长时任务 (= 原版的前台服务保活), 停止时一并取消
     * (两者是同一件事的两面: 系统为长时任务自己发的那条通知不可修改, 故业务通知单发一条)。
     */
    fun refresh() = synchronized(lock) {
        if (!WebServerManager.isRun) {
            OhosNativeBridge.cancelNotification(
                NotificationId.WebService,
                stopContinuousTask = true,
            )
            return@synchronized
        }
        OhosNativeBridge.showNotification(
            id = NotificationId.WebService,
            title = syncGetString("web_service"),
            content = content(),
            progress = 0,
            max = 0,
            ongoing = true,
            buttons = listOf(
                NotificationButton(text = syncGetString("cancel"), action = IntentAction.stop)
            ),
            clickAction = ACTION_COPY_HOST_ADDRESS,
            continuousTask = CONTINUOUS_TASK_MODE,
        )
    }

    /**
     * 通知正文: 每行一个可访问地址 (对照 app 端 `notificationList.joinToString("\n")`)。
     *
     * 无可用网络时 [WebServerManager.hostAddress] 存的是平台传入的本地化文案
     * (对照 app 端 `R.string.network_connection_unavailable` 那一支)。
     */
    private fun content(): String {
        val addresses = WebServerManager.hostAddresses
        if (addresses.isNotEmpty()) return addresses.joinToString("\n")
        return WebServerManager.hostAddress
    }
}

/**
 * 鸿蒙宿主启动早期调用 (见 `io.legado.app.help.config.registerOhosProviders`)。
 *
 * 须先于任何一次 [WebServerManager] 启停 (注册目录里的位置: ServiceLauncher 之后、UI provider 之前)。
 */
fun registerOhosWebServiceNotifications() {
    // 启动即收掉上一条常驻通知: 鸿蒙无 Service, 进程被回收不带走通知
    // (残留的那条"取消"已无服务可停, 且地址是上次运行时的; 背靠的长时任务也随进程一起没了)
    OhosNativeBridge.cancelNotification(NotificationId.WebService, stopContinuousTask = true)
    OhosWebServiceNotifications.observe()
}
