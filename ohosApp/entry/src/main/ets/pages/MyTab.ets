import promptAction from '@ohos.promptAction';

// 功能入口项模型 (对齐 app 端 MyConfigScreen 条目)
interface MyTabItem {
  title: string;
  summary: string;
  icon: string;  // 图标占位字符 (后续接真实图标资源)
}

/**
 * 我的 tab (ArkTS UI)。
 *
 * 对应 Android 端 `MyTab` + `MyConfigScreen` (app/.../ui/main/my/MyTab.kt +
 * shared/.../ui/main/my/MyConfigScreen.kt)。
 *
 * 顶部应用信息 (图标/名称/版本号) + 中部功能入口列表 + 底部 WebDav 同步状态。
 * 当前各入口的 bridge 跳转均未实现, 点击统一提示"功能开发中"。
 */
@Entry
@Component
struct MyTab {
  // 功能入口列表 (顺序对齐 app 端 MyConfigScreen)
  private items: MyTabItem[] = [
    { title: '书源管理', summary: '管理本地/网络书源', icon: '书' },
    { title: '订阅源管理', summary: '管理 RSS 订阅源', icon: '订' },
    { title: '替换规则', summary: '内容净化与替换', icon: '换' },
    { title: '备份/恢复', summary: 'WebDav 与本地备份', icon: '备' },
    { title: '主题设置', summary: '主题色/字号/行距', icon: '主' },
    { title: '其他设置', summary: '阅读/翻页/服务', icon: '其' },
    { title: '关于', summary: '版本/帮助/源码', icon: '关' },
  ];

  // bridge 跳转未实现时的兜底提示
  private showTodo(): void {
    promptAction.showToast({ message: '功能开发中', duration: 2000 });
  }

  build() {
    Column() {
      // 顶部应用信息 (图标 + 名称 + 版本号)
      Column({ space: 8 }) {
        // 应用图标占位
        Text('阅')
          .width(72)
          .height(72)
          .fontSize(40)
          .fontColor(Color.White)
          .backgroundColor($r('app.color.app_primary'))
          .borderRadius(16)
          .textAlign(TextAlign.Center)
        // 应用名称 (与 desktop Main.kt 窗口标题一致)
        Text('阅读')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.text_primary'))
        // 版本号 (与 Index.ets 底部版本信息风格一致)
        Text('Legado KMP · 鸿蒙 KP8+ 预览')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
      }
      .width('100%')
      .padding({ top: 32, bottom: 24 })
      .alignItems(HorizontalAlign.Center)

      // 功能入口列表
      List({ space: 1 }) {
        ForEach(this.items, (item: MyTabItem) => {
          ListItem() {
            Row({ space: 12 }) {
              // 图标占位 (圆形背景 + 字符)
              Text(item.icon)
                .width(40)
                .height(40)
                .fontSize(18)
                .fontColor(Color.White)
                .backgroundColor($r('app.color.app_primary'))
                .borderRadius(20)
                .textAlign(TextAlign.Center)
              // 标题 + 摘要
              Column({ space: 2 }) {
                Text(item.title)
                  .fontSize(16)
                  .fontColor($r('app.color.text_primary'))
                Text(item.summary)
                  .fontSize(12)
                  .fontColor($r('app.color.text_secondary'))
              }
              .layoutWeight(1)
              .alignItems(HorizontalAlign.Start)
              // 右箭头
              Text('>')
                .fontSize(18)
                .fontColor($r('app.color.text_secondary'))
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
            .backgroundColor(Color.White)
            .onClick(() => {
              this.showTodo();
            })
          }
        }, (item: MyTabItem) => item.title)
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor($r('app.color.app_background'))
      .divider({ strokeWidth: 1, color: '#EEEEEE', startMargin: 68, endMargin: 16 })

      // 底部 WebDav 同步状态
      Row({ space: 8 }) {
        // 同步状态指示点
        Text('●')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
        Text('WebDav: 未配置')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
      }
      .width('100%')
      .height(36)
      .backgroundColor(Color.White)
      .padding({ left: 16, right: 16 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.app_background'))
  }
}
