@file:Suppress("unused")

package io.legado.app.napi

import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
import io.legado.app.help.config.NativeSystemTheme
import io.legado.app.help.glide.progress.OnProgressListener
import io.legado.app.help.media.AudioFocusChange
import io.legado.app.help.media.PhoneCallState
import io.legado.app.help.media.SystemMediaControl
import io.legado.app.help.storage.BackupShared
import io.legado.app.help.tts.OhosReadAloudHost
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.OhosPlatformCapabilities
import io.legado.app.ui.root.AppForegroundState
import io.legado.app.utils.KS_JSON
import io.legado.app.web.WebServerManager
import io.legado.app.web.refreshOhosWebServerAddresses
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

/**
 * 鸿蒙统一平台事件通道 (ArkTS → Kotlin 单向推送)。
 *
 * # 为什么是单通道
 * 两个平台事件源 (HTTP 下载进度 / 应用生命周期) 方向相同 (ArkTS → Kotlin), 均为
 * 小 JSON 载荷, 共用一个 napi 入口 ([OhosNativeBridge.onPlatformEvent] →
 * `legado.platformEvent`) 与解析器, 避免每个功能各建一套 C++ tsfn / @CName 桥。
 *
 * # 事件协议 (与 ArkTS PlatformEventBridge.ets 对齐)
 * - HTTP 下载进度: `{ "type":"httpProgress", "url":"...", "bytesReceived":123,
 *   "totalBytes":456, "isComplete":false }` → [OhosDownloadProgressEvents]
 * - 应用生命周期: `{ "type":"lifecycle", "event":"onForeground"|"onBackground" }`
 *   → [OhosAppLifecycle]
 * - 系统深浅色: `{ "type":"colorMode", "isDark":true|false }`
 *   → [NativeSystemTheme] (「主题模式=跟随系统」档的唯一来源)
 * - 音频设备变化: `{ "type":"audioDeviceChange", "disconnected":true }`
 *   → [SystemMediaControl.onAudioBecomingNoisy] (拔耳机/蓝牙断开自动暂停)
 * - 音频焦点: `{ "type":"audioFocusChange", "change":"gain"|"loss"|"lossTransient"|"lossTransientCanDuck" }`
 *   → [SystemMediaControl.onAudioFocusChange] (AVPlayer 的 audioInterrupt 事件)
 * - 电话状态: `{ "type":"callStateChange", "state":"idle"|"ringing"|"offHook" }`
 *   → `OhosReadAloudHost.onPhoneCallState` (来电暂停朗读)
 * - 网络可用: `{ "type":"networkAvailable" }`
 *   → `io.legado.app.web.refreshOhosWebServerAddresses` (刷新 Web 服务地址)
 * - 系统对话框结果: `{ "type":"dialogResult", "requestId":3, "dialogResult":"{...}" }`
 *   → [OhosNativeBridge.onDialogResult] (系统对话框桥的响应侧, 见 [OhosNativeBridge.invokeDialogSync])
 * - 通知按钮点击: `{ "type":"notificationAction", "notificationId":104, "action":"stop" }`
 *   → [io.legado.app.ui.OhosPlatformCapabilities.onNotificationAction] (通知 actionButtons 的点击侧)
 * - 长时任务取消: `{ "type":"continuousTaskCancel", "reason":3, "notificationId":105 }`
 *   → 被取消方是 Web 服务时停掉它 (长时任务已取消 = 服务不再被保活)
 *
 * 后四类是同一次扩展加上的 (媒体/电话/网络), 仍走同一个 napi 入口与同一个解析器;
 * dialogResult 是系统对话框桥的响应侧 (请求侧复用 window tsfn), 同样走本入口。
 *
 * # 线程
 * 事件由 ArkTS 主线程经 napi → @CName 直推 (同 mediaEvent/ttsEvent 模式), 无跨线程
 * dispatch, 监听器回调执行在 ArkTS 主线程 (Compose 状态更新即在此线程)。
 */

/**
 * 应用生命周期事件类型 (ArkTS EntryAbility.onForeground/onBackground → Kotlin)。
 *
 * 对照 iOS IosBackgroundTasks 的 UIApplicationWillEnterForeground /
 * DidEnterBackground 通知: [ON_FOREGROUND] 回到前台, [ON_BACKGROUND] 退到后台。
 */
enum class OhosLifecycleEvent { ON_FOREGROUND, ON_BACKGROUND }

/**
 * 应用生命周期事件入口: 只把前后台状态灌进 [AppForegroundState] (全局唯一真源)。
 *
 * 消费方订阅 [AppForegroundState.isForeground] (由页面级 Lifecycle 消费, 见 RouteLifecycle),
 * 不再各自挂监听。
 */
object OhosAppLifecycle {

    /** 事件分发 (由 [OhosPlatformEventChannel] 调用)。 */
    internal fun dispatch(event: OhosLifecycleEvent) {
        AppForegroundState.set(event == OhosLifecycleEvent.ON_FOREGROUND)
        if (event == OhosLifecycleEvent.ON_BACKGROUND) {
            BackupShared.autoBack()
        }
    }
}

/**
 * 鸿蒙 HTTP 下载进度事件注册表 (ArkTS @ohos.net.http requestInStream → Kotlin)。
 *
 * # 与 Android ProgressManager 同形
 * 进度回调签名/去重语义对齐 [OnProgressListener]/ProgressManager:
 * - [addListener]/[removeListener]/[getProgressListener] 按 url (去书源参数后的 key) 注册;
 * - 回调 `(isComplete, percentage, bytesRead, totalBytes)`; totalBytes<=0 视为不确定进度
 *   (percentage 0, 与 ProgressManager 同语义);
 * - isComplete=true 为终态 (成功/失败/取消), 回调后自动移除监听 (消费方无需再 remove,
 *   与 ProgressManager 完成即 removeListener 的行为一致);
 * - 仅转发递增进度 (丢弃重复/回退事件, 对齐 ProgressManager lastBytesRead 去重)。
 *
 * 消费端 (图片加载进度/MangaPageImageView 等价物) 暂未接入: 本注册表是桥打通后的
 * Kotlin 侧可订阅 API, 由主代理后续在 ohosMain 图片/下载路径接线。
 */
object OhosDownloadProgressEvents {

    private val lock = SynchronizedObject()

    /** url (去参 key) → 进度监听器 (同一 url 重复注册覆盖前者, 同 ProgressManager)。 */
    private val listenersMap = mutableMapOf<String, OnProgressListener>()

    /** url (去参 key) → 上次已转发的字节数 (递增去重, 同 ProgressManager). */
    private val lastBytesReadMap = mutableMapOf<String, Long>()

    /**
     * 注册下载进度监听器。
     *
     * @param url 请求 url (书源参数 `{,{...}}` 尾部会被剥离作为 key, 与 ProgressManager 一致)
     * @param listener 进度回调; 同一 url 重复注册覆盖前者
     */
    fun addListener(url: String, listener: OnProgressListener) {
        if (url.isEmpty()) return
        val key = getUrlNoOption(url)
        synchronized(lock) {
            listenersMap[key] = listener
            lastBytesReadMap[key] = -1L
        }
    }

    /**
     * 注销下载进度监听器 (未注册时为 no-op)。
     *
     * 注: 终态 (isComplete) 事件会自动注销, 无需显式调用; 提前取消/失败时调用方
     * 主动 remove 即可立刻停止接收 (同 ProgressManager 用法)。
     */
    fun removeListener(url: String) {
        if (url.isEmpty()) return
        val key = getUrlNoOption(url)
        synchronized(lock) {
            listenersMap.remove(key)
            lastBytesReadMap.remove(key)
        }
    }

    /** 取指定 url 的进度监听器 (未注册或 url 为空返回 null)。 */
    fun getProgressListener(url: String): OnProgressListener? {
        if (url.isEmpty()) return null
        return synchronized(lock) { listenersMap[getUrlNoOption(url)] }
    }

    /**
     * 进度事件入口 (由 [OhosPlatformEventChannel] 调用)。
     *
     * @param isComplete 终态标记 (请求成功/失败/取消; 终态按 100% 回调并自动注销监听)
     */
    internal fun onProgress(
        url: String,
        bytesRead: Long,
        totalBytes: Long,
        isComplete: Boolean,
    ) {
        if (url.isEmpty()) return
        val key = getUrlNoOption(url)
        // 锁内取监听器 + 递增去重 (终态事件直接放行, 不依赖字节数递进)
        val listener = synchronized(lock) {
            val l = listenersMap[key] ?: return
            val lastBytesRead = lastBytesReadMap[key] ?: -1L
            if (isComplete) {
                lastBytesReadMap.remove(key)
            } else if (bytesRead > lastBytesRead) {
                lastBytesReadMap[key] = bytesRead
            } else {
                // 重复/回退进度 (同值重发或乱序) 丢弃, 对齐 ProgressManager
                return
            }
            l
        }
        val percentage = when {
            isComplete -> 100
            totalBytes <= 0L -> 0
            else -> (bytesRead * 1f / totalBytes * 100f).toInt().coerceIn(0, 100)
        }
        listener(isComplete, percentage, bytesRead, totalBytes)
        if (isComplete) {
            synchronized(lock) { listenersMap.remove(key) }
        }
    }

    /**
     * 去掉 url 中的书源参数部分 (如 `https://...{,{"method":"GET"}}`), 作为监听器表 key,
     * 避免注册 url 与 ArkTS 事件回传 url 因参数写法不一致无法对齐 (同 ProgressManager 语义)。
     */
    fun getUrlNoOption(url: String): String {
        val match = AnalyzeUrlCore.paramPattern.find(url)
        return if (match != null) url.take(match.range.first) else url
    }
}

/**
 * 统一平台事件解析/分发器 (由 [OhosNativeBridge.onPlatformEvent] 调用)。
 *
 * 单 JSON 一次反序列化后按 `type` 路由 (KS_JSON ignoreUnknownKeys, 未来新增事件类型
 * 不破坏旧字段); 未知 type / 解析失败静默丢弃 (事件通道为尽力而为, 不阻塞 ArkTS 侧)。
 */
internal object OhosPlatformEventChannel {

    fun onEvent(eventJson: String) {
        val payload = runCatching {
            KS_JSON.decodeFromString(OhosPlatformEventPayload.serializer(), eventJson)
        }.getOrNull() ?: return

        when (payload.type) {
            "httpProgress" -> OhosDownloadProgressEvents.onProgress(
                url = payload.url.orEmpty(),
                bytesRead = payload.bytesReceived,
                totalBytes = payload.totalBytes,
                isComplete = payload.isComplete,
            )

            "lifecycle" -> when (payload.event) {
                "onForeground" -> OhosAppLifecycle.dispatch(OhosLifecycleEvent.ON_FOREGROUND)
                "onBackground" -> OhosAppLifecycle.dispatch(OhosLifecycleEvent.ON_BACKGROUND)
            }

            "colorMode" -> NativeSystemTheme.update(payload.isDark)

            // 音频输出设备变化 (拔耳机/蓝牙断开) → 暂停当前出声的一方
            "audioDeviceChange" -> if (payload.disconnected) {
                SystemMediaControl.onAudioBecomingNoisy()
            }

            // 音频焦点/打断四档 (与 Android AUDIOFOCUS_* 一一对应)
            "audioFocusChange" -> when (payload.change) {
                "gain" -> SystemMediaControl.onAudioFocusChange(AudioFocusChange.Gain)
                "loss" -> SystemMediaControl.onAudioFocusChange(AudioFocusChange.Loss)
                "lossTransient" -> SystemMediaControl.onAudioFocusChange(AudioFocusChange.LossTransient)
                "lossTransientCanDuck" ->
                    SystemMediaControl.onAudioFocusChange(AudioFocusChange.LossTransientCanDuck)
            }

            // 网络可用 (ArkTS @ohos.net.connection netAvailable) → 重算 Web 服务地址。
            // 服务没开时共享侧是 no-op, 故与 Android "只在 Service 存活期监听" 观感一致。
            "networkAvailable" -> refreshOhosWebServerAddresses()

            // 电话状态三态 → 朗读宿主 (门槛与恢复语义都在共享件里)
            "callStateChange" -> when (payload.state) {
                "idle" -> OhosReadAloudHost.onPhoneCallState(PhoneCallState.Idle)
                "ringing" -> OhosReadAloudHost.onPhoneCallState(PhoneCallState.Ringing)
                "offHook" -> OhosReadAloudHost.onPhoneCallState(PhoneCallState.OffHook)
            }

            // 系统对话框用户选择 (SysDialogBridgeHandler → EntryAbility window callback →
            // emitDialogResult) → 唤醒 OhosNativeBridge.invokeDialogSync 阻塞中的请求方
            "dialogResult" -> OhosNativeBridge.onDialogResult(
                requestId = payload.requestId,
                resultJson = payload.dialogResult.orEmpty(),
            )

            // 通知按钮点击 (EntryAbility 从按钮 want 的 parameters 解出 → emitPlatformEvent)
            "notificationAction" -> OhosPlatformCapabilities.onNotificationAction(
                notificationId = payload.notificationId,
                action = payload.action.orEmpty(),
            )

            // 长时任务被系统/用户取消 (删除长时任务通知、系统一致性校验判定不合规等)。被取消方的
            // 业务通知 id 在 notificationId 里: 只有 Web 服务那种"靠长时任务存活"的业务要停掉自己
            // (对照 app 端 WebService 收到 IntentAction.stop 的 stopSelf); 缓存/更新/导出等业务继续跑,
            // 只是本轮不再保活 (ArkTS 仲裁器已按接入规范抑制立即重申请)
            "continuousTaskCancel" -> {
                AppLog.put(
                    "长时任务已取消, 业务通知 id=${payload.notificationId} reason=${payload.reason}"
                )
                if (payload.notificationId == NotificationId.WebService && WebServerManager.isRun) {
                    OhosPlatformCapabilities.setWebService(false)
                }
            }
        }
    }
}

/**
 * 统一平台事件载荷 (Kotlin 侧唯一一份定义, 与 ArkTS PlatformEventBridge.ets 对齐)。
 *
 * @param type 事件类型: "httpProgress" | "lifecycle" | "colorMode" |
 *   "audioDeviceChange" | "audioFocusChange" | "callStateChange" | "networkAvailable" | "dialogResult" |
 *   "notificationAction" | "continuousTaskCancel"
 * @param url httpProgress: 请求 url (与 addListener 的 url 对应)
 * @param bytesReceived httpProgress: 已接收字节数
 * @param totalBytes httpProgress: 总字节数 (<=0 表示未知, 如分块传输无 Content-Length)
 * @param isComplete httpProgress: 终态 (请求成功/失败/取消, 之后无该 url 的后续事件)
 * @param event lifecycle: "onForeground" | "onBackground"
 * @param isDark colorMode: 系统是否深色
 * @param disconnected audioDeviceChange: true = 当前输出设备已断开
 * @param change audioFocusChange: "gain" | "loss" | "lossTransient" | "lossTransientCanDuck"
 * @param state callStateChange: "idle" | "ringing" | "offHook"
 * @param requestId dialogResult: 对应 [OhosNativeBridge.invokeDialogSync] 生成的请求 ID
 * @param dialogResult dialogResult: 系统对话框结果 JSON (`{ok,index}` / `{ok,confirmed}` / `{ok,cancelled}`)
 * @param notificationId notificationAction: 通知 id (与 ArkTS NOTIFICATION_ROUTES、[io.legado.app.constant.NotificationId] 同一张 id 表);
 *   continuousTaskCancel: 被取消方的业务通知 id
 * @param action notificationAction: 按钮动作标识 (原版 `addAction` 的 pendingIntent action, 如 "stop")
 * @param reason continuousTaskCancel: 长时任务取消原因 (ArkTS backgroundTaskManager.ContinuousTaskCancelReason 原值)
 */
@Serializable
internal data class OhosPlatformEventPayload(
    val type: String,
    val url: String? = null,
    val bytesReceived: Long = 0L,
    val totalBytes: Long = 0L,
    val isComplete: Boolean = false,
    val event: String? = null,
    val isDark: Boolean = false,
    val disconnected: Boolean = false,
    val change: String? = null,
    val state: String? = null,
    val requestId: Long = 0L,
    val dialogResult: String? = null,
    val notificationId: Int = 0,
    val action: String? = null,
    val reason: Int = 0,
)
