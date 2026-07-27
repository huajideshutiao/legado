import router from '@ohos.router';
import legado from 'liblegado_napi.so';

/**
 * KMP 互操作测试页面 (ArkTS UI)。
 *
 * 演示 ArkTS 调用 KMP 共享代码 (通过 napi 桥接)。
 *
 * 桥接方案 (详见 ohosApp/INTEROP.md):
 * 1. shared 模块 Kotlin/Native 编译为 linuxArm64 共享库 `liblegado_shared.so`
 *    (用 `@CName("legado_chinese_t2s")` 导出 C ABI 函数)
 * 2. entry/src/main/cpp/ 提供 napi 中间层 (legado_napi.cpp)
 *    用 dlopen/dlsym 把 .so 中的 C 函数包装为 ArkTS 可调用的方法
 * 3. ArkTS 侧 `import legado from 'liblegado_napi.so'` 后调用 `legado.chineseT2S("简体")` 等函数
 *
 * KP5: 已接入真实 napi 桥接 (替代 KP4 的 mock 兜底)。
 * - 工具类: chineseT2S / chineseS2T / md5Encode / formatPercentUs / isProvidersRegistered
 * - 业务类: bookshelfList / searchBook / loadChapter / importBookSource
 *
 * 注: 业务类函数内部 runBlocking 转 suspend, 应在 TaskPool/Worker 调用;
 * 当前简化版直接在 runAllTests 调用 (测试数据量小, 阻塞可接受)。
 */

@Entry
@Component
struct KmpInteropTest {
  @State inputText: string = '简体转繁体';
  @State t2sResult: string = '';
  @State s2tResult: string = '';
  @State md5Result: string = '';
  @State percentResult: string = '';
  @State providersRegistered: boolean = false;
  // KP5 业务类测试结果
  @State bookshelfResult: string = '';
  @State searchResult: string = '';
  @State loadChapterResult: string = '';
  @State importSourceResult: string = '';

  /**
   * 运行所有 napi 函数测试。
   *
   * 工具类用 inputText 作为输入; 业务类中:
   * - bookshelfList() 无参数, 返回书架书籍 JSON 数组
   * - searchBook(inputText) 用 inputText 作为搜索关键词
   * - loadChapter(inputText, 0) 用 inputText 作为 bookUrl (用户可输入真实 bookUrl 测试)
   * - importBookSource('[]') 用空数组测试 (避免导入垃圾数据, 返回 0)
   */
  private runAllTests(): void {
    // 工具类 (KP4)
    this.t2sResult = legado.chineseT2S(this.inputText);
    this.s2tResult = legado.chineseS2T(this.inputText);
    this.md5Result = legado.md5Encode(this.inputText);
    this.percentResult = legado.formatPercentUs(0.7321);
    this.providersRegistered = legado.isProvidersRegistered();

    // 业务类 (KP5 新增)
    // bookshelfList: 返回 JSON 数组, 解析后统计书籍数量
    try {
      const json = legado.bookshelfList();
      const list = JSON.parse(json) as Object[];
      this.bookshelfResult = `共 ${list.length} 本书 (JSON ${json.length} 字节)`;
    } catch (e) {
      this.bookshelfResult = `解析失败: ${(e as Error).message ?? e}`;
    }

    // searchBook: 用 inputText 作为搜索关键词, 返回匹配书籍 JSON 数组
    try {
      const json = legado.searchBook(this.inputText);
      const list = JSON.parse(json) as Object[];
      this.searchResult = `匹配 ${list.length} 本书 (关键词: "${this.inputText}")`;
    } catch (e) {
      this.searchResult = `解析失败: ${(e as Error).message ?? e}`;
    }

    // loadChapter: 用 inputText 作为 bookUrl, 加载第 0 章
    try {
      const content = legado.loadChapter(this.inputText, 0);
      this.loadChapterResult = `内容长度 ${content.length} 字符 (bookUrl="${this.inputText}")`;
    } catch (e) {
      this.loadChapterResult = `调用失败: ${(e as Error).message ?? e}`;
    }

    // importBookSource: 用空数组测试 (返回 0, 避免导入垃圾数据)
    try {
      const count = legado.importBookSource('[]');
      this.importSourceResult = `导入 ${count} 个书源 (测试用空数组, 实际可传真实书源 JSON)`;
    } catch (e) {
      this.importSourceResult = `调用失败: ${(e as Error).message ?? e}`;
    }
  }

  aboutToAppear(): void {
    this.runAllTests();
  }

  build() {
    Column() {
      // 顶部标题栏
      Row() {
        Text('返回')
          .fontSize(14)
          .fontColor(Color.White)
          .onClick(() => router.back())
        Text($r('app.string.kmp_interop_title'))
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(Color.White)
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
        Text('')
          .width(40)
      }
      .width('100%')
      .height(56)
      .backgroundColor($r('app.color.app_primary'))
      .padding({ left: 16, right: 16 })

      Scroll() {
        Column({ space: 12 }) {
          // 输入区
          Column({ space: 8 }) {
            Text('输入文本')
              .fontSize(14)
              .fontColor($r('app.color.text_secondary'))
            TextInput({ text: this.inputText })
              .width('90%')
              .height(44)
              .fontSize(14)
              .backgroundColor(Color.White)
              .padding({ left: 12, right: 12 })
              .onChange((value: string) => {
                this.inputText = value;
              })

            Button('运行测试')
              .width('90%')
              .height(40)
              .fontSize(14)
              .backgroundColor($r('app.color.app_primary'))
              .fontColor(Color.White)
              .onClick(() => this.runAllTests())
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 16 })

          // 结果区 - 工具类 (KP4)
          Column({ space: 12 }) {
            this.renderResultRow('ChineseUtils.t2s()', this.t2sResult)
            this.renderResultRow('ChineseUtils.s2t()', this.s2tResult)
            this.renderResultRow('MD5Utils.md5Encode()', this.md5Result)
            this.renderResultRow('formatPercentUs(0.7321)', this.percentResult)
            this.renderResultRow(
              'registerOhosProviders()',
              this.providersRegistered ? '已注册' : '未注册 (napi 桥接异常)'
            )
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8 })

          // 结果区 - 业务类 (KP5 新增)
          Column({ space: 12 }) {
            this.renderResultRow('bookshelfList()', this.bookshelfResult)
            this.renderResultRow('searchBook(inputText)', this.searchResult)
            this.renderResultRow('loadChapter(inputText, 0)', this.loadChapterResult)
            this.renderResultRow('importBookSource("[]")', this.importSourceResult)
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8 })

          // 桥接状态提示
          Column({ space: 4 }) {
            Text('桥接状态')
              .fontSize(12)
              .fontColor($r('app.color.text_secondary'))
            Text(this.providersRegistered
              ? '✓ KMP .so 已加载, provider 已注册'
              : '○ napi 桥接异常 (详见 ohosApp/INTEROP.md)')
              .fontSize(12)
              .fontColor($r('app.color.text_secondary'))
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 24 })
        }
        .width('100%')
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor($r('app.color.app_background'))
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  renderResultRow(label: string, value: string) {
    Column({ space: 4 }) {
      Text(label)
        .fontSize(12)
        .fontColor($r('app.color.text_secondary'))
        .width('100%')
      Text(value)
        .fontSize(14)
        .fontColor($r('app.color.text_primary'))
        .width('100%')
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .backgroundColor(Color.White)
    }
    .width('100%')
  }
}
