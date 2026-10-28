import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import fileShare from '@ohos.fileshare';
import fileUri from '@ohos.file.fileuri';
import common from '@ohos.app.ability.common';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { BusinessError } from '@ohos.base';
import { BridgeRequest, BridgeResponse, dispatchBridgeRequest } from './BridgeDispatch';
import { ensurePermissionGranted } from './PermissionBridgeHandler';
import { arrayBufferToBase64, base64ToArrayBuffer } from './BridgeCodec';

/** 同一时刻只允许一个系统选择器: 旧调用真实结束 (含用户取消) 前不放行新调用。 */
let pickerOccupied = false;

/** 选择器串行队列: 当前调用真实回执后才推进下一个 (不是忙则拒绝)。 */
interface CancelPayload {
  requestId: number;
}

interface PendingPickerPick {
  requestJson: string;
  callback: FilePickerCallback;
}

const pickerQueue: PendingPickerPick[] = [];
let pickerBusy = false;

/** 本通道已受理、尚未回执的请求 id。 */
const pendingFilePickerIds: Set<number> = new Set<number>();
// 当前真正起跑的请求: 取消只撤排队项, 运行项保持 pending 直到 SDK 真实回执/通道排空
let runningPickerId: number = -1;
// 来源代次接纳: 低于 minValidGen 的队列项属于已失效源, 不得再启动
let maxSeenGen: number = 0;
let minValidGen: number = 0;
// 已被消费侧放弃的复制目录: 清理控制帧先到/后到都按具体目录生效, 不靠全局代次
const cancelledCopyDirs: Set<string> = new Set<string>();

/**
 * ArkTS 侧文件选择器处理器 (KP8+)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeFilePickerSync 通过 tsfn dispatch 过来的文件选择请求,
 * 调用 @ohos.file.picker.DocumentViewPicker (选文件) / @ohos.file.fs (读内容) 执行真实操作,
 * 完成后通过 legado.filePickerCallback(requestId, resultJson) 回送结果给 Kotlin。
 *
 * # JSON 协议 (与 OhosFilePicker.ohos.kt / OhosNativeBridge.kt 对齐)
 *
 * ## 请求 (Kotlin → ArkTS)
 * {
 *   "requestId": <number>,
 *   "action": "pickDocuments" | "pickDocumentContent" | "pickDirectory"
 *             | "activateDirectoryPermissions" | "saveImageToAlbum",
 *   "payload": "<JSON string, action-specific>"
 * }
 *
 * ## pickDocuments payload
 * {
 *   "fileSuffixes": ["json", "txt", ...],  // 扩展名 (不含点), 拼成 fileSuffixFilters
 *   "allowsMultiple": <boolean>
 * }
 *
 * ## pickDocumentContent payload
 * {
 *   "uri": "<string>"  // pickDocuments 返回的 URI
 * }
 *
 * ## pickDirectory payload
 * {}  // 无参数, maxSelectNumber 固定为 1
 *
 * ## 响应 (ArkTS → Kotlin)
 * - pickDocuments 成功: `{ ok: true, uris: ["<uri1>", "<uri2>", ...] }`
 * - pickDocuments 用户取消: `{ ok: true, cancelled: true }`
 * - pickDocumentContent 成功: `{ ok: true, data: "<base64>" }`
 * - pickDirectory 成功 (2in1/Tablet): `{ ok: true, uris: ["<dirUri>"], paths: ["<沙箱路径>"] }`
 *   paths 为 fileUri.FileUri(uri).path 官方转换结果 (与 uris 一一对应);
 *   授权已持久化 (persistPermission READ_MODE | WRITE_MODE), 持久化失败 →
 *   `{ ok: false, code, error, policyErrors? }`, 不返回路径
 * - pickDirectory (Phone/无目录能力): `{ ok: false, code: 801 }` (不把单文件父目录当目录授权)
 * - pickDirectory 用户取消: `{ ok: true, cancelled: true }`
 * - saveImageToAlbum 成功: `{ ok: true, uris: ["<图库资源 URI>"] }`
 * - activateDirectoryPermissions 成功: `{ ok: true, uris: [已激活的 uri] }`
 * - activateDirectoryPermissions 部分失败: `{ ok: false, code: 13900001,
 *   policyErrors: [{uri, code, message}], uris: [成功子集] }`
 * - 失败: `{ ok: false, code?: <BusinessError.code>, error: "<string>" }`
 *
 * # 后缀过滤
 * KMP 调用方传扩展名列表 (如 ["txt","epub"]), 鸿蒙 DocumentViewPicker 用文件后缀过滤
 * (fileSuffixFilters, 无 MIME 过滤能力), 此处拼成后缀项; 列表为空时不设 filter
 * (显示所有文件)。
 *
 * # 用户取消识别
 * 用户取消时 DocumentViewPicker.select 以空数组返回 (官方 Promise<Array<string>> 语义),
 * 识别为 cancelled: true 而非 error, 与 iOS documentPickerDidCancel 语义对齐
 * (调用方据此返回 null 而非抛异常)。非空返回后的抛错 (如 13900020 Invalid argument /
 * 13900042 Unknown error) 是真实失败, 原样带 code 回传, 不当作取消。
 */

// FilePicker callback 类型 (由 EntryAbility 注入, 内部调 legado.filePickerCallback)
export type FilePickerCallback = (requestId: number, resultJson: string) => void;

// UIAbilityContext (saveImageToAlbum 的相册权限与 photoAccessHelper 需要; 由 EntryAbility 注入)
let abilityContext: common.UIAbilityContext | undefined;

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface FilePickerResponse extends BridgeResponse {
  uris?: string[];
  cancelled?: boolean;
  data?: string;
  // pickDirectory: 与 uris 一一对应的沙箱路径 (fileUri.FileUri(uri).path 官方转换; Phone 分支不转换)
  paths?: string[];
  // BusinessError.code (801=Capability not supported, 13900001=Operation not permitted 等)
  code?: number;
  // 13900001 部分失败明细 (fileShare.PolicyErrorResult: uri/code/message)
  policyErrors?: PolicyErrorItem[];
}

interface PolicyErrorItem {
  uri: string;
  code: number;
  message: string;
}

interface ActivatePermissionsPayload {
  uris: string[];
}

interface PickDocumentsPayload {
  fileSuffixes: string[];
  allowsMultiple: boolean;
}

interface CopyDocumentPayload {
  uri: string;
  destPath: string;
  destDir: string;
}

interface PickDocumentContentPayload {
  uri: string;
}

interface PickDirectoryPayload {
  // 无参数 (maxSelectNumber 固定为 1)
}

interface SaveImageToAlbumPayload {
  extension: string; // 图片扩展名 (不含点), KMP 按字节魔数判定
  data: string; // base64 编码的图片字节
}

// ===== 后缀过滤项拼装 =====
// 鸿蒙 DocumentSelectOptions 用 fileSuffixFilters (后缀数组) 过滤, 不接受 MIME;
// 每项形如 '.txt' 或 '说明|.txt,.log' (竖线前为描述, 逗号分隔多后缀)。
function toSuffixFilters(fileSuffixes: string[]): string[] {
  const filters = new Set<string>();
  for (const suffix of fileSuffixes) {
    const trimmed = suffix.trim().replace(/^\.+/, '');
    if (trimmed.length > 0) {
      filters.add('.' + trimmed.toLowerCase());
    }
  }
  return Array.from(filters);
}

/**
 * 初始化文件桥 (由 EntryAbility.onCreate 调用, 注入 UIAbilityContext)。
 *
 * @param context UIAbility 上下文 (saveImageToAlbum 的相册权限申请与 photoAccessHelper 需要)
 */
export function initFilePickerBridge(context: common.UIAbilityContext): void {
  abilityContext = context;
}

/**
 * 处理来自 Kotlin 的文件选择请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.filePickerCallback)
 */
function generationOf(requestJson: string): number {
  try {
    const gen = (JSON.parse(requestJson) as BridgeRequest).gen;
    return gen == null ? 0 : gen;
  } catch (e) {
    return 0;
  }
}

function requestIdOf(requestJson: string): number {
  try {
    return (JSON.parse(requestJson) as BridgeRequest).requestId;
  } catch (e) {
    return -1;
  }
}

/** Kotlin 侧请求 Job 取消: 撤掉排队项并注销 pending, 运行项等 SDK 真实结束。 */
function cancelQueued(targetId: number): void {
  let queuedRemoved = false;
  for (let i = pickerQueue.length - 1; i >= 0; i--) {
    if (requestIdOf(pickerQueue[i].requestJson) === targetId) {
      const removed = pickerQueue.splice(i, 1)[0];
      queuedRemoved = true;
      // 排队项从未起跑: 回执失败, Kotlin 才能清掉 pending 登记
      removed.callback(targetId, JSON.stringify({
        ok: false,
        cancelled: true,
        error: '页面已取消'
      }));
    }
  }
  if (queuedRemoved) {
    pendingFilePickerIds.delete(targetId);
  } else if (runningPickerId !== targetId) {
    // 既不在队列也不是运行项: 残留登记直接清理
    pendingFilePickerIds.delete(targetId);
  }
  // 运行项: pending 保留, 等 SDK 真实回执或窗口 drain 显式结束
}

export function handleFilePickerRequest(requestJson: string, callback: FilePickerCallback): void {
  const requestId = requestIdOf(requestJson);
  if (requestId < 0) {
    // 解析失败由 dispatchBridgeRequest 统一记日志
    dispatchBridgeRequest(requestJson, 'FilePickerBridge', handleAction, callback);
    return;
  }
  // 取消控制请求: 不登记 pending, 不进队列
  let action = '';
  try {
    action = (JSON.parse(requestJson) as BridgeRequest).action;
  } catch (e) {
    action = '';
  }
  if (action === 'invalidateSource') {
    // 重绑: 新源代次已发布, 旧源队列项不再启动
    const gen = generationOf(requestJson);
    if (gen > maxSeenGen) maxSeenGen = gen;
    if (gen > minValidGen) minValidGen = gen;
    return;
  }
  if (action === 'cancel') {
    let targetId = -1;
    try {
      const payloadJson = (JSON.parse(requestJson) as BridgeRequest).payload ?? '{}';
      targetId = (JSON.parse(payloadJson) as CancelPayload).requestId;
    } catch (e) {
      targetId = -1;
    }
    if (targetId >= 0) cancelQueued(targetId);
    return;
  }
  const requestGen = generationOf(requestJson);
  if (requestGen > maxSeenGen) maxSeenGen = requestGen;
  // 接纳即登记: 排队中的请求也属于本通道, 关闭时必须一并列出。
  pendingFilePickerIds.add(requestId);
  if (pickerBusy) {
    const queued: PendingPickerPick = { requestJson: requestJson, callback: callback };
    pickerQueue.push(queued);
    return;
  }
  runPickerRequest(requestJson, callback, requestId);
}

function startNextPicker(): void {
  pickerBusy = false;
  runningPickerId = -1;
  const next: PendingPickerPick | undefined = pickerQueue.shift();
  if (next != null) {
    runPickerRequest(next.requestJson, next.callback, requestIdOf(next.requestJson));
  }
}

function runPickerRequest(requestJson: string, callback: FilePickerCallback, requestId: number): void {
  const requestGen = generationOf(requestJson);
  let action = '';
  try { action = (JSON.parse(requestJson) as BridgeRequest).action; } catch (e) { action = ''; }
  if (action !== 'cleanupDir' && requestGen < minValidGen) {
    // 已在 ETS 队列但所属源已失效: 不启动过期输入/复制; 复制目标按本次目录清理
    pendingFilePickerIds.delete(requestId);
    if (action === 'copyDocumentTo') {
      try {
        const p = JSON.parse((JSON.parse(requestJson) as BridgeRequest).payload ?? '{}') as CopyDocumentPayload;
        cleanupCopyTarget(p);
      } catch (e) {
        console.error('[FilePickerBridge] stale copy cleanup parse failed: ' + JSON.stringify(e));
      }
    }
    callback(requestId, JSON.stringify({ ok: false, cancelled: true, error: '来源已失效' }));
    startNextPicker();
    return;
  }
  pickerBusy = true;
  runningPickerId = requestId;
  dispatchBridgeRequest(requestJson, 'FilePickerBridge', handleAction,
    (id: number, resultJson: string): void => {
      pendingFilePickerIds.delete(id);
      runningPickerId = -1;
      try {
        callback(id, resultJson);
      } finally {
        startNextPicker();
      }
    });
}

/** 窗口销毁: 当前已见源全部失效; 新注册的更高代次请求仍可被接纳。 */
export function invalidateFilePickerSource(gen?: number): void {
  if (gen != null) {
    // 注册边界同步传入真实新代次: 旧队列项立即失效, 不依赖后续控制帧调度
    if (gen > maxSeenGen) maxSeenGen = gen;
    if (gen > minValidGen) minValidGen = gen;
    return;
  }
  minValidGen = maxSeenGen + 1;
}

/** 本通道仍在等待回执的请求 id: 通道关闭时随 0 号回执发出, 只结束本通道的请求。 */
export function pendingFilePickerRequestIds(): number[] {
  const ids: number[] = [];
  pendingFilePickerIds.forEach((id: number): void => {
    ids.push(id);
  });
  return ids;
}

/**
 * 通道关闭: 返回本通道全部未回执 id 并清空队列。
 * 排队项不再启动 (Kotlin 已按 id 结束等待), 运行项等其真实回调收尾。
 */
export function drainFilePickerRequests(): number[] {
  const ids: number[] = [];
  pendingFilePickerIds.forEach((id: number): void => {
    ids.push(id);
  });
  pendingFilePickerIds.clear();
  pickerQueue.splice(0, pickerQueue.length);
  return ids;
}

// 根据 action 分发到具体处理函数
async function handleAction(req: BridgeRequest): Promise<FilePickerResponse> {
  switch (req.action) {
    case 'pickDocuments': return await handlePickDocuments(req.payload ?? '');
    case 'pickDocumentContent': return await handlePickDocumentContent(req.payload ?? '');
    case 'copyDocumentTo': return await handleCopyDocumentTo(req.payload ?? '', req.gen ?? 0);
    case 'cleanupDir': {
      const p = JSON.parse(req.payload ?? '{}') as CopyDocumentPayload;
      return { ok: requestCopyCleanup(p) };
    }
    case 'pickDirectory': return await handlePickDirectory(req.payload ?? '');
    case 'activateDirectoryPermissions': return await handleActivateDirectoryPermissions(req.payload ?? '');
    case 'saveImageToAlbum': return await handleSaveImageToAlbum(req.payload ?? '');
    default: return { ok: false, error: 'unknown action: ' + req.action };
  }
}

// ===== pickDocuments: 调 DocumentViewPicker 选文件, 返回 URI 列表 =====
async function handlePickDocuments(payload: string): Promise<FilePickerResponse> {
  const p = JSON.parse(payload) as PickDocumentsPayload;

  // 扩展名 → 后缀过滤项 (去重)
  const suffixFilters = toSuffixFilters(p.fileSuffixes ?? []);

  const documentSelectOptions = new picker.DocumentSelectOptions();
  // maxSelectNumber: 鸿蒙默认 1, 多选时设较大值 (与 iOS allowsMultipleSelection 语义对齐)
  documentSelectOptions.maxSelectNumber = p.allowsMultiple ? 100 : 1;
  // fileSuffixFilters: 仅当有过滤项时设置 (不设表示不限制, 显示所有文件)
  if (suffixFilters.length > 0) {
    documentSelectOptions.fileSuffixFilters = suffixFilters;
  }

  const documentViewPicker = new picker.DocumentViewPicker();
  if (pickerOccupied) {
    return { ok: false, error: '选择器正在使用中，请稍候' };
  }
  pickerOccupied = true;
  try {
    const uris: string[] = await documentViewPicker.select(documentSelectOptions);
    if (uris == null || uris.length === 0) {
      // 用户取消: select 以空数组返回
      return { ok: true, cancelled: true };
    }
    return { ok: true, uris: uris };
  } catch (e) {
    // 真实失败 (权限/文件/参数/未知): 带 code 回传, 由调用方如实报错
    const err = e as BusinessError;
    return { ok: false, code: err.code, error: err.message ?? String(e) };
  } finally {
    pickerOccupied = false;
  }
}

// ===== copyDocumentTo: 选中的 URI 以 FD 异步复制到调用方给的沙箱目标, 不整份进内存 =====
// 目录由本侧在真正起跑时创建并拥有: 未到达 ETS 的投递失败不会留下目录;
// 失败或通道代次变化时在 FD 关闭后 unlink+rmdir (sync 真实完成) 再回执。
function cleanupCopyTarget(p: CopyDocumentPayload): boolean {
  let ok = true;
  try {
    fs.unlinkSync(p.destPath);
  } catch (e) {
    const err = e as BusinessError;
    // 13900002 = 文件不存在: 已无目标可删, 视为完成; 其余真实失败必须记录并回执失败
    if (err.code !== 13900002) {
      ok = false;
      console.error('[FilePickerBridge] cleanup unlink failed: ' + JSON.stringify(e));
    }
  }
  try {
    fs.rmdirSync(p.destDir);
  } catch (e) {
    const err = e as BusinessError;
    if (err.code !== 13900002) {
      ok = false;
      console.error('[FilePickerBridge] cleanup rmdir failed: ' + JSON.stringify(e));
    }
  }
  return ok;
}

/** 队列里是否还有该目录的 copy 尚未起跑 (防止清理先到、副本后跑)。 */
function hasQueuedCopy(destDir: string): boolean {
  for (const item of pickerQueue) {
    try {
      const req = JSON.parse(item.requestJson) as BridgeRequest;
      if (req.action === 'copyDocumentTo') {
        const copy = JSON.parse(req.payload ?? '{}') as CopyDocumentPayload;
        if (copy.destDir === destDir) return true;
      }
    } catch (e) {
      // 解析失败由正常分发处理
    }
  }
  return false;
}

/** 消费侧要求回收某次复制目标: 清理与复制同队列串行, 到达时 IO 已终结; 仅排队副本需要防迟起跑。 */
function requestCopyCleanup(p: CopyDocumentPayload): boolean {
  if (hasQueuedCopy(p.destDir)) cancelledCopyDirs.add(p.destDir);
  return cleanupCopyTarget(p);
}

async function handleCopyDocumentTo(payload: string, requestGen: number): Promise<FilePickerResponse> {
  if (!canIUse('SystemCapability.FileManagement.File.FileIO')) {
    return { ok: false, code: 801, error: '设备不支持文件 IO 能力' };
  }
  const p = JSON.parse(payload) as CopyDocumentPayload;
  let src: fs.File | undefined;
  let dst: fs.File | undefined;
  let created = false;
  let success = false;
  if (cancelledCopyDirs.has(p.destDir)) {
    // 清理帧先到: 该作业已被消费侧放弃, 不再起跑/创建目录
    cancelledCopyDirs.delete(p.destDir);
    return { ok: false, cancelled: true };
  }
  try {
    // 递归创建父目录: 冷启动/清缓存后 cacheDir/filePicker 可能不存在
    fs.mkdirSync(p.destDir, true);
    created = true;
    src = fs.openSync(p.uri, fs.OpenMode.READ_ONLY);
    dst = fs.openSync(p.destPath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
    // SDK 原生异步复制 (fd → fd): 大文件不经过 ArrayBuffer/base64
    await fs.copyFile(src.fd, dst.fd);
    success = true;
    return { ok: true };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, code: err.code, error: err.message ?? String(e) };
  } finally {
    if (src != null) {
      try { fs.closeSync(src); } catch (e) { console.warn('[FilePickerBridge] close src failed: ' + JSON.stringify(e)); }
    }
    if (dst != null) {
      try { fs.closeSync(dst); } catch (e) { console.warn('[FilePickerBridge] close dst failed: ' + JSON.stringify(e)); }
    }
    // FD 已关闭: 失败删; 成功但源已失效 (关窗/重绑) 也删; 当前源成功才留给消费侧
    if (created && (!success || requestGen < minValidGen)) {
      cleanupCopyTarget(p);
    }
  }
}

// ===== pickDocumentContent: 调 fs.openSync/readSync 读文件字节, base64 编码返回 =====
async function handlePickDocumentContent(payload: string): Promise<FilePickerResponse> {
  if (!canIUse('SystemCapability.FileManagement.File.FileIO')) {
    return { ok: false, code: 801, error: '设备不支持文件 IO 能力' };
  }
  const p = JSON.parse(payload) as PickDocumentContentPayload;

  let file: fs.File | undefined;
  try {
    // 以只读模式打开 URI 对应的文件 (DocumentViewPicker 返回的 URI 可直接用 fs.openSync 打开)
    file = fs.openSync(p.uri, fs.OpenMode.READ_ONLY);
    const stat = fs.statSync(file.fd);
    const size = stat.size;

    if (size <= 0) {
      // 空文件: 返回空 base64 (调用方解码得到 ByteArray(0))
      return { ok: true, data: '' };
    }

    // 逐段读到 size 字节 (不传 offset: 由文件指针自然递增): 短读 (实读少于请求)
    // 继续读剩余部分, 读到 0 字节即文件比 stat 短, 作为真实失败回传, 不静默截断
    const arrayBuffer = new ArrayBuffer(size);
    const view = new Uint8Array(arrayBuffer);
    let readBytes = 0;
    while (readBytes < size) {
      const chunk = new ArrayBuffer(size - readBytes);
      const readLen = fs.readSync(file.fd, chunk);
      if (readLen <= 0) {
        return { ok: false, error: 'read stopped at ' + readBytes + '/' + size + ' bytes' };
      }
      view.set(new Uint8Array(chunk, 0, readLen), readBytes);
      readBytes += readLen;
    }
    // ArrayBuffer → base64 字符串 (与 ImageBridgeHandler.encode / CryptoBridgeHandler 一致)
    const base64 = arrayBufferToBase64(arrayBuffer);
    return { ok: true, data: base64 };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, code: err.code, error: err.message ?? String(e) };
  } finally {
    if (file != null) {
      try {
        fs.closeSync(file);
      } catch (e) {
        // close 失败不影响已读取结果, 仅记录日志
        console.warn('[FilePickerBridge] closeSync failed: ' + JSON.stringify(e));
      }
    }
  }
}

// FolderSelection: 选目录能力 (2in1/Tablet); FolderAuthorization: 授权持久化能力
// (fileShare.persistPermission/activatePermission, 官方 file-persistPermission.md
// 指定用 canIUse 检测该 syscap)
const FOLDER_SELECTION_SYSCAP = 'SystemCapability.FileManagement.UserFileService.FolderSelection';
const FOLDER_AUTHORIZATION_SYSCAP = 'SystemCapability.FileManagement.AppFileService.FolderAuthorization';

// ===== pickDirectory: 选目录 (仅 2in1/Tablet) =====
//
// 目录选择是 2in1/Tablet 能力 (syscap UserFileService.FolderSelection),
// 官方设备 syscap 清单里 Phone 无该能力, 也无目录授权类 API。
// 无能力时明确拒绝 (ok:false, code:801), 由调用方改走单文件导入;
// 不把“某个文件的父目录”登记成可长期访问的目录。
async function handlePickDirectory(payload: string): Promise<FilePickerResponse> {
  if (canIUse(FOLDER_SELECTION_SYSCAP)) {
    return await pickDirectoryNative();
  }
  return await pickDirectoryViaFileParent();
}

/**
 * 2in1/Tablet: 原生选目录 (selectMode=FOLDER) + 授权持久化。
 *
 * select 返回的 URI 只有临时授权 (官方 select-user-file.md: "待退出应用后台后，获取的临时权限
 * 就会失效"), 选完目录后立即 fileShare.persistPermission 持久化读写模式; 持久化失败即本次
 * 选择失败 (ok:false, 不返回路径 —— 不允许"本次临时授权"被当长期授权)。
 * 重启后由 Kotlin 侧经 activateDirectoryPermissions 按需激活。
 */
async function pickDirectoryNative(): Promise<FilePickerResponse> {
  if (!canIUse(FOLDER_SELECTION_SYSCAP)) {
    return await pickDirectoryViaFileParent();
  }
  // 不传 fileSuffixFilters (允许用户导航文件系统选择目录), maxSelectNumber=1 限定单选;
  // 必须显式选目录模式, 默认 FILE 会选文件而非目录
  const documentSelectOptions = new picker.DocumentSelectOptions();
  documentSelectOptions.maxSelectNumber = 1;
  documentSelectOptions.selectMode = picker.DocumentSelectMode.FOLDER;
  const documentViewPicker = new picker.DocumentViewPicker();
  if (pickerOccupied) {
    return { ok: false, error: '选择器正在使用中，请稍候' };
  }
  pickerOccupied = true;
  try {
    const uris: string[] = await documentViewPicker.select(documentSelectOptions);
    if (uris == null || uris.length === 0) {
      // 用户取消: select 以空数组返回
      return { ok: true, cancelled: true };
    }
    return await persistAndConvert(uris[0]);
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, code: err.code, error: err.message ?? String(e) };
  } finally {
    pickerOccupied = false;
  }
}

/**
 * 选定目录后: 持久化 (READ_MODE | WRITE_MODE, 失败即本次选择失败) → fileUri 官方转换沙箱路径。
 * 转换不做路径校验 (官方约定), 可访问性由 Kotlin 侧真实检查。
 *
 * 该选择器被多个入口共用 (书籍保存位置/导入目录/单文件导出目录/图片保存目录), 其中导出、
 * 书籍保存、图片保存都要往选定目录里写文件; 官方《授权持久化》写明 Picker 选文件或文件夹
 * 得到的授权是"临时读写权限", PolicyInfo.operationMode 可组合 READ_MODE | WRITE_MODE
 * (@ohos.fileshare.d.ts 的取值说明同), 故按读写一起持久化 —— 只持久化 READ_MODE 会让
 * 重启后激活到只读, 之后的写入被拒。
 */
async function persistAndConvert(dirUri: string): Promise<FilePickerResponse> {
  if (!canIUse(FOLDER_AUTHORIZATION_SYSCAP)) {
    return { ok: false, code: 801, error: 'FolderAuthorization not supported on this device' };
  }
  const policy: fileShare.PolicyInfo = {
    uri: dirUri,
    operationMode: fileShare.OperationMode.READ_MODE | fileShare.OperationMode.WRITE_MODE,
  };
  try {
    await fileShare.persistPermission([policy]);
  } catch (e) {
    const err = e as BusinessError<Array<fileShare.PolicyErrorResult>>;
    if (err.code === 13900001 && err.data) {
      return {
        ok: false,
        code: err.code,
        policyErrors: err.data.map((d): PolicyErrorItem => ({ uri: d.uri, code: d.code, message: String(d.message ?? '') })),
        error: err.message ?? String(e),
      };
    }
    return { ok: false, code: err.code, error: err.message ?? String(e) };
  }
  // URI → 沙箱路径: fileUri.FileUri(uri).path 是官方转换 API (系统约定字符串替换,
  // 不做路径校验, 不保证可访问), 可访问性由 Kotlin 侧真实检查
  try {
    const path: string = new fileUri.FileUri(dirUri).path;
    return { ok: true, uris: [dirUri], paths: [path] };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, code: err.code, error: err.message ?? String(e) };
  }
}

/**
 * 无目录选择能力的设备: 明确拒绝, 不再用“选文件取父目录”伪造目录授权。
 * 调用方 (Kotlin) 收到 801 后提示用户改用文件导入。
 */
async function pickDirectoryViaFileParent(): Promise<FilePickerResponse> {
  // 单文件父目录不是目录授权: 设备无目录选择能力时明确拒绝, 调用方改走文件导入,
  // 不能把某个文件的父目录登记成可长期访问的目录。
  return { ok: false, code: 801, error: 'Directory picking is not supported on this device' };
}

/**
 * 激活已持久化的目录授权 (重启后按需调用, 官方 file-persistPermission.md:
 * 持久化授权存系统数据库, 应用/设备重启后须 activatePermission 才能使用)。
 *
 * 部分失败 (13900001) 时 policyErrors 携带逐条失败明细 (fileShare.PolicyErrorResult),
 * 响应 uris 返回成功子集, 由 Kotlin 侧区分处理。
 */
async function handleActivateDirectoryPermissions(payload: string): Promise<FilePickerResponse> {
  const p = JSON.parse(payload) as ActivatePermissionsPayload;
  if (p.uris == null || p.uris.length === 0) {
    return { ok: false, error: 'no uris to activate' };
  }
  if (!canIUse(FOLDER_AUTHORIZATION_SYSCAP)) {
    return { ok: false, code: 801, error: 'FolderAuthorization not supported on this device' };
  }
  const policies: fileShare.PolicyInfo[] = p.uris.map((uri): fileShare.PolicyInfo => ({
    uri: uri,
    // 与持久化同策略 (读写): 激活只到读权限时, 写入选定目录的入口仍会被拒
    operationMode: fileShare.OperationMode.READ_MODE | fileShare.OperationMode.WRITE_MODE,
  }));
  try {
    await fileShare.activatePermission(policies);
    return { ok: true, uris: p.uris };
  } catch (e) {
    const err = e as BusinessError<Array<fileShare.PolicyErrorResult>>;
    if (err.code === 13900001 && err.data) {
      const failed = new Set<string>(err.data.map(d => d.uri));
      return {
        ok: false,
        code: err.code,
        policyErrors: err.data.map((d): PolicyErrorItem => ({ uri: d.uri, code: d.code, message: String(d.message ?? '') })),
        uris: p.uris.filter(u => !failed.has(u)),
      };
    }
    return { ok: false, code: err.code, error: err.message ?? String(e) };
  }
}

/**
 * 取文件 URI 的父目录 URI (去掉最后一段路径)。
 *
 * 只按 '/' 分段, 不解析 file:// authority: 父目录 URI 仍交给下游 fileIo 按同一 URI 方案访问,
 * 保持与原生选目录返回值同形。
 *
 * @returns 父目录 URI; URI 无可去尾段时返回 null
 */
function parentUriOf(uri: string): string | null {
  const idx = uri.lastIndexOf('/');
  if (idx <= 0) {
    return null;
  }
  const parent = uri.substring(0, idx);
  // file://docs (仅剩 scheme+authority) 不是可用目录
  return parent.includes('://') && parent.indexOf('/', parent.indexOf('://') + 3) < 0 ? null : parent;
}

// ===== saveImageToAlbum: photoAccessHelper.createAsset 建图库资源, fs.write 写入字节 =====
// 相册写入只有 ArkTS 有 API (photoAccessHelper), 故 KMP 把字节交到这里落盘;
// ohos.permission.WRITE_IMAGEVIDEO 是 user_grant (module.json5 已声明), 首次保存时弹窗申请。
async function handleSaveImageToAlbum(payload: string): Promise<FilePickerResponse> {
  const p = JSON.parse(payload) as SaveImageToAlbumPayload;
  if (abilityContext == null) {
    return { ok: false, error: 'abilityContext not initialized' };
  }
  const granted = await ensurePermissionGranted(abilityContext, 'ohos.permission.WRITE_IMAGEVIDEO');
  if (!granted) {
    return { ok: false, error: 'WRITE_IMAGEVIDEO not granted' };
  }
  let file: fs.File | undefined;
  let helper: photoAccessHelper.PhotoAccessHelper | undefined;
  try {
    // createAsset 返回的 URI 已指向图库中的新文件, 无需沙盒中转
    helper = photoAccessHelper.getPhotoAccessHelper(abilityContext);
    const uri = await helper.createAsset(photoAccessHelper.PhotoType.IMAGE, p.extension);
    file = fs.openSync(uri, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE);
    // 逐段写到全部字节落地 (不传 offset: 由文件指针自然递增); 未写完即真实失败
    const bytes = base64ToArrayBuffer(p.data);
    let written = 0;
    while (written < bytes.byteLength) {
      const rest = bytes.slice(written);
      const writeLen = fs.writeSync(file.fd, rest);
      if (writeLen <= 0) {
        return { ok: false, error: 'write stopped at ' + written + '/' + bytes.byteLength + ' bytes' };
      }
      written += writeLen;
    }
    return { ok: true, uris: [uri] };
  } catch (e) {
    console.error('[FilePickerBridge] saveImageToAlbum failed: ' + JSON.stringify(e));
    return { ok: false, error: (e as BusinessError).message ?? String(e) };
  } finally {
    if (file != null) {
      try {
        fs.closeSync(file);
      } catch (e) {
        console.warn('[FilePickerBridge] closeSync failed: ' + JSON.stringify(e));
      }
    }
    if (helper != null) {
      try {
        // SDK 要求实例不再使用时 release; 已落相册结果不因清理失败冒充未保存
        await helper.release();
      } catch (e) {
        console.warn('[FilePickerBridge] photoAccessHelper.release failed: ' + JSON.stringify(e));
      }
    }
  }
}
