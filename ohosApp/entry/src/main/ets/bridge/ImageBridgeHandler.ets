import image from '@ohos.multimedia.image';

/**
 * ArkTS 侧图片操作处理器 (KP8+)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeImageSync 通过 tsfn dispatch 过来的图片操作请求,
 * 调用 @ohos.multimedia.image (PixelMap/ImageSource/ImagePacker) 执行真实像素操作,
 * 完成后通过 legado.imageCallback(requestId, resultJson, body?) 回送结果给 Kotlin。
 *
 * # 混合协议 (与 OhosImageOps.kt / OhosNativeBridge.kt 对齐, 同 WebView 桥思路: 大字节走裸参数)
 *
 * ## 请求 (Kotlin → ArkTS)
 * ```
 * handleImageRequest(requestJson, bytesArrayBuffer)
 * {
 *   "requestId": <number>,
 *   "action": "decode" | "encode" | "size" | "crop" | "split" | "stitch" | "rotate" | "flip" | "release",
 *   "payload": "<JSON string, action-specific>"
 * }
 * ```
 * - 控制面: requestJson 内 payload 只含小字段 (pixelMapId/format/quality/坐标等)
 * - 数据面: bytesArrayBuffer 为 decode 的图片字节 (tsfn call-js 用 napi external ArrayBuffer
 *   零拷贝包装; 其余 action 为 undefined)
 *
 * ## 响应 (ArkTS → Kotlin)
 * ```
 * imageCallback(requestId, resultJson, bodyArrayBuffer)
 * ```
 * - 控制面 resultJson: `{ ok, pixelMapId?, pixelMapIds?, width?, height?, error? }`
 *   (encode 的 packed 字节不走 JSON, 见下)
 * - 数据面 bodyArrayBuffer: encode 的 packed 图片字节裸传 (不经 base64, 二进制保真)
 *
 * # PixelMap 句柄管理
 * ArkTS 侧用 Map<number, PixelMap> 持有 PixelMap 实例, Kotlin 侧只持有 id。
 * decode/crop/split/stitch 创建新 PixelMap 并存入 Map; release 删除。
 * 应用退出时 Map 自动 GC, PixelMap 随之释放 (native 侧引用计数归零)。
 */

// PixelMap 句柄存储 (id → PixelMap)
const pixelMaps = new Map<number, image.PixelMap>();
let nextPixelMapId = 1;

// Image callback 类型 (由 EntryAbility 注入, 内部调 legado.imageCallback; body 为数据面裸字节)
export type ImageCallback = (requestId: number, resultJson: string, body: ArrayBuffer | undefined) => void;

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface ImageBridgeRequest {
  requestId: number;
  action: string;
  payload: string;
}

interface ImageResponse {
  ok: boolean;
  pixelMapId?: number;
  pixelMapIds?: number[];
  width?: number;
  height?: number;
  error?: string;
}

// Payload 各 action 的参数结构 (encode 的 packed 字节走回调 body 裸参, 不在 JSON)
interface EncodePayload { pixelMapId: number; format: string; quality: number; }
interface SizePayload { pixelMapId: number; }
interface CropPayload { pixelMapId: number; x: number; y: number; w: number; h: number; }
interface SplitPayload { pixelMapId: number; rows: number; cols: number; }
interface StitchPayload { pixelMapIds: number[]; direction: string; }
interface RotatePayload { pixelMapId: number; degrees: number; }
interface FlipPayload { pixelMapId: number; isH: boolean; isV: boolean; }
interface ReleasePayload { pixelMapId: number; }

// action 处理结果: 控制面 ImageResponse + 可选数据面裸字节 (仅 encode 携带 packed 图片)
interface ImageActionResult {
  resp: ImageResponse;
  body?: ArrayBuffer;
}

// stitch 中间态: 单个源 PixelMap 的尺寸 + 已读出的像素 buffer
interface StitchSource {
  w: number;
  h: number;
  buffer: ArrayBuffer;
}

/**
 * 处理来自 Kotlin 的图片操作请求 (由 EntryAbility 注册的 tsfn 回调调用, 混合协议)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload 控制面)
 * @param bytes 数据面裸字节 ArrayBuffer (decode 的图片字节; 其余 action 为 undefined)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.imageCallback)
 */
export function handleImageRequest(requestJson: string, bytes: ArrayBuffer | undefined, callback: ImageCallback): void {
  let req: ImageBridgeRequest;
  try {
    req = JSON.parse(requestJson) as ImageBridgeRequest;
  } catch (e) {
    console.error('[ImageBridge] parse request failed: ' + requestJson);
    return;
  }

  // 异步处理, 完成后回调
  handleAction(req, bytes).then((result: ImageActionResult) => {
    callback(req.requestId, JSON.stringify(result.resp), result.body);
  }).catch((err: Error) => {
    const resp: ImageResponse = { ok: false, error: err.message ?? String(err) };
    // 失败无数据面, body 显式传 undefined (ImageCallback 三参必填)
    callback(req.requestId, JSON.stringify(resp), undefined);
  });
}

// 根据 action 分发到具体处理函数
async function handleAction(req: ImageBridgeRequest, bytes: ArrayBuffer | undefined): Promise<ImageActionResult> {
  switch (req.action) {
    case 'decode': return { resp: await handleDecode(bytes) };
    case 'encode': return await handleEncode(req.payload);
    case 'size': return { resp: await handleSize(req.payload) };
    case 'crop': return { resp: await handleCrop(req.payload) };
    case 'split': return { resp: await handleSplit(req.payload) };
    case 'stitch': return { resp: await handleStitch(req.payload) };
    case 'rotate': return { resp: await handleRotate(req.payload) };
    case 'flip': return { resp: await handleFlip(req.payload) };
    case 'release': return { resp: await handleRelease(req.payload) };
    default: return { resp: { ok: false, error: 'unknown action: ' + req.action } };
  }
}

// ===== decode: ArrayBuffer (napi 裸字节) → PixelMap =====
async function handleDecode(bytes: ArrayBuffer | undefined): Promise<ImageResponse> {
  if (bytes == null || bytes.byteLength === 0) {
    return { ok: false, error: 'decode: empty image bytes' };
  }
  // napi external ArrayBuffer 零拷贝直用, 不经 base64
  const imageSource = image.createImageSource(bytes);
  try {
    const pixelMap = await imageSource.createPixelMap();
    const id = nextPixelMapId++;
    pixelMaps.set(id, pixelMap);
    return { ok: true, pixelMapId: id };
  } finally {
    imageSource.release();
  }
}

// ===== encode: PixelMap → packed 字节 (回调 body 裸传) =====
async function handleEncode(payload: string): Promise<ImageActionResult> {
  const p = JSON.parse(payload) as EncodePayload;
  const pixelMap = pixelMaps.get(p.pixelMapId);
  if (pixelMap == null) {
    return { resp: { ok: false, error: 'pixelMap not found: ' + p.pixelMapId } };
  }
  const packer = image.createImagePacker();
  try {
    // format 规范: "jpeg" → "image/jpeg", "png" → "image/png"
    const formatStr = p.format.startsWith('image/') ? p.format : 'image/' + p.format;
    const packed = await packer.packing(pixelMap, { format: formatStr, quality: p.quality });
    // packed 是 ArrayBuffer: 原样裸传 (零拷贝, 二进制保真)
    return { resp: { ok: true }, body: packed };
  } finally {
    packer.release();
  }
}

// ===== size: 获取 PixelMap 宽高 =====
async function handleSize(payload: string): Promise<ImageResponse> {
  const p = JSON.parse(payload) as SizePayload;
  const pixelMap = pixelMaps.get(p.pixelMapId);
  if (pixelMap == null) {
    return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
  }
  const info = pixelMap.getImageInfoSync();
  return { ok: true, width: info.size.width, height: info.size.height };
}

// ===== crop: 裁剪 PixelMap 区域, 返回新 PixelMap =====
async function handleCrop(payload: string): Promise<ImageResponse> {
  const p = JSON.parse(payload) as CropPayload;
  const srcMap = pixelMaps.get(p.pixelMapId);
  if (srcMap == null) {
    return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
  }
  // 创建独立副本后裁剪 (crop 修改 in-place, 不能直接操作原图)
  const croppedMap = await createCroppedCopy(srcMap, p.x, p.y, p.w, p.h);
  const id = nextPixelMapId++;
  pixelMaps.set(id, croppedMap);
  return { ok: true, pixelMapId: id };
}

// ===== split: 将 PixelMap 切分为 rows×cols 网格, 返回多个 PixelMap id =====
async function handleSplit(payload: string): Promise<ImageResponse> {
  const p = JSON.parse(payload) as SplitPayload;
  const srcMap = pixelMaps.get(p.pixelMapId);
  if (srcMap == null) {
    return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
  }
  if (p.rows <= 0 || p.cols <= 0) {
    return { ok: false, error: 'invalid rows/cols: ' + p.rows + 'x' + p.cols };
  }

  const info = srcMap.getImageInfoSync();
  const totalW = info.size.width;
  const totalH = info.size.height;
  const cellW = Math.floor(totalW / p.cols);
  const cellH = Math.floor(totalH / p.rows);

  // 读取一次原图像素, 供所有 cell 复用 (createPixelMap 内部拷贝数据)
  const srcBuffer = new ArrayBuffer(totalW * totalH * 4);
  await srcMap.readPixelsToBuffer(srcBuffer);

  const ids: number[] = [];
  for (let row = 0; row < p.rows; row++) {
    for (let col = 0; col < p.cols; col++) {
      const x = col * cellW;
      const y = row * cellH;
      const cellMap = await createPixelMapFromSubRegion(srcBuffer, totalW, totalH, x, y, cellW, cellH);
      const id = nextPixelMapId++;
      pixelMaps.set(id, cellMap);
      ids.push(id);
    }
  }
  return { ok: true, pixelMapIds: ids };
}

// ===== stitch: 将多个 PixelMap 合并为一个, 返回新 PixelMap =====
async function handleStitch(payload: string): Promise<ImageResponse> {
  const p = JSON.parse(payload) as StitchPayload;
  if (p.pixelMapIds.length === 0) {
    return { ok: false, error: 'empty pixelMapIds' };
  }

  // 收集所有源 PixelMap 及其尺寸
  const sources: image.PixelMap[] = [];
  const sizes: StitchSource[] = [];
  for (const id of p.pixelMapIds) {
    const pm = pixelMaps.get(id);
    if (pm == null) {
      return { ok: false, error: 'pixelMap not found: ' + id };
    }
    sources.push(pm);
  }

  // 读取每个源 PixelMap 的像素数据
  for (const pm of sources) {
    const info = pm.getImageInfoSync();
    const w = info.size.width;
    const h = info.size.height;
    const buf = new ArrayBuffer(w * h * 4);
    await pm.readPixelsToBuffer(buf);
    const src: StitchSource = { w: w, h: h, buffer: buf };
    sizes.push(src);
  }

  // 计算合并后画布尺寸
  const isHorizontal = p.direction === 'horizontal';
  let totalW: number;
  let totalH: number;
  if (isHorizontal) {
    totalW = sizes.reduce((sum, s) => sum + s.w, 0);
    totalH = sizes.reduce((max, s) => Math.max(max, s.h), 0);
  } else {
    totalW = sizes.reduce((max, s) => Math.max(max, s.w), 0);
    totalH = sizes.reduce((sum, s) => sum + s.h, 0);
  }

  // 创建合并后的像素 buffer, 初始填 0 (透明)
  const combinedBuffer = new ArrayBuffer(totalW * totalH * 4);
  const dstView = new Uint8Array(combinedBuffer);

  // 逐个源拷贝到合并 buffer 的正确位置
  let offset = 0;
  for (const s of sizes) {
    const srcView = new Uint8Array(s.buffer);
    if (isHorizontal) {
      // 水平排列: 每行拷贝 s.w 个像素 (s.w*4 字节) 到 (offset, y) 位置
      for (let y = 0; y < s.h; y++) {
        const srcStart = y * s.w * 4;
        const dstStart = y * totalW * 4 + offset * 4;
        dstView.set(srcView.subarray(srcStart, srcStart + s.w * 4), dstStart);
      }
      offset += s.w;
    } else {
      // 垂直排列: 每行拷贝 s.w 个像素到 (0, offset+y) 位置
      for (let y = 0; y < s.h; y++) {
        const srcStart = y * s.w * 4;
        const dstStart = (offset + y) * totalW * 4;
        dstView.set(srcView.subarray(srcStart, srcStart + s.w * 4), dstStart);
      }
      offset += s.h;
    }
  }

  // 从合并 buffer 创建新 PixelMap
  const stitchedMap = await image.createPixelMap(combinedBuffer, {
    size: { width: totalW, height: totalH },
    pixelFormat: image.PixelMapFormat.RGBA_8888,
    editable: true,
    alphaType: image.AlphaType.PREMUL,
  });
  const id = nextPixelMapId++;
  pixelMaps.set(id, stitchedMap);
  return { ok: true, pixelMapId: id };
}

// ===== rotate: PixelMap 旋转 (in-place, 先复制再旋转), 返回新 PixelMap =====
async function handleRotate(payload: string): Promise<ImageResponse> {
  const p = JSON.parse(payload) as RotatePayload;
  const srcMap = pixelMaps.get(p.pixelMapId);
  if (srcMap == null) {
    return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
  }
  // rotate 是 in-place 操作, 先做可编辑副本再旋转, 不动原图
  const copy = await createEditableCopy(srcMap);
  try {
    // PixelMap.rotate(angle): 正值为顺时针 (Kotlin 侧已归一化到 [0,360))
    await copy.rotate(p.degrees);
  } catch (e) {
    copy.release();
    return { ok: false, error: 'rotate failed: ' + String((e as Error).message ?? e) };
  }
  const id = nextPixelMapId++;
  pixelMaps.set(id, copy);
  return { ok: true, pixelMapId: id };
}

// ===== flip: PixelMap 翻转/镜像 (in-place, 先复制再翻转), 返回新 PixelMap =====
async function handleFlip(payload: string): Promise<ImageResponse> {
  const p = JSON.parse(payload) as FlipPayload;
  const srcMap = pixelMaps.get(p.pixelMapId);
  if (srcMap == null) {
    return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
  }
  const copy = await createEditableCopy(srcMap);
  try {
    await copy.flip(p.isH, p.isV);
  } catch (e) {
    copy.release();
    return { ok: false, error: 'flip failed: ' + String((e as Error).message ?? e) };
  }
  const id = nextPixelMapId++;
  pixelMaps.set(id, copy);
  return { ok: true, pixelMapId: id };
}

// ===== release: 释放指定 PixelMap =====
async function handleRelease(payload: string): Promise<ImageResponse> {
  const p = JSON.parse(payload) as ReleasePayload;
  const pm = pixelMaps.get(p.pixelMapId);
  if (pm != null) {
    pm.release();
    pixelMaps.delete(p.pixelMapId);
  }
  return { ok: true };
}

// ===== 工具函数 =====

/**
 * 从源 PixelMap 创建裁剪后的独立副本。
 * 先读像素到 buffer, 从中提取子区域, 再用子区域 buffer 创建新 PixelMap。
 * (不用 pixelMap.crop() 因其修改 in-place, 会影响原图)
 */
async function createCroppedCopy(
  srcMap: image.PixelMap, x: number, y: number, w: number, h: number
): Promise<image.PixelMap> {
  const info = srcMap.getImageInfoSync();
  const srcW = info.size.width;
  const srcH = info.size.height;
  const srcBuffer = new ArrayBuffer(srcW * srcH * 4);
  await srcMap.readPixelsToBuffer(srcBuffer);
  return await createPixelMapFromSubRegion(srcBuffer, srcW, srcH, x, y, w, h);
}

/**
 * 创建源 PixelMap 的可编辑独立副本 (供 rotate/flip 等 in-place 操作使用)。
 * 读像素到 buffer → 以同尺寸 RGBA_8888 editable 重建, 与原图完全隔离。
 */
async function createEditableCopy(srcMap: image.PixelMap): Promise<image.PixelMap> {
  const info = srcMap.getImageInfoSync();
  const w = info.size.width;
  const h = info.size.height;
  const buf = new ArrayBuffer(w * h * 4);
  await srcMap.readPixelsToBuffer(buf);
  return await image.createPixelMap(buf, {
    size: { width: w, height: h },
    pixelFormat: image.PixelMapFormat.RGBA_8888,
    editable: true,
    alphaType: image.AlphaType.PREMUL,
  });
}

/**
 * 从源像素 buffer 中提取 (x, y, w, h) 子区域, 创建新 PixelMap。
 * 逐行拷贝子区域像素到新 buffer, 再用新 buffer 创建 PixelMap。
 */
async function createPixelMapFromSubRegion(
  srcBuffer: ArrayBuffer, srcW: number, srcH: number,
  x: number, y: number, w: number, h: number
): Promise<image.PixelMap> {
  // 边界裁断 (防止越界)
  const clampedW = Math.min(w, srcW - x);
  const clampedH = Math.min(h, srcH - y);
  if (clampedW <= 0 || clampedH <= 0) {
    // 空区域: 返回 1x1 透明 PixelMap
    const emptyBuf = new ArrayBuffer(4);
    return await image.createPixelMap(emptyBuf, {
      size: { width: 1, height: 1 },
      pixelFormat: image.PixelMapFormat.RGBA_8888,
      editable: true,
      alphaType: image.AlphaType.PREMUL,
    });
  }

  const subBuffer = new ArrayBuffer(clampedW * clampedH * 4);
  const srcView = new Uint8Array(srcBuffer);
  const dstView = new Uint8Array(subBuffer);
  for (let row = 0; row < clampedH; row++) {
    const srcStart = ((y + row) * srcW + x) * 4;
    const dstStart = row * clampedW * 4;
    dstView.set(srcView.subarray(srcStart, srcStart + clampedW * 4), dstStart);
  }

  return await image.createPixelMap(subBuffer, {
    size: { width: clampedW, height: clampedH },
    pixelFormat: image.PixelMapFormat.RGBA_8888,
    editable: true,
    alphaType: image.AlphaType.PREMUL,
  });
}
