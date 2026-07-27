package io.legado.app.help

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.toJson
import kotlin.io.File

/**
 * nativeMain: 直链上传配置的 iOS/鸿蒙共用实现 (对照 desktop DesktopDirectLinkUpload)。
 * 配置落 `{AppFilesDirs.filesDir}/{ruleFileName}` 单文件; 默认规则经
 * [DefaultDataResourceProviders] 读 composeResources files/defaultData/directLinkUpload.json。
 */
object NativeDirectLinkUpload : DirectLinkUploadStoreProvider, DirectLinkUploadDefaultsProvider {

    private val configPath: String
        get() = "${AppFilesDirs.get().filesDir}/$ruleFileName"

    // 命名 defaultRulesCache 避免与 override getDefaultRules() 签名冲突 (与 desktop/app 一致)
    private val defaultRulesCache: List<DirectLinkUploadRule> by lazy {
        val json = DefaultDataResourceProviders.get().readResource("directLinkUpload.json")
        GSON.fromJsonArray<DirectLinkUploadRule>(json).getOrThrow()
    }

    override fun getConfig(): DirectLinkUploadRule? {
        val file = File(configPath)
        if (!file.exists()) return null
        return parseDirectLinkUploadRule(file.readBytes().decodeToString())
    }

    override fun putConfig(rule: DirectLinkUploadRule) {
        val file = File(configPath)
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
        file.writeText(GSON.toJson(rule), Charsets.UTF_8)
    }

    override fun getDefaultRules(): List<DirectLinkUploadRule> = defaultRulesCache
}

/**
 * 注册 [DirectLinkUploadStoreProviders] + [DirectLinkUploadDefaultsProviders]
 * (iOS/鸿蒙共用, 在 AppFilesDirs + DefaultDataResourceProvider 之后)。
 * 未注册时备份/恢复静默跳过 directLinkUploadRule.json。
 */
fun registerNativeDirectLinkUploadProviders() {
    DirectLinkUploadStoreProviders.register(NativeDirectLinkUpload)
    DirectLinkUploadDefaultsProviders.register(NativeDirectLinkUpload)
}
