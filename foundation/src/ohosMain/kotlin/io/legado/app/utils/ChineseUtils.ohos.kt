package io.legado.app.utils

/**
 * ChineseUtils 的鸿蒙 (OHOS) actual。
 *
 * 平台 API 核验结论: 鸿蒙公开 SDK 无 CFStringTransform 等价的原生繁简转换 API
 * (@ohos.i18n 仅提供地区/日历/排序等能力, 无汉字繁简转换; napi 桥也无对应系统接口),
 * 故保持 commonMain [ChineseSimplifiedConverter] 的单字级繁简转换:
 * - 仅单字映射, 无词组上下文消歧 (如 "头发" vs "发现" 的 发 简→繁统一映射为 發)
 * - 不支持地区变体 (t2hk/t2tw 退化为标准繁简转换)
 * - unLoad/loadDict/fixT2sDict/pathProvider 为 no-op (字典已内嵌, 无缓存机制)
 *
 * iOS 端 (iosMain) 用系统 CFStringTransform 原生转换, 覆盖面更广, 见 ChineseUtils.ios.kt。
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
