package io.legado.app.utils

/**
 * ChineseUtils actual (iOS/鸿蒙, nativeMain 中间源集共用)。
 *
 * 详见 commonMain/utils/ChineseUtils.kt expect 注释。
 *
 * iOS/鸿蒙平台均无 quick-chinese-transfer (JVM-only, 依赖 Java 反射) 支持,
 * 委托 commonMain 的 [ChineseSimplifiedConverter] 提供基础的单字级繁简转换能力。
 * 两端 actual 实现完全一致, 下沉到 nativeMain 共用。
 *
 * 功能降级说明:
 * - 仅支持单字级转换, 不支持词组上下文消歧 (如 "头发" vs "发现")
 * - 不支持地区变体 (t2hk/t2tw 退化为标准繁简转换)
 * - unLoad/loadDict/fixT2sDict/pathProvider 为 no-op (字典已内嵌, 无缓存机制)
 *
 * 调用方 (BookDisplayBridge.native.kt / JsExtensionsPlatform.native.kt 等) 行为不变。
 */
actual object ChineseUtils {

    actual fun s2t(content: String): String = ChineseSimplifiedConverter.s2t(content)

    actual fun t2s(content: String): String = ChineseSimplifiedConverter.t2s(content)

    actual fun unLoad(vararg transType: TransType) {
        // no-op: 字典已内嵌, 无动态加载/卸载机制
    }

    actual fun loadDict(transType: TransType) {
        // no-op: 字典已内嵌, 无动态加载机制
    }

    actual fun fixT2sDict() {
        // no-op: 字典已内嵌, 无排除列表修正机制
    }

    actual var pathProvider: Any? = null
}
