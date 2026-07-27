import legado from 'liblegado_napi.so';

// Arco Design 主题色 (对齐 app 端 Arco 规范, arcoblue-6 选中态, 无阴影)
const ARCO_BLUE_6: string = '#165DFF';
const ARCO_GRAY_3: string = '#86909C';
const ARCO_BG_WHITE: string = '#FFFFFF';
const BOTTOM_BAR_HEIGHT: number = 56;

// Tab 标签常量 (对齐 shared/src/commonMain/.../constant/BottomNavTag.kt)
const NAV_TAG_HOME: string = 'home';
const NAV_TAG_BOOKSHELF: string = 'bookshelf';
const NAV_TAG_DISCOVERY: string = 'discovery';
const NAV_TAG_MY: string = 'my';
// 双击重选阈值 (对齐 app 端 MainActivity.onTabReselect 300ms)
const RESELECT_INTERVAL_MS: number = 300;

// 底栏配置 (对齐 app 端 computeVisibleTags + homePageIndex)
class BottomNavConfig {
  visibleTags: string[] = [];
  initialPage: number = 0;
}

// Tab 元数据
class BottomNavItem {
  tag: string = '';
  label: string = '';
  icon: string = '';
}

/**
 * 主界面入口 (路由 pages/Index) - BottomNavigation 容器骨架。
 *
 * 对齐 app 端 `MainScreen` + `MainBottomBar` (shared/src/sharedUiMain/.../ui/main/)。
 * - 4 个 tab: home/bookshelf/discovery/my (顺序与显隐通过 Bridge 读取, 失败降级默认值)
 * - 双击当前 tab 触发 onTabReselect (对齐 app 端 MainActivity.onTabReselect)
 * - 底栏 Arco Design arcoblue-6 选中色, 无阴影
 * - tab 内容当前为占位, 后续 KP 接入 HomeTab/BookshelfTab/ExploreTab/MyTab
 */
@Entry
@Component
struct Index {
  @State config: BottomNavConfig = new BottomNavConfig();
  @State currentIndex: number = 0;
  @State bookshelfCount: number = 0;
  // 双击重选检测时间戳与 tag
  private lastClickTime: number = 0;
  private lastClickTag: string = '';

  aboutToAppear(): void {
    this.config = this.loadBottomNavConfig();
    this.currentIndex = this.config.initialPage;
    this.loadBookshelfCount();
  }

  // 通过 Bridge 读取底栏配置 (对齐 app 端 computeVisibleTags/homePageIndex)
  // napi 桥接未暴露 AppConfig 字段, 当前用默认配置 + 桥接就绪校验
  private loadBottomNavConfig(): BottomNavConfig {
    const defaultTags: string[] = [
      NAV_TAG_HOME, NAV_TAG_BOOKSHELF, NAV_TAG_DISCOVERY, NAV_TAG_MY,
    ];
    // 桥接就绪校验 (napi isProvidersRegistered)
    try {
      legado.isProvidersRegistered();
    } catch (e) {
      // 桥接未就绪也用默认配置, UI 仍可展示骨架
    }
    // TODO: 桥接暴露 AppConfig.bottomNavItemOrder/showHome/showDiscovery/defaultHomePage 后从 Bridge 读取
    const cfg = new BottomNavConfig();
    cfg.visibleTags = defaultTags.slice();
    const initialPage = cfg.visibleTags.indexOf(NAV_TAG_BOOKSHELF);
    cfg.initialPage = initialPage >= 0 ? initialPage : 0;
    return cfg;
  }

  // 通过 Bridge 读取书架书籍数量 (napi bookshelfList, 复用 app 端 KMP 业务)
  private loadBookshelfCount(): void {
    try {
      const json = legado.bookshelfList();
      const list = JSON.parse(json) as Object[];
      this.bookshelfCount = list.length;
    } catch (e) {
      this.bookshelfCount = 0;
    }
  }

  // tab 点击: 切换或双击重选 (对齐 app 端 selectPage + onTabReselect)
  private onTabClick(tag: string, index: number): void {
    if (index === this.currentIndex) {
      const now = Date.now();
      if (now - this.lastClickTime < RESELECT_INTERVAL_MS && this.lastClickTag === tag) {
        this.onTabReselect(tag);
        this.lastClickTime = 0;
        this.lastClickTag = '';
      } else {
        this.lastClickTime = now;
        this.lastClickTag = tag;
      }
    } else {
      this.currentIndex = index;
      this.lastClickTime = 0;
      this.lastClickTag = '';
    }
  }

  // tab 重选行为 (对齐 app 端 onTabReselect: bookshelf gotoTop / discovery compressExplore)
  private onTabReselect(tag: string): void {
    switch (tag) {
      case NAV_TAG_BOOKSHELF:
        // app 端: bookshelfController?.gotoTop()
        console.info('[Legado] BottomNav reselect: bookshelf (gotoTop pending)');
        break;
      case NAV_TAG_DISCOVERY:
        // app 端: exploreController?.compressExplore()
        console.info('[Legado] BottomNav reselect: discovery (compressExplore pending)');
        break;
      default:
        console.info('[Legado] BottomNav reselect: ' + tag);
        break;
    }
  }

  private getItem(tag: string): BottomNavItem {
    const item = new BottomNavItem();
    item.tag = tag;
    switch (tag) {
      case NAV_TAG_HOME:
        item.label = '主页';
        item.icon = '🏠';
        break;
      case NAV_TAG_BOOKSHELF:
        item.label = '书架';
        item.icon = '📚';
        break;
      case NAV_TAG_DISCOVERY:
        item.label = '发现';
        item.icon = '🧭';
        break;
      case NAV_TAG_MY:
        item.label = '我的';
        item.icon = '👤';
        break;
      default:
        item.label = tag;
        item.icon = '?';
        break;
    }
    return item;
  }

  build() {
    Column() {
      // Tabs 容器 (barHeight=0 隐藏默认 bar, 用自定义底栏对齐 app 端 MainBottomBar)
      Tabs({ index: this.currentIndex, barHeight: 0 }) {
        ForEach(this.config.visibleTags, (tag: string) => {
          TabContent() {
            this.TabContentByTag(tag)
          }
          .tabBar('')
        }, (tag: string) => tag)
      }
      .onChange((index: number) => {
        // 滑动手势切换时同步 currentIndex + 重置双击状态
        this.currentIndex = index;
        this.lastClickTime = 0;
        this.lastClickTag = '';
      })
      .layoutWeight(1)
      .barMode(BarMode.Fixed)
      .backgroundColor($r('app.color.app_background'))

      // 自定义底栏 (对齐 app 端 MainBottomBar, 无阴影)
      this.BottomBar()
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  TabContentByTag(tag: string) {
    Column() {
      if (tag === NAV_TAG_BOOKSHELF) {
        // 通过 napi.bookshelfList 显示书籍数量占位 (复用 app 端 KMP 业务)
        Text(`书架 (${this.bookshelfCount} 本)`)
          .fontSize(16)
          .fontColor($r('app.color.text_primary'))
        Text('书架列表 UI 待接入 (后续 KP 重构 Bookshelf.ets 为可复用组件)')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .margin({ top: 8 })
      } else if (tag === NAV_TAG_HOME) {
        Text('主页')
          .fontSize(16)
          .fontColor($r('app.color.text_primary'))
        Text('待实现 (后续 KP 接入 HomeTab)')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .margin({ top: 8 })
      } else if (tag === NAV_TAG_DISCOVERY) {
        Text('发现')
          .fontSize(16)
          .fontColor($r('app.color.text_primary'))
        Text('待实现 (后续 KP 接入 ExploreTab)')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .margin({ top: 8 })
      } else {
        Text('我的')
          .fontSize(16)
          .fontColor($r('app.color.text_primary'))
        Text('待实现 (后续 KP 接入 MyTab)')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .margin({ top: 8 })
      }
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  BottomBar() {
    Row() {
      ForEach(this.config.visibleTags, (tag: string, index: number) => {
        Column() {
          Text(this.getItem(tag).icon)
            .fontSize(22)
            .fontColor(index === this.currentIndex ? ARCO_BLUE_6 : ARCO_GRAY_3)
          Text(this.getItem(tag).label)
            .fontSize(12)
            .fontColor(index === this.currentIndex ? ARCO_BLUE_6 : ARCO_GRAY_3)
            .margin({ top: 2 })
        }
        .layoutWeight(1)
        .height(BOTTOM_BAR_HEIGHT)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .onClick(() => this.onTabClick(tag, index))
      }, (tag: string) => tag)
    }
    .width('100%')
    .height(BOTTOM_BAR_HEIGHT)
    .backgroundColor(ARCO_BG_WHITE)
  }
}
