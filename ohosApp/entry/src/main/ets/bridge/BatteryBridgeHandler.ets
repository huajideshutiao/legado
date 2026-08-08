import batteryInfo from '@ohos.batteryInfo';

/**
 * ArkTS 侧电池电量处理器 (桥接 @ohos.batteryInfo)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeBatterySync 通过 tsfn dispatch 过来的电量查询请求,
 * 调用 @ohos.batteryInfo.batterySOC 读取当前电量百分比,
 * 完成后通过 legado.batteryCallback(requestId, resultJson) 回送结果给 Kotlin。
 *
 * # JSON 协议 (与 OhosNativeBridge.kt BatteryBridgeRequest 对齐)
 *
 * ## 请求 (Kotlin → ArkTS)
 * {
 *   "requestId": <number>,
 *   "action": "getLevel"
 * }
 *
 * ## 响应 (ArkTS → Kotlin, 由 legado.batteryCallback 推送)
 * - 成功: `{ ok: true, level: <0-100> }`
 * - 失败: `{ ok: false, error: "<string>" }`
 *
 * # 调用链
 * KMP OhosReaderPlatformProvider.getBatteryLevel → OhosNativeBridge.invokeBatterySync("getLevel")
 *   → batteryTsfn → C++ ohos_battery_dispatch → napi_call_threadsafe_function → BatteryCallJs
 *   → [handleBatteryRequest] → batteryInfo.batterySOC
 *   → legado.batteryCallback(requestId, resultJson) → onBatteryResult → deferred.complete
 *   → getBatteryLevel 解析 level 返回 (失败/超时返回 -1 不显示)
 */

// 由 EntryAbility 注入的结果回送函数类型 (内部调 legado.batteryCallback)
export type BatteryCallback = (requestId: number, resultJson: string) => void;

interface BatteryBridgeRequest {
  requestId: number;
  action: string;
}

interface BatteryResponse {
  ok: boolean;
  level?: number;
  error?: string;
}

/**
 * 处理来自 Kotlin 的电量查询请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.batteryCallback)
 */
export function handleBatteryRequest(requestJson: string, callback: BatteryCallback): void {
  let req: BatteryBridgeRequest;
  try {
    req = JSON.parse(requestJson) as BatteryBridgeRequest;
  } catch (e) {
    console.error('[BatteryBridge] parse request failed: ' + requestJson);
    return;
  }

  if (req.action !== 'getLevel') {
    const resp: BatteryResponse = { ok: false, error: 'unknown action: ' + req.action };
    callback(req.requestId, JSON.stringify(resp));
    return;
  }

  try {
    // batterySOC: 当前电池电量百分比 (0-100); 不支持充电状态时可能返回 -1, 由 Kotlin 侧兜底不显示
    const level = batteryInfo.batterySOC;
    const resp: BatteryResponse = { ok: true, level: level };
    callback(req.requestId, JSON.stringify(resp));
  } catch (e) {
    console.error('[BatteryBridge] read battery level failed: ' + JSON.stringify(e));
    const resp: BatteryResponse = { ok: false, error: (e as Error).message ?? String(e) };
    callback(req.requestId, JSON.stringify(resp));
  }
}
