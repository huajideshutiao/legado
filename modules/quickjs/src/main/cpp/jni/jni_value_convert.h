#ifndef JNI_VALUE_CONVERT_H
#define JNI_VALUE_CONVERT_H

#include <quickjs.h>
#include <jni.h>
#include <string>

/**
 * JSValue <-> Java 值转换。
 *
 * 设计原则: Opaque 句柄模式,不递归复制 JS 对象。
 * - JSValue -> jobject: 基本类型(Boolean/Integer/Double/String)直接转,
 *   对象/数组类型返回句柄包装 (Java 侧用 JsValue 包装)。
 * - jobject -> JSValue: 基本类型直接转,Java 对象走 JavaObjectClass.wrap。
 *
 * 这避免了 quickjs-kt 的"全量递归复制"性能问题。
 */
class JniValueConvert {
public:
    /**
     * JSValue 转 jobject (供 JNI 返回给 Java)。
     *
     * 类型映射:
     *   null/undefined -> null
     *   bool -> java.lang.Boolean
     *   int32 -> java.lang.Integer
     *   float64 -> java.lang.Double
     *   string -> java.lang.String
     *   object/array -> java.lang.Long (句柄,Java 侧用 JsValue 包装)
     *   exception -> 抛出 JSException
     *
     * 注意: 调用方负责 JS_FreeValue(传入的 value)。
     */
    static jobject toJavaObject(JSContext *ctx, JNIEnv *env, JSValue value);

    /**
     * jobject 转 JSValue (供 Java 值传入 JS)。
     *
     * 类型映射:
     *   null -> JS_NULL
     *   java.lang.Boolean -> JS_NewBool
     *   java.lang.Integer -> JS_NewInt32
     *   java.lang.Double -> JS_NewFloat64
     *   java.lang.String -> JS_NewString
     *   其他 Java 对象 -> JavaObjectClass.wrap (走 exotic trap)
     *
     * 返回的 JSValue 需调用方 FreeValue。
     */
    static JSValue fromJavaObject(JSContext *ctx, JNIEnv *env, jobject javaObj);

    /**
     * 获取 JSValue 的类型标签 (供 Java 侧 getTypeTag)。
     *
     * 返回值:
     *   0 = null
     *   1 = undefined
     *   2 = boolean
     *   3 = int32
     *   4 = float64
     *   5 = string
     *   6 = object
     *   7 = array
     *   8 = function
     *   9 = exception
     *   10 = JavaObject (自定义类)
     */
    static int getTypeTag(JSContext *ctx, JSValueConst value);

    /**
     * 构建 JS 异常的完整错误消息 (含行号/堆栈)。
     *
     * - 调用 JS_ToCString 获取 message (如 "TypeError: xxx")
     * - 若 exc 是 Error 对象 (JS_IsError), 附加 stack 属性 (含 `at line:col` 位置信息)
     *
     * 用于 [toJavaObject] 异常分支和 nativeCompile 编译失败分支,
     * 让用户能直接看到出错行号, 而非只有 "TypeError: xxx"。
     *
     * @param ctx JSContext
     * @param exc JS_GetException 返回的异常对象 (调用方负责 FreeValue)
     * @return 完整错误消息字符串 (含 message + stack)
     */
    static std::string buildExceptionMessage(JSContext *ctx, JSValue exc);
};

// 导出 Long 类缓存供其他模块复用 (避免重复 FindClass)
extern jclass g_LongCls;
extern jmethodID g_LongValueOf;

#endif // JNI_VALUE_CONVERT_H
