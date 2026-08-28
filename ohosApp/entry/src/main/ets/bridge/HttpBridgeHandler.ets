import http from '@ohos.net.http';
import { emitHttpProgress } from './PlatformEventBridge';

/**
 * ArkTS 侧 HTTP 客户端处理器 (桥接 @ohos.net.http)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeHttpSync 通过 tsfn dispatch 过来的 HTTP 请求,
 * 调用 @ohos.net.http (createHttp + requestInStream, 流式接收) 执行真实请求,
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
 *
 * # 流式接收 (requestInStream) 与下载进度
 * 本端用 requestInStream + on('dataReceive') 分段接收响应体 (一次性 request() 无进度回调),
 * 同时保证 invokeHttpSync 的“请求-响应”接口不变: 分段字节在 ArkTS 侧拼装成完整 body 后
 * 经 legado.httpCallback 原样回送 Kotlin (与 request() 一次性返回语义一致); 进度事件另走
 * [emitHttpProgress] (统一平台事件通道 legado.platformEvent → Kotlin
 * OhosDownloadProgressEvents, 按 url 关联 bytesReceived/totalBytes)。
 *
 * # 重定向限制 (@ohos.net.http 不暴露重定向链)
 * 系统自动跟随重定向 (usingDefaultHostRedirects=true) 后返回最终目标资源 (body/code/header),
 * 但 IncomingMessage 只有 responseCode/header/result/resultType, 无最终请求 URL, 响应头也不
 * 携带重定向后地址 —— 本端无法向 Kotlin 回传“最终 URL”。故 KmpResponse.request 保持原始请求
 * (StrResponse.url() 在重定向场景返回原始地址), 与 iOS Ktor finalUrl 修复存在平台差异
 * (详见 KmpHttpTypes.ohos.kt KmpResponse 注释)。未采用手动跟随 (usingDefaultHostRedirects=false
 * + Location 循环重发) 的原因: 会改变全部 HTTP 请求的核心重定向语义 (301/302/303 转 GET、
 * 307/308 保 POST、跨域剥离 Authorization、重定向 Cookie 回写等), 本机无 DevEco 无法验证。
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

// 流式请求 url Map<requestId, string>, 供 cancel action 补发完成进度事件 (消费端停止转圈)
const streamingUrls = new Map<number, string>();

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

// ===== execute: http.createHttp + requestInStream (流式接收, 分段回传下载进度) =====
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
  streamingUrls.set(requestId, p.url);

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
  // SOCKS 代理 OS API 不支持 (与 Android Proxy.Type.SOCKS 行为差异, 见 NativeHttpProvider 注释)
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

  // ===== 流式接收状态 =====
  // requestInStream 回调只给响应头 (responseCode/header), 响应体经 dataReceive 事件分段到达;
  // 分段字节在这里拼装成完整 body 回送 Kotlin (与 request() 一次性返回语义一致)。
  const chunks: ArrayBuffer[] = [];
  let receivedBytes = 0;
  let totalBytes = 0;
  let lastResponseCode = 0;
  let lastHeaders: HttpHeader[] = [];
  let finished = false;

  // 单次完成防护: dataEnd 与回调 err 可能先后到达 (cancel destroy 也可能中断事件流),
  // 只允许第一个终态生效, 避免重复 callback 唤醒 Kotlin 侧 CompletableDeferred 两次。
  const finish = (resp: HttpResponse, respBody: ArrayBuffer | undefined): void => {
    if (finished) return;
    finished = true;
    cleanupStreaming(requestId, httpRequest);
    callback(requestId, JSON.stringify(resp), respBody);
  };

  // 向 Kotlin 推送进度 (统一平台事件通道; 终态 isComplete=true 后 Kotlin 自动注销监听)
  const emitProgress = (isComplete: boolean): void => {
    emitHttpProgress(p.url, receivedBytes, totalBytes, isComplete);
  };

  // 分段接收: 追加字节 + 累计进度回传 (每个数据段一次事件; Kotlin 侧递增去重)
  httpRequest.on('dataReceive', (data: ArrayBuffer) => {
    if (finished || data == null || data.byteLength <= 0) return;
    chunks.push(data);
    receivedBytes += data.byteLength;
    emitProgress(false);
  });

  // 响应头: requestInStream 回调只回 ResponseCode (number), header 经 headersReceive 事件到达
  // (SDK typings: "should use on_headersReceive and on_dataReceive to get http response")
  httpRequest.on('headersReceive', (header: object) => {
    if (finished || header == null) return;
    lastHeaders = headerMapToList(header);
    // Content-Length 兜底 totalBytes (dataReceiveProgress 可能未触发; 分块传输缺省时恒 0 =
    // 不确定进度, 消费端按 0% 展示)
    if (totalBytes <= 0) {
      totalBytes = parseContentLength(header);
    }
  });

  // 接收进度: 优先用系统给的 totalSize (Content-Length 缺失/分块时也能给出总数)
  httpRequest.on('dataReceiveProgress', (info: http.DataReceiveProgressInfo) => {
    if (info != null && info.totalSize > 0) {
      totalBytes = info.totalSize;
    }
  });

  // 流式结束: 拼装完整 body 回送 (进度终态先行, 消费端停止转圈)
  httpRequest.on('dataEnd', () => {
    if (finished) return;
    emitProgress(true);
    finish(
      {
        ok: true,
        code: lastResponseCode,
        // message (HTTP reason phrase) 不再在此计算: 纯映射已下沉到 K/N 唯一一份
        // (KmpHttpTypes.ohos.kt ohosStatusText), 此处置空串, K/N executeOnce 按 code 计算
        // (对齐 OkHttp Response.message 语义, 消除双份维护)。
        message: '',
        headers: lastHeaders,
      },
      concatChunks(chunks)
    );
  });

  // requestInStream: 回调先于 dataReceive/dataEnd 到达 (响应头就绪即回调, 事件随后流式推送);
  // data 即 ResponseCode (number), 响应头已在 headersReceive 事件里捕获
  httpRequest.requestInStream(p.url, options, (err, data) => {
    if (finished) return;
    if (err) {
      emitProgress(true);
      finish({ ok: false, error: err.message ?? String(err) }, undefined);
      return;
    }
    lastResponseCode = data;
  });
}

// ===== cancel: 销毁对应 httpRequest, 补发完成进度, 回送 ok=true (与 OkHttp Call.cancel 语义对齐) =====
async function handleCancel(requestId: number, callback: HttpCallback): Promise<void> {
  const req = activeRequests.get(requestId);
  if (req != null) {
    // 取消时 requestInStream 的 dataEnd 不保证触发, 主动补发完成进度 (消费端停止转圈)
    const url = streamingUrls.get(requestId);
    if (url != null) {
      emitHttpProgress(url, 0, 0, true);
    }
    try {
      req.destroy();
    } catch (e) {
      // 忽略 destroy 异常
    }
  }
  cleanupStreaming(requestId, req ?? null);
  const resp: HttpResponse = { ok: true };
  callback(requestId, JSON.stringify(resp), undefined);
}

// 清理流式监听 + 活跃请求映射 (请求完成/失败/取消时调用)
function cleanupStreaming(requestId: number, httpRequest: http.HttpRequest | null): void {
  if (httpRequest != null) {
    try { httpRequest.off('dataReceive'); } catch (e) { /* 忽略 off 异常 */ }
    try { httpRequest.off('dataEnd'); } catch (e) { /* 忽略 off 异常 */ }
    try { httpRequest.off('dataReceiveProgress'); } catch (e) { /* 忽略 off 异常 */ }
    try { httpRequest.destroy(); } catch (e) { /* 忽略 destroy 异常 */ }
  }
  activeRequests.delete(requestId);
  streamingUrls.delete(requestId);
}

// 拼装分段的 ArrayBuffer 为完整 body (零段返回 undefined, 单段直接复用, 多段拷贝合并)
function concatChunks(chunks: ArrayBuffer[]): ArrayBuffer | undefined {
  if (chunks.length === 0) return undefined;
  if (chunks.length === 1) return chunks[0];
  let total = 0;
  for (const chunk of chunks) {
    total += chunk.byteLength;
  }
  const merged = new ArrayBuffer(total);
  const mergedView = new Uint8Array(merged);
  let offset = 0;
  for (const chunk of chunks) {
    mergedView.set(new Uint8Array(chunk), offset);
    offset += chunk.byteLength;
  }
  return merged;
}

// 从响应头取 Content-Length (大小写不敏感; 缺失/非法返回 0 = 总大小未知)
function parseContentLength(header: object | undefined): number {
  if (header == null) return 0;
  const obj = header as Record<string, string | string[]>;
  for (const key of Object.keys(obj)) {
    if (key.toLowerCase() === 'content-length') {
      const value = obj[key];
      const text = Array.isArray(value) ? value[0] : value;
      const parsed = Number.parseInt(String(text), 10);
      if (!Number.isNaN(parsed) && parsed > 0) {
        return parsed;
      }
    }
  }
  return 0;
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
