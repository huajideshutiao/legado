package io.legado.app.help.http

/**
 * 无子资源回调的 WebView 共用的"资源正则 (sourceRegex)"嗅探脚本。
 *
 * # 谁在用
 * WKWebView 两个后端 (macOS 桌面 `MacWebViewEngine`、iOS `IosBackstageWebView`)。
 * WKWebView 不向宿主暴露 http/https 子资源请求 (`WKNavigationDelegate` 只给主框架导航,
 * `WKURLSchemeHandler` 只接管自定义 scheme), 故只能用 **document-start 注入 JS hook +
 * 轮询** 这套降级做法近似 `WebViewClient.onLoadResource` / `shouldInterceptRequest`:
 * - [SNIFF_HOOK_JS]: 文档开始前注入, 改写页面发起请求的入口 (fetch / XMLHttpRequest.open)
 *   并把 URL 记到页面全局 `window.__legadoSniff`;
 * - [SNIFF_COLLECT_JS]: 轮询收集, 把记录与 `performance.getEntriesByType('resource')`
 *   (兜底静态资源) 合并返回 JSON 数组, 由调用方逐条与 sourceRegex 比对。
 *
 * 两段脚本原先各有一份在 `MacWebViewEngine` 内, 现只此一份, mac 端与 iOS 端共用。
 * 其它平台不用这套: 安卓 `SnifferWebClient.onLoadResource`、Windows WebView2
 * 的 `WebResourceRequested`、Linux WebKitGTK 的 `resource-load-started`、鸿蒙 ArkUI
 * `onInterceptRequest` 都有宿主级子资源回调, 命中即拦 (能力更强)。
 *
 * # 已知代价 (如实记录, 调用方须自行兜底)
 * - **拿不到浏览器自设的 Referer / User-Agent**: JS hook 只能拿到 URL, 拿不到 WebKit 真正
 *   发出的请求头, 也读不到 HttpOnly Cookie —— 防盗链场景可能取到播不了的地址
 *   (桌面 mac 端因此靠 `playHeaders` 用 UA=宿主 UA + Referer=解析页 兜底; iOS 端无此兜底);
 * - **无法拦截 / 替换响应**: 命中后既不能吞掉该请求, 也不能改写响应体, 只能"轮询发现即返回"
 *   (比安卓拦截语义滞后一个轮询周期)。
 */
object WebViewSnifferScripts {

    /** document-start 注入的嗅探 hook: 捕获 fetch/XHR 的 URL。 */
    const val SNIFF_HOOK_JS = """
        (function(){
          if (window.__legadoSniff) return;
          window.__legadoSniff = [];
          var p = function(u){ try{ if(u) window.__legadoSniff.push(String(u)); }catch(e){} };
          try{ var f = window.fetch; if (f) window.fetch = function(){
            try{ p(arguments[0] && (arguments[0].url || arguments[0])); }catch(e){}
            return f.apply(this, arguments); }; }catch(e){}
          try{ var o = XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open = function(m,u){
            try{ p(u); }catch(e){} return o.apply(this, arguments); }; }catch(e){}
        })();
    """

    /** 轮询取全部嗅探到的 URL (hook 结果 + performance entries 兜底静态资源), JSON 数组。 */
    const val SNIFF_COLLECT_JS = """
        (function(){try{
          var a = (window.__legadoSniff || []).concat(
            performance.getEntriesByType('resource').map(function(e){ return e.name; }));
          return JSON.stringify(a);
        }catch(e){ return '[]'; }})()
    """
}
