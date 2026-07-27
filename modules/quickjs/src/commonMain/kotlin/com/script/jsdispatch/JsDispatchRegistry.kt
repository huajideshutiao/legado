package com.script.jsdispatch

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 静态分派表注册中心。
 *
 * 生成代码的注册入口是固定 FQN 的 [GENERATED_TABLES_CLASS]:
 * 首次 [forClass] 时 Class.forName 触发其 <clinit> 完成注册 (一次性引导,
 * 之后零反射)。宿主无生成代码时 (如纯单元测试) 引导静默失败, 表为空,
 * 一切走反射 fallback。
 *
 * K/N 平台无 Class.forName, 届时由平台入口显式调用 [register];
 * 本文件的 Class/forName/ConcurrentHashMap 是 JVM 查找入口, 生成表本身平台无关。
 *
 * 注: 原属 `:modules:js-dispatch` 模块的 jvmAndAndroidMain 源集, KMP 结构重构
 * (合并 js-dispatch 到 quickjs) 后移入 quickjs.commonMain。quickjs 模块当前
 * 仅配置 androidTarget + jvm target (无 iOS/ohos), commonMain 实际为 JVM-only,
 * 故可直接使用 java.util.concurrent / Class.forName 等 JVM API。后续若为 quickjs
 * 新增 K/N target, 需把本文件拆为 expect/actual (commonMain 声明 expect object,
 * jvmAndAndroidMain 实现 actual object)。
 */
object JsDispatchRegistry {

    /** call/getProperty 的 miss 哨兵 (null 是合法返回值, 不能用 null 区分)。 */
    @JvmField
    val NO_MATCH: Any = Any()

    const val GENERATED_TABLES_CLASS = "com.script.jsdispatch.generated.JsDispatchTables"

    private val dispatchers = CopyOnWriteArrayList<JsApiDispatcher>()

    /** Class -> dispatcher 解析缓存 (含 miss, 用 NONE 哨兵)。 */
    private val byClass = ConcurrentHashMap<Class<*>, Any>()
    private val NONE = Any()

    @Volatile
    private var bootstrapped = false

    /** 测试开关: false 时 [forClass] 恒 null → 纯反射路径 (表开/关行为等价性测试用)。 */
    @Volatile
    private var enabled = true

    fun register(dispatcher: JsApiDispatcher) {
        dispatchers.add(dispatcher)
        byClass.clear()
    }

    /**
     * 查找覆盖 [clazz] 的分派表 (targetClass 可为其父类/接口, 多个命中取最具体)。
     * 无命中返回 null (调用方走反射)。本方法为 JVM 入口 (生成表本身平台无关)。
     */
    fun forClass(clazz: Class<*>): JsApiDispatcher? {
        if (!enabled) return null
        if (!bootstrapped) bootstrap()
        val cached = byClass[clazz]
        if (cached != null) {
            return if (cached === NONE) null else cached as JsApiDispatcher
        }
        var best: JsApiDispatcher? = null
        for (d in dispatchers) {
            if (d.targetClass.java.isAssignableFrom(clazz)) {
                if (best == null || best.targetClass.java.isAssignableFrom(d.targetClass.java)) {
                    best = d
                }
            }
        }
        // 容量控制: 10000+ 书源场景防 Class 条目无限膨胀 (对齐 JavaObjectBridge.checkCapacity)
        if (byClass.size > 2048) byClass.clear()
        byClass.putIfAbsent(clazz, best ?: NONE)
        return best
    }

    @Synchronized
    private fun bootstrap() {
        if (bootstrapped) return
        try {
            // 触发生成类 <clinit> -> init 块内 register(...)
            Class.forName(GENERATED_TABLES_CLASS)
        } catch (_: ClassNotFoundException) {
            // 宿主未接入 KSP 生成 (单元测试等), 纯反射路径
        } catch (_: LinkageError) {
            // 生成类初始化失败不应拖垮引擎, 降级反射
        }
        bootstrapped = true
    }

    /** 仅测试用: 关闭查表 → 纯反射路径 (已注册的表保留, [enableForTest] 恢复)。 */
    fun disableForTest() {
        enabled = false
    }

    /** 仅测试用: 恢复查表。 */
    fun enableForTest() {
        enabled = true
        byClass.clear()
    }
}
