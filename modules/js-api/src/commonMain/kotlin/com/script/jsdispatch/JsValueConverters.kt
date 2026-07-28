package com.script.jsdispatch

import com.script.jsdispatch.JsValueConverters.convertAll
import com.script.jsdispatch.JsValueConverters.register
import kotlin.concurrent.Volatile

/**
 * [JsValueConverter] 全局注册表。
 *
 * quickjs 引擎在入 JS 边界统一调用 [convertAll]; 业务模块 (shared) 在启动期
 * 通过 [register] 挂接具体类型转换器 (如 kotlinx.serialization JsonElement)。
 *
 * 注册顺序即优先级, 先注册先匹配。引擎零依赖业务类型。
 */
object JsValueConverters {

    @Volatile
    private var converters: List<JsValueConverter> = emptyList()

    /** 注册一个转换器。重复注册同一实例是幂等的。 */
    fun register(converter: JsValueConverter) {
        if (converters.none { it === converter }) {
            // 宿主启动期注册, copy-on-write 让执行期读取无需锁。
            converters = converters + converter
        }
    }

    /** 清空全部转换器 (测试用)。 */
    fun clear() {
        converters = emptyList()
    }

    /**
     * 顺序尝试所有转换器。
     *
     * @return 首个改变值 (返回 !== 原值) 的转换结果; 均不处理则返回原值。
     */
    fun convertAll(value: Any?): Any? {
        if (value == null) return null
        for (converter in converters) {
            val converted = converter.convert(value)
            if (converted !== value) return converted
        }
        return value
    }
}
