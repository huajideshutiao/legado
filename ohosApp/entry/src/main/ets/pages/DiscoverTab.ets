import promptAction from '@ohos.promptAction';
import legado from 'liblegado_napi.so';

/**
 * 发现源数据项 (字段对齐 KMP BookSourcePart 实体序列化 JSON)。
 *
 * 对应 shared/.../data/entities/BookSourcePart.kt (DatabaseView book_sources_part)。
 * napi exploreList() 返回 JSON 数组, 字段名与 BookSourcePart 一致, 可直接 JSON.parse 复用。
 */
interface ExploreSourceItem {
  bookSourceUrl: string;
  bookSourceName: string;
  bookSourceGroup: string;
  enabledExplore: boolean;
  hasLoginUrl: boolean;
  lastUpdateTime: number;
}

/**
 * 发现 Tab 内容组件 (嵌入 Index.ets BottomNavigation, 非 @Entry)。
 *
 * 对应 app 端 `ExploreTab` (app/.../ui/main/explore/ExploreTab.kt) +
 * shared `ExploreScreen` (shared/.../ui/main/explore/ExploreScreen.kt)。
 *
 * - 顶部 TitleBar: "发现" + 右侧刷新/菜单图标 (对照 ExploreTitleBar)
 * - Refresh + List 展示发现源 (源名称 / 源 URL / 最后更新时间)
 * - 点击 → bridge openExplore 打开源详情/书籍列表
 * - 长按 → ActionMenu (编辑/置顶/删除); 通过 bridge 调用对应方法
 *
 * 桥接说明: exploreList/openExplore/editExploreSource/topExploreSource/deleteExploreSource
 * 已实现 (Index.d.ts + C++ 侧), 但 Compose 路径不经 napi; 运行时调用若异常,
 * 由 try/catch 降级为空列表/Toast 提示 (与桥接异常时的 mock 兜底哲学一致,
 * 同 BookshelfTab.ets loadBooks 模式)。
 */
@Component
export struct DiscoverTab {
  @State sources: ExploreSourceItem[] = [];
  @State refreshing: boolean = false;

  aboutToAppear(): void {
    this.loadSources();
  }

  // 调用 napi exploreList() 加载发现源; 异常时显示空占位
  private loadSources(): void {
    try {
      const json = legado.exploreList();
      this.sources = JSON.parse(json) as ExploreSourceItem[];
    } catch (e) {
      this.sources = [];
    }
  }

  // 下拉刷新: 同步加载后关闭指示器 (对齐 BookshelfTab.ets onRefresh)
  private onRefresh(): void {
    this.loadSources();
    this.refreshing = false;
  }

  // 时间戳格式化 (对齐 BookshelfTab.ets/Bookshelf.ets formatTime)
  private formatTime(millis: number): string {
    if (millis <= 0) {
      return '未知';
    }
    const date = new Date(millis);
    const y = date.getFullYear();
    const m = (date.getMonth() + 1).toString().padStart(2, '0');
    const d = date.getDate().toString().padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  // 点击源项: 通过 bridge 打开源详情/书籍列表
  private openSource(item: ExploreSourceItem): void {
    try {
      legado.openExplore(item.bookSourceUrl, '');
    } catch (e) {
      // 桥接未就绪, Toast 提示 (对齐 BookshelfTab.ets showBookMenu 兜底模式)
      promptAction.showToast({ message: '桥接未就绪, 无法打开源详情' });
    }
  }

  // 长按弹出操作菜单 (编辑/置顶/删除, 对齐 BookshelfTab.ets showBookMenu 模式)
  private showSourceMenu(item: ExploreSourceItem): void {
    promptAction.showActionMenu({
      title: item.bookSourceName,
      buttons: [
        { text: '编辑', color: '#212121' },
        { text: '置顶', color: '#212121' },
        { text: '删除', color: '#D32F2F' }
      ]
    } as promptAction.ActionMenuOptions).then((result: promptAction.ActionMenuSuccessResponse) => {
      this.onMenuAction(result.index, item);
    });
  }

  // 菜单项动作: 通过 bridge 执行; 置顶/删除后重载列表
  private onMenuAction(index: number, item: ExploreSourceItem): void {
    try {
      if (index === 0) {
        // 编辑
        legado.editExploreSource(item.bookSourceUrl);
      } else if (index === 1) {
        // 置顶
        legado.topExploreSource(item.bookSourceUrl);
        this.loadSources();
      } else if (index === 2) {
        // 删除
        legado.deleteExploreSource(item.bookSourceUrl);
        this.loadSources();
      }
    } catch (e) {
      // 桥接未就绪, Toast 提示
      promptAction.showToast({ message: '桥接未就绪, 操作失败' });
    }
  }

  @Builder
  TitleBar() {
    Row() {
      // 标题左对齐 (对齐 BookshelfTab.ets TitleBar 布局)
      Text($r('app.string.discover_title'))
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .fontColor(Color.White)
        .layoutWeight(1)

      // 刷新图标 (↻)
      Text('↻')
        .fontSize(22)
        .fontColor(Color.White)
        .margin({ right: 16 })
        .onClick(() => this.loadSources())

      // 溢出菜单图标 (⋮, 预留分组菜单入口)
      Text('⋮')
        .fontSize(22)
        .fontColor(Color.White)
        .onClick(() => {
          promptAction.showToast({ message: '分组菜单待接入' });
        })
    }
    .width('100%')
    .height(56)
    .backgroundColor($r('app.color.app_primary'))
    .padding({ left: 16, right: 16 })
  }

  @Builder
  EmptyState() {
    Column() {
      Text($r('app.string.discover_empty'))
        .fontSize(16)
        .fontColor($r('app.color.text_secondary'))
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  SourceItem(item: ExploreSourceItem) {
    Row({ space: 12 }) {
      // 源信息列: 名称 / URL / 最后更新时间
      Column({ space: 4 }) {
        Text(item.bookSourceName)
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor($r('app.color.text_primary'))
          .maxLines(1)
        Text(item.bookSourceUrl)
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .maxLines(1)
        Text(`更新: ${this.formatTime(item.lastUpdateTime)}`)
          .fontSize(10)
          .fontColor($r('app.color.text_secondary'))
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)

      // 右侧展开箭头 (对齐 app 端 ic_arrow_right)
      Text('›')
        .fontSize(20)
        .fontColor($r('app.color.text_secondary'))
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(Color.White)
    .borderRadius(8)
    .onClick(() => this.openSource(item))
    .gesture(
      LongPressGesture({ duration: 500 })
        .onAction(() => this.showSourceMenu(item))
    )
  }

  build() {
    Column() {
      this.TitleBar()

      Refresh({ refreshing: $$this.refreshing }) {
        if (this.sources.length === 0) {
          this.EmptyState()
        } else {
          List({ space: 8 }) {
            ForEach(this.sources, (item: ExploreSourceItem) => {
              ListItem() {
                this.SourceItem(item)
              }
            }, (item: ExploreSourceItem) => item.bookSourceUrl)
          }
          .width('100%')
          .layoutWeight(1)
          .backgroundColor($r('app.color.app_background'))
        }
      }
      .width('100%')
      .layoutWeight(1)
      .onRefreshing(() => this.onRefresh())
    }
    .width('100%')
    .height('100%')
  }
}
