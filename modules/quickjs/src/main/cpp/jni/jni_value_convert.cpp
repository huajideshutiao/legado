#include "jni_value_convert.h"
#include "jni_handle.h"
#include "jni_object_class.h"
#include <android/log.h>
#include <cstring>
#include <mutex>

#define TAG "legado_qjs"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 缓存 Java 类引用,避免每次 FindClass
// Long 类缓存导出给 jni_bridge.cpp 等模块复用
jclass g_LongCls = nullptr;
jmethodID g_LongValueOf = nullptr;

namespace {
    jclass g_BooleanCls = nullptr;
    jclass g_IntegerCls = nullptr;
    jclass g_DoubleCls = nullptr;
    jclass g_ByteCls = nullptr;
    jclass g_ShortCls = nullptr;
    jclass g_FloatCls = nullptr;
    // String 类缓存 (fromJavaObject 热路径: 几乎每个 Java 方法返回值都过 String 判断)
    // 原先每次都 FindClass("java/lang/String") + DeleteLocalRef, 锁 ClassLoader, 显著开销
    jclass g_StringCls = nullptr;
    // NativeObject (com/script/quickjs/NativeObject) - JS Object 在 Kotlin 侧的标记类型
    // 对齐 rhino NativeObject: 业务代码用 is NativeObject 区分 JS 返回的对象与 JsonPath 返回的 Map
    jclass g_NativeObjectCls = nullptr;
    jmethodID g_NativeObjectInitI = nullptr;   // <init>(I)V - 带初始容量
    jmethodID g_NativeObjectPut = nullptr;     // put(Object,Object)Object - 复用 Map.put
    jmethodID g_BooleanValueOf = nullptr;
    jmethodID g_BooleanValue = nullptr;
    jmethodID g_IntegerValueOf = nullptr;
    jmethodID g_IntegerValue = nullptr;
    jmethodID g_DoubleValueOf = nullptr;
    jmethodID g_DoubleValue = nullptr;
    // 注: g_LongValueOf 提升到文件级全局 (上方), 供 jni_bridge.cpp 复用, 避免重复 FindClass
    jmethodID g_LongValue = nullptr;       // Long.longValue() (fromJavaObject 热路径)
    jmethodID g_ByteValue = nullptr;
    jmethodID g_ShortValue = nullptr;
    jmethodID g_FloatValue = nullptr;
    // 用 std::once_flag 保证类缓存初始化只跑一次, 且初始化期间的所有写对其它
    // 线程 happen-before 可见。原先裸 bool 没有 release barrier, thread A 写完
    // g_inited=true 之前, g_BooleanCls 等赋值可能还在 store buffer, thread B 已
    // 看到 g_inited=true 后用零/部分初始化的全局做 JNI 调用 (NULL methodID),
    // 触发 JNI 内部 abort 或写错位置, 与远处 JSString header 损坏对得上。
    std::once_flag g_initFlag;

    void ensureClassCache(JNIEnv *env) {
        std::call_once(g_initFlag, [env]() {
            // Boolean
            jclass localBool = env->FindClass("java/lang/Boolean");
            g_BooleanCls = (jclass) env->NewGlobalRef(localBool);
            env->DeleteLocalRef(localBool);
            g_BooleanValueOf = env->GetStaticMethodID(g_BooleanCls, "valueOf",
                                                      "(Z)Ljava/lang/Boolean;");
            g_BooleanValue = env->GetMethodID(g_BooleanCls, "booleanValue", "()Z");
            // Integer
            jclass localInt = env->FindClass("java/lang/Integer");
            g_IntegerCls = (jclass) env->NewGlobalRef(localInt);
            env->DeleteLocalRef(localInt);
            g_IntegerValueOf = env->GetStaticMethodID(g_IntegerCls, "valueOf",
                                                      "(I)Ljava/lang/Integer;");
            g_IntegerValue = env->GetMethodID(g_IntegerCls, "intValue", "()I");
            // Double
            jclass localDouble = env->FindClass("java/lang/Double");
            g_DoubleCls = (jclass) env->NewGlobalRef(localDouble);
            env->DeleteLocalRef(localDouble);
            g_DoubleValueOf = env->GetStaticMethodID(g_DoubleCls, "valueOf",
                                                     "(D)Ljava/lang/Double;");
            g_DoubleValue = env->GetMethodID(g_DoubleCls, "doubleValue", "()D");
            // Long (用于句柄包装)
            jclass localLong = env->FindClass("java/lang/Long");
            g_LongCls = (jclass) env->NewGlobalRef(localLong);
            env->DeleteLocalRef(localLong);
            g_LongValueOf = env->GetStaticMethodID(g_LongCls, "valueOf", "(J)Ljava/lang/Long;");
            g_LongValue = env->GetMethodID(g_LongCls, "longValue", "()J");
            // Byte (byte[] 元素访问需要, 否则 Byte 会被包装为 JavaObject 导致位运算失败)
            jclass localByte = env->FindClass("java/lang/Byte");
            g_ByteCls = (jclass) env->NewGlobalRef(localByte);
            env->DeleteLocalRef(localByte);
            g_ByteValue = env->GetMethodID(g_ByteCls, "byteValue", "()B");
            // Short
            jclass localShort = env->FindClass("java/lang/Short");
            g_ShortCls = (jclass) env->NewGlobalRef(localShort);
            env->DeleteLocalRef(localShort);
            g_ShortValue = env->GetMethodID(g_ShortCls, "shortValue", "()S");
            // Float
            jclass localFloat = env->FindClass("java/lang/Float");
            g_FloatCls = (jclass) env->NewGlobalRef(localFloat);
            env->DeleteLocalRef(localFloat);
            g_FloatValue = env->GetMethodID(g_FloatCls, "floatValue", "()F");
            // String (fromJavaObject 热路径; toJavaObject 也复用)
            jclass localStr = env->FindClass("java/lang/String");
            g_StringCls = (jclass) env->NewGlobalRef(localStr);
            env->DeleteLocalRef(localStr);
            // NativeObject (com/script/quickjs/NativeObject)
            jclass localNO = env->FindClass("com/script/quickjs/NativeObject");
            g_NativeObjectCls = (jclass) env->NewGlobalRef(localNO);
            env->DeleteLocalRef(localNO);
            g_NativeObjectInitI = env->GetMethodID(g_NativeObjectCls, "<init>", "(I)V");
            g_NativeObjectPut = env->GetMethodID(g_NativeObjectCls, "put",
                                                 "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        });
    }
}

jobject JniValueConvert::toJavaObject(JSContext *ctx, JNIEnv *env, JSValue value) {
    ensureClassCache(env);

    // 异常: 抛出 JSException,返回 null
    if (JS_IsException(value)) {
        // 获取异常对象, 构建含 stack 的完整错误消息 (含行号位置)
        JSValue exc = JS_GetException(ctx);
        std::string msgStr = buildExceptionMessage(ctx, exc);
        JS_FreeValue(ctx, exc);
        jclass excCls = env->FindClass("com/script/quickjs/JsNativeException");
        if (excCls) {
            env->ThrowNew(excCls, msgStr.c_str());
            env->DeleteLocalRef(excCls);
        }
        return nullptr;
    }

    // null
    if (JS_IsNull(value)) {
        return nullptr;
    }
    // undefined -> null (Java 无 undefined)
    if (JS_IsUndefined(value)) {
        return nullptr;
    }
    // bool
    if (JS_IsBool(value)) {
        int b = JS_ToBool(ctx, value);
        return env->CallStaticObjectMethod(g_BooleanCls, g_BooleanValueOf,
                                           b != 0 ? JNI_TRUE : JNI_FALSE);
    }
    // int32 (quickjs-ng 无 JS_IsInt32, 用 tag 判断)
    if (JS_VALUE_GET_TAG(value) == JS_TAG_INT) {
        int32_t v;
        JS_ToInt32(ctx, &v, value);
        return env->CallStaticObjectMethod(g_IntegerCls, g_IntegerValueOf, v);
    }
    // float64 (JS_TAG_IS_FLOAT64 宏兼容 NAN_BOXING 和非 NAN_BOXING)
    if (JS_TAG_IS_FLOAT64(JS_VALUE_GET_TAG(value))) {
        double v;
        JS_ToFloat64(ctx, &v, value);
        return env->CallStaticObjectMethod(g_DoubleCls, g_DoubleValueOf, v);
    }
    // string
    if (JS_IsString(value)) {
        const char *str = JS_ToCString(ctx, value);
        jstring jstr = env->NewStringUTF(str ? str : "");
        JS_FreeCString(ctx, str);
        return jstr;
    }
    // JavaObject (自定义类实例) -> 解包返回原始 jobject
    if (JavaObjectClass::isInstance(ctx, value)) {
        jobject obj = JavaObjectClass::getJavaObject(ctx, value);
        // 返回 local ref,调用方负责释放
        return env->NewLocalRef(obj);
    }
    // JS array -> ArrayList (递归转换, 对齐 rhino NativeArray -> List 行为)
    // 必须在 JS_IsObject 之前判断, 否则 array 会被当成 plain object
    if (JS_IsArray(value)) {
        int64_t len64 = 0;
        JS_GetLength(ctx, value, &len64);
        jclass arrayListCls = env->FindClass("java/util/ArrayList");
        jobject list = env->NewObject(arrayListCls,
                                      env->GetMethodID(arrayListCls, "<init>", "(I)V"),
                                      (jint) len64);
        if (!list) {
            env->DeleteLocalRef(arrayListCls);
            return nullptr;
        }
        jmethodID addMethod = env->GetMethodID(arrayListCls, "add", "(Ljava/lang/Object;)Z");
        for (int64_t i = 0; i < len64; i++) {
            JSValue elem = JS_GetPropertyUint32(ctx, value, (uint32_t) i);
            jobject elemObj = toJavaObject(ctx, env, elem);
            JS_FreeValue(ctx, elem);
            // 递归 toJavaObject 抛 JsNativeException 后必须立刻退出, 否则后续
            // CallBooleanMethod / DeleteLocalRef 都属于 "pending exception 下的 JNI 调用",
            // 会污染 JNI 状态, 表现为远处堆腐败 (JSString header.kind 被覆盖)
            if (env->ExceptionCheck()) {
                if (elemObj) env->DeleteLocalRef(elemObj);
                env->DeleteLocalRef(arrayListCls);
                env->DeleteLocalRef(list);
                return nullptr;
            }
            // 注意: null 元素也要 add (List 允许 null), 不能跳过
            env->CallBooleanMethod(list, addMethod, elemObj);
            if (elemObj) env->DeleteLocalRef(elemObj);
        }
        env->DeleteLocalRef(arrayListCls);
        return list;
    }
    // JS Error 对象 -> NativeObject (对齐 rhino NativeError, 不转 Throwable)
    //
    // 设计: JS Error 作为返回值/参数时, 转成 NativeObject (含 message/name/stack),
    // 对齐 rhino NativeError (ScriptableObject, 不是 Throwable)。Throwable 只在
    // JS throw 时由 JS_IsException 分支 (上方行 112-123) 产生 (抛 ScriptException)。
    //
    // 为什么不转 ScriptException (Throwable):
    // 1. rhino 从不把 JS Error 对象转 Throwable。eval("new Error()") 返回 NativeError,
    //    只有 throw new Error() 才会包装成 JavaScriptException 抛出。
    // 2. 转 ScriptException 后 return (不 throw), 会让 ScriptException 作为返回值
    //    继续往后走, 导致 AnalyzeRule getElements 的 `it as List<Any>` ClassCastException。
    // 3. catch(e) { AppLog.put(e, e, false) } 在 rhino 下第二参数 Throwable 也会失败
    //    (NativeJavaObject.coerceTypeImpl 报 EvaluatorException), 不需要 QuickJS 越权转换。
    //
    // 为什么手动塞 message/name/stack (不复用下方 plain object 分支枚举):
    // JS Error 的 message/name/stack 是 non-enumerable, 下方 JS_GPN_ENUM_ONLY 拿不到,
    // 会塞进空 NativeObject, 业务拿不到 message。这里手动获取这 3 个标准属性塞入。
    // enumerable 自有属性 (如 e.customField = "abc") 会被忽略, 这是已知取舍 (少见场景)。
    //
    // 必须在 plain object 分支之前, 否则 Error 会被当普通对象塞进空 NativeObject。
    if (JS_IsError(value)) {
        jobject map = env->NewObject(g_NativeObjectCls, g_NativeObjectInitI, (jint) 3);
        if (!map) return nullptr;
        static const char *const kErrKeys[] = {"message", "name", "stack"};
        for (int k = 0; k < 3; k++) {
            JSValue val = JS_GetPropertyStr(ctx, value, kErrKeys[k]);
            jobject valObj = toJavaObject(ctx, env, val);
            JS_FreeValue(ctx, val);
            // 递归 toJavaObject 抛 JsNativeException 时必须立刻退出, 避免 pending
            // exception 下的后续 JNI 调用污染 JNI 状态 (同上方 array 分支的处理)
            if (env->ExceptionCheck()) {
                if (valObj) env->DeleteLocalRef(valObj);
                env->DeleteLocalRef(map);
                return nullptr;
            }
            jstring keyStr = env->NewStringUTF(kErrKeys[k]);
            env->CallObjectMethod(map, g_NativeObjectPut, keyStr, valObj);
            env->DeleteLocalRef(keyStr);
            if (valObj) env->DeleteLocalRef(valObj);
        }
        return map;
    }
    // plain JS object (非 function) -> NativeObject (递归转换)
    // 对齐 rhino NativeObject: 业务代码用 is NativeObject 区分 JS 返回的对象与 JsonPath 返回的 Map
    // NativeObject 继承 LinkedHashMap, 仍可当 Map 用 (get/put/entries 等)
    // JS function 仍走句柄包装 (用于 JsFunctionHandle callback)
    if (JS_IsObject(value) && !JS_IsFunction(ctx, value)) {
        JSPropertyEnum *ptab = nullptr;
        uint32_t plen = 0;
        // JS_GPN_STRING_MASK: 只枚举字符串键 (排除 Symbol)
        // JS_GPN_ENUM_ONLY: 只枚举可枚举属性 (排除 non-enumerable)
        int ret = JS_GetOwnPropertyNames(ctx, &ptab, &plen, value,
                                         JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY);
        if (ret == 0) {
            jobject map = env->NewObject(g_NativeObjectCls, g_NativeObjectInitI, (jint) plen);
            // NewObject 失败时 map 是 NULL 且有 pending JNI 异常, 继续调用任何 JNI
            // 函数都是 UB。先释放 ptab 把 ctx 资源退出, 再让上层看到异常。
            if (!map) {
                for (uint32_t i = 0; i < plen; i++) JS_FreeAtom(ctx, ptab[i].atom);
                js_free(ctx, ptab);
                return nullptr;
            }
            for (uint32_t i = 0; i < plen; i++) {
                const char *key = JS_AtomToCString(ctx, ptab[i].atom);
                JSValue val = JS_GetProperty(ctx, value, ptab[i].atom);
                jobject valObj = toJavaObject(ctx, env, val);
                JS_FreeValue(ctx, val);
                // 递归 toJavaObject 可能抛 JsNativeException (val 是 JS_EXCEPTION 时)。
                // JNI 契约: 有 pending exception 时除 ExceptionClear 等少数函数外都是 UB,
                // 继续 NewStringUTF / CallObjectMethod / DeleteLocalRef 会污染 JNI 状态,
                // 最终堆上其它 JSString header 被随机改写 -> 远处 JS_ToCString -> strv abort。
                if (env->ExceptionCheck()) {
                    JS_FreeCString(ctx, key);
                    JS_FreeAtom(ctx, ptab[i].atom);
                    if (valObj) env->DeleteLocalRef(valObj);
                    // 后续 atom 也要释放, 否则 ctx 持续泄漏 atom
                    for (uint32_t j = i + 1; j < plen; j++) JS_FreeAtom(ctx, ptab[j].atom);
                    js_free(ctx, ptab);
                    env->DeleteLocalRef(map);
                    return nullptr;
                }
                jstring keyStr = env->NewStringUTF(key ? key : "");
                JS_FreeCString(ctx, key);
                env->CallObjectMethod(map, g_NativeObjectPut, keyStr, valObj);
                env->DeleteLocalRef(keyStr);
                if (valObj) env->DeleteLocalRef(valObj);
                JS_FreeAtom(ctx, ptab[i].atom);
            }
            js_free(ctx, ptab);
            return map;
        }
        // GetOwnPropertyNames 失败, 走句柄包装兜底
    }
    // JS function 或其他 object -> 句柄包装 (Java 侧用 JsValue 包装)
    if (JS_IsObject(value)) {
        // DupValue 后存入句柄表,Java 侧拿到 Long 句柄
        JSValue dup = JS_DupValue(ctx, value);
        int64_t handle = JsHandleTable::instance().store(ctx, dup);
        return env->CallStaticObjectMethod(g_LongCls, g_LongValueOf, (jlong) handle);
    }

    // 兜底: 转 string
    const char *str = JS_ToCString(ctx, value);
    jstring jstr = env->NewStringUTF(str ? str : "");
    JS_FreeCString(ctx, str);
    return jstr;
}

JSValue JniValueConvert::fromJavaObject(JSContext *ctx, JNIEnv *env, jobject javaObj) {
    ensureClassCache(env);

    if (javaObj == nullptr) {
        return JS_NULL;
    }

    // 热路径优化: 书源 JS 大量返回 String / Integer / 自定义 Java 对象。
    // 先做 String 判断 (高频, 且字符串拼接/拷贝是常见瓶颈), 再 Integer (循环计数器、
    // size/length 返回), 然后其他基本类型, 最后落到自定义 Java 对象 wrap 分支。
    // String
    if (env->IsInstanceOf(javaObj, g_StringCls)) {
        const char *str = env->GetStringUTFChars((jstring) javaObj, nullptr);
        JSValue val = JS_NewString(ctx, str ? str : "");
        env->ReleaseStringUTFChars((jstring) javaObj, str);
        return val;
    }
    // Integer
    if (env->IsInstanceOf(javaObj, g_IntegerCls)) {
        jint v = env->CallIntMethod(javaObj, g_IntegerValue);
        return JS_NewInt32(ctx, v);
    }
    // Boolean
    if (env->IsInstanceOf(javaObj, g_BooleanCls)) {
        jboolean b = env->CallBooleanMethod(javaObj, g_BooleanValue);
        return JS_NewBool(ctx, b == JNI_TRUE);
    }
    // Double
    if (env->IsInstanceOf(javaObj, g_DoubleCls)) {
        jdouble v = env->CallDoubleMethod(javaObj, g_DoubleValue);
        return JS_NewFloat64(ctx, v);
    }
    // Long -> JS Number (用 float64 避免溢出, Long 句柄可能超过 int32 范围)
    // 重要: binding 返回的 classHandle/objHandle 是 Long, 需要作为 JS Number 传递
    if (env->IsInstanceOf(javaObj, g_LongCls)) {
        jlong v = env->CallLongMethod(javaObj, g_LongValue);
        return JS_NewFloat64(ctx, (double) v);
    }
    // Float
    if (env->IsInstanceOf(javaObj, g_FloatCls)) {
        jfloat v = env->CallFloatMethod(javaObj, g_FloatValue);
        return JS_NewFloat64(ctx, (double) v);
    }
    // Byte (byte[] 元素访问)
    if (env->IsInstanceOf(javaObj, g_ByteCls)) {
        jbyte v = env->CallByteMethod(javaObj, g_ByteValue);
        return JS_NewInt32(ctx, (int32_t) v);
    }
    // Short
    if (env->IsInstanceOf(javaObj, g_ShortCls)) {
        jshort v = env->CallShortMethod(javaObj, g_ShortValue);
        return JS_NewInt32(ctx, (int32_t) v);
    }

    // 其他 Java 对象 -> JavaObjectClass.wrap (走 exotic trap)
    return JavaObjectClass::wrap(ctx, env, javaObj);
}

int JniValueConvert::getTypeTag(JSContext *ctx, JSValueConst value) {
    if (JS_IsException(value)) return 9;
    if (JS_IsNull(value)) return 0;
    if (JS_IsUndefined(value)) return 1;
    if (JS_IsBool(value)) return 2;
    // quickjs-ng 无 JS_IsInt32/JS_IsFloat64, 用 tag 宏判断
    if (JS_VALUE_GET_TAG(value) == JS_TAG_INT) return 3;
    if (JS_TAG_IS_FLOAT64(JS_VALUE_GET_TAG(value))) return 4;
    if (JS_IsString(value)) return 5;
    if (JavaObjectClass::isInstance(ctx, value)) return 10;
    if (JS_IsObject(value)) {
        // 区分 array/function/object
        if (JS_IsFunction(ctx, value)) return 8;
        if (JS_IsArray(value)) return 7;
        return 6;
    }
    return 6; // 兜底按 object 处理
}

std::string JniValueConvert::buildExceptionMessage(JSContext *ctx, JSValue exc) {
    // 1. 获取 message (toString), 如 "TypeError: xxx" / "SyntaxError: ... at line 1 col 6"
    const char *msg = JS_ToCString(ctx, exc);
    std::string msgStr = msg ? msg : "JS Exception";
    JS_FreeCString(ctx, msg);

    // 2. 若是 Error 对象 (含子类 SyntaxError/TypeError 等), 附加 stack 属性
    //    stack 含调用位置信息, 如 "    at foo (<eval>:3)\n    at <eval>:5"
    //    让用户直接看到出错行号, 而非只有错误类型+消息
    if (JS_IsError(exc)) {
        JSValue stack = JS_GetPropertyStr(ctx, exc, "stack");
        if (!JS_IsUndefined(stack) && !JS_IsNull(stack)) {
            const char *stackStr = JS_ToCString(ctx, stack);
            if (stackStr && stackStr[0] != '\0') {
                msgStr += "\n";
                msgStr += stackStr;
            }
            JS_FreeCString(ctx, stackStr);
        }
        JS_FreeValue(ctx, stack);
    }
    return msgStr;
}
