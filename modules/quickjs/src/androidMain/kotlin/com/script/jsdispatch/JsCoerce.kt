package com.script.jsdispatch

import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

/**
 * 生成代码用的参数 coercion / 重载评分辅助, 语义逐条对齐 JavaObjectBridge 反射路径:
 *
 * - 数值: coerceArgs 对 null+primitive 传 0, 非 Number 抛裸 ClassCastException
 * - Boolean/Char: null 或不可转形态对齐 invoke 的 "argument type mismatch" (IAE)
 * - String: coerceValue 兜底 value.toString()
 * - ByteArray/Array<String>: coerceToArray 的 List/数组 -> 目标数组转换;
 *   静态表只接手 *Strict 校验过的干净形态, 脏形态 (元素含句柄 Map/null 等) 由
 *   调用点 precheck 返回 NO_MATCH 落反射, 保证选择与异常形态逐位一致
 * - Map: JSON 对象字符串 -> LinkedHashMap (书源 header 惯例)
 * - score*: 重载 specificity 评分 (对齐 paramSpecificityScore, 越低越优先)
 * - [invokeTarget]: 方法体异常包 InvocationTargetException, 对齐 Method.invoke;
 *   coercion 异常在包装外抛出 (对齐 coerceArgs 先于 invoke 执行)
 *
 * 跨平台注: 除 [invokeTarget] 的 ITE 与 [jsonStringToMap] 的 org.json 外均为纯 Kotlin;
 * 进 commonMain 时这两处换 expect/actual, 语义不变。
 */
object JsCoerce {

    @JvmStatic
    fun toInt(v: Any?): Int = if (v == null) 0 else (v as Number).toInt()

    @JvmStatic
    fun toLong(v: Any?): Long = if (v == null) 0L else (v as Number).toLong()

    @JvmStatic
    fun toShort(v: Any?): Short = if (v == null) 0 else (v as Number).toShort()

    @JvmStatic
    fun toByte(v: Any?): Byte = if (v == null) 0 else (v as Number).toByte()

    @JvmStatic
    fun toFloat(v: Any?): Float = if (v == null) 0f else (v as Number).toFloat()

    @JvmStatic
    fun toDouble(v: Any?): Double = if (v == null) 0.0 else (v as Number).toDouble()

    // 可空包装数值: 对齐 coerceValue 的 boxed 分支 ((value as Number).toXxx(),
    // 非 Number 抛裸 CCE)。选择阶段的"boxed 只认精确包装类"由 guard 表达, 与 coercion 解耦。

    @JvmStatic
    fun toIntOrNull(v: Any?): Int? = if (v == null) null else (v as Number).toInt()

    @JvmStatic
    fun toLongOrNull(v: Any?): Long? = if (v == null) null else (v as Number).toLong()

    @JvmStatic
    fun toShortOrNull(v: Any?): Short? = if (v == null) null else (v as Number).toShort()

    @JvmStatic
    fun toByteOrNull(v: Any?): Byte? = if (v == null) null else (v as Number).toByte()

    @JvmStatic
    fun toFloatOrNull(v: Any?): Float? = if (v == null) null else (v as Number).toFloat()

    @JvmStatic
    fun toDoubleOrNull(v: Any?): Double? = if (v == null) null else (v as Number).toDouble()

    @JvmStatic
    fun toBooleanArg(v: Any?): Boolean = when (v) {
        // 反射路径: coerceArgs 对 null+primitive 传 Integer 0, invoke 抛 IllegalArgumentException
        null -> throw IllegalArgumentException("argument type mismatch")
        else -> v as Boolean
    }

    @JvmStatic
    fun toBooleanOrNull(v: Any?): Boolean? = when (v) {
        // 反射路径: coerceValue 无 boxed Boolean 分支, 原值透传 → invoke 抛 IAE
        null -> null
        is Boolean -> v
        else -> throw IllegalArgumentException("argument type mismatch")
    }

    @JvmStatic
    fun toCharArg(v: Any?): Char = when (v) {
        null -> throw IllegalArgumentException("argument type mismatch")
        else -> v.toString().firstOrNull() ?: ' '
    }

    @JvmStatic
    fun toCharOrNull(v: Any?): Char? = when (v) {
        // 反射路径: coerceValue 无 boxed Character 分支, 原值透传 → invoke 抛 IAE
        null -> null
        is Char -> v
        else -> throw IllegalArgumentException("argument type mismatch")
    }

    /**
     * 引用/List 类型参数: 兼容形态透传, 其余抛 IAE。
     * 对齐反射路径: coerceValue 对不认识的引用类型原值透传, invoke 抛 argument type mismatch。
     * [ok] 由生成代码以 `v is X` 传入 (null 恒放行, coerceArgs 对引用类型 null 透传)。
     */
    @JvmStatic
    fun refArg(v: Any?, ok: Boolean): Any? =
        if (v == null || ok) v else throw IllegalArgumentException("argument type mismatch")

    // ============ 数组参数: guard(broad) / precheck(strict) / coercion ============

    /** 重载 guard: 数组参数可参与匹配的形态 (对齐 isArgsCompatible 的 isArrayCoercible, 宽面)。 */
    @JvmStatic
    fun isArrayLike(v: Any?): Boolean =
        v == null || v is ByteArray || v is List<*> || v is Array<*> ||
            v is IntArray || v is LongArray || v is ShortArray || v is CharArray ||
            v is FloatArray || v is DoubleArray || v is BooleanArray

    /**
     * ByteArray 参数静态可保真形态 (严面): 元素全 Number 的 List/Array 或数值 prim 数组。
     * 宽面兼容但严面不过 (null/句柄 Map/Char 元素等) → 调用点 NO_MATCH 落反射,
     * 由反射复刻 unwrap/逐元素异常语义。
     */
    @JvmStatic
    fun isByteArrayStrict(v: Any?): Boolean = when (v) {
        null, is ByteArray -> true
        is List<*> -> v.all { it is Number }
        is Array<*> -> v.all { it is Number }
        is IntArray, is LongArray, is ShortArray, is FloatArray, is DoubleArray -> true
        else -> false
    }

    /** Array<String> 参数静态可保真形态 (严面): 元素无句柄 Map (反射会 unwrap, 静态不模拟)。 */
    @JvmStatic
    fun isStringArrayStrict(v: Any?): Boolean = when (v) {
        null -> true
        is List<*> -> v.none { it is Map<*, *> }
        is Array<*> -> v.none { it is Map<*, *> }
        else -> isArrayLike(v)
    }

    /**
     * -> ByteArray (对齐 coerceToArray, 元素 (it as Number).toByte())。
     * 仅接手严面形态; 非数组形态抛 IAE (对齐 coerceValue 透传后 invoke 抛 IAE)。
     */
    @JvmStatic
    fun toByteArrayArg(v: Any?): ByteArray? = when (v) {
        null -> null
        is ByteArray -> v
        is List<*> -> ByteArray(v.size) { i -> (v[i] as Number).toByte() }
        is Array<*> -> ByteArray(v.size) { i -> (v[i] as Number).toByte() }
        is IntArray -> ByteArray(v.size) { i -> v[i].toByte() }
        is LongArray -> ByteArray(v.size) { i -> v[i].toByte() }
        is ShortArray -> ByteArray(v.size) { i -> v[i].toByte() }
        is FloatArray -> ByteArray(v.size) { i -> v[i].toInt().toByte() }
        is DoubleArray -> ByteArray(v.size) { i -> v[i].toInt().toByte() }
        else -> throw IllegalArgumentException("argument type mismatch")
    }

    /** -> Array<String?> (对齐 coerceToArray, 元素 toString; null 元素保持 null)。 */
    @JvmStatic
    fun toStringArrayArg(v: Any?): Array<String?>? = when (v) {
        null -> null
        is List<*> -> Array(v.size) { i -> v[i]?.toString() }
        is Array<*> -> Array(v.size) { i -> v[i]?.toString() }
        is IntArray -> Array<String?>(v.size) { i -> v[i].toString() }
        is LongArray -> Array<String?>(v.size) { i -> v[i].toString() }
        is ShortArray -> Array<String?>(v.size) { i -> v[i].toString() }
        is CharArray -> Array<String?>(v.size) { i -> v[i].toString() }
        is FloatArray -> Array<String?>(v.size) { i -> v[i].toString() }
        is DoubleArray -> Array<String?>(v.size) { i -> v[i].toString() }
        is BooleanArray -> Array<String?>(v.size) { i -> v[i].toString() }
        is ByteArray -> Array<String?>(v.size) { i -> v[i].toString() }
        else -> throw IllegalArgumentException("argument type mismatch")
    }

    /**
     * Map 参数: JSON 对象字符串 -> LinkedHashMap (对齐 coerceValue 的 jsonStringToMap 分支,
     * 书源惯例 java.post(url, body, '{"Referer":"x"}'))。Map 透传; 其余抛 IAE (对齐 invoke)。
     */
    @JvmStatic
    fun toMapArg(v: Any?): Any? = when {
        v == null -> null
        v is Map<*, *> -> v
        v is String -> jsonStringToMap(v)
            ?: throw IllegalArgumentException("argument type mismatch")

        else -> throw IllegalArgumentException("argument type mismatch")
    }

    /** JSON 对象字符串 -> LinkedHashMap; 非 JSON 对象 (含数组/纯量) 返回 null。 */
    @JvmStatic
    fun jsonStringToMap(value: String): Map<String, String>? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val json = JSONObject(trimmed)
            val map = LinkedHashMap<String, String>(json.length())
            json.keys().forEach { key -> map[key] = json.get(key).toString() }
            map
        } catch (_: JSONException) {
            null
        }
    }

    /** 方法体异常包 InvocationTargetException, 对齐 Method.invoke 语义。 */
    inline fun invokeTarget(block: () -> Any?): Any? {
        try {
            return block()
        } catch (t: Throwable) {
            throw InvocationTargetException(t)
        }
    }

    // ============ 重载 specificity 评分 (对齐 paramSpecificityScore, 越低越优先) ============
    // 只在 guard (isArgsCompatible 镜像) 命中后调用, 因此各函数只需覆盖兼容形态的取值分布;
    // guard 下取值恒定的类别 (String/Boolean/Char/boxed 数值/prim 化简) 由生成代码内联常量。

    @JvmStatic
    fun isIntegerNumber(n: Number): Boolean = when (n) {
        is Int, is Long, is Short, is Byte -> true
        is Double -> n == n.toInt().toDouble()
        is Float -> n == n.toInt().toFloat()
        else -> false
    }

    /** primitive int/long 参数 (guard: is Number): 精确 0 / 整数值 2 / 小数值 4。 */
    @JvmStatic
    fun scoreIntPrim(v: Any?): Int = when {
        v is Int -> 0
        v is Number -> if (isIntegerNumber(v)) 2 else 4
        else -> 5
    }

    @JvmStatic
    fun scoreLongPrim(v: Any?): Int = when {
        v is Long -> 0
        v is Number -> if (isIntegerNumber(v)) 2 else 4
        else -> 5
    }

    @JvmStatic
    fun scoreShortPrim(v: Any?): Int = if (v is Short) 0 else 5

    @JvmStatic
    fun scoreBytePrim(v: Any?): Int = if (v is Byte) 0 else 5

    /** primitive float/double 参数 (guard: is Number): 精确 0 / 小数值 2 / 整数值 3。 */
    @JvmStatic
    fun scoreFloatPrim(v: Any?): Int = when {
        v is Float -> 0
        v is Number -> if (isIntegerNumber(v)) 3 else 2
        else -> 5
    }

    @JvmStatic
    fun scoreDoublePrim(v: Any?): Int = when {
        v is Double -> 0
        v is Number -> if (isIntegerNumber(v)) 3 else 2
        else -> 5
    }

    /** 引用类型参数: null 0 / 精确类 0 / 可赋值 1 (KClass API 全平台可用)。 */
    @JvmStatic
    fun scoreRef(v: Any?, k: kotlin.reflect.KClass<*>): Int = when {
        v == null -> 0
        v::class == k -> 0
        k.isInstance(v) -> 1
        else -> 5
    }

    /** ByteArray 参数 (guard: isArrayLike): 精确 0 / List/其他数组可 coerce 3。 */
    @JvmStatic
    fun scoreByteArray(v: Any?): Int = when {
        v == null -> 0
        v is ByteArray -> 0
        else -> 3
    }

    /** Array<String> 参数 (guard: isArrayLike)。 */
    @JvmStatic
    fun scoreStringArray(v: Any?): Int = when {
        v == null -> 0
        v is Array<*> && v.isArrayOf<String>() -> 0
        else -> 3
    }
}
