package com.script.jsdispatch.generated

/**
 * KSP 生成表 (NativeGeneratedDispatch) 与手写桥之间的纯 Kotlin 返回值协议。
 *
 * 生成物为纯 Kotlin (无 cinterop 依赖), 不能在 nativeMain 引用 JSValue;
 * 返回值统一包装为本密封类, 由 NativeJsExtensionsBridge.nativeResultToJs 转 JSValue
 * (与手写分支同层转换语义: Str→qjs_NewString, Int→qjs_NewInt32, Bytes→JS Array 等)。
 *
 * [NONE] 表示"本表未处理" (methodId 不在生成表), 桥落手写分支。
 * [UNIT] 表示方法已调用且无返回值 (对应 Kotlin Unit 返回)。
 * [Handle] 表示返回需要 JS 层对象包装的对象 (StrResponse/JsURL/QueryTTF/BaseSource 等),
 * 由 JS 工厂函数按返回类型映射包装 (生成器产出 JS_METHOD_TABLE 时确定工厂函数)。
 */
sealed class NativeDispatchResult {
    data class Str(val v: String?) : NativeDispatchResult()
    data class Int(val v: Int) : NativeDispatchResult()
    data class Long(val v: Long) : NativeDispatchResult()
    data class Double(val v: Double) : NativeDispatchResult()
    data class Bool(val v: Boolean) : NativeDispatchResult()
    data class Bytes(val v: ByteArray?) : NativeDispatchResult()
    data class AnyVal(val v: Any?) : NativeDispatchResult()

    /** 对象返回: 已注册 handle (Long), 0 = null。JS 层按映射工厂包装。 */
    data class Handle(val v: Long) : NativeDispatchResult()

    object UNIT : NativeDispatchResult()
    object NONE : NativeDispatchResult()
}
