#ifndef JNI_VALUE_CONVERT_H
#define JNI_VALUE_CONVERT_H

#include <quickjs.h>
#include <jni.h>

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
};

#endif // JNI_VALUE_CONVERT_H
