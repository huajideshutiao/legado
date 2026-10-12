package io.legado.app.help.storage

import io.legado.app.data.entities.RuleSub
import kotlin.concurrent.Volatile

/**
 * 规则订阅自动更新的导入环节注入点。
 *
 * # 为什么需要注入
 * "解析 → 按新增/内容有变化判据入库"这段与手动打开订阅是**同一条链**
 * (`Import*ViewModelShared` 的解析与比对)。那条链在 :ui (它依赖 Compose 与 okHttp 包装),
 * 而本机制挂在 :data 的 [BackupShared] 上, :data 不能反向依赖 :ui, 故按本仓既有的
 * `object XxxProviders { register(impl) / get() }` 模式 ([BackupRestoreHooks] /
 * `OkHttpClientProviders` / `ContentProcessorProviders` 同族) 注入实现,
 * 保证全仓只有一份判据与一份解析。
 *
 * # 实现方契约
 * 实现必须**复用** `Import*ViewModelShared` 的 `importSource` / `comparisonSource` /
 * `importSelect`, 不得另写解析或比对。返回值是实际入库条数 (0 表示解析出的条目全部已是最新,
 * 或载荷无有效条目); 载荷无法解析时抛异常, 由调用方记日志并照常推进时间戳。
 *
 * 未注册实现时 [RuleSubAutoUpdate] 记一条日志并跳过本轮 (不静默成功)。
 */
interface RuleSubAutoUpdateHook {

    /**
     * 按订阅类型解析载荷并入库, 返回实际入库条数。
     *
     * @param type 订阅类型 (与 [RuleSub.type] 同值: 0/1 书源, 2 替换规则, 3 目录规则,
     *   4 字典规则, 5 语音源)
     * @param text 待导入载荷 (纯 JSON 文本, 或指向 JSON 的 URL)
     */
    suspend fun importByType(type: Int, text: String): Int
}

/** [RuleSubAutoUpdateHook] 容器 (宿主启动早期注册一次, 须早于任何自动更新触发)。 */
object RuleSubAutoUpdateHooks {

    @Volatile
    private var impl: RuleSubAutoUpdateHook? = null

    fun register(hook: RuleSubAutoUpdateHook) {
        impl = hook
    }

    fun get(): RuleSubAutoUpdateHook? = impl
}
