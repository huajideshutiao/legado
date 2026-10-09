package io.legado.app.utils

import io.legado.app.constant.AppLog
import platform.Foundation.NSString
import platform.Foundation.stringByApplyingTransform

/**
 * ChineseUtils 的 iOS actual: 系统原生繁简转换
 * (Foundation `-[NSString stringByApplyingTransform:reverse:]`, 底层即 CoreFoundation 的
 * CFStringTransform 转写)。
 *
 * # 变换标识必须是 ICU 变换 ID (原实现传的是常量名, 无效)
 * 该 API 的 transform 取「CoreFoundation 预定义常量」或「任意合法 ICU 变换 ID」
 * (Apple: "you can also use any valid ICU transform ID defined in the ICU User Guide")。
 * CFStringTransform 的预定义常量只有 16 个 (StripCombiningMarks / ToLatin / FullwidthHalfwidth /
 * Latin* / MandarinLatin / ToXMLHex / ToUnicodeName / StripDiacritics), **其中没有简繁互转**;
 * `kCFStringTransform*` 是变量**名**, 其**值**才是 ICU 变换 ID —— 所以不能把常量名当字符串传。
 * 简繁互转的 ICU 变换 ID 是 `Hans-Hant` (简→繁) 与 `Hant-Hans` (繁→简)
 * (ICU 数据中另有别名 Simplified-Traditional / Traditional-Simplified)。
 *
 * 原实现传 `"kCFStringTransformSimplifiedToTraditional"` /
 * `"kCFStringTransformTraditionalToSimplified"`: 该常量在 Apple 头文件里并不存在, 也不构成合法
 * ICU ID; 而无效标识符按该 API 的头文件契约返回 nil, 原代码把 nil 兜回原文 —— 于是转换
 * **恒等于不转换** (静默失效)。本版改用上面的 ICU 变换 ID。
 *
 * # 与 jvmAndAndroidMain (quick-transfer 词组+单字) 的行为差异
 * - 无词典: quick-transfer 的词典是 JVM 侧反射装载 + 缓存文件通道, native 侧没有该类与通道,
 *   故本端不做 T2S_EXCLUDE_LIST 与 t2hk/t2tw 地区变体
 * - 系统变换不可用时回落 commonMain [ChineseSimplifiedConverter] 的单字映射表并落日志,
 *   不静默返回原文
 *
 * 调用方 (BookDisplayBridge.native / JsExtensionsPlatform.native 等) 行为不变。
 */
actual object ChineseUtils {

    /** 简→繁的 ICU 变换 ID。 */
    private const val S2T_TRANSFORM = "Hans-Hant"

    /** 繁→简的 ICU 变换 ID。 */
    private const val T2S_TRANSFORM = "Hant-Hans"

    actual fun s2t(content: String): String =
        applySystemTransform(content, S2T_TRANSFORM) { ChineseSimplifiedConverter.s2t(it) }

    actual fun t2s(content: String): String =
        applySystemTransform(content, T2S_TRANSFORM) { ChineseSimplifiedConverter.t2s(it) }

    /**
     * 走系统变换; 系统返回 null (变换不可用 / 系统未带该 ICU 数据) 时回落单字映射表。
     * 回落必须落日志: 静默返回原文会让"转换没生效"变成看不见的错。
     */
    private fun applySystemTransform(
        content: String,
        transform: String,
        fallback: (String) -> String,
    ): String {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val transformed = (content as NSString)
            .stringByApplyingTransform(transform, reverse = false)
        if (transformed != null) return transformed
        AppLog.put("iOS 系统繁简变换不可用 ($transform), 已回落单字映射表")
        return fallback(content)
    }

    actual fun unLoad(vararg transType: TransType) {
        // no-op: 转换由系统变换内置, 无动态词典加载/卸载
    }

    actual fun loadDict(transType: TransType) {
        // no-op
    }

    actual fun fixT2sDict() {
        // no-op
    }

    actual var pathProvider: Any? = null
}
