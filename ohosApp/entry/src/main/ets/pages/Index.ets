import { ArkUIViewController, Compose } from '@cpf-kmp-cmp/compose';
import { hilog } from '@kit.PerformanceAnalysisKit';
import webview from '@ohos.web.webview';
import http from '@ohos.net.http';
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
  TEXT_ACTION_ITEMS,
  IMAGE_ACTION_ITEMS,
  type TextActionMenuData
} from '../bridge/TextActionBridgeHandler';

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
  // 文本操作浮动菜单状态 (长按选字 → KMP → handleShowTextActionMenu 更新)
  @State private textMenu: TextActionMenuData = { visible: false, text: '', x: 0, y: 0, src: '', type: 'text' };
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
      // 图片长按菜单复用同一叠层, 按 type 切换菜单项 (TEXT_ACTION_ITEMS / IMAGE_ACTION_ITEMS)
      if (this.textMenu.visible) {
        Column() {
          Column() {
            ForEach(this.textMenu.type === 'image' ? IMAGE_ACTION_ITEMS : TEXT_ACTION_ITEMS, (item: { label: string; action: string }): void => {
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
   * 1. @ohos.net.http 下载图片字节 (ARRAY_BUFFER)
   * 2. 检查/申请 ohos.permission.WRITE_IMAGEVIDEO (user_grant, 已在 module.json5 声明;
   *    未授予时经 abilityAccessCtrl.requestPermissionsFromUser 弹窗申请)
   * 3. @ohos.file.photoAccessHelper getPhotoAccessHelper.createAsset 创建图库资源 URI
   *    (PhotoType.IMAGE + 扩展名), 再 fileIo.openSync(uri, READ_WRITE|CREATE) + writeSync
   *    把下载字节写入该 URI (createAsset 后 URI 即指向图库中的新文件, 无需再走沙盒中转)
   *
   * 注: 若书源图片带防盗链 header, 此处裸下载可能失败 (对照原版会用书源 header 下载;
   * 鸿蒙端 ArkTS 拿不到书源 header, 先保持裸下载, 失败 toast 提示)。
   */
  private async saveImageToGallery(src: string): Promise<void> {
    try {
      // 1. 下载图片字节
      const httpRequest = http.createHttp();
      let bytes: ArrayBuffer | undefined;
      try {
        const response = await httpRequest.request(src, {
          method: http.RequestMethod.GET,
          expectDataType: http.HttpDataType.ARRAY_BUFFER,
        });
        if (response.responseCode !== 200 || !(response.result instanceof ArrayBuffer)) {
          promptAction.showToast({ message: '保存图片失败: 下载失败', duration: 2000 });
          return;
        }
        bytes = response.result as ArrayBuffer;
      } finally {
        httpRequest.destroy();
      }
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
