package io.legado.app.help.file

import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.exception.SecurityException
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.utils.KS_JSON
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.IOException
import okio.Path.Companion.toPath

/**
 * 鸿蒙目录授权表。
 *
 * Picker select 返回的 URI 只有临时权限 (重启/退后台失效), 选目录时 ArkTS 侧已
 * persistPermission 持久化 (读写模式 —— 备份/导出/书籍保存/图片保存这些入口都要往选定
 * 目录里写); 本表按官方要求在应用侧持久化 URI↔路径映射,
 * 文件访问入口经 [ensureActivatedFor] 按需 activatePermission 激活 —— 持久化授权存系统
 * 数据库, 重启后须激活才能访问, 官方要求按需激活不要全量激活。
 *
 * 每条记录带**用途** ([DirUsage], 即用户经哪个入口选它): 激活时按用途匹配, 只有同用途的记录
 * 才能放行该访问 —— 为备份或字体选的宽目录不会放行书籍文件访问。同一目录在不同用途下各留一条
 * (既是书籍目录又是导出目录时两边都可用)。
 *
 * 已登记目录的访问只在激活成功后放行; 激活失败抛 [SecurityException] (带 errCode/policyCode),
 * 由调用链处理, 不降级成"文件不存在"。未登记路径 (沙盒内/手机降级路径) 原行为直通。
 */
object OhosDirAuthorizations {

    /** 授权集合的 pref key (值: JSON Map<用途, Map<授权URI, 沙箱路径>>)。 */
    private const val KEY_DIR_POLICIES = "ohosDirAuthPolicies"

    private val lock = SynchronizedObject()

    /** 授权集合缓存 (用途 → uri → 沙箱路径), 与 pref 同步写回。 */
    private var cachedPolicies: Map<DirUsage, Map<String, String>>? = null

    /** 记录刚持久化成功的目录授权 (同一用途内同路径重复选择替换旧条目, 其余条目保留)。 */
    fun remember(uri: String, path: String, usage: DirUsage) {
        synchronized(lock) {
            val normalizedPath = path.toPath(normalize = true).toString()
            val current = storedPolicies().toMutableMap()
            val forUsage = current[usage].orEmpty()
                .filterValues { it != normalizedPath }
                .toMutableMap()
            forUsage[uri] = normalizedPath
            current[usage] = forUsage
            cachedPolicies = current
            PreferenceProviders.get().putString(KEY_DIR_POLICIES, KS_JSON.encodeToString(current))
        }
    }

    /**
     * 访问 [path] 前确保 [usage] 用途下所属授权目录已激活: 未登记路径直通; 已登记目录激活成功才返回,
     * 失败抛 [SecurityException]。
     */
    fun ensureActivatedFor(path: String, usage: DirUsage) {
        synchronized(lock) {
            val uri = matchingUri(path.toPath(normalize = true).toString(), usage) ?: return
            activateOrThrow(uri)
        }
    }

    /** 锁内调用：访问前激活目标目录，不把进程内旧激活结果当作当前授权。 */
    private fun activateOrThrow(uri: String) {
        val result = activateDirectoryPermissions(listOf(uri))
        if (result.ok && uri in result.activatedUris) return
        val detail = result.policyErrors.firstOrNull { it.uri == uri }
        val policyCode = detail?.code ?: 0
        val message = "目录授权激活失败 (errCode=${result.code} policyCode=$policyCode)" +
            (detail?.message ?: result.message)?.let { " $it" }.orEmpty()
        AppLog.put("$message uri=$uri")
        if (result.code == 201 || result.code == 801 || detail != null) {
            throw SecurityException(message)
        }
        throw IOException(message)
    }

    /** 锁内调用: [path] 在 [usage] 用途下所属授权 uri (路径段前缀匹配), 未登记返回 null。 */
    private fun matchingUri(path: String, usage: DirUsage): String? =
        storedPolicies()[usage].orEmpty().entries.firstOrNull { (_, dirPath) ->
            val root = dirPath.trimEnd('/')
            path == root || path.startsWith("$root/")
        }?.key

    /** 锁内调用: 授权集合 (volatile 缓存优先, 首次解析 pref)。 */
    private fun storedPolicies(): Map<DirUsage, Map<String, String>> {
        cachedPolicies?.let { return it }
        val raw = PreferenceProviders.get().getString(KEY_DIR_POLICIES, "")
        val parsed = if (raw.isEmpty()) {
            emptyMap()
        } else {
            runCatching { KS_JSON.decodeFromString<Map<DirUsage, Map<String, String>>>(raw) }.getOrElse {
                AppLog.put("目录授权集合解析失败\n${it.message}", it)
                emptyMap()
            }
        }
        cachedPolicies = parsed
        return parsed
    }
}
