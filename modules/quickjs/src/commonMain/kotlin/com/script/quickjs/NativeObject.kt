package com.script.quickjs

/**
 * QuickJS JS Object 在 Kotlin 侧的标记类型,对齐 rhino 的 NativeObject。
 *
 * 由 native 层 `JniValueConvert::toJavaObject` 把 plain JS object (非 array/function)
 * 包装为本类实例,业务代码可用 `is NativeObject` 区分 JS 返回的对象与其他来源的 Map
 * (如 JsonPath 返回的普通 LinkedHashMap)。
 *
 * 继承 LinkedHashMap 保持 Map 兼容性,业务代码可直接当 Map 使用 (get/put/entries 等)。
 *
 * 注意: 与 rhino 的 `org.mozilla.javascript.NativeObject` 同名但不同包,
 * 仅作为类型标记,不实现 rhino 的 Scriptable 接口。
 */
class NativeObject : LinkedHashMap<String, Any?> {

    constructor() : super()

    constructor(initialCapacity: Int) : super(initialCapacity)

    constructor(initialCapacity: Int, loadFactor: Float) : super(initialCapacity, loadFactor)
}
