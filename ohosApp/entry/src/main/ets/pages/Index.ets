import { ArkUIViewController, Compose } from '@cpf-kmp-cmp/compose';
import { hilog } from '@kit.PerformanceAnalysisKit';
import legado from 'liblegado_napi.so';

const DOMAIN: number = 0x0000;

/**
 * Legado 鸿蒙 Compose 入口。
 *
 * `@cpf-kmp-cmp/compose` 会依据 lib 中的 backend id 使用 FusionRendererCompose，
 * 把 Compose 绘制接入 ArkUI RenderNode；本页只负责控制器与完整生命周期转发。
 */
@Entry
@Component
struct Index {
  private controller: ArkUIViewController | undefined = undefined;
  @State private errorMessage: string = 'Compose controller is not ready';

  aboutToAppear(): void {
    try {
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
