package io.legado.app.utils

import platform.Foundation.NSString
import platform.Foundation.stringByApplyingTransform

/**
 * ChineseUtils 的 iOS actual: 系统原生繁简转换 (CFStringTransform,
 * 经 NSString.stringByApplyingTransform 调用, transform 标识即常量字符串本身)。
 *
 * 原 nativeMain 共用的单字映射实现已按平台拆分:
 * - iOS (本文件): CFStringTransform 标准繁简表, 覆盖远大于 ChineseSimplifiedConverter (~500 字),
 *   转换内置于系统, 无词典加载/卸载
 * - ohosMain: 鸿蒙无 CFStringTransform 等价公开 API, 保持 commonMain
 *   [ChineseSimplifiedConverter] 单字映射 (见 ChineseUtils.ohos.kt)
 *
 * 与 jvmAndAndroidMain (quick-transfer 词组+单字) 的行为差异:
 * - 词组上下文消歧能力有限 (如 简→繁 "头发/发现" 的 发 仍可能统一映射为 發, 与 quick-transfer 有差异)
 * - 不做地区变体 (t2hk/t2tw) 与 T2S_EXCLUDE_LIST
 * - 转换失败 (stringByApplyingTransform 返回 null) 时原样返回输入
 */
actual object ChineseUtils {

    actual fun s2t(content: String): String {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val transformed = (content as NSString)
            .stringByApplyingTransform(
                "kCFStringTransformSimplifiedToTraditional",
                reverse = false
            )
        return transformed as String? ?: content
    }

    actual fun t2s(content: String): String {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val transformed = (content as NSString)
            .stringByApplyingTransform(
                "kCFStringTransformTraditionalToSimplified",
                reverse = false
            )
        return transformed as String? ?: content
    }

    actual fun unLoad(vararg transType: TransType) {
        // no-op: 转换由系统 CFStringTransform 内置, 无动态词典加载/卸载
    }

    actual fun loadDict(transType: TransType) {
        // no-op
    }

    actual fun fixT2sDict() {
        // no-op
    }

    actual var pathProvider: Any? = null
}
