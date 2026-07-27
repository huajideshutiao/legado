package io.legado.app.utils

/**
 * 繁简转换 KMP 抽象面 (commonMain expect)。
 *
 * - jvmAndAndroidMain: 委托 quick-transfer 库 (ChineseUtils 主体保留, 反射 + 字典缓存)
 * - iosMain/ohosMain: 委托 commonMain 的 ChineseSimplifiedConverter (内嵌字典, 字符级转换)
 *
 * TransType 下沉到 commonMain, 让 ChineseUtils 公开 API (unLoad/loadDict) 可在 common 引用,
 * 同时让 app 端 (ReadStyleDialog/ChineseUtilsUi) 不再直接依赖 quick-transfer 库的 TransType。
 *
 * pathProvider 类型为 Any?, 各平台自行决定具体类型:
 * - jvmAndAndroidMain: 实际存 TcDictCachePathProvider? (返回 java.io.File), 见同文件 actual
 * - iosMain/ohosMain: 忽略 (无字典缓存机制, 字典已内嵌)
 */
expect object ChineseUtils {
    /** 简转繁 */
    fun s2t(content: String): String
    /** 繁转简 */
    fun t2s(content: String): String
    /** 卸载已加载词典 (iOS/鸿蒙无操作) */
    fun unLoad(vararg transType: TransType)
    /** 加载词典 (iOS/鸿蒙无操作, 字典已内嵌) */
    fun loadDict(transType: TransType)
    /** 修正 t2s 词典排除列表 (iOS/鸿蒙无操作) */
    fun fixT2sDict()
    /** 词典缓存路径提供者 (iOS/鸿蒙无操作; jvmAndAndroid 存 TcDictCachePathProvider?) */
    var pathProvider: Any?
}

/**
 * 繁简转换类型枚举 (commonMain)。
 *
 * 与 quick-transfer 的 com.github.liuyueyi.quick.transfer.constants.TransType 部分对应,
 * jvmAndAndroidMain actual 实现内部做映射 (TransType.toQuick())。
 *
 * 注: quick-transfer 还支持 SIMPLE_TO_HONGKONG/SIMPLE_TO_TAIWAN/HONGKONG_TO_SIMPLE/TAIWAN_TO_SIMPLE,
 * 但 app 端 (ChineseUtilsUi/ReadStyleDialog) 仅使用简繁互转, 故本枚举只保留两项,
 * 避免枚举值与 quick-transfer 实际不支持的项目 (如 TRADITIONAL_TO_HONGKONG) 产生映射错误。
 *
 * jvmAndAndroidMain 的 loadDict 内部 map key 用 quick-transfer TransType.type (s2t/t2s),
 * 不用本枚举的 name, 以保持 quick-transfer 反射逻辑零改动。
 */
enum class TransType {
    SIMPLE_TO_TRADITIONAL,
    TRADITIONAL_TO_SIMPLE;

    val type: String get() = name
}
