/**
 * ArkTS 侧统一平台事件回送 (ArkTS → Kotlin, 单通道复用)。
 *
 * 与 mediaEvent/ttsEvent 同方向 (ArkTS → Kotlin 单向推送): 本模块持有事件回送回调
 * (EntryAbility 注入 legado.platformEvent), 各功能模块 (HttpBridgeHandler 的 HTTP 下载
 * 进度 / EntryAbility 的应用生命周期) 调 [emitHttpProgress] / [emitAppLifecycle] 推送统一
 * JSON 事件; Kotlin 侧 OhosPlatformEventChannel (OhosPlatformEvents.kt) 按 type 分发到
 * OhosDownloadProgressEvents / OhosAppLifecycle。
 *
 * # 事件协议 (与 Kotlin OhosPlatformEventPayload 对齐)
 * - HTTP 下载进度: { type: 'httpProgress', url, bytesReceived, totalBytes, isComplete }
 * - 应用生命周期: { type: 'lifecycle', event: 'onForeground' | 'onBackground' }
 *
 * # 线程
 * 回调在调用线程同步执行 (ArkTS 主线程 / @ohos.net.http 事件线程), 与 mediaEvent 同语义;
 * 回调未注入时打 warn 降级 (事件丢失, 不把异常抛给调用方)。
 */

/** 平台事件回送函数类型 (由 EntryAbility 注入, 内部调 legado.platformEvent)。 */
export type PlatformEventCallback = (eventJson: string) => void;

// 事件回送回调 (EntryAbility onCreate 注册后注入; 未注入时事件丢弃 + warn 日志)
let eventCallback: PlatformEventCallback | null = null;

/**
 * 初始化平台事件回送 (由 EntryAbility.onCreate 调用)。
 *
 * @param callback 事件回送函数 (EntryAbility 注入 legado.platformEvent)
 */
export function initPlatformEvent(callback: PlatformEventCallback): void {
  eventCallback = callback;
}

/** HTTP 下载进度事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=httpProgress)。 */
export interface HttpProgressEvent {
  type: string;   // 'httpProgress'
  url: string;
  bytesReceived: number;
  totalBytes: number;
  isComplete: boolean;
}

/**
 * 推送 HTTP 下载进度事件。
 *
 * @param url 请求 url (Kotlin OhosDownloadProgressEvents 以去参后的 url 为 key 关联监听器)
 * @param bytesReceived 已接收字节数
 * @param totalBytes 总字节数 (<=0 表示未知: 分块传输无 Content-Length, 消费端按不确定进度处理)
 * @param isComplete 终态标记 (请求成功/失败/取消为 true; 之后不再有该 url 的事件,
 *   Kotlin 侧按 100% 回调并自动注销监听)
 */
export function emitHttpProgress(
  url: string, bytesReceived: number, totalBytes: number, isComplete: boolean
): void {
  if (eventCallback == null) {
    console.warn('[PlatformEvent] callback not set, httpProgress event dropped: ' + url);
    return;
  }
  const event: HttpProgressEvent = {
    type: 'httpProgress', url, bytesReceived, totalBytes, isComplete
  };
  try {
    eventCallback(JSON.stringify(event));
  } catch (e) {
    console.error('[PlatformEvent] httpProgress emit failed: ' + JSON.stringify(e) + ', url=' + url);
  }
}

/** 应用生命周期事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=lifecycle)。 */
export interface AppLifecycleEvent {
  type: string;  // 'lifecycle'
  event: string; // 'onForeground' | 'onBackground'
}

/**
 * 推送应用生命周期事件 (EntryAbility.onForeground/onBackground 调用)。
 *
 * @param event 'onForeground' (回到前台, 阅读页 onResume) | 'onBackground' (退到后台, 阅读页 onPause)
 */
export function emitAppLifecycle(event: 'onForeground' | 'onBackground'): void {
  if (eventCallback == null) {
    console.warn('[PlatformEvent] callback not set, lifecycle event dropped: ' + event);
    return;
  }
  const payload: AppLifecycleEvent = { type: 'lifecycle', event };
  try {
    eventCallback(JSON.stringify(payload));
  } catch (e) {
    console.error('[PlatformEvent] lifecycle emit failed: ' + JSON.stringify(e) + ', event=' + event);
  }
}
