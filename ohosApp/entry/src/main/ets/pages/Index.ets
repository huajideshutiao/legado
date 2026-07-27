import router from '@ohos.router';

/**
 * 首页 (路由入口) - 提供跳转书架 / KMP 互操作测试的入口按钮。
 *
 * 对应 Android 端 `MainScreen` (app/src/main/java/io/legado/app/ui/main/MainScreen.kt) 的简化版。
 * 当前为最小可运行骨架, 实际书架内容在 `pages/Bookshelf`。
 *
 * 设计风格: 鸿蒙原生 ArkTS 风格 (不强制 Arco Design), 参照 Android 端
 * 主屏布局: 顶部标题栏 + 中部入口按钮列表。
 */
@Entry
@Component
struct Index {
  build() {
    Column() {
      // 顶部标题栏 (参照 Android Material AppBar)
      // 标题改为"阅读" (与 desktop Main.kt 窗口标题一致, 用户反馈: 应用名应该就叫阅读)
      Text('阅读')
        .width('100%')
        .height(56)
        .fontSize(22)
        .fontWeight(FontWeight.Bold)
        .fontColor($r('app.color.text_primary'))
        .backgroundColor($r('app.color.app_primary'))
        .textAlign(TextAlign.Center)
        .padding({ left: 16, right: 16 })

      // 中部入口列表
      Column({ space: 12 }) {
        Button('进入书架')
          .width('80%')
          .height(48)
          .fontSize(16)
          .backgroundColor($r('app.color.app_primary'))
          .fontColor(Color.White)
          .onClick(() => {
            router.pushUrl({ url: 'pages/Bookshelf' });
          })

        Button('KMP 互操作测试')
          .width('80%')
          .height(48)
          .fontSize(16)
          .backgroundColor($r('app.color.app_background'))
          .fontColor($r('app.color.text_primary'))
          .onClick(() => {
            router.pushUrl({ url: 'pages/KmpInteropTest' });
          })
      }
      .width('100%')
      .layoutWeight(1)
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)

      // 底部版本信息
      Text('Legado KMP · 鸿蒙 target · KP5 真实数据')
        .width('100%')
        .height(36)
        .fontSize(12)
        .fontColor($r('app.color.text_secondary'))
        .textAlign(TextAlign.Center)
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.app_background'))
  }
}
