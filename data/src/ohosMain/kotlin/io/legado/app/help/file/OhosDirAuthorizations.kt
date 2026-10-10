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
import okio.FileSystem
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
 * (既是书籍目录又是导出目录时两边都可用); 同一用途内命中多条 (父目录与子目录各选定过一次) 时
 * 逐条尝试, 已失效的条目跳过、其余可用条目照旧放行。
 *
 * 放行判据: 应用私有目录 ([privateRoots] 的根及其子目录, 本端无外部存储目录) 直通; 私有目录之外的
 * 路径必须在本用途下已登记。未登记抛 [SecurityException]; 已登记目录的访问只在激活成功后放行,
 * 激活失败同样抛 [SecurityException] (带 errCode/policyCode), 由调用链处理, 不降级成"文件不存在"。
 *
 * 路径一律先按 realpath 归一 ([canonicalPath]) 再判归属与匹配: 登记侧存的是归一后的目录, 被访问侧
 * 也传归一后的路径 —— 容器内的符号链接指向容器外时按容器外路径判, 不能凭链接名直通。
 *
 * 设备无目录授权持久化能力 ([NO_PERSIST_CAPABILITY]) 时该登记没有可激活的持久授权, 按该登记放行
 * —— 该设备上选目录时无从持久化授权, 用途匹配照旧生效。
 */
object OhosDirAuthorizations {

    /** 授权集合的 pref key (值: JSON Map<用途, Map<授权URI, 沙箱路径>>)。 */
    private const val KEY_DIR_POLICIES = "ohosDirAuthPolicies"

    /** 设备无目录授权持久化能力 (FolderAuthorization) 的 errCode; 该设备上持久化同样不可用。 */
    private const val NO_PERSIST_CAPABILITY = 801

    private val lock = SynchronizedObject()

    /** 授权集合缓存 (用途 → uri → 沙箱路径), 与 pref 同步写回。 */
    private var cachedPolicies: Map<DirUsage, Map<String, String>>? = null

    /** 登记 [usage] 用途下选定的目录 (同一用途内同路径重复选择替换旧条目, 其余条目保留)。 */
    fun remember(uri: String, path: String, usage: DirUsage) {
        synchronized(lock) {
            val normalizedPath = canonicalPath(path)
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
     * 访问 [path] 前确保 [usage] 用途下所属授权目录可用: 应用私有目录直通; 私有目录之外的路径须在
     * 本用途下已登记, 未登记或激活失败抛 [SecurityException]。
     *
     * @return 授权表判定使用的规范化路径 (登记与匹配都按它比较)
     */
    fun ensureActivatedFor(path: String, usage: DirUsage): String {
        val effectivePath = canonicalPath(path)
        if (!isAppPrivatePath(effectivePath)) {
            synchronized(lock) {
                activateFirstAvailable(effectivePath, usage)
            }
        }
        return effectivePath
    }

    /**
     * 锁内调用：逐个尝试 [usage] 用途下覆盖 [path] 的授权条目, 激活成功即返回; 该用途下无登记时抛
     * [SecurityException]。
     *
     * 同一用途内可能有多条命中 (父目录与子目录各选定过一次): 命中条目已失效时后面的可用条目仍要放行,
     * 全部失败才报错 (报第一条的失败原因)。
     *
     * 每次访问都重新激活, 不把进程内旧激活结果当作当前授权。
     */
    private fun activateFirstAvailable(path: String, usage: DirUsage) {
        val matchedUris = matchingUris(path, usage)
        if (matchedUris.isEmpty()) {
            AppLog.put("目录未按用途登记: $path ($usage)")
            throw SecurityException("没有访问权限: $path")
        }
        var firstFailure: Exception? = null
        for (uri in matchedUris) {
            val result = activateDirectoryPermissions(listOf(uri))
            if (result.ok && uri in result.activatedUris) return
            if (result.code == NO_PERSIST_CAPABILITY) return
            val failure = activationFailure(uri, result)
            if (firstFailure == null) firstFailure = failure
        }
        firstFailure?.let { throw it }
    }

    /**
     * 归一为真实路径 (realpath): 解析符号链接与 `..`, 解析不出的路径段按原样保留。
     *
     * 已存在的路径与 [io.legado.app.utils.File.absolutePath] 同值 (okio canonicalize → realpath);
     * 目标不存在 (realpath 失败) 时上溯到最近的已存在祖先做同样归一, 再拼回剩余路径段 —— 登记的是
     * 用户选定的既有目录, 被访问的可能是已删除的文件, 任一侧退化成原样字符串都会让两侧结果不可比:
     * 沙盒回退路径 (napi 未注入时的相对路径) 与容器内路径会被判成互不匹配, 即误拦。
     */
    private fun canonicalPath(path: String): String {
        val normalized = path.toPath(normalize = true).toString()
        var head = normalized
        var remainder = ""
        while (true) {
            val real = runCatching { FileSystem.SYSTEM.canonicalize(head.toPath()) }.getOrNull()
            if (real != null) return real.toString() + remainder
            val cut = head.lastIndexOf('/')
            if (cut <= 0) return normalized
            remainder = head.substring(cut) + remainder
            head = head.substring(0, cut)
        }
    }

    /**
     * 应用私有目录 (本应用沙盒) 的根: 落在其中的路径无目录授权也可访问。
     *
     * 鸿蒙把同一模块的应用文件都放在该模块的沙盒目录下 (files/cache/temp/preferences 等为同级目录,
     * 见官方《应用沙箱目录》), 故根取 filesDir 与 cacheDir 的公共父目录 —— 与 iOS 以容器 home 判定的
     * 口径一致。两目录来源不同 (父目录不覆盖 cacheDir) 时退回两者自身, 不把范围扩到判不出归属的路径。
     *
     * 应用级沙盒根 (el2/base 下应用级目录、database、distributedfiles、安装包 el1/bundle) 目前没有经
     * 本表访问的入口; 新增入口时把根加进本函数, 不得默认直通。
     */
    private fun privateRoots(): List<String> {
        val appDirs = AppFilesDirs.get()
        val filesDir = canonicalPath(appDirs.filesDir)
        val cacheDir = canonicalPath(appDirs.cacheDir)
        val sandboxDir = filesDir.substringBeforeLast('/', "")
        return if (sandboxDir.isNotBlank() && underRoot(sandboxDir, cacheDir)) {
            listOf(sandboxDir)
        } else {
            listOf(filesDir, cacheDir)
        }
    }

    /** [path] 是否落在应用私有目录内 (这类路径无目录授权也可访问)。 */
    private fun isAppPrivatePath(path: String): Boolean =
        privateRoots().any { underRoot(it, path) }

    /**
     * [path] 是否等于 [root] 或其子路径 (按路径段前缀判; [root] 为空时不匹配)。
     *
     * 两参数都必须是 [canonicalPath] 归一后的结果。
     */
    private fun underRoot(root: String, path: String): Boolean {
        if (root.isBlank()) return false
        val normalizedRoot = root.trimEnd('/')
        return path == normalizedRoot || path.startsWith("$normalizedRoot/")
    }

    /** 锁内调用：激活失败的如实原因 (逐条记日志, 由调用方决定是否上报)。 */
    private fun activationFailure(uri: String, result: ActivatePermissionsResult): Exception {
        val detail = result.policyErrors.firstOrNull { it.uri == uri }
        val policyCode = detail?.code ?: 0
        val message = "目录授权激活失败 (errCode=${result.code} policyCode=$policyCode)" +
            (detail?.message ?: result.message)?.let { " $it" }.orEmpty()
        AppLog.put("$message uri=$uri")
        return if (result.code == 201 || detail != null) {
            SecurityException(message)
        } else {
            IOException(message)
        }
    }

    /** 锁内调用: [path] 在 [usage] 用途下所属的全部授权 uri (路径段前缀匹配, 按登记顺序); 未登记为空。 */
    private fun matchingUris(path: String, usage: DirUsage): List<String> =
        storedPolicies()[usage].orEmpty().entries
            .filter { (_, dirPath) -> underRoot(dirPath, path) }
            .map { it.key }

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
