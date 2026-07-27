import media from '@ohos.multimedia.media';
import fs from '@ohos.file.fs';

/**
 * ArkTS 侧媒体播放处理器 (KP8+)。
 *
 * 接收 Kotlin OhosNativeBridge.sendMediaCommand 通过 tsfn dispatch 过来的播放器命令,
 * 调用 @ohos.multimedia.media AVPlayer 执行真实音频播放,
 * AVPlayer 事件通过 legado.mediaEvent(eventJson) 回送给 Kotlin。
 *
 * # JSON 协议 (与 HttpTtsPlayer.ohos.kt / OhosNativeBridge.kt 对齐)
 *
 * ## 命令 (Kotlin → ArkTS, fire-and-forget)
 * {
 *   "action": "setSource" | "play" | "pause" | "stop" | "seekTo" | "release",
 *   "path": "<string?>",       // setSource 时为本地文件路径
 *   "position": <number?>      // seekTo 时为跳转位置 (毫秒)
 * }
 *
 * ## 事件 (ArkTS → Kotlin, via legado.mediaEvent)
 * {
 *   "event": "onReady" | "onEndOfMedia" | "onError" | "onBufferingUpdate" |
 *            "onDuration" | "onPosition" | "onPlaying" | "onPaused",
 *   "message": "<string?>",    // onError 时含错误信息
 *   "percent": <number?>,      // onBufferingUpdate 时为缓冲百分比 (0-100)
 *   "duration": <number?>,     // onDuration 时为总时长 (毫秒)
 *   "position": <number?>      // onPosition 时为当前播放位置 (毫秒)
 * }
 *
 * # AVPlayer 状态机
 * idle → (设 url) → initialized → (prepare) → prepared → (play) → playing ↔ paused
 *   → (stop) → stopped → (reset) → idle → ... → (release) → released
 *
 * 完整事件链:
 * - setSource 命令 → 打开文件 → 设 url → prepare() → stateChange('prepared') → 发 onReady
 * - play 命令 → avPlayer.play() → stateChange('playing') → 发 onPlaying
 * - pause 命令 → avPlayer.pause() → stateChange('paused') → 发 onPaused
 * - 播放结束 → stateChange('completed') → 发 onEndOfMedia
 * - 错误 → error 事件 / stateChange('error') → 发 onError
 * - 时长更新 → durationUpdate 事件 → 发 onDuration
 * - 位置更新 → timeUpdate 事件 → 发 onPosition
 */

// Media 事件回调类型 (由 EntryAbility 注入, 内部调 legado.mediaEvent)
export type MediaEventCallback = (eventJson: string) => void;

// 当前 AVPlayer 实例 (同一时间仅一个 player 活跃, 与 Kotlin 侧单 listener 模型一致)
let avPlayer: media.AVPlayer | null = null;

// 当前打开的文件 fd (setSource 时打开, release/reset 时关闭)
let currentFd: number | null = null;

// Media 事件回调 (由 EntryAbility 注入)
let eventCallback: MediaEventCallback | null = null;

// 本地 media 事件监听器 (ArkTS UI 页面订阅 AVPlayer 事件用于状态更新, 与 KMP 回调并行)
let localListeners: Array<(eventJson: string) => void> = [];

/**
 * 注册本地 media 事件监听 (UI 页面 aboutToAppear 调用)。
 * 与 eventCallback (→ KMP) 并行触发, 互不影响。
 */
export function addMediaEventListener(listener: (eventJson: string) => void): void {
  localListeners.push(listener);
}

/** 注销本地 media 事件监听 (UI 页面 aboutToDisappear 调用)。 */
export function removeMediaEventListener(listener: (eventJson: string) => void): void {
  const idx = localListeners.indexOf(listener);
  if (idx >= 0) {
    localListeners.splice(idx, 1);
  }
}

// ===== 命令接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface MediaCommand {
  action: string;
  path?: string;
  position?: number;
  speed?: number;
}

interface MediaEventPayload {
  event: string;
  message?: string;
  percent?: number;
  duration?: number;
  position?: number;
}

/**
 * 初始化 Media 桥接 (由 EntryAbility.onCreate 调用, 注入事件回送函数)。
 *
 * @param callback 事件回送函数 (EntryAbility 注入 legado.mediaEvent)
 */
export function initMediaBridge(callback: MediaEventCallback): void {
  eventCallback = callback;
}

/**
 * 处理来自 Kotlin 的 media 命令 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param commandJson 命令 JSON 字符串 (含 action/path/position)
 */
export function handleMediaCommand(commandJson: string): void {
  let cmd: MediaCommand;
  try {
    cmd = JSON.parse(commandJson) as MediaCommand;
  } catch (e) {
    console.error('[MediaBridge] parse command failed: ' + commandJson);
    return;
  }

  switch (cmd.action) {
    case 'setSource':
      handleSetSource(cmd.path ?? '').catch((err: Error) => {
        emitEvent({ event: 'onError', message: 'setSource failed: ' + (err.message ?? String(err)) });
      });
      break;
    case 'play':
      handlePlay();
      break;
    case 'pause':
      handlePause();
      break;
    case 'stop':
      handleStop();
      break;
    case 'seekTo':
      handleSeekTo(cmd.position ?? 0);
      break;
    case 'setSpeed':
      handleSetSpeed(cmd.speed ?? 1);
      break;
    case 'release':
      handleRelease();
      break;
    default:
      console.warn('[MediaBridge] unknown action: ' + cmd.action);
  }
}

// ===== setSource: 打开文件 → 创建 AVPlayer → 设源 → prepare =====
async function handleSetSource(path: string): Promise<void> {
  if (path.length === 0) {
    emitEvent({ event: 'onError', message: 'setSource: path is empty' });
    return;
  }

  // 释放旧 player + 关闭旧 fd
  await releasePlayer();

  // 打开本地文件
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  currentFd = file.fd;

  // 创建 AVPlayer 并注册事件监听
  avPlayer = await media.createAVPlayer();
  registerPlayerEvents(avPlayer);

  // 设源: 用 fd:// 协议指向本地文件
  avPlayer.url = 'fd://' + file.fd;
  console.info('[MediaBridge] setSource: path=' + path + ' fd=' + file.fd);

  // 异步 prepare, 就绪后 stateChange('prepared') 触发 onReady 事件
  await avPlayer.prepare();
}

// ===== play =====
function handlePlay(): void {
  if (avPlayer != null) {
    avPlayer.play().catch((err: Error) => {
      emitEvent({ event: 'onError', message: 'play failed: ' + (err.message ?? String(err)) });
    });
  } else {
    console.warn('[MediaBridge] play: avPlayer is null');
  }
}

// ===== pause =====
function handlePause(): void {
  if (avPlayer != null) {
    avPlayer.pause().catch((err: Error) => {
      emitEvent({ event: 'onError', message: 'pause failed: ' + (err.message ?? String(err)) });
    });
  } else {
    console.warn('[MediaBridge] pause: avPlayer is null');
  }
}

// ===== stop =====
function handleStop(): void {
  if (avPlayer != null) {
    avPlayer.stop().catch((err: Error) => {
      emitEvent({ event: 'onError', message: 'stop failed: ' + (err.message ?? String(err)) });
    });
  } else {
    console.warn('[MediaBridge] stop: avPlayer is null');
  }
}

// ===== seekTo =====
function handleSeekTo(position: number): void {
  if (avPlayer != null) {
    avPlayer.seek(position);
  } else {
    console.warn('[MediaBridge] seekTo: avPlayer is null');
  }
}

// ===== setSpeed (倍速, 浮点 → PlaybackSpeed 枚举档位) =====
function handleSetSpeed(speed: number): void {
  if (avPlayer != null) {
    avPlayer.setSpeed(mapSpeed(speed));
  } else {
    console.warn('[MediaBridge] setSpeed: avPlayer is null');
  }
}

// 浮点倍速 → 最近支持档位
function mapSpeed(rate: number): media.PlaybackSpeed {
  if (rate <= 0.875) return media.PlaybackSpeed.SPEED_FORWARD_0_75_X;
  if (rate <= 1.125) return media.PlaybackSpeed.SPEED_FORWARD_1_00_X;
  if (rate <= 1.5) return media.PlaybackSpeed.SPEED_FORWARD_1_25_X;
  if (rate <= 1.875) return media.PlaybackSpeed.SPEED_FORWARD_1_75_X;
  return media.PlaybackSpeed.SPEED_FORWARD_2_00_X;
}

// ===== release =====
function handleRelease(): void {
  releasePlayer().catch((err: Error) => {
    console.error('[MediaBridge] release failed: ' + (err.message ?? String(err)));
  });
}

// ===== AVPlayer 事件注册 =====
function registerPlayerEvents(player: media.AVPlayer): void {
  // 状态变化 → 转换为 Kotlin 侧事件
  player.on('stateChange', (state: string, reason: media.StateChangeReason) => {
    console.info('[MediaBridge] stateChange: state=' + state + ' reason=' + reason);
    switch (state) {
      case 'prepared':
        // 准备就绪 → 发 onReady (Kotlin 侧据此触发段落推进)
        emitEvent({ event: 'onReady' });
        break;
      case 'playing':
        emitEvent({ event: 'onPlaying' });
        break;
      case 'paused':
        emitEvent({ event: 'onPaused' });
        break;
      case 'completed':
        // 播放结束 → 发 onEndOfMedia (Kotlin 侧据此推进到下一段)
        emitEvent({ event: 'onEndOfMedia' });
        break;
      case 'error':
        emitEvent({ event: 'onError', message: 'AVPlayer entered error state, reason=' + reason });
        break;
      default:
        // idle / initialized / stopped / released 等状态不转发
        break;
    }
  });

  // 错误事件 → 发 onError
  player.on('error', (err: Error) => {
    emitEvent({ event: 'onError', message: err.message ?? String(err) });
  });

  // 时长更新 → 发 onDuration (供 Kotlin 缓存 duration getter)
  player.on('durationUpdate', (duration: number) => {
    emitEvent({ event: 'onDuration', duration: duration });
  });

  // 播放位置更新 → 发 onPosition (供 Kotlin 缓存 currentPosition getter)
  player.on('timeUpdate', (time: number) => {
    emitEvent({ event: 'onPosition', position: time });
  });

  // 缓冲更新 → 发 onBufferingUpdate
  player.on('bufferingUpdate', (info: media.BufferingInfoType) => {
    // BufferingInfoType 含 percent 字段, 表示缓冲百分比
    emitEvent({ event: 'onBufferingUpdate', percent: info.percent });
  });
}

// ===== 内部工具函数 =====

/** 释放当前 AVPlayer 并关闭文件 fd */
async function releasePlayer(): Promise<void> {
  if (avPlayer != null) {
    try {
      avPlayer.release();
    } catch (e) {
      console.error('[MediaBridge] release AVPlayer error: ' + (e as Error).message);
    }
    avPlayer = null;
  }
  if (currentFd != null) {
    try {
      fs.closeSync(currentFd);
    } catch (e) {
      console.error('[MediaBridge] close fd error: ' + (e as Error).message);
    }
    currentFd = null;
  }
}

/** 发送事件到 Kotlin (通过 EntryAbility 注入的 legado.mediaEvent 回调) + 本地 UI 监听器 */
function emitEvent(event: MediaEventPayload): void {
  const json = JSON.stringify(event);
  if (eventCallback != null) {
    eventCallback(json);
  } else {
    console.warn('[MediaBridge] eventCallback not set, event=' + event.event);
  }
  // 并行推送给本地 UI 监听器 (AudioPlay 页面据此更新进度/状态)
  for (let i = 0; i < localListeners.length; i++) {
    try {
      localListeners[i](json);
    } catch (e) {
      console.error('[MediaBridge] local listener error: ' + (e as Error).message);
    }
  }
}
