import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import media from '@ohos.multimedia.media';
import { KeyCode } from '@ohos.multimodalInput.keyCode';
import legado from 'liblegado_napi.so';
import { addMediaEventListener, removeMediaEventListener } from '../bridge/MediaBridgeHandler';

/**
 * 视频播放页 (ArkTS UI)。
 *
 * 对应 Android 端 VideoPlayActivity + shared VideoPlayerScreenContent (Compose)。
 * - 全屏 ArkUI Video 组件渲染 (VideoController 控制播放/暂停/seek)
 * - 顶部 TitleBar: 返回 + 书名 + 章节名 (半透明黑底白字, 对齐 shared VideoTitleBar)
 * - 控制层 (中央 prev/-10s/play/+10s/next + 底部 进度条/时间/倍速/目录, 5s 自动隐藏,
 *   对齐 VideoControlsController.autoHideTimeout = 5000ms)
 * - 章节目录抽屉 (左侧滑出, 复用 napi chapterList, 与 Reader/AudioPlay 一致)
 * - 方向键左右 ±10s 进度跳转 (与 app 端 onPreviewKeyEvent 一致, 不切章节)
 *
 * 注: 鸿蒙端 media bridge 的 AVPlayer 未绑定 surface, 无法直接渲染视频;
 * 当前 Video 组件自管 AVPlayer, 控制经 VideoController; 同时注册 media bridge
 * 事件监听器, 为后续新增 surface 绑定 napi (让 bridge AVPlayer 输出到 Video surface) 预留。
 * 章节视频源 URL 加载需新增 napi (调 VideoPlayShared.prev/next/skipTo + loadPlayUrl)。
 */
@Entry
@Component
struct VideoPlay {
  // 路由参数 (从 Bookshelf 跳转传入)
  @State bookName: string = '';
  @State bookUrl: string = '';
  @State videoPath: string = '';
  @State chapterIndex: number = 0;

  // 章节目录 (napi chapterList 加载)
  @State chapterList: ChapterItem[] = [];

  // 播放状态 (Video 组件事件驱动)
  @State isPlaying: boolean = false;
  @State durationMs: number = 0;
  @State positionMs: number = 0;
  @State loading: boolean = false;

  // 控制 UI 本地状态
  @State speed: number = 1.0;
  @State drawerVisible: boolean = false;
  @State controlsVisible: boolean = true;

  // 拖动中的进度预览 (-1=未拖动)
  @State dragMs: number = -1;

  // Video 组件控制器 (start/pause/stop/seek)
  private controller: VideoController = new VideoController();

  // 控制层自动隐藏定时器句柄
  private autoHideTimerId: number = -1;

  // media 事件本地监听器引用 (aboutToDisappear 注销用; 当前 bridge AVPlayer 未接 surface,
  // 事件不会触发, 仅为后续 surface 绑定 napi 预留)
  private mediaListener: ((eventJson: string) => void) | null = null;

  aboutToAppear(): void {
    // 接收跳转参数
    const params = router.getParams() as Record<string, Object>;
    if (params) {
      this.bookName = (params.bookName as string) ?? '';
      this.bookUrl = (params.bookUrl as string) ?? '';
      this.videoPath = (params.videoPath as string) ?? '';
      this.chapterIndex = (params.chapterIndex as number) ?? 0;
    }
    if (!this.bookName) {
      this.bookName = '(未选择视频)';
    }

    this.loadChapterList();

    // 注册 media 事件监听 (为后续 surface 绑定 napi 预留)
    this.mediaListener = (eventJson: string): void => this.onMediaEvent(eventJson);
    addMediaEventListener(this.mediaListener);

    // 有源则等待 Video onPrepared 完成 (加载态覆盖层显示到 onPrepared/onError)
    this.loading = this.videoPath.length > 0;
  }

  aboutToDisappear(): void {
    if (this.mediaListener != null) {
      removeMediaEventListener(this.mediaListener);
      this.mediaListener = null;
    }
    this.clearAutoHideTimer();
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

  // media 事件 → UI 状态 (当前 bridge AVPlayer 未接 surface, 事件不触发; 预留)
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
        this.scheduleAutoHide();
        break;
      case 'onPaused':
        this.isPlaying = false;
        this.cancelAutoHide();
        break;
      case 'onDuration':
        this.durationMs = ev.duration ?? 0;
        break;
      case 'onPosition':
        this.positionMs = ev.position ?? 0;
        break;
      case 'onError':
        this.loading = false;
        promptAction.showToast({ message: '播放错误: ' + (ev.message ?? ''), duration: 2000 });
        break;
      default:
        break;
    }
  }

  // 播放/暂停切换 (VideoController)
  private togglePlay(): void {
    if (this.isPlaying) {
      this.controller.pause();
    } else {
      this.controller.start();
    }
    this.showControlsAndScheduleAutoHide();
  }

  // 进度跳转 (钳制到 [0, duration])
  private seekTo(ms: number): void {
    const target = Math.max(0, Math.min(ms, this.durationMs));
    this.positionMs = target;
    this.controller.setCurrentTime(target, SeekMode.Accurate);
    this.showControlsAndScheduleAutoHide();
  }

  // 上一章 (需新增 napi 调 VideoPlayShared.prev + loadPlayUrl; 当前占位)
  private prevChapter(): void {
    if (this.chapterIndex <= 0) return;
    promptAction.showToast({ message: '章节切换需视频源 napi', duration: 1500 });
  }

  // 下一章
  private nextChapter(): void {
    if (this.chapterList.length > 0 && this.chapterIndex >= this.chapterList.length - 1) return;
    promptAction.showToast({ message: '章节切换需视频源 napi', duration: 1500 });
  }

  // 设倍速 (Video 组件 currentProgressRate, 浮点 → PlaybackSpeed 枚举档位)
  private setSpeed(rate: number): void {
    this.speed = rate;
    this.showControlsAndScheduleAutoHide();
  }

  // 浮点倍速 → PlaybackSpeed 枚举 (与 MediaBridgeHandler.mapSpeed 档位一致)
  private mapSpeed(rate: number): media.PlaybackSpeed {
    if (rate <= 0.875) return media.PlaybackSpeed.SPEED_FORWARD_0_75_X;
    if (rate <= 1.125) return media.PlaybackSpeed.SPEED_FORWARD_1_00_X;
    if (rate <= 1.5) return media.PlaybackSpeed.SPEED_FORWARD_1_25_X;
    if (rate <= 1.875) return media.PlaybackSpeed.SPEED_FORWARD_1_75_X;
    return media.PlaybackSpeed.SPEED_FORWARD_2_00_X;
  }

  // 倍速文字 (去尾零 + X, 对齐 shared speedLabel)
  private speedLabel(rate: number): string {
    if (rate === 1.0) return '1X';
    if (rate === 0.75) return '0.75X';
    if (rate === 1.25) return '1.25X';
    if (rate === 1.75) return '1.75X';
    if (rate === 2.0) return '2X';
    return rate + 'X';
  }

  // 显示控制层并安排 5s 后自动隐藏 (仅播放中且非拖动时隐藏, 对齐 VideoControlsController)
  private showControlsAndScheduleAutoHide(): void {
    this.controlsVisible = true;
    this.scheduleAutoHide();
  }

  // 安排自动隐藏 (播放中且非拖动才隐藏; 对齐 VideoControlsController.autoHideTimeout = 5000ms)
  private scheduleAutoHide(): void {
    this.clearAutoHideTimer();
    if (this.isPlaying && this.dragMs < 0) {
      this.autoHideTimerId = setTimeout(() => {
        this.controlsVisible = false;
        this.autoHideTimerId = -1;
      }, 5000);
    }
  }

  private cancelAutoHide(): void {
    this.clearAutoHideTimer();
  }

  private clearAutoHideTimer(): void {
    if (this.autoHideTimerId !== -1) {
      clearTimeout(this.autoHideTimerId);
      this.autoHideTimerId = -1;
    }
  }

  // 切换控制层显隐 (点击视频区触发)
  private toggleControls(): void {
    if (this.controlsVisible) {
      this.controlsVisible = false;
      this.clearAutoHideTimer();
    } else {
      this.showControlsAndScheduleAutoHide();
    }
  }

  // mm:ss / h:mm:ss 格式化 (对齐 shared Long.toDurationTime)
  private formatTime(ms: number): string {
    let v = ms;
    if (v < 0) v = 0;
    const totalSec = Math.floor(v / 1000);
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    if (h > 0) {
      const mm = m < 10 ? '0' + m : '' + m;
      const ss = s < 10 ? '0' + s : '' + s;
      return h + ':' + mm + ':' + ss;
    }
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
      // 全屏 Video 组件 (ArkUI Video + VideoController, 自管 AVPlayer)
      Video({
        src: this.videoPath,
        currentProgressRate: this.mapSpeed(this.speed),
        controller: this.controller
      })
        .width('100%')
        .height('100%')
        .autoPlay(true)
        .controls(false)
        .objectFit(ImageFit.Contain)
        .onPrepared((event: { duration: number }) => {
          this.durationMs = event.duration;
          this.loading = false;
        })
        .onStart(() => {
          this.isPlaying = true;
          this.scheduleAutoHide();
        })
        .onPause(() => {
          this.isPlaying = false;
          this.cancelAutoHide();
          this.controlsVisible = true;
        })
        .onFinish(() => {
          this.isPlaying = false;
          this.cancelAutoHide();
          this.controlsVisible = true;
        })
        .onUpdate((event: { time: number }) => {
          this.positionMs = event.time;
        })
        .onError(() => {
          this.loading = false;
          promptAction.showToast({ message: '视频加载失败', duration: 2000 });
        })
        .onClick(() => this.toggleControls())

      // 顶部 TitleBar: 返回 + 书名 + 章节名 (半透明黑底白字, 对齐 shared VideoTitleBar)
      if (this.controlsVisible) {
        Row() {
          Text('返回')
            .fontSize(14)
            .fontColor(Color.White)
            .onClick(() => router.back())
          Column() {
            Text(this.bookName)
              .fontSize(18)
              .fontWeight(FontWeight.Medium)
              .fontColor(Color.White)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(this.currentChapterTitle())
              .fontSize(12)
              .fontColor('#B3FFFFFF')
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 2 })
          }
          .layoutWeight(1)
          .margin({ left: 12, right: 12 })
          .alignItems(HorizontalAlign.Start)
          Text('目录')
            .fontSize(14)
            .fontColor(Color.White)
            .onClick(() => { this.drawerVisible = true; })
          Text('换源')
            .fontSize(14)
            .fontColor(Color.White)
            .margin({ left: 16 })
            .onClick(() => promptAction.showToast({ message: '换源功能开发中', duration: 1500 }))
        }
        .width('100%')
        .height(56)
        .backgroundColor('#98000000')
        .padding({ left: 16, right: 16 })
        .align(Alignment.Top)
      }

      // 中央控制行 (prev | -10s | play/pause | +10s | next, 对齐 shared VideoCenterControls)
      if (this.controlsVisible) {
        Row({ space: 12 }) {
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
          // 播放/暂停 (圆形白底, 对齐 shared PlayPauseButton 64dp)
          Text(this.isPlaying ? '⏸' : '▶')
            .fontSize(30)
            .fontColor(Color.Black)
            .width(64)
            .height(64)
            .borderRadius(32)
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
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .align(Alignment.Center)
      }

      // 底部控制行: 当前时间 | 进度条 | 总时长 | 倍速 (对齐 shared VideoControlsOverlay 底栏)
      if (this.controlsVisible) {
        Column() {
          Slider({
            value: this.dragMs >= 0 ? this.dragMs : this.positionMs,
            min: 0,
            max: Math.max(this.durationMs, 1),
            style: SliderStyle.OutSet
          })
            .width('100%')
            .trackColor('#80FFFFFF')
            .selectedColor($r('app.color.app_primary'))
            .blockColor($r('app.color.app_primary'))
            .onChange((value: number, mode: SliderChangeMode) => {
              // Moving 阶段仅预览 + 暂停自动隐藏, End/Click 才 seek
              if (mode === SliderChangeMode.Moving) {
                this.dragMs = value;
                this.cancelAutoHide();
              } else if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.dragMs = -1;
                this.seekTo(value);
              }
            })

          Row() {
            Text(this.formatTime(this.dragMs >= 0 ? this.dragMs : this.positionMs))
              .fontSize(12)
              .fontColor(Color.White)
              .layoutWeight(1)
            Text(this.speedLabel(this.speed))
              .fontSize(14)
              .fontWeight(FontWeight.Bold)
              .fontColor(Color.White)
              .padding({ left: 12, right: 12, top: 6, bottom: 6 })
              .borderRadius(4)
              .backgroundColor('#66000000')
              .bindMenu([
                { value: '0.75X', action: () => this.setSpeed(0.75) },
                { value: '1X', action: () => this.setSpeed(1.0) },
                { value: '1.25X', action: () => this.setSpeed(1.25) },
                { value: '1.75X', action: () => this.setSpeed(1.75) },
                { value: '2X', action: () => this.setSpeed(2.0) }
              ])
            Text(this.formatTime(this.durationMs))
              .fontSize(12)
              .fontColor(Color.White)
              .layoutWeight(1)
              .textAlign(TextAlign.End)
          }
          .width('100%')
          .padding({ left: 8, right: 8, top: 4, bottom: 4 })
        }
        .width('100%')
        .padding({ left: 8, right: 8, bottom: 8 })
        .backgroundColor('#98000000')
        .align(Alignment.Bottom)
      }

      // 加载态 (Video onPrepared 前)
      if (this.loading) {
        Column() {
          Text('加载中…')
            .fontSize(14)
            .fontColor(Color.White)
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      }

      // 章节目录抽屉 (左侧滑出, 70% 面板 + 30% 遮罩, 与 AudioPlay/Reader 一致)
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
                      // 切章需视频源 napi (调 VideoPlayShared.skipTo + loadPlayUrl)
                      promptAction.showToast({ message: '章节切换需视频源 napi', duration: 1500 });
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
    .backgroundColor(Color.Black)
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
 * 章节目录项 (与 napi chapterList 返回 JSON 字段对齐, 复用 Reader/AudioPlay 定义)。
 */
interface ChapterItem {
  index: number;
  title: string;
  url: string;
}
