import { ArkUIViewController, Compose } from '@cpf-kmp-cmp/compose';
import { hilog } from '@kit.PerformanceAnalysisKit';
import webview from '@ohos.web.webview';
import legado from 'liblegado_napi.so';
import { initWebViewBridge, handlePageEnd, handleErrorReceive, handleInterceptRequest } from '../bridge/WebViewBridgeHandler';

const DOMAIN: number = 0x0000;

/**
 * Legado 鸿蒙 Compose 入口。
 *
 * `@cpf-kmp-cmp/compose` 会依据 lib 中的 backend id 使用 FusionRendererCompose，
 * 把 Compose 绘制接入 ArkUI RenderNode；本页只负责控制器与完整生命周期转发。
 *
 * 隐藏 Web 组件: 书源 webView/webViewGetSource 规则的后台抓取承载 (K/N 无法实例化
 * ArkUI Web 组件, 由本页挂载一个隐藏 Web + WebviewController, WebViewBridgeHandler 驱动,
 * 见 bridge/WebViewBridgeHandler.ets)。
 */
@Entry
@Component
struct Index {
  private controller: ArkUIViewController | undefined = undefined;
  private webController: webview.WebviewController = new webview.WebviewController();
  @State private webSrc: string = 'about:blank';
  @State private errorMessage: string = 'Compose controller is not ready';

  aboutToAppear(): void {
    try {
      // 隐藏 Web 组件控制器注入 (须在首次 handleWebViewRequest 之前)
      initWebViewBridge(this.webController);
      this.controller = legado.MainArkUIViewController();
      if (!this.controller) {
        this.errorMessage = 'Compose controller creation returned null';
      }
    } catch (e) {
      const detail = JSON.stringify(e);
      hilog.error(DOMAIN, 'LegadoCompose', 'controller creation failed: %{public}s', detail);
      this.errorMessage = 'Compose controller creation failed: ' + detail;
    }
  }

  build() {
    Column() {
      if (this.controller) {
        Compose({
          controller: this.controller,
          libraryName: 'legado_napi',
          onBackPressed: (): boolean => this.controller!.onBackPress()
        })
      } else {
        Text(this.errorMessage)
      }
      // 后台 WebView: 隐藏组件承载书源 webView 规则 (加载 + runJavaScript 取源码)
      // visibility(Hidden) 不入渲染但仍执行网络请求与 JS; 尺寸 1vp 避免 0 尺寸组件被销毁
      Web({ src: this.webSrc, controller: this.webController })
        .visibility(Hidden)
        .width(1)
        .height(1)
        .onPageEnd((event: webview.PageEndEvent): void => {
          handlePageEnd();
        })
        .onErrorReceive((event: webview.OnErrorReceiveEvent): void => {
          handleErrorReceive(event.error.getErrorInfo());
        })
        .onInterceptRequest((event: webview.WebResourceRequest): webview.WebResourceResponse | undefined => {
          return handleInterceptRequest(event);
        })
    }
    .width('100%')
    .height('100%')
  }

  onPageHide(): void {
    this.controller?.onPageHide();
  }

  onPageShow(): void {
    this.controller?.onPageShow();
  }

  onBackPressed(): boolean {
    return this.controller ? this.controller.onBackPress() : false;
  }
}
