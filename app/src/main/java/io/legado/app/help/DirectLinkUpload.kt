package io.legado.app.help

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.DirectLinkUpload.getConfig
import io.legado.app.help.DirectLinkUpload.putConfig
import io.legado.app.help.DirectLinkUpload.upLoad
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson

/**
 * 直链上传 Android 实现 (与 shared [DirectLinkUploadShared] 配套)。
 *
 * # 下沉说明
 * [DirectLinkUploadRule] data class / [ruleFileName] 常量 / 配置访问接口与 [upLoad] 编排
 * 已下沉 shared (commonMain [upLoadToDirectLink])。本 object 保留 Android 专属实现:
 * - [upLoad]: 薄壳注入 JVM 平台原语 ([DirectLinkUploadJvmPrimitives])
 * - [getConfig] / [putConfig]: 依赖 ACache (Android 文件缓存)
 * - [defaultRules]: 经 [DefaultDataResourceProviders] 读 composeResources files/defaultData/
 *
 * 本 object 实现 [DirectLinkUploadStoreProvider] + [DirectLinkUploadDefaultsProvider],
 * 宿主启动早期通过 `registerAndroidDirectLinkUploadProviders()` 注册到 shared,
 * 供 shared 端 (如 BackupShared/RestoreShared) 跨平台访问。
 *
 * # 类型映射
 * 原 `DirectLinkUpload.Rule` → shared [DirectLinkUploadRule] (同包, 直接引用)
 * 原 `DirectLinkUpload.ruleFileName` → shared [ruleFileName] (同包 top-level 常量)
 *
 * # @Keep 注解移除
 * 原 Rule 上的 @Keep (androidx.annotation) 随 Rule 下沉移除,
 * 反射保活由 shared consumer-rules.pro 的 keep 规则覆盖。
 */
object DirectLinkUpload : DirectLinkUploadStoreProvider, DirectLinkUploadDefaultsProvider {

    @Throws(NoStackTraceException::class)
    suspend fun upLoad(
        fileName: String,
        file: Any,
        contentType: String,
        rule: DirectLinkUploadRule = getRule()
    ): String {
        return upLoadToDirectLink(
            DirectLinkUploadJvmPrimitives,
            fileName, file, contentType, rule
        )
    }

    // 命名为 defaultRulesCache 避免与 override fun getDefaultRules() 的 JVM 签名 (getDefaultRules()) 冲突
    private val defaultRulesCache: List<DirectLinkUploadRule> by lazy {
        // 走 DefaultDataResourceProviders 单一数据源 (composeResources files/defaultData/)
        val json = DefaultDataResourceProviders.get().readResource("directLinkUpload.json")
        GSON.fromJsonArray<DirectLinkUploadRule>(json).getOrThrow()
    }

    fun getRule(): DirectLinkUploadRule {
        return getConfig() ?: defaultRulesCache[0]
    }

    override fun getConfig(): DirectLinkUploadRule? {
        val json = ACache.get(cacheDir = false).getAsString(ruleFileName)
        return GSON.fromJsonObject<DirectLinkUploadRule>(json).getOrNull()
    }

    override fun putConfig(rule: DirectLinkUploadRule) {
        ACache.get(cacheDir = false).put(ruleFileName, GSON.toJson(rule))
    }

    /** 恢复备份时原样写回 JSON, 与原 Restore 的 ACache.put(ruleFileName, json) 一致。 */
    override fun putConfigJson(json: String) {
        ACache.get(cacheDir = false).put(ruleFileName, json)
    }

    override fun getDefaultRules(): List<DirectLinkUploadRule> = defaultRulesCache

    fun getSummary(): String {
        return getRule().summary
    }

}

/**
 * 安卓宿主启动早期注册 [DirectLinkUploadStoreProvider] + [DirectLinkUploadDefaultsProvider]。
 *
 * [DirectLinkUpload] object 已实现这两个接口, 注册后 shared/commonMain 内可通过
 * [DirectLinkUploadStoreProviders.get] / [DirectLinkUploadDefaultsProviders.get]
 * 跨平台访问直链上传配置 (如 BackupShared/RestoreShared 备份恢复 directLinkUploadRule.json)。
 *
 * 调用时机: App.onCreate, 在任何 shared 模块备份/恢复之前。
 *
 * 模式参考 [io.legado.app.help.storage.registerAndroidPasswordProvider] /
 * [io.legado.app.help.ExploreKindsCacheProviders] (JsEnginesAndroid.kt 注册先例)。
 */
fun registerAndroidDirectLinkUploadProviders() {
    DirectLinkUploadStoreProviders.register(DirectLinkUpload)
    DirectLinkUploadDefaultsProviders.register(DirectLinkUpload)
}
