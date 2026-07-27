import UIAbility from '@ohos.app.ability.UIAbility';
import type { AbilityConstant, Want } from '@ohos.app.ability.AbilityConstant';
import type { Configuration } from '@ohos.app.ability.Configuration';
import type window from '@ohos.window';
import notificationManager from '@ohos.notificationManager';
// napi 桥接层加载 (鸿蒙编译产物 liblegado_napi.so)
// 详见 ohosApp/INTEROP.md 与 ohosApp/entry/src/main/cpp/legado_napi.cpp
// KP5: napi 桥接已就绪 (bookshelfList/searchBook/loadChapter/importBookSource 接入真实 KMP 数据),
// 与 Bookshelf.ets / Reader.ets / KmpInteropTest.ets 一致地 import legado from 'liblegado_napi.so'
import legado from 'liblegado_napi.so';
// KP8+ Image/Media 桥接处理器 (ArkTS 侧 PixelMap/AVPlayer 真实实现)
import { handleImageRequest } from '../bridge/ImageBridgeHandler';
import { initMediaBridge, handleMediaCommand } from '../bridge/MediaBridgeHandler';
import { initTtsBridge, handleTtsCommand } from '../bridge/TtsBridgeHandler';
import { handleCryptoRequest } from '../bridge/CryptoBridgeHandler';
import { handleHttpRequest } from '../bridge/HttpBridgeHandler';
import { handleFilePickerRequest } from '../bridge/FilePickerBridgeHandler';
import { handlePasteboardRequest } from '../bridge/PasteboardBridgeHandler';
import { handleTextCodecRequest } from '../bridge/TextCodecBridgeHandler';
// KP8+ System 桥接处理器 (ArkTS 侧 promptAction.showToast + context.startAbility 真实实现)
import { initSystemBridge, handleShowToast, handleOpenUrl } from '../bridge/SystemBridgeHandler';

// Notification tsfn 跨语言 payload (与 OhosNativeBridge.kt NotificationPayload 对齐)
// 由 KMP 侧 KS_JSON 序列化为 JSON 字符串, 经 C++ tsfn dispatch 到 ArkTS 回调, 此处 JSON.parse 还原。
// 注: Toast payload 已移至 SystemBridgeHandler.ets (与 handleShowToast 一并管理)
interface NotificationPayload {
  action: string;  // 'SHOW' | 'CANCEL'
  id: number;
  title?: string;
  content?: string;
  progress?: number;
  max?: number;
}

/**
 * 鸿蒙 UIAbility 入口 - Legado 应用启动入口。
 *
 * 对应 Android 端 `MainActivity` (app/src/main/java/io/legado/app/ui/main/MainActivity.kt)。
 * 当前为 KP8+ 阶段:
 * 1. onCreate 中调用 [legado.registerOhosProviders] 注册 shared 模块所有 KMP provider
 *    (AppFilesDir/PreferenceProvider/AppConfigAccessor/AppDb/BookStorage/SourceHelp/JsEngines/Tts 等)
 *    —— 见 [io.legado.app.help.config.registerOhosProviders]
 * 2. onWindowStageCreate 中加载 `pages/Index` 作为首页
 * 3. KP5 已接入真实数据库 (Room KMP BundledSQLiteDriver) 与书源加载 (napi bookshelfList/searchBook/loadChapter)
 * 4. KP7+ 已注入 filesDir/cacheDir 沙盒路径 + 注册 Toast/Notification tsfn 回调
 * 5. KP8+ 已注册 Image/Media tsfn 回调, 接入 @ohos.multimedia.image (PixelMap) 和 @ohos.multimedia.media (AVPlayer)
 *    使书源 JS 图片操作 (decode/encode/crop/split/stitch) 和 HTTP TTS 播放在鸿蒙端真实工作
 * 6. KP8+ 已注册 TTS tsfn 回调, 接入 @ohos.textToSpeech 系统语音合成引擎,
 *    使系统 TTS 朗读 (createEngine/speak/stop/shutdown) 在鸿蒙端真实工作
 * 7. KP8+ 已注册 Crypto tsfn 回调, 接入 @ohos.security.cryptoFramework (createAsyCodec/createSign/createVerify),
 *    使书源 JS 非对称加解密 (createAsymmetricCrypto encrypt/decrypt) 和签名/验签 (createSign sign/verify) 在鸿蒙端真实工作
 * 8. KP8+ 已注册 OpenUrl tsfn 回调 + 抽出 SystemBridgeHandler 处理 showToast/openUrl,
 *    使 OhosToastProvider.showToast (promptAction.showToast) 和 OhosOpenUrlProvider.openUrl (context.startAbility)
 *    在鸿蒙端真实工作 (此前均为 println stub)
 *
 * 注意: `liblegado_napi.so` 由 CMakeLists.txt 编译 legado_napi.cpp 生成,
 * 依赖 Kotlin/Native linuxArm64 产物 liblegado_shared.so。
 * napi 桥接层未就绪 (CMakeLists.txt 中 LEGADO_SHARED_AVAILABLE=0) 时, dlsym 解析的函数指针为 null,
 * legado_napi.cpp 内部各函数会返回 mock 兜底值 (空数组/空串/0), 不影响 UI 骨架运行。
 */
export default class EntryAbility extends UIAbility {

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    console.info('[Legado] EntryAbility.onCreate');

    // KP7+: 注入鸿蒙应用沙盒 filesDir / cacheDir 真实路径 (供 shared AppFilesDir / DatabaseDriver 使用)。
    // 必须在 registerOhosProviders 之前调用, 使 OhosDatabaseDriver.defaultDbPath 读到真实沙盒路径
    // (而非 POSIX user.dir 回退路径)。context.filesDir / cacheDir 为鸿蒙 Context 标准沙盒目录。
    try {
      legado.registerFileDir(this.context.filesDir);
      legado.registerCacheDir(this.context.cacheDir);
      console.info('[Legado] ohos sandbox dirs injected: filesDir=' + this.context.filesDir);
    } catch (e) {
      console.error('[Legado] registerFileDir/CacheDir failed: ' + JSON.stringify(e));
    }

    // KP8+: 初始化 System 桥接处理器 (注入 UIAbilityContext 供 handleOpenUrl 调 startAbility)。
    // 必须在 registerToastCallback / registerOpenUrlCallback 之前调用, 使 tsfn 回调触发时
    // SystemBridgeHandler.abilityContext 已就绪 (避免 KMP 极早调用 openUrl 时 context 未注入)。
    try {
      initSystemBridge(this.context);
      console.info('[Legado] system bridge initialized (UIAbilityContext injected)');
    } catch (e) {
      console.error('[Legado] initSystemBridge failed: ' + JSON.stringify(e));
    }

    // KP5: 注册 shared 模块所有 KMP provider。
    // 必须在任何 commonMain 业务代码调用前完成 (webBook 编排链依赖 AppConfigAccessor;
    // Bookshelf.ets aboutToAppear 调 legado.bookshelfList() 依赖 AppDbProviders 已注册)。
    // napi 桥接层 (legado_napi.cpp) 内部已对 dlsym 失败做 null 兜底, 此处调用始终安全。
    try {
      legado.registerOhosProviders();
      console.info('[Legado] KMP providers registered via napi');
    } catch (e) {
      console.error('[Legado] registerOhosProviders failed: ' + JSON.stringify(e));
    }

    // KP7+: 注册 Toast/Notification tsfn 回调 (KMP → ArkTS 跨线程 dispatch)。
    // C++ 侧 (legado_napi.cpp RegisterToastCallback) 创建 napi_threadsafe_function 包装此回调,
    // 并通过 @CName legado_register_toast_fn/legado_register_notification_fn 把 dispatch 函数指针
    // 注入 Kotlin OhosNativeBridge.toastTsfn/notificationTsfn。此后 KMP 业务侧
    // OhosNativeBridge.showToast/showNotification 调用时, JSON payload 跨线程 dispatch 到此回调,
    // 由 ArkTS 调 promptAction.showToast / notificationManager.publish (KMP 无 ArkTS API 访问能力)。
    // 未注册时 KMP 侧降级 println (见 OhosNativeBridge.showToast/showNotification)。
    // KP8+: toast 回调抽至 SystemBridgeHandler.handleShowToast (与 handleOpenUrl 一并管理 system 桥接)。
    try {
      legado.registerToastCallback(handleShowToast);
      legado.registerNotificationCallback((json: string): void => {
        try {
          const payload = JSON.parse(json) as NotificationPayload;
          if (payload.action === 'CANCEL') {
            notificationManager.cancel(payload.id);
            return;
          }
          // SHOW: 发基本文本通知 (进度文本已由 KMP 侧拼入 content, 见 NotificationProgress.ohos.kt)
          const request: notificationManager.NotificationRequest = {
            id: payload.id,
            content: {
              notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
              normal: { title: payload.title ?? '', text: payload.content ?? '' }
            }
          };
          notificationManager.publish(request);
        } catch (e) {
          console.error('[Legado] notification callback failed: ' + JSON.stringify(e) + ', json=' + json);
        }
      });
      console.info('[Legado] toast/notification tsfn callbacks registered via napi');
    } catch (e) {
      console.error('[Legado] registerToastCallback/NotificationCallback failed: ' + JSON.stringify(e));
    }

    // KP8+: 注册 OpenUrl tsfn 回调 (KMP → ArkTS 跨线程 dispatch, 同 Toast 模式 fire-and-forget)。
    // C++ 侧 (legado_napi.cpp RegisterOpenUrlCallback) 创建 napi_threadsafe_function 包装此回调,
    // 并通过 @CName legado_register_open_url_fn 把 dispatch 函数指针
    // 注入 Kotlin OhosNativeBridge.openUrlTsfn。此后 KMP 业务侧 OhosOpenUrlProviderImpl.openUrl →
    // OhosNativeBridge.openUrl 调用时, JSON payload 跨线程 dispatch 到此回调,
    // 由 ArkTS 调 context.startAbility(Want) 打开 URL (KMP 无 ArkTS API 访问能力)。
    // 未注册时 KMP 侧降级 println (见 OhosNativeBridge.openUrl)。
    try {
      legado.registerOpenUrlCallback(handleOpenUrl);
      console.info('[Legado] openUrl tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerOpenUrlCallback failed: ' + JSON.stringify(e));
    }

    // KP8+: 注册 FilePicker tsfn 回调 (KMP → ArkTS 跨线程 dispatch, 同 Image/Crypto/Http 模式)。
    // C++ 侧 (legado_napi.cpp RegisterFilePickerCallback) 创建 napi_threadsafe_function 包装此回调,
    // 并通过 @CName legado_register_file_picker_fn 把 dispatch 函数指针
    // 注入 Kotlin OhosNativeBridge.filePickerTsfn。此后 KMP 业务侧 OhosFilePicker.pickDocuments /
    // pickDocumentContent → OhosNativeBridge.invokeFilePickerSync 调用时, JSON 请求跨线程 dispatch
    // 到此回调, 由 ArkTS FilePickerBridgeHandler 调 @ohos.file.picker.DocumentViewPicker 选文件 /
    // @ohos.file.fs 读内容, 完成后调 legado.filePickerCallback(requestId, resultJson) 回送结果
    // 唤醒 KMP 侧 CompletableDeferred。未注册时 KMP 侧降级返回 null (见 OhosFilePicker.ohos.kt)。
    try {
      legado.registerFilePickerCallback((json: string): void => {
        handleFilePickerRequest(json, (requestId: number, resultJson: string): void => {
          legado.filePickerCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] filePicker tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerFilePickerCallback failed: ' + JSON.stringify(e));
    }

    // Pasteboard tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 FilePicker 模式)
    // KMP OhosPlatformOps.copyToClipboard / readFromClipboard → OhosNativeBridge.invokePasteboardSync
    // 发 read/write 请求, ArkTS 侧 PasteboardBridgeHandler 调 @ohos.pasteboard.getSystemPasteboard
    // 读写纯文本, 完成后调 legado.pasteboardCallback(requestId, resultJson) 回送结果
    // 唤醒 KMP 侧 CompletableDeferred。未注册时 KMP 侧降级 println / 返回 null。
    try {
      legado.registerPasteboardCallback((json: string): void => {
        handlePasteboardRequest(json, (requestId: number, resultJson: string): void => {
          legado.pasteboardCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] pasteboard tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerPasteboardCallback failed: ' + JSON.stringify(e));
    }

    // TextCodec tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Pasteboard 模式)
    // KMP OhosNativeBridge.invokeTextCodecSync 发 decode/encode 请求 (TXT 分章 GB18030/Big5 等编解码),
    // ArkTS 侧 TextCodecBridgeHandler 调 @ohos.util.TextDecoder/TextEncoder 完成真实转换,
    // 完成后调 legado.textCodecCallback(requestId, resultJson) 回送结果
    // 唤醒 KMP 侧 CompletableDeferred。未注册时 KMP 侧降级。
    try {
      legado.registerTextCodecCallback((json: string): void => {
        handleTextCodecRequest(json, (requestId: number, resultJson: string): void => {
          legado.textCodecCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] textCodec tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerTextCodecCallback failed: ' + JSON.stringify(e));
    }

    // KP8+: 注册 Image/Media tsfn 回调 (KMP → ArkTS 跨线程 dispatch)。
    // Image: KMP OhosNativeBridge.invokeImageSync 发图片操作请求 (decode/encode/size/crop/split/stitch),
    //        ArkTS 侧 ImageBridgeHandler 调 @ohos.multimedia.image 执行真实像素操作,
    //        完成后调 legado.imageCallback(requestId, resultJson) 回送结果唤醒 KMP 侧 CompletableDeferred。
    // Media: KMP OhosNativeBridge.sendMediaCommand 发播放器命令 (setSource/play/pause/stop/seekTo/release),
    //        ArkTS 侧 MediaBridgeHandler 调 @ohos.multimedia.media AVPlayer 执行真实播放,
    //        AVPlayer 事件通过 legado.mediaEvent(eventJson) 回送给 KMP 侧 MediaEventListener。
    try {
      // Image: 注册 tsfn 回调, 请求 JSON dispatch 到 handleImageRequest
      legado.registerImageCallback((json: string): void => {
        handleImageRequest(json, (requestId: number, resultJson: string): void => {
          legado.imageCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] image tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerImageCallback failed: ' + JSON.stringify(e));
    }

    try {
      // Media: 先注入事件回送函数 (AVPlayer 事件 → legado.mediaEvent → KMP)
      initMediaBridge((eventJson: string): void => {
        legado.mediaEvent(eventJson);
      });
      // 再注册 tsfn 回调, 命令 JSON dispatch 到 handleMediaCommand
      legado.registerMediaCallback((json: string): void => {
        handleMediaCommand(json);
      });
      console.info('[Legado] media tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerMediaCallback failed: ' + JSON.stringify(e));
    }

    // TTS tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch)
    // KMP OhosNativeBridge.sendTtsCommand 发 TTS 命令 (createEngine/speak/pause/resume/stop/shutdown),
    // ArkTS 侧 TtsBridgeHandler 调 @ohos.textToSpeech 执行真实朗读,
    // TTS 事件通过 legado.ttsEvent(eventJson) 回送给 KMP 侧 TtsEventListener
    try {
      initTtsBridge((eventJson: string): void => {
        legado.ttsEvent(eventJson);
      });
      legado.registerTtsCallback((json: string): void => {
        handleTtsCommand(json);
      });
      console.info('[Legado] tts tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerTtsCallback failed: ' + JSON.stringify(e));
    }

    // Crypto tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Image 模式)
    // KMP OhosNativeBridge.invokeCryptoSync 发 crypto 操作请求 (encrypt/decrypt/sign/verify),
    // ArkTS 侧 CryptoBridgeHandler 调 @ohos.security.cryptoFramework 执行真实运算,
    // 完成后调 legado.cryptoCallback(requestId, resultJson) 回送结果唤醒 KMP 侧 CompletableDeferred。
    try {
      legado.registerCryptoCallback((json: string): void => {
        handleCryptoRequest(json, (requestId: number, resultJson: string): void => {
          legado.cryptoCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] crypto tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerCryptoCallback failed: ' + JSON.stringify(e));
    }

    // Http tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Crypto 模式)
    // KMP OhosNativeBridge.invokeHttpSync 发 HTTP 请求 (execute/cancel),
    // ArkTS 侧 HttpBridgeHandler 调 @ohos.net.http 执行真实请求,
    // 完成后调 legado.httpCallback(requestId, resultJson) 回送结果唤醒 KMP 侧 CompletableDeferred。
    try {
      legado.registerHttpCallback((json: string): void => {
        handleHttpRequest(json, (requestId: number, resultJson: string): void => {
          legado.httpCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] http tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerHttpCallback failed: ' + JSON.stringify(e));
    }

    // legado:// deep link 冷启动投递 (放 onCreate 最尾: 须在 registerOhosProviders 之后,
    // 否则 LegadoDeepLinkHandler 下游导入 VM 取 AppDbProviders 会未注册)。
    this.handleDeepLink(want);
  }

  /**
   * 热启动 deep link 投递 (应用已在后台, 系统复用实例时走本回调而非 onCreate)。
   *
   * 对应 Android 端 AssociationActivity 的 singleTask onNewIntent 语义。
   */
  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    console.info('[Legado] EntryAbility.onNewWant');
    this.handleDeepLink(want);
  }

  /**
   * 投递 legado:// / yuedu:// 一键导入链接给 KMP 侧 (onCreate 冷启动 / onNewWant 热启动共用)。
   *
   * 链路: want.uri → napi legado.handleDeepLink → @CName legado_handle_deep_link →
   * Kotlin LegadoDeepLinkHandler.handle 解析写 pending (StateFlow) →
   * Compose 侧 DeepLinkImportHost 消费弹勾选导入对话框。
   *
   * 非 legado/yuedu scheme 直接忽略 (普通启动 want.uri 为空)。
   */
  private handleDeepLink(want: Want): void {
    const uri: string = want.uri ?? '';
    if (!uri.startsWith('legado://') && !uri.startsWith('yuedu://')) {
      return;
    }
    try {
      const handled: boolean = legado.handleDeepLink(uri);
      console.info('[Legado] deep link dispatched: handled=' + handled + ', uri=' + uri);
    } catch (e) {
      console.error('[Legado] handleDeepLink failed: ' + JSON.stringify(e) + ', uri=' + uri);
    }
  }

  onConfigurationUpdate(newConfig: Configuration): void {
    console.info('[Legado] EntryAbility.onConfigurationUpdate');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    console.info('[Legado] EntryAbility.onWindowStageCreate');
    // 加载首页 Index, ArkTS 路由表见 resources/base/profile/main_pages.json
    windowStage.loadContent('pages/Index', (err) => {
      if (err && err.code) {
        console.error('[Legado] loadContent failed: ' + JSON.stringify(err));
        return;
      }
      console.info('[Legado] loadContent pages/Index succeed');
    });
  }

  onWindowStageDestroy(): void {
    console.info('[Legado] EntryAbility.onWindowStageDestroy');
  }

  onForeground(): void {
    console.info('[Legado] EntryAbility.onForeground');
  }

  onBackground(): void {
    console.info('[Legado] EntryAbility.onBackground');
  }
}
