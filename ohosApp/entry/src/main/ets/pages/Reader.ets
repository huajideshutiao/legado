import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
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
 * - 顶部标题栏 (返回按钮 + 书名 + 当前章节序号 + 菜单按钮)
 * - 中部正文区域 (Text 显示当前章节内容, 空内容时显示占位提示)
 * - 底部翻页按钮区 (上一章 / 章节进度条 / 下一章, 始终显示, 对齐 app 端"音量键始终翻页"行为)
 * - 左侧章节目录抽屉 (通过 napi chapterList 获取, 当前章节高亮, 已读章节标记)
 *
 * 注: loadChapter() / chapterList() 内部 runBlocking 转 suspend, 应在 TaskPool/Worker 调用;
 * 当前简化版直接在 aboutToAppear / 按钮回调调用 (单章加载阻塞可接受), 后续 KP6+ 改 TaskPool。
 * 仅读本地缓存章节, 不联网拉取 (WebBook.getContentAwait 编排复杂, 后续 KP6+ 实现)。
 * 朗读 / 设置菜单项为占位 (ArkTS 不能直接调 KMP ReadAloudController / ReadConfigScreen,
 * 后续 KP6+ 在 KMP 侧暴露 startReadAloud / showReadConfig napi 接口)。
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

  // 章节目录 (从 napi chapterList 加载, JSON 数组解析为 ChapterItem[])
  @State chapterList: ChapterItem[] = [];

  // 已读章节索引集合 (加载过的章节加入此集合, 用于目录项已读标记)
  @State readChapters: number[] = [];

  // 章节抽屉可见性 (顶部菜单"目录"切换)
  @State drawerVisible: boolean = false;

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
    // 加载章节目录 + 起始章节正文
    this.loadChapterList();
    this.loadChapter(this.chapterIndex);
  }

  /**
   * 调用 napi chapterList() 加载章节目录。
   *
   * napi 返回 JSON 数组字符串, 用 JSON.parse 解析为 ChapterItem[]。
   * 异常时 napi 返回 "[]" (空数组), 抽屉显示空目录占位。
   */
  private loadChapterList(): void {
    if (!this.bookUrl) {
      this.chapterList = [];
      return;
    }
    try {
      const json = legado.chapterList(this.bookUrl);
      const list = JSON.parse(json) as ChapterItem[];
      this.chapterList = list;
    } catch (e) {
      // napi 桥接未就绪或 JSON 解析失败, 显示空目录
      this.chapterList = [];
    }
  }

  /**
   * 调用 napi loadChapter() 加载指定章节正文。
   *
   * napi 返回 UTF-8 字符串 (章节正文); 缓存未命中 / 章节越界 / 异常时返回空字符串。
   * 空内容时显示占位提示, 不修改 chapterIndex (让用户知道目标章节无内容)。
   * 加载成功后将该章节索引加入 readChapters 集合 (用于目录已读标记)。
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
      // 标记当前章节为已读 (替换数组触发 @State 观察更新)
      if (!this.readChapters.includes(idx)) {
        this.readChapters = [...this.readChapters, idx];
      }
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

  /**
   * 判断章节是否已读 (索引存在于 readChapters 集合中)。
   */
  private isChapterRead(idx: number): boolean {
    return this.readChapters.includes(idx);
  }

  /**
   * 占位菜单项: 设置 / 朗读功能暂未接入, 提示开发中。
   * 后续 KP6+ 在 KMP 侧暴露 startReadAloud / showReadConfig napi 接口后接入。
   */
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
          // 菜单按钮 (点击弹出目录 / 设置 / 朗读 菜单)
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
              },
              {
                value: $r('app.string.reader_read_aloud'),
                action: () => { this.showNotImplemented(); }
              }
            ])
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

        // 底部翻页按钮区 (上一章 + 章节进度条 + 下一章, 始终显示)
        Row({ space: 8 }) {
          // 上一章按钮 (左翻页)
          Button('◀')
            .width(44)
            .height(44)
            .fontSize(18)
            .backgroundColor($r('app.color.app_background'))
            .fontColor($r('app.color.text_primary'))
            .enabled(this.chapterIndex > 0)
            .onClick(() => {
              if (this.chapterIndex > 0) {
                this.loadChapter(this.chapterIndex - 1);
              }
            })

          // 章节进度条 (拖动跳转章节, 仅在松手时加载避免连续加载)
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
              // 仅在 End (松手) 或 Click (点击轨道) 时跳转, 避免 Moving 阶段频繁加载
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                const target = Math.floor(value);
                if (target !== this.chapterIndex) {
                  this.loadChapter(target);
                }
              }
            })

          // 下一章按钮 (右翻页)
          Button('▶')
            .width(44)
            .height(44)
            .fontSize(18)
            .backgroundColor($r('app.color.app_primary'))
            .fontColor(Color.White)
            .enabled(this.chapterList.length === 0 || this.chapterIndex < this.chapterList.length - 1)
            .onClick(() => {
              this.loadChapter(this.chapterIndex + 1);
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

      // 章节目录抽屉 (左侧滑出, 70% 宽面板 + 30% 宽遮罩)
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
              // 章节列表 (当前章节高亮, 已读章节显示标记)
              List({ space: 0 }) {
                ForEach(this.chapterList, (ch: ChapterItem) => {
                  ListItem() {
                    Row({ space: 8 }) {
                      // 已读标记 (左对齐, 已读显示绿色小点, 未读不显示)
                      Text(this.isChapterRead(ch.index) ? '●' : '')
                        .fontSize(8)
                        .fontColor($r('app.color.app_primary'))
                        .width(10)
                      // 章节标题 (当前章节高亮加粗, 其余常规)
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
                      this.loadChapter(ch.index);
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
 * 仅含 UI 所需字段 (index/title/url), 不传输 BookChapter 全部字段。
 */
interface ChapterItem {
  index: number;
  title: string;
  url: string;
}
