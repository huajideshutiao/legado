import promptAction from '@ohos.promptAction';
import legado from 'liblegado_napi.so';

/**
 * 书源数据项 (字段对齐 KMP BookSourcePart 实体序列化 JSON)。
 *
 * 对应 shared/.../data/entities/BookSourcePart.kt (DatabaseView book_sources_part)。
 * napi 端目前仅有 exploreList() (仅返回 enabledExplore=true 子集), 完整 bookSourceList() 待新增。
 */
interface BookSourceItem {
  bookSourceUrl: string;
  bookSourceName: string;
  bookSourceGroup: string;
  enabled: boolean;
  enabledExplore: boolean;
  hasLoginUrl: boolean;
  hasExploreUrl: boolean;
  customOrder: number;
  lastUpdateTime: number;
  weight: number;
}

// 分组聚合条目 (用于"分组" Tab 展示)
interface GroupEntry {
  name: string;
  items: BookSourceItem[];
}

// 顶部分类 Tab 索引 (对齐 app 端 BookSourceActivity upBookSource 分支语义)
const TAB_ALL: number = 0;
const TAB_ENABLED: number = 1;
const TAB_DISABLED: number = 2;
const TAB_GROUP: number = 3;

/**
 * 书源管理列表组件 (非 @Entry, 由宿主页/路由包装)。
 *
 * 对应 app 端 `BookSourceActivity` + shared `BookSourceListScreen`:
 * - 顶部 TitleBar: "书源管理" + 搜索/添加/菜单图标 (对照 BookSourceActions)
 * - 顶部 Tab: 全部 / 启用 / 禁用 / 分组 (对照 upBookSource 的 enabled/disabled/group 分支)
 * - Refresh + List 展示书源 (名称 / URL / 分组 / 启用开关)
 * - 点击 → 跳编辑页 (bridge editBookSource 待新增, 复用 editExploreSource 占位)
 * - 长按 → ActionMenu (启用/禁用 / 置顶 / 删除)
 *
 * 桥接说明: 完整的 bookSourceList/enableBookSource/topBookSource/deleteBookSource
 * 均未在 napi 中实现; 当前 exploreList() 仅返回 enabledExplore=true 子集, C++ 侧亦未实现,
 * 运行时调用会抛异常, 由 try/catch 降级为空列表/Toast 提示 (与 DiscoverTab.ets 模式一致)。
 */
@Component
export struct BookSourceList {
  @State sources: BookSourceItem[] = [];
  @State refreshing: boolean = false;
  @State currentTab: number = TAB_ALL;
  @State showSearch: boolean = false;
  @State searchKey: string = '';

  aboutToAppear(): void {
    this.loadSources();
  }

  // 调用 napi 加载书源列表; 接口未就绪时降级为空列表
  private loadSources(): void {
    try {
      // TODO: napi bookSourceList() 未实现, 当前复用 exploreList() (仅返回 enabledExplore=true 子集)
      // 替换为 legado.bookSourceList() 后, 全部/启用/禁用/分组 Tab 才能完整工作
      const json = legado.exploreList();
      this.sources = JSON.parse(json) as BookSourceItem[];
    } catch (e) {
      this.sources = [];
    }
  }

  // 下拉刷新: 同步加载后关闭指示器 (对齐 BookshelfTab.ets onRefresh)
  private onRefresh(): void {
    this.loadSources();
    this.refreshing = false;
  }

  // 按 currentTab + searchKey 过滤列表 (对齐 app 端 upBookSource 的 enabled/disabled 分支)
  private filteredSources(): BookSourceItem[] {
    let list: BookSourceItem[] = this.sources;
    if (this.searchKey.length > 0) {
      const key: string = this.searchKey.toLowerCase();
      list = list.filter((s: BookSourceItem) => {
        return s.bookSourceName.toLowerCase().includes(key)
          || s.bookSourceUrl.toLowerCase().includes(key)
          || (s.bookSourceGroup || '').toLowerCase().includes(key);
      });
    }
    if (this.currentTab === TAB_ENABLED) {
      list = list.filter((s: BookSourceItem) => s.enabled);
    } else if (this.currentTab === TAB_DISABLED) {
      list = list.filter((s: BookSourceItem) => !s.enabled);
    }
    return list;
  }

  // 分组 Tab: 按 bookSourceGroup 聚合 (分隔符对齐 AppPattern.splitGroupRegex = [,;，；])
  private groupedSources(): GroupEntry[] {
    const map = new Map<string, BookSourceItem[]>();
    this.filteredSources().forEach((s: BookSourceItem) => {
      const raw: string = s.bookSourceGroup || '';
      const groups: string[] = raw.split(/[,;，；]/)
        .map((g: string) => g.trim())
        .filter((g: string) => g.length > 0);
      if (groups.length === 0) {
        groups.push('未分组');
      }
      groups.forEach((g: string) => {
        const exist = map.get(g);
        if (exist) {
          exist.push(s);
        } else {
          map.set(g, [s]);
        }
      });
    });
    const result: GroupEntry[] = [];
    map.forEach((items: BookSourceItem[], name: string) => {
      result.push({ name, items });
    });
    return result;
  }

  // 点击源项: 跳编辑页 (bridge editBookSource 待新增, 复用 editExploreSource 占位)
  private openEdit(item: BookSourceItem): void {
    try {
      // TODO: napi editBookSource(sourceUrl) 未实现; 复用 editExploreSource 仅做跳转占位
      legado.editExploreSource(item.bookSourceUrl);
    } catch (e) {
      promptAction.showToast({ message: '编辑入口待 napi 扩展' });
    }
  }

  // 长按弹出操作菜单 (启用/禁用 / 置顶 / 删除, 对齐 BookshelfTab.ets showBookMenu 模式)
  private async showSourceMenu(item: BookSourceItem): Promise<void> {
    const enableText: string = item.enabled ? '禁用' : '启用';
    try {
      const result = await promptAction.showActionMenu({
        title: item.bookSourceName,
        buttons: [
          { text: enableText, color: '#212121' },
          { text: '置顶', color: '#212121' },
          { text: '删除', color: '#D32F2F' }
        ],
      });
      // 用户取消时 index = -1
      if (result.index < 0) {
        return;
      }
      this.onMenuAction(result.index, item);
    } catch (e) {
      // showActionMenu 异常, 忽略
    }
  }

  // 菜单项动作: 启用/禁用本地翻转 / 置顶 / 删除, 持久化桥接未就绪时 Toast 提示
  private onMenuAction(index: number, item: BookSourceItem): void {
    try {
      if (index === 0) {
        // TODO: napi enableBookSource(sourceUrl, enabled) 未实现; 本地翻转 + Toast 提示
        item.enabled = !item.enabled;
        this.sources = [...this.sources];
        promptAction.showToast({ message: '启用状态已本地变更, 持久化待 napi 扩展' });
      } else if (index === 1) {
        // TODO: napi topBookSource(sourceUrl) 未实现; 复用 topExploreSource 占位
        legado.topExploreSource(item.bookSourceUrl);
        this.loadSources();
      } else if (index === 2) {
        // TODO: napi deleteBookSource(sourceUrl) 未实现; 复用 deleteExploreSource 占位
        legado.deleteExploreSource(item.bookSourceUrl);
        this.loadSources();
      }
    } catch (e) {
      promptAction.showToast({ message: '操作失败, napi 桥接未就绪' });
    }
  }

  // 顶栏溢出菜单 (新增/本地导入/网络导入/分组管理, 对照 BookSourceActions OverflowMenu)
  private async showOverflowMenu(): Promise<void> {
    try {
      const result = await promptAction.showActionMenu({
        title: '书源管理',
        buttons: [
          { text: '新增书源', color: '#212121' },
          { text: '本地导入', color: '#212121' },
          { text: '网络导入', color: '#212121' },
          { text: '分组管理', color: '#212121' }
        ],
      });
      if (result.index < 0) {
        return;
      }
      const labels: string[] = ['新增书源', '本地导入', '网络导入', '分组管理'];
      promptAction.showToast({ message: `「${labels[result.index]}」待接入` });
    } catch (e) {
      // 忽略
    }
  }

  @Builder
  TitleBar() {
    Row() {
      Text('书源管理')
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .fontColor(Color.White)
        .layoutWeight(1)

      // 搜索图标 (点击切换搜索输入框显隐)
      Text('搜索')
        .fontSize(14)
        .fontColor(Color.White)
        .margin({ right: 16 })
        .onClick(() => {
          this.showSearch = !this.showSearch;
          if (!this.showSearch) {
            this.searchKey = '';
          }
        })

      // 添加图标 (＋, 新增书源, 桥接待扩展)
      Text('＋')
        .fontSize(22)
        .fontColor(Color.White)
        .margin({ right: 16 })
        .onClick(() => {
          promptAction.showToast({ message: '新增书源待 napi 扩展' });
        })

      // 溢出菜单图标 (⋮)
      Text('⋮')
        .fontSize(22)
        .fontColor(Color.White)
        .onClick(() => this.showOverflowMenu())
    }
    .width('100%')
    .height(56)
    .backgroundColor($r('app.color.app_primary'))
    .padding({ left: 16, right: 16 })
  }

  // 搜索输入框 (TitleBar 下方, 显隐由 showSearch 控制)
  @Builder
  SearchBar() {
    if (this.showSearch) {
      Row({ space: 8 }) {
        Text('🔍')
          .fontSize(14)
          .fontColor($r('app.color.text_secondary'))
        TextInput({ text: this.searchKey, placeholder: '搜索书源名称/URL/分组' })
          .fontSize(14)
          .layoutWeight(1)
          .backgroundColor(Color.Transparent)
          .onChange((value: string) => {
            this.searchKey = value;
          })
      }
      .width('100%')
      .height(44)
      .backgroundColor(Color.White)
      .padding({ left: 16, right: 16 })
      .alignItems(VerticalAlign.Center)
    }
  }

  // 顶部 Tab 栏 (对齐 app 端 BookSourceActivity 的 enabled/disabled/group 筛选分支)
  @Builder
  TabBar() {
    Row() {
      this.TabItem('全部', TAB_ALL)
      this.TabItem('启用', TAB_ENABLED)
      this.TabItem('禁用', TAB_DISABLED)
      this.TabItem('分组', TAB_GROUP)
    }
    .width('100%')
    .height(44)
    .backgroundColor(Color.White)
  }

  @Builder
  TabItem(label: string, index: number) {
    Column() {
      Text(label)
        .fontSize(14)
        .fontColor(this.currentTab === index
          ? $r('app.color.app_primary')
          : $r('app.color.text_secondary'))
        .fontWeight(this.currentTab === index ? FontWeight.Medium : FontWeight.Normal)
      // 下划线指示器 (选中态主色, 未选中透明)
      Divider()
        .strokeWidth(2)
        .color(this.currentTab === index
          ? $r('app.color.app_primary')
          : Color.Transparent)
        .width(24)
        .margin({ top: 6 })
    }
    .layoutWeight(1)
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .onClick(() => {
      this.currentTab = index;
    })
  }

  @Builder
  EmptyState() {
    Column() {
      Text('暂无书源')
        .fontSize(16)
        .fontColor($r('app.color.text_secondary'))
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  SourceItem(item: BookSourceItem) {
    Row({ space: 12 }) {
      // 源信息列: 名称 / URL / 分组 (对齐 app BookSourceItem getDisPlayNameGroup + bookSourceUrl)
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
        if (item.bookSourceGroup && item.bookSourceGroup.length > 0) {
          Text(item.bookSourceGroup)
            .fontSize(10)
            .fontColor($r('app.color.text_secondary'))
            .maxLines(1)
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)

      // 启用状态开关 (对齐 app 端 AppSwitch, 选中态主色)
      Toggle({ type: ToggleType.Switch, isOn: item.enabled })
        .selectedColor($r('app.color.app_primary'))
        .onChange((on: boolean) => {
          // TODO: napi enableBookSource(sourceUrl, enabled) 未实现; 本地翻转 + Toast 提示
          item.enabled = on;
          this.sources = [...this.sources];
          promptAction.showToast({ message: '启用状态已本地变更, 持久化待 napi 扩展' });
        })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(Color.White)
    .borderRadius(8)
    .onClick(() => this.openEdit(item))
    .gesture(
      LongPressGesture({ duration: 500 })
        .onAction(() => this.showSourceMenu(item))
    )
  }

  // 分组头 (对齐 app BookSourceItem 按 host 分组时的组头文本样式)
  @Builder
  GroupHeader(name: string, count: number) {
    Row() {
      Text(name)
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .fontColor($r('app.color.app_primary'))
        .layoutWeight(1)
      Text(`${count}`)
        .fontSize(12)
        .fontColor($r('app.color.text_secondary'))
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    .backgroundColor($r('app.color.app_background'))
  }

  build() {
    Column() {
      this.TitleBar()
      this.SearchBar()
      this.TabBar()

      Refresh({ refreshing: $$this.refreshing }) {
        if (this.filteredSources().length === 0) {
          this.EmptyState()
        } else if (this.currentTab === TAB_GROUP) {
          // 分组模式: 组头 + 组内条目
          List({ space: 8 }) {
            ForEach(this.groupedSources(), (entry: GroupEntry) => {
              ListItem() {
                this.GroupHeader(entry.name, entry.items.length)
              }
              ForEach(entry.items, (item: BookSourceItem) => {
                ListItem() {
                  this.SourceItem(item)
                }
              }, (item: BookSourceItem) => item.bookSourceUrl)
            }, (entry: GroupEntry) => entry.name)
          }
          .width('100%')
          .layoutWeight(1)
          .backgroundColor($r('app.color.app_background'))
        } else {
          List({ space: 8 }) {
            ForEach(this.filteredSources(), (item: BookSourceItem) => {
              ListItem() {
                this.SourceItem(item)
              }
            }, (item: BookSourceItem) => item.bookSourceUrl)
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
