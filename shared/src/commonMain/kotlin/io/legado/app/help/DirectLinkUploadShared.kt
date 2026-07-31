package io.legado.app.help

import io.legado.app.help.DirectLinkUploadStoreProviders.get
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * 直链上传规则的 KMP 共享版 (与 [RestoreShared] / [BackupShared] 配套)。
 *
 * # 背景
 * app 端 [io.legado.app.help.DirectLinkUpload] 单例依赖大量 Android-specific 类
 * (ACache 磁盘缓存 / appCtx.assets 读取默认规则 / ZipUtils+FileUtils 压缩 /
 * java.io.File 类型判断 / appCtx.externalCache 临时目录 / AnalyzeUrl app 端子类),
 * 不能直接下沉 shared/commonMain。本 KMP 版抽掉 Android 专属依赖, 仅保留:
 * - [DirectLinkUploadRule] 纯数据类 (GSON 序列化, 与 app 端字段完全一致)
 * - [ruleFileName] 常量 (BackupShared/RestoreShared 备份恢复时引用)
 * - 配置读写 + 默认规则的 Provider 接口 (宿主注入实现)
 * - [getRuleShared] / [getSummaryShared] 跨平台访问点
 *
 * # 与 app 端 [io.legado.app.help.DirectLinkUpload] 的差异
 * - **不下沉 upLoad()**: 强依赖 java.io.File / ZipUtils / FileUtils / appCtx.externalCache
 *   / AnalyzeUrl(app 端 JsExtensions 子类), 保留 app 端原实现
 * - **不下沉 defaultRules 直接读取**: 依赖 appCtx.assets (Android assets), 通过
 *   [DirectLinkUploadDefaultsProvider] 注入
 * - **不下沉 getConfig/putConfig 的 ACache 访问**: ACache 为 Android 文件缓存, 通过
 *   [DirectLinkUploadStoreProvider] 注入
 * - **@Keep 注解移除**: commonMain 无 androidx.annotation, 反射保活由 consumer-rules.pro
 *   的 `-keep class io.legado.app.help.DirectLinkUploadRule { *; }` 覆盖
 *   (照 OldRssSource / AnalyzeByXPath 先例)
 *
 * # 命名后缀 Shared
 * 与 app 端 [io.legado.app.help.DirectLinkUpload] 同包同名会冲突, 故 Rule 类提到顶层
 * 命名 [DirectLinkUploadRule] (app 端用 typealias `DirectLinkUpload.Rule` 保持兼容)。
 *
 * # 模式参考
 * - app 端 [io.legado.app.help.DirectLinkUpload] (业务对照原型)
 * - shared/commonMain [io.legado.app.help.config.PasswordProviders] (Provider 注入先例)
 * - shared/commonMain [io.legado.app.help.ExploreKindsCacheProviders] (ACache 抽象先例)
 */

/**
 * 直链上传规则数据结构。
 *
 * 与 app 端 `DirectLinkUpload.Rule` 字段完全一致 (uploadUrl/downloadUrlRule/summary/compress),
 * GSON 序列化兼容 (kotlinx.serialization @Serializable, 字段名/类型/默认值零变化)。
 *
 * @Keep 注解移除: commonMain 无 androidx.annotation, 由 consumer-rules.pro keep 覆盖。
 */
@Serializable
data class DirectLinkUploadRule(
    var uploadUrl: String, //创建分享链接
    var downloadUrlRule: String, //下载链接规则
    var summary: String, //注释
    var compress: Boolean = false, //是否压缩
) {

    override fun toString(): String {
        return summary
    }
}

/**
 * 直链上传规则文件名常量 (与 app 端 [io.legado.app.help.DirectLinkUpload.ruleFileName] 一致)。
 *
 * BackupShared/RestoreShared 备份恢复时引用本常量, 避免硬编码字符串。
 */
const val ruleFileName = "directLinkUploadRule.json"

/**
 * 直链上传配置 (读写) Provider 接口。
 *
 * app 端 [io.legado.app.help.DirectLinkUpload] 的 getConfig/putConfig 依赖 ACache
 * (Android 文件缓存, 无法下沉 shared)。宿主启动早期通过 [DirectLinkUploadStoreProviders.register]
 * 注入实现, shared 内通过 [DirectLinkUploadStoreProviders.get] 获取。
 *
 * 模式参考 [io.legado.app.help.ExploreKindsCacheProvider] (ACache 抽象先例)。
 */
interface DirectLinkUploadStoreProvider {

    /** 读取已保存的规则, 无配置返回 null (与 app 端 `DirectLinkUpload.getConfig()` 行为一致)。 */
    fun getConfig(): DirectLinkUploadRule?

    /** 保存规则 (与 app 端 `DirectLinkUpload.putConfig(rule)` 行为一致)。 */
    fun putConfig(rule: DirectLinkUploadRule)

    /**
     * 保存规则的原始 JSON (恢复备份用)。
     *
     * 默认先解析再 [putConfig]; app 端覆写为原样写入, 与原 `ACache.put(ruleFileName, json)` 逐字一致。
     */
    fun putConfigJson(json: String) {
        parseDirectLinkUploadRule(json)?.let { putConfig(it) }
    }
}

/**
 * [DirectLinkUploadStoreProvider] 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `DirectLinkUploadStoreProviders.get()?.getConfig()` 替代
 * 直接 ACache 调用, 行为完全一致, 仅多一层 provider 间接。
 * 未注册时 [get] 返回 null (等价 app 端 getConfig() 为 null 时跳过的行为)。
 */
object DirectLinkUploadStoreProviders {

    @Volatile
    private var impl: DirectLinkUploadStoreProvider? = null

    /** 宿主启动早期注册一次 (任何 shared 调用之前)。 */
    fun register(impl: DirectLinkUploadStoreProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null (等价 app 端无配置时的 null 行为)。 */
    fun get(): DirectLinkUploadStoreProvider? = impl
}

/**
 * 直链上传默认规则 Provider 接口。
 *
 * app 端 [io.legado.app.help.DirectLinkUpload] 的 defaultRules 依赖 appCtx.assets
 * (Android assets, 无法下沉 shared)。宿主启动早期通过
 * [DirectLinkUploadDefaultsProviders.register] 注入实现。
 *
 * 模式参考 [io.legado.app.help.config.PasswordProvider] (assets/Context 抽象先例)。
 */
interface DirectLinkUploadDefaultsProvider {

    /** 返回内置默认规则列表 (与 app 端 `DirectLinkUpload.defaultRules` 行为一致)。 */
    fun getDefaultRules(): List<DirectLinkUploadRule>
}

/**
 * [DirectLinkUploadDefaultsProvider] 容器。宿主启动早期注册一次。
 */
object DirectLinkUploadDefaultsProviders {

    @Volatile
    private var impl: DirectLinkUploadDefaultsProvider? = null

    /** 宿主启动早期注册一次 (任何 shared 调用之前)。 */
    fun register(impl: DirectLinkUploadDefaultsProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null。 */
    fun get(): DirectLinkUploadDefaultsProvider? = impl
}

/**
 * 获取当前生效的直链上传规则 (跨平台访问点)。
 *
 * 与 app 端 [io.legado.app.help.DirectLinkUpload.getRule] 同语义:
 * - 优先返回已保存配置 [DirectLinkUploadStoreProvider.getConfig]
 * - 无配置时返回默认规则列表的第一条 [DirectLinkUploadDefaultsProvider.getDefaultRules]
 * - 均不可用时抛 [IllegalStateException]
 *
 * @throws IllegalStateException StoreProvider 和 DefaultsProvider 均未注册, 或默认规则列表为空
 */
fun getRuleShared(): DirectLinkUploadRule {
    DirectLinkUploadStoreProviders.get()?.getConfig()?.let { return it }
    val defaults = DirectLinkUploadDefaultsProviders.get()?.getDefaultRules()
        ?: error("DirectLinkUploadDefaultsProvider not registered")
    if (defaults.isEmpty()) error("default rules is empty")
    return defaults[0]
}

/**
 * 获取当前规则的摘要文本 (跨平台访问点)。
 *
 * 与 app 端 [io.legado.app.help.DirectLinkUpload.getSummary] 同语义:
 * `getSummary() = getRule().summary`。
 */
fun getSummaryShared(): String = getRuleShared().summary

/**
 * 把规则序列化为 JSON 字符串 (跨平台工具, 复用 shared GSON 兼容层)。
 *
 * 与 app 端 `GSON.toJson(rule)` 行为一致, 供备份/剪贴板复制等场景用。
 */
fun DirectLinkUploadRule.toJsonString(): String = GSON.toJson(this)

/**
 * 从 JSON 字符串反序列化为规则 (跨平台工具, 复用 shared GSON 兼容层)。
 *
 * 与 app 端 `GSON.fromJsonObject<Rule>(json)` 行为一致。
 * 解析失败返回 null (与 app 端 getOrNull() 用法对齐)。
 */
fun parseDirectLinkUploadRule(json: String?): DirectLinkUploadRule? =
    GSON.fromJsonObject<DirectLinkUploadRule>(json).getOrNull()
