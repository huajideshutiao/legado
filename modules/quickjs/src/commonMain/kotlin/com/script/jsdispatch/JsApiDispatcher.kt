package com.script.jsdispatch

import kotlin.reflect.KClass

/**
 * 单个 [JsApi] 类型的编译期静态分派表, 由 KSP processor 生成实现。
 *
 * 接口与生成实现均为平台无关纯 Kotlin (KClass, 无 java.lang.Class/反射),
 * 未来可整体进 KMP commonMain; JVM 专属的引导/查找逻辑收敛在 [JsDispatchRegistry]。
 *
 * 语义对齐 JavaObjectBridge 反射路径:
 * - [call]: 方法调用 (重载按参数个数+isArgsCompatible guard+specificity 评分分派,
 *   参数 coercion 与 coerceArgs 一致, 方法体抛出的异常包装为
 *   InvocationTargetException 对齐 Method.invoke)
 * - [getProperty]/[setProperty]: Kotlin 公开属性读写 (对齐 findGetter/findSetter 路径)
 * - [hasMethod]: 方法名存在性 (供属性 trap 的 METHOD_MARKER 快速判定)
 *
 * miss 协议: [call]/[getProperty] 返回 [JsDispatchRegistry.NO_MATCH],
 * [setProperty] 返回 false, 调用方落反射 fallback, 行为零变化。
 */
interface JsApiDispatcher {

    /** 分派目标类型 (标注 @JsApi 的类/接口)。 */
    val targetClass: KClass<*>

    /** 表内是否存在该 JVM 方法名 (含属性访问器名)。 */
    fun hasMethod(name: String): Boolean

    /** 调用方法。返回原始 Java 对象; miss 返回 [JsDispatchRegistry.NO_MATCH]。 */
    fun call(target: Any, name: String, args: Array<Any?>): Any?

    /** 读属性。miss 返回 [JsDispatchRegistry.NO_MATCH] (null 是合法属性值)。 */
    fun getProperty(target: Any, name: String): Any?

    /** 写属性。true 表示已处理, false 表示表中无此属性 (落反射)。 */
    fun setProperty(target: Any, name: String, value: Any?): Boolean
}
