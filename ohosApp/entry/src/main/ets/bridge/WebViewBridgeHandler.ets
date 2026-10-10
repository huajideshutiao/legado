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

// =====================================================================================
// 前台 Web 控制器桥 (可见 Web 组件, 与上方"后台抓取"链相互独立)
// =====================================================================================
//
// 上方是**后台抓取**链 (书源 webView/webViewGetSource 规则): 隐藏 Web 组件 + 混合协议
// (控制面 JSON + 数据面裸 HTML) + legado.webViewCallback 三参数回送。
// 本节是**前台 Web**链: Compose 槽位 (WebViewRoute/ReadRssRoute/登录 Overlay) 内由
// Index.ets 的 interop builder ('legadoForegroundWeb', 复用仓库既有 ArkUIView2 +
// registerComposeInteropBuilder 范式) 创建**可见** Web 组件, 单 JSON 字符串协议 +
// legado.webControllerCallback(requestId, resultJson) 两参数回送。两条链各有独立 Web
// 实例与 tsfn, 互不抢占 (后台链的状态机/队列完全不受本节点影响)。
//
// # JSON 协议 (与 OhosWebViewControllerBridge.kt 逐字段对齐)
//
// ## 请求 (Kotlin → ArkTS, tsfn 回调单参数)
// {
//   "requestId": <number>,
//   "action": "show" | "loadUrl" | "runJavaScript" | "getCookies" |
//             "getUrl" | "canGoBack" | "goBack" | "reload" | "exitFullScreen",
//   "payload": "<JSON string, 各 action 的入参>"      // 注意: payload 是字符串, 需二次 JSON.parse
// }
//
// ## 请求 payload
// - show:          OhosWebViewControllerPayload
//                  { url?, html?, headers?: [{name,value}], wideViewPort?, isLogin?, sourceKey? }
// - loadUrl/getCookies: OhosWebUrlPayload { url? }
// - runJavaScript: OhosWebScriptPayload { script }
// - goBack/reload/exitFullScreen/getUrl/canGoBack: 空对象 {}
//
// ## 响应/事件 (ArkTS → Kotlin, 由 legado.webControllerCallback 两参数推送)
// 字段与 Kotlin OhosWebControllerResult 同名同义:
// { ok, result?, cookie?, canGoBack?, url?, error?, event?, title?, progress? }
// - requestId > 0: 命令响应 (回 Kotlin 下发的同值)
// - requestId == 0: 主动页面事件, event ∈ onPageFinished | onUrlChanged |
//   onReceivedTitle | onProgressChanged | onCloseWindow
// 注: show/loadUrl/goBack/reload/exitFullScreen 为 fire-and-forget (不回响应, 回送
// 反而会让 Kotlin 侧打出"未知 requestId 的前台 Web 回调"调试日志)。
//
// # cookie 语义
// onPageFinished 事件带页面 host 域 cookie (WebCookieManager.fetchCookieSync);
// ArkTS 侧**不落库**, 由 Kotlin OhosWebViewSlot 写 CookieStoreProviders (含 isLogin 时按
// sourceKey 另存), 与 Android/iOS slot 同口径。
//
// # show 命令缓存 (controller 就绪早于/晚于命令都要工作)
// show 是加载配置的权威来源: 到达时若可见 Web 组件尚未挂载 (Web 组件抛 17100001 Init error),
// 则缓存为 pendingShow, 待首次 onPageEnd (about:blank 起载完成 = 组件已挂载) 时补执行;
// 已成功执行的配置按 appliedShowKey 去重, 不会因页面重载/参数更新而重复加载。

/**
 * 前台 Web 请求 payload: show (与 Kotlin OhosWebViewControllerPayload 逐字段对齐)。
 * headers 复用后台抓取的同形 [{name,value}] (即 Kotlin OhosNativeBridge.WebViewHeader)。
 */
interface ForegroundWebShowPayload {
  url?: string;
  html?: string;
  headers?: WebViewHeader[];
  wideViewPort?: boolean;
  isLogin?: boolean;
  sourceKey?: string;
}

/** 前台 Web 请求 payload: runJavaScript (Kotlin OhosWebScriptPayload)。 */
interface ForegroundWebScriptPayload {
  script?: string;
}

/** 前台 Web 请求 payload: loadUrl / getCookies (Kotlin OhosWebUrlPayload; 无参命令省略)。 */
interface ForegroundWebUrlPayload {
  url?: string;
}

/**
 * 前台 Web 响应/事件 (与 Kotlin OhosWebControllerResult 逐字段对齐; Kotlin 侧
 * ignoreUnknownKeys=true, 多传字段无害)。
 */
interface ForegroundWebResult {
  ok: boolean;
  result?: string;
  cookie?: string;
  canGoBack?: boolean;
  url?: string;
  error?: string;
  event?: string;
  title?: string;
  progress?: number;
}

/** 前台 Web 结果/事件回送函数类型 (EntryAbility 侧传 legado.webControllerCallback)。 */
export type WebControllerCallback = (requestId: number, resultJson: string) => void;

/**
 * 宽视口状态键 (AppStorage)。
 *
 * `metaViewport`/`overviewModeAccess` 是 Web 组件的**静态属性** (只能在 build 里设),
 * 而宽视口开关随 show 命令动态变化, 故经 AppStorage 中转: 本模块写, Index.ets 的
 * `LegadoForegroundWeb` 组件以 @StorageLink 订阅并重新渲染, 由 ArkUI 就地更新属性
 * (与视频 surface 用 AppStorage 传缩放/平移、再靠 @StorageLink 驱动重渲染同一范式)。
 */
export const FOREGROUND_WIDE_VIEWPORT_KEY: string = 'legadoForegroundWebWideViewPort';

// ===== 模块级状态 (前台可见 Web 单实例; 与后台链的 queue/active 完全独立) =====

/** 可见 Web 组件控制器 (Index.ets 的 LegadoForegroundWeb 组件 aboutToAppear 注入)。 */
let foregroundController: webview.WebviewController | undefined;

/** 结果/事件回送函数 (Index.ets 注入, 内部调 legado.webControllerCallback)。 */
let foregroundEventCallback: WebControllerCallback | undefined;

/** 待执行的 show 配置 (加载配置的权威来源; null = 无待加载配置)。 */
let pendingShow: ForegroundWebShowPayload | null = null;

/** 最近一次"已成功下发加载"的配置去重键 (避免同配置重复加载)。 */
let appliedShowKey: string = '';

/** 是否已有真实内容加载过 (pageEnd 判空白页用; 组件起载/复位后的 about:blank 不算)。 */
let foregroundContentLoaded = false;

/** HTML5 视频全屏退出句柄 (onFullScreenEnter 给, exitFullScreen 命令用; 退出/未全屏时为 undefined)。 */
let foregroundFullScreenHandler: FullScreenExitHandler | undefined;

/**
 * 初始化前台 Web 桥 (由 Index.ets 的 LegadoForegroundWeb 组件 aboutToAppear 调用)。
 *
 * @param controller 可见 Web 组件的 WebviewController (与 interop builder 的 Web 组件绑定)
 * @param eventCallback 结果/事件回送函数 (内部调 legado.webControllerCallback; 页面事件用 requestId=0)
 */
export function initForegroundWebBridge(
  controller: webview.WebviewController,
  eventCallback: WebControllerCallback,
): void {
  foregroundController = controller;
  foregroundEventCallback = eventCallback;
  // 宽视口默认 false (对齐 Android: useWideViewPort/loadWithOverviewMode 默认关闭, config 打开才设)
  AppStorage.setOrCreate(FOREGROUND_WIDE_VIEWPORT_KEY, false);
  // show 命令早于组件挂载到达 (pendingShow 已缓存) 时立即补执行; 组件未就绪则静默留给 onPageEnd
  loadPendingShow();
  console.info('[WebControllerBridge] foreground controller injected');
}

/**
 * 处理来自 Kotlin 的前台 Web 命令 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送命令响应的函数 (EntryAbility 注入 legado.webControllerCallback)
 */
export function handleForegroundWebRequest(requestJson: string, callback: WebControllerCallback): void {
  let req: WebViewBridgeRequest;
  try {
    req = JSON.parse(requestJson) as WebViewBridgeRequest;
  } catch (e) {
    console.error('[WebControllerBridge] parse request failed: ' + requestJson);
    return;
  }
  const payload: string = (req.payload != null) ? req.payload : '';
  switch (req.action) {
    // ===== fire-and-forget (ArkTS → Kotlin 不回响应) =====
    case 'show':
      applyShow(payload);
      return;
    case 'loadUrl':
      applyLoadUrl(payload);
      return;
    case 'goBack':
      applyGoBack();
      return;
    case 'reload':
      applyReload();
      return;
    case 'exitFullScreen':
      applyExitFullScreen();
      return;
    // ===== runJavaScript 结果异步到达: 在回调里回送 (不能同步返回) =====
    case 'runJavaScript':
      applyRunJavaScript(req.requestId, payload, callback);
      return;
    // ===== 同步查询 (请求-响应) =====
    case 'getCookies':
      callback(req.requestId, JSON.stringify(queryCookies(payload)));
      return;
    case 'getUrl':
      callback(req.requestId, JSON.stringify(queryUrl()));
      return;
    case 'canGoBack':
      callback(req.requestId, JSON.stringify(queryCanGoBack()));
      return;
    default:
      callback(req.requestId, JSON.stringify({ ok: false, error: 'unknown action: ' + req.action } as ForegroundWebResult));
      return;
  }
}

// ===== 请求处理: show (加载配置下发) =====

/** `show`: 缓存加载配置并下发 (组件未就绪时留给 onPageEnd 补执行; 同配置去重)。 */
function applyShow(payloadJson: string): void {
  const p: ForegroundWebShowPayload | undefined = parseShowPayload(payloadJson);
  if (p === undefined) {
    return;
  }
  pendingShow = p;
  loadPendingShow();
}

/** 解析 show payload (解析失败返回 undefined, 只记日志)。 */
function parseShowPayload(payloadJson: string): ForegroundWebShowPayload | undefined {
  try {
    return JSON.parse(payloadJson.length > 0 ? payloadJson : '{}') as ForegroundWebShowPayload;
  } catch (e) {
    console.error('[WebControllerBridge] parse show payload failed: ' + payloadJson);
    return undefined;
  }
}

/**
 * 下发待执行的 show 配置 (controller 未注入/组件未挂载/同配置已加载时跳过)。
 * 加载调用成功才记 appliedShowKey, 失败则保留 pendingShow 供下次 onPageEnd 重试。
 */
function loadPendingShow(): void {
  const p = pendingShow;
  const controller = foregroundController;
  if (p == null || controller == null) {
    return;
  }
  const key = showKey(p);
  if (key === appliedShowKey) {
    return;
  }
  applyViewportSettings(p);
  const url: string = p.url ?? '';
  const html: string = p.html ?? '';
  const headers: webview.WebHeader[] = toWebHeaders(p.headers);
  try {
    if (html.length > 0) {
      // html 优先 (对齐 Android loadDataWithBaseURL / iOS loadHTMLString); baseUrl 取 url, 无则 about:blank
      controller.loadData(html, 'text/html', 'utf-8', url.length > 0 ? url : 'about:blank');
    } else if (url.length > 0) {
      if (headers.length > 0) {
        controller.loadUrl(url, headers);
      } else {
        controller.loadUrl(url);
      }
    } else {
      // url 与 html 皆空: 无内容可加载 (不记键, 后续配置变化仍可下发)
      return;
    }
    appliedShowKey = key;
    foregroundContentLoaded = true;
  } catch (e) {
    // 常见: 17100001 Init error (Web 组件尚未挂载); 保留 pendingShow, 等组件就绪后重试
    console.warn('[WebControllerBridge] loadUrl/loadData deferred (web component not attached yet): ' + JSON.stringify(e));
  }
}

/** 配置去重键: 只含影响加载结果的字段 (isLogin/sourceKey 只影响 cookie 落库, 不触发重载)。 */
function showKey(p: ForegroundWebShowPayload): string {
  const hs: WebViewHeader[] = p.headers ?? [];
  const headerKey = hs.map((h: WebViewHeader): string => h.name + '=' + h.value).join('&');
  const wide = p.wideViewPort === true ? '1' : '0';
  return (p.url ?? '') + '\u0001' + (p.html ?? '') + '\u0001' + headerKey + '\u0001' + wide;
}

/** 应用视口/UA 设置 (宽视口经 AppStorage 驱动 Index.ets 的 @StorageLink 重渲染)。 */
function applyViewportSettings(p: ForegroundWebShowPayload): void {
  const controller = foregroundController;
  if (controller == null) {
    return;
  }
  AppStorage.setOrCreate(FOREGROUND_WIDE_VIEWPORT_KEY, p.wideViewPort === true);
  // UA: headers 带 User-Agent 则设之, 否则重置为系统默认 (空串语义与后台链一致)
  const ua: string | undefined = findUserAgent(p.headers);
  try {
    controller.setCustomUserAgent(ua ?? '');
  } catch (e) {
    console.warn('[WebControllerBridge] setCustomUserAgent failed: ' + JSON.stringify(e));
  }
}

/** 取 headers 里的 User-Agent (大小写不敏感; 无则 undefined)。 */
function findUserAgent(headers?: WebViewHeader[]): string | undefined {
  if (headers == null) {
    return undefined;
  }
  for (const h of headers) {
    if (h.name.toLowerCase() === 'user-agent') {
      return h.value;
    }
  }
  return undefined;
}

/** headers 转 ArkUI WebHeader[] (headerKey/headerValue 命名与 web.d.ts 一致)。 */
function toWebHeaders(headers?: WebViewHeader[]): webview.WebHeader[] {
  const out: webview.WebHeader[] = [];
  if (headers == null) {
    return out;
  }
  for (const h of headers) {
    out.push({ headerKey: h.name, headerValue: h.value });
  }
  return out;
}

// ===== 请求处理: loadUrl / goBack / reload / exitFullScreen =====

/** `loadUrl`: 直接加载 URL (不改 appliedShowKey: 页面内导航不应被后续 onPageEnd 的 show 补加载覆盖)。 */
function applyLoadUrl(payloadJson: string): void {
  const p: ForegroundWebUrlPayload | undefined = parseUrlPayload(payloadJson);
  const url: string = p?.url ?? '';
  const controller = foregroundController;
  if (controller == null || url.length === 0) {
    return;
  }
  try {
    controller.loadUrl(url);
    foregroundContentLoaded = true;
  } catch (e) {
    console.warn('[WebControllerBridge] loadUrl failed: ' + JSON.stringify(e));
  }
}

/** `goBack`: 可后退才后退 (对齐 Android WebView.goBack 前的 canGoBack 守卫语义)。 */
function applyGoBack(): void {
  const controller = foregroundController;
  if (controller == null) {
    return;
  }
  try {
    if (controller.accessBackward()) {
      controller.backward();
    }
  } catch (e) {
    console.warn('[WebControllerBridge] goBack failed: ' + JSON.stringify(e));
  }
}

/** `reload`: 重新加载当前页 (对齐 Android WebView.reload)。 */
function applyReload(): void {
  const controller = foregroundController;
  if (controller == null) {
    return;
  }
  try {
    controller.refresh();
  } catch (e) {
    console.warn('[WebControllerBridge] reload failed: ' + JSON.stringify(e));
  }
}

/** `exitFullScreen`: 退出网页 HTML5 视频全屏 (无全屏会话时静默)。 */
function applyExitFullScreen(): void {
  const handler = foregroundFullScreenHandler;
  if (handler == null) {
    return;
  }
  foregroundFullScreenHandler = undefined;
  try {
    handler.exitFullScreen();
  } catch (e) {
    console.warn('[WebControllerBridge] exitFullScreen failed: ' + JSON.stringify(e));
  }
}

/** 解析仅带 url 的 payload (无 payload/空对象时返回空对象)。 */
function parseUrlPayload(payloadJson: string): ForegroundWebUrlPayload | undefined {
  if (payloadJson.length === 0) {
    return {};
  }
  try {
    return JSON.parse(payloadJson) as ForegroundWebUrlPayload;
  } catch (e) {
    console.error('[WebControllerBridge] parse url payload failed: ' + payloadJson);
    return undefined;
  }
}

/** 解析 runJavaScript payload (解析失败返回 undefined, 由调用方回送错误响应)。 */
function parseScriptPayload(payloadJson: string): ForegroundWebScriptPayload | undefined {
  try {
    return JSON.parse(payloadJson.length > 0 ? payloadJson : '{}') as ForegroundWebScriptPayload;
  } catch (e) {
    console.error('[WebControllerBridge] parse script payload failed: ' + payloadJson);
    return undefined;
  }
}

// ===== 请求处理: 同步查询 (getCookies / getUrl / canGoBack) =====

/** `getCookies`: 取指定 url (缺省 = 当前页 URL) 的 cookie; 取不到回 ok:false 由 Kotlin 兜底。 */
function queryCookies(payloadJson: string): ForegroundWebResult {
  const p: ForegroundWebUrlPayload | undefined = parseUrlPayload(payloadJson);
  if (p === undefined) {
    return { ok: false, error: 'parse payload failed' };
  }
  const url: string = (p.url != null && p.url.length > 0) ? p.url : currentForegroundUrl();
  if (url.length === 0) {
    return { ok: false, error: '前台 Web 未就绪或当前无页面 URL' };
  }
  return { ok: true, cookie: readForegroundCookie(url) };
}

/** `getUrl`: 当前页 URL (取不到回 ok:false, Kotlin 侧回退预取 URL)。 */
function queryUrl(): ForegroundWebResult {
  const url = currentForegroundUrl();
  if (url.length === 0) {
    return { ok: false, error: '前台 Web 未就绪或当前无页面 URL' };
  }
  return { ok: true, url: url };
}

/** `canGoBack`: 是否可后退 (取不到回 ok:false, Kotlin 侧按 false 处理)。 */
function queryCanGoBack(): ForegroundWebResult {
  const controller = foregroundController;
  if (controller == null) {
    return { ok: false, error: '鸿蒙端前台WebView不可用: 可见 Web 组件未挂载' };
  }
  try {
    return { ok: true, canGoBack: controller.accessBackward() };
  } catch (e) {
    return { ok: false, error: (e as Error).message ?? String(e) };
  }
}

/** 当前页 URL (controller 未注入/未挂载时为空串)。 */
function currentForegroundUrl(): string {
  const controller = foregroundController;
  if (controller == null) {
    return '';
  }
  try {
    const current: string = controller.getUrl();
    return current != null ? current : '';
  } catch (e) {
    return '';
  }
}

/** 读页面 cookie (fetchCookieSync; getCookie 自 API 11 废弃)。 */
function readForegroundCookie(url: string): string {
  if (url.length === 0) {
    return '';
  }
  try {
    // getCookie 自 API 11 废弃, 用 fetchCookieSync (与后台链同一取值方式)
    return webview.WebCookieManager.fetchCookieSync(url) ?? '';
  } catch (e) {
    console.warn('[WebControllerBridge] fetchCookieSync failed: ' + JSON.stringify(e));
    return '';
  }
}

// ===== 请求处理: runJavaScript (结果异步到达, 在回调里回送) =====

/**
 * `runJavaScript`: 执行 JS 并把返回值经 callback 回送 `{ ok:true, result:"<JS返回值>" }`。
 *
 * 必须异步回送: WebviewController.runJavaScript 只有回调/Promise 形态, 命令本身不返回结果;
 * Kotlin 侧 `OhosVisibleWebViewBridge.invokeSync("runJavaScript")` 阻塞等待该响应 (15s 超时)。
 */
function applyRunJavaScript(requestId: number, payloadJson: string, callback: WebControllerCallback): void {
  const p: ForegroundWebScriptPayload | undefined = parseScriptPayload(payloadJson);
  if (p === undefined) {
    callback(requestId, JSON.stringify({ ok: false, error: 'parse payload failed' } as ForegroundWebResult));
    return;
  }
  const script: string = p.script ?? '';
  const controller = foregroundController;
  if (script.length === 0) {
    callback(requestId, JSON.stringify({ ok: false, error: 'runJavaScript: script 为空' } as ForegroundWebResult));
    return;
  }
  if (controller == null) {
    // 可见 Web 组件未挂载: 明确错误而非静默失败 (Kotlin 侧 evaluateJavascript 收到 null)
    callback(requestId, JSON.stringify({
      ok: false,
      error: '鸿蒙端前台WebView不可用: 可见 Web 组件未挂载 (Index.ets 未注册 legadoForegroundWeb builder)'
    } as ForegroundWebResult));
    return;
  }
  try {
    // 回调形参必填两个 (ArkTS 无可选参数重载): error 非空即执行失败, 此时 result 无意义
    controller.runJavaScript(script, (error: Error, result: string): void => {
      if (error != null) {
        console.error('[WebControllerBridge] runJavaScript failed: ' + JSON.stringify(error));
        callback(requestId, JSON.stringify({
          ok: false,
          error: (error as Error).message ?? String(error)
        } as ForegroundWebResult));
        return;
      }
      callback(requestId, JSON.stringify({ ok: true, result: result ?? '' } as ForegroundWebResult));
    });
  } catch (e) {
    callback(requestId, JSON.stringify({ ok: false, error: (e as Error).message ?? String(e) } as ForegroundWebResult));
  }
}

// ===== 页面事件 (Index.ets 的 Web 组件回调 → requestId=0 推送 Kotlin) =====

/**
 * onPageEnd (主框架加载完成): 补执行待加载配置 + 回送 onPageFinished (带 host 域 cookie)。
 *
 * about:blank 起载完成也走这里 —— 那是"可见 Web 组件已挂载"的信号, 故在此补执行缓存的 show。
 */
export function handleForegroundPageEnd(urlFromEvent: string): void {
  // 组件挂载完成 (about:blank 起载结束) 时会走到这里: 就地补执行缓存的 show
  loadPendingShow();
  const url: string = (urlFromEvent != null && urlFromEvent.length > 0) ? urlFromEvent : currentForegroundUrl();
  if (url.length === 0 || (url === 'about:blank' && !foregroundContentLoaded)) {
    // 纯空白页 = 组件起载/复位态, 不是用户可见页面: 不回送事件
    // (带 html 加载的页面 baseUrl 也是 about:blank, 但它已计入 foregroundContentLoaded, 照常回送)
    return;
  }
  const event: ForegroundWebResult = { ok: true, event: 'onPageFinished' };
  event.url = url;
  const cookie: string = readForegroundCookie(url);
  if (cookie.length > 0) {
    event.cookie = cookie;
  }
  emitForegroundEvent(event);
  // 注: 不再单独回送 onUrlChanged —— Kotlin OhosWebViewSlot 已在 onPageFinished 分支里
  // 调 callbacks.onUrlChanged(url), 与 Android 端 (onPageFinished → onPageFinished + onUrlChanged)
  // 同为"页面完成即同步 URL"一条链, 额外回送只会重复触发同一回调。
}

/**
 * 组件确实已挂载后的补执行 (只在尚未加载过任何内容时执行)。
 *
 * 用作 onPageEnd 之外的兼底: 只要收到任一页面事件 (标题/进度/URL/错误), 就说明可见 Web 组件
 * 已挂载, 可再试一次缓存的 show。若已加载过内容则不动 —— 否则 show 之后的页面内导航
 * (loadUrl/服务端重定向) 会被 show 配置重新覆盖回去。
 */
function retryPendingShowIfNotLoaded(): void {
  if (!foregroundContentLoaded) {
    loadPendingShow();
  }
}

/**
 * onRefreshAccessedHistory: 回送 onUrlChanged (页面内跳转/重载后 URL 变化同步给 Kotlin,
 * 与 Kotlin OhosWebViewPageListener 的 onUrlChanged 分支对齐; Kotlin 用它刷新页面 URL 状态)。
 *
 * 空白页 (about:blank) 不下发: 那是组件初始/复位态, 不是用户可见页面。
 */
export function handleForegroundUrlChanged(url: string): void {
  retryPendingShowIfNotLoaded();
  if (url == null || url.length === 0 || url === 'about:blank') {
    return;
  }
  emitForegroundEvent({ ok: true, event: 'onUrlChanged', url: url });
}

/** onTitleReceive: 回送 onReceivedTitle (无标题时 ArkUI/Android 都按 URL 兜底, 语义一致)。 */
export function handleForegroundTitleReceive(title: string): void {
  retryPendingShowIfNotLoaded();
  emitForegroundEvent({ ok: true, event: 'onReceivedTitle', title: title != null ? title : '' });
}

/** onProgressChange: 回送 onProgressChanged (0..100, Kotlin 侧 progress 为 Int)。 */
export function handleForegroundProgressChange(progress: number): void {
  retryPendingShowIfNotLoaded();
  emitForegroundEvent({ ok: true, event: 'onProgressChanged', progress: Math.round(progress) });
}

/**
 * onWindowExit: 回送 onCloseWindow (页面要求关窗, Kotlin 侧 onCloseWindow 回调非空时才处理)。
 *
 * 空白页 (about:blank/空 URL) 的窗口关闭不算用户可见页面, 过滤掉以免误触关页面逻辑。
 */
export function handleForegroundWindowExit(): void {
  const url: string = currentForegroundUrl();
  if (url.length === 0 || url === 'about:blank') {
    return;
  }
  emitForegroundEvent({ ok: true, event: 'onCloseWindow' });
}

/** onErrorReceive: 只记日志 (加载失败的用户提示由 Kotlin 预取层负责, 与 Android 端口径一致)。 */
export function handleForegroundErrorReceive(errorInfo: string): void {
  retryPendingShowIfNotLoaded();
  console.error('[WebControllerBridge] page error: ' + errorInfo);
}

/** onFullScreenEnter: 记下全屏退出句柄 (供 exitFullScreen 命令使用)。 */
export function handleForegroundFullScreenEnter(handler: FullScreenExitHandler): void {
  foregroundFullScreenHandler = handler;
}

/** onFullScreenExit: 清掉全屏句柄 (会话已结束)。 */
export function handleForegroundFullScreenExit(): void {
  foregroundFullScreenHandler = undefined;
}

/** 回送主动页面事件 (requestId=0; 未注入回送函数时静默)。 */
function emitForegroundEvent(result: ForegroundWebResult): void {
  const cb: WebControllerCallback | undefined = foregroundEventCallback;
  if (cb == null) {
    return;
  }
  try {
    cb(0, JSON.stringify(result));
  } catch (e) {
    console.error('[WebControllerBridge] event callback failed: ' + JSON.stringify(e));
  }
}
