import observer from '@ohos.telephony.observer';
import call from '@ohos.telephony.call';
import connection from '@ohos.net.connection';
import {
  emitPlatformEvent,
  NetworkAvailableEvent,
  PhoneCallStateEvent,
} from '../bridge/PlatformEventBridge';

/**
 * 鸿蒙平台侧"系统事件源"注册: 来电状态与网络变化, 统一经 [emitPlatformEvent] 回送 Kotlin。
 *
 * 与 MediaBridgeHandler 的音频设备/音频打断事件同一条通道 (ArkTS → Kotlin 单向 JSON),
 * Kotlin 侧由 OhosPlatformEventChannel 按 `type` 分发。
 *
 * # 为何单独一个模块
 * 这两个事件不属于媒体播放器 (也不属于 napi 命令面), 但都要求"进程早期注册一次、全程在册";
 * 挂在 EntryAbility 里会让本就 17+ 个桥注册的 onCreate 再长两段, 也不便于单独看日志。
 *
 * # 线程
 * 系统回调在 ArkTS 主线程, [emitPlatformEvent] 同步序列化后回送, 与既有事件同语义。
 */

let installed: boolean = false;

/**
 * 安装来电状态与网络变化监听 (幂等; EntryAbility.onCreate 调用)。
 *
 * # 来电 (callStateChange)
 * 三态映射与 Android `TelephonyManager.CALL_STATE_*` 一一对应:
 * `CALL_STATE_IDLE → 'idle'`, `CALL_STATE_RINGING → 'ringing'`, 其余 → `'offHook'`。
 * Kotlin 侧的消费门槛 (`ignoreAudioFocus && pauseReadAloudWhilePhoneCalls`) 与恢复语义
 * 都在共享件里现读, 故这里不必按开关注册/注销。
 * SDK d.ts 中该事件未标 `@permission` (同文件其它 API 多带
 * `ohos.permission.GET_TELEPHONY_STATE`), 故不申请权限; 若实际抛错, 只记日志并保留
 * "无来电暂停" 的旧行为。
 *
 * # 网络 (netAvailable)
 * `@ohos.net.connection` 的 `netAvailable` 只在有网时回调, 与 Android
 * `NetworkCallback.onAvailable` 同语义 (不关心断开); Kotlin 侧据此重算 Web 服务地址。
 * 该事件在 SDK 中挂在 `NetConnection` 实例上 (不是模块级函数), 故先用
 * `createNetConnection()` 建一个实例再注册。
 */
export function installSystemEventSources(): void {
  if (installed) {
    return;
  }
  installed = true;
  installCallStateObserver();
  installNetworkObserver();
}

/** 注册来电状态监听 (见 [installSystemEventSources] 的来电段说明)。 */
function installCallStateObserver(): void {
  try {
    observer.on('callStateChange', (info: observer.CallStateInfo) => {
      let state: string;
      if (info.state === call.CallState.CALL_STATE_IDLE) {
        state = 'idle';
      } else if (info.state === call.CallState.CALL_STATE_RINGING) {
        state = 'ringing';
      } else {
        state = 'offHook';
      }
      const payload: PhoneCallStateEvent = { type: 'callStateChange', state: state };
      emitPlatformEvent(payload);
    });
    console.info('[SystemEvents] callStateChange listener installed');
  } catch (e) {
    console.error('[SystemEvents] install callStateChange failed: ' + JSON.stringify(e));
  }
}

/** 注册网络可用监听 (见 [installSystemEventSources] 的网络段说明)。 */
function installNetworkObserver(): void {
  try {
    // netAvailable 挂在 NetConnection 实例上 (SDK: `NetConnection.on(type: 'netAvailable', ...)`),
    // 不是模块级函数; 不传 netSpecifier 即监听默认网络。
    connection.createNetConnection().on('netAvailable', () => {
      const payload: NetworkAvailableEvent = { type: 'networkAvailable' };
      emitPlatformEvent(payload);
    });
    console.info('[SystemEvents] netAvailable listener installed');
  } catch (e) {
    console.error('[SystemEvents] install netAvailable failed: ' + JSON.stringify(e));
  }
}
