import { ArkUIViewController, Compose } from '@cpf-kmp-cmp/compose';
// 视频画面 surface 的 Compose interop builder 注册 API (ArkUIView2 混排 ArkTS XComponent)。
// 注: 包根 Index.d.ets 未声明该导出, 若编译期报 "no exported member" 需改从子模块导入:
//   import { registerComposeInteropBuilder } from '@cpf-kmp-cmp/compose/src/main/ets/compose/ArkUIView'
import { registerComposeInteropBuilder } from '@cpf-kmp-cmp/compose';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { taskpool } from '@kit.ArkTS';
import webview from '@ohos.web.webview';
import http from '@ohos.net.http';
import buffer from '@ohos.buffer';
import promptAction from '@ohos.promptAction';
import { fileIo as fs } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { abilityAccessCtrl, common } from '@kit.AbilityKit';
import legado from 'liblegado_napi.so';
import { initWebViewBridge, handlePageEnd, handleErrorReceive, handleInterceptRequest } from '../bridge/WebViewBridgeHandler';
import {
  initTextActionBridge,
  fireTextAction,
  dismissTextActionMenu,
  type TextActionMenuData
} from '../bridge/TextActionBridgeHandler';
import { initKeyboardBridge } from '../bridge/KeyboardBridgeHandler';
import { setVideoSurfaceId } from '../bridge/MediaBridgeHandler';

const DOMAIN: number = 0x0000;

// 视频画面 surface builder (K/N OhosVideoPlayPlatformProvider 的 ArkUIView2(name="legadoVideoSurface")
// 混排到 Compose 层级时由 CPF 调用)。param 为 K/N 侧传入的 interop 参数 (含 playerId)。
// XComponent(type:'surface').onLoad 拿到 surfaceId 后交给 MediaBridgeHandler 绑定到 videoBook
// AVPlayer (player.surfaceId = surfaceId), 否则视频只能出声没有画面。
@Builder
function LegadoVideoSurfaceBuilder(param: object) {
  XComponent({ id: 'legadoVideoSurface', type: 'surface' })
    .onLoad((context: object): void => {
      const ctx = context as XComponentContext;
      setVideoSurfaceId(ctx.surfaceId);
    })
    .width('100%')
    .height('100%')
}

// taskpool worker 任务: 调 K/N 带书源 header 的下载管线 (base64 返回)。
// 必须离主线程执行: K/N 下载内部走 invokeHttpSync → tsfn → 主线程处理回调,
// 主线程直接调用会因主线程被阻塞而无法处理 HTTP 回调 → 死锁超时
// (与 bookshelfList 等业务函数同约束, 见 Index.d.ts downloadImageBytes 注释)。
function loadImageBytesTask(url: string): string {
  return legado.downloadImageBytes(url);
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
  // 文本操作浮动菜单状态 (长按选字 → KMP → handleShowTextActionMenu 更新)
  @State private textMenu: TextActionMenuData = { visible: false, text: '', x: 0, y: 0, src: '', type: 'text', menuItems: [] };
  // 图片全屏预览状态 (图片菜单"查看": 叠层 Image 加载网络图, 点击关闭)
  @State private imagePreviewVisible: boolean = false;
  @State private imagePreviewSrc: string = '';

  aboutToAppear(): void {
    try {
      // 文本操作菜单状态注入 (KMP 经 tsfn 更新菜单显隐/定位)
      initTextActionBridge((data: TextActionMenuData): void => {
        this.textMenu = data;
      });
      // 隐藏 Web 组件控制器注入 (须在首次 handleWebViewRequest 之前)
      initWebViewBridge(this.webController);
      // 软键盘桥: 注入 UIContext (setKeyboardAvoidMode 需要; hide/show 走 @ohos.inputMethod 不依赖)。
      // 独立 try: 失败不应阻断 Compose 控制器创建 (仅 setAvoidMode 不可用)。
      try {
        initKeyboardBridge(this.getUIContext());
      } catch (e) {
        hilog.warn(DOMAIN, 'LegadoCompose', 'initKeyboardBridge failed (keyboard avoid mode unavailable): %{public}s', JSON.stringify(e));
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
    Stack({ alignContent: Alignment.TopStart }) {
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

      // 文本操作浮动菜单叠层 (平台原生 ArkUI 组件, 对标 Android ActionMode 浮动菜单):
      // 全屏半透明遮罩 (点外部收起 → __dismiss 取消选择) + 锚点定位的菜单气泡
      // 菜单项清单由 K/N 下发 (TextActionBridgeHandler 已归一化: menuItems 优先,
      // 缺失回退内置 TEXT_ACTION_ITEMS / IMAGE_ACTION_ITEMS)
      if (this.textMenu.visible) {
        Column() {
          Column() {
            ForEach(this.textMenu.menuItems, (item: { label: string; action: string }): void => {
              Text(item.label)
                .fontSize(16)
                .fontColor(Color.Black)
                .width('100%')
                .height(44)
                .textAlign(TextAlign.Center)
                .onClick((event: ClickEvent): void => {
                  event.stopPropagation();
                  this.onMenuActionClick(item.action, this.textMenu.text, this.textMenu.src);
                })
            }, (item: { label: string; action: string }): string => item.action)
          }
          .backgroundColor(Color.White)
          .borderRadius(12)
          .padding({ top: 4, bottom: 4 })
          .position({ x: this.textMenu.x, y: this.textMenu.y })
          .shadow({ radius: 16, color: 'rgba(0,0,0,0.2)' })
          .onClick((event: ClickEvent): void => {
            // 阻止冒泡到遮罩 (点菜单内部不收起)
            event.stopPropagation();
          })
        }
        .width('100%')
        .height('100%')
        .backgroundColor('rgba(0,0,0,0.25)')
        .onClick((): void => {
          dismissTextActionMenu();
        })
      }

      // 图片全屏预览叠层 (图片菜单"查看"): 黑底 + Image 网络图 (objectFit Contain),
      // 点击任意处关闭 (对照原版 PhotoDialog 查看大图)
      if (this.imagePreviewVisible) {
        Stack({ alignContent: Alignment.Center }) {
          Image(this.imagePreviewSrc)
            .width('100%')
            .height('100%')
            .objectFit(ImageFit.Contain)
            .onClick((): void => {
              this.imagePreviewVisible = false;
            })
        }
        .width('100%')
        .height('100%')
        .backgroundColor(Color.Black)
        .onClick((): void => {
          this.imagePreviewVisible = false;
        })
      }
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

  /**
   * 浮动菜单项点击分发:
   * - 文本菜单: 全部经 fireTextAction 回送 KMP (对照原版 TextActionMenu 动作)
   * - 图片菜单: view → 叠层全屏预览 (ArkTS 本地); save → 下载存系统相册 (ArkTS 本地);
   *   refresh → 回送 KMP 清 ReaderImageCache + 重排 (见 OhosReaderPlatformProvider.onTextAction)
   */
  private onMenuActionClick(action: string, text: string, src: string): void {
    if (this.textMenu.type === 'image') {
      if (action === 'view') {
        this.imagePreviewSrc = src;
        this.imagePreviewVisible = true;
        dismissTextActionMenu();
      } else if (action === 'save') {
        dismissTextActionMenu();
        this.saveImageToGallery(src);
      } else {
        // refresh: 回送 KMP 处理 (清缓存 + 重排)
        fireTextAction(action, text, src);
      }
    } else {
      fireTextAction(action, text, src);
    }
  }

  /**
   * 保存图片到系统相册 (图片菜单"保存到相册", 对标原版 menu_save):
   * 1. 下载图片字节 —— 优先复用 shared 下载管线 (K/N downloadImageBytes: AnalyzeUrlCore 带
   *    书源 header/cookie/charset/JS + 解密, 解决防盗链), 经 TaskPool 离主线程执行
   *    (K/N 下载内部走 HTTP 桥 tsfn → 主线程回调, 主线程直接调会死锁超时);
   *    失败/无活动阅读书时回退 @ohos.net.http 裸下载 (保留旧行为)
   * 2. 检查/申请 ohos.permission.WRITE_IMAGEVIDEO (user_grant, 已在 module.json5 声明;
   *    未授予时经 abilityAccessCtrl.requestPermissionsFromUser 弹窗申请)
   * 3. @ohos.file.photoAccessHelper getPhotoAccessHelper.createAsset 创建图库资源 URI
   *    (PhotoType.IMAGE + 扩展名), 再 fileIo.openSync(uri, READ_WRITE|CREATE) + writeSync
   *    把下载字节写入该 URI (createAsset 后 URI 即指向图库中的新文件, 无需再走沙盒中转)
   */
  private async saveImageToGallery(src: string): Promise<void> {
    try {
      // 1. 下载图片字节
      const bytes = await this.downloadImageBytes(src);
      if (bytes == null || bytes.byteLength === 0) {
        promptAction.showToast({ message: '保存图片失败: 图片为空', duration: 2000 });
        return;
      }

      // 2. 权限检查 + 运行时申请 (user_grant 权限, module.json5 已声明)
      const context = getContext(this) as common.UIAbilityContext;
      const atManager = abilityAccessCtrl.createAtManager();
      let granted = await atManager.checkAccessToken(
        context.applicationInfo.accessTokenId, 'ohos.permission.WRITE_IMAGEVIDEO'
      ) === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      if (!granted) {
        const result = await atManager.requestPermissionsFromUser(
          context, ['ohos.permission.WRITE_IMAGEVIDEO']
        );
        granted = result.authResults.length > 0 &&
          result.authResults[0] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      }
      if (!granted) {
        promptAction.showToast({ message: '保存图片失败: 未授予相册权限', duration: 2000 });
        return;
      }

      // 3. photoAccessHelper.createAsset 创建图库资源 + fileIo 写入字节
      const phAccessHelper = photoAccessHelper.getPhotoAccessHelper(context);
      const uri = await phAccessHelper.createAsset(photoAccessHelper.PhotoType.IMAGE, this.guessImageExtension(src));
      const file = fs.openSync(uri, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE);
      try {
        fs.writeSync(file.fd, bytes);
      } finally {
        fs.closeSync(file);
      }
      promptAction.showToast({ message: '已保存到相册', duration: 2000 });
    } catch (e) {
      console.error('[LegadoCompose] save image failed: ' + JSON.stringify(e) + ', src=' + src);
      promptAction.showToast({ message: '保存图片失败', duration: 2000 });
    }
  }

  /**
   * 下载图片字节: 优先 K/N 共享下载管线 (带书源 header 防盗链, TaskPool 离主线程执行),
   * 失败回退 @ohos.net.http 裸下载。
   */
  private async downloadImageBytes(src: string): Promise<ArrayBuffer | undefined> {
    // 优先 K/N 下载管线 (TaskPool worker, 避免主线程死锁)
    try {
      const b64 = await taskpool.execute(new taskpool.Task(loadImageBytesTask, src)) as string;
      if (b64 != null && b64.length > 0) {
        const buf = buffer.from(b64, 'base64');
        const ab = new ArrayBuffer(buf.byteLength);
        const view = new Uint8Array(ab);
        view.set(new Uint8Array(buf.buffer, 0, buf.byteLength));
        if (ab.byteLength > 0) {
          console.info('[LegadoCompose] image downloaded via K/N pipeline, bytes=' + ab.byteLength);
          return ab;
        }
      }
      console.warn('[LegadoCompose] K/N image download returned empty, fallback to bare http');
    } catch (e) {
      console.warn('[LegadoCompose] K/N image download failed, fallback to bare http: ' + JSON.stringify(e));
    }
    // 回退: @ohos.net.http 裸下载 (拿不到书源 header, 防盗链图可能失败, 保留旧行为)
    const httpRequest = http.createHttp();
    try {
      const response = await httpRequest.request(src, {
        method: http.RequestMethod.GET,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
      });
      if (response.responseCode === 200 && response.result instanceof ArrayBuffer) {
        return response.result as ArrayBuffer;
      }
    } catch (e) {
      console.error('[LegadoCompose] bare http download failed: ' + JSON.stringify(e) + ', src=' + src);
    } finally {
      httpRequest.destroy();
    }
    return undefined;
  }

  /** 从 URL 猜测图片扩展名 (createAsset 的 extension 参数, 缺省 jpg)。 */
  private guessImageExtension(src: string): string {
    const lower = src.toLowerCase();
    if (lower.includes('.png')) return 'png';
    if (lower.includes('.webp')) return 'webp';
    if (lower.includes('.gif')) return 'gif';
    if (lower.includes('.bmp')) return 'bmp';
    return 'jpg';
  }
}
