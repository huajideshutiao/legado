/**
 * ArkTS 桥接公共分发工具。
 *
 * 请求型/命令型 handler 的「JSON 解析 + 失败日志 + action 分发 + 结果回送」样板收敛
 * (对外行为/日志格式/错误响应结构与原各 handler 内联实现逐字一致):
 * - 请求型 (requestId 关联, 有响应): [dispatchBridgeRequest] (异步 handleAction) /
 *   [dispatchBridgeRequestSync] (同步 handleAction)
 * - 命令型 (fire-and-forget, 无响应): [parseBridgeCommand] (解析 + 失败日志,
 *   action 分发结构因 handler 而异, 留在各 handler 内)
 *
 * 混合协议 handler (Image/Http/WebView 的数据面裸参数 + 多参 callback) 结构特殊, 不走本工具。
 */

/** 请求型桥通用请求结构 (Kotlin → ArkTS; requestId 关联响应, payload 为 action 参数 JSON)。 */
export interface BridgeRequest {
  requestId: number;
  action: string;
  payload?: string;
}

/** 桥响应公共字段 (各 handler 的响应接口 extends 此接口; ok:false 时仅含 error)。 */
export interface BridgeResponse {
  ok: boolean;
  error?: string;
}

/** 结果回送函数 (由 EntryAbility 注入, 内部调 legado.xxxCallback)。 */
export type BridgeResultCallback = (requestId: number, resultJson: string) => void;

/**
 * 请求型分发 (异步): 解析请求 JSON → handleAction 按 action 分发 → 成功/失败响应回送。
 *
 * - 解析失败: 打 `[tag] parse request failed: <json>` 日志后直接返回 (无 requestId 可回送)。
 * - handleAction 抛异常/ reject: 回送 `{ ok: false, error: <message> }`。
 * - unknown action: 由 handleAction 的 default 分支返回 `{ ok: false, error: 'unknown action: ...' }`。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param tag 日志 tag (如 'NetworkBridge')
 * @param handleAction 按 action 分发到具体处理函数, 返回响应
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.xxxCallback)
 */
export function dispatchBridgeRequest(
  requestJson: string,
  tag: string,
  handleAction: (req: BridgeRequest) => Promise<BridgeResponse>,
  callback: BridgeResultCallback
): void {
  let req: BridgeRequest;
  try {
    req = JSON.parse(requestJson) as BridgeRequest;
  } catch (e) {
    console.error('[' + tag + '] parse request failed: ' + requestJson);
    return;
  }

  handleAction(req).then((resp: BridgeResponse) => {
    callback(req.requestId, JSON.stringify(resp));
  }).catch((err: Error) => {
    const resp: BridgeResponse = { ok: false, error: err.message ?? String(err) };
    callback(req.requestId, JSON.stringify(resp));
  });
}

/**
 * 请求型分发 (同步): 与 [dispatchBridgeRequest] 同协议, 但 handleAction 为同步函数,
 * 异常由本函数统一捕获并回送 `{ ok: false, error: <message> }`。
 */
export function dispatchBridgeRequestSync(
  requestJson: string,
  tag: string,
  handleAction: (req: BridgeRequest) => BridgeResponse,
  callback: BridgeResultCallback
): void {
  let req: BridgeRequest;
  try {
    req = JSON.parse(requestJson) as BridgeRequest;
  } catch (e) {
    console.error('[' + tag + '] parse request failed: ' + requestJson);
    return;
  }

  let resp: BridgeResponse;
  try {
    resp = handleAction(req);
  } catch (e) {
    const err = e as Error;
    resp = { ok: false, error: err.message ?? String(e) };
  }
  callback(req.requestId, JSON.stringify(resp));
}

/** 命令型桥通用命令结构 (fire-and-forget 命令的公共 action 字段)。 */
export interface BridgeCommand {
  action: string;
}

/**
 * 命令型分发: 解析命令 JSON, 失败打 `[tag] parse <kind> failed: <json>` 日志并返回 undefined
 * (命令无响应, 无 requestId 可回送); 成功则返回命令对象, 由调用方断言回各自的命令接口后
 * 自行按 action 分发 (各命令型 handler 的 switch 分支带 per-case 错误处理, 结构不统一, 不强行收敛)。
 *
 * @param json 命令 JSON 字符串
 * @param tag 日志 tag (如 'TtsBridge')
 * @param kind 解析失败日志中的载荷称呼 ('command' | 'payload', 与原各 handler 日志措辞一致)
 */
export function parseBridgeCommand(json: string, tag: string, kind: string): BridgeCommand | undefined {
  let cmd: BridgeCommand;
  try {
    cmd = JSON.parse(json) as BridgeCommand;
  } catch (e) {
    console.error('[' + tag + '] parse ' + kind + ' failed: ' + json);
    return undefined;
  }
  return cmd;
}
