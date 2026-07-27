import router from '@ohos.router';
import legado from 'liblegado_napi.so';

/**
 * 阅读页面 (ArkTS UI)。
 *
 * 对应 Android 端 `ReadBookActivity` (app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt)。
 *
 * KP5: 通过 napi `loadChapter(bookUrl, chapterIndex)` 调用 KMP
 * `BookStorageProviders.get().getContent(book, chapter)` 加载真实章节正文
 * (替代 KP4 的本地 mock 翻页内容)。
 *
 * - 顶部标题栏 (返回按钮 + 书名 + 当前章节序号)
 * - 中部正文区域 (Text 显示当前章节内容, 空内容时显示占位提示)
 * - 底部翻章按钮 (上一章 / 下一章)
 *
 * 注: loadChapter() 内部 runBlocking 转 suspend, 应在 TaskPool/Worker 调用;
 * 当前简化版直接在 aboutToAppear / 按钮回调调用 (单章加载阻塞可接受), 后续 KP6+ 改 TaskPool。
 * 仅读本地缓存章节, 不联网拉取 (WebBook.getContentAwait 编排复杂, 后续 KP6+ 实现)。
 */
@Entry
@Component
struct Reader {
  // 路由参数: 书名 + bookUrl + 起始章节索引 (从 Bookshelf 跳转传入)
  @State bookName: string = '';
  @State bookUrl: string = '';
  @State chapterIndex: number = 0;

  // 章节正文 (从 napi loadChapter 加载, 空字符串表示缓存未命中或越界)
  @State chapterContent: string = '';

  aboutToAppear(): void {
    // 接收 Bookshelf 跳转参数
    const params = router.getParams() as Record<string, Object>;
    if (params) {
      this.bookName = (params.bookName as string) ?? '';
      this.bookUrl = (params.bookUrl as string) ?? '';
      this.chapterIndex = (params.chapterIndex as number) ?? 0;
    }
    if (!this.bookName) {
      this.bookName = '(未选择书籍)';
    }
    // 加载起始章节正文
    this.loadChapter(this.chapterIndex);
  }

  /**
   * 调用 napi loadChapter() 加载指定章节正文。
   *
   * napi 返回 UTF-8 字符串 (章节正文); 缓存未命中 / 章节越界 / 异常时返回空字符串。
   * 空内容时显示占位提示, 不修改 chapterIndex (让用户知道目标章节无内容)。
   *
   * @param idx 章节索引 (0-based)
   */
  private loadChapter(idx: number): void {
    if (!this.bookUrl) {
      this.chapterContent = '未选择书籍, 无法加载章节。';
      return;
    }
    try {
      const content = legado.loadChapter(this.bookUrl, idx);
      this.chapterIndex = idx;
      if (content.length === 0) {
        this.chapterContent = '暂无章节内容 (缓存未命中或已到末章)。';
      } else {
        this.chapterContent = content;
      }
    } catch (e) {
      // napi 桥接未就绪或调用异常, 显示错误提示
      this.chapterIndex = idx;
      this.chapterContent = `加载章节失败: ${(e as Error).message ?? e}`;
    }
  }

  build() {
    Column() {
      // 顶部标题栏
      Row() {
        Text($r('app.string.reader_back'))
          .fontSize(14)
          .fontColor(Color.White)
          .onClick(() => router.back())
        Text(this.bookName)
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .fontColor(Color.White)
          .layoutWeight(1)
          .maxLines(1)
          .textAlign(TextAlign.Center)
        Text(`第 ${this.chapterIndex + 1} 章`)
          .fontSize(12)
          .fontColor(Color.White)
      }
      .width('100%')
      .height(56)
      .backgroundColor($r('app.color.app_primary'))
      .padding({ left: 16, right: 16 })

      // 正文区域
      Scroll() {
        Text(this.chapterContent)
          .fontSize(16)
          .lineHeight(28)
          .fontColor($r('app.color.text_primary'))
          .padding({ left: 20, right: 20, top: 20, bottom: 20 })
          .width('100%')
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor(Color.White)
      .align(Alignment.TopStart)

      // 底部翻章按钮
      Row({ space: 16 }) {
        Button($r('app.string.reader_prev'))
          .width('40%')
          .height(44)
          .fontSize(14)
          .backgroundColor($r('app.color.app_background'))
          .fontColor($r('app.color.text_primary'))
          .enabled(this.chapterIndex > 0)
          .onClick(() => {
            if (this.chapterIndex > 0) {
              this.loadChapter(this.chapterIndex - 1);
            }
          })

        Button($r('app.string.reader_next'))
          .width('40%')
          .height(44)
          .fontSize(14)
          .backgroundColor($r('app.color.app_primary'))
          .fontColor(Color.White)
          .enabled(true)
          .onClick(() => {
            this.loadChapter(this.chapterIndex + 1);
          })
      }
      .width('100%')
      .height(72)
      .justifyContent(FlexAlign.Center)
      .backgroundColor($r('app.color.app_background'))
    }
    .width('100%')
    .height('100%')
  }
}
