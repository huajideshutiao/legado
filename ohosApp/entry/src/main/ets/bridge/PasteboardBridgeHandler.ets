import pasteboard from '@ohos.pasteboard';
import { BusinessError } from '@ohos.base';
import { BridgeRequest, BridgeResponse, dispatchBridgeRequest } from './BridgeDispatch';

/**
 * ArkTS 侧剪贴板处理器。
 *
 * 接收 Kotlin OhosNativeBridge.invokePasteboardSync 通过 tsfn dispatch 过来的剪贴板请求,
 * 调用 @ohos.pasteboard.getSystemPasteboard() 读写纯文本,
 * 完成后通过 legado.pasteboardCallback(requestId, resultJson) 回送结果给 Kotlin。
 *
 * # JSON 协议 (与 OhosPlatformOps.ohos.kt / OhosNativeBridge.kt 对齐)
 *
 * ## 请求 (Kotlin → ArkTS)
 * {
 *   "requestId": <number>,
 *   "action": "read" | "write",
 *   "payload": "<JSON string, action-specific>"
 * }
 *
 * ## write payload
 * { "text": "<string>" }
 *
 * ## read payload
 * {}  // 无参数
 *
 * ## 响应 (ArkTS → Kotlin)
 * - read 成功:  `{ ok: true, text: "<剪贴板纯文本, 无内容为空串>" }`
 * - write 成功: `{ ok: true }`
 * - 失败:       `{ ok: false, error: "<string>" }`
 *
 * # 空剪贴板
 * getData() 在剪贴板为空时抛 BusinessError (12900004 等), 统一返回 `{ ok: true, text: '' }`,
 * 由 Kotlin 侧把空串归一为 null (与 iOS UIPasteboard.string 为 nil 语义对齐)。
 */

// Pasteboard callback 类型 (由 EntryAbility 注入, 内部调 legado.pasteboardCallback)
export type PasteboardCallback = (requestId: number, resultJson: string) => void;

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface PasteboardResponse extends BridgeResponse {
  text?: string;
}

interface PasteboardWritePayload {
  text: string;
}

/**
 * 处理来自 Kotlin 的剪贴板请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.pasteboardCallback)
 */
export function handlePasteboardRequest(requestJson: string, callback: PasteboardCallback): void {
  dispatchBridgeRequest(requestJson, 'PasteboardBridge', handleAction, callback);
}

// 根据 action 分发到具体处理函数
async function handleAction(req: BridgeRequest): Promise<PasteboardResponse> {
  switch (req.action) {
    case 'read': return await handleRead();
    case 'write': return await handleWrite(req.payload ?? '');
    default: return { ok: false, error: 'unknown action: ' + req.action };
  }
}

// ===== read: getSystemPasteboard().getData() → getPrimaryText() =====
async function handleRead(): Promise<PasteboardResponse> {
  try {
    const systemPasteboard = pasteboard.getSystemPasteboard();
    const data: pasteboard.PasteData = await systemPasteboard.getData();
    const count = data.getRecordCount();
    if (count === 0) {
      return { ok: true, text: '' };
    }
    // getPrimaryText 仅在首条记录为 PLAINTEXT 时有值, 否则回退遍历取首个纯文本记录
    const primary = data.getPrimaryText();
    if (primary != null && primary.length > 0) {
      return { ok: true, text: primary };
    }
    for (let i = 0; i < count; i++) {
      const record = data.getRecord(i);
      if (record.mimeType === pasteboard.MIMETYPE_TEXT_PLAIN && record.plainText != null) {
        return { ok: true, text: record.plainText };
      }
    }
    return { ok: true, text: '' };
  } catch (e) {
    // 剪贴板为空 / 无读取权限时 getData 抛 BusinessError, 归一为空文本而非错误
    const err = e as BusinessError;
    console.warn('[PasteboardBridge] getData failed: ' + (err.message ?? String(e)));
    return { ok: true, text: '' };
  }
}

// ===== write: createPlainTextData → setData =====
async function handleWrite(payload: string): Promise<PasteboardResponse> {
  let p: PasteboardWritePayload;
  try {
    p = JSON.parse(payload) as PasteboardWritePayload;
  } catch (e) {
    return { ok: false, error: 'parse write payload failed' };
  }
  try {
    const pasteData = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, p.text);
    const systemPasteboard = pasteboard.getSystemPasteboard();
    await systemPasteboard.setData(pasteData);
    return { ok: true };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}
