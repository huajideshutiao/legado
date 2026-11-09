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
 * - 控制面 resultJson: `{ ok, pixelMapId?, pixelMapIds?, width?, height?, error?, invalidArgument? }`
 *   (encode 的 packed 字节不走 JSON, 见下)
 * - 数据面 bodyArrayBuffer: encode 的 packed 图片字节裸传 (不经 base64, 二进制保真)
 *
 * # PixelMap 句柄管理
 * ArkTS 侧用 Map<number, PixelMapEntry> 持有 PixelMap 实例与借用/释放状态, Kotlin 侧只持有 id。
 * decode/crop/split/stitch/rotate/flip 创建新 PixelMap 并存入 Map; release 请求先等该图
 * 借用计数归零, 再在 PixelMap.release() 的 Promise 成功后删除并结束; 失败保留条目并回执真实错误。
 */

// PixelMap 句柄存储 (id → 资源条目: PixelMap + 真实在途使用/释放状态)
const pixelMaps = new Map<number, PixelMapEntry>();
let nextPixelMapId = 1;

// 单个 PixelMap 的资源条目: 借用计数与释放状态归同一处
interface PixelMapEntry {
  id: number;
  pixelMap: image.PixelMap;
  inUse: number;
  releasePending: boolean;
  releasing: boolean;
  releasePromise?: Promise<ImageResponse>;
  releaseResolve?: (response: ImageResponse) => void;
}

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
  release?: boolean;
  invalidArgument?: boolean;
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

// ===== 资源条目与借用/释放 =====

function newPixelMapEntry(id: number, pixelMap: image.PixelMap): PixelMapEntry {
  return {
    id: id,
    pixelMap: pixelMap,
    inUse: 0,
    releasePending: false,
    releasing: false,
  };
}

/** 借用输入图: 释放已开始的图拒绝新操作; 在 SDK 操作终态 (含失败) 必须调用 [returnPixelMaps] 归还。 */
function borrowPixelMap(id: number, borrowed: PixelMapEntry[]): PixelMapEntry | undefined {
  const entry = pixelMaps.get(id);
  if (entry == undefined) {
    return undefined;
  }
  if (entry.releasing) {
    throw new Error('pixelMap releasing: ' + id);
  }
  entry.inUse += 1;
  borrowed.push(entry);
  return entry;
}

/** 归还借用；按登记次数成对递减，最后一个借用归还且已有释放请求时开始真实 release。 */
function returnPixelMaps(borrowed: PixelMapEntry[]): void {
  for (const entry of borrowed) {
    entry.inUse -= 1;
    if (entry.inUse === 0 && entry.releasePending) {
      startRelease(entry);
    }
  }
}

/** 真实释放: 只有 SDK release() Promise 成功后才从 Map 移除；失败保留资源条目并回执错误。 */
async function startRelease(entry: PixelMapEntry): Promise<void> {
  if (entry.releasing || entry.inUse > 0 || !entry.releasePending) {
    return;
  }
  entry.releasing = true;
  let response: ImageResponse;
  try {
    await entry.pixelMap.release();
    pixelMaps.delete(entry.id);
    response = { ok: true, release: true, pixelMapId: entry.id };
  } catch (e) {
    const err = e as BusinessError;
    const message = 'release pixelMapId=' + entry.id + ' failed: ' + (err.message ?? String(e));
    console.error('[ImageBridge] ' + message);
    response = { ok: false, release: true, pixelMapId: entry.id, error: message };
  }
  entry.releasing = false;
  entry.releasePending = false;
  const resolver = entry.releaseResolve!;
  entry.releasePromise = undefined;
  entry.releaseResolve = undefined;
  resolver(response);
}

/** 请求释放: 有借用未归还时等最后一个归还再 release；同一图共享同一回执 Promise。 */
function requestRelease(entry: PixelMapEntry): Promise<ImageResponse> {
  entry.releasePending = true;
  if (entry.releasePromise != undefined) {
    return entry.releasePromise;
  }
  const promise = new Promise<ImageResponse>((resolve) => {
    // 包一层, 避免把 Promise 构造器的宽 resolver 类型收窄后直接存入条目
    entry.releaseResolve = (response: ImageResponse): void => {
      resolve(response);
    };
  });
  entry.releasePromise = promise;
  if (entry.inUse === 0) {
    startRelease(entry);
  }
  return promise;
}

/** 撤销本次请求新建的图: 逐个等到 release 终态; 失败保留资源条目。 */
async function releaseCreatedEntries(ids: number[]): Promise<void> {
  for (const id of ids) {
    const entry = pixelMaps.get(id);
    if (entry == undefined) {
      continue;
    }
    await requestRelease(entry);
  }
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
    pixelMaps.set(id, newPixelMapEntry(id, pixelMap));
    return { ok: true, pixelMapId: id };
  } finally {
    // release() 返回 Promise, 必须等待完成; 失败只记录辅助资源真实错误, 不改变本次解码结果
    try {
      await imageSource.release();
    } catch (e) {
      console.error('[ImageBridge] decode: imageSource.release failed: ' + String((e as BusinessError).message ?? e));
    }
  }
}

// ===== encode: PixelMap → packed 字节 (回调 body 裸传) =====
async function handleEncode(payload: string): Promise<ImageActionResult> {
  const p = JSON.parse(payload) as EncodePayload;
  const entry = pixelMaps.get(p.pixelMapId);
  if (entry == undefined) {
    return { resp: { ok: false, error: 'pixelMap not found: ' + p.pixelMapId } };
  }
  const borrowed: PixelMapEntry[] = [];
  const packer = image.createImagePacker();
  try {
    try {
      borrowPixelMap(p.pixelMapId, borrowed);
    } catch (e) {
      // 借用失败 (如释放已开始) 是真实操作错误, 不带 packToData 前缀
      return { resp: { ok: false, error: (e as Error).message ?? String(e) } };
    }
    // format 规范: "jpeg" → "image/jpeg", "png" → "image/png"
    const formatStr = p.format.startsWith('image/') ? p.format : 'image/' + p.format;
    const packed = await packer.packToData(entry.pixelMap, { format: formatStr, quality: p.quality });
    // packed 是 ArrayBuffer: 原样裸传 (零拷贝, 二进制保真)
    return { resp: { ok: true }, body: packed };
  } catch (e) {
    const err = e as BusinessError;
    return { resp: { ok: false, error: 'packToData failed: ' + (err.message ?? String(e)) } };
  } finally {
    // release() 返回 Promise, 必须等待完成; 失败只记录辅助资源真实错误, 不改变本次编码结果
    try {
      await packer.release();
    } catch (e) {
      console.error('[ImageBridge] encode: packer.release failed: ' + String((e as BusinessError).message ?? e));
    }
    returnPixelMaps(borrowed);
  }
}

// ===== size: 获取 PixelMap 宽高 =====
async function handleSize(payload: string): Promise<ImageResponse> {
  const borrowed: PixelMapEntry[] = [];
  try {
    const p = JSON.parse(payload) as SizePayload;
    const entry = borrowPixelMap(p.pixelMapId, borrowed);
    if (entry == undefined) {
      return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
    }
    const info = entry.pixelMap.getImageInfoSync();
    return { ok: true, width: info.size.width, height: info.size.height };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  } finally {
    returnPixelMaps(borrowed);
  }
}

// ===== crop: 裁剪 PixelMap 区域, 返回新 PixelMap =====
async function handleCrop(payload: string): Promise<ImageResponse> {
  const borrowed: PixelMapEntry[] = [];
  try {
    const p = JSON.parse(payload) as CropPayload;
    const entry = borrowPixelMap(p.pixelMapId, borrowed);
    if (entry == undefined) {
      return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
    }
    const info = entry.pixelMap.getImageInfoSync();
    if (p.x < 0 || p.y < 0 || p.w <= 0 || p.h <= 0 ||
      p.x + p.w > info.size.width || p.y + p.h > info.size.height) {
      return {
        ok: false,
        invalidArgument: true,
        error: 'invalid crop region: (' + p.x + ',' + p.y + ',' + p.w + ',' + p.h +
          ') image ' + info.size.width + 'x' + info.size.height,
      };
    }
    // 创建独立副本后裁剪 (crop 修改 in-place, 不能直接操作原图)
    const croppedMap = await createCroppedCopy(entry.pixelMap, p.x, p.y, p.w, p.h);
    const id = nextPixelMapId++;
    pixelMaps.set(id, newPixelMapEntry(id, croppedMap));
    return { ok: true, pixelMapId: id };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  } finally {
    returnPixelMaps(borrowed);
  }
}

// ===== split: 将 PixelMap 切分为 rows×cols 网格, 返回多个 PixelMap id =====
async function handleSplit(payload: string): Promise<ImageResponse> {
  // 本次请求已入 Map 的切块 id: 中途失败时逐个撤销, 不误删原图或其他请求的图
  const createdIds: number[] = [];
  const borrowed: PixelMapEntry[] = [];
  try {
    const p = JSON.parse(payload) as SplitPayload;
    const entry = borrowPixelMap(p.pixelMapId, borrowed);
    if (entry == undefined) {
      return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
    }
    if (p.rows <= 0 || p.cols <= 0) {
      return { ok: false, invalidArgument: true, error: 'invalid rows/cols: ' + p.rows + 'x' + p.cols };
    }

    const info = entry.pixelMap.getImageInfoSync();
    const totalW = info.size.width;
    const totalH = info.size.height;
    if (p.rows > totalH || p.cols > totalW) {
      return {
        ok: false,
        invalidArgument: true,
        error: 'rows/cols exceed image: ' + p.rows + 'x' + p.cols + ' image ' + totalW + 'x' + totalH,
      };
    }
    const cellW = Math.floor(totalW / p.cols);
    const cellH = Math.floor(totalH / p.rows);

    // 读取一次原图像素, 供所有 cell 复用 (createPixelMap 内部拷贝数据)
    const srcBuffer = new ArrayBuffer(totalW * totalH * 4);
    await entry.pixelMap.readPixelsToBuffer(srcBuffer);

    const ids: number[] = [];
    for (let row = 0; row < p.rows; row++) {
      for (let col = 0; col < p.cols; col++) {
        const x = col * cellW;
        const y = row * cellH;
        const cellWidth = col === p.cols - 1 ? totalW - x : cellW;
        const cellHeight = row === p.rows - 1 ? totalH - y : cellH;
        const cellMap = await createPixelMapFromSubRegion(
          srcBuffer, totalW, x, y, cellWidth, cellHeight
        );
        const id = nextPixelMapId++;
        pixelMaps.set(id, newPixelMapEntry(id, cellMap));
        createdIds.push(id);
        ids.push(id);
      }
    }
    return { ok: true, pixelMapIds: ids };
  } catch (e) {
    await releaseCreatedEntries(createdIds);
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  } finally {
    returnPixelMaps(borrowed);
  }
}

// ===== stitch: 将多个 PixelMap 合并为一个, 返回新 PixelMap =====
async function handleStitch(payload: string): Promise<ImageResponse> {
  const borrowed: PixelMapEntry[] = [];
  try {
    const p = JSON.parse(payload) as StitchPayload;
    if (p.pixelMapIds.length === 0) {
      return { ok: false, error: 'empty pixelMapIds' };
    }
    const direction = p.direction.toLowerCase();
    if (direction !== 'h' && direction !== 'v') {
      return { ok: false, invalidArgument: true, error: 'invalid stitch direction: ' + p.direction };
    }

    // 逐个借用源 PixelMap; 中途缺图时 finally 归还已借用的部分
    for (const id of p.pixelMapIds) {
      if (borrowPixelMap(id, borrowed) == undefined) {
        return { ok: false, error: 'pixelMap not found: ' + id };
      }
    }

    // 读取每个源 PixelMap 的像素数据
    const sizes: StitchSource[] = [];
    for (const entry of borrowed) {
      const info = entry.pixelMap.getImageInfoSync();
      const w = info.size.width;
      const h = info.size.height;
      const buf = new ArrayBuffer(w * h * 4);
      await entry.pixelMap.readPixelsToBuffer(buf);
      const src: StitchSource = { w: w, h: h, buffer: buf };
      sizes.push(src);
    }

    // 计算合并后画布尺寸
    const isHorizontal = direction === 'h';
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
    pixelMaps.set(id, newPixelMapEntry(id, stitchedMap));
    return { ok: true, pixelMapId: id };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  } finally {
    returnPixelMaps(borrowed);
  }
}

// ===== rotate: PixelMap 旋转 (in-place, 先复制再旋转), 返回新 PixelMap =====
async function handleRotate(payload: string): Promise<ImageResponse> {
  const borrowed: PixelMapEntry[] = [];
  try {
    const p = JSON.parse(payload) as RotatePayload;
    const entry = borrowPixelMap(p.pixelMapId, borrowed);
    if (entry == undefined) {
      return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
    }
    // rotate 是 in-place 操作, 先做可编辑副本再旋转, 不动原图
    const copy = await createEditableCopy(entry.pixelMap);
    try {
      // PixelMap.rotate(angle): 正值为顺时针 (Kotlin 侧已归一化到 [0,360))
      await copy.rotate(p.degrees);
    } catch (e) {
      // 失败副本按官方 release() Promise 完整等待, 失败只记录真实错误, 不掩盖 rotate 的错误
      try {
        await copy.release();
      } catch (re) {
        console.error('[ImageBridge] rotate: release failed copy: ' + String((re as BusinessError).message ?? re));
      }
      return { ok: false, error: 'rotate failed: ' + String((e as Error).message ?? e) };
    }
    const id = nextPixelMapId++;
    pixelMaps.set(id, newPixelMapEntry(id, copy));
    return { ok: true, pixelMapId: id };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  } finally {
    returnPixelMaps(borrowed);
  }
}

// ===== flip: PixelMap 翻转/镜像 (in-place, 先复制再翻转), 返回新 PixelMap =====
async function handleFlip(payload: string): Promise<ImageResponse> {
  const borrowed: PixelMapEntry[] = [];
  try {
    const p = JSON.parse(payload) as FlipPayload;
    const entry = borrowPixelMap(p.pixelMapId, borrowed);
    if (entry == undefined) {
      return { ok: false, error: 'pixelMap not found: ' + p.pixelMapId };
    }
    const copy = await createEditableCopy(entry.pixelMap);
    try {
      await copy.flip(p.isH, p.isV);
    } catch (e) {
      try {
        await copy.release();
      } catch (re) {
        console.error('[ImageBridge] flip: release failed copy: ' + String((re as BusinessError).message ?? re));
      }
      return { ok: false, error: 'flip failed: ' + String((e as Error).message ?? e) };
    }
    const id = nextPixelMapId++;
    pixelMaps.set(id, newPixelMapEntry(id, copy));
    return { ok: true, pixelMapId: id };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  } finally {
    returnPixelMaps(borrowed);
  }
}

// ===== release: 等该 PixelMap 当前在途使用结束后释放 =====
async function handleRelease(payload: string): Promise<ImageResponse> {
  let p: ReleasePayload;
  try {
    p = JSON.parse(payload) as ReleasePayload;
  } catch (e) {
    // 释放请求解析失败必须回带 release 标识, 让 Kotlin 侧按释放结果记录真实错误
    return { ok: false, release: true, error: 'release payload parse failed: ' + String((e as Error).message ?? e) };
  }
  const entry = pixelMaps.get(p.pixelMapId);
  if (entry == undefined) {
    // 已成功释放/从未创建: 幂等成功, 不能把缺失条目当成新的失败资源
    return { ok: true, release: true, pixelMapId: p.pixelMapId };
  }
  return await requestRelease(entry);
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
  try {
    const info = srcMap.getImageInfoSync();
    const srcW = info.size.width;
    const srcH = info.size.height;
    const srcBuffer = new ArrayBuffer(srcW * srcH * 4);
    await srcMap.readPixelsToBuffer(srcBuffer);
    return await createPixelMapFromSubRegion(srcBuffer, srcW, x, y, w, h);
  } catch (e) {
    console.error('[ImageBridge] createCroppedCopy failed: ' + JSON.stringify(e));
    throw e as Error;
  }
}

/**
 * 创建源 PixelMap 的可编辑独立副本 (供 rotate/flip 等 in-place 操作使用)。
 * 读像素到 buffer → 以同尺寸 RGBA_8888 editable 重建, 与原图完全隔离。
 */
async function createEditableCopy(srcMap: image.PixelMap): Promise<image.PixelMap> {
  try {
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
  } catch (e) {
    console.error('[ImageBridge] createEditableCopy failed: ' + JSON.stringify(e));
    throw e as Error;
  }
}

/**
 * 从源像素 buffer 中提取 (x, y, w, h) 子区域, 创建新 PixelMap。
 * 逐行拷贝子区域像素到新 buffer, 再用新 buffer 创建 PixelMap; 调用方已完成边界校验。
 */
async function createPixelMapFromSubRegion(
  srcBuffer: ArrayBuffer, srcW: number,
  x: number, y: number, w: number, h: number
): Promise<image.PixelMap> {
  const subBuffer = new ArrayBuffer(w * h * 4);
  const srcView = new Uint8Array(srcBuffer);
  const dstView = new Uint8Array(subBuffer);
  for (let row = 0; row < h; row++) {
    const srcStart = ((y + row) * srcW + x) * 4;
    const dstStart = row * w * 4;
    dstView.set(srcView.subarray(srcStart, srcStart + w * 4), dstStart);
  }

  return await image.createPixelMap(subBuffer, {
    size: { width: w, height: h },
    pixelFormat: image.PixelMapFormat.RGBA_8888,
    editable: true,
    alphaType: image.AlphaType.PREMUL,
  });
}
