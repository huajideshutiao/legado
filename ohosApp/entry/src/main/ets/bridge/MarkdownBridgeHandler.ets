import webview from '@ohos.web.webview';
import legado from 'liblegado_napi.so';

/**
 * Markdown 查看器桥 (KMP MarkdownContent → 鸿蒙 Web 组件渲染, composeResources 单一数据源)。
 *
 * # 数据流
 * KMP MarkdownContent (ArkUIView2 interop 混排 Web 组件) 经 tsfn 推送渲染请求:
 *   OhosNativeBridge.sendMarkdown(json) → C++ ohos_markdown_dispatch →
 *   [handleMarkdownRequest] (ArkTS 主线程) → 缓存 latest 渲染参数,
 *   Web 组件就绪后 runJavaScript 注入 renderMarkdown(...) 渲染。
 *
 * # 页面来源 (零拷贝零重复)
 * 完整 viewer HTML 由 K/N 运行时从 composeResources 直读模板 + marked/highlight/
 * github-markdown 亮暗 css 内联拼装 (legado.buildMarkdownViewerHtml, 进程内缓存),
 * 本处理器经 WebviewController.loadData 交给 Web 组件 —— 仓库与 HAP 均无资源副本。
 *
 * # 生命周期状态机
 * Web 组件由 Index.ets 的 registerComposeInteropBuilder('legadoMarkdownWeb') 创建,
 * 初始 src=about:blank, 仅当 KMP ArkUIView2 节点组合时才存在。每次加载完成 (onPageEnd):
 *   1. 未加载过 viewer → loadData(viewerHtml) (从 about:blank 切到 viewer);
 *   2. javaScriptProxy 未注册 → registerJavaScriptProxy + 重新 loadData
 *      (proxy 对后续加载的页面生效, 比 refresh 更可控);
 *   3. 已就绪 → injectLatest() 注入最新渲染参数。
 * Web 组件销毁重建 (对话框关闭重开) 时 onPageBegin 复位去重键, 重载后重新渲染。
 *
 * # 链接回送
 * viewer 页面内 <a> 点击经 javaScriptProxy (legadoMarkdownBridge.openLink) 调回本处理器,
 * 再经 EntryAbility 注入的 eventCallback (legado.markdownEvent) 回推 KMP,
 * 由 KMP 调 OpenUrlProviders 走系统浏览器打开 (决策: Markdown 链接一律系统浏览器)。
 */

/** KMP → ArkTS 渲染请求 payload (与 OhosNativeBridge.MarkdownRenderPayload 对齐)。 */
interface MarkdownRenderPayload {
  content: string;
  isDark: boolean;
  fontSize: number;
}

/** ArkTS → KMP 事件 payload (与 OhosNativeBridge.MarkdownEventPayload 对齐, action='openLink')。 */
interface MarkdownEventPayload {
  action: string;
  url?: string;
}

/** 当前渲染参数 (最后一次 push, Web 就绪后按此注入)。 */
let latest: MarkdownRenderPayload | null = null;

/** 查看器 Web 组件控制器 (Index.ets 注入, 与后台抓取 Web 实例相互独立)。 */
let webController: webview.WebviewController | undefined;

/** ArkTS → KMP 事件回送函数 (EntryAbility 注入, 内部调 legado.markdownEvent)。 */
let eventCallback: ((json: string) => void) | null = null;

/** 完整 viewer HTML (K/N composeResources 直读内联, initMarkdownBridge 时预构建, 进程内缓存)。 */
let viewerHtml: string = '';

/** viewer 页面是否已 loadData 到 Web 组件 (false = 当前还是 about:blank)。 */
let htmlLoaded = false;

/** javaScriptProxy 是否已注册到控制器 (注册后重新 loadData 一次页面才生效)。 */
let proxyRegistered = false;

/** 页面是否已加载完成 (onPageEnd 后为 true)。 */
let pageReady = false;

/** 已注入参数的去重键 (页面重载时由 handlePageBegin 复位)。 */
let lastInjected: string = '';

/** javaScriptProxy 对象: viewer 页面内 `legadoMarkdownBridge.openLink(url)` 调用。 */
class MarkdownJsProxy {
  openLink(url: string): void {
    if (url == null || url.length === 0) {
      return;
    }
    if (eventCallback != null) {
      const payload: MarkdownEventPayload = { action: 'openLink', url: url };
      eventCallback(JSON.stringify(payload));
    }
  }
}

const jsProxy = new MarkdownJsProxy();

/**
 * 初始化 Markdown 查看器桥 (Index.ets aboutToAppear 调用)。
 *
 * @param controller 查看器 Web 组件的 WebviewController (与 Index.ets interop builder 绑定)
 * @param onEvent ArkTS → KMP 事件回送函数 (EntryAbility 注入 legado.markdownEvent)
 */
export function initMarkdownBridge(controller: webview.WebviewController, onEvent: (json: string) => void): void {
  webController = controller;
  eventCallback = onEvent;
  // 预构建 viewer HTML (composeResources 直读内联, 纯内存读取, 主线程同步安全; 进程内缓存)
  try {
    viewerHtml = legado.buildMarkdownViewerHtml();
    console.info('[MarkdownBridge] viewer html built, length=' + viewerHtml.length);
  } catch (e) {
    console.error('[MarkdownBridge] buildMarkdownViewerHtml failed: ' + JSON.stringify(e));
    viewerHtml = '';
  }
  console.info('[MarkdownBridge] controller injected');
}

/**
 * 处理来自 KMP 的渲染请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param json 渲染请求 JSON `{ content, isDark, fontSize }`
 */
export function handleMarkdownRequest(json: string): void {
  try {
    latest = JSON.parse(json) as MarkdownRenderPayload;
  } catch (e) {
    console.error('[MarkdownBridge] parse request failed: ' + json);
    return;
  }
  injectLatest();
}

/** 页面开始加载 (Web 组件每次加载/重建都会触发): 复位注入去重键。 */
export function handlePageBegin(): void {
  pageReady = false;
  lastInjected = '';
}

/**
 * 页面加载完成 (onPageEnd): 状态机驱动 viewer 页面加载 → proxy 注册 → 内容注入。
 */
export function handlePageEnd(): void {
  // 1. 首次: 从 about:blank 切到 viewer 页面 (loadData 触发新一轮 onPageBegin/onPageEnd)
  if (!htmlLoaded) {
    htmlLoaded = true;
    if (viewerHtml.length > 0) {
      try {
        webController?.loadData(viewerHtml, 'text/html', 'utf-8', 'about:blank');
        console.info('[MarkdownBridge] viewer html loaded via loadData');
      } catch (e) {
        console.error('[MarkdownBridge] loadData failed: ' + JSON.stringify(e));
      }
    }
    return;
  }
  pageReady = true;
  // 2. javaScriptProxy 未注册: 注册后重新 loadData (proxy 对后续加载的页面生效)
  if (!proxyRegistered) {
    try {
      webController?.registerJavaScriptProxy(jsProxy, 'legadoMarkdownBridge', ['openLink']);
      proxyRegistered = true;
      console.info('[MarkdownBridge] js proxy registered, reload viewer to apply');
      if (viewerHtml.length > 0) {
        webController?.loadData(viewerHtml, 'text/html', 'utf-8', 'about:blank');
      }
    } catch (e) {
      console.error('[MarkdownBridge] registerJavaScriptProxy failed: ' + JSON.stringify(e));
    }
    return;
  }
  // 3. 就绪: 注入最新渲染参数
  injectLatest();
}

/** 按最新渲染参数注入 (页面未就绪/无参数/无 viewer 页面/内容未变化时跳过)。 */
function injectLatest(): void {
  if (!pageReady || latest == null || webController == null || viewerHtml.length === 0) {
    return;
  }
  const key = latest.content + '\u0001' + (latest.isDark ? '1' : '0') + '\u0001' + latest.fontSize;
  if (key === lastInjected) {
    return;
  }
  lastInjected = key;
  // JSON.stringify 自动转义引号/换行/控制字符, 注入为对象字面量安全
  const script = 'window.renderMarkdown(' + JSON.stringify(latest) + ')';
  try {
    webController.runJavaScript(script, (error: Error, result: string): void => {
      if (error != null) {
        console.error('[MarkdownBridge] render injection failed: ' + JSON.stringify(error));
        // 注入失败允许重试 (下一次 push / 页面重载时重注入)
        lastInjected = '';
      }
    });
  } catch (e) {
    console.error('[MarkdownBridge] runJavaScript failed: ' + JSON.stringify(e));
    lastInjected = '';
  }
}
