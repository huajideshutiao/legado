import media from '@ohos.multimedia.media';
import fs from '@ohos.file.fs';

/**
 * ArkTS 侧媒体播放处理器 (KP8+)。
 *
 * 接收 Kotlin OhosNativeBridge.sendMediaCommand 通过 tsfn dispatch 过来的播放器命令,
 * 调用 @ohos.multimedia.media AVPlayer 执行真实音频播放,
 * AVPlayer 事件通过 legado.mediaEvent(eventJson) 回送给 Kotlin。
 *
 * # 多实例 (playerId)
 * 音频书 (audioBook) 与 HttpTTS 朗读 (httpTts) 需同时各持一个 AVPlayer, 故按 playerId
 * 维护 Map<playerId, PlayerSlot>。命令与事件均带 playerId; 缺省时按 DEFAULT_PLAYER_ID
 * 处理 (向后兼容旧协议 / ArkTS UI 页面直驱)。
 *
 * # JSON 协议 (与 HttpTtsPlayer.ohos.kt / OhosAudioPlayCommander.ohos.kt / OhosNativeBridge.kt 对齐)
 *
 * ## 命令 (Kotlin → ArkTS, fire-and-forget)
 * {
 *   "playerId": "<string?>",   // 缺省 = "default"
 *   "action": "setSource" | "setSourceUrl" | "play" | "pause" | "stop" |
 *             "seekTo" | "setSpeed" | "release",
 *   "path": "<string?>",       // setSource 时为本地文件路径
 *   "url": "<string?>",        // setSourceUrl 时为网络流地址
 *   "headers": <Record?>,      // setSourceUrl 时为请求头
 *   "position": <number?>,     // seekTo 时为跳转位置 (毫秒)
 *   "speed": <number?>         // setSpeed 时为浮点倍速
 * }
 *
 * ## 事件 (ArkTS → Kotlin, via legado.mediaEvent)
 * {
 *   "playerId": "<string>",    // 必带, 与命令的 playerId 对应
 *   "event": "onReady" | "onEndOfMedia" | "onError" | "onBufferingUpdate" |
 *            "onDuration" | "onPosition" | "onPlaying" | "onPaused",
 *   "message": "<string?>",    // onError 时含错误信息
 *   "percent": <number?>,      // onBufferingUpdate 时为缓冲百分比 (0-100)
 *   "duration": <number?>,     // onDuration 时为总时长 (毫秒)
 *   "position": <number?>      // onPosition 时为当前播放位置 (毫秒)
 * }
 *
 * # AVPlayer 状态机
 * idle → (设源) → initialized → (prepare) → prepared → (play) → playing ↔ paused
 *   → (stop) → stopped → (reset) → idle → ... → (release) → released
 *
 * 完整事件链:
 * - setSource/setSourceUrl 命令 → 设源 → prepare() → stateChange('prepared') → 发 onReady
 * - play 命令 → avPlayer.play() → stateChange('playing') → 发 onPlaying
 * - pause 命令 → avPlayer.pause() → stateChange('paused') → 发 onPaused
 * - 播放结束 → stateChange('completed') → 发 onEndOfMedia
 * - 错误 → error 事件 / stateChange('error') → 发 onError
 * - 时长更新 → durationUpdate 事件 → 发 onDuration
 * - 位置更新 → timeUpdate 事件 → 发 onPosition
 */

// Media 事件回调类型 (由 EntryAbility 注入, 内部调 legado.mediaEvent)
export type MediaEventCallback = (eventJson: string) => void;

/** 缺省 playerId: 无 playerId 字段的旧协议消息落到这里。 */
export const DEFAULT_PLAYER_ID: string = 'default';

/** 音频书播放实例 (Kotlin OhosAudioPlayCommander + ArkTS AudioPlay 页共用)。 */
export const PLAYER_ID_AUDIO_BOOK: string = 'audioBook';

/** HttpTTS 朗读播放实例 (Kotlin OhosHttpTtsPlayer 专用)。 */
export const PLAYER_ID_HTTP_TTS: string = 'httpTts';

/** 视频播放实例 (Kotlin OhosVideoPlayerController 专用)。 */
export const PLAYER_ID_VIDEO: string = 'videoBook';

/** 单个播放实例的状态 (AVPlayer + 其打开的本地文件 fd)。 */
class PlayerSlot {
  player: media.AVPlayer | null = null;
  fd: number | null = null;
}

// playerId → 播放实例
let players: Map<string, PlayerSlot> = new Map<string, PlayerSlot>();

// Media 事件回调 (由 EntryAbility 注入)
let eventCallback: MediaEventCallback | null = null;

// 视频画面 surfaceId (由 Index.ets 经 registerComposeInteropBuilder 的 XComponent.onLoad 注入;
// 先于 AVPlayer 创建时暂存, 创建 videoBook 播放器时再绑定)
let videoSurfaceId: string | null = null;

/** 本地事件监听项: listener + 关注的 playerId (null = 全收)。 */
class LocalListenerEntry {
  listener: (eventJson: string) => void;
  playerId: string | null;

  constructor(listener: (eventJson: string) => void, playerId: string | null) {
    this.listener = listener;
    this.playerId = playerId;
  }
}

// 本地 media 事件监听器 (ArkTS UI 页面订阅 AVPlayer 事件用于状态更新, 与 KMP 回调并行)
let localListeners: Array<LocalListenerEntry> = [];

/**
 * 注册本地 media 事件监听 (UI 页面 aboutToAppear 调用)。
 * 与 eventCallback (→ KMP) 并行触发, 互不影响。
 *
 * @param playerId 只接收该 playerId 的事件; 省略则接收全部 (旧行为)
 */
export function addMediaEventListener(listener: (eventJson: string) => void, playerId?: string): void {
  localListeners.push(new LocalListenerEntry(listener, playerId ?? null));
}

/** 注销本地 media 事件监听 (UI 页面 aboutToDisappear 调用)。 */
export function removeMediaEventListener(listener: (eventJson: string) => void): void {
  for (let i = localListeners.length - 1; i >= 0; i--) {
    if (localListeners[i].listener === listener) {
      localListeners.splice(i, 1);
    }
  }
}

// ===== 命令接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface MediaCommand {
  playerId?: string;
  action: string;
  path?: string;
  url?: string;
  headers?: Record<string, string>;
  position?: number;
  speed?: number;
}

interface MediaEventPayload {
  playerId?: string;
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
 * 绑定视频画面 surface (Index.ets 的 XComponent.onLoad 回调调用)。
 *
 * XComponent(type:'surface') 加载完成时把 surfaceId 交给 AVPlayer (player.surfaceId =
 * surfaceId) 才能显示画面 (否则视频只能出声)。surfaceId 可能先于 videoBook AVPlayer 创建
 * (Compose 先渲染 surface、后发 setSourceUrl), 故先暂存, 播放器创建时再绑定。
 *
 * @param surfaceId XComponent.onLoad 的 surfaceId (目标平台 surface)
 */
export function setVideoSurfaceId(surfaceId: string): void {
  videoSurfaceId = surfaceId;
  // 若 videoBook 播放器已存在则立即绑定
  const slot = players.get(PLAYER_ID_VIDEO);
  if (slot != null && slot.player != null) {
    try {
      slot.player.surfaceId = surfaceId;
      console.info('[MediaBridge] video surface bound: id=' + surfaceId);
    } catch (e) {
      console.error('[MediaBridge] bind video surface failed: ' + (e as Error).message);
    }
  } else {
    console.info('[MediaBridge] video surfaceId stored (player not ready yet): ' + surfaceId);
  }
}

/**
 * 处理来自 Kotlin 的 media 命令 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param commandJson 命令 JSON 字符串 (含 playerId/action/path/url/position)
 */
export function handleMediaCommand(commandJson: string): void {
  let cmd: MediaCommand;
  try {
    cmd = JSON.parse(commandJson) as MediaCommand;
  } catch (e) {
    console.error('[MediaBridge] parse command failed: ' + commandJson);
    return;
  }

  const id: string = cmd.playerId ?? DEFAULT_PLAYER_ID;

  switch (cmd.action) {
    case 'setSource':
      handleSetSource(id, cmd.path ?? '').catch((err: Error) => {
        emitEvent(id, { event: 'onError', message: 'setSource failed: ' + (err.message ?? String(err)) });
      });
      break;
    case 'setSourceUrl':
      handleSetSourceUrl(id, cmd.url ?? '', cmd.headers).catch((err: Error) => {
        emitEvent(id, { event: 'onError', message: 'setSourceUrl failed: ' + (err.message ?? String(err)) });
      });
      break;
    case 'play':
      handlePlay(id);
      break;
    case 'pause':
      handlePause(id);
      break;
    case 'stop':
      handleStop(id);
      break;
    case 'seekTo':
      handleSeekTo(id, cmd.position ?? 0);
      break;
    case 'setSpeed':
      handleSetSpeed(id, cmd.speed ?? 1);
      break;
    case 'release':
      handleRelease(id);
      break;
    default:
      console.warn('[MediaBridge] unknown action: ' + cmd.action);
  }
}

// ===== setSource: 打开文件 → 创建 AVPlayer → 设源 → prepare =====
async function handleSetSource(id: string, path: string): Promise<void> {
  if (path.length === 0) {
    emitEvent(id, { event: 'onError', message: 'setSource: path is empty' });
    return;
  }

  // 释放旧 player + 关闭旧 fd
  await releasePlayer(id);

  const slot = new PlayerSlot();
  players.set(id, slot);

  // 打开本地文件
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  slot.fd = file.fd;

  // 创建 AVPlayer 并注册事件监听
  const player = await media.createAVPlayer();
  slot.player = player;
  registerPlayerEvents(id, player);
  bindVideoSurfaceIfNeeded(id, player);

  // 设源: 用 fd:// 协议指向本地文件
  player.url = 'fd://' + file.fd;
  console.info('[MediaBridge] setSource: id=' + id + ' path=' + path + ' fd=' + file.fd);

  // 异步 prepare, 就绪后 stateChange('prepared') 触发 onReady 事件
  await player.prepare();
}

// ===== setSourceUrl: 网络流播 (免预下载), 带请求头 (API 12+) =====
async function handleSetSourceUrl(id: string, url: string, headers?: Record<string, string>): Promise<void> {
  if (url.length === 0) {
    emitEvent(id, { event: 'onError', message: 'setSourceUrl: url is empty' });
    return;
  }

  await releasePlayer(id);

  const slot = new PlayerSlot();
  players.set(id, slot);

  const player = await media.createAVPlayer();
  slot.player = player;
  registerPlayerEvents(id, player);
  bindVideoSurfaceIfNeeded(id, player);

  // MediaSource 支持自定义请求头 (Referer/User-Agent/Cookie), 直接设 url 属性做不到
  const source: media.MediaSource = media.createMediaSourceWithUrl(url, headers);
  await player.setMediaSource(source);
  console.info('[MediaBridge] setSourceUrl: id=' + id + ' url=' + url);

  // setMediaSource 完成即进入 initialized, 仍需 prepare 才 ready
  await player.prepare();
}

// ===== play =====
function handlePlay(id: string): void {
  const player = playerOf(id);
  if (player != null) {
    player.play().catch((err: Error) => {
      emitEvent(id, { event: 'onError', message: 'play failed: ' + (err.message ?? String(err)) });
    });
  } else {
    console.warn('[MediaBridge] play: avPlayer is null, id=' + id);
  }
}

// ===== pause =====
function handlePause(id: string): void {
  const player = playerOf(id);
  if (player != null) {
    player.pause().catch((err: Error) => {
      emitEvent(id, { event: 'onError', message: 'pause failed: ' + (err.message ?? String(err)) });
    });
  } else {
    console.warn('[MediaBridge] pause: avPlayer is null, id=' + id);
  }
}

// ===== stop =====
function handleStop(id: string): void {
  const player = playerOf(id);
  if (player != null) {
    player.stop().catch((err: Error) => {
      emitEvent(id, { event: 'onError', message: 'stop failed: ' + (err.message ?? String(err)) });
    });
  } else {
    console.warn('[MediaBridge] stop: avPlayer is null, id=' + id);
  }
}

// ===== seekTo =====
function handleSeekTo(id: string, position: number): void {
  const player = playerOf(id);
  if (player != null) {
    player.seek(position);
  } else {
    console.warn('[MediaBridge] seekTo: avPlayer is null, id=' + id);
  }
}

// ===== setSpeed (倍速, 浮点 → PlaybackSpeed 枚举档位) =====
function handleSetSpeed(id: string, speed: number): void {
  const player = playerOf(id);
  if (player != null) {
    player.setSpeed(mapSpeed(speed));
  } else {
    console.warn('[MediaBridge] setSpeed: avPlayer is null, id=' + id);
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
function handleRelease(id: string): void {
  releasePlayer(id).catch((err: Error) => {
    console.error('[MediaBridge] release failed: ' + (err.message ?? String(err)));
  });
}

// ===== AVPlayer 事件注册 =====
function registerPlayerEvents(id: string, player: media.AVPlayer): void {
  // 状态变化 → 转换为 Kotlin 侧事件
  player.on('stateChange', (state: string, reason: media.StateChangeReason) => {
    console.info('[MediaBridge] stateChange: id=' + id + ' state=' + state + ' reason=' + reason);
    switch (state) {
      case 'prepared':
        // 准备就绪 → 发 onReady (Kotlin 侧据此触发段落推进)
        emitEvent(id, { event: 'onReady' });
        break;
      case 'playing':
        emitEvent(id, { event: 'onPlaying' });
        break;
      case 'paused':
        emitEvent(id, { event: 'onPaused' });
        break;
      case 'completed':
        // 播放结束 → 发 onEndOfMedia (Kotlin 侧据此推进到下一段)
        emitEvent(id, { event: 'onEndOfMedia' });
        break;
      case 'error':
        emitEvent(id, { event: 'onError', message: 'AVPlayer entered error state, reason=' + reason });
        break;
      default:
        // idle / initialized / stopped / released 等状态不转发
        break;
    }
  });

  // 错误事件 → 发 onError
  player.on('error', (err: Error) => {
    emitEvent(id, { event: 'onError', message: err.message ?? String(err) });
  });

  // 时长更新 → 发 onDuration (供 Kotlin 缓存 duration getter)
  player.on('durationUpdate', (duration: number) => {
    emitEvent(id, { event: 'onDuration', duration: duration });
  });

  // 播放位置更新 → 发 onPosition (供 Kotlin 缓存 currentPosition getter)
  player.on('timeUpdate', (time: number) => {
    emitEvent(id, { event: 'onPosition', position: time });
  });

  // 缓冲更新 → 发 onBufferingUpdate
  // 回调签名为 (infoType, value): BufferingInfoType 是枚举, 百分比在 value 中,
  // BUFFERING_PERCENT 档携带 0-100 百分比; BUFFERING_START/END 是起止标记, 显式映射
  // 为 0/100, 避免 Kotlin 侧停在上一轮的旧百分比 (转圈常亮/常灭)
  player.on('bufferingUpdate', (infoType: media.BufferingInfoType, value: number) => {
    if (infoType === media.BufferingInfoType.BUFFERING_PERCENT) {
      emitEvent(id, { event: 'onBufferingUpdate', percent: value });
    } else if (infoType === media.BufferingInfoType.BUFFERING_START) {
      emitEvent(id, { event: 'onBufferingUpdate', percent: 0 });
    } else if (infoType === media.BufferingInfoType.BUFFERING_END) {
      emitEvent(id, { event: 'onBufferingUpdate', percent: 100 });
    }
  });
}

// ===== 内部工具函数 =====

/** 取指定 playerId 的 AVPlayer (不存在返回 null)。 */
function playerOf(id: string): media.AVPlayer | null {
  const slot = players.get(id);
  return slot != null ? slot.player : null;
}

/** videoBook 播放器创建后若已有暂存 surfaceId 则立即绑定 (视频画面显示)。 */
function bindVideoSurfaceIfNeeded(id: string, player: media.AVPlayer): void {
  if (id !== PLAYER_ID_VIDEO) return;
  if (videoSurfaceId == null) return;
  try {
    player.surfaceId = videoSurfaceId;
    console.info('[MediaBridge] video surface bound on player create: id=' + videoSurfaceId);
  } catch (e) {
    console.error('[MediaBridge] bind video surface on create failed: ' + (e as Error).message);
  }
}

/** 释放指定 playerId 的 AVPlayer 并关闭文件 fd */
async function releasePlayer(id: string): Promise<void> {
  const slot = players.get(id);
  if (slot == null) {
    return;
  }
  players.delete(id);
  if (slot.player != null) {
    try {
      slot.player.release();
    } catch (e) {
      console.error('[MediaBridge] release AVPlayer error: ' + (e as Error).message);
    }
    slot.player = null;
  }
  if (slot.fd != null) {
    try {
      fs.closeSync(slot.fd);
    } catch (e) {
      console.error('[MediaBridge] close fd error: ' + (e as Error).message);
    }
    slot.fd = null;
  }
}

/** 发送事件到 Kotlin (通过 EntryAbility 注入的 legado.mediaEvent 回调) + 本地 UI 监听器 */
function emitEvent(id: string, event: MediaEventPayload): void {
  event.playerId = id;
  const json = JSON.stringify(event);
  if (eventCallback != null) {
    eventCallback(json);
  } else {
    console.warn('[MediaBridge] eventCallback not set, event=' + event.event);
  }
  // 并行推送给本地 UI 监听器 (AudioPlay 页面据此更新进度/状态)
  for (let i = 0; i < localListeners.length; i++) {
    const entry = localListeners[i];
    if (entry.playerId != null && entry.playerId !== id) {
      continue;
    }
    try {
      entry.listener(json);
    } catch (e) {
      console.error('[MediaBridge] local listener error: ' + (e as Error).message);
    }
  }
}
