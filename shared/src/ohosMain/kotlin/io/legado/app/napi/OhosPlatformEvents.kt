@file:Suppress("unused")

package io.legado.app.napi

import io.legado.app.help.glide.progress.OnProgressListener
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.utils.KS_JSON
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
 *
 * # 线程
 * 事件由 ArkTS 主线程经 napi → @CName 直推 (同 mediaEvent/ttsEvent 模式), 无跨线程
 * dispatch, 监听器回调执行在 ArkTS 主线程 (Compose 状态更新即在此线程)。
 */

/**
 * 应用生命周期事件类型 (ArkTS EntryAbility.onForeground/onBackground → Kotlin)。
 *
 * 对照 iOS [io.legado.app.ui.book.read.IosReaderPlatformProvider] 的
 * UIApplicationWillEnterForegroundNotification / UIApplicationDidEnterBackgroundNotification:
 * - [ON_FOREGROUND]: app 回到前台, 阅读页消费方调 ReaderScreenModel.onResume()
 * - [ON_BACKGROUND]: app 退到后台, 阅读页消费方调 ReaderScreenModel.onPause()
 */
enum class OhosLifecycleEvent { ON_FOREGROUND, ON_BACKGROUND }

/**
 * 应用生命周期事件注册表 (阅读页/provider 挂卸监听)。
 *
 * # 用法 (对照 iOS onEnter/onExit 注册前后台通知观察者)
 * 进入阅读页 onEnter 时 [addListener], 退出 onExit/Dispose 时 [removeListener], 避免
 * 残留监听重复触发 (同一 listener 重复 add 幂等, remove 按引用删除)。
 *
 * # 多监听
 * 支持多个监听器 (可能同时存在漫画阅读页与文本阅读页实例, 各自注册/注销互不覆盖);
 * dispatch 取快照遍历, 锁外回调 (与 OhosNativeBridge.shutdownTtsIfListener 同思路,
 * 避免锁内回调经 AppLog/toast 反锁 [OhosNativeBridge.lock] 造成死锁)。
 */
object OhosAppLifecycle {

    /**
     * 生命周期事件监听器。
     *
     * 命名与 ArkTS 事件对齐 (onForeground/onBackground); 阅读页消费时与
     * [ReaderScreenModel.onResume]/[onPause] 的对应关系见 [OhosLifecycleEvent]。
     */
    interface Listener {
        /** 应用回到前台。 */
        fun onForeground()

        /** 应用退到后台。 */
        fun onBackground()
    }

    private val lock = SynchronizedObject()

    /** 监听器表 (add 去重, remove 按引用删除)。 */
    private val listeners = mutableListOf<Listener>()

    /** 添加生命周期监听器 (同一实例重复添加为 no-op)。 */
    fun addListener(listener: Listener) {
        synchronized(lock) {
            if (listener !in listeners) listeners.add(listener)
        }
    }

    /** 移除生命周期监听器 (未注册时为 no-op)。 */
    fun removeListener(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    /** 当前监听器快照 (AppLog 等调试用)。 */
    fun listenerCount(): Int = synchronized(lock) { listeners.size }

    /** 事件分发 (由 [OhosPlatformEventChannel] 调用; 锁外遍历快照)。 */
    internal fun dispatch(event: OhosLifecycleEvent) {
        val snapshot = synchronized(lock) { listeners.toList() }
        for (listener in snapshot) {
            when (event) {
                OhosLifecycleEvent.ON_FOREGROUND -> listener.onForeground()
                OhosLifecycleEvent.ON_BACKGROUND -> listener.onBackground()
            }
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
        }
    }
}

/**
 * 统一平台事件载荷 (Kotlin 侧唯一一份定义, 与 ArkTS PlatformEventBridge.ets 对齐)。
 *
 * @param type 事件类型: "httpProgress" | "lifecycle"
 * @param url httpProgress: 请求 url (与 addListener 的 url 对应)
 * @param bytesReceived httpProgress: 已接收字节数
 * @param totalBytes httpProgress: 总字节数 (<=0 表示未知, 如分块传输无 Content-Length)
 * @param isComplete httpProgress: 终态 (请求成功/失败/取消, 之后无该 url 的后续事件)
 * @param event lifecycle: "onForeground" | "onBackground"
 */
@Serializable
internal data class OhosPlatformEventPayload(
    val type: String,
    val url: String? = null,
    val bytesReceived: Long = 0L,
    val totalBytes: Long = 0L,
    val isComplete: Boolean = false,
    val event: String? = null,
)
