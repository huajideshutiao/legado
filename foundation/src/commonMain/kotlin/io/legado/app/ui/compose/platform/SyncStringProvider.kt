package io.legado.app.ui.compose.platform

import kotlin.concurrent.Volatile

/**
 * 同步(非挂起/非 Composable)取 composeResources 字符串的注册式通道。
 *
 * 原实现直接依赖 compose 资源生成器 (legado.ui.generated.resources), 属 :ui 层;
 * 但 data/core 的 native 桥 (JS 作用域报错文案 / 文件访问器) 也需要同步取本地化串。
 * 故把查询入口下沉本文件 (foundation), 采用「provider 注册」而非 expect/actual:
 * - :ui 启动时 registerSyncStringProvider 注册 compose 资源实现 (见 ComposeResourceLookup.kt)
 * - 未注册时 fallback 返回 key 名, 运行期安全不崩 (与 AppStringProvider 同一套路)
 */
fun interface SyncStringProvider {
    fun get(key: String, vararg formatArgs: Any?): String
}

@Volatile
private var syncStringProvider: SyncStringProvider? = null

/** 注册同步字符串 provider (:ui 宿主启动早期调用)。 */
fun registerSyncStringProvider(provider: SyncStringProvider) {
    syncStringProvider = provider
}

/**
 * 同步取 composeResources 字符串 (key 缺失返回 key 名, 与 rememberString 兜底一致;
 * 占位符只认索引式 %1$s/%1$d)。
 */
fun syncGetString(key: String, vararg formatArgs: Any?): String =
    syncStringProvider?.get(key, *formatArgs) ?: key
