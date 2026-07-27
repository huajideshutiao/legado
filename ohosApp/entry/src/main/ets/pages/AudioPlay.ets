import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import { KeyCode } from '@ohos.multimodalInput.keyCode';
import legado from 'liblegado_napi.so';
import { handleMediaCommand, addMediaEventListener, removeMediaEventListener } from '../bridge/MediaBridgeHandler';

/**
 * 音频播放页 (ArkTS UI)。
 *
 * 对应 Android 端 AudioPlayActivity + shared AudioPlayScreenContent (Compose)。
 * 鸿蒙端无 AudioPlayService 等价物, 直接通过 media bridge (MediaBridgeHandler)
 * 驱动 AVPlayer (setSource/play/pause/seekTo/setSpeed/release),
 * AVPlayer 事件 (onDuration/onPosition/onPlaying/onPaused) 经本地监听回送 UI。
 *
 * - 顶部 TitleBar: 返回 + 书名 + 换源(占位)
 * - 中部封面(占位) + 章节副标题
 * - 进度条 + 当前/总时长
 * - 控制排: 上一章 | 后退10s | 播放/暂停 | 前进10s | 下一章
 * - 底部: 倍速 | 定时 | 目录
 * - 方向键左右 ±10s 进度跳转 (与 app 端 onPreviewKeyEvent 一致, 不切章节)
 *
 * 注: 章节音频源 URL 加载需新增 napi (调 AudioPlayShared.prev/next/skipTo + loadPlayUrl),
 * 当前仅渲染 UI + 通过 media bridge 控制已设源的播放; 章节目录来自 chapterList napi。
 */
@Entry
@Component
struct AudioPlay {
  // 路由参数 (从 Bookshelf 跳转传入)
  @State bookName: string = '';
  @State bookUrl: string = '';
  @State author: string = '';
  @State coverUrl: string = '';
  @State audioPath: string = '';
  @State chapterIndex: number = 0;

  // 章节目录 (napi chapterList 加载)
  @State chapterList: ChapterItem[] = [];

  // 播放状态 (来自 media bridge 事件)
  @State isPlaying: boolean = false;
  @State durationMs: number = 0;
  @State positionMs: number = 0;
  @State bufferMs: number = 0;
  @State loading: boolean = false;

  // 控制 UI 本地状态
  @State speed: number = 1.0;
  @State timerMinute: number = 0;
  @State drawerVisible: boolean = false;

  // 拖动中的进度预览 (-1=未拖动)
  @State dragMs: number = -1;

  // 定时器句柄
  private sleepTimerId: number = -1;

  // media 事件本地监听器引用 (aboutToDisappear 注销用)
  private mediaListener: ((eventJson: string) => void) | null = null;

  aboutToAppear(): void {
    // 接收跳转参数
    const params = router.getParams() as Record<string, Object>;
    if (params) {
      this.bookName = (params.bookName as string) ?? '';
      this.bookUrl = (params.bookUrl as string) ?? '';
      this.author = (params.author as string) ?? '';
      this.coverUrl = (params.coverUrl as string) ?? '';
      this.audioPath = (params.audioPath as string) ?? '';
      this.chapterIndex = (params.chapterIndex as number) ?? 0;
    }
    if (!this.bookName) {
      this.bookName = '(未选择书籍)';
    }

    this.loadChapterList();

    // 注册 media 事件监听, AVPlayer 事件 → UI 状态
    this.mediaListener = (eventJson: string): void => this.onMediaEvent(eventJson);
    addMediaEventListener(this.mediaListener);

    // 设源并准备 (audioPath 由调用方传入; 无则等待后续章节源 napi)
    if (this.audioPath.length > 0) {
      this.loading = true;
      this.sendMedia({ action: 'setSource', path: this.audioPath });
    }
  }

  aboutToDisappear(): void {
    if (this.mediaListener != null) {
      removeMediaEventListener(this.mediaListener);
      this.mediaListener = null;
    }
    this.clearSleepTimer();
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

  // 发送 media 命令到 AVPlayer
  private sendMedia(cmd: Record<string, Object>): void {
    try {
      handleMediaCommand(JSON.stringify(cmd));
    } catch (e) {
      promptAction.showToast({ message: '播放控制失败', duration: 1500 });
    }
  }

  // media 事件 → UI 状态
  private onMediaEvent(eventJson: string): void {
    let ev: MediaEventPayload;
    try {
      ev = JSON.parse(eventJson) as MediaEventPayload;
    } catch (e) {
      return;
    }
    switch (ev.event) {
      case 'onReady':
        this.loading = false;
        break;
      case 'onPlaying':
        this.isPlaying = true;
        this.loading = false;
        break;
      case 'onPaused':
        this.isPlaying = false;
        break;
      case 'onDuration':
        this.durationMs = ev.duration ?? 0;
        break;
      case 'onPosition':
        this.positionMs = ev.position ?? 0;
        break;
      case 'onBufferingUpdate':
        this.bufferMs = Math.floor((ev.percent ?? 0) / 100 * this.durationMs);
        break;
      case 'onEndOfMedia':
        this.isPlaying = false;
        break;
      case 'onError':
        this.loading = false;
        promptAction.showToast({ message: '播放错误: ' + (ev.message ?? ''), duration: 2000 });
        break;
      default:
        break;
    }
  }

  // 播放/暂停切换
  private togglePlay(): void {
    if (this.isPlaying) {
      this.sendMedia({ action: 'pause' });
    } else {
      this.sendMedia({ action: 'play' });
    }
  }

  // 进度跳转 (钳制到 [0, duration])
  private seekTo(ms: number): void {
    const target = Math.max(0, Math.min(ms, this.durationMs));
    this.positionMs = target;
    this.sendMedia({ action: 'seekTo', position: target });
  }

  // 上一章 (需新增 napi 调 AudioPlayShared.prev + loadPlayUrl; 当前占位)
  private prevChapter(): void {
    if (this.chapterIndex <= 0) return;
    promptAction.showToast({ message: '章节切换需音频源 napi', duration: 1500 });
  }

  // 下一章
  private nextChapter(): void {
    if (this.chapterList.length > 0 && this.chapterIndex >= this.chapterList.length - 1) return;
    promptAction.showToast({ message: '章节切换需音频源 napi', duration: 1500 });
  }

  // 设倍速 (经 media bridge setSpeed)
  private setSpeed(rate: number): void {
    this.speed = rate;
    this.sendMedia({ action: 'setSpeed', speed: rate });
  }

  // 设定时 (分钟, 0=取消; 到时调 pause)
  private setTimer(minute: number): void {
    this.timerMinute = minute;
    this.clearSleepTimer();
    if (minute > 0) {
      this.sleepTimerId = setTimeout(() => {
        this.sendMedia({ action: 'pause' });
        this.timerMinute = 0;
        this.sleepTimerId = -1;
      }, minute * 60 * 1000);
    }
  }

  private clearSleepTimer(): void {
    if (this.sleepTimerId !== -1) {
      clearTimeout(this.sleepTimerId);
      this.sleepTimerId = -1;
    }
  }

  // mm:ss 格式化
  private formatTime(ms: number): string {
    let v = ms;
    if (v < 0) v = 0;
    const totalSec = Math.floor(v / 1000);
    const m = Math.floor(totalSec / 60);
    const s = totalSec % 60;
    const mm = m < 10 ? '0' + m : '' + m;
    const ss = s < 10 ? '0' + s : '' + s;
    return mm + ':' + ss;
  }

  // 当前章节标题 (从 chapterList 取, 缺省占位)
  private currentChapterTitle(): string {
    if (this.chapterList.length > 0 && this.chapterIndex < this.chapterList.length) {
      return this.chapterList[this.chapterIndex].title;
    }
    return '未知章节';
  }

  build() {
    Stack({ alignContent: Alignment.Start }) {
      Column() {
        // TitleBar: 返回 + 书名 + 换源(占位)
        Row() {
          Text('返回')
            .fontSize(14)
            .fontColor(Color.White)
            .onClick(() => router.back())
          Text(this.bookName)
            .fontSize(18)
            .fontWeight(FontWeight.Medium)
            .fontColor(Color.White)
            .layoutWeight(1)
            .maxLines(1)
            .textAlign(TextAlign.Center)
          Text('换源')
            .fontSize(14)
            .fontColor(Color.White)
            .onClick(() => promptAction.showToast({ message: '换源功能开发中', duration: 1500 }))
        }
        .width('100%')
        .height(56)
        .backgroundColor($r('app.color.app_primary'))
        .padding({ left: 16, right: 16 })

        // 副标题: 当前章节名
        Text(this.currentChapterTitle())
          .fontSize(14)
          .fontColor(Color.White)
          .width('100%')
          .textAlign(TextAlign.Center)
          .padding({ left: 16, right: 16, top: 8, bottom: 8 })

        // 中部: 封面占位 + 书名 + 作者
        Column() {
          // 圆形封面占位 (200vp, accent 描边)
          Column() {
            Text('♪')
              .fontSize(60)
              .fontColor(Color.White)
          }
          .width(200)
          .height(200)
          .borderRadius(100)
          .border({ width: 2, color: $r('app.color.app_primary') })
          .backgroundColor('#424242')
          .justifyContent(FlexAlign.Center)

          Text(this.bookName)
            .fontSize(18)
            .fontWeight(FontWeight.Medium)
            .fontColor(Color.White)
            .margin({ top: 16 })
          Text(this.author.length > 0 ? this.author : '未知作者')
            .fontSize(14)
            .fontColor(Color.White)
            .margin({ top: 4 })
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)

        // 进度条 + 当前/总时长
        Row() {
          Text(this.formatTime(this.dragMs >= 0 ? this.dragMs : this.positionMs))
            .fontSize(12)
            .fontColor(Color.White)
            .width(60)
            .textAlign(TextAlign.Center)
          Slider({
            value: this.dragMs >= 0 ? this.dragMs : this.positionMs,
            min: 0,
            max: Math.max(this.durationMs, 1),
            style: SliderStyle.OutSet
          })
            .layoutWeight(1)
            .trackColor('#80FFFFFF')
            .selectedColor($r('app.color.app_primary'))
            .blockColor($r('app.color.app_primary'))
            .onChange((value: number, mode: SliderChangeMode) => {
              // Moving 阶段仅预览, End/Click 才 seek
              if (mode === SliderChangeMode.Moving) {
                this.dragMs = value;
              } else if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.dragMs = -1;
                this.seekTo(value);
              }
            })
          Text(this.formatTime(this.durationMs))
            .fontSize(12)
            .fontColor(Color.White)
            .width(60)
            .textAlign(TextAlign.Center)
        }
        .width('100%')
        .padding({ left: 12, right: 12 })

        // 控制排: 上一章 | 后退10s | 播放/暂停 | 前进10s | 下一章
        Row({ space: 8 }) {
          Text('⏮')
            .fontSize(26)
            .fontColor(Color.White)
            .width(48)
            .height(48)
            .textAlign(TextAlign.Center)
            .opacity(this.chapterIndex > 0 ? 1 : 0.3)
            .onClick(() => { if (this.chapterIndex > 0) this.prevChapter(); })
          Text('⏪')
            .fontSize(26)
            .fontColor(Color.White)
            .width(48)
            .height(48)
            .textAlign(TextAlign.Center)
            .onClick(() => this.seekTo(this.positionMs - 10000))
          // 播放/暂停 (圆形白底)
          Text(this.isPlaying ? '⏸' : '▶')
            .fontSize(30)
            .fontColor(Color.Black)
            .width(60)
            .height(60)
            .borderRadius(30)
            .backgroundColor(Color.White)
            .textAlign(TextAlign.Center)
            .onClick(() => this.togglePlay())
          Text('⏩')
            .fontSize(26)
            .fontColor(Color.White)
            .width(48)
            .height(48)
            .textAlign(TextAlign.Center)
            .onClick(() => this.seekTo(this.positionMs + 10000))
          Text('⏭')
            .fontSize(26)
            .fontColor(Color.White)
            .width(48)
            .height(48)
            .textAlign(TextAlign.Center)
            .opacity(this.chapterList.length === 0 || this.chapterIndex < this.chapterList.length - 1 ? 1 : 0.3)
            .onClick(() => this.nextChapter())
        }
        .width('100%')
        .height(72)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)

        // 底部: 倍速 | 定时 | 目录
        Row({ space: 16 }) {
          Text(this.speed === 1.0 ? '倍速' : this.speed + 'X')
            .fontSize(13)
            .fontColor(Color.White)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .borderRadius(8)
            .backgroundColor('#66000000')
            .bindMenu([
              { value: '0.75X', action: () => this.setSpeed(0.75) },
              { value: '1.0X', action: () => this.setSpeed(1.0) },
              { value: '1.25X', action: () => this.setSpeed(1.25) },
              { value: '1.5X', action: () => this.setSpeed(1.5) },
              { value: '2.0X', action: () => this.setSpeed(2.0) }
            ])
          Text(this.timerMinute > 0 ? this.timerMinute + '分钟' : '定时')
            .fontSize(13)
            .fontColor(Color.White)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .borderRadius(8)
            .backgroundColor('#66000000')
            .bindMenu([
              { value: '关闭', action: () => this.setTimer(0) },
              { value: '5分钟', action: () => this.setTimer(5) },
              { value: '10分钟', action: () => this.setTimer(10) },
              { value: '15分钟', action: () => this.setTimer(15) },
              { value: '20分钟', action: () => this.setTimer(20) },
              { value: '30分钟', action: () => this.setTimer(30) },
              { value: '60分钟', action: () => this.setTimer(60) }
            ])
          Text('目录')
            .fontSize(13)
            .fontColor(Color.White)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .borderRadius(8)
            .backgroundColor('#66000000')
            .onClick(() => { this.drawerVisible = true; })
        }
        .width('100%')
        .height(56)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#303030')

      // 章节目录抽屉 (左侧滑出, 70% 面板 + 30% 遮罩)
      if (this.drawerVisible) {
        Row() {
          Column() {
            Text('章节目录')
              .fontSize(18)
              .fontWeight(FontWeight.Bold)
              .fontColor($r('app.color.text_primary'))
              .width('100%')
              .padding({ left: 16, right: 16, top: 16, bottom: 12 })

            if (this.chapterList.length === 0) {
              Column() {
                Text('暂无章节')
                  .fontSize(14)
                  .fontColor($r('app.color.text_secondary'))
              }
              .width('100%')
              .layoutWeight(1)
              .justifyContent(FlexAlign.Center)
              .alignItems(HorizontalAlign.Center)
            } else {
              List({ space: 0 }) {
                ForEach(this.chapterList, (ch: ChapterItem) => {
                  ListItem() {
                    Row({ space: 8 }) {
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
                      // 切章需音频源 napi (调 AudioPlayShared.skipTo + loadPlayUrl)
                      promptAction.showToast({ message: '章节切换需音频源 napi', duration: 1500 });
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

          // 右侧遮罩 (点击关闭)
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
    // 方向键左右 ±10s 进度跳转 (与 app 端 onPreviewKeyEvent 一致, 不切章节)
    .onKeyEvent((event: KeyEvent): boolean => {
      if (event.type !== KeyType.Down) {
        return false;
      }
      if (event.keyCode === KeyCode.KEYCODE_DPAD_LEFT) {
        this.seekTo(this.positionMs - 10000);
        return true;
      }
      if (event.keyCode === KeyCode.KEYCODE_DPAD_RIGHT) {
        this.seekTo(this.positionMs + 10000);
        return true;
      }
      return false;
    })
  }
}

/**
 * media 事件载荷 (与 MediaBridgeHandler MediaEventPayload 对齐)。
 */
interface MediaEventPayload {
  event: string;
  message?: string;
  percent?: number;
  duration?: number;
  position?: number;
}

/**
 * 章节目录项 (与 napi chapterList 返回 JSON 字段对齐, 复用 Reader.ets 定义)。
 */
interface ChapterItem {
  index: number;
  title: string;
  url: string;
}
