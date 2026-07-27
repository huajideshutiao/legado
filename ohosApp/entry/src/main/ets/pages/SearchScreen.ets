import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import legado from 'liblegado_napi.so';

/**
 * 搜索结果项 (字段对齐 KMP SearchBook / Book 实体序列化 JSON)。
 *
 * napi searchBook() 返回书架内 Book 序列化 JSON, 字段名与 Book 实体一致;
 * 后续接入在线搜索 napi 时返回 SearchBook JSON, 显示字段同名, UI 无需改动。
 * 可空字段 KMP 端输出 null, 此处用 string 兜底 + || '—' 处理 (与 BookshelfTab 同模式)。
 */
interface SearchResultItem {
  bookUrl: string;
  name: string;
  author: string;
  origin: string;
  originName: string;
  latestChapterTitle: string;
  coverUrl: string;
  intro: string;
}

/**
 * 搜索历史项 (字段对齐 KMP SearchKeyword 实体)。
 *
 * napi 未暴露 searchKeywordDao, 当前内存 placeholder; TODO 接入 napi 后由
 * searchKeywordDao 持久化 (按 lastUseTime 排序, usage 计数)。
 */
interface SearchHistoryItem {
  word: string;
}

/**
 * 搜索页面 (ArkTS UI, 路由 pages/SearchScreen)。
 *
 * 对应 app 端 SearchActivity + shared SearchScreen:
 * - 顶栏: 返回 + 搜索框 (TextInput) + 搜索按钮 (对照 SearchField)
 * - 输入帮助态: 搜索历史 chips (点击发起搜索, 长按删除, 对照 InputHelp 历史区)
 * - 结果态: 列表展示 (封面占位 + 书名 + 作者 + 最新章节 + 来源, 对照 SearchListItem)
 * - 点击/长按结果 → ActionMenu (详情/加入书架)
 *
 * 数据说明:
 * - 结果当前用 napi searchBook() 兜底 (书架内搜索); TODO 接入在线搜索 napi
 *   (走 SearchModel 跨书源搜索, 返回 SearchBook 列表)
 * - 历史/详情/加入书架 napi 均未暴露, placeholder + TODO (同 DiscoverTab 桥接未就绪模式)
 */
@Entry
@Component
struct SearchScreen {
  @State query: string = '';
  @State results: SearchResultItem[] = [];
  @State history: SearchHistoryItem[] = [];
  @State hasSearched: boolean = false;

  aboutToAppear(): void {
    this.loadHistory();
  }

  // 加载搜索历史; napi 未暴露, 当前内存 placeholder
  private loadHistory(): void {
    // TODO: 接入 napi searchHistoryList() 读 searchKeywordDao (按 lastUseTime 倒序)
    this.history = [];
  }

  // 执行搜索; key 非空时覆盖当前 query (历史点击场景)
  private doSearch(key?: string): void {
    const searchKey = (key !== undefined ? key : this.query).trim();
    if (searchKey.length === 0) {
      return;
    }
    this.query = searchKey;
    this.saveHistory(searchKey);
    this.hasSearched = true;
    // 当前用书架内搜索 napi 兜底; TODO 接入 napi searchOnline() 走 SearchModel 跨书源搜索
    try {
      const json = legado.searchBook(searchKey);
      this.results = JSON.parse(json) as SearchResultItem[];
    } catch (e) {
      this.results = [];
    }
  }

  // 保存搜索词到历史; napi 未暴露, 当前内存 placeholder (保留最近 20 条, 去重置顶)
  private saveHistory(word: string): void {
    // TODO: 接入 napi saveSearchKey(word) 写 searchKeywordDao (usage+1 / lastUseTime 更新)
    const idx = this.history.findIndex((h: SearchHistoryItem) => h.word === word);
    if (idx >= 0) {
      this.history.splice(idx, 1);
    }
    this.history.unshift({ word });
    if (this.history.length > 20) {
      this.history.pop();
    }
  }

  // 删除单条历史; napi 未暴露, 当前内存 placeholder
  private deleteHistory(word: string): void {
    // TODO: 接入 napi deleteSearchKey(word)
    const idx = this.history.findIndex((h: SearchHistoryItem) => h.word === word);
    if (idx >= 0) {
      this.history.splice(idx, 1);
    }
  }

  // 清空全部历史; napi 未暴露
  private clearHistory(): void {
    // TODO: 接入 napi clearSearchHistory()
    this.history = [];
  }

  // 点击/长按结果: 弹操作菜单 (详情/加入书架)
  private async showResultMenu(book: SearchResultItem): Promise<void> {
    try {
      const result = await promptAction.showActionMenu({
        title: book.name,
        buttons: [
          { text: '详情', color: '#212121' },
          { text: '加入书架', color: '#212121' },
        ],
      });
      // 用户取消时 index = -1
      if (result.index < 0) {
        return;
      }
      this.onResultAction(result.index, book);
    } catch (e) {
      // showActionMenu 异常, 忽略
    }
  }

  // 结果菜单动作: napi 均未暴露, Toast 提示待扩展
  private onResultAction(index: number, book: SearchResultItem): void {
    if (index === 0) {
      // TODO: 接入 napi openBookDetail(bookUrl, name, author, origin) 跳书籍详情
      promptAction.showToast({ message: '书籍详情待 napi 扩展' });
    } else if (index === 1) {
      // TODO: 接入 napi addToBookshelf(bookUrl, name, author, origin) 加入书架
      promptAction.showToast({ message: '加入书架待 napi 扩展' });
    }
  }

  @Builder
  TitleBar() {
    Row({ space: 8 }) {
      // 返回
      Text('←')
        .fontSize(22)
        .fontColor(Color.White)
        .onClick(() => router.back())

      // 搜索框 (对齐 app 端 SearchField, 白底圆角嵌入主色顶栏)
      TextInput({ text: this.query, placeholder: '搜索书名/作者' })
        .layoutWeight(1)
        .backgroundColor(Color.White)
        .borderRadius(4)
        .onChange((value: string) => {
          this.query = value;
          // 清空输入时回到历史态 (对齐 app 端 onCleared 收起结果区)
          if (value.length === 0) {
            this.hasSearched = false;
          }
        })
        .onSubmit(() => this.doSearch())

      // 搜索按钮
      Text('搜索')
        .fontSize(14)
        .fontColor(Color.White)
        .onClick(() => this.doSearch())
    }
    .width('100%')
    .height(56)
    .backgroundColor($r('app.color.app_primary'))
    .padding({ left: 16, right: 16 })
  }

  @Builder
  HistorySection() {
    Column() {
      // 标题行 + 清空 (对齐 app 端 InputHelp 历史标题 + clear)
      Row() {
        Text('搜索历史')
          .fontSize(14)
          .fontColor($r('app.color.text_primary'))
          .layoutWeight(1)
        if (this.history.length > 0) {
          Text('清空')
            .fontSize(14)
            .fontColor($r('app.color.text_secondary'))
            .onClick(() => this.clearHistory())
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })

      // 历史 chips (Flex wrap, 对齐 app 端 FlowRow + AppFilletTextButton)
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(this.history, (item: SearchHistoryItem) => {
          Text(item.word)
            .fontSize(14)
            .fontColor($r('app.color.text_primary'))
            .backgroundColor($r('app.color.app_background'))
            .borderRadius(16)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .margin({ right: 8, bottom: 8 })
            .onClick(() => this.doSearch(item.word))
            .gesture(
              LongPressGesture({ duration: 500 })
                .onAction(() => this.deleteHistory(item.word))
            )
        }, (item: SearchHistoryItem) => item.word)
      }
      .width('100%')
      .padding({ left: 16, right: 16 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  EmptyState() {
    Column() {
      Text('暂无搜索结果')
        .fontSize(16)
        .fontColor($r('app.color.text_secondary'))
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  ResultItem(book: SearchResultItem) {
    Row({ space: 12 }) {
      // 封面占位 (首字符, 沿用 BookshelfTab.ets 约定, 真实封面加载留作后续)
      Column() {
        Text(book.name.charAt(0))
          .fontSize(28)
          .fontColor($r('app.color.app_primary'))
      }
      .width(64)
      .height(80)
      .backgroundColor($r('app.color.app_background'))
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)

      // 信息列: 书名 / 作者 / 最新章节 / 来源 (对齐 app 端 SearchListItem)
      Column({ space: 4 }) {
        Text(book.name)
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .fontColor($r('app.color.text_primary'))
          .maxLines(1)
        Text(book.author || '—')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .maxLines(1)
        Text(book.latestChapterTitle || '—')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .maxLines(1)
          .layoutWeight(1)
        Text(book.originName || '—')
          .fontSize(11)
          .fontColor($r('app.color.text_secondary'))
          .maxLines(1)
      }
      .layoutWeight(1)
      .height(80)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    .backgroundColor(Color.White)
    .onClick(() => this.showResultMenu(book))
    .gesture(
      LongPressGesture({ duration: 500 })
        .onAction(() => this.showResultMenu(book))
    )
  }

  build() {
    Column() {
      this.TitleBar()
      // 输入帮助态 (未搜索) 显示历史; 结果态显示列表/空占位 (对齐 app 端 InputHelp/ResultArea 互斥)
      if (!this.hasSearched) {
        this.HistorySection()
      } else if (this.results.length === 0) {
        this.EmptyState()
      } else {
        List({ space: 8 }) {
          ForEach(this.results, (book: SearchResultItem) => {
            ListItem() {
              this.ResultItem(book)
            }
          }, (book: SearchResultItem) => book.bookUrl)
        }
        .width('100%')
        .layoutWeight(1)
        .backgroundColor($r('app.color.app_background'))
      }
    }
    .width('100%')
    .height('100%')
  }
}
