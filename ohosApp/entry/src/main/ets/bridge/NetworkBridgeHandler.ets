import connection from '@ohos.net.connection';
import { BusinessError } from '@ohos.base';
import { BridgeRequest, BridgeResponse, dispatchBridgeRequest } from './BridgeDispatch';

/**
 * ArkTS 侧网络状态处理器。
 *
 * 接收 Kotlin OhosNativeBridge.invokeNetworkSync 通过 tsfn dispatch 过来的网络状态查询,
 * 调用 @ohos.net.connection 的 getDefaultNetSync + getNetCapabilitiesSync (API 10+,
 * 同步 API) 查询网络可用性与网络类型, 完成后通过 legado.networkCallback(requestId, resultJson)
 * 回送结果给 Kotlin。
 *
 * # JSON 协议 (与 NetworkAvailability.ohos.kt / OhosNativeBridge.kt 对齐)
 *
 * ## 请求 (Kotlin → ArkTS)
 * {
 *   "requestId": <number>,
 *   "action": "query",
 *   "payload": "{}"
 * }
 *
 * ## 响应 (ArkTS → Kotlin)
 * - 成功:  `{ ok: true, network: <boolean>, wifi: <boolean> }`
 *   - network: 能取到默认网络句柄及连接属性 (有可用网络)
 *   - wifi:    网络能力 bearerTypes 含 BEARER_WIFI
 * - 失败:  `{ ok: false, error: "<string>" }` (查询异常, Kotlin 侧降级 true 不拦截)
 *
 * # 权限
 * getNetCapabilitiesSync 需 ohos.permission.GET_NETWORK_INFO (normal + system_grant,
 * module.json5 声明即自动授予), 无用户授权流程。
 *
 * # 无网络语义
 * 无默认网络时 getDefaultNetSync 抛 BusinessError → 返回 { ok: true, network: false, wifi: false };
 * 句柄有效但取连接属性失败 (权限/其他异常) → 返回 { ok: false, error } 由 Kotlin 降级处理。
 */

// Network callback 类型 (由 EntryAbility 注入, 内部调 legado.networkCallback)
export type NetworkCallback = (requestId: number, resultJson: string) => void;

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface NetworkResponse extends BridgeResponse {
  network?: boolean;
  wifi?: boolean;
}

/**
 * 处理来自 Kotlin 的网络状态查询 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.networkCallback)
 */
export function handleNetworkRequest(requestJson: string, callback: NetworkCallback): void {
  dispatchBridgeRequest(requestJson, 'NetworkBridge', handleAction, callback);
}

// 根据 action 分发到具体处理函数
async function handleAction(req: BridgeRequest): Promise<NetworkResponse> {
  switch (req.action) {
    case 'query': return await handleQuery();
    default: return { ok: false, error: 'unknown action: ' + req.action };
  }
}

// ===== query: getDefaultNetSync + getNetCapabilitiesSync → network/wifi =====
async function handleQuery(): Promise<NetworkResponse> {
  // 第一步: 取默认网络句柄。无默认网络 (无网) 时抛 BusinessError → 明确报"无网络"。
  let netHandle: connection.NetHandle;
  try {
    netHandle = connection.getDefaultNetSync();
  } catch (e) {
    const err = e as BusinessError;
    console.warn('[NetworkBridge] getDefaultNetSync failed: ' + (err.message ?? String(e)));
    return { ok: true, network: false, wifi: false };
  }
  // 第二步: 取网络能力。句柄无效/查询失败等异常 → ok:false, 由 Kotlin 降级 true (不拦截)。
  // API 24 起 ConnectionProperties 不再含 bearerType, 改用 NetCapabilities.bearerTypes 数组。
  try {
    const caps = connection.getNetCapabilitiesSync(netHandle);
    return {
      ok: true,
      network: true,
      wifi: caps.bearerTypes.includes(connection.NetBearType.BEARER_WIFI),
    };
  } catch (e) {
    const err = e as BusinessError;
    console.warn('[NetworkBridge] getNetCapabilitiesSync failed: ' + (err.message ?? String(e)));
    return { ok: false, error: err.message ?? String(e) };
  }
}
