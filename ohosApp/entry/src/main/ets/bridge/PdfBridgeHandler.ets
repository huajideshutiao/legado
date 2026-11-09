/**
 * PDF 桥接处理器 (ArkTS 侧 @hms.officeservice.pdfservice 实现)。
 *
 * 请求面: invokePdfSync 经源代次通道 dispatch (控制面 JSON: requestId/action/payload,
 * gen 资格在 C++ 层把关); 结果面: legado.pdfCallback(requestId, resultJson, bytes?) 回送。
 * 动作: openDocument {path} (前置 releaseCurrent 含草稿终结, 未成功拒绝; PARSE_SUCCESS
 * 判定, 失败释放草稿不发布) / renderPage {index,width} (整页矩阵 getAreaPixelMap →
 * 白底合成 → ImagePacker JPEG 90) / closeDocument (releaseCurrent, 失败如实 ok:false)。
 * 回执契约: 动作函数只产出结果 (可抛由外层收敛), cb 只在 handleAsync 尾交付一次,
 * 不在任何处理 catch 内; 资源释放失败计入结果 (不装成功); 文档/草稿 owner 只在
 * releaseDocument 真实成功后移交/丢弃, 失败保留待重试。迟到回执 (K 侧超时/通道
 * 重注册结算) 由 K 侧 pending 表丢弃。
 * 线程契约: ArkTS 主线程串行同步片段, await 期间后续请求可插入, 局部资源归本请求;
 * 调用方在非 ArkTS 主线程同步等待 (与 image 桥同一约束)。
 */
import { pdfService } from '@kit.PDFKit';
import image from '@ohos.multimedia.image';
import drawing from '@ohos.graphics.drawing';

interface BridgeRequest {
  requestId: number;
  action: string;
  payload: string;
}

interface OpenPayload {
  path: string;
}

interface RenderPayload {
  index: number;
  width: number;
}

/** 交付载荷: 控制面 JSON + 数据面字节 (仅 renderPage 成功)。 */
interface Deliverable {
  json: string;
  bytes?: ArrayBuffer;
}

/** 打开中的 PDF 文档 (唯一 owner: 本模块; 打开/关闭只经 openDocumentAction/closeDocument)。 */
let pdfDoc: pdfService.PdfDocument | undefined = undefined;

/**
 * 未发布草稿槽 (releaseDocument 失败时保留, 同时刻最多一个: 文档会话串行)。
 * 由下一次 releaseCurrent (openDocument 前置/closeDocument) 重试终结, 不丢弃引用。
 */
let pendingDraft: pdfService.PdfDocument | undefined = undefined;

/** 桥请求入口 (EntryAbility.onCreate 经 legado.registerPdfCallback 注册; gen 通道单 JSON 参)。 */
export function handlePdfRequest(
  requestJson: string,
  cb: (requestId: number, resultJson: string, body?: ArrayBuffer) => void
): void {
  handleAsync(requestJson, cb);
}

/** 处理 + 交付分离入口: 动作产出结果, 交付点唯一且在全部处理 catch 之外。 */
async function handleAsync(
  requestJson: string,
  cb: (requestId: number, resultJson: string, body?: ArrayBuffer) => void
): Promise<void> {
  let requestId = 0;
  let deliverable: Deliverable | undefined = undefined;
  let failure: string | undefined = undefined;
  try {
    const req = JSON.parse(requestJson) as BridgeRequest;
    requestId = req.requestId;
    switch (req.action) {
      case 'openDocument':
        deliverable = openDocumentAction(req);
        break;
      case 'renderPage':
        deliverable = await renderPageAction(req);
        break;
      case 'closeDocument':
        deliverable = { json: JSON.stringify({ ok: releaseCurrent() }) };
        break;
      default:
        deliverable = { json: JSON.stringify({ ok: false, error: 'unknown action: ' + req.action }) };
    }
  } catch (e) {
    const err = e as Error;
    console.error('[PdfBridge] request failed: ' + JSON.stringify(e) + ', request=' + requestJson);
    failure = err.message ?? String(e);
  }
  // 交付点 (唯一): 不在任何处理 catch 内
  if (deliverable !== undefined) {
    cb(requestId, deliverable.json, deliverable.bytes);
  } else {
    cb(requestId, JSON.stringify({ ok: false, error: failure ?? 'request failed' }));
  }
}

/** openDocument: 前置关闭校验 → loadDocument → 页数; 只产出结果, 可抛。 */
function openDocumentAction(req: BridgeRequest): Deliverable {
  const p = JSON.parse(req.payload ?? '{}') as OpenPayload;
  if (typeof p.path !== 'string' || p.path.length === 0) {
    return { json: JSON.stringify({ ok: false, error: 'path is empty' }) };
  }
  // 前一文档/遗留草稿未释放成功: 不覆盖原件/不发布新文档 (重试由调用方驱动)
  if (!releaseCurrent()) {
    return { json: JSON.stringify({ ok: false, error: 'previous document not released' }) };
  }
  const doc = new pdfService.PdfDocument();
  let parseResult: pdfService.ParseResult;
  try {
    parseResult = doc.loadDocument(p.path);
  } catch (e) {
    tryReleaseDraft(doc);
    throw e as Error;
  }
  if (parseResult !== pdfService.ParseResult.PARSE_SUCCESS) {
    tryReleaseDraft(doc);
    return {
      json: JSON.stringify({
        ok: false,
        error: 'loadDocument failed: ' + parseResult,
        parseResult: parseResult as number
      })
    };
  }
  let pageCount = 0;
  try {
    pageCount = doc.getPageCount();
  } catch (e) {
    tryReleaseDraft(doc);
    throw e as Error;
  }
  pdfDoc = doc;
  return { json: JSON.stringify({ ok: true, pageCount: pageCount }) };
}

/** renderPage: 白底合成 + JPEG 打包; 本请求局部资源在产出前全部尝试释放 (失败计入结果)。 */
async function renderPageAction(req: BridgeRequest): Promise<Deliverable> {
  if (pdfDoc === undefined) {
    return { json: JSON.stringify({ ok: false, error: 'document not open' }) };
  }
  const p = JSON.parse(req.payload ?? '{}') as RenderPayload;
  const pageCount = pdfDoc.getPageCount();
  if (!(p.index >= 0 && p.index < pageCount)) {
    return { json: JSON.stringify({ ok: false, error: 'page index out of range: ' + p.index }) };
  }
  const page = pdfDoc.getPage(p.index);
  const pageWidth = page.getWidth();
  const pageHeight = page.getHeight();
  if (!(pageWidth > 0 && pageHeight > 0)) {
    return { json: JSON.stringify({ ok: false, error: 'invalid page size' }) };
  }
  // 输出位图: 宽 = 请求宽度, 高按页面比例; PdfMatrix 只描述页内整页区域
  // (x 左起 / y 底起 / width / height), 输出像素尺寸由 bitmapwidth/bitmapHeight 指定;
  // 页面自身旋转标记由渲染器处理, 矩阵 rotate 置 0 不重复旋转
  const bitmapHeight = Math.round(p.width * pageHeight / pageWidth);
  const matrix = new pdfService.PdfMatrix();
  matrix.x = 0;
  matrix.y = 0;
  matrix.width = pageWidth;
  matrix.height = pageHeight;
  matrix.rotate = 0;
  const rendered = page.getAreaPixelMap(matrix, p.width, bitmapHeight, false, true);
  let data: ArrayBuffer | undefined = undefined;
  let failure: string | undefined = undefined;
  let target: image.PixelMap | undefined = undefined;
  let packer: image.ImagePacker | undefined = undefined;
  try {
    // 白底合成: 可写目标位图 → Canvas 填白 (默认 blend SRC_OVER) → 叠 PDF 页
    target = image.createPixelMapSync({
      size: { width: p.width, height: bitmapHeight },
      pixelFormat: image.PixelMapFormat.RGBA_8888,
      editable: true,
      alphaType: image.AlphaType.PREMUL,
    });
    const canvas = new drawing.Canvas(target);
    canvas.drawColor({ alpha: 255, red: 255, green: 255, blue: 255 });
    canvas.drawImage(rendered, 0, 0);
    packer = image.createImagePacker();
    const option: image.PackingOption = { format: 'image/jpeg', quality: 90 };
    data = await packer.packing(target, option);
  } catch (e) {
    const err = e as Error;
    failure = err.message ?? String(e);
    console.error('[PdfBridge] render failed: ' + JSON.stringify(e) + ', index=' + p.index);
  }
  // 资源终结: 全部释放尝试都执行; 任一失败如实计入结果 (不装成功)
  if (packer !== undefined) {
    try {
      await packer.release();
    } catch (e) {
      failure = failure ?? 'packer release failed';
      console.error('[PdfBridge] packer release failed: ' + JSON.stringify(e));
    }
  }
  if (target !== undefined) {
    try {
      await target.release();
    } catch (e) {
      failure = failure ?? 'target pixelmap release failed';
      console.error('[PdfBridge] target pixelmap release failed: ' + JSON.stringify(e));
    }
  }
  try {
    await rendered.release();
  } catch (e) {
    failure = failure ?? 'rendered pixelmap release failed';
    console.error('[PdfBridge] rendered pixelmap release failed: ' + JSON.stringify(e));
  }
  if (data === undefined || failure !== undefined) {
    return { json: JSON.stringify({ ok: false, error: failure ?? 'render failed' }) };
  }
  return { json: JSON.stringify({ ok: true, width: p.width, height: bitmapHeight }), bytes: data };
}

/**
 * 释放当前文档与遗留草稿: releaseDocument 真实成功才移交/丢弃 owner; 失败保留原件
 * (文档留在 pdfDoc / 草稿留在 pendingDraft) 并返回 false, 不覆盖、不装成功。
 */
function releaseCurrent(): boolean {
  let ok = true;
  if (pendingDraft !== undefined) {
    const draft = pendingDraft;
    try {
      draft.releaseDocument();
      pendingDraft = undefined;
    } catch (e) {
      pendingDraft = draft;
      console.error('[PdfBridge] draft releaseDocument failed: ' + JSON.stringify(e));
      ok = false;
    }
  }
  if (pdfDoc === undefined) {
    return ok;
  }
  const doc = pdfDoc;
  try {
    doc.releaseDocument();
    pdfDoc = undefined;
  } catch (e) {
    console.error('[PdfBridge] releaseDocument failed: ' + JSON.stringify(e));
    ok = false;
  }
  return ok;
}

/** 未发布草稿的清理释放 (失败保留到 pendingDraft 待重试; 草稿未发布无 owner 移交)。 */
function tryReleaseDraft(doc: pdfService.PdfDocument): void {
  try {
    doc.releaseDocument();
  } catch (e) {
    pendingDraft = doc;
    console.error('[PdfBridge] releaseDocument (draft kept for retry) failed: ' + JSON.stringify(e));
  }
}
