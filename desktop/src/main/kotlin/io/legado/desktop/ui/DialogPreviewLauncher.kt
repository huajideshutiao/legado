package io.legado.desktop.ui

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.data.snapshotDatabaseTo
import io.legado.app.ui.debug.DialogPreviewLaunchers
import io.legado.desktop.help.launchRestartProcess
import io.legado.desktop.startupArgs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "dialog-preview"

/**
 * 进入预览页的启动参数前缀: `--legado-dialog-preview=<调试数据根绝对路径>`。
 *
 * 数据根经**启动参数**传递而不是系统属性: 系统属性不跨进程继承, 重启出的新进程读不到,
 * 会静默落回正式数据根 (预览页里的改动就真的写进正式数据了)。
 */
private const val PREVIEW_ARG_PREFIX = "--legado-dialog-preview="

/** 调试数据根目录名 (与真实数据根同级, 不混入正式数据目录内部)。 */
private const val PREVIEW_ROOT_NAME = "dialog-preview"

/** 参数里请求的预览数据根 (无参数或目录名不对返回 null)。 */
fun dialogPreviewRootArg(args: Array<String> = startupArgs): File? {
    val value = args.firstOrNull { it.startsWith(PREVIEW_ARG_PREFIX) }
        ?.substring(PREVIEW_ARG_PREFIX.length)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val dir = File(value)
    return dir.takeIf { it.name == PREVIEW_ROOT_NAME }
}

/** 参数里请求且**已生效**的预览数据根 (未请求、目录不存在或已被别的来源覆盖时为 null)。 */
private var appliedPreviewRoot: File? = null

/**
 * 把启动参数里的预览数据根落到系统属性 (`legado.portable.root`)。
 *
 * 必须在 `initDesktopRuntimeEnvironment()` **之前**调用: 数据根在进程内只解析一次, 晚一步就
 * 已经指向正式数据目录了。
 */
fun applyDialogPreviewRoot(args: Array<String>) {
    val dir = dialogPreviewRootArg(args) ?: return
    if (!dir.isDirectory) {
        AppLog.put("弹窗预览数据根不存在, 已忽略: ${dir.absolutePath}", tag = TAG)
        return
    }
    System.setProperty("legado.portable.root", dir.absolutePath)
    appliedPreviewRoot = dir
    AppLog.put("弹窗预览模式, 数据根 = ${dir.absolutePath}", tag = TAG)
}

/**
 * 本次启动是否真的落在预览数据根上 (决定初始路由)。
 *
 * 判据不是"参数存在", 而是"参数请求的目录 == 当前生效的数据根": 数据根在进程内只解析一次,
 * 任何一步改动都会让"以为在副本上、实际写正式数据"成真, 故不相等一律当作普通启动。
 */
fun isDialogPreviewLaunch(): Boolean {
    val requested = appliedPreviewRoot ?: return false
    val effective = File(desktopAppRootDir()).absolutePath
    val matched = effective == requested.absolutePath
    if (!matched) {
        AppLog.put(
            "弹窗预览未生效: 请求数据根 ${requested.absolutePath} 与生效数据根 $effective 不一致, 按普通启动处理",
            tag = TAG,
        )
    }
    return matched
}

/**
 * 弹窗预览入口 (桌面端注入到共享层的 [DialogPreviewLaunchers])。
 *
 * # 为什么必须换数据根
 * 预览页会把每个弹窗真正打开, 其中字典规则/TXT 目录规则/校验设置等弹窗的保存动作由弹窗自身
 * 直接写库或写偏好 (调用方拦不住)。要保证"看到真实内容"且"不真实操作数据", 只能让整个进程
 * 落到一份副本上, 而不是逐弹窗加只读特判。
 *
 * # 流程
 * 1. 确认 (说明会重启 + 每次都用当前数据重建副本);
 * 2. 后台线程把真实数据根重建一份副本到 `{真实数据根}/dialog-preview/`
 *    (库用 `VACUUM INTO` 一致性快照, `files/` 逐文件复制; 正式数据只被读);
 * 3. 以调试数据根为启动参数重启进程 (进程内数据根只解析一次, 不重启无法切换)。
 *
 * 配置项 (java.util.prefs, 存在系统注册表) 与数据根无关, 预览里改配置会改到正式配置, 故入口
 * 文案如实说明, 不隐瞒。
 */
fun installDialogPreviewLauncher() {
    // 进入入口: 只在开发运行期可见 (run 任务注入 -Dlegado.debug=true, 打包产物不带, 与 Main.kt 的调试判定同源)
    val devRun = System.getProperty("legado.debug")?.toBoolean() == true
    // 退出入口: 按"本次启动是否真落在预览数据根上"判定。进入预览靠重启进程, 重启命令只带 classpath
    // 与启动参数、不带 -D 系统属性, 新进程里 legado.debug 恒为假 —— 退出若也绑在它上面, 预览页的
    // 返回箭头就没有注册 (用户表现为回不去, 只能关窗口)
    val inPreview = isDialogPreviewLaunch()
    if (!devRun && !inPreview) return
    val enter: (() -> Unit)? = if (devRun) ::requestEnterPreview else null
    val exit: (() -> Unit)? = if (inPreview) ::exitDialogPreview else null
    DialogPreviewLaunchers.register(enter = enter, exit = exit)
}

/** 进入预览前的确认: 说明会重启、每次都用当前数据重建副本。 */
private fun requestEnterPreview() {
    DesktopDialogs.show(
        DesktopDialogRequest.Confirm(
            title = "弹窗预览",
            message = "将用当前数据重建一份副本到独立目录, 并重启进入预览页。\n\n" +
                "副本只放在那个目录里, 正式数据不被改动; 每次进入都会重建, " +
                "副本里上次的改动会被覆盖。\n" +
                "但主题模式/字号等界面设置项存在系统注册表里, 与数据目录无关, " +
                "在预览页里改动会同步到正式设置。",
            okText = "重建副本并进入",
            noText = "取消",
            onOk = { startDialogPreview() },
            onNo = {},
        )
    )
}

/** 准备调试数据根并以它重启进程进入预览页。 */
private fun startDialogPreview() {
    val realRoot = File(desktopAppRootDir())
    val previewRoot = File(realRoot, PREVIEW_ROOT_NAME)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val failure = runCatching { preparePreviewRoot(realRoot, previewRoot) }.exceptionOrNull()
        withContext(Dispatchers.Main) {
            if (failure != null) {
                // 副本没建成时不能重启: 新进程会以空数据根启动, 看到的不是用户数据的副本
                AppLog.put("准备弹窗预览数据副本失败", failure, tag = TAG)
                io.legado.app.help.toast.Toasters.get().toast("预览数据准备失败, 未进入预览")
                return@withContext
            }
            val previousArgs = startupArgs
            startupArgs = previousArgs.filterNot { it.startsWith(PREVIEW_ARG_PREFIX) }
                .plus("$PREVIEW_ARG_PREFIX${previewRoot.absolutePath}")
                .toTypedArray()
            if (launchRestartProcess()) {
                kotlin.system.exitProcess(0)
            } else {
                startupArgs = previousArgs
                io.legado.app.help.toast.Toasters.get().toast("重启失败, 未进入预览")
            }
        }
    }
}

/** 退出预览: 去掉启动参数后重启回正式数据根 (预览进程的数据根无法就地切换)。 */
private fun exitDialogPreview() {
    val previousArgs = startupArgs
    startupArgs = previousArgs.filterNot { it.startsWith(PREVIEW_ARG_PREFIX) }.toTypedArray()
    if (launchRestartProcess()) {
        kotlin.system.exitProcess(0)
    } else {
        startupArgs = previousArgs
        io.legado.app.help.toast.Toasters.get().toast("重启失败, 请手动关闭窗口")
    }
}

/**
 * 重建预览数据副本 (每次进入都重建, 副本里上次的改动不做保留)。
 *
 * 复制的项都是"看弹窗内容"需要的真实数据: 数据库 (书源/书籍/目录/规则/分组/书签/服务器) 与
 * 应用私有文件目录 (主题配置/背景/字体等)。书正文缓存与图片缓存不进副本 (预览不需要且体积大)。
 *
 * 数据库用 `VACUUM INTO` 取一致性快照 —— 正式库此刻正被本进程打开, 直接拷文件会得到撕裂的副本。
 */
private fun preparePreviewRoot(realRoot: File, previewRoot: File) {
    previewRoot.deleteRecursively()
    previewRoot.mkdirs()

    // 1. 数据库: 一致性快照 (VACUUM INTO 要求目标文件不存在)
    val dbName = AppDatabase.DATABASE_NAME
    val sourceDb = File(realRoot, dbName)
    if (!sourceDb.isFile) error("真实数据库不存在: ${sourceDb.absolutePath}")
    snapshotDatabaseTo(sourceDb.absolutePath, File(previewRoot, dbName).absolutePath)

    // 2. 应用私有文件目录 (主题配置/背景/字体等都在 files/ 下)
    copyDirectory(File(realRoot, "files"), File(previewRoot, "files"))
}

private fun copyDirectory(source: File, target: File) {
    if (!source.isDirectory) return
    source.walkTopDown().forEach { file ->
        val destination = File(target, file.relativeTo(source).path)
        if (file.isDirectory) {
            destination.mkdirs()
        } else if (file.isFile) {
            runCatching {
                Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { AppLog.put("预览复制文件失败: ${file.absolutePath}", it, tag = TAG) }
        }
    }
}
