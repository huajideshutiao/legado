import systemShare from '@ohos.share.systemShare';

/**
 * ArkTS 侧系统分享处理器 (桥接 @ohos.share.systemShare)。
 *
 * 接收 Kotlin OhosNativeBridge.shareText / shareFile 通过 tsfn dispatch 过来的分享请求,
 * 调用 @ohos.share.systemShare.systemShare.show 弹系统分享面板 (SharePanel)。
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
 *   → [handleShareRequest] → systemShare.show(ShareData) 弹系统分享面板
 *
 * # 设计说明
 * - fire-and-forget (与 Toast 同模式): 分享面板由用户操作, 结果对调用方无意义, 无 ArkTS → Kotlin 回调。
 * - file 分享: 把沙盒内文件绝对路径转成 file:// URI (share 面板要求 uri 为可访问的文件 URI)。
 * - KMP 侧桥未注册 (shareTsfn 未注入) 时降级写剪贴板 + toast (见 OhosPlatformServices)。
 */

interface SharePayload {
  action: string;
  text?: string;
  filePath?: string;
  mimeType?: string;
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
  try {
    systemShare.show({
      title: '分享',
      text: text,
      mimeType: 'text/plain',
    });
    console.info('[SystemShareBridge] share text, len=' + text.length);
  } catch (e) {
    console.error('[SystemShareBridge] share text failed: ' + JSON.stringify(e));
  }
}

/** 分享文件 (filePath 为沙盒内绝对路径, 转 file:// URI 后交给 SharePanel)。 */
function shareFile(filePath: string, mimeType: string): void {
  if (filePath.length === 0) {
    console.warn('[SystemShareBridge] shareFile: filePath is empty');
    return;
  }
  try {
    systemShare.show({
      title: '分享文件',
      uri: 'file://' + filePath,
      mimeType: mimeType,
    });
    console.info('[SystemShareBridge] share file: ' + filePath + ', mime=' + mimeType);
  } catch (e) {
    console.error('[SystemShareBridge] share file failed: ' + JSON.stringify(e) + ', path=' + filePath);
  }
}
