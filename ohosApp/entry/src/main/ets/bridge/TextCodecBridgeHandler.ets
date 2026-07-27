import util from '@ohos.util';
import buffer from '@ohos.buffer';

/**
 * ArkTS 侧文本编解码处理器 - TXT 分章 GB18030/Big5 编解码处理器。
 *
 * Kotlin/Native 侧无原生 ICU/字符集转换能力, 由 ArkTS 调 @ohos.util.TextDecoder/TextEncoder
 * 完成 GB18030/Big5 等非 UTF-8 字符集与字符串的互转, 完成后通过
 * legado.textCodecCallback(requestId, resultJson) 回送结果给 Kotlin。
 *
 * # JSON 协议 (与 OhosNativeBridge.kt 对齐)
 *
 * ## 请求 (Kotlin → ArkTS)
 * {
 *   "requestId": <number>,
 *   "action": "decode" | "encode",
 *   "payload": "<JSON string, action-specific>"
 * }
 *
 * ## decode payload
 * { "charset": "gb18030" | "big5" | ..., "data": "<base64>" }
 *
 * ## encode payload
 * { "charset": "gb18030" | "big5" | ..., "text": "<string>" }
 *
 * ## 响应 (ArkTS → Kotlin)
 * - decode 成功: `{ ok: true, text: "<解码后的字符串>" }`
 * - encode 成功: `{ ok: true, data: "<base64>" }`
 * - 失败:       `{ ok: false, error: "<string>" }`
 */

// TextCodec callback 类型 (由 EntryAbility 注入, 内部调 legado.textCodecCallback)
export type TextCodecCallback = (requestId: number, resultJson: string) => void;

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface TextCodecBridgeRequest {
  requestId: number;
  action: string;
  payload: string;
}

interface TextCodecResponse {
  ok: boolean;
  text?: string;
  data?: string;
  error?: string;
}

interface DecodePayload {
  charset: string;
  data: string;
}

interface EncodePayload {
  charset: string;
  text: string;
}

// TextDecoder 按 charset 缓存, 避免每次请求重建 (util.TextDecoder.create 有一定开销)
const decoderCache = new Map<string, util.TextDecoder>();

function getDecoder(charset: string): util.TextDecoder {
  let decoder = decoderCache.get(charset);
  if (decoder == null) {
    decoder = util.TextDecoder.create(charset, { fatal: false, ignoreBOM: false });
    decoderCache.set(charset, decoder);
  }
  return decoder;
}

/**
 * 处理来自 Kotlin 的文本编解码请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.textCodecCallback)
 */
export function handleTextCodecRequest(requestJson: string, callback: TextCodecCallback): void {
  let req: TextCodecBridgeRequest;
  try {
    req = JSON.parse(requestJson) as TextCodecBridgeRequest;
  } catch (e) {
    console.error('[TextCodecBridge] parse request failed: ' + requestJson);
    return;
  }

  let resp: TextCodecResponse;
  try {
    switch (req.action) {
      case 'decode':
        resp = handleDecode(req.payload);
        break;
      case 'encode':
        resp = handleEncode(req.payload);
        break;
      default:
        resp = { ok: false, error: 'unknown action: ' + req.action };
    }
  } catch (e) {
    const err = e as Error;
    resp = { ok: false, error: err.message ?? String(e) };
  }
  callback(req.requestId, JSON.stringify(resp));
}

// ===== decode: base64 → bytes → 按 charset 解码为字符串 =====
function handleDecode(payload: string): TextCodecResponse {
  const p = JSON.parse(payload) as DecodePayload;
  const buf = buffer.from(p.data, 'base64');
  const bytes = new Uint8Array(buf.buffer, buf.byteOffset, buf.length);
  const text = getDecoder(p.charset).decodeToString(bytes, { stream: false });
  return { ok: true, text };
}

// ===== encode: 字符串 → 按 charset 编码为 bytes → base64 =====
function handleEncode(payload: string): TextCodecResponse {
  const p = JSON.parse(payload) as EncodePayload;
  const encoder = new util.TextEncoder(p.charset);
  const bytes = encoder.encodeInto(p.text);
  const data = buffer.from(bytes.buffer, bytes.byteOffset, bytes.length).toString('base64');
  return { ok: true, data };
}
