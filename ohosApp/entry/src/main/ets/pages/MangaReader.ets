import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import legado from 'liblegado_napi.so';

/**
 * 漫画阅读页面 (ArkTS UI)。
 *
 * 对应 Android 端 ReadMangaActivity (app/.../ui/book/manga/ReadMangaActivity.kt) +
 * shared MangaReaderScreenContent (shared/.../ui/book/manga/MangaReaderScreenContent.kt)。
 *
 * 通过 napi loadMangaChapter(bookUrl, chapterIndex) 获取图片 URL 列表,
 * Scroll + Column + Image 实现上下滑动浏览, 章节切换经底部按钮 / 进度条。
 * GIF 动图暂未实现 (占位 TODO)。
 */
@Entry
@Component
struct MangaReader {
  // 路由参数: 书名 + bookUrl + 起始章节索引
  @State bookName: string = '';
  @State bookUrl: string = '';
  @State chapterIndex: number = 0;

  // 当前章节图片 URL 列表 (从 napi loadMangaChapter 加载)
  @State images: string[] = [];

  // 章节目录 (从 napi chapterList 加载)
  @State chapterList: ChapterItem[] = [];

  // 已读章节索引集合
  @State readChapters: number[] = [];

  // 章节抽屉可见性
  @State drawerVisible: boolean = false;

  // 加载指示器
  @State loading: boolean = false;

  // 滚动控制器 (切章后回到顶部)
  private scroller: Scroller = new Scroller();

  aboutToAppear(): void {
    // 接收跳转参数
    const params = router.getParams() as Record<string, Object>;
    if (params) {
      this.bookName = (params.bookName as string) ?? '';
      this.bookUrl = (params.bookUrl as string) ?? '';
      this.chapterIndex = (params.chapterIndex as number) ?? 0;
    }
    if (!this.bookName) {
      this.bookName = '(未选择书籍)';
    }
    this.loadChapterList();
    this.loadMangaChapter(this.chapterIndex);
  }

  // 加载章节目录 (复用 napi chapterList)
  private loadChapterList(): void {
    if (!this.bookUrl) {
      this.chapterList = [];
      return;
    }
    try {
      const json = legado.chapterList(this.bookUrl);
      this.chapterList = JSON.parse(json) as ChapterItem[];
    } catch (e) {
      this.chapterList = [];
    }
  }

  // 加载漫画章节图片 URL 列表
  private loadMangaChapter(idx: number): void {
    if (!this.bookUrl) {
      this.images = [];
      return;
    }
    this.loading = true;
    try {
      const json = legado.loadMangaChapter(this.bookUrl, idx);
      const data = JSON.parse(json) as MangaChapterData;
      this.chapterIndex = idx;
      this.images = data.images ?? [];
      if (!this.readChapters.includes(idx)) {
        this.readChapters = [...this.readChapters, idx];
      }
      // 切章后回到顶部
      this.scroller.scrollEdge(Edge.Top);
    } catch (e) {
      this.chapterIndex = idx;
      this.images = [];
      promptAction.showToast({ message: `加载漫画章节失败: ${(e as Error).message ?? e}` });
    } finally {
      this.loading = false;
    }
  }

  private isChapterRead(idx: number): boolean {
    return this.readChapters.includes(idx);
  }

  private showNotImplemented(): void {
    promptAction.showToast({ message: '功能开发中', duration: 2000 });
  }

  build() {
    Stack({ alignContent: Alignment.Start }) {
      Column() {
        // 顶部标题栏 (返回 + 书名 + 当前章节序号 + 菜单按钮)
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
          // 菜单按钮 (呼出目录 / 设置)
          Text('☰')
            .fontSize(20)
            .fontColor(Color.White)
            .margin({ left: 12 })
            .bindMenu([
              {
                value: $r('app.string.reader_toc'),
                action: () => { this.drawerVisible = true; }
              },
              {
                value: $r('app.string.reader_settings'),
                action: () => { this.showNotImplemented(); }
              }
            ])
        }
        .width('100%')
        .height(56)
        .backgroundColor($r('app.color.app_primary'))
        .padding({ left: 16, right: 16 })

        // 图片区域 (Scroll + Column + Image, 上下滑动浏览)
        Stack() {
          Scroll(this.scroller) {
            Column() {
              ForEach(this.images, (url: string, index: number) => {
                // TODO: GIF 动图判断与单次播放暂未实现
                Image(url)
                  .width('100%')
                  .objectFit(ImageFit.Contain)
                  .backgroundColor('#F5F5F5')
              }, (url: string, index: number) => `${index}_${url}`)
            }
            .width('100%')
          }
          .width('100%')
          .layoutWeight(1)
          .backgroundColor('#1A1A1A')
          .align(Alignment.TopStart)
          .scrollBar(BarState.Off)

          // 加载指示器
          if (this.loading) {
            Column() {
              LoadingProgress()
                .width(48)
                .height(48)
                .color(Color.White)
              Text($r('app.string.manga_loading_images'))
                .fontSize(14)
                .fontColor(Color.White)
                .margin({ top: 12 })
            }
            .width('100%')
            .height('100%')
            .justifyContent(FlexAlign.Center)
            .alignItems(HorizontalAlign.Center)
          }

          // 空图片占位
          if (!this.loading && this.images.length === 0) {
            Column() {
              Text($r('app.string.manga_chapter_images_empty'))
                .fontSize(14)
                .fontColor($r('app.color.text_secondary'))
            }
            .width('100%')
            .height('100%')
            .justifyContent(FlexAlign.Center)
            .alignItems(HorizontalAlign.Center)
          }
        }
        .width('100%')
        .layoutWeight(1)

        // 底部翻页按钮区 (上一章 + 章节进度条 + 下一章)
        Row({ space: 8 }) {
          // 上一章按钮
          Button('◀')
            .width(44)
            .height(44)
            .fontSize(18)
            .backgroundColor($r('app.color.app_background'))
            .fontColor($r('app.color.text_primary'))
            .enabled(this.chapterIndex > 0)
            .onClick(() => {
              if (this.chapterIndex > 0) {
                this.loadMangaChapter(this.chapterIndex - 1);
              }
            })

          // 章节进度条 (拖动跳转章节, 仅松手时加载)
          Slider({
            value: this.chapterIndex,
            min: 0,
            max: Math.max(this.chapterList.length - 1, 0),
            step: 1,
            style: SliderStyle.OutSet
          })
            .layoutWeight(1)
            .trackColor($r('app.color.app_background'))
            .selectedColor($r('app.color.app_primary'))
            .blockColor($r('app.color.app_primary'))
            .onChange((value: number, mode: SliderChangeMode) => {
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                const target = Math.floor(value);
                if (target !== this.chapterIndex) {
                  this.loadMangaChapter(target);
                }
              }
            })

          // 下一章按钮
          Button('▶')
            .width(44)
            .height(44)
            .fontSize(18)
            .backgroundColor($r('app.color.app_primary'))
            .fontColor(Color.White)
            .enabled(this.chapterList.length === 0 || this.chapterIndex < this.chapterList.length - 1)
            .onClick(() => {
              this.loadMangaChapter(this.chapterIndex + 1);
            })
        }
        .width('100%')
        .height(72)
        .padding({ left: 12, right: 12 })
        .justifyContent(FlexAlign.Center)
        .backgroundColor($r('app.color.app_background'))
      }
      .width('100%')
      .height('100%')

      // 章节目录抽屉 (左侧滑出, 复用 Reader.ets 抽屉模式)
      if (this.drawerVisible) {
        Row() {
          // 抽屉面板: 标题 + 章节列表
          Column() {
            Text($r('app.string.reader_chapter_list_title'))
              .fontSize(18)
              .fontWeight(FontWeight.Bold)
              .fontColor($r('app.color.text_primary'))
              .width('100%')
              .padding({ left: 16, right: 16, top: 16, bottom: 12 })

            if (this.chapterList.length === 0) {
              // 空目录占位
              Column() {
                Text($r('app.string.reader_chapter_empty'))
                  .fontSize(14)
                  .fontColor($r('app.color.text_secondary'))
              }
              .width('100%')
              .layoutWeight(1)
              .justifyContent(FlexAlign.Center)
              .alignItems(HorizontalAlign.Center)
            } else {
              // 章节列表 (当前章节高亮, 已读章节标记)
              List({ space: 0 }) {
                ForEach(this.chapterList, (ch: ChapterItem) => {
                  ListItem() {
                    Row({ space: 8 }) {
                      // 已读标记
                      Text(this.isChapterRead(ch.index) ? '●' : '')
                        .fontSize(8)
                        .fontColor($r('app.color.app_primary'))
                        .width(10)
                      // 章节标题 (当前章节高亮加粗)
                      Text(ch.title)
                        .fontSize(14)
                        .maxLines(1)
                        .layoutWeight(1)
                        .fontColor(this.chapterIndex === ch.index
                          ? $r('app.color.app_primary')
                          : $r('app.color.text_primary'))
                        .fontWeight(this.chapterIndex === ch.index
                          ? FontWeight.Bold
                          : FontWeight.Regular)
                    }
                    .width('100%')
                    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                    .backgroundColor(this.chapterIndex === ch.index
                      ? $r('app.color.app_background')
                      : Color.White)
                    .onClick(() => {
                      this.loadMangaChapter(ch.index);
                      this.drawerVisible = false;
                    })
                  }
                }, (ch: ChapterItem) => `${ch.index}_${ch.url}`)
              }
              .width('100%')
              .layoutWeight(1)
              .backgroundColor(Color.White)
            }
          }
          .width('70%')
          .height('100%')
          .backgroundColor(Color.White)

          // 抽屉右侧遮罩 (点击关闭)
          Column()
            .layoutWeight(1)
            .height('100%')
            .backgroundColor('#80000000')
            .onClick(() => {
              this.drawerVisible = false;
            })
        }
        .width('100%')
        .height('100%')
      }
    }
    .width('100%')
    .height('100%')
  }
}

/**
 * 章节目录项 (与 napi chapterList 返回 JSON 字段对齐)。
 */
interface ChapterItem {
  index: number;
  title: string;
  url: string;
}

/**
 * 漫画章节数据 (与 napi loadMangaChapter 返回 JSON 字段对齐)。
 */
interface MangaChapterData {
  images: string[];
}
