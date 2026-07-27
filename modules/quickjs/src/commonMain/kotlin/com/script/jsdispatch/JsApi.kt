package com.script.jsdispatch

/**
 * 标注 JS 面类型(JsExtensions/CookieStore/CacheManager/image 别名对象等)。
 *
 * KSP processor (modules/js-dispatch/processor) 在编译期为其公开方法/属性生成
 * 静态分派表 [JsApiDispatcher], JS 桥调用时先查表、miss 再落反射。
 * K/N 无反射平台的根本解法, JVM 平台顺带消除反射分派开销。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class JsApi
