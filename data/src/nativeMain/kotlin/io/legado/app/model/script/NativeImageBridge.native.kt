@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import io.legado.app.help.image.ImageOps
import io.legado.app.help.image.ImageRef
import io.legado.app.napi.quickjs.JSContext
import io.legado.app.napi.quickjs.JSRuntime
import io.legado.app.napi.quickjs.JSValue
import io.legado.app.napi.quickjs.JSClassDef
import io.legado.app.napi.quickjs.JS_Call
import io.legado.app.napi.quickjs.JS_DupValue
import io.legado.app.napi.quickjs.JS_FreeValue
import io.legado.app.napi.quickjs.JS_GetClassID
import io.legado.app.napi.quickjs.JS_GetGlobalObject
import io.legado.app.napi.quickjs.JS_GetLength
import io.legado.app.napi.quickjs.JS_GetOpaque
import io.legado.app.napi.quickjs.JS_GetPropertyStr
import io.legado.app.napi.quickjs.JS_GetPropertyUint32
import io.legado.app.napi.quickjs.JS_IsArray
import io.legado.app.napi.quickjs.JS_IsArrayBuffer
import io.legado.app.napi.quickjs.JS_NewArray
import io.legado.app.napi.quickjs.JS_NewCFunctionData
import io.legado.app.napi.quickjs.JS_NewClass
import io.legado.app.napi.quickjs.JS_NewClassID
import io.legado.app.napi.quickjs.JS_NewObject
import io.legado.app.napi.quickjs.JS_NewObjectClass
import io.legado.app.napi.quickjs.JS_SetOpaque
import io.legado.app.napi.quickjs.JS_SetPropertyStr
import io.legado.app.napi.quickjs.JS_SetPropertyUint32
import io.legado.app.napi.quickjs.JS_TAG_UNDEFINED
import io.legado.app.napi.quickjs.JS_ToFloat64
import io.legado.app.napi.quickjs.JS_ToInt32
import io.legado.app.napi.quickjs.qjs_ArrayBufferRead
import io.legado.app.napi.quickjs.qjs_ArrayBufferSize
import io.legado.app.napi.quickjs.qjs_FreeCString
import io.legado.app.napi.quickjs.qjs_IsException
import io.legado.app.napi.quickjs.qjs_IsNumber
import io.legado.app.napi.quickjs.qjs_IsString
import io.legado.app.napi.quickjs.qjs_IsUndefined
import io.legado.app.napi.quickjs.qjs_NewBool
import io.legado.app.napi.quickjs.qjs_NewCFunction
import io.legado.app.napi.quickjs.qjs_NewInt32
import io.legado.app.napi.quickjs.qjs_ToCString
import io.legado.app.utils.Base64Lenient
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.refTo
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.useContents
import platform.posix.memcpy

/**
 * native 端 (iOS/鸿蒙) `image` 绑定的 QuickJS 桥。
 *
 * # 与旧 __nativeDispatch 数字 handle 协议的区别
 * ImageRef 是被脚本真实持有的图片对象: 用 SDK 用户类 + opaque 托管 StableRef,
 * JSValue 存活期间 Kotlin 侧 ImageOps/ImageRef 强引用就存活; JSValue 回收后 finalizer
 * 只释放这一份 StableRef, 不执行 JS、不提前 close 仍在别处使用的图。
 * 旧桥的 numeric handle 在 eval 末尾统一注销, 无法表达脚本跨语句持有的图片, 故不复用。
 *
 * # 类注册
 * QuickJS 的类 ID 从 JSRuntime 的 js_class_id_alloc 分配 (JS_NewClassID), 每个 runtime
 * 初始化一次并记录在 [NativeImageBridgeState]; 该 state 挂在持有 rt/ctx 的 NativeJsScope 上,
 * 借用视图共享同一实例。每次 toJsValue 不再注册类。
 */
internal class NativeImageBridgeState(
    val opsClassId: UInt,
    val refClassId: UInt,
)

/** func_data 携带的后端: 真实 ImageOps + 本 runtime 的两个类 ID (不持 scope/ctx/JSValue)。 */
private class ImageOpsBackend(
    val ops: ImageOps,
    val opsClassId: UInt,
    val refClassId: UInt,
)

/** ImageRef JS 对象的 opaque 数据: 只持真实 Kotlin 图片引用。 */
private class ImageRefHolder(val ref: ImageRef)

private const val IMAGE_CLASS_NAME_OPS = "LegadoImageOps"
private const val IMAGE_CLASS_NAME_REF = "LegadoImageRef"

/** 图片方法 magic (JSCFunctionData 的有限操作常量)。 */
private const val MAGIC_DECODE = 1
private const val MAGIC_ENCODE = 2
private const val MAGIC_SPLIT = 3
private const val MAGIC_STITCH = 4
private const val MAGIC_CROP = 5
private const val MAGIC_ROTATE = 6
private const val MAGIC_FLIP = 7
private const val MAGIC_SIZE = 8

/** JS 方法名/arity 与 ImageOps 现有签名一一对应。 */
private class ImageMethodSpec(val name: String, val length: Int, val magic: Int)

private val IMAGE_METHODS = listOf(
    ImageMethodSpec("decode", 1, MAGIC_DECODE),
    ImageMethodSpec("encode", 3, MAGIC_ENCODE),
    ImageMethodSpec("split", 3, MAGIC_SPLIT),
    ImageMethodSpec("stitch", 2, MAGIC_STITCH),
    ImageMethodSpec("crop", 5, MAGIC_CROP),
    ImageMethodSpec("rotate", 2, MAGIC_ROTATE),
    ImageMethodSpec("flip", 2, MAGIC_FLIP),
    ImageMethodSpec("size", 1, MAGIC_SIZE),
)

/**
 * 每个 JSRuntime 调用一次: 分配两个用户类 ID 并注册 finalizer。
 *
 * 失败直接抛错 (调用方在 getRuntimeScope/createStandaloneScope 释放 rt/ctx), 不静默降级。
 */
internal fun initializeNativeImageBridge(rt: CPointer<JSRuntime>): NativeImageBridgeState {
    val opsClassId = newClassId(rt, IMAGE_CLASS_NAME_OPS)
    val refClassId = newClassId(rt, IMAGE_CLASS_NAME_REF)
    return NativeImageBridgeState(opsClassId, refClassId)
}

private fun newClassId(rt: CPointer<JSRuntime>, className: String): UInt = memScoped {
    val idVar = alloc<UIntVar>()
    idVar.value = 0u
    val id = JS_NewClassID(rt, idVar.ptr)
    check(id != 0u) { "QuickJS 图片桥类 ID 分配失败: $className" }
    val def = alloc<JSClassDef>()
    def.class_name = className.cstr.ptr
    def.finalizer = imageOpaqueFinalizer
    def.gc_mark = null
    def.call = null
    def.exotic = null
    check(JS_NewClass(rt, id, def.ptr) == 0) { "QuickJS 图片桥类注册失败: $className" }
    id
}

/** ImageOps/ImageRef 用户类对象的 finalizer: 只释放 opaque 持有的 StableRef。 */
private val imageOpaqueFinalizer = staticCFunction { _: CPointer<JSRuntime>?, value: CValue<JSValue> ->
    disposeOpaque(value)
}

/**
 * 通过对象自身 classID 取 opaque 并释放 StableRef (按运行期注册的类, 不依赖 ThreadLocal)。
 * finalizer 不允许 Kotlin 异常穿过 C 边界, 失败只放弃本次释放。
 */
private fun disposeOpaque(value: CValue<JSValue>) {
    try {
        val opaque = JS_GetOpaque(value, JS_GetClassID(value)) ?: return
        // 两个用户类的 opaque 都是 StableRef, 具体类型只在提取端区分; dispose 语义相同。
        opaque.asStableRef<Any>().dispose()
    } catch (_: Throwable) {
        // finalizer 运行期无法向 JS 层报告; 不把异常抛穿 C 回调边界
    }
}

/** 把 ImageOps 包装为 JS 对象: 八个方法通过 func_data 共享同一个后端 token。 */
internal fun wrapImageOps(
    ctx: CPointer<JSContext>,
    ops: ImageOps,
    state: NativeImageBridgeState,
): CValue<JSValue> {
    val obj = JS_NewObject(ctx)
    if (qjs_IsException(obj) != 0) {
        JS_FreeValue(ctx, obj)
        throw IllegalStateException("创建 image 对象失败")
    }
    val backend = ImageOpsBackend(ops, state.opsClassId, state.refClassId)
    val backendRef = try {
        StableRef.create(backend)
    } catch (t: Throwable) {
        JS_FreeValue(ctx, obj)
        throw t
    }
    val token = JS_NewObjectClass(ctx, state.opsClassId)
    if (qjs_IsException(token) != 0) {
        JS_FreeValue(ctx, token)
        backendRef.dispose()
        JS_FreeValue(ctx, obj)
        throw IllegalStateException("创建 image 后端 token 失败")
    }
    if (JS_SetOpaque(token, backendRef.asCPointer()) != 0) {
        JS_FreeValue(ctx, token)
        backendRef.dispose()
        JS_FreeValue(ctx, obj)
        throw IllegalStateException("设置 image 后端 opaque 失败")
    }
    try {
        for (method in IMAGE_METHODS) {
            memScoped {
                val data = allocArray<JSValue>(1)
                token.useContents {
                    memcpy(
                        interpretCPointer<ByteVar>(data[0L].rawPtr),
                        interpretCPointer<ByteVar>(this.rawPtr),
                        sizeOf<JSValue>().toULong()
                    )
                }
                val fn = JS_NewCFunctionData(
                    ctx, imageMethodFn, method.length, method.magic, 1, data
                )
                if (qjs_IsException(fn) != 0) {
                    JS_FreeValue(ctx, fn)
                    throw IllegalStateException("创建 image.${method.name} 失败")
                }
                // JS_SetPropertyStr 消费 fn (异常路径同样释放), 不能再 JS_FreeValue
                if (JS_SetPropertyStr(ctx, obj, method.name, fn) != 0) {
                    throw IllegalStateException("挂载 image.${method.name} 失败")
                }
            }
        }
    } catch (t: Throwable) {
        JS_FreeValue(ctx, obj)
        JS_FreeValue(ctx, token)
        throw t
    }
    // 方法 func_data 已各持一份 token 引用, 释放构造期的本地引用
    JS_FreeValue(ctx, token)
    return obj
}

/** 把 ImageRef 包装为真实 JS 对象: opaque 持本次 Kotlin 图片引用。 */
internal fun wrapImageRef(
    ctx: CPointer<JSContext>,
    ref: ImageRef,
    refClassId: UInt,
): CValue<JSValue> {
    val obj = JS_NewObjectClass(ctx, refClassId)
    if (qjs_IsException(obj) != 0) {
        JS_FreeValue(ctx, obj)
        throw IllegalStateException("创建 ImageRef 对象失败")
    }
    val holderRef = try {
        StableRef.create(ImageRefHolder(ref))
    } catch (t: Throwable) {
        JS_FreeValue(ctx, obj)
        throw t
    }
    if (JS_SetOpaque(obj, holderRef.asCPointer()) != 0) {
        holderRef.dispose()
        JS_FreeValue(ctx, obj)
        throw IllegalStateException("设置 ImageRef opaque 失败")
    }
    return obj
}

/** 从新 ImageRef 类对象解回 Kotlin 图片引用; 其他对象返回 null。 */
internal fun unwrapImageRef(
    ctx: CPointer<JSContext>,
    value: CValue<JSValue>,
    state: NativeImageBridgeState,
): ImageRef? {
    if (JS_GetClassID(value) != state.refClassId) return null
    val opaque = JS_GetOpaque(value, state.refClassId) ?: return null
    return opaque.asStableRef<ImageRefHolder>().get().ref
}

/** JSCFunctionData: 八个图片方法共用的 C 入口, magic 区分操作。 */
internal val imageMethodFn = staticCFunction {
        ctx: CPointer<JSContext>?,
        thisVal: CValue<JSValue>,
        argc: Int,
        argv: CPointer<JSValue>?,
        magic: Int,
        funcData: CPointer<JSValue>? ->
    imageMethodImpl(ctx, thisVal, argc, argv, magic, funcData)
}

private fun imageMethodImpl(
    ctx: CPointer<JSContext>?,
    @Suppress("UNUSED_PARAMETER") thisVal: CValue<JSValue>,
    argc: Int,
    argv: CPointer<JSValue>?,
    magic: Int,
    funcData: CPointer<JSValue>?,
): CValue<JSValue> {
    ctx ?: return jsUndefined()
    if (funcData == null) {
        return NativeJsExtensionsBridge.jsThrowError(ctx, "image 后端 token 缺失")
    }
    return try {
        val token = funcData[0L].readValue()
        val opaque = JS_GetOpaque(token, JS_GetClassID(token))
            ?: return NativeJsExtensionsBridge.jsThrowError(ctx, "image 后端 token 失效")
        val backend = opaque.asStableRef<ImageOpsBackend>().get()
        dispatchImageMethod(ctx, backend, magic, argc, argv)
    } catch (t: Throwable) {
        NativeJsExtensionsBridge.jsThrowError(ctx, t.message ?: "image 调用失败")
    }
}

private fun dispatchImageMethod(
    ctx: CPointer<JSContext>,
    backend: ImageOpsBackend,
    magic: Int,
    argc: Int,
    argv: CPointer<JSValue>?,
): CValue<JSValue> {
    when (magic) {
        MAGIC_DECODE -> {
            requireArgc("decode", argc, 1)
            val bytes = readBytesArg(ctx, argv!![0L].readValue())
            return wrapImageRef(ctx, backend.ops.decode(bytes), backend.refClassId)
        }

        MAGIC_ENCODE -> {
            requireArgc("encode", argc, 3)
            val imgVal = argv!![0L].readValue()
            val img = readRefArg(ctx, imgVal, backend, "encode 第一个参数")
            val format = readStringArg(ctx, argv[1L].readValue())
            val quality = readIntArg(ctx, argv[2L].readValue())
            return NativeJsEngine.byteArrayToJsUint8Array(
                ctx, backend.ops.encode(img, format, quality)
            )
        }

        MAGIC_SPLIT -> {
            requireArgc("split", argc, 3)
            val imgVal = argv!![0L].readValue()
            val img = readRefArg(ctx, imgVal, backend, "split 第一个参数")
            val rows = readIntArg(ctx, argv[1L].readValue())
            val cols = readIntArg(ctx, argv[2L].readValue())
            return wrapImageRefArray(
                ctx, backend.ops.split(img, rows, cols), backend.refClassId,
                img to imgVal
            )
        }

        MAGIC_STITCH -> {
            requireArgc("stitch", argc, 2)
            val listVal = argv!![0L].readValue()
            if (!JS_IsArray(listVal)) {
                throw IllegalArgumentException("image.stitch 第一个参数需为 ImageRef 数组")
            }
            val (refs, refVals) = readRefArrayArg(ctx, listVal, backend, "stitch 第一个参数")
            try {
                val direction = readStringArg(ctx, argv[1L].readValue())
                val result = backend.ops.stitch(refs, direction)
                val sameIndex = refs.indexOfFirst { it === result }
                if (sameIndex >= 0) {
                    JS_DupValue(ctx, refVals[sameIndex])
                } else {
                    wrapImageRef(ctx, result, backend.refClassId)
                }
            } finally {
                refVals.forEach { JS_FreeValue(ctx, it) }
            }
        }

        MAGIC_CROP -> {
            requireArgc("crop", argc, 5)
            val imgVal = argv!![0L].readValue()
            val img = readRefArg(ctx, imgVal, backend, "crop 第一个参数")
            val x = readIntArg(ctx, argv[1L].readValue())
            val y = readIntArg(ctx, argv[2L].readValue())
            val w = readIntArg(ctx, argv[3L].readValue())
            val h = readIntArg(ctx, argv[4L].readValue())
            val result = backend.ops.crop(img, x, y, w, h)
            return if (result === img) {
                JS_DupValue(ctx, imgVal)
            } else {
                wrapImageRef(ctx, result, backend.refClassId)
            }
        }

        MAGIC_ROTATE -> {
            requireArgc("rotate", argc, 2)
            val imgVal = argv!![0L].readValue()
            val img = readRefArg(ctx, imgVal, backend, "rotate 第一个参数")
            val deg = readIntArg(ctx, argv[1L].readValue())
            val result = backend.ops.rotate(img, deg)
            return if (result === img) {
                JS_DupValue(ctx, imgVal)
            } else {
                wrapImageRef(ctx, result, backend.refClassId)
            }
        }

        MAGIC_FLIP -> {
            requireArgc("flip", argc, 2)
            val imgVal = argv!![0L].readValue()
            val img = readRefArg(ctx, imgVal, backend, "flip 第一个参数")
            val direction = readStringArg(ctx, argv[1L].readValue())
            val result = backend.ops.flip(img, direction)
            return if (result === img) {
                JS_DupValue(ctx, imgVal)
            } else {
                wrapImageRef(ctx, result, backend.refClassId)
            }
        }

        MAGIC_SIZE -> {
            requireArgc("size", argc, 1)
            val imgVal = argv!![0L].readValue()
            val img = readRefArg(ctx, imgVal, backend, "size 参数")
            return wrapSizeMap(ctx, backend.ops.size(img))
        }

        else -> throw IllegalArgumentException("未知 image 方法: magic=$magic")
    }
}

private fun requireArgc(name: String, actual: Int, expected: Int) {
    if (actual != expected) {
        throw IllegalArgumentException("image.$name 需要 $expected 个参数, 收到 $actual")
    }
}

/** ImageRef 参数只接受本桥注册类 + opaque 的真实对象, 拒绝数字/Map/字符串冒充。 */
private fun readRefArg(
    ctx: CPointer<JSContext>,
    value: CValue<JSValue>,
    backend: ImageOpsBackend,
    argName: String,
): ImageRef {
    if (JS_GetClassID(value) != backend.refClassId) {
        throw IllegalArgumentException("$argName 需为 image 方法返回的 ImageRef 对象")
    }
    val opaque = JS_GetOpaque(value, backend.refClassId)
        ?: throw IllegalStateException("$argName 的 ImageRef opaque 丢失")
    return opaque.asStableRef<ImageRefHolder>().get().ref
}

/** JS 数组读为 ImageRef 列表, 同时保留每个元素 JSValue 供同引用复用。 */
private fun readRefArrayArg(
    ctx: CPointer<JSContext>,
    arrayValue: CValue<JSValue>,
    backend: ImageOpsBackend,
    argName: String,
): Pair<List<ImageRef>, List<CValue<JSValue>>> = memScoped {
    val pLen = alloc<LongVar>()
    if (JS_GetLength(ctx, arrayValue, pLen.ptr) != 0) {
        throw IllegalArgumentException("$argName 长度读取失败")
    }
    val len = pLen.value.toInt()
    if (len < 0) throw IllegalArgumentException("$argName 长度无效: $len")
    val refs = ArrayList<ImageRef>(len)
    val vals = ArrayList<CValue<JSValue>>(len)
    try {
        for (i in 0 until len) {
            val elem = JS_GetPropertyUint32(ctx, arrayValue, i.toUInt())
            try {
                if (qjs_IsException(elem) != 0) {
                    throw IllegalArgumentException("$argName[$i] 读取失败")
                }
                refs.add(readRefArg(ctx, elem, backend, "$argName[$i]"))
                vals.add(elem)
            } catch (t: Throwable) {
                JS_FreeValue(ctx, elem)
                throw t
            }
        }
    } catch (t: Throwable) {
        vals.forEach { JS_FreeValue(ctx, it) }
        throw t
    }
    return refs to vals
}

/** split 结果: 核心 JS Array + 每个元素真实 ImageRef; 同引用入参复用原 JSValue。 */
private fun wrapImageRefArray(
    ctx: CPointer<JSContext>,
    refs: List<ImageRef>,
    refClassId: UInt,
    sameInput: Pair<ImageRef, CValue<JSValue>>?,
): CValue<JSValue> {
    val array = JS_NewArray(ctx)
    if (qjs_IsException(array) != 0) {
        JS_FreeValue(ctx, array)
        throw IllegalStateException("创建 ImageRef 数组失败")
    }
    try {
        refs.forEachIndexed { index, ref ->
            val elem = if (sameInput != null && ref === sameInput.first) {
                JS_DupValue(ctx, sameInput.second)
            } else {
                wrapImageRef(ctx, ref, refClassId)
            }
            if (JS_SetPropertyUint32(ctx, array, index.toUInt(), elem) != 0) {
                throw IllegalStateException("写入 ImageRef 数组第 $index 项失败")
            }
        }
        setArrayMethods(ctx, array)
        return array
    } catch (t: Throwable) {
        JS_FreeValue(ctx, array)
        throw t
    }
}

/** 给 split 结果补通用 List 读面 (get/size/isEmpty), 方法通过 this 读数组, 不捕获数组。 */
private fun setArrayMethods(ctx: CPointer<JSContext>, array: CValue<JSValue>) {
    attachArrayMethod(ctx, array, "get", 1, qjs_NewCFunction(ctx, arrayGetFn, "get", 1))
    attachArrayMethod(ctx, array, "size", 0, qjs_NewCFunction(ctx, arraySizeFn, "size", 0))
    attachArrayMethod(ctx, array, "isEmpty", 0, qjs_NewCFunction(ctx, arrayIsEmptyFn, "isEmpty", 0))
}

private fun attachArrayMethod(
    ctx: CPointer<JSContext>,
    array: CValue<JSValue>,
    name: String,
    method: CValue<JSValue>,
) {
    if (qjs_IsException(method) != 0) {
        JS_FreeValue(ctx, method)
        throw IllegalStateException("创建数组 $name 方法失败")
    }
    // JS_SetPropertyStr 消费 method (异常路径同样释放), 不能再 JS_FreeValue
    if (JS_SetPropertyStr(ctx, array, name, method) != 0) {
        throw IllegalStateException("挂载数组 $name 方法失败")
    }
}

private val arrayGetFn = staticCFunction {
        ctx: CPointer<JSContext>?,
        thisVal: CValue<JSValue>,
        argc: Int,
        argv: CPointer<JSValue>? ->
    arrayGetImpl(ctx, thisVal, argc, argv)
}

private fun arrayGetImpl(
    ctx: CPointer<JSContext>?,
    thisVal: CValue<JSValue>,
    argc: Int,
    argv: CPointer<JSValue>?,
): CValue<JSValue> {
    ctx ?: return jsUndefined()
    return try {
        if (argc != 1 || argv == null) {
            throw IllegalArgumentException("get(index) 需要 1 个参数")
        }
        val index = readIndexArg(ctx, argv[0L].readValue())
        val len = readArrayLength(ctx, thisVal)
        if (index !in 0 until len) {
            throw IllegalArgumentException("IndexOutOfBoundsException: Index: $index, Size: $len")
        }
        JS_GetPropertyUint32(ctx, thisVal, index.toUInt())
    } catch (t: Throwable) {
        NativeJsExtensionsBridge.jsThrowError(ctx, t.message ?: "get(index) 失败")
    }
}

private fun readIndexArg(ctx: CPointer<JSContext>, value: CValue<JSValue>): Int {
    if (qjs_IsNumber(value) != 0) {
        return memScoped {
            val pres = alloc<IntVar>()
            if (JS_ToInt32(ctx, pres.ptr, value) != 0) {
                throw IllegalArgumentException("索引不是整数")
            }
            pres.value
        }
    }
    if (qjs_IsString(value) != 0) {
        return readStringArg(ctx, value).toIntOrNull()
            ?: throw IllegalArgumentException("索引不是数字字符串")
    }
    throw IllegalArgumentException("索引需为整数或数字字符串")
}

private val arraySizeFn = staticCFunction {
        ctx: CPointer<JSContext>?,
        thisVal: CValue<JSValue>,
        argc: Int,
        argv: CPointer<JSValue>? ->
    arraySizeImpl(ctx, thisVal, argc, argv)
}

private fun arraySizeImpl(
    ctx: CPointer<JSContext>?,
    thisVal: CValue<JSValue>,
    @Suppress("UNUSED_PARAMETER") argc: Int,
    @Suppress("UNUSED_PARAMETER") argv: CPointer<JSValue>?,
): CValue<JSValue> {
    val ctxNotNull = ctx ?: return jsUndefined()
    return try {
        qjs_NewInt32(ctxNotNull, readArrayLength(ctxNotNull, thisVal))
    } catch (t: Throwable) {
        NativeJsExtensionsBridge.jsThrowError(ctxNotNull, t.message ?: "size() 失败")
    }
}

private val arrayIsEmptyFn = staticCFunction {
        ctx: CPointer<JSContext>?,
        thisVal: CValue<JSValue>,
        argc: Int,
        argv: CPointer<JSValue>? ->
    arrayIsEmptyImpl(ctx, thisVal, argc, argv)
}

private fun arrayIsEmptyImpl(
    ctx: CPointer<JSContext>?,
    thisVal: CValue<JSValue>,
    @Suppress("UNUSED_PARAMETER") argc: Int,
    @Suppress("UNUSED_PARAMETER") argv: CPointer<JSValue>?,
): CValue<JSValue> {
    val ctxNotNull = ctx ?: return jsUndefined()
    return try {
        qjs_NewBool(ctxNotNull, if (readArrayLength(ctxNotNull, thisVal) == 0) 1 else 0)
    } catch (t: Throwable) {
        NativeJsExtensionsBridge.jsThrowError(ctxNotNull, t.message ?: "isEmpty() 失败")
    }
}

private fun readArrayLength(ctx: CPointer<JSContext>, value: CValue<JSValue>): Int {
    if (!JS_IsArray(value)) {
        throw IllegalArgumentException("方法需以 split 返回的图片数组为 this")
    }
    return memScoped {
        val pLen = alloc<LongVar>()
        if (JS_GetLength(ctx, value, pLen.ptr) != 0) {
            throw IllegalArgumentException("数组长度读取失败")
        }
        pLen.value.toInt().coerceAtLeast(0)
    }
}

/** size 返回新建 Map: 普通 JS 数据 + 既有 __wrapJsonMap 门面 (不进 JSON/不注册 handle)。 */
private fun wrapSizeMap(ctx: CPointer<JSContext>, size: Map<String, Int>): CValue<JSValue> {
    val data = JS_NewObject(ctx)
    if (qjs_IsException(data) != 0) {
        JS_FreeValue(ctx, data)
        throw IllegalStateException("创建 size 对象失败")
    }
    try {
        size.forEach { (key, value) ->
            if (JS_SetPropertyStr(ctx, data, key, qjs_NewInt32(ctx, value)) != 0) {
                throw IllegalStateException("写入 size.$key 失败")
            }
        }
        val global = JS_GetGlobalObject(ctx)
        try {
            val wrapper = JS_GetPropertyStr(ctx, global, "__wrapJsonMap")
            try {
                if (qjs_IsException(wrapper) != 0) {
                    throw IllegalStateException("读取 __wrapJsonMap 失败")
                }
                if (qjs_IsUndefined(wrapper) != 0) {
                    throw IllegalStateException("__wrapJsonMap 未定义")
                }
                return memScoped {
                    val argv = allocArray<JSValue>(1)
                    data.useContents {
                        memcpy(
                            interpretCPointer<ByteVar>(argv[0L].rawPtr),
                            interpretCPointer<ByteVar>(this.rawPtr),
                            sizeOf<JSValue>().toULong()
                        )
                    }
                    JS_Call(ctx, wrapper, global, 1, argv)
                }
            } finally {
                JS_FreeValue(ctx, wrapper)
            }
        } finally {
            JS_FreeValue(ctx, global)
        }
    } finally {
        JS_FreeValue(ctx, data)
    }
}

/** 参数: Uint8Array/TypedArray、ArrayBuffer、与 ByteArray 兼容的 JS Array、Base64 字符串。 */
private fun readBytesArg(ctx: CPointer<JSContext>, value: CValue<JSValue>): ByteArray {
    if (qjs_IsString(value) != 0) {
        return Base64Lenient.decode(readStringArg(ctx, value))
    }
    NativeJsEngine.tryGetUint8ArrayBytes(ctx, value)?.let { return it }
    readEmptyTypedArrayBytes(ctx, value)?.let { return it }
    if (JS_IsArrayBuffer(value)) {
        return readArrayBufferBytes(ctx, value)
    }
    if (JS_IsArray(value)) {
        return readJsArrayBytes(ctx, value)
    }
    throw IllegalArgumentException(
        "image.decode 参数需为 Uint8Array、ArrayBuffer、字节数组或 Base64 字符串"
    )
}

/** 零长度 TypedArray (如 subarray(i,i)) 是合法空字节; 共用 helper 对空视图返回 null。 */
private fun readEmptyTypedArrayBytes(
    ctx: CPointer<JSContext>,
    value: CValue<JSValue>,
): ByteArray? {
    val buffer = JS_GetPropertyStr(ctx, value, "buffer")
    try {
        if (qjs_IsException(buffer) != 0 || !JS_IsArrayBuffer(buffer)) return null
        val byteLength = JS_GetPropertyStr(ctx, value, "byteLength")
        try {
            if (qjs_IsException(byteLength) != 0 || qjs_IsNumber(byteLength) == 0) return null
            val len = memScoped {
                val pres = alloc<IntVar>()
                if (JS_ToInt32(ctx, pres.ptr, byteLength) != 0) -1 else pres.value
            }
            return if (len == 0) ByteArray(0) else null
        } finally {
            JS_FreeValue(ctx, byteLength)
        }
    } finally {
        JS_FreeValue(ctx, buffer)
    }
}

private fun readArrayBufferBytes(ctx: CPointer<JSContext>, value: CValue<JSValue>): ByteArray =
    memScoped {
        val size = qjs_ArrayBufferSize(ctx, value).toInt()
        if (size < 0) throw IllegalArgumentException("ArrayBuffer 长度无效")
        val out = ByteArray(size)
        if (size > 0) {
            val read = qjs_ArrayBufferRead(ctx, value, 0uL, out.refTo(0), size.toULong())
            if (read.toInt() != size) throw IllegalArgumentException("ArrayBuffer 读取不完整")
        }
        out
    }

/** JS Array 按 ByteArray 兼容语义读取: 每个元素必须是 0..255 数字, 非法元素不默认 0。 */
private fun readJsArrayBytes(ctx: CPointer<JSContext>, value: CValue<JSValue>): ByteArray =
    memScoped {
        val pLen = alloc<LongVar>()
        if (JS_GetLength(ctx, value, pLen.ptr) != 0) {
            throw IllegalArgumentException("字节数组长度读取失败")
        }
        val len = pLen.value.toInt()
        if (len < 0) throw IllegalArgumentException("字节数组长度无效: $len")
        val out = ByteArray(len)
        for (i in 0 until len) {
            val elem = JS_GetPropertyUint32(ctx, value, i.toUInt())
            try {
                if (qjs_IsException(elem) != 0) {
                    throw IllegalArgumentException("字节数组[$i] 读取失败")
                }
                if (qjs_IsNumber(elem) == 0) {
                    throw IllegalArgumentException("字节数组[$i] 不是数字")
                }
                // 用 SDK 数值转换并拒绝 NaN/小数, 不把非法元素默认成 0
                val d = memScoped {
                    val pres = alloc<DoubleVar>()
                    if (JS_ToFloat64(ctx, pres.ptr, elem) != 0) {
                        throw IllegalArgumentException("字节数组[$i] 不是有效数字")
                    }
                    pres.value
                }
                if (!d.isFinite() || d % 1.0 != 0.0) {
                    throw IllegalArgumentException("字节数组[$i]=$d 不是整数")
                }
                val byte = d.toInt()
                if (byte !in 0..255) {
                    throw IllegalArgumentException("字节数组[$i]=$byte 超出 0..255")
                }
                out[i] = byte.toByte()
            } finally {
                JS_FreeValue(ctx, elem)
            }
        }
        out
    }

private fun readIntArg(ctx: CPointer<JSContext>, value: CValue<JSValue>): Int =
    memScoped {
        if (qjs_IsNumber(value) == 0) {
            throw IllegalArgumentException("参数不是数字")
        }
        val pres = alloc<IntVar>()
        if (JS_ToInt32(ctx, pres.ptr, value) != 0) {
            throw IllegalArgumentException("参数不是整数")
        }
        pres.value
    }

private fun readStringArg(ctx: CPointer<JSContext>, value: CValue<JSValue>): String {
    if (qjs_IsString(value) == 0) {
        throw IllegalArgumentException("参数不是字符串")
    }
    val cstr = qjs_ToCString(ctx, value)
        ?: throw IllegalArgumentException("参数不是字符串")
    return try {
        cstr.toKString()
    } finally {
        qjs_FreeCString(ctx, cstr)
    }
}

private fun jsUndefined(): CValue<JSValue> = cValue {
    tag = JS_TAG_UNDEFINED.toLong()
}
