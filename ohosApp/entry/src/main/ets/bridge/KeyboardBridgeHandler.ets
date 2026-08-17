import inputMethod from '@ohos.inputMethod';
import { UIContext } from '@ohos.arkui.UIContext';
import { BridgeCommand, parseBridgeCommand } from './BridgeDispatch';

/**
 * ArkTS 侧软键盘处理器 (桥接 @ohos.inputMethod + UIContext)。
 *
 * 接收 Kotlin OhosNativeBridge.hideSoftInput / showSoftInput / setKeyboardAvoidMode
 * 通过 tsfn dispatch 过来的键盘命令, 调用 @ohos.inputMethod 执行真实显隐 / 避让。
 *
 * # JSON 协议 (与 OhosNativeBridge.kt KeyboardCommand 对齐)
 *
 * ## 命令 (Kotlin → ArkTS, fire-and-forget)
 * {
 *   "action": "hide" | "show" | "setAvoidMode",
 *   "mode": <number?>   // setAvoidMode 时: 0=OFFSET(上推, 同 ADJUST_PAN) 1=RESIZE(收缩) 2=NONE(不避让)
 * }
 *
 * # 调用链
 * KMP OhosPlatformServices.keyboard.hideSoftInput → OhosNativeBridge.hideSoftInput
 *   → keyboardTsfn → C++ ohos_keyboard_dispatch → napi_call_threadsafe_function → KeyboardCallJs
 *   → [handleKeyboardCommand] → inputMethod.getController().stopInputSession() / showSoftKeyboard() /
 *   uiContext.setKeyboardAvoidMode(...)
 *
 * # 设计说明
 * - fire-and-forget (与 Window 同模式): 命令无返回值, 无 ArkTS → Kotlin 回调。
 * - setKeyboardAvoidMode 需要 UIContext (由 Index.ets aboutToAppear 经 [initKeyboardBridge] 注入);
 *   未注入时降级仅打日志 (避让策略不生效, hide/show 不受影响)。
 * - 鸿蒙 KeyboardAvoidMode 枚举只有 OFFSET/RESIZE, 无 NONE; mode=2 (NONE) 时跳过不改避让模式。
 */

// 由 Index.ets 注入的 UIContext (供 setKeyboardAvoidMode; 未注入不影响 hide/show)
let uiContext: UIContext | undefined;

interface KeyboardCommand extends BridgeCommand {
  mode?: number;
}

/**
 * 初始化键盘桥 (由 Index.ets aboutToAppear 调用, 注入 UIContext)。
 *
 * @param ctx 当前窗口 UIContext (用于 setKeyboardAvoidMode)
 */
export function initKeyboardBridge(ctx: UIContext): void {
  uiContext = ctx;
}

/**
 * 处理来自 Kotlin 的软键盘命令 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param json 命令 JSON 字符串, 形如 `{ action: 'hide'|'show'|'setAvoidMode', mode? }`
 */
export function handleKeyboardCommand(json: string): void {
  const parsed = parseBridgeCommand(json, 'KeyboardBridge', 'command');
  if (parsed == null) {
    return;
  }
  const cmd = parsed as KeyboardCommand;

  switch (cmd.action) {
    case 'hide':
      handleHide();
      break;
    case 'show':
      handleShow();
      break;
    case 'setAvoidMode':
      handleSetAvoidMode(cmd.mode ?? 0);
      break;
    default:
      console.warn('[KeyboardBridge] unknown action: ' + cmd.action);
  }
}

/** 收起软键盘 (对照 inputMethod.getController().stopInputSession)。 */
function handleHide(): void {
  try {
    const controller = inputMethod.getController();
    controller.stopInputSession();
    console.info('[KeyboardBridge] hide');
  } catch (e) {
    console.error('[KeyboardBridge] hide failed: ' + JSON.stringify(e));
  }
}

/** 拉起软键盘 (对照 inputMethod.getController().showSoftKeyboard)。 */
function handleShow(): void {
  try {
    const controller = inputMethod.getController();
    controller.showSoftKeyboard();
    console.info('[KeyboardBridge] show');
  } catch (e) {
    console.error('[KeyboardBridge] show failed: ' + JSON.stringify(e));
  }
}

/**
 * 设置键盘避让模式 (对照 UIContext.setKeyboardAvoidMode)。
 *
 * @param mode 0=OFFSET (页面整体上推, 对齐 Android ADJUST_PAN),
 *   1=RESIZE (页面收缩, 对齐 ADJUST_RESIZE), 2=NONE (不避让, 鸿蒙枚举无此项, 跳过)
 */
function handleSetAvoidMode(mode: number): void {
  if (uiContext == null) {
    console.warn('[KeyboardBridge] setAvoidMode: uiContext not injected, mode=' + mode);
    return;
  }
  try {
    // KeyboardAvoidMode 是数值枚举 (OFFSET=0 / RESIZE=1), 直接传数值即可
    if (mode === 0) {
      uiContext.setKeyboardAvoidMode(0);
    } else if (mode === 1) {
      uiContext.setKeyboardAvoidMode(1);
    } else {
      // mode=2 (NONE): 鸿蒙 KeyboardAvoidMode 枚举无 NONE, 跳过保持当前模式
      console.info('[KeyboardBridge] setAvoidMode: NONE not supported on 鸿蒙, skip');
      return;
    }
    console.info('[KeyboardBridge] setAvoidMode: mode=' + mode);
  } catch (e) {
    console.error('[KeyboardBridge] setAvoidMode failed: ' + JSON.stringify(e) + ', mode=' + mode);
  }
}
