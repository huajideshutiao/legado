/**
 * ArkTS 桥接公共二进制编解码工具 (base64 ↔ Uint8Array/ArrayBuffer, string → ArrayBuffer)。
 *
 * 统一 Crypto/FilePicker/Http/Image 下载等处近似拷贝的转换逻辑。
 * # @ohos.buffer 注意点 (各原拷贝点反复注释过的同一坑, 此处集中一份):
 * - Buffer 的字节长度属性是 length (无 byteLength);
 * - buffer.from 可能返回底层 ArrayBuffer 的池化子视图, 起始偏移必须取 byteOffset 而非 0,
 *   故 base64 → 二进制时复制到独立 ArrayBuffer, 避免视图偏移影响 API 读取;
 * - 反向转换同理: Uint8Array 可能是大 buffer 的子视图, 先复制再编码。
 */
import buffer from '@ohos.buffer';

/** base64 字符串 → Uint8Array (复制到独立 ArrayBuffer, 消除 Buffer 池化视图偏移)。 */
export function base64ToUint8(b64: string): Uint8Array {
  const buf = buffer.from(b64, 'base64');
  const ab = new ArrayBuffer(buf.length);
  const view = new Uint8Array(ab);
  view.set(new Uint8Array(buf.buffer, buf.byteOffset, buf.length));
  return view;
}

/** Uint8Array → base64 字符串 (先复制到独立 ArrayBuffer, 消除子视图偏移)。 */
export function uint8ToBase64(bytes: Uint8Array): string {
  const ab = new ArrayBuffer(bytes.byteLength);
  const view = new Uint8Array(ab);
  view.set(bytes);
  return buffer.from(ab).toString('base64');
}

/** ArrayBuffer → base64 字符串。 */
export function arrayBufferToBase64(ab: ArrayBuffer): string {
  return buffer.from(ab).toString('base64');
}

/** base64 字符串 → 独立 ArrayBuffer (与 [base64ToUint8] 同一复制语义, 直接给按 ArrayBuffer 收参的 API 用)。 */
export function base64ToArrayBuffer(b64: string): ArrayBuffer {
  const buf = buffer.from(b64, 'base64');
  const ab = new ArrayBuffer(buf.length);
  new Uint8Array(ab).set(new Uint8Array(buf.buffer, buf.byteOffset, buf.length));
  return ab;
}

/** 字符串 → UTF-8 编码的 ArrayBuffer (slice 出独立副本, 不共享 Buffer 池化底层)。 */
export function utf8ToArrayBuffer(text: string): ArrayBuffer {
  const b = buffer.from(text, 'utf-8');
  return b.buffer.slice(b.byteOffset, b.byteOffset + b.length);
}
