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
 * - 系统深浅色: { type: 'colorMode', isDark }
 * - 音频设备变化: { type: 'audioDeviceChange', disconnected }
 * - 音频焦点: { type: 'audioFocusChange', change: 'gain' | 'loss' | 'lossTransient' | 'lossTransientCanDuck' }
 * - 电话状态: { type: 'callStateChange', state: 'idle' | 'ringing' | 'offHook' }
 * - 系统对话框结果: { type: 'dialogResult', requestId, dialogResult } (系统对话框桥的响应侧)
 * - 通知按钮点击: { type: 'notificationAction', notificationId, action }
 *
 * 后加的三类经 [emitPlatformEvent] 走同一通道 (不再各建一套回送函数)。
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

/** 系统深浅色事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=colorMode)。 */
export interface ColorModeEvent {
  type: string;    // 'colorMode'
  isDark: boolean;
}

/**
 * 推送系统深浅色 (EntryAbility.onCreate 取初值 / onConfigurationUpdate 变更时调用)。
 *
 * Kotlin/Native 侧没有 configuration API, 「主题模式=跟随系统」这一档只能由此通道供给
 * (Kotlin 侧落到 NativeSystemTheme.update, 变更时刷派生缓存 + emit RECREATE)。
 *
 * @param isDark 系统是否深色 (config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK)
 */
export function emitColorMode(isDark: boolean): void {
  if (eventCallback == null) {
    console.warn('[PlatformEvent] callback not set, colorMode event dropped: ' + isDark);
    return;
  }
  const payload: ColorModeEvent = { type: 'colorMode', isDark };
  try {
    eventCallback(JSON.stringify(payload));
  } catch (e) {
    console.error('[PlatformEvent] colorMode emit failed: ' + JSON.stringify(e));
  }
}

/** 系统对话框结果事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=dialogResult)。 */
export interface DialogResultEvent {
  type: string;         // 'dialogResult'
  requestId: number;    // 对应 Kotlin OhosNativeBridge.invokeDialogSync 的请求 ID
  dialogResult: string; // 对话框结果 JSON ({ok,index} / {ok,confirmed} / {ok,cancelled} / {ok:false,error})
}

/**
 * 推送系统对话框结果 (SysDialogBridgeHandler 拿到用户选择后, 经 EntryAbility 的 window callback 回送)。
 *
 * 系统对话框的请求侧复用 window tsfn (OhosNativeBridge.WindowCommand action="showDialog"),
 * 结果侧走本统一通道 (Kotlin OhosPlatformEventChannel type="dialogResult" →
 * OhosNativeBridge.onDialogResult), 唤醒 invokeDialogSync 阻塞中的请求方。
 *
 * @param requestId 请求 ID (与 Kotlin 生成的一致)
 * @param resultJson 对话框结果 JSON (由 SysDialogBridgeHandler 产出)
 */
export function emitDialogResult(requestId: number, resultJson: string): void {
  if (eventCallback == null) {
    console.warn('[PlatformEvent] callback not set, dialogResult event dropped: ' + requestId);
    return;
  }
  const payload: DialogResultEvent = { type: 'dialogResult', requestId, dialogResult: resultJson };
  try {
    eventCallback(JSON.stringify(payload));
  } catch (e) {
    console.error('[PlatformEvent] dialogResult emit failed: ' + JSON.stringify(e) + ', requestId=' + requestId);
  }
}

/** 音频设备变化事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=audioDeviceChange)。 */
export interface AudioDeviceChangeEvent {
  type: string;          // 'audioDeviceChange'
  disconnected: boolean;
}

/** 音频焦点事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=audioFocusChange)。 */
export interface AudioFocusChangeEvent {
  type: string;          // 'audioFocusChange'
  change: string;        // 'gain' | 'loss' | 'lossTransient' | 'lossTransientCanDuck'
}

/** 电话状态事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=callStateChange)。 */
export interface PhoneCallStateEvent {
  type: string;          // 'callStateChange'
  state: string;         // 'idle' | 'ringing' | 'offHook'
}

/** 网络可用事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=networkAvailable)。 */
export interface NetworkAvailableEvent {
  type: string;          // 'networkAvailable'
}

/** 通知按钮点击事件载荷 (与 Kotlin OhosPlatformEventPayload 对齐, type=notificationAction)。 */
export interface NotificationActionEvent {
  type: string;           // 'notificationAction'
  notificationId: number; // 通知 id (与 Kotlin NotificationId 常量、ArkTS NOTIFICATION_ROUTES 同一张 id 表)
  action: string;         // 按钮动作标识 (原版 addAction 的 pendingIntent action, 如 'stop')
}

/** 后加的平台事件载荷集合 (共用 [emitPlatformEvent])。 */
export type ExtraPlatformEvent =
  AudioDeviceChangeEvent | AudioFocusChangeEvent | PhoneCallStateEvent | NetworkAvailableEvent |
  NotificationActionEvent;

/**
 * 推送一条后加类型的平台事件 (媒体设备变化/音频焦点/电话状态/网络可用/通知按钮点击)。
 *
 * 为什么不让调用方各自建模块级回送函数: 事件回送回调只有本模块持有, 各功能模块
 * (MediaBridgeHandler 的音频设备断开/音频打断、SystemEventSources 的电话与网络) 只需
 * 构造自己的载荷对象, 序列化与降级日志统一在这里, 与 [emitHttpProgress] /
 * [emitAppLifecycle] / [emitColorMode] 三个专用函数同语义。
 *
 * 协议: Kotlin 侧 OhosPlatformEventPayload 按 `type` 分发, 未知字段与未知 type 均被忽略
 * (KS_JSON ignoreUnknownKeys), 故新增事件类型向后兼容。
 *
 * @param payload 事件载荷对象 (必带 type 字段)
 */
export function emitPlatformEvent(payload: ExtraPlatformEvent): void {
  if (eventCallback == null) {
    console.warn('[PlatformEvent] callback not set, event dropped: ' + JSON.stringify(payload));
    return;
  }
  try {
    eventCallback(JSON.stringify(payload));
  } catch (e) {
    console.error('[PlatformEvent] emit failed: ' + JSON.stringify(e));
  }
}
