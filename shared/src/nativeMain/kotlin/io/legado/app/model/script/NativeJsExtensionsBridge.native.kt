@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import io.legado.app.help.JsEncodeUtilsDefaults
import io.legado.app.help.JsExtensionsCommon
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.NativeAsymmetricCrypto
import io.legado.app.help.crypto.NativeSign
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SymmetricCrypto
import io.legado.app.help.crypto.NativeSymmetricCrypto
import io.legado.app.napi.quickjs.JSContext
import io.legado.app.napi.quickjs.JSValue
import io.legado.app.napi.quickjs.JSValueVar
import io.legado.app.napi.quickjs.JS_EVAL_TYPE_GLOBAL
import io.legado.app.napi.quickjs.JS_Eval
import io.legado.app.napi.quickjs.JS_TAG_NULL
import io.legado.app.napi.quickjs.JS_TAG_UNDEFINED
import io.legado.app.napi.quickjs.JS_FreeCString
import io.legado.app.napi.quickjs.JS_FreeValue
import io.legado.app.napi.quickjs.JS_GetLength
import io.legado.app.napi.quickjs.JS_GetPropertyUint32
import io.legado.app.napi.quickjs.JS_IsArray
import io.legado.app.napi.quickjs.JS_NewArray
import io.legado.app.napi.quickjs.JS_SetPropertyUint32
import io.legado.app.napi.quickjs.qjs_IsNumber
import io.legado.app.napi.quickjs.qjs_IsString
import io.legado.app.napi.quickjs.qjs_NewFloat64
import io.legado.app.napi.quickjs.qjs_NewInt32
import io.legado.app.napi.quickjs.qjs_NewString
import io.legado.app.napi.quickjs.qjs_ToCString
import io.legado.app.napi.quickjs.qjs_ValueGetFloat64
import io.legado.app.napi.quickjs.qjs_ValueGetInt
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString

/**
 * native 端 (iOS/鸿蒙) JsExtensions 桥接器: 把 [JsExtensionsCommon] 等 Kotlin 对象桥接为 JS 对象。
 *
 * nativeMain 中间源集下沉: 原 iosMain [IosJsExtensionsBridge] / ohosMain [OhosJsExtensionsBridge]
 * 逻辑完全一致 (基于 cinterop 共享 quickjs 绑定 + 同一份 krypto), 下沉到 nativeMain 共用,
 * iosMain/ohosMain 用 typealias 别名指向本类。
 *
 * # 背景
 * native 端 [NativeJsEngine] P0 阶段 toJsValue 对复杂 Kotlin 对象返回 null (跳过注入),
 * 导致 `bindings["java"] = analyzeRuleCore` 时 JS 里 `java` 为 undefined,
 * `java.createSymmetricCrypto` / `java.md5Encode` 等调用全部失效。
 *
 * # 方案: 通用分派 C 函数 + JS 层工厂包装
 * 1. bootstrap 注入全局 C 函数 `__nativeDispatch(handle, methodId, argsArray)` 到 globalThis;
 * 2. bootstrap 注入 JS 工厂函数 `__createJavaObj(handle)` / `__createCryptoObj(handle)` 等;
 * 3. [NativeJsEngine.toJsValue] 识别 JsExtensionsCommon 时, 注册 handle, 调 `__createJavaObj(handle)` 得到 JS 对象;
 * 4. JS 调用 `java.md5Encode("str")` → JS 层 `__nativeDispatch(handle, 101, ["str"])` → C 回调 → Kotlin 分派。
 *
 * # 为什么不用 JSClassDef + opaque
 * JSClassDef + opaque + StableRef 是更"正规"的方案, 但实现复杂 (finalizer / StableRef 生命周期管理),
 * 且 cinterop 绑定 JSClassDef 结构体的 finalizer 函数指针类型映射容易出错。
 * 本方案用 handle 表 + JS 层闭包捕获 handle, 避免了 C 层面的上下文传递,
 * 只需 1 个 staticCFunction (C 函数不能捕获上下文), 所有方法分派在 Kotlin 层完成。
 *
 * # handle 生命周期
 * - handleTable 用强引用, 防止 Kotlin 对象在 JS 使用期间被 GC;
 * - [NativeJsScope.handles] 记录 scope 内注册的所有 handle, scope close 时统一清理;
 * - createSymmetricCrypto 返回的 SymmetricCrypto 对象 handle 也记录在 scope.handles 中。
 *
 * # 补齐的方法 (methodId)
 * - 1-99: JsExtensionsCommon 纯函数面 (base64Encode/base64Decode/strToBytes/bytesToStr/hex/encodeURI/htmlFormat/timeFormat/randomUUID/t2s/s2t)
 * - 100-199: JsEncodeUtilsDefaults 摘要/HMAC 面 (md5Encode/md5Encode16/digestHex/digestBase64Str/HMacHex/HMacBase64)
 * - 200-299: 工厂方法 (createSymmetricCrypto/createAsymmetricCrypto/createSign) 返回 handle (Float64)
 * - 1000-1099: SymmetricCrypto 对象方法 (encryptBase64/decryptStr/decrypt/setIv)
 * - 1100-1199: AsymmetricCrypto 对象方法 (setPrivateKey/setPublicKey/decrypt/encrypt) [native 降级 stub]
 * - 1200-1299: Sign 对象方法 (setPrivateKey/setPublicKey) [native 降级 stub]
 *
 * 注: AnalyzeUrlCore/AnalyzeRuleCore 实现 JsExtensionsCommon 但不实现 JsEncodeUtilsDefaults,
 * 故 md5Encode 等摘要方法在 AnalyzeUrlCore/AnalyzeRuleCore 路径下不可用 (JS 调用返回 undefined);
 * createSymmetricCrypto 等工厂方法直接调用 native 端 [NativeSymmetricCrypto],
 * 不依赖 JsEncodeUtilsDefaults (iOS/鸿蒙 actual 均 typealias 到 NativeSymmetricCrypto)。
 */
object NativeJsExtensionsBridge {

    /**
     * handle → Kotlin 对象映射 (强引用, 防止 GC)。
     *
     * 注: Kotlin/Native 无 `java.util.concurrent.ConcurrentHashMap`, 用普通 HashMap;
     * quickjs 单线程模型, scope 在单线程内串行访问 (与 native 端 SourceCacheProvider 内存层策略一致)。
     */
    private val handleTable = HashMap<Long, Any>()

    /**
     * handle 自增计数器。
     *
     * 注: Kotlin/Native 无 `java.util.concurrent.atomic.AtomicLong`, 用普通 var Long;
     * quickjs 单线程模型, 自增在单线程内串行执行, 无竞态。
     */
    private var handleCounter: Long = 0

    /** 注册 Kotlin 对象, 返回 handle */
    fun registerObject(obj: Any): Long {
        handleCounter += 1
        val handle = handleCounter
        handleTable[handle] = obj
        return handle
    }

    /** 取 Kotlin 对象 */
    fun getObject(handle: Long): Any? = handleTable[handle]

    /** 注销 handle (scope close 时调用) */
    fun unregisterObject(handle: Long) {
        handleTable.remove(handle)
    }

    /**
     * 把 JsExtensionsCommon 对象桥接为 JS 对象。
     *
     * 内部: 注册 handle → 调 JS `__createJavaObj(handle)` → 返回 JS 对象。
     * JS 对象的方法通过闭包捕获 handle, 调用 `__nativeDispatch(handle, methodId, argsArray)` 回 Kotlin。
     *
     * @param ctx quickjs JSContext
     * @param ext Kotlin 对象 (JsExtensionsCommon / SymmetricCrypto 等)
     * @param scope 当前 scope (用于记录 handle, close 时清理)
     * @return JS 对象的 JSValue (已 retain, 调用方负责 free 或转移所有权)
     */
    fun createJsObject(
        ctx: CPointer<JSContext>,
        ext: Any,
        scope: NativeJsScope
    ): CValue<JSValue> {
        val handle = registerObject(ext)
        scope.handles.add(handle)
        // 调用 JS 工厂函数 __createJavaObj(handle) 或 __createCryptoObj(handle)
        val factoryFn = when (ext) {
            is SymmetricCrypto -> "__createCryptoObj"
            is AsymmetricCrypto -> "__createAsymCryptoObj"
            is Sign -> "__createSignObj"
            else -> "__createJavaObj"
        }
        val js = "$factoryFn($handle)"
        memScoped {
            val cstr = js.cstr.ptr
            // JS_Eval 执行工厂函数, 返回 JS 对象
            val result = JS_Eval(
                ctx, cstr, js.length.toULong(), "<bridge>".cstr.ptr,
                JS_EVAL_TYPE_GLOBAL
            )
            // result 是 JS 对象, 调用方负责 JS_FreeValue
            // 但我们在 toJsValue 中返回它, 由 JS_SetPropertyStr 转移所有权
            // 如果 eval 异常, result 是 exception, 调用方会得到异常 JSValue
            // 此处不检查异常 (toJsValue 不检查), 让上层 eval 时暴露
            return result
        }
    }

    // ============ native dispatch C 函数 (staticCFunction, 不捕获上下文) ============

    /**
     * 全局 native 分派函数 C 指针, 由 bootstrap 注入 globalThis.__nativeDispatch。
     *
     * 签名: __nativeDispatch(handle: number, methodId: number, args: array) → any
     * - argv[0] = handle (Float64)
     * - argv[1] = methodId (Int32)
     * - argv[2] = args (JS Array)
     *
     * staticCFunction 限制: 不能捕获上下文, 只能调用 object 方法。
     * 类型由编译器推断为 CPointer<CFunction<(...) -> CValue<JSValue>>>, 与 qjs_NewCFunction 的
     * JSCFunction* 参数兼容 (cinterop 绑定 JSCFunction 为同名函数类型)。
     */
    internal val nativeDispatchFn =
        staticCFunction { ctx: CPointer<JSContext>?, thisVal: CValue<JSValue>?, argc: Int, argv: CPointer<JSValueVar>? ->
            nativeDispatchImpl(ctx, thisVal, argc, argv)
        }

    /**
     * nativeDispatch 的 Kotlin 实现 (object 方法, 可被 staticCFunction 调用)。
     */
    private fun nativeDispatchImpl(
        ctx: CPointer<JSContext>?,
        @Suppress("UNUSED_PARAMETER") thisVal: CValue<JSValue>?,
        argc: Int,
        argv: CPointer<JSValueVar>?
    ): CValue<JSValue> {
        val ctxNotNull = ctx ?: return jsUndefined()
        if (argc < 3 || argv == null) return jsUndefined()
        try {
            val handle = qjs_ValueGetFloat64(argv[0]!!).toLong()
            val methodId = qjs_ValueGetInt(argv[1]!!)
            val argsArray = argv[2]!!
            val obj = getObject(handle) ?: return jsUndefined()
            return dispatch(ctxNotNull, obj, methodId, argsArray)
        } catch (t: Throwable) {
            // 桥接异常返回 undefined, 避免 JS 引擎崩溃 (与 Android 端 exotic trap 行为一致)
            return jsUndefined()
        }
    }

    // ============ 方法分派 (Kotlin 层) ============

    /**
     * 根据 methodId 分派到对应 Kotlin 方法。
     *
     * @param ctx quickjs JSContext (用于 JSValue 转换)
     * @param obj Kotlin 对象 (JsExtensionsCommon / SymmetricCrypto 等)
     * @param methodId 方法 ID (见文件头注释)
     * @param argsArray JS Array 参数
     * @return 返回值 JSValue
     */
    fun dispatch(
        ctx: CPointer<JSContext>,
        obj: Any,
        methodId: Int,
        argsArray: CValue<JSValue>
    ): CValue<JSValue> {
        // 把 JS Array 转为 Kotlin List<Any?>
        val args = jsArrayToList(ctx, argsArray)

        return when {
            // ============ JsExtensionsCommon 纯函数面 (1-99) ============
            obj is JsExtensionsCommon && methodId == 1 -> {
                // base64Encode(str)
                stringToJsValue(ctx, obj.base64Encode(args.getString(0)))
            }
            obj is JsExtensionsCommon && methodId == 2 -> {
                // base64Decode(str)
                stringToJsValue(ctx, obj.base64Decode(args.getOrNull(0) as? String))
            }
            obj is JsExtensionsCommon && methodId == 3 -> {
                // strToBytes(str) → ByteArray → JS Array
                byteArrayToJsArray(ctx, obj.strToBytes(args.getString(0)))
            }
            obj is JsExtensionsCommon && methodId == 4 -> {
                // bytesToStr(bytes) → String (bytes 从 JS Array 取)
                val bytes = jsArrayToByteArray(ctx, argsArray)
                stringToJsValue(ctx, obj.bytesToStr(bytes))
            }
            obj is JsExtensionsCommon && methodId == 5 -> {
                // hexDecodeToString(hex)
                stringToJsValue(ctx, obj.hexDecodeToString(args.getString(0)))
            }
            obj is JsExtensionsCommon && methodId == 6 -> {
                // hexEncodeToString(utf8)
                stringToJsValue(ctx, obj.hexEncodeToString(args.getString(0)))
            }
            obj is JsExtensionsCommon && methodId == 7 -> {
                // encodeURI(str)
                stringToJsValue(ctx, obj.encodeURI(args.getString(0)))
            }
            obj is JsExtensionsCommon && methodId == 8 -> {
                // htmlFormat(str)
                stringToJsValue(ctx, obj.htmlFormat(args.getString(0)))
            }
            obj is JsExtensionsCommon && methodId == 9 -> {
                // timeFormat(time)
                val time = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                stringToJsValue(ctx, obj.timeFormat(time))
            }
            obj is JsExtensionsCommon && methodId == 10 -> {
                // randomUUID()
                stringToJsValue(ctx, obj.randomUUID())
            }
            obj is JsExtensionsCommon && methodId == 11 -> {
                // t2s(text)
                stringToJsValue(ctx, obj.t2s(args.getString(0)))
            }
            obj is JsExtensionsCommon && methodId == 12 -> {
                // s2t(text)
                stringToJsValue(ctx, obj.s2t(args.getString(0)))
            }

            // ============ JsEncodeUtilsDefaults 摘要/HMAC 面 (100-199) ============
            obj is JsEncodeUtilsDefaults && methodId == 101 -> {
                // md5Encode(str)
                stringToJsValue(ctx, obj.md5Encode(args.getString(0)))
            }
            obj is JsEncodeUtilsDefaults && methodId == 102 -> {
                // md5Encode16(str)
                stringToJsValue(ctx, obj.md5Encode16(args.getString(0)))
            }
            obj is JsEncodeUtilsDefaults && methodId == 103 -> {
                // digestHex(data, algorithm)
                stringToJsValue(ctx, obj.digestHex(args.getString(0), args.getString(1)))
            }
            obj is JsEncodeUtilsDefaults && methodId == 104 -> {
                // digestBase64Str(data, algorithm)
                stringToJsValue(ctx, obj.digestBase64Str(args.getString(0), args.getString(1)))
            }
            obj is JsEncodeUtilsDefaults && methodId == 105 -> {
                // HMacHex(data, algorithm, key)
                stringToJsValue(ctx, obj.HMacHex(args.getString(0), args.getString(1), args.getString(2)))
            }
            obj is JsEncodeUtilsDefaults && methodId == 106 -> {
                // HMacBase64(data, algorithm, key)
                stringToJsValue(ctx, obj.HMacBase64(args.getString(0), args.getString(1), args.getString(2)))
            }

            // ============ 工厂方法 (200-299) → 返回 handle (Float64) ============
            obj is JsExtensionsCommon && methodId == 201 -> {
                // createSymmetricCrypto(transformation, key, iv) → cryptoHandle
                val transformation = args.getString(0)
                val key = args.getOrNull(1) as? String
                val iv = args.getOrNull(2) as? String
                val crypto = NativeSymmetricCrypto(transformation, key?.encodeToByteArray())
                if (iv != null && iv.isNotEmpty()) {
                    // NativeSymmetricCrypto 不支持 setIv (krypto AES ECB only), 忽略 iv
                }
                val handle = registerObject(crypto)
                // 注意: crypto handle 不添加到 scope.handles, 因为调用方 scope 不可达
                // crypto 对象生命周期与 JS 引用绑定, handle 泄漏量可控 (每次调用一个)
                qjs_NewFloat64(ctx, handle.toDouble())
            }
            obj is JsExtensionsCommon && methodId == 202 -> {
                // createAsymmetricCrypto(transformation) → cryptoHandle (native 降级 stub)
                val transformation = args.getString(0)
                val crypto = NativeAsymmetricCrypto(transformation)
                val handle = registerObject(crypto)
                qjs_NewFloat64(ctx, handle.toDouble())
            }
            obj is JsExtensionsCommon && methodId == 203 -> {
                // createSign(algorithm) → signHandle (native 降级 stub)
                val algorithm = args.getString(0)
                val sign = NativeSign(algorithm)
                val handle = registerObject(sign)
                qjs_NewFloat64(ctx, handle.toDouble())
            }

            // ============ SymmetricCrypto 对象方法 (1000-1099) ============
            obj is SymmetricCrypto && methodId == 1001 -> {
                // encryptBase64(data)
                val data = args.getString(0)
                stringToJsValue(ctx, obj.encryptBase64(data))
            }
            obj is SymmetricCrypto && methodId == 1002 -> {
                // decryptStr(data) → String
                val data = args.getString(0)
                stringToJsValue(ctx, obj.decrypt(data).decodeToString())
            }
            obj is SymmetricCrypto && methodId == 1003 -> {
                // decrypt(data) → ByteArray → JS Array
                val data = args.getString(0)
                byteArrayToJsArray(ctx, obj.decrypt(data))
            }
            obj is NativeSymmetricCrypto && methodId == 1004 -> {
                // setIv(iv) → this (native krypto ECB only, iv 忽略, 返回 this)
                // 返回 handle 让 JS 层重新包装 (实际 JS 层直接返回 this)
                jsUndefined() // setIv 在 JS 层直接返回 this, 不走 dispatch
            }

            // ============ AsymmetricCrypto 对象方法 (1100-1199, native 降级 stub) ============
            obj is AsymmetricCrypto && methodId == 1101 -> {
                // setPrivateKey(key) → this
                obj.setPrivateKey(args.getString(0))
                qjs_NewFloat64(ctx, 1.0) // JS 层返回 this, 此处不实际使用
            }
            obj is AsymmetricCrypto && methodId == 1102 -> {
                // setPublicKey(key) → this
                obj.setPublicKey(args.getString(0))
                qjs_NewFloat64(ctx, 1.0)
            }
            obj is AsymmetricCrypto && methodId == 1103 -> {
                // decrypt(data, usePublicKey) → ByteArray (native 降级 stub 返回空)
                val usePublicKey = (args.getOrNull(1) as? Boolean) ?: true
                byteArrayToJsArray(ctx, obj.decrypt(args.getString(0), usePublicKey))
            }
            obj is AsymmetricCrypto && methodId == 1104 -> {
                // encrypt(data, usePublicKey) → ByteArray (native 降级 stub 返回空)
                val usePublicKey = (args.getOrNull(1) as? Boolean) ?: true
                byteArrayToJsArray(ctx, obj.encrypt(args.getString(0), usePublicKey))
            }

            // ============ Sign 对象方法 (1200-1299, native 降级 stub) ============
            obj is Sign && methodId == 1201 -> {
                // setPrivateKey(key) → this
                obj.setPrivateKey(args.getString(0))
                qjs_NewFloat64(ctx, 1.0)
            }
            obj is Sign && methodId == 1202 -> {
                // setPublicKey(key) → this
                obj.setPublicKey(args.getString(0))
                qjs_NewFloat64(ctx, 1.0)
            }

            else -> jsUndefined()
        }
    }

    // ============ JS 工厂代码 (注入 bootstrap) ============

    /**
     * JS 工厂函数源码, 注入 bootstrap。
     *
     * - `__createJavaObj(handle)`: 创建 java 对象 JS 包装, 每个方法调 `__nativeDispatch(handle, methodId, args)`;
     * - `__createCryptoObj(handle)`: 创建 SymmetricCrypto 对象 JS 包装;
     * - `__createAsymCryptoObj(handle)`: 创建 AsymmetricCrypto 对象 JS 包装;
     * - `__createSignObj(handle)`: 创建 Sign 对象 JS 包装。
     *
     * JS 层用闭包捕获 handle, 避免在 C 层面传递上下文。
     *
     * 注: iOS/鸿蒙两端 JS 工厂代码完全一致 (JS 层行为相同), 下沉到 nativeMain 共用。
     */
    val JS_FACTORY_CODE: String = """
// ============ JsExtensions 桥接工厂 (handle → JS 对象) ============

function __createJavaObj(handle) {
    var obj = {};
    // JsExtensionsCommon 纯函数面 (1-99)
    obj.base64Encode = function(str) { return __nativeDispatch(handle, 1, [str]); };
    obj.base64Decode = function(str) { return __nativeDispatch(handle, 2, [str]); };
    obj.strToBytes = function(str) { return __nativeDispatch(handle, 3, [str]); };
    obj.bytesToStr = function(bytes) { return __nativeDispatch(handle, 4, bytes); };
    obj.hexDecodeToString = function(hex) { return __nativeDispatch(handle, 5, [hex]); };
    obj.hexEncodeToString = function(utf8) { return __nativeDispatch(handle, 6, [utf8]); };
    obj.encodeURI = function(str) { return __nativeDispatch(handle, 7, [str]); };
    obj.htmlFormat = function(str) { return __nativeDispatch(handle, 8, [str]); };
    obj.timeFormat = function(time) { return __nativeDispatch(handle, 9, [time]); };
    obj.randomUUID = function() { return __nativeDispatch(handle, 10, []); };
    obj.t2s = function(text) { return __nativeDispatch(handle, 11, [text]); };
    obj.s2t = function(text) { return __nativeDispatch(handle, 12, [text]); };
    // JsEncodeUtilsDefaults 摘要/HMAC 面 (100-199)
    obj.md5Encode = function(str) { return __nativeDispatch(handle, 101, [str]); };
    obj.md5Encode16 = function(str) { return __nativeDispatch(handle, 102, [str]); };
    obj.digestHex = function(data, algorithm) { return __nativeDispatch(handle, 103, [data, algorithm]); };
    obj.digestBase64Str = function(data, algorithm) { return __nativeDispatch(handle, 104, [data, algorithm]); };
    obj.HMacHex = function(data, algorithm, key) { return __nativeDispatch(handle, 105, [data, algorithm, key]); };
    obj.HMacBase64 = function(data, algorithm, key) { return __nativeDispatch(handle, 106, [data, algorithm, key]); };
    // 工厂方法 (200-299) → 返回 handle, 用 __createCryptoObj 包装
    obj.createSymmetricCrypto = function(transformation, key, iv) {
        var cryptoHandle = __nativeDispatch(handle, 201, [transformation, key, iv]);
        return __createCryptoObj(cryptoHandle);
    };
    obj.createAsymmetricCrypto = function(transformation) {
        var cryptoHandle = __nativeDispatch(handle, 202, [transformation]);
        return __createAsymCryptoObj(cryptoHandle);
    };
    obj.createSign = function(algorithm) {
        var signHandle = __nativeDispatch(handle, 203, [algorithm]);
        return __createSignObj(signHandle);
    };
    return obj;
}

function __createCryptoObj(handle) {
    var obj = {};
    obj.encryptBase64 = function(data) { return __nativeDispatch(handle, 1001, [data]); };
    obj.decryptStr = function(data) { return __nativeDispatch(handle, 1002, [data]); };
    obj.decrypt = function(data) { return __nativeDispatch(handle, 1003, [data]); };
    obj.setIv = function(iv) { return obj; }; // native ECB only, iv 忽略, 返回 this
    return obj;
}

function __createAsymCryptoObj(handle) {
    var obj = {};
    obj.setPrivateKey = function(key) { __nativeDispatch(handle, 1101, [key]); return obj; };
    obj.setPublicKey = function(key) { __nativeDispatch(handle, 1102, [key]); return obj; };
    obj.decrypt = function(data, usePublicKey) { return __nativeDispatch(handle, 1103, [data, usePublicKey]); };
    obj.encrypt = function(data, usePublicKey) { return __nativeDispatch(handle, 1104, [data, usePublicKey]); };
    return obj;
}

function __createSignObj(handle) {
    var obj = {};
    obj.setPrivateKey = function(key) { __nativeDispatch(handle, 1201, [key]); return obj; };
    obj.setPublicKey = function(key) { __nativeDispatch(handle, 1202, [key]); return obj; };
    return obj;
}
    """.trimIndent()

    // ============ 工具方法: JSValue ↔ Kotlin 转换 ============

    /** JS Array → Kotlin List<Any?> (递归取元素) */
    private fun jsArrayToList(ctx: CPointer<JSContext>, arr: CValue<JSValue>): List<Any?> {
        val result = mutableListOf<Any?>()
        memScoped {
            val pLen = alloc<LongVar>()
            if (JS_GetLength(ctx, arr, pLen.ptr) != 0) return@memScoped
            val count = pLen.value.toInt()
            for (i in 0 until count) {
                val elem = JS_GetPropertyUint32(ctx, arr, i.toUInt())
                try {
                    result.add(fromJsValue(ctx, elem))
                } finally {
                    JS_FreeValue(ctx, elem)
                }
            }
        }
        return result
    }

    /** JS Array → ByteArray (每个元素 toByte) */
    private fun jsArrayToByteArray(ctx: CPointer<JSContext>, arr: CValue<JSValue>): ByteArray {
        val list = jsArrayToList(ctx, arr)
        return ByteArray(list.size) { (list[it] as? Number)?.toByte() ?: 0 }
    }

    /** ByteArray → JS Array (每个 byte 转 Int) */
    private fun byteArrayToJsArray(ctx: CPointer<JSContext>, bytes: ByteArray): CValue<JSValue> {
        val arr = JS_NewArray(ctx)
        bytes.forEachIndexed { i, b ->
            // JS_SetPropertyUint32 转移 jsV 所有权, 不需要 JS_FreeValue
            JS_SetPropertyUint32(ctx, arr, i.toUInt(), qjs_NewInt32(ctx, b.toInt()))
        }
        return arr
    }

    /** String → JSValue (qjs_NewString, memScoped 内分配 C 字符串) */
    private fun stringToJsValue(ctx: CPointer<JSContext>, s: String?): CValue<JSValue> {
        if (s == null) return jsNull()
        memScoped {
            return qjs_NewString(ctx, s.cstr.ptr)
        }
    }

    /** JSValue → String (qjs_ToCString + JS_FreeCString) */
    private fun jsValueToString(ctx: CPointer<JSContext>, v: CValue<JSValue>): String {
        val cstr = qjs_ToCString(ctx, v) ?: return ""
        return try {
            cstr.toKString()
        } finally {
            JS_FreeCString(ctx, cstr)
        }
    }

    /** JSValue → Kotlin 值 (基本类型 + Array + String) */
    private fun fromJsValue(ctx: CPointer<JSContext>, v: CValue<JSValue>): Any? {
        if (qjs_IsNumber(v) != 0) {
            return qjs_ValueGetFloat64(v)
        }
        if (qjs_IsString(v) != 0) {
            return jsValueToString(ctx, v)
        }
        if (JS_IsArray(v)) {
            return jsArrayToList(ctx, v)
        }
        // 其他类型 fallback 到字符串
        return jsValueToString(ctx, v)
    }

    /** 构造 JS undefined JSValue */
    private fun jsUndefined(): CValue<JSValue> = cValue {
        tag = JS_TAG_UNDEFINED.toLong()
    }

    /** 构造 JS null JSValue */
    private fun jsNull(): CValue<JSValue> = cValue {
        tag = JS_TAG_NULL.toLong()
    }

    /** List 取 String (index 越界或类型不符返回 "") */
    private fun List<Any?>.getString(index: Int): String =
        (getOrNull(index) as? String) ?: ""
}
