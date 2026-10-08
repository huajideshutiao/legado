import { ArkUIViewController, Compose } from '@cpf-kmp-cmp/compose';
// 视频画面 surface 的 Compose interop builder 注册 API (ArkUIView2 混排 ArkTS XComponent)。
// 包根 Index.d.ets 未 re-export 该函数, 只能从子模块直接导入。
import { registerComposeInteropBuilder } from '@cpf-kmp-cmp/compose/src/main/ets/compose/ArkUIView';
import { hilog } from '@kit.PerformanceAnalysisKit';
import webview from '@ohos.web.webview';
import legado from 'liblegado_napi.so';
import { initWebViewBridge, handlePageEnd, handleErrorReceive, handleInterceptRequest } from '../bridge/WebViewBridgeHandler';
import {
  initMarkdownBridge,
  handlePageBegin as handleMarkdownPageBegin,
  handlePageEnd as handleMarkdownPageEnd
} from '../bridge/MarkdownBridgeHandler';
import { initKeyboardBridge } from '../bridge/KeyboardBridgeHandler';
import { initSystemBridgeUiContext } from '../bridge/SystemBridgeHandler';
import { setVideoSurfaceId, setVideoPipSurfaceController, getVideoPixelSize, Size2D } from '../bridge/MediaBridgeHandler';

const DOMAIN: number = 0x0000;

// 视频画面 surface builder (K/N OhosVideoPlayPlatformProvider 的 ArkUIView2(name="legadoVideoSurface")
// 混排到 Compose 层级时由 CPF 调用)。param 为 K/N 侧传入的 interop 参数 (含 playerId)。
// 纯 ArkTS XComponent 的 onLoad 不带 surfaceId (回调参数是 native 侧自定义 context), 故绑
// XComponentController, onLoad 后经 getXComponentSurfaceId() 取 id 交给 MediaBridgeHandler
// 绑定到 videoBook AVPlayer (player.surfaceId = surfaceId), 否则视频只能出声没有画面。
const videoSurfaceController: XComponentController = new XComponentController();

/**
 * 视频画面 (XComponent surface + 画面缩放)。
 *
 * XComponent 是原生 surface 混排节点, Compose 的图层变换作用不到它, 所以缩放只能在本组件做:
 * Kotlin 侧把手势算出的 VideoZoom 经 media 命令通道推给 MediaBridgeHandler 写进 AppStorage,
 * 这里用 @StorageLink 订阅。
 *
 * - 缩放: scale 以组件中心为锚
 * - 平移: offset 按“画面 FIT 后的显示尺寸 × 倍数 − 容器”折算成 vp, 与 Kotlin 手势层同一口径
 * - 外层 Stack 裁剪: 放大后的画面不能盖到选集网格/控制栏以外的区域
 *
 * SURFACE 类盯能设置的动态属性以本机 SDK 组件文档为准: 从 API 18 起不支持清单是
 * background/animation/gesture/clip/align 等, 不含 scale/offset。
 */
@Component
struct LegadoVideoSurface {
  // 键字面量必与 MediaBridgeHandler.ets 的 VIDEO_ZOOM_*_KEY 一致 (@StorageLink 的键要求字面量)
  @StorageLink('legadoVideoZoomScale') zoomScale: number = 1;
  @StorageLink('legadoVideoZoomPanX') panX: number = 0;
  @StorageLink('legadoVideoZoomPanY') panY: number = 0;
  @State boxWidth: number = 0;
  @State boxHeight: number = 0;

  build() {
    Stack() {
      XComponent({ id: 'legadoVideoSurface', type: XComponentType.SURFACE, controller: videoSurfaceController })
        .onLoad((): void => {
          setVideoSurfaceId(videoSurfaceController.getXComponentSurfaceId());
          // controller 一并注入画中画通道 (PiPWindow.create 的 componentController 复用本 XComponent 的 surface)
          setVideoPipSurfaceController(videoSurfaceController);
        })
        .onAreaChange((_oldArea: Area, newArea: Area): void => {
          this.boxWidth = newArea.width as number;
          this.boxHeight = newArea.height as number;
        })
        .width('100%')
        .height('100%')
        .scale({ x: this.zoomScale, y: this.zoomScale, centerX: '50%', centerY: '50%' })
        .offset({ x: this.panOffsetX(), y: this.panOffsetY() })
    }
    .width('100%')
    .height('100%')
    .clip(true)
  }

  /** 画面按宽高比 FIT 进容器的显示尺寸 (vp); 视频尺寸未知时按铺满容器 (同手势层的兜底口径)。 */
  private fitContent(): Size2D {
    const size: Size2D = getVideoPixelSize();
    if (size.width <= 0 || size.height <= 0 || this.boxWidth <= 0 || this.boxHeight <= 0) {
      return { width: this.boxWidth, height: this.boxHeight };
    }
    // px2vp 自 API 18 起弃用 (建议改走 UIContext.px2vp), 但后者需 API 18+ 而本工程兼容 API 12,
    // 故保留全局函数
    const videoWidth: number = px2vp(size.width);
    const videoHeight: number = px2vp(size.height);
    const fit: number = Math.min(this.boxWidth / videoWidth, this.boxHeight / videoHeight);
    return { width: videoWidth * fit, height: videoHeight * fit };
  }

  /** 横向平移量 (vp): 可平移范围 × 位置比例 (-1..1); 画面未超出容器时为 0 (居中)。 */
  private panOffsetX(): number {
    const content: Size2D = this.fitContent();
    return this.panX * Math.max(0, (content.width * this.zoomScale - this.boxWidth) / 2);
  }

  /** 同 [panOffsetX], 纵向。 */
  private panOffsetY(): number {
    const content: Size2D = this.fitContent();
    return this.panY * Math.max(0, (content.height * this.zoomScale - this.boxHeight) / 2);
  }
}

@Builder
function LegadoVideoSurfaceBuilder(param: object) {
  LegadoVideoSurface()
}

// ===== Markdown 查看器 Web 组件 (interop builder) =====
// K/N MarkdownContent 的 ArkUIView2(name="legadoMarkdownWeb") 混排到 Compose 层级时由 CPF 调用本
// builder, 在 Compose 槽位内创建一个 Web 组件。页面内容不是 rawfile: 完整 viewer HTML 由 K/N
// 运行时从 composeResources 直读模板 + marked/hljs/css 内联拼装 (legado.buildMarkdownViewerHtml),
// MarkdownBridgeHandler 在首个 onPageEnd 后 loadData 加载 (零拷贝零重复, 见 bridge/MarkdownBridgeHandler.ets)。
// controller 为模块级单例, 与后台抓取 Web 实例相互独立。
const markdownWebController: webview.WebviewController = new webview.WebviewController();

@Builder
function LegadoMarkdownWebBuilder(param: object) {
  Web({ src: 'about:blank', controller: markdownWebController })
    .width('100%')
    .height('100%')
    // 页面 body 透明 + 组件透明, 露出 Compose 侧对话框底色 (fillet), 亮暗主题由 CSS 切换
    .backgroundColor(Color.Transparent)
    .onPageBegin((): void => {
      handleMarkdownPageBegin();
    })
    .onPageEnd((): void => {
      handleMarkdownPageEnd();
    })
    .onErrorReceive((event: OnErrorReceiveEvent): void => {
      console.error('[LegadoMarkdown] page error: ' + event.error.getErrorInfo());
    })
}

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
      // Markdown 查看器桥注入 (K/N MarkdownContent 的 tsfn 渲染请求 + 链接回送事件)。
      // 独立 try: 失败不应阻断 Compose 控制器创建 (仅 Markdown 内容不渲染)。
      try {
        initMarkdownBridge(markdownWebController, (eventJson: string): void => {
          legado.markdownEvent(eventJson);
        });
      } catch (e) {
        hilog.warn(DOMAIN, 'LegadoCompose', 'initMarkdownBridge failed: %{public}s', JSON.stringify(e));
      }
      // 软键盘桥: 注入 UIContext (setKeyboardAvoidMode 需要; hide/show 走 @ohos.inputMethod 不依赖)。
      // 独立 try: 失败不应阻断 Compose 控制器创建 (仅 setAvoidMode 不可用)。
      try {
        initKeyboardBridge(this.getUIContext());
      } catch (e) {
        hilog.warn(DOMAIN, 'LegadoCompose', 'initKeyboardBridge failed (keyboard avoid mode unavailable): %{public}s', JSON.stringify(e));
      }
      // Toast 桥: 注入 UIContext (UIContext.getPromptAction().showToast 需要)。
      // 独立 try: 失败不应阻断 Compose 控制器创建 (仅 Toast 不可用)。
      try {
        initSystemBridgeUiContext(this.getUIContext());
      } catch (e) {
        hilog.warn(DOMAIN, 'LegadoCompose', 'initSystemBridgeUiContext failed (toast unavailable): %{public}s', JSON.stringify(e));
      }
      // 视频画面 surface builder 注册 (K/N ArkUIView2(name="legadoVideoSurface") 混排 XComponent 的入口;
      // 未注册时该 interop 节点为空, 视频只能出声。注册 key 与 OhosVideoPlayPlatformProvider.
      // ARKUI_BUILDER_VIDEO_SURFACE 字面量一致)。
      // 独立 try: 注册失败不应阻断 Compose 控制器创建 (视频退化为只能出声)。
      try {
        registerComposeInteropBuilder('legadoVideoSurface', LegadoVideoSurfaceBuilder);
      } catch (e) {
        hilog.warn(DOMAIN, 'LegadoCompose', 'registerComposeInteropBuilder failed (video surface unavailable): %{public}s', JSON.stringify(e));
      }
      // Markdown 查看器 Web 组件 builder 注册 (K/N ArkUIView2(name="legadoMarkdownWeb") 混排入口;
      // 未注册时该 interop 节点为空, Markdown 内容不渲染)。
      // 注册 key 与 MarkdownContent.ohos.kt ARKUI_BUILDER_MARKDOWN_WEB 字面量一致。
      try {
        registerComposeInteropBuilder('legadoMarkdownWeb', LegadoMarkdownWebBuilder);
      } catch (e) {
        hilog.warn(DOMAIN, 'LegadoCompose', 'registerComposeInteropBuilder failed (markdown web unavailable): %{public}s', JSON.stringify(e));
      }
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
        .visibility(Visibility.Hidden)
        .width(1)
        .height(1)
        .onPageEnd((event: OnPageEndEvent): void => {
          handlePageEnd();
        })
        .onErrorReceive((event: OnErrorReceiveEvent): void => {
          handleErrorReceive(event.error.getErrorInfo());
        })
        .onInterceptRequest((event: OnInterceptRequestEvent): WebResourceResponse | undefined => {
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
