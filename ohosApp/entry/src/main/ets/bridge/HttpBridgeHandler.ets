import http from '@ohos.net.http';
import buffer from '@ohos.buffer';

/**
 * ArkTS 侧 HTTP 客户端处理器 (桥接 @ohos.net.http)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeHttpSync 通过 tsfn dispatch 过来的 HTTP 请求,
 * 调用 @ohos.net.http (createHttp + request) 执行真实请求,
 * 完成后通过 legado.httpCallback(requestId, resultJson) 回送结果给 Kotlin。
 *
 * # JSON 协议 (与 KmpHttpTypes.ohos.kt 对齐)
 *
 * ## 请求 (Kotlin → ArkTS, 由 OhosNativeBridge.HttpBridgeRequest 序列化)
 * {
 *   "requestId": <number>,
 *   "action": "execute" | "cancel",
 *   "payload": "<JSON string, HttpRequestPayload>"
 * }
 *
 * ## execute payload (HttpRequestPayload)
 * {
 *   "url": "<string>",
 *   "method": "GET" | "POST" | "PUT" | "DELETE" | "HEAD" | "OPTIONS" | "PATCH",
 *   "headers": [{"name":"<string>","value":"<string>"}],
 *   "body": "<base64>?",              // null 表示无 body
 *   "contentType": "<string>?",       // body 的 content type
 *   "timeoutMs": <number>,            // callTimeout, 0 表示用默认; 作为 connectTimeout
 *   "readTimeoutMs": <number>?        // readTimeout, 0/缺省时回退用 timeoutMs
 * }
 *
 * ## 响应 (ArkTS → Kotlin, 由 legado.httpCallback 推送)
 * - 成功: `{ ok: true, code: <number>, message: "<string>", headers: [{"name":"<string>","value":"<string>"}], body: "<base64>" }`
 * - 失败: `{ ok: false, error: "<string>" }`
 *
 * # 设计说明
 * - body 用 base64 传输 (与 CryptoBridgeHandler 一致), ArkTS 侧解码为 ArrayBuffer 传给 @ohos.net.http,
 *   响应 body 统一用 HttpDataType.ARRAY_BUFFER 接收, 编码为 base64 回送 Kotlin。
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
  body?: string;
  contentType?: string;
  timeoutMs: number;
  readTimeoutMs?: number;
}

interface HttpResponse {
  ok: boolean;
  code?: number;
  message?: string;
  headers?: HttpHeader[];
  body?: string;
  error?: string;
}

// 由 EntryAbility 注入的结果回送函数类型 (内部调 legado.httpCallback)
export type HttpCallback = (requestId: number, resultJson: string) => void;

// 活跃的 httpRequest 对象 Map<requestId, http.HttpRequest>, 供 cancel action 使用
const activeRequests = new Map<number, http.HttpRequest>();

/**
 * 处理来自 Kotlin 的 HTTP 请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.httpCallback)
 */
export function handleHttpRequest(requestJson: string, callback: HttpCallback): void {
  let req: HttpBridgeRequest;
  try {
    req = JSON.parse(requestJson) as HttpBridgeRequest;
  } catch (e) {
    console.error('[HttpBridge] parse request failed: ' + requestJson);
    return;
  }

  // 异步处理, 完成后回调 (与 CryptoBridgeHandler.ets 同模式)
  handleAction(req, callback).catch((err: Error) => {
    const resp: HttpResponse = { ok: false, error: err.message ?? String(err) };
    callback(req.requestId, JSON.stringify(resp));
  });
}

// 根据 action 分发到具体处理函数
async function handleAction(req: HttpBridgeRequest, callback: HttpCallback): Promise<void> {
  switch (req.action) {
    case 'execute':
      return await handleExecute(req.requestId, req.payload, callback);
    case 'cancel':
      return await handleCancel(req.requestId, callback);
    default: {
      const resp: HttpResponse = { ok: false, error: 'unknown action: ' + req.action };
      callback(req.requestId, JSON.stringify(resp));
    }
  }
}

// ===== execute: http.createHttp + request(url, options, callback) =====
async function handleExecute(requestId: number, payloadJson: string, callback: HttpCallback): Promise<void> {
  let p: HttpRequestPayload;
  try {
    p = JSON.parse(payloadJson) as HttpRequestPayload;
  } catch (e) {
    const resp: HttpResponse = { ok: false, error: 'parse payload failed' };
    callback(requestId, JSON.stringify(resp));
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
  // body: base64 → ArrayBuffer (作为 extraData); GET/HEAD 不应携带 body (RFC 7231)
  const methodNoBody = p.method === 'GET' || p.method === 'HEAD';
  if (!methodNoBody && p.body != null && p.body.length > 0) {
    try {
      const buf = buffer.from(p.body, 'base64');
      options.extraData = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.length);
    } catch (e) {
      const resp: HttpResponse = { ok: false, error: 'decode body failed: ' + String(e) };
      cleanupRequest(requestId);
      callback(requestId, JSON.stringify(resp));
      return;
    }
  }

  httpRequest.request(p.url, options, (err, data) => {
    cleanupRequest(requestId);
    if (err) {
      const resp: HttpResponse = { ok: false, error: err.message ?? String(err) };
      callback(requestId, JSON.stringify(resp));
      return;
    }
    // 响应 body: ArrayBuffer → base64
    let bodyBase64 = '';
    const result = data.result;
    if (result instanceof ArrayBuffer) {
      bodyBase64 = buffer.from(new Uint8Array(result)).toString('base64');
    } else if (typeof result === 'string') {
      bodyBase64 = buffer.from(result, 'utf-8').toString('base64');
    } else if (result != null) {
      // 兜底: 其他类型转 string 再编码
      bodyBase64 = buffer.from(String(result), 'utf-8').toString('base64');
    }
    const resp: HttpResponse = {
      ok: true,
      code: data.responseCode,
      message: statusText(data.responseCode),
      headers: headerMapToList(data.header),
      body: bodyBase64,
    };
    callback(requestId, JSON.stringify(resp));
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
  callback(requestId, JSON.stringify(resp));
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
// Set-Cookie 多值保持独立 (RFC 6265: 不能用逗号合并); 其他多值 header 仍可逗号合并 (RFC 7230)
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

// HTTP 状态码 → 状态文本 (与 OkHttp Response.message 行为对齐, 仅常见状态码)
function statusText(code: number): string {
  switch (code) {
    case 200: return 'OK';
    case 201: return 'Created';
    case 204: return 'No Content';
    case 301: return 'Moved Permanently';
    case 302: return 'Found';
    case 304: return 'Not Modified';
    case 400: return 'Bad Request';
    case 401: return 'Unauthorized';
    case 403: return 'Forbidden';
    case 404: return 'Not Found';
    case 500: return 'Internal Server Error';
    case 502: return 'Bad Gateway';
    case 503: return 'Service Unavailable';
    default: return code >= 200 && code < 300 ? 'OK' : '';
  }
}
