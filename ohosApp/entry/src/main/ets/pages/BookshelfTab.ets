import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import legado from 'liblegado_napi.so';

/**
 * 书架数据项 (字段对齐 KMP Book 实体序列化 JSON, 含未读计数所需字段)。
 *
 * napi bookshelfList() 由 shared LegadoNativeExports.bookshelfList 经
 * KS_JSON + Book.serializer() 序列化, 字段名与 Book 实体一致。
 * 可空字段 KMP 端输出 null, 此处用 string 兜底 + || '' 处理 (与 Bookshelf.ets 同模式)。
 */
interface BookshelfItem {
  bookUrl: string;
  name: string;
  author: string;
  latestChapterTitle: string;
  // 未读计数 + 进度
  durChapterIndex: number;
  durChapterPos: number;
  totalChapterNum: number;
  lastCheckCount: number;
  intro: string;
}

/**
 * 书架 Tab 内容组件 (嵌入 BottomNavigation, 非 @Entry)。
 *
 * 对应 app 端 BookshelfTab + shared BookshelfScreen 的列表档:
 * - 顶部 TitleBar: "书架" + 搜索图标 + 溢出菜单图标 (对照 BookshelfActions)
 * - Refresh + List 展示书籍 (封面占位 + 书名 + 未读徽标 + 作者 + 最新章节)
 * - 点击 → router 跳 Reader (Reader 内部调 legado.loadChapter 加载正文)
 * - 长按 → ActionMenu (详情/分组/删除); 分组/删除待 napi 扩展, 详情走对话框
 *
 * 数据通过 napi bookshelfList() 获取 (runBlocking 同步, 数据量小阻塞可接受),
 * 真实封面加载 (@ohos.multimedia.image / Image 组件) 留作后续, 此处沿用首字符占位。
 */
@Component
export struct BookshelfTab {
  @State books: BookshelfItem[] = [];
  @State refreshing: boolean = false;

  aboutToAppear(): void {
    this.loadBooks();
  }

  // 调用 napi bookshelfList() 加载书架; 异常时显示空书架
  private loadBooks(): void {
    try {
      const json = legado.bookshelfList();
      this.books = JSON.parse(json) as BookshelfItem[];
    } catch (e) {
      this.books = [];
    }
  }

  // 下拉刷新: 同步加载后关闭指示器
  private onRefresh(): void {
    this.loadBooks();
    this.refreshing = false;
  }

  // 未读章数 (对齐 app 端 Book.getUnreadChapterNum 非模拟分支:
  // totalChapterNum - durChapterIndex + (durChapterPos<0?-1:0), 下限 0)
  private unreadCount(book: BookshelfItem): number {
    const n = book.totalChapterNum - book.durChapterIndex + (book.durChapterPos < 0 ? -1 : 0);
    return n > 0 ? n : 0;
  }

  // 点击打开阅读页 (跳 Reader, 传 durChapterIndex 续读, Reader 内部调 legado.loadChapter)
  private openReader(book: BookshelfItem): void {
    router.pushUrl({
      url: 'pages/Reader',
      params: {
        bookUrl: book.bookUrl,
        bookName: book.name,
        chapterIndex: book.durChapterIndex
      }
    });
  }

  // 长按弹出操作菜单 (详情/分组/删除)
  private async showBookMenu(book: BookshelfItem): Promise<void> {
    try {
      const result = await promptAction.showActionMenu({
        title: book.name,
        buttons: [
          { text: '详情', color: '#212121' },
          { text: '分组', color: '#212121' },
          { text: '删除', color: '#D32F2F' }
        ],
      });
      // 用户取消时 index = -1
      if (result.index < 0) {
        return;
      }
      this.onMenuAction(result.index, book);
    } catch (e) {
      // showActionMenu 异常, 忽略
    }
  }

  // 菜单项动作: 详情走对话框; 分组/删除当前 napi 未提供, 提示待扩展
  private onMenuAction(index: number, book: BookshelfItem): void {
    if (index === 0) {
      this.showBookInfo(book);
    } else if (index === 1) {
      promptAction.showToast({ message: '分组功能待 napi 扩展' });
    } else if (index === 2) {
      promptAction.showToast({ message: '删除功能待 napi 扩展' });
    }
  }

  // 详情对话框 (展示书名/作者/进度/最新章节/简介)
  private showBookInfo(book: BookshelfItem): void {
    const progress = `第 ${book.durChapterIndex + 1}/${book.totalChapterNum} 章`;
    const latest = book.latestChapterTitle || '—';
    const intro = book.intro || '—';
    promptAction.showDialog({
      title: book.name,
      message: `作者: ${book.author || '—'}\n进度: ${progress}\n最新: ${latest}\n简介: ${intro}`,
      buttons: [{ text: '关闭', color: '#1976D2' }],
    });
  }

  // 顶栏溢出菜单 (刷新书架/书架管理/导入书架, 待接入)
  private async showOverflowMenu(): Promise<void> {
    try {
      const result = await promptAction.showActionMenu({
        title: '书架',
        buttons: [
          { text: '刷新书架', color: '#212121' },
          { text: '书架管理', color: '#212121' },
          { text: '导入书架', color: '#212121' },
        ],
      });
      if (result.index < 0) {
        return;
      }
      const labels = ['刷新书架', '书架管理', '导入书架'];
      promptAction.showToast({ message: `「${labels[result.index]}」待接入` });
    } catch (e) {
      // 忽略
    }
  }

  @Builder
  TitleBar() {
    Row() {
      Text($r('app.string.bookshelf_title'))
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .fontColor(Color.White)
        .layoutWeight(1)

      // 搜索图标 (沿用文本按钮, 与现有 ohosApp 无图标资源约定一致)
      Text('搜索')
        .fontSize(14)
        .fontColor(Color.White)
        .margin({ right: 16 })
        .onClick(() => {
          promptAction.showToast({ message: '搜索入口待接入' });
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

  @Builder
  EmptyState() {
    Column() {
      Text($r('app.string.bookshelf_empty'))
        .fontSize(16)
        .fontColor($r('app.color.text_secondary'))
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  BookItem(book: BookshelfItem) {
    Row({ space: 12 }) {
      // 封面占位 (首字符, 沿用 Bookshelf.ets 约定, 真实封面加载留作后续)
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

      // 书籍信息列
      Column({ space: 4 }) {
        // 书名行 + 未读徽标
        Row() {
          Text(book.name)
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .fontColor($r('app.color.text_primary'))
            .maxLines(1)
            .layoutWeight(1)

          // 未读徽标 (对齐 app UnreadBadge: >0 显示, lastCheckCount>0 高亮 accent)
          if (this.unreadCount(book) > 0) {
            Text(this.unreadCount(book).toString())
              .fontSize(11)
              .fontColor(Color.White)
              .backgroundColor(book.lastCheckCount > 0
                ? $r('app.color.app_primary')
                : $r('app.color.text_secondary'))
              .borderRadius(8)
              .padding({ left: 5, right: 5, top: 1, bottom: 1 })
              .margin({ left: 4 })
          }
        }
        .width('100%')

        // 作者行
        Text(book.author || '—')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .maxLines(1)

        // 最新章节行 (对齐 ShelfListItem ic_book_last 行)
        Text(book.latestChapterTitle || '')
          .fontSize(12)
          .fontColor($r('app.color.text_secondary'))
          .maxLines(1)
          .layoutWeight(1)
      }
      .layoutWeight(1)
      .height(80)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    .backgroundColor(Color.White)
    .onClick(() => this.openReader(book))
    .gesture(
      LongPressGesture({ duration: 500 })
        .onAction(() => this.showBookMenu(book))
    )
  }

  build() {
    Column() {
      this.TitleBar()

      Refresh({ refreshing: $$this.refreshing }) {
        if (this.books.length === 0) {
          this.EmptyState()
        } else {
          List({ space: 8 }) {
            ForEach(this.books, (book: BookshelfItem) => {
              ListItem() {
                this.BookItem(book)
              }
            }, (book: BookshelfItem) => book.bookUrl)
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
