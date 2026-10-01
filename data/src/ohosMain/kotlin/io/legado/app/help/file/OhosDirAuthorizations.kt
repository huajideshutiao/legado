package io.legado.app.help.file

import io.legado.app.constant.AppLog
import io.legado.app.exception.SecurityException
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.encodeStringMap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.decodeFromString
import okio.IOException
import okio.Path.Companion.toPath

/**
 * 鸿蒙目录授权表。
 *
 * Picker select 返回的 URI 只有临时只读权限 (重启/退后台失效), 选目录时 ArkTS 侧已
 * persistPermission 持久化 (READ_MODE); 本表按官方要求在应用侧持久化 URI↔路径映射,
 * 文件访问入口经 [ensureActivatedFor] 按需 activatePermission 激活 —— 持久化授权存系统
 * 数据库, 重启后须激活才能访问, 官方要求按需激活不要全量激活。
 *
 * 已登记目录的访问只在激活成功后放行; 激活失败抛 [SecurityException] (带 errCode/policyCode),
 * 由调用链处理, 不降级成"文件不存在"。未登记路径 (沙盒内/手机降级路径) 原行为直通。
 */
object OhosDirAuthorizations {

    /** 授权集合的 pref key (值: JSON Map<授权URI, 沙箱路径>)。 */
    private const val KEY_DIR_POLICIES = "ohosDirAuthPolicies"

    private val lock = SynchronizedObject()

    /** 授权集合缓存 (uri → 沙箱路径), 与 pref 同步写回。 */
    private var cachedPolicies: Map<String, String>? = null

    /** 记录刚持久化成功的目录授权 (同 uri/同路径重复选择替换旧条目, 其余目录保留)。 */
    fun remember(uri: String, path: String) {
        synchronized(lock) {
            val normalizedPath = path.toPath(normalize = true).toString()
            val current = storedPolicies().filterTo(mutableMapOf()) { (_, p) -> p != normalizedPath }
            current[uri] = normalizedPath
            cachedPolicies = current
            PreferenceProviders.get().putString(KEY_DIR_POLICIES, encodeStringMap(current))
        }
    }

    /**
     * 访问 [path] 前确保所属授权目录已激活: 未登记路径直通; 已登记目录激活成功才返回,
     * 失败抛 [SecurityException]。
     */
    fun ensureActivatedFor(path: String) {
        synchronized(lock) {
            val uri = matchingUri(path.toPath(normalize = true).toString()) ?: return
            activateOrThrow(uri)
        }
    }

    /** 锁内调用：访问前激活目标目录，不把进程内旧激活结果当作当前授权。 */
    private fun activateOrThrow(uri: String) {
        val result = activateDirectoryPermissions(listOf(uri))
        if (result.ok && uri in result.activatedUris) return
        val detail = result.policyErrors.firstOrNull { it.uri == uri }
        val policyCode = detail?.code ?: 0
        val message = "目录授权激活失败 (errCode=${result.code} policyCode=$policyCode)"
            + (detail?.message ?: result.message)?.let { " $it" }.orEmpty()
        AppLog.put("$message uri=$uri")
        if (result.code == 201 || result.code == 801 || detail != null) {
            throw SecurityException(message)
        }
        throw IOException(message)
    }

    /** 锁内调用: [path] 所属授权 uri (路径段前缀匹配), 未登记返回 null。 */
    private fun matchingUri(path: String): String? =
        storedPolicies().entries.firstOrNull { (_, dirPath) ->
            val root = dirPath.trimEnd('/')
            path == root || path.startsWith("$root/")
        }?.key

    /** 锁内调用: 授权集合 (volatile 缓存优先, 首次解析 pref)。 */
    private fun storedPolicies(): Map<String, String> {
        cachedPolicies?.let { return it }
        val raw = PreferenceProviders.get().getString(KEY_DIR_POLICIES, "")
        val parsed = if (raw.isEmpty()) {
            emptyMap()
        } else {
            runCatching { KS_JSON.decodeFromString<Map<String, String>>(raw) }.getOrElse {
                AppLog.put("目录授权集合解析失败\n${it.message}", it)
                emptyMap()
            }
        }
        cachedPolicies = parsed
        return parsed
    }
}
