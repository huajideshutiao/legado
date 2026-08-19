@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import com.fleeksoft.ksoup.nodes.Node
import io.legado.app.help.JsEncodeUtilsDefaults
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapterLike
import io.legado.app.help.JsExtensionsCommon
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.NativeAsymmetricCrypto
import io.legado.app.help.crypto.NativeSign
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SymmetricCrypto
import io.legado.app.help.crypto.NativeSymmetricCrypto
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.SourceCacheProvider
import io.legado.app.help.source.SourceNetworkProvider
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.GSON
import io.legado.app.utils.JsURL
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import org.jsoup.Connection
import com.script.jsdispatch.generated.NativeDispatchResult
import com.script.jsdispatch.generated.NativeGeneratedDispatch
import io.legado.app.napi.quickjs.JSContext
import io.legado.app.napi.quickjs.JSValue
import io.legado.app.napi.quickjs.qjs_EvalTypeGlobal
import io.legado.app.napi.quickjs.JS_Eval
import io.legado.app.napi.quickjs.JS_TAG_NULL
import io.legado.app.napi.quickjs.JS_TAG_UNDEFINED
import io.legado.app.napi.quickjs.qjs_FreeCString
import io.legado.app.napi.quickjs.JS_FreeValue
import io.legado.app.napi.quickjs.JS_GetLength
import io.legado.app.napi.quickjs.JS_GetPropertyUint32
import io.legado.app.napi.quickjs.JS_IsArray
import io.legado.app.napi.quickjs.JS_NewArray
import io.legado.app.napi.quickjs.JS_NewObject
import io.legado.app.napi.quickjs.JS_SetPropertyStr
import io.legado.app.napi.quickjs.JS_SetPropertyUint32
import io.legado.app.napi.quickjs.qjs_IsBool
import io.legado.app.napi.quickjs.qjs_IsNull
import io.legado.app.napi.quickjs.qjs_IsNumber
import io.legado.app.napi.quickjs.qjs_IsString
import io.legado.app.napi.quickjs.qjs_IsUndefined
import io.legado.app.napi.quickjs.qjs_NewBool
import io.legado.app.napi.quickjs.qjs_NewFloat64
import io.legado.app.napi.quickjs.qjs_NewInt32
import io.legado.app.napi.quickjs.qjs_NewString
import io.legado.app.napi.quickjs.qjs_ValueGetBool
import io.legado.app.napi.quickjs.qjs_ToCString
import io.legado.app.napi.quickjs.qjs_ValueGetFloat64
import io.legado.app.napi.quickjs.qjs_ValueGetInt
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
 * - 23-28: 规则引擎核心补齐 (getSource/put/get/evalJS/getString/getStringList, 对齐 JVM @JsApi 分派表)
 * - 100-199: JsEncodeUtilsDefaults 摘要/HMAC 面 (md5Encode/md5Encode16/digestHex/digestBase64Str/HMacHex/HMacBase64)
 * - 200-299: 工厂方法 (createSymmetricCrypto/createAsymmetricCrypto/createSign) 返回 handle (Float64)
 * - 1000-1099: SymmetricCrypto 对象方法 (encryptBase64/decryptStr/decrypt/setIv/encrypt/encryptHex)
 * - 1100-1199: AsymmetricCrypto 对象方法 (setPrivateKey/setPublicKey/decrypt/encrypt/decryptStr/encryptHex/encryptBase64)
 * - 1200-1299: Sign 对象方法 (setPrivateKey/setPublicKey/sign/signHex/verify)
 * - 13-29: 编解码补齐/重载 (strToBytes/bytesToStr/base64 系/hexDecodeToByteArray/encodeURI/timeFormatUTC/toNumChapter/toURL)
 * - 300-399: 网络族 (ajax/ajaxAll/connect/webView 系/getCookie/getWebViewUA; 309-311 java get/head/post)
 * - 400-499: UI/杂项 (refreshUi/log/logType/toast/longToast/androidId/openUrl/startBrowser 系/getVerificationCode/copy)
 * - 500-599: 字体族 (queryTTF/queryBase64TTF/replaceFont)
 * - 600-699: 文件/压缩族 (getFile/readFile/readTxtFile/deleteFile/解压全族/downloadFile/cacheFile/importScript)
 * - 700-799: Connection.Response 对象方法 (java.get/head/post 返回值: body/statusCode/url/header/cookie 等;
 *   规整方法已由 KSP 生成表接管, 手写仅剩 704 url()/712 method() 特例)
 * - 1300-1399: QueryTTF 对象方法 (规整函数手写闭包与生成表并存: 生成注入点在 JS 工厂
 *   @@methods 标记处、位于手写定义之后, 同名以生成为准; 1306-1308 映射表属性为生成器
 *   不覆盖项, 仅手写); 1400-1499: StrResponse 对象方法 (规整已生成表接管,
 *   手写仅 1406 headers toString 降级); 1500-1599: JsURL 对象属性 (仅属性, 生成器无输出, 全手写);
 *   1600-1699: BaseSource 对象方法 (getKey/getTag/getSourceType/getLoginJs 已生成表接管; 手写仅
 *   1604 getHeaderMap 特例与 1605/1606/1608/1609 属性 getter 方法化 —— 属性不在生成范围, 手写保留)
 * - 1700-2199: 复杂对象属性桥 (NativeJsPropertyBridge, book/source/chapter/java 属性 getter,
 *   分派表与 JS 工厂见 NativeJsPropertyBridge.native.kt; >= 1700 在 dispatch 开头短路转发)
 * - 2200+: 带参分派 (NativeJsPropertyBridge.dispatchWithArgs): 2300-2399 book 变量/方法面 |
 *   2400-2499 chapter 变量/方法面 | 2500-2599 cookie (SourceNetworkProvider) |
 *   2600-2699 cache (SourceCacheProvider) | 2700-3199 属性写 (setterId = getterId + 1000) |
 *   3200-3299 ksoup Element/Node 方法面 (src binding 逐项循环)
 * - 5000+: KSP 生成表 (NativeGeneratedDispatch, JsApiProcessor 按 jsapi.nativeTargets 目标类生成;
 *   dispatch 顶部查表优先, JS 闭包按工厂分区注入 `// @@methods:<factory>@@` 标记处)
 *
 * 注: AnalyzeUrlCore/AnalyzeRuleCore 不实现 JsEncodeUtilsDefaults (JVM 端由 JsExtensionsJvm
 * 多继承注入同接口), 100-199 摘要段对规则类用同源默认实现补全 (encodeDefaultsOf),
 * 保证书源 java.md5Encode 等与 Android 端同结果; createSymmetricCrypto 等工厂方法直接调用
 * native 端 [NativeSymmetricCrypto], 不依赖 JsEncodeUtilsDefaults。
 *
 * 注: cookie/cache binding 注入依赖 [NativeJsEngine.toJsValue] 识别 SourceNetworkProvider /
 * SourceCacheProvider (NativeJsEngine.native.kt, 需并行修改方生效; 工厂与分派段已在本文件就绪)。
 */
object NativeJsExtensionsBridge {

    /**
     * handle → Kotlin 对象映射 (强引用, 防止 GC)。
     *
     * 全局共享: iOS/鸿蒙各线程独立 scope 并发 eval, register/unregister/lookup 跨线程
     * 访问同一表; K/N 无 ConcurrentHashMap, 所有操作由 [handleTableLock] 串行化。
     */
    private val handleTable = HashMap<Long, Any>()

    /** handle 表全局锁 (可重入; 临界区只做 map 操作, 不含 JS 回调/IO, 无死锁风险)。 */
    private val handleTableLock = SynchronizedObject()

    /**
     * handle 自增计数器。
     *
     * 在 [handleTableLock] 内自增并入表 (成对原子), 保证跨线程 handle 唯一。
     */
    private var handleCounter: Long = 0

    /** 注册 Kotlin 对象, 返回 handle */
    fun registerObject(obj: Any): Long = synchronized(handleTableLock) {
        handleCounter += 1
        val handle = handleCounter
        handleTable[handle] = obj
        handle
    }

    /** 取 Kotlin 对象 */
    fun getObject(handle: Long): Any? = synchronized(handleTableLock) {
        handleTable[handle]
    }

    /** 注销 handle (scope close 时调用) */
    fun unregisterObject(handle: Long) {
        synchronized(handleTableLock) {
            handleTable.remove(handle)
        }
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
            is QueryTTF -> "__createQueryTTFObj"
            is StrResponse -> "__createStrResponseObj"
            is JsURL -> "__createJsUrlObj"
            // 复杂对象属性桥 (NativeJsPropertyBridge): 工厂带属性 getter
            is BaseSource -> "__createBaseSourceObj"
            is BaseBook -> "__createBookObj"
            is BookChapterLike -> "__createChapterObj"
            is AnalyzeUrlCore, is AnalyzeRuleCore -> "__createAnalyzeObj"
            // cookie/cache binding (SourceNetworkProvider/SourceCacheProvider, 注入侧见 NativeJsEngine)
            is SourceNetworkProvider -> "__createCookieObj"
            is SourceCacheProvider -> "__createCacheObj"
            // ksoup Element/Node (src binding 逐项循环场景, 工厂见 NativeJsPropertyBridge)
            is Node -> "__createElementObj"
            else -> "__createJavaObj"
        }
        val js = "$factoryFn($handle)"
        // JS_Eval 执行工厂函数, 返回 JS 对象
        val result = JS_Eval(
            ctx, js, js.length.toULong(), "<bridge>", qjs_EvalTypeGlobal()
        )
        // result 是 JS 对象, 调用方负责 JS_FreeValue
        // 但我们在 toJsValue 中返回它, 由 JS_SetPropertyStr 转移所有权
        // 如果 eval 异常, result 是 exception, 调用方会得到异常 JSValue
        // 此处不检查异常 (toJsValue 不检查), 让上层 eval 时暴露
        return result
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
        staticCFunction { ctx: CPointer<JSContext>?, thisVal: CValue<JSValue>, argc: Int, argv: CPointer<JSValue>? ->
            nativeDispatchImpl(ctx, thisVal, argc, argv)
        }

    /**
     * nativeDispatch 的 Kotlin 实现 (object 方法, 可被 staticCFunction 调用)。
     */
    private fun nativeDispatchImpl(
        ctx: CPointer<JSContext>?,
        @Suppress("UNUSED_PARAMETER") thisVal: CValue<JSValue>,
        argc: Int,
        argv: CPointer<JSValue>?
    ): CValue<JSValue> {
        val ctxNotNull = ctx ?: return jsUndefined()
        if (argc < 3 || argv == null) return jsUndefined()
        try {
            val handle = qjs_ValueGetFloat64(argv!![0L].readValue()).toLong()
            val methodId = qjs_ValueGetInt(argv!![1L].readValue())
            val argsArray = argv!![2L].readValue()
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
        // KSP 生成表优先: 未命中 (NONE) 落下方手写分支。生成物为纯 Kotlin (无 cinterop 依赖),
        // 返回值经 [nativeResultToJs] 转 JSValue (与手写分支同层转换语义)。
        // registerHandle: REF 返回白名单方法 (如 Response.parse) 注册对象 handle,
        // JS 层由生成闭包里的工厂函数 (__createElementObj 等) 包装。
        val generated =
            NativeGeneratedDispatch.dispatch(
                obj,
                methodId,
                jsArrayToList(ctx, argsArray),
                ::registerObject
            )
        if (generated !== NativeDispatchResult.NONE) return nativeResultToJs(ctx, generated)
        // 复杂对象属性/方法段 (>= 1700, NativeJsPropertyBridge):
        // 1700-2199 属性 getter 无参数, 直接转发跳过 JS Array 解析 (hot path: 一次属性读取零堆分配);
        // 2200+ 属性写/变量方法/cookie/cache 需解析参数后转发 (setter 与调用路径, 非 hot path)
        if (methodId >= PROPERTY_ID_BASE) {
            return if (methodId < PROPERTY_WRITE_BASE) {
                NativeJsPropertyBridge.dispatch(ctx, obj, methodId) ?: jsUndefined()
            } else {
                val args = jsArrayToList(ctx, argsArray)
                NativeJsPropertyBridge.dispatchWithArgs(ctx, obj, methodId, args) ?: jsUndefined()
            }
        }
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
            obj is JsExtensionsCommon && methodId == 14 -> {
                // bytesToStr(bytes, charset) — bytes 为 args[0] (JS Array)
                val bytes = args.getOrNull(0).toBytesOrNull() ?: ByteArray(0)
                stringToJsValue(ctx, obj.bytesToStr(bytes, args.getString(1)))
            }
            obj is JsExtensionsCommon && methodId == 15 -> {
                // base64Decode(str, charset|flags) — 第二参类型分派重载
                when (val second = args.getOrNull(1)) {
                    is Number -> stringToJsValue(ctx, obj.base64Decode(args.getString(0), second.toInt()))
                    else -> stringToJsValue(
                        ctx, obj.base64Decode(args.getOrNull(0) as? String, (second as? String) ?: "UTF-8")
                    )
                }
            }
            obj is JsExtensionsCommon && methodId == 16 -> {
                // base64DecodeToByteArray(str[, flags]) → ByteArray?
                val flags = (args.getOrNull(1) as? Number)?.toInt()
                val bytes = if (flags != null) obj.base64DecodeToByteArray(args.getOrNull(0) as? String, flags)
                else obj.base64DecodeToByteArray(args.getOrNull(0) as? String)
                bytes?.let { byteArrayToJsArray(ctx, it) } ?: jsNull()
            }
            obj is JsExtensionsCommon && methodId == 17 -> {
                // base64Encode(str, flags)
                stringToJsValue(ctx, obj.base64Encode(args.getString(0), (args.getOrNull(1) as? Number)?.toInt() ?: 2))
            }
            obj is JsExtensionsCommon && methodId == 23 -> {
                // getSource() → BaseSource handle (0 = null, JS 层 __createBaseSourceObj 包装)
                val source = when (obj) {
                    is AnalyzeRuleCore -> obj.getSource()
                    is AnalyzeUrlCore -> obj.getSource()
                    is BaseSource -> obj // BaseSource.getSource() 返回自身 (与 JVM 行为一致)
                    else -> null
                }
                qjs_NewFloat64(ctx, (source?.let { registerObject(it) } ?: 0L).toDouble())
            }

            obj is JsExtensionsCommon && methodId == 24 -> {
                // put(key, value) → value (AnalyzeRuleCore/AnalyzeUrlCore 共用, 保存变量)
                stringToJsValue(ctx, obj.corePut(args.getString(0), args.getString(1)))
            }

            obj is JsExtensionsCommon && methodId == 25 -> {
                // get(key) → String (读取变量)
                stringToJsValue(ctx, obj.coreGet(args.getString(0)))
            }

            obj is JsExtensionsCommon && methodId == 26 -> {
                // evalJS(jsStr) → 任意值 (String/Number/Boolean/Map/List → JS 值)
                val result = obj.coreEvalJS(args.getString(0))
                anyToJs(ctx, result)
            }

            obj is AnalyzeRuleCore && methodId == 27 -> {
                // getString(rule, content[, isUrl]) → String (规则解析, 与 JVM AnalyzeRule.getString 对齐)
                val isUrl = (args.getOrNull(2) as? Boolean) ?: false
                stringToJsValue(
                    ctx,
                    obj.getString(args.getOrNull(0) as? String, args.getOrNull(1), isUrl)
                )
            }

            obj is AnalyzeRuleCore && methodId == 28 -> {
                // getStringList(rule, content[, isUrl]) → String[] (null → JS null)
                val isUrl = (args.getOrNull(2) as? Boolean) ?: false
                val list = obj.getStringList(args.getOrNull(0) as? String, args.getOrNull(1), isUrl)
                if (list == null) {
                    jsNull()
                } else {
                    val arr = JS_NewArray(ctx)
                    list.forEachIndexed { i, s ->
                        JS_SetPropertyUint32(ctx, arr, i.toUInt(), stringToJsValue(ctx, s))
                    }
                    arr
                }
            }

            // ============ JsEncodeUtilsDefaults 摘要/HMAC 面 (100-199) ============
            // AnalyzeRuleCore/AnalyzeUrlCore 不实现 JsEncodeUtilsDefaults (JVM 端由 JsExtensionsJvm
            // 多继承注入同接口), 规则类走同源默认实现 (NativeRuleEncodeDefaults, 复用 MD5Utils /
            // NativeDigestOps/NativeHmacOps, 非重写), 保证 java.md5Encode 等与 Android 端同结果
            methodId in 101..106 -> encodeDefaultsOf(obj)?.let { enc ->
                when (methodId) {
                    101 -> stringToJsValue(ctx, enc.md5Encode(args.getString(0)))
                    102 -> stringToJsValue(ctx, enc.md5Encode16(args.getString(0)))
                    103 -> stringToJsValue(ctx, enc.digestHex(args.getString(0), args.getString(1)))
                    104 -> stringToJsValue(ctx, enc.digestBase64Str(args.getString(0), args.getString(1)))
                    105 -> stringToJsValue(ctx, enc.HMacHex(args.getString(0), args.getString(1), args.getString(2)))
                    106 -> stringToJsValue(ctx, enc.HMacBase64(args.getString(0), args.getString(1), args.getString(2)))
                    else -> jsUndefined()
                }
            } ?: jsUndefined()

            // ============ 工厂方法 (200-299) → 返回 handle (Float64) ============
            obj is JsExtensionsCommon && methodId == 201 -> {
                // createSymmetricCrypto(transformation, key, iv) → cryptoHandle (key/iv 支持 Utf8String|ByteArray)
                val transformation = args.getString(0)
                val key = args.getOrNull(1).toBytesOrNull()
                val iv = args.getOrNull(2).toBytesOrNull()
                val crypto = NativeSymmetricCrypto(transformation, key)
                if (iv != null && iv.isNotEmpty()) crypto.setIv(iv)
                val handle = registerObject(crypto)
                // 注意: crypto handle 不添加到 scope.handles, 因为调用方 scope 不可达
                // crypto 对象生命周期与 JS 引用绑定, handle 泄漏量可控 (每次调用一个)
                qjs_NewFloat64(ctx, handle.toDouble())
            }
            obj is JsExtensionsCommon && methodId == 202 -> {
                // createAsymmetricCrypto(transformation) → cryptoHandle (iOS Security.framework / 鸿蒙 napi 桥接)
                val transformation = args.getString(0)
                val crypto = NativeAsymmetricCrypto(transformation)
                val handle = registerObject(crypto)
                qjs_NewFloat64(ctx, handle.toDouble())
            }
            obj is JsExtensionsCommon && methodId == 203 -> {
                // createSign(algorithm) → signHandle (iOS Security.framework / 鸿蒙 napi 桥接)
                val algorithm = args.getString(0)
                val sign = NativeSign(algorithm)
                val handle = registerObject(sign)
                qjs_NewFloat64(ctx, handle.toDouble())
            }

            // ============ SymmetricCrypto 对象方法 (1000-1099) ============
            obj is SymmetricCrypto && methodId == 1001 -> {
                // encryptBase64(data) — data 支持 Utf8String|ByteArray
                when (val data = args.getOrNull(0)) {
                    is String -> stringToJsValue(ctx, obj.encryptBase64(data))
                    else -> stringToJsValue(ctx, obj.encryptBase64(data.toBytesOrNull() ?: ByteArray(0)))
                }
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
                // setIv(iv) → 真实存 IV (iv 支持 Utf8String|ByteArray); JS 层返回 this
                args.getOrNull(0).toBytesOrNull()?.let { obj.setIv(it) }
                jsUndefined()
            }
            obj is SymmetricCrypto && methodId == 1005 -> {
                // encrypt(data) → ByteArray → JS Array — data 支持 Utf8String|ByteArray
                when (val data = args.getOrNull(0)) {
                    is String -> byteArrayToJsArray(ctx, obj.encrypt(data))
                    else -> byteArrayToJsArray(ctx, obj.encrypt(data.toBytesOrNull() ?: ByteArray(0)))
                }
            }
            obj is SymmetricCrypto && methodId == 1006 -> {
                // encryptHex(data) — data 支持 Utf8String|ByteArray
                when (val data = args.getOrNull(0)) {
                    is String -> stringToJsValue(ctx, obj.encryptHex(data))
                    else -> stringToJsValue(ctx, obj.encryptHex(data.toBytesOrNull() ?: ByteArray(0)))
                }
            }

            // ============ AsymmetricCrypto 对象方法 (1100-1199, iOS Security.framework / 鸿蒙 napi 桥接) ============
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
                // decrypt(data, usePublicKey) → ByteArray (iOS 真实 / 鸿蒙抛异常)
                val usePublicKey = (args.getOrNull(1) as? Boolean) ?: true
                byteArrayToJsArray(ctx, obj.decrypt(args.getDataArg(0), usePublicKey))
            }
            obj is AsymmetricCrypto && methodId == 1104 -> {
                // encrypt(data, usePublicKey) → ByteArray (iOS 真实 / 鸿蒙抛异常)
                val usePublicKey = (args.getOrNull(1) as? Boolean) ?: true
                byteArrayToJsArray(ctx, obj.encrypt(args.getDataArg(0), usePublicKey))
            }
            obj is AsymmetricCrypto && methodId == 1105 -> {
                // decryptStr(data, usePublicKey) — data String 走 hex 优先否则 base64 (hutool SecureUtil.decode)
                val usePublicKey = (args.getOrNull(1) as? Boolean) ?: true
                stringToJsValue(ctx, obj.decryptStr(args.getDataArg(0), usePublicKey))
            }
            obj is AsymmetricCrypto && methodId == 1106 -> {
                // encryptHex(data, usePublicKey)
                val usePublicKey = (args.getOrNull(1) as? Boolean) ?: true
                stringToJsValue(ctx, obj.encryptHex(args.getDataArg(0), usePublicKey))
            }
            obj is AsymmetricCrypto && methodId == 1107 -> {
                // encryptBase64(data, usePublicKey)
                val usePublicKey = (args.getOrNull(1) as? Boolean) ?: true
                stringToJsValue(ctx, obj.encryptBase64(args.getDataArg(0), usePublicKey))
            }

            // ============ Sign 对象方法 (1200-1299, iOS Security.framework 真实 / 鸿蒙 napi) ============
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
            obj is NativeSign && methodId == 1203 -> {
                // sign(data) → ByteArray → JS Array — data 支持 Utf8String|ByteArray
                when (val data = args.getDataArg(0)) {
                    is String -> byteArrayToJsArray(ctx, obj.sign(data))
                    is ByteArray -> byteArrayToJsArray(ctx, obj.sign(data))
                    else -> jsUndefined()
                }
            }
            obj is NativeSign && methodId == 1204 -> {
                // signHex(data) — 小写 hex (对齐 hutool HexUtil.encodeHexStr)
                when (val data = args.getDataArg(0)) {
                    is String -> stringToJsValue(ctx, obj.signHex(data))
                    is ByteArray -> stringToJsValue(ctx, obj.signHex(data))
                    else -> jsUndefined()
                }
            }
            obj is NativeSign && methodId == 1205 -> {
                // verify(data, sign) → Boolean
                val sig = args.getOrNull(1).toBytesOrNull() ?: ByteArray(0)
                val result = when (val data = args.getDataArg(0)) {
                    is String -> obj.verify(data, sig)
                    is ByteArray -> obj.verify(data, sig)
                    else -> false
                }
                qjs_NewBool(ctx, if (result) 1 else 0)
            }

            // ============ 网络族 (300-399) ============
            obj is JsExtensionsCommon && methodId == 301 -> {
                // ajax(url) — url 支持 String | Array
                stringToJsValue(ctx, obj.ajax(args.getOrNull(0) ?: ""))
            }
            obj is JsExtensionsCommon && methodId == 302 -> {
                // ajaxAll(urlList) → StrResponse handle 数组 (JS 层逐个包装)
                val urls = (args.getOrNull(0) as? List<*>)?.map { it.toString() }?.toTypedArray()
                    ?: emptyArray()
                val responses = obj.ajaxAll(urls)
                val arr = JS_NewArray(ctx)
                responses.forEachIndexed { i, resp ->
                    JS_SetPropertyUint32(
                        ctx, arr, i.toUInt(),
                        qjs_NewFloat64(ctx, registerObject(resp).toDouble())
                    )
                }
                arr
            }
            obj is JsExtensionsCommon && methodId == 303 -> {
                // connect(urlStr[, header]) → StrResponse handle (header null 时与单参重载等价)
                val resp = obj.connect(args.getString(0), args.getOrNull(1) as? String)
                qjs_NewFloat64(ctx, registerObject(resp).toDouble())
            }
            obj is JsExtensionsCommon && methodId == 304 -> {
                // webView(html, url, js[, delayTime]) — common 内部 runBlocking 同步返回
                val delay = (args.getOrNull(3) as? Number)?.toLong()
                val body = if (delay != null) {
                    obj.webView(args.getStringOrNull(0), args.getStringOrNull(1), args.getStringOrNull(2), delay)
                } else {
                    obj.webView(args.getStringOrNull(0), args.getStringOrNull(1), args.getStringOrNull(2))
                }
                stringToJsValue(ctx, body)
            }
            obj is JsExtensionsCommon && methodId == 305 -> {
                // webViewGetSource(html, url, js, sourceRegex[, delayTime])
                val delay = (args.getOrNull(4) as? Number)?.toLong()
                val body = if (delay != null) {
                    obj.webViewGetSource(
                        args.getStringOrNull(0), args.getStringOrNull(1),
                        args.getStringOrNull(2), args.getStringOrNull(3), delay
                    )
                } else {
                    obj.webViewGetSource(
                        args.getStringOrNull(0), args.getStringOrNull(1),
                        args.getStringOrNull(2), args.getStringOrNull(3)
                    )
                }
                stringToJsValue(ctx, body)
            }
            obj is JsExtensionsCommon && methodId == 306 -> {
                // webViewGetOverrideUrl(html, url, js, overrideUrlRegex[, delayTime])
                val delay = (args.getOrNull(4) as? Number)?.toLong()
                val body = if (delay != null) {
                    obj.webViewGetOverrideUrl(
                        args.getStringOrNull(0), args.getStringOrNull(1),
                        args.getStringOrNull(2), args.getStringOrNull(3), delay
                    )
                } else {
                    obj.webViewGetOverrideUrl(
                        args.getStringOrNull(0), args.getStringOrNull(1),
                        args.getStringOrNull(2), args.getStringOrNull(3)
                    )
                }
                stringToJsValue(ctx, body)
            }
            obj is JsExtensionsCommon && methodId == 307 -> {
                // getCookie(tag[, key])
                stringToJsValue(ctx, obj.getCookie(args.getString(0), args.getOrNull(1) as? String))
            }
            obj is JsExtensionsCommon && methodId == 309 -> {
                // java.get(url, headers) — 网络 GET (单参 java.get(key) 走 25 变量读取; 双参分派对齐 JVM)
                // headers 由 JS 层 JSON.stringify (对象/JSON 字符串都转成 JSON 文本), 此处解析回 Map
                val headerMap = parseHeaderArg(args.getOrNull(1))
                qjs_NewFloat64(ctx, registerObject(obj.get(args.getString(0), headerMap)).toDouble())
            }
            obj is JsExtensionsCommon && methodId == 310 -> {
                // java.head(url, headers)
                val headerMap = parseHeaderArg(args.getOrNull(1))
                qjs_NewFloat64(ctx, registerObject(obj.head(args.getString(0), headerMap)).toDouble())
            }
            obj is JsExtensionsCommon && methodId == 311 -> {
                // java.post(url, body, headers)
                val headerMap = parseHeaderArg(args.getOrNull(2))
                qjs_NewFloat64(ctx, registerObject(obj.post(args.getString(0), args.getString(1), headerMap)).toDouble())
            }

            // ============ Connection.Response 对象方法 (700-799, java.get/head/post 返回值) ============
            // 规整方法已由 KSP 生成表接管 (5000+ 段); 以下为无法模板化的特例:
            // 704 url() URL→String; 712 method() 枚举 .name
            obj is Connection.Response && methodId == 704 -> stringToJsValue(ctx, obj.url()?.toString())
            obj is Connection.Response && methodId == 712 -> stringToJsValue(ctx, obj.method().name)

            obj is JsExtensionsCommon && methodId == 403 -> {
                // logType(any)
                obj.logType(args.getOrNull(0))
                jsUndefined()
            }
            obj is JsExtensionsCommon && methodId == 408 -> {
                // startBrowser(url, title)
                obj.startBrowser(args.getString(0), args.getString(1))
                jsUndefined()
            }
            obj is JsExtensionsCommon && methodId == 409 -> {
                // startBrowserAwait(url, title[, refetchAfterSuccess]) → StrResponse handle
                val refetch = (args.getOrNull(2) as? Boolean) ?: false
                val resp = obj.startBrowserAwait(args.getString(0), args.getString(1), refetch)
                qjs_NewFloat64(ctx, registerObject(resp).toDouble())
            }
            // ============ 字体族 (500-599) ============
            obj is JsExtensionsCommon && methodId == 501 -> {
                // queryTTF(data[, useCache]) → QueryTTF handle (null → 0, JS 层转 null)
                val data = args.getDataArgOrNull(0)
                val useCache = (args.getOrNull(1) as? Boolean) ?: true
                val ttf = obj.queryTTF(data, useCache)
                qjs_NewFloat64(ctx, (ttf?.let { registerObject(it) } ?: 0L).toDouble())
            }
            obj is JsExtensionsCommon && methodId == 502 -> {
                // queryBase64TTF(data) — 已过时, 转发保持书源兼容
                @Suppress("DEPRECATION")
                val ttf = obj.queryBase64TTF(args.getOrNull(0) as? String)
                qjs_NewFloat64(ctx, (ttf?.let { registerObject(it) } ?: 0L).toDouble())
            }
            obj is JsExtensionsCommon && methodId == 503 -> {
                // replaceFont(text, errTTFHandle, corTTFHandle[, filter]) — handle 由 JS 包装层传回
                val err = (args.getOrNull(1) as? Number)?.toLong()?.let { getObject(it) as? QueryTTF }
                val cor = (args.getOrNull(2) as? Number)?.toLong()?.let { getObject(it) as? QueryTTF }
                val filter = (args.getOrNull(3) as? Boolean) ?: false
                stringToJsValue(ctx, obj.replaceFont(args.getString(0), err, cor, filter))
            }

            obj is JsExtensionsCommon && methodId == 611 -> {
                // getRarStringContent(url, path[, charsetName])
                val charset = args.getOrNull(2) as? String
                val text = if (charset != null) {
                    obj.getRarStringContent(args.getString(0), args.getString(1), charset)
                } else obj.getRarStringContent(args.getString(0), args.getString(1))
                stringToJsValue(ctx, text)
            }
            obj is JsExtensionsCommon && methodId == 612 -> {
                // get7zStringContent(url, path[, charsetName])
                val charset = args.getOrNull(2) as? String
                val text = if (charset != null) {
                    obj.get7zStringContent(args.getString(0), args.getString(1), charset)
                } else obj.get7zStringContent(args.getString(0), args.getString(1))
                stringToJsValue(ctx, text)
            }
            obj is JsExtensionsCommon && methodId == 617 -> {
                // cacheFile(urlStr[, saveTime])
                val saveTime = (args.getOrNull(1) as? Number)?.toInt()
                val text = if (saveTime != null) obj.cacheFile(args.getString(0), saveTime)
                else obj.cacheFile(args.getString(0))
                stringToJsValue(ctx, text)
            }
            // ============ QueryTTF 对象方法 (1300-1399) ============
            // getGlyfById 等规整函数已由 KSP 生成表接管 (5000+ 段, JS 闭包经 @@methods 注入点覆盖);
            // 此处仅保留 1306-1308 映射表属性 (生成器只收集函数, 属性不生成)
            obj is QueryTTF && methodId == 1301 -> {
                // getGlyfById(glyfId) → String?
                stringToJsValue(ctx, obj.getGlyfById((args.getOrNull(0) as? Number)?.toInt() ?: 0))
            }
            obj is QueryTTF && methodId == 1302 -> {
                // getGlyfIdByUnicode(unicode) → Int
                qjs_NewInt32(ctx, obj.getGlyfIdByUnicode((args.getOrNull(0) as? Number)?.toInt() ?: 0))
            }
            obj is QueryTTF && methodId == 1303 -> {
                // getGlyfByUnicode(unicode) → String?
                stringToJsValue(ctx, obj.getGlyfByUnicode((args.getOrNull(0) as? Number)?.toInt() ?: 0))
            }
            obj is QueryTTF && methodId == 1304 -> {
                // getUnicodeByGlyf(glyph) → Int
                qjs_NewInt32(ctx, obj.getUnicodeByGlyf(args.getOrNull(0) as? String))
            }
            obj is QueryTTF && methodId == 1305 -> {
                // isBlankUnicode(unicode) → Boolean
                qjs_NewBool(ctx, if (obj.isBlankUnicode((args.getOrNull(0) as? Number)?.toInt() ?: 0)) 1 else 0)
            }
            obj is QueryTTF && methodId == 1306 -> {
                // unicodeToGlyph 映射表 → JSON (JS 层 JSON.parse 后惰性缓存)
                stringToJsValue(ctx, GSON.toJson(obj.unicodeToGlyph))
            }
            obj is QueryTTF && methodId == 1307 -> {
                // glyphToUnicode 映射表 → JSON
                stringToJsValue(ctx, GSON.toJson(obj.glyphToUnicode))
            }
            obj is QueryTTF && methodId == 1308 -> {
                // unicodeToGlyphId 映射表 → JSON
                stringToJsValue(ctx, GSON.toJson(obj.unicodeToGlyphId))
            }

            // ============ StrResponse 对象方法 (1400-1499) ============
            // 规整方法已由 KSP 生成表接管; 1406 特例: headers() 降级为字符串
            // (native KmpHeaders 无完整对象桥, 与 Response.headers() 的 JSON 语义不同)
            obj is StrResponse && methodId == 1406 -> {
                stringToJsValue(ctx, obj.headers().toString())
            }

            // ============ JsURL 对象属性 (1500-1599) ============
            obj is JsURL && methodId == 1501 -> stringToJsValue(ctx, obj.host)
            obj is JsURL && methodId == 1502 -> stringToJsValue(ctx, obj.origin)
            obj is JsURL && methodId == 1503 -> stringToJsValue(ctx, obj.pathname)
            obj is JsURL && methodId == 1504 -> {
                // searchParams → JSON (null → JS null)
                obj.searchParams?.let { stringToJsValue(ctx, GSON.toJson(it)) } ?: jsNull()
            }

            // ============ BaseSource 对象方法 (1600-1699, getSource() 返回对象) ============
            // getKey/getTag/getSourceType/getLoginJs 已由 KSP 生成表接管; 1604 特例: getHeaderMap
            // 推断返回 HashMap 无法归类生成 (名单内, 手写 JSON + null 传播);
            // 1605/1606/1608/1609 为属性 getter 方法化 (阶段 3 E5 属性遍历接管)
            obj is BaseSource && methodId == 1604 -> {
                // getHeaderMap() → JSON 对象 (null → JS null)
                obj.getHeaderMap()?.let { stringToJsValue(ctx, GSON.toJson(it)) } ?: jsNull()
            }

            obj is BaseSource && methodId == 1605 -> stringToJsValue(ctx, obj.loginUrl)
            obj is BaseSource && methodId == 1606 -> stringToJsValue(ctx, obj.header)
            obj is BaseSource && methodId == 1608 -> stringToJsValue(ctx, obj.concurrentRate)
            obj is BaseSource && methodId == 1609 -> stringToJsValue(ctx, obj.jsLib)

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
     * KSP 生成表闭包按工厂分区注入: 每个工厂函数体内 `return obj;` 前有唯一标记注释
     * `// @@methods:<factory>@@`, 下方 val 声明的 `.let {}` 拼装块按
     * [NativeGeneratedDispatch.JS_METHOD_TABLES] 逐工厂替换 (见 JS_FACTORY_CODE 尾部)。
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
    obj.encodeURI = function(str) { return __nativeDispatch(handle, 7, [str]); };
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
    // ============ 重载覆盖 (新定义覆盖上方单参版; 单参路径仍走原 methodId, 行为不变) ============
    obj.strToBytes = function(str, charset) {
        return arguments.length >= 2 ? __nativeDispatch(handle, 13, [str, charset]) : __nativeDispatch(handle, 3, [str]);
    };
    obj.bytesToStr = function(bytes, charset) {
        return arguments.length >= 2 ? __nativeDispatch(handle, 14, [bytes, charset]) : __nativeDispatch(handle, 4, bytes);
    };
    obj.base64Decode = function(str, second) {
        return arguments.length >= 2 ? __nativeDispatch(handle, 15, [str, second]) : __nativeDispatch(handle, 2, [str]);
    };
    obj.base64Encode = function(str, flags) {
        return arguments.length >= 2 ? __nativeDispatch(handle, 17, [str, flags]) : __nativeDispatch(handle, 1, [str]);
    };
    obj.encodeURI = function(str, enc) {
        return arguments.length >= 2 ? __nativeDispatch(handle, 19, [str, enc]) : __nativeDispatch(handle, 7, [str]);
    };
    // ============ 编解码补齐 (13-29) ============
    obj.base64DecodeToByteArray = function(str, flags) { return __nativeDispatch(handle, 16, [str, flags]); };
    obj.toURL = function(url, baseUrl) { return __createJsUrlObj(__nativeDispatch(handle, 22, [url, baseUrl])); };
    // ============ 规则引擎核心补齐 (23-28, 对齐 JVM @JsApi 分派表) ============
    obj.getSource = function() { var h = __nativeDispatch(handle, 23, []); return h ? __createBaseSourceObj(h) : null; };
    obj.put = function(key, value) { return __nativeDispatch(handle, 24, [key, value]); };
    obj.get = function(key) { return __nativeDispatch(handle, 25, [key]); };
    obj.evalJS = function(jsStr) { return __nativeDispatch(handle, 26, [jsStr]); };
    obj.getString = function(rule, content, isUrl) { return __nativeDispatch(handle, 27, [rule, content, isUrl]); };
    obj.getStringList = function(rule, content, isUrl) { return __nativeDispatch(handle, 28, [rule, content, isUrl]); };
    // ============ 网络族 (300-399) ============
    obj.ajax = function(url) { return __nativeDispatch(handle, 301, [url]); };
    obj.ajaxAll = function(urlList) {
        var hs = __nativeDispatch(handle, 302, [urlList]);
        if (!hs) return [];
        var out = [];
        for (var i = 0; i < hs.length; i++) out.push(__createStrResponseObj(hs[i]));
        return out;
    };
    obj.connect = function(urlStr, header) { return __createStrResponseObj(__nativeDispatch(handle, 303, [urlStr, header])); };
    obj.webView = function(html, url, js, delayTime) { return __nativeDispatch(handle, 304, [html, url, js, delayTime]); };
    obj.webViewGetSource = function(html, url, js, sourceRegex, delayTime) { return __nativeDispatch(handle, 305, [html, url, js, sourceRegex, delayTime]); };
    obj.webViewGetOverrideUrl = function(html, url, js, overrideUrlRegex, delayTime) { return __nativeDispatch(handle, 306, [html, url, js, overrideUrlRegex, delayTime]); };
    obj.getCookie = function(tag, key) { return __nativeDispatch(handle, 307, [tag, key]); };
    // java.get 重载: 单参 = 变量读取 (25), 双参 = 网络 GET (309) — 对齐 JVM 分派表按参数个数分派
    obj.get = function(a, b) {
        if (arguments.length >= 2) {
            var h = (typeof b === 'string') ? b : JSON.stringify(b);
            return __createRespObj(__nativeDispatch(handle, 309, [a, h]));
        }
        return __nativeDispatch(handle, 25, [a]);
    };
    obj.head = function(urlStr, headers) {
        var h = (typeof headers === 'string') ? headers : JSON.stringify(headers);
        return __createRespObj(__nativeDispatch(handle, 310, [urlStr, h]));
    };
    obj.post = function(urlStr, body, headers) {
        var h = (typeof headers === 'string') ? headers : JSON.stringify(headers);
        return __createRespObj(__nativeDispatch(handle, 311, [urlStr, body, h]));
    };
    // ============ UI/杂项 (400-499) ============
    obj.log = function(msg) { __nativeDispatch(handle, 402, [msg]); return msg; };
    obj.logType = function(any) { __nativeDispatch(handle, 403, [any]); };
    obj.openUrl = function(url, mimeType) { __nativeDispatch(handle, 407, [url, mimeType]); };
    obj.startBrowser = function(url, title) { __nativeDispatch(handle, 408, [url, title]); };
    obj.startBrowserAwait = function(url, title, refetchAfterSuccess) {
        return __createStrResponseObj(__nativeDispatch(handle, 409, [url, title, refetchAfterSuccess]));
    };
    // ============ 字体族 (500-599) ============
    obj.queryTTF = function(data, useCache) { return __createQueryTTFObj(__nativeDispatch(handle, 501, [data, useCache])); };
    obj.queryBase64TTF = function(data) { return __createQueryTTFObj(__nativeDispatch(handle, 502, [data])); };
    obj.replaceFont = function(text, errorQueryTTF, correctQueryTTF, filter) {
        var e = errorQueryTTF ? errorQueryTTF.__h : null;
        var c = correctQueryTTF ? correctQueryTTF.__h : null;
        return __nativeDispatch(handle, 503, [text, e, c, filter === true]);
    };
    // ============ 文件/压缩族 (600-699) ============
    obj.readTxtFile = function(path, charsetName) { return __nativeDispatch(handle, 603, [path, charsetName]); };
    obj.getZipStringContent = function(url, path, charsetName) { return __nativeDispatch(handle, 610, [url, path, charsetName]); };
    obj.getRarStringContent = function(url, path, charsetName) { return __nativeDispatch(handle, 611, [url, path, charsetName]); };
    obj.get7zStringContent = function(url, path, charsetName) { return __nativeDispatch(handle, 612, [url, path, charsetName]); };
    obj.downloadFile = function(a, b) { return __nativeDispatch(handle, 616, [a, b]); };
    obj.cacheFile = function(urlStr, saveTime) { return __nativeDispatch(handle, 617, [urlStr, saveTime]); };
    // @@methods:__createJavaObj@@
    return obj;
}

function __createCryptoObj(handle) {
    var obj = {};
    obj.encryptBase64 = function(data) { return __nativeDispatch(handle, 1001, [data]); };
    obj.decryptStr = function(data) { return __nativeDispatch(handle, 1002, [data]); };
    obj.decrypt = function(data) { return __nativeDispatch(handle, 1003, [data]); };
    obj.setIv = function(iv) { __nativeDispatch(handle, 1004, [iv]); return obj; };
    obj.encrypt = function(data) { return __nativeDispatch(handle, 1005, [data]); };
    obj.encryptHex = function(data) { return __nativeDispatch(handle, 1006, [data]); };
    // @@methods:__createCryptoObj@@
    return obj;
}

function __createAsymCryptoObj(handle) {
    var obj = {};
    obj.setPrivateKey = function(key) { __nativeDispatch(handle, 1101, [key]); return obj; };
    obj.setPublicKey = function(key) { __nativeDispatch(handle, 1102, [key]); return obj; };
    obj.decrypt = function(data, usePublicKey) { return __nativeDispatch(handle, 1103, [data, usePublicKey]); };
    obj.encrypt = function(data, usePublicKey) { return __nativeDispatch(handle, 1104, [data, usePublicKey]); };
    obj.decryptStr = function(data, usePublicKey) { return __nativeDispatch(handle, 1105, [data, usePublicKey]); };
    obj.encryptHex = function(data, usePublicKey) { return __nativeDispatch(handle, 1106, [data, usePublicKey]); };
    obj.encryptBase64 = function(data, usePublicKey) { return __nativeDispatch(handle, 1107, [data, usePublicKey]); };
    // @@methods:__createAsymCryptoObj@@
    return obj;
}

function __createSignObj(handle) {
    var obj = {};
    obj.setPrivateKey = function(key) { __nativeDispatch(handle, 1201, [key]); return obj; };
    obj.setPublicKey = function(key) { __nativeDispatch(handle, 1202, [key]); return obj; };
    obj.sign = function(data) { return __nativeDispatch(handle, 1203, [data]); };
    obj.signHex = function(data) { return __nativeDispatch(handle, 1204, [data]); };
    obj.verify = function(data, sign) { return __nativeDispatch(handle, 1205, [data, sign]); };
    // @@methods:__createSignObj@@
    return obj;
}

function __createQueryTTFObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle; // replaceFont 回传句柄用
    // getGlyfById 等规整函数手写闭包 (生成表注入点在下方 @@methods 标记处, 位于其后,
    // 同名定义以生成为准); 映射表属性: 首次访问时 JSON 反序列化并缓存 (生成器只收集函数)
    obj.getGlyfById = function(glyfId) { return __nativeDispatch(handle, 1301, [glyfId]); };
    obj.getGlyfIdByUnicode = function(unicode) { return __nativeDispatch(handle, 1302, [unicode]); };
    obj.getGlyfByUnicode = function(unicode) { return __nativeDispatch(handle, 1303, [unicode]); };
    obj.getUnicodeByGlyf = function(glyph) { return __nativeDispatch(handle, 1304, [glyph]); };
    obj.isBlankUnicode = function(unicode) { return __nativeDispatch(handle, 1305, [unicode]); };
    // 映射表属性: 首次访问时 JSON 反序列化并缓存
    [["unicodeToGlyph", 1306], ["glyphToUnicode", 1307], ["unicodeToGlyphId", 1308]].forEach(function(m) {
        var cache = null;
        Object.defineProperty(obj, m[0], {
            get: function() {
                if (cache === null) cache = JSON.parse(__nativeDispatch(handle, m[1], []) || "{}");
                return cache;
            }
        });
    });
    // @@methods:__createQueryTTFObj@@
    return obj;
}

function __createStrResponseObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle;
    // 规整方法 (body/url/code/message/isSuccessful) 由生成表注入 (下方标记处)
    obj.headers = function() { return __nativeDispatch(handle, 1406, []); };
    // @@methods:__createStrResponseObj@@
    return obj;
}

function __createRespObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle;
    // Connection.Response 方法面: 规整方法由生成表注入 (下方标记处);
    // 手写特例 704 url() (URL→String) / 712 method() (枚举 .name)
    obj.url = function() { return __nativeDispatch(handle, 704, []); };
    obj.method = function() { return __nativeDispatch(handle, 712, []); };
    // @@methods:__createRespObj@@
    return obj;
}

function __createJsUrlObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle;
    Object.defineProperty(obj, "host", { get: function() { return __nativeDispatch(handle, 1501, []); } });
    Object.defineProperty(obj, "origin", { get: function() { return __nativeDispatch(handle, 1502, []); } });
    Object.defineProperty(obj, "pathname", { get: function() { return __nativeDispatch(handle, 1503, []); } });
    Object.defineProperty(obj, "searchParams", { get: function() {
        var s = __nativeDispatch(handle, 1504, []);
        return (s === null || s === undefined) ? null : JSON.parse(s);
    } });
    // @@methods:__createJsUrlObj@@
    return obj;
}
    """.trimIndent()
        .let { base ->
            // KSP 生成表闭包按 JS 工厂分区注入: 每个工厂函数体内 `return obj;` 前有唯一标记注释
            // `// @@methods:<factory>@@` (4 空格缩进), 对应 [NativeGeneratedDispatch.JS_METHOD_TABLES]
            // 的同名键替换之 (空表/未生成键的标记原样保留, 是合法 JS 注释)。
            // 生成物是 obj.xxx = function 裸语句, 必须注入工厂函数体内才有局部 obj/handle,
            // 拼在全局作用域会 ReferenceError。先拼接属性桥串再替换: __createBaseSourceObj/
            // __createElementObj 等工厂在 NativeJsPropertyBridge 的串里, 标记同样生效。
            var code = base + "\n" + NativeJsPropertyBridge.JS_PROPERTY_FACTORY_CODE
            for ((factory, table) in NativeGeneratedDispatch.JS_METHOD_TABLES) {
                val marker = "    // @@methods:$factory@@"
                // trim('\n') 只去首尾换行, 保留行内 4 空格缩进 (与标记行对齐)
                if (marker in code) code = code.replace(marker, table.trim('\n'))
            }
            code
        }

    // ============ 工具方法: JSValue ↔ Kotlin 转换 ============

    /** KSP 生成表 (NativeGeneratedDispatch) 的返回值: 纯 Kotlin, 由 [nativeResultToJs] 转 JSValue。 */
    private fun nativeResultToJs(
        ctx: CPointer<JSContext>,
        r: NativeDispatchResult
    ): CValue<JSValue> = when (r) {
        is NativeDispatchResult.Str -> stringToJsValue(ctx, r.v)
        is NativeDispatchResult.Int -> qjs_NewInt32(ctx, r.v)
        is NativeDispatchResult.Long -> qjs_NewFloat64(ctx, r.v.toDouble())
        is NativeDispatchResult.Double -> qjs_NewFloat64(ctx, r.v)
        is NativeDispatchResult.Bool -> qjs_NewBool(ctx, if (r.v) 1 else 0)
        is NativeDispatchResult.Bytes -> r.v?.let { byteArrayToJsArray(ctx, it) } ?: jsNull()
        is NativeDispatchResult.AnyVal -> anyToJs(ctx, r.v)
        // Handle: 0 = null, 非 0 = 已注册对象 handle, JS 层按返回类型工厂包装
        is NativeDispatchResult.Handle -> if (r.v == 0L) jsNull() else qjs_NewFloat64(
            ctx,
            r.v.toDouble()
        )
        NativeDispatchResult.UNIT -> jsUndefined()
        NativeDispatchResult.NONE -> jsUndefined()
    }

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

    /** String → JSValue (qjs_NewString, 直接传 Kotlin String, cinterop 内部转 C 字符串) */
    private fun stringToJsValue(ctx: CPointer<JSContext>, s: String?): CValue<JSValue> {
        if (s == null) return jsNull()
        return qjs_NewString(ctx, s)
    }

    /** JSValue → String (qjs_ToCString + qjs_FreeCString) */
    private fun jsValueToString(ctx: CPointer<JSContext>, v: CValue<JSValue>): String {
        val cstr = qjs_ToCString(ctx, v) ?: return ""
        return try {
            cstr.toKString()
        } finally {
            qjs_FreeCString(ctx, cstr)
        }
    }

    /** JSValue → Kotlin 值 (基本类型 + Array + String; null/undefined → null, boolean → Boolean) */
    private fun fromJsValue(ctx: CPointer<JSContext>, v: CValue<JSValue>): Any? {
        if (qjs_IsNull(v) != 0 || qjs_IsUndefined(v) != 0) {
            return null
        }
        if (qjs_IsBool(v) != 0) {
            return qjs_ValueGetBool(v) != 0
        }
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

    /**
     * java.get/head/post 的 headers 参数: JS 层已 JSON.stringify 成 JSON 文本 (对象或 JSON 字符串), 解析回 Map。
     * 对齐 JVM 端 dispatcher 的 Map 强转 (JS 对象 → Map) 与 JSON 字符串 → Map 两条路径。
     */
    private fun parseHeaderArg(raw: Any?): Map<String, String> {
        val json = raw as? String ?: return emptyMap()
        return GSON.fromJsonObject<Map<String, String>>(json).getOrNull() ?: emptyMap()
    }

    /** List 取可空 String (webView html/url/js 等可空参数) */
    private fun List<Any?>.getStringOrNull(index: Int): String? =
        getOrNull(index) as? String

    /** data 参数: Utf8String 直传, JS Array → ByteArray, 其余 null (queryTTF 等可空数据参数)。 */
    private fun List<Any?>.getDataArgOrNull(index: Int): Any? = when (val v = getOrNull(index)) {
        is String -> v
        is List<*> -> ByteArray(v.size) { (v[it] as? Number)?.toInt()?.toByte() ?: 0 }
        else -> null
    }

    /** 取 data 参数: Utf8String 直传, JS Array → ByteArray, 其余归一为空串 (与 getString 同宽松策略)。 */
    private fun List<Any?>.getDataArg(index: Int): Any = when (val v = getOrNull(index)) {
        is String -> v
        is List<*> -> ByteArray(v.size) { (v[it] as? Number)?.toInt()?.toByte() ?: 0 }
        else -> ""
    }

    /** key/iv 参数转字节: Utf8String → UTF-8 字节, JS Array → ByteArray, null/undefined → null。 */
    private fun Any?.toBytesOrNull(): ByteArray? = when (this) {
        is String -> encodeToByteArray()
        is List<*> -> ByteArray(size) { (this[it] as? Number)?.toInt()?.toByte() ?: 0 }
        else -> null
    }

    // ============ 规则引擎核心方法 (AnalyzeRuleCore / AnalyzeUrlCore 共用, [X4] 补齐) ============

    /**
     * 摘要/HMAC 默认实现解析: 实现 JsEncodeUtilsDefaults 的对象直接用;
     * AnalyzeRuleCore/AnalyzeUrlCore (规则路径 java binding) 不实现该接口, JVM 端由
     * JsExtensionsJvm 多继承注入, native 端用同源默认实现补全 (逻辑复用, 非重写)。
     */
    private fun encodeDefaultsOf(obj: Any): JsEncodeUtilsDefaults? = when (obj) {
        is JsEncodeUtilsDefaults -> obj
        is AnalyzeRuleCore, is AnalyzeUrlCore -> NativeRuleEncodeDefaults
        else -> null
    }

    /** nativeMain [JsEncodeUtilsDefaults] 默认实现实例 (MD5Utils + NativeDigestOps/NativeHmacOps)。 */
    private object NativeRuleEncodeDefaults : JsEncodeUtilsDefaults

    private fun Any.corePut(key: String, value: String): String = when (this) {
        is AnalyzeRuleCore -> put(key, value)
        is AnalyzeUrlCore -> put(key, value)
        else -> value
    }

    private fun Any.coreGet(key: String): String = when (this) {
        is AnalyzeRuleCore -> get(key)
        is AnalyzeUrlCore -> get(key)
        else -> ""
    }

    private fun Any.coreEvalJS(jsStr: String): Any? = when (this) {
        is AnalyzeRuleCore -> evalJS(jsStr)
        is AnalyzeUrlCore -> evalJS(jsStr)
        else -> null
    }

    /**
     * 任意 Kotlin 值 → JSValue (evalJS 返回值用):
     * 基本类型直转; Map/List 递归构造 JS 对象/数组 (JS_SetProperty 转移所有权, 不额外 free);
     * 其余对象 toString 降级 (与 JVM 反射包装的差异, 复杂对象不可桥接)。
     */
    private fun anyToJs(ctx: CPointer<JSContext>, value: Any?): CValue<JSValue> = when (value) {
        null -> jsNull()
        is String -> stringToJsValue(ctx, value)
        is Boolean -> qjs_NewBool(ctx, if (value) 1 else 0)
        is Number -> {
            val d = value.toDouble()
            if (d.isNaN() || d.isInfinite()) jsNull() else qjs_NewFloat64(ctx, d)
        }

        is Map<*, *> -> {
            val obj = JS_NewObject(ctx)
            for ((k, v) in value) {
                val key = k?.toString() ?: continue
                JS_SetPropertyStr(ctx, obj, key, anyToJs(ctx, v))
            }
            obj
        }

        is List<*> -> {
            val arr = JS_NewArray(ctx)
            value.forEachIndexed { i, v ->
                JS_SetPropertyUint32(ctx, arr, i.toUInt(), anyToJs(ctx, v))
            }
            arr
        }

        else -> stringToJsValue(ctx, value.toString())
    }
}

