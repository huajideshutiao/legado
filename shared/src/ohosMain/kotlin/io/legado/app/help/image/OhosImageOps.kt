package io.legado.app.help.image

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.Base64Lenient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 鸿蒙端 [ImageOps] 真实实现 (KP8+)。
 *
 * # 存在意义
 * [io.legado.app.model.script.JsBindings] 构造时访问 [io.legado.app.model.script.JsBindingInjector.image],
 * 未注册会 checkNotNull 失败, 导致任何 `JsEngine.eval` 都跑不了。
 * 鸿蒙端 JS 引擎 ([io.legado.app.model.script.OhosJsEngine]) 解阻塞必须先注册一个 [ImageOps] 实现。
 *
 * # 当前实现 (KP8+: 基于 @ohos.multimedia.image PixelMap 的真实实现)
 *
 * 鸿蒙 `@ohos.multimedia.image` 仅提供 ArkTS API, Kotlin/Native 无法直接调用,
 * 通过 [OhosNativeBridge] napi 桥接 (tsfn 发请求 + @CName 回调返回结果) 实现真实像素操作。
 *
 * ## 句柄模型
 * [OhosImageRef] 持有原始 [ByteArray] + ArkTS 侧 PixelMap 句柄 ID (`pixelMapId`)。
 * - [decode]: 通过桥接调 `image.createImageSource(bytes).createPixelMap()`, ArkTS 侧存入
 *   `Map<pixelMapId, PixelMap>`, 返回 ID 给 Kotlin; 同时保留原始 bytes 供降级/encode 回退。
 *   图片字节作为 invokeImageSync 数据面裸参数 (napi ArrayBuffer) 直传, 不经 base64
 * - [encode]: 通过桥接调 `image.createImagePacker().packing(pixelMap, {format, quality})`,
 *   ArkTS 侧把 packed 字节经 imageCallback 第三参数 ArrayBuffer 裸传, Kotlin 拷为 ByteArray
 * - [size]: 通过桥接调 `pixelMap.getImageInfo().size`, 返回 `{w, h}`
 * - [crop]: 通过桥接调新 PixelMap + `crop({x, y, size:{w, h}})`, 返回新 pixelMapId
 * - [split]: 通过桥接循环 crop, 返回新 pixelMapId 列表
 * - [stitch]: 通过桥接创建大 PixelMap + 逐个 writePixels, 返回新 pixelMapId
 * - [rotate]: 通过桥接复制 PixelMap 后调 `pixelMap.rotate(degrees)` (in-place), 返回新 pixelMapId
 * - [flip]: 通过桥接复制 PixelMap 后调 `pixelMap.flip(isH, isV)` (in-place), 返回新 pixelMapId
 *
 * ## 混合协议 (KP9+, 与 WebView 桥同思路)
 * 大字节面 (decode 入参图片字节 / encode 出参 packed 字节) 走 napi 裸参数 (ArrayBuffer),
 * 控制面小字段 (pixelMapId/format/quality/宽高/错误) 保留 JSON。不经 base64, 避免 33% 体积
 * 膨胀与双端编解码拷贝, 且二进制保真 (图片是任意字节)。
 *
 * ## 降级策略 (桥接未就绪时, 与 KP5 占位行为一致)
 * [OhosNativeBridge.isImageBridgeReady] 返回 false (tsfn 未注入) 时, 所有操作降级为
 * 字节持有模式 (decode 持有原始 bytes, encode 返回原始 bytes, split 返回原图, etc.),
 * 让书源 JS 调用链不崩, 真实像素操作不可用但调用链能继续推进。
 *
 * # 调用链 (以 decode 为例)
 * ```
 * JS image.decode(bytes)
 *   → OhosImageOps.decode(bytes)
 *   → OhosNativeBridge.invokeImageSync("decode", "{}", bytes)  (bytes 走裸参 ArrayBuffer)
 *   → [tsfn] ArkTS image callback: createImageSource + createPixelMap → pixelMaps[id] = pixelMap
 *   → [napi @CName] legado_image_callback(requestId, {"ok":true,"pixelMapId":1})
 *   → OhosImageOps.decode 返回 OhosImageRef(bytes, pixelMapId=1)
 * ```
 *
 * # 影响
 * - 书源 JS 中 `image.decode/encode/split/stitch/crop/size` 在鸿蒙上全部真实工作
 * - 桥接未就绪时降级为占位行为 (不抛异常让 JS 调用链不崩)
 *
 * 参考: iOS 端 [IosImageOps] (UIImage + UIGraphics 真实实现);
 * base64 解码复用 commonMain [Base64Lenient] (ohosMain actual 已实现, 容忍 data URI 前缀);
 * napi 桥接模式参考 [OhosNativeBridge] Toast/Notification tsfn + FileDir @CName 回调。
 */
object OhosImageOps : ImageOps {

    /**
     * 鸿蒙端 [ImageRef] 真实实现: 持有原始 [ByteArray] + ArkTS 侧 PixelMap 句柄 ID。
     *
     * - [bytes]: 始终持有原始字节 (供降级 encode/回退使用)
     * - [pixelMapId]: ArkTS 侧 PixelMap 句柄 ID (decode 成功后非 null); null 表示桥接未就绪,
     *   操作降级为字节持有模式
     */
    private class OhosImageRef(val bytes: ByteArray, val pixelMapId: Long? = null) : ImageRef

    override fun decode(bytes: ByteArray): ImageRef {
        // 桥接未就绪: 降级为字节持有 (不抛异常, 让 JS 调用链不崩)
        if (!OhosNativeBridge.isImageBridgeReady()) {
            return OhosImageRef(bytes)
        }
        // 桥接就绪: 通过 napi 调 ArkTS image.createImageSource + createPixelMap
        // 图片字节走 invokeImageSync 数据面裸参数 (napi ArrayBuffer), 不再 base64 进 JSON
        val reply = OhosNativeBridge.invokeImageSync("decode", "{}", bytes)
        if (reply == null) {
            // 桥接调用失败 (tsfn 异常 / 超时): 降级为字节持有
            return OhosImageRef(bytes)
        }
        val resp = runCatching {
            KS_JSON.decodeFromString(
                BridgeResponse.serializer(),
                reply.json
            )
        }.getOrNull()
        if (resp == null || !resp.ok || resp.pixelMapId == null) {
            // ArkTS 解码失败: 抛异常 (与 ImageOps.decode 契约一致: "失败抛异常")
            throw IllegalArgumentException(
                "image.decode: 鸿蒙 PixelMap 解码失败(${bytes.size} bytes): ${resp?.error ?: "unknown"}"
            )
        }
        return OhosImageRef(bytes, resp.pixelMapId)
    }

    override fun decode(base64: String): ImageRef {
        // 用 Base64Lenient.decode 解码 (容忍 data:image/...;base64, 前缀, 与 ImageOps.decode 契约一致)
        val bytes = Base64Lenient.decode(base64)
        return decode(bytes)
    }

    override fun encode(img: ImageRef, format: String, quality: Int): ByteArray {
        val ref = img as? OhosImageRef ?: return ByteArray(0)
        val pixelMapId = ref.pixelMapId ?: return ref.bytes // 降级: 返回原始字节
        // 桥接就绪: 通过 napi 调 ArkTS image.createImagePacker().packing(pixelMap, {format, quality})
        val payload = KS_JSON.encodeToString(
            EncodePayload(pixelMapId = pixelMapId, format = format, quality = quality)
        )
        val reply = OhosNativeBridge.invokeImageSync("encode", payload)
        if (reply == null) return ref.bytes // 降级
        val resp = runCatching {
            KS_JSON.decodeFromString(
                BridgeResponse.serializer(),
                reply.json
            )
        }.getOrNull()
        if (resp == null || !resp.ok) {
            // 编码失败: 降级返回原始字节 (不抛异常, 让 JS 调用链不崩)
            return ref.bytes
        }
        // 数据面: packed 字节走 imageCallback 裸字节 (napi ArrayBuffer), 不再 base64
        return reply.bytes ?: ByteArray(0)
    }

    override fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef> {
        val ref = img as? OhosImageRef ?: return listOf(img)
        val pixelMapId = ref.pixelMapId ?: return listOf(img) // 降级
        val payload = KS_JSON.encodeToString(
            SplitPayload(pixelMapId = pixelMapId, rows = rows, cols = cols)
        )
        val reply = OhosNativeBridge.invokeImageSync("split", payload)
        if (reply == null) return listOf(img) // 降级
        val resp = runCatching {
            KS_JSON.decodeFromString(
                BridgeResponse.serializer(),
                reply.json
            )
        }.getOrNull()
        if (resp == null || !resp.ok || resp.pixelMapIds == null) {
            return listOf(img) // 降级
        }
        // 返回真实切分结果: 每个 pixelMapId 包装为 OhosImageRef (bytes 保留原图的, 仅供降级回退)
        return resp.pixelMapIds.map { OhosImageRef(ref.bytes, it) }
    }

    override fun stitch(imgs: List<ImageRef>, direction: String): ImageRef {
        if (imgs.isEmpty()) return OhosImageRef(ByteArray(0))
        val refs = imgs.mapNotNull { it as? OhosImageRef }
        if (refs.size != imgs.size) return imgs.first() // 含非本类 ImageRef, 降级
        val pixelMapIds = refs.mapNotNull { it.pixelMapId }
        if (pixelMapIds.size != refs.size) return imgs.first() // 含降级 ref, 降级
        val payload = KS_JSON.encodeToString(
            StitchPayload(pixelMapIds = pixelMapIds, direction = direction)
        )
        val reply = OhosNativeBridge.invokeImageSync("stitch", payload)
        if (reply == null) return imgs.first() // 降级
        val resp = runCatching {
            KS_JSON.decodeFromString(
                BridgeResponse.serializer(),
                reply.json
            )
        }.getOrNull()
        if (resp == null || !resp.ok || resp.pixelMapId == null) {
            return imgs.first() // 降级
        }
        return OhosImageRef(refs.first().bytes, resp.pixelMapId)
    }

    override fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef {
        val ref = img as? OhosImageRef ?: return img
        val pixelMapId = ref.pixelMapId ?: return img // 降级
        val payload = KS_JSON.encodeToString(
            CropPayload(pixelMapId = pixelMapId, x = x, y = y, w = w, h = h)
        )
        val reply = OhosNativeBridge.invokeImageSync("crop", payload)
        if (reply == null) return img // 降级
        val resp = runCatching {
            KS_JSON.decodeFromString(
                BridgeResponse.serializer(),
                reply.json
            )
        }.getOrNull()
        if (resp == null || !resp.ok || resp.pixelMapId == null) {
            return img // 降级
        }
        return OhosImageRef(ref.bytes, resp.pixelMapId)
    }

    override fun rotate(img: ImageRef, deg: Int): ImageRef {
        val ref = img as? OhosImageRef ?: return img
        val pixelMapId = ref.pixelMapId ?: return img // 降级
        // PixelMap.rotate 正值为顺时针; 归一化到 [0,360) (负值旋转等价于 360+deg)
        val degrees = ((deg % 360) + 360) % 360
        val payload = KS_JSON.encodeToString(
            RotatePayload(pixelMapId = pixelMapId, degrees = degrees)
        )
        val reply = OhosNativeBridge.invokeImageSync("rotate", payload)
        if (reply == null) return img // 降级
        val resp = runCatching {
            KS_JSON.decodeFromString(
                BridgeResponse.serializer(),
                reply.json
            )
        }.getOrNull()
        if (resp == null || !resp.ok || resp.pixelMapId == null) {
            return img // 降级
        }
        return OhosImageRef(ref.bytes, resp.pixelMapId)
    }

    override fun flip(img: ImageRef, direction: String): ImageRef {
        val ref = img as? OhosImageRef ?: return img
        val pixelMapId = ref.pixelMapId ?: return img // 降级
        val isH = direction.lowercase() == "h"
        val isV = direction.lowercase() == "v"
        if (!isH && !isV) {
            // 与其余端一致: 非法方向抛异常
            throw IllegalArgumentException("image.flip: direction 仅支持 h/v，收到 $direction")
        }
        val payload = KS_JSON.encodeToString(
            FlipPayload(pixelMapId = pixelMapId, isH = isH, isV = isV)
        )
        val reply = OhosNativeBridge.invokeImageSync("flip", payload)
        if (reply == null) return img // 降级
        val resp = runCatching {
            KS_JSON.decodeFromString(
                BridgeResponse.serializer(),
                reply.json
            )
        }.getOrNull()
        if (resp == null || !resp.ok || resp.pixelMapId == null) {
            return img // 降级
        }
        return OhosImageRef(ref.bytes, resp.pixelMapId)
    }

    override fun size(img: ImageRef): Map<String, Int> {
        val ref = img as? OhosImageRef ?: return emptyMap()
        val pixelMapId = ref.pixelMapId ?: return emptyMap() // 降级
        val payload = KS_JSON.encodeToString(SizePayload(pixelMapId = pixelMapId))
        val reply = OhosNativeBridge.invokeImageSync("size", payload)
        if (reply == null) return emptyMap() // 降级
        val resp = runCatching {
            KS_JSON.decodeFromString(
                BridgeResponse.serializer(),
                reply.json
            )
        }.getOrNull()
        if (resp == null || !resp.ok || resp.width == null || resp.height == null) {
            return emptyMap() // 降级
        }
        // key 用 "w"/"h" (与 IosImageOps.size / DesktopImageOps.size 一致)
        return mapOf("w" to resp.width, "h" to resp.height)
    }

    // ===== 桥接 payload 数据类 (与 ArkTS 侧 JSON 协议对齐, 混合协议: 大字节走裸参数) =====

    /** encode 请求: pixelMapId + 格式 + 质量。 */
    @Serializable
    private data class EncodePayload(val pixelMapId: Long, val format: String, val quality: Int)

    /** size 请求: pixelMapId。 */
    @Serializable
    private data class SizePayload(val pixelMapId: Long)

    /** crop 请求: pixelMapId + 区域。 */
    @Serializable
    private data class CropPayload(val pixelMapId: Long, val x: Int, val y: Int, val w: Int, val h: Int)

    /** split 请求: pixelMapId + 行列数。 */
    @Serializable
    private data class SplitPayload(val pixelMapId: Long, val rows: Int, val cols: Int)

    /** stitch 请求: pixelMapId 列表 + 方向。 */
    @Serializable
    private data class StitchPayload(val pixelMapIds: List<Long>, val direction: String)

    /** rotate 请求: pixelMapId + 角度 (归一化到 [0,360), 正值顺时针)。 */
    @Serializable
    private data class RotatePayload(val pixelMapId: Long, val degrees: Int)

    /** flip 请求: pixelMapId + 翻转轴 (isH 水平镜像 / isV 垂直镜像)。 */
    @Serializable
    private data class FlipPayload(val pixelMapId: Long, val isH: Boolean, val isV: Boolean)

    /**
     * 桥接统一响应 (ArkTS → Kotlin, 混合协议: 控制面 JSON + 数据面裸字节)。
     * 所有操作的响应都走此结构, 不同的操作读取不同字段:
     * - decode/crop/stitch/rotate/flip: 读 [pixelMapId]
     * - split: 读 [pixelMapIds]
     * - encode: [ok]=true, packed 字节走 [OhosNativeBridge.OhosBinaryBridgeResponse.bytes] 裸字节
     * - size: 读 [width]/[height]
     * - 失败: [ok]=false, [error] 含错误信息
     */
    @Serializable
    private data class BridgeResponse(
        val ok: Boolean = false,
        val pixelMapId: Long? = null,
        val pixelMapIds: List<Long>? = null,
        val width: Int? = null,
        val height: Int? = null,
        val error: String? = null,
    )
}
