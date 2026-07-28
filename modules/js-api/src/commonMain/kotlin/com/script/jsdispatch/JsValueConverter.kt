package com.script.jsdispatch

/**
 * JS 值转换器协议。
 *
 * 业务模块 (shared) 持有具体 JSON 库 (kotlinx.serialization) 的类型知识,
 * JS 引擎 (quickjs) 只认识本协议, 避免引擎层对业务依赖的反向耦合。
 *
 * 引擎在 bindings 注入与 Java 方法返回两条入 JS 路径统一调用 [convertAll],
 * 非匹配类型返回原值 (引擎按既有规则处理), 匹配类型由 converter 转为 JS 原生值。
 */
interface JsValueConverter {
    /**
     * 把宿主对象转为 JS 原生值。
     *
     * @return 转换后的值 (String/Number/Boolean/null/List/Map),
     * 或原值 (表示本 converter 不处理该类型)。
     */
    fun convert(value: Any): Any?
}
