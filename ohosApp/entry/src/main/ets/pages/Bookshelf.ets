import router from '@ohos.router';
import legado from 'liblegado_napi.so';

/**
 * 书架数据项模型 (与 KMP Book 实体字段对齐)。
 *
 * 对应 Android 端 `Book` 实体 (modules/shared/.../data/entities/Book.kt)。
 * 字段命名与 KMP Book 实体一致, 便于 napi 桥接后直接复用 JSON 解析。
 */
interface BookshelfItem {
  bookUrl: string;
  name: string;
  author: string;
  coverUrl: string;
  latestChapterTitle: string;
  latestChapterTime: number;
}

/**
 * 书架页面 (ArkTS UI)。
 *
 * 对应 Android 端 `BookshelfScreen1` (app/src/main/java/io/legado/app/ui/main/bookshelf/style1/BookshelfScreen1.kt)。
 *
 * KP5: 通过 napi `bookshelfList()` 调用 KMP `AppDbProviders.get().bookDao.getBooksByGroup(BookGroup.IdAll)`
 * 加载真实书架数据 (替代 KP4 的本地 mock 数据)。
 *
 * - List + ListItem 展示书籍列表 (真实 KMP 数据)
 * - 点击 ListItem 跳转 `pages/Reader` 阅读
 * - 顶部标题栏 + 返回按钮
 *
 * 注: bookshelfList() 内部 runBlocking 转 suspend, 应在 TaskPool/Worker 调用;
 * 当前简化版直接在 aboutToAppear 调用 (数据量小, 阻塞可接受), 后续 KP6+ 改 TaskPool。
 */
@Entry
@Component
struct Bookshelf {
  @State books: BookshelfItem[] = [];

  aboutToAppear(): void {
    this.loadBooks();
  }

  /**
   * 调用 napi bookshelfList() 加载书架书籍。
   *
   * napi 返回 JSON 数组字符串, 用 JSON.parse 解析为 BookshelfItem[]。
   * 异常时 napi 返回 "[]" (空数组), UI 显示空书架占位。
   */
  private loadBooks(): void {
    try {
      const json = legado.bookshelfList();
      // JSON.parse 返回 Object, 强制转换为 BookshelfItem[] (ArkTS 严格模式)
      const list = JSON.parse(json) as BookshelfItem[];
      this.books = list;
    } catch (e) {
      // napi 桥接未就绪或 JSON 解析失败, 显示空书架
      this.books = [];
    }
  }

  private formatTime(millis: number): string {
    // 鸿蒙 Date 格式化简化版
    const date = new Date(millis);
    const y = date.getFullYear();
    const m = (date.getMonth() + 1).toString().padStart(2, '0');
    const d = date.getDate().toString().padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  build() {
    Column() {
      // 顶部标题栏
      Row() {
        Text('返回')
          .fontSize(14)
          .fontColor(Color.White)
          .onClick(() => router.back())
        Text($r('app.string.bookshelf_title'))
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(Color.White)
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
        Text('+')
          .fontSize(24)
          .fontColor(Color.White)
      }
      .width('100%')
      .height(56)
      .backgroundColor($r('app.color.app_primary'))
      .padding({ left: 16, right: 16 })

      // 书籍列表
      if (this.books.length === 0) {
        // 空书架占位
        Column() {
          Text($r('app.string.bookshelf_empty'))
            .fontSize(16)
            .fontColor($r('app.color.text_secondary'))
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      } else {
        List({ space: 8 }) {
          ForEach(this.books, (book: BookshelfItem) => {
            ListItem() {
              Row({ space: 12 }) {
                // 封面占位 (后续接 @ohos.multimedia.image 加载真实封面)
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

                // 书籍信息
                Column({ space: 4 }) {
                  Text(book.name)
                    .fontSize(16)
                    .fontWeight(FontWeight.Medium)
                    .fontColor($r('app.color.text_primary'))
                    .maxLines(1)
                  Text(book.author)
                    .fontSize(12)
                    .fontColor($r('app.color.text_secondary'))
                    .maxLines(1)
                  Text(book.latestChapterTitle || '')
                    .fontSize(12)
                    .fontColor($r('app.color.text_secondary'))
                    .maxLines(1)
                    .layoutWeight(1)
                  Text(`更新: ${this.formatTime(book.latestChapterTime)}`)
                    .fontSize(10)
                    .fontColor($r('app.color.text_secondary'))
                }
                .layoutWeight(1)
                .height(80)
                .alignItems(HorizontalAlign.Start)
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 8, bottom: 8 })
              .backgroundColor(Color.White)
              .onClick(() => {
                // 跳转阅读页, 传 bookUrl 参数 (从 0 章开始读)
                router.pushUrl({
                  url: 'pages/Reader',
                  params: { bookUrl: book.bookUrl, bookName: book.name, chapterIndex: 0 }
                });
              })
            }
          }, (book: BookshelfItem) => book.bookUrl)
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
