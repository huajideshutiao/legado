import UIAbility from '@ohos.app.ability.UIAbility';
import type AbilityConstant from '@ohos.app.ability.AbilityConstant';
import type Want from '@ohos.app.ability.Want';
import type { Configuration } from '@ohos.app.ability.Configuration';
import type window from '@ohos.window';
import display from '@ohos.display';
import notificationManager from '@ohos.notificationManager';
// napi 桥接层加载 (鸿蒙编译产物 liblegado_napi.so)
// 详见 ohosApp/INTEROP.md 与 ohosApp/entry/src/main/cpp/legado_napi.cpp
// KP5: napi 桥接已就绪 (bookshelfList/searchBook/loadChapter/importBookSource 接入真实 KMP 数据)
// import legado from 'liblegado_napi.so' (ArkTS 侧 napi 模块加载)
import legado from 'liblegado_napi.so';
// KP8+ Image/Media 桥接处理器 (ArkTS 侧 PixelMap/AVPlayer 真实实现)
import { handleImageRequest } from '../bridge/ImageBridgeHandler';
import { initMediaBridge, handleMediaCommand } from '../bridge/MediaBridgeHandler';
import { initTtsBridge, handleTtsCommand } from '../bridge/TtsBridgeHandler';
import { handleCryptoRequest } from '../bridge/CryptoBridgeHandler';
import { handleHttpRequest } from '../bridge/HttpBridgeHandler';
import { handleFilePickerRequest } from '../bridge/FilePickerBridgeHandler';
import { handlePasteboardRequest } from '../bridge/PasteboardBridgeHandler';
import { handleNetworkRequest } from '../bridge/NetworkBridgeHandler';
import { handleTextCodecRequest } from '../bridge/TextCodecBridgeHandler';
// 后台 WebView 桥接处理器 (隐藏 Web 组件 + runJavaScript 取源码, 书源 webView 规则)
import { handleWebViewRequest } from '../bridge/WebViewBridgeHandler';
// Markdown 查看器桥接处理器 (composeResources 直读内联的 Web 页面 + marked/hljs 渲染, KMP MarkdownContent)
import { handleMarkdownRequest } from '../bridge/MarkdownBridgeHandler';
// KP8+ System 桥接处理器 (ArkTS 侧 promptAction.showToast + context.startAbility 真实实现)
import { initSystemBridge, handleShowToast, handleOpenUrl } from '../bridge/SystemBridgeHandler';
import { handleShowTextActionMenu } from '../bridge/TextActionBridgeHandler';
import { handleBatteryRequest } from '../bridge/BatteryBridgeHandler';
import { initShareBridge, handleShareRequest } from '../bridge/SystemShareBridgeHandler';
import { handleKeyboardCommand } from '../bridge/KeyboardBridgeHandler';
import { initPermissionBridge, handlePermissionRequest } from '../bridge/PermissionBridgeHandler';

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

/** 窗口策略命令 payload (与 OhosNativeBridge.kt WindowCommand 对齐)。 */
interface WindowCommand {
  action: string;  // 'setFullScreenLayout' | 'setKeepScreenOn' | 'setPreferredOrientation' | 'setSystemBarEnable'
  enabled?: boolean;
  orientation?: number;
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
 * 6. KP8+ 已注册 TTS tsfn 回调, 接入 @kit.CoreSpeechKit textToSpeech 系统语音合成引擎,
 *    使系统 TTS 朗读 (createEngine/speak/stop/shutdown) 在鸿蒙端真实工作
 * 7. KP8+ 已注册 Crypto tsfn 回调, 接入 @ohos.security.cryptoFramework (createAsyCodec/createSign/createVerify),
 *    使书源 JS 非对称加解密 (createAsymmetricCrypto encrypt/decrypt) 和签名/验签 (createSign sign/verify) 在鸿蒙端真实工作
 * 8. KP8+ 已注册 OpenUrl tsfn 回调 + 抽出 SystemBridgeHandler 处理 showToast/openUrl,
 *    使 OhosToastProvider.showToast (promptAction.showToast) 和 OhosOpenUrlProvider.openUrl (context.startAbility)
 *    在鸿蒙端真实工作 (此前均为 println stub)
 *
 * 注意: `liblegado_napi.so` 由 CMakeLists.txt 编译 legado_napi.cpp 生成，
 * 直接链接 CPF `ohosArm64` 产物 `liblegado_shared.so` 与 Compose RenderNode 桥。
 * 根 Gradle staging 和 CMake 均对缺失产物直接失败，不再生成表面可运行的 mock HAP。
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
    // KMP 业务侧调 legado.bookshelfList() 等 napi 函数依赖 AppDbProviders 已注册)。
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

    // KP8+: 注册 TextAction tsfn 回调 (KMP → ArkTS 跨线程 dispatch 文本操作菜单请求)。
    // C++ 侧 (legado_napi.cpp RegisterTextActionCallback) 创建 napi_threadsafe_function 包装此回调,
    // 并通过 @CName legado_register_text_action_fn 把 dispatch 函数指针
    // 注入 Kotlin OhosNativeBridge.textActionTsfn。此后 KMP 长按选字完成 →
    // OhosNativeBridge.showTextActionMenu 调用时, JSON payload 跨线程 dispatch 到此回调,
    // 由 ArkTS TextActionBridgeHandler 更新 Index.ets 叠层浮动菜单。
    try {
      legado.registerTextActionCallback(handleShowTextActionMenu);
      console.info('[Legado] textAction tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerTextActionCallback failed: ' + JSON.stringify(e));
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

    // Network tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Pasteboard 模式)
    // KMP NetworkAvailability.ohos.kt → OhosNativeBridge.invokeNetworkSync 发 query 请求,
    // ArkTS 侧 NetworkBridgeHandler 调 @ohos.net.connection getDefaultNetSync +
    // getConnectionPropertiesSync (需 ohos.permission.GET_NETWORK_INFO, normal+system_grant)
    // 查询网络可用性与是否 WiFi, 完成后调 legado.networkCallback(requestId, resultJson) 回送结果
    // 唤醒 KMP 侧 CompletableDeferred。未注册时 KMP 侧降级 true (不拦截)。
    try {
      legado.registerNetworkCallback((json: string): void => {
        handleNetworkRequest(json, (requestId: number, resultJson: string): void => {
          legado.networkCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] network tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerNetworkCallback failed: ' + JSON.stringify(e));
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
    //        完成后调 legado.imageCallback(requestId, resultJson, body?) 回送结果唤醒 KMP 侧 CompletableDeferred
    //        (混合协议: 控制面 JSON + 数据面裸字节 ArrayBuffer, decode 入参图片字节 / encode 出参 packed 字节)。
    // Media: KMP OhosNativeBridge.sendMediaCommand 发播放器命令 (setSource/play/pause/stop/seekTo/release),
    //        ArkTS 侧 MediaBridgeHandler 调 @ohos.multimedia.media AVPlayer 执行真实播放,
    //        AVPlayer 事件通过 legado.mediaEvent(eventJson) 回送给 KMP 侧 MediaEventListener。
    try {
      // Image: 注册 tsfn 回调, 请求 JSON + 裸字节 dispatch 到 handleImageRequest
      legado.registerImageCallback((json: string, bytes: ArrayBuffer | undefined): void => {
        handleImageRequest(json, bytes, (requestId: number, resultJson: string, body: ArrayBuffer | undefined): void => {
          legado.imageCallback(requestId, resultJson, body);
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
    // ArkTS 侧 TtsBridgeHandler 调 @kit.CoreSpeechKit textToSpeech 执行真实朗读,
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

    // Http tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Crypto 模式, 混合协议)
    // KMP OhosNativeBridge.invokeHttpSync 发 HTTP 请求 (execute/cancel),
    // ArkTS 侧 HttpBridgeHandler 调 @ohos.net.http 执行真实请求,
    // 完成后调 legado.httpCallback(requestId, resultJson, body?) 回送结果唤醒 KMP 侧 CompletableDeferred
    // (混合协议: 控制面 JSON + 数据面响应 body 裸字节 ArrayBuffer; 请求 body 同样裸传)。
    try {
      legado.registerHttpCallback((json: string, body: ArrayBuffer | undefined): void => {
        handleHttpRequest(json, body, (requestId: number, resultJson: string, respBody: ArrayBuffer | undefined): void => {
          legado.httpCallback(requestId, resultJson, respBody);
        });
      });
      console.info('[Legado] http tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerHttpCallback failed: ' + JSON.stringify(e));
    }

    // 后台 WebView tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Http 模式, 混合协议)
    // KMP OhosNativeBridge.invokeWebViewSync(jsonControl, htmlRaw) 发后台抓取请求
    // (书源 webView/webViewGetSource 规则), 控制面 JSON 与数据面裸 html 双参数 dispatch 到
    // ArkTS 侧 WebViewBridgeHandler, 用隐藏 Web 组件 (Index.ets 挂载) 加载页面 + runJavaScript 取源码,
    // 完成后调 legado.webViewCallback(requestId, resultJson, bodyRaw) 回送结果
    // (resultJson 为控制面小字段, bodyRaw 为裸源码/命中 URL) 唤醒 KMP 侧 CompletableDeferred。
    try {
      legado.registerWebViewCallback((json: string, htmlRaw: string): void => {
        handleWebViewRequest(json, htmlRaw, (requestId: number, resultJson: string, bodyRaw: string): void => {
          legado.webViewCallback(requestId, resultJson, bodyRaw);
        });
      });
      console.info('[Legado] webView tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerWebViewCallback failed: ' + JSON.stringify(e));
    }

    // Markdown 查看器 tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Toast 模式 fire-and-forget)
    // KMP MarkdownContent (ArkUIView2 混排的 Web 组件) → OhosNativeBridge.sendMarkdown
    // 发渲染请求 { content, isDark, fontSize }, dispatch 到 ArkTS 侧 MarkdownBridgeHandler,
    // 经 Index.ets 挂载的查看器 Web 组件 runJavaScript 注入 renderMarkdown (marked + hljs) 渲染。
    // 页面内链接点击经 legado.markdownEvent(eventJson) 回送 KMP 走系统浏览器。
    try {
      legado.registerMarkdownCallback((json: string): void => {
        handleMarkdownRequest(json);
      });
      console.info('[Legado] markdown tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerMarkdownCallback failed: ' + JSON.stringify(e));
    }

    // Battery tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 阅读页电池电量查询)
    // KMP OhosReaderPlatformProvider.getBatteryLevel → OhosNativeBridge.invokeBatterySync
    // 发 getLevel 请求, ArkTS 侧 BatteryBridgeHandler 调 @ohos.batteryInfo.batterySOC,
    // 完成后调 legado.batteryCallback(requestId, resultJson) 回送结果唤醒 KMP 侧 CompletableDeferred。
    // 未注册时 KMP 侧 getBatteryLevel 恒返回 -1 (阅读页不显示电量)。
    try {
      legado.registerBatteryCallback((json: string): void => {
        handleBatteryRequest(json, (requestId: number, resultJson: string): void => {
          legado.batteryCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] battery tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerBatteryCallback failed: ' + JSON.stringify(e));
    }

    // Share tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 系统分享面板, 同 Toast 模式 fire-and-forget)
    // KMP OhosPlatformServices.sharing.shareText/shareFile → OhosNativeBridge.shareText/shareFile
    // 经 tsfn dispatch 到此回调, ArkTS 侧 SystemShareBridgeHandler 调 @kit.ShareKit systemShare
    // 弹系统分享面板。未注册时 KMP 侧降级写剪贴板 + toast (见 OhosPlatformServices)。
    try {
      // ShareController.show 需 UIAbilityContext, 注册回调前先注入
      initShareBridge(this.context);
      legado.registerShareCallback(handleShareRequest);
      console.info('[Legado] share tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerShareCallback failed: ' + JSON.stringify(e));
    }

    // Keyboard tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 软键盘显隐/避让, 同 Window 模式 fire-and-forget)
    // KMP OhosPlatformServices.keyboard.hideSoftInput/showSoftInput/setSoftInputPolicy
    // → OhosNativeBridge.hideSoftInput/showSoftInput/setKeyboardAvoidMode 经 tsfn dispatch 到此回调,
    // ArkTS 侧 KeyboardBridgeHandler 调 @ohos.inputMethod / UIContext。
    // 未注册时 KMP 侧降级 println。
    try {
      legado.registerKeyboardCallback(handleKeyboardCommand);
      console.info('[Legado] keyboard tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerKeyboardCallback failed: ' + JSON.stringify(e));
    }

    // Permission tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 权限查询/申请)
    // KMP OhosPlatformServices.permissions.hasPermission/requestPermission
    // → OhosNativeBridge.invokePermissionSync 经 tsfn dispatch 到此回调,
    // ArkTS 侧 PermissionBridgeHandler 调 @ohos.abilityAccessCtrl.checkAccessTokenSync /
    // requestPermissionsFromUser, 完成后调 legado.permissionCallback 回送结果。
    // 未注册时 KMP 侧按"无权限"降级。
    try {
      initPermissionBridge(this.context);
      legado.registerPermissionCallback((json: string): void => {
        handlePermissionRequest(json, (requestId: number, resultJson: string): void => {
          legado.permissionCallback(requestId, resultJson);
        });
      });
      console.info('[Legado] permission tsfn callback registered via napi');
    } catch (e) {
      console.error('[Legado] registerPermissionCallback failed: ' + JSON.stringify(e));
    }

    // 外部启动请求冷启动投递 (放 onCreate 最尾: 须在 registerOhosProviders 之后,
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
   * 投递外部启动请求给 KMP 侧 (onCreate 冷启动 / onNewWant 热启动共用)。
   *
   * # 分流 (与 Kotlin LegadoNativeExports.handleDeepLink / handleLaunchRequest 分工对应)
   * - legado:// / yuedu:// 一键导入链接 → legado.handleDeepLink
   *   (@CName legado_handle_deep_link → LegadoDeepLinkHandler 弹勾选导入对话框)
   * - 其余非空 URI (文件关联 file:// / 其他 scheme deep link / 通知 route:xxx) →
   *   legado.handleLaunchRequest (@CName legado_handle_launch_request → OhosLaunchRequests
   *   → LaunchRequestBus → Compose LegadoApp 路由导航)。普通启动 want.uri 为空, 跳过。
   */
  private handleDeepLink(want: Want): void {
    const uri: string = want.uri ?? '';
    if (uri.length === 0) {
      return;
    }
    if (uri.startsWith('legado://') || uri.startsWith('yuedu://')) {
      try {
        const handled: boolean = legado.handleDeepLink(uri);
        console.info('[Legado] deep link dispatched: handled=' + handled + ', uri=' + uri);
      } catch (e) {
        console.error('[Legado] handleDeepLink failed: ' + JSON.stringify(e) + ', uri=' + uri);
      }
      return;
    }
    // 文件关联 / 其他 scheme deep link / 通知 route: 投递给 LaunchRequestBus (Compose 侧消费路由)
    try {
      const handled: boolean = legado.handleLaunchRequest(uri);
      console.info('[Legado] launch request dispatched: handled=' + handled + ', uri=' + uri);
    } catch (e) {
      console.error('[Legado] handleLaunchRequest failed: ' + JSON.stringify(e) + ', uri=' + uri);
    }
  }

  onConfigurationUpdate(newConfig: Configuration): void {
    console.info('[Legado] EntryAbility.onConfigurationUpdate');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    console.info('[Legado] EntryAbility.onWindowStageCreate');

    // 注入显示物理像素尺寸 (供 shared ScreenInfoProviders / sharedUiMain AppDialogSizes
    // 计算对话框尺寸; 未注册时 AppDialogSizes 兜底 get() 直接 error 导致所有对话框崩溃,
    // 与 iOS registerIosScreenInfoProvider 对齐)。必须在 loadContent 之前调用:
    // 首帧组合后任何对话框尺寸计算都可能触发。
    // display.getDefaultDisplaySync() 返回 vp 尺寸 + densityPixels, 物理像素 = vp × density
    // (与 iOS UIScreen.nativeBounds / Android displayMetrics 的物理像素语义一致)。
    try {
      const displayInfo = display.getDefaultDisplaySync();
      const widthPx = Math.round(displayInfo.width * displayInfo.densityPixels);
      const heightPx = Math.round(displayInfo.height * displayInfo.densityPixels);
      legado.registerScreenSize(widthPx, heightPx);
      console.info('[Legado] screen size injected: ' + widthPx + 'x' + heightPx);
    } catch (e) {
      console.error('[Legado] registerScreenSize failed: ' + JSON.stringify(e));
    }

    // 加载首页 Index, ArkTS 路由表见 resources/base/profile/main_pages.json
    windowStage.loadContent('pages/Index', (err) => {
      if (err && err.code) {
        console.error('[Legado] loadContent failed: ' + JSON.stringify(err));
        return;
      }
      console.info('[Legado] loadContent pages/Index succeed');

      // 窗口策略桥 (KMP → ArkTS @ohos.window): 注册 tsfn 回调。
      // C++ 侧 (legado_napi.cpp RegisterWindowCallback) 创建 napi_threadsafe_function 包装此回调,
      // 经 @CName legado_register_window_fn 注入 dispatch 指针到 Kotlin OhosNativeBridge.windowTsfn。
      // 此后 KMP WindowController.setFullscreen/setKeepScreenOn/setOrientation/setSystemBars
      // 的 JSON 命令跨线程 dispatch 到此回调, 由 ArkTS 在窗口上执行 @ohos.window API。
      // 未注册时 KMP 侧降级 println (见 OhosNativeBridge.sendWindowCommand)。
      // 注: 单栏策略 (HiddenStatusBar/HiddenNavigationBar) 已由 KMP 侧映射为整体显隐
      // (见 WindowPolicy.kt), 故此处只按 enabled 传"全显/全隐"两种 names 组合。
      try {
        legado.registerWindowCallback((json: string): void => {
          try {
            const cmd = JSON.parse(json) as WindowCommand;
            windowStage.getMainWindow().then((win: window.Window) => {
              switch (cmd.action) {
                case 'setFullScreenLayout':
                  win.setWindowLayoutFullScreen(cmd.enabled ?? false);
                  break;
                case 'setKeepScreenOn':
                  win.setWindowKeepScreenOn(cmd.enabled ?? false);
                  break;
                case 'setPreferredOrientation':
                  win.setPreferredOrientation(cmd.orientation ?? 0);
                  break;
                case 'setSystemBarEnable': {
                  // names 为要显示的系统栏; 关闭时传空数组 (状态栏+导航栏全隐)
                  const bars: Array<'status' | 'navigation'> =
                    (cmd.enabled ?? true) ? ['status', 'navigation'] : [];
                  win.setWindowSystemBarEnable(bars);
                  break;
                }
                case 'exitApplication':
                  // 退出应用: 仅 UIAbility 自身 context 可调 terminateSelf (系统弹回收确认?
                  // 无, 直接终止)。从 window tsfn 通道分发 (复用桥避免新增独立通道)。
                  try {
                    this.context.terminateSelf();
                    console.info('[Legado] window callback: exitApplication (terminateSelf)');
                  } catch (e) {
                    console.error('[Legado] window callback terminateSelf failed: ' + JSON.stringify(e));
                  }
                  break;
                default:
                  console.warn('[Legado] window callback unknown action: ' + cmd.action);
              }
            }).catch((e: Error) => {
              console.error('[Legado] window callback getMainWindow failed: ' + JSON.stringify(e));
            });
          } catch (e) {
            console.error('[Legado] window callback failed: ' + JSON.stringify(e) + ', json=' + json);
          }
        });
        console.info('[Legado] window tsfn callback registered via napi');
      } catch (e) {
        console.error('[Legado] registerWindowCallback failed: ' + JSON.stringify(e));
      }
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
