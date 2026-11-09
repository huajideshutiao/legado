@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import io.legado.app.help.image.ImageOps
import io.legado.app.help.image.ImageRef
import io.legado.app.napi.quickjs.JSContext
import io.legado.app.napi.quickjs.JSRuntime
import io.legado.app.napi.quickjs.JSValue
import io.legado.app.napi.quickjs.JSClassDef
import io.legado.app.napi.quickjs.JS_Call
import io.legado.app.napi.quickjs.JS_DefinePropertyValueStr
import io.legado.app.napi.quickjs.JS_DupValue
import io.legado.app.napi.quickjs.JS_FreeValue
import io.legado.app.napi.quickjs.JS_GetClassID
import io.legado.app.napi.quickjs.JS_GetArrayBuffer
import io.legado.app.napi.quickjs.JS_GetGlobalObject
import io.legado.app.napi.quickjs.JS_GetLength
import io.legado.app.napi.quickjs.JS_GetOpaque
import io.legado.app.napi.quickjs.JS_GetPropertyStr
import io.legado.app.napi.quickjs.JS_GetPropertyUint32
import io.legado.app.napi.quickjs.JS_GetTypedArrayBuffer
import io.legado.app.napi.quickjs.JS_GetTypedArrayType
import io.legado.app.napi.quickjs.JS_IsArray
import io.legado.app.napi.quickjs.JS_IsArrayBuffer
import io.legado.app.napi.quickjs.JS_NewArray
import io.legado.app.napi.quickjs.JS_NewCFunctionData
import io.legado.app.napi.quickjs.JS_NewClass
import io.legado.app.napi.quickjs.JS_NewClassID
import io.legado.app.napi.quickjs.JS_NewObject
import io.legado.app.napi.quickjs.JS_NewObjectClass
import io.legado.app.napi.quickjs.JS_PROP_CONFIGURABLE
import io.legado.app.napi.quickjs.JS_PROP_WRITABLE
import io.legado.app.napi.quickjs.JS_SetOpaque
import io.legado.app.napi.quickjs.JS_SetPropertyStr
import io.legado.app.napi.quickjs.JS_SetPropertyUint32
import io.legado.app.napi.quickjs.JS_TAG_EXCEPTION
import io.legado.app.napi.quickjs.JS_ToFloat64
import io.legado.app.napi.quickjs.qjs_ArrayBufferRead
import io.legado.app.napi.quickjs.qjs_FreeCString
import io.legado.app.napi.quickjs.qjs_IsException
import io.legado.app.napi.quickjs.qjs_IsNumber
import io.legado.app.napi.quickjs.qjs_IsString
import io.legado.app.napi.quickjs.qjs_NewBool
import io.legado.app.napi.quickjs.qjs_NewCFunction
import io.legado.app.napi.quickjs.qjs_NewInt32
import io.legado.app.napi.quickjs.qjs_ToCString
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
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
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.posix.memcpy

/**
 * native 端 (iOS/鸿蒙) `image` 绑定的 QuickJS 桥。
 *
 * ImageRef 由 SDK 用户类 + opaque 托管 StableRef: JSValue 存活期间 Kotlin 侧 ImageOps/ImageRef
 * 强引用存活; JSValue 回收后 finalizer 只释放这份 StableRef, 不执行 JS、不提前 close 仍在
 * 别处使用的图。
 *
 * QuickJS 类 ID 从 JSRuntime 的 js_class_id_alloc 分配 (JS_NewClassID), 每个 runtime
 * 初始化一次并记录在 [NativeImageBridgeState]; 该 state 挂在持有 rt/ctx 的 NativeJsScope 上,
 * 借用视图共享同一实例。
 */
internal class NativeImageBridgeState(
    val opsClassId: UInt,
    val refClassId: UInt,
)

/** func_data 携带的后端: 真实 ImageOps + 本 runtime 的 ImageRef 类 ID (不持 scope/ctx/JSValue)。 */
private class ImageOpsBackend(
    val ops: ImageOps,
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

/** split 结果方法属性 flags: 可写/可配置, 不加 enumerable。 */
private val IMAGE_METHOD_FLAGS: Int = (JS_PROP_WRITABLE or JS_PROP_CONFIGURABLE).toInt()

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
    val def = alloc<JSClassDef>()
    def.class_name = className.cstr.ptr
    def.finalizer = imageOpaqueFinalizer
    def.gc_mark = null
    def.call = null
    def.exotic = null
    check(JS_NewClass(rt, id, def.ptr) == 0) { "QuickJS 图片桥类注册失败: $className" } // 类名原子/类表分配失败 (OOM)
    id
}

/** ImageOps/ImageRef 用户类对象的 finalizer: 只释放 opaque 持有的 StableRef。 */
private val imageOpaqueFinalizer = staticCFunction { _: CPointer<JSRuntime>?, value: CValue<JSValue> ->
    disposeOpaque(value)
}

/**
 * 通过对象自身 classID 取 opaque 并释放 StableRef。
 * 部分创建失败 (opaque 仍为空) 的对象 finalizer 会到此返回; 已设置的 opaque 必为本桥创建的 StableRef。
 */
private fun disposeOpaque(value: CValue<JSValue>) {
    val opaque = JS_GetOpaque(value, JS_GetClassID(value)) ?: return
    opaque.asStableRef<Any>().dispose()
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
        throw IllegalStateException("创建 image 对象失败") // SDK 对象分配失败 (OOM)
    }
    val backend = ImageOpsBackend(ops, state.refClassId)
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
        throw IllegalStateException("创建 image 后端 token 失败") // SDK 类对象分配失败 (OOM)
    }
    // opaque 由 SDK 对象持有, finalizer 负责释放 StableRef
    JS_SetOpaque(token, backendRef.asCPointer())
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
                    throw IllegalStateException("创建 image.${method.name} 失败") // SDK 函数/数据分配失败
                }
                // JS_SetPropertyStr 消费 fn; 返回值 <0 表示 SDK 异常
                if (JS_SetPropertyStr(ctx, obj, method.name, fn) < 0) {
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
    // opaque 由 SDK 对象持有, finalizer 负责释放 StableRef
    JS_SetOpaque(obj, holderRef.asCPointer())
    return obj
}

/** 从新 ImageRef 类对象解回 Kotlin 图片引用; 其他对象返回 null。 */
internal fun unwrapImageRef(
    value: CValue<JSValue>,
    state: NativeImageBridgeState,
): ImageRef? {
    if (JS_GetClassID(value) != state.refClassId) return null
    val opaque = JS_GetOpaque(value, state.refClassId)!!
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
    ctxOrNull: CPointer<JSContext>?,
    @Suppress("UNUSED_PARAMETER") thisVal: CValue<JSValue>,
    argc: Int,
    argv: CPointer<JSValue>?,
    magic: Int,
    funcData: CPointer<JSValue>?,
): CValue<JSValue> {
    val ctx = ctxOrNull!!
    return try {
        val token = funcData!![0L].readValue()
        val opaque = JS_GetOpaque(token, JS_GetClassID(token))!!
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
            val arg = argv!![0L].readValue()
            val image = if (qjs_IsString(arg) != 0) {
                backend.ops.decode(readStringArg(ctx, arg))
            } else {
                val bytes = readBytesArg(ctx, arg) ?: return jsException()
                backend.ops.decode(bytes)
            }
            return wrapImageRef(ctx, image, backend.refClassId)
        }

        MAGIC_ENCODE -> {
            requireArgc("encode", argc, 3)
            val imgVal = argv!![0L].readValue()
            val img = readRefArg(imgVal, backend, "encode 第一个参数")
            val format = readStringArg(ctx, argv[1L].readValue())
            val quality = readIntArg(ctx, argv[2L].readValue())
            return NativeJsEngine.newUint8ArrayCopy(
                ctx, backend.ops.encode(img, format, quality)
            )
        }

        MAGIC_SPLIT -> {
            requireArgc("split", argc, 3)
            val imgVal = argv!![0L].readValue()
            val img = readRefArg(imgVal, backend, "split 第一个参数")
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
            return try {
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
            val img = readRefArg(imgVal, backend, "crop 第一个参数")
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
            val img = readRefArg(imgVal, backend, "rotate 第一个参数")
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
            val img = readRefArg(imgVal, backend, "flip 第一个参数")
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
            val img = readRefArg(imgVal, backend, "size 参数")
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
    value: CValue<JSValue>,
    backend: ImageOpsBackend,
    argName: String,
): ImageRef {
    if (JS_GetClassID(value) != backend.refClassId) {
        throw IllegalArgumentException("$argName 需为 image 方法返回的 ImageRef 对象")
    }
    val opaque = JS_GetOpaque(value, backend.refClassId)!!
    return opaque.asStableRef<ImageRefHolder>().get().ref
}

/** JS 数组读为 ImageRef 列表, 同时保留每个元素 JSValue 供同引用复用。 */
private fun readRefArrayArg(
    ctx: CPointer<JSContext>,
    arrayValue: CValue<JSValue>,
    backend: ImageOpsBackend,
    argName: String,
): Pair<List<ImageRef>, List<CValue<JSValue>>> {
    val len = readJsLength(ctx, arrayValue, argName)
    val refs = ArrayList<ImageRef>(len)
    val vals = ArrayList<CValue<JSValue>>(len)
    try {
        for (i in 0 until len) {
            val elem = JS_GetPropertyUint32(ctx, arrayValue, i.toUInt())
            try {
                if (qjs_IsException(elem) != 0) {
                    throw IllegalArgumentException("$argName[$i] 读取失败")
                }
                refs.add(readRefArg(elem, backend, "$argName[$i]"))
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
            // JS_SetPropertyUint32 消费 elem; 返回值 <0 表示 SDK 异常
            if (JS_SetPropertyUint32(ctx, array, index.toUInt(), elem) < 0) {
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
    attachArrayMethod(ctx, array, "get", qjs_NewCFunction(ctx, arrayGetFn, "get", 1))
    attachArrayMethod(ctx, array, "size", qjs_NewCFunction(ctx, arraySizeFn, "size", 0))
    attachArrayMethod(ctx, array, "isEmpty", qjs_NewCFunction(ctx, arrayIsEmptyFn, "isEmpty", 0))
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
    // 只去掉 enumerable, 保留可写/可配置; SDK flags: JS_PROP_WRITABLE | JS_PROP_CONFIGURABLE
    // JS_DefinePropertyValueStr 消费 method; 返回值 <0 表示 SDK 异常
    if (JS_DefinePropertyValueStr(ctx, array, name, method, IMAGE_METHOD_FLAGS) < 0) {
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
    ctxOrNull: CPointer<JSContext>?,
    thisVal: CValue<JSValue>,
    argc: Int,
    argv: CPointer<JSValue>?,
): CValue<JSValue> {
    val ctx = ctxOrNull!!
    return try {
        if (argc != 1) {
            throw IllegalArgumentException("get(index) 需要 1 个参数")
        }
        val index = readIndexArg(ctx, argv!![0L].readValue())
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
    if (qjs_IsNumber(value) != 0) return readNumberAsInt(ctx, value)
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
    ctxOrNull: CPointer<JSContext>?,
    thisVal: CValue<JSValue>,
    @Suppress("UNUSED_PARAMETER") argc: Int,
    @Suppress("UNUSED_PARAMETER") argv: CPointer<JSValue>?,
): CValue<JSValue> {
    val ctx = ctxOrNull!!
    return try {
        qjs_NewInt32(ctx, readArrayLength(ctx, thisVal))
    } catch (t: Throwable) {
        NativeJsExtensionsBridge.jsThrowError(ctx, t.message ?: "size() 失败")
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
    ctxOrNull: CPointer<JSContext>?,
    thisVal: CValue<JSValue>,
    @Suppress("UNUSED_PARAMETER") argc: Int,
    @Suppress("UNUSED_PARAMETER") argv: CPointer<JSValue>?,
): CValue<JSValue> {
    val ctx = ctxOrNull!!
    return try {
        qjs_NewBool(ctx, if (readArrayLength(ctx, thisVal) == 0) 1 else 0)
    } catch (t: Throwable) {
        NativeJsExtensionsBridge.jsThrowError(ctx, t.message ?: "isEmpty() 失败")
    }
}

private fun readArrayLength(ctx: CPointer<JSContext>, value: CValue<JSValue>): Int {
    if (!JS_IsArray(value)) {
        throw IllegalArgumentException("方法需以 split 返回的图片数组为 this")
    }
    return readJsLength(ctx, value, "数组")
}

/** JS Array 的 int64 长度先检查可表示范围, 再转 Kotlin Int; 不伪造空数组。 */
private fun readJsLength(ctx: CPointer<JSContext>, value: CValue<JSValue>, argName: String): Int =
    memScoped {
        val pLen = alloc<LongVar>()
        JS_GetLength(ctx, value, pLen.ptr)
        val len = pLen.value
        if (len < 0 || len > Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException("$argName 长度无效: $len")
        }
        len.toInt()
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
            // JS_SetPropertyStr 消费 value; 返回值 <0 表示 SDK 异常
            if (JS_SetPropertyStr(ctx, data, key, qjs_NewInt32(ctx, value)) < 0) {
                throw IllegalStateException("写入 size.$key 失败")
            }
        }
        val global = JS_GetGlobalObject(ctx)
        try {
            val wrapper = JS_GetPropertyStr(ctx, global, "__wrapJsonMap")
            try {
                if (qjs_IsException(wrapper) != 0) {
                    throw IllegalStateException("读取 __wrapJsonMap 失败") // SDK 全局属性读取异常
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

/**
 * 字节参数: TypedArray 视图、ArrayBuffer 或与 ByteArray 兼容的 JS Array。
 * ArrayBuffer 的 SDK 异常以 null 上抛, 由调用方返回 JS_EXCEPTION。
 */
private fun readBytesArg(ctx: CPointer<JSContext>, value: CValue<JSValue>): ByteArray? {
    readTypedArrayBytes(ctx, value)?.let { return it }
    if (JS_IsArrayBuffer(value)) {
        return readArrayBufferBytes(ctx, value)
    }
    if (JS_IsArray(value)) {
        return readJsArrayBytes(ctx, value)
    }
    throw IllegalArgumentException(
        "image.decode 参数需为 TypedArray、ArrayBuffer 或字节数组"
    )
}

/**
 * 按 SDK 的 TypedArray 类型读取自身视图字节; 非 TypedArray 返回 null。
 * 空视图返回空 ByteArray; 读取 SDK 失败抛错, 不用属性探测或静默 null。
 */
private fun readTypedArrayBytes(
    ctx: CPointer<JSContext>,
    value: CValue<JSValue>,
): ByteArray? {
    if (JS_GetTypedArrayType(value) < 0) return null
    return memScoped {
        val offset = alloc<ULongVar>()
        val length = alloc<ULongVar>()
        val buffer = JS_GetTypedArrayBuffer(
            ctx, value, offset.ptr, length.ptr, null
        )
        if (qjs_IsException(buffer) != 0) {
            JS_FreeValue(ctx, buffer)
            throw IllegalArgumentException("TypedArray 视图读取失败")
        }
        try {
            val byteOffset = offset.value
            val byteLength = length.value
            val len = byteLength.toInt()
            val out = ByteArray(len)
            if (len > 0) {
                val read = qjs_ArrayBufferRead(ctx, buffer, byteOffset, out.refTo(0), len.toULong())
                if (read.toInt() != len) throw IllegalArgumentException("TypedArray 读取不完整")
            }
            out
        } finally {
            JS_FreeValue(ctx, buffer)
        }
    }
}

/**
 * 读取 ArrayBuffer 字节; JS_GetArrayBuffer 返回 NULL 表示 SDK 真实失败 (如 detached),
 * 此时保留 SDK 异常并返回 null, 不把失败当空字节。
 */
private fun readArrayBufferBytes(ctx: CPointer<JSContext>, value: CValue<JSValue>): ByteArray? =
    memScoped {
        val pSize = alloc<ULongVar>()
        val data = JS_GetArrayBuffer(ctx, pSize.ptr, value)
        if (data == null) return@memScoped null
        val len = pSize.value.toInt()
        val out = ByteArray(len)
        if (len > 0) {
            val read = qjs_ArrayBufferRead(ctx, value, 0uL, out.refTo(0), len.toULong())
            if (read.toInt() != len) throw IllegalArgumentException("ArrayBuffer 读取不完整")
        }
        out
    }

/** JS Array 按 ByteArray 兼容语义读取: 有限数字按 Number.toInt().toByte() 转换, 非法元素不默认 0。 */
private fun readJsArrayBytes(ctx: CPointer<JSContext>, value: CValue<JSValue>): ByteArray =
    memScoped {
        val len = readJsLength(ctx, value, "字节数组")
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
                val d = memScoped {
                    val pres = alloc<DoubleVar>()
                    JS_ToFloat64(ctx, pres.ptr, elem)
                    pres.value
                }
                if (!d.isFinite()) {
                    throw IllegalArgumentException("字节数组[$i]=$d 不是有限数字")
                }
                // 与既有 Kotlin ByteArray 数值转换一致: Number.toInt().toByte()
                out[i] = d.toInt().toByte()
            } finally {
                JS_FreeValue(ctx, elem)
            }
        }
        out
    }

private fun readIntArg(ctx: CPointer<JSContext>, value: CValue<JSValue>): Int {
    if (qjs_IsNumber(value) == 0) {
        throw IllegalArgumentException("参数不是数字")
    }
    return readNumberAsInt(ctx, value)
}

/** JS Number 经 SDK 读成 double, 再按既有 Number.toInt() 语义转换 (不经 JS_ToInt32 模转换)。 */
private fun readNumberAsInt(ctx: CPointer<JSContext>, value: CValue<JSValue>): Int =
    memScoped {
        val pres = alloc<DoubleVar>()
        JS_ToFloat64(ctx, pres.ptr, value)
        pres.value.toInt()
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

private fun jsException(): CValue<JSValue> = cValue {
    tag = JS_TAG_EXCEPTION.toLong()
}
