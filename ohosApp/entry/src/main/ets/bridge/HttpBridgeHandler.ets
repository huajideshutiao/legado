import http from '@ohos.net.http';
import { utf8ToArrayBuffer } from './BridgeCodec';

/**
 * ArkTS 侧 HTTP 客户端处理器 (桥接 @ohos.net.http)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeHttpSync 通过 tsfn dispatch 过来的 HTTP 请求,
 * 调用 @ohos.net.http (createHttp + request) 执行真实请求,
 * 完成后通过 legado.httpCallback(requestId, resultJson, body?) 回送结果给 Kotlin。
 *
 * # 混合协议 (与 KmpHttpTypes.ohos.kt 对齐, 同 WebView 桥思路: 大字节面走 napi 裸参数)
 *
 * ## 请求 (Kotlin → ArkTS, 由 OhosNativeBridge.HttpBridgeRequest 序列化 + tsfn 第二参数)
 * ```
 * handleHttpRequest(requestJson, bodyArrayBuffer)
 * {
 *   "requestId": <number>,
 *   "action": "execute" | "cancel",
 *   "payload": "<JSON string, HttpRequestPayload>"
 * }
 * ```
 * - 控制面: requestJson 内 payload (HttpRequestPayload) 只含小字段 (url/method/headers/超时/代理), 不含 body
 * - 数据面: bodyArrayBuffer 为请求体裸字节 (tsfn call-js 用 napi external ArrayBuffer 零拷贝包装;
 *   无 body 时为 undefined, 二进制保真)
 *
 * ## execute payload (HttpRequestPayload)
 * {
 *   "url": "<string>",
 *   "method": "GET" | "POST" | "PUT" | "DELETE" | "HEAD" | "OPTIONS" | "PATCH",
 *   "headers": [{"name":"<string>","value":"<string>"}],
 *   "contentType": "<string>?",       // body 的 content type
 *   "timeoutMs": <number>,            // callTimeout, 0 表示用默认; 作为 connectTimeout
 *   "readTimeoutMs": <number>?        // readTimeout, 0/缺省时回退用 timeoutMs
 * }
 *
 * ## 响应 (ArkTS → Kotlin, 由 legado.httpCallback 推送)
 * ```
 * httpCallback(requestId, resultJson, bodyArrayBuffer)
 * ```
 * - 成功: `{ ok: true, code: <number>, message: "<string>", headers: [{"name":"<string>","value":"<string>"}] }`
 *   (message 置空串: 状态文本纯映射已下沉到 K/N KmpHttpTypes.ohos.kt ohosStatusText, 见 handleExecute;
 *   body 走第三参数 ArrayBuffer 裸传, 不经 base64)
 * - 失败: `{ ok: false, error: "<string>" }` (无 body)
 *
 * # 设计说明
 * - body 用 napi ArrayBuffer 裸传 (与 WebView 桥裸字符串同思路; body 是任意二进制,
 *   ArrayBuffer 保真), ArkTS 侧直接作为 extraData 传给 @ohos.net.http,
 *   响应 body 统一用 HttpDataType.ARRAY_BUFFER 接收, 原样回传 Kotlin (零拷贝)。
 * - method 字符串映射到 http.RequestMethod 枚举。
 * - timeoutMs 作为 connectTimeout, readTimeoutMs 作为 readTimeout (缺省回退 timeoutMs),
 *   与 OkHttp callTimeout/readTimeout 双超时语义对齐。
 * - GET/HEAD 不携带 extraData (RFC 7231 语义: 这些方法不应有 body)。
 * - Set-Cookie 响应头多值保持独立条目 (RFC 6265: 多个 cookie 不能用逗号合并)。
 * - @ohos.net.http 默认 usingDefaultHostRedirects=true 自动跟随重定向。
 */

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface HttpBridgeRequest {
  requestId: number;
  action: string;
  payload: string;
}

interface HttpHeader {
  name: string;
  value: string;
}

interface HttpRequestPayload {
  url: string;
  method: string;
  headers: HttpHeader[];
  contentType?: string;
  timeoutMs: number;
  readTimeoutMs?: number;
  // 代理 (仅 http/https; @ohos.net.http 不支持 SOCKS): proxyHost 非空时设 usingProxy
  proxyHost?: string;
  proxyPort?: number;
  proxyUsername?: string;
  proxyPassword?: string;
}

interface HttpResponse {
  ok: boolean;
  code?: number;
  message?: string;
  headers?: HttpHeader[];
  error?: string;
}

// 由 EntryAbility 注入的结果回送函数类型 (内部调 legado.httpCallback; body 为数据面裸字节 ArrayBuffer)
export type HttpCallback = (requestId: number, resultJson: string, body: ArrayBuffer | undefined) => void;

// 活跃的 httpRequest 对象 Map<requestId, http.HttpRequest>, 供 cancel action 使用
const activeRequests = new Map<number, http.HttpRequest>();

/**
 * 处理来自 Kotlin 的 HTTP 请求 (由 EntryAbility 注册的 tsfn 回调调用, 混合协议)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload 控制面)
 * @param body 请求体裸字节 ArrayBuffer (tsfn 第二参数; 无 body 时为 undefined)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.httpCallback)
 */
export function handleHttpRequest(requestJson: string, body: ArrayBuffer | undefined, callback: HttpCallback): void {
  let req: HttpBridgeRequest;
  try {
    req = JSON.parse(requestJson) as HttpBridgeRequest;
  } catch (e) {
    console.error('[HttpBridge] parse request failed: ' + requestJson);
    return;
  }

  // 异步处理, 完成后回调 (与 CryptoBridgeHandler.ets 同模式)
  handleAction(req, body, callback).catch((err: Error) => {
    const resp: HttpResponse = { ok: false, error: err.message ?? String(err) };
    callback(req.requestId, JSON.stringify(resp), undefined);
  });
}

// 根据 action 分发到具体处理函数
async function handleAction(req: HttpBridgeRequest, body: ArrayBuffer | undefined, callback: HttpCallback): Promise<void> {
  switch (req.action) {
    case 'execute':
      return await handleExecute(req.requestId, req.payload, body, callback);
    case 'cancel':
      return await handleCancel(req.requestId, callback);
    default: {
      const resp: HttpResponse = { ok: false, error: 'unknown action: ' + req.action };
      callback(req.requestId, JSON.stringify(resp), undefined);
    }
  }
}

// ===== execute: http.createHttp + request(url, options, callback) =====
async function handleExecute(
  requestId: number, payloadJson: string, body: ArrayBuffer | undefined, callback: HttpCallback
): Promise<void> {
  let p: HttpRequestPayload;
  try {
    p = JSON.parse(payloadJson) as HttpRequestPayload;
  } catch (e) {
    const resp: HttpResponse = { ok: false, error: 'parse payload failed' };
    callback(requestId, JSON.stringify(resp), undefined);
    return;
  }

  const httpRequest = http.createHttp();
  activeRequests.set(requestId, httpRequest);

  // 构造 HttpRequestOptions
  const options: http.HttpRequestOptions = {
    method: mapMethod(p.method),
    header: buildHeaderMap(p.headers, p.contentType),
    expectDataType: http.HttpDataType.ARRAY_BUFFER,
  };
  // connectTimeout 用 callTimeout (timeoutMs); readTimeout 优先用 readTimeoutMs, 缺省回退 timeoutMs
  if (p.timeoutMs > 0) {
    options.connectTimeout = p.timeoutMs;
    options.readTimeout = p.timeoutMs;
  }
  if (p.readTimeoutMs != null && p.readTimeoutMs > 0) {
    options.readTimeout = p.readTimeoutMs;
  }
  // 代理: @ohos.net.http 支持 HttpProxy (host/port/username/password, API 12+);
  // SOCKS 代理 OS API 不支持 (与 Android Proxy.Type.SOCKS 行为差异, 见 OhosHttpProvider 注释)
  if (p.proxyHost != null && p.proxyHost.length > 0 && p.proxyPort != null && p.proxyPort > 0) {
    const proxy: http.HttpProxy = {
      host: p.proxyHost,
      port: p.proxyPort,
      exclusionList: [],
    };
    if (p.proxyUsername != null && p.proxyUsername.length > 0) {
      proxy.username = p.proxyUsername;
    }
    if (p.proxyPassword != null && p.proxyPassword.length > 0) {
      proxy.password = p.proxyPassword;
    }
    options.usingProxy = proxy;
  }
  // body: napi ArrayBuffer 裸字节直接作为 extraData (不经 base64, 二进制保真);
  // GET/HEAD 不应携带 body (RFC 7231)
  const methodNoBody = p.method === 'GET' || p.method === 'HEAD';
  if (!methodNoBody && body != null && body.byteLength > 0) {
    options.extraData = body;
  }

  httpRequest.request(p.url, options, (err, data) => {
    cleanupRequest(requestId);
    if (err) {
      const resp: HttpResponse = { ok: false, error: err.message ?? String(err) };
      callback(requestId, JSON.stringify(resp), undefined);
      return;
    }
    // 响应 body: HttpDataType.ARRAY_BUFFER 接收, ArrayBuffer 原样裸传 (零拷贝, 二进制保真);
    // 非 ArrayBuffer 结果 (string/其他) 兜底转 ArrayBuffer
    const result = data.result;
    let respBody: ArrayBuffer | undefined;
    if (result instanceof ArrayBuffer) {
      respBody = result;
    } else if (typeof result === 'string') {
      respBody = utf8ToArrayBuffer(result);
    } else if (result != null) {
      // 兜底: 其他类型转 string 再编码
      respBody = utf8ToArrayBuffer(String(result));
    }
    // message (HTTP reason phrase) 不再在此计算: 纯映射已下沉到 K/N 唯一一份
    // (KmpHttpTypes.ohos.kt ohosStatusText), 此处置空串, K/N executeOnce 按 code 计算
    // (对齐 OkHttp Response.message 语义, 消除双份维护)。
    const resp: HttpResponse = {
      ok: true,
      code: data.responseCode,
      message: '',
      headers: headerMapToList(data.header),
    };
    callback(requestId, JSON.stringify(resp), respBody);
  });
}

// ===== cancel: 销毁对应 httpRequest, 回送 ok=true (与 OkHttp Call.cancel 语义对齐) =====
async function handleCancel(requestId: number, callback: HttpCallback): Promise<void> {
  const req = activeRequests.get(requestId);
  if (req != null) {
    try {
      req.destroy();
    } catch (e) {
      // 忽略 destroy 异常
    }
    activeRequests.delete(requestId);
  }
  const resp: HttpResponse = { ok: true };
  callback(requestId, JSON.stringify(resp), undefined);
}

// 清理活跃请求 Map (请求完成/失败时调用)
function cleanupRequest(requestId: number): void {
  activeRequests.delete(requestId);
}

// method 字符串 → http.RequestMethod 枚举
function mapMethod(method: string): http.RequestMethod {
  const m = method.toUpperCase();
  switch (m) {
    case 'GET': return http.RequestMethod.GET;
    case 'POST': return http.RequestMethod.POST;
    case 'PUT': return http.RequestMethod.PUT;
    case 'DELETE': return http.RequestMethod.DELETE;
    case 'HEAD': return http.RequestMethod.HEAD;
    case 'OPTIONS': return http.RequestMethod.OPTIONS;
    case 'CONNECT': return http.RequestMethod.CONNECT;
    case 'TRACE': return http.RequestMethod.TRACE;
    default: return http.RequestMethod.GET;
  }
}

// headers List + contentType → Record<string, string> (同名合并, 与 OkHttp 行为一致)
function buildHeaderMap(headers: HttpHeader[], contentType?: string): Record<string, string> {
  const map: Record<string, string> = {};
  if (headers != null) {
    for (const h of headers) {
      map[h.name] = h.value;
    }
  }
  if (contentType != null && map['Content-Type'] == null && map['content-type'] == null) {
    map['Content-Type'] = contentType;
  }
  return map;
}

// 响应 header (object/Record) → HttpHeader[] (扁平化)
// Set-Cookie 多值保持独立 (RFC 6265: 不能用逗号合并); 其他多值 header 仍可逗号合并 (RFC 7230)。
// 注: 本函数是把 @ohos.net.http 的原始 header 形态转成 K/N 协议扁平列表的 OS 适配层,
// 规则 (Set-Cookie 拆分 / 逗号合并) 仅此处一份; K/N 侧 (KmpHttpTypes.ohos.kt executeOnce)
// 再按 name 分组回 Map, 两侧职责互补, 无重复维护。
function headerMapToList(header: object | undefined): HttpHeader[] {
  const list: HttpHeader[] = [];
  if (header == null) return list;
  const obj = header as Record<string, string | string[]>;
  for (const key of Object.keys(obj)) {
    const v = obj[key];
    if (Array.isArray(v)) {
      if (key.toLowerCase() === 'set-cookie') {
        for (const item of v) {
          list.push({ name: key, value: String(item) });
        }
      } else {
        list.push({ name: key, value: v.join(', ') });
      }
    } else {
      list.push({ name: key, value: String(v) });
    }
  }
  return list;
}
