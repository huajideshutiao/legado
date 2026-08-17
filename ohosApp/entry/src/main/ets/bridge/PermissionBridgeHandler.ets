import abilityAccessCtrl from '@ohos.abilityAccessCtrl';
import type { Permissions } from '@ohos.abilityAccessCtrl';
import common from '@ohos.app.ability.common';
import { BusinessError } from '@ohos.base';
import { BridgeRequest, BridgeResponse, dispatchBridgeRequest } from './BridgeDispatch';

/**
 * ArkTS 侧权限处理器 (桥接 @ohos.abilityAccessCtrl)。
 *
 * 接收 Kotlin OhosNativeBridge.invokePermissionSync 通过 tsfn dispatch 过来的权限查询/申请请求,
 * 调用 @ohos.abilityAccessCtrl.createAtManager() 的 checkAccessTokenSync / requestPermissionsFromUser
 * 执行真实权限检查 / 运行时申请 (user_grant 弹窗), 完成后通过
 * legado.permissionCallback(requestId, resultJson) 回送结果给 Kotlin。
 *
 * # JSON 协议 (与 OhosNativeBridge.kt PermissionBridgeRequest + OhosPlatformServices.kt 对齐)
 *
 * ## 请求 (Kotlin → ArkTS)
 * {
 *   "requestId": <number>,
 *   "action": "check" | "request",
 *   "payload": "{\"permission\":\"ohos.permission.XXX\"}"
 * }
 *
 * ## 响应 (ArkTS → Kotlin, 由 legado.permissionCallback 推送)
 * - 成功: `{ ok: true, granted: <boolean> }` (granted 为最终授权态)
 * - 失败: `{ ok: false, error: "<string>" }`
 *
 * # 调用链
 * KMP OhosPlatformServices.permissions.hasPermission/requestPermission
 *   → OhosNativeBridge.invokePermissionSync("check"|"request", payload)
 *   → permissionTsfn → C++ ohos_permission_dispatch → napi_call_threadsafe_function → PermissionCallJs
 *   → [handlePermissionRequest] → atManager.checkAccessTokenSync / requestPermissionsFromUser
 *   → legado.permissionCallback(requestId, resultJson) → onPermissionResult → deferred.complete
 *
 * # 权限语义
 * - check: 同步查 tokenId (accessTokenId 取应用自身), 不弹窗。
 * - request: requestPermissionsFromUser 会回授权结果 (Promise), 结果即最终是否授予
 *   (与 Kotlin PermissionService.requestPermission 的"返回值即最终是否授予"注释对齐)。
 */

// 由 EntryAbility 注入的结果回送函数类型 (内部调 legado.permissionCallback)
export type PermissionCallback = (requestId: number, resultJson: string) => void;

// UIAbilityContext (requestPermissionsFromUser 需要; 由 EntryAbility 注入)
let abilityContext: common.UIAbilityContext | undefined;

interface PermissionPayload {
  permission: string;
}

interface PermissionResponse extends BridgeResponse {
  granted?: boolean;
}

/**
 * 初始化权限桥 (由 EntryAbility.onCreate 调用, 注入 UIAbilityContext)。
 *
 * @param context UIAbility 上下文 (用于 requestPermissionsFromUser)
 */
export function initPermissionBridge(context: common.UIAbilityContext): void {
  abilityContext = context;
}

/**
 * 检查并按需申请单个权限, 返回最终授权态。
 *
 * 页面内直接复用 (如 Index.ets saveImageToGallery 保存图片前的相册权限),
 * 与本桥 check/request 两个 action 的能力等价, 避免各页面内联重复
 * checkAccessTokenSync + requestPermissionsFromUser 组合。
 *
 * @param context UIAbility 上下文 (requestPermissionsFromUser 需要)
 * @param permission 权限名 (如 'ohos.permission.WRITE_IMAGEVIDEO')
 */
export async function ensurePermissionGranted(context: common.UIAbilityContext, permission: string): Promise<boolean> {
  const atManager = abilityAccessCtrl.createAtManager();
  // accessTokenId 取应用自身 token: UIAbilityContext.applicationInfo.accessTokenId
  const tokenId = context.applicationInfo.accessTokenId;
  let granted = atManager.checkAccessTokenSync(tokenId, permission as Permissions) ===
    abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  if (!granted) {
    // Permissions 是 SDK 的字符串字面量联合类型, 调用方传的是普通 string, 此处断言收窄
    const permissions: Array<Permissions> = [permission as Permissions];
    const result = await atManager.requestPermissionsFromUser(context, permissions);
    granted = result.authResults.length > 0 &&
      result.authResults[0] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  }
  return granted;
}

/**
 * 处理来自 Kotlin 的权限请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.permissionCallback)
 */
export function handlePermissionRequest(requestJson: string, callback: PermissionCallback): void {
  dispatchBridgeRequest(requestJson, 'PermissionBridge', handleAction, callback);
}

// 根据 action 分发到具体处理函数
async function handleAction(req: BridgeRequest): Promise<PermissionResponse> {
  if (req.action === 'check') {
    return await handleCheck(req);
  } else if (req.action === 'request') {
    return await handleRequest(req);
  }
  return { ok: false, error: 'unknown action: ' + req.action };
}

// ===== check: checkAccessTokenSync (同步查询, 不弹窗) =====
function handleCheck(req: BridgeRequest): PermissionResponse {
  let p: PermissionPayload;
  try {
    p = JSON.parse(req.payload ?? '') as PermissionPayload;
  } catch (e) {
    return { ok: false, error: 'parse payload failed' };
  }
  try {
    const atManager = abilityAccessCtrl.createAtManager();
    // accessTokenId 取应用自身 token: UIAbilityContext.applicationInfo.accessTokenId
    const tokenId = abilityContext?.applicationInfo.accessTokenId ?? 0;
    const granted = atManager.checkAccessTokenSync(tokenId, p.permission as Permissions) ===
      abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
    return { ok: true, granted: granted };
  } catch (e) {
    console.error('[PermissionBridge] check failed: ' + JSON.stringify(e));
    return { ok: false, error: (e as BusinessError).message ?? String(e) };
  }
}

// ===== request: requestPermissionsFromUser (运行时申请, 弹窗, 返回最终授权态) =====
async function handleRequest(req: BridgeRequest): Promise<PermissionResponse> {
  let p: PermissionPayload;
  try {
    p = JSON.parse(req.payload ?? '') as PermissionPayload;
  } catch (e) {
    return { ok: false, error: 'parse payload failed' };
  }
  if (abilityContext == null) {
    return { ok: false, error: 'abilityContext not initialized' };
  }
  try {
    const atManager = abilityAccessCtrl.createAtManager();
    // Permissions 是 SDK 的字符串字面量联合类型, KMP 侧传的是普通 string, 此处断言收窄
    const permissions: Array<Permissions> = [p.permission as Permissions];
    const result = await atManager.requestPermissionsFromUser(abilityContext, permissions);
    const granted = result.authResults.length > 0 &&
      result.authResults[0] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
    return { ok: true, granted: granted };
  } catch (e) {
    console.error('[PermissionBridge] request failed: ' + JSON.stringify(e));
    return { ok: false, error: (e as BusinessError).message ?? String(e) };
  }
}
