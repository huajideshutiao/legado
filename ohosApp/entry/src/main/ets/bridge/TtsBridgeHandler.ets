import textToSpeech from '@ohos.textToSpeech';

/**
 * ArkTS 侧 TTS 桥接处理器。
 *
 * 接收 Kotlin OhosNativeBridge.sendTtsCommand 通过 tsfn dispatch 过来的 TTS 命令,
 * 调用 @ohos.textToSpeech 执行真实语音合成,
 * TTS 事件通过 legado.ttsEvent(eventJson) 回送给 Kotlin。
 *
 * # JSON 协议 (与 OhosSystemTtsEngine.kt / OhosNativeBridge.kt TtsCommand 对齐)
 *
 * ## 命令 (Kotlin → ArkTS, fire-and-forget)
 * {
 *   "action": "createEngine" | "speak" | "pause" | "resume" | "stop" | "shutdown",
 *   "text": "<string?>",          // speak 时为待合成文本
 *   "utteranceId": "<string?>",   // speak 时为任务标识 (作为 requestId)
 *   "rate": <number?>,            // speak 时为语速 (0.5-2.0)
 *   "lang": "<string?>"           // createEngine 时为语种 (如 "zh-CN")
 * }
 *
 * ## 事件 (ArkTS → Kotlin, via legado.ttsEvent)
 * {
 *   "event": "onStart" | "onComplete" | "onStop" | "onError",
 *   "utteranceId": "<string?>",   // 对应 speak 时的 utteranceId
 *   "message": "<string?>",       // onError 时含错误信息
 *   "errorCode": <number?>        // onError 时含错误码
 * }
 *
 * # @ohos.textToSpeech API
 * - createEngine(CreateEngineConfig): Promise<TextToSpeechEngine>
 * - engine.setListener(SpeakListener): 设置 onStart/onComplete/onStop/onError 回调
 * - engine.speak(text, SpeakParams): 合成并播报 (SpeakParams.extraParams.speed 控制语速)
 * - engine.stop(): 停止当前播报
 * - engine.shutdown(): 释放引擎资源
 *
 * # 注意
 * @ohos.textToSpeech 无 pause/resume API, pause/resume 命令仅打日志,
 * 由 Kotlin 侧标记状态 + ReadAloudController 重播当前段落。
 */

// TTS 事件回调类型 (由 EntryAbility 注入, 内部调 legado.ttsEvent)
export type TtsEventCallback = (eventJson: string) => void;

// 当前 TTS 引擎实例 (同一时间仅一个引擎活跃, 与 Kotlin 侧单 listener 模型一致)
let ttsEngine: textToSpeech.TextToSpeechEngine | null = null;

// 引擎创建 Promise (speak 命令到达时若引擎未就绪, 等待此 Promise)
let engineCreatePromise: Promise<void> | null = null;

// TTS 事件回调 (由 EntryAbility 注入)
let eventCallback: TtsEventCallback | null = null;

// 当前语言和语速 (createEngine 时设置, speak 时使用)
let currentLang: string = 'zh-CN';
let currentRate: number = 1.0;

// ===== 命令接口定义 (与 Kotlin 侧 TtsCommand 对齐) =====

interface TtsCommand {
  action: string;
  text?: string;
  utteranceId?: string;
  rate?: number;
  lang?: string;
}

interface TtsEventPayload {
  event: string;
  utteranceId?: string;
  message?: string;
  errorCode?: number;
}

/**
 * 初始化 TTS 桥接 (由 EntryAbility.onCreate 调用, 注入事件回送函数)。
 *
 * @param callback 事件回送函数 (EntryAbility 注入 legado.ttsEvent)
 */
export function initTtsBridge(callback: TtsEventCallback): void {
  eventCallback = callback;
}

/**
 * 处理来自 Kotlin 的 TTS 命令 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param commandJson 命令 JSON 字符串 (含 action/text/utteranceId/rate/lang)
 */
export function handleTtsCommand(commandJson: string): void {
  let cmd: TtsCommand;
  try {
    cmd = JSON.parse(commandJson) as TtsCommand;
  } catch (e) {
    console.error('[TtsBridge] parse command failed: ' + commandJson);
    return;
  }

  switch (cmd.action) {
    case 'createEngine':
      handleCreateEngine(cmd.lang ?? 'zh-CN', cmd.rate ?? 1.0).catch((err: Error) => {
        console.error('[TtsBridge] createEngine error: ' + (err.message ?? String(err)));
      });
      break;
    case 'speak':
      handleSpeak(cmd.text ?? '', cmd.utteranceId ?? '', cmd.rate ?? currentRate).catch((err: Error) => {
        emitEvent({ event: 'onError', utteranceId: cmd.utteranceId ?? '', message: 'speak failed: ' + (err.message ?? String(err)) });
      });
      break;
    case 'pause':
      handlePause();
      break;
    case 'resume':
      handleResume();
      break;
    case 'stop':
      handleStop();
      break;
    case 'shutdown':
      handleShutdown();
      break;
    default:
      console.warn('[TtsBridge] unknown action: ' + cmd.action);
  }
}

// ===== createEngine: 创建引擎 + 设置监听 =====

async function handleCreateEngine(lang: string, rate: number): Promise<void> {
  currentLang = lang;
  currentRate = rate;

  // 释放旧引擎
  await shutdownEngine();

  engineCreatePromise = doCreateEngine(lang);
  await engineCreatePromise;
  engineCreatePromise = null;
}

async function doCreateEngine(lang: string): Promise<void> {
  try {
    ttsEngine = await textToSpeech.createEngine({
      language: lang,
      person: 0,
      online: 1,
      extraParams: {}
    });
    registerTtsListener(ttsEngine);
    console.info('[TtsBridge] engine created: lang=' + lang);
  } catch (e) {
    console.error('[TtsBridge] createEngine failed: ' + (e as Error).message);
    emitEvent({ event: 'onError', message: 'createEngine failed: ' + (e as Error).message });
  }
}

// ===== speak: 合成并播报 =====

async function handleSpeak(text: string, utteranceId: string, rate: number): Promise<void> {
  currentRate = rate;

  // 等待引擎创建完成 (如有进行中的 createEngine)
  if (engineCreatePromise != null) {
    await engineCreatePromise;
    engineCreatePromise = null;
  }

  if (ttsEngine == null) {
    // 引擎未就绪, 尝试创建
    console.warn('[TtsBridge] speak: engine not ready, creating...');
    await doCreateEngine(currentLang);
    if (ttsEngine == null) {
      emitEvent({ event: 'onError', utteranceId, message: 'engine not ready' });
      return;
    }
  }

  const speakParams: textToSpeech.SpeakParams = {
    requestId: utteranceId,
    extraParams: {
      queueMode: 0,
      speed: rate,
      volume: 1,
      pitch: 1,
      languageContext: currentLang,
      playType: 1,
    }
  };

  console.info('[TtsBridge] speak: utteranceId=' + utteranceId + ' text.len=' + text.length + ' rate=' + rate);
  await ttsEngine.speak(text, speakParams);
}

// ===== pause / resume =====

// @ohos.textToSpeech 无 pause/resume API, 由 Kotlin 侧标记状态 + 控制器重播
function handlePause(): void {
  console.warn('[TtsBridge] pause: not supported by @ohos.textToSpeech');
}

function handleResume(): void {
  console.warn('[TtsBridge] resume: not supported by @ohos.textToSpeech');
}

// ===== stop =====

function handleStop(): void {
  if (ttsEngine != null) {
    try {
      ttsEngine.stop();
      console.info('[TtsBridge] stop');
    } catch (e) {
      console.error('[TtsBridge] stop failed: ' + (e as Error).message);
    }
  } else {
    console.warn('[TtsBridge] stop: engine is null');
  }
}

// ===== shutdown =====

function handleShutdown(): void {
  shutdownEngine().catch((err: Error) => {
    console.error('[TtsBridge] shutdown failed: ' + (err.message ?? String(err)));
  });
}

// ===== TTS 引擎事件监听 (onStart/onComplete/onStop/onError → legado.ttsEvent) =====

function registerTtsListener(engine: textToSpeech.TextToSpeechEngine): void {
  engine.setListener({
    onStart(requestId: string, response: textToSpeech.StartResponse) {
      console.info('[TtsBridge] onStart: requestId=' + requestId);
      emitEvent({ event: 'onStart', utteranceId: requestId });
    },
    onComplete(requestId: string, response: textToSpeech.CompleteResponse) {
      console.info('[TtsBridge] onComplete: requestId=' + requestId);
      emitEvent({ event: 'onComplete', utteranceId: requestId });
    },
    onStop(requestId: string, response: textToSpeech.StopResponse) {
      console.info('[TtsBridge] onStop: requestId=' + requestId);
      emitEvent({ event: 'onStop', utteranceId: requestId });
    },
    onData(requestId: string, audio: ArrayBuffer, response: textToSpeech.SynthesisResponse) {
      // 不处理音频流数据
    },
    onError(requestId: string, errorCode: number, errorMessage: string) {
      console.error('[TtsBridge] onError: requestId=' + requestId + ' code=' + errorCode + ' msg=' + errorMessage);
      emitEvent({ event: 'onError', utteranceId: requestId, message: errorMessage, errorCode: errorCode });
    }
  });
}

// ===== 内部工具函数 =====

/** 释放当前 TTS 引擎 */
async function shutdownEngine(): Promise<void> {
  if (ttsEngine != null) {
    try {
      ttsEngine.shutdown();
    } catch (e) {
      console.error('[TtsBridge] shutdown engine error: ' + (e as Error).message);
    }
    ttsEngine = null;
  }
  engineCreatePromise = null;
}

/** 发送事件到 Kotlin (通过 EntryAbility 注入的 legado.ttsEvent 回调) */
function emitEvent(event: TtsEventPayload): void {
  if (eventCallback != null) {
    eventCallback(JSON.stringify(event));
  } else {
    console.warn('[TtsBridge] eventCallback not set, event=' + event.event);
  }
}
