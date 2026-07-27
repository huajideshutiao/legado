import promptAction from '@ohos.promptAction';
import common from '@ohos.app.ability.common';
import type { Want } from '@ohos.app.ability.AbilityConstant';

/**
 * ArkTS 侧系统桥接处理器 (桥接 promptAction.showToast + context.startAbility)。
 *
 * 接收 Kotlin OhosNativeBridge.showToast / openUrl 通过 tsfn dispatch 过来的请求,
 * 调用鸿蒙系统 API 执行真实操作 (KMP 无 ArkTS API 访问能力, 需 tsfn 桥接)。
 *
 * # JSON 协议 (与 OhosNativeBridge.kt ToastPayload / OpenUrlPayload 对齐)
 * - toast:  `{ message: string, durationMs: number }`
 * - openUrl: `{ url: string, mimeType?: string, sourceKey?: string, sourceTag?: string, sourceType: number }`
 *
 * # 调用链
 * - toast:
 *   KMP OhosToastProvider.showToast → OhosNativeBridge.showToast → toastTsfn →
 *   C++ ohos_toast_dispatch → napi_call_threadsafe_function → ArkTS ToastCallJs →
 *   [handleShowToast] → promptAction.showToast
 * - openUrl:
 *   KMP OhosOpenUrlProvider.openUrl → OhosNativeBridge.openUrl → openUrlTsfn →
 *   C++ ohos_open_url_dispatch → napi_call_threadsafe_function → ArkTS OpenUrlCallJs →
 *   [handleOpenUrl] → context.startAbility(Want.uri=url)
 *
 * # 设计说明
 * - 与 ImageBridgeHandler / CryptoBridgeHandler / HttpBridgeHandler 不同, 本 handler 是
 *   fire-and-forget (无 requestId, 无结果回送), 与 Toast/Notification 同模式。
 * - showToast 之前在 EntryAbility.ets 内联实现 (registerToastCallback 回调内), 现抽出至此 handler
 *   统一管理 system 桥接 (与 ImageBridge 等保持一致结构, 便于维护)。
 * - openUrl 调 context.startAbility(Want) 让系统选择合适应用打开 URL (浏览器/文件查看器等),
 *   action='ohos.want.action.viewData' 对齐鸿蒙隐式启动语义, mimeType 作为 Want.type 过滤候选应用。
 */

interface ToastPayload {
  message: string;
  durationMs: number;
}

interface OpenUrlPayload {
  url: string;
  mimeType?: string;
  sourceKey?: string;
  sourceTag?: string;
  sourceType: number;
}

// 由 EntryAbility 注入的 UIAbilityContext (供 startAbility 使用)
// 注: promptAction.showToast 无需 context (全局 API), 但 startAbility 必须通过 UIAbilityContext 调用
let abilityContext: common.UIAbilityContext | undefined;

/**
 * 初始化系统桥 (由 EntryAbility.onCreate 调用, 注入 UIAbilityContext)。
 *
 * @param context UIAbility 上下文 (用于 context.startAbility 打开 URL)
 */
export function initSystemBridge(context: common.UIAbilityContext): void {
  abilityContext = context;
}

/**
 * 处理 toast 请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param json ToastPayload JSON 字符串, 形如 `{ message: '<text>', durationMs: <number> }`
 */
export function handleShowToast(json: string): void {
  try {
    const payload = JSON.parse(json) as ToastPayload;
    promptAction.showToast({ message: payload.message, duration: payload.durationMs });
  } catch (e) {
    console.error('[SystemBridge] toast failed: ' + JSON.stringify(e) + ', json=' + json);
  }
}

/**
 * 处理 openUrl 请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * 调用 context.startAbility(Want) 打开 URL, action='ohos.want.action.viewData' 让系统选择合适应用打开
 * (浏览器打开 http/https 链接, 文件查看器打开 file:// 链接, 其他应用打开 intent 等)。
 *
 * mimeType 非空时作为 Want.type 传给系统, 用于过滤候选应用 (与 Android Intent.setType 语义一致)。
 *
 * @param json OpenUrlPayload JSON 字符串, 形如 `{ url, mimeType?, sourceKey?, sourceTag?, sourceType }`
 */
export function handleOpenUrl(json: string): void {
  try {
    const payload = JSON.parse(json) as OpenUrlPayload;
    if (abilityContext == null) {
      console.error('[SystemBridge] openUrl failed: abilityContext not initialized, url=' + payload.url);
      return;
    }
    const want: Want = {
      uri: payload.url,
      action: 'ohos.want.action.viewData',
    };
    if (payload.mimeType != null && payload.mimeType.length > 0) {
      want.type = payload.mimeType;
    }
    abilityContext.startAbility(want).catch((err: Error) => {
      console.error('[SystemBridge] startAbility failed: ' + JSON.stringify(err) + ', url=' + payload.url);
    });
  } catch (e) {
    console.error('[SystemBridge] openUrl failed: ' + JSON.stringify(e) + ', json=' + json);
  }
}
