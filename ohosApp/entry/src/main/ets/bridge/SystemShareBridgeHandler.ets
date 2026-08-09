import { systemShare } from '@kit.ShareKit';
import uniformTypeDescriptor from '@ohos.data.uniformTypeDescriptor';
import fileUri from '@ohos.file.fileuri';
import common from '@ohos.app.ability.common';

/**
 * ArkTS 侧系统分享处理器 (桥接 @kit.ShareKit systemShare)。
 *
 * 接收 Kotlin OhosNativeBridge.shareText / shareFile 通过 tsfn dispatch 过来的分享请求,
 * 用 systemShare.SharedData + ShareController.show 弹系统分享面板 (SharePanel)。
 *
 * # JSON 协议 (与 OhosNativeBridge.kt SharePayload 对齐)
 *
 * ## 请求 (Kotlin → ArkTS, fire-and-forget)
 * {
 *   "action": "text" | "file",
 *   "text": "<string?>",        // action='text' 时为待分享纯文本
 *   "filePath": "<string?>",    // action='file' 时为沙盒内文件绝对路径
 *   "mimeType": "<string?>"     // action='file' 时为文件 MIME 类型
 * }
 *
 * # 调用链
 * KMP OhosPlatformServices.sharing.shareText/shareFile → OhosNativeBridge.shareText/shareFile
 *   → shareTsfn → C++ ohos_share_dispatch → napi_call_threadsafe_function → ShareCallJs
 *   → [handleShareRequest] → new ShareController(SharedData).show(context, options)
 *
 * # 设计说明
 * - fire-and-forget (与 Toast 同模式): 分享面板由用户操作, 结果对调用方无意义, 无 ArkTS → Kotlin 回调。
 * - ShareController.show 需要 UIAbilityContext, 由 [initShareBridge] 从 EntryAbility 注入。
 * - 分享内容由 SharedRecord 描述: utd 为统一类型描述符 (纯文本 general.plain-text /
 *   文件按 mimeType 反查), 文本走 content, 文件走 uri。
 * - 文件 uri 用 fileUri.getUriFromPath 转换 (沙盒绝对路径 → file://<bundle>/... 可授权 URI),
 *   裸拼 'file://' + path 不带 bundle 段, 分享目标应用无法访问。
 * - KMP 侧桥未注册 (shareTsfn 未注入) 时降级写剪贴板 + toast (见 OhosPlatformServices)。
 */

interface SharePayload {
  action: string;
  text?: string;
  filePath?: string;
  mimeType?: string;
}

// 由 EntryAbility 注入的 UIAbilityContext (ShareController.show 必需)
let abilityContext: common.UIAbilityContext | undefined;

/**
 * 初始化分享桥 (由 EntryAbility.onCreate 调用, 注入 UIAbilityContext)。
 *
 * @param context UIAbility 上下文 (用于 ShareController.show 弹分享面板)
 */
export function initShareBridge(context: common.UIAbilityContext): void {
  abilityContext = context;
}

/**
 * 处理来自 Kotlin 的分享请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param json 请求 JSON 字符串, 形如 `{ action: 'text'|'file', text?, filePath?, mimeType? }`
 */
export function handleShareRequest(json: string): void {
  let payload: SharePayload;
  try {
    payload = JSON.parse(json) as SharePayload;
  } catch (e) {
    console.error('[SystemShareBridge] parse payload failed: ' + json);
    return;
  }

  if (payload.action === 'text') {
    shareText(payload.text ?? '');
  } else if (payload.action === 'file') {
    shareFile(payload.filePath ?? '', payload.mimeType ?? 'application/octet-stream');
  } else {
    console.warn('[SystemShareBridge] unknown action: ' + payload.action);
  }
}

/** 分享纯文本 (对照 Android Intent.ACTION_SEND text/plain)。 */
function shareText(text: string): void {
  if (text.length === 0) {
    console.warn('[SystemShareBridge] shareText: text is empty');
    return;
  }
  const record: systemShare.SharedRecord = {
    utd: uniformTypeDescriptor.UniformDataType.PLAIN_TEXT,
    content: text,
    title: '分享',
  };
  showPanel(record, 'text len=' + text.length);
}

/** 分享文件 (filePath 为沙盒内绝对路径, 转可授权 file URI 后交给 SharePanel)。 */
function shareFile(filePath: string, mimeType: string): void {
  if (filePath.length === 0) {
    console.warn('[SystemShareBridge] shareFile: filePath is empty');
    return;
  }
  let uri: string;
  try {
    uri = fileUri.getUriFromPath(filePath);
  } catch (e) {
    console.error('[SystemShareBridge] getUriFromPath failed: ' + JSON.stringify(e) + ', path=' + filePath);
    return;
  }
  const record: systemShare.SharedRecord = {
    utd: utdOfMimeType(mimeType),
    uri: uri,
    title: '分享文件',
  };
  showPanel(record, 'file=' + filePath + ', mime=' + mimeType);
}

/** MIME → 统一类型描述符; 未知 MIME 回退通用文件类型。 */
function utdOfMimeType(mimeType: string): string {
  try {
    const typeId = uniformTypeDescriptor.getUniformDataTypeByMIMEType(mimeType);
    if (typeId.length > 0) {
      return typeId;
    }
  } catch (e) {
    console.warn('[SystemShareBridge] unknown mimeType: ' + mimeType);
  }
  return uniformTypeDescriptor.UniformDataType.FILE;
}

/** 构造 SharedData + ShareController 并弹面板。 */
function showPanel(record: systemShare.SharedRecord, logDesc: string): void {
  if (abilityContext == null) {
    console.error('[SystemShareBridge] share failed: abilityContext not initialized, ' + logDesc);
    return;
  }
  try {
    const controller = new systemShare.ShareController(new systemShare.SharedData(record));
    const options: systemShare.ShareControllerOptions = {
      selectionMode: systemShare.SelectionMode.SINGLE,
      previewMode: systemShare.SharePreviewMode.DETAIL,
    };
    controller.show(abilityContext, options).then((): void => {
      console.info('[SystemShareBridge] share panel shown: ' + logDesc);
    }).catch((err: Error) => {
      console.error('[SystemShareBridge] share panel failed: ' + JSON.stringify(err) + ', ' + logDesc);
    });
  } catch (e) {
    console.error('[SystemShareBridge] share failed: ' + JSON.stringify(e) + ', ' + logDesc);
  }
}
