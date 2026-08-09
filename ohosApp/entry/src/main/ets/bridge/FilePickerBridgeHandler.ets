import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import buffer from '@ohos.buffer';
import { BusinessError } from '@ohos.base';

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
 *   "action": "pickDocuments" | "pickDocumentContent" | "pickImages" | "pickDirectory",
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
 * - pickDirectory 成功: `{ ok: true, uris: ["<dirUri>"] }`
 * - pickDirectory 用户取消: `{ ok: true, cancelled: true }`
 * - 失败: `{ ok: false, error: "<string>" }`
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

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface FilePickerBridgeRequest {
  requestId: number;
  action: string;
  payload: string;
}

interface FilePickerResponse {
  ok: boolean;
  uris?: string[];
  cancelled?: boolean;
  data?: string;
  error?: string;
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
 * 处理来自 Kotlin 的文件选择请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.filePickerCallback)
 */
export function handleFilePickerRequest(requestJson: string, callback: FilePickerCallback): void {
  let req: FilePickerBridgeRequest;
  try {
    req = JSON.parse(requestJson) as FilePickerBridgeRequest;
  } catch (e) {
    console.error('[FilePickerBridge] parse request failed: ' + requestJson);
    return;
  }

  // 异步处理, 完成后回调
  handleAction(req).then((resp: FilePickerResponse) => {
    callback(req.requestId, JSON.stringify(resp));
  }).catch((err: Error) => {
    const resp: FilePickerResponse = { ok: false, error: err.message ?? String(err) };
    callback(req.requestId, JSON.stringify(resp));
  });
}

// 根据 action 分发到具体处理函数
async function handleAction(req: FilePickerBridgeRequest): Promise<FilePickerResponse> {
  switch (req.action) {
    case 'pickDocuments': return await handlePickDocuments(req.payload);
    case 'pickDocumentContent': return await handlePickDocumentContent(req.payload);
    case 'pickImages': return await handlePickImages(req.payload);
    case 'pickDirectory': return await handlePickDirectory(req.payload);
    case 'saveDocument': return await handleSaveDocument(req.payload);
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
    const base64 = buffer.from(arrayBuffer).toString('base64');
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

// ===== pickImages: 调 PhotoViewPicker 选图片, 返回图片 URI 列表 =====
async function handlePickImages(payload: string): Promise<FilePickerResponse> {
  const p = JSON.parse(payload) as PickImagesPayload;

  const photoSelectOptions = new picker.PhotoSelectOptions();
  // maxSelectNumber: 鸿蒙默认 1, 多选时设较大值 (与 iOS allowsMultipleSelection 语义对齐)
  photoSelectOptions.maxSelectNumber = p.allowsMultiple ? 100 : 1;
  // MIMEType 只接受 PhotoViewMIMETypes 枚举 (image/* | video/* | */*), 无逐格式过滤能力;
  // 调用方传的图片类 UTI (public.jpeg/public.png/...) 统一落到 IMAGE_TYPE
  photoSelectOptions.MIMEType = picker.PhotoViewMIMETypes.IMAGE_TYPE;

  const photoViewPicker = new picker.PhotoViewPicker();
  try {
    const result: picker.PhotoSelectResult = await photoViewPicker.select(photoSelectOptions);
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

// ===== pickDirectory: 调 DocumentViewPicker 选目录, maxSelectNumber=1, 返回目录 URI =====
async function handlePickDirectory(payload: string): Promise<FilePickerResponse> {
  // 选目录: 不传 fileSuffixFilters (允许用户导航文件系统选择目录), maxSelectNumber=1 限定单选
  const documentSelectOptions = new picker.DocumentSelectOptions();
  documentSelectOptions.maxSelectNumber = 1;
  // 必须显式选目录模式, 默认 FILE 会选文件而非目录
  documentSelectOptions.selectMode = picker.DocumentSelectMode.FOLDER;
  const documentViewPicker = new picker.DocumentViewPicker();
  try {
    const uris: string[] = await documentViewPicker.select(documentSelectOptions);
    if (uris == null || uris.length === 0) {
      // select 返回空数组视为取消 (理论上不会发生, 取消会抛异常)
      return { ok: true, cancelled: true };
    }
    // maxSelectNumber=1, 取首个作为目录 URI (uris 数组中仅 1 个元素)
    return { ok: true, uris: [uris[0]] };
  } catch (e) {
    // 用户取消: 与 handlePickDocuments 一致, 识别取消码返回 cancelled: true
    if (isUserCancelled(e)) {
      return { ok: true, cancelled: true };
    }
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
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
    const buf = buffer.from(p.data, 'base64');
    // Buffer 的长度属性是 length (无 byteLength); buffer.from 可能返回池化视图, 起始偏移取 byteOffset
    const ab = new ArrayBuffer(buf.length);
    new Uint8Array(ab).set(new Uint8Array(buf.buffer, buf.byteOffset, buf.length));
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

// ===== 工具函数 =====

/**
 * 判断异常是否为用户取消 (对照 iOS documentPickerDidCancel)。
 *
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
