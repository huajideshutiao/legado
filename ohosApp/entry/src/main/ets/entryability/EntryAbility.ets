import UIAbility from '@ohos.app.ability.UIAbility';
import type AbilityConstant from '@ohos.app.ability.AbilityConstant';
import type Want from '@ohos.app.ability.Want';
import type { Configuration } from '@ohos.app.ability.Configuration';
import ConfigurationConstant from '@ohos.app.ability.ConfigurationConstant';
import { bundleManager } from '@kit.AbilityKit';
import type window from '@ohos.window';
import display from '@ohos.display';
import notificationManager from '@ohos.notificationManager';
// 长时任务: 申请/释放统一走仲裁器 (音频绝对优先), 长时任务取消监听是 API 15 接口
import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import deviceInfo from '@ohos.deviceInfo';
import type { WantAgent } from '@ohos.app.ability.wantAgent';
import {
  acquireWorkTask,
  releaseWorkTask,
  publishTransferProgress,
  notifySystemCancelled,
  notifyAbilityDestroyed
} from '../bridge/ContinuousTaskArbiter';
// napi 桥接层加载 (鸿蒙编译产物 liblegado_napi.so)
// 详见 ohosApp/INTEROP.md 与 ohosApp/entry/src/main/cpp/legado_napi.cpp
// KP5: napi 桥接已就绪 (bookshelfList/searchBook/loadChapter/importBookSource 接入真实 KMP 数据)
// import legado from 'liblegado_napi.so' (ArkTS 侧 napi 模块加载)
import legado from 'liblegado_napi.so';
// KP8+ Image/Media 桥接处理器 (ArkTS 侧 PixelMap/AVPlayer 真实实现)
import { handleImageRequest } from '../bridge/ImageBridgeHandler';
import { initMediaBridge, handleMediaCommand, buildLaunchWantAgent } from '../bridge/MediaBridgeHandler';
import { initTtsBridge, handleTtsCommand } from '../bridge/TtsBridgeHandler';
import { handleCryptoRequest } from '../bridge/CryptoBridgeHandler';
import { handleHttpRequest } from '../bridge/HttpBridgeHandler';
import { initFilePickerBridge, handleFilePickerRequest } from '../bridge/FilePickerBridgeHandler';
import { initPasteboardBridge, handlePasteboardRequest } from '../bridge/PasteboardBridgeHandler';
import { handleNetworkRequest } from '../bridge/NetworkBridgeHandler';
import { handleTextCodecRequest } from '../bridge/TextCodecBridgeHandler';
// 后台 WebView 桥接处理器 (隐藏 Web 组件 + runJavaScript 取源码, 书源 webView 规则)
import { handleWebViewRequest, handleForegroundWebRequest } from '../bridge/WebViewBridgeHandler';
// Markdown 查看器桥接处理器 (composeResources 直读内联的 Web 页面 + marked/hljs 渲染, KMP MarkdownContent)
import { handleMarkdownRequest } from '../bridge/MarkdownBridgeHandler';
// KP8+ System 桥接处理器 (ArkTS 侧 promptAction.showToast + context.startAbility 真实实现)
import { initSystemBridge, handleShowToast, handleOpenUrl } from '../bridge/SystemBridgeHandler';
import { handleBatteryRequest } from '../bridge/BatteryBridgeHandler';
import { initPlatformEvent, emitAppLifecycle, emitColorMode, emitDialogResult, emitPlatformEvent, NotificationActionEvent } from '../bridge/PlatformEventBridge';
import { initShareBridge, handleShareRequest } from '../bridge/SystemShareBridgeHandler';
import { handleDialogRequest } from '../bridge/SysDialogBridgeHandler';
import { installSystemEventSources } from '../bridge/SystemEventSources';
import { systemShare } from '@kit.ShareKit';
import { handleKeyboardCommand } from '../bridge/KeyboardBridgeHandler';
import { initPermissionBridge, handlePermissionRequest } from '../bridge/PermissionBridgeHandler';

// Notification tsfn 跨语言 payload (与 OhosNativeBridge.kt NotificationPayload 对齐)
// 由 KMP 侧 KS_JSON 序列化为 JSON 字符串, 经 C++ tsfn dispatch 到 ArkTS 回调, 此处 JSON.parse 还原。
// 注: Toast payload 已移至 SystemBridgeHandler.ets (与 handleShowToast 一并管理)

/** 通知上的一个按钮 (与 Kotlin OhosNativeBridge.NotificationButton 对齐)。 */
interface NotificationButton {
  text: string;    // 按钮文案 (原版 addAction 的 title)
  action: string;  // 动作标识 (原版 addAction 的 pendingIntent action, 如 IntentAction.stop)
}

interface NotificationPayload {
  action: string;  // 'SHOW' | 'CANCEL'
  id: number;
  title?: string;
  content?: string;
  progress?: number;
  max?: number;
  // 进行中分支 (对照原版 setOngoing(true)): isOngoing = 进行中通知; tapDismissed=false = 点正文/按钮
  // 都不把通知清掉; 缺省时两个字段都不下发 (tapDismissed 默认 true ↔ 原版 setAutoCancel(true))。
  ongoing?: boolean;
  // 只提醒一次 (对照原版 setOnlyAlertOnce(true)); 缺省不下发, 按系统通知模式提醒。
  alertOnce?: boolean;
  // 通知按钮 (对照原版 addAction); 缺省/空数组 = 无按钮
  buttons?: NotificationButton[];
  // 点击通知本体的动作标识 (原版 contentIntent 直达 Service action 的场景, 如 WebService 的
  // "copyHostAddress"); 缺省时点击仍走下方 id→route 表
  clickAction?: string;
  // 长时任务类型 (module.json5 backgroundModes 的配置项, 如 'multiDeviceConnection' / 'dataTransfer'):
  // 非空时发布通知后交仲裁器申请同名长时任务 (保活), 取消该通知 (stopContinuousTask) 时一并取消;
  // 缺省 = 本业务当前不要长时任务 (如缓存的"缓存完成"那一条), 已申请过的会被释放
  continuousTask?: string;
  // CANCEL 专用: 取消该通知时是否一并取消长时任务 (对照 app 端 WebService 的 stopSelf 同时停前台服务)
  stopContinuousTask?: boolean;
}

/**
 * 通知 id → 点击后的目标路由 (逐字对齐 app 端各通知 contentIntent 里的 route extra)。
 *
 * 本 tsfn 通道只能传 id 与文本, 无法携带路由, 故用 id 作细分判据 (与 Kotlin
 * NotificationId 常量、以及 iOS IosNotificationService.routeOf 同一张表)。id 或路由串
 * 改了必须同步改; 无对应页面的 id 返回 undefined (只拉起应用)。
 */
const NOTIFICATION_ROUTES: Map<number, string> = new Map<number, string>([
  [101, 'route:last_read'],          // NotificationId.ReadAloudService
  [102, 'route:audio_play'],         // NotificationId.AudioPlayService
  [104, 'route:bookshelf_manage'],   // NotificationId.ExportBookService
  [107, 'route:book_source_manage'], // NotificationId.CheckSourceService
]);

function routeOfNotification(id: number): string | undefined {
  return NOTIFICATION_ROUTES.get(id);
}

// ===== 通知按钮回传参数约定 (EntryAbility 写进按钮 want、被拉起时从 want.parameters 读回) =====
// 与 shortcuts_config.json 的 wants.parameters 同一形制: 系统把 wants.parameters 放进被拉起
// Ability 的 `want.parameters` (不出现在 want.uri), 值一律取字符串。按钮由此不必新增 napi 导出。
const NOTIFICATION_ID_PARAM: string = 'notificationId';
const NOTIFICATION_ACTION_PARAM: string = 'action';

/** 窗口策略命令 payload (与 OhosNativeBridge.kt WindowCommand 对齐)。 */
interface WindowCommand {
  action: string;  // 'setFullScreenLayout' | 'setKeepScreenOn' | 'setPreferredOrientation' | 'setSystemBarEnable' | 'exitApplication' | 'showDialog'
  enabled?: boolean;
  orientation?: number;
  /** 'showDialog' 专用: 系统对话框桥请求 JSON (OhosNativeBridge.DialogBridgeRequest)。 */
  payload?: string;
}

// ===== 外部启动请求投递串约定 (与 ui/src/ohosMain OhosLaunchRequests 常量逐字对齐) =====
// 只传一个字符串的 napi 导出 (legado_handle_launch_request) 无法携带整个 Want, 故把 Want.type
// 拼成 `mime:<type>|<uri>` 前缀形式 (同 `route:` 一样的既有约定), 由 Kotlin 侧解回。
// 改这三处字面量必须同步改 OhosLaunchRequests, 否则外部投递会静默变成 DeepLink。
const LAUNCH_ROUTE_PREFIX: string = 'route:';
const LAUNCH_MIME_PREFIX: string = 'mime:';
const LAUNCH_MIME_SEPARATOR: string = '|';

// ===== 系统分享接收串约定 (与 ui/src/ohosMain OhosLaunchRequests 的 TEXT_PREFIX 逐字对齐) =====
// 分享进来的文本可能含任意字符 (也可能本身就是带 scheme 的地址/legado 深链), 与普通 uri 无法区分,
// 故按已有 `route:` / `mime:` 同一路数用 `text:` 前缀显式标记, 由 Kotlin 侧解回 (含链接 token 的
// 文本回主界面, 其余进搜索页 —— 分流在 shared 侧统一处置, 对照原版 AssociationActivity.dispose)。
const SHARE_TEXT_PREFIX: string = 'text:';

/** 系统分享投递的 action (与 module.json5 share skill 声明的 actions 字面量一致)。 */
const SHARE_ACTION: string = 'ohos.want.action.sendData';

/**
 * 按 UTD 判定为"文本类"的记录 (字面量取自 module.json5 share skill 声明的同一组 UTD,
 * 即本机 SDK @ohos.data.uniformTypeDescriptor.d.ts 的 TEXT/PLAIN_TEXT/HTML/HYPERLINK)。
 */
const SHARE_TEXT_UTDS: string[] = ['general.text', 'general.plain-text', 'general.html', 'general.hyperlink'];

/**
 * 分享记录 → 外部启动请求串 (交 legado.handleLaunchRequest → OhosLaunchRequests.parse)。
 *
 * **只处理文本类 UTD** —— 与 module.json5 里 share skill 的声明面 (只申报 general.text /
 * plain-text / html / hyperlink) 以及原版 Android 的注册范围 (AndroidManifest 的
 * ProcessTextActivity alias 只挂 `SEND` + `text/plain`, 文件分享走的是 `ACTION_VIEW` 文件关联)
 * 严格对齐: 非文本类型 (图片/视频/压缩包…) 系统根本不会投给本 Ability 的 share skill,
 * 写一条"非文本取 uri → 导入"的分支等于给声明不到的路径留代码 (旧代码就是这样)。
 *
 * 文本类记录里 content 为空的情况退回 uri: 那是"文本类 UTD 但载的是一个文件" (如从文件管理器
 * 分享 .txt), 对应 Android 同一条注册下的 `ACTION_SEND` + `EXTRA_STREAM` → `ImportFile` 分支。
 *
 * @returns 空串 = 该记录不可用 (调用方跳过)
 */
function toLaunchRequestFromSharedRecord(record: systemShare.SharedRecord): string {
  if (!SHARE_TEXT_UTDS.includes(record.utd)) {
    return '';
  }
  const content: string = record.content ?? '';
  if (content.length > 0) {
    return SHARE_TEXT_PREFIX + content;
  }
  return record.uri ?? '';
}

/**
 * 主屏快捷方式的裸路由名 (`shortcuts_config.json` 的 `wants.parameters.route`)。
 *
 * 必须单独取: 快捷方式系统把 `wants.parameters` 放进 `want.parameters` (不是 `want.uri`),
 * 而本 Ability 其余投递口都只看 `want.uri` —— 不读这里就等于"快捷方式怎么配都是死的"。
 * 值约定为**裸路由名** (与 Android 快捷方式 intent 的 `route` extra、iOS 的
 * `UIApplicationShortcutItemUserInfo["route"]` 同一形制), 拼上既有的 `route:` 前缀后投给
 * legado.handleLaunchRequest → OhosLaunchRequests.parse → navigateToRoute。
 *
 * @returns 空串 = 本次启动不是带 route 的快捷方式 (或值不是字符串)
 */
function routeFromWantParameters(want: Want): string {
  const params: Record<string, Object> | undefined = want.parameters;
  if (params === undefined) {
    return '';
  }
  const value: Object | undefined = params['route'];
  return typeof value === 'string' ? value : '';
}

/**
 * 通知按钮的 want 参数 (写进按钮的 wantAgent, 见 buildLaunchWantAgent 的 parameters 入参)。
 *
 * 两个值都取字符串 (与 shortcuts_config.json 的 wants.parameters 同一约束: 值只允许 string),
 * 由本文件自己写、自己读 —— 点击经既有 `want → handleDeepLink` 入口回送 Kotlin, 不必新增 napi 导出。
 *
 * @param notificationId 通知 id (与 Kotlin NotificationId 常量同一张 id 表)
 * @param action 按钮动作标识 (来自 Kotlin NotificationButton.action, 原版 IntentAction 字面量)
 */
function notificationActionWantParameters(notificationId: number, action: string): Record<string, Object> {
  const parameters: Record<string, Object> = {};
  parameters[NOTIFICATION_ID_PARAM] = String(notificationId);
  parameters[NOTIFICATION_ACTION_PARAM] = action;
  return parameters;
}

/**
 * 投递通知按钮点击 (从按钮 want 的 parameters 解出, 经统一平台事件通道回送 Kotlin)。
 *
 * 两个参数 (notificationId + action) 齐备才算通知按钮/点击动作, 否则返回 false 由调用方继续走
 * 其它启动分支 (与 [routeFromWantParameters] 同款“解不出就不接管”的处理)。
 *
 * @returns true = 本次 want 是通知按钮/点击动作 (已投递)
 */
function dispatchNotificationAction(want: Want): boolean {
  const params: Record<string, Object> | undefined = want.parameters;
  if (params === undefined) {
    return false;
  }
  const id: Object | undefined = params[NOTIFICATION_ID_PARAM];
  const action: Object | undefined = params[NOTIFICATION_ACTION_PARAM];
  if (typeof id !== 'string' || typeof action !== 'string' || action.length === 0) {
    return false;
  }
  const notificationId: number = Number.parseInt(id, 10);
  if (Number.isNaN(notificationId)) {
    return false;
  }
  const payload: NotificationActionEvent = {
    type: 'notificationAction',
    notificationId: notificationId,
    action: action
  };
  emitPlatformEvent(payload);
  console.info('[Legado] notification action dispatched: id=' + notificationId + ', action=' + action);
  return true;
}

// ===== 长时任务 (后台保活) =====
// 鸿蒙无 Android 前台服务: 要把进程从后台挂起里拿出来只能申请长时任务, 申请成功后系统会自己
// 在通知栏发一条与本任务绑定的通知 (长时任务通知的内容不允许修改 —— Background Tasks Kit 接入规范:
// "不得修改长时任务通知内容"; 数据传输类型的进度更新是唯一例外), 故业务通知 (Kotlin 侧发的那条)
// 与它是两条。申请/释放全部交给 [ContinuousTaskArbiter]: 音频/朗读绝对优先, 后台作业在音频播放
// 期间不申请、音频占据时主动让位 (只影响保活, 业务与业务通知都不动)。

/**
 * 注册长时任务被取消的监听 (用户删除长时任务通知、系统管控取消等)。
 *
 * 只做两件事: 把"被取消"告诉仲裁器 (清持有态 + 按接入规范抑制立即重申请 + 回送 KMP 取消事件),
 * 以及记日志。该接口 @since 15, 本模块 compatibleSdkVersion 为 API 12, 低版本设备上直接调用会报错。
 */
function registerContinuousTaskCancelListener(): void {
  if (deviceInfo.sdkApiVersion < 15) {
    console.info('[Legado] continuousTaskCancel listener skipped, api=' + deviceInfo.sdkApiVersion);
    return;
  }
  try {
    backgroundTaskManager.on('continuousTaskCancel',
      (info: backgroundTaskManager.ContinuousTaskCancelInfo): void => {
        console.info('[Legado] continuous task cancelled, reason=' + info.reason);
        notifySystemCancelled(info.reason);
      });
    console.info('[Legado] continuousTaskCancel listener installed');
  } catch (e) {
    console.error('[Legado] register continuousTaskCancel failed: ' + JSON.stringify(e));
  }
}

/**
 * 把 Want.type 拼成 `mime:<type>|<uri>` 投递串 (不带或形态不对时原样返回 uri)。
 *
 * 只对形如 `<top>/<sub>` 的 MIME 生效; 已带 `route:` / `mime:` 前缀的串不重复拼
 * (拼了就破了 Kotlin OhosLaunchRequests 的前缀约定)。
 */
function withMimeTypeHint(uri: string, type: string): string {
  const mime: string = type.trim().toLowerCase();
  if (mime.length === 0 || !mime.includes('/') || mime.includes('|')) {
    return uri;
  }
  if (uri.startsWith(LAUNCH_ROUTE_PREFIX) || uri.startsWith(LAUNCH_MIME_PREFIX)) {
    return uri;
  }
  return LAUNCH_MIME_PREFIX + mime + LAUNCH_MIME_SEPARATOR + uri;
}

/**
 * 安全执行一段初始化/回调注册代码: 成功打 info 日志, 抛异常打 error 日志后继续
 * (onCreate 中 17+ 个同构 try/catch 注册块的收敛; 各桥注册互不阻断的语义保持不变,
 * 日志文案逐字保留, 由调用方传入)。
 *
 * @param register 注册动作 (可含注册前的 init 调用, 与原同块 try 内语句一致)
 * @param successLog 成功日志全文 (原各块 console.info 的字符串)
 * @param failLogPrefix 失败日志前缀 (拼 JSON.stringify(e), 即原 catch 的 error 文案)
 */
function registerSafely(register: () => void, successLog: string, failLogPrefix: string): void {
  try {
    register();
    console.info(successLog);
  } catch (e) {
    console.error(failLogPrefix + JSON.stringify(e));
  }
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

    // 注入应用版本名 (供关于页显示与复制)。版本号写在 app.json5, 且由 hvigorfile.ts 在构建阶段
    // 覆写为 "3.yy.MMddHH", 只有运行期读应用包信息才能拿到真实值 (静态文件里是占位值)。
    // getBundleInfoForSelfSync 取自身 BundleInfo, versionName 即 app.json5 的 versionName 字段。
    try {
      const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
      legado.registerAppVersion(bundleInfo.versionName);
      console.info('[Legado] app version injected: ' + bundleInfo.versionName);
    } catch (e) {
      console.error('[Legado] registerAppVersion failed: ' + JSON.stringify(e));
    }

    // KP8+: 初始化 System 桥接处理器 (注入 UIAbilityContext 供 handleOpenUrl 调 startAbility)。
    // 必须在 registerToastCallback / registerOpenUrlCallback 之前调用, 使 tsfn 回调触发时
    // SystemBridgeHandler.abilityContext 已就绪 (避免 KMP 极早调用 openUrl 时 context 未注入)。
    registerSafely((): void => {
      initSystemBridge(this.context);
    }, '[Legado] system bridge initialized (UIAbilityContext injected)', '[Legado] initSystemBridge failed: ');

    // KP5: 注册 shared 模块所有 KMP provider。
    // 必须在任何 commonMain 业务代码调用前完成 (webBook 编排链依赖 AppConfigAccessor;
    // KMP 业务侧调 legado.bookshelfList() 等 napi 函数依赖 AppDbProviders 已注册)。
    // napi 桥接层 (legado_napi.cpp) 内部已对 dlsym 失败做 null 兜底, 此处调用始终安全。
    registerSafely((): void => {
      legado.registerOhosProviders();
    }, '[Legado] KMP providers registered via napi', '[Legado] registerOhosProviders failed: ');

    // 统一平台事件回送通道 (HTTP 下载进度 + 应用生命周期: ArkTS → Kotlin 单向推送, 单通道复用)。
    // Kotlin 侧 OhosPlatformEventChannel (OhosNativeBridge.onPlatformEvent) 按 type 分发到
    // OhosDownloadProgressEvents / OhosAppLifecycle; 事件方向恒为 ArkTS → Kotlin (同 mediaEvent
    // 模式, 无 tsfn)。须在 registerHttpCallback 之前注入: HttpBridgeHandler 的 requestInStream
    // 进度回调与 EntryAbility 生命周期回调都走此通道, 迟到只影响早期事件丢失 (warn 降级)。
    registerSafely((): void => {
      initPlatformEvent((json: string): void => {
        legado.platformEvent(json);
      });
    }, '[Legado] platform event callback registered via napi', '[Legado] initPlatformEvent failed: ');

    // 系统深浅色初值: Kotlin/Native 侧没有 configuration API, 「主题模式=跟随系统」这一档
    // 只能由 ArkTS 供给 (Kotlin 侧落到 NativeSystemTheme, 见 OhosPlatformEventChannel)。
    // 须在 initPlatformEvent 之后: 通道未注入时事件被 warn 降级丢弃。
    registerSafely((): void => {
      const isDark: boolean =
        this.context.config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
      emitColorMode(isDark);
    }, '[Legado] system colorMode injected', '[Legado] emitColorMode failed: ');

    // 系统事件源: 来电状态 (telephony.observer) + 网络可用 (net.connection)
    // 两者都经 PlatformEventBridge 同一通道回送 Kotlin (OhosPlatformEventChannel 按 type 分发),
    // 与 mediaEvent 同方向。须在 initPlatformEvent 之后 (通道未注入时事件被 warn 降级丢弃)。
    registerSafely((): void => {
      installSystemEventSources();
    }, '[Legado] system event sources installed (callState/netAvailable)', '[Legado] installSystemEventSources failed: ');

    // 长时任务被取消 (用户删除长时任务通知/系统管控): 回送 KMP 停服务并撤业务通知。
    // 须在 initPlatformEvent 之后 (事件通道未注入时事件被丢弃); 低版本设备上内部自行跳过。
    registerSafely((): void => {
      registerContinuousTaskCancelListener();
    }, '[Legado] continuous task cancel listener installed', '[Legado] registerContinuousTaskCancelListener failed: ');

    // KP7+: 注册 Toast/Notification tsfn 回调 (KMP → ArkTS 跨线程 dispatch)。
    // C++ 侧 (legado_napi.cpp RegisterToastCallback) 创建 napi_threadsafe_function 包装此回调,
    // 并通过 @CName legado_register_toast_fn/legado_register_notification_fn 把 dispatch 函数指针
    // 注入 Kotlin OhosNativeBridge.toastTsfn/notificationTsfn。此后 KMP 业务侧
    // OhosNativeBridge.showToast/showNotification 调用时, JSON payload 跨线程 dispatch 到此回调,
    // 由 ArkTS 调 promptAction.showToast / notificationManager.publish (KMP 无 ArkTS API 访问能力)。
    // 未注册时 KMP 侧降级 println (见 OhosNativeBridge.showToast/showNotification)。
    // KP8+: toast 回调抽至 SystemBridgeHandler.handleShowToast (与 handleOpenUrl 一并管理 system 桥接)。
    registerSafely((): void => {
      legado.registerToastCallback(handleShowToast);
      legado.registerNotificationCallback(async (json: string): Promise<void> => {
        try {
          const payload = JSON.parse(json) as NotificationPayload;
          if (payload.action === 'CANCEL') {
            // 通知不存在时 cancel 会以 1600007 拒绝, 不接住就是无人处理的 Promise 拒绝
            notificationManager.cancel(payload.id).catch((err: Error): void => {
              console.info('[Legado] notification cancel ignored: ' + JSON.stringify(err) + ', id=' + payload.id);
            });
            // 该通知背靠长时任务时一并释放 (对照 app 端 WebService 的 stopSelf 同时停前台服务)
            if (payload.stopContinuousTask === true) {
              await releaseWorkTask(this.context, payload.id.toString());
            }
            return;
          }
          // SHOW: 发基本文本通知; progress/max 齐备时附加 downloadTemplate 系统进度条。
          // 普通通知内容 (BasicContent/LongTextContent) 无 progress 字段 (SDK notificationContent.d.ts
          // 的 NotificationProgress 仅属 systemLiveView, 第三方应用不可直接创建), 官方进度条方案 =
          // NotificationRequest.template (downloadTemplate, since 8, data.progressValue 为 0-100 百分比);
          // 无进度语义 (max<=0/缺省) 时降级纯文本, KMP 侧拼入 content 的进度文本 (NotificationProgress.ohos.kt)
          // 在无进度条场景下是唯一进度呈现, 故保留。
          const request: notificationManager.NotificationRequest = {
            id: payload.id,
            content: {
              notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
              normal: { title: payload.title ?? '', text: payload.content ?? '' }
            }
          };
          const progress = payload.progress;
          const max = payload.max;
          if (progress != null && max != null && max > 0) {
            request.template = {
              name: 'downloadTemplate',
              data: {
                title: payload.title ?? '',
                fileName: payload.content ?? '',
                progressValue: Math.max(0, Math.min(100, Math.round(progress / max * 100)))
              }
            };
          }
          // 进行中分支 + 只提醒一次 (对照原版 setOngoing(true) / setOnlyAlertOnce(true)):
          // 两个字段独立 (WebService 通知只调了前者), 故不从一个布尔里推导
          if (payload.ongoing === true) {
            request.isOngoing = true;
            request.tapDismissed = false;
          }
          if (payload.alertOnce === true) {
            request.isAlertOnce = true;
          }
          // 通知按钮 (对照原版 addAction): 每项一个 wantAgent, 携带 notificationId + action
          // (见 dispatchNotificationAction); 构建失败则本次不带按钮, 通知照发。
          const buttons: NotificationButton[] | undefined = payload.buttons;
          if (buttons !== undefined && buttons.length > 0) {
            try {
              const actionButtons: notificationManager.NotificationActionButton[] = [];
              for (const button of buttons) {
                actionButtons.push({
                  title: button.text,
                  wantAgent: await buildLaunchWantAgent(
                    this.context, undefined, notificationActionWantParameters(payload.id, button.action)
                  )
                });
              }
              request.actionButtons = actionButtons;
            } catch (e) {
              console.error('[Legado] notification actionButtons failed: ' + JSON.stringify(e));
            }
          }
          // 点击通知拉起应用: 带 clickAction 的通知 (原版 contentIntent 直达 Service action)
          // 把点击目标构建成携带 {notificationId, action} 的 Want (与按钮同一形制, 见
          // dispatchNotificationAction); 其余仍按 [NOTIFICATION_ROUTES] 的 route 串跳页。
          // 未挂 wantAgent 时由系统默认行为拉起。
          const clickAction: string | undefined = payload.clickAction;
          let taskWantAgent: WantAgent | undefined = undefined;
          try {
            taskWantAgent = clickAction !== undefined && clickAction.length > 0
              ? await buildLaunchWantAgent(
                this.context, undefined, notificationActionWantParameters(payload.id, clickAction)
              )
              : await buildLaunchWantAgent(this.context, routeOfNotification(payload.id));
            request.wantAgent = taskWantAgent;
          } catch (e) {
            console.error('[Legado] notification wantAgent failed: ' + JSON.stringify(e));
          }
          // 发布失败 (通知未授权 1600004 / 不允许发送 1600020 等) 必须可见:
          // publish 返回 Promise, 不用 catch 接住的话失败现场什么都不剩
          notificationManager.publish(request).catch((err: Error): void => {
            console.error('[Legado] notification publish failed: ' + JSON.stringify(err) + ', id=' + payload.id);
          });
          // 长时任务: 交仲裁器申请 —— 音频/朗读占着时这里只记录期望、不申请 (降级为只发业务通知),
          // 音频随后起时仲裁器会把后台作业让出去 (业务与通知都不受影响)。
          // 不带 continuousTask 的这一条 = 本业务当前不要长时任务 (如缓存的"缓存完成"那一条),
          // 释放掉即可 (从未申请过的业务是 no-op)
          const continuousTask: string | undefined = payload.continuousTask;
          if (continuousTask !== undefined && continuousTask.length > 0) {
            const holderWantAgent: WantAgent = taskWantAgent != undefined
              ? taskWantAgent
              : await buildLaunchWantAgent(this.context);
            await acquireWorkTask(
              this.context, payload.id.toString(), continuousTask, holderWantAgent
            );
            // 数据传输类型还需要写系统那条实况窗进度 (长时任务通知内容不可改, 只有 DATA_TRANSFER
            // 的进度是平台要求的例外); 未持有 dataTransfer 任务时内部 no-op
            await publishTransferProgress(
              payload.title ?? '', payload.content ?? '', payload.progress ?? 0, payload.max ?? 0
            );
          } else {
            await releaseWorkTask(this.context, payload.id.toString());
          }
        } catch (e) {
          console.error('[Legado] notification callback failed: ' + JSON.stringify(e) + ', json=' + json);
        }
      });
    }, '[Legado] toast/notification tsfn callbacks registered via napi', '[Legado] registerToastCallback/NotificationCallback failed: ');

    // KP8+: 注册 OpenUrl tsfn 回调 (KMP → ArkTS 跨线程 dispatch, 同 Toast 模式 fire-and-forget)。
    // C++ 侧 (legado_napi.cpp RegisterOpenUrlCallback) 创建 napi_threadsafe_function 包装此回调,
    // 并通过 @CName legado_register_open_url_fn 把 dispatch 函数指针
    // 注入 Kotlin OhosNativeBridge.openUrlTsfn。此后 KMP 业务侧 OhosOpenUrlProviderImpl.openUrl →
    // OhosNativeBridge.openUrl 调用时, JSON payload 跨线程 dispatch 到此回调,
    // 由 ArkTS 调 context.startAbility(Want) 打开 URL (KMP 无 ArkTS API 访问能力)。
    // 未注册时 KMP 侧降级 println (见 OhosNativeBridge.openUrl)。
    registerSafely((): void => {
      legado.registerOpenUrlCallback(handleOpenUrl);
    }, '[Legado] openUrl tsfn callback registered via napi', '[Legado] registerOpenUrlCallback failed: ');

    // KP8+: 注册 FilePicker tsfn 回调 (KMP → ArkTS 跨线程 dispatch, 同 Image/Crypto/Http 模式)。
    // C++ 侧 (legado_napi.cpp RegisterFilePickerCallback) 创建 napi_threadsafe_function 包装此回调,
    // 并通过 @CName legado_register_file_picker_fn 把 dispatch 函数指针
    // 注入 Kotlin OhosNativeBridge.filePickerTsfn。此后 KMP 业务侧 OhosFilePicker.pickDocuments /
    // pickDocumentContent → OhosNativeBridge.invokeFilePickerSync 调用时, JSON 请求跨线程 dispatch
    // 到此回调, 由 ArkTS FilePickerBridgeHandler 调 @ohos.file.picker.DocumentViewPicker 选文件 /
    // @ohos.file.fs 读内容, 完成后调 legado.filePickerCallback(requestId, resultJson) 回送结果
    // 唤醒 KMP 侧 CompletableDeferred。未注册时 KMP 侧降级返回 null (见 OhosFilePicker.ohos.kt)。
    registerSafely((): void => {
      // 相册保存 (saveImageToAlbum) 需 UIAbilityContext 申请权限 + 建图库资源
      initFilePickerBridge(this.context);
      legado.registerFilePickerCallback((json: string): void => {
        handleFilePickerRequest(json, (requestId: number, resultJson: string): void => {
          legado.filePickerCallback(requestId, resultJson);
        });
      });
    }, '[Legado] filePicker tsfn callback registered via napi', '[Legado] registerFilePickerCallback failed: ');

    // Pasteboard tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 FilePicker 模式)
    // KMP OhosPlatformOps.copyToClipboard / readFromClipboard → OhosNativeBridge.invokePasteboardSync
    // 发 read/write 请求, ArkTS 侧 PasteboardBridgeHandler 调 @ohos.pasteboard.getSystemPasteboard
    // 读写纯文本, 完成后调 legado.pasteboardCallback(requestId, resultJson) 回送结果
    // 唤醒 KMP 侧 CompletableDeferred。未注册时 KMP 侧降级 println / 返回 null。
    registerSafely((): void => {
      initPasteboardBridge(this.context);
      legado.registerPasteboardCallback((json: string): void => {
        handlePasteboardRequest(json, (requestId: number, resultJson: string): void => {
          legado.pasteboardCallback(requestId, resultJson);
        });
      });
    }, '[Legado] pasteboard tsfn callback registered via napi', '[Legado] registerPasteboardCallback failed: ');

    // Network tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Pasteboard 模式)
    // KMP NetworkAvailability.ohos.kt → OhosNativeBridge.invokeNetworkSync 发 query 请求,
    // ArkTS 侧 NetworkBridgeHandler 调 @ohos.net.connection getDefaultNetSync +
    // getConnectionPropertiesSync (需 ohos.permission.GET_NETWORK_INFO, normal+system_grant)
    // 查询网络可用性与是否 WiFi, 完成后调 legado.networkCallback(requestId, resultJson) 回送结果
    // 唤醒 KMP 侧 CompletableDeferred。未注册时 KMP 侧降级 true (不拦截)。
    registerSafely((): void => {
      legado.registerNetworkCallback((json: string): void => {
        handleNetworkRequest(json, (requestId: number, resultJson: string): void => {
          legado.networkCallback(requestId, resultJson);
        });
      });
    }, '[Legado] network tsfn callback registered via napi', '[Legado] registerNetworkCallback failed: ');

    // TextCodec tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Pasteboard 模式)
    // KMP OhosNativeBridge.invokeTextCodecSync 发 decode/encode 请求 (TXT 分章 GB18030/Big5 等编解码),
    // ArkTS 侧 TextCodecBridgeHandler 调 @ohos.util.TextDecoder/TextEncoder 完成真实转换,
    // 完成后调 legado.textCodecCallback(requestId, resultJson) 回送结果
    // 唤醒 KMP 侧 CompletableDeferred。未注册时 KMP 侧降级。
    registerSafely((): void => {
      legado.registerTextCodecCallback((json: string): void => {
        handleTextCodecRequest(json, (requestId: number, resultJson: string): void => {
          legado.textCodecCallback(requestId, resultJson);
        });
      });
    }, '[Legado] textCodec tsfn callback registered via napi', '[Legado] registerTextCodecCallback failed: ');

    // KP8+: 注册 Image/Media tsfn 回调 (KMP → ArkTS 跨线程 dispatch)。
    // Image: KMP OhosNativeBridge.invokeImageSync 发图片操作请求 (decode/encode/size/crop/split/stitch),
    //        ArkTS 侧 ImageBridgeHandler 调 @ohos.multimedia.image 执行真实像素操作,
    //        完成后调 legado.imageCallback(requestId, resultJson, body?) 回送结果唤醒 KMP 侧 CompletableDeferred
    //        (混合协议: 控制面 JSON + 数据面裸字节 ArrayBuffer, decode 入参图片字节 / encode 出参 packed 字节)。
    // Media: KMP OhosNativeBridge.sendMediaCommand 发播放器命令 (setSource/play/pause/stop/seekTo/release),
    //        ArkTS 侧 MediaBridgeHandler 调 @ohos.multimedia.media AVPlayer 执行真实播放,
    //        AVPlayer 事件通过 legado.mediaEvent(eventJson) 回送给 KMP 侧 MediaEventListener。
    registerSafely((): void => {
      // Image: 注册 tsfn 回调, 请求 JSON + 裸字节 dispatch 到 handleImageRequest
      legado.registerImageCallback((json: string, bytes: ArrayBuffer | undefined): void => {
        handleImageRequest(json, bytes, (requestId: number, resultJson: string, body: ArrayBuffer | undefined): void => {
          legado.imageCallback(requestId, resultJson, body);
        });
      });
    }, '[Legado] image tsfn callback registered via napi', '[Legado] registerImageCallback failed: ');

    registerSafely((): void => {
      // Media: 先注入事件回送函数 (AVPlayer 事件 → legado.mediaEvent → KMP) 与 UIAbilityContext (AVSession/长时任务)
      initMediaBridge((eventJson: string): void => {
        legado.mediaEvent(eventJson);
      }, this.context);
      // 再注册 tsfn 回调, 命令 JSON dispatch 到 handleMediaCommand
      legado.registerMediaCallback((json: string): void => {
        handleMediaCommand(json);
      });
    }, '[Legado] media tsfn callback registered via napi', '[Legado] registerMediaCallback failed: ');

    // TTS tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch)
    // KMP OhosNativeBridge.sendTtsCommand 发 TTS 命令 (createEngine/speak/pause/resume/stop/shutdown),
    // ArkTS 侧 TtsBridgeHandler 调 @kit.CoreSpeechKit textToSpeech 执行真实朗读,
    // TTS 事件通过 legado.ttsEvent(eventJson) 回送给 KMP 侧 TtsEventListener
    registerSafely((): void => {
      initTtsBridge((eventJson: string): void => {
        legado.ttsEvent(eventJson);
      });
      legado.registerTtsCallback((json: string): void => {
        handleTtsCommand(json);
      });
    }, '[Legado] tts tsfn callback registered via napi', '[Legado] registerTtsCallback failed: ');

    // Crypto tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Image 模式)
    // KMP OhosNativeBridge.invokeCryptoSync 发 crypto 操作请求 (encrypt/decrypt/sign/verify),
    // ArkTS 侧 CryptoBridgeHandler 调 @ohos.security.cryptoFramework 执行真实运算,
    // 完成后调 legado.cryptoCallback(requestId, resultJson) 回送结果唤醒 KMP 侧 CompletableDeferred。
    registerSafely((): void => {
      legado.registerCryptoCallback((json: string): void => {
        handleCryptoRequest(json, (requestId: number, resultJson: string): void => {
          legado.cryptoCallback(requestId, resultJson);
        });
      });
    }, '[Legado] crypto tsfn callback registered via napi', '[Legado] registerCryptoCallback failed: ');

    // Http tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Crypto 模式, 混合协议)
    // KMP OhosNativeBridge.invokeHttpSync 发 HTTP 请求 (execute/cancel),
    // ArkTS 侧 HttpBridgeHandler 调 @ohos.net.http 执行真实请求,
    // 完成后调 legado.httpCallback(requestId, resultJson, body?) 回送结果唤醒 KMP 侧 CompletableDeferred
    // (混合协议: 控制面 JSON + 数据面响应 body 裸字节 ArrayBuffer; 请求 body 同样裸传)。
    registerSafely((): void => {
      legado.registerHttpCallback((json: string, body: ArrayBuffer | undefined): void => {
        handleHttpRequest(json, body, (requestId: number, resultJson: string, respBody: ArrayBuffer | undefined): void => {
          legado.httpCallback(requestId, resultJson, respBody);
        });
      });
    }, '[Legado] http tsfn callback registered via napi', '[Legado] registerHttpCallback failed: ');

    // 后台 WebView tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Http 模式, 混合协议)
    // KMP OhosNativeBridge.invokeWebViewSync(jsonControl, htmlRaw) 发后台抓取请求
    // (书源 webView/webViewGetSource 规则), 控制面 JSON 与数据面裸 html 双参数 dispatch 到
    // ArkTS 侧 WebViewBridgeHandler, 用隐藏 Web 组件 (Index.ets 挂载) 加载页面 + runJavaScript 取源码,
    // 完成后调 legado.webViewCallback(requestId, resultJson, bodyRaw) 回送结果
    // (resultJson 为控制面小字段, bodyRaw 为裸源码/命中 URL) 唤醒 KMP 侧 CompletableDeferred。
    registerSafely((): void => {
      legado.registerWebViewCallback((json: string, htmlRaw: string): void => {
        handleWebViewRequest(json, htmlRaw, (requestId: number, resultJson: string, bodyRaw: string): void => {
          legado.webViewCallback(requestId, resultJson, bodyRaw);
        });
      });
    }, '[Legado] webView tsfn callback registered via napi', '[Legado] registerWebViewCallback failed: ');

    // 前台 Web 控制器 tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 单 JSON 协议)
    // KMP OhosWebViewControllerBridge → OhosNativeBridge.invokeWebControllerSync 发前台 WebView
    // 命令 (show/hide/load/reload/eval/hitTest 等), dispatch 到 ArkTS 侧 WebViewBridgeHandler
    // 的 handleForegroundWebRequest (操作 Index.ets 挂载的前台 Web 组件),
    // 完成后调 legado.webControllerCallback(requestId, resultJson) 回送结果唤醒 KMP 侧
    // CompletableDeferred。未注册时 KMP 侧 isReady 恒为 false, 前台 Web 只显示占位文字。
    registerSafely((): void => {
      legado.registerWebControllerCallback((json: string): void => {
        handleForegroundWebRequest(json, (requestId: number, resultJson: string): void => {
          legado.webControllerCallback(requestId, resultJson);
        });
      });
    }, '[Legado] webController tsfn callback registered via napi', '[Legado] registerWebControllerCallback failed: ');

    // Markdown 查看器 tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 同 Toast 模式 fire-and-forget)
    // KMP MarkdownContent (ArkUIView2 混排的 Web 组件) → OhosNativeBridge.sendMarkdown
    // 发渲染请求 { content, isDark, fontSize }, dispatch 到 ArkTS 侧 MarkdownBridgeHandler,
    // 经 Index.ets 挂载的查看器 Web 组件 runJavaScript 注入 renderMarkdown (marked + hljs) 渲染。
    // 页面内链接点击经 legado.markdownEvent(eventJson) 回送 KMP 走系统浏览器。
    registerSafely((): void => {
      legado.registerMarkdownCallback((json: string): void => {
        handleMarkdownRequest(json);
      });
    }, '[Legado] markdown tsfn callback registered via napi', '[Legado] registerMarkdownCallback failed: ');

    // Battery tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 阅读页电池电量查询)
    // KMP OhosReaderPlatformProvider.getBatteryLevel → OhosNativeBridge.invokeBatterySync
    // 发 getLevel 请求, ArkTS 侧 BatteryBridgeHandler 调 @ohos.batteryInfo.batterySOC,
    // 完成后调 legado.batteryCallback(requestId, resultJson) 回送结果唤醒 KMP 侧 CompletableDeferred。
    // 未注册时 KMP 侧 getBatteryLevel 恒返回 -1 (阅读页不显示电量)。
    registerSafely((): void => {
      legado.registerBatteryCallback((json: string): void => {
        handleBatteryRequest(json, (requestId: number, resultJson: string): void => {
          legado.batteryCallback(requestId, resultJson);
        });
      });
    }, '[Legado] battery tsfn callback registered via napi', '[Legado] registerBatteryCallback failed: ');

    // Share tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 系统分享面板, 同 Toast 模式 fire-and-forget)
    // KMP OhosPlatformServices.sharing.shareText/shareFile → OhosNativeBridge.shareText/shareFile
    // 经 tsfn dispatch 到此回调, ArkTS 侧 SystemShareBridgeHandler 调 @kit.ShareKit systemShare
    // 弹系统分享面板。未注册时 KMP 侧降级写剪贴板 + toast (见 OhosPlatformServices)。
    registerSafely((): void => {
      // ShareController.show 需 UIAbilityContext, 注册回调前先注入
      initShareBridge(this.context);
      legado.registerShareCallback(handleShareRequest);
    }, '[Legado] share tsfn callback registered via napi', '[Legado] registerShareCallback failed: ');

    // Keyboard tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 软键盘显隐/避让, 同 Window 模式 fire-and-forget)
    // KMP OhosPlatformServices.keyboard.hideSoftInput/setSoftInputPolicy
    // → OhosNativeBridge.hideSoftInput/setKeyboardAvoidMode 经 tsfn dispatch 到此回调,
    // ArkTS 侧 KeyboardBridgeHandler 调 @ohos.inputMethod / UIContext。
    // 未注册时 KMP 侧降级 println。
    registerSafely((): void => {
      legado.registerKeyboardCallback(handleKeyboardCommand);
    }, '[Legado] keyboard tsfn callback registered via napi', '[Legado] registerKeyboardCallback failed: ');

    // Permission tsfn 回调注册 (KMP → ArkTS 跨线程 dispatch, 权限查询/申请)
    // KMP OhosPlatformServices.permissions.hasPermission/requestPermission
    // → OhosNativeBridge.invokePermissionSync 经 tsfn dispatch 到此回调,
    // ArkTS 侧 PermissionBridgeHandler 调 @ohos.abilityAccessCtrl.checkAccessTokenSync /
    // requestPermissionsFromUser, 完成后调 legado.permissionCallback 回送结果。
    // 未注册时 KMP 侧按"无权限"降级。
    registerSafely((): void => {
      initPermissionBridge(this.context);
      legado.registerPermissionCallback((json: string): void => {
        handlePermissionRequest(json, (requestId: number, resultJson: string): void => {
          legado.permissionCallback(requestId, resultJson);
        });
      });
    }, '[Legado] permission tsfn callback registered via napi', '[Legado] registerPermissionCallback failed: ');

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
   * - 通知按钮点击 (want.uri 为空, notificationId + action 在 want.parameters) →
   *   emitPlatformEvent (Kotlin OhosPlatformEventChannel 的 type="notificationAction")
   * - legado:// / yuedu:// 一键导入链接 → legado.handleDeepLink
   *   (@CName legado_handle_deep_link → LegadoDeepLinkHandler 弹勾选导入对话框)
   * - 主屏快捷方式 (want.uri 为空, 路由在 want.parameters.route) → legado.handleLaunchRequest
   * - 其余非空 URI (文件关联 file:// / 其他 scheme deep link / 通知 route:xxx) →
   *   legado.handleLaunchRequest (@CName legado_handle_launch_request → OhosLaunchRequests
   *   → LaunchRequestBus → Compose LegadoApp 路由导航)。普通启动 want.uri 为空, 跳过。
   *
   * # want.type (MIME) 为何要拼进同一串
   * 系统把视频投给本 Ability 时, 光有 want.uri 不够: 媒体库地址形如
   * `file://media/Video/<fileId>`, 末段没有扩展名, KMP 侧 VideoDirect 的扩展名嗅探判不出
   * 视频 (只有 want.type = video/mp4 能认)。napi 导出签名只能传一个字符串, 改签名要同步
   * 重生成 liblegado_shared_api.h (构建产物, 不得手改), 所以按 Kotlin OhosLaunchRequests
   * 的既定约定把 MIME 前缀拼到同一串: `mime:<type>|<uri>` (与已有的 `route:` 前缀同一路数)。
   * 不带 MIME 的普通启动串依旧原样透传, 旧链路行为零变化。
   */
  private handleDeepLink(want: Want): void {
    const uri: string = want.uri ?? '';
    if (uri.length === 0) {
      // 通知按钮点击: 参数在 want.parameters (notificationId + action), 不在 want.uri ——
      // 先投递回 Kotlin (统一平台事件通道 type="notificationAction"), 不再走其它启动分支。
      if (dispatchNotificationAction(want)) {
        return;
      }
      // 主屏快捷方式: 路由参数在 want.parameters.route (shortcuts_config.json 的
      // wants.parameters), 不在 want.uri —— 不先把这一支捞出来, 三项快捷方式全都只能
      // 拉起应用而不会跳转。值按裸路由名约定拼 `route:` 前缀投递 (与通知点击同一条链)。
      const shortcutRoute: string = routeFromWantParameters(want);
      if (shortcutRoute.length > 0) {
        const routeRequest: string = LAUNCH_ROUTE_PREFIX + shortcutRoute;
        try {
          const handledRoute: boolean = legado.handleLaunchRequest(routeRequest);
          console.info('[Legado] shortcut route dispatched: handled=' + handledRoute + ', route=' + shortcutRoute);
        } catch (e) {
          console.error('[Legado] shortcut route dispatch failed: ' + JSON.stringify(e) + ', route=' + shortcutRoute);
        }
        return;
      }
      // 系统分享投递的数据不在 want.uri 里 (纯文本分享根本没有 uri, 文件分享的数据在
      // systemShare 的 SharedData 中), 故此处补解析 (对照 iOS 宿主回调 handleIosLaunchRequest
      // 的入口位置; 鸿蒙侧对应入口 = OhosLaunchRequests)。非分享 want 由 action 判定直接返回。
      this.handleSharedData(want);
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
    const request: string = withMimeTypeHint(uri, want.type ?? '');
    try {
      const handled: boolean = legado.handleLaunchRequest(request);
      console.info('[Legado] launch request dispatched: handled=' + handled + ', uri=' + uri);
    } catch (e) {
      console.error('[Legado] handleLaunchRequest failed: ' + JSON.stringify(e) + ', uri=' + uri);
    }
  }

  /**
   * 解析系统分享投递过来的数据 (文件/文本) 并交给既有外部启动请求链。
   *
   * # 为什么需要单独一条路
   * module.json5 的 share skill 只负责"声明可接分享", 而分享投递的载荷不在 want.uri 里 ——
   * 文本分享根本无 uri, 文件分享的数据在 systemShare.getSharedData 的 SharedData 记录中。
   * 宿主入口侧不解析就毫无反应 (之前的状态), 故在此把每条记录转成既有投递串,
   * 交 legado.handleLaunchRequest (@CName legado_handle_launch_request →
   * OhosLaunchRequests.parse → LaunchRequestBus), 使分享进来的文件走既有导入分发
   * (对照 iOS 宿主调 handleIosLaunchRequest 的位置与分工)。
   *
   * # 与 want.uri 链的分工
   * 只在 want.uri 为空时调用 (handleDeepLink 的空 uri 分支): 带 uri 的 want (文件关联) 已由
   * 原链路投递, 此处再解一次会对同一文件重复投递。
   *
   * @param want 本次启动/热启动的 Want (action 非分享时直接返回, 不调 getSharedData)
   */
  private handleSharedData(want: Want): void {
    if (want.action !== SHARE_ACTION) {
      return;
    }
    systemShare.getSharedData(want).then((data: systemShare.SharedData): void => {
      const records: systemShare.SharedRecord[] = data.getRecords();
      for (const record of records) {
        const request: string = toLaunchRequestFromSharedRecord(record);
        if (request.length === 0) {
          continue;
        }
        try {
          const handled: boolean = legado.handleLaunchRequest(request);
          console.info('[Legado] share data dispatched: handled=' + handled + ', request=' + request);
        } catch (e) {
          console.error('[Legado] handleLaunchRequest (share) failed: ' + JSON.stringify(e) + ', request=' + request);
        }
      }
    }).catch((err: Error): void => {
      // 非分享 want 或解析失败 (BusinessError 1003703001) 走到这里: 不影响其它启动路径
      console.error('[Legado] getSharedData failed: ' + JSON.stringify(err));
    });
  }

  onConfigurationUpdate(newConfig: Configuration): void {
    console.info('[Legado] EntryAbility.onConfigurationUpdate');
    // 系统深浅色变更 → Kotlin NativeSystemTheme: 刷按日/夜取键的派生缓存并 emit RECREATE,
    // AppTheme 与直读日/夜色的界面随之重组 (「主题模式=跟随系统」时生效)。
    try {
      const isDark: boolean =
        newConfig.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
      emitColorMode(isDark);
    } catch (e) {
      console.error('[Legado] colorMode update failed: ' + JSON.stringify(e));
    }
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
                case 'showDialog':
                  // 对话框桥 (KMP → ArkTS 跨线程 dispatch, 复用本通道避免新增独立 tsfn;
                  // 同 exitApplication 的复用先例)。payload 是 OhosNativeBridge.DialogBridgeRequest
                  // JSON, 交 SysDialogBridgeHandler 弹对话框 (候选列表 / 是-否确认框 / 多字段输入框),
                  // 用户选择后经 legado.platformEvent (type='dialogResult') 回送 KMP。
                  handleDialogRequest(cmd.payload ?? '', (requestId: number, resultJson: string): void => {
                    emitDialogResult(requestId, resultJson);
                  });
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

  /**
   * UIAbility 销毁: 长时任务由 UIAbility 申请 (系统文档: stopBackgroundRunning 取消"当前 UIAbility
   * 下所有长时任务"), Ability 销毁后必须重新申请, 故清掉仲裁器的持有态。
   */
  onDestroy(): void {
    console.info('[Legado] EntryAbility.onDestroy');
    notifyAbilityDestroyed();
  }

  onForeground(): void {
    console.info('[Legado] EntryAbility.onForeground');
    // 应用回到前台 → Kotlin OhosAppLifecycle (阅读页 onEnter 挂的监听器调 ReaderScreenModel.onResume:
    // 开始阅读计时 + web 进度恢复, 对齐 iOS UIApplicationWillEnterForegroundNotification)。
    // 事件仅在前台切换时触发一次, 无重复; 阅读页未挂监听时 Kotlin 侧无操作。
    emitAppLifecycle('onForeground');
  }

  onBackground(): void {
    console.info('[Legado] EntryAbility.onBackground');
    // 应用退到后台 → Kotlin OhosAppLifecycle (阅读页监听器调 ReaderScreenModel.onPause:
    // 计时结束 + 落库上传 + 取消预下载, 对齐 iOS UIApplicationDidEnterBackgroundNotification)。
    emitAppLifecycle('onBackground');
  }
}
