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
 *   "action": "pickDocuments" | "pickDocumentContent" | "pickImages" | "pickDirectory"
 *             | "activateDirectoryPermissions" | "saveDocument" | "saveImageToAlbum",
 *   "payload": "<JSON string, action-specific>"
 * }
 *
 * ## pickDocuments payload
 * {
 *   "contentTypes": ["public.json", "public.text", ...],  // iOS UTI 风格, 内部映射到 MIME
 *   "allowsMultiple": <boolean>
 * }
 *
 * ## pickDocumentContent payload
 * {
 *   "uri": "<string>"  // pickDocuments 返回的 URI
 * }
 *
 * ## pickImages payload
 * {
 *   "contentTypes": ["public.image", "public.jpeg", ...],  // iOS UTI 风格, 内部映射到 PhotoSelectOptions.MIMEType
 *   "allowsMultiple": <boolean>
 * }
 *
 * ## pickDirectory payload
 * {}  // 无参数, maxSelectNumber 固定为 1
 *
 * ## 响应 (ArkTS → Kotlin)
 * - pickDocuments 成功: `{ ok: true, uris: ["<uri1>", "<uri2>", ...] }`
 * - pickDocuments 用户取消: `{ ok: true, cancelled: true }`
 * - pickDocumentContent 成功: `{ ok: true, data: "<base64>" }`
 * - pickImages 成功: `{ ok: true, uris: ["<uri1>", "<uri2>", ...] }`
 * - pickImages 用户取消: `{ ok: true, cancelled: true }`
 * - pickDirectory 成功 (2in1/Tablet): `{ ok: true, uris: ["<dirUri>"], paths: ["<沙箱路径>"] }`
 *   paths 为 fileUri.FileUri(uri).path 官方转换结果 (与 uris 一一对应);
 *   授权已持久化 (persistPermission READ_MODE, select 只授临时只读权限),
 *   持久化失败 → `{ ok: false, code, error, policyErrors? }`, 不返回路径
 * - pickDirectory 成功 (Phone): `{ ok: true, uris: ["<父目录 URI>"] }` (无 paths, 原行为)
 * - pickDirectory 用户取消: `{ ok: true, cancelled: true }`
 * - saveImageToAlbum 成功: `{ ok: true, uris: ["<图库资源 URI>"] }`
 * - activateDirectoryPermissions 成功: `{ ok: true, uris: [已激活的 uri] }`
 * - activateDirectoryPermissions 部分失败: `{ ok: false, code: 13900001,
 *   policyErrors: [{uri, code, message}], uris: [成功子集] }`
 * - 失败: `{ ok: false, code?: <BusinessError.code>, error: "<string>" }`
 *
 * # UTI → 后缀映射
 * KMP 调用方 (对照 iOS pickDocuments) 传入 iOS UTI 字符串 (如 "public.json"),
 * 鸿蒙 DocumentViewPicker 用文件后缀过滤 (fileSuffixFilters, 无 MIME 过滤能力), 内部做映射;
 * 未知 UTI 不传 filter (显示所有文件, 与 iOS "允许选择任意文件" 语义一致)。
 * 选图 (PhotoViewPicker) 只有 image/video/全部 三档 (PhotoViewMIMETypes), 无逐格式过滤。
 *
 * # 用户取消识别
 * DocumentViewPicker.select 在用户取消时抛出 BusinessError, code 13900042 (ENOENT) /
 * 14000011 (INVALID_INPUT) 等为常见取消码, 识别为 cancelled: true 而非 error,
 * 与 iOS documentPickerDidCancel 语义对齐 (调用方据此返回 null 而非抛异常)。
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
  contentTypes: string[];
  allowsMultiple: boolean;
}

interface PickDocumentContentPayload {
  uri: string;
}

interface PickImagesPayload {
  contentTypes: string[];
  allowsMultiple: boolean;
}

interface PickDirectoryPayload {
  // 无参数 (maxSelectNumber 固定为 1)
}

interface SaveDocumentPayload {
  fileName: string;
  data: string; // base64 编码的文件内容
}

interface SaveImageToAlbumPayload {
  extension: string; // 图片扩展名 (不含点), KMP 按字节魔数判定
  data: string; // base64 编码的图片字节
}

// ===== UTI → 文件后缀映射表 (对照 iOS UTI 常用值) =====
// 鸿蒙 DocumentSelectOptions 用 fileSuffixFilters (后缀数组) 过滤, 不接受 MIME;
// 每项形如 '.txt' 或 '说明|.txt,.log' (竖线前为描述, 逗号分隔多后缀);
// 未知 UTI 不产生过滤项, 全部未命中时不设 filter (显示所有文件)。
const UTI_TO_SUFFIX: Record<string, string> = {
  'public.json': '.json',
  'public.text': '.txt,.log,.md',
  'public.plain-text': '.txt,.log,.md',
  'public.html': '.html,.htm',
  'public.xml': '.xml',
  'public.source': '.txt',
  'public.image': '.png,.jpg,.jpeg,.bmp,.gif,.webp',
  'public.jpeg': '.jpg,.jpeg',
  'public.png': '.png',
  'public.bmp': '.bmp',
  'public.gif': '.gif',
  'public.zip-archive': '.zip',
  'org.idpf.epub-container': '.epub',
};

// ===== UTI → 图片 MIME 类别 (PhotoSelectOptions.MIMEType 只接受 PhotoViewMIMETypes 枚举) =====
// 本桥只用于选图, 故所有图片类 UTI 统一落到 IMAGE_TYPE。

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
export function handleFilePickerRequest(requestJson: string, callback: FilePickerCallback): void {
  dispatchBridgeRequest(requestJson, 'FilePickerBridge', handleAction, callback);
}

// 根据 action 分发到具体处理函数
async function handleAction(req: BridgeRequest): Promise<FilePickerResponse> {
  switch (req.action) {
    case 'pickDocuments': return await handlePickDocuments(req.payload ?? '');
    case 'pickDocumentContent': return await handlePickDocumentContent(req.payload ?? '');
    case 'pickImages': return await handlePickImages(req.payload ?? '');
    case 'pickDirectory': return await handlePickDirectory(req.payload ?? '');
    case 'activateDirectoryPermissions': return await handleActivateDirectoryPermissions(req.payload ?? '');
    case 'saveDocument': return await handleSaveDocument(req.payload ?? '');
    case 'saveImageToAlbum': return await handleSaveImageToAlbum(req.payload ?? '');
    default: return { ok: false, error: 'unknown action: ' + req.action };
  }
}

// ===== pickDocuments: 调 DocumentViewPicker 选文件, 返回 URI 列表 =====
async function handlePickDocuments(payload: string): Promise<FilePickerResponse> {
  const p = JSON.parse(payload) as PickDocumentsPayload;

  // UTI → 后缀过滤项 (去重, 丢弃未知 UTI)
  const suffixSet = new Set<string>();
  for (const uti of p.contentTypes) {
    const suffix = UTI_TO_SUFFIX[uti];
    if (suffix != null) {
      suffixSet.add(suffix);
    }
  }

  const documentSelectOptions = new picker.DocumentSelectOptions();
  // maxSelectNumber: 鸿蒙默认 1, 多选时设较大值 (与 iOS allowsMultipleSelection 语义对齐)
  documentSelectOptions.maxSelectNumber = p.allowsMultiple ? 100 : 1;
  // fileSuffixFilters: 仅当有可映射后缀时设置 (不设表示不限制, 显示所有文件)
  if (suffixSet.size > 0) {
    documentSelectOptions.fileSuffixFilters = Array.from(suffixSet);
  }

  const documentViewPicker = new picker.DocumentViewPicker();
  try {
    const uris: string[] = await documentViewPicker.select(documentSelectOptions);
    if (uris == null || uris.length === 0) {
      // select 返回空数组视为取消 (理论上不会发生, 取消会抛异常)
      return { ok: true, cancelled: true };
    }
    return { ok: true, uris: uris };
  } catch (e) {
    // 用户取消: 鸿蒙 DocumentViewPicker.select 在用户取消时抛 BusinessError
    // 常见取消码: 13900042 (ENOENT), 14000011 (INVALID_INPUT) 等
    if (isUserCancelled(e)) {
      return { ok: true, cancelled: true };
    }
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

// ===== pickDocumentContent: 调 fs.openSync/readSync 读文件字节, base64 编码返回 =====
async function handlePickDocumentContent(payload: string): Promise<FilePickerResponse> {
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

    // 读取全部字节到 ArrayBuffer
    const arrayBuffer = new ArrayBuffer(size);
    fs.readSync(file.fd, arrayBuffer);
    // ArrayBuffer → base64 字符串 (与 ImageBridgeHandler.encode / CryptoBridgeHandler 一致)
    const base64 = arrayBufferToBase64(arrayBuffer);
    return { ok: true, data: base64 };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
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

// ===== pickImages: 调 photoAccessHelper.PhotoViewPicker 选图片, 返回图片 URI 列表 =====
async function handlePickImages(payload: string): Promise<FilePickerResponse> {
  const p = JSON.parse(payload) as PickImagesPayload;

  const photoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
  // maxSelectNumber: 鸿蒙默认 1, 多选时设较大值 (与 iOS allowsMultipleSelection 语义对齐)
  photoSelectOptions.maxSelectNumber = p.allowsMultiple ? 100 : 1;
  // MIMEType 只接受 PhotoViewMIMETypes 枚举 (image/* | video/* | */*), 无逐格式过滤能力;
  // 调用方传的图片类 UTI (public.jpeg/public.png/...) 统一落到 IMAGE_TYPE
  photoSelectOptions.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;

  const photoViewPicker = new photoAccessHelper.PhotoViewPicker();
  try {
    const result: photoAccessHelper.PhotoSelectResult = await photoViewPicker.select(photoSelectOptions);
    const uris: string[] = result.photoUris;
    if (uris == null || uris.length === 0) {
      // select 返回空数组视为取消 (理论上不会发生, 取消会抛异常)
      return { ok: true, cancelled: true };
    }
    return { ok: true, uris: uris };
  } catch (e) {
    // 用户取消: PhotoViewPicker.select 在用户取消时抛 BusinessError (与 DocumentViewPicker 一致)
    if (isUserCancelled(e)) {
      return { ok: true, cancelled: true };
    }
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

// FolderSelection: 选目录能力 (2in1/Tablet); FolderAuthorization: 授权持久化能力
// (fileShare.persistPermission/activatePermission, 官方 file-persistPermission.md
// 指定用 canIUse 检测该 syscap)
const FOLDER_SELECTION_SYSCAP = 'SystemCapability.FileManagement.UserFileService.FolderSelection';
const FOLDER_AUTHORIZATION_SYSCAP = 'SystemCapability.FileManagement.AppFileService.FolderAuthorization';

// ===== pickDirectory: 选目录 (2in1/Tablet) 或 选文件取父目录 (Phone) =====
//
// 目录选择是 2in1/Tablet 能力 (syscap UserFileService.FolderSelection),
// 官方设备 syscap 清单里 Phone 无该能力, 且 Phone 也无任何目录授权类 API
// (目录权限 READ_WRITE_*_DIRECTORY 的支持设备是 PC/2in1 | Tablet)。
// Phone 降级为“选该目录下的任一文件, 取其父目录”: 用户仍能指定导入目录,
// 空目录需先放入一个文件才能选中。
async function handlePickDirectory(payload: string): Promise<FilePickerResponse> {
  if (canIUse(FOLDER_SELECTION_SYSCAP)) {
    return await pickDirectoryNative();
  }
  return await pickDirectoryViaFileParent();
}

/**
 * 2in1/Tablet: 原生选目录 (selectMode=FOLDER) + 授权持久化。
 *
 * select 返回的 URI 只有临时只读权限 (官方 select-user-file.md, 重启/退后台失效),
 * 选完目录后立即 fileShare.persistPermission 只持久化 READ_MODE; 持久化失败即本次
 * 选择失败 (ok:false, 不返回路径 —— 不允许"本次临时可读"被当长期可读)。
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
  try {
    const uris: string[] = await documentViewPicker.select(documentSelectOptions);
    if (uris == null || uris.length === 0) {
      // select 返回空数组视为取消 (理论上不会发生, 取消会抛异常)
      return { ok: true, cancelled: true };
    }
    return await persistAndConvert(uris[0]);
  } catch (e) {
    // 用户取消: 与 handlePickDocuments 一致, 识别取消码返回 cancelled: true
    if (isUserCancelled(e)) {
      return { ok: true, cancelled: true };
    }
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

/**
 * 选定目录后: 持久化 (READ_MODE, 失败即本次选择失败) → fileUri 官方转换沙箱路径。
 * 转换不做路径校验 (官方约定), 可访问性由 Kotlin 侧真实检查。
 */
async function persistAndConvert(dirUri: string): Promise<FilePickerResponse> {
  if (!canIUse(FOLDER_AUTHORIZATION_SYSCAP)) {
    return { ok: false, code: 801, error: 'FolderAuthorization not supported on this device' };
  }
  // 只持久化 select 实际授予的 READ_MODE (超出临时授权范围的 mode 会被拒)
  const policy: fileShare.PolicyInfo = {
    uri: dirUri,
    operationMode: fileShare.OperationMode.READ_MODE,
  };
  try {
    await fileShare.persistPermission([policy]);
  } catch (e) {
    const err = e as BusinessError<Array<fileShare.PolicyErrorResult>>;
    if (err.code === 13900001 && err.data) {
      return {
        ok: false,
        code: err.code,
        policyErrors: err.data.map(d => ({ uri: d.uri, code: d.code, message: String(d.message ?? '') })),
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
 * Phone: 选一个文件, 取其父目录作为导入根目录 (原行为, 不做持久化/激活/路径转换)。
 *
 * 文件选择 (selectMode 保持默认 FILE) 的 syscap 是基础 UserFileService, 手机可用。
 * URI 形如 file://docs/storage/Users/currentUser/Documents/books/novel.txt,
 * 去掉末段文件名即目录 URI。
 */
async function pickDirectoryViaFileParent(): Promise<FilePickerResponse> {
  const documentSelectOptions = new picker.DocumentSelectOptions();
  documentSelectOptions.maxSelectNumber = 1;
  const documentViewPicker = new picker.DocumentViewPicker();
  try {
    const uris: string[] = await documentViewPicker.select(documentSelectOptions);
    if (uris == null || uris.length === 0) {
      return { ok: true, cancelled: true };
    }
    const dirUri = parentUriOf(uris[0]);
    if (dirUri == null) {
      return { ok: false, error: 'cannot resolve parent directory of: ' + uris[0] };
    }
    return { ok: true, uris: [dirUri] };
  } catch (e) {
    if (isUserCancelled(e)) {
      return { ok: true, cancelled: true };
    }
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
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
  const policies: fileShare.PolicyInfo[] = p.uris.map(uri => ({
    uri: uri,
    // 与持久化同策略: select 只授 READ_MODE
    operationMode: fileShare.OperationMode.READ_MODE,
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
        policyErrors: err.data.map(d => ({ uri: d.uri, code: d.code, message: String(d.message ?? '') })),
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

// ===== saveDocument: 调 DocumentViewPicker.save 弹系统保存器, fs.write 写入内容 =====
async function handleSaveDocument(payload: string): Promise<FilePickerResponse> {
  const p = JSON.parse(payload) as SaveDocumentPayload;

  // 1. 弹系统保存器 (newFileNames 为建议文件名, 用户可改名/选目录)
  const documentSaveOptions = new picker.DocumentSaveOptions();
  documentSaveOptions.newFileNames = [p.fileName];
  const documentViewPicker = new picker.DocumentViewPicker();
  let uris: string[];
  try {
    uris = await documentViewPicker.save(documentSaveOptions);
  } catch (e) {
    // 用户取消: 与 handlePickDocuments 一致, 识别取消码返回 cancelled: true
    if (isUserCancelled(e)) {
      return { ok: true, cancelled: true };
    }
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
  if (uris == null || uris.length === 0) {
    return { ok: true, cancelled: true };
  }

  // 2. 写内容: base64 → 独立 ArrayBuffer (与 CryptoBridgeHandler.base64ToUint8 同模式) → fs.write
  //    (save 返回的 URI 已授予写权限, 文件由系统创建为空文件, TRUNC 覆盖写)
  let file: fs.File | undefined;
  try {
    const ab = base64ToArrayBuffer(p.data);
    file = fs.openSync(uris[0], fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
    fs.writeSync(file.fd, ab);
    return { ok: true, uris: [uris[0]] };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  } finally {
    if (file != null) {
      try {
        fs.closeSync(file);
      } catch (e) {
        console.warn('[FilePickerBridge] closeSync failed: ' + JSON.stringify(e));
      }
    }
  }
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
  try {
    // createAsset 返回的 URI 已指向图库中的新文件, 无需沙盒中转
    const helper = photoAccessHelper.getPhotoAccessHelper(abilityContext);
    const uri = await helper.createAsset(photoAccessHelper.PhotoType.IMAGE, p.extension);
    file = fs.openSync(uri, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE);
    fs.writeSync(file.fd, base64ToArrayBuffer(p.data));
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
  }
}

// ===== 工具函数 =====

/**
 * 判断异常是否为用户取消 (对照 iOS documentPickerDidCancel)。 *
 * 鸿蒙 DocumentViewPicker.select 在用户取消时抛 BusinessError, code 不统一:
 * - 13900042: ERR_ENOENT (无对应文件, 部分版本取消场景)
 * - 14000011: ERR_INVALID_INPUT (输入无效, 部分版本取消场景)
 * - 13900020: ENODEV (部分版本取消场景)
 *
 * 此处把上述 code 视为取消 (返回 cancelled: true), 调用方据此返回 null 而非抛异常,
 * 与 iOS pickDocuments 用户取消返回 null 语义对齐。
 */
function isUserCancelled(e: object): boolean {
  const err = e as BusinessError;
  const code = err.code;
  return code === 13900042 || code === 14000011 || code === 13900020;
}
