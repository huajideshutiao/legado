import webview from '@ohos.web.webview';

/**
 * ArkTS 侧后台 WebView 处理器 (桥接隐藏 Web 组件)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeWebViewSync 通过 tsfn dispatch 过来的后台抓取请求
 * (书源 webView / webViewGetSource 规则), 用隐藏 Web 组件 (Index.ets 挂载) 加载页面,
 * onPageEnd 后 runJavaScript 执行 JS 取源码, 完成后通过 legado.webViewCallback(requestId, resultJson, bodyRaw)
 * 回送结果给 Kotlin。
 *
 * # 混合协议 (与 OhosNativeBridge.kt 对齐)
 * 传输分控制面 (JSON) 与数据面 (裸字符串) 两路, 避免大段 HTML/源码的 JSON 转义膨胀
 * 与双端编解码拷贝:
 *
 * ## 请求 (Kotlin → ArkTS, tsfn 回调双参数)
 * - 参数 1 (jsonControl, JSON):
 * ```
 * {
 *   "requestId": <number>,
 *   "action": "request",
 *   "payload": "<JSON string, WebViewRequestPayload 不含 html>"
 * }
 * ```
 * - 参数 2 (htmlRaw, 裸字符串): 直接加载的 HTML 内容 (与 url 二选一, 空串=无)
 *
 * ## request payload (WebViewRequestPayload, 控制面小字段)
 * {
 *   "url": "<string>?",               // 目标 URL (与 html 二选一)
 *   "encode": "<string>?",            // HTML 编码 (默认 utf-8)
 *   "tag": "<string>?",               // cookie 保存标签 (书源 key, 回写用)
 *   "headers": [{"name","value"}],   // 请求头 (含 User-Agent)
 *   "sourceRegex": "<string>?",       // 资源嗅探正则 (onInterceptRequest 命中即返回该资源 URL)
 *   "overrideUrlRegex": "<string>?",  // URL 重定向嗅探正则 (命中即返回该 URL)
 *   "js": "<string>?",                // 页面加载后执行的 JS (为空走 document.documentElement.outerHTML)
 *   "delayTime": <number>,            // JS 执行延迟毫秒 (默认 1000)
 *   "cookie": "<string>?"             // 加载前注入的业务层 cookie ("k1=v1; k2=v2")
 * }
 *
 * ## 响应 (ArkTS → Kotlin, 由 legado.webViewCallback 三参数推送)
 * - 参数 1 (resultJson, JSON 控制面):
 *   - 成功: `{ ok: true, url: "<finalUrl>", cookie: "<k=v; ...>" }`
 *   - 命中嗅探: `{ ok: true, url: "<原始url>" }`
 *   - 失败: `{ ok: false, error: "<原因>" }`
 * - 参数 2 (bodyRaw, 裸字符串): 源码 HTML / 命中 URL (失败或空结果时为空串)
 *
 * # 设计说明
 * - 隐藏 Web 组件单实例, 请求 FIFO 串行 (书源 webView 调用本就低频, 且 Web 组件复用更省资源);
 * - 语义对齐 iOS IosBackstageWebView: onPageEnd 先到提前执行 JS, 否则等到 delayTime;
 *   JS 取不到结果按 30 次 × 1s 重试, 总超时 15s (AppConst.timeLimit);
 * - onInterceptRequest 拦截全部子资源请求 (比 iOS WKWebView 只拦主框架能力强),
 *   命中 sourceRegex/overrideUrlRegex 立即回传命中 URL 并吞掉该请求;
 * - cookie: 加载前 WebCookieManager.configCookieSync 注入, 成功后 fetchCookieSync 回传 (有 tag 由 Kotlin 落库);
 * - 未就绪 (Index.ets 未挂 Web / controller 未注入) 时回送明确错误, Kotlin 侧转为规则错误而非崩溃。
 */

interface WebViewBridgeRequest {
  requestId: number;
  action: string;
  payload: string;
}

interface WebViewHeader {
  name: string;
  value: string;
}

interface WebViewRequestPayload {
  url?: string;
  encode?: string;
  tag?: string;
  headers?: WebViewHeader[];
  sourceRegex?: string;
  overrideUrlRegex?: string;
  js?: string;
  delayTime?: number;
  cookie?: string;
}

interface WebViewResult {
  ok: boolean;
  url?: string;
  cookie?: string;
  error?: string;
}

interface PendingRequest {
  requestId: number;
  callback: WebViewCallback;
  payload: WebViewRequestPayload;
  /** 数据面裸 html 入参 (空串=无, 走 url 加载)。 */
  htmlRaw: string;
}

// 由 EntryAbility 注入的结果回送函数类型 (内部调 legado.webViewCallback, 混合协议三参数)
export type WebViewCallback = (requestId: number, resultJson: string, bodyRaw: string) => void;

// ===== 模块级状态 (隐藏 Web 组件单实例, 请求串行) =====

/** 隐藏 Web 组件控制器 (Index.ets aboutToAppear 注入)。 */
let webController: webview.WebviewController | undefined;

/** 等待队列 (同一时刻只处理一个请求)。 */
const queue: PendingRequest[] = [];

/** 当前处理中的请求 (null = 空闲)。 */
let active: PendingRequest | null = null;

/** 当前请求的开始时间 (等 delayTime 用)。 */
let activeStartTime = 0;

/** 当前请求的绝对截止时间 (15s, 对齐 AppConst.timeLimit)。 */
let activeDeadline = 0;

/** onErrorReceive 主框架错误信息 (非空则快速失败)。 */
let pageError: string | null = null;

/** JS 重试定时器 (30 次 × 1s)。 */
let jsRetryTimer: number | undefined;

/** 已执行的 JS 重试次数。 */
let jsRetryCount = 0;

const DEFAULT_JS = 'document.documentElement.outerHTML';
const MAX_JS_RETRY = 30;
const JS_RETRY_INTERVAL_MS = 1000;
const DEFAULT_TIMEOUT_MS = 15000;

/**
 * 初始化 WebView 桥 (由 Index.ets aboutToAppear 调用, 注入隐藏 Web 组件控制器)。
 *
 * @param controller 隐藏 Web 组件的 WebviewController (与 Index.ets 的 Web 组件绑定)
 */
export function initWebViewBridge(controller: webview.WebviewController): void {
  webController = controller;
  console.info('[WebViewBridge] controller injected');
}

/**
 * 处理来自 Kotlin 的 webView 请求 (由 EntryAbility 注册的 tsfn 回调调用, 混合协议双参数)。
 *
 * @param requestJson 控制面请求 JSON 字符串 (含 requestId/action/payload, payload 不含 html)
 * @param htmlRaw 数据面裸 HTML 字符串 (html 入参, 空串=无)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.webViewCallback)
 */
export function handleWebViewRequest(requestJson: string, htmlRaw: string, callback: WebViewCallback): void {
  let req: WebViewBridgeRequest;
  try {
    req = JSON.parse(requestJson) as WebViewBridgeRequest;
  } catch (e) {
    console.error('[WebViewBridge] parse request failed: ' + requestJson);
    return;
  }
  if (req.action !== 'request') {
    const resp: WebViewResult = { ok: false, error: 'unknown action: ' + req.action };
    callback(req.requestId, JSON.stringify(resp), '');
    return;
  }
  let payload: WebViewRequestPayload;
  try {
    payload = JSON.parse(req.payload) as WebViewRequestPayload;
  } catch (e) {
    const resp: WebViewResult = { ok: false, error: 'parse payload failed' };
    callback(req.requestId, JSON.stringify(resp), '');
    return;
  }
  if (htmlRaw.length === 0 && (payload.url == null || payload.url.length === 0)) {
    const resp: WebViewResult = { ok: false, error: 'url与html不能同时为空' };
    callback(req.requestId, JSON.stringify(resp), '');
    return;
  }
  if (webController == null) {
    // 隐藏 Web 组件未挂载 (Index.ets 未初始化): 明确错误而非静默失败
    const resp: WebViewResult = {
      ok: false,
      error: '鸿蒙端后台WebView不可用: 隐藏 Web 组件未挂载 (Index.ets 未调用 initWebViewBridge)'
    };
    callback(req.requestId, JSON.stringify(resp), '');
    return;
  }
  queue.push({ requestId: req.requestId, callback: callback, payload: payload, htmlRaw: htmlRaw });
  if (active == null) {
    processNext();
  }
}

// ===== 请求处理 (串行) =====

function processNext(): void {
  if (active != null || queue.length === 0) {
    return;
  }
  const req = queue.shift();
  if (req == null) {
    return;
  }
  active = req;
  activeStartTime = Date.now();
  activeDeadline = activeStartTime + DEFAULT_TIMEOUT_MS;
  pageError = null;
  jsRetryCount = 0;
  clearJsRetryTimer();
  startLoad(req.payload, req.htmlRaw);
}

/** 启动页面加载: cookie 注入 → UA → loadUrl / loadData。 */
function startLoad(payload: WebViewRequestPayload, htmlRaw: string): void {
  const controller = webController;
  if (controller == null) {
    completeActive({ ok: false, error: 'webController 未注入' }, '');
    return;
  }
  // 1. cookie 注入 (对齐安卓全局 CookieManager 的隐式共享)
  if (payload.cookie != null && payload.cookie.length > 0) {
    const cookieUrl = (payload.url != null && payload.url.length > 0) ? payload.url : 'about:blank';
    const parts = payload.cookie.split(';');
    for (const part of parts) {
      const kv = part.trim();
      if (kv.length === 0) {
        continue;
      }
      try {
        // WebCookieManager 全为静态方法; setCookie 自 API 11 废弃, 用 configCookieSync
        webview.WebCookieManager.configCookieSync(cookieUrl, kv);
      } catch (e) {
        console.error('[WebViewBridge] setCookie failed: ' + JSON.stringify(e));
      }
    }
  }
  // 2. UA (headers 里带 User-Agent 则设置, 否则重置为系统默认)
  let ua: string | undefined;
  if (payload.headers != null) {
    for (const h of payload.headers) {
      if (h.name.toLowerCase() === 'user-agent') {
        ua = h.value;
        break;
      }
    }
  }
  try {
    controller.setCustomUserAgent(ua ?? '');
  } catch (e) {
    console.error('[WebViewBridge] setCustomUserAgent failed: ' + JSON.stringify(e));
  }
  // 3. 加载 (html 优先, 与 iOS loadHTMLString 语义一致)
  try {
    if (htmlRaw.length > 0) {
      controller.loadData(htmlRaw, 'text/html', payload.encode ?? 'utf-8', payload.url);
    } else if (payload.url != null && payload.url.length > 0) {
      if (payload.headers != null && payload.headers.length > 0) {
        const headers: webview.WebHeader[] = [];
        for (const h of payload.headers) {
          headers.push({ headerKey: h.name, headerValue: h.value });
        }
        controller.loadUrl(payload.url, headers);
      } else {
        controller.loadUrl(payload.url);
      }
    }
  } catch (e) {
    completeActive({ ok: false, error: 'loadUrl/loadData failed: ' + JSON.stringify(e) }, '');
    return;
  }
  // 4. 兜底定时器: 到 delayTime 后若页面未完成也尝试跑 JS (对齐 iOS "等 delayTime" 语义)
  const delay = (payload.delayTime != null && payload.delayTime > 0) ? payload.delayTime : 1000;
  jsRetryTimer = setTimeout(() => {
    jsRetryTimer = undefined;
    maybeRunJs();
  }, delay);
}

/** onPageEnd 回调 (Index.ets Web 组件事件, 先到提前跑 JS)。 */
export function handlePageEnd(): void {
  maybeRunJs();
}

/** onErrorReceive 回调 (主框架加载失败, 快速失败)。 */
export function handleErrorReceive(errorInfo: string): void {
  if (active == null) {
    return;
  }
  pageError = errorInfo;
  maybeRunJs();
}

/** 到 delayTime (或 onPageEnd 先到) 后执行 JS; 页面未完成也照跑 (与 iOS 等 delayTime 语义一致)。 */
function maybeRunJs(): void {
  if (active == null) {
    return;
  }
  if (pageError != null) {
    completeActive({ ok: false, error: 'webView页面加载失败: ' + pageError }, '');
    return;
  }
  runJsOnce();
}

/** 执行一次 JS 取源码; 结果为空按 1s 间隔重试 (对齐 iOS 30 次 × 1s)。 */
function runJsOnce(): void {
  if (active == null) {
    return;
  }
  // 清掉未触发的 delayTime 兜底定时器, 避免与重试定时器并发执行 JS
  clearJsRetryTimer();
  const controller = webController;
  if (controller == null) {
    completeActive({ ok: false, error: 'webController 未注入' }, '');
    return;
  }
  const payload = active.payload;
  const script = (payload.js != null && payload.js.length > 0) ? payload.js : DEFAULT_JS;
  try {
    controller.runJavaScript(script, (error: Error, result: string) => {
      if (active == null) {
        return;
      }
      if (error != null) {
        // JS 执行异常: 视为空结果继续重试 (与 iOS error != null → null 一致)
        retryOrTimeout();
        return;
      }
      const body = result ?? '';
      if (body.length > 0 && body !== 'null') {
        completeActive({
          ok: true,
          url: controller.getUrl(),
          cookie: readActiveCookie(),
        }, body);
        return;
      }
      retryOrTimeout();
    });
  } catch (e) {
    completeActive({ ok: false, error: 'runJavaScript failed: ' + JSON.stringify(e) }, '');
  }
}

/** JS 结果为空: 未超时则 1s 后重试, 超时则报错。 */
function retryOrTimeout(): void {
  if (active == null) {
    return;
  }
  if (Date.now() >= activeDeadline || jsRetryCount >= MAX_JS_RETRY) {
    completeActive({ ok: false, error: 'js执行超时' }, '');
    return;
  }
  jsRetryCount++;
  clearJsRetryTimer();
  jsRetryTimer = setTimeout(() => {
    jsRetryTimer = undefined;
    if (pageError != null) {
      completeActive({ ok: false, error: 'webView页面加载失败: ' + pageError }, '');
      return;
    }
    runJsOnce();
  }, JS_RETRY_INTERVAL_MS);
}

/** 读取当前页面的 cookie (回传 Kotlin, 有 tag 才落库)。 */
function readActiveCookie(): string {
  const controller = webController;
  if (controller == null) {
    return '';
  }
  try {
    const url = controller.getUrl();
    if (url.length === 0) {
      return '';
    }
    // getCookie 自 API 11 废弃, 用 fetchCookieSync (同为静态方法)
    return webview.WebCookieManager.fetchCookieSync(url) ?? '';
  } catch (e) {
    return '';
  }
}

/**
 * 嗅探拦截 (Index.ets Web 组件 onInterceptRequest 回调, 拦截所有子资源请求)。
 *
 * 注: OnInterceptRequestEvent/WebResourceRequest/WebResourceResponse 是 ArkUI 全局声明
 * (component/web.d.ts), 不属于 @ohos.web.webview 命名空间。
 */
export function handleInterceptRequest(event: OnInterceptRequestEvent): WebResourceResponse | undefined {
  if (active == null) {
    return undefined;
  }
  const payload = active.payload;
  const target: string = event.request.getRequestUrl();
  if (target.length === 0) {
    return undefined;
  }
  try {
    if (payload.overrideUrlRegex != null && payload.overrideUrlRegex.length > 0) {
      if (new RegExp(payload.overrideUrlRegex).test(target)) {
        completeActive({ ok: true, url: payload.url ?? target }, target);
        return emptyResponse();
      }
    }
    if (payload.sourceRegex != null && payload.sourceRegex.length > 0) {
      if (new RegExp(payload.sourceRegex).test(target)) {
        completeActive({ ok: true, url: payload.url ?? target }, target);
        return emptyResponse();
      }
    }
  } catch (e) {
    console.error('[WebViewBridge] regex match failed: ' + JSON.stringify(e));
  }
  return undefined;
}

/** 命中嗅探后吞掉该请求用的空响应 (WebResourceResponse 只有无参构造, 字段走 setter)。 */
function emptyResponse(): WebResourceResponse {
  const resp = new WebResourceResponse();
  resp.setResponseData('');
  resp.setResponseEncoding('utf-8');
  resp.setResponseMimeType('text/plain');
  resp.setResponseCode(200);
  resp.setReasonMessage('OK');
  resp.setResponseHeader([]);
  resp.setResponseIsReady(true);
  return resp;
}

/** 完成当前请求并处理下一个 (清状态 + 回送结果, 混合协议: JSON 控制面 + 裸 bodyRaw 数据面)。 */
function completeActive(result: WebViewResult, bodyRaw: string): void {
  const req = active;
  active = null;
  clearJsRetryTimer();
  pageError = null;
  if (req != null) {
    try {
      req.callback(req.requestId, JSON.stringify(result), bodyRaw);
    } catch (e) {
      console.error('[WebViewBridge] callback failed: ' + JSON.stringify(e));
    }
  }
  processNext();
}

function clearJsRetryTimer(): void {
  if (jsRetryTimer !== undefined) {
    clearTimeout(jsRetryTimer);
    jsRetryTimer = undefined;
  }
}
